package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** shell 日志只导出所属启动段的故障字段 */
class ShellInputLogExcerptTest {
    private val packageName = "io.github.shaonialife.buicklinkplus"
    private val start = "10:00:00.000 service start: pkg=$packageName lastUpdate=1234 pid=2001"

    /** 返回拒绝与异常必须分别保留，异常只输出类型及明确权限标记 */
    @Test
    fun separatesRejectionFromSanitizedException() {
        val text =
            "$start\n10:00:01.000 输入注入被拒 display=44 action=0 method=不输出原文\n" +
                "10:00:02.000 输入注入异常 display=44 action=1 method=不输出原文 " +
                "原因=java.lang.SecurityException: INJECT_EVENTS password=保密内容，联系人及网络地址\n" +
                "10:00:03.000 音频日志中含其他敏感内容"
        val safe = ShellInputLogExcerpt.extract(text, packageName)
        assertTrue(safe.contains("输入注入被拒 display=44 action=0 返回=false"))
        assertTrue(safe.contains("输入注入异常 display=44 action=1 原因类型=java.lang.SecurityException 权限标记=INJECT_EVENTS"))
        assertFalse(safe.contains("不输出原文"))
        assertFalse(safe.contains("保密内容"))
        assertFalse(safe.contains("联系人"))
        assertFalse(safe.contains("其他敏感内容"))
        assertTrue(safe.contains("可能跨日期或投屏会话"))
    }

    /** 缺启动标记时不能凭显示屏编号认领历史错误 */
    @Test
    fun incompleteTailIsNotAttributed() {
        val safe = ShellInputLogExcerpt.extract("10:00:01.000 输入注入被拒 display=44 action=0", packageName)
        assertTrue(safe.contains("无法确认归属"))
        assertFalse(safe.contains("display=44"))
    }

    /** 兼容旧 DateFormat 生成的字面 SSS 时间标记 */
    @Test
    fun androidDateFormatLiteralMillisecondsAreAccepted() {
        val safe =
            ShellInputLogExcerpt.extract(
                start.replace(".000", ".SSS") + "\n10:00:01.SSS 输入注入被拒 display=44 action=0",
                packageName,
            )
        assertTrue(safe.contains("时间=10:00:01.SSS 输入注入被拒 display=44"))
        assertTrue(safe.contains("不能解释成毫秒"))
    }

    /** 后启动的其他安装包阻断旧本包片段，不能越过标记拼接 */
    @Test
    fun otherPackageCannotBeExported() {
        val safe =
            ShellInputLogExcerpt.extract(
                "$start\n10:00:01.000 输入注入被拒 display=44 action=0\n" +
                    "10:01:00.000 service start: pkg=other.package lastUpdate=1234 pid=2002\n" +
                    "10:01:01.000 输入注入被拒 display=55 action=0",
                packageName,
            )
        assertTrue(safe.contains("其他安装包"))
        assertFalse(safe.contains("display="))
    }

    /** 同包重启后仅保留末次启动块，不混入上一代失败 */
    @Test
    fun restartedServiceDropsOlderFailures() {
        val safe =
            ShellInputLogExcerpt.extract(
                "$start\n10:00:01.000 输入注入被拒 display=44 action=0\n" +
                    "10:01:00.000 service start: pkg=$packageName lastUpdate=1234 pid=2002\n" +
                    "10:01:01.000 输入注入被拒 display=55 action=0",
                packageName,
            )
        assertFalse(safe.contains("display=44"))
        assertTrue(safe.contains("display=55"))
        assertTrue(safe.contains("pid=2002"))
    }

    /** 无错误记录不代表输入检查通过 */
    @Test
    fun emptyFailureListDoesNotClaimSuccess() {
        assertTrue(ShellInputLogExcerpt.extract(start, packageName).contains("不代表触控已通过"))
    }

    /** 超限内容即使带有旧启动标记，也不能绕过尾部长度限制 */
    @Test
    fun oversizedTextCannotRetainOldOwner() {
        val safe = ShellInputLogExcerpt.extract("$start\n" + "x".repeat(ShellInputLogExcerpt.MAX_BYTES), packageName)
        assertTrue(safe.contains("无法确认归属"))
    }
}
