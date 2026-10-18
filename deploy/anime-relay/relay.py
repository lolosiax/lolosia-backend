#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Bangumi 异步取数中继（部署在境外节点，供墙内后端调用）。

设计要点——**这不是反代，也不是隧道**：只把「触发」与「出站」在时间上错开，
入站请求与出站请求没有一一对应关系，全程无长连接、无端口转发。

    1. 墙内后端 `POST /anime/bangumi/notify` 只登记一个任务，**立刻返回**（此刻不出站）；
    2. 本进程隔 RELAY_DELAY_SECONDS（默认 180s，另加 0..JITTER 抖动）后才真正出站拉取目标 URL；
    3. 后端过几分钟再 `GET /anime/bangumi/result` 取结果。

`notify` / `result` 的响应体走**自定义请求通道**（`application/vnd.lolosia.xhr.data`：msgpack + gzip，
前 10 字节用随机数盖头）——与前端基座 / 后端一致，避免明文 JSON 特征；`/healthz` 例外，保持明文便于探活。

依赖 `msgpack`（`pip install msgpack` 或 `apt install python3-msgpack`）。其余配置全部走环境变量，见同目录 relay.env 示例。

@author 洛洛希雅Lolosia
"""
from __future__ import annotations

import gzip
import hmac
import json
import os
import random
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import msgpack

TOKEN = os.environ.get("RELAY_TOKEN", "")
HOST = os.environ.get("RELAY_HOST", "0.0.0.0")
PORT = int(os.environ.get("RELAY_PORT", "8790"))
DELAY = int(os.environ.get("RELAY_DELAY_SECONDS", "180"))
JITTER = int(os.environ.get("RELAY_JITTER_SECONDS", "120"))
TARGET = os.environ.get("RELAY_TARGET_URL", "https://api.bgm.tv/calendar")
UA = os.environ.get("RELAY_UA", "lolosia-anime/1.0 (+http://lolosia.moe)")
TIMEOUT = int(os.environ.get("RELAY_TIMEOUT_SECONDS", "30"))
STATE_FILE = os.environ.get("RELAY_STATE_FILE", "/srv/anime-relay/state.json")
# 封面镜像：目录拿到日历后，后台**逐个、带间隔**地把封面图也拉下来（墙内后端连不到 Bangumi 图床）。
COVER_DIR = os.environ.get("RELAY_COVER_DIR", "/srv/anime-relay/covers")
COVER_DELAY = int(os.environ.get("RELAY_COVER_DELAY_SECONDS", "2"))
COVER_JITTER = int(os.environ.get("RELAY_COVER_JITTER_SECONDS", "2"))
COVER_MAX_TRIES = int(os.environ.get("RELAY_COVER_MAX_TRIES", "3"))
MAX_BODY = 8 * 1024 * 1024

# 与前端基座/后端一致的「自定义请求通道」：msgpack + gzip，前 10 字节用随机数盖掉 gzip 头
# （解码端只需把前 4 字节改回 gzip 魔数即可还原）。见 doc/anime-timetable-design.md §4.4。
XHR_MIME = "application/vnd.lolosia.xhr.data"
GZIP_MAGIC = b"\x1f\x8b\x08\x00"


def _xhr_encode(obj: object) -> bytes:
    """JSON 对象 → msgpack → gzip → 10 字节随机盖头（即通道的响应体编码）。"""
    packed = msgpack.packb(obj, use_bin_type=True)
    gz = bytearray(gzip.compress(packed))
    gz[0:10] = os.urandom(10)
    return bytes(gz)


def _xhr_decode(data: bytes) -> object:
    """通道请求体还原：改回 gzip 魔数 → 解压 → msgpack 解包。"""
    buf = bytearray(data)
    if len(buf) < 4:
        return None
    buf[0:4] = GZIP_MAGIC
    return msgpack.unpackb(gzip.decompress(bytes(buf)), raw=False)

# job: 待执行任务（含 fire_at）；last: 最近一次结果（ready / error），持久化后可在重启后继续供数
# covers: 日历里各番的封面 id -> URL（后台据此逐个下载）；cover_tries: 失败重试计数（超过上限即放弃）
_lock = threading.Lock()
_state: dict = {"job": None, "last": None, "covers": {}, "cover_tries": {}}


def _load_state() -> None:
    """启动时把上次成功的结果与封面清单读回来，避免进程重启后一段时间取不到数。"""
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return
        if isinstance(data.get("last"), dict):
            _state["last"] = data["last"]
        if isinstance(data.get("covers"), dict):
            _state["covers"] = data["covers"]
    except Exception:
        pass


def _save_state() -> None:
    try:
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"last": _state["last"], "covers": _state["covers"]}, f, ensure_ascii=False)
        os.replace(tmp, STATE_FILE)
    except Exception:
        pass  # 落盘失败不影响服务


def _fetch() -> object:
    """真正出站——只在任务到点后被 worker 调用。"""
    req = urllib.request.Request(
        TARGET,
        headers={"User-Agent": UA, "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        raw = resp.read(MAX_BODY)
    return json.loads(raw.decode("utf-8"))


def _collect_covers(data: object) -> dict:
    """从日历数组里抽 `id -> 封面 URL`（large 优先，退回 common/medium/grid）。"""
    covers: dict = {}
    if not isinstance(data, list):
        return covers
    for day in data:
        if not isinstance(day, dict):
            continue
        for item in (day.get("items") or []):
            if not isinstance(item, dict):
                continue
            cid = item.get("id")
            images = item.get("images") or {}
            url = images.get("large") or images.get("common") or images.get("medium") or images.get("grid")
            if cid and url:
                covers[str(cid)] = url
    return covers


def _cover_path(cid: str) -> str:
    return os.path.join(COVER_DIR, cid + ".jpg")


def _download_cover(cid: str, url: str) -> None:
    """下载单张封面并原子落盘（先写 .tmp 再 rename）。"""
    os.makedirs(COVER_DIR, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://bgm.tv/"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        body = resp.read(MAX_BODY)
    tmp = _cover_path(cid) + ".tmp"
    with open(tmp, "wb") as f:
        f.write(body)
    os.replace(tmp, _cover_path(cid))


def _cover_worker() -> None:
    """后台**逐个**下载封面；每张之间睡 COVER_DELAY + 抖动，避免短时间内猛拉图床。"""
    while True:
        time.sleep(1)
        with _lock:
            pick = None
            for cid, c_url in _state["covers"].items():
                if os.path.exists(_cover_path(cid)):
                    continue
                if _state["cover_tries"].get(cid, 0) >= COVER_MAX_TRIES:
                    continue
                pick = (cid, c_url)
                break
        if pick is None:
            continue
        cid, c_url = pick
        try:
            _download_cover(cid, c_url)
            with _lock:
                _state["cover_tries"].pop(cid, None)
        except Exception as exc:  # noqa: BLE001 —— 记一次失败，超过上限即放弃该张
            with _lock:
                _state["cover_tries"][cid] = _state["cover_tries"].get(cid, 0) + 1
            print("cover %s 下载失败：%s: %s" % (cid, type(exc).__name__, exc), flush=True)
        time.sleep(COVER_DELAY + random.uniform(0, COVER_JITTER))


def _worker() -> None:
    while True:
        time.sleep(1)
        with _lock:
            job = _state["job"]
            if job is None or time.time() < job["fire_at"]:
                continue
            _state["job"] = None
        now = int(time.time())
        try:
            data = _fetch()
            with _lock:
                _state["last"] = {
                    "status": "ready",
                    "jobId": job["id"],
                    "fetchedAt": now,
                    "target": TARGET,
                    "count": len(data) if isinstance(data, list) else None,
                    "data": data,
                }
                # 顺带把这次的封面清单并入待下载集合，交给 _cover_worker 慢慢拉
                _state["covers"].update(_collect_covers(data))
            _save_state()
        except Exception as exc:  # noqa: BLE001 —— 任何出站异常都记为一次失败结果
            with _lock:
                _state["last"] = {
                    "status": "error",
                    "jobId": job["id"],
                    "fetchedAt": now,
                    "target": TARGET,
                    "error": f"{type(exc).__name__}: {exc}",
                }


class Handler(BaseHTTPRequestHandler):
    server_version = "anime-relay/1.0"

    def log_message(self, fmt, *args):  # 交给 journald
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)

    def _json(self, code: int, obj: object) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _xhr(self, code: int, obj: object) -> None:
        """走自定义通道输出：msgpack + gzip + 10 字节随机头，Content-Type 换成该 mime。"""
        body = _xhr_encode(obj)
        self.send_response(code)
        self.send_header("Content-Type", XHR_MIME)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ok(self, data: object) -> None:
        """成功：HTTP 200 + 项目统一信封 `{code, msg, data}`。"""
        self._json(200, {"code": 200, "msg": "success", "data": data})

    def _ok_xhr(self, data: object) -> None:
        """成功（走通道）：信封与 [_ok] 相同，只是编码换成 msgpack+gzip。"""
        self._xhr(200, {"code": 200, "msg": "success", "data": data})

    def _err(self, http: int, code: int, msg: str) -> None:
        """失败：HTTP 状态码与信封 code 一致（与后端 `ApiResult` 约定相同）。"""
        self._json(http, {"code": code, "msg": msg, "data": None})

    def _err_xhr(self, http: int, code: int, msg: str) -> None:
        """失败（走通道）：信封与 [_err] 相同，编码换成 msgpack+gzip。"""
        self._xhr(http, {"code": code, "msg": msg, "data": None})

    def _authed(self, query: dict) -> bool:
        token = self.headers.get("X-Relay-Token") or (query.get("token", [""])[0])
        return bool(TOKEN) and hmac.compare_digest(token, TOKEN)

    def do_GET(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        query = parse_qs(url.query)

        if url.path == "/healthz":
            with _lock:
                last = _state["last"]
                return self._ok({
                    "ok": True,
                    "pending": _state["job"] is not None,
                    "last": None if not last else {"status": last["status"], "fetchedAt": last["fetchedAt"]},
                })

        if url.path == "/anime/bangumi/result":
            if not self._authed(query):
                return self._err_xhr(401, 401, "unauthorized")
            with _lock:
                if _state["job"] is not None or _state["last"] is None:
                    return self._ok_xhr({"status": "pending"})
                last = _state["last"]
            data = {
                "status": last["status"],
                "jobId": last.get("jobId"),
                "fetchedAt": last.get("fetchedAt"),
                "target": TARGET,
            }
            if last["status"] == "ready":
                data["count"] = last.get("count")
                data["calendar"] = last.get("data")
            else:
                data["error"] = last.get("error")
            return self._ok_xhr(data)

        if url.path == "/anime/bangumi/covers/status":
            if not self._authed(query):
                return self._err_xhr(401, 401, "unauthorized")
            with _lock:
                total = len(_state["covers"])
                ids = sorted(cid for cid in _state["covers"] if os.path.exists(_cover_path(cid)))
            return self._ok_xhr({"total": total, "ready": len(ids), "ids": ids})

        if url.path.startswith("/anime/bangumi/covers/"):
            if not self._authed(query):
                return self._err(401, 401, "unauthorized")
            cid = url.path.rsplit("/", 1)[-1]
            if cid.endswith(".jpg"):
                cid = cid[:-4]
            if not cid.isdigit() or not os.path.exists(_cover_path(cid)):
                return self._err(404, 404, "cover not ready")
            with open(_cover_path(cid), "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        return self._err(404, 404, "not found")

    def do_POST(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        query = parse_qs(url.query)
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                raw = self.rfile.read(min(length, 4096))
                # 通道请求体（notify 无业务载荷），解出来即丢弃；解不开也不影响登记任务
                if self.headers.get("Content-Type") == XHR_MIME:
                    try:
                        _xhr_decode(raw)
                    except Exception:
                        pass
        except Exception:
            pass

        if url.path != "/anime/bangumi/notify":
            return self._err(404, 404, "not found")
        if not self._authed(query):
            return self._err_xhr(401, 401, "unauthorized")

        # 联调用：?delay=<秒> 覆盖默认延迟（只影响等待、不改变行为）
        delay = DELAY
        try:
            if query.get("delay"):
                delay = max(0, min(3600, int(query["delay"][0])))
        except Exception:
            pass

        with _lock:
            if _state["job"] is not None:
                job = _state["job"]
                return self._ok_xhr({
                    "jobId": job["id"],
                    "etaSeconds": int(max(0, job["fire_at"] - time.time())),
                    "deduped": True,
                })
            job = {
                "id": uuid.uuid4().hex[:12],
                "fire_at": time.time() + delay + random.uniform(0, JITTER),
            }
            _state["job"] = job
        return self._ok_xhr({"jobId": job["id"], "etaSeconds": int(job["fire_at"] - time.time())})


def main() -> None:
    if not TOKEN:
        raise SystemExit("RELAY_TOKEN 未设置：请通过环境变量提供共享令牌")
    _load_state()
    threading.Thread(target=_worker, daemon=True).start()
    threading.Thread(target=_cover_worker, daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        f"anime-relay listening on {HOST}:{PORT}, delay={DELAY}s + jitter 0..{JITTER}s, "
        f"target={TARGET}, covers={COVER_DIR}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
