package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReusePolicyTest {
    @Test fun acceptsLiveHotspotWithoutInternetOrVisibleSsid() {
        assertTrue(NetworkReusePolicy.usable("车机", "车机", null, true, true, true))
    }

    @Test fun rejectsStaleChangedAndUnlinkedNetworks() {
        assertFalse(NetworkReusePolicy.usable("车机", "车机", null, false, true, true))
        assertFalse(NetworkReusePolicy.usable("车机", "车机", "其他", true, true, true))
        assertFalse(NetworkReusePolicy.usable("新车机", "旧车机", null, true, true, true))
        assertFalse(NetworkReusePolicy.usable("车机", "车机", null, true, true, false))
        assertFalse(NetworkReusePolicy.usable("车机", "车机", null, true, false, true))
    }
}
