package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.scale
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * link_plus 原版软件自带的 10 张壁纸（`assets/wallpapers/`）。
 *
 * 来源说明：这 10 张图片是从原版 link_plus 软件自带壁纸资源中**按用户明确要求恢复**的，
 * 不是第三方图库素材，也不是本仓库代码生成的图片；文件名与显示名沿用原版资源语义。
 * 列表顺序与 [TITLES] 一一对应，调用方不得增删顺序。
 *
 * 用途：设置「外观与壁纸」页的壁纸网格（[loadThumb] 缩略图 + [applyAsync] 应用），
 * 以及后续"首页壁纸行"入口。应用后写入原版 [WallpaperStore] 的用户壁纸（等价于用户自己
 * 选了一张图），原版桌面/合成器/编辑器通过 WallpaperStore 版本监听自动刷新。
 *
 * 线程模型：[loadThumb] 同步解码（带 LruCache；建议网格在后台线程批量调用）；
 * [applyAsync] 自行调度后台解码/落盘并回主线程回调。
 */
object BuiltinWallpapers {
    /** 10 张内置壁纸显示名（与 [ASSETS] 顺序一一对应）。 */
    val TITLES: List<String> =
        listOf(
            "极光",
            "秋日",
            "秋夜",
            "小黄鸭·日",
            "小黄鸭·夜",
            "金色山脉",
            "美人鱼",
            "星球",
            "紫色太空",
            "雨战",
        )

    /** 应用为正式壁纸时允许的最长边（与原版内置壁纸落盘逻辑一致，避免超大图占内存）。 */
    private const val APPLY_MAX_EDGE = 1920

    /** 缩略图缓存（网格滚动时避免重复解码）。 */
    private val thumbCache =
        object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    private val ASSETS =
        listOf(
            "wallpapers/aurora.jpg",
            "wallpapers/autumn_day.jpg",
            "wallpapers/autumn_night.jpg",
            "wallpapers/ducks_day.jpg",
            "wallpapers/ducks_night.jpg",
            "wallpapers/golden_mountains.jpg",
            "wallpapers/mermaid.jpg",
            "wallpapers/planets.jpg",
            "wallpapers/purple_space_day.webp",
            "wallpapers/rainy_football.jpg",
        )

    /**
     * 解码内置壁纸缩略图（同步，带缓存）。
     *
     * @param maxSize 目标最长边（会收敛到 32..1024 像素）
     * @return 返回缓存位图；**调用方不得 recycle**（可多次 setImageBitmap）
     */
    fun loadThumb(
        context: Context,
        index: Int,
        maxSize: Int,
    ): Bitmap? {
        if (index !in TITLES.indices) return null
        val longest = maxSize.coerceIn(32, 1024)
        val key = "$index:$longest"
        thumbCache.get(key)?.let { if (!it.isRecycled) return it }
        val bitmap = decodeSampled(context, index, longest) ?: return null
        thumbCache.put(key, bitmap)
        return bitmap
    }

    /**
     * 应用内置壁纸：后台解码 assets 图片（限最长边 1920）→ 写入原版 [WallpaperStore] 用户壁纸，
     * 完成后在主线程回调。
     *
     * 回调约定（与设置页 `applyAsync(context, index) { err -> }` 一致）：
     * - 成功：`onDone(null)`
     * - 失败：`onDone("壁纸索引无效")` / `"壁纸解码失败"` / `"壁纸保存失败，请重试"`
     *
     * @param onDone 错误文案（主线程；null = 已应用成功）
     */
    fun applyAsync(
        context: Context,
        index: Int,
        onDone: (String?) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (index !in TITLES.indices) {
            postToMain { onDone("壁纸索引无效") }
            return
        }
        Bg.run("builtin-wallpaper-apply") {
            val decoded = decodeSampled(appContext, index, APPLY_MAX_EDGE)
            val error =
                if (decoded == null) {
                    "壁纸解码失败"
                } else {
                    try {
                        val scaled = scaleToApplyEdge(decoded)
                        val scaledCopy = scaled !== decoded
                        try {
                            if (WallpaperStore.saveBitmap(appContext, scaled)) null else "壁纸保存失败，请重试"
                        } finally {
                            if (scaledCopy) scaled.recycle()
                        }
                    } finally {
                        decoded.recycle()
                    }
                }
            postToMain { onDone(error) }
        }
    }

    /** 把解码结果压到 [APPLY_MAX_EDGE] 以内（JPEG 落盘前限制内存/文件尺寸）。 */
    private fun scaleToApplyEdge(bitmap: Bitmap): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= APPLY_MAX_EDGE) return bitmap
        val ratio = APPLY_MAX_EDGE.toFloat() / maxEdge
        return bitmap.scale(
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
        )
    }

    /**
     * 按最长边采样解码（BitmapFactory inSampleSize，2 的幂）；webp/jpg 都走同一条路径。
     * 采样后仍可能大于目标尺寸，调用方按需再缩放。
     */
    private fun decodeSampled(
        context: Context,
        index: Int,
        maxEdge: Int,
    ): Bitmap? {
        val asset = ASSETS.getOrNull(index) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            // 采样到 >= 目标尺寸的最小 2 的幂，保证清晰度又不过度占用内存
            while (longest / (sample * 2) >= maxEdge) {
                sample *= 2
            }
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { runCatching(block) }
    }
}
