package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.OverlayCard
import io.github.shaonialife.buicklinkplus.render.PixelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil

/** 检查轮廓开关及 GL 合成输出 */
class PanelOutlineAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 检查横竖屏面板内外的壁纸是否连续 */
    @Test fun outlinedWallpaperSamplesPhysicalPanelPosition() {
        val prefs = context.getSharedPreferences("settings", 0)
        val had = prefs.contains("panelOutlineVisible")
        val original = CarAppearancePrefs.panelOutlineVisible(context)
        try {
            for (portrait in listOf(false, true)) {
                verifyPhysicalWallpaper(portrait, false)
                verifyPhysicalWallpaper(portrait, true)
            }
        } finally {
            if (had) {
                prefs
                    .edit()
                    .putBoolean(
                        "panelOutlineVisible",
                        original,
                    ).commit()
            } else {
                prefs.edit().remove("panelOutlineVisible").commit()
            }
            UiAppearance.refreshWindows()
        }
    }

    /** 比较 OES 输出和同位置壁纸像素 */
    private fun verifyPhysicalWallpaper(
        portrait: Boolean,
        coordinatePattern: Boolean,
    ) {
        val width = if (portrait) 360 else 640
        val height = if (portrait) 640 else 360
        val geometry = DisplayGeometry.create(width, height, width, height, PixelArea(12, 12, width - 12, height - 12))
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        lateinit var preview: TextureView
        instrumentation.runOnMainSync {
            preview = TextureView(activity)
            activity.setContentView(FrameLayout(activity).apply { addView(preview, FrameLayout.LayoutParams(width, height)) })
        }
        await { preview.isAvailable && activity.hasWindowFocus() }
        instrumentation.runOnMainSync { checkNotNull(preview.surfaceTexture).setDefaultBufferSize(width, height) }
        val output = Surface(preview.surfaceTexture)
        val composer = DualDisplayComposer(output, geometry, portrait, context)
        val dockWidth = if (portrait) geometry.content.width else 72
        val dockHeight = if (portrait) 72 else geometry.content.height
        val appWidth = if (portrait) dockWidth else geometry.content.width - dockWidth
        val appHeight = if (portrait) geometry.content.height - dockHeight else dockHeight
        WallpaperStore.trimBackdropCache()
        val source =
            if (coordinatePattern) {
                // 校准图用连续坐标色带，让真实几何偏移与原始细描线的二次采样平滑分别验证
                val pixels =
                    IntArray(width * height) { index ->
                        val x = index % width
                        val y = index / width
                        Color.rgb(
                            (120 + 90 * kotlin.math.sin(x / 18.0)).toInt(),
                            (120 + 90 * kotlin.math.sin(y / 19.0)).toInt(),
                            (
                                120 +
                                    90 * kotlin.math.sin((x + y) / 29.0)
                            ).toInt(),
                        )
                    }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val cacheField = WallpaperStore::class.java.getDeclaredField("canvasCache").apply { isAccessible = true }

                    @Suppress("UNCHECKED_CAST")
                    val cache = cacheField.get(WallpaperStore) as android.util.LruCache<String, Bitmap>
                    cache.put("${width}x$height", bitmap)
                    WallpaperStore::class.java
                        .getDeclaredField("canvasCacheVersion")
                        .apply {
                            isAccessible = true
                        }.setLong(WallpaperStore, WallpaperStore.version)
                }
            } else {
                checkNotNull(WallpaperStore.aspectFillCanvas(context, width, height))
            }
        var surfaces: List<Surface> = emptyList()
        var legacyGeometry = coordinatePattern

        fun submit(outline: Boolean) {
            surfaces.forEachIndexed { index, surface ->
                val w = if (index == 0) dockWidth else appWidth
                val h = if (index == 0) dockHeight else appHeight
                val area =
                    if (legacyGeometry) {
                        val safe = geometry.content
                        RectF(safe.left.toFloat(), safe.top.toFloat(), safe.right.toFloat(), safe.bottom.toFloat()).apply {
                            if (portrait) {
                                val edge = (safe.bottom - dockHeight).toFloat()
                                if (index == 0) top = edge else bottom = edge
                            } else {
                                val edge = (safe.left + dockWidth).toFloat()
                                if (index == 0) right = edge else left = edge
                            }
                        }
                    } else {
                        WallpaperStore.panelArea(geometry, dockWidth, dockHeight, portrait, index == 0, outline)
                    }
                val bitmap = checkNotNull(WallpaperStore.windowBackdrop(context, w, h, w, h, 0, 0, geometry, area, false))
                val canvas = surface.lockCanvas(null)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, canvas.width, canvas.height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
                surface.unlockCanvasAndPost(canvas)
            }
        }
        composer.onSurfacesReady = { dock, app, _, _ ->
            surfaces = listOf(dock, app)
            submit(true)
        }
        try {
            CarAppearancePrefs.setPanelOutlineVisible(context, true)
            composer.start(dockWidth, dockHeight, appWidth, appHeight)
            if (coordinatePattern) {
                await { composer.composedFrames >= 10 }
                var old: Bitmap? = null
                instrumentation.runOnMainSync { old = preview.getBitmap(width, height) }
                val previous = checkNotNull(old)
                try {
                    val panel = checkNotNull(composer.floatingPanelLayout).app
                    var error = 0L
                    var count = 0
                    val startY = (panel.top + geometry.content.top + panel.radius + 3).toInt()
                    val endY = (panel.bottom + geometry.content.top - panel.radius - 3).toInt()
                    val startX = (panel.left + geometry.content.left + panel.radius + 3).toInt()
                    val endX = (panel.right + geometry.content.left - panel.radius - 3).toInt()
                    for (y in startY until endY) {
                        for (x in startX until endX) {
                            error += difference(source.getPixel(x, y), previous.getPixel(x, y))
                            count++
                        }
                    }
                    val mean = error.toDouble() / count
                    File(context.getExternalFilesDir(null), "physical-wallpaper-result.txt")
                        .appendText("旧逻辑采样回放 竖屏=$portrait 平均通道误差=$mean\n")
                    assertTrue("坐标图必须能够检出旧逻辑的采样错位，不能使用没有判别力的图案", mean > 3.0)
                } finally {
                    previous.recycle()
                }
                legacyGeometry = false
            }
            for (outline in listOf(true, false, true)) {
                CarAppearancePrefs.setPanelOutlineVisible(context, outline)
                submit(outline)
                val after = composer.composedFrames + 8
                await { composer.composedFrames >= after }
                val layout = checkNotNull(composer.floatingPanelLayout)
                val pixels = IntArray(width * height)
                instrumentation.runOnMainSync {
                    val bitmap = checkNotNull(preview.getBitmap(width, height))
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()
                }
                val frame = Frame(pixels, width)
                val patternName = if (coordinatePattern) "coordinates" else "wallpaper"
                val direction = if (portrait) "portrait" else "landscape"
                saveFrame(
                    frame,
                    width,
                    height,
                    "physical-$patternName-$direction-$outline",
                )
                var maxError = 0
                var totalError = 0L
                var samples = 0
                var largeErrors = 0
                for (panel in listOf(layout.dock, layout.app)) {
                    val rect =
                        RectF(
                            panel.left + geometry.content.left,
                            panel.top + geometry.content.top,
                            panel.right + geometry.content.left,
                            panel.bottom + geometry.content.top,
                        )
                    for (y in rect.top.toInt() + 5 until rect.bottom.toInt() - 5) {
                        for (x in rect.left.toInt() + 5 until rect.right.toInt() - 5) {
                            if (!panel.contains(x + 0.5f - geometry.content.left, y + 0.5f - geometry.content.top)) continue
                            val dx = minOf(x - rect.left, rect.right - x)
                            val dy = minOf(y - rect.top, rect.bottom - y)
                            if (dx < panel.radius + 3 && dy < panel.radius + 3) continue
                            val error = difference(source.getPixel(x, y), frame.color(x, y))
                            maxError = maxOf(maxError, error)
                            totalError += error
                            samples++
                            if (error > 8) largeErrors++
                        }
                    }
                }
                val meanError = totalError.toDouble() / samples
                val largeFraction = largeErrors.toDouble() / samples
                File(
                    context.getExternalFilesDir(null),
                    "physical-wallpaper-result.txt",
                ).appendText("坐标图=$coordinatePattern 竖屏=$portrait 轮廓=$outline 最大通道误差=$maxError 平均误差=$meanError 大于8比例=$largeFraction\n")
                if (coordinatePattern) assertTrue("连续坐标校准图不得出现几何偏移：误差=$maxError", maxError <= 3)
                // 原图含细描线，裁图与 GL 两次线性采样允许局部平滑，整体偏移必须由全域统计拦截
                assertTrue("实际面板壁纸不得整体错位：竖屏=$portrait 轮廓=$outline 平均误差=$meanError", meanError <= 1.5)
            }
        } finally {
            composer.stop()
            await { composer.floatingPanelLayout == null }
            output.release()
            WallpaperStore.trimBackdropCache()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 检查旧轮廓设置保留、背景参数独立及外部变更同步 */
    @Test fun outlineSwitchIsIndependentAndRefreshesFromSharedPreferences() {
        val isolated =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ) = context.getSharedPreferences("outline-test-$name", mode)
            }
        val isolatedPrefs = isolated.getSharedPreferences("settings", 0)
        isolatedPrefs.edit().clear().commit()
        try {
            assertFalse(CarAppearancePrefs.panelOutlineVisible(isolated))
            assertFalse(isolatedPrefs.contains("panelOutlineVisible"))
            CarAppearancePrefs.setPanelOutlineVisible(isolated, false)
            CarAppearancePrefs.Preset.entries.forEach {
                CarAppearancePrefs.applyPreset(isolated, it)
                assertFalse("背景预设不能重新打开轮廓", CarAppearancePrefs.panelOutlineVisible(isolated))
            }
            CarAppearancePrefs.setTransparency(isolated, 83)
            CarAppearancePrefs.setBlur(isolated, 27)
            CarAppearancePrefs.setPanelOutlineVisible(isolated, true)
            assertEquals(83, CarAppearancePrefs.transparency(isolated))
            assertEquals(27, CarAppearancePrefs.blur(isolated))
        } finally {
            isolatedPrefs.edit().clear().commit()
        }
        val prefs = context.getSharedPreferences("settings", 0)
        val had = prefs.contains("panelOutlineVisible")
        val original = CarAppearancePrefs.panelOutlineVisible(context)
        val opacity = CarAppearancePrefs.opacity(context)
        val blur = CarAppearancePrefs.blur(context)
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra("menu", SettingsMenus.CAR_APPEARANCE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        try {
            await { activity.hasWindowFocus() && activity.findViewById<SwitchCompat>(R.id.panelOutlineSwitch)?.isLaidOut == true }
            instrumentation.runOnMainSync {
                val control = activity.findViewById<SwitchCompat>(R.id.panelOutlineSwitch)
                assertEquals(original, control.isChecked)
                control.performClick()
                assertEquals(!original, control.isChecked)
                assertEquals(!original, CarAppearancePrefs.panelOutlineVisible(context))
                assertEquals(opacity, CarAppearancePrefs.opacity(context))
                assertEquals(blur, CarAppearancePrefs.blur(context))
            }
            CarAppearancePrefs.setPanelOutlineVisible(context, original)
            await { activity.findViewById<SwitchCompat>(R.id.panelOutlineSwitch).isChecked == original }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (had) {
                prefs
                    .edit()
                    .putBoolean(
                        "panelOutlineVisible",
                        original,
                    ).commit()
            } else {
                prefs.edit().remove("panelOutlineVisible").commit()
            }
            UiAppearance.refreshWindows()
        }
    }

    /** 逐像素检查关闭轮廓后的圆角、间隙和拼接线 */
    @Test fun actualComposerRemovesAndRestoresBothPanelOutlinesWithoutNewInput() {
        val prefs = context.getSharedPreferences("settings", 0)
        val had = prefs.contains("panelOutlineVisible")
        val original = CarAppearancePrefs.panelOutlineVisible(context)
        try {
            for (portrait in listOf(false, true)) verifyComposer(portrait, false)
        } finally {
            if (had) {
                prefs
                    .edit()
                    .putBoolean(
                        "panelOutlineVisible",
                        original,
                    ).commit()
            } else {
                prefs.edit().remove("panelOutlineVisible").commit()
            }
            UiAppearance.refreshWindows()
        }
    }

    /** 检查壁纸切片与合成器拼接后的缩放和位置 */
    @Test fun wallpaperSlicesRemainContinuousAfterOutlineIsHidden() {
        val prefs = context.getSharedPreferences("settings", 0)
        val had = prefs.contains("panelOutlineVisible")
        val original = CarAppearancePrefs.panelOutlineVisible(context)
        try {
            for (portrait in listOf(false, true)) verifyComposer(portrait, true)
        } finally {
            if (had) {
                prefs
                    .edit()
                    .putBoolean(
                        "panelOutlineVisible",
                        original,
                    ).commit()
            } else {
                prefs.edit().remove("panelOutlineVisible").commit()
            }
            UiAppearance.refreshWindows()
        }
    }

    /** 静止输入时修改设置，检查装饰层立即隐藏 */
    private fun verifyComposer(
        portrait: Boolean,
        wallpaper: Boolean,
    ) {
        val width = if (portrait) 360 else 640
        val height = if (portrait) 640 else 360
        val geometry = DisplayGeometry.create(width, height, width, height, PixelArea(8, 8, width - 8, height - 8))
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        val sequence = AtomicLong()
        lateinit var preview: TextureView
        instrumentation.runOnMainSync {
            preview = TextureView(activity)
            preview.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                    /**
                     * 缓冲尺寸在开始合成前统一设置
                     */
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    /**
                     * 固定测试窗口不在运行中改变缓冲
                     */
                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    /**
                     * 窗口销毁时由框架释放所属纹理
                     */
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                    /** 仅统计已显示纹理的更新帧 */
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        sequence.incrementAndGet()
                    }
                }
            activity.setContentView(FrameLayout(activity).apply { addView(preview, FrameLayout.LayoutParams(width, height)) })
        }
        await { preview.isAvailable && activity.hasWindowFocus() }
        instrumentation.runOnMainSync { checkNotNull(preview.surfaceTexture).setDefaultBufferSize(width, height) }
        val output = Surface(preview.surfaceTexture)
        val composer = DualDisplayComposer(output, geometry, portrait, context)
        val dockWidth = if (portrait) geometry.content.width else 72
        val dockHeight = if (portrait) 72 else geometry.content.height
        val appWidth = if (portrait) geometry.content.width else geometry.content.width - dockWidth
        val appHeight = if (portrait) geometry.content.height - dockHeight else geometry.content.height
        val overlay = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val source =
            if (wallpaper) {
                checkNotNull(WallpaperStore.contentCanvas(context, geometry.content.width, geometry.content.height, geometry))
            } else {
                val w = geometry.content.width
                val h = geometry.content.height
                val pixels =
                    IntArray(w * h) { index ->
                        val x = index % w
                        val y = index / w
                        Color.rgb(20 + x * 210 / w, 20 + y * 210 / h, if ((x / 17 + y / 17) % 2 == 0) 60 else 180)
                    }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
        composer.onSurfacesReady = { dock, app, _, map ->
            for ((surface, isDock) in listOf(dock to true, app to false, checkNotNull(map) to false)) {
                val area =
                    if (isDock) {
                        Rect(0, if (portrait) appHeight else 0, dockWidth, geometry.content.height)
                    } else {
                        Rect(if (portrait) 0 else dockWidth, 0, geometry.content.width, appHeight)
                    }
                val canvas = surface.lockCanvas(null)
                if (wallpaper && !isDock) {
                    val crop =
                        checkNotNull(
                            WallpaperStore.windowBackdrop(
                                context,
                                appWidth,
                                appHeight,
                                appWidth,
                                appHeight,
                                0,
                                0,
                                geometry,
                                android.graphics
                                    .RectF(
                                        area,
                                    ).apply { offset(geometry.content.left.toFloat(), geometry.content.top.toFloat()) },
                                false,
                            ),
                        )
                    canvas.drawBitmap(crop, null, Rect(0, 0, canvas.width, canvas.height), null)
                } else {
                    canvas.drawBitmap(source, area, Rect(0, 0, canvas.width, canvas.height), null)
                }
                surface.unlockCanvasAndPost(canvas)
            }
        }
        try {
            CarAppearancePrefs.setPanelOutlineVisible(context, false)
            composer.start(
                dockWidth,
                dockHeight,
                appWidth,
                appHeight,
                mapWidthPx = appWidth,
                mapHeightPx = appHeight,
            )
            await { composer.composedFrames >= 12 && sequence.get() >= 5 }
            val layout = checkNotNull(composer.floatingPanelLayout)
            val before = nextFrame(preview, sequence, width, height)
            CarAppearancePrefs.setPanelOutlineVisible(context, true)
            await { composer.floatingPanelLayout?.gap?.let { it > 0f } == true }
            val outlinedLayout = checkNotNull(composer.floatingPanelLayout)
            val visible = nextFrame(preview, sequence, width, height)
            assertNotSame(layout, outlinedLayout)
            CarAppearancePrefs.setPanelOutlineVisible(context, false)
            await { composer.floatingPanelLayout === layout }
            val hidden = nextFrame(preview, sequence, width, height)
            val name = "outline-${if (portrait) "portrait" else "landscape"}-${if (wallpaper) "wallpaper" else "pattern"}"
            saveFrame(visible, width, height, "$name-on")
            saveFrame(hidden, width, height, "$name-off")
            assertSeamless(hidden, source, geometry)
            assertTrue("再次关闭不能残留旧轮廓", before.pixels.contentEquals(hidden.pixels))
            for (panel in listOf(outlinedLayout.dock, outlinedLayout.app)) {
                var changed = 0
                var removedShadow = 0
                val y = ceil(panel.top).toInt() + geometry.content.top
                val outsideY = (panel.top.toInt() + geometry.content.top - 2).coerceAtLeast(0)
                val start = (panel.left + panel.radius + geometry.content.left).toInt()
                val end = (panel.right - panel.radius + geometry.content.left).toInt()
                for (x in start until end) {
                    if (difference(visible.color(x, y), hidden.color(x, y)) > 4) changed++
                    if (difference(visible.color(x, outsideY), hidden.color(x, outsideY)) > 2) removedShadow++
                }
                assertTrue("外围描边未按开关变化：$portrait $panel", changed > 10)
                assertTrue("外圈阴影未按开关变化：$portrait $panel", removedShadow > 10)
            }
            assertSame(layout, composer.floatingPanelLayout)
            // 同一无轮廓几何还须覆盖地图全屏分支，不重新提交输入纹理
            composer.setTileLayout(DualDisplayComposer.TileLayout.FULLSCREEN)
            await { composer.presentedMapLayout == DualDisplayComposer.TileLayout.FULLSCREEN }
            assertSeamless(nextFrame(preview, sequence, width, height), source, geometry)
            // 故意让提示层越出应用区域，矩形硬裁剪必须仍能保护 Dock
            composer.overlayCard = OverlayCard(overlay, 0f, 0f, geometry.content.width.toFloat(), geometry.content.height.toFloat())
            val covered = nextFrame(preview, sequence, width, height)
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val expected = if (layout.dock.contains(x + 0.5f, y + 0.5f)) source.getPixel(x, y) else Color.MAGENTA
                    assertTrue(
                        "无轮廓仍须把叠层限制在应用区域：($x,$y)",
                        difference(expected, covered.color(x + geometry.content.left, y + geometry.content.top)) <= 2,
                    )
                }
            }
            composer.overlayCard = null
            for (ignored in 0 until 4) {
                CarAppearancePrefs.setPanelOutlineVisible(context, true)
                await { composer.floatingPanelLayout === outlinedLayout }
                CarAppearancePrefs.setPanelOutlineVisible(context, false)
                await { composer.floatingPanelLayout === layout }
            }
            assertSeamless(nextFrame(preview, sequence, width, height), source, geometry)
        } finally {
            composer.stop()
            await { composer.floatingPanelLayout == null }
            output.release()
            overlay.recycle()
            if (!wallpaper) source.recycle()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 检查四角和拼接边缘连续，无留白或半透明接缝 */
    private fun assertSeamless(
        actual: Frame,
        expected: Bitmap,
        geometry: DisplayGeometry,
    ) {
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val color = actual.color(x + geometry.content.left, y + geometry.content.top)
                assertTrue("关闭后像素有留白、错位或边缘残留：($x,$y)", difference(expected.getPixel(x, y), color) <= 2)
            }
        }
    }

    /** 保存原始 GL 合成图像供查看 */
    private fun saveFrame(
        frame: Frame,
        width: Int,
        height: Int,
        name: String,
    ) {
        val pixels = IntArray(width * height) { frame.color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val directory = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    /** 设置变化后等待至少三帧输出，期间不提交新输入 */
    private fun nextFrame(
        preview: TextureView,
        sequence: AtomicLong,
        width: Int,
        height: Int,
    ): Frame {
        val after = sequence.get() + 3
        await { sequence.get() >= after }
        val pixels = IntArray(width * height)
        instrumentation.runOnMainSync {
            val bitmap = checkNotNull(preview.getBitmap(width, height))
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
        }
        return Frame(pixels, width)
    }

    /** 直接读取窗口纹理，包含 GPU 绘制内容 */
    private data class Frame(
        val pixels: IntArray,
        val width: Int,
    ) {
        /**
         * 按实际输出宽度读取统一的 ARGB 颜色
         */
        fun color(
            x: Int,
            y: Int,
        ): Int = pixels[y * width + x]
    }

    /**
     * 最大通道差用于区分细描边和纯黑半透明阴影
     */
    private fun difference(
        first: Int,
        second: Int,
    ): Int =
        maxOf(
            abs(Color.red(first) - Color.red(second)),
            abs(Color.green(first) - Color.green(second)),
            abs(
                Color.blue(first) - Color.blue(second),
            ),
        )

    /**
     * 有界等待只用于独立测试线程，不阻塞窗口或合成线程
     */
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        error("轮廓设置或合成输出未在期限内更新")
    }
}
