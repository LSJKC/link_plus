package io.github.shaonialife.buicklinkplus.ui

/** 壁纸请求按版本与几何合并，只有成功应用才记为已显示 */
internal class WallpaperRequestState {
    data class Request(
        val key: String,
        val generation: Long,
    )

    private var key: String? = null
    private var shown: String? = null
    private var generation = 0L
    private var pending = false
    private var attempts = 0
    private var retryAt = 0L

    /** 相同内容最多解码两次，失败后留出退避时间 */
    @Synchronized
    fun begin(
        next: String,
        now: Long,
    ): Request? {
        if (next != key) {
            key = next
            generation++
            pending = false
            attempts = 0
            retryAt = 0L
        }
        if (shown == next || pending || attempts >= 2 || now < retryAt) return null
        attempts++
        pending = true
        return Request(next, ++generation)
    }

    /** 旧尺寸、旧壁纸或已取消的回调不得发布图片 */
    @Synchronized
    fun owns(request: Request): Boolean = pending && request.generation == generation && request.key == key

    /** 返回是否允许再尝试一次，成功标记必须在图片应用之后提交 */
    @Synchronized
    fun finish(
        request: Request,
        applied: Boolean,
        now: Long,
    ): Boolean {
        if (!owns(request)) return false
        pending = false
        if (applied) shown = request.key else retryAt = now + 500L
        return !applied && attempts < 2
    }

    /** 生命周期取消不属于解码失败，重新挂载允许重新申请 */
    @Synchronized
    fun cancel() {
        generation++
        pending = false
        key = null
        shown = null
    }
}
