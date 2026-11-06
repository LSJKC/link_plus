package io.github.shaonialife.buicklinkplus.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 根据系统输出区分实际能力与检查失败，防止 Android 17 被错误禁用 */
class OrientationCapabilityTest {
    private val supportedDump =
        """
        ChangeId(265451093; name=OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR; disabled; overridable)
        ChangeId(265464455; name=OVERRIDE_ANY_ORIENTATION; disabled; overridable)
        """.trimIndent()

    /** 授权错误是未知状态，不能因为错误文本非空就判断为不支持 */
    @Test
    fun permissionFailuresRemainUnknown() {
        listOf("Shizuku 未连接", "命令执行失败：Permission denied", "Permission Denial: can't dump").forEach {
            assertEquals(OrientationCapability.UNKNOWN, classifyOrientationCapability(37, false, it))
        }
    }

    /** 不认识的成功输出和空输出也不能当作有效能力检查 */
    @Test
    fun emptyOrUnexpectedOutputRemainsUnknown() {
        listOf("", "Permission denied", "未知平台输出").forEach {
            assertEquals(OrientationCapability.UNKNOWN, classifyOrientationCapability(37, true, it))
        }
    }

    /** 两个标准项都注册时，Android 17 正常判为支持 */
    @Test
    fun android17WithBothChangesIsSupported() {
        assertEquals(OrientationCapability.SUPPORTED, classifyOrientationCapability(37, true, supportedDump))
    }

    /** 权限恢复后的正常输出必须能通过 */
    @Test
    fun successfulRetryCanFollowPermissionFailure() {
        assertEquals(OrientationCapability.UNKNOWN, classifyOrientationCapability(37, false, "Shizuku 未连接"))
        assertEquals(OrientationCapability.SUPPORTED, classifyOrientationCapability(37, true, supportedDump))
    }

    /** 只注册一个目标项时，不借用无关条目的名称判为全部支持 */
    @Test
    fun partialRegistrationIsUnsupported() {
        val dump = "ChangeId(265451093; name=OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR)\nChangeId(123; name=OTHER_CHANGE)"
        assertEquals(OrientationCapability.UNSUPPORTED, classifyOrientationCapability(37, true, dump))
    }

    /** 未注册项只有原始覆盖数据，不能误认成平台定义 */
    @Test
    fun rawOverridesDoNotProveRegistration() {
        val dump =
            "ChangeId(265451093; rawOverrides={com.example.app=true})\n" +
                "ChangeId(265464455; rawOverrides={})\nChangeId(123; name=OTHER_CHANGE)"
        assertEquals(OrientationCapability.UNSUPPORTED, classifyOrientationCapability(37, true, dump))
    }

    /** Android 13 缺少这组平台兼容项，与权限检查失败是不同原因 */
    @Test
    fun android13UsesVersionLimit() {
        assertEquals(OrientationCapability.UNSUPPORTED, classifyOrientationCapability(33, true, supportedDump))
    }

    /** 保留退出状态，命令失败时即使混有旧条目也不能当作成功 */
    @Test
    fun failedCommandCannotUsePartialOutput() {
        assertEquals(OrientationCapability.UNKNOWN, classifyOrientationCapability(37, false, supportedDump))
    }
}
