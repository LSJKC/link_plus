package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsStateTest {
    private val track = PlayerLyricsIdentity(1, "com.tencent.qqmusic", "甲", "歌曲甲", "歌手", "专辑")

    /** 独立车载包可以使用既有歌词来源，但普通通知仍不能充当歌词 */
    @Test fun officialCarPackagesRequireRealLyricsSignals() {
        for (player in listOf("com.tencent.qqmusiccar", "com.kugou.android.auto", "com.netease.cloudmusic.iot")) {
            assertTrue(PlayerLyricsPolicy.supports(player))
            assertFalse(PlayerLyricsPolicy.isLyricTicker(player, 0))
            assertTrue(PlayerLyricsPolicy.isLyricTicker(player, 0x01000000))
        }
        assertFalse(PlayerLyricsPolicy.supports("com.tencent.qqmusiccar.fake"))
    }

    /** 普通 ticker 和白名单外通知不作歌词，目标播放器需明确标志 */
    @Test fun tickerRequiresKnownPlayerAndExplicitFlag() {
        for (player in listOf("com.tencent.qqmusic", "com.netease.cloudmusic", "com.kugou.android", "cn.kuwo.player")) {
            assertFalse(PlayerLyricsPolicy.isLyricTicker(player, 2))
            assertTrue(PlayerLyricsPolicy.isLyricTicker(player, 0x01000000))
            assertTrue(PlayerLyricsPolicy.isLyricTicker(player, 0x02000000))
        }
        assertFalse(PlayerLyricsPolicy.isLyricTicker("其他应用", 0x01000000))
    }

    /** 实时句保留原文，不生成时间轴，错误应用和歌名不能覆盖当前内容 */
    @Test fun acceptsOnlyCurrentPlayerAndMatchingTitle() {
        val state = PlayerLyricsState()
        state.select(track, 1_000)
        assertFalse(state.accept("cn.kuwo.player", "1", 1_100, 100, "错误来源", true))
        assertFalse(state.accept(track.packageName, "1", 1_100, 100, "错误歌曲", false, "歌曲乙"))
        assertTrue(state.accept(track.packageName, "1", 1_100, 100, "当前原句", true))
        assertEquals("当前原句", state.current(101, true)?.text)
        assertTrue(state.current(101, true)?.live == true)
    }

    /** 切歌、同名新会话及元数据补齐均清除旧内容 */
    @Test fun trackAndSessionChangesClearOldLines() {
        val state = PlayerLyricsState()
        state.select(track, 1_000)
        state.accept(track.packageName, "1", 1_100, 100, "旧句", true)
        state.select(track.copy(mediaId = "乙", title = "歌曲乙"), 2_000)
        assertNull(state.current(200, true))
        assertFalse(state.accept(track.packageName, "1", 1_999, 200, "旧回调", true))
        state.select(track.copy(session = 2), 3_000)
        assertNull(state.current(200, true))
        state.accept(track.packageName, "1", 3_100, 300, "新句", true)
        state.select(track.copy(session = 2, album = "补齐专辑"), 3_200)
        assertNull(state.current(400, true))
        state.clear()
        assertFalse(state.accept(track.packageName, "1", 4_000, 500, "退役回调", true))
    }

    /** 通知略早于元数据时，只有歌名明确一致的近期全文允许补入 */
    @Test fun oneShotFullLyricsCanArriveJustBeforeMetadata() {
        val state = PlayerLyricsState()
        state.select(track, 5_000)
        assertFalse(state.accept(track.packageName, "1", 4_900, 100, "实时句", true))
        assertFalse(state.accept(track.packageName, "1", 2_999, 100, "旧全文", false, track.title))
        assertTrue(state.accept(track.packageName, "1", 4_900, 100, "[00:01]歌词", false, track.title))
        val result = requireNotNull(state.current(50_000, true))
        assertFalse(result.live)
        assertEquals("[00:01]歌词", result.text)
    }

    /** 重用通知编号时，旧更新及旧移除都不得清掉新内容 */
    @Test fun notificationOrderingAndRemovalAreBounded() {
        val state = PlayerLyricsState()
        state.select(track, 1_000)
        state.accept(track.packageName, "1", 1_100, 100, "第一句", true)
        state.accept(track.packageName, "1", 1_200, 200, "第二句", true)
        assertFalse(state.accept(track.packageName, "1", 1_150, 250, "迟到第一句", true))
        assertFalse(state.remove("1", 1_100))
        assertFalse(state.remove("其他通知", 1_300))
        assertEquals("第二句", state.current(300, true)?.text)
        assertTrue(state.remove("1", 1_200))
        assertNull(state.current(400, true))
    }

    /** 暂停保留当前句，恢复后长期无更新则清除 */
    @Test fun staleLiveLinesExpireWithoutAffectingFullLyrics() {
        val state = PlayerLyricsState()
        state.select(track, 1_000)
        state.accept(track.packageName, "1", 1_100, 100, "实时句", true)
        assertEquals("实时句", state.current(30_000, false)?.text)
        assertNull(state.current(30_000, true))
        state.accept(track.packageName, "1", 1_200, 200, "完整歌词", false)
        assertEquals("完整歌词", state.current(90_000, true)?.text)
    }

    /** 实时通知与全文通知并存时保留时间轴，独立撤销各自来源 */
    @Test fun liveUpdatesDoNotReplaceTheFullDocument() {
        val state = PlayerLyricsState()
        state.select(track, 1_000)
        state.accept(track.packageName, "全文通知", 1_100, 100, "[00:01]完整时间轴", false)
        state.accept(track.packageName, "实时通知", 1_200, 200, "当前句", true)
        assertEquals("[00:01]完整时间轴", state.fullText?.text)
        assertEquals("当前句", state.current(300, true)?.text)
        state.remove("实时通知", 1_200)
        assertEquals("[00:01]完整时间轴", state.current(400, true)?.text)
        assertFalse(state.accept(track.packageName, "实时通知", 1_100, 500, "迟到旧句", true))
        state.remove("全文通知", 1_100)
        assertNull(state.fullText)
    }

    /** 空白、网址、未知载荷与超大实时句不能进入展示层 */
    @Test fun invalidNativePayloadsAreRejected() {
        assertFalse(PlayerLyricsPolicy.validText(" ", true))
        assertFalse(PlayerLyricsPolicy.validText("https://example.com/lyric", false))
        assertFalse(PlayerLyricsPolicy.validText("{未知载荷}", false))
        assertFalse(PlayerLyricsPolicy.validText("词".repeat(2_000), true))
        assertTrue(PlayerLyricsPolicy.validText("[00:01]原生歌词", false))
    }
}
