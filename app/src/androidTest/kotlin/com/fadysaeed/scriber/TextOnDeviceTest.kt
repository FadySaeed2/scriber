package com.fadysaeed.scriber

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fadysaeed.scriber.core.Compare
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.Medicines
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Scoring
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.core.Wer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's text matching (ICU) is stricter than the desktop Java the unit tests run on: a pattern
 * that works there can crash on the phone. Every text function runs here on real Android.
 */
@RunWith(AndroidJUnit4::class)
class TextOnDeviceTest {
    private fun run(engine: String, text: String) = TranscriptRun(engine, "s", engine, engine, "", emptyMap(), "", 0, status = RunStatus.DONE, segments = listOf(Segment(0, 5000, text)))

    @Test fun dictationFormattingRunsOnAndroid() {
        assertEquals("Exam type: CT chest PE protocol.\n\nFindings: The main PA is patent.\n\nImpression: Acute PE.",
            TranscriptFormats.readable("[EXAM TYPE] CT chest PE protocol {period} [FINDINGS] the main PA is patent {period} [IMPRESSION] acute PE {period}", SessionMode.DICTATION))
        assertEquals("Mental state: calm and settled.\n\nPlan: continue olanzapine.",
            TranscriptFormats.readable("mental state colon calm and settled period new paragraph plan colon continue olanzapine period", SessionMode.DICTATION))
    }

    @Test fun comparisonDictionaryAndScoringRunOnAndroid() {
        val meds = Medicines.bundled()
        val r = Compare.reading(run("parakeet", "Continue all and zapping 10 mg nocte."), run("medasr", "continue olanzapine ten mg nocte {period}"), SessionMode.DICTATION, meds)
        assertEquals("olanzapine", r.differences.single().medicine)
        assertEquals("olanzapine", meds.find(listOf("olanzapene"))!!.canonical)
        assertEquals(1, Scoring.score("Started olanzapine.", listOf(run("medasr", "started olanzapine")), meds).single().medicinesHeard)
        assertEquals(listOf("main", "pa", "is", "patent"), Wer.normalise("[MAIN] PA is patent {period}"))
        assertEquals("Ward-review-bed-4", Ids.fileStem("Ward review, bed 4"))
    }
}
