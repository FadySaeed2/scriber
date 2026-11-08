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

    @Test fun parsesReferenceFile() {
        val refs = Wer.parseReferences("# 0.wav\nLine one.\nLine two.\n\n# 1.wav\nOther.\n")
        assertEquals(mapOf("0.wav" to "Line one. Line two.", "1.wav" to "Other."), refs)
    }
}
