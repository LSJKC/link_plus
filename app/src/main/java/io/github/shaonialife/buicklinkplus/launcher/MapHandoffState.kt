package io.github.shaonialife.buicklinkplus.launcher

/**
 * 地图交接的暂停及操作代次，旧会话结果不能发布到新会话
 */
internal class MapHandoffState {
    private var generation = 0L

    @Volatile var busy = false
        private set

    @Volatile var paused = false
        private set

    @Volatile var message = "连接车机后可交接地图"
        private set

    /** 交接请求互斥，手机接管后仍可主动交回 */
    @Synchronized fun begin(): Long? {
        if (busy) return null
        busy = true
        return ++generation
    }

    /**
     * 异步任务每个边界重新核对自己的操作代次
     */
    @Synchronized fun owns(request: Long): Boolean = busy && request == generation

    /**
     * 迁移前先暂停展示，失败时也保留可恢复状态
     */
    @Synchronized fun pause(request: Long): Boolean {
        if (!owns(request)) return false
        paused = true
        message = "地图正在交接，车机展示已暂停"
        return true
    }

    /** 检查完成后一次发布结果，不能只凭命令已接受就更新状态 */
    @Synchronized fun verified(
        request: Long,
        phone: Boolean,
    ): Boolean {
        if (!owns(request)) return false
        paused = phone
        message = if (phone) "地图已交给手机，确认后可交回车机" else "原地图任务已在车机恢复并提交画面"
        return true
    }

    /**
     * 仅原请求可以结束操作，失败保留暂停状态和重试入口
     */
    @Synchronized fun finish(request: Long) {
        if (!owns(request)) return
        busy = false
        if (paused) message = "地图由手机接管或交接未完成，车机展示已暂停，可重试交回"
    }

    /**
     * 手机主动打开普通地图时暂停，不覆盖正在执行的用户交接
     */
    @Synchronized fun observePhone(): Boolean {
        if (busy) return false
        paused = true
        message = "手机地图已在前台，车机暂停展示；确认后可交回原任务"
        return true
    }

    /**
     * 断开或更换会话使旧操作和旧暂停状态全部失效
     */
    @Synchronized fun reset() {
        generation++
        busy = false
        paused = false
        message = "已断开连接"
    }
}
