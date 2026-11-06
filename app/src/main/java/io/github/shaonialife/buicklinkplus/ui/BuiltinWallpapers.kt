package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.shaonialife.buicklinkplus.util.Bg
import java.io.InputStream

/**
 * 内置壁纸（移植自旧工程 hybrid63 的 9 张内置图，见 `tl/BuiltinWallpapers.java`）。
 *
 * 图片位于 `assets/wallpapers/`，选中后写入 [WallpaperStore] 的用户壁纸文件，
 * 合成器/壁纸页通过 [WallpaperStore.version] 监听刷新。不再依赖闭源 APK 的反射刷新。
 */
object BuiltinWallpapers {
    /** assets 下的内置壁纸（与标题一一对应）。 */
    val ASSETS =
        listOf(
            "wallpapers/aurora.jpg",
            "wallpapers/autumn_day.jpg",
            "wallpapers/autumn_night.jpg",
            "wallpapers/ducks_day.jpg",
            "wallpapers/ducks_night.jpg",
            "wallpapers/golden_mountains.jpg",
            "wallpapers/mermaid.jpg",
            "wallpapers/planets.jpg",
            "wallpapers/rainy_football.jpg",
        )

    val TITLES =
        listOf(
            "极光",
            "秋日",
            "秋夜",
            "小黄鸭·日",
            "小黄鸭·夜",
            "金色山脉",
            "美人鱼",
            "星球",
            "雨战",
        )

    /** 后台加载缩略图（调用方负责 recycle）。 */
    fun loadThumb(
        context: Context,
        index: Int,
        targetPx: Int,
    ): Bitmap? {
        val asset = ASSETS.getOrNull(index) ?: return null
        return try {
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    context.assets.open(asset).use { input: InputStream -> BitmapFactory.decodeStream(input, null, this) }
                }
            var sample = 1
            val max = maxOf(bounds.outWidth, bounds.outHeight)
            while (max / sample > targetPx * 2) sample *= 2
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            context.assets.open(asset).use { input -> BitmapFactory.decodeStream(input, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 应用内置壁纸到 [WallpaperStore]（后台调用）。
     *
     * @return 成功为 null，失败返回给用户看的原因
     */
    fun apply(
        context: Context,
        index: Int,
    ): String? {
        val asset = ASSETS.getOrNull(index) ?: return "壁纸索引无效"
        return try {
            val bitmap =
                context.assets.open(asset).use { input ->
                    BitmapFactory.decodeStream(
                        input,
                        null,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    )
                } ?: return "壁纸解码失败：$asset"
            val bounded =
                try {
                    val max = maxOf(bitmap.width, bitmap.height)
                    if (max > 1920) {
                        val scale = 1920f / max
                        Bitmap
                            .createScaledBitmap(
                                bitmap,
                                (bitmap.width * scale).toInt().coerceAtLeast(1),
                                (bitmap.height * scale).toInt().coerceAtLeast(1),
                                true,
                            ).also { if (it !== bitmap) bitmap.recycle() }
                    } else {
                        bitmap
                    }
                } catch (_: Exception) {
                    bitmap
                }
            val ok =
                try {
                    WallpaperStore.saveBuiltin(context, bounded)
                } finally {
                    bounded.recycle()
                }
            if (ok) null else "保存内置壁纸失败"
        } catch (_: Exception) {
            "应用内置壁纸失败"
        }
    }

    /** 后台应用并回主线程提示（页面按钮共用）。 */
    fun applyAsync(
        context: Context,
        index: Int,
        onResult: (String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        Bg.run("builtin-wallpaper") {
            val error = apply(appContext, index)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(error) }
        }
    }
}
