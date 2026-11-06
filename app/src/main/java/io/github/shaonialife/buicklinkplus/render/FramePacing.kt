package io.github.shaonialife.buicklinkplus.render

/**
 * 合成出帧按纳秒计时，避免毫秒向上取整降低帧率，静止心跳仍服从接收端上限
 */
internal object FramePacing {
    /**
     * 返回距离下次允许出帧的等待时间，新输入通知不能绕过帧率限制
     */
    fun waitNanos(
        now: Long,
        lastFrame: Long,
        targetFps: Int,
        hasNew: Boolean,
    ): Long {
        val interval = intervalNanos(targetFps, hasNew)
        return (interval - (now - lastFrame)).coerceAtLeast(0L)
    }

    /** 保留出帧时间的小数余量，严重落后时重新计时 */
    fun advance(
        now: Long,
        lastFrame: Long,
        targetFps: Int,
        hasNew: Boolean,
    ): Long {
        val interval = intervalNanos(targetFps, hasNew)
        return if (now - lastFrame >= interval * 2) now else lastFrame + interval
    }

    /**
     * 新内容和静止心跳共用输出上限
     */
    private fun intervalNanos(
        targetFps: Int,
        hasNew: Boolean,
    ): Long {
        val fps = targetFps.coerceIn(1, 120)
        val frameInterval = (1_000_000_000L + fps - 1) / fps
        return if (hasNew) frameInterval else maxOf(33_333_334L, frameInterval)
    }
}
