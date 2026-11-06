package io.github.shaonialife.buicklinkplus.shizuku

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 复用原发现预算等待激活，仅明确的连接失败允许重新发现端点
 */
internal fun awaitAdbActivation(
    deadline: Long,
    now: () -> Long,
    checkActive: () -> Unit,
    attempt: () -> Boolean,
    pause: (Long) -> Unit,
    onConnectionFailure: (Exception) -> Unit,
): Boolean {
    var lastConnectionFailure: Exception? = null
    while (now() < deadline) {
        checkActive()
        try {
            if (attempt()) {
                checkActive()
                return true
            }
        } catch (error: Exception) {
            checkActive()
            if (!isAdbConnectionFailure(error)) throw error
            lastConnectionFailure = error
            if (now() >= deadline) throw error
            onConnectionFailure(error)
        }
        checkActive()
        val remaining = deadline - now()
        if (remaining > 0) pause(minOf(300L, remaining))
    }
    checkActive()
    lastConnectionFailure?.let { throw it }
    return false
}

/** 提取 EOF 附带的传输原因，认证、权限和中断优先结束，按异常对象去重 */
private fun isAdbConnectionFailure(error: Throwable): Boolean {
    val pending = ArrayDeque<Throwable>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var connectionFailure = false
    pending.add(error)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current is AdbAuthenticationFailedException ||
            current is AdbPairingRequiredException ||
            current is SecurityException ||
            current is InterruptedException ||
            current is InterruptedIOException
        ) {
            return false
        }
        if (current is ConnectException) connectionFailure = true
        current.cause?.let { pending.add(it) }
        current.suppressed.forEach { pending.add(it) }
    }
    return connectionFailure
}
