package io.github.shaonialife.buicklinkplus.shizuku

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.util.DebugFileLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 检查 NSD 端点拒绝连接后能否恢复 TLS 连接 */
class AdbRefusedRecoveryAndroidTest {
    /** 端点被拒后重新发现，再检查输入恢复 */
    @Test
    fun refusedAdvertisementThenRealWirelessRestore() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("adbRefusedRecovery") == "true")
        assertEquals("true", arguments.getString("embeddedAdb"))
        assertEquals("true", arguments.getString("restoreOnAppOpen"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val qemu =
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu"))
                .bufferedReader()
                .use { it.readText().trim() }
        check(qemu == "1") { "该专项仅允许项目专用模拟器执行" }
        EmbeddedAdb.initialize(context)
        assertTrue("必须预先完成真实配对，专项不能制造配对状态", EmbeddedAdb.hasPairing)
        assertFalse(EmbeddedServiceProvider.isRegistered)
        val manager = context.getSystemService(NsdManager::class.java)
        val registered = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val registrationError = AtomicReference<String?>()
        val advertised = AtomicBoolean()
        val retried = AtomicBoolean()
        val retryRequest = AtomicReference<Long?>()
        val watcherError = AtomicReference<Throwable?>()
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    advertised.set(true)
                    registered.countDown()
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    registrationError.set("注册失败=$errorCode")
                    registered.countDown()
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    advertised.set(false)
                    removed.countDown()
                }

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    registrationError.set("注销失败=$errorCode")
                    removed.countDown()
                }
            }
        var watcher: Thread? = null
        val enabled =
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("settings get global adb_wifi_enabled"))
                .bufferedReader()
                .use { it.readText().trim() }
        assertEquals("专项前无线调试必须开启", "1", enabled)
        try {
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("settings put global adb_wifi_enabled 0"))
                .use { it.readBytes() }
            // 只广告关闭的本机端口，不实现伪造的 TLS 或 Binder 成功通路
            val refusedPort = ServerSocket(0).use { it.localPort }
            manager.registerService(
                NsdServiceInfo().apply {
                    serviceName = "buick-adb-refused-recovery-test"
                    serviceType = "_adb-tls-connect._tcp."
                    port = refusedPort
                },
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
            assertTrue("NSD 注册超时", registered.await(10, TimeUnit.SECONDS))
            assertEquals(null, registrationError.get())
            watcher =
                Thread({
                    try {
                        val deadline = SystemClock.elapsedRealtime() + 30_000
                        while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                            if (DebugFileLog.read(context, currentSessionOnly = true).orEmpty().contains("连接失败，重新发现端点 次数=")) {
                                retried.set(true)
                                retryRequest.set(PairingService.snapshot.request)
                                manager.unregisterService(listener)
                                check(removed.await(5, TimeUnit.SECONDS)) { "拒绝端点注销超时" }
                                check(registrationError.get() == null) { "拒绝端点注销失败" }
                                android.os.ParcelFileDescriptor
                                    .AutoCloseInputStream(
                                        instrumentation.uiAutomation.executeShellCommand("settings put global adb_wifi_enabled 1"),
                                    ).use { it.readBytes() }
                                return@Thread
                            }
                            Thread.sleep(50)
                        }
                        error("未观察到真实连接拒绝后的重发现")
                    } catch (error: Throwable) {
                        watcherError.set(error)
                    }
                }, "adb-refused-recovery-test").apply {
                    isDaemon = true
                    start()
                }
            // 复用完整专项：只打开首页，检查真实 TLS、shell UID、显示帧、触控与退出清理
            EmbeddedActivationAndroidTest().actualWirelessPairingBootstrapsIndependentService()
            assertTrue("必须在同一请求内观察到拒绝后重发现", retried.get())
            watcherError.get()?.let { throw AssertionError("恢复观察失败", it) }
            assertEquals(PairingService.Phase.READY, PairingService.snapshot.phase)
            assertEquals("拒绝与就绪须属于同一请求", retryRequest.get(), PairingService.snapshot.request)
            File(context.getExternalFilesDir(null), "adb-refused-recovery-result.txt").writeText(
                "真实NSD拒绝端点=true\n同一恢复流程重新发现=true\n系统TLS与输入专项通过=true\n",
            )
        } finally {
            watcher?.interrupt()
            watcher?.join(3000)
            if (advertised.get()) runCatching { manager.unregisterService(listener) }
            // 专项前提为无线调试开启；不把临时关闭留给后续回归
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("settings put global adb_wifi_enabled 1"))
                .use { it.readBytes() }
        }
    }
}
