package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.createBitmap
import io.github.shaonialife.buicklinkplus.util.Bg
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 原创生成壁纸：**不使用任何第三方图片**，全部用 Canvas 渐变/路径实时绘制。
 *
 * 目的：历史参考实现的内置壁纸已按版权要求删除后，车机桌面/安全区背景不能是黑底；这里提供 9 张
 * 代码生成的深色壁纸（极光/深空/山脉/星云/极地/几何/潮汐/晨曦/霓虹），风格接近原生
 * CarPlay 深色壁纸，也适合 CarLife 安全区 contain 后做四周边框背景。
 *
 * 线程模型：[render]/[loadThumb] 是同步的、可后台调用（绘制只用渐变与少量路径，单张
 * 1920x1080 约几毫秒~十几毫秒）；[applyAsync] 自己调度后台生成并回主线程回调。
 *
 * 版权：全部图形由本文件代码绘制，无外部素材；可自由随本仓库分发。
 */
object GeneratedWallpapers {
    /**
     * 壁纸标题列表（设置「外观与壁纸」网格直接展示）。

     * 索引与 [render] 的 `index` 一一对应；调用方不得增删顺序。
     */
    val TITLES: List<String> =
        listOf(
            "极光穹顶",
            "深空星野",
            "暮色山脉",
            "星云流光",
            "极地夜光",
            "几何棱镜",
            "深海潮汐",
            "晨曦微光",
            "霓虹余晖",
        )

    /** 用户未设置壁纸时使用的默认壁纸索引（深色极光，接近原生 CarPlay 深色背景）。 */
    const val DEFAULT_INDEX = 0

    /** 应用为正式壁纸时的分辨率（16:9；写入 JPEG 后由 WallpaperStore 按画布 cover）。 */
    private const val APPLY_WIDTH = 1920
    private const val APPLY_HEIGHT = 1080

