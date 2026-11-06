package io.github.shaonialife.buicklinkplus.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.graphics.scale
import androidx.core.net.toUri
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsDiagnostics
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsParser
import io.github.shaonialife.buicklinkplus.media.lyrics.PlayerLyricsIdentity
import io.github.shaonialife.buicklinkplus.media.lyrics.PlayerLyricsNotification
import io.github.shaonialife.buicklinkplus.media.lyrics.PlayerLyricsPolicy
import io.github.shaonialife.buicklinkplus.media.lyrics.PlayerLyricsState

/**
 * 通知监听服务，提供活跃媒体会话、曲目信息和播放状态
 * 缺少通知使用权时可能只能读到播放状态，授权由 [NotificationAccess] 处理
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun componentName(context: android.content.Context): ComponentName = ComponentName(context, MediaListenerService::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 已连上即代表系统认可本应用的通知使用权（此时 metadata 才会可读）。
        // 两个状态都置位：NotificationAccess 供「是否要再试授权」判断，
        // MediaSessionsState 供 UI 文案区分「没在播放」与「读不到信息」。
        NotificationAccess.markGranted()
        NotificationAccess.markConnected()
        MediaSessionsState.accessGranted = true
        connected = true
        try {
            manager.removeOnActiveSessionsChangedListener(sessionListener)
            manager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName(this),
            )
            MediaSessionsState.resyncHook = resync
            MediaSessionsState.controlHook = controls
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.removeCallbacks(periodicRefresh)
            mainHandler.post(periodicRefresh)
        } catch (_: SecurityException) {
            // 通知使用权被撤销：静默（授权后系统会重新绑定本服务）
            MediaSessionsState.accessGranted = false
            releaseSessions()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationAccess.markDisconnected()
        // 断开时清空媒体状态，避免卡片继续显示旧歌名
        releaseSessions()
        NavGuidance.clearAll()
    }

    /** 仅处理当前播放器明确共享的歌词字段或带专用标志的歌词通知 */
    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        if (!connected) return
        // 导航通知独立于媒体会话：解析出简约指引后即返回，不进歌词链路
        if (NavGuidance.handles(posted.packageName)) {
            runCatching { NavGuidance.fromNotification(posted.key, posted.notification) }
            return
        }
        if (posted.packageName != selected?.packageName || !PlayerLyricsPolicy.supports(posted.packageName)) return
        val receivedAt = android.os.SystemClock.elapsedRealtime()
        val session = selectedGeneration
        mainHandler.post {
            if (!connected || session != selectedGeneration) return@post
            // 先读取实际会话，避免上一首排队中的通知被归给新歌曲
            refresh()
            if (session != selectedGeneration) return@post
            val target = selected ?: return@post
            if (posted.packageName != target.packageName) return@post
            runCatching {
                if (!reportedLyricsNotification) {
                    reportedLyricsNotification = true
                    val value = posted.notification
                    val explicit =
                        value.extras
                            ?.keySet()
                            ?.take(128)
                            ?.any(LyricsParser::isLyricsKey) == true
                    val ticker = PlayerLyricsPolicy.isLyricTicker(posted.packageName, value.flags)
                    LyricsDiagnostics.record(
                        "LYRICS: 来源=播放器通知 阶段=能力快照 目标=${posted.packageName} 明确字段=$explicit 歌词标志=$ticker",
                    )
                }
                if (reportedLyricsSchemas.size < 4) {
                    val schema = PlayerLyricsNotification.schema(posted.notification)
                    if (reportedLyricsSchemas.add(schema)) {
                        LyricsDiagnostics.record("LYRICS: 来源=播放器通知 阶段=结构快照 目标=${posted.packageName} $schema")
                    }
                }
                val value =
                    PlayerLyricsNotification.read(
                        posted.packageName,
                        posted.notification,
                        target.sessionToken,
                        baseNowPlaying?.title,
                        baseNowPlaying?.artist,
                    ) ?: return@runCatching
                val hadLyrics = playerLyrics.current(receivedAt, baseNowPlaying?.playing == true) != null
                val accepted =
                    if (value.text.isBlank()) {
                        playerLyrics.remove(posted.key, posted.postTime)
                    } else {
                        playerLyrics.accept(
                            posted.packageName,
                            posted.key,
                            posted.postTime,
                            receivedAt,
                            value.text,
                            value.live,
                            value.titleHint,
                        )
                    }
                if (accepted) {
                    if (!hadLyrics && value.text.isNotBlank()) {
                        LyricsDiagnostics.record(
                            "LYRICS: 来源=播放器通知 阶段=接收共享 结果=${if (value.live) "实时句" else "全文"}",
                        )
                    }
                    publishPlayerLyrics()
                }
            }
        }
    }

    /** 原通知撤销后清理对应歌词，旧移除回调不能清掉后来发布的内容 */
    override fun onNotificationRemoved(notification: StatusBarNotification?) {
        val removed = notification ?: return
        if (!connected) return
        if (NavGuidance.handles(removed.packageName)) {
            NavGuidance.clear(removed.key)
            return
        }
        val session = selectedGeneration
        mainHandler.post {
            if (connected && session == selectedGeneration && playerLyrics.remove(removed.key, removed.postTime)) refresh()
        }
    }

    override fun onDestroy() {
        releaseSessions()
        artExecutor.shutdownNow()
        super.onDestroy()
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** 已注册的回调随会话移除及监听服务断开注销 */
    private val watched =
        linkedMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var selected: android.media.session.MediaController? = null
    private var selectedGeneration = 0L
    private val playerLyrics = PlayerLyricsState()
    private var baseNowPlaying: MediaSessionsState.NowPlaying? = null
    private var reportedLyricsNotification = false
    private val reportedLyricsSchemas = mutableSetOf<String>()

    @Volatile private var connected = false
    private val controlReadback = MediaControlReadback<MediaSession.Token>()
    private val resync: () -> Unit = { mainHandler.post { if (connected) refresh() } }
    private val controls: (Int, String) -> Boolean = { key, source ->
        connected && mainHandler.post { if (connected) control(key, source) }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 兜底轮询：部分播放器不触发回调/回调丢失时保证卡片信息更新。 */
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                if (!connected) return
                refresh()
                mainHandler.postDelayed(this, 1_000)
            }
        }

    /** 上次封面提取键（会话+歌名+封面 uri）——getBitmap 每次反序列化，1s 轮询不能重复做。 */
    private var lastArtKey: String? = null

    /** 封面位图缓存（键不变时复用同一实例，保证 NowPlaying 的 equals 判稳）。 */
    private var cachedArt: android.graphics.Bitmap? = null

    /** 封面为空时缓存 2 秒后重试，等待切歌后的完整元数据 */
    private var artEmptyAt = 0L

    /** 封面请求代次，隔离断开重连及同一曲目的迟到解码结果 */
    private var artGeneration = 0L

    /** 封面 uri 解码专用单线程（串行；主线程不能做 IO/解码）。 */
    private val artExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-art").apply { isDaemon = true }
        }

    private fun update(controllers: List<android.media.session.MediaController>) {
        if (!connected) return
        val tokens = controllers.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in watched) {
                val callback =
                    object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            if (!connected || watched[controller.sessionToken]?.first !== controller) return
                            // 排队回调只作为重新读取信号，旧事件值不能覆盖新控制后的真实状态
                            val actualState = controller.playbackState
                            val observation =
                                controlReadback.observe(
                                    controlReadback.generation,
                                    controller.sessionToken,
                                    actualState?.let { it.state == PlaybackState.STATE_PLAYING },
                                )
                            if (observation != null) {
                                VirtualLauncher.logSink?.invoke(
                                    "MEDIA-CALLBACK: 代次=${controlReadback.generation} " +
                                        "Token=${controller.sessionToken.hashCode()} 状态=${actualState?.state} 观测=$observation",
                                )
                            }
                            refresh()
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            if (connected && watched[controller.sessionToken]?.first === controller) refresh()
                        }

                        /** 播放器通过公开扩展字段补发歌词时重新读取当前会话 */
                        override fun onExtrasChanged(extras: android.os.Bundle?) {
                            if (connected && watched[controller.sessionToken]?.first === controller) refresh()
                        }
                    }
                controller.registerCallback(callback, mainHandler)
                watched[controller.sessionToken] = controller to callback
            }
        }
        refresh()
    }

    /** 优先正在播放的会话，暂停后保留原控制目标，避免列表重排切错播放器 */
    private fun refresh() {
        if (!connected) return
        val controllers =
            try {
                manager.getActiveSessions(componentName(this))
            } catch (_: SecurityException) {
                emptyList()
            }
        val picked =
            MediaSessionPolicy
                .choose(
                    controllers.map {
                        MediaSessionPolicy.Candidate(
                            it.sessionToken,
                            it.playbackState?.state == PlaybackState.STATE_PLAYING,
                            it.metadata != null,
                        )
                    },
                    selected?.sessionToken,
                )?.let(controllers::get)
        if (selected?.sessionToken != picked?.sessionToken) selectedGeneration = MediaSessionsState.nextSessionGeneration()
        selected = picked
        val now =
            picked?.let { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                MediaSessionsState.NowPlaying(
                    title = title,
                    artist =
                        meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    playing = playing,
                    art = artFor(controller, meta, title),
                    pkg = controller.packageName,
                    positionMs = state?.position ?: 0L,
                    playbackSpeed = if (playing) state.playbackSpeed else 0f,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    anchoredAtMs = state?.lastPositionUpdateTime ?: 0L,
                    sampledAtMs = android.os.SystemClock.elapsedRealtime(),
                    positionKnown = state != null && state.position >= 0,
                    album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    mediaId = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    sessionGeneration = selectedGeneration,
                    lyrics = if (PlayerLyricsPolicy.supports(controller.packageName)) explicitLyrics(controller, meta, state) else null,
                )
            }
        val lyricsIdentityChanged =
            playerLyrics.select(
                now?.let {
                    PlayerLyricsIdentity(
                        it.sessionGeneration,
                        it.pkg.orEmpty(),
                        it.mediaId,
                        it.title.orEmpty(),
                        it.artist.orEmpty(),
                        it.album.orEmpty(),
                    )
                },
                System.currentTimeMillis(),
            )
        if (lyricsIdentityChanged) {
            reportedLyricsNotification = false
            reportedLyricsSchemas.clear()
            if (now != null) {
                LyricsDiagnostics.record(
                    "LYRICS: 来源=媒体会话 阶段=能力快照 目标=${now.pkg} 明确全文=${!now.lyrics.isNullOrBlank()}",
                )
            }
        }
        baseNowPlaying = now
        publishPlayerLyrics()
    }

    /** 原生实时句不改写歌曲标题、播放位置或媒体控制目标 */
    private fun publishPlayerLyrics() {
        val now = baseNowPlaying
        val shared = playerLyrics.current(android.os.SystemClock.elapsedRealtime(), now?.playing == true)
        MediaSessionsState.publish(
            now?.copy(
                lyrics = now.lyrics ?: playerLyrics.fullText?.text,
                lyricLine = shared?.takeIf { it.live }?.text,
            ),
        )
    }

    /** 只接受明确的歌词字段 */
    private fun explicitLyrics(
        controller: MediaController,
        metadata: MediaMetadata?,
        state: PlaybackState?,
    ): String? =
        runCatching {
            val candidates = mutableListOf<Pair<String, CharSequence>>()
            metadata?.let { meta ->
                meta.keySet().take(128).filter(LyricsParser::isLyricsKey).forEach { key ->
                    runCatching { meta.getText(key) }.getOrNull()?.let { candidates.add(key to it) }
                }
            }
            for (extras in listOf(controller.extras, state?.extras)) {
                extras?.keySet()?.take(128)?.filter(LyricsParser::isLyricsKey)?.forEach { key ->
                    runCatching { extras.getCharSequence(key) }.getOrNull()?.let { candidates.add(key to it) }
                }
            }
            candidates
                .sortedBy {
                    val name =
                        it.first
                            .substringAfterLast('.')
                            .substringAfterLast(':')
                            .lowercase(java.util.Locale.ROOT)
                    if (name.startsWith("synced") || name == "lrc") 0 else 1
                }.firstOrNull { (_, text) -> text.isNotBlank() && text.length <= LyricsParser.MAX_BYTES }
                ?.second
                ?.toString()
        }.getOrNull()

    /**
     * 直接控制已选会话，不在暂停后交给系统重新猜测目标播放器
     */
    private fun control(
        key: Int,
        source: String,
    ) {
        MediaSessionsState.cancelFallback()
        refresh()
        val controller = selected
        val generation = controlReadback.begin(controller?.sessionToken)
        if (controller == null) {
            MediaSessionsState.systemMediaKey(this, key, "$source 无可控制会话")
            return
        }
        val state = controller.playbackState
        val actualKey =
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (state?.state == PlaybackState.STATE_PLAYING) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
            } else {
                key
            }
        val action =
            when (actualKey) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackState.ACTION_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackState.ACTION_PAUSE
                KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                KeyEvent.KEYCODE_MEDIA_STOP -> PlaybackState.ACTION_STOP
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlaybackState.ACTION_FAST_FORWARD
                KeyEvent.KEYCODE_MEDIA_REWIND -> PlaybackState.ACTION_REWIND
                else -> 0L
            }
        val directed = action != 0L && ((state?.actions ?: 0L) and action) != 0L
        val result =
            runCatching {
                if (directed) {
                    val transport = controller.transportControls
                    when (actualKey) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> transport.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> transport.pause()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> transport.skipToNext()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transport.skipToPrevious()
                        KeyEvent.KEYCODE_MEDIA_STOP -> transport.stop()
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> transport.fastForward()
                        KeyEvent.KEYCODE_MEDIA_REWIND -> transport.rewind()
                    }
                    true
                } else {
                    val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, actualKey))
                    val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, actualKey))
                    down && up
                }
            }
        VirtualLauncher.logSink?.invoke(
            "MEDIA-CONTROL: 代次=$generation Token=${controller.sessionToken.hashCode()} 来源=$source " +
                "目标=${controller.packageName} key=$actualKey 原状态=${state?.state} " +
                "定向传输=$directed 已提交=${result.getOrDefault(false)} 错误=${result.exceptionOrNull()?.message}",
        )
        for (delay in listOf(400L, 1_500L, 3_500L)) {
            mainHandler.postDelayed({
                if (connected && generation == controlReadback.generation && selected?.sessionToken == controller.sessionToken) {
                    refresh()
                    if (selected?.sessionToken != controller.sessionToken) return@postDelayed
                    val observed = controller.playbackState?.state
                    val observation =
                        controlReadback.observe(
                            generation,
                            controller.sessionToken,
                            observed?.let { it == PlaybackState.STATE_PLAYING },
                        )
                    val audio = getSystemService(android.media.AudioManager::class.java)
                    val routes =
                        runCatching {
                            audio
                                ?.getDevices(
                                    android.media.AudioManager.GET_DEVICES_OUTPUTS,
                                )?.map { it.type }
                        }.getOrNull()
                    VirtualLauncher.logSink?.invoke(
                        "MEDIA-CONTROL: 代次=$generation Token=${controller.sessionToken.hashCode()} " +
                            "目标=${controller.packageName} key=$actualKey 延时=${delay}ms 回读状态=$observed 观测=$observation 输出设备=$routes",
                    )
                    if (actualKey == KeyEvent.KEYCODE_MEDIA_PLAY && observed != PlaybackState.STATE_PLAYING) {
                        VirtualLauncher.recordInteractionDiagnostics("播放请求后未确认持续播放，可能仍在缓冲或被音频焦点限制")
                    }
                }
            }, delay)
        }
    }

    /**
     * 释放回调及钩子，旧服务退出不能清除新服务的控制入口
     */
    private fun releaseSessions() {
        connected = false
        controlReadback.begin(null)
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        watched.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        watched.clear()
        selected = null
        baseNowPlaying = null
        playerLyrics.clear()
        reportedLyricsNotification = false
        reportedLyricsSchemas.clear()
        if (MediaSessionsState.controlHook === controls) {
            MediaSessionsState.controlHook = null
            MediaSessionsState.cancelFallback()
        }
        if (MediaSessionsState.resyncHook === resync) {
            MediaSessionsState.resyncHook = null
            MediaSessionsState.accessGranted = false
            MediaSessionsState.publish(null)
        }
        lastArtKey = null
        cachedArt = null
        artGeneration++
    }

    /** 按会话、歌名和封面 URI 缓存封面，URI 图片在后台解码 */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
    ): android.graphics.Bitmap? {
        val artUri =
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "${controller.sessionToken}/$title/$artUri"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastArtKey) {
            if (cachedArt != null) return cachedArt
            // 键没变但封面为空：2 秒内不重试（getBitmap 有成本），超后重提取——
            // app 在切歌后补封面（bitmap 键从无到有）靠这条路兜住
            if (now - artEmptyAt < 2_000) return null
        }
        lastArtKey = key
        cachedArt = null
        val generation = ++artGeneration
        val direct =
            meta
                ?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            cachedArt = scaleArt(direct)
            return cachedArt
        }
        artEmptyAt = now
        if (artUri != null) {
            // 解码在专用线程；就绪后不主动 refresh——periodicRefresh 下一轮（≤1s）拉取
            artExecutor.execute {
                val bmp =
                    runCatching {
                        val input =
                            if (artUri.startsWith("http")) {
                                java.net.URL(artUri).openStream()
                            } else {
                                contentResolver.openInputStream((artUri).toUri())
                            }
                        input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                val scaled = bmp?.let(::scaleArt)
                mainHandler.post { if (connected && key == lastArtKey && generation == artGeneration) cachedArt = scaled }
            }
        }
        return null
    }

    /** 封面缩到 ≤512px（卡片显示 ~120dp，控制内存）。 */
    private fun scaleArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 512) return src
        val sample =
            generateSequence(1) { it * 2 }.first { maxSide / it <= 512 }
        return src.scale(src.width / sample, src.height / sample, true)
    }
}

