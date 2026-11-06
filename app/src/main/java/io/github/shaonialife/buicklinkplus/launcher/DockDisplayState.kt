package io.github.shaonialife.buicklinkplus.launcher

/**
 * 当前显示内容与运行列表分开维护，失败不提交，旧请求不能覆盖新选择
 */
internal class DockDisplayState {
    enum class Page { DISCONNECTED, DESKTOP, DASHBOARD, SETTINGS, APP, MAP, OTHER }

    data class Content(
        val page: Page,
        val component: String? = null,
    )

    data class Request(
        val session: Long,
        val generation: Long,
    )

    private var session = 0L
    private var generation = 0L
    private var pendingTarget: String? = null

    @Volatile var hasPending = false
        private set

    /** 等待项只表示待切换目标，已显示项保持原值 */
    @Synchronized fun pendingComponent(): String? = if (hasPending) pendingTarget else null

    @Volatile var content = Content(Page.DISCONNECTED)
        private set

    /**
     * 发起显示切换时只记录凭据，保留已显示项
     */
    @Synchronized fun begin(): Request {
        hasPending = true
        pendingTarget = null
        return Request(session, ++generation)
    }

    /** 连点同一图标复用当前请求，避免反复取消启动和首帧等待 */
    @Synchronized fun beginTarget(target: String): Request? {
        if (hasPending && pendingTarget == target) return null
        return begin().also { pendingTarget = target }
    }

    /**
     * 等待期间可检查所有权，断开和新切换都会淘汰旧回调
     */
    @Synchronized fun owns(request: Request): Boolean = request.session == session && request.generation == generation

    /**
     * 低频前台核对只捕获当前代次，不抢占正在启动的请求
     */
    @Synchronized fun snapshot(): Request = Request(session, generation)

    /** 组件回调只接续原请求，不能覆盖用户后来打开的页面 */
    @Synchronized fun beginObservedTarget(
        request: Request,
        target: String,
    ): Request? {
        if (!owns(request) || hasPending) return null
        return beginTarget(target)
    }

    /**
     * 只有实际显示成功的当前请求才提交选中内容
     */
    @Synchronized fun commit(
        request: Request,
        value: Content,
    ): Boolean {
        if (!owns(request)) return false
        content = value
        hasPending = false
        pendingTarget = null
        generation++
        return true
    }

    /**
     * 失败只结束当前请求，原显示内容保持不变
     */
    @Synchronized fun fail(request: Request) {
        if (owns(request)) {
            hasPending = false
            pendingTarget = null
            generation++
        }
    }

    /** 页面恢复后更新显示状态，启动中仍由原请求决定 */
    @Synchronized fun observe(value: Content): Boolean {
        if (hasPending || content == value) return false
        generation++
        content = value
        return true
    }

    /**
     * 查询返回后复核代次，旧快照不能覆盖新的启动或断开状态
     */
    @Synchronized fun observe(
        request: Request,
        value: Content,
    ): Boolean = owns(request) && observe(value)

    /**
     * 断开时立即清除选中项并使旧请求失效
     */
    @Synchronized fun disconnect() {
        session++
        generation++
        hasPending = false
        pendingTarget = null
        content = Content(Page.DISCONNECTED)
    }
}
