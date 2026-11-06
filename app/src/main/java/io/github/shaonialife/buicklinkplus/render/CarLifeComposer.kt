package io.github.shaonialife.buicklinkplus.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P1B CarLife 合成器：壁纸背景 + 安全区 contain + 圆角裁剪（对应原版龟壳 d9 的合成语义）。
 *
 * 输入是 CarLife 解码器输出 Surface（[inputSurface]，内部用 SurfaceTexture 转成 OES 纹理）；
 * 输出是 VideoSender 的编码器输入 Surface（构造参数 [output]，EGL window surface）。
 *
 * 每帧绘制顺序（整幅画布 = 编码器 profile 尺寸）：
 * 1. 纯色底（0xFF161326，即 link_plus 默认壁纸底色）清屏；
 * 2. 壁纸按 cover 居中铺满整幅画布（可选，见 [setWallpaper]；不设置时只显示纯色底）；
 * 3. CarLife 视频按码流宽高比 **contain** 在车机安全区 [DisplayGeometry.content] 内，不变形，
 *    左右/上下留壁纸边（对应原版 `CarLifeBridge.publishFit` 的等比放入算法）；
 * 4. 视频四边形按 [cornerRadiusPx] 做圆角裁剪（shader 内 SDF 抗锯齿，默认 17px 可调）；
 * 5. `eglSwapBuffers` 提交给编码器。
 *
 * 线程模型：自有 GL 线程 `carlife-composer`。SurfaceTexture 有新帧时立即合成；静止时按
 * [targetFps] 上限继续画心跳帧，避免编码器断粮。[updateLayout] / [setWallpaper] /
 * [targetFps] / [cornerRadiusPx] 可在任意线程调用，实际 GL 操作只在 GL 线程执行。
 *
 * 与原版 d9 字段语义对应：`hyL/hyR/hyB/hyT` = contain 后视频窗格在画布窗口坐标（左下原点）的
 * 左/右/下/上边界；`hyRad` = 圆角半径（画布像素）。本类用 [paneInCanvas] 暴露同一窗格
 * （左上原点），P3 触摸映射可直接复用。
 *
 * ### 接入步骤（Lead 后续统一接线，本文件当前可先不被调用）
 * 在 `capture/CarLifeScreenSource.attach(...)` 内：
 * 1. 创建：`composer = CarLifeComposer(surface, log = { line -> log(line) })`；
 * 2. 先布局再启动：
 *    `composer.updateLayout(geometry, profile.width, profile.height)`（码流尺寸未知时先用 profile，
 *    收到 SPS 实测尺寸后再调一次）；
 *    `composer.targetFps = profile.fps`；
 *    `if (!composer.start()) { 回退到 P1A 直出（decoder 直接输出到 attach 的 Surface） }`；
 * 3. 解码器输出改到合成器输入：`CarLifeVideoDecoder(w, h, fps, composer.inputSurface(), listener)`；
 * 4. 壁纸（可选）：`CarLifeWallpaperSource(context, log)` → `start(canvasW, canvasH) { composer.setWallpaper(it) }`；
 * 5. SPS 尺寸变化：先 `composer.updateLayout(geometry, newW, newH)`，再重建解码器；
 * 6. detach 顺序（重要）：`client.stop()` → 旧 decoder `stopAndWait()` → `composer.stop()` →
 *    最后才允许 VideoSender 释放编码器 Surface；顺序反了会让解码/合成线程操作已释放的 Surface。
 */