/** 全局「正在播放」状态（本进程内共享；GridActivity 音乐卡监听渲染）。 */
object MediaSessionsState {
    private val sessionGeneration =
        java.util.concurrent.atomic
            .AtomicLong()

    /** 服务重建后仍使用单调代次，避免新旧播放器身份相同而误用歌词 */
    internal fun nextSessionGeneration(): Long = sessionGeneration.incrementAndGet()

    private val fallbackGeneration =
        java.util.concurrent.atomic
            .AtomicLong()
    private val fallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile var controlNotice: String? = null
        private set

    /** 新控制或原服务退役时使所有旧结果提示失效 */
    internal fun cancelFallback() {
        fallbackGeneration.incrementAndGet()
        setControlNotice(null)
    }

    /** 状态提示单独通知，不修改播放数据 */
    private fun setControlNotice(value: String?) {
        if (controlNotice == value) return
        controlNotice = value
        listeners.forEach { runCatching { it() } }
    }

    @Volatile internal var controlHook: ((Int, String) -> Boolean)? = null

    /**
     * 音乐卡及车机媒体键共用定向入口，监听未就绪时才使用系统媒体键
     */
    fun dispatchMediaKey(
        context: android.content.Context,
        key: Int,
        source: String,
    ) {
        cancelFallback()
        if (controlHook?.invoke(key, source) == true) return
        systemMediaKey(context, key, "$source 监听未就绪")
    }

