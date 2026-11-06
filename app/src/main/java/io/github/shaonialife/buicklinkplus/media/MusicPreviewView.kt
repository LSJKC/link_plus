package io.github.shaonialife.buicklinkplus.media

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.lyrics.LrcLyricsView
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsDocument
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsFailure
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsRepository
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsResult
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsStatus
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsTrack
import io.github.shaonialife.buicklinkplus.media.lyrics.PlayerLyricsPolicy
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/**
 * 音乐卡上半部原位预览，歌词区域不派发启动、切歌或桌面翻页手势
 */
class MusicPreviewView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        private val cover = ImageView(context)
        private val lyrics = LinearLayout(context)
        private val header = TextView(context)
        private val back = TextView(context)
        private val current = TextView(context)
        private val lrc = LrcLyricsView(context)
        private val syncLines = LinearLayout(context)
        private val body = FrameLayout(context)
        private var now: MediaSessionsState.NowPlaying? = null
        private var track: LyricsTrack? = null
        private var result = LyricsResult(LyricsStatus.INSUFFICIENT_INFO)
        private var renderedResult: LyricsResult? = null
        private var lyricsMode = false
        private var active = false
        private var closed = false
        var onModeChanged: (() -> Unit)? = null
        val showingLyrics: Boolean get() = lyricsMode
        private val repository by lazy {
            LyricsRepository(context) { value ->
                result = value
                renderResult()
                renderPosition()
            }
        }

        /** 整张音乐卡保持一秒刷新，歌词内部只在下一句边界定位 */
        val tickIntervalMs: Long
            get() = 1_000L

        init {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.clipToOutline = true
            cover.contentDescription = context.getString(R.string.lyrics_open)
            cover.isFocusable = true
            cover.outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
            addView(cover, LayoutParams(dp(118), dp(118), Gravity.CENTER))
            cover.setOnClickListener {
                showLyrics()
            }
            lyrics.orientation = LinearLayout.VERTICAL
            lyrics.visibility = GONE
            addView(lyrics, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.textSize = 11f
            header.setAppearanceTextColor(R.color.m3_on_var)
            header.maxLines = 1
            header.ellipsize = android.text.TextUtils.TruncateAt.END
            bar.addView(header, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            back.setText(R.string.lyrics_back_cover)
            back.textSize = 12f
            back.gravity = Gravity.CENTER
            back.setPadding(dp(8), 0, dp(8), 0)
            back.minHeight = dp(44)
            back.isFocusable = true
            back.setAppearanceTextColor(R.color.m3_primary)
            back.setOnClickListener {
                lyricsMode = false
                repository.clear()
                lrc.setPlayback(null, false)
                lrc.setDocument(null)
                track = null
                lyrics.visibility = GONE
                cover.visibility = VISIBLE
                onModeChanged?.invoke()
            }
            bar.addView(back, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            lyrics.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            lyrics.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            syncLines.orientation = LinearLayout.VERTICAL
            syncLines.gravity = Gravity.CENTER
            for (line in listOf(current)) {
                line.gravity = Gravity.CENTER
                line.maxLines = 2
                line.ellipsize = android.text.TextUtils.TruncateAt.END
                line.textSize = if (line === current) 17f else 13f
                line.setAppearanceTextColor(if (line === current) R.color.m3_primary else R.color.m3_on_var)
                syncLines.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            current.setTypeface(null, android.graphics.Typeface.BOLD)
            body.addView(syncLines, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            body.addView(lrc, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            renderCover()
        }

        /** 官方组件与原音乐卡共用歌词数据 */
        fun showLyrics() {
            if (closed || lyricsMode) return
            lyricsMode = true
            cover.visibility = GONE
            lyrics.visibility = VISIBLE
            result = LyricsResult(if (now == null) LyricsStatus.INSUFFICIENT_INFO else LyricsStatus.WAITING_FOR_PLAYER)
            renderResult()
            requestLyrics()
            onModeChanged?.invoke()
        }

        /** 官方组件下方只显示歌词，隐藏返回封面按钮并保留来源提示 */
        fun showLyricsOnly() {
            back.visibility = GONE
            header.minHeight = dp(28)
            header.gravity = Gravity.CENTER_VERTICAL
            showLyrics()
        }

        /** 实时句直接随快照刷新，全文内容不变时不重复解析 */
        fun update(
            value: MediaSessionsState.NowPlaying?,
            visible: Boolean,
        ) {
            if (closed) return
            val oldArt = now?.art
            now = value
            val wasActive = active
            active = visible && isAttachedToWindow && windowVisibility == VISIBLE
            if (oldArt !== value?.art) renderCover()
            if (!active) {
                lrc.setPlayback(null, false)
                if (wasActive) {
                    repository.clear()
                    track = null
                }
                return
            }
            if (lyricsMode) {
                requestLyrics()
                renderResult()
                renderPosition()
            }
        }

        /** 无封面时保留可点击占位，不因异步图片缺失隐藏歌词入口 */
        private fun renderCover() {
            val art = now?.art
            if (art != null) {
                cover.setImageBitmap(art)
                cover.imageTintList = null
            } else {
                cover.setImageResource(R.drawable.ic_music)
                cover.imageTintList =
                    io.github.shaonialife.buicklinkplus.ui.UiAppearance
                        .colors(context, R.color.m3_on_var)
            }
        }

        /** 当前窗口与媒体会话共同隔离解析，播放器补发内容时允许更新 */
        private fun requestLyrics() {
            if (!active || closed) return
            val value = now
            if (value == null) {
                repository.clear()
                track = null
                result = LyricsResult(LyricsStatus.INSUFFICIENT_INFO)
                renderResult()
                return
            }
            val target =
                LyricsTrack(
                    value.sessionGeneration,
                    value.pkg.orEmpty(),
                    value.mediaId,
                    value.title.orEmpty(),
                    value.artist.orEmpty(),
                    value.album.orEmpty(),
                    value.durationMs,
                    value.lyrics,
                )
            if (target == track) return
            track = target
            repository.select(target)
        }

        /** 只更新变化内容，实时句单独展示，纯文本保留滚动位置 */
        private fun renderResult() {
            val live = now?.lyricLine?.takeIf { it.isNotBlank() && PlayerLyricsPolicy.supports(now?.pkg.orEmpty()) }
            val display =
                if (result.status != LyricsStatus.SYNCED && result.status != LyricsStatus.INSTRUMENTAL && live != null) {
                    LyricsResult(LyricsStatus.LIVE, LyricsDocument(plainText = live), "播放器")
                } else {
                    result
                }
            if (renderedResult == display) return
            renderedResult = display
            val status = display.status
            header.text =
                when (status) {
                    LyricsStatus.SYNCED -> context.getString(R.string.lyrics_synced_source, display.source)
                    LyricsStatus.LIVE -> context.getString(R.string.lyrics_live)
                    LyricsStatus.PLAIN -> context.getString(R.string.lyrics_plain)
                    else ->
                        context.getString(
                            when (display.failure) {
                                LyricsFailure.NO_INTERNET -> R.string.lyrics_no_internet
                                LyricsFailure.SERVER_BUSY -> R.string.lyrics_server_busy
                                LyricsFailure.RATE_LIMITED -> R.string.lyrics_rate_limited
                                LyricsFailure.TIMEOUT -> R.string.lyrics_timeout
                                LyricsFailure.CONNECTION -> R.string.lyrics_connection_failed
                                LyricsFailure.INVALID_RESPONSE -> R.string.lyrics_invalid_response
                                LyricsFailure.QUEUE_FULL -> R.string.lyrics_queue_busy
                                null -> R.string.lyrics_preview
                            },
                        )
                }
            val hasDocument = status == LyricsStatus.PLAIN || status == LyricsStatus.SYNCED
            syncLines.visibility = if (hasDocument) GONE else VISIBLE
            lrc.visibility = if (hasDocument) VISIBLE else GONE
            lrc.setDocument(display.document.takeIf { hasDocument })
            lrc.setPlayback(now.takeIf { status == LyricsStatus.SYNCED }, active && lyricsMode && status == LyricsStatus.SYNCED)
            if (!hasDocument) {
                current.text =
                    when (status) {
                        LyricsStatus.LOADING -> context.getString(R.string.lyrics_loading)
                        LyricsStatus.LIVE -> display.document?.plainText.orEmpty()
                        LyricsStatus.INSTRUMENTAL -> context.getString(R.string.lyrics_instrumental)
                        else -> context.getString(R.string.lyrics_unavailable)
                    }
            }
        }

        /** 暂停或缓冲时不推算位置，未知位置不高亮首句 */
        private fun renderPosition() {
            if (result.status != LyricsStatus.SYNCED) return
            lrc.setPlayback(now, active && lyricsMode)
        }

        /** 高度不足时优先保留当前行及返回入口，不挤压卡片下方播放按钮 */
        override fun onSizeChanged(
            width: Int,
            height: Int,
            oldWidth: Int,
            oldHeight: Int,
        ) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            val size = minOf(dp(118), width, height).coerceAtLeast(1)
            cover.layoutParams =
                (cover.layoutParams as LayoutParams).apply {
                    this.width = size
                    this.height = size
                }
            header.maxLines = if (width < dp(200)) 2 else 1
            current.maxLines = if (height < dp(115)) 1 else 2
        }

        /** 隐藏页面停止等待，重新可见后由新的媒体快照恢复 */
        override fun onDetachedFromWindow() {
            active = false
            repository.clear()
            lrc.setPlayback(null, false)
            lrc.setDocument(null)
            track = null
            super.onDetachedFromWindow()
        }

        /** 窗口隐藏后立即取消歌词请求 */
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != VISIBLE && active) {
                active = false
                repository.clear()
                lrc.setPlayback(null, false)
                track = null
            }
        }

        /** 窗口销毁后不再接受媒体轮询或歌词回调 */
        fun close() {
            closed = true
            onModeChanged = null
            repository.clear()
            lrc.setPlayback(null, false)
            lrc.setDocument(null)
            track = null
            now = null
            cover.setImageDrawable(null)
        }

        /** 动态封面占位在日夜切换后同步语义颜色 */
        fun refreshAppearance() {
            renderCover()
        }

        /** 尺寸使用当前显示屏密度 */
        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }
