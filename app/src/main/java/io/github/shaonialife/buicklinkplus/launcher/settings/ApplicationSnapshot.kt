package io.github.shaonialife.buicklinkplus.launcher.settings

/**
 * 应用列表一次分组的数据快照，调用方后续修改选择不会改变该结果
 */
data class ApplicationSnapshot<T>(
    val desktop: List<T>,
    val others: List<T>,
)

/**
 * 保持已排序列表的顺序，以一次遍历生成两个独立快照
 */
fun <T> applicationSnapshot(
    apps: List<T>,
    selected: Set<String>,
    key: (T) -> String,
): ApplicationSnapshot<T> {
    val desktop = ArrayList<T>()
    val others = ArrayList<T>()
    apps.forEach { if (key(it) in selected) desktop += it else others += it }
    return ApplicationSnapshot(desktop.toList(), others.toList())
}
