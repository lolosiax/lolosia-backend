package moe.lolosia.web.service.media.worker

/**
 * 外发分析标签的命名空间与名字常量（`doc/vision-autotag-design.md` §7.2）。
 *
 * 构图 / 题材 / 有效性复用已有的 `scene` / `series` / `meta`；物件 / 动作复用固定枚举里已有的
 * `object` / `action`；角色属性与场合（事件）是本方案**新增**的 `attribute` / `event` 两个命名空间。
 * 命名空间在库里是自由字符串，所以这里只是约定 + 集中书写，不需要改表结构。
 *
 * @author 洛洛希雅Lolosia
 */

/** 构图 / 人数。 */
const val NAMESPACE_SCENE: String = "scene"

/** 题材（`series:VRChat` / `series:娃照` 已由目录规则使用）。 */
const val NAMESPACE_SERIES: String = "series"

/** 角色视觉属性（本方案新增）。 */
const val NAMESPACE_ATTRIBUTE: String = "attribute"

/** 画面里的显著物件 / 道具 / 场景元素（复用固定枚举里已有的 `object`）。 */
const val NAMESPACE_OBJECT: String = "object"

/** 场合 / 活动（如「生日会」）：主要依据路径与文件夹名推断，也可从画面望文生义（本方案新增）。 */
const val NAMESPACE_EVENT: String = "event"

/** 画面里正在做的事 / 动作（复用固定枚举里已有的 `action`）。 */
const val NAMESPACE_ACTION: String = "action"

/** 单人 → 写真。 */
const val SCENE_SINGLE: String = "单人写真"

/** 双人。 */
const val SCENE_DOUBLE: String = "双人"

/** 三人。 */
const val SCENE_TRIPLE: String = "三人"

/** 多人（≥ 4）。 */
const val SCENE_MANY: String = "多人"

/** 合影（≥ 2 的统一叫法，检索入口比逐个数人数更常用）。 */
const val SCENE_GROUP: String = "合影"

/** 有效内容。 */
const val META_VALID: String = "有效"

/** 无效内容（废片 / 纯色 / 截图 / 遮挡 / 过曝）。 */
const val META_INVALID: String = "无效"

/** 题材：VRChat（与目录规则 `series:VRChat` 同名）。 */
const val SERIES_VRCHAT: String = "VRChat"

/** 题材：娃照（与目录规则 `series:娃照` 同名）。 */
const val SERIES_DOLL: String = "娃照"

/** 题材：杂项。 */
const val SERIES_MISC: String = "杂项"

/**
 * 把模型的人数判定映射成 `scene` 标签名；认不出返回 `null`。
 *
 * `many` 同时给出「多人」与「合影」；`2` / `3` 同时给出「双人 / 三人」与「合影」——
 * 前者用于精确检索，后者是常用的粗筛入口。
 */
fun sceneTagsOf(count: String?): List<String> = when (count?.trim()?.lowercase()) {
    "1", "single", "一人" -> listOf(SCENE_SINGLE)
    "2", "two", "两人" -> listOf(SCENE_DOUBLE, SCENE_GROUP)
    "3", "three", "三人" -> listOf(SCENE_TRIPLE, SCENE_GROUP)
    "many", "multi", "多人" -> listOf(SCENE_MANY, SCENE_GROUP)
    else -> emptyList()
}

/** 把模型的题材判定映射成 `series` 标签名；认不出返回 `null`。 */
fun seriesTagOf(subject: String?): String? = when (subject?.trim()?.lowercase()) {
    "vrchat", "vrc" -> SERIES_VRCHAT
    "doll", "娃照", "娃娃" -> SERIES_DOLL
    "misc", "other", "杂项" -> SERIES_MISC
    else -> null
}
