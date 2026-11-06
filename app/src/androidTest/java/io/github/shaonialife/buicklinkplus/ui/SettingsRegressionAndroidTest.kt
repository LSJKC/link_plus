package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.DefaultMapPrefs
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 检查图片解码、设置保存和窗口显示 */
class SettingsRegressionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 隔离测试壁纸和偏好，保留原壁纸 */
    private fun isolated(): Context =
        object : ContextWrapper(context) {
            override fun getSharedPreferences(
                name: String,
                mode: Int,
            ) = context.getSharedPreferences("settings-regression-$name", mode)

            override fun getFilesDir(): File = File(context.cacheDir, "settings-regression-wallpapers").apply { mkdirs() }
        }

    /** 检查自选图片、读取失败和旧版壁纸引用 */
    @Test fun userWallpaperPersistsAndRemovedAssetsFallBackToColor() {
        val isolated = isolated()
        val prefs = isolated.getSharedPreferences("wallpaper_calibration", 0)
        prefs.edit().clear().commit()
        val source = File(isolated.filesDir, "sample.png")
        try {
            val sample = Bitmap.createBitmap(840, 420, Bitmap.Config.ARGB_8888)
            try {
                sample.eraseColor(android.graphics.Color.MAGENTA)
                source.outputStream().use { assertTrue(sample.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                sample.recycle()
            }
            assertTrue(WallpaperStore.save(isolated, android.net.Uri.fromFile(source), false, WallpaperCalibration(120, 100, -100)))
            assertNotNull(WallpaperStore.userWallpaperFile(isolated))
            val decoded = WallpaperStore.decode(isolated, 420)
            assertNotNull(decoded)
            assertTrue(maxOf(decoded!!.width, decoded.height) <= 420)
            decoded.recycle()
            assertEquals(120, WallpaperStore.calibration(isolated).zoomPercent)
            val before = prefs.all.toMap()
            val missing = android.net.Uri.fromFile(File(isolated.filesDir, "missing.png"))
            assertFalse(WallpaperStore.save(isolated, missing, false, WallpaperCalibration()))
            assertEquals(before, prefs.all)
            assertTrue(WallpaperStore.resetToDefault(isolated))
            assertNull(WallpaperStore.userWallpaperFile(isolated))
            prefs.edit().putString("image", "asset:removed.jpg").commit()
            val fallback = WallpaperStore.decode(isolated, 420)
            assertNotNull("旧引用也能显示背景", fallback)
            fallback?.recycle()
            assertTrue(WallpaperStore.save(isolated, null, false, WallpaperCalibration()))
            assertEquals("", prefs.getString("image", null))
        } finally {
            WallpaperStore.userWallpaperFile(isolated)?.delete()
            source.delete()
            prefs.edit().clear().commit()
        }
    }

    /** 检查手机和车机共用偏好，以及未连接时的交接失败 */
    @Test fun mapPanelExposesSharedScaleAndSafeDisconnectedHandoff() {
        val prefs = context.getSharedPreferences("settings", 0)
        val existed = prefs.contains("map_content_scale")
        val previous = DefaultMapPrefs.contentScale(context)
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra("menu", SettingsMenus.DEFAULT_MAP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        try {
            await { (activity.findViewById<SeekBar>(R.id.mapScaleSeek)?.width ?: 0) > 0 && activity.hasWindowFocus() }
            instrumentation.runOnMainSync {
                val seek = activity.findViewById<SeekBar>(R.id.mapScaleSeek)
                assertEquals(10, seek.max)
                seek.progress = 0
                assertTrue(activity.findViewById<TextView>(R.id.mapScaleValue).text.contains("50%"))
                seek.progress = 10
                assertTrue(activity.findViewById<TextView>(R.id.mapScaleValue).text.contains("100%"))
            }
            DefaultMapPrefs.setContentScale(context, 87)
            assertEquals(85, DefaultMapPrefs.contentScale(context))
            assertEquals("请先连接车机", VirtualLauncher.handoffMap(true))
            instrumentation.runOnMainSync {
                for (id in listOf(R.id.mapToPhone, R.id.mapToCar)) {
                    val button = activity.findViewById<android.widget.Button>(id)
                    assertNull("地图交接按钮不能残留系统抬升动画", button.stateListAnimator)
                    assertEquals(0f, button.elevation)
                    assertTrue("地图交接按钮触达高度不足", button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    val background = button.background as android.graphics.drawable.RippleDrawable
                    val shape = background.getDrawable(0) as android.graphics.drawable.GradientDrawable
                    assertEquals(14 * activity.resources.displayMetrics.density, shape.cornerRadius, 0.5f)
                }
                activity.findViewById<android.widget.ScrollView>(R.id.phonePanelScroll).fullScroll(View.FOCUS_DOWN)
            }
            await {
                listOf(R.id.mapToPhone, R.id.mapToCar).all { id ->
                    val button = activity.findViewById<View>(id)
                    val visible = android.graphics.Rect()
                    button.getGlobalVisibleRect(visible) && visible.height() == button.height
                }
            }
            screenshot(activity, "map-controls")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (existed) prefs.edit().putInt("map_content_scale", previous).commit() else prefs.edit().remove("map_content_scale").commit()
        }
    }

    /** 检查未安装高德时，车机设置仍显示比例和交接选项 */
    @Test fun carMapControlsRemainVisibleWithoutAmapMode() {
        instrumentation.runOnMainSync {
            val page =
                android.view.LayoutInflater
                    .from(context)
                    .inflate(R.layout.fragment_panel_map, null, false)
            val mode = page.findViewById<View>(R.id.amapModeSection)
            for (visibility in listOf(View.VISIBLE, View.GONE)) {
                mode.visibility = visibility
                for (id in listOf(R.id.mapScaleSeek, R.id.mapToPhone, R.id.mapToCar)) {
                    var ancestor: View? = page.findViewById(id)
                    while (ancestor != null) {
                        assertEquals("非高德模式下地图控件被隐藏", View.VISIBLE, ancestor.visibility)
                        ancestor = ancestor.parent as? View
                    }
                }
            }
        }
    }

    /** 检查通透预设的背景参数、端值和内容不透明度 */
    @Test fun transparentPresetKeepsForegroundAndRemovesBackdropWash() {
        val isolated = isolated()
        instrumentation.runOnMainSync {
            val image = android.widget.ImageView(isolated)
            val scrim = View(isolated)
            val text = TextView(isolated).apply { text = "清晰内容" }
            CarAppearancePrefs.applyPreset(isolated, CarAppearancePrefs.Preset.TRANSPARENT)
            CarAppearancePrefs.applyBackdrop(image, scrim, 1f)
            assertEquals(100, CarAppearancePrefs.transparency(isolated))
            assertEquals(0, CarAppearancePrefs.blur(isolated))
            assertEquals(0, android.graphics.Color.alpha((scrim.background as android.graphics.drawable.ColorDrawable).color))
            assertEquals(1f, text.alpha)
            CarAppearancePrefs.setTransparency(isolated, -10)
            assertEquals(100, CarAppearancePrefs.opacity(isolated))
            CarAppearancePrefs.setTransparency(isolated, 150)
            assertEquals(0, CarAppearancePrefs.opacity(isolated))
        }
        val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val virtual =
            checkNotNull(
                displays.createVirtualDisplay(
                    "004外观检查",
                    32,
                    32,
                    160,
                    null,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                ),
            )
        try {
            val car =
                object : ContextWrapper(context.createDisplayContext(virtual.display)) {
                    override fun getSharedPreferences(
                        name: String,
                        mode: Int,
                    ) = isolated.getSharedPreferences(name, mode)
                }
            instrumentation.runOnMainSync {
                assertTrue(UiAppearance.isCar(car))
                CarAppearancePrefs.applyPreset(car, CarAppearancePrefs.Preset.FROSTED)
                assertEquals(255, UiAppearance.drawable(car, R.drawable.bg_card)!!.alpha)
                CarAppearancePrefs.applyPreset(car, CarAppearancePrefs.Preset.TRANSPARENT)
                for (resource in listOf(R.drawable.bg_card, R.drawable.bg_glass_outline, R.color.glass_stroke)) {
                    assertEquals("车机背景或描边未透明", 0, UiAppearance.drawable(car, resource)!!.alpha)
                }
            }
        } finally {
            virtual.release()
        }
    }

    /** 取消后关闭迟到的采集管道，停止请求沿用原会话 ID */
    @Test fun cancelledAudioInitializationClosesLatePipe() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val pipe = android.os.ParcelFileDescriptor.createReliablePipe()
        val current =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val startedSession =
            java.util.concurrent.atomic
                .AtomicLong()
        val stoppedSession =
            java.util.concurrent.atomic
                .AtomicLong()
        val service =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(io.github.shaonialife.buicklinkplus.compat.ICarInput::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "capabilities" -> 1
                    "startAudioCapture" -> {
                        startedSession.set(arguments!![0] as Long)
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        pipe[0]
                    }
                    "stopAudioCapture" -> {
                        stoppedSession.set(arguments!![0] as Long)
                        stopped.countDown()
                        null
                    }
                    else -> null
                }
            } as io.github.shaonialife.buicklinkplus.compat.ICarInput
        var error: Throwable? = null
        val caller =
            Thread {
                try {
                    io.github.shaonialife.buicklinkplus.audio.ShellCapture
                        .open(service, 48000) { current.get() }
                        .close()
                } catch (failure: Exception) {
                    error = failure
                } finally {
                    returned.countDown()
                }
            }
        try {
            caller.start()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            current.set(false)
            assertTrue("取消必须及时解除等待", returned.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull("已取消的初始化不能成功", error)
            release.countDown()
            assertTrue(stopped.await(3, java.util.concurrent.TimeUnit.SECONDS))
            await { !pipe[0].fileDescriptor.valid() }
            assertEquals(startedSession.get(), stoppedSession.get())
        } finally {
            release.countDown()
            caller.join(2000)
            pipe.forEach { runCatching { it.close() } }
        }
    }

    /** 清除壁纸后取消编辑，原配置保持不变 */
    @Test fun clearWallpaperRemainsDraftUntilSaved() {
        val prefs = context.getSharedPreferences("wallpaper_calibration", 0)
        val before = prefs.all.toMap()
        val activity =
            instrumentation.startActivitySync(
                Intent(context, WallpaperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as Activity
        try {
            await { activity.findViewById<View>(R.id.wallpaperSave).isEnabled && activity.hasWindowFocus() }
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<View>(R.id.wallpaperReset).performClick())
            }
            await { activity.findViewById<View>(R.id.wallpaperSave).isEnabled }
            assertEquals(before, prefs.all)
            screenshot(activity, "wallpaper-draft")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        assertEquals(before, prefs.all)
    }

    /** 保存已取得焦点并完成布局的窗口 */
    private fun screenshot(
        activity: Activity,
        name: String,
    ) {
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val directory = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    /** 等待布局完成，超时则失败 */
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            instrumentation.runOnMainSync { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        error("界面未在时限内就绪")
    }
}
