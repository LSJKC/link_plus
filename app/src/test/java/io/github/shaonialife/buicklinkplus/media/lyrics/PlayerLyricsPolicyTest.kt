package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查目标播放器白名单，排除标题和歌手元数据 */
class PlayerLyricsPolicyTest {
    @Test fun supportsFourTargetPlayers() {
        assertTrue(PlayerLyricsPolicy.supports("com.tencent.qqmusic"))
        assertTrue(PlayerLyricsPolicy.supports("com.netease.cloudmusic"))
        assertTrue(PlayerLyricsPolicy.supports("com.kugou.android"))
        assertTrue(PlayerLyricsPolicy.supports("cn.kuwo.player"))
        assertFalse(PlayerLyricsPolicy.supports("com.tencent.qqmusic.unknown"))
    }

    @Test fun lyricBodyRejectsMetadataText() {
        assertTrue(
            PlayerLyricsPolicy.isLyricBody(
                "com.netease.cloudmusic",
                "雨下整夜我的爱溢出就像雨水",
                "七里香",
                "七里香",
                "周杰伦",
            ),
        )
        // 正文与通知标题/曲名/歌手同形时不算歌词
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("com.kugou.android", "七里香", "七里香", "七里香", "周杰伦"),
        )
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("com.kugou.android", "周杰伦", "七里香", "七里香", "周杰伦"),
        )
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("cn.kuwo.player", "周杰伦 - 七里香", "七里香", "七里香", "周杰伦"),
        )
        // 非白名单与非法载荷不进入
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("com.other.player", "雨下整夜", null, "七里香", "周杰伦"),
        )
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("cn.kuwo.player", "https://example.com", null, "七里香", "周杰伦"),
        )
        // 播放器 UI 状态文案不是歌词
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("com.kugou.android", "正在播放", null, "七里香", "周杰伦"),
        )
        assertFalse(
            PlayerLyricsPolicy.isLyricBody("com.kugou.android", "暂无歌词", null, "七里香", "周杰伦"),
        )
    }
}
