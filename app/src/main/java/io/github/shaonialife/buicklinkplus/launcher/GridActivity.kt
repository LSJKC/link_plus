package io.github.shaonialife.buicklinkplus.launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.MusicPreviewView
import io.github.shaonialife.buicklinkplus.render.CARD_MARGIN_PX
import io.github.shaonialife.buicklinkplus.render.DASH_GAP_PX
import io.github.shaonialife.buicklinkplus.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * 车机桌面，包含壁纸、分页网格和页码
 * 横屏 4×3，竖屏最多 6 行；第一格固定设置，其余按桌面应用偏好显示
 */
class GridActivity : GlassActivity() {
    override val glassBackdropEnabled = true
    private val reloadRequests =
        io.github.shaonialife.buicklinkplus.util
            .RequestEpoch()
    private val appsChanged: () -> Unit = { reload() }

    /**
     * 原位更新分页指示，不改变当前桌面页
     */
    override fun onAppearanceChanged() {
        updateDots(pager?.currentItem ?: 0)
        dashboardView?.findViewById<MusicPreviewView>(R.id.dashMusicPreview)?.refreshAppearance()
        dashboardView?.findViewById<DashboardWidgetView>(R.id.dashWidgetContent)?.refreshAppearance()
    }

    /** 桌面格子：仪表盘页（第 0 页）/ 固定设置入口 / 普通应用。 */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** 当前行数（横屏 3；竖屏按格子高宽比自适应，分页容量 = COLUMNS × rows）。 */
    private var rows = ROWS_MIN

