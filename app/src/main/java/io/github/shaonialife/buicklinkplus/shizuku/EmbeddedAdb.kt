package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Context
import android.net.InetAddresses
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.shaonialife.buicklinkplus.session.SessionController
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 仅用于本机配对和启动服务的 ADB 客户端
 */
object EmbeddedAdb {
    private const val IDENTITY = "embedded-adb-identity"
    private const val KEY_ALIAS = "buick-link-embedded-adb"
    private val generation = AtomicLong()

    @Volatile
    private var transport: AdbTransport? = null
    private val busy = AtomicBoolean()

    @Volatile private var worker: Thread? = null

    @Volatile
    var hasIdentity: Boolean = false
        private set

    @Volatile
    var hasPairing: Boolean = false
        private set

    @Volatile
    var activating: Boolean = false
        private set

    /**
     * 后台读取身份是否存在，手机界面只读取快照
     */
    fun initialize(context: Context) {
        hasIdentity = File(context.noBackupFilesDir, IDENTITY).isFile
        hasPairing = hasIdentity && context.getSharedPreferences("embedded_service", Context.MODE_PRIVATE).getBoolean("paired", false)
    }

    /**
     * 配对和连接地址必须属于本机，拒绝连接其他设备
     */
    fun validateHost(host: String): String {
        val address = InetAddresses.parseNumericAddress(host.trim())
        require(address.isLoopbackAddress || NetworkInterface.getByInetAddress(address) != null) { "请输入本机无线调试地址" }
        return address.hostAddress ?: error("无法读取本机地址")
    }

    /**
     * 执行一次用户明确发起的配对，不保存配对码
     */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun pair(
        context: Context,
        host: String,
        port: Int,
        code: String,
    ) {
        check(!EmbeddedServiceProvider.isRegistered) { "内置服务已在线，无需重新配对" }
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "请填写有效配对端口和六位配对码" }
        operation(context, host, port) { current, localPort, request ->
            check(current.pair("127.0.0.1", localPort, code)) { "系统未接受配对，请重新打开配对码窗口" }
            check(generation.get() == request && !Thread.currentThread().isInterrupted) { "配对已取消" }
            check(
                context
                    .getSharedPreferences("embedded_service", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("paired", true)
                    .commit(),
            ) { "配对记录保存失败，请重试" }
            hasPairing = true
        }
    }

    /**
     * 连接本机并启动服务，交接完成后主动关闭 ADB 通道
     */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun activate(
        context: Context,
        host: String,
        port: Int,
    ) {
        if (CarInputClient.ready) return
        if (EmbeddedServiceProvider.isRegistered) {
            check(CarInputClient.awaitReady(context, retry = true)) { "权限服务在线，但触控检查尚未通过，请直接重试" }
            return
        }
        operation(context, host, port) { current, localPort, request ->
            var token: String? = null
            var success = false
            try {
                check(current.connect("127.0.0.1", localPort)) { "未连接本机无线调试，请检查连接端口" }
                check(generation.get() == request && !Thread.currentThread().isInterrupted) { "激活已取消" }
                token = EmbeddedServiceProvider.issueToken()
                val log = "/data/local/tmp/${context.packageName}-embedded-service.log"
                val command =
                    "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/setsid /system/bin/app_process /system/bin " +
                        "--nice-name=${quote(context.packageName + ":car_input")} " +
                        "${EmbeddedServiceMain::class.java.name} ${quote(context.packageName)} ${quote(token)} " +
                        "> ${quote(log)} 2>&1 < /dev/null & wait"
                current.openStream("shell:$command").use {
                    check(EmbeddedServiceProvider.awaitBinder(10_000)) { "服务交接超时，请检查系统是否限制无线调试启动" }
                }
                check(generation.get() == request && !Thread.currentThread().isInterrupted) { "激活已取消" }
                check(CarInputClient.awaitReady(context, retry = true)) { "服务已返回，但输入能力检查失败" }
                check(generation.get() == request && !Thread.currentThread().isInterrupted) { "激活已取消" }
                check(
                    context
                        .getSharedPreferences("embedded_service", Context.MODE_PRIVATE)
                        .edit()
                        .putString("host", validateHost(host))
                        .putInt("port", port)
                        .commit(),
                ) { "激活信息保存失败，请重试" }
                success = true
            } finally {
                if (success) EmbeddedServiceProvider.cancelPending() else token?.let { EmbeddedServiceProvider.reject(it) }
            }
        }
    }

