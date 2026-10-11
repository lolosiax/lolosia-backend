package moe.lolosia.web.service.media.worker

import moe.lolosia.web.util.llm.ChatUsage

/**
 * 外发分析（视觉打标）的提供方抽象 —— 对齐 `doc/vision-autotag-design.md` §7.1。
 *
 * `MediaAnalyzeService` 只依赖本接口：谁被装配由配置决定（`DeepSeek` 云 API 或 `noop`）。
 * 一次调用接收**一批**图片（本地已降采样），按序返回每张的结构化判定 —— 批量是省 token 的
 * 主要杠杆（摊薄系统提示，见设计稿 §3）。
 *
 * 实现不负责闸门与落库：那些都在服务层统一做。
 *
 * @author 洛洛希雅Lolosia
 */
interface TaggingProvider {

    /** provider id，落 `media_asset.analyze_provider`；如 `deepseek-flash@vision`。 */
    val id: String

    /** 单请求最多接受多少张（服务层据此切批）；`0` 表示不限。 */
    val maxBatch: Int

    /** 分析一批图片；返回的 [Output.results] 与 [items] 按序对应（数量不足时由服务层补空）。 */
    suspend fun tag(items: List<Item>): Output

    /**
     * 单张图的输入：**本地降采样后**的字节 + MIME（省上行、避开请求体限制），外加让模型
     * 「看到以前的分析结果」的上下文（已有标签 / 已有描述 / 所属文件夹备注），由提示词层渲染。
     */
    class Item(
        val path: String,
        val bytes: ByteArray,
        val mime: String,
        /** 该图当前生效的标签（`namespace:name`），供模型沿用 / 增补。 */
        val tags: List<String> = emptyList(),
        /** 该图已有的描述（`media_asset.caption`），供模型参考改写。 */
        val caption: String? = null,
        /** 该图所属文件夹的备注（`folder_remark`），只作上下文，不该写进描述。 */
        val folderRemark: String? = null,
    )

    /**
     * 单张图的判定。
     *
     * 字段为 `null` 表示模型没给出该维度（或该维度未开启）——服务层据此决定要不要落标签。
     */
    class Result(
        /** 人数：`0` / `1` / `2` / `3` / `many`；判不出为 `null`。 */
        val count: String? = null,
        /** 是否有效内容（废片 / 纯色 / 截图 / 遮挡为否）。 */
        val valid: Boolean? = null,
        /** 题材：`vrchat` / `doll` / `misc`。 */
        val subject: String? = null,
        /** 一句话描述（只讲画面里发生了什么，不评价、不推测身份）。 */
        val caption: String? = null,
        /** 角色视觉属性（如 `粉发`、`双马尾`、`和服`）。 */
        val attributes: List<String> = emptyList(),
    )

    /** 一次分析的产物：逐图判定 + 本次 token 用量（来自响应 `usage`）。 */
    class Output(
        val results: List<Result>,
        val usage: ChatUsage? = null,
    )
}
