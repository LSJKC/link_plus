package io.github.shaonialife.buicklinkplus.carlife

import io.github.shaonialife.buicklinkplus.capture.RetiringDecoderGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RetiringDecoderGate] 纯 JVM 测试（不触碰 android.*）。
 *
 * 钉住审计发现的关键规则：解码器 stopAndWait 超时后，只要旧解码线程还活着，就绝不允许在
 * **同一个编码器输入 Surface** 上新建解码器；只有线程真正退出才清门放行。
 */
class RetiringDecoderGateTest {
    /** 假解码器：线程存活状态可手动翻转，替代真实 [CarLifeVideoDecoder]（其构造需要 Android Surface）。 */
    private class FakeDecoder(
        var threadAlive: Boolean,
    )

    private fun gate() = RetiringDecoderGate<FakeDecoder> { decoder -> decoder.threadAlive }

    @Test
    fun allowsCreateWhenNoRetiringDecoder() {
        val gate = gate()
        assertTrue(gate.canCreate())
        assertNull(gate.retiring)
    }

    @Test
    fun blocksCreateWhileRetiringThreadStillAlive() {
        val gate = gate()
        val old = FakeDecoder(threadAlive = true)
        gate.retire(old)

        assertFalse("旧解码线程未退出时必须推迟新建，否则同一 Surface 出现双 producer", gate.canCreate())
        // 反复检查仍然拒绝，且门保持原引用。
        assertFalse(gate.canCreate())
        assertSame(old, gate.retiring)
    }

    @Test
    fun opensGateOnlyAfterRetiringThreadExits() {
        val gate = gate()
        val old = FakeDecoder(threadAlive = true)
        gate.retire(old)
        assertFalse(gate.canCreate())

        old.threadAlive = false
        assertTrue("旧解码线程已退出：应清门放行", gate.canCreate())
        assertNull("放行后门必须清空", gate.retiring)
        assertTrue(gate.canCreate())
    }

    @Test
    fun latestRetiringDecoderWins() {
        val gate = gate()
        val first = FakeDecoder(threadAlive = true)
        val second = FakeDecoder(threadAlive = true)
        gate.retire(first)
        gate.retire(second)
        first.threadAlive = false
        // 门里是后一个（仍在跑）→ 继续拒绝。
        assertFalse(gate.canCreate())

        second.threadAlive = false
        assertTrue(gate.canCreate())
        assertNull(gate.retiring)
    }
}
