package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class CatalogTest {
    private val catalog = ModelCatalog.bundled()

    @Test fun everyFileIsHttpsOnAnAllowedHost() {
        for (p in catalog.packs) for (f in p.files) for (u in f.urls) {
            val uri = URI(u)
            assertEquals("${p.id}/${f.path} must use https", "https", uri.scheme)
            assertTrue("${p.id}/${f.path} host ${uri.host} is not allowed", AllowedHosts.isAllowed(uri.host))
        }
    }

    @Test fun everyFileHasASizeAndAFullSha256() {
        for (p in catalog.packs) for (f in p.files) {
            assertTrue("${p.id}/${f.path} size", f.size > 0)
            assertTrue("${p.id}/${f.path} sha256 '${f.sha256}'", Regex("^[0-9a-f]{64}$").matches(f.sha256))
        }
    }

    @Test fun huggingFaceFilesArePinnedToACommit() {
        for (p in catalog.packs) for (f in p.files) for (u in f.urls) if (URI(u).host == "huggingface.co") {
            assertTrue("${p.id}/${f.path} is not pinned: $u", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(u))
        }
    }

    @Test fun idsAndPathsAreUniqueAndSafe() {
        assertEquals(catalog.packs.size, catalog.packs.map { it.id }.toSet().size)
        for (p in catalog.packs) {
            assertTrue(Regex("^[a-z0-9-]+$").matches(p.id))
            assertEquals(p.files.size, p.files.map { it.path }.toSet().size)
            for (f in p.files) assertTrue("${f.path} must stay inside its folder", !f.path.contains("..") && !f.path.startsWith("/"))
        }
    }

    @Test fun shapeOfTheDefaultSet() {
        assertEquals(2, catalog.byRole(PackRole.ASR).size)
        assertTrue(catalog.byRole(PackRole.ASR).all { it.engine in setOf("parakeet", "medasr") })
        assertEquals(1, catalog.byRole(PackRole.VAD).size)
        assertEquals(1, catalog.byRole(PackRole.SEGMENTATION).size)
        assertEquals(1, catalog.byRole(PackRole.SPEAKER_EMBEDDING).size)
        assertTrue(catalog.byRole(PackRole.LLM).none { it.required })
        val bench = catalog.byRole(PackRole.BENCHMARK_AUDIO).single()
        assertTrue(bench.files.any { it.path == "transcript.txt" })
    }
}
