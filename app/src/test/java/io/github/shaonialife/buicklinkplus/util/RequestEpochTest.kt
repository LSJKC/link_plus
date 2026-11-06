package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面重建、网络取消重连与迟到回调共用的所有权回归
 */
class RequestEpochTest {
    @Test fun oldViewCallbackCannotPublishIntoNewRequest() {
        val epoch = RequestEpoch()
        val old = epoch.advance()
        val fresh = epoch.advance()
        assertFalse(epoch.owns(old))
        assertTrue(epoch.owns(fresh))
    }

    @Test fun oldNetworkTimeoutCannotReleaseReconnectedNetwork() {
        val epoch = RequestEpoch()
        val previous = epoch.advance()
        epoch.advance()
        val reconnect = epoch.advance()
        var bound = true
        if (epoch.owns(previous)) bound = false
        assertTrue(bound)
        assertTrue(epoch.owns(reconnect))
    }

    @Test fun cancellationInvalidatesAvailableAndLostCallbacks() {
        val epoch = RequestEpoch()
        val ticket = epoch.advance()
        epoch.advance()
        assertFalse(epoch.owns(ticket))
    }
}
