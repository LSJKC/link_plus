package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsCacheTest {
    @get:Rule val folder = TemporaryFolder()

    /** 成功歌词重新创建缓存实例后仍可离线读取，曲目版本各自隔离 */
    @Test fun successfulLyricsSurviveRepositoryRecreation() {
        val directory = folder.newFolder()
        val cache = LyricsCache(directory)
        val result = LyricsResult.from(requireNotNull(LyricsParser.parse("[00:01]歌词")), "LRCLIB", "[00:01]歌词")
        cache.put("录音甲", result)
        assertEquals(result, LyricsCache(directory).get("录音甲"))
        assertNull(cache.get("录音乙"))
        assertTrue(directory.listFiles().orEmpty().none { it.name.contains("录音甲") })
        val updated = LyricsResult.from(requireNotNull(LyricsParser.parse("第一段\n第二段")), "LRCLIB")
        cache.put("录音甲", updated)
        val saved = directory.listFiles().orEmpty().single()
        val interrupted = java.io.File(directory, saved.name + ".tmp")
        interrupted.writeText("模拟进程退出时留下的半份缓存")
        assertEquals(updated, LyricsCache(directory).get("录音甲"))
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    /** 未找到只缓存十分钟，网络错误不形成负缓存 */
    @Test fun negativeCacheExpiresAndFailuresAreNotStored() {
        var clock = 1_000L
        val cache = LyricsCache(folder.newFolder()) { clock }
        cache.put("未找到", LyricsResult(LyricsStatus.NOT_FOUND))
        clock += 599_999
        assertEquals(LyricsStatus.NOT_FOUND, cache.get("未找到")?.status)
        clock++
        assertNull(cache.get("未找到"))
        cache.put("网络失败", LyricsResult(LyricsStatus.FAILED))
        assertNull(cache.get("网络失败"))
    }

    /** 成功和负缓存合计最多 100 首，按最近使用时间淘汰 */
    @Test fun cacheEntryCountIsBounded() {
        var clock = 1_000L
        val directory = folder.newFolder()
        val cache = LyricsCache(directory) { clock }
        repeat(101) { index ->
            cache.put("曲目$index", LyricsResult(LyricsStatus.NOT_FOUND))
            clock += 1_000
        }
        assertEquals(100, directory.listFiles().orEmpty().size)
        assertNull(cache.get("曲目0"))
        assertEquals(LyricsStatus.NOT_FOUND, cache.get("曲目100")?.status)
    }

    /** 缓存超过 5MB 时淘汰旧记录，损坏文件按未命中处理 */
    @Test fun cacheBytesAndCorruptFilesAreBounded() {
        var clock = 1_000L
        val directory = folder.newFolder()
        val cache = LyricsCache(directory) { clock }
        val result = LyricsResult.from(LyricsDocument(plainText = "词".repeat(80_000)), "LRCLIB")
        repeat(30) { index ->
            cache.put("长曲目$index", result)
            clock += 1_000
        }
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 5 * 1_024 * 1_024)
        val before = directory.listFiles().orEmpty().toSet()
        cache.put("损坏记录", LyricsResult.from(LyricsDocument(plainText = "歌词"), "LRCLIB"))
        val saved = directory.listFiles().orEmpty().first { it !in before }
        saved.writeBytes(byteArrayOf(0, 1))
        assertNull(cache.get("损坏记录"))
    }
}
