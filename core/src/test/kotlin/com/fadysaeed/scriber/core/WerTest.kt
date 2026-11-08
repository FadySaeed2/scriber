package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WerTest {
    @Test fun identicalTextHasNoErrors() {
        assertEquals(0, Wer.compute("Heart rate is seventy two.", "heart rate is seventy two").errors)
    }

    @Test fun dictationCommandsAndPunctuationAreIgnored() {
        val ref = "Findings colon. The main PA is patent period."
        val hyp = "Findings: The main PA is patent {period}"
        assertEquals(0, Wer.compute(ref, hyp).errors)
    }

    @Test fun substitutionsInsertionsDeletions() {
        val r = Wer.compute("a b c d", "a x c d e")
        assertEquals(4, r.referenceWords)
        assertEquals(2, r.errors)
        assertEquals(0.5, r.rate, 1e-9)
    }

    @Test fun spokenAndWrittenNumbersMatch() {
        assertEquals(0, Wer.compute("The patient temperature is thirty seven point two degrees. Oxygen saturation is ninety eight percent.",
            "The patient temperature is 37.2 degrees. Oxygen saturation is 98%.").errors)
        assertEquals(0, Wer.compute("Indication 54 year old female", "Indication fifty four year old female").errors)
        assertEquals(0, Wer.compute("one hundred and twenty", "120").errors)
        assertEquals(listOf("72", "beats"), Wer.normalise("seventy two beats"))
    }

    @Test fun sectionTagsKeepTheirWords() {
        assertEquals(0, Wer.compute("Exam type CT chest", "[EXAM TYPE] CT chest").errors)
    }

    @Test fun parsesReferenceFile() {
        val refs = Wer.parseReferences("# 0.wav\nLine one.\nLine two.\n\n# 1.wav\nOther.\n")
        assertEquals(mapOf("0.wav" to "Line one. Line two.", "1.wav" to "Other."), refs)
    }
}
