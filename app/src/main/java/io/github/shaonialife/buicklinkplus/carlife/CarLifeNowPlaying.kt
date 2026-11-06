/*
 * Adapted from:
 *  - CarPToCarL app/src/main/java/io/github/shaonialife/carptocar/iap2/Iap2NowPlaying.kt
 *    (MIT, Copyright (c) 2026 lizhihua; commit 7b729fb) — 0x5001 NowPlayingUpdate 字段模型/编码；
 *    注意参考实现里曾把专辑名写到 0x001A 是历史 bug，这里按官方字段表：album = 0x0006，
 *    0x001A = u8 artworkTransferID（封面传输标识，不是专辑名）。
 *  - 历史参考实现（只用来确认历史 iAP2 0x5001 只发文本属性、albumArt 不进消息体）。
 *  - carvex-android protocol-carplay core/NowPlayingUpdate.kt（字段 ID / 订阅筛选语义交叉核对）。
 *
 * iAP2 0x5001 官方无歌词字段（不要写歌词）；封面图片不进消息体，只走 file-transfer + field 0x001A。
 */

package io.github.shaonialife.buicklinkplus.carlife

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import io.github.shaonialife.buicklinkplus.airplay.NowPlayingSubscription
import io.github.shaonialife.buicklinkplus.airplay.VirtualIap2Link
import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 媒体信息 → iAP2 `0x5001 NowPlayingUpdate` 桥（P5 前置，测试版）。
 *
 * ## 链路
 *
 * 真车（Buick E2LB，2026-11-05/06 日志）里 0x5000/0x5001 走 **AirPlay 虚拟 iAP2 链路**
 * （[VirtualIap2Link]，日志 `IAPLINK: rx 0x5000` / `NOWPLAYING:`），BT iAP2 不承载 NowPlaying。
 * 所以本类只通过 [VirtualIap2Link.active] 发送；车机没发 0x5000 订阅前一律不发（空态/失败都打日志）。
 *
 * ## 数据源
 *
 * [CarLifeHuClient.onMediaInfo]（0x10035，歌名/歌手/专辑/封面/duration）+ `onMediaProgress`
 * （0x10037 或 65590，u32 ms）→ [onMediaInfo] / [onMediaProgress]。
 * CarLife 的内置播放器只给"正在播"的媒体信息，没有可靠的 pause/stop 字段；可选把
 * [CarLifeHuClient.onAudioGate] 的 0x30002/0x30003 信号接到 [onAudioGate] 补播放状态。
 *
 * ## 0x5001 字段（组参数）
 *
 * 组 `0x0000`（media item）：`0x0001` title、`0x0004` duration(u32 ms)、`0x0006` album、
 * `0x000C` artist、`0x001A` artworkTransferID(u8)。
 * 组 `0x0001`（playback）：`0x0000` status(u8；0=stop/1=play/2=pause)、`0x0001` elapsed(u32 ms)、
 * `0x0005` shuffle(u8)、`0x0006` repeat(u8)、`0x0007` appName、`0x000C` playbackSpeed(u16，可选)。
 * 车机 0x5000 带了属性筛选表时按订阅表只发被选中的字段（见 [VirtualIap2Link.nowPlayingSelection]）。
 *
 * ## 封面（测试版，尽力）
 *
 * CarLifeMediaInfo.albumArt 解码后居中裁方、缩到 768×768、JPEG 质量 85，缓存 [PreparedArtwork] 并打
 * `NOWPLAYING:` 日志。但 **0x5001 不带 field 26**：封面必须走 iAP2 file-transfer 会话，会话号 k1 是
 * 车机动态给的（不能硬编码 12），且 SETUP/START/DATA/SUCCESS 的消息号/时序还没有真车证据（历史实现
 * 也没实现发送端）。拿不到 k1 / 传输失败时按坑位要求退化为"只发元数据"，不声明 artwork。
 *
 * ## 接线（SessionController / CarLifeScreenSource，Lead 负责）
 *
 * ```
 * // 1) CarLifeScreenSource 构造 CarLifeHuClient 时：
 * onMediaInfo = { CarLifeNowPlaying.onMediaInfo(it) },
 * onMediaProgress = { CarLifeNowPlaying.onMediaProgress(it) },
 * onAudioGate = { CarLifeNowPlaying.onAudioGate(it) },   // 可选：补 pause/play 状态
 * // 2) CarLife 断开时：
 * CarLifeNowPlaying.onCarLifeDisconnected()
 * // 3) 日志写盘（可选，不接则默认 android.util.Log tag=NOWPLAYING）：
 * CarLifeNowPlaying.log = { appendLog(it) }
 * ```
 *
 * 本类在第一次 [onMediaInfo]/[onMediaProgress] 时自动注册订阅钩子；[start] 用于提前注册、
 * [stop] 用于彻底卸载。**不改 [io.github.shaonialife.buicklinkplus.session.SessionController]。**
 */
