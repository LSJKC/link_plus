package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException

class LyricsLookupTest {
    private val track = LyricsTrack(1, "com.tencent.qqmusic", "1", "歌曲", "歌手", "专辑", 180_000)
    private val record = LyricsRecord("歌曲", "歌手", "专辑", 180_000, "[00:01]歌词", null)

    /** 精确接口使用秒并保留原始LRC，命中后不额外搜索 */
    @Test fun exactLookupPreservesLrcAndUsesSeconds() {
        var calls = 0
        val lookup =
            LyricsLookup { path, parameters, _ ->
                calls++
                assertEquals("/api/get", path)
                assertEquals("180.0", parameters["duration"])
                assertEquals("专辑", parameters["album_name"])
                LyricsResponse(200, listOf(record))
            }
        val result = lookup.load(track, LyricsRequest { 0 })
        assertEquals(LyricsStatus.SYNCED, result.status)
        assertEquals(record.synced, result.rawLrc)
        assertEquals(1, calls)
    }

    /** 未找到后只补一次结构化搜索，纯文本候选可升级为同步歌词 */
    @Test fun missingOrPlainLyricsSearchOnlyOnce() {
        for (exact in listOf(LyricsResponse(404), LyricsResponse(200, listOf(record.copy(synced = null, plain = "纯文本"))))) {
            val calls = mutableListOf<String>()
            val lookup =
                LyricsLookup { path, parameters, _ ->
                    calls.add(path)
                    assertTrue("q" !in parameters)
                    if (path == "/api/get") exact else LyricsResponse(200, listOf(record))
                }
            assertEquals(LyricsStatus.SYNCED, lookup.load(track, LyricsRequest { 0 }).status)
            assertEquals(listOf("/api/get", "/api/search"), calls)
        }
    }

    /** 匹配版本和时长，多候选不直接取首项；翻唱可按唯一歌名匹配 */
    @Test fun ambiguousAndDifferentVersionsAreRejected() {
        assertNull(LyricsLookup.match(track, listOf(record, record)))
        assertNull(LyricsLookup.match(track, listOf(record.copy(title = "歌曲 Live"))))
        assertNull(LyricsLookup.match(track, listOf(record.copy(durationMs = 182_001))))
        assertTrue(LyricsLookup.match(track, listOf(record.copy(durationMs = 182_000))) != null)
        assertTrue(LyricsLookup.match(track, listOf(record, record.copy(album = "另一专辑"))) != null)
    }

    /** 翻唱版本歌名唯一且时长匹配时可用，同名多曲时拒绝 */
    @Test fun coverMatchesOriginalByUniqueTitle() {
        assertTrue(LyricsLookup.match(track, listOf(record.copy(artist = "翻唱歌手"))) != null)
        assertTrue(LyricsLookup.match(track, listOf(record.copy(artist = "翻唱歌手", album = "翻唱专辑"))) != null)
        assertNull(
            LyricsLookup.match(
                track,
                listOf(record.copy(artist = "歌手甲"), record.copy(artist = "歌手乙")),
            ),
        )
    }

    /** 纯文本查询遇到限流仍保留已取得的真实内容，接口错误不追加请求 */
    @Test fun serviceErrorsDoNotCascadeAndPlainTextSurvives() {
        for (status in listOf(400, 401, 429, 503)) {
            var calls = 0
            val failed =
                LyricsLookup { _, _, _ ->
                    calls++
                    LyricsResponse(status)
                }
            assertEquals(LyricsStatus.FAILED, failed.load(track, LyricsRequest { 0 }).status)
            assertEquals(1, calls)
        }
        val plain =
            LyricsLookup { path, _, _ ->
                if (path == "/api/get") LyricsResponse(200, listOf(record.copy(synced = null, plain = "原文"))) else LyricsResponse(503)
            }
        assertEquals(LyricsStatus.PLAIN, plain.load(track, LyricsRequest { 0 }).status)
        val timeout =
            LyricsLookup { path, _, _ ->
                if (path == "/api/get") {
                    LyricsResponse(200, listOf(record.copy(synced = null, plain = "原文")))
                } else {
                    throw java.net.SocketTimeoutException("模拟读取超时")
                }
            }
        assertEquals(LyricsStatus.PLAIN, timeout.load(track, LyricsRequest { 0 }).status)
    }

    /** 缺少歌手不向外发送不可靠查询，取消和总预算覆盖全部接口 */
    @Test fun missingMetadataAndExpiredRequestsNeverQuery() {
        var calls = 0
        val lookup =
            LyricsLookup { _, _, _ ->
                calls++
                LyricsResponse(404)
            }
        assertEquals(LyricsStatus.INSUFFICIENT_INFO, lookup.load(track.copy(artist = ""), LyricsRequest { 0 }).status)
        var time = 0L
        val request = LyricsRequest { time }
        time = 12_000
        assertTrue(runCatching { lookup.load(track, request) }.exceptionOrNull() is InterruptedIOException)
        val cancelled = LyricsRequest { 0 }.apply { cancel() }
        assertTrue(runCatching { lookup.load(track, cancelled) }.exceptionOrNull() is InterruptedIOException)
        assertEquals(0, calls)
    }

    /** 只按明确标志判断纯音乐 */
    @Test fun instrumentalHasExplicitSemantics() {
        val instrumental = LyricsLookup { _, _, _ -> LyricsResponse(200, listOf(record.copy(instrumental = true, synced = null))) }
        assertEquals(LyricsStatus.INSTRUMENTAL, instrumental.load(track, LyricsRequest { 0 }).status)
        assertNull(LyricsLookup.match(track, listOf(record.copy(synced = null))))
    }
}
