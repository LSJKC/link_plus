package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查 ctrl 身份保持 sender 和 950.7.1，避免车机跳过探测
 * 不注册端口为 0 的 _device-info 服务
 */
class CarPlayCtrlAdvertiserTest {
    @Test
    fun ctrlTxtKeepsValuesVerifiedOnAudi() {
        val srcvers = CarPlayCtrlAdvertiser.advertisedSrcversForTest()
        val name = CarPlayCtrlAdvertiser.advertisedServiceNameForTest()
        assertEquals("车机按该版本号分支：改掉会连 ctrl 探测都不发", "950.7.1", srcvers)
        assertEquals("0.1.6 起实测可用的实例名", "sender", name)
        assertTrue("服务类型不得改动", CarPlayCtrlAdvertiser.SERVICE_TYPE == "_carplay-ctrl._tcp")
    }

    @Test
    fun deviceInfoIsNotAdvertised() {
        // _device-info._tcp 曾以 port=0 注册并必然失败（The port is missing）。
        // 若将来要恢复，必须先确认 NsdManager 接受合法端口且该车机确有此需求。
        assertTrue(
            "不应再广播 _device-info._tcp",
            !CarPlayCtrlAdvertiser.advertisedServiceTypesForTest().contains("_device-info._tcp"),
        )
    }
}
