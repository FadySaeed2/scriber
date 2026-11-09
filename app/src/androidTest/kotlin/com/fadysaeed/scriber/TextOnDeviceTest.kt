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

    private fun one(s: String) = s.replace("\n", "⏎")

    @Test fun dictationFormattingRunsOnAndroid() {
        same("Exam type: CT chest PE protocol.\n\nFindings: The main PA is patent.\n\nImpression: Acute PE.",
            TranscriptFormats.readable("[EXAM TYPE] CT chest PE protocol {period} [FINDINGS] the main PA is patent {period} [IMPRESSION] acute PE {period}", SessionMode.DICTATION))
        same("Mental state: calm and settled.\n\nPlan: continue olanzapine.",
            TranscriptFormats.readable("mental state colon calm and settled period new paragraph plan colon continue olanzapine period", SessionMode.DICTATION))
    }

    private fun same(expected: String, actual: String) = assertEquals(one(expected), one(actual))

    @Test fun comparisonDictionaryAndScoringRunOnAndroid() {
        val meds = Medicines.bundled()
        val r = Compare.reading(run("parakeet", "Continue all and zapping 10 mg nocte."), run("medasr", "continue olanzapine ten mg nocte {period}"), SessionMode.DICTATION, meds)
        assertEquals("olanzapine", r.differences.single().medicine)
        assertEquals("olanzapine", meds.find(listOf("olanzapene"))!!.canonical)
        assertEquals(1, Scoring.score("Started olanzapine.", listOf(run("medasr", "started olanzapine")), meds).single().medicinesHeard)
        assertEquals(listOf("main", "pa", "is", "patent"), Wer.normalise("[MAIN] PA is patent {period}"))
        assertEquals("Ward-review-bed-4", Ids.fileStem("Ward review, bed 4"))
    }

    /** The clean copy for cloud models uses regular expressions too: run them on Android's engine. */
    @Test fun cleanCopyRunsOnAndroid() {
        val c = com.fadysaeed.scriber.core.CleanTranscript
        assertEquals("I have limited availability.", c.tidy("Uh I have lim limited availability."))
        assertEquals("I was tired. Then I slept.", c.tidy("I was tired, um. then I slept."))
        val lines = listOf(
            com.fadysaeed.scriber.core.Line(0, 0, 3000, "So without repeating the assessment,", false, 0, "Dr Fady"),
            com.fadysaeed.scriber.core.Line(1, 3100, 6000, "have you ever tried stimulants before?", false, 0, "Dr Fady"),
            com.fadysaeed.scriber.core.Line(2, 6500, 9000, "Yeah, uh, I've tried my partner's.", false, 1, "Rowan"),
        )
        assertEquals("[00:00] Dr Fady: So without repeating the assessment, have you ever tried stimulants before?\n\n[00:06] Rowan: Yeah, I've tried my partner's.",
            c.render(c.paragraphs(lines, SessionMode.CONVERSATION), times = true))
        val dictation = listOf(com.fadysaeed.scriber.core.Line(0, 0, 4000, "mental state colon calm and settled period new paragraph plan colon uh continue olanzapine period", false))
        assertEquals("Mental state: calm and settled.\n\nPlan: continue olanzapine.", c.render(c.paragraphs(dictation, SessionMode.DICTATION), times = false))
        assertEquals(12, com.fadysaeed.scriber.core.Prompts.builtIns().size)
    }
}
