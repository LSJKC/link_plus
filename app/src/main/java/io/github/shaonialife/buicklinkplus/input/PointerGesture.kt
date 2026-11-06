package io.github.shaonialife.buicklinkplus.input

/** 注入成功后才推进手势，跨屏收笔使用旧屏最后成功的位置 */
internal class PointerGesture {
    data class Target(
        val displayId: Int,
        val x: Int,
        val y: Int,
    )

    enum class Action { DOWN, MOVE, UP }

    var activeTarget: Target? = null
        private set
    var awaitingRelease = false
        private set

    fun dispatch(
        down: Boolean,
        target: Target,
        inject: (Action, Target) -> Boolean,
    ): Boolean {
        val active = activeTarget
        if (!down) {
            if (active == null) return true
            // 抬起点已进入其他区域时，不能把新屏局部坐标用于旧屏
            return release(if (active.displayId == target.displayId) target else active, inject)
        }
        if (active != null && (awaitingRelease || active.displayId != target.displayId)) {
            if (!release(active, inject)) return false
        }
        val action = if (activeTarget == null) Action.DOWN else Action.MOVE
        if (!inject(action, target)) return false
        activeTarget = target
        return true
    }

    private fun release(
        target: Target,
        inject: (Action, Target) -> Boolean,
    ): Boolean {
        // 拒绝或异常后，下一次按下先补收笔，不能误发为旧手势的 MOVE
        awaitingRelease = true
        if (!inject(Action.UP, target)) return false
        reset()
        return true
    }

    /** 抬起恰逢布局或服务不可用时，保留收笔意图供下次有效输入补发 */
    fun deferRelease() {
        awaitingRelease = activeTarget != null
    }

    fun reset(): Target? {
        val active = activeTarget
        activeTarget = null
        awaitingRelease = false
        return active
    }
}
