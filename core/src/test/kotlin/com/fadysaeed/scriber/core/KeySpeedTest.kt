package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keys stay exactly what they were before the spelling rules were built once, and keying is fast. */
class KeySpeedTest {
    private fun oldSpelling(k: String): String {
        if (k.length < 3) return k
        var x = k
        if (x == "mum" || x == "mums") return x.replace("mum", "mom")
        x = x.replace("ae", "e").replace("oe", "e")
        x = x.replace(Regex("""isation(s?)$"""), "ization$1").replace(Regex("""is(e|ed|es|ing)$"""), "iz$1")
        x = x.replace(Regex("""our(s?)$"""), "or$1").replace(Regex("""tre(s?)$"""), "ter$1")
        x = x.replace(Regex("""ogue(s?)$"""), "og$1").replace(Regex("""lled$"""), "led").replace(Regex("""lling$"""), "ling")
        return x
    }

    private fun words(): List<String> {
        val terms = javaClass.getResourceAsStream("/medical_terms.txt")!!.bufferedReader().readLines()
        val common = Medicines.commonWords()
        val meds = Medicines.bundledNames()
        val extra = listOf("organisations", "realise", "realised", "realising", "colours", "centre", "centres", "catalogue",
            "travelled", "travelling", "mum", "mums", "oedema", "haemorrhage", "is", "this", "analyse", "tour", "our")
        return (terms + common + meds + extra).flatMap { it.split(Regex("""\s+""")) }
    }

    @Test fun keysAreTheSameAsBefore() {
        val all = words()
        assertTrue(all.size > 50_000)
        for (w in all) {
            val k = w.lowercase().filter { it.isLetterOrDigit() }
            if (k.isEmpty() || k.all { it.isDigit() }) continue
            val expected = Words.key(w, SessionMode.CONVERSATION)
            // Number words map to digits before spelling is reached; everything else must match the old rules.
            if (expected.all { it.isDigit() }) continue
            assertEquals(w, oldSpelling(k), expected)
        }
    }

    @Test fun theMedicalListLoadsQuickly() {
        val lines = javaClass.getResourceAsStream("/medical_terms.txt")!!.bufferedReader().readLines()
        MedicalTerms(lines.take(2000))
        val t = System.nanoTime()
        val terms = MedicalTerms(lines)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("MedicalTerms: ${terms.size} keys in $ms ms")
        assertTrue("took $ms ms", ms < 3000)
    }
}
