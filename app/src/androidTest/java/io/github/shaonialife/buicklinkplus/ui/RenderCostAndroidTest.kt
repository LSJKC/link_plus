package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.PixelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 测量隐藏输入的位图和 GL 开销 */
class RenderCostAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 检查卡片只绘制在允许的留白区域 */
    @Test fun outsideGlanceUsesExplicitPermissionAndKeepsSafeContent() {
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        lateinit var preview: TextureView
        instrumentation.runOnMainSync {
            preview = TextureView(activity)
            activity.setContentView(FrameLayout(activity).apply { addView(preview, FrameLayout.LayoutParams(986, 260)) })
        }
        await { preview.isAvailable && activity.hasWindowFocus() }
        val geometry = DisplayGeometry.create(1972, 520, 1972, 520, PixelArea(278, 44, 1686, 520))
        val images = mutableListOf<IntArray>()
        try {
            for (allowed in listOf(false, true)) {
                instrumentation.runOnMainSync { checkNotNull(preview.surfaceTexture).setDefaultBufferSize(1972, 520) }
                val output = Surface(preview.surfaceTexture)
                val composer = DualDisplayComposer(output, geometry.copy(outsideUiAllowed = allowed), false, context)
                composer.onSurfacesReady = { dock, app, _, _ ->
                    for (surface in listOf(dock, app)) {
                        val canvas = surface.lockCanvas(null)
                        canvas.drawColor(Color.rgb(30, 24, 45))
                        surface.unlockCanvasAndPost(canvas)
                    }
                }
                try {
                    composer.start(111, 476, 1297, 476)
                    await { composer.composedFrames >= 25 }
                    val pixels = IntArray(1972 * 520)
                    instrumentation.runOnMainSync {
                        val bitmap = checkNotNull(preview.getBitmap(1972, 520))
                        bitmap.getPixels(pixels, 0, 1972, 0, 0, 1972, 520)
                        File(context.getExternalFilesDir(null), "test-screenshots")
                            .apply { mkdirs() }
                            .resolve(
                                "side-glance-permission-$allowed.png",
                            ).outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    images += pixels
                } finally {
                    composer.stop()
                    await { composer.floatingPanelLayout == null }
                    output.release()
                }
            }
            var changed = 0
            for (y in 0 until 520) {
                for (x in 0 until 1972) {
                    val index = y * 1972 + x
                    if (x >= 278) assertEquals("信息卡不能改变安全区和右侧区域", images[0][index], images[1][index])
                    if (x in 25..250 && y in 130..430 && images[0][index] != images[1][index]) changed++
                }
            }
            assertTrue("明确许可后应在左侧显示真实文字卡片", changed > 1000)
            result("side-glance-result.txt", "许可关闭=仅壁纸 许可开启=左侧只读时间与媒体信息 安全区和右侧像素一致 变化像素=$changed\n")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 检查画布缓存上限，以及淘汰时是否保留仍在显示的图片 */
    @Test fun resizedWallpaperCacheIsBoundedWithoutRecyclingDisplayedBitmap() {
        WallpaperStore.trimBackdropCache()
        val first = checkNotNull(WallpaperStore.aspectFillCanvas(context, 1200, 360))
        val expected = first.getPixel(300, 180)
        try {
            for (index in 1..18) checkNotNull(WallpaperStore.aspectFillCanvas(context, 1200 + index * 24, 360))
            val field = WallpaperStore::class.java.getDeclaredField("canvasCache").apply { isAccessible = true }
            val cache = field.get(WallpaperStore)
            val values =
                when (cache) {
                    is Map<*, *> -> cache.values
                    is android.util.LruCache<*, *> -> cache.snapshot().values
                    else -> error("未知的壁纸缓存类型")
                }.filterIsInstance<Bitmap>()
            val bytes = values.sumOf { it.allocationByteCount.toLong() }
            val text = "生成尺寸=19 缓存图片=${values.size} 缓存字节=$bytes 首图仍有效=${!first.isRecycled}\n"
            result("wallpaper-cache-result.txt", text)
            assertFalse("缓存淘汰不能主动回收窗口仍持有的位图", first.isRecycled)
            assertEquals(expected, first.getPixel(300, 180))
            assertTrue("多尺寸壁纸缓存必须限制在 16 MiB 内，实际=$bytes", bytes <= 16L * 1024 * 1024)
            val recent = checkNotNull(WallpaperStore.aspectFillCanvas(context, 1632, 360))
            assertTrue("最近使用尺寸应继续命中缓存", recent === WallpaperStore.aspectFillCanvas(context, 1632, 360))
        } finally {
            WallpaperStore.trimBackdropCache()
        }
    }

    /** 地图全屏遮住应用后，隐藏应用的动画不提高输出帧率 */
    @Test fun coveredApplicationAnimationDoesNotDriveVisibleOutput() {
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        lateinit var preview: TextureView
        instrumentation.runOnMainSync {
            preview = TextureView(activity)
            activity.setContentView(FrameLayout(activity).apply { addView(preview, FrameLayout.LayoutParams(640, 360)) })
        }
        await { preview.isAvailable && activity.hasWindowFocus() }
        instrumentation.runOnMainSync { checkNotNull(preview.surfaceTexture).setDefaultBufferSize(640, 360) }
        val output = Surface(preview.surfaceTexture)
        val composer = DualDisplayComposer(output, DisplayGeometry.create(640, 360, 640, 360, PixelArea(0, 0, 640, 360)), false, context)
        val producing = AtomicBoolean(true)
        var producer: Thread? = null
        composer.targetFps = 60
        composer.onSurfacesReady = { dock, app, _, map ->
            for ((surface, color) in listOf(dock to Color.BLUE, checkNotNull(map) to Color.GREEN)) {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(color)
                surface.unlockCanvasAndPost(canvas)
            }
            producer =
                Thread({
                    var frame = 0
                    while (producing.get()) {
                        val canvas = app.lockCanvas(null)
                        canvas.drawColor(if (frame++ % 2 == 0) Color.RED else Color.YELLOW)
                        app.unlockCanvasAndPost(canvas)
                        SystemClock.sleep(8)
                    }
                }, "render-cost-producer").apply { start() }
        }
        try {
            composer.start(72, 360, 568, 360, mapWidthPx = 568, mapHeightPx = 360)
            composer.setTileLayout(DualDisplayComposer.TileLayout.FULLSCREEN)
            await { composer.presentedMapLayout == DualDisplayComposer.TileLayout.FULLSCREEN }
            SystemClock.sleep(300)
            val start = composer.composedFrames
            val at = SystemClock.elapsedRealtime()
            SystemClock.sleep(2400)
            val fps = (composer.composedFrames - start) * 1000.0 / (SystemClock.elapsedRealtime() - at)
            var screenshot: Bitmap? = null
            instrumentation.runOnMainSync { screenshot = preview.getBitmap(640, 360) }
            val bitmap = checkNotNull(screenshot)
            try {
                assertEquals("地图内容不能受隐藏应用动画影响", Color.GREEN, bitmap.getPixel(350, 180))
                File(context.getExternalFilesDir(null), "test-screenshots")
                    .apply { mkdirs() }
                    .resolve("render-cost-map-fullscreen.png")
                    .outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            composer.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
            await { composer.presentedMapLayout == DualDisplayComposer.TileLayout.HIDDEN }
            val visibleStart = composer.composedFrames
            val visibleAt = SystemClock.elapsedRealtime()
            SystemClock.sleep(1600)
            val visibleFps = (composer.composedFrames - visibleStart) * 1000.0 / (SystemClock.elapsedRealtime() - visibleAt)
            result("render-cost-result.txt", "隐藏应用动画时输出帧率=$fps 应用可见时输出帧率=$visibleFps 目标帧率=60 地图像素=绿色\n")
            assertTrue("全屏遮挡后应保持原静止心跳，实际=$fps", fps in 20.0..40.0)
            assertTrue("应用恢复可见后仍应响应动画，实际=$visibleFps", visibleFps > 40.0)
        } finally {
            producing.set(false)
            producer?.join(2000)
            assertFalse("停止后生产线程必须退出", producer?.isAlive == true)
            composer.stop()
            await { composer.floatingPanelLayout == null }
            output.release()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 记录同一模拟器上的前后测量值 */
    private fun result(
        name: String,
        value: String,
    ) {
        File(context.getExternalFilesDir(null), name).writeText(value)
    }

    /** 等待窗口或合成提交，超时则失败 */
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "等待渲染状态超时" }
            SystemClock.sleep(20)
        }
    }
}
