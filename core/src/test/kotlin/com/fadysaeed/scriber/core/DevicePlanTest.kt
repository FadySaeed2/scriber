package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlanTest {
    private val catalog = ModelCatalog.bundled()
    private val gb = 1024L * 1024 * 1024

    @Test fun twelveGigabytePhoneGetsE4B() {
        // A 12 GB S23 Ultra reports about 11.1 GB.
        assertEquals("E4B", DevicePlan.languageModelFor(catalog, (11.1 * gb).toLong()).variant)
    }

    @Test fun eightGigabytePhoneGetsE2B() {
        assertEquals("E2B", DevicePlan.languageModelFor(catalog, (7.3 * gb).toLong()).variant)
    }

    @Test fun defaultSelectionHasEveryRequiredPackAndOneLanguageModel() {
        val sel = DevicePlan.defaultSelection(catalog, 11 * gb)
        assertTrue(catalog.packs.filter { it.required }.all { it in sel })
        assertEquals(1, sel.count { it.role == PackRole.LLM })
    }

    @Test fun bytesNeededSubtractsWhatIsAlreadyThere() {
        val sel = DevicePlan.defaultSelection(catalog, 11 * gb)
        val all = sel.sumOf { it.totalBytes }
        assertEquals(all + DevicePlan.SAFETY_MARGIN_BYTES, DevicePlan.bytesNeeded(sel) { 0 })
        assertEquals(DevicePlan.SAFETY_MARGIN_BYTES, DevicePlan.bytesNeeded(sel) { it.size })
    }
}