    /** 缩略图缓存（网格滚动时避免重复绘制）。 */
    private val thumbCache =
        object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * 生成指定索引、指定尺寸的壁纸位图（同步；建议后台线程调用）。
     *
     * @param context 仅用于取应用上下文（图形的分辨率由 width/height 决定，与 dpi 无关）
     * @return null = 索引无效 / 尺寸非法 / 内存不足（调用方用纯色兜底）
     */
    fun render(
        context: Context,
        index: Int,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (index !in TITLES.indices) return null
        val w = width.coerceIn(8, 4096)
        val h = height.coerceIn(8, 4096)
        return runCatching {
            val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            try {
                drawWallpaper(canvas, index, w.toFloat(), h.toFloat())
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
            bitmap
        }.getOrNull()
    }

    /**
     * 生成 16:9 缩略图（网格/列表用）；返回缓存位图，**调用方不得 recycle**。
     *
     * @param maxSize 最长边上限（会收敛到 32..1024 像素）
     */
    fun loadThumb(
        context: Context,
        index: Int,
        maxSize: Int,
    ): Bitmap? {
        if (index !in TITLES.indices) return null
        val longest = maxSize.coerceIn(32, 1024)
        val width = longest
        val height = (longest * 9 / 16).coerceAtLeast(2)
        val key = "$index:$width"
        thumbCache.get(key)?.let { if (!it.isRecycled) return it }
        val bitmap = render(context, index, width, height) ?: return null
        thumbCache.put(key, bitmap)
        return bitmap
    }

    /**
     * 生成并写入用户壁纸（[WallpaperStore.saveBitmap]），完成后在主线程回调。
     *
     * 回调约定（与设置页 `applyAsync(context, index) { err -> }` 的用法一致）：
     * - 成功：`onDone(null)`
     * - 失败：`onDone("壁纸生成失败，请重试")` / `"壁纸保存失败，请重试"` / `"壁纸索引无效"`
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
        Bg.run("generated-wallpaper-apply") {
            val bitmap = render(appContext, index, APPLY_WIDTH, APPLY_HEIGHT)
            val error =
                if (bitmap == null) {
                    "壁纸生成失败，请重试"
                } else {
                    try {
                        if (WallpaperStore.saveBitmap(appContext, bitmap)) {
                            null
                        } else {
                            "壁纸保存失败，请重试"
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            postToMain { onDone(error) }
        }
    }

    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { runCatching(block) }
    }

    // ---- 绘制入口 ----

    private fun drawWallpaper(
        canvas: Canvas,
        index: Int,
        width: Float,
        height: Float,
    ) {
        when (index) {
            0 -> drawAuroraDome(canvas, width, height)
            1 -> drawDeepSpace(canvas, width, height)
            2 -> drawDuskMountains(canvas, width, height)
            3 -> drawNebulaFlow(canvas, width, height)
            4 -> drawPolarNight(canvas, width, height)
            5 -> drawGeometryPrism(canvas, width, height)
            6 -> drawDeepTide(canvas, width, height)
            7 -> drawDawnGlow(canvas, width, height)
            else -> drawNeonDusk(canvas, width, height)
        }
    }

    // ---- 0. 极光穹顶 ----

    private fun drawAuroraDome(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF05070F.toInt(), 0xFF0A1826.toInt(), 0xFF061019.toInt()),
        )
        radialGlow(canvas, width * 0.22f, height * 0.05f, width * 0.55f, 0x5534D399, 0x00101822)
        radialGlow(canvas, width * 0.78f, height * 0.1f, width * 0.5f, 0x442F80C9, 0x00101822)

        val unit = height / 720f
        val random = Random(101)
        drawStars(canvas, random, 110, width, height * 0.75f, unit)
        drawAuroraBand(canvas, width, height, 0.30f, 0.10f, -0.55f, 0xCC2FE0A0.toInt(), 0x0034D399)
        drawAuroraBand(canvas, width, height, 0.42f, 0.14f, 1.35f, 0x99E05CD0.toInt(), 0x00A34FE0)
        drawAuroraBand(canvas, width, height, 0.52f, 0.09f, 3.05f, 0x8838D4E8.toInt(), 0x0020A0C8)
        bottomFade(canvas, width, height, 0.70f)
    }

    private fun drawAuroraBand(
        canvas: Canvas,
        width: Float,
        height: Float,
        baseYFraction: Float,
        amplitudeFraction: Float,
        phase: Float,
        topColor: Int,
        bottomColor: Int,
    ) {
        val baseY = height * baseYFraction
        val amplitude = height * amplitudeFraction
        val thickness = height * 0.26f
        val path =
            Path().apply {
                moveTo(-width * 0.1f, baseY + amplitude * sin(phase))
                cubicTo(
                    width * 0.22f,
                    baseY - amplitude * 1.3f,
                    width * 0.52f,
                    baseY + amplitude * 1.6f,
                    width * 1.1f,
                    baseY + amplitude * 0.2f,
                )
                lineTo(width * 1.1f, baseY + thickness)
                cubicTo(
                    width * 0.52f,
                    baseY + thickness + amplitude * 1.1f,
                    width * 0.22f,
                    baseY + thickness - amplitude * 0.7f,
                    -width * 0.1f,
                    baseY + thickness + amplitude * 0.5f,
                )
                close()
            }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        baseY - amplitude,
                        0f,
                        baseY + thickness,
                        topColor,
                        bottomColor,
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawPath(path, paint)
    }

    // ---- 1. 深空星野 ----

    private fun drawDeepSpace(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        val centerX = width * 0.5f
        val centerY = height * 0.42f
        val paint =
            Paint().apply {
                shader =
                    RadialGradient(
                        centerX,
                        centerY,
                        maxOf(width, height) * 0.85f,
                        intArrayOf(0xFF18213D.toInt(), 0xFF0A0E1E.toInt(), 0xFF03040A.toInt()),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, 0f, width, height, paint)
        radialGlow(canvas, width * 0.28f, height * 0.30f, width * 0.30f, 0x552A1A5E, 0x0003040A)
        radialGlow(canvas, width * 0.72f, height * 0.24f, width * 0.26f, 0x441F3B8C, 0x0003040A)
        radialGlow(canvas, width * 0.55f, height * 0.70f, width * 0.34f, 0x332E6E8E, 0x0003040A)
        val unit = height / 720f
        val random = Random(202)
        drawStars(canvas, random, 170, width, height, unit)
        // 银河带：一条倾斜的柔光长条
        val bandPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        width * 0.1f,
                        height * 0.72f,
                        width * 0.9f,
                        height * 0.18f,
                        intArrayOf(0x00C9C4FF, 0x33C9C4FF, 0x00C9C4FF),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        val band =
            Path().apply {
                moveTo(width * 0.05f, height * 0.82f)
                cubicTo(width * 0.35f, height * 0.55f, width * 0.65f, height * 0.35f, width * 0.98f, height * 0.12f)
                cubicTo(width * 0.7f, height * 0.30f, width * 0.42f, height * 0.52f, width * 0.12f, height * 0.90f)
                close()
            }
        canvas.drawPath(band, bandPaint)
    }

    // ---- 2. 暮色山脉 ----

    private fun drawDuskMountains(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF1B1430.toInt(), 0xFF4B2A4A.toInt(), 0xFFC2703F.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
        )
        radialGlow(canvas, width * 0.66f, height * 0.58f, width * 0.26f, 0xCCFFD9A0.toInt(), 0x00FFB36B)
        radialGlow(canvas, width * 0.66f, height * 0.58f, width * 0.62f, 0x44FFB36B, 0x00C2703F)
        mountains(
            canvas,
            width,
            height,
            baseY = height * 0.78f,
            peaks = floatArrayOf(0f, 0.16f, 0.30f, 0.47f, 0.62f, 0.80f, 1f),
            peakHeights = floatArrayOf(0.06f, 0.20f, 0.12f, 0.26f, 0.15f, 0.22f, 0.10f),
            color = 0xFF35243F.toInt(),
        )
        mountains(
            canvas,
            width,
            height,
            baseY = height * 0.88f,
            peaks = floatArrayOf(0f, 0.22f, 0.40f, 0.58f, 0.76f, 1f),
            peakHeights = floatArrayOf(0.08f, 0.18f, 0.10f, 0.20f, 0.12f, 0.07f),
            color = 0xFF1D1627.toInt(),
        )
        mountains(
            canvas,
            width,
            height,
            baseY = height * 0.97f,
            peaks = floatArrayOf(0f, 0.30f, 0.55f, 0.80f, 1f),
            peakHeights = floatArrayOf(0.05f, 0.12f, 0.08f, 0.13f, 0.04f),
            color = 0xFF0C0912.toInt(),
        )
        // 云霞：几道横向柔光
        val cloudPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        height * 0.55f,
                        0f,
                        height * 0.72f,
                        intArrayOf(0x00FFD9A0, 0x55FFD9A0, 0x00FFD9A0),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, height * 0.55f, width, height * 0.72f, cloudPaint)
    }

    private fun mountains(
        canvas: Canvas,
        width: Float,
        height: Float,
        baseY: Float,
        peaks: FloatArray,
        peakHeights: FloatArray,
        color: Int,
    ) {
        val path =
            Path().apply {
                moveTo(0f, height)
                lineTo(0f, baseY)
                for (index in peaks.indices) {
                    val x = width * peaks[index]
                    val y = baseY - height * peakHeights[index]
                    if (index == 0) {
                        lineTo(x, y)
                    } else {
                        val prevX = width * peaks[index - 1]
                        lineTo((prevX + x) / 2f, y)
                    }
                }
                lineTo(width, baseY)
                lineTo(width, height)
                close()
            }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        baseY - height * 0.3f,
                        0f,
                        height,
                        intArrayOf(color, Color.argb(255, Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)),
                        null,
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawPath(path, paint)
    }

    // ---- 3. 星云流光 ----

    private fun drawNebulaFlow(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        val paint =
            Paint().apply {
                shader =
                    RadialGradient(
                        width * 0.42f,
                        height * 0.5f,
                        maxOf(width, height) * 0.9f,
                        intArrayOf(0xFF1B0F2E.toInt(), 0xFF0A0616.toInt(), 0xFF04020A.toInt()),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, 0f, width, height, paint)
        radialGlow(canvas, width * 0.30f, height * 0.36f, width * 0.26f, 0x66FF5FA2, 0x0004020A)
        radialGlow(canvas, width * 0.62f, height * 0.30f, width * 0.30f, 0x553B7BF0, 0x0004020A)
        radialGlow(canvas, width * 0.74f, height * 0.66f, width * 0.24f, 0x4437E0CE, 0x0004020A)
        radialGlow(canvas, width * 0.44f, height * 0.76f, width * 0.22f, 0x44A34FE0, 0x0004020A)
        val unit = height / 720f
        drawStars(canvas, Random(303), 120, width, height, unit)
        // 斜向流光丝带
        for (line in 0 until 3) {
            val offset = 0.2f + line * 0.22f
            val streakPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2.2f * unit
                    shader =
                        LinearGradient(
                            width * 0.05f,
                            height * offset,
                            width * 0.95f,
                            height * (offset - 0.18f),
                            intArrayOf(0x00FF5FA2, 0x55FF5FA2, 0x3337E0CE, 0x00FF5FA2),
                            floatArrayOf(0f, 0.35f, 0.7f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                }
            val streak =
                Path().apply {
                    moveTo(-width * 0.05f, height * (offset + 0.10f))
                    cubicTo(
                        width * 0.35f,
                        height * (offset - 0.02f),
                        width * 0.62f,
                        height * (offset + 0.06f),
                        width * 1.05f,
                        height * (offset - 0.06f),
                    )
                }
            canvas.drawPath(streak, streakPaint)
        }
    }

    // ---- 4. 极地夜光 ----

    private fun drawPolarNight(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF031018.toInt(), 0xFF062A33.toInt(), 0xFF03141C.toInt()),
        )
        radialGlow(canvas, width * 0.5f, height * 0.72f, width * 0.55f, 0x3320C5A0, 0x0003141C)
        val unit = height / 720f
        drawStars(canvas, Random(404), 90, width, height * 0.6f, unit)
        drawAuroraBand(canvas, width, height, 0.26f, 0.07f, 0.8f, 0xBB5BF0C8.toInt(), 0x0012A37E)
        drawAuroraBand(canvas, width, height, 0.36f, 0.09f, 2.1f, 0x8853C7FF.toInt(), 0x001E6FF0)
        drawAuroraBand(canvas, width, height, 0.44f, 0.05f, 4.0f, 0x6635FFC8, 0x0012A37E)
        // 雪原反光
        val snowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        height * 0.74f,
                        0f,
                        height,
                        intArrayOf(0x3320C5A0, 0x08030D12),
                        null,
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, height * 0.74f, width, height, snowPaint)
        bottomFade(canvas, width, height, 0.82f)
    }

    // ---- 5. 几何棱镜 ----

    private fun drawGeometryPrism(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        canvas.drawColor(0xFF0A0B12.toInt())
        radialGlow(canvas, width * 0.18f, height * 0.20f, width * 0.34f, 0x5531D6E8, 0xFF0A0B12.toInt())
        radialGlow(canvas, width * 0.84f, height * 0.78f, width * 0.36f, 0x553B4FE0, 0xFF0A0B12.toInt())
        radialGlow(canvas, width * 0.62f, height * 0.10f, width * 0.26f, 0x44E0A24F, 0xFF0A0B12.toInt())
        val unit = height / 720f
        val random = Random(505)
        val polygonColors =
            intArrayOf(
                0x5531D6E8,
                0x443B4FE0,
                0x44E05C9E,
                0x33E0A24F,
                0x3331E8A0,
            )
        repeat(9) {
            val centerX = width * (0.08f + random.nextFloat() * 0.84f)
            val centerY = height * (0.06f + random.nextFloat() * 0.86f)
            val radius = height * (0.10f + random.nextFloat() * 0.24f)
            val rotation = random.nextFloat() * (Math.PI.toFloat() * 2f)
            val sides = 3 + random.nextInt(3)
            val path = Path()
            for (side in 0 until sides) {
                val angle = rotation + side * (Math.PI.toFloat() * 2f / sides)
                val x = centerX + cos(angle) * radius
                val y = centerY + sin(angle) * radius
                if (side == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = polygonColors[it % polygonColors.size]
                    style = Paint.Style.FILL
                }
            canvas.drawPath(path, paint)
            val edgePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x66FFFFFF
                    style = Paint.Style.STROKE
                    strokeWidth = 1.1f * unit
                }
            canvas.drawPath(path, edgePaint)
        }
        // 斜向高光细线
        val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x227FE8FF
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * unit
            }
        for (line in 0 until 5) {
            val offset = height * (line * 0.2f)
            canvas.drawLine(0f, offset, width, offset - height * 0.35f, linePaint)
        }
        bottomFade(canvas, width, height, 0.82f)
    }

    // ---- 6. 深海潮汐 ----

    private fun drawDeepTide(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF020A18.toInt(), 0xFF06304A.toInt(), 0xFF0A4E63.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
        )
        radialGlow(canvas, width * 0.76f, height * 0.20f, width * 0.24f, 0x55A8E8FF, 0x00020A18)
        radialGlow(canvas, width * 0.76f, height * 0.20f, width * 0.55f, 0x223FA8D8, 0x00020A18)
        val unit = height / 720f
        for (wave in 0 until 4) {
            val baseY = height * (0.58f + wave * 0.11f)
            val amplitude = height * (0.045f - wave * 0.007f)
            val path =
                Path().apply {
                    moveTo(-width * 0.05f, baseY)
                    var x = -width * 0.05f
                    var up = true
                    while (x < width * 1.05f) {
                        val nextX = x + width * 0.16f
                        val controlY = if (up) baseY - amplitude * 1.8f else baseY + amplitude * 1.8f
                        quadTo(x + width * 0.08f, controlY, nextX, baseY)
                        x = nextX
                        up = !up
                    }
                    lineTo(width * 1.05f, height)
                    lineTo(-width * 0.05f, height)
                    close()
                }
            val topColor = Color.argb(150 - wave * 30, 90, 220, 230)
            val bottomColor = Color.argb(20 - wave * 4, 10, 70, 110)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, baseY, 0f, height, topColor, bottomColor, Shader.TileMode.CLAMP)
                }
            canvas.drawPath(path, paint)
        }
        // 月光撒在水面上的碎光
        val shimmerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x44D8F6FF
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * unit
            }
        for (line in 0 until 6) {
            val y = height * (0.60f + line * 0.05f)
            canvas.drawLine(width * (0.42f + line * 0.05f), y, width * (0.66f + line * 0.04f), y, shimmerPaint)
        }
        bottomFade(canvas, width, height, 0.85f)
    }

    // ---- 7. 晨曦微光 ----

    private fun drawDawnGlow(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF0A0A1E.toInt(), 0xFF33234F.toInt(), 0xFF8A4A5E.toInt(), 0xFFE8A25C.toInt()),
            floatArrayOf(0f, 0.5f, 0.78f, 1f),
        )
        radialGlow(canvas, width * 0.5f, height * 0.82f, width * 0.30f, 0xAAFFE3B0.toInt(), 0x00E8A25C)
        radialGlow(canvas, width * 0.5f, height * 0.82f, width * 0.66f, 0x44FFD9A0, 0x00C2703F)
        mountains(
            canvas,
            width,
            height,
            baseY = height * 0.88f,
            peaks = floatArrayOf(0f, 0.18f, 0.34f, 0.52f, 0.70f, 0.86f, 1f),
            peakHeights = floatArrayOf(0.05f, 0.14f, 0.08f, 0.16f, 0.09f, 0.13f, 0.05f),
            color = 0xFF231A30.toInt(),
        )
        mountains(
            canvas,
            width,
            height,
            baseY = height * 0.97f,
            peaks = floatArrayOf(0f, 0.28f, 0.50f, 0.74f, 1f),
            peakHeights = floatArrayOf(0.04f, 0.10f, 0.06f, 0.11f, 0.03f),
            color = 0xFF100B17.toInt(),
        )
        // 晨雾横条
        val hazePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        height * 0.62f,
                        0f,
                        height * 0.8f,
                        intArrayOf(0x00FFE3B0, 0x44FFE3B0, 0x00FFE3B0),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(0f, height * 0.62f, width, height * 0.8f, hazePaint)
    }

    // ---- 8. 霓虹余晖 ----

    @android.annotation.SuppressLint("UseKtx")
    private fun drawNeonDusk(
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        verticalGradient(
            canvas,
            width,
            height,
            intArrayOf(0xFF07070F.toInt(), 0xFF1A0B25.toInt(), 0xFF2A1030.toInt()),
        )
        val horizon = height * 0.62f
        val sunRadius = height * 0.26f
        radialGlow(canvas, width * 0.5f, horizon, sunRadius * 2.4f, 0x55FF4FA0, 0x001A0B25)
        // 半沉霓虹太阳：圆形裁切 + 竖渐变
        val sunPath =
            Path().apply {
                addCircle(width * 0.5f, horizon, sunRadius, Path.Direction.CW)
            }
        canvas.save()
        canvas.clipPath(sunPath)
        val sunPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        horizon - sunRadius,
                        0f,
                        horizon + sunRadius * 0.2f,
                        intArrayOf(0xFFFFD36B.toInt(), 0xFFFF5FA2.toInt(), 0xFFB83BE0.toInt()),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawRect(
            width * 0.5f - sunRadius,
            horizon - sunRadius,
            width * 0.5f + sunRadius,
            horizon + sunRadius,
            sunPaint,
        )
        canvas.restore()
        // 地平线亮线
        val horizonPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader =
                    LinearGradient(
                        0f,
                        0f,
                        width,
                        0f,
                        intArrayOf(0x00FF5FA2, 0xCCFF5FA2.toInt(), 0xCC53C7FF.toInt(), 0x00FF5FA2),
                        floatArrayOf(0f, 0.25f, 0.75f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                strokeWidth = maxOf(1.5f, height * 0.004f)
                style = Paint.Style.STROKE
            }
        canvas.drawLine(0f, horizon, width, horizon, horizonPaint)
        // 下方透视网格
        val unit = height / 720f
        val gridPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x44FF5FA2
                strokeWidth = 1.1f * unit
                style = Paint.Style.STROKE
            }
        val gridPaintBlue = Paint(gridPaint).apply { color = 0x3353C7FF }
        var row = 0
        while (true) {
            val t = row / 10f
            val y = horizon + (height - horizon) * t * t
            if (y > height + 1f) break
            canvas.drawLine(0f, y, width, y, if (row % 2 == 0) gridPaint else gridPaintBlue)
            row++
        }
        for (column in -6..6) {
            val bottomX = width * 0.5f + column * width * 0.16f
            canvas.drawLine(width * 0.5f, horizon, bottomX, height, if (column % 2 == 0) gridPaint else gridPaintBlue)
        }
        bottomFade(canvas, width, height, 0.90f)
    }

    // ---- 通用绘制工具 ----

    private fun verticalGradient(
        canvas: Canvas,
        width: Float,
        height: Float,
        colors: IntArray,
        positions: FloatArray? = null,
    ) {
        val paint =
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height, colors, positions, Shader.TileMode.CLAMP)
            }
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun radialGlow(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        innerColor: Int,
        outerColor: Int,
    ) {
        if (radius <= 0f) return
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(centerX, centerY, radius, innerColor, outerColor, Shader.TileMode.CLAMP)
            }
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    private fun drawStars(
        canvas: Canvas,
        random: Random,
        count: Int,
        width: Float,
        height: Float,
        unit: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(count) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val radius = (0.4f + random.nextFloat() * 1.3f) * unit.coerceAtLeast(0.5f)
            paint.color = Color.argb(40 + random.nextInt(170), 235, 240, 255)
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    private fun bottomFade(
        canvas: Canvas,
        width: Float,
        height: Float,
        startFraction: Float,
    ) {
        val start = height * startFraction
        val paint =
            Paint().apply {
                shader = LinearGradient(0f, start, 0f, height, intArrayOf(0x00000000, 0x99000000.toInt()), null, Shader.TileMode.CLAMP)
            }
        canvas.drawRect(0f, start, width, height, paint)
    }
}
