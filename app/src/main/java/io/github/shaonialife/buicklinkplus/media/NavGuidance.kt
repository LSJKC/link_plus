package io.github.shaonialife.buicklinkplus.media

import android.app.Notification

/** 从高德通知中的转向关键词提取导航指引，无法识别时不发布 */
internal object NavGuidance {
    enum class Direction {
        LEFT,
        RIGHT,
        SLIGHT_LEFT,
        SLIGHT_RIGHT,
        STRAIGHT,
        U_TURN,
        ARRIVE,
    }

    /** 单条指引：动作 + 距离 + 道路 + 剩余行程 + 原始文案（展开卡/行程组件用） */
    data class Step(
        val direction: Direction,
        val distance: String?,
        val road: String?,
        val remaining: String?,
        val text: String,
    )

    @Volatile
    var current: Step? = null
        private set

    /** 当前指引对应的通知名（旧移除回调不能清掉后来发布的指引） */
    @Volatile
    private var currentKey: String? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 导航应用包名，高德沿用项目登记，百度来源为车联网开放文档和应用商店 */
    private val PACKAGES =
        setOf(
            "com.autonavi.amapauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.baidu.naviauto",
            "com.baidu.BaiduMap.auto",
        )

    fun handles(packageName: String): Boolean = packageName in PACKAGES

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知到达/更新：取可见文本解析后发布（无明确动作则清空） */
    fun fromNotification(
        key: String,
        notification: Notification,
    ) {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        publish(key, parse(title, listOfNotNull(text, bigText).joinToString(" ").ifBlank { null }))
    }

    /** 仅清掉自己发布的指引，随后更新的通知不受旧移除影响 */
    fun clear(key: String) {
        if (key == currentKey) publish(null, null)
    }

    fun clearAll() = publish(null, null)

    private fun publish(
        key: String?,
        step: Step?,
    ) {
        if (current == step && currentKey == key) return
        current = step
        currentKey = key
        listeners.forEach { runCatching { it() } }
    }

    /** 按明确的转向、直行、掉头和到达关键词解析指引，未匹配返回 null */
    fun parse(
        title: String?,
        text: String?,
    ): Step? {
        val joined = listOfNotNull(title, text).joinToString(" ").trim()
        if (joined.isEmpty()) return null
        val direction =
            when {
                "掉头" in joined -> Direction.U_TURN
                "右后转" in joined || "稍向右" in joined || "靠右" in joined -> Direction.SLIGHT_RIGHT
                "左后转" in joined || "稍向左" in joined || "靠左" in joined -> Direction.SLIGHT_LEFT
                "右转" in joined -> Direction.RIGHT
                "左转" in joined -> Direction.LEFT
                "直行" in joined -> Direction.STRAIGHT
                "到达" in joined -> Direction.ARRIVE
                else -> return null
            }
        val distance =
            DISTANCE.find(joined)?.let { match ->
                val value = match.groupValues[1]
                val unit = match.groupValues[2]
                "$value$unit"
            }
        val road =
            ROAD
                .find(joined)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val remaining =
            if ("剩余" in joined || "预计" in joined) {
                val dist = REMAIN_DIST.find(joined)?.groupValues?.get(1)
                val minutes = REMAIN_MIN.find(joined)?.groupValues?.get(1)
                when {
                    dist != null && minutes != null -> "$dist · $minutes 分钟"
                    dist != null -> dist
                    minutes != null -> "$minutes 分钟"
                    else -> null
                }
            } else {
                null
            }
        return Step(direction, distance, road, remaining, joined)
    }

    private val DISTANCE = Regex("""(\d+(?:\.\d+)?)\s*(公里|千米|米)""")

    private val ROAD = Regex("""进入([^，,。；;]+)""")

    private val REMAIN_DIST = Regex("""剩余\s*([\d.]+\s*(?:公里|千米|米))""")

    private val REMAIN_MIN = Regex("""(?:预计|约|还需)\s*(\d+)\s*分钟""")
}
