package io.github.shaonialife.buicklinkplus.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查不同车机的 MFi 长度记忆互不影响 */
class MfiLengthMemoryTest {
    private val baselineCar = listOf(20, 32, 16) // 真车系：20B 优先
    private val baselineTestBench = listOf(32, 20, 16) // 测试台：32B 优先

    @Test
    fun receiverKeyIsStableAndFilesystemFriendly() {
        // 0x1D01 的 name + model + manufacturer 拼接（会带空格与大小写差异）
        val a = MfiLengthMemory.receiverKey("audi_mmi_0001 audi 5163 delphi")
        val b = MfiLengthMemory.receiverKey("Audi_MMI_0001  AUDI 5163 DELPHI")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        // 不同车机使用不同记忆键
        assertFalse(a == MfiLengthMemory.receiverKey("audi_mmi_0002 audi 3260 delphi"))
    }

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, MfiLengthMemory.Entry()))
        assertEquals(
            baselineTestBench,
            MfiLengthMemory.orderAttempts(baselineTestBench, MfiLengthMemory.Entry()),
        )
    }

    @Test
    fun rememberedSuccessGoesFirst() {
        // 上次成功的 32B 放在最前，跳过失败档位的等待
        val entry = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineCar, entry))
        // 测试台场景同理（32B 本来就在最前，顺序不变）
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineTestBench, entry))
    }

    @Test
    fun silentAndRejectedLengthsSinkButStayAsFallback() {
        val entry = MfiLengthMemory.Entry(silent = setOf(20), rejected = setOf(16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        // 成功档位没有；20B 静默、16B 被拒都排到最后，32B 提前
        assertEquals(listOf(32, 20, 16), order)
        // 不丢档位：记忆只影响顺序，不会让某个长度永远不被尝试
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun allRejectedFallsBackToTryingEverything() {
        // 记忆与现状矛盾（换了固件/同一身份的不同车）：全部被拒过也要照常试，避免永久卡死
        val entry = MfiLengthMemory.Entry(rejected = setOf(20, 32, 16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(baselineCar, order)
    }

    @Test
    fun onlySucceededLengthIsDroppedFromDuplicateCandidates() {
        val entry = MfiLengthMemory.Entry(succeeded = 20)
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(20, order.first())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun describeShowsWhatWasRemembered() {
        assertEquals("无", MfiLengthMemory.describe(MfiLengthMemory.Entry()))
        val text =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(succeeded = 32, silent = setOf(20), rejected = setOf(16)),
            )
        assertTrue(text.contains("成功=32B"))
        assertTrue(text.contains("静默=20B"))
        assertTrue(text.contains("被拒=16B"))
    }
}
