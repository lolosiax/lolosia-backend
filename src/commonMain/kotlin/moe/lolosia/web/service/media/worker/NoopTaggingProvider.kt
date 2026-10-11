package moe.lolosia.web.service.media.worker

/**
 * 空实现：不开外发分析（或 provider 配成 `noop`）时装配它。
 *
 * 返回的每条结果都是 `null` 维度的空 [TaggingProvider.Result]，服务层据此把资产标成
 * `skipped` 而不落任何标签 —— 与「本地预筛判废片 / 参考库未就绪」是同一出口，
 * 不阻断打标流水线。
 *
 * @author 洛洛希雅Lolosia
 */
object NoopTaggingProvider : TaggingProvider {

    override val id: String = "noop"

    override val maxBatch: Int = 0

    override suspend fun tag(items: List<TaggingProvider.Item>): TaggingProvider.Output =
        TaggingProvider.Output(items.map { TaggingProvider.Result() })
}
