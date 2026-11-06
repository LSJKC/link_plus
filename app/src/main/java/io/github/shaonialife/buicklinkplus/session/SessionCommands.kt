package io.github.shaonialife.buicklinkplus.session

import android.content.Context
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * 界面通过异步命令操作进程级会话
 */
object SessionCommands {
    private val stop = AsyncSessionStop(Bg.pool)
    val stopping: Boolean get() = stop.stopping

    /**
     * 立即取消旧请求并后台释放本次连接资源
     */
    fun disconnect(context: Context) {
        val application = context.applicationContext
        stop.submit(
            cancel = {
                SessionController.connectCancelled = true
                SessionController.sessionStarting.cancel()
            },
            cleanup = { SessionController.disconnect(application) },
        )
    }
}
