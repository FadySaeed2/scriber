package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Build-time checks that the privacy design is still true in the code:
 * only the fetch module may contain network code or ask for the internet permission, and the
 * network security config trusts nothing except the download hosts.
 */
class PrivacyGuardTest {
    private val root = File(System.getProperty("user.dir")).parentFile

    private val networkCode = Regex(
        """java\.net\.(URL|HttpURLConnection|URLConnection|Socket|ServerSocket|DatagramSocket|InetAddress)\b|""" +
            """javax\.net\.|HttpsURLConnection|okhttp|io\.ktor|android\.webkit\.WebView|android\.net\.http"""
    )

    @Test fun onlyTheFetchModuleContainsNetworkCode() {
        val offenders = listOf("core", "vault", "app").flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { f -> f.readLines().any { networkCode.containsMatchIn(it) } }
                .map { it.relativeTo(root).path }
                .toList()
        }
        assertEquals("Network code outside the fetch module", emptyList<String>(), offenders)
    }

    @Test fun onlyTheFetchModuleAsksForInternet() {
        val manifests = root.walkTopDown().filter { it.name == "AndroidManifest.xml" && "/src/main/" in it.path }.toList()
        assertTrue(manifests.isNotEmpty())
        val asking = manifests.filter { "android.permission.INTERNET" in it.readText() }.map { it.relativeTo(root).path }
        assertEquals(listOf("fetch/src/main/AndroidManifest.xml"), asking)
    }

    @Test fun networkSecurityConfigTrustsOnlyTheDownloadHosts() {
        val xml = File(root, "app/src/main/res/xml/network_security_config.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val base = doc.getElementsByTagName("base-config").item(0) as org.w3c.dom.Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val baseAnchors = base.getElementsByTagName("trust-anchors").item(0) as org.w3c.dom.Element
        assertEquals("The default must trust no certificate at all", 0, baseAnchors.getElementsByTagName("certificates").length)

        val domains = doc.getElementsByTagName("domain")
        val listed = (0 until domains.length).map {
            val d = domains.item(it) as org.w3c.dom.Element
            assertEquals("true", d.getAttribute("includeSubdomains"))
            d.textContent.trim()
        }
        assertEquals(AllowedHosts.domains.sorted(), listed.sorted())
    }

    @Test fun appManifestKeepsDataOffBackups() {
        val m = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:allowBackup=\"false\""))
        assertTrue(m.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