object CarLifeNowPlaying {
    /** 播放状态：停止。 */
    const val PLAYBACK_STATUS_STOPPED = 0

    /** 播放状态：正在播放。 */
    const val PLAYBACK_STATUS_PLAYING = 1

    /** 播放状态：暂停。 */
    const val PLAYBACK_STATUS_PAUSED = 2

    // ---- 0x5001 参数 ID：组 0 媒体项 ----
    private const val GROUP_MEDIA_ITEM = 0x0000
    private const val MEDIA_TITLE = 0x0001
    private const val MEDIA_DURATION = 0x0004
    private const val MEDIA_ALBUM = 0x0006
    private const val MEDIA_ARTIST = 0x000C
    private const val MEDIA_ARTWORK_TRANSFER_ID = 0x001A

    // ---- 0x5001 参数 ID：组 1 播放属性 ----
    private const val GROUP_PLAYBACK = 0x0001
    private const val PLAYBACK_STATUS = 0x0000
    private const val PLAYBACK_ELAPSED = 0x0001
    private const val PLAYBACK_SHUFFLE = 0x0005
    private const val PLAYBACK_REPEAT = 0x0006
    private const val PLAYBACK_APP_NAME = 0x0007
    private const val PLAYBACK_SPEED = 0x000C

    private const val APP_NAME = "CarLife"
    private const val ARTWORK_SIZE_PX = 768
    private const val ARTWORK_JPEG_QUALITY = 85
    private const val MIN_SEND_INTERVAL_MS = 300L
    private const val SKIP_LOG_INTERVAL_MS = 10_000L

    /** 0x5001 字段模型（title/artist/album(0x0006)/duration/position(playback elapsed)/artworkTransferID 等）。 */
    data class Track(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Int,
        /** 播放位置（组 1 0x0001 elapsed，单位 ms）。 */
        val elapsedMs: Int,
        /** 0=停止、1=播放、2=暂停（见 PLAYBACK_STATUS_*）。 */
        val status: Int,
        val shuffleMode: Int = 0,
        val repeatMode: Int = 0,
        val appName: String? = APP_NAME,
        /** 组 0 0x001A u8 封面传输标识；null = 本版本不发（见类 KDoc 封面说明）。 */
        val artworkTransferId: Int? = null,
        /** 组 1 0x000C playbackSpeed（u16）；CarLife 不提供速率，默认不发。 */
        val playbackSpeed: Int? = null,
    )

    /** 已解码/压缩好的封面（测试版：仅缓存 + 日志，file-transfer 未接线，暂不进 0x5001）。 */
    data class PreparedArtwork(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val transferId: Int?,
    )

    /** 诊断日志出口；默认用 android.util.Log（tag=NOWPLAYING），接线时替换为写盘日志。 */
    @Volatile
    var log: ((String) -> Unit)? = { message -> Log.i("NOWPLAYING", message) }

    /** 是否解码/压缩 CarLifeMediaInfo.albumArt（测试版默认开，只产日志与缓存，不发 field26）。 */
    @Volatile
    var artworkEnabled: Boolean = true

    private val lock = Any()

    private val subscriptionHook: (Boolean) -> Unit = { subscribed -> onSubscriptionChanged(subscribed) }

    @Volatile
    private var hookInstalled = false

    private var currentTrack: Track? = null
    private var lastProgressMs = -1
    private var lastSentKey = ""
    private var lastSendAt = 0L
    private var lastSkipLogAt = 0L
    private var artworkSignature = 0
    private var artworkGeneration = 0
    private var audioGateActive = true

    @Volatile
    private var preparedArtwork: PreparedArtwork? = null

    /** 最近一次准备好的封面（768×768 JPEG；诊断/后续 file-transfer 接入用；未准备时为 null）。 */
    val artworkSnapshot: PreparedArtwork?
        get() = preparedArtwork

    /** 已成功发送的 0x5001 次数（诊断/设置页展示用）。 */
    @Volatile
    var sentCount: Long = 0L
        private set

