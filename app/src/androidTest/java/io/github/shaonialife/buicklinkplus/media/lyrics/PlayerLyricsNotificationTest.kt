package io.github.shaonialife.buicklinkplus.media.lyrics

import android.app.Notification
import android.media.session.MediaSession
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsNotificationTest {
    /** 诊断只记录有限字段名和存在标志，不记录正文或歌词 */
    @Test fun schemaDiagnosticsAreBoundedAndExcludeContent() {
        val notification =
            Notification().apply {
                flags = 0x01000000
                tickerText = "不得进入日志的实时歌词"
                extras =
                    Bundle().apply {
                        putString(Notification.EXTRA_TEXT, "不得进入日志的通知正文")
                        repeat(160) { putString("test.$it." + "x".repeat(100), "不得进入日志的字段内容") }
                    }
            }
        val schema = PlayerLyricsNotification.schema(notification)
        assertTrue(schema.contains("flags=1000000"))
        assertTrue(schema.contains("ticker=true"))
        assertTrue(!schema.contains("不得进入日志"))
        assertTrue(schema.length < 1_650)
    }

    /** 普通媒体通知的正文不能作为歌词来源 */
    @Test fun ordinaryNotificationTextIsNeverGuessedAsLyrics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSession(context, "歌词通知用例")
        try {
            val notification =
                Notification().apply {
                    tickerText = "看起来像歌词的普通提示"
                    extras =
                        Bundle().apply {
                            putString(Notification.EXTRA_TITLE, "歌曲甲")
                            putString(Notification.EXTRA_TEXT, "普通正文")
                            putParcelable(Notification.EXTRA_MEDIA_SESSION, session.sessionToken)
                        }
                }
            assertNull(PlayerLyricsNotification.read("com.tencent.qqmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"))
            notification.flags = 0x01000000
            val value =
                requireNotNull(
                    PlayerLyricsNotification.read("com.tencent.qqmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"),
                )
            assertTrue(value.live)
            assertEquals("看起来像歌词的普通提示", value.text)
        } finally {
            session.release()
        }
    }

    /** 歌词字段需匹配曲目和 Token，拒绝错误类型或会话 */
    @Test fun explicitLyricsRequireTheCurrentSessionOrSong() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSession(context, "当前歌词会话")
        val other = MediaSession(context, "其他歌词会话")
        try {
            val notification =
                Notification().apply {
                    extras =
                        Bundle().apply {
                            putString(Notification.EXTRA_TITLE, "歌曲甲")
                            putString("android.media.metadata.LYRICS", "[00:01]原生歌词")
                            putParcelable(Notification.EXTRA_MEDIA_SESSION, other.sessionToken)
                        }
                }
            assertNull(PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"))
            notification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, session.sessionToken)
            val value =
                requireNotNull(
                    PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"),
                )
            assertEquals("[00:01]原生歌词", value.text)
            assertNull(PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲乙", "歌手甲"))
            notification.extras.putString(Notification.EXTRA_MEDIA_SESSION, "错误Token类型")
            assertNull(PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"))
            notification.extras.remove(Notification.EXTRA_MEDIA_SESSION)
            assertTrue(PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲甲", "歌手甲") != null)
            notification.extras.remove(Notification.EXTRA_TITLE)
            assertNull(PlayerLyricsNotification.read("com.netease.cloudmusic", notification, session.sessionToken, "歌曲甲", "歌手甲"))
        } finally {
            session.release()
            other.release()
        }
    }
}
