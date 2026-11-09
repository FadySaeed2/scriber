package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTranscriptTest {
    private val meds = Medicines.bundled()

    @Test fun fillersRepeatsAndBrokenStartsGoEveryOtherWordStays() {
        assertEquals("I have limited availability.", CleanTranscript.tidy("Uh I have lim limited availability."))
        assertEquals("I'm currently based in Perth.", CleanTranscript.tidy("I'm I'm currently uh based in Perth."))
        assertEquals("So, I believe you met Farid.", CleanTranscript.tidy("So, um s I believe you met Farid."))
        assertEquals("So I'm sure it affects your work.", CleanTranscript.tidy("Um so I'm sure it affects your work."))
        assertEquals("It was not a major problem.", CleanTranscript.tidy("It was not not a major problem."))
        // Said twice on purpose: kept.
        assertEquals("Very very protective over food. No, no.", CleanTranscript.tidy("very very protective over food. No, no."))
        // A spike, not "spi": the broken start goes; real short words before a longer one stay.
        assertEquals("It has a spike in the first three hours.", CleanTranscript.tidy("It has a spi spike in the first three hours."))
        assertEquals("He went in in the evening, and an analysis followed.", CleanTranscript.tidy("He went in in the evening, and an analysis followed."))
        assertEquals("Vitamin D and B12 are fine.", CleanTranscript.tidy("Vitamin D and B12 are fine."))
        // The end of a sentence carried by a filler is kept; dangling commas go.
        assertEquals("I was tired. Then I slept.", CleanTranscript.tidy("I was tired, um. then I slept."))
        assertEquals("Yeah, fine.", CleanTranscript.tidy("Uh, yeah, fine."))
    }

    @Test fun noWordIsEverAddedOrChanged() {
        val text = "Okay, so the plan is, uh, I'll put the letter, uh, put the special authority approval for you, um, together and you can see Doctor Locke whenever you're ready."
        val before = Wer.normalise(text).filterNot { it in setOf("uh", "um") }
        val after = Wer.normalise(CleanTranscript.tidy(text))
        assertEquals(before, after)
    }

    private fun line(i: Int, start: Long, end: Long, text: String, who: String?) = Line(i, start, end, text, false, null, who)

    @Test fun linesBecomeOneParagraphPerTurn() {
        val lines = listOf(
            line(0, 0, 3000, "So without repeating the assessment,", "Dr Fady"),
            line(1, 3100, 6000, "have you ever tried stimulants before?", "Dr Fady"),
            line(2, 6500, 9000, "Yeah, I've tried my partner's.", "Rowan"),
            line(3, 9100, 12000, "Uh methylphenidate.", "Rowan"),
            line(4, 12500, 14000, "Okay.", "Dr Fady"),
        )
        val p = CleanTranscript.paragraphs(lines, SessionMode.CONVERSATION)
        assertEquals(listOf("Dr Fady", "Rowan", "Dr Fady"), p.map { it.speaker })
        assertEquals("So without repeating the assessment, have you ever tried stimulants before?", p[0].text)
        assertEquals("Yeah, I've tried my partner's. Methylphenidate.", p[1].text)
        assertEquals("Dr Fady: So without repeating the assessment, have you ever tried stimulants before?\n\nRowan: Yeah, I've tried my partner's. Methylphenidate.\n\nDr Fady: Okay.",
            CleanTranscript.render(p, times = false))
        assertTrue(CleanTranscript.render(p, times = true).startsWith("[00:00] Dr Fady: So"))
        assertTrue(CleanTranscript.render(p, times = true).contains("\n\n[00:06] Rowan: "))
    }

    @Test fun withoutSpeakersBrokenLinesJoinAndPausesStartParagraphs() {
        val lines = listOf(
            line(0, 0, 3000, "Finding it easier to remember things without having to stay", null),
            line(1, 3050, 6000, "super late every time I'm at work.", null),
            line(2, 9000, 12000, "And finding it easier to study.", null),
        )
        val p = CleanTranscript.paragraphs(lines, SessionMode.MEETING)
        assertEquals(2, p.size)
        assertEquals("Finding it easier to remember things without having to stay super late every time I'm at work.", p[0].text)
    }

    @Test fun dictationBecomesReadable() {
        val lines = listOf(line(0, 0, 4000, "mental state colon calm and settled period new paragraph", null), line(1, 4000, 8000, "plan colon uh continue olanzapine period", null))
        assertEquals("Mental state: calm and settled.\n\nPlan: continue olanzapine.", CleanTranscript.render(CleanTranscript.paragraphs(lines, SessionMode.DICTATION), times = false))
    }

    @Test fun theCopyReadsMedicinesTheSecondOpinionHeard() {
        fun run(id: String, engine: String, vararg text: String) = TranscriptRun(id, "s", engine, engine, "", emptyMap(), "", 0, status = RunStatus.DONE,
            segments = text.mapIndexed { i, t -> Segment(i * 5000L, i * 5000L + 4000, t) })
        val base = run("b", "parakeet", "How have you been since the change to all and zapping?", "I still take the quetiapin at night.", "I sleep better now.")
        val alt = run("m", "medasr", "how have you been since the change to olanzapine", "i still take the quetiapine at night", "i sleep better now")
        val reading = Compare.reading(base, alt, SessionMode.CONVERSATION, meds)
        val r = CleanTranscript.medicineLines(base, reading, null)
        assertTrue(r.lines[0].text, r.lines[0].text.contains("olanzapine"))
        assertTrue(r.lines[1].text, r.lines[1].text.contains("quetiapine"))
        assertEquals("I sleep better now.", r.lines[2].text)
        assertEquals(setOf(0, 1), r.cleaned.keys)
        // A difference Fady kept as the base had it stays as it was.
        val kept = EditLayer("s", "b", "", kept = reading.differences.filter { it.medicine == "olanzapine" }.map { it.id }.toSet())
        assertTrue(!CleanTranscript.medicineLines(base, reading, kept).lines[0].text.contains("olanzapine"))
    }

    @Test fun theCopyHasAHeaderAndThePromptGoesFirst() {
        val h = CleanTranscript.header(SessionMode.MEETING, 1_794_150_000_000L, 26 * 60_000L + 50_000, listOf("Anna", "Ben"))
        assertTrue(h, h.startsWith("Meeting recorded ") && h.endsWith(", 27 min. Speakers: Anna, Ben."))
        assertEquals("$h\n\nbody", CleanTranscript.compose(null, h, "body"))
        assertEquals("Do this.\n\nTRANSCRIPT\n\n$h\n\nbody", CleanTranscript.compose("  Do this.\n", h, "body"))
    }

    @Test fun builtInPromptsAreCompleteAndPlain() {
        val all = Prompts.builtIns()
        assertEquals(12, all.size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
        for (p in all) {
            assertTrue(p.id, p.builtIn && p.name.isNotBlank() && p.text.length > 300)
            assertTrue(p.id, !p.text.contains("{TRANSCRIPT}") && !p.text.contains("\$"))
            // No leftover indentation from the source code.
            assertTrue(p.id, p.text.lines().none { it.startsWith("    ") })
            assertTrue(p.id, p.text.contains("[check:"))
        }
        for (id in Prompts.DEFAULTS.values) assertTrue(all.any { it.id == id })
        val clinical = all.filter { it.id !in setOf("minutes", "dictation") }
        for (p in clinical) assertTrue(p.id, p.text.contains("Never record a medicine the clinician only explained"))
        assertTrue(all.first { it.id == "inpatient" }.text.contains("Never label risk as low, moderate or high"))
        assertTrue(Prompts.blank("x", "Mine").text.contains("Use only what is in the transcript"))
    }
}
