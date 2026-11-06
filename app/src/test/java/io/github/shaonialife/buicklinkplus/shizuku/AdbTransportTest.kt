package io.github.shaonialife.buicklinkplus.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket

/**
 * 传输取消必须关闭等待握手的两端，不能只改变界面状态
 */
class AdbTransportTest {
    /**
     * 远端端口拒绝连接时保留真实原因，不能只向上层留下 EOF
     */
    @Test
    fun refusedEndpointKeepsTransportCause() {
        val port = ServerSocket(0).use { it.localPort }
        AdbTransport("127.0.0.1", port).use { transport ->
            Socket("127.0.0.1", transport.localPort).use { client ->
                client.soTimeout = 2000
                assertEquals(-1, client.getInputStream().read())
                assertTrue("必须保留远端拒绝连接的实际异常", transport.failure is ConnectException)
            }
        }
    }

    @Test
    fun cancellingClosesBothDirections() {
        ServerSocket(0).use { server ->
            server.soTimeout = 2000
            AdbTransport("127.0.0.1", server.localPort).use { transport ->
                Socket("127.0.0.1", transport.localPort).use { client ->
                    server.accept().use { peer ->
                        client.soTimeout = 2000
                        peer.soTimeout = 2000
                        client.getOutputStream().write(37)
                        assertEquals(37, peer.getInputStream().read())
                        peer.getOutputStream().write(51)
                        assertEquals(51, client.getInputStream().read())
                        transport.close()
                        assertEquals(-1, peer.getInputStream().read())
                        assertEquals(-1, client.getInputStream().read())
                    }
                }
            }
        }
    }
}