    /** 仪表盘模式面板（分屏时显示在应用区右半；左半由地图 tile 覆盖）。 */
    private var dashboardView: android.view.View? = null
    private var mediaCardResumed = false
    private var widgetMode = true
    private var lastWidgetId = -1
    private val dashboardPanels = mutableSetOf<View>()
    private val widgetPreferences by lazy { getSharedPreferences(DashboardWidgets.PREFERENCES, MODE_PRIVATE) }
    private val widgetPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "selected") renderMediaCard()
        }

    /** 页面隐藏或断开后立即停止音乐卡歌词请求 */
    private val displayListener: () -> Unit = { runOnUiThread { renderMediaCard() } }

    /** 仪表盘模式变化（VirtualLauncher 通知）。 */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread { applyDashboardMode(dashboard) }
    }

    /** 媒体状态监听（MediaSessionsState 主线程回调）。 */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    /** 进度每秒更新，歌词按句子边界更新，整卡在渲染后安排刷新 */
    private val mediaTick =
        object : Runnable {
            override fun run() {
                renderMediaCard()
            }
        }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** mm:ss（超 1 小时 h:mm:ss）。 */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1_000
        val h = totalSec / 3_600
        val m = totalSec % 3_600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * 更新音乐卡封面、文字和播放状态，区分无通知权限、暂停和无会话
     * 标题和副标题按同一状态一起更新
     */
    private fun renderMediaCard() {
        if (isDestroyed || isFinishing) return
        val panel = dashboardView ?: return
        val now = MediaSessionsState.nowPlaying
        val title = now?.title?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.takeIf { it.isNotBlank() }
        // 有会话但读不到任何元数据 + 未授权 → 权限问题，不是「没在播放」
        val missingDueToPermission =
            now != null && title == null && !MediaSessionsState.accessGranted
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            title ?: if (missingDueToPermission) "无法读取播放信息" else "未在播放"
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            MediaSessionsState.controlNotice ?: artist
                ?: when {
                    missingDueToPermission -> "未授予「通知使用权」——请在系统设置中开启"
                    now?.playing == true -> "正在播放"
                    else -> ""
                }
        panel.findViewById<ImageView>(R.id.dashPlayPause).setAppearanceImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
        panel.findViewById<TextView>(R.id.dashMediaTime)?.let { time ->
            if (now != null && now.durationMs > 0) {
                time.visibility = View.VISIBLE
                time.text =
                    getString(
                        R.string.media_time_pair,
                        formatTime(now.displayPositionAt(android.os.SystemClock.elapsedRealtime())),
                        formatTime(now.durationMs),
                    )
            } else {
                time.visibility = View.GONE
            }
        }
        panel.findViewById<ProgressBar>(R.id.dashMediaProgress)?.let { bar ->
            if (now != null && now.durationMs > 0) {
                bar.visibility = View.VISIBLE
                bar.progress =
                    (now.displayPositionAt(android.os.SystemClock.elapsedRealtime()) * 1000 / now.durationMs)
                        .toInt()
                        .coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
        val preview = panel.findViewById<MusicPreviewView>(R.id.dashMusicPreview)
        panel.findViewById<TextView>(R.id.dashMediaLabel).visibility = if (preview.showingLyrics) View.GONE else View.VISIBLE
        val visible =
            mediaCardResumed &&
                VirtualLauncher.dashboardMode &&
                VirtualLauncher.displayedContent.page == DockDisplayState.Page.DASHBOARD
        val widgetId = DashboardWidgets.selected(this)
        if (widgetId != lastWidgetId) {
            lastWidgetId = widgetId
            widgetMode = true
        }
        val useWidget = widgetId > 0 && widgetMode
        panel.findViewById<TextView>(R.id.dashShowWidget).setAppearanceTextColor(if (useWidget) R.color.m3_primary else R.color.m3_on_var)
        panel.findViewById<TextView>(R.id.dashShowLyrics).setAppearanceTextColor(if (useWidget) R.color.m3_on_var else R.color.m3_primary)
        panel.findViewById<View>(R.id.dashWidgetToolbar).visibility = if (widgetId > 0) View.VISIBLE else View.GONE
        panel.findViewById<View>(R.id.dashMediaFallback).visibility = if (useWidget) View.GONE else View.VISIBLE
        panel.findViewById<DashboardWidgetView>(R.id.dashWidgetContent).apply {
            visibility = if (useWidget) View.VISIBLE else View.GONE
            show(visible && useWidget)
            updateLyrics(now)
        }
        preview.update(now, visible && !useWidget)
        // 进度显示需要持续走秒：渲染尾部续调度（防重：先移除）
        tickHandler.removeCallbacks(mediaTick)
        if (visible) tickHandler.postDelayed(mediaTick, preview.tickIntervalMs)
    }

    /** 仪表盘模式 → pager 位置同步（0=仪表盘，1+=桌面；已在目标位则不动）。 */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager = findViewById<ViewPager2>(R.id.gridPager)
        if (dashboard && pager.currentItem != 0) pager.setCurrentItem(0, false)
        if (!dashboard && pager.currentItem == 0) pager.setCurrentItem(1, false)
        renderMediaCard()
    }

    /** 音乐卡使用统一的媒体会话控制入口 */
    private fun dispatchMediaKey(keyCode: Int) {
        MediaSessionsState.dispatchMediaKey(this, keyCode, "车机音乐卡")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)

        // 格子尺寸/行数按屏幕动态计算（布局关系在 XML，尺寸测量在代码）
        computeCellMetrics()
        // 虚拟屏重建竞态：dm 短暂为 0 时 cell/icon 尺寸会算出 0，首帧绑定即崩
        // （Bitmap.createBitmap 0 宽高）。夹最小值保底，并在 pager 首帧布局后重算+重绑。
        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                computeCellMetrics()
                iconSizePx = iconSizePx.coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // 桌面和音乐卡共享 GlassActivity 的单一模糊背景，仍按完整画布与安全区裁切
        MediaSessionsState.addListener(mediaStateListener)
        widgetPreferences.registerOnSharedPreferenceChangeListener(widgetPreferenceListener)
        VirtualLauncher.addDisplayListener(displayListener)
        VirtualLauncher.addDashboardListener(dashboardListener)
        applyDashboardMode(VirtualLauncher.dashboardMode)

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                adapter = PageAdapter()
            }
        dotsRow = findViewById(R.id.dotsRow)
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    // 用户滑动切换仪表盘/桌面：回写 launcher（含地图任务与 tile 布局）
                    if (position == 0 && !VirtualLauncher.dashboardMode) VirtualLauncher.showDashboard()
                    if (position != 0 && VirtualLauncher.dashboardMode) VirtualLauncher.showDesktop()
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    // 滑动过程中 tile 跟随手指平移（合成器采样位置随进度左移/右移）；
                    // 回滑进仪表盘时提前显示 tile（纯视觉，状态在落定 IDLE 时统一对账）
                    if (position == 0) {
                        VirtualLauncher.setTileSlide(positionOffset)
                        if (positionOffset < 1f) VirtualLauncher.showMapTile()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        VirtualLauncher.setTileSlide(0f)
                        // 落定对账：仪表盘模式与所在页一致（含异常中断的对账恢复）
                        VirtualLauncher.syncDashboard(pager?.currentItem == 0, fromPager = true)
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        mediaCardResumed = true
        VirtualLauncher.addAppsListener(appsChanged)
        reload()
        renderMediaCard()
    }

    /** 按可用区域计算格子：横屏 4×3，竖屏按高宽比 1.25 增加行数，最多 6 行 */
    private fun computeCellMetrics() {
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page 左右 padding
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // 上/下 padding + 页点区
        val usableW = dm.widthPixels - chromeHorizontalPx
        val usableH = dm.heightPixels - chromeVerticalPx
        if (usableW <= 0 || usableH <= 0) {
            cellWidthPx = 0
            cellHeightPx = 0
            iconSizePx = 0
            return
        }
        cellWidthPx = usableW / COLUMNS
        // 横竖屏统一：先按 3 行试算，格子过高（竖屏）时加行收敛到目标比例
        var rowCount = ROWS_MIN
        if (cellWidthPx > 0) {
            rowCount = (usableH / (cellWidthPx * CELL_HEIGHT_RATIO)).toInt().coerceIn(ROWS_MIN, ROWS_MAX)
        }
        rows = rowCount
        cellHeightPx = usableH / rowCount
        iconSizePx =
            (cellHeightPx * 0.63f)
                .toInt()
                .coerceAtMost((cellWidthPx * 0.72f).toInt())
    }

    /** 重新读取应用清单与桌面选择（首帧与每次回到桌面时调用）。 */
    // 分页容量和每格几何会随屏幕调整，全量重排需要通知所有页
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun reload() {
        val request = reloadRequests.advance()
        val version = VirtualLauncher.appsCacheVersion
        VirtualLauncher.peekCachedApps()?.let {
            publishApps(it)
            return
        }
        val root = window.decorView
        val app = applicationContext
        Bg.run("desktop-apps") {
            val apps = VirtualLauncher.cachedApps(app)
            root.post {
                if (isDestroyed || isFinishing || !reloadRequests.owns(request)) return@post
                if (VirtualLauncher.appsCacheVersion != version) {
                    reload()
                    return@post
                }
                publishApps(apps)
            }
        }
    }

    /** 列表加载完成后回主线程发布 */
    // 应用集合和分页容量改变时所有页面都要按新几何重排
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun publishApps(apps: List<VirtualLauncher.AppEntry>) {
        VirtualLauncher.replaceIconCache(apps.associate { it.component to it.icon })
        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // 桌面第一格：车机设置（固定，不可取消）
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // 第 0 页 = 仪表盘，其后为桌面网格页：左右滑动即互相切换
        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(COLUMNS * rows)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // 位置与仪表盘模式对账（home 键循环/应用启动会改 dashboardMode）：
        // 仅纠偏方向，不把停在桌面第 N 页的用户拽回第 1 页
        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode && current == 0) pager?.setCurrentItem(1, false)
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                // 仪表盘页 = 无边距容器：音乐卡面板 + 分屏拖拽把手（把手落在加宽的
                // 中缝内、不被合成器叠画的地图 tile 遮挡，触摸走应用屏正常派发）
                val container = android.widget.FrameLayout(parent.context)
                container.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                val panel = layoutInflater.inflate(R.layout.view_dashboard, container, false)
                dashboardPanels.add(panel)
                panel.setAppearanceBackground(R.drawable.bg_card)
                container.addView(panel)
                container.addView(DashSplitHandle(parent.context))
                DashboardHolder(container, panel)
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = COLUMNS
                // 行数由 computeCellMetrics 按横竖屏决定（横屏 3、竖屏最多 6）
                grid.rowCount = rows
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /** 仪表盘页 Holder：无边距容器（音乐卡面板 + 分屏把手）随页面创建。 */
    private inner class DashboardHolder(
        container: View,
        val panel: View,
    ) : RecyclerView.ViewHolder(container) {
        private var handle: DashSplitHandle? = null

        fun bind() {
            if (dashboardView !== panel) {
                dashboardView?.findViewById<MusicPreviewView>(R.id.dashMusicPreview)?.update(MediaSessionsState.nowPlaying, false)
                dashboardView?.findViewById<DashboardWidgetView>(R.id.dashWidgetContent)?.show(false)
            }
            dashboardView = panel
            panel.findViewById<MusicPreviewView>(R.id.dashMusicPreview).onModeChanged = { renderMediaCard() }
            panel.findViewById<View>(R.id.dashShowWidget).setOnClickListener {
                widgetMode = true
                renderMediaCard()
            }
            panel.findViewById<View>(R.id.dashShowLyrics).setOnClickListener {
                widgetMode = false
                panel.findViewById<MusicPreviewView>(R.id.dashMusicPreview).showLyrics()
                renderMediaCard()
            }
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            panel.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        if (VirtualLauncher.dockAtBottom) {
                            leftMargin = cardMargin
                            rightMargin = cardMargin
                            topMargin = cardMargin
                            bottomMargin = cardMargin
                        } else {
                            topMargin = cardMargin
                            rightMargin = cardMargin
                            bottomMargin = cardMargin
                        }
                    }
            panel.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, 24f * resources.displayMetrics.density)
                    }
                }
            panel.clipToOutline = true
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // 在控制入口读取真实状态决定播放或暂停，图标只跟随会话回调
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            panel.findViewById<TextView>(R.id.dashMediaTitle).apply {
                contentDescription = "打开当前播放器"
                isFocusable = true
                setOnClickListener {
                    val target = MediaSessionsState.nowPlaying ?: return@setOnClickListener
                    val pkg = target.pkg ?: return@setOnClickListener
                    // 包管理器查询及应用启动保持在后台，不阻塞歌词或播放控件
                    Bg.run("launch-player") {
                        val component = packageManager.getLaunchIntentForPackage(pkg)?.component
                        if (MediaSessionsState.nowPlaying?.sessionGeneration != target.sessionGeneration) return@run
                        if (component != null) {
                            VirtualLauncher.launchApp(component)
                        } else {
                            VirtualLauncher.logSink?.invoke("MEDIA: 当前播放器无可启动入口")
                        }
                    }
                }
            }
            // 全屏地图的退出走 dock 的 home 按钮（showDashboard 路由闸门会自动
            // setMapFullscreen(false)），仪表盘卡上不再放「全屏地图」按钮
            bindSplitHandle(scale)
            renderMediaCard()
        }

        /** 配置分屏把手（方向/尺寸/拖拽回调）并按当前分屏摆放面板与把手。 */
        private fun bindSplitHandle(scale: Float) {
            val child = (itemView as? android.widget.FrameLayout)?.getChildAt(1) as? DashSplitHandle
            handle = child ?: return
            val portrait = VirtualLauncher.dockAtBottom
            // 触达条比视觉中缝宽（DASH_HANDLE_TOUCH_PX > DASH_GAP_PX）：两侧各溢出
            // 一段压在卡片上扩大触控目标——地图侧溢出段被 tile 盖住不可见但可触摸
            val thickness = (DASH_HANDLE_TOUCH_PX * scale).toInt()
            child.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else thickness,
                        if (portrait) thickness else ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            child.configure(portrait) { centerScreen, final ->
                if (final) {
                    // commit 内部钳制并真实调整地图屏尺寸，返回值用于校正边距防止漂移
                    layoutSplit(VirtualLauncher.commitDashSplitScreen(centerScreen), scale)
                } else {
                    layoutSplit(centerScreen, scale)
                    VirtualLauncher.previewDashSplitScreen(centerScreen)
                }
            }
            layoutSplit(VirtualLauncher.dashHandleCenterScreen(), scale)
        }

        /** 把手中心（应用屏像素）→ 音乐卡与把手的 margin。未 attach（<0）时兜底中线。 */
        private fun layoutSplit(
            centerScreen: Float,
            scale: Float,
        ) {
            val portrait = VirtualLauncher.dockAtBottom
            val center =
                if (centerScreen >= 0) {
                    centerScreen
                } else {
                    // 取不到分屏位置时使用应用区中线
                    ((if (portrait) VirtualLauncher.appAreaHeight else VirtualLauncher.appAreaWidth) / 2f) * scale
                }
            // 音乐卡让位按视觉缝；把手按触达宽居中（比缝宽、压在两侧卡片上）
            val halfGap = DASH_GAP_PX / 2f * scale
            val halfTouch = DASH_HANDLE_TOUCH_PX / 2f * scale
            (panel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                if (portrait) {
                    lp.topMargin = (center + halfGap).toInt()
                } else {
                    lp.leftMargin = (center + halfGap).toInt()
                }
            }
            handle?.let { h ->
                (h.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                    if (portrait) {
                        lp.topMargin = (center - halfTouch).toInt()
                    } else {
                        lp.leftMargin = (center - halfTouch).toInt()
                    }
                }
                h.requestLayout()
            }
            panel.requestLayout()
        }
    }

    private inner class PageHolder(
        private val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // 仪表盘独立成页，不进网格
                    is Cell.Pinned -> {
                        icon.setAppearanceImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.App -> {
                        AppIcons.bind(icon, cell.entry, iconSizePx, this@GridActivity)
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            // 不用系统 Toast：虚拟屏上显示位置不可控（SettingsToastHost 的由来）；
                            // 应用拉起本身就是反馈
                            Bg.run("launch-app") { VirtualLauncher.launchApp(cell.entry.component) }
                        }
                    }
                }
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    private fun openSettings() {
        // 滑动转场由 SettingsActivity 主题的 windowAnimationStyle 提供
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (pages.size < 2) return // 单页不显示页点（对照原型）
        val density = resources.displayMetrics.density
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(UiAppearance.color(this@GridActivity, R.color.glass_handle))
                        }
                },
                LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(UiAppearance.color(this, if (i == selected) R.color.m3_on_surface else R.color.glass_handle))
        }
    }

    /**
     * 离开桌面后停止包变化刷新，旧加载回调失效
     */
    override fun onPause() {
        mediaCardResumed = false
        renderMediaCard()
        reloadRequests.advance()
        VirtualLauncher.removeAppsListener(appsChanged)
        super.onPause()
    }

    override fun onDestroy() {
        widgetPreferences.unregisterOnSharedPreferenceChangeListener(widgetPreferenceListener)
        dashboardPanels.forEach { panel ->
            panel.findViewById<MusicPreviewView>(R.id.dashMusicPreview)?.close()
            panel.findViewById<DashboardWidgetView>(R.id.dashWidgetContent)?.close()
        }
        dashboardPanels.clear()
        dashboardView = null
        tickHandler.removeCallbacks(mediaTick)
        VirtualLauncher.removeDisplayListener(displayListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        MediaSessionsState.removeListener(mediaStateListener)
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // 桌面不退出

    private companion object {
        const val COLUMNS = 4 // CarPlay 规格：强制 4 列

        /** 横屏 CarPlay 规格行数（竖屏自适应时用作下限）。 */
        const val ROWS_MIN = 3

        /** 竖屏最多 6 行 */
        const val ROWS_MAX = 6

        /** 格子目标高宽比（高/宽）：竖屏加行的收敛目标。 */
        const val CELL_HEIGHT_RATIO = 1.25f
    }
}
