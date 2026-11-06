package io.github.shaonialife.buicklinkplus.input

/** 手势一旦离开安全区，必须等物理抬起后才允许下一笔 */
internal class SafeGestureGate {
    private var blocked = false
    private var previousLayout: Any? = null
    private var physicalDown = false

    /**
     * 手势中布局改变时同样拒绝后续触点，防止按新几何补发点击
     */
    fun accept(
        down: Boolean,
        inside: Boolean,
        layoutToken: Any? = null,
    ): Boolean {
        val usable = inside && !(physicalDown && previousLayout !== layoutToken)
        previousLayout = layoutToken
        physicalDown = down
        val accepted = !blocked && usable
        if (down && !usable) blocked = true
        if (!down) blocked = false
        return accepted
    }

    fun reset() {
        blocked = false
        previousLayout = null
        physicalDown = false
    }
}
