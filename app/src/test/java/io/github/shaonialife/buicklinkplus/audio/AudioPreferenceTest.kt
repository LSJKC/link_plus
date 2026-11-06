package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户策略优先于记忆，媒体不回退到语音通道 */
class AudioPreferenceTest {
    private val pcm = AudioProfile("media", 1L shl 15, 48000, 250)
    private val aac = AudioProfile("media", 1L shl 23, 48000, 250, 102, AudioCodec.AAC_LC)
    private val compat = AudioProfile("compatibility", 1L shl 15, 48000, 250)

    @Test fun qualityOverridesRememberedAacAndKeepsAacFallback() {
        assertEquals(listOf(pcm, aac), AudioPreference.order(listOf(aac, compat, pcm), listOf(aac, pcm, compat), AudioPreference.QUALITY))
    }

    @Test fun autoKeepsMemoryOrder() {
        val remembered = listOf(compat, aac, pcm)
        assertEquals(remembered, AudioPreference.order(listOf(aac, pcm, compat), remembered, AudioPreference.AUTO))
    }

    @Test fun compatibilityRemainsExplicitChoice() {
        assertEquals(
            listOf(compat, pcm),
            AudioPreference.order(listOf(aac, pcm, compat), listOf(aac, pcm, compat), AudioPreference.COMPATIBILITY),
        )
    }

    @Test fun speechAndCallChannelsAreNeverMediaCandidates() {
        listOf("telephony", "speechRecognition", "alert", null).forEach { assertFalse(AudioPreference.isMediaChannel(it)) }
        listOf("media", "compatibility", "default").forEach { assertTrue(AudioPreference.isMediaChannel(it)) }
    }
}
