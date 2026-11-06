package io.github.shaonialife.buicklinkplus.audio

import android.os.IBinder
import io.github.shaonialife.buicklinkplus.compat.CarInputClient

/** UI 只读取能力快照，不调用远程服务 */
object AudioCaptureSupport {
    private val executor =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "audio-capability-probe").apply { isDaemon = true } },
        )

    @Volatile private var owner: IBinder? = null

    @Volatile private var supported = false
    val available: Boolean get() = supported && owner === CarInputClient.service?.asBinder() && owner?.isBinderAlive == true

    /** 后台检查新接口，旧服务失败时保留原授权入口 */
    fun refresh(): Boolean {
        val service = CarInputClient.service
        val task = java.util.concurrent.FutureTask { service != null && service.capabilities() and 1 != 0 }
        val result =
            try {
                executor.execute(task)
                task.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            } finally {
                task.cancel(true)
                executor.remove(task)
            }
        owner = service?.asBinder()
        supported = result
        return available
    }
}
