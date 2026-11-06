package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Bitmap
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * P1B 壁纸来源：直接复用 link_plus 原版 [WallpaperStore]（用户壁纸 + 原版默认底色 + 校准/桌面切片），
 * 按 CarLife 合成器画布尺寸取 aspectFill 位图后异步回调，供 [CarLifeComposer.setWallpaper] 使用。
 *
 * 本类不生成、不内置任何壁纸素材：壁纸内容完全由原版 WallpaperStore / WallpaperActivity 决定；
 * 未设置用户壁纸时原版 `aspectFillCanvas` 会返回纯色默认底（WallpaperStore.BACKGROUND_COLOR），
 * 与原版桌面观感一致；若原版加载失败则回调 null，让合成器回退同一个默认底色。
 *
 * 为什么需要单独一层：合成器只接受位图、不碰文件/SharedPreferences；用户更换壁纸时由本类
 * 监听 [WallpaperStore] 版本变化自动重载，合成器只负责上传纹理。
 *
 * 线程模型：[start]/[updateCanvas]/[stop] 任意线程可调用；回调在后台线程（`Bg` 池）触发。
 * 回调的 Bitmap 属于 WallpaperStore 的画布缓存（可能被复用），**调用方不得 recycle**。
 *
 * ### 接入步骤
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
     * @param onWallpaper 壁纸就绪回调（后台线程；null = 原版加载失败，合成器回退默认底色）
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
            // 原版 WallpaperStore：有用户壁纸用用户图，没有时返回原版纯色默认底（aspectFill 后整幅画布同色）
            val bitmap =
                runCatching { WallpaperStore.aspectFillCanvas(appContext, width, height) }.getOrNull()
            var deliver: ((Bitmap?) -> Unit)? = null
            synchronized(lock) {
                if (started && generation == generationSnapshot) {
                    deliver = callback
                }
            }
            if (bitmap == null) {
                log("CARLIFE-COMPOSER: 原版壁纸加载失败，合成器回退默认底色（${width}x$height）")
            } else {
                log("CARLIFE-COMPOSER: 壁纸来源已加载 ${bitmap.width}x${bitmap.height}（画布 ${width}x$height）")
            }
            deliver?.invoke(bitmap)
        }
    }
}