    /** 最近一次跳过发送的原因（诊断/设置页展示用）。 */
    @Volatile
    var lastSkipReason: String = ""
        private set

    /**
     * 媒体/音乐订阅/封面准备状态摘要（设置页「CarLife」页展示）。
     *
     * 注意：封面目前**不声明 0x001A transferID**（k1 file-transfer 未接线），因此
     * 车机不会显示封面；这里只显示本地压缩准备情况，便于实车日志核对。
     */
    fun statusText(): String {
        val track = synchronized(lock) { currentTrack }
        val link = VirtualIap2Link.active
        val art = preparedArtwork
        return buildString {
            append("媒体：")
            if (track == null) {
                append("暂无")
            } else {
                append("${track.title ?: "?"} / ${track.artist ?: "?"} / ${track.album ?: "?"}")
            }
            append(" · 已发 $sentCount 次")
            append("\n订阅：")
            append(
                when {
                    link == null -> "无 AirPlay 虚拟 iAP2 链路"
                    link.nowPlayingSubscribed -> "已订阅 0x5000"
                    else -> "未订阅 0x5000"
                },
            )
            append(" · 封面：")
            if (art == null) {
                append(if (artworkEnabled) "无（未收到/解码中）" else "已关闭")
            } else {
                append("${art.width}×${art.height} ${art.jpeg.size}B transferID=${art.transferId ?: "未分配"}")
            }
            lastSkipReason.takeIf { it.isNotBlank() }?.let { append("\n最近跳过：").append(it) }
        }
    }

    private var executor: ExecutorService? = null

    private val emptyTrack =
        Track(
            title = "",
            artist = "",
            album = "",
            durationMs = 0,
            elapsedMs = 0,
            status = PLAYBACK_STATUS_STOPPED,
            appName = APP_NAME,
        )

    /** 提前注册车机订阅钩子（可选；首次媒体回调会自动注册）。 */
    fun start() {
        synchronized(lock) {
            installHookLocked()
        }
        if (VirtualIap2Link.active?.nowPlayingSubscribed == true) {
            onSubscriptionChanged(true)
        }
    }

    /** 卸载订阅钩子并释放封面线程（断开/退出时调用；之后仍可被媒体回调自动重启）。 */
    fun stop() {
        synchronized(lock) {
            hookInstalled = false
            executor?.shutdownNow()
            executor = null
            artworkGeneration++
            currentTrack = null
            lastProgressMs = -1
            lastSentKey = ""
            preparedArtwork = null
            artworkSignature = 0
        }
        if (VirtualIap2Link.onNowPlayingSubscription === subscriptionHook) {
            VirtualIap2Link.onNowPlayingSubscription = null
        }
        nowPlayingLog("NOWPLAYING: 桥已停止")
    }

    /** CarLife 0x10035 媒体信息（sing/artist/album/albumArt/duration）。 */
    fun onMediaInfo(info: CarLifeMediaInfo) {
        ensureStarted()
        if (info.song.isBlank() && info.artist.isBlank() && info.album.isBlank() && info.duration <= 0) {
            nowPlayingLog("NOWPLAYING: 收到空媒体信息（${info.albumArt?.size ?: 0}B 封面）→ 按停止处理")
            sendStoppedSnapshot("空媒体信息")
            return
        }
        val artworkRaw = info.albumArt?.takeIf { it.isNotEmpty() }
        var metadataChanged = true
        synchronized(lock) {
            val next =
                Track(
                    title = info.song,
                    artist = info.artist,
                    album = info.album,
                    durationMs = info.duration.coerceAtLeast(0),
                    elapsedMs = lastProgressMs.coerceAtLeast(0),
                    status = if (audioGateActive) PLAYBACK_STATUS_PLAYING else PLAYBACK_STATUS_PAUSED,
                    appName = APP_NAME,
                )
            metadataChanged = currentTrack?.let { trackKey(it) != trackKey(next) } ?: true
            currentTrack = next
            if (artworkRaw != null && artworkEnabled) {
                val signature = artworkSignatureOf(artworkRaw)
                if (signature != artworkSignature) {
                    artworkSignature = signature
                    scheduleArtworkLocked(artworkRaw, signature)
                }
            } else if (artworkRaw == null && artworkSignature != 0) {
                artworkSignature = 0
                preparedArtwork = null
            }
        }
        nowPlayingLog(
            "NOWPLAYING: 媒体信息 title=${info.song} artist=${info.artist} album=${info.album} " +
                "dur=${info.duration}ms art=${artworkRaw?.size ?: 0}B mode=${info.mode}",
        )
        if (metadataChanged) sendSnapshot(force = true)
    }

