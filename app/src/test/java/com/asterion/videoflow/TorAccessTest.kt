package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class TorAccessTest {
    private val ahmia="http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/"
    @Test fun acceptsRealTorV3Address() {
        assertTrue(TorAccess.isOnion(ahmia))
        assertTrue(TorAccess.isV3Onion(ahmia))
    }
    @Test fun rejectsLegacyOrFakeOnion() {
        assertFalse(TorAccess.isV3Onion("http://notactuallyonion.onion/watch/123"))
        assertFalse(TorAccess.isV3Onion("https://example.com/videos/a"))
    }
    @Test fun recognisesOnionWithoutConfusingSubstrings() {
        assertFalse(TorAccess.isOnion("https://example.com/watch?u=abc.onion"))
        assertTrue(TorAccess.isOnion(ahmia))
    }
    @Test fun onionProxyDefaultsToDisabled() {
        val options=TorOptions()
        assertFalse(options.enabled)
        assertEquals(9050,options.socksPort)
        assertEquals("socks5h://127.0.0.1:9050",options.proxyUrl)
    }
    @Test fun rejectsInvalidProxyPorts() {
        assertFalse(TorOptions(true,0).validPort())
        assertFalse(TorOptions(true,65536).validPort())
        assertTrue(TorOptions(true,9050).validPort())
    }
}