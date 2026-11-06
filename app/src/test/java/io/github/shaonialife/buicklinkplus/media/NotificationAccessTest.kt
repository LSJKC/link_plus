package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查通知授权列表精确匹配组件，避免相同包名前缀误判 */
class NotificationAccessTest {
    private val spec = "io.github.shaonialife.buicklinkplus/io.github.shaonialife.buicklinkplus.media.MediaListenerService"

    @Test
    fun detectsOwnComponentAmongOthers() {
        val enabled =
            "com.google.android.projection.gearhead/GearheadListenerService:" +
                "$spec:com.hihonor.health/HnNotificationListenerService"
        assertTrue(NotificationAccess.isGrantedIn(enabled, spec))
    }

    @Test
    fun detectsWhenOnlyEntry() {
        assertTrue(NotificationAccess.isGrantedIn(spec, spec))
    }

    @Test
    fun rejectsWhenAbsent() {
        assertFalse(NotificationAccess.isGrantedIn("com.other.app/OtherService", spec))
        assertFalse(NotificationAccess.isGrantedIn("", spec))
        assertFalse(NotificationAccess.isGrantedIn(null, spec))
    }

    @Test
    fun doesNotMatchByPrefixOnly() {
        // 旧包名是前缀相同的另一条授权：不能因为字符串包含就判为已授权
        // （改包名后最容易踩的坑——旧条目还在列表里，新包其实没授权）
        val staleOldPackage =
            "io.github.shaonialife.buicklinkplus/io.github.shaonialife.buicklinkplus.media.OtherService"
        assertFalse(NotificationAccess.isGrantedIn(staleOldPackage, spec))
    }

    /** 检查已授权但未出现在 Live listeners 中的未绑定状态 */
    @Test
    fun liveSectionDecidesWhetherMetadataIsReadable() {
        val dump =
            """
            Notification listeners:
              Allowed notification listeners:
                $spec (user: 0 isPrimary: true)
              All notification listeners (2) enabled for current profiles:
                ComponentInfo{$spec}
              Live notification listeners (1):
                ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              Snoozed notification listeners (0):
            """.trimIndent()
        // 授权列表里有、Live 段没有 → 仍是未绑定（必须触发重绑）
        assertFalse(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun liveSectionIsDetectedOnceBound() {
        val dump =
            """
            Live notification listeners (2):
              ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              ComponentInfo{$spec} (user 0): android.service.notification.INotificationListener${'$'}Stub${'$'}Proxy@abc
            Snoozed notification listeners (0):
            """.trimIndent()
        assertTrue(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun missingLiveSectionIsNotTreatedAsBound() {
        assertFalse(NotificationAccess.isLiveIn("no such section", spec))
    }
}
