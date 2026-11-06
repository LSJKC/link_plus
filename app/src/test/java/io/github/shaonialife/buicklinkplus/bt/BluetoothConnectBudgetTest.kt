package io.github.shaonialife.buicklinkplus.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothConnectBudgetTest {
    /** 无需等定时回调，亮屏后迟到成功仍为超时 */
    @Test fun delayedTimerCannotAcceptLateConnection() {
        val budget = BluetoothConnectBudget()
        assertFalse(budget.expired(100, 20_099, 20_000))
        assertTrue(budget.expired(100, 20_100, 20_000))
        assertTrue(budget.expired(100, 120_000, 20_000))
    }

    /** 旧任务未退出前同目标及超额任务均不能进入 */
    @Test fun blockedWorkersHaveBoundedSlots() {
        val budget = BluetoothConnectBudget()
        assertTrue(budget.acquire("甲"))
        assertFalse(budget.acquire("甲"))
        assertTrue(budget.acquire("乙"))
        assertFalse(budget.acquire("丙"))
        budget.release("甲")
        assertTrue(budget.acquire("丙"))
    }
}
