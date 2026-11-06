package io.github.shaonialife.buicklinkplus.media

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离线环境下的组件布局测试 */
class MusicPreviewViewTest {
    /** 官方组件下方只显示歌词，保留原音乐卡的封面切换行为 */
    @Test fun lyricsOnlyAreaHasNoCoverNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val preview = MusicPreviewView(instrumentation.targetContext)
            try {
                preview.showLyricsOnly()
                preview.update(null, false)
                assertTrue(preview.showingLyrics)
                val back = allViews(preview).filterIsInstance<TextView>().first { it.text == "返回封面" }
                assertTrue(back.visibility == View.GONE)
                val cover = allViews(preview).first { it.contentDescription == "点击查看歌词" }
                assertTrue(cover.visibility == View.GONE)
            } finally {
                preview.close()
            }
        }
    }

    /** 无封面仍能切换预览，窄卡与短卡的返回入口保持可见 */
    @Test fun placeholderAndBackWorkAtCompactAndNormalSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            for ((widthDp, heightDp) in listOf(160 to 90, 320 to 200)) {
                val preview = MusicPreviewView(context)
                preview.update(null, false)
                val cover = allViews(preview).first { it.contentDescription == "点击查看歌词" }
                assertTrue(cover.performClick())
                assertTrue(preview.showingLyrics)
                val width = (widthDp * density).toInt()
                val height = (heightDp * density).toInt()
                preview.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                preview.layout(0, 0, width, height)
                val back = allViews(preview).filterIsInstance<TextView>().first { it.text == "返回封面" }
                assertTrue(back.width > 0 && back.height > 0 && back.right <= width)
                assertTrue(back.performClick())
                assertFalse(preview.showingLyrics)
                preview.close()
            }
        }
    }

    /** 遍历控件树，不依赖私有字段 */
    private fun allViews(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
