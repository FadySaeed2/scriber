package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeAndHostsTest {
    @Test fun resumePlan() {
        assertEquals(ResumeAction.StartFresh, Resume.plan(0, 100))
        assertEquals(ResumeAction.ResumeFrom(40), Resume.plan(40, 100))
        assertEquals(ResumeAction.AlreadyComplete, Resume.plan(100, 100))
        assertEquals(ResumeAction.StartFresh, Resume.plan(140, 100))
    }

    @Test fun contentRange() {
        assertEquals(100L, Resume.contentRangeStart("bytes 100-999/1000"))
        assertEquals(0L, Resume.contentRangeStart("bytes 0-9/*"))
        assertNull(Resume.contentRangeStart("bytes */1000"))
        assertNull(Resume.contentRangeStart(null))
    }

    @Test fun hosts() {
        assertTrue(AllowedHosts.isAllowed("huggingface.co"))
        assertTrue(AllowedHosts.isAllowed("us.aws.cdn.hf.co"))
        assertTrue(AllowedHosts.isAllowed("release-assets.githubusercontent.com"))
        assertTrue(AllowedHosts.isAllowed("GitHub.com."))
        assertFalse(AllowedHosts.isAllowed("evilhuggingface.co"))
        assertFalse(AllowedHosts.isAllowed("huggingface.co.evil.com"))
        assertFalse(AllowedHosts.isAllowed("example.com"))
        assertFalse(AllowedHosts.isAllowed(null))
    }

    @Test fun sha256OfKnownText() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc".toByteArray()))
    }
}