    /** CarLife 0x10037（65590）媒体进度（u32，单位 ms）。 */
    fun onMediaProgress(progressMs: Int) {
        ensureStarted()
        if (progressMs < 0) return
        val changed =
            synchronized(lock) {
                if (progressMs == lastProgressMs) {
                    false
                } else {
                    lastProgressMs = progressMs
                    val track = currentTrack
                    if (track == null) {
                        false
                    } else {
                        currentTrack = track.copy(elapsedMs = progressMs)
                        true
                    }
                }
            }
        if (changed) sendSnapshot(force = false)
    }

    /**
     * 可选：CarLife 引擎音频开停（0x30002 开始 / 0x30003 停止）→ 播放/暂停状态。
     *
     * 这个信号只代表 PCM 推流开停（来电/路由切换会让它停），比"进度不涨"更实时；
     * 车机没有独立 pause 信号时用它补 playback status。
     */
    fun onAudioGate(active: Boolean) {
        ensureStarted()
        val changed =
            synchronized(lock) {
                if (audioGateActive == active) {
                    false
                } else {
                    audioGateActive = active
                    val track = currentTrack
                    if (track == null) {
                        false
                    } else {
                        currentTrack =
                            track.copy(status = if (active) PLAYBACK_STATUS_PLAYING else PLAYBACK_STATUS_PAUSED)
                        true
                    }
                }
            }
        if (changed) sendSnapshot(force = true)
    }

    /** CarLife 链路断开：清空状态并给车机发一次停止空态（车机仪表/媒体卡复位）。 */
    fun onCarLifeDisconnected() {
        synchronized(lock) {
            currentTrack = null
            lastProgressMs = -1
            lastSentKey = ""
            artworkSignature = 0
            artworkGeneration++
            preparedArtwork = null
        }
        nowPlayingLog("NOWPLAYING: CarLife 断开，清空媒体状态")
        sendStoppedSnapshot("CarLife 断开")
    }

    /** 主动发一次停止空态（可给"退出 CarLife/切回桌面"场景用）。 */
    fun requestStoppedSnapshot() {
        sendStoppedSnapshot("主动请求")
    }

    /**
     * 编码 0x5001 **参数体**（不含 `0x4040 | len | msgId` CSM 帧头；[VirtualIap2Link.sendNowPlaying]
     * 会自己补帧头，不能把完整帧再封一次）。
     *
     * @param selection 车机 0x5000 订阅的属性表；null = 未提供筛选，按默认全字段发送。
     */
    fun encodeBody(
        track: Track,
        selection: NowPlayingSubscription? = null,
    ): ByteArray {
        val mediaAllowed = selection?.mediaAttributes
        val playbackAllowed = selection?.playbackAttributes
        val mediaFields = ArrayList<ByteArray>(5)
        val playbackFields = ArrayList<ByteArray>(6)
        if (allowed(mediaAllowed, MEDIA_TITLE)) mediaFields += Iap2Csm.string(MEDIA_TITLE, track.title.orEmpty())
        if (allowed(mediaAllowed, MEDIA_DURATION)) {
            mediaFields += Iap2Csm.u32(MEDIA_DURATION, track.durationMs.toLong().coerceAtLeast(0L))
        }
        if (allowed(mediaAllowed, MEDIA_ALBUM)) mediaFields += Iap2Csm.string(MEDIA_ALBUM, track.album.orEmpty())
        if (allowed(mediaAllowed, MEDIA_ARTIST)) mediaFields += Iap2Csm.string(MEDIA_ARTIST, track.artist.orEmpty())
        track.artworkTransferId
            ?.takeIf { allowed(mediaAllowed, MEDIA_ARTWORK_TRANSFER_ID) }
            ?.let { mediaFields += Iap2Csm.u8(MEDIA_ARTWORK_TRANSFER_ID, it) }
        if (allowed(playbackAllowed, PLAYBACK_STATUS)) playbackFields += Iap2Csm.u8(PLAYBACK_STATUS, track.status)
        if (allowed(playbackAllowed, PLAYBACK_ELAPSED)) {
            playbackFields += Iap2Csm.u32(PLAYBACK_ELAPSED, track.elapsedMs.toLong().coerceAtLeast(0L))
        }
        if (allowed(playbackAllowed, PLAYBACK_SHUFFLE)) playbackFields += Iap2Csm.u8(PLAYBACK_SHUFFLE, track.shuffleMode)
        if (allowed(playbackAllowed, PLAYBACK_REPEAT)) playbackFields += Iap2Csm.u8(PLAYBACK_REPEAT, track.repeatMode)
        if (allowed(playbackAllowed, PLAYBACK_APP_NAME)) {
            playbackFields += Iap2Csm.string(PLAYBACK_APP_NAME, track.appName.orEmpty())
        }
        track.playbackSpeed
            ?.takeIf { allowed(playbackAllowed, PLAYBACK_SPEED) }
            ?.let { playbackFields += Iap2Csm.u16(PLAYBACK_SPEED, it) }
        val out = ByteArrayOutputStream()
        if (mediaFields.isNotEmpty()) out.write(Iap2Csm.param(GROUP_MEDIA_ITEM, concat(mediaFields)))
        if (playbackFields.isNotEmpty()) out.write(Iap2Csm.param(GROUP_PLAYBACK, concat(playbackFields)))
        return out.toByteArray()
    }

