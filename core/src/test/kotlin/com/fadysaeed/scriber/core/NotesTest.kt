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
        assertEquals(listOf("F1", "F2", "F3", "F4"), facts.map { it.id })
        assertEquals(listOf(FactKind.SYMPTOMS, FactKind.RISK_SELF, FactKind.PROTECTIVE, FactKind.PLAN), facts.map { it.kind })
        assertEquals(6, rejected.size)
        // The first fact names olanzapine although the line says only "fifteen milligrams": its medicine is not there.
        assertTrue(rejected.joinToString { it.reason }, rejected.any { it.reason.startsWith("medicine") })
        assertTrue(rejected.any { it.reason.startsWith("number 8") })
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
}
