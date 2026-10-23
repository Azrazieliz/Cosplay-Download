package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class SearchRelevanceTest {
    private fun decide(query:String,title:String,url:String,snippet:String="",
                       video:Boolean=false,adult:Boolean=false) =
        SearchRelevance.evaluate(query,SearchRelevance.Evidence(title,snippet,url,video,adult))

    @Test fun exactTwoKeywordAdultVideoIncluded(){
        assertTrue(decide("silver garden","Silver Garden HD full scene",
            "https://www.xvideos.com/video1234567/a-video").accept)
    }
    @Test fun unrelatedAdultVideoExcluded(){
        assertFalse(decide("silver garden","A different video",
            "https://www.xvideos.com/video1234567/a-video").accept)
    }
    @Test fun partialKeywordExcluded(){
        assertFalse(decide("silver garden","Silver Moon",
            "https://www.xvideos.com/video1234567/a-video").accept)
    }
    @Test fun queryTermDoesNotMatchInsideUnrelatedWord(){
        assertFalse(decide("cat","Catalog showcase",
            "https://www.xvideos.com/video1234567/some-video").accept)
    }
    @Test fun nonVideoAdultHomepageExcluded(){
        assertFalse(decide("silver garden","Silver Garden",
            "https://www.pornhub.com/").accept)
    }
    @Test fun regularYouTubeContentExcludedDespiteMatchingTitle(){
        assertFalse(decide("silver garden","Silver Garden",
            "https://www.youtube.com/watch?v=abc12345").accept)
    }
    @Test fun unknownHostRequiresVideoAndAdultPageEvidence(){
        val link="https://unusual-media.example/watch/xyz123"
        assertFalse(decide("silver garden","Silver Garden",link,video=true).accept)
        assertFalse(decide("silver garden","Silver Garden",link,adult=true).accept)
        assertTrue(decide("silver garden","Silver Garden",link,video=true,adult=true).accept)
    }
    @Test fun nonVideoUnknownHostExcludedEvenWhenExplicit(){
        assertFalse(decide("silver garden","Silver Garden adult",
            "https://unusual-media.example/about",video=false,adult=true).accept)
    }
    @Test fun footFeetSynonymRecognized(){
        assertTrue(decide("foot model","Feet model",
            "https://www.xvideos.com/video12345/test").accept)
    }
    @Test fun obscureDomainsStillEligibleWithProof(){
        assertTrue(decide("silver garden","Silver Garden",
            "https://unusual.example/videos/123",video=true,adult=true).accept)
    }
    @Test fun broadPornOnlyQueryRejected(){
        assertFalse(decide("porn videos","generic porn",
            "https://www.xvideos.com/video1234567/example").accept)
    }
}