    // ---- 内部 ----

    private fun ensureStarted() {
        synchronized(lock) {
            installHookLocked()
        }
    }

    private fun installHookLocked() {
        if (hookInstalled) return
        hookInstalled = true
        VirtualIap2Link.onNowPlayingSubscription = subscriptionHook
    }

    private fun onSubscriptionChanged(subscribed: Boolean) {
        nowPlayingLog("NOWPLAYING: 车机 0x5000 订阅状态 = $subscribed")
        if (!subscribed) return
        // 订阅到达后立刻补一次当前快照（没有媒体信息时按"空数据不发"跳过，只留日志）。
        synchronized(lock) { lastSentKey = "" }
        sendSnapshot(force = true)
    }

    private fun sendSnapshot(force: Boolean) {
        val link = VirtualIap2Link.active
        if (link == null) {
            logSkip("无 AirPlay 虚拟 iAP2 链路（等 AirPlay 会话建立）")
            return
        }
        if (!link.nowPlayingSubscribed) {
            logSkip("车机未订阅 0x5000")
            return
        }
        val track = synchronized(lock) { currentTrack }
        if (track == null) {
            logSkip("暂无媒体信息（空数据不发）")
            return
        }
        val body = encodeBody(track, link.nowPlayingSelection)
        if (body.isEmpty()) {
            logSkip("订阅属性表为空，没有可发字段")
            return
        }
        val key = trackKey(track)
        val shouldSend =
            synchronized(lock) {
                val now = System.currentTimeMillis()
                when {
                    force -> true
                    now - lastSendAt < MIN_SEND_INTERVAL_MS -> false
                    key == lastSentKey -> false
                    else -> true
                }
            }
        if (!shouldSend) return
        val ok = link.sendNowPlaying(body)
        synchronized(lock) {
            lastSendAt = System.currentTimeMillis()
            if (ok) {
                lastSentKey = key
                sentCount++
            }
        }
        nowPlayingLog(
            "NOWPLAYING: 0x5001 ${if (ok) "已发" else "发送失败"} title=${track.title} " +
                "artist=${track.artist} album=${track.album} dur=${track.durationMs}ms pos=${track.elapsedMs}ms " +
                "status=${track.status} artwork=${track.artworkTransferId ?: "无"} " +
                "fields=${describeBody(body)} bytes=${body.size}",
        )
    }

    private fun sendStoppedSnapshot(reason: String) {
        val link = VirtualIap2Link.active
        if (link == null) {
            logSkip("无 AirPlay 虚拟 iAP2 链路，跳过停止空态（$reason）")
            return
        }
        if (!link.nowPlayingSubscribed) {
            logSkip("车机未订阅 0x5000，跳过停止空态（$reason）")
            return
        }
        val body = encodeBody(emptyTrack, link.nowPlayingSelection)
        if (body.isEmpty()) {
            logSkip("订阅属性表为空，跳过停止空态（$reason）")
            return
        }
        val ok = link.sendNowPlaying(body)
        synchronized(lock) {
            lastSendAt = System.currentTimeMillis()
            lastSentKey = trackKey(emptyTrack)
        }
        nowPlayingLog(
            "NOWPLAYING: 0x5001 空态（$reason）${if (ok) "已发" else "发送失败"} bytes=${body.size}",
        )
    }

