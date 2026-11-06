package io.github.shaonialife.buicklinkplus.compat

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置无线 ADB 激活已按需求整体移除；这些仅为遗留调用点的降级占位，不执行任何 ADB/shell/root 操作。
 *
 * 真实输入服务（原 shizuku 包内的 CarInputClient）已随内置 ADB 激活一并删除。
 * 保留同名入口只为让既有调用点继续编译并安全降级：服务恒为空、就绪检查恒失败、
 * 失效/回收操作恒无副作用，调用方必须走各自的“无服务”分支。
 */
object CarInputClient {
    private const val TAG = "NoAdbCompat"

    /** 兼容占位：输入服务已移除，恒为 null。 */
    @Volatile
    var service: ICarInput? = null
        private set

    /** 保留日志出口以兼容上游赋值；本对象只输出一次移除说明。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 兼容占位：没有输入服务，永远不可能就绪。 */
    val ready: Boolean get() = false

    private val removalLogged = AtomicBoolean(false)

    /** 兼容遗留的“确保输入服务在线”调用：只记录一次移除说明，不启动任何服务。 */
    fun ensure(context: Context) {
        if (!removalLogged.compareAndSet(false, true)) return
        val message = "内置无线 ADB 激活已移除：输入服务不再提供（${context.packageName}）"
        Log.i(TAG, message)
        logSink?.invoke(message)
    }

    /** 兼容占位：等待输入服务恒失败，调用方应直接走降级分支。 */
    @Suppress("UNUSED_PARAMETER")
    fun awaitReady(
        context: Context,
        retry: Boolean = false,
    ): Boolean = false

    /** 兼容占位：没有输入服务可失效。 */
    @Suppress("UNUSED_PARAMETER")
    fun invalidate(dead: Any?) = Unit

    /** 兼容占位：没有本应用拉起的服务进程可回收。 */
    fun reapStaleUserServices(): Int = 0
}
