package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** 仪表盘卡片边距，绘制、建屏、布局和触摸共同使用，单位为流像素 */
const val CARD_MARGIN_PX = 16

/** 地图与音乐卡的可见间隙，位置由 VirtualLauncher.dashSplitGeom 计算 */
const val DASH_GAP_PX = 24f

/** 分屏把手触摸宽度，大于可见间隙，向两侧卡片扩展点击区域 */
const val DASH_HANDLE_TOUCH_PX = 60f

/**
 * 将 Dock、当前应用和地图纹理合成到编码器 Surface
 * 输入帧唤醒 GL 线程，按目标帧率输出；切换应用屏时等待新帧
 * 纹理就绪后通过 [onSurfacesReady] 提供虚拟屏输入 Surface
 */
class DualDisplayComposer(
    private val output: Surface,
    private val geometry: DisplayGeometry,
    /** true：dock 是底部横条（竖屏车机）；false：dock 是左侧竖条（横屏车机）。 */
    private val dockAtBottom: Boolean = false,
    wallpaperContext: Context,
) {
    // 合成器由会话持有，只保留应用上下文，避免误传窗口后延长其生命周期
    private val wallpaperContext = wallpaperContext.applicationContext
    private val outWidth = geometry.content.width
    private val outHeight = geometry.content.height

    /**
     * 最近一次成功提交画面的面板边界，触摸路由只使用已显示的几何
     */
    @Volatile var floatingPanelLayout: FloatingPanelLayout? = null
        private set

    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出左侧），由 GridActivity 随滑动驱动。 */
    @Volatile
    var tileSlideFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            sceneDirty.set(true)
            wakeRenderer()
        }

    /**
     * 拖动时覆盖地图边缘位置，只调整合成矩形，松手后再 resize
     * 单位为顶部原点的流像素，小于等于 0 时使用默认位置
     */
    @Volatile
    private var tileEdgeOverride = -1f

    fun setTileEdgeOverride(edgePx: Float) {
        tileEdgeOverride = edgePx
        sceneDirty.set(true)
        wakeRenderer()
    }

    /** 合成目标帧率，运行中可改；新帧和静止心跳都受接收端上限约束 */
    @Volatile
    var targetFps: Int = 30
        set(value) {
            field = value
            wakeRenderer()
        }

    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val TILE_CORNER_RADIUS = 24f

        /** 2D 纹理程序用的单位纹理矩阵（Bitmap 已按目标方向摆好顶点）。 */
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
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    // 此监视器需要 wait/notify，保留 Java Object 的显式接口
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val renderWakeup = java.lang.Object()
    private var renderSignal = 0L
    private val sceneDirty = AtomicBoolean(true)

    /** 回调只唤醒合成线程，纹理操作留在 GL 线程 */
    private fun wakeRenderer() {
        synchronized(renderWakeup) {
            renderSignal++
            renderWakeup.notifyAll()
        }
    }

    /** 输入 Surface 就绪后放行 start，不等待合成循环结束 */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** 是否创建了超采样应用屏（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasHiInput = false

    /** 是否创建了地图屏输入（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasMapInput = false

    /** 当前应用区显示的是超采样屏（true）还是原生屏（false）。触摸路由以它为准。 */
    @Volatile
    private var activeAppHi = false

    /** 待切换的目标屏（true=超采样屏；null=无待切换）；切屏等目标出帧，避免闪黑/闪旧帧。 */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0L

    @Volatile
    private var pendingSince = 0L

    /** 后台生成的叠加卡片，GL 线程上传并绘制；触摸和画面共用顶部原点坐标 */
    @Volatile
    var overlayCard: OverlayCard? = null
        set(value) {
            field = value
            overlayDirty = true
            wakeRenderer()
        }

    @Volatile
    private var overlayDirty = false

    /** 已上传纹理对应的卡片（仅 GL 线程访问）。 */
    private var uploadedOverlay: OverlayCard? = null

    /** 叠加卡片纹理（0 = 尚未创建，仅 GL 线程访问）。 */
    private var overlayTexture = 0

    /** 超采样输入（GL 线程创建；start() 返回后对其他线程安全可读）。 */
    @Volatile
    private var appHi: Input? = null

    /** 原生应用屏输入（同上；requestActiveApp 读其帧号作切换判据）。 */
    @Volatile
    private var appInput: Input? = null

    /** 地图屏输入（GL 线程创建）。 */
    @Volatile private var mapInput: Input? = null

    /**
     * 成功合成提交的地图布局，供触控路由使用，尚无地图内容时保持隐藏
     */
    @Volatile var presentedMapLayout = TileLayout.HIDDEN
        private set

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?) -> Unit)? = null

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
    ) {
        if (running.getAndSet(true)) return
        presentedMapLayout = TileLayout.HIDDEN
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread =
            Thread({
                try {
                    runGl(dockWidthPx, dockHeightPx, appWidthPx, appHeightPx, appHiWidthPx, appHiHeightPx, mapWidthPx, mapHeightPx)
                } catch (error: Exception) {
                    failure = IllegalStateException("合成器停止：${error.message}", error)
                    VirtualLauncher.logSink?.invoke("RENDER: 合成器异常停止：${error.message}")
                } finally {
                    running.set(false)
                    ready.countDown()
                }
            }, "dual-composer").apply {
                isDaemon = true
                start()
            }
        ready.await()
        failure?.let {
            running.set(false)
            throw it
        }
    }

    /**
     * 请求切换原生或超采样屏，等待目标新帧后生效
     * 历史画面 600ms 兜底，最长等待 2 秒；复用静态帧需启动器确认任务和尺寸
     */
    fun requestActiveApp(
        hi: Boolean,
        afterFrame: MapFrameMark? = null,
        reuseConfirmedFrame: Boolean = false,
        onDisplayed: (() -> Unit)? = null,
    ) {
        val target = if (!hasHiInput) false else hi
        val generation = appPresentationGeneration.incrementAndGet()
        appPresentation = onDisplayed?.let { AppPresentation(generation, target, afterFrame, it) }
        if (activeAppHi == target && pendingTargetHi == null) {
            sceneDirty.set(true)
            wakeRenderer()
            return
        }
        val targetInput = if (target) appHi else appInput
        // 已核验的新输入沿用启动前边界，不能在此重新起算再等一帧或额外等满 600 毫秒
        pendingBaseFrame =
            if (reuseConfirmedFrame && targetInput?.hasContent == true) {
                targetInput.frameCount - 1L
            } else {
                afterFrame?.sequence ?: targetInput?.frameCount ?: 0L
            }
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // 无超采样屏：无需等待，直接生效
            activeAppHi = false
            pendingTargetHi = null
        }
        sceneDirty.set(true)
        wakeRenderer()
    }

    /** 应用区当前活动屏（触摸路由/坐标换算以此为准）。 */
    fun isActiveAppHi(): Boolean = activeAppHi

    /** 启动前记录目标输入帧，避免误用旧应用纹理 */
    fun appFrameMark(hi: Boolean): MapFrameMark = (if (hi && hasHiInput) appHi else appInput)?.frameMark ?: MapFrameMark(0, 0)

    /**
     * 在后台等待目标应用首帧，期间继续合成当前页面，超时不切到旧纹理
     */
    fun awaitAppFrame(
        hi: Boolean,
        after: MapFrameMark,
        isAllowed: () -> Boolean,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 2_000L
        while (running.get() && isAllowed() && android.os.SystemClock.uptimeMillis() < deadline) {
            if (appFrameMark(hi).newerThan(after)) return true
            Thread.sleep(20L)
        }
        return false
    }

    private data class AppPresentation(
        val generation: Long,
        val hi: Boolean,
        val boundary: MapFrameMark?,
        val action: () -> Unit,
    )

    private val appPresentationGeneration =
        java.util.concurrent.atomic
            .AtomicLong()

    @Volatile private var appPresentation: AppPresentation? = null

    /** 地图目标布局，触摸仍使用 presentedMapLayout 中已提交的布局 */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    @Volatile
    private var lastPortraitTileDiag = 0L

    private fun launcherDashHint(): Boolean =
        try {
            VirtualLauncher.dashboardMode
        } catch (_: Throwable) {
            false
        }

    /** 切换地图 tile 布局。 */
    fun setTileLayout(
        layout: TileLayout,
        onDisplayed: (() -> Unit)? = null,
    ) {
        tileLayout = layout
        tilePresentation = onDisplayed?.let { layout to it }
        sceneDirty.set(true)
        wakeRenderer()
    }

    @Volatile private var tilePresentation: Pair<TileLayout, () -> Unit>? = null

    /** 虚拟屏 resize 后同步 SurfaceTexture buffer，避免新画面仍使用旧尺寸 */
    class MapBufferChange internal constructor(
        val request: MapResizeCoordinator.Request,
    ) {
        internal var previous: Pair<Int, Int>? = null
    }

    @Volatile private var mapBufferSize: Pair<Int, Int>? = null

    @Volatile private var mapBufferChange: MapBufferChange? = null

    @Volatile private var confirmedMapRequest: MapResizeCoordinator.Request? = null

    @Volatile private var mapFrameBoundary = MapFrameMark(0, 0)

    /** 返回已消费的图像帧，不能用通知次数代替 */
    fun mapFrameMark(): MapFrameMark = mapInput?.frameMark ?: MapFrameMark(0, 0)

    /** 应用屏尺寸变化后，在 GL 线程同步 buffer */
    internal fun updateAppGeometry(
        hi: Boolean,
        width: Int,
        height: Int,
        isAllowed: () -> Boolean,
    ): Boolean =
        mapGlCommand {
            if (!isAllowed()) return@mapGlCommand false
            val input = (if (hi) appHi else appInput) ?: return@mapGlCommand false
            input.surfaceTexture.setDefaultBufferSize(width, height)
            true
        }

    /**
     * 等待切换后的输入图像，调用方只能在后台等待，合成线程持续处理其他内容
     */
    fun awaitMapFrame(
        after: MapFrameMark,
        isAllowed: () -> Boolean,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + 1_500L
        while (running.get() && isAllowed() && android.os.SystemClock.uptimeMillis() < deadline) {
            val now = mapFrameMark()
            if (now.newerThan(after)) return true
            Thread.sleep(20L)
        }
        return false
    }

    /** 先在 GL 线程准备 buffer，超时后按队列撤销，避免影响下一次调整 */
    fun prepareMapInput(request: MapResizeCoordinator.Request): MapBufferChange? {
        val change = MapBufferChange(request)
        val ready =
            mapGlCommand {
                val input = mapInput ?: return@mapGlCommand false
                if (mapBufferChange != null) return@mapGlCommand false
                change.previous = mapBufferSize
                mapBufferChange = change
                input.surfaceTexture.setDefaultBufferSize(request.width, request.height)
                mapBufferSize = request.width to request.height
                true
            }
        if (!ready) finishMapInput(change, false)
        return change.takeIf { ready }
    }

    /** 系统尺寸确认后记录帧边界，失败只恢复本次调整的旧 buffer */
    fun finishMapInput(
        change: MapBufferChange,
        applied: Boolean,
    ): Boolean =
        mapGlCommand {
            if (mapBufferChange !== change) return@mapGlCommand false
            val input = mapInput ?: return@mapGlCommand false
            try {
                if (!applied) {
                    change.previous?.let { (width, height) ->
                        input.surfaceTexture.setDefaultBufferSize(width, height)
                        mapBufferSize = width to height
                    }
                } else {
                    // 消费掉已经排队的旧图像，以系统调整确认后的图像作为就绪依据
                    consumeInput(input)
                    mapFrameBoundary = mapFrameMark()
                    confirmedMapRequest = change.request
                }
                true
            } finally {
                mapBufferChange = null
            }
        }

    /**
     * 命令只执行短暂的纹理操作，超时后仍按队列顺序完成收尾
     */
    private fun mapGlCommand(action: () -> Boolean): Boolean {
        if (!running.get()) return false
        val task = java.util.concurrent.FutureTask<Boolean> { if (running.get()) action() else false }
        glCommands.add(task)
        wakeRenderer()
        return runCatching { task.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            .onFailure { VirtualLauncher.logSink?.invoke("RENDER: 地图缓冲命令未确认：${it.message}") }
            .getOrDefault(false)
    }

    /**
     * 只有纹理更新成功才发布消费帧号与时间戳
     */
    private fun consumeInput(input: Input) {
        if (!input.pendingFrame.getAndSet(false)) return
        input.surfaceTexture.updateTexImage()
        input.lastConsumedAt = android.os.SystemClock.uptimeMillis()
        input.frameMark = MapFrameMark(input.frameCount + 1, input.surfaceTexture.timestamp)
        input.hasContent = true
    }

    private val glCommands = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.FutureTask<Boolean>>()

    @Volatile var composedFrames: Long = 0L
        private set

    fun stop() {
        if (!running.getAndSet(false)) return
        presentedMapLayout = TileLayout.HIDDEN
        wakeRenderer()
        glThread?.interrupt()
        glThread = null
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
    ) {
        val pendingFrame = AtomicBoolean(false)
        val callbacks =
            java.util.concurrent.atomic
                .AtomicLong()

        @Volatile var lastFrameAt = 0L

        @Volatile var lastConsumedAt = 0L

        @Volatile var frameMark = MapFrameMark(0, 0)
        val timestamp: Long get() = frameMark.timestamp
        val hasNewFrame: Boolean get() = pendingFrame.get()

        /** 是否已产出过至少一帧（未产出的输入不参与绘制，避免空纹理） */
        @Volatile
        var hasContent = false

        /** 累计产出帧数（活动屏切换的「已出帧」判据）。 */
        val frameCount: Long get() = frameMark.sequence
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
    ) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs =
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
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface =
            EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                output,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // 独立帧回调线程，设置页面繁忙不会阻塞地图帧通知
        val frameCallbacks = android.os.HandlerThread("display-frame-callbacks").apply { start() }
        val frameHandler = android.os.Handler(frameCallbacks.looper)
        val createdInputs = mutableListOf<Input>()
        try {
            // ---- 输入纹理 ----
            fun newInput(
                width: Int,
                height: Int,
            ): Input {
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                val st = SurfaceTexture(tex[0])
                st.setDefaultBufferSize(width, height)
                val input = Input(tex[0], st, Surface(st))
                createdInputs += input
                st.setOnFrameAvailableListener({
                    input.callbacks.incrementAndGet()
                    input.lastFrameAt = android.os.SystemClock.uptimeMillis()
                    input.pendingFrame.set(true)
                    wakeRenderer()
                }, frameHandler)
                return input
            }

            val dock = newInput(dockW, dockH)
            val app = newInput(appW, appH).also { appInput = it }
            val outlinedPanels = FloatingPanelLayout.create(outWidth, outHeight, if (dockAtBottom) dockH else dockW, dockAtBottom)
            val seamlessPanels = FloatingPanelLayout.create(outWidth, outHeight, if (dockAtBottom) dockH else dockW, dockAtBottom, false)
            var panels = outlinedPanels
            VirtualLauncher.logSink?.invoke(
                "PANELS: 画布=${outWidth}x$outHeight 缩放=${panels.scale} 间距=${panels.gap} 侧栏=${panels.dock} 内容=${panels.app}",
            )
            val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
            val map =
                if (hasMapInput) {
                    newInput(mapW, mapH).also {
                        mapInput = it
                        mapBufferSize = mapW to mapH
                    }
                } else {
                    null
                }

            // ---- 着色器 ----
            val program = buildProgram(oes = true)
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
            val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
            val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
            val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
            val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
            val uPanelRect = GLES20.glGetUniformLocation(program, "uPanelRect")
            val uPanelRadius = GLES20.glGetUniformLocation(program, "uPanelRadius")
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val matrix = FloatArray(16)

            // ---- 叠加卡片着色器（普通 2D 纹理；Bitmap 由 Canvas 预排版）----
            val overlayProgram = buildProgram(oes = false)
            val oaPos = GLES20.glGetAttribLocation(overlayProgram, "aPos")
            val oaTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
            val ouSTMatrix = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix")
            val ouPanelRect = GLES20.glGetUniformLocation(overlayProgram, "uPanelRect")
            val ouPanelRadius = GLES20.glGetUniformLocation(overlayProgram, "uPanelRadius")
            val ouPanelEnabled = GLES20.glGetUniformLocation(overlayProgram, "uPanelEnabled")
            val frameProgram = buildProgram(oes = false, frame = true)
            val faPos = GLES20.glGetAttribLocation(frameProgram, "aPos")
            val fuPanelRect = GLES20.glGetUniformLocation(frameProgram, "uPanelRect")
            val fuPanelRadius = GLES20.glGetUniformLocation(frameProgram, "uPanelRadius")
            val fuShadow = GLES20.glGetUniformLocation(frameProgram, "uShadow")
            val fuStrength = GLES20.glGetUniformLocation(frameProgram, "uStrength")

            // 输入与提示层复用顶点缓冲，悬浮装饰只绘制边缘窄带，不增加整屏离屏合成
            val inputPositions = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val inputCoords = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            val overlayPositions = floatBuffer(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val overlayCoords = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            val shadow = minOf(outWidth, outHeight) * 0.016f

            /**
             * 描边和阴影只覆盖面板边沿，中央留空以降低每帧像素填充量
             */
            fun frameRing(panel: FloatingPanelLayout.Panel): FloatBuffer {
                val inset = minOf(panel.radius + 2f, panel.width / 2f, panel.height / 2f)
                val outer = floatArrayOf(panel.left - shadow, panel.top - shadow, panel.right + shadow, panel.bottom + shadow)
                val inner = floatArrayOf(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)
                val values = ArrayList<Float>(20)
                for (corner in listOf(0 to 1, 0 to 3, 2 to 3, 2 to 1, 0 to 1)) {
                    for (rect in listOf(outer, inner)) {
                        values += rect[corner.first] / outWidth * 2f - 1f
                        values += (outHeight - rect[corner.second]) / outHeight * 2f - 1f
                    }
                }
                return floatBuffer(*values.toFloatArray())
            }
            val dockRing = frameRing(outlinedPanels.dock)
            val appRing = frameRing(outlinedPanels.app)
            var frameAppearanceVersion = -1L
            var panelOutlineVisible = false

            /** 将顶部原点面板边界转为安全区内的 GL 裁剪矩形 */
            fun panelUniform(
                rect: Int,
                radius: Int,
                panel: FloatingPanelLayout.Panel,
            ) {
                GLES20.glUniform4f(
                    rect,
                    panel.left + geometry.content.left,
                    geometry.canvasHeight - geometry.content.top - panel.bottom,
                    panel.right + geometry.content.left,
                    geometry.canvasHeight - geometry.content.top - panel.top,
                )
                GLES20.glUniform1f(radius, panel.radius)
            }

            /**
             * 淡紫发丝描边与轻阴影沿同一圆角边界绘制，保持壁纸间隙通透
             */
            fun drawFrames() {
                if (!panelOutlineVisible) return
                GLES20.glUseProgram(frameProgram)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUniform1f(fuShadow, shadow)
                panelUniform(fuPanelRect, fuPanelRadius, panels.app)
                GLES20.glUniform1f(fuStrength, 0.22f)
                GLES20.glVertexAttribPointer(faPos, 2, GLES20.GL_FLOAT, false, 0, appRing)
                GLES20.glEnableVertexAttribArray(faPos)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 10)
                panelUniform(fuPanelRect, fuPanelRadius, panels.dock)
                GLES20.glUniform1f(fuStrength, 0.34f)
                GLES20.glVertexAttribPointer(faPos, 2, GLES20.GL_FLOAT, false, 0, dockRing)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 10)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(program)
            }
            val ouTexture = GLES20.glGetUniformLocation(overlayProgram, "uTexture")
            val ouAlpha = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")

            var wallpaperTexture = 0
            var wallpaperVersion = -1L
            val wallpaperRequests =
                io.github.shaonialife.buicklinkplus.ui
                    .WallpaperRequestState()
            val preparedWallpaper =
                java.util.concurrent.atomic
                    .AtomicReference<Pair<io.github.shaonialife.buicklinkplus.ui.WallpaperRequestState.Request, Bitmap>?>(null)
            val wallpaperPositions = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val wallpaperCoords = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            val leftWidgets = LeftWidgetColumn(geometry, wallpaperContext)
            var sideTexture = 0
            val musicIsland = MusicIsland(geometry, wallpaperContext)
            var islandTexCompact = 0
            var islandTexExpanded = 0
            val sidePositions =
                LeftWidgetColumn.area(geometry)?.let { area ->
                    val left = area.left * 2f / geometry.canvasWidth - 1f
                    val right = area.right * 2f / geometry.canvasWidth - 1f
                    val bottom = 1f - area.bottom * 2f / geometry.canvasHeight
                    val top = 1f - area.top * 2f / geometry.canvasHeight
                    floatBuffer(left, bottom, right, bottom, left, top, right, top)
                }

            /**
             * 完整画布先绘制同一张壁纸，交互内容随后限制在安全区内
             */
            fun drawWallpaper() {
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glViewport(0, 0, geometry.canvasWidth, geometry.canvasHeight)
                GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val version = WallpaperStore.version
                val wallpaperRequest =
                    wallpaperRequests.begin(
                        "$version:${geometry.canvasWidth}x${geometry.canvasHeight}",
                        android.os.SystemClock.elapsedRealtime(),
                    )
                if (wallpaperRequest != null) {
                    io.github.shaonialife.buicklinkplus.util.Bg.run("composer-wallpaper") {
                        val bitmap =
                            runCatching {
                                WallpaperStore.aspectFillCanvas(wallpaperContext, geometry.canvasWidth, geometry.canvasHeight)
                            }.getOrNull()
                        if (!running.get() || !wallpaperRequests.owns(wallpaperRequest)) return@run
                        if (bitmap != null && WallpaperStore.version == version) {
                            preparedWallpaper.set(wallpaperRequest to bitmap)
                        } else {
                            val retry = wallpaperRequests.finish(wallpaperRequest, false, android.os.SystemClock.elapsedRealtime())
                            VirtualLauncher.logSink?.invoke("WALLPAPER-COMPOSER: 解码失败或版本过期，重试=$retry")
                        }
                    }
                }
                val prepared = preparedWallpaper.get()
                if (wallpaperVersion != version &&
                    prepared != null &&
                    wallpaperRequests.owns(prepared.first) &&
                    prepared.first.key == "$version:${geometry.canvasWidth}x${geometry.canvasHeight}"
                ) {
                    val textures = IntArray(1)
                    val uploaded =
                        runCatching {
                            GLES20.glGenTextures(1, textures, 0)
                            check(textures[0] != 0) { "壁纸纹理创建失败" }
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, prepared.second, 0)
                            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "壁纸纹理上传失败" }
                        }.isSuccess
                    if (uploaded) {
                        if (wallpaperTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(wallpaperTexture), 0)
                        wallpaperTexture = textures[0]
                        wallpaperVersion = version
                    } else {
                        if (textures[0] != 0) GLES20.glDeleteTextures(1, textures, 0)
                        VirtualLauncher.logSink?.invoke("WALLPAPER-COMPOSER: 上传失败，保留原壁纸并按预算重试")
                    }
                    wallpaperRequests.finish(prepared.first, uploaded, android.os.SystemClock.elapsedRealtime())
                    preparedWallpaper.compareAndSet(prepared, null)
                }
                if (wallpaperTexture != 0) {
                    GLES20.glUseProgram(overlayProgram)
                    GLES20.glUniform1i(ouTexture, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wallpaperTexture)
                    GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)
                    GLES20.glUniform1i(ouPanelEnabled, 0)
                    GLES20.glUniform1f(ouAlpha, 1f)
                    GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, wallpaperPositions)
                    GLES20.glEnableVertexAttribArray(oaPos)
                    GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, wallpaperCoords)
                    GLES20.glEnableVertexAttribArray(oaTexCoord)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
                leftWidgets.takePrepared(android.os.SystemClock.elapsedRealtime())?.let { bitmap ->
                    try {
                        if (sideTexture == 0) {
                            val texture = IntArray(1)
                            GLES20.glGenTextures(1, texture, 0)
                            sideTexture = texture[0]
                        }
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sideTexture)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (sideTexture != 0 && sidePositions != null) {
                    GLES20.glUseProgram(overlayProgram)
                    GLES20.glUniform1i(ouTexture, 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sideTexture)
                    GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)
                    GLES20.glUniform1i(ouPanelEnabled, 0)
                    GLES20.glUniform1f(ouAlpha, 1f)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, sidePositions)
                    GLES20.glEnableVertexAttribArray(oaPos)
                    GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, wallpaperCoords)
                    GLES20.glEnableVertexAttribArray(oaTexCoord)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(uTexture, 0)
                GLES20.glViewport(geometry.content.left, geometry.canvasHeight - geometry.content.bottom, outWidth, outHeight)
            }

            /** 上传/更新叠加卡片纹理（仅 GL 线程；卡片引用变化时才重新上传）。 */
            fun syncOverlayTexture(): OverlayCard? {
                val card =
                    overlayCard ?: run {
                        uploadedOverlay = null
                        return null
                    }
                if (card === uploadedOverlay && overlayTexture != 0) return card
                if (overlayTexture == 0) {
                    val tex = IntArray(1)
                    GLES20.glGenTextures(1, tex, 0)
                    overlayTexture = tex[0]
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, card.bitmap, 0)
                uploadedOverlay = card
                return card
            }

            /** 将顶部原点的卡片矩形转为 GL 坐标，位图纹理 v 轴与顶点方向相反 */
            fun drawOverlay(
                card: OverlayCard,
                left: Float,
                top: Float,
                right: Float,
                bottom: Float,
            ) {
                GLES20.glUseProgram(overlayProgram)
                GLES20.glUniform1i(ouTexture, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)
                GLES20.glUniform1i(ouPanelEnabled, 1)
                GLES20.glUniform1f(ouAlpha, 1f)
                panelUniform(ouPanelRect, ouPanelRadius, panels.app)

                fun px(v: Float) = v / outWidth * 2f - 1f

                fun py(v: Float) = v / outHeight * 2f - 1f
                val xLeft = panels.displayX(panels.app, left)
                val xRight = panels.displayX(panels.app, right)
                val yTop = outHeight - panels.displayY(panels.app, top)
                val yBottom = outHeight - panels.displayY(panels.app, bottom)
                overlayPositions.position(0)
                overlayPositions
                    .put(px(xLeft))
                    .put(py(yBottom))
                    .put(px(xRight))
                    .put(py(yBottom))
                    .put(px(xLeft))
                    .put(py(yTop))
                    .put(px(xRight))
                    .put(py(yTop))
                    .position(0)
                // Bitmap v=0 在顶部：底部顶点取 v=1，顶部顶点取 v=0
                GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, overlayPositions)
                GLES20.glEnableVertexAttribArray(oaPos)
                GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, overlayCoords)
                GLES20.glEnableVertexAttribArray(oaTexCoord)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisable(GLES20.GL_BLEND)
                // 恢复主着色器状态（后续帧继续用 OES 程序）
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(uTexture, 0)
            }

            /** 上传音乐岛位图（每份快照取走时调用一次；0 = 新建纹理） */
            fun uploadIslandTexture(
                texture: Int,
                bitmap: Bitmap,
            ): Int {
                val id =
                    if (texture != 0) {
                        texture
                    } else {
                        val textures = IntArray(1)
                        GLES20.glGenTextures(1, textures, 0)
                        textures[0]
                    }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                return id
            }

            /** 在安全区 viewport 中绘制音乐岛，位图已带圆角，不再裁剪 */
            fun drawIslandLayer(
                texture: Int,
                box: MusicIsland.Box,
                alpha: Float,
            ) {
                GLES20.glUseProgram(overlayProgram)
                GLES20.glUniform1i(ouTexture, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUniformMatrix4fv(ouSTMatrix, 1, false, IDENTITY_ST, 0)
                GLES20.glUniform1i(ouPanelEnabled, 0)
                GLES20.glUniform1f(ouAlpha, alpha)

                fun px(v: Float) = v / outWidth * 2f - 1f

                fun py(v: Float) = 1f - v / outHeight * 2f
                overlayPositions.position(0)
                overlayPositions
                    .put(px(box.left))
                    .put(py(box.bottom))
                    .put(px(box.right))
                    .put(py(box.bottom))
                    .put(px(box.left))
                    .put(py(box.top))
                    .put(px(box.right))
                    .put(py(box.top))
                    .position(0)
                GLES20.glVertexAttribPointer(oaPos, 2, GLES20.GL_FLOAT, false, 0, overlayPositions)
                GLES20.glEnableVertexAttribArray(oaPos)
                GLES20.glVertexAttribPointer(oaTexCoord, 2, GLES20.GL_FLOAT, false, 0, overlayCoords)
                GLES20.glEnableVertexAttribArray(oaTexCoord)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(program)
                GLES20.glUniform1i(uTexture, 0)
            }

            /** 音乐岛：快照上传 + 按过渡帧绘制（最上层，叠在面板与轮廓之上） */
            fun drawMusicIsland() {
                musicIsland.takeSnapshot()?.let { snapshot ->
                    islandTexCompact = uploadIslandTexture(islandTexCompact, snapshot.compact)
                    islandTexExpanded = uploadIslandTexture(islandTexExpanded, snapshot.expanded)
                    snapshot.recycle()
                }
                val frame = musicIsland.frameAt(android.os.SystemClock.elapsedRealtime()) ?: return
                if (frame.outgoingAlpha > 0f) {
                    drawIslandLayer(if (frame.outgoingExpanded) islandTexExpanded else islandTexCompact, frame.box, frame.outgoingAlpha)
                }
                if (frame.primaryAlpha > 0f) {
                    drawIslandLayer(if (frame.primaryExpanded) islandTexExpanded else islandTexCompact, frame.box, frame.primaryAlpha)
                }
            }

            /** [u0]/[u1]：横向纹理采样区间（地图分屏时采样缓冲中央带区用）。 */
            fun drawInputUV(
                input: Input,
                x0: Float,
                x1: Float,
                y0: Float,
                y1: Float,
                u0: Float = 0f,
                u1: Float = 1f,
                cornerRadius: Float = 0f,
            ) {
                input.surfaceTexture.getTransformMatrix(matrix)
                GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
                val panel = if (input === dock) panels.dock else panels.app
                val left = panels.displayX(panel, x0)
                val right = panels.displayX(panel, x1)
                val bottom = outHeight - panels.displayY(panel, outHeight - y0)
                val top = outHeight - panels.displayY(panel, outHeight - y1)
                panelUniform(uPanelRect, uPanelRadius, panel)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                // 圆角裁剪：shader 用 gl_FragCoord（GL 底部原点）与 uClipRect 比较，
                // 故直接传顶点坐标（本函数 y 即 GL 坐标）。此前翻转成屏幕坐标——
                // 全高 tile（横屏）对称区间恰好重合未暴露；竖屏半高 tile 顶点区域
                // 与裁剪区域完全不相交，整块被 discard（「地图卡不可见」根因）
                if (cornerRadius > 0f) {
                    GLES20.glUniform1i(uClipEnabled, 1)
                    GLES20.glUniform4f(
                        uClipRect,
                        left + geometry.content.left,
                        bottom + geometry.canvasHeight - geometry.content.bottom,
                        right + geometry.content.left,
                        top + geometry.canvasHeight - geometry.content.bottom,
                    )
                    GLES20.glUniform1f(uClipRadius, cornerRadius * panels.scale)
                } else {
                    GLES20.glUniform1i(uClipEnabled, 0)
                }

                // 像素坐标 → 裁剪坐标（GL 左下原点；纹理翻转由 uSTMatrix 处理，勿重复翻转）
                fun px(v: Float) = v / outWidth * 2f - 1f

                fun py(v: Float) = v / outHeight * 2f - 1f
                inputPositions.position(0)
                inputPositions
                    .put(px(left))
                    .put(py(bottom))
                    .put(px(right))
                    .put(py(bottom))
                    .put(px(left))
                    .put(py(top))
                    .put(px(right))
                    .put(py(top))
                    .position(0)
                inputCoords.position(0)
                inputCoords
                    .put(u0)
                    .put(0f)
                    .put(u1)
                    .put(0f)
                    .put(u0)
                    .put(1f)
                    .put(u1)
                    .put(1f)
                    .position(0)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, inputPositions)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, inputCoords)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            fun drawInput(
                input: Input,
                x0: Float,
                x1: Float,
                y0: Float,
                y1: Float,
            ) = drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

            fun composeFrame(fullRedraw: Boolean) {
                // 同帧统一取消描边、阴影、缩放、留白与圆角，保留原虚拟屏和壁纸逻辑坐标
                val version = UiAppearance.version
                if (frameAppearanceVersion != version) {
                    panelOutlineVisible = CarAppearancePrefs.panelOutlineVisible(wallpaperContext)
                    panels = if (panelOutlineVisible) outlinedPanels else seamlessPanels
                    frameAppearanceVersion = version
                }
                // 待切应用屏：目标屏出帧即切（帧号前进 / 已有内容 600ms 兜底 / 2s 超时兜底）
                pendingTargetHi?.let { targetHi ->
                    val nowMs = android.os.SystemClock.uptimeMillis()
                    val target = if (targetHi) hi else app
                    val advanced = target != null && target.frameCount > pendingBaseFrame
                    if (advanced ||
                        (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                        nowMs - pendingSince >= 2_000L
                    ) {
                        activeAppHi = targetHi
                        pendingTargetHi = null
                    }
                }
                drawWallpaper()
                // 只对有新帧的输入 updateTexImage；已有内容的输入沿用旧纹理（画面持久）。
                // 两块应用屏都持续消费（非活动屏也保持最新，切换时不闪旧帧）。
                consumeInput(dock)
                consumeInput(app)
                hi?.let(::consumeInput)
                map?.let(::consumeInput)
                val activeInput = if (activeAppHi) hi else app
                // 同一帧只使用一次布局快照，绘制、提交回调和触控依据必须一致
                val frameMapLayout = tileLayout
                val frameTilePresentation = tilePresentation
                val mapCoversApp = frameMapLayout == TileLayout.FULLSCREEN && map?.hasContent == true
                if (dockAtBottom) {
                    // 竖屏：dock 横条贴底（GL 坐标 y=0 为底边），app 在其余区域；
                    // 仪表盘时地图卡叠在应用区上半（地图上/音乐下），全屏盖满应用区。
                    if (dock.hasContent) drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                    if (!mapCoversApp && activeInput?.hasContent == true) {
                        drawInput(activeInput, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                    }
                    val layoutP = frameMapLayout
                    if (map?.hasContent == true && layoutP != TileLayout.HIDDEN) {
                        if (layoutP == TileLayout.FULLSCREEN) {
                            drawInput(map, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                        } else {
                            // 上半卡片：左右留 16、顶留 16；pager 水平滑动 → tile 向左滑出（跟手）
                            // tileEdgeOverride = tile 把手侧边缘（顶部原点 y，即 tile 下缘）；
                            // GL y = outHeight − 顶部 y（底部原点换算，勿翻转——坐标系约定见类注释）
                            val tileH = (outHeight - dockH) / 2f - CARD_MARGIN_PX * 1.5f
                            val handleEdge =
                                if (tileEdgeOverride > 0f) tileEdgeOverride else CARD_MARGIN_PX + tileH
                            val slide = tileSlideFraction * outWidth
                            val nowMs = android.os.SystemClock.uptimeMillis()
                            if (nowMs - lastPortraitTileDiag > 5_000) {
                                lastPortraitTileDiag = nowMs
                                VirtualLauncher.logSink?.invoke(
                                    "COMPOSER: 竖屏 tile 绘制 tileH=$tileH handleEdge=$handleEdge " +
                                        "slide=$slide frames=${map.frameCount}",
                                )
                            }
                            drawInputUV(
                                map,
                                CARD_MARGIN_PX - slide,
                                outWidth - CARD_MARGIN_PX - slide,
                                outHeight - handleEdge,
                                (outHeight - CARD_MARGIN_PX).toFloat(),
                                cornerRadius = TILE_CORNER_RADIUS,
                            )
                        }
                    } else {
                        // 竖屏 tile 未绘制：状态节流采样（5s），排查「看不到地图卡」
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        if (nowMs - lastPortraitTileDiag > 5_000) {
                            lastPortraitTileDiag = nowMs
                            VirtualLauncher.logSink?.invoke(
                                "COMPOSER: 竖屏 tile 未画 layout=$layoutP " +
                                    "mapInput=${map != null} hasContent=${map?.hasContent} " +
                                    "frames=${map?.frameCount} dash=${launcherDashHint()}",
                            )
                        }
                    }
                } else {
                    if (!mapCoversApp && activeInput?.hasContent == true) {
                        drawInput(activeInput, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                    }
                    // 地图盖在应用区上方，隐藏时不画
                    // 分屏用 tile 尺寸，全屏用应用区尺寸，虚拟屏随布局 resize 后整幅映射
                    val layout = frameMapLayout
                    if (map?.hasContent == true && layout != TileLayout.HIDDEN) {
                        if (layout == TileLayout.FULLSCREEN) {
                            drawInput(map, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                        } else {
                            // 卡片形态：四周留边 + 圆角（与音乐卡一致）。
                            // 分屏把手拖动期间 tileEdgeOverride（tile 右缘 x）替代公式值：
                            // 只改绘制矩形、纹理整幅拉伸（不动 buffer/虚拟屏），松手才 resize。
                            // tileSlideFraction：随 pager 滑动把 tile 平移出/入左侧（视觉跟手）。
                            val half = (outWidth - dockW) / 2f
                            val tileW = half - CARD_MARGIN_PX - CARD_MARGIN_PX / 2f
                            val x1 =
                                if (tileEdgeOverride > 0f) {
                                    tileEdgeOverride
                                } else {
                                    dockW + CARD_MARGIN_PX + tileW
                                }
                            val slide = tileSlideFraction * (outWidth - dockW)
                            drawInputUV(
                                map,
                                dockW + CARD_MARGIN_PX - slide,
                                x1 - slide,
                                CARD_MARGIN_PX.toFloat(),
                                (outHeight - CARD_MARGIN_PX).toFloat(),
                                cornerRadius = TILE_CORNER_RADIUS,
                            )
                        }
                    }
                    // dock 最后绘制（最上层）：tile 滑动经过 dock 区域时从其下方穿过
                    if (dock.hasContent) drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
                }
                // 叠加卡片最后绘制（最上层，不受分支影响）：卡片坐标为输出画面坐标（顶部原点）
                syncOverlayTexture()?.let { card ->
                    drawOverlay(card, card.left, card.top, card.right, card.bottom)
                }
                drawFrames()
                drawMusicIsland()
                if (fullRedraw ||
                    overlayDirty ||
                    dock.hasContent ||
                    app.hasContent ||
                    hi?.hasContent == true ||
                    map?.hasContent == true
                ) {
                    if (EGL14.eglSwapBuffers(display, outputSurface)) {
                        floatingPanelLayout = panels
                        presentedMapLayout = if (map?.hasContent == true) frameMapLayout else TileLayout.HIDDEN
                        frameTilePresentation?.let { presentation ->
                            if (tilePresentation === presentation && presentation.first == presentedMapLayout) {
                                tilePresentation = null
                                presentation.second()
                            }
                        }
                        appPresentation?.let { presentation ->
                            if (activeAppHi == presentation.hi &&
                                pendingTargetHi == null &&
                                activeInput?.hasContent == true &&
                                (presentation.boundary == null || activeInput.frameMark.newerThan(presentation.boundary)) &&
                                appPresentationGeneration.get() == presentation.generation
                            ) {
                                if (appPresentation === presentation) appPresentation = null
                                presentation.action()
                            }
                        }
                    }
                }
                overlayDirty = false
            }

            // 首帧先铺壁纸，再交付输入 Surface 并放行启动流程
            drawWallpaper()
            EGL14.eglSwapBuffers(display, outputSurface)
            onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface)
            startLatch?.countDown()

            // 有新帧时按纳秒帧周期合成，避免毫秒取整累积误差
            // 静止时约每 33ms 重画一次，给编码器续帧，同时遵守车机帧率上限
            try {
                var lastComposeAt = System.nanoTime()
                var lastDiagnosticsAt = android.os.SystemClock.uptimeMillis()
                var lastMapSequence = 0L
                val mapStallGate = MapStallGate()
                while (running.get()) {
                    val observedSignal = synchronized(renderWakeup) { renderSignal }
                    while (true) (glCommands.poll() ?: break).run()
                    val diagnosticsAt = android.os.SystemClock.uptimeMillis()
                    if (mapStallGate.inspect(
                            android.os.SystemClock.elapsedRealtime(),
                            presentedMapLayout != TileLayout.HIDDEN,
                            map?.frameCount ?: 0L,
                        )
                    ) {
                        VirtualLauncher.inspectStalledMap(this, mapFrameMark())
                    }
                    if (diagnosticsAt - lastDiagnosticsAt >= 10_000L) {
                        VirtualLauncher.logSink?.invoke(
                            "RENDER: 地图通知=${map?.callbacks?.get() ?: 0} 地图消费=${map?.frameCount ?: 0} " +
                                "距末帧=${map?.lastFrameAt?.takeIf { it > 0 }?.let { diagnosticsAt - it } ?: -1}ms " +
                                "消费时间=${map?.lastConsumedAt} 时间戳=${map?.timestamp} 边界=$mapFrameBoundary " +
                                "缓冲=$mapBufferSize 尺寸事务=$confirmedMapRequest 准备中=${mapBufferChange?.request} " +
                                "任务快照=${VirtualLauncher.mapDiagnostics()} " +
                                "地图输入=${if ((map?.frameCount ?: 0L) > lastMapSequence) "存在新图像，帧内3D变化未知" else "本周期无新图像，静止时也可能正常"} " +
                                "侧栏输入=${dock.frameMark} 侧栏通知=${dock.callbacks.get()} " +
                                "原生输入=${app.frameMark} 原生通知=${app.callbacks.get()} " +
                                "超采样输入=${hi?.frameMark} 超采样通知=${hi?.callbacks?.get()} " +
                                "活动屏超采样=$activeAppHi 待切换=$pendingTargetHi " +
                                "合成=$composedFrames 发送=${io.github.shaonialife.buicklinkplus.session.SessionController.videoSender
                                    ?.framesSentTotal ?: 0} 目标布局=$tileLayout 已合成布局=$presentedMapLayout，" +
                                "输入帧更新不能证明帧内的道路与建筑持续更新",
                        )
                        lastDiagnosticsAt = diagnosticsAt
                        lastMapSequence = map?.frameCount ?: 0L
                    }
                    // 只有 Dock、活动应用屏和可见地图的新帧才触发合成
                    // 非活动屏仍消费纹理，避免队列积压，但不为隐藏内容增加出帧
                    val appCovered = tileLayout == TileLayout.FULLSCREEN && mapInput?.hasContent == true
                    val hasNew =
                        sceneDirty.get() ||
                            overlayDirty ||
                            dock.hasNewFrame ||
                            (!appCovered && (if (activeAppHi) appHi else app)?.hasNewFrame == true) ||
                            (tileLayout != TileLayout.HIDDEN && mapInput?.hasNewFrame == true)
                    val now = System.nanoTime()
                    val fps = targetFps
                    val waitNanos = FramePacing.waitNanos(now, lastComposeAt, fps, hasNew)
                    if (waitNanos == 0L) {
                        sceneDirty.set(false)
                        composeFrame(fullRedraw = false)
                        composedFrames++
                        lastComposeAt = FramePacing.advance(now, lastComposeAt, fps, hasNew)
                    } else {
                        synchronized(renderWakeup) {
                            // 信号在检查与等待之间变化时立即重算，避免丢失首帧或缓冲命令唤醒
                            if (running.get() && observedSignal == renderSignal) {
                                renderWakeup.wait(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                leftWidgets.close()
                musicIsland.close()
                if (sideTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(sideTexture), 0) }
                if (islandTexCompact != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(islandTexCompact), 0) }
                if (islandTexExpanded != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(islandTexExpanded), 0) }
                if (wallpaperTexture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(wallpaperTexture), 0) }
                }
                if (overlayTexture != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(overlayTexture), 0) }
                    overlayTexture = 0
                }
            }
        } finally {
            frameCallbacks.quitSafely()
            floatingPanelLayout = null
            presentedMapLayout = TileLayout.HIDDEN
            appPresentation = null
            tilePresentation = null
            mapBufferChange = null
            mapInput = null
            appInput = null
            appHi = null
            while (true) (glCommands.poll() ?: break).cancel(false)
            createdInputs.forEach {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /**
     * 外部输入与提示层共享圆角公式，装饰程序只处理面板边缘窄带
     */
    private fun buildProgram(
        oes: Boolean,
        frame: Boolean = false,
    ): Int {
        val panelClip =
            """
            uniform vec4 uPanelRect;
            uniform float uPanelRadius;
            float roundedDistance(vec4 rect, float radius) {
                vec2 center = (rect.xy + rect.zw) * 0.5;
                vec2 halfSize = (rect.zw - rect.xy) * 0.5;
                vec2 q = abs(gl_FragCoord.xy - center) - halfSize + vec2(radius);
                return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }
            float panelCoverage() {
                // 无轮廓使用完整矩形，不能让抗锯齿在拼接处留下半透明细线
                if (uPanelRadius <= 0.0) {
                    return gl_FragCoord.x >= uPanelRect.x && gl_FragCoord.x < uPanelRect.z &&
                        gl_FragCoord.y >= uPanelRect.y && gl_FragCoord.y < uPanelRect.w ? 1.0 : 0.0;
                }
                return 1.0 - smoothstep(-0.8, 0.4, roundedDistance(uPanelRect, uPanelRadius));
            }
            """.trimIndent()
        val precision =
            """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            """.trimIndent()
        val fragment =
            if (frame) {
                """
                $precision
                $panelClip
                uniform float uShadow;
                uniform float uStrength;
                void main() {
                    float d = roundedDistance(uPanelRect, uPanelRadius);
                    float shade = (1.0 - smoothstep(0.0, uShadow, max(d, 0.0))) * step(0.0, d) * 0.18;
                    float line = (1.0 - smoothstep(0.35, 1.25, abs(d + 0.65))) * uStrength;
                    float t = clamp((gl_FragCoord.y - uPanelRect.y) / (uPanelRect.w - uPanelRect.y), 0.0, 1.0);
                    vec3 tint = mix(vec3(0.67, 0.52, 0.98), vec3(0.96, 0.93, 1.0), t);
                    gl_FragColor = vec4(tint * line, line + shade * (1.0 - line));
                }
                """.trimIndent()
            } else if (oes) {
                """
                #extension GL_OES_EGL_image_external : require
                $precision
                $panelClip
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                uniform int uClipEnabled;
                uniform vec4 uClipRect;
                uniform float uClipRadius;
                void main() {
                    float coverage = panelCoverage();
                    if (uClipEnabled == 1) {
                        coverage *= 1.0 - smoothstep(-0.8, 0.4, roundedDistance(uClipRect, uClipRadius));
                    }
                    if (coverage <= 0.0) discard;
                    gl_FragColor = texture2D(uTexture, vTexCoord) * coverage;
                }
                """.trimIndent()
            } else {
                """
                $precision
                $panelClip
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                uniform int uPanelEnabled;
                uniform float uAlpha;
                void main() {
                    float coverage = uPanelEnabled == 1 ? panelCoverage() : 1.0;
                    if (coverage <= 0.0) discard;
                    gl_FragColor = texture2D(uTexture, vTexCoord) * coverage * uAlpha;
                }
                """.trimIndent()
            }
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

        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            // 编译失败必须留痕并抛出：静默继续 = 黑屏无从排查（EGL 有 checkEgl，GLSL 这层此前漏了）
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "GLSL 编译失败（type=$type）：${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GLSL compile type=$type")
            }
            return shader
        }

        val program = GLES20.glCreateProgram()
        val shaders = ArrayList<Int>(2)
        try {
            shaders += compile(GLES20.GL_VERTEX_SHADER, vertex)
            shaders += compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            shaders.forEach { GLES20.glAttachShader(program, it) }
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "GLSL 链接失败：${GLES20.glGetProgramInfoLog(program)}" }
        } catch (error: Throwable) {
            GLES20.glDeleteProgram(program)
            throw error
        } finally {
            shaders.forEach { GLES20.glDeleteShader(it) }
        }
        return program
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
}
