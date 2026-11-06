package io.github.shaonialife.buicklinkplus.shizuku

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.IBinder
import android.os.ParcelFileDescriptor

/** shell 媒体采集，参考 APK 的公开系统调用行为独立实现 */
internal class ShellAudioCapture(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val lease =
        io.github.shaonialife.buicklinkplus.audio
            .AudioCaptureLease()
    private var binder: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var policy: Any? = null
    private var recorder: AudioRecord? = null
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null

    @Volatile private var worker: Thread? = null

    /**
     * shell 的归属必须与系统授予媒体路由权限的包一致
     */
    private class ShellContext(
        context: Context,
    ) : ContextWrapper(context) {
        override fun getPackageName(): String = "com.android.shell"

        override fun getOpPackageName(): String = "com.android.shell"

        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName(packageName).build()
    }

    /** 读取采集能力，Binder 绑定成功不算采集就绪 */
    fun available(): Boolean =
        android.os.Process.myUid() == 2000 &&
            context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 注册独占媒体与导航路由，成功录音后才向应用交付管道
     */
    @Synchronized
    // 仅在 shell 媒体路由权限核验通过后反射注册音频策略，没有公开独占路由接口
    @android.annotation.SuppressLint("PrivateApi")
    fun start(
        session: Long,
        rate: Int,
        client: IBinder,
    ): ParcelFileDescriptor {
        require(rate == 48000 || rate == 44100)
        check(available()) { "系统未提供媒体路由权限，请使用兼容音频授权" }
        check(lease.canBegin(session)) { "采集请求已取消或被更新" }
        stop(lease.owner)
        check(lease.begin(session)) { "采集请求已取消或过期" }
        var readEnd: ParcelFileDescriptor? = null
        try {
            val shell = ShellContext(context)
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rules = ruleBuilderClass.getConstructor().newInstance()
            val match = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val add = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            for (usage in listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            )) {
                add.invoke(rules, match, AudioAttributes.Builder().setUsage(usage).build())
            }
            val format =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(ruleBuilderClass.getMethod("build").invoke(rules))
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass
                .getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val builderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val builder = builderClass.getConstructor(Context::class.java).newInstance(shell)
            builderClass.getMethod("addMix", mixClass).invoke(builder, mix)
            val registered = builderClass.getMethod("build").invoke(builder)
            check(
                AudioManager::class.java
                    .getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                    .apply {
                        isAccessible = true
                    }.invoke(null, registered) ==
                    0,
            ) {
                "系统拒绝媒体路由，请使用兼容音频授权"
            }
            policy = registered
            val registration = mixClass.getMethod("getRegistration").invoke(mix) as String
            check(registration.isNotBlank()) { "媒体路由注册未完成" }
            val attributes = AudioAttributes.Builder()
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(attributes, 8)
            val tag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
            tag.invoke(attributes, "addr=$registration")
            tag.invoke(attributes, "fixedVolume")
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "采样格式不可用" }
            val recordBuilder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minBuffer, 8192))
            AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java).invoke(recordBuilder, shell)
            AudioRecord.Builder::class.java
                .getMethod(
                    "setAudioAttributes",
                    AudioAttributes::class.java,
                ).invoke(recordBuilder, attributes.build())
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("shell 未获得录音权限，请使用兼容声音授权")
            }
            val record = recordBuilder.build()
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "媒体采集初始化失败" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统拒绝媒体采集" }
            val pipe = ParcelFileDescriptor.createReliablePipe()
            readEnd = pipe[0]
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            output = sink
            val recipient = IBinder.DeathRecipient { stop(session) }
            client.linkToDeath(recipient, 0)
            binder = client
            death = recipient
            val thread =
                Thread({
                    try {
                        val bytes = ByteArray(4096)
                        while (worker === Thread.currentThread()) {
                            val count = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "采集读取失败：$count" }
                            sink.write(bytes, 0, count)
                        }
                    } catch (error: Exception) {
                        log("AUDIO: shell 采集结束：${error.message}")
                    } finally {
                        stop(session)
                    }
                }, "shell-audio-capture").apply { isDaemon = true }
            worker = thread
            thread.start()
            log("AUDIO: 媒体导航独占采集已启动 ${rate}Hz")
            return pipe[0]
        } catch (error: Exception) {
            runCatching { readEnd?.close() }
            stop(session)
            throw IllegalStateException("媒体采集未启动：${error.cause?.message ?: error.message}", error)
        }
    }

    /**
     * 只释放自己的采集，断开及客户端死亡均走同一清理路径
     */
    @Synchronized
    // 仅撤销本服务注册的音频策略，没有公开的独占路由释放接口
    @android.annotation.SuppressLint("PrivateApi")
    fun stop(session: Long) {
        if (!lease.stop(session)) return
        worker = null
        val previous = recorder
        recorder = null
        runCatching { previous?.stop() }
        runCatching { output?.close() }
        output = null
        runCatching { previous?.release() }
        death?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
        binder = null
        death = null
        val previousPolicy = policy
        policy = null
        if (previousPolicy != null) {
            runCatching {
                AudioManager::class.java
                    .getDeclaredMethod(
                        "unregisterAudioPolicyAsyncStatic",
                        Class.forName("android.media.audiopolicy.AudioPolicy"),
                    ).apply {
                        isAccessible =
                            true
                    }.invoke(null, previousPolicy)
            }.onFailure { log("AUDIO: 释放媒体路由失败：${it.message}") }
        }
    }

    /**
     * 宿主退出时关闭当前采集租约
     */
    @Synchronized
    fun close() {
        stop(lease.owner)
    }
}
