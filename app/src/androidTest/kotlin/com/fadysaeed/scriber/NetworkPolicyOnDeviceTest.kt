package com.fadysaeed.scriber

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Proves on a real Android system that the network security config does what it claims:
 * any host outside the model hosts fails at the TLS handshake, plain http fails everywhere,
 * and the model hosts still work.
 */
@RunWith(AndroidJUnit4::class)
class NetworkPolicyOnDeviceTest {
    private fun open(url: String): Int {
        val c = URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 20_000
        c.instanceFollowRedirects = false
        try { return c.responseCode } finally { c.disconnect() }
    }

    @Test fun otherHostsAreRefused() {
        for (host in listOf("https://example.com/", "https://www.google.com/", "https://api.anthropic.com/")) {
            try {
                open(host)
                fail("$host should have been refused")
            } catch (e: IOException) {
                val chain = generateSequence<Throwable>(e) { it.cause }.toList()
                assertTrue("$host failed for the wrong reason: $e", chain.any { it is SSLException || "Trust anchor" in (it.message ?: "") })
            }
        }
    }

    @Test fun plainHttpIsRefusedEvenForModelHosts() {
        try {
            open("http://huggingface.co/")
            fail("Cleartext should be refused")
        } catch (e: IOException) {
            assertTrue("$e", "leartext" in (e.message ?: "") || e is java.net.UnknownServiceException)
        }
    }

    @Test fun modelHostsWork() {
        val code = open("https://huggingface.co/api/models/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0")
        assertTrue("got $code", code in 200..399)
        // The project page, not a release file: k2-fsa's release downloads can time out for hours.
        val c = URL("https://github.com/k2-fsa/sherpa-onnx").openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false
        assertTrue("got ${c.responseCode}", c.responseCode in 200..399)
        c.disconnect()
    }
}
