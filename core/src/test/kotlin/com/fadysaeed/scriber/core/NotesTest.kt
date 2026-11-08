package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {
    private val meds = Medicines.bundled()
    private val lines = listOf(
        Line(0, 0, 4000, "How have you been since we increased the olanzapine?", false, 0, "Speaker 1"),
        Line(1, 4200, 9000, "Better. I take fifteen milligrams at night now and I sleep about six hours.", false, 1, "Speaker 2"),
        Line(2, 9500, 12000, "Any thoughts of hurting yourself?", false, 0, "Speaker 1"),
        Line(3, 12200, 15000, "No, none at all, I want to go home to my daughter.", false, 1, "Speaker 2"),
        Line(4, 15500, 19000, "We'll continue fifteen milligrams and stay on 15/60 observations.", false, 0, "Speaker 1"),
    )

    private fun pipeline(answer: (String) -> String) = NotePipeline(meds, answer)

    @Test fun factsAreKeptOnlyWhenTheirWordsMedicinesAndNumbersAreReallyThere() {
        val p = pipeline { "" }
        val src = p.lines(lines)
        val answer = """
            MEDICATIONS | L2 | Takes olanzapine 15 mg at night | "I take fifteen milligrams at night"
            SYMPTOMS | L2 | Sleeps about 6 hours | "I sleep about six hours"
            RISK_SELF | L4 | Denies thoughts of self-harm | "No, none at all"
            PROTECTIVE | L4 | Wants to return home to her daughter | "I want to go home to my daughter"
            SYMPTOMS | L2 | Sleeps 8 hours | "I sleep about six hours"
            MEDICATIONS | L2 | Also takes quetiapine | "I take fifteen milligrams"
            RISK_SELF | L4 | Plans to harm himself | "I have a plan to end it"
            MOOD | L2 | Mood better | "Better"
            PLAN | L9 | Discharge | "go home"
            PLAN | L5 | Continue 15 mg and 15/60 observations | "continue fifteen milligrams and stay on 15/60 observations"
        """.trimIndent()
        val (facts, rejected) = p.parseFacts(answer, src, 1)
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5", "F6"), facts.map { it.id })
        assertEquals(listOf(FactKind.MEDICATIONS, FactKind.SYMPTOMS, FactKind.RISK_SELF, FactKind.PROTECTIVE, FactKind.MEDICATIONS, FactKind.PLAN), facts.map { it.kind })
        assertEquals(4, rejected.size)
        // A medicine the line does not name (the engine may have misheard it) stays, marked to check.
        assertTrue(facts[0].doubt!!.startsWith("medicine olanzapine"))
        assertTrue(facts[4].doubt!!.startsWith("medicine quetiapine"))
        assertNull(facts[1].doubt)
        assertTrue(rejected.joinToString { it.reason }, rejected.any { it.reason.startsWith("number 8") })
        assertTrue(rejected.any { it.reason.startsWith("quote not found") })
        assertTrue(rejected.any { it.reason.startsWith("unknown kind") })
        assertTrue(rejected.any { it.reason.startsWith("no such line") })
        assertEquals(4200L, facts.first().startMs)
    }

    @Test fun sectionsKeepCitationsAndMarkUnsupportedSentences() {
        val p = pipeline { "" }
        val facts = listOf(
            Fact("F1", FactKind.MEDICATIONS, "Takes 15 mg at night", "I take fifteen milligrams at night", 2, 4200, 9000),
            Fact("F2", FactKind.SYMPTOMS, "Sleeps about 6 hours", "I sleep about six hours", 2, 4200, 9000),
        ).associateBy { it.id }
        val s = p.parseSection("She takes 15 mg at night [F1]. She sleeps about six hours [F2]. She is eating well. She takes 20 mg [F1].", SectionForm.PROSE, facts)
        assertEquals(listOf("She takes 15 mg at night.", "She sleeps about six hours.", "She is eating well.", "She takes 20 mg."), s.map { it.text })
        assertEquals(listOf(listOf("F1"), listOf("F2"), emptyList(), listOf("F1")), s.map { it.factIds })
        assertNull(s[0].warning); assertNull(s[1].warning)
        assertEquals("No fact behind this sentence", s[2].warning)
        assertTrue(s[3].warning!!.startsWith("number 20"))
        assertTrue(s[3].severe); assertTrue(!s[2].severe)
        val list = p.parseSection("1. Continue current dose [F1]\n2. Review sleep [F2]", SectionForm.NUMBERED, facts)
        assertEquals(listOf("Continue current dose", "Review sleep"), list.map { it.text })
    }

    @Test fun theWholePipelineRunsWithAnyModel() {
        val p = pipeline { prompt ->
            when {
                prompt.startsWith("You take facts") -> """
                    MEDICATIONS | L2 | Takes 15 mg at night | "I take fifteen milligrams at night"
                    RISK_SELF | L4 | Denies thoughts of self-harm | "No, none at all"
                    PLAN | L5 | Continue 15 mg and 15/60 observations | "continue fifteen milligrams and stay on 15/60 observations"
                """.trimIndent()
                prompt.contains("Section: Plan") -> "1. Continue 15 mg [F3]\n2. Remain on 15/60 observations [F3]"
                prompt.contains("Section: Risk") -> "She denied thoughts of self-harm [F2]."
                prompt.contains("Section: Medication") -> "She takes 15 mg at night [F1]."
                else -> "Stable [F1]."
            }
        }
        val src = p.lines(lines)
        val (facts, rejected) = p.facts(src)
        assertEquals(3, facts.size); assertTrue(rejected.isEmpty())
        val template = Templates.builtIns().first { it.id == "inpatient" }
        val note = p.note(template, facts)
        val text = NoteText.render(note)
        assertTrue(text, text.contains("Plan\n1. Continue 15 mg\n2. Remain on 15/60 observations"))
        assertTrue(text, text.contains("Risk\nShe denied thoughts of self-harm."))
        // No context facts: "Present" is left out; MSE has none but says so.
        assertTrue(note.none { it.sectionId == "present" })
        assertEquals("Not assessed.", note.first { it.sectionId == "mse" }.sentences.single().text)
    }

    @Test fun longTranscriptsAreSplitWithOverlap() {
        val p = pipeline { "" }
        val many = (1..100).map { NotePipeline.SourceLine(it, null, "word ".repeat(30).trim(), it * 1000L, it * 1000L + 900) }
        val c = p.chunks(many, maxChars = 2000)
        assertTrue(c.size > 1)
        assertEquals(1, c.first().first().number); assertEquals(100, c.last().last().number)
        for (i in 1 until c.size) assertTrue(c[i].first().number <= c[i - 1].last().number)
    }

    @Test fun builtInTemplatesAreComplete() {
        val t = Templates.builtIns()
        assertEquals(listOf("inpatient", "followup", "telehealth", "adhd", "intake"), t.map { it.id })
        for (x in t) {
            assertTrue(x.sections.isNotEmpty())
            assertEquals(x.sections.size, x.sections.map { it.id }.distinct().size)
            assertTrue(x.sections.all { it.kinds.isNotEmpty() && it.instruction.isNotBlank() })
            assertTrue(x.sections.last().form == SectionForm.NUMBERED)
        }
    }

    @Test fun rolesAreReadFromTheModelsAnswer() {
        val p = pipeline { "Speaker 1 | CLINICIAN\n**Speaker 2** | Patient\nSpeaker 9 | STAFF" }
        val roled = p.withRoles(p.lines(lines))
        assertEquals("clinician", roled[0].role); assertEquals("patient", roled[1].role)
        assertEquals("L1 Speaker 1 (clinician)", roled[0].label)
        assertTrue(p.factPrompt(roled).contains("L2 Speaker 2 (patient): Better."))
        // No speakers: nothing to ask, lines unchanged.
        val bare = p.lines(lines.map { it.copy(speakerName = null, speaker = null) })
        assertEquals(bare, p.withRoles(bare))
    }

    @Test fun theCheckMarksUnsupportedAndContradictedSentences() {
        val p = pipeline { "" }
        val list = listOf(NoteSentence("He denies suicidal thoughts.", listOf("F1")), NoteSentence("He is preoccupied with self-harm.", listOf("F1")),
            NoteSentence("He sleeps well and exercises daily.", listOf("F2")), NoteSentence("He wants to go home.", listOf("F3")))
        val out = p.applyVerdicts("S1 | SUPPORTED | ok\nS2 | CONTRADICTED | F1 says he denies it\n- S3 | PARTLY | exercise not stated\nS4|supported", list)
        assertNull(out[0].warning)
        assertTrue(out[1].severe); assertTrue(out[1].warning!!.startsWith("Contradicts its facts: F1 says"))
        assertTrue(!out[2].severe); assertTrue(out[2].warning!!.startsWith("Only partly"))
        assertNull(out[3].warning)
    }

    @Test fun sentencesFullOfWordsNotInTheirFactsAreMarked() {
        val p = pipeline { "" }
        assertTrue(p.unsupportedShare("He is well groomed, cooperative, with linear and goal directed thought.", "Feels better and sleeps more") > 0.6)
        assertTrue(p.unsupportedShare("He reports feeling better and sleeping more.", "Feels better and sleeps more. Better. Sleeping more.") < 0.3)
        val facts = mapOf("F1" to Fact("F1", FactKind.SYMPTOMS, "Feels better and sleeps more", "Better. Sleeping more.", 2, 0, 1))
        val s = p.parseSection("He presents as well groomed and cooperative with linear goal directed thinking [F1].", SectionForm.PROSE, facts)
        assertEquals("Much of it is not in its facts", s.single().warning)
    }

    @Test fun medicinesPutInByTheCleaningAreMarkedToCheck() {
        val p = pipeline { "" }
        val src = p.lines(listOf(Line(0, 0, 3000, "I'm on sertraline for anxiety, fifty milligrams.", false, 1, "Speaker 2")), mapOf(0 to setOf("sertraline")))
        val (facts, rejected) = p.parseFacts("MEDICATION | L1 | Takes sertraline 50 mg for anxiety | \"on sertraline for anxiety\"", src, 1)
        assertTrue(rejected.isEmpty())
        assertTrue(facts.single().doubt!!.contains("second opinion"))
    }

    @Test fun educationIsKeptApartFromThePatientsMedicines() {
        val t = Templates.builtIns().first { it.id == "adhd" }
        assertTrue(t.sections.any { FactKind.EDUCATION in it.kinds })
        assertTrue(t.sections.none { it.id == "meds" && FactKind.EDUCATION in it.kinds })
        val p = pipeline { "" }
        assertTrue(p.factPrompt(p.lines(lines), t).contains("A medicine the clinician only explains is never one of the patient's MEDICATIONS"))
    }

    @Test fun theNoteReadsMedicinesTheSecondOpinionHeard() {
        fun run(id: String, engine: String, vararg text: String) = TranscriptRun(id, "s", engine, engine, "", emptyMap(), "", 0, status = RunStatus.DONE,
            segments = text.mapIndexed { i, t -> Segment(i * 5000L, i * 5000L + 4000, t) })
        val base = run("b", "parakeet", "How have you been since the change to all and zapping?", "I still take the quetiapin at night.", "I sleep better now.")
        val alt = run("m", "medasr", "how have you been since the change to olanzapine", "i still take the quetiapine at night", "i sleep better now")
        val reading = Compare.reading(base, alt, SessionMode.CONVERSATION, meds)
        val r = NoteInput.lines(base, reading, null)
        assertTrue(r.lines[0].text, r.lines[0].text.contains("olanzapine"))
        assertTrue(r.lines[1].text, r.lines[1].text.contains("quetiapine"))
        assertEquals("I sleep better now.", r.lines[2].text)
        assertEquals(setOf(0, 1), r.cleaned.keys)
        // A difference Fady kept as the base had it stays as it was.
        val kept = EditLayer("s", "b", "", kept = reading.differences.filter { it.medicine == "olanzapine" }.map { it.id }.toSet())
        assertTrue(!NoteInput.lines(base, reading, kept).lines[0].text.contains("olanzapine"))
    }

    @Test fun theLabsRolePlaysAndTheirChecklistsRead() {
        for (f in RolePlay.FILES) {
            val r = RolePlay.bundled(f)
            assertTrue(f, r.turns.size > 20 && r.expected.size > 10 && r.forbidden.size >= 4)
            assertTrue(r.turns.none { it.first.startsWith("#") })
        }
        val adhd = RolePlay.bundled("roleplay_adhd.txt")
        assertEquals(setOf("Speaker 1", "Speaker 2"), adhd.turns.map { it.first }.toSet())
        val s = adhd.score("He takes sertraline 100 mg. He is currently taking atomoxetine 40 mg. BP 120/80.")
        assertTrue(s.found.contains("sertraline 100 mg"))
        assertEquals(listOf("atomoxetine as his medicine", "blood pressure or pulse figures nobody said"), s.wrong)
        assertTrue(s.missing.contains("Concerta 36 mg"))
        assertTrue(adhd.score("I explained atomoxetine takes weeks to work.").wrong.isEmpty())
    }
}
