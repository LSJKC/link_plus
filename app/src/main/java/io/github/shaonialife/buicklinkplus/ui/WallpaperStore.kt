package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.FloatingPanelLayout
import java.io.File

/** 保存用户壁纸，没有图片时使用纯色背景 */
object WallpaperStore {
    private const val BACKGROUND_COLOR = 0xFF161326.toInt()
    private const val USER_FILE = "wallpaper.jpg"

    private val versionCounter =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** 当前壁纸版本（更换后递增；接收方据此判断是否需要重载）。 */
    val version: Long get() = versionCounter.get()

    // ---- 变更通知（监听器模式；接收方注册后应立即读一次当前状态兜底） ----

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Long) -> Unit>()

    /** 注册壁纸变更监听（在主线程回调；参数为新版本号）。 */
    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    fun markChanged() {
        val version = versionCounter.incrementAndGet()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            listeners.forEach { runCatching { it(version) } }
            UiAppearance.refreshWindows()
        }
    }

    /** 读取用户壁纸，旧版内置图片引用按未设置处理 */
    fun userWallpaperFile(context: Context): File? {
        val prefs = context.getSharedPreferences("wallpaper_calibration", Context.MODE_PRIVATE)
        val name = prefs.getString("image", USER_FILE).orEmpty()
        if (name.isEmpty() || name.startsWith("asset:") || '/' in name || '\\' in name) return null
        return File(context.filesDir, name).takeIf { it.exists() && it.length() > 0 }
    }

    /** 后台解码用户图片，读取失败时使用纯色背景 */
    fun decode(
        context: Context,
        maxSize: Int = 2560,
    ): Bitmap? = decodeUser(context, maxSize) ?: defaultBackground()

    /** 预览和正式画面共用同一底色 */
    internal fun defaultBackground(): Bitmap = createBitmap(1, 1).apply { eraseColor(BACKGROUND_COLOR) }

    // ---- 壁纸明暗检测：车机明暗主题跟随壁纸亮度（浅壁纸深字、深壁纸浅字） ----

    /** 一起发布壁纸版本和明暗结果，忽略旧图结果 */
    @Volatile
    private var darknessSnapshot: Pair<Long, Boolean?>? = null

    private val darknessComputing =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 从内存读取壁纸明暗，后台只解码小图；换图后重算，失败时沿用日夜判断 */
    fun isDarkWallpaper(context: Context): Boolean? {
        val current = version
        val snapshot = darknessSnapshot
        if (snapshot?.first == current) return snapshot.second
        if (darknessComputing.compareAndSet(false, true)) {
            val appContext = context.applicationContext
            io.github.shaonialife.buicklinkplus.util.Bg
                .run("wallpaper-darkness") {
                    try {
                        val requestedVersion = version
                        val bitmap = runCatching { decode(appContext, maxSize = 64) }.getOrNull()
                        val dark =
                            try {
                                bitmap?.let(::darknessOf)
                            } finally {
                                // 本任务独占的小图不进入共享画布缓存
                                bitmap?.recycle()
                            }
                        if (version == requestedVersion) {
                            darknessSnapshot = requestedVersion to dark
                            if (dark != null) UiAppearance.refreshWindows()
                        }
                    } finally {
                        darknessComputing.set(false)
                        // 计算期间换图也能继续调度，不依赖下一次窗口重绘触发
                        if (darknessSnapshot?.first != version) isDarkWallpaper(appContext)
                    }
                }
        }
        return null
    }

    /**
     * 采样计算位图平均亮度，低于阈值判定为暗色（后台线程调用）
     */
    private fun darknessOf(bitmap: Bitmap): Boolean {
        var sum = 0L
        var count = 0
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 32)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = bitmap[x, y]
                val red = (pixel ushr 16) and 0xFF
                val green = (pixel ushr 8) and 0xFF
                val blue = pixel and 0xFF
                sum += (red * 299 + green * 587 + blue * 114) / 1000
                count++
            }
        }
        return count > 0 && sum / count < 128
    }

    private fun decodeUser(
        context: Context,
        maxSize: Int,
    ): Bitmap? {
        val file = userWallpaperFile(context) ?: return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width > maxSize || info.size.height > maxSize) {
                    decoder.setTargetSampleSize(
                        maxOf(info.size.width, info.size.height) / maxSize + 1,
                    )
                }
            }
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    /**
     * 读取经过边界校验的壁纸校准值
     */
    fun calibration(context: Context): WallpaperCalibration {
        val prefs = context.getSharedPreferences("wallpaper_calibration", Context.MODE_PRIVATE)
        return WallpaperCalibration(prefs.getInt("zoom", 100), prefs.getInt("x", 0), prefs.getInt("y", 0)).normalized()
    }

    /** 先写入图片，再一次保存引用和校准，只通知一次变更 */
    @Synchronized
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun save(
        context: Context,
        uri: Uri?,
        useDefault: Boolean,
        value: WallpaperCalibration,
    ): Boolean {
        if (uri != null && useDefault) return false
        val prefs = context.getSharedPreferences("wallpaper_calibration", Context.MODE_PRIVATE)
        val previous = userWallpaperFile(context)
        var pending: File? = null
        val previousImage = prefs.getString("image", USER_FILE)
        val previousValue = calibration(context)
        var committing = false
        return try {
            if (uri != null) {
                val destination = File(context.filesDir, "wallpaper-${java.util.UUID.randomUUID()}.jpg")
                pending = destination
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                } ?: error("无法读取选择的图片")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destination.absolutePath, options)
                check(options.outWidth > 0 && options.outHeight > 0) { "选择的图片无法解码" }
            }
            val normalized = value.normalized()
            val image =
                when {
                    useDefault -> ""
                    pending != null -> pending.name
                    previousImage?.startsWith("asset:") == true -> ""
                    else -> previousImage.orEmpty()
                }
            committing = true
            check(
                prefs
                    .edit()
                    .putString("image", image)
                    .putInt("zoom", normalized.zoomPercent)
                    .putInt("x", normalized.xPermille)
                    .putInt("y", normalized.yPermille)
                    .commit(),
            ) { "无法保存壁纸配置" }
            // 新配置已落盘，旧图片不再被配置引用后才清理
            if (previous != null && previous.name != image) previous.delete()
            markChanged()
            true
        } catch (_: Exception) {
            if (committing) {
                // commit 失败仍可能修改内存值，恢复原引用后才能删除未提交图片
                runCatching {
                    prefs
                        .edit()
                        .putString("image", previousImage)
                        .putInt("zoom", previousValue.zoomPercent)
                        .putInt("x", previousValue.xPermille)
                        .putInt("y", previousValue.yPermille)
                        .commit()
                }
            }
            pending?.delete()
            false
        }
    }

    /**
     * 更换图片时重置校准，正式编辑入口通过保存草稿调用
     */
    fun setFromUri(
        context: Context,
        uri: Uri,
    ): Boolean = save(context, uri, false, WallpaperCalibration())

    /**
     * 内置壁纸：直接从 Bitmap 写入用户壁纸文件，不经过内容 Uri。
     *
     * 与 [save] 语义一致：先落盘图片，再提交引用和默认校准，最后通知一次变更。
     */
    @Synchronized
    // 后台事务同步落盘并检查结果，成功后才释放旧资源或通知监听者
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun saveBuiltin(
        context: Context,
        bitmap: Bitmap,
    ): Boolean {
        val prefs = context.getSharedPreferences("wallpaper_calibration", Context.MODE_PRIVATE)
        val previous = userWallpaperFile(context)
        val previousImage = prefs.getString("image", USER_FILE)
        val destination = File(context.filesDir, "wallpaper-${java.util.UUID.randomUUID()}.jpg")
        return try {
            java.io.FileOutputStream(destination).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "无法编码内置壁纸" }
                output.fd.sync()
            }
            check(
                prefs
                    .edit()
                    .putString("image", destination.name)
                    .putInt("zoom", 100)
                    .putInt("x", 0)
                    .putInt("y", 0)
                    .commit(),
            ) { "无法保存壁纸配置" }
            if (previous != null && previous.name != destination.name) previous.delete()
            markChanged()
            true
        } catch (_: Exception) {
            destination.delete()
            runCatching { prefs.edit().putString("image", previousImage).commit() }
            false
        }
    }

    /** 清除壁纸并重置画面位置 */
    fun resetToDefault(context: Context): Boolean = save(context, null, true, WallpaperCalibration())

    // ---- 桌面切片：全流画布的 aspectFill 位图（GridActivity/DockActivity 共用） ----

    /**
     * 从完整投屏壁纸裁出安全区，再缩放到对应虚拟屏的采样尺寸
     */
    @Synchronized
    fun contentCanvas(
        context: Context,
        width: Int,
        height: Int,
        geometry: DisplayGeometry?,
    ): Bitmap? {
        if (geometry == null) return aspectFillCanvas(context, width, height)
        if (width <= 0 || height <= 0) return null
        val source = aspectFillCanvas(context, geometry.canvasWidth, geometry.canvasHeight) ?: return null
        val area = geometry.content
        val key = "安全区:${geometry.canvasWidth}x${geometry.canvasHeight}:$area:${width}x$height"
        canvasCache.get(key)?.let { return it }
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawBitmap(
            source,
            android.graphics.Rect(area.left, area.top, area.right, area.bottom),
            android.graphics.Rect(0, 0, width, height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        canvasCache.put(key, bitmap)
        return bitmap
    }

    /** 按实际字节限制缓存，淘汰时只移除引用，不回收仍在显示的位图 */
    private val canvasCache =
        object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }
    private var canvasCacheVersion = -1L

    private val backdropCache =
        object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }
    private var backdropCacheVersion = -1L

    /**
     * 背景采样使用面板实际显示位置，避免逻辑画布缩小后与外围壁纸产生跳变
     */
    fun panelArea(
        geometry: DisplayGeometry,
        dockWidth: Int,
        dockHeight: Int,
        dockAtBottom: Boolean,
        dock: Boolean,
        outlineVisible: Boolean,
    ): RectF {
        val safe = geometry.content
        val dockSize = if (dockAtBottom) dockHeight else dockWidth
        // 会话退役可能先清空侧栏尺寸，迟到窗口刷新不能再构造无效面板
        if (dockSize !in 1 until if (dockAtBottom) safe.height else safe.width) {
            return RectF(safe.left.toFloat(), safe.top.toFloat(), safe.right.toFloat(), safe.bottom.toFloat())
        }
        val panels =
            FloatingPanelLayout.create(
                safe.width,
                safe.height,
                dockSize,
                dockAtBottom,
                outlineVisible,
            )
        val panel = if (dock) panels.dock else panels.app
        return RectF(panel.left + safe.left, panel.top + safe.top, panel.right + safe.left, panel.bottom + safe.top)
    }

    /**
     * 为窗口生成低分辨率模糊底图，缓存按版本、几何、窗口位置及主题区分
     */
    @Synchronized
    fun windowBackdrop(
        context: Context,
        width: Int,
        height: Int,
        displayWidth: Int,
        displayHeight: Int,
        left: Int,
        top: Int,
        geometry: DisplayGeometry?,
        area: RectF?,
        night: Boolean,
    ): Bitmap? {
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0) return null
        val requestedVersion = version
        if (backdropCacheVersion != requestedVersion) {
            backdropCache.evictAll()
            backdropCacheVersion = requestedVersion
        }
        val key = "$requestedVersion:$geometry:$area:${displayWidth}x$displayHeight:$left,$top:${width}x$height:$night"
        backdropCache.get(key)?.let { return it }
        val source =
            aspectFillCanvas(context, geometry?.canvasWidth ?: displayWidth, geometry?.canvasHeight ?: displayHeight)
                ?: return null
        if (canvasCacheVersion != requestedVersion || version != requestedVersion) return null
        val sourceArea = area ?: RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val windowArea =
            android.graphics.RectF(
                sourceArea.left + left.toFloat() / displayWidth * sourceArea.width(),
                sourceArea.top + top.toFloat() / displayHeight * sourceArea.height(),
                sourceArea.left + (left + width).toFloat() / displayWidth * sourceArea.width(),
                sourceArea.top + (top + height).toFloat() / displayHeight * sourceArea.height(),
            )
        val scale = minOf(1f, 1280f / maxOf(width, height))
        val sourceRect =
            RectF(
                windowArea.left.coerceIn(0f, source.width - 1f),
                windowArea.top.coerceIn(0f, source.height - 1f),
                windowArea.right.coerceIn(1f, source.width.toFloat()),
                windowArea.bottom.coerceIn(1f, source.height.toFloat()),
            )
        if (sourceRect.isEmpty) return null
        val bitmap = createBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
        val matrix =
            android.graphics.Matrix().apply {
                setRectToRect(
                    sourceRect,
                    RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    android.graphics.Matrix.ScaleToFit.FILL,
                )
            }
        android.graphics.Canvas(bitmap).drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        if (version == requestedVersion) backdropCache.put(key, bitmap)
        return bitmap
    }

    /**
     * 内存紧张时在后台释放缓存，已显示的位图仍由视图持有，不能主动回收
     */
    @Synchronized
    fun trimBackdropCache() {
        backdropCache.evictAll()
        canvasCache.evictAll()
    }

    /** 画布位图就绪监听（首次构建/版本更换重建后回调；参数 = 画布版本号）。 */
    private val canvasReadyListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    fun addCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.add(listener)
    }

    fun removeCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.remove(listener)
    }

    /** 按整幅画布等比填充壁纸，按尺寸和版本缓存；后台调用，返回位图只读 */
    @Synchronized
    fun aspectFillCanvas(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val requestedVersion = version
        val key = "${canvasWidth}x$canvasHeight"
        canvasCache.get(key)?.let { cached ->
            if (canvasCacheVersion == requestedVersion) return cached
        }
        if (canvasCacheVersion != requestedVersion) canvasCache.evictAll()
        val source = decode(context, maxOf(canvasWidth, canvasHeight) * 2) ?: return null
        val bitmap = createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        val bounds = calibration(context).bounds(source.width, source.height, canvasWidth.toFloat(), canvasHeight.toFloat())
        canvas.drawBitmap(
            source,
            null,
            android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        // 解码期间若切换壁纸，本次结果仍归属于旧版本，下次读取会重新生成
        canvasCacheVersion = requestedVersion
        canvasCache.put(key, bitmap)
        canvasReadyListeners.forEach { runCatching { it(requestedVersion) } }
        return bitmap
    }
}
