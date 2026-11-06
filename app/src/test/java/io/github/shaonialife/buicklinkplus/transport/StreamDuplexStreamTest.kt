package io.github.shaonialife.buicklinkplus.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 检查读线程阻塞时写线程仍可发送 */
class StreamDuplexStreamTest {
    @Test
    fun writeCompletesWhileReaderBlocksInRead() {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()
        sink.connect(source)
        val stream = StreamDuplexStream(source, sink)

        val reader =
            Thread {
                val buffer = ByteArray(1024)
                try {
                    while (stream.read(buffer) >= 0) {
                        // 持续阻塞读，模拟链路通道的读线程
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        Thread.sleep(200) // 确保读线程已经阻塞在 read()

        val written = CountDownLatch(1)
        Thread({
            stream.write(byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1D, 0x00))
            written.countDown()
        }, "writer").apply {
            isDaemon = true
            start()
        }

        assertTrue("write 在读线程阻塞期间发生了死锁", written.await(2, TimeUnit.SECONDS))

        stream.close()
        reader.join(1_000)
    }
}
