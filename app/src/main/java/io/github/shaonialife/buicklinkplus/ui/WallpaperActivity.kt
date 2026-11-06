package io.github.shaonialife.buicklinkplus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.withTranslation
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * 壁纸草稿编辑器，只有保存操作才替换正式壁纸和校准配置
 */
class WallpaperActivity : GlassActivity() {
    override val glassBackdropEnabled: Boolean = false
    private var calibration = WallpaperCalibration()
    private var selectedUri: Uri? = null
    private var defaultImage = false

    @Volatile private var generation = 0L
    private val previewExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "wallpaper-preview").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor
                .DiscardOldestPolicy(),
        )
    private var saving = false
    private lateinit var preview: Preview
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var saveButton: TextView
    private lateinit var actions: List<View>
    private val sliders = mutableListOf<SeekBar>()
    private val labels = mutableListOf<TextView>()
    private val picker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null && !saving) {
                selectedUri = uri
                defaultImage = false
                calibration = WallpaperCalibration()
                updateControls()
                loadPreview()
            }
        }

    /**
     * 恢复未保存的编辑状态，构建低开销的预览与精调控件
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        calibration = savedInstanceState?.let {
            WallpaperCalibration(it.getInt("zoom", 100), it.getInt("x", 0), it.getInt("y", 0)).normalized()
        } ?: WallpaperStore.calibration(this)
        selectedUri = savedInstanceState?.getString("uri")?.let(Uri::parse)
        defaultImage = savedInstanceState?.getBoolean("default", false) ?: false
        setContentView(R.layout.activity_wallpaper)
        controls = findViewById(R.id.wallpaperAdjustments)
        status = findViewById(R.id.wallpaperStatus)
        saveButton = findViewById(R.id.wallpaperSave)
        actions =
            listOf(
                findViewById(R.id.wallpaperBack),
                findViewById(R.id.wallpaperPick),
                findViewById(R.id.wallpaperCenter),
                findViewById(R.id.wallpaperReset),
            )
        preview = Preview()
        findViewById<FrameLayout>(R.id.wallpaperPreviewHost).addView(preview, FrameLayout.LayoutParams(-1, -2))
        addSlider(getString(R.string.wallpaper_editor_zoom), 275, calibration.zoomPercent - 25)
        addSlider(getString(R.string.wallpaper_editor_horizontal), 2000, calibration.xPermille + 1000)
        addSlider(getString(R.string.wallpaper_editor_vertical), 2000, calibration.yPermille + 1000)
        findViewById<View>(R.id.wallpaperBack).setOnClickListener { if (!saving) finish() }
        findViewById<View>(R.id.wallpaperPick).setOnClickListener {
            if (!saving) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.wallpaperCenter).setOnClickListener {
            if (saving) return@setOnClickListener
            calibration = calibration.copy(xPermille = 0, yPermille = 0)
            updateControls()
        }
        findViewById<View>(R.id.wallpaperReset).setOnClickListener {
            if (saving) return@setOnClickListener
            selectedUri = null
            defaultImage = true
            calibration = WallpaperCalibration()
            updateControls()
            loadPreview()
        }
        saveButton.setOnClickListener { save() }
        updateControls()
        loadPreview()
    }

    /** 保存时禁用操作，预览加载成功后才允许提交 */
    private fun refreshControlsEnabled() {
        actions.forEach {
            it.isEnabled = !saving
            it.alpha = if (saving) 0.4f else 1f
        }
        sliders.forEach {
            it.isEnabled = !saving
            PhonePanelUi.refreshStepper(it)
        }
        saveButton.isEnabled = !saving && preview.bitmap != null
        saveButton.setText(if (saving) R.string.wallpaper_editor_saving else R.string.wallpaper_editor_save)
    }

    /**
     * 滑动只修改内存草稿并重绘小图，不触发磁盘写入或正式背景生成
     */
    private fun addSlider(
        title: String,
        maximum: Int,
        initial: Int,
    ) {
        val density = resources.displayMetrics.density
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        row.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_surface)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val value =
            TextView(this).apply {
                textSize = 15f
                gravity = Gravity.END
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setAppearanceTextColor(R.color.m3_primary)
            }
        labels += value
        row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * density).toInt() })
        controls.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * density).toInt() })
        val index = sliders.size

        fun applyProgress(progress: Int) {
            if (saving) return
            calibration =
                when (index) {
                    0 -> calibration.copy(zoomPercent = progress + 25)
                    1 -> calibration.copy(xPermille = progress - 1000)
                    else -> calibration.copy(yPermille = progress - 1000)
                }
            updateControls()
        }
        val slider =
            SeekBar(this).apply {
                max = maximum
                progress = initial
                contentDescription = title
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (!fromUser || saving) return
                            applyProgress(progress)
                        }
                    },
                )
            }
        sliders += slider
        controls.addView(slider, LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        PhonePanelUi.bindStepper(slider, step = if (index == 0) 1 else 10, onStep = ::applyProgress)
    }

    /**
     * 同步标签与滑块，程序更新不重复保存草稿
     */
    private fun updateControls() {
        sliders.getOrNull(0)?.progress = calibration.zoomPercent - 25
        sliders.getOrNull(1)?.progress = calibration.xPermille + 1000
        sliders.getOrNull(2)?.progress = calibration.yPermille + 1000
        labels.getOrNull(0)?.text = getString(R.string.wallpaper_editor_zoom_value, calibration.zoomPercent)
        labels.getOrNull(1)?.text = getString(R.string.wallpaper_editor_offset_value, calibration.xPermille / 10f)
        labels.getOrNull(2)?.text = getString(R.string.wallpaper_editor_offset_value, calibration.yPermille / 10f)
        sliders.forEach(PhonePanelUi::refreshStepper)
        if (::preview.isInitialized) preview.invalidate()
    }

    /**
     * 解码预览使用后台线程，快速换图时丢弃过期结果
     */
    private fun loadPreview() {
        val request = ++generation
        val uri = selectedUri
        val useDefault = defaultImage
        val app = applicationContext
        preview.bitmap = null
        preview.invalidate()
        status.setText(R.string.wallpaper_editor_loading)
        refreshControlsEnabled()
        previewExecutor.execute {
            if (request != generation) return@execute
            val bitmap =
                runCatching {
                    when {
                        useDefault -> WallpaperStore.defaultBackground()
                        uri == null -> WallpaperStore.decode(app, 1280)
                        else -> {
                            val source = ImageDecoder.createSource(app.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                val scale = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
                                decoder.setTargetSize(
                                    maxOf(1, (info.size.width * scale).toInt()),
                                    maxOf(1, (info.size.height * scale).toInt()),
                                )
                            }
                        }
                    }
                }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                preview.bitmap = bitmap
                status.setText(
                    when {
                        bitmap == null -> R.string.wallpaper_editor_load_error
                        VirtualLauncher.wallpaperGeometry == null -> R.string.wallpaper_editor_example
                        else -> R.string.wallpaper_editor_live
                    },
                )
                preview.requestLayout()
                preview.invalidate()
                refreshControlsEnabled()
            }
        }
    }

    /**
     * 后台原子保存图片并统一发布校准版本，失败时保留编辑页
     */
    private fun save() {
        if (preview.bitmap == null || saving) return
        saving = true
        refreshControlsEnabled()
        val value = calibration.normalized()
        val uri = selectedUri
        val useDefault = defaultImage
        val app = applicationContext
        status.setText(R.string.wallpaper_editor_saving)
        Bg.run("wallpaper-editor-save") {
            val ok = WallpaperStore.save(app, uri, useDefault, value)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saving = false
                refreshControlsEnabled()
                if (ok) finish() else status.setText(R.string.wallpaper_editor_save_error)
            }
        }
    }

    /**
     * 旋转后继续编辑草稿，不提前覆盖正式配置
     */
    override fun onBackPressed() {
        if (!saving) super.onBackPressed()
    }

    /**
     * 保存当前编辑草稿
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("zoom", calibration.zoomPercent)
        outState.putInt("x", calibration.xPermille)
        outState.putInt("y", calibration.yPermille)
        outState.putString("uri", selectedUri?.toString())
        outState.putBoolean("default", defaultImage)
        super.onSaveInstanceState(outState)
    }

    /**
     * 页面结束后旧图片加载结果失效
     */
    override fun onDestroy() {
        generation++
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * 内置壁纸/其它入口应用壁纸后，若编辑器当前显示的是“已保存壁纸”（没有未保存草稿）
     * 则立即刷新预览；有草稿（选了新图/恢复默认）时不覆盖用户操作。
     */
    private val wallpaperVersionListener: (Long) -> Unit = {
        if (!saving && selectedUri == null && !defaultImage && ::preview.isInitialized) {
            loadPreview()
        }
    }

    override fun onStart() {
        super.onStart()
        WallpaperStore.addListener(wallpaperVersionListener)
    }

    override fun onStop() {
        WallpaperStore.removeListener(wallpaperVersionListener)
        super.onStop()
    }

    /**
     * 按正式画布的宽高比显示草稿，保留缩小和偏移后的背景区域
     */
    private inner class Preview : View(this@WallpaperActivity) {
        var bitmap: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bitmapBounds = RectF()

        /** 按画布比例显示预览，竖向画布限制高度 */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val geometry = VirtualLauncher.wallpaperGeometry
            val ratio = (geometry?.canvasWidth ?: 1972).toFloat() / (geometry?.canvasHeight ?: 520)
            val available = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (320 * resources.displayMetrics.density).toInt()
            val previewWidth = resolveSize(available, widthMeasureSpec)
            val desiredHeight = (previewWidth / ratio).toInt().coerceIn(1, (240 * resources.displayMetrics.density).toInt())
            setMeasuredDimension(previewWidth, resolveSize(desiredHeight, heightMeasureSpec))
        }

        /**
         * 使用与正式壁纸完全相同的变换公式
         */
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val geometry = VirtualLauncher.wallpaperGeometry
            val ratio = (geometry?.canvasWidth ?: 1972).toFloat() / (geometry?.canvasHeight ?: 520)
            val targetWidth = minOf(width.toFloat(), height * ratio)
            val targetHeight = targetWidth / ratio
            val left = (width - targetWidth) / 2f
            val top = (height - targetHeight) / 2f
            canvas.withTranslation(left, top) {
                canvas.clipRect(0f, 0f, targetWidth, targetHeight)
                canvas.drawColor(android.graphics.Color.BLACK)
                bitmap?.let {
                    val bounds = calibration.bounds(it.width, it.height, targetWidth, targetHeight)
                    bitmapBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    canvas.drawBitmap(it, null, bitmapBounds, paint)
                }
            }
        }
    }
}
