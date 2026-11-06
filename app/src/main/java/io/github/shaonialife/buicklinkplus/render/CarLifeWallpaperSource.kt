package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Bitmap
import io.github.shaonialife.buicklinkplus.ui.GeneratedWallpapers
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * P1B 壁纸来源（可选组件）：从 link_plus [WallpaperStore] 读取当前壁纸，按画布尺寸做 cover
 * 预合成后异步回调，供 [CarLifeComposer.setWallpaper] 使用。
 *
 * 为什么需要单独一层：合成器只接受位图、不碰文件/SharedPreferences；用户更换壁纸时由本类
 * 监听 [WallpaperStore] 版本变化自动重载，合成器只负责上传纹理。
 *
 * 线程模型：[start]/[updateCanvas]/[stop] 任意线程可调用；回调在后台线程（`Bg` 池）触发。
 * 回调的 Bitmap 属于 WallpaperStore 的画布缓存（可能被复用），**调用方不得 recycle**。
 *
 * ### 接入步骤（Lead 后续统一接线）
 * ```
 * val wallpaperSource = CarLifeWallpaperSource(context, log = ::log)
 * composer.start()
 * wallpaperSource.start(profile.width, profile.height) { bitmap -> composer.setWallpaper(bitmap) }
 * // detach：
 * wallpaperSource.stop()
 * composer.stop()
 * ```
 */
class CarLifeWallpaperSource(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private var canvasWidth = 0
    private var canvasHeight = 0
    private var callback: ((Bitmap?) -> Unit)? = null
    private var generation = 0L
    private var started = false

    /** WallpaperStore 版本变化（切换/编辑壁纸）时重载；回调线程由 WallpaperStore 决定。 */
    private val versionListener: (Long) -> Unit = { requestReload() }

    /**
     * 开始加载并监听壁纸变化（可重复调用以更换回调/画布尺寸）。
     *
     * @param canvasWidth 编码画布宽（= VideoProfile.width）
     * @param canvasHeight 编码画布高
     * @param onWallpaper 壁纸就绪回调（后台线程；null = 加载失败，合成器应回退纯色底）
     */
    fun start(
        canvasWidth: Int,
        canvasHeight: Int,
        onWallpaper: (Bitmap?) -> Unit,
    ) {
        synchronized(lock) {
            this.canvasWidth = canvasWidth.coerceAtLeast(1)
            this.canvasHeight = canvasHeight.coerceAtLeast(1)
            callback = onWallpaper
            if (!started) {
                started = true
                WallpaperStore.addListener(versionListener)
            }
        }
        requestReload()
    }

    /** 画布尺寸变化（重新 attach / profile 变化）：按新尺寸重载壁纸。 */
    fun updateCanvas(
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        synchronized(lock) {
            if (!started) return
            this.canvasWidth = canvasWidth.coerceAtLeast(1)
            this.canvasHeight = canvasHeight.coerceAtLeast(1)
        }
        requestReload()
    }

    /** 强制重载当前壁纸（用户换图后由监听自动触发，一般无需手动调用）。 */
    fun refresh() {
        requestReload()
    }

    /** 停止监听；进行中的加载结果会被丢弃（不会回调已过期的画布尺寸）。 */
    fun stop() {
        val wasStarted =
            synchronized(lock) {
                val was = started
                started = false
                callback = null
                generation++
                was
            }
        if (wasStarted) WallpaperStore.removeListener(versionListener)
    }

    private fun requestReload() {
        var generationSnapshot = 0L
        var width = 0
        var height = 0
        synchronized(lock) {
            if (!started) return
            generation++
            generationSnapshot = generation
            width = canvasWidth
            height = canvasHeight
        }
        Bg.run("carlife-wallpaper") {
            // WallpaperStore 无用户壁纸时会返回生成壁纸；这里再兜一层，避免任何情况下回黑底
            val bitmap =
                runCatching { WallpaperStore.aspectFillCanvas(appContext, width, height) }.getOrNull()
                    ?: runCatching {
                        GeneratedWallpapers.render(appContext, GeneratedWallpapers.DEFAULT_INDEX, width, height)
                    }.getOrNull()
            var deliver: ((Bitmap?) -> Unit)? = null
            synchronized(lock) {
                if (started && generation == generationSnapshot) {
                    deliver = callback
                }
            }
            if (bitmap == null) {
                log("CARLIFE-COMPOSER: 壁纸加载/生成失败，合成器使用纯色底")
            } else {
                log("CARLIFE-COMPOSER: 壁纸来源已加载 ${bitmap.width}x${bitmap.height}（画布 ${width}x$height）")
            }
            deliver?.invoke(bitmap)
        }
    }
}
