package io.github.shaonialife.buicklinkplus.render

/**
 * 合并相同尺寸请求，切换目标或结束会话后旧请求失效
 */
class MapResizeCoordinator {
    data class Request(
        val width: Int,
        val height: Int,
        val generation: Long,
        val density: Int,
        val session: Long,
    )

    /**
     * 显示屏与缓冲分别确认，部分成功不能作为地图尺寸就绪
     */
    data class Result(
        val request: Request,
        val displayApplied: Boolean,
        val bufferConfirmed: Boolean,
    ) {
        val ready: Boolean get() = displayApplied && bufferConfirmed
    }

    private var generation = 0L
    private var session = 0L
    private var latest: Request? = null

    /**
     * 请求相同尺寸复用当前凭据，只有目标变化才推进代次
     */
    @Synchronized fun request(
        width: Int,
        height: Int,
        density: Int = 200,
    ): Request {
        require(width > 0 && height > 0 && density > 0)
        latest?.takeIf { it.width == width && it.height == height && it.density == density }?.let { return it }
        return Request(width, height, ++generation, density, session).also { latest = it }
    }

    /**
     * 检查任务是否仍为当前目标
     */
    @Synchronized fun owns(request: Request): Boolean = latest == request

    /**
     * 会话结束后所有旧尺寸请求失效
     */
    @Synchronized fun invalidate() {
        session++
        generation++
        latest = null
    }
}
