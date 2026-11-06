package io.github.shaonialife.buicklinkplus.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 检查 shell 身份和随机令牌后接收服务 Binder */
class EmbeddedServiceProvider : ContentProvider() {
    /**
     * Provider 初始化不执行磁盘和网络操作
     */
    override fun onCreate(): Boolean = true

    /**
     * 只接受 shell 进程对当前激活请求的回传
     */
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        if (method != REGISTER || Binder.getCallingUid() != 2000) throw SecurityException("仅接受本机 shell 服务注册")
        val supplied = extras?.getString(TOKEN) ?: return Bundle()
        val binder = extras.getBinder(SERVICE) ?: return Bundle()
        synchronized(lock) {
            val expected = pendingToken ?: return Bundle()
            if (!MessageDigest.isEqual(expected.toByteArray(), supplied.toByteArray())) return Bundle()
            if (shell?.asBinder()?.isBinderAlive == true) return Bundle()
            val candidate = IEmbeddedShell.Stub.asInterface(binder)
            binder.linkToDeath({
                synchronized(lock) { if (shell === candidate) shell = null }
                CarInputClient.invalidate(candidate)
            }, 0)
            pendingToken = null
            registeredToken = supplied
            shell = candidate
            ready.countDown()
            return Bundle().apply { putBinder(CLIENT, clientLifetime) }
        }
    }

    /**
     * 不提供数据查询
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    /**
     * 不提供内容类型
     */
    override fun getType(uri: Uri): String? = null

    /**
     * 不提供数据写入
     */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    /**
     * 不提供数据删除
     */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    /**
     * 不提供数据更新
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        const val REGISTER = "register"
        const val TOKEN = "token"
        const val SERVICE = "service"
        const val CLIENT = "client"
        const val PROTOCOL = 1
        private val lock = Any()
        private val clientLifetime = Binder()
        private var pendingToken: String? = null
        private var registeredToken: String? = null
        private var ready = CountDownLatch(1)

        @Volatile
        var shell: IEmbeddedShell? = null
            private set

        /**
         * 返回已交接的服务，不进行远程探活
         */
        val isRegistered: Boolean get() = shell?.asBinder()?.isBinderAlive == true

        /**
         * 为单次启动生成令牌，禁止替换仍在线的服务
         */
        fun issueToken(): String =
            synchronized(lock) {
                check(!isRegistered) { "内置服务已在线" }
                ready = CountDownLatch(1)
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                bytes.joinToString("") { "%02x".format(it) }.also { pendingToken = it }
            }

        /**
         * 后台有界等待服务交接
         */
        fun awaitBinder(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS) && isRegistered

        /**
         * 取消过期交接，阻止迟到服务污染后续请求
         */
        fun cancelPending() =
            synchronized(lock) {
                pendingToken = null
                ready.countDown()
            }

        /**
         * 只清理本次失败交接，禁止迟到服务替换后续请求
         */
        fun reject(token: String) {
            val rejected =
                synchronized(lock) {
                    if (pendingToken == token) pendingToken = null
                    if (registeredToken != token) return
                    registeredToken = null
                    shell.also { shell = null }
                }
            rejected?.let {
                CarInputClient.invalidate(it)
                runCatching { it.shutdown() }
            }
        }

        /**
         * 使用实际安装包名区分基础包和别克测试包
         */
        fun authority(context: Context): String = context.packageName + ".embedded-service"
    }
}
