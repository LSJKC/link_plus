package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查日志按最后一次启动标记切片，覆盖无标记、多标记和截断文件 */
class DebugFileLogTest {
    @Test
    fun keepsOnlyContentAfterLastMarker() {
        val text =
            "old line 1\n" +
                "old line 2\n" +
                "[12:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "previous run line\n" +
                "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n" +
                "current line A\ncurrent line B\n"

        val slice = DebugFileLog.currentSessionSlice(text)

        assertTrue(slice.contains("current line A"))
        assertTrue(slice.contains("current line B"))
        // 上一轮与更早的内容都不能出现（标记本身也不该留在正文里）
        assertTrue(!slice.contains("previous run line"))
        assertTrue(!slice.contains("old line 1"))
        assertTrue(!slice.contains(DebugFileLog.SESSION_MARKER))
    }

    @Test
    fun fallsBackToWholeTextWithoutMarker() {
        val text = "line 1\nline 2\n"
        assertEquals(text, DebugFileLog.currentSessionSlice(text))
    }

    @Test
    fun emptyFileStaysEmpty() {
        assertEquals("", DebugFileLog.currentSessionSlice(""))
    }

    @Test
    fun markerAsLastLineYieldsEmptySlice() {
        // 刚写标记、还没有其它日志：切片为空，导出层会退回界面缓冲
        val text = "[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\n"
        assertEquals("\n", DebugFileLog.currentSessionSlice(text))
    }
}
