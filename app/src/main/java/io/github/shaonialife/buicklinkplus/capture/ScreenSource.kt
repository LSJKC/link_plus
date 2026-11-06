package io.github.shaonialife.buicklinkplus.capture

import android.view.Surface
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry

/** 画面来源接口，负责向编码器 Surface 绘制；当前实现为 [HiddenDisplaySource] */
interface ScreenSource {
    val name: String

    /** 绑定（或重绑）编码 Surface；返回是否成功 */
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometry: DisplayGeometry,
        targetFps: Int,
    ): Boolean

    /** 解绑 Surface（保留底层资源以便快速重连） */
    fun detach()

    /** 释放全部资源 */
    fun release()
}
