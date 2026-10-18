#!/usr/bin/env node
/**
 * 服务端 EventBus 的本地 mock（仅开发用）。
 *
 * 复刻真实服务端的三个 SSE 端点与信封约定，方便在没有公网后端 / 没有测试账号时验证
 * Android 后台推送：
 *   POST /api/sse                （头 x-upgrade: event-source）→ {data:<id>, code:200}
 *   POST /api/sse/registry       → {code,msg,data:{message}}
 *   GET  /api/sse?eventSourceId= → text/event-stream（首帧 status + 5 秒心跳 + 定时 anime.release）
 *
 * 另外提供登录 / 用户 / 番组表的假数据，客户端可以完整登录进来：
 *   POST /api/login、POST /api/user/myInfo、GET /api/anime/timetable、POST /api/logout
 *
 * 手动推一条：
 *   curl -X POST http://127.0.0.1:18080/api/mock/publish \
 *        -H 'Content-Type: application/json' \
 *        -d '{"event":"anime.release","data":{"seriesTitle":"测试番","episodeNo":7,"title":"第 7 话"}}'
 *
 * 运行：
 *   node tools/mock-sse-server.mjs [端口]        # 默认 18080
 *   EVENT_INTERVAL_MS=3000 node tools/mock-sse-server.mjs
 *
 * Android 模拟器访问宿主机用 http://10.0.2.2:18080（已在 network_security_config 里放行明文）。
 */

import http from 'node:http'
import { randomUUID } from 'node:crypto'

const port = Number(process.env.PORT || process.argv[2] || 18080)
const autoIntervalMs = Number(process.env.EVENT_INTERVAL_MS || 8000)
const heartbeatMs = 5000

/** eventSourceId → ServerResponse */
const streams = new Map()
let frameId = 0
let episodeNo = 0

const series = [
  { id: 1, title: '列女战纪：女娲石记', groupName: 'LoliHouse' },
  { id: 2, title: '示例番剧：风与海', groupName: 'Nekomoe kissaten' },
]

function log(message) {
  console.log(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${message}`)
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}

function ok(res, data) {
  sendJson(res, 200, { code: 200, msg: 'success', data })
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', (chunk) => (body += chunk))
    req.on('end', () => resolve(body))
  })
}

function writeFrame(res, event, data) {
  frameId += 1
  const text = typeof data === 'string' ? data : JSON.stringify(data)
  res.write(`id: ${frameId}\nevent: ${event}\ndata: ${text}\nretry: 3000\n\n`)
}

function broadcast(event, data) {
  for (const [id, res] of streams) {
    try {
      writeFrame(res, event, data)
    } catch {
      streams.delete(id)
    }
  }
  return streams.size
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`)

  // ---- 握手 / 建流 / 绑定 -------------------------------------------------
  if (req.method === 'POST' && url.pathname === '/api/sse') {
    const id = randomUUID()
    log(`分配连接标识 ${id}（x-upgrade=${req.headers['x-upgrade'] || '-'}）`)
    return sendJson(res, 200, { data: id, code: 200 })
  }

  if (req.method === 'POST' && url.pathname === '/api/sse/registry') {
    const body = await readBody(req)
    log(`绑定登录态：${body || '{}'}`)
    return ok(res, { message: 'success' })
  }

  if (req.method === 'GET' && url.pathname === '/api/sse') {
    const id = url.searchParams.get('eventSourceId')
    if (!id) return sendJson(res, 400, { code: 400, msg: '缺少 eventSourceId', data: null })
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    res.write('id: 0\nevent: status\ndata: "connect successful"\nretry: 3000\n\n')
    streams.set(id, res)
    log(`SSE 已连接 ${id}（当前 ${streams.size} 条）`)
    req.on('close', () => {
      streams.delete(id)
      log(`SSE 已断开 ${id}（当前 ${streams.size} 条）`)
    })
    return
  }

  // ---- 假登录 / 资料 / 番组表 --------------------------------------------
  if (req.method === 'POST' && url.pathname === '/api/login') {
    const body = await readBody(req)
    log(`登录请求：${body}`)
    return ok(res, {
      id: 'mock-user',
      userName: 'mock',
      realName: 'Mock User',
      Authorization: 'mock-token',
    })
  }

  if (req.method === 'POST' && url.pathname === '/api/user/myInfo') {
    return ok(res, {
      id: 'mock-user',
      userName: 'mock',
      realName: 'Mock User',
      roleId: 4,
    })
  }

  if (req.method === 'GET' && url.pathname === '/api/anime/timetable') {
    return ok(
      res,
      series.map((item, index) => ({
        id: item.id,
        title: item.title,
        airWeekday: index + 1,
        status: 'airing',
        followed: true,
      })),
    )
  }

  if (req.method === 'POST' && url.pathname === '/api/logout') {
    return ok(res, { message: 'success' })
  }

  // ---- 手动推送 ------------------------------------------------------------
  if (req.method === 'POST' && url.pathname === '/api/mock/publish') {
    const raw = await readBody(req)
    let payload = {}
    try {
      payload = raw ? JSON.parse(raw) : {}
    } catch {
      return sendJson(res, 400, { code: 400, msg: '请求体不是 JSON', data: null })
    }
    const event = payload.event || 'mock.event'
    const count = broadcast(event, payload.data ?? { message: 'manual push' })
    log(`手动推送 ${event} → ${count} 条连接`)
    return ok(res, { event, connections: count })
  }

  if (req.method === 'GET' && url.pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
    return res.end('ok')
  }

  sendJson(res, 404, { code: 404, msg: `未知路径 ${req.method} ${url.pathname}`, data: null })
})

// 心跳：和真实服务端一致，用 SSE 注释帧让空闲连接保持有字节流动。
setInterval(() => {
  for (const [id, res] of streams) {
    try {
      res.write(': heartbeat\n\n')
    } catch {
      streams.delete(id)
    }
  }
}, heartbeatMs)

// 定时模拟「番组更新」推送；没有客户端连接时什么也不做。
setInterval(() => {
  if (streams.size === 0) return
  episodeNo += 1
  const item = series[(episodeNo - 1) % series.length]
  broadcast('anime.release', {
    seriesId: item.id,
    seriesTitle: item.title,
    groupName: item.groupName,
    title: `[${item.groupName}] ${item.title} - ${String(episodeNo).padStart(2, '0')} [1080p]`,
    episodeNo,
    torrentUrl: '',
    detailUrl: '',
    publishedAt: Date.now(),
  })
  log(`自动推送 anime.release 第 ${episodeNo} 话 → ${streams.size} 条连接`)
}, autoIntervalMs)

server.listen(port, '0.0.0.0', () => {
  log(`mock SSE server 已启动：http://0.0.0.0:${port}`)
  log(`模拟器内请使用：http://10.0.2.2:${port}`)
  log(`自动事件间隔：${autoIntervalMs}ms；手动推送：POST /api/mock/publish`)
})