    /** 无媒体会话时使用系统按键路由，只记录提交结果 */
    internal fun systemMediaKey(
        context: android.content.Context,
        key: Int,
        source: String,
    ) {
        val request = fallbackGeneration.incrementAndGet()
        val issuedAt = android.os.SystemClock.elapsedRealtime()
        setControlNotice("媒体键已提交，正在等待播放器状态")
        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }.onFailure { VirtualLauncher.logSink?.invoke("MEDIA-CONTROL: 系统媒体键失败：${it.message}") }
        VirtualLauncher.logSink?.invoke("MEDIA-CONTROL: 来源=$source 系统媒体键=$key，尚未确认播放结果")
        for (delay in listOf(1_500L, 3_500L)) {
            fallbackHandler.postDelayed({
                if (fallbackGeneration.get() != request) return@postDelayed
                requestResync()
                val state = nowPlaying
                val expected =
                    when (key) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> true
                        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> false
                        else -> null
                    }
                val notice =
                    MediaFallbackPolicy.message(
                        state != null,
                        (state?.sampledAtMs ?: 0) >= issuedAt,
                        expected,
                        state?.playing == true,
                    )
                setControlNotice(notice)
                VirtualLauncher.logSink?.invoke("MEDIA-CONTROL: 系统兜底回读 请求=$request 延时=${delay}ms $notice")
            }, delay)
        }
    }

    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val art: android.graphics.Bitmap? = null,
        /** 媒体会话所属应用，点击歌名可拉回播放器 */
        val pkg: String? = null,
        /** 采样时刻的播放位置（ms）。 */
        val positionMs: Long = 0L,
        /** 播放速度（暂停时 0——不外推）。 */
        val playbackSpeed: Float = 1f,
        val durationMs: Long = 0L,
        /** 播放器最后一次更新位置时的经过时间，不能用轮询时间替代 */
        val anchoredAtMs: Long = 0L,
        /** 本应用读取状态的时间，仅用于确认控制后是否发生新采样 */
        val sampledAtMs: Long = 0L,
        val positionKnown: Boolean = true,
        val album: String? = null,
        val mediaId: String? = null,
        val sessionGeneration: Long = 0L,
        val lyrics: String? = null,
        /** 播放器共享的实时句，单独展示 */
        val lyricLine: String? = null,
    ) {
        /** 渲染时刻的推算播放位置（播放中按速度外推，钳制在时长内）。 */
        fun displayPositionAt(elapsedRealtimeMs: Long): Long =
            MediaPosition.at(positionMs, anchoredAtMs, playbackSpeed, playing, durationMs, elapsedRealtimeMs) ?: 0L
    }

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    /** 检查通知使用权，供界面区分未播放和无权读取媒体信息 */
    @Volatile
    var accessGranted: Boolean = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        val old = nowPlaying
        nowPlaying = value
        // 位置每秒都在变（1s 兜底轮询）——日志只看内容键，避免刷屏；
        // listeners 仍每次通知（UI 据此刷新进度文本）
        val contentChanged =
            old?.title != value?.title ||
                old?.artist != value?.artist ||
                old?.pkg != value?.pkg ||
                old?.playing != value?.playing ||
                old?.art !== value?.art ||
                old?.album != value?.album ||
                old?.mediaId != value?.mediaId ||
                old?.durationMs != value?.durationMs ||
                old?.sessionGeneration != value?.sessionGeneration
        val lyricsChanged = old?.lyrics != value?.lyrics || old?.lyricLine != value?.lyricLine
        if (contentChanged) {
            VirtualLauncher.logSink?.invoke(
                "MEDIA: " + (
                    value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing} pkg=${it.pkg}（会话回读）" }
                        ?: "无活跃会话"
                ),
            )
        }
        // 内容未变（仅位置锚点随轮询更新）不通知：进度走秒由 UI 侧 mediaTick 每秒自刷新
        // ——此前 anchoredAtMs 恒变导致每秒全量通知监听者，与 mediaTick 形成双路重复渲染
        if (!contentChanged && !lyricsChanged) return
        listeners.forEach { runCatching { it() } }
    }

    /** 重同步钩子，服务连接后供界面主动拉取真实状态 */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}
