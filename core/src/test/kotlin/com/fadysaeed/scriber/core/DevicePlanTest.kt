package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePlanTest {
    private val catalog = ModelCatalog.bundled()

    @Test fun defaultSelectionIsEveryRequiredPackAndNoLanguageModel() {
        val sel = DevicePlan.defaultSelection(catalog)
        assertEquals(catalog.packs.filter { it.required }, sel)
        assertTrue(catalog.packs.none { it.id.startsWith("llm") })
    }

    @Test fun bytesNeededSubtractsWhatIsAlreadyThere() {
        val sel = DevicePlan.defaultSelection(catalog)
        val all = sel.sumOf { it.totalBytes }
        assertEquals(all + DevicePlan.SAFETY_MARGIN_BYTES, DevicePlan.bytesNeeded(sel) { 0 })
        assertEquals(DevicePlan.SAFETY_MARGIN_BYTES, DevicePlan.bytesNeeded(sel) { it.size })
    }
}
