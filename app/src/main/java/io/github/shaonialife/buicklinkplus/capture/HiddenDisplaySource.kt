package io.github.shaonialife.buicklinkplus.capture

import android.content.Context
import android.view.Surface
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.shizuku.CarInputClient
import io.github.shaonialife.buicklinkplus.shizuku.ShellServiceGate

/**
 * 把虚拟屏启动器接到编码器 Surface
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "隐形屏"

    /** 保留应用屏 ID，兼容原有调用 */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometry: DisplayGeometry,
        targetFps: Int,
    ): Boolean {
        detach()
        val gate = ShellServiceGate.state(context)
        if (gate != ShellServiceGate.State.READY) {
            log("SERVICE: 内置服务未就绪，请先在手机激活服务")
            return false
        }
        CarInputClient.ensure(context)
        // 由启动器创建虚拟屏，合成后输出到编码器
        val ok =
            VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                geometry,
                targetFps,
                log,
            )
        displayId =
            if (ok) VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()
}