    private fun scheduleArtworkLocked(
        raw: ByteArray,
        signature: Int,
    ) {
        val worker = ensureExecutorLocked()
        val generation = ++artworkGeneration
        worker.execute {
            val prepared = runCatching { prepareArtwork(raw) }.getOrNull()
            synchronized(lock) {
                if (generation != artworkGeneration) return@synchronized
                if (prepared == null) {
                    preparedArtwork = null
                    nowPlayingLog("NOWPLAYING: 封面解码/压缩失败（源 ${raw.size}B）→ 0x5001 不发 field26")
                    return@synchronized
                }
                preparedArtwork = prepared
                prepared.transferId?.let { transferId ->
                    currentTrack = currentTrack?.copy(artworkTransferId = transferId)
                }
                nowPlayingLog(
                    "NOWPLAYING: 封面 ${prepared.width}x${prepared.height} JPEG ${prepared.jpeg.size}B" +
                        "（源 ${raw.size}B，signature=${signature.toString(16)}）" +
                        " transferID=${prepared.transferId ?: "无（k1 未接线，不发 field26）"}",
                )
            }
        }
    }

    private fun ensureExecutorLocked(): ExecutorService =
        executor
            ?: Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "carlife-artwork").apply { isDaemon = true }
                }.also { executor = it }

    /**
     * 解码 CarLife 封面 → 居中裁方 → 768×768 JPEG(85)。
     * CarLife 0x10035 的 albumArt 是完整图片字节（真机常见 JPEG/PNG），解码失败返回 null。
     */
    private fun prepareArtwork(raw: ByteArray): PreparedArtwork? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight) }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2
        val square = Bitmap.createBitmap(decoded, left, top, side, side)
        val scaled = square.scale(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
        val jpeg =
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
                out.toByteArray()
            }
        if (jpeg.isEmpty()) return null
        return PreparedArtwork(jpeg, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, startArtworkTransfer(jpeg))
    }

    /**
     * 封面 file-transfer 占位（测试版）。
     *
     * 封面**不进 0x5001 消息体**（官方规范：0x5001 只带 u8 artworkTransferID）；图片走 iAP2
     * file-transfer 会话（SETUP/START/DATA/SUCCESS）。会话号 k1 由车机动态给（AirPlay /info 或
     * 0x4301 无线组，真车可能不是 12，绝不能硬编码），本版本还没接线；且这些文件传输消息的
     * 消息号/时序没有真车证据（历史参考实现 CarLifeMedia.java 也没实现，只把 albumArt 画在手机本地媒体卡）。
     * 因此这里只缓存 768×768 JPEG + 打日志，返回 null → 0x5001 不带 field 26（失败即退化）。
     * 后续接入点：拿到 k1 后在这里实现 file-transfer，成功后再返回 artworkTransferID。
     */
    private fun startArtworkTransfer(jpeg: ByteArray): Int? {
        nowPlayingLog(
            "NOWPLAYING: artwork file-transfer 未接线（k1 会话号需动态取；SETUP/START/DATA/SUCCESS 待实车）" +
                "——JPEG ${jpeg.size}B 已缓存，本次 0x5001 不发 field26",
        )
        return null
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= ARTWORK_SIZE_PX) sample *= 2
        return sample
    }

    private fun artworkSignatureOf(raw: ByteArray): Int {
        var signature = raw.size
        for (index in 0 until minOf(raw.size, 32)) {
            signature = signature * 31 + (raw[index].toInt() and 0xFF)
        }
        return signature
    }

    private fun trackKey(track: Track): String =
        listOf(
            track.title.orEmpty(),
            track.artist.orEmpty(),
            track.album.orEmpty(),
            track.durationMs.toString(),
            track.status.toString(),
            track.artworkTransferId?.toString() ?: "-",
            (track.elapsedMs / 1_000).toString(),
        ).joinToString("\u0001")

    private fun allowed(
        selected: Set<Int>?,
        id: Int,
    ): Boolean = selected == null || id in selected

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun describeBody(body: ByteArray): String =
        Iap2Csm.parseParams(body).joinToString(" ") { group ->
            val children = Iap2Csm.parseParams(group.value).joinToString(",") { "0x%02x".format(it.id) }
            "g0x%04x[%s]".format(group.id, children)
        }

    private fun logSkip(reason: String) {
        lastSkipReason = reason
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastSkipLogAt < SKIP_LOG_INTERVAL_MS) return
            lastSkipLogAt = now
        }
        nowPlayingLog("NOWPLAYING: 暂不发 0x5001——$reason（同类提示 10 秒最多一行）")
    }

    private fun nowPlayingLog(message: String) {
        log?.invoke(message)
    }
}
