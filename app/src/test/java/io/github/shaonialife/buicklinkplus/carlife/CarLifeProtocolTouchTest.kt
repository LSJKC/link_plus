// Adapted from CarPToCarL carlife/CarLifeProtocolTouchTest.kt (MIT, Copyright (c) 2026 lizhihua), commit 7b729fb.
package io.github.shaonialife.buicklinkplus.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 触控负载编码：单点消息与复合动作是**两套负载**，混用会让引擎收到错位字段。
 *
 * - 单点（0x68002/3/4）→ `CarlifeTouchSinglePoint{x=1, y=2}`
 * - 复合（0x68001）    → `CarlifeTouchAction{action=1, x=2, y=3}`
 */
class CarLifeProtocolTouchTest {
    @Test
    fun `单点负载只有 x 和 y 两个字段`() {
        // x=100 → tag 0x08 / 0x64；y=200 → tag 0x10 / varint(200)=0xC8 0x01
        assertArrayEquals(
            byteArrayOf(0x08, 0x64, 0x10, 0xC8.toByte(), 0x01),
            CarLifeProtocol.touchPoint(100, 200),
        )
    }

    @Test
    fun `复合负载带上动作码`() {
        // action=0 → tag 0x08 / 0x00；x=100 → 0x10 0x64；y=200 → 0x18 0xC8 0x01
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x10, 0x64, 0x18, 0xC8.toByte(), 0x01),
            CarLifeProtocol.touchAction(0, 100, 200),
        )
    }

    @Test
    fun `双指动作码等于 Android 的指针索引约定`() {
        val act = CarLifeProtocol.TouchAction
        // 第二根手指按下/抬起：ACTION_POINTER_DOWN|(1<<8)=261、ACTION_POINTER_UP|(1<<8)=262
        assertEquals(261, act.withPointer(act.POINTER_DOWN, 1))
        assertEquals(262, act.withPointer(act.POINTER_UP, 1))
        // 单指按下/抬起不变
        assertEquals(0, act.withPointer(act.DOWN, 0))
        assertEquals(1, act.withPointer(act.UP, 0))
        // MOVE 保持 2（不带索引——Android 的 MOVE 本身就没有指针索引位）
        assertEquals(2, act.MOVE)
    }

    @Test
    fun `261 走多字节 varint`() {
        // 261 = 0x105 → varint 0x85 0x02
        val payload = CarLifeProtocol.touchAction(261, 10, 20)
        assertArrayEquals(
            byteArrayOf(0x08, 0x85.toByte(), 0x02, 0x10, 0x0A, 0x18, 0x14),
            payload,
        )
    }
}
