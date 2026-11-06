package io.github.shaonialife.buicklinkplus.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 检查 am compat 失败分类，临时故障保留偏好，永久拒绝不保存 */
class CompatFailureClassifyTest {
    /** 第三方应用在 user build 被拒的输出样本 */
    @Test
    fun nonDebuggableOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a change on a non-debuggable app and user build."),
        )
    }

    /** 目标是该 ChangeId 的默认对象时的另一种拒绝措辞。 */
    @Test
    fun defaultChangeOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a default enabled/disabled change on a user build."),
        )
    }

    /** 检查 ROM 未注册 ChangeId 时的警告和拒绝输出 */
    @Test
    fun unregisteredChangeIdIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure(
                "Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.\n" +
                    "Cannot override a change on a non-debuggable app and user build.",
            ),
        )
        // 仅警告行（某些 ROM 只回警告）同样判永久
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Warning! Change 265464455 is not known yet."),
        )
    }

    /** 服务未连接时视为临时故障 */
    @Test
    fun shizukuNotConnectedIsTemporary() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Shizuku 未连接"),
        )
    }

    /** 超时/进程被杀：临时故障。 */
    @Test
    fun emptyOrTimeoutIsTemporary() {
        assertEquals(AppOrientationCompat.WriteResult.FAILED, classifyCompatFailure(""))
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("命令执行失败：timeout"),
        )
    }

    /** 成功输出不分类为永久拒绝 */
    @Test
    fun successTextIsNotPermanent() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Enabled change 265451093 for com.example.app."),
        )
    }

    /** 检查强制横屏最低版本为 Android 14，低版本直接判为不支持 */
    @Test
    fun minSupportedSdkIsAndroid14() {
        assertEquals(34, AppOrientationCompat.MIN_SUPPORTED_SDK)
    }
}
