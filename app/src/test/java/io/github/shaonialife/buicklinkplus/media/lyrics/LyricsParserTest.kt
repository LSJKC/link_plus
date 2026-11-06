package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    /** 检查小数时间、多时间标签、偏移和同时间译文 */
    @Test fun parsesOffsetsRepeatedTagsAndTranslations() {
        val document = requireNotNull(LyricsParser.parse("[offset:100]\n[00:01.2][00:03.45]原文\n[00:01.200]译文\n[00:05.678]末句"))
        assertEquals(listOf(1_100L, 3_350L, 5_578L), document.lines.map { it.atMs })
        assertEquals("原文\n译文", document.lines[0].text)
        assertEquals(-1, document.lineAt(1_099))
        assertEquals(0, document.lineAt(1_100))
        assertEquals(1, document.lineAt(5_000))
    }

    /** 纯文本不伪造时间轴，空白间奏也不延长上一句 */
    @Test fun plainAndInstrumentalGapsHaveSeparateSemantics() {
        val plain = requireNotNull(LyricsParser.parse("[ar:歌手]\n第一句\n第二句"))
        assertTrue(plain.lines.isEmpty())
        assertEquals("第一句\n第二句", plain.plainText)
        val synced = requireNotNull(LyricsParser.parse("[00:01]第一句\n[00:03]\n[00:05]第二句"))
        assertEquals("", synced.lines[synced.lineAt(4_000)].text)
    }

    /** 损坏、网址、元数据占位及过大文本不得成为歌词预览 */
    @Test fun rejectsInvalidAndOversizedPayloads() {
        assertNull(LyricsParser.parse("[00:99]错误时间"))
        assertNull(LyricsParser.parse("[ti:只有标题]"))
        assertNull(LyricsParser.parse("https://example.com/lyrics"))
        assertNull(LyricsParser.parse("<html>错误页面</html>"))
        assertNull(LyricsParser.parse("中".repeat(LyricsParser.MAX_BYTES)))
        assertNull(LyricsParser.parse("[00:01]".repeat(2_001) + "歌词"))
        assertNull(LyricsParser.parse("[00:01][00:02]" + "词".repeat(60_000)))
    }

    /** 只接受明确歌词键，忽略简介和网址 */
    @Test fun acceptsOnlyExplicitTextKeys() {
        assertTrue(LyricsParser.isLyricsKey("android.media.metadata.LYRICS"))
        assertTrue(LyricsParser.isLyricsKey("com.player.synced_lyrics"))
        assertFalse(LyricsParser.isLyricsKey("android.media.metadata.DISPLAY_DESCRIPTION"))
        assertFalse(LyricsParser.isLyricsKey("lyrics_url"))
    }

    /** 超长段落分段后内容完整，表情代理对和纯文本语义不变 */
    @Test fun longPlainParagraphsHaveBoundedRowsWithoutBreakingEmoji() {
        val text = "字".repeat(511) + "😀" + "字".repeat(40_000) + "\n\n末行"
        val document = requireNotNull(LyricsParser.parse(text))
        assertTrue(document.lines.isEmpty())
        assertTrue(document.plainRows.all { it.length <= 512 })
        assertTrue(document.plainRows.none { it.firstOrNull()?.isLowSurrogate() == true || it.lastOrNull()?.isHighSurrogate() == true })
        assertEquals(text.replace("\n", ""), document.plainRows.joinToString(""))
        assertEquals("", document.plainRows[document.plainRows.lastIndex - 1])
        assertNull(LyricsDocument.plain("行\n".repeat(2_001)))
    }
}
