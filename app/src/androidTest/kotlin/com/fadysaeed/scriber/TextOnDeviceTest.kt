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

    /** The note pipeline's text checks, on Android's regex engine, with a fake writer. */
    @Test fun notePipelineRunsOnAndroid() {
        val lines = listOf(
            com.fadysaeed.scriber.core.Line(0, 0, 4000, "How have you been since we increased the olanzapine?", false, 0, "Speaker 1"),
            com.fadysaeed.scriber.core.Line(1, 4200, 9000, "Better. I take fifteen milligrams at night now and I sleep about six hours.", false, 1, "Speaker 2"),
            com.fadysaeed.scriber.core.Line(2, 12200, 15000, "No, none at all, I want to go home to my daughter.", false, 1, "Speaker 2"),
            com.fadysaeed.scriber.core.Line(3, 15500, 19000, "We'll continue fifteen milligrams and stay on 15/60 observations.", false, 0, "Speaker 1"),
        )
        val p = com.fadysaeed.scriber.core.NotePipeline(Medicines.bundled()) { prompt ->
            when {
                prompt.startsWith("You take facts") -> "- MEDICATIONS | L2 | Takes 15 mg at night | \"I take fifteen milligrams at night\"\n" +
                    "**RISK_SELF** | L3 | Denies thoughts of self-harm | “No, none at all”\n" +
                    "PLAN | L4 | Continue 15 mg and 15/60 observations | \"continue fifteen milligrams and stay on 15/60 observations\"\n" +
                    "SYMPTOMS | L2 | Sleeps 8 hours | \"I sleep about six hours\""
                prompt.contains("Section: Plan") -> "1. Continue 15 mg [F3]\n2) Remain on 15/60 observations [F3]\n- Review in a week"
                prompt.contains("Section: Risk") -> "She denied thoughts of self-harm [F2]. She has a plan [F2]. Mood is low [F9]."
                else -> "She takes 15 mg at night [F1, F3]. Sleep is fine."
            }
        }
        val (facts, rejected) = p.facts(p.lines(lines))
        assertEquals(listOf("F1", "F2", "F3"), facts.map { it.id })
        assertEquals(1, rejected.size)
        val note = p.note(com.fadysaeed.scriber.core.Templates.builtIns().first { it.id == "inpatient" }, facts)
        val plan = note.first { it.sectionId == "plan" }.sentences
        assertEquals(listOf("Continue 15 mg", "Remain on 15/60 observations", "Review in a week"), plan.map { it.text })
        assertEquals("No fact behind this sentence", plan[2].warning)
        val risk = note.first { it.sectionId == "risk" }.sentences
        assertEquals(listOf("She denied thoughts of self-harm.", "She has a plan.", "Mood is low."), risk.map { it.text })
        assertEquals(listOf(listOf("F2"), listOf("F2"), emptyList()), risk.map { it.factIds })
        assertEquals("Not assessed.", note.first { it.sectionId == "mse" }.sentences.single().text)
        assertEquals(null, p.groundedIn("Takes 15 mg", "I take 15 mg"))
        assertEquals(5, com.fadysaeed.scriber.core.NoteText.words("Plan\n1. Continue 15 mg"))
    }

    /** The Lab's checklists are regular expressions: they must compile and match on Android's engine. */
    @Test fun labRolePlaysScoreOnAndroid() {
        for (f in com.fadysaeed.scriber.core.RolePlay.FILES) {
            val r = com.fadysaeed.scriber.core.RolePlay.bundled(f)
            r.score("nothing")
        }
        val adhd = com.fadysaeed.scriber.core.RolePlay.bundled("roleplay_adhd.txt")
        val s = adhd.score("He takes sertraline 100 mg. He is currently taking atomoxetine 40 mg. BP 120/80.")
        assertEquals(listOf("atomoxetine as his medicine", "blood pressure or pulse figures nobody said"), s.wrong)
        val p = com.fadysaeed.scriber.core.NotePipeline(Medicines.bundled()) { "" }
        assertEquals(true, p.unsupportedShare("He is well groomed, cooperative, with linear and goal directed thought.", "Feels better") > 0.6)
        val out = p.applyVerdicts("S1 | CONTRADICTED | denies it\n- S2 | PARTLY | x", listOf(
            com.fadysaeed.scriber.core.NoteSentence("a", listOf("F1")), com.fadysaeed.scriber.core.NoteSentence("b", listOf("F1"))))
        assertEquals(true, out[0].severe); assertEquals("Only partly in its facts: x", out[1].warning)
    }
}