class CarLifeComposer(
    private val output: Surface,
    private val log: (String) -> Unit = {},
) {
    /** 合成目标帧率（新帧与静止心跳共用上限），运行中可改；1..120。 */
    @Volatile
    var targetFps: Int = 30
        set(value) {
            field = value.coerceIn(1, 120)
            wakeRenderer()
        }

    /**
     * 视频圆角半径（画布像素），0 = 直角。原版 d9 的 `hyRad` 约 16~17px，
     * 默认 [DEFAULT_CORNER_RADIUS_PX]；运行中可改。
     */
    @Volatile
    var cornerRadiusPx: Float = DEFAULT_CORNER_RADIUS_PX
        set(value) {
            field = value.coerceAtLeast(0f)
            sceneDirty.set(true)
            wakeRenderer()
        }

    /** 已提交到编码器 Surface 的合成帧数（诊断/验收日志用）。 */
    @Volatile
    var composedFrames: Long = 0L
        private set

    val isRunning: Boolean get() = running.get()

    private val running = AtomicBoolean(false)
    private val sceneDirty = AtomicBoolean(true)
    private val frameAvailable = AtomicBoolean(false)

    /**
     * 待上传壁纸（null 请求对象 = 不需要动作；[WallpaperRequest.bitmap] 为 null = 恢复纯色底）。
     * 位图所有权归调用方，本类上传后不 recycle。
     */
    private val pendingWallpaper = AtomicReference<WallpaperRequest?>(null)

    /** 解码器输出 Surface（SurfaceTexture 生产端）；start 成功后非空，stop 后清空。 */
    private val inputSurfaceRef = AtomicReference<Surface?>(null)

    /** 布局（画布/安全区/码流尺寸）快照；updateLayout 原子替换，GL 线程每次合成前读最新值。 */
    private val layoutLock = Any()
    private var layout: Layout? = null

    /** 供 updateLayout 从任意线程同步 SurfaceTexture 缓冲尺寸；生命周期由 GL 线程管理。 */
    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    private val lifecycleLock = Any()
    private var glThread: Thread? = null

    @Volatile
    private var startupError: String? = null

    // 此监视器需要 wait/notify，保留 Java Object 的显式接口
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val renderWakeup = Object()
    private var renderSignal = 0L

    /** 唤醒 GL 线程重算等待时间（只置信号，不平移工作到调用线程）。 */
    private fun wakeRenderer() {
        synchronized(renderWakeup) {
            renderSignal++
            renderWakeup.notifyAll()
        }
    }

    // ---- 对外配置/查询 ----

    /**
     * 更新画布、安全区与码流尺寸（任意线程可调用，start 前必须先调一次）。
     *
     * @param geometry 会话显示几何：`canvasWidth/Height` 应等于编码器 profile 尺寸（即 [output]
     *   Surface 尺寸）；`content` 是车机安全区（画布坐标，左上原点）。
     * @param sourceW 解码器输出宽度（SPS 实测值；未知传 0 表示先铺满安全区）
     * @param sourceH 解码器输出高度
     */
    fun updateLayout(
        geometry: DisplayGeometry,
        sourceW: Int,
        sourceH: Int,
    ) {
        val sourceWidth = sourceW.coerceAtLeast(0)
        val sourceHeight = sourceH.coerceAtLeast(0)
        val content = geometry.content
        val pane = containPane(content, sourceWidth, sourceHeight)
        val snapshot = Layout(geometry.canvasWidth, geometry.canvasHeight, content, sourceWidth, sourceHeight, pane)
        val previous =
            synchronized(layoutLock) {
                val old = layout
                layout = snapshot
                old
            }
        val sourceChanged =
            previous == null || previous.sourceWidth != sourceWidth || previous.sourceHeight != sourceHeight
        // SurfaceTexture 缓冲尺寸必须跟码流一致，否则解码帧会被 Surface 额外缩放一次
        if (sourceChanged && sourceWidth > 0 && sourceHeight > 0) {
            runCatching { surfaceTexture?.setDefaultBufferSize(sourceWidth, sourceHeight) }
        }
        if (previous != null && (previous.canvasWidth != snapshot.canvasWidth || previous.canvasHeight != snapshot.canvasHeight)) {
            log(
                "CARLIFE-COMPOSER: 画布尺寸变化 ${previous.canvasWidth}x${previous.canvasHeight} -> " +
                    "${snapshot.canvasWidth}x${snapshot.canvasHeight}（输出 Surface 尺寸固定，建议重建合成器）",
            )
        }
        log(
            "CARLIFE-COMPOSER: fit source=${sourceWidth}x$sourceHeight " +
                "canvas=${snapshot.canvasWidth}x${snapshot.canvasHeight} " +
                "content=${content.width}x${content.height}@${content.left},${content.top} " +
                "pane=${pane.width}x${pane.height}@${pane.left},${pane.top} radius=$cornerRadiusPx",
        )
        sceneDirty.set(true)
        wakeRenderer()
    }

    /** 当前 CarLife 视频窗格（画布坐标、左上原点）；未布局时为 null，供 P3 触摸映射/诊断。 */
    fun paneInCanvas(): PixelArea? = synchronized(layoutLock) { layout?.pane }

    /**
     * 设置壁纸（cover 居中铺满整幅画布）；传 null 恢复纯色底。任意线程可调用。
     *
     * 位图所有权归调用方，本类上传后**不会 recycle**；若传 HARDWARE 位图会先复制成软件位图。
     * 建议直接传 `WallpaperStore.aspectFillCanvas(context, canvasW, canvasH)` 得到的画布尺寸位图
     * （见 [CarLifeWallpaperSource]），这样合成器不做二次缩放。
     */
    fun setWallpaper(bitmap: Bitmap?) {
        pendingWallpaper.set(WallpaperRequest(bitmap))
        sceneDirty.set(true)
        wakeRenderer()
    }

    /**
     * 启动 GL 渲染线程并创建解码器输入 Surface。
     *
     * 调用前必须先 [updateLayout]；最多等 [START_TIMEOUT_MS] 毫秒完成 EGL/纹理初始化。
     *
     * @return true = [inputSurface] 可用；false = 启动失败（已清理，可修正后重试）
     */
    fun start(): Boolean {
        if (!output.isValid) {
            log("CARLIFE-COMPOSER: 启动失败——编码器输出 Surface 无效")
            return false
        }
        val initial = synchronized(layoutLock) { layout }
        if (initial == null) {
            log("CARLIFE-COMPOSER: 启动失败——未调用 updateLayout")
            return false
        }
        synchronized(lifecycleLock) {
            if (running.get()) return true
        }
        val ready = CountDownLatch(1)
        val thread = Thread({ runGl(initial, ready) }, THREAD_NAME)
        thread.isDaemon = true
        synchronized(lifecycleLock) {
            if (running.get()) return true
            inputSurfaceRef.set(null)
            startupError = null
            composedFrames = 0L
            sceneDirty.set(true)
            frameAvailable.set(false)
            glThread = thread
            running.set(true)
        }
        thread.start()
        val readyInTime = runCatching { ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!readyInTime || !running.get() || inputSurfaceRef.get() == null) {
            log("CARLIFE-COMPOSER: 启动失败 - ${startupError ?: "等待 GL 初始化超时"}")
            stop()
            return false
        }
        log(
            "CARLIFE-COMPOSER: 已启动 canvas=${initial.canvasWidth}x${initial.canvasHeight} " +
                "input=${initial.sourceWidth}x${initial.sourceHeight} targetFps=$targetFps radius=$cornerRadiusPx",
        )
        return true
    }

    /**
     * 停止 GL 线程并释放解码器输入 Surface / SurfaceTexture / EGL 资源；幂等，可再次 [start]。
     * 不释放构造参数 [output]（归 VideoSender 所有）。
     */
    fun stop() {
        var thread: Thread? = null
        synchronized(lifecycleLock) {
            running.set(false)
            thread = glThread
            glThread = null
        }
        if (thread != null) {
            wakeRenderer()
            thread.interrupt()
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                log("CARLIFE-COMPOSER: GL 线程未在 ${STOP_JOIN_TIMEOUT_MS}ms 内退出")
            }
        }
        inputSurfaceRef.set(null)
        surfaceTexture = null
        if (thread != null) log("CARLIFE-COMPOSER: 已停止（合成 $composedFrames 帧）")
    }

    /** 解码器输出 Surface（SurfaceTexture 生产端）；start 成功后有效，stop 后失效。 */
    fun inputSurface(): Surface = inputSurfaceRef.get() ?: throw IllegalStateException("CarLifeComposer 未启动或已停止，inputSurface 不可用")

    // ---- 布局/contain 计算 ----

    /**
     * contain 算法与原版 `CarLifeBridge.publishFit` 一致：安全区内按码流宽高比等比放入并居中，
     * 剩余空间留给壁纸（不变形）。码流尺寸未知时铺满安全区。
     */
    private fun containPane(
        content: PixelArea,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PixelArea {
        if (sourceWidth <= 0 || sourceHeight <= 0 || content.width <= 0 || content.height <= 0) return content
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val contentAspect = content.width.toDouble() / content.height
        return if (contentAspect > sourceAspect) {
            val paneWidth = (content.height * sourceAspect).toInt().coerceIn(1, content.width)
            val left = content.left + (content.width - paneWidth) / 2
            PixelArea(left, content.top, left + paneWidth, content.bottom)
        } else {
            val paneHeight = (content.width / sourceAspect).toInt().coerceIn(1, content.height)
            val top = content.top + (content.height - paneHeight) / 2
            PixelArea(content.left, top, content.right, top + paneHeight)
        }
    }

    private class Layout(
        val canvasWidth: Int,
        val canvasHeight: Int,
        val content: PixelArea,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val pane: PixelArea,
    )

    private class WallpaperRequest(
        val bitmap: Bitmap?,
    )

    // ---- GL 线程 ----

    private fun runGl(
        initial: Layout,
        ready: CountDownLatch,
    ) {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var oesTexture = 0
        var wallpaperTexture = 0
        var wallpaperBitmapWidth = 0
        var wallpaperBitmapHeight = 0
        var oesProgram = 0
        var wallpaperProgram = 0
        var texture: SurfaceTexture? = null
        var inputSurface: Surface? = null
        var frameThread: HandlerThread? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
            val version = IntArray(2)
            checkEgl(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize")
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            checkEgl(
                EGL14.eglChooseConfig(display, EGL_ATTRIBS, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0,
                "eglChooseConfig",
            )
            context =
                EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
            checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
            checkEgl(eglSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
            checkEgl(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context), "eglMakeCurrent")

            // 编码器 Surface 的实际缓冲尺寸（正常等于 geometry.canvas 尺寸；不一致时按比例换算）
            val surfaceWidth = IntArray(1)
            val surfaceHeight = IntArray(1)
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
            EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
            val glCanvasWidth = surfaceWidth[0].takeIf { it > 0 } ?: initial.canvasWidth
            val glCanvasHeight = surfaceHeight[0].takeIf { it > 0 } ?: initial.canvasHeight
            if (glCanvasWidth != initial.canvasWidth || glCanvasHeight != initial.canvasHeight) {
                log(
                    "CARLIFE-COMPOSER: 输出 Surface 实际 ${glCanvasWidth}x$glCanvasHeight 与几何画布 " +
                        "${initial.canvasWidth}x${initial.canvasHeight} 不一致，按实际尺寸换算",
                )
            }

            // 解码器输入：OES 纹理 + SurfaceTexture 作为生产端
            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            oesTexture = textureIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val inputTexture = SurfaceTexture(oesTexture)
            inputTexture.setDefaultBufferSize(
                initial.sourceWidth.coerceAtLeast(2),
                initial.sourceHeight.coerceAtLeast(2),
            )
            val frameHandlerThread = HandlerThread(FRAME_THREAD_NAME).apply { start() }
            frameThread = frameHandlerThread
            inputTexture.setOnFrameAvailableListener(
                {
                    frameAvailable.set(true)
                    wakeRenderer()
                },
                Handler(frameHandlerThread.looper),
            )
            texture = inputTexture
            this.surfaceTexture = inputTexture
            inputSurface = Surface(inputTexture)
            inputSurfaceRef.set(inputSurface)

            // ---- 着色器 ----
            oesProgram = buildProgram(wallpaper = false)
            val oesPosAttrib = GLES20.glGetAttribLocation(oesProgram, "aPos")
            val oesTexCoordAttrib = GLES20.glGetAttribLocation(oesProgram, "aTexCoord")
            val oesStMatrixUniform = GLES20.glGetUniformLocation(oesProgram, "uSTMatrix")
            val oesTextureUniform = GLES20.glGetUniformLocation(oesProgram, "uTexture")
            val oesPaneRectUniform = GLES20.glGetUniformLocation(oesProgram, "uPaneRect")
            val oesPaneRadiusUniform = GLES20.glGetUniformLocation(oesProgram, "uPaneRadius")
            wallpaperProgram = buildProgram(wallpaper = true)
            val wallpaperPosAttrib = GLES20.glGetAttribLocation(wallpaperProgram, "aPos")
            val wallpaperTexCoordAttrib = GLES20.glGetAttribLocation(wallpaperProgram, "aTexCoord")
            val wallpaperStMatrixUniform = GLES20.glGetUniformLocation(wallpaperProgram, "uSTMatrix")
            val wallpaperTextureUniform = GLES20.glGetUniformLocation(wallpaperProgram, "uTexture")

            val fullscreenPositions = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val unitCoords = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            val wallpaperCoords = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            val panePositions = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val stMatrix = FloatArray(16)
            var textureHasContent = false
            var appliedLayout = initial

            /** 全屏清屏颜色（与 link_plus 默认壁纸底色一致）。 */
            val backgroundRed = ((BACKGROUND_COLOR shr 16) and 0xFF) / 255f
            val backgroundGreen = ((BACKGROUND_COLOR shr 8) and 0xFF) / 255f
            val backgroundBlue = (BACKGROUND_COLOR and 0xFF) / 255f

            /** 壁纸 cover 参数：把位图居中裁剪到画布宽高比。 */
            fun updateWallpaperCoords(
                canvasWidth: Int,
                canvasHeight: Int,
                bitmapWidth: Int,
                bitmapHeight: Int,
            ) {
                if (canvasWidth <= 0 || canvasHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
                val scale = maxOf(canvasWidth.toFloat() / bitmapWidth, canvasHeight.toFloat() / bitmapHeight)
                val visibleWidth = canvasWidth / scale
                val visibleHeight = canvasHeight / scale
                val u0 = (((bitmapWidth - visibleWidth) * 0.5f) / bitmapWidth).coerceIn(0f, 1f)
                val v0 = (((bitmapHeight - visibleHeight) * 0.5f) / bitmapHeight).coerceIn(0f, 1f)
                val u1 = 1f - u0
                val v1 = 1f - v0
                wallpaperCoords.position(0)
                // 顶点顺序：左下、右下、左上、右上；Bitmap 第 0 行在上，因此顶部用 v0
                wallpaperCoords
                    .put(u0)
                    .put(v1)
                    .put(u1)
                    .put(v1)
                    .put(u0)
                    .put(v0)
                    .put(u1)
                    .put(v0)
                wallpaperCoords.position(0)
            }

            /** 上传/替换待处理壁纸（仅 GL 线程调用）。 */
            fun syncWallpaperTexture(
                canvasWidth: Int,
                canvasHeight: Int,
            ) {
                val request = pendingWallpaper.getAndSet(null) ?: return
                if (wallpaperTexture != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(wallpaperTexture), 0)
                    wallpaperTexture = 0
                }
                val source = request.bitmap
                if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) return
                val uploadBitmap =
                    if (source.config == Bitmap.Config.HARDWARE) {
                        runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    } else {
                        source
                    }
                if (uploadBitmap == null) {
                    log("CARLIFE-COMPOSER: HARDWARE 壁纸复制失败，沿用纯色底")
                    return
                }
                try {
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    val id = ids[0]
                    if (id == 0) {
                        log("CARLIFE-COMPOSER: 壁纸纹理分配失败")
                        return
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
                    if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                        GLES20.glDeleteTextures(1, ids, 0)
                        log("CARLIFE-COMPOSER: 壁纸纹理上传失败，沿用纯色底")
                        return
                    }
                    wallpaperTexture = id
                    wallpaperBitmapWidth = uploadBitmap.width
                    wallpaperBitmapHeight = uploadBitmap.height
                    updateWallpaperCoords(canvasWidth, canvasHeight, uploadBitmap.width, uploadBitmap.height)
                    log("CARLIFE-COMPOSER: 壁纸已上传 ${uploadBitmap.width}x${uploadBitmap.height} canvas=${canvasWidth}x$canvasHeight")
                } finally {
                    if (uploadBitmap !== source) uploadBitmap.recycle()
                }
            }

            /** contain 窗格顶点（画布坐标 → NDC）。 */
            fun updatePanePositions(layout: Layout) {
                val canvasWidth = glCanvasWidth.toFloat().coerceAtLeast(1f)
                val canvasHeight = glCanvasHeight.toFloat().coerceAtLeast(1f)
                val scaleX = glCanvasWidth.toFloat() / layout.canvasWidth.coerceAtLeast(1)
                val scaleY = glCanvasHeight.toFloat() / layout.canvasHeight.coerceAtLeast(1)
                val left = layout.pane.left * scaleX / canvasWidth * 2f - 1f
                val right = layout.pane.right * scaleX / canvasWidth * 2f - 1f
                val top = 1f - layout.pane.top * scaleY / canvasHeight * 2f
                val bottom = 1f - layout.pane.bottom * scaleY / canvasHeight * 2f
                panePositions.position(0)
                panePositions
                    .put(left)
                    .put(bottom)
                    .put(right)
                    .put(bottom)
                panePositions
                    .put(left)
                    .put(top)
                    .put(right)
                    .put(top)
                panePositions.position(0)
            }

            /** 绘制一帧（含壁纸、CarLife 视频、圆角裁剪）。返回 false = 编码器 Surface 已不可用。 */
            fun composeFrame(
                layout: Layout,
                updateTexture: Boolean,
            ): Boolean {
                if (updateTexture) {
                    runCatching {
                        texture.updateTexImage()
                        texture.getTransformMatrix(stMatrix)
                    }
                    textureHasContent = true
                }
                GLES20.glViewport(0, 0, glCanvasWidth, glCanvasHeight)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glClearColor(backgroundRed, backgroundGreen, backgroundBlue, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                syncWallpaperTexture(glCanvasWidth, glCanvasHeight)
                if (wallpaperTexture != 0) {
                    GLES20.glUseProgram(wallpaperProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallpaperTexture)
                    GLES20.glUniform1i(wallpaperTextureUniform, 0)
                    GLES20.glUniformMatrix4fv(wallpaperStMatrixUniform, 1, false, IDENTITY_ST, 0)
                    fullscreenPositions.position(0)
                    GLES20.glVertexAttribPointer(wallpaperPosAttrib, 2, GLES20.GL_FLOAT, false, 0, fullscreenPositions)
                    GLES20.glEnableVertexAttribArray(wallpaperPosAttrib)
                    wallpaperCoords.position(0)
                    GLES20.glVertexAttribPointer(wallpaperTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, wallpaperCoords)
                    GLES20.glEnableVertexAttribArray(wallpaperTexCoordAttrib)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisableVertexAttribArray(wallpaperPosAttrib)
                    GLES20.glDisableVertexAttribArray(wallpaperTexCoordAttrib)
                }

                if (textureHasContent) {
                    GLES20.glUseProgram(oesProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
                    GLES20.glUniform1i(oesTextureUniform, 0)
                    GLES20.glUniformMatrix4fv(oesStMatrixUniform, 1, false, stMatrix, 0)
                    // 窗格窗口坐标（左下原点）：SDF 圆角在 gl_FragCoord 空间计算
                    val scaleX = layout.canvasWidth.toFloat() / glCanvasWidth.toFloat().coerceAtLeast(1f)
                    val scaleY = layout.canvasHeight.toFloat() / glCanvasHeight.toFloat().coerceAtLeast(1f)
                    val windowLeft = layout.pane.left / scaleX
                    val windowRight = layout.pane.right / scaleX
                    val windowBottom = glCanvasHeight - layout.pane.bottom / scaleY
                    val windowTop = glCanvasHeight - layout.pane.top / scaleY
                    GLES20.glUniform4f(oesPaneRectUniform, windowLeft, windowBottom, windowRight, windowTop)
                    GLES20.glUniform1f(oesPaneRadiusUniform, cornerRadiusPx)
                    updatePanePositions(layout)
                    panePositions.position(0)
                    GLES20.glVertexAttribPointer(oesPosAttrib, 2, GLES20.GL_FLOAT, false, 0, panePositions)
                    GLES20.glEnableVertexAttribArray(oesPosAttrib)
                    unitCoords.position(0)
                    GLES20.glVertexAttribPointer(oesTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, unitCoords)
                    GLES20.glEnableVertexAttribArray(oesTexCoordAttrib)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisable(GLES20.GL_BLEND)
                    GLES20.glDisableVertexAttribArray(oesPosAttrib)
                    GLES20.glDisableVertexAttribArray(oesTexCoordAttrib)
                }
                return EGL14.eglSwapBuffers(display, eglSurface)
            }

            // 启动就绪：inputSurface + 程序 + 纹理全部可用，start() 可以返回
            ready.countDown()

            var lastComposeAt = 0L
            var lastStatsAt = 0L
            var firstFrameLogged = false
            while (running.get()) {
                val observedSignal = synchronized(renderWakeup) { renderSignal }
                val current = synchronized(layoutLock) { layout } ?: appliedLayout
                if (current !== appliedLayout) {
                    val sourceChanged =
                        current.sourceWidth != appliedLayout.sourceWidth || current.sourceHeight != appliedLayout.sourceHeight
                    if (sourceChanged && current.sourceWidth > 0 && current.sourceHeight > 0) {
                        runCatching { texture.setDefaultBufferSize(current.sourceWidth, current.sourceHeight) }
                    }
                    if (wallpaperTexture != 0 &&
                        (current.canvasWidth != appliedLayout.canvasWidth || current.canvasHeight != appliedLayout.canvasHeight)
                    ) {
                        updateWallpaperCoords(glCanvasWidth, glCanvasHeight, wallpaperBitmapWidth, wallpaperBitmapHeight)
                    }
                    appliedLayout = current
                }
                val newFrame = frameAvailable.getAndSet(false)
                val sceneChanged = sceneDirty.getAndSet(false)
                val hasNew = newFrame || sceneChanged
                val now = System.nanoTime()
                val waitNanos = FramePacing.waitNanos(now, lastComposeAt, targetFps, hasNew)
                if (waitNanos > 0L) {
                    synchronized(renderWakeup) {
                        // 信号在检查与等待之间变化时立即重算，避免丢新帧/丢配置更新
                        if (running.get() && observedSignal == renderSignal) {
                            renderWakeup.wait(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                        }
                    }
                    continue
                }
                if (!composeFrame(appliedLayout, newFrame)) {
                    log("CARLIFE-COMPOSER: eglSwapBuffers 失败（编码器 Surface 可能已释放），GL 线程退出")
                    break
                }
                composedFrames++
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    log("CARLIFE-COMPOSER: 首帧已提交 ${glCanvasWidth}x$glCanvasHeight")
                }
                lastComposeAt = FramePacing.advance(now, lastComposeAt, targetFps, hasNew)
                if (now - lastStatsAt >= STATS_INTERVAL_NANOS) {
                    lastStatsAt = now
                    val pane = appliedLayout.pane
                    log(
                        "CARLIFE-COMPOSER: 合成帧=$composedFrames pane=${pane.width}x${pane.height} " +
                            "source=${appliedLayout.sourceWidth}x${appliedLayout.sourceHeight} " +
                            "有画面=$textureHasContent 壁纸=${wallpaperTexture != 0}",
                    )
                }
            }
        } catch (error: Throwable) {
            startupError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "GL 线程异常退出", error)
            log("CARLIFE-COMPOSER: GL 线程异常退出 - ${error.message ?: error.javaClass.simpleName}")
        } finally {
            running.set(false)
            ready.countDown()
            if (wallpaperTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(wallpaperTexture), 0) }
            if (oesTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0) }
            if (oesProgram != 0) runCatching { GLES20.glDeleteProgram(oesProgram) }
            if (wallpaperProgram != 0) runCatching { GLES20.glDeleteProgram(wallpaperProgram) }
            runCatching { texture?.setOnFrameAvailableListener(null) }
            this.surfaceTexture = null
            runCatching { inputSurface?.release() }
            inputSurfaceRef.set(null)
            runCatching { texture?.release() }
            frameThread?.quitSafely()
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            if (display != EGL14.EGL_NO_DISPLAY) runCatching { EGL14.eglTerminate(display) }
            log("CARLIFE-COMPOSER: GL 线程退出")
        }
    }

    /** 编译/链接一遍 GLSL 程序；[wallpaper] true = 普通 2D 壁纸程序，false = OES 视频 + 圆角程序。 */
    private fun buildProgram(wallpaper: Boolean): Int {
        val precision =
            """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            """.trimIndent()
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
            """.trimIndent()
        val fragment =
            if (wallpaper) {
                """
                $precision
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);
                }
                """.trimIndent()
            } else {
                """
                #extension GL_OES_EGL_image_external : require
                $precision
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform vec4 uPaneRect;
                uniform float uPaneRadius;
                void main() {
                    vec2 halfSize = (uPaneRect.zw - uPaneRect.xy) * 0.5;
                    vec2 center = (uPaneRect.xy + uPaneRect.zw) * 0.5;
                    vec2 q = abs(gl_FragCoord.xy - center) - halfSize + vec2(uPaneRadius);
                    float distanceToEdge = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - uPaneRadius;
                    float coverage = 1.0 - smoothstep(-0.8, 0.4, distanceToEdge);
                    if (coverage <= 0.0) discard;
                    gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, coverage);
                }
                """.trimIndent()
            }
        val program = GLES20.glCreateProgram()
        val shaders = ArrayList<Int>(2)
        try {
            shaders += compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            shaders += compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            shaders.forEach { GLES20.glAttachShader(program, it) }
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "GLSL 链接失败：${GLES20.glGetProgramInfoLog(program)}" }
        } catch (error: Throwable) {
            if (program != 0) GLES20.glDeleteProgram(program)
            throw error
        } finally {
            shaders.forEach { runCatching { GLES20.glDeleteShader(it) } }
        }
        return program
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "GLSL 编译失败（type=$type）：${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("GLSL compile type=$type")
        }
        return shader
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(
        ok: Boolean,
        stage: String,
    ) {
        if (!ok) {
            Log.e(TAG, "EGL 失败：$stage")
            throw IllegalStateException("EGL $stage")
        }
    }

    companion object {
        private const val TAG = "CarLifeComposer"

        /** 视频圆角半径默认值（画布像素），对应原版 d9 `hyRad` 的 16~17px 观感。 */
        const val DEFAULT_CORNER_RADIUS_PX = 17f

        private const val THREAD_NAME = "carlife-composer"
        private const val FRAME_THREAD_NAME = "carlife-compose-frames"
        private const val START_TIMEOUT_MS = 4_000L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val STATS_INTERVAL_NANOS = 10_000_000_000L

        /** EGL 配置：8/8/8/8 + ES2 + RECORDABLE（编码器输入 Surface 必需）。 */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** 纯色底：link_plus 默认壁纸底色 0xFF161326。 */
        private val BACKGROUND_COLOR = 0xFF161326.toInt()

        /** 单位纹理矩阵（壁纸程序用，Bitmap 已经按 cover 参数摆好顶点）。 */
        private val IDENTITY_ST =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        private val EGL_ATTRIBS =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
    }
}
