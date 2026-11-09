package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalTermsTest {
    private val terms = MedicalTerms.bundled()
    private val meds = Medicines.bundled()

    @Test fun theListKnowsMedicalWordsAndLeavesEverydayOnesAlone() {
        assertTrue(terms.size > 50_000)
        for (w in listOf("tachycardia", "bradycardia", "schizophrenia", "akathisia", "catatonia", "haemorrhage", "hemorrhage", "oedema", "stemi", "ecg", "suicidal", "self-harm"))
            assertTrue(w, terms.isTerm(w))
        for (w in listOf("heart", "pressure", "nurse", "shops", "yesterday", "partner", "the"))
            assertFalse(w, terms.isTerm(w))
        assertEquals("heart failure", terms.termIn(listOf("the", "heart", "failure", "clinic"), 1..1))
        assertNull(terms.termIn(listOf("the", "heart", "is", "fine"), 1..1))
    }

    private fun run(id: String, engine: String, vararg text: String) = TranscriptRun(id, "s", engine, engine, "", emptyMap(), "", 0, status = RunStatus.DONE,
        segments = text.mapIndexed { i, t -> Segment(i * 5000L, i * 5000L + 4000, t) })

    private fun diffs(base: String, alt: String) =
        Compare.reading(run("b", "parakeet", base), run("m", "medasr", alt), SessionMode.CONVERSATION, meds, terms).differences

    @Test fun differencesInMedicalLanguageAreMarkedAndEverydayOnesAreNot() {
        val brady = diffs("My grandfather had a Brady Tardia with a heart block.", "my grandfather had a bradycardia with a heart block").single()
        assertEquals("bradycardia", brady.term); assertTrue(brady.medical); assertNull(brady.medicine)
        assertTrue(diffs("My other granddad died of an LAD stimmy.", "my other granddad died of an lad stemi").single().medical)
        assertNotNull(diffs("No techie card, no palpitations.", "no tachycardia no palpitations").firstOrNull { it.medical })
        // "hard failure" for "heart failure": only one word differs, the phrase is still found.
        assertNotNull(diffs("He has hard failure.", "he has heart failure").single().term)
        val shop = diffs("I went to the shops yesterday.", "i went to the shop yesterday").single()
        assertNull(shop.term); assertFalse(shop.medical)
    }

    @Test fun theCopyTakesOnlyClearFixesOfBrokenWords() {
        val common = Medicines.commonWords()
        val brady = diffs("My grandfather had a Brady Tardia with a heart block.", "my grandfather had a bradycardia with a heart block").single()
        assertEquals("bradycardia", CleanTranscript.clearTerm(brady, terms, common))
        // "hard" is a real word: the second opinion may be right, but Fady decides.
        assertNull(CleanTranscript.clearTerm(diffs("He has hard failure.", "he has heart failure").single(), terms, common))
        val base = run("b", "parakeet", "My grandfather had a Brady Tardia with a heart block.")
        val reading = Compare.reading(base, run("m", "medasr", "my grandfather had a bradycardia with a heart block"), SessionMode.CONVERSATION, meds, terms)
        assertTrue(CleanTranscript.medicineLines(base, reading, null).lines.single().text.contains("bradycardia", ignoreCase = true))
    }
}
