package io.github.shaonialife.buicklinkplus.media.lyrics

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import kotlin.math.abs

/** 歌词只在文档变化时重建行，播放过程中按下一句时间安排单次刷新 */
class LrcLyricsView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        private val list = RecyclerView(context)
        private val manager = LinearLayoutManager(context)
        private val rows = Rows()
        private var document: LyricsDocument? = null
        private var playback: MediaSessionsState.NowPlaying? = null
        private var enabled = false
        private val tick = Runnable { locate(true) }

        init {
            list.layoutManager = manager
            list.adapter = rows
            list.itemAnimator = null
            list.overScrollMode = View.OVER_SCROLL_NEVER
            list.clipToPadding = false
            list.isVerticalScrollBarEnabled = false
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        /** 原始解析结果实例不变时不触发整表通知和文字测量 */
        internal fun setDocument(value: LyricsDocument?) {
            if (document === value) return
            removeCallbacks(tick)
            list.stopScroll()
            document = value
            val oldSize = rows.itemCount
            rows.lines = value?.lines.orEmpty()
            rows.plainRows = value?.plainRows.orEmpty()
            rows.current = -1
            if (oldSize > 0) rows.notifyItemRangeRemoved(0, oldSize)
            if (rows.itemCount > 0) rows.notifyItemRangeInserted(0, rows.itemCount)
            updatePadding(height)
            manager.scrollToPositionWithOffset(0, 0)
            locate(false)
        }

        /** 播放状态变化立即重算，跳转或会话变化直接定位而不堆积滚动动画 */
        internal fun setPlayback(
            value: MediaSessionsState.NowPlaying?,
            visible: Boolean,
        ) {
            val now = SystemClock.elapsedRealtime()
            val old = playback
            val continuous =
                old != null &&
                    value != null &&
                    old.sessionGeneration == value.sessionGeneration &&
                    abs(old.displayPositionAt(now) - value.displayPositionAt(now)) < 750
            playback = value
            enabled = visible
            locate(continuous)
        }

        /** 二分定位后只更新旧行和新行，静止画面不会周期性重绘 */
        private fun locate(animate: Boolean) {
            removeCallbacks(tick)
            val data = document ?: return
            if (data.lines.isEmpty()) return
            val state = playback ?: return
            if (!enabled || !isAttachedToWindow || windowVisibility != VISIBLE || visibility != VISIBLE) return
            val now = SystemClock.elapsedRealtime()
            val position = state.takeIf { it.positionKnown }?.displayPositionAt(now)
            val index = position?.let(data::lineAt) ?: -1
            if (rows.current != index) {
                val old = rows.current
                rows.current = index
                if (old in rows.lines.indices) rows.notifyItemChanged(old, true)
                if (index in rows.lines.indices) rows.notifyItemChanged(index, true)
                list.stopScroll()
                if (index >= 0) {
                    if (animate) {
                        manager.startSmoothScroll(
                            object : LinearSmoothScroller(context) {
                                override fun calculateDtToFit(
                                    viewStart: Int,
                                    viewEnd: Int,
                                    boxStart: Int,
                                    boxEnd: Int,
                                    snap: Int,
                                ): Int = (boxStart + boxEnd - viewStart - viewEnd) / 2

                                override fun calculateTimeForDeceleration(dx: Int): Int = minOf(180, super.calculateTimeForDeceleration(dx))
                            }.apply { targetPosition = index },
                        )
                    } else {
                        centerImmediately(index)
                    }
                }
            }
            if (state.anchoredAtMs in 1..now) {
                LyricsTimeline.nextDelay(data, position, state.playbackSpeed, state.playing)?.let { postDelayed(tick, it) }
            }
        }

        /** 首次定位和尺寸变化直接居中，保持字号变化下的可见行 */
        private fun centerImmediately(index: Int) {
            val rowHeight = manager.findViewByPosition(index)?.height ?: dp(40)
            manager.scrollToPositionWithOffset(index, (list.height - list.paddingTop - list.paddingBottom - rowHeight) / 2)
        }

        /** 首尾留出居中空间，歌词区域不挤占下方播放控制 */
        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            updatePadding(h)
            if (rows.current >= 0) centerImmediately(rows.current)
        }

        /** 纯文本自然滚动，同步歌词首尾保留居中空间 */
        private fun updatePadding(height: Int) {
            val padding = if (rows.lines.isEmpty()) dp(4) else ((height - dp(40)) / 2).coerceAtLeast(0)
            list.setPadding(0, padding, 0, padding)
        }

        /** 只在附着并可见时恢复下一句调度 */
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            locate(false)
        }

        /** 隐藏后移除唯一的歌词时间任务并停止滚动 */
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) {
                locate(false)
            } else {
                removeCallbacks(tick)
                list.stopScroll()
            }
        }

        /** 退出窗口不持有旧媒体封面、状态或歌词任务 */
        override fun onDetachedFromWindow() {
            removeCallbacks(tick)
            list.stopScroll()
            playback = null
            enabled = false
            super.onDetachedFromWindow()
        }

        /** 歌词滑动只由本区域消费，不触发桌面横向翻页 */
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }

        /** 行高和内边距按实际虚拟屏密度计算 */
        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        private class Row(
            val text: TextView,
        ) : RecyclerView.ViewHolder(text)

        /** 高亮只改变语义色，不改变字号或字重，避免每句重新测量整表 */
        private inner class Rows : RecyclerView.Adapter<Row>() {
            var lines: List<LyricsLine> = emptyList()
            var plainRows: List<String> = emptyList()
            var current = -1

            override fun getItemCount(): Int = if (lines.isNotEmpty()) lines.size else plainRows.size

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): Row =
                Row(
                    TextView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        minHeight = dp(40)
                        gravity = Gravity.CENTER
                        textSize = 16f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                    },
                )

            override fun onBindViewHolder(
                holder: Row,
                position: Int,
            ) {
                val plain = lines.isEmpty()
                holder.text.maxLines = if (plain) Int.MAX_VALUE else 2
                holder.text.ellipsize = if (plain) null else android.text.TextUtils.TruncateAt.END
                holder.text.text =
                    if (plain) plainRows[position] else lines[position].text.ifBlank { context.getString(R.string.lyrics_gap) }
                holder.text.setAppearanceTextColor(if (position == current) R.color.m3_primary else R.color.m3_on_var)
            }

            override fun onBindViewHolder(
                holder: Row,
                position: Int,
                payloads: MutableList<Any>,
            ) {
                if (payloads.isEmpty()) {
                    onBindViewHolder(holder, position)
                } else {
                    holder.text.setAppearanceTextColor(if (position == current) R.color.m3_primary else R.color.m3_on_var)
                }
            }
        }
    }
