package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Direct URL only. Do not regress to hidden-service or keyword search support. */
class WebDiscoveryTest {
    @Test fun validWebVideoUrl() {
        assertTrue(WebDiscovery.isUrl("https://www.example.com/videos/123456"))
        assertTrue(WebDiscovery.isUrl("https://media.example.com/sample.mp4?token=abc"))
    }
    @Test fun exactUrlAndSignedQueryArePreserved(){
        val url="https://www.example.com/watch/Video%20Name?token=a%2Bb&signature=987"
        assertEquals(url,WebDiscovery.normalize(url))
    }
    @Test fun keywordAndTorLinksNotSupported(){
        assertFalse(WebDiscovery.isUrl("search:performer example"))
        assertFalse(WebDiscovery.isUrl("http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion"))
        assertNull(WebDiscovery.normalize("performer example videos"))
    }
    @Test fun rejectLocalNetworkAddresses(){
        listOf("http://localhost/a","http://127.0.0.1/a","http://192.168.0.2/a",
            "http://10.1.4.5/a","http://172.20.0.2/a",
            "http://169.254.169.254/latest/meta-data/").forEach{
            assertFalse("Expected a blocked local address: "+it,WebDiscovery.isUrl(it))
        }
    }
    @Test fun identifyTypicalVideoUrls(){
        assertTrue(WebDiscovery.isVideoPage("https://example.com/videos/abc123"))
        assertTrue(WebDiscovery.isVideoPage("https://www.pornhub.com/view_video.php?viewkey=1234"))
        assertTrue(WebDiscovery.isVideoPage("https://example.com/watch?v=123"))
        assertTrue(WebDiscovery.isVideoPage("https://videos.example.com/video.mp4"))
        assertFalse(WebDiscovery.isVideoPage("https://www.example.com/"))
    }
}
