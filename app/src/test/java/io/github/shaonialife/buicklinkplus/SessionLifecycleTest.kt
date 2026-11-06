package io.github.shaonialife.buicklinkplus

import io.github.shaonialife.buicklinkplus.session.SessionAttemptGate
import io.github.shaonialife.buicklinkplus.session.SessionTermination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleTest {
    /** 网络与蓝牙并发只允许一个请求，旧收尾不能解锁新会话 */
    @Test fun staleCompletionCannotReleaseNewAttempt() {
        val gate = SessionAttemptGate()
        val old = requireNotNull(gate.begin())
        assertNull(gate.begin())
        gate.cancel()
        val current = requireNotNull(gate.begin())
        gate.finish(old)
        assertTrue(gate.get())
        assertFalse(gate.owns(old))
        gate.finish(current)
        assertFalse(gate.get())
    }

    /** 控制EOF后视频与音频派生异常不改变首因 */
    @Test fun derivedFailuresKeepFirstCause() {
        val state = SessionTermination()
        state.record("控制EOF", 10)
        assertEquals("控制EOF", state.record("视频失败", 20).reason)
        assertEquals(10L, state.record("音频失败", 30).at)
    }
}
