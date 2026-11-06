package io.github.shaonialife.buicklinkplus.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** 阻塞式双向字节流抽象：蓝牙 RFCOMM socket 与内存回环测试共用。 */
interface DuplexStream : Closeable {
    /** 阻塞读取；返回 -1 表示流结束。 */
    fun read(buffer: ByteArray): Int

    /** 阻塞写入全部字节或抛异常。 */
    fun write(bytes: ByteArray)

    override fun close()
}

class StreamDuplexStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : DuplexStream {
    /**
     * 读写使用独立锁，避免阻塞读占锁后无法发送
     * 写入由链路通道锁串行保护，读取只在收包线程执行
     */
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}

/** 内存回环流对：两端各持一个 DuplexStream，用于单元测试。 */
class InMemoryStreamPair {
    private val aToB = Pipe()
    private val bToA = Pipe()

    val sideA: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = aToB.source.read(buffer)

            override fun write(bytes: ByteArray) = bToA.writeAndFlush(bytes)

            override fun close() {
                aToB.close()
                bToA.closePartner()
            }
        }

    val sideB: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = bToA.source.read(buffer)

            override fun write(bytes: ByteArray) = aToB.writeAndFlush(bytes)

            override fun close() {
                bToA.close()
                aToB.closePartner()
            }
        }

    private class Pipe {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()

        init {
            sink.connect(source)
        }

        fun writeAndFlush(bytes: ByteArray) {
            sink.write(bytes)
            sink.flush()
        }

        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }

        /** 关闭对端出口，让对方读到 EOF。 */
        fun closePartner() {
            runCatching { sink.close() }
        }
    }
}

class DuplexClosedException(
    message: String,
) : IOException(message)
