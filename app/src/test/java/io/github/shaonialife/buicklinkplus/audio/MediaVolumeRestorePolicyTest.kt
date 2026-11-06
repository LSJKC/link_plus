package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVolumeRestorePolicyTest {
    /** 原静音且系统公开零音量时，不再误判恢复失败 */
    @Test fun mutedStreamDoesNotCompareHiddenVolume() {
        assertTrue(MediaVolumeRestorePolicy.matches(true, 7, 0, true))
        assertFalse(MediaVolumeRestorePolicy.matches(true, 7, 7, false))
    }

    /** 原零音量及非零音量都必须同时验证静音位，路由延迟不得提前成功 */
    @Test fun unmutedVolumeRequiresExactReadback() {
        assertTrue(MediaVolumeRestorePolicy.matches(false, 0, 0, false))
        assertTrue(MediaVolumeRestorePolicy.matches(false, 7, 7, false))
        assertFalse(MediaVolumeRestorePolicy.matches(false, 7, 0, false))
        assertFalse(MediaVolumeRestorePolicy.matches(false, 7, 7, true))
    }
}
