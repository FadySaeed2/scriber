package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2CoreTest {
    private val meds = Medicines.bundled()

    private fun run(engine: String, vararg segs: Pair<LongRange, String>) = TranscriptRun(
        id = engine, sessionId = "s", engine = engine, engineTitle = engine, modelSha256 = "", settings = emptyMap(),
        audioSha256 = "", startedAtMillis = 0, status = RunStatus.DONE,
        segments = segs.map { (t, text) -> Segment(t.first, t.last, text) },
    )

    @Test fun dictionaryKnowsGenericsBrandsAndMisspellings() {
        assertTrue(meds.size > 800)
        assertEquals("olanzapine", meds.find(listOf("olanzapine"))!!.canonical)
        assertTrue(meds.find(listOf("Olanzapine,"))!!.exact)
        assertEquals("olanzapine", meds.find(listOf("Zyprexa"))!!.generic)
        val typo = meds.find(listOf("olanzapene"))!!
        assertEquals("olanzapine", typo.canonical); assertFalse(typo.exact)
        assertEquals("quetiapine", meds.find(listOf("quetiapin"))!!.canonical)
        assertEquals("olanzapine", meds.find(listOf("olan", "zapine"), joinOnly = true)!!.canonical)
        assertEquals("Invega Sustenna", meds.find(listOf("invega", "sustenna"), joinOnly = true)!!.canonical)
        // Everyday words that look like medicines are left alone.
        for (w in listOf("concert", "singular", "ability", "january", "guidance", "parent", "sequel", "patient", "settled", "eye", "contact")) {
            assertNull(w, meds.find(listOf(w)))
        }
    }

    @Test fun fadysOwnWordsAreLearned() {
        val m = Medicines.bundled(PersonalWords(words = listOf("Brixadi"), heardAs = mapOf("allanzapine" to "olanzapine")))
        assertEquals("Brixadi", m.find(listOf("brixadi"))!!.canonical)
        assertEquals("olanzapine", m.find(listOf("Allanzapine"))!!.canonical)
    }

    @Test fun distanceCountsSwapsAsOne() {
        assertEquals(1, Medicines.distance("olanzapine", "olnazapine"))
        assertEquals(2, Medicines.distance("abcdef", "abxdey"))
        assertEquals(3, Medicines.distance("aaaaaaaa", "bbbbbbbb", limit = 2))
    }

    @Test fun wordTimesComeFromTokensWhenTheySpellTheText() {
        val seg = Segment(1000, 3000, "Main PA patent.", listOf(Token("▁Main", 1000), Token("▁P", 1400), Token("A", 1500), Token("▁pat", 2000), Token("ent.", 2300)))
        val r = run("p").copy(segments = listOf(seg))
        val (words, ranges) = Words.of(r, SessionMode.CONVERSATION)
        assertEquals(listOf(1000L, 1400L, 2000L), words.map { it.startMs })
        assertEquals(listOf(0..2), ranges)
        // Tokens that do not spell the text: spread evenly.
        val odd = r.copy(segments = listOf(seg.copy(tokens = listOf(Token("x", 1000)))))
        assertEquals(listOf(1000L, 1666L, 2333L), Words.of(odd, SessionMode.CONVERSATION).first.map { it.startMs })
    }

    @Test fun differencesAreFoundOnlyWhereTheEnginesReallyDisagree() {
        val base = run("parakeet", 0L..4000L to "The main PE is patent and he takes 15 mg.", 5000L..9000L to "Plan follow-up next week.")
        val alt = run("medasr", 0L..4000L to "the main PA is patent and he takes fifteen mg {period}", 5000L..9000L to "[PLAN] follow up next week {period}")
        val r = Compare.reading(base, alt, SessionMode.DICTATION, meds)
        assertEquals(1, r.differences.size)
        val d = r.differences.single()
        assertEquals("PE", d.baseText); assertEquals("PA", d.altText); assertEquals("PA", d.suggestion)
        assertEquals(2, d.from); assertEquals(3, d.to); assertNull(d.medicine)
    }

    @Test fun theSecondEngineCatchesAMedicineTheBaseMisheard() {
        val base = run("parakeet", 0L..5000L to "Continue all and zapping 10 mg nocte.")
        val alt = run("medasr", 0L..5000L to "continue olanzapine ten mg nocte {period}")
        val r = Compare.reading(base, alt, SessionMode.DICTATION, meds)
        val d = r.differences.single()
        assertEquals("all and zapping", d.baseText)
        assertEquals("olanzapine", d.medicine)
        assertEquals("olanzapine", d.suggestion)
        assertEquals(1, d.from); assertEquals(4, d.to)
        // Punctuation and capitals of the base words carry over to what is put in.
        val q = Compare.reading(run("p", 0L..5000L to "Since the change to all and zapping?"), run("m", 0L..5000L to "since the change to olanzapine"), SessionMode.CONVERSATION, meds)
        assertEquals("olanzapine?", q.differences.single().suggestion)
    }

    @Test fun wordsOnlyTheSecondEngineHeardShowOnlyWhenTheyNameAMedicine() {
        val base = run("parakeet", 0L..5000L to "He is on 20 mg daily.")
        val noise = run("medasr", 0L..5000L to "he is uh on twenty mg daily")
        assertTrue(Compare.reading(base, noise, SessionMode.CONVERSATION, meds).differences.isEmpty())
        val drug = run("medasr", 0L..5000L to "he is on fluoxetine twenty mg daily")
        val d = Compare.reading(base, drug, SessionMode.CONVERSATION, meds).differences.single()
        assertEquals(3, d.from); assertEquals(3, d.to); assertEquals("fluoxetine", d.medicine)
    }

    @Test fun britishAndAmericanSpellingsAreNotDifferences() {
        val base = run("parakeet", 0L..5000L to "My mum noticed oedema and the colour changed, she apologised.")
        val alt = run("medasr", 0L..5000L to "my mom noticed edema and the color changed she apologized")
        assertTrue(Compare.reading(base, alt, SessionMode.CONVERSATION, meds).differences.isEmpty())
        // But a real one-letter medical difference still shows.
        assertEquals(1, Compare.reading(run("p", 0L..1000L to "main PE"), run("m", 0L..1000L to "main PA"), SessionMode.CONVERSATION, meds).differences.size)
    }

    @Test fun medicinesInTheBaseAreMarkedAndMisspellingsOffered() {
        val base = run("parakeet", 0L..5000L to "Started olanzapine and quetiapin, stopped Zyprexa.")
        val r = Compare.reading(base, null, SessionMode.CONVERSATION, meds)
        assertEquals(listOf("olanzapine" to true, "quetiapine" to false, "Zyprexa" to true), r.medicines.map { it.canonical to it.exact })
    }

    @Test fun editsLayOverTheRunWithoutChangingIt() {
        val base = run("parakeet", 0L..4000L to "The main PE is patent.", 5000L..9000L to "Continue all and zapping nocte.")
        val r = Compare.reading(base, null, SessionMode.CONVERSATION, meds)
        assertEquals(listOf("The main PE is patent.", "Continue all and zapping nocte."), Document.lines(base, r, null).map { it.text })
        var layer = EditLayer("s", base.id, "seal")
            .with(WordEdit(2, 3, 0, "PA", "second", 1))
            .with(WordEdit(6, 9, 1, "olanzapine", "medicine", 2))
            .with(WordEdit(6, 6, 1, "please", "typed", 3))
        assertEquals(listOf("The main PA is patent.", "Continue please olanzapine nocte."), Document.lines(base, r, layer).map { it.text })
        // A newer edit over the same words wins; undo takes back the newest.
        layer = layer.with(WordEdit(6, 9, 1, "Zyprexa", "typed", 4))
        assertEquals("Continue please Zyprexa nocte.", Document.lines(base, r, layer)[1].text)
        layer = layer.undo()
        assertEquals("Continue please all and zapping nocte.", Document.lines(base, r, layer)[1].text)
        // Removing words.
        layer = layer.with(WordEdit(0, 1, 0, "", "typed", 5))
        assertEquals("main PA is patent.", Document.lines(base, r, layer)[0].text)
        assertTrue(Document.lines(base, r, layer).all { it.edited })
    }

    @Test fun scoringCountsMedicinesByGenericName() {
        val ref = "Started olanzapine, previously on Zyprexa and sertraline."
        val runs = listOf(run("parakeet", 0L..1L to "Started all and zapping previously on Zyprexa and sertraline."), run("medasr", 0L..1L to "started olanzapine previously on zyprexa and sertraline"))
        val s = Scoring.score(ref, runs, meds)
        assertEquals(3, s[0].medicinesSaid)
        assertEquals(2, s[0].medicinesHeard)
        assertEquals(3, s[1].medicinesHeard)
        assertTrue(s[1].wer.rate < s[0].wer.rate)
    }

    @Test fun exportsUseTheCorrectedLines() {
        val lines = listOf(Line(0, 0, 1500, "Main PA patent.", true))
        assertTrue(TranscriptFormats.srt(lines, SessionMode.CONVERSATION, false).contains("Main PA patent."))
        assertTrue(TranscriptFormats.plain(lines, SessionMode.CONVERSATION, false).startsWith("[00:00:00] Main PA patent."))
    }
}
