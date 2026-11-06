package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查官方包、签名历史和原生屏选择 */
class OfficialCarAppsTest {
    @Test fun phonePackagesKeepOriginalIdentityAndLabels() {
        listOf("com.tencent.qqmusic", "com.netease.cloudmusic", "com.kugou.android").forEach {
            assertNull(OfficialCarApps.find(it))
            assertEquals("原应用", OfficialCarApps.displayLabel(it, "原应用"))
        }
    }

    @Test fun carPackagesHaveDistinctLabelsWithoutGuessingActivityNames() {
        assertEquals(
            5,
            OfficialCarApps.entries
                .map { it.packageName }
                .toSet()
                .size,
        )
        OfficialCarApps.entries.forEach {
            assertEquals(it.title, OfficialCarApps.displayLabel(it.packageName, "同名音乐"))
            assertTrue(it.title.contains(if (it.independentCar) "车载版" else "手机版"))
            assertTrue(it.website.startsWith("https://"))
        }
    }

    @Test fun unknownAndEmptySignaturesCannotBeClaimedAsOfficial() {
        OfficialCarApps.entries.forEach {
            assertFalse(it.matchesCertificates(emptySet()))
            assertFalse(it.matchesCertificates(setOf("未知证书")))
            assertTrue(it.matchesCertificates(setOf(it.certificateSha256.lowercase())))
        }
    }

    @Test fun verifiedRotationHistoryStillIdentifiesOriginalPublisher() {
        val app = OfficialCarApps.entries.first()
        assertTrue(app.matchesCertificates(setOf("轮换后的证书", app.certificateSha256)))
    }

    @Test fun portraitSplashDoesNotForceCarAppIntoSupersampling() {
        assertFalse(SupersamplePrefs.resolveMode(SupersamplePrefs.MODE_AUTO, true) { error("车载版无需查询启动页方向") })
        assertTrue(SupersamplePrefs.resolveMode(SupersamplePrefs.MODE_AUTO, false) { true })
        assertFalse(SupersamplePrefs.resolveMode(SupersamplePrefs.MODE_AUTO, false) { false })
    }

    @Test fun explicitScreenPreferencesOverrideAutomaticCarAppRule() {
        assertTrue(SupersamplePrefs.resolveMode(SupersamplePrefs.MODE_ON, true) { false })
        assertFalse(SupersamplePrefs.resolveMode(SupersamplePrefs.MODE_OFF, false) { true })
    }
}
