package io.github.shaonialife.buicklinkplus.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 私有歌词缓存采用受限二进制结构，不保存原始接口响应 */
internal class LyricsCache(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        // 仅清理本版本原子写入被进程退出打断后遗留的临时文件
        runCatching {
            directory.listFiles { item -> item.name.matches(Regex("[a-f0-9]{64}\\.lyrics\\.tmp")) }?.forEach { it.delete() }
        }
    }

    /** 命中时更新最近使用时间，未找到结果十分钟后失效 */
    fun get(key: String): LyricsResult? {
        val file = file(key)
        if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC)
                val created = input.readLong()
                val status = LyricsStatus.valueOf(readText(input))
                require(status in CACHEABLE)
                if (status == LyricsStatus.NOT_FOUND && (clock() < created || clock() - created >= 600_000)) return null
                val source = readText(input)
                val result =
                    if (status == LyricsStatus.NOT_FOUND) {
                        LyricsResult(status)
                    } else {
                        val instrumental = input.readBoolean()
                        val raw = readText(input).takeIf { it.isNotBlank() }
                        val plain = readText(input)
                        val count = input.readInt()
                        require(count in 0..2_000)
                        val lines = List(count) { LyricsLine(input.readLong(), readText(input)) }
                        require(lines.all { it.atMs >= 0 })
                        require(lines.zipWithNext().all { (first, second) -> first.atMs <= second.atMs })
                        require(
                            lines.sumOf {
                                it.text
                                    .toByteArray(Charsets.UTF_8)
                                    .size
                                    .toLong()
                            } <= LyricsParser.MAX_BYTES,
                        )
                        require(instrumental || plain.isNotBlank() || lines.any { it.text.isNotBlank() })
                        val document =
                            if (lines.isEmpty() && !instrumental) {
                                requireNotNull(LyricsDocument.plain(plain))
                            } else {
                                LyricsDocument(lines, plain, instrumental)
                            }
                        LyricsResult.from(document, source, raw).also { require(it.status == status) }
                    }
                file.setLastModified(clock())
                result
            }
        }.getOrNull()
    }

    /** 单后台执行器串行读写，落盘失败只影响缓存，不影响当前歌词显示 */
    fun put(
        key: String,
        result: LyricsResult,
    ) {
        if (result.status !in CACHEABLE) return
        runCatching {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(clock())
                writeText(output, result.status.name)
                writeText(output, result.source)
                if (result.status != LyricsStatus.NOT_FOUND) {
                    val document = requireNotNull(result.document)
                    output.writeBoolean(document.instrumental)
                    writeText(output, result.rawLrc.orEmpty())
                    writeText(output, document.plainText)
                    output.writeInt(document.lines.size)
                    document.lines.forEach { line ->
                        output.writeLong(line.atMs)
                        writeText(output, line.text)
                    }
                }
            }
            if (bytes.size() > MAX_ENTRY_BYTES) return
            directory.mkdirs()
            val target = file(key)
            val temporary = File(directory, target.name + ".tmp")
            try {
                temporary.writeBytes(bytes.toByteArray())
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
            target.setLastModified(clock())
            val files =
                directory
                    .listFiles { item -> item.name.matches(Regex("[a-f0-9]{64}\\.lyrics")) }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
            var bytesKept = 0L
            files.forEachIndexed { index, item ->
                bytesKept += item.length()
                if (index >= 100 || bytesKept > 5 * 1_024 * 1_024) item.delete()
            }
        }
    }

    /** 文件名使用曲目键摘要，文件名不包含歌名或路径片段 */
    private fun file(key: String): File {
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$hash.lyrics")
    }

    companion object {
        private const val MAGIC = 0x4C524302
        private const val MAX_ENTRY_BYTES = 768 * 1_024
        private val CACHEABLE = setOf(LyricsStatus.SYNCED, LyricsStatus.PLAIN, LyricsStatus.INSTRUMENTAL, LyricsStatus.NOT_FOUND)

        /** 字符串独立校验长度，损坏缓存不能导致无界分配 */
        private fun readText(input: DataInputStream): String {
            val size = input.readInt()
            require(size in 0..LyricsParser.MAX_BYTES)
            return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
        }

        /** 按 UTF-8 字节长度写入，避免 DataOutput 短字符串上限 */
        private fun writeText(
            output: DataOutputStream,
            value: String,
        ) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= LyricsParser.MAX_BYTES)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }
}