    /**
     * 串行持有一次有界传输，所有结束路径均关闭 ADB 和套接字
     */
    private fun operation(
        context: Context,
        host: String,
        port: Int,
        action: (Manager, Int, Long) -> Unit,
    ) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "配对和激活必须在后台执行" }
        check(
            !io.github.shaonialife.buicklinkplus.session.SessionController
                .isSessionRunning() &&
                !io.github.shaonialife.buicklinkplus.session.SessionController.sessionStarting
                    .get(),
        ) { "请先断开车机，再进行配对或激活" }
        require(port in 1..65535) { "请填写有效端口" }
        val local = validateHost(host)
        check(!Thread.currentThread().isInterrupted) { "操作已取消" }
        check(busy.compareAndSet(false, true)) { "已有配对或激活正在进行" }
        val request = generation.incrementAndGet()
        activating = true
        worker = Thread.currentThread()
        var current: Manager? = null
        try {
            current = Manager(loadIdentity(context))
            hasIdentity = true
            check(generation.get() == request && !Thread.currentThread().isInterrupted) { "操作已取消" }
            AdbTransport(local, port).use { channel ->
                transport = channel
                check(generation.get() == request && !Thread.currentThread().isInterrupted) { "操作已取消" }
                try {
                    action(current, channel.localPort, request)
                } catch (error: Throwable) {
                    channel.failure?.takeIf { it !== error }?.let { error.addSuppressed(it) }
                    throw error
                }
            }
        } finally {
            transport?.close()
            transport = null
            runCatching { current?.disconnect() }
            worker = null
            activating = false
            busy.set(false)
            Thread.interrupted()
        }
    }

    /** 关闭传输并中断等待，阻塞的 ADB 关闭不在主线程执行 */
    fun cancel() {
        if (!activating || CarInputClient.ready) return
        generation.incrementAndGet()
        transport?.close()
        worker?.interrupt()
        EmbeddedServiceProvider.cancelPending()
    }

    /**
     * 用户确认后仅重建本应用配对身份，保留车机和桌面设置
     */
    // 重置配对必须确认记录落盘后才删除原密钥
    @android.annotation.SuppressLint("UseKtx")
    fun resetIdentity(context: Context) {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "重置配对身份必须在后台执行" }
        check(!EmbeddedServiceProvider.isRegistered && busy.compareAndSet(false, true)) { "请先结束当前激活操作" }
        try {
            check(
                context
                    .getSharedPreferences("embedded_service", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("paired", false)
                    .commit(),
            ) { "重置配对记录失败" }
            hasPairing = false
            val file = AtomicFile(File(context.noBackupFilesDir, IDENTITY))
            file.delete()
            check(!file.baseFile.exists()) { "旧配对身份删除失败" }
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
            hasIdentity = false
        } finally {
            busy.set(false)
        }
    }

    /**
     * 使用单引号转义启动参数，不接受任意 shell 片段
     */
    internal fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * 应用私有身份由 Android Keystore AES 密钥加密并原子保存
     */
    private fun loadIdentity(context: Context): Pair<PrivateKey, Certificate> {
        val file = AtomicFile(File(context.noBackupFilesDir, IDENTITY))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key =
            (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance("AES", "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
        if (file.baseFile.exists()) {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size in 29..65536) { "ADB 身份文件损坏" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            DataInputStream(ByteArrayInputStream(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))).use { input ->
                val size = input.readInt()
                require(size in 1..8192) { "ADB 密钥长度异常" }
                val encoded = ByteArray(size).also { input.readFully(it) }
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input)
                return privateKey to certificate
            }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val name = X500Name("CN=Buick Link Plus")
        val certificate =
            JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(
                    name,
                    BigInteger(64, SecureRandom()),
                    now,
                    Date(now.time + TimeUnit.DAYS.toMillis(3650)),
                    name,
                    pair.public,
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
            )
        val plain =
            ByteArrayOutputStream()
                .apply {
                    DataOutputStream(this).use { out ->
                        out.writeInt(pair.private.encoded.size)
                        out.write(pair.private.encoded)
                        out.write(certificate.encoded)
                    }
                }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(cipher.doFinal(plain))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return pair.private to certificate
    }

    /** 适配 ADB 身份，固定系统版本和连接超时 */
    private class Manager(
        private val identity: Pair<PrivateKey, Certificate>,
    ) : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(8, TimeUnit.SECONDS)
            setThrowOnUnauthorised(true)
        }

        /**
         * 返回解密后的私钥，仅驻留当前进程
         */
        override fun getPrivateKey(): PrivateKey = identity.first

        /**
         * 返回对应证书
         */
        override fun getCertificate(): Certificate = identity.second

        /**
         * 系统已配对设备列表中的名称
         */
        override fun getDeviceName(): String = "Buick Link Plus"
    }
}
