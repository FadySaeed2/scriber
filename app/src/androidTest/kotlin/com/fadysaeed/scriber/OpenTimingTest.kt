package com.fadysaeed.scriber

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fadysaeed.scriber.core.Compare
import com.fadysaeed.scriber.core.MedicalTerms
import com.fadysaeed.scriber.core.Medicines
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.core.Words
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long opening a half-hour conversation takes: loading the medical terms, and comparing the
 * two engines' transcripts. Times go to the log (tag ScriberTiming) so builds can be compared.
 */
@RunWith(AndroidJUnit4::class)
class OpenTimingTest {
    private val lines = listOf(
        "How have you been sleeping since we increased the olanzapine to fifteen milligrams at night?",
        "Better, I get about six hours now, but I still wake up early and feel a bit flat in the mornings.",
        "Any palpitations, chest pain or shortness of breath since starting the new tablets?",
        "No, nothing like that, but my appetite has gone up a lot and I have put on two kilos.",
        "We will check your fasting glucose and lipids this week and see the dietitian about the weight.",
    )

    private fun run(id: String, engine: String, alt: Boolean): TranscriptRun = TranscriptRun(id, "s", engine, engine, "", emptyMap(), "", 0, status = RunStatus.DONE,
        segments = List(360) { i ->
            var t = lines[i % lines.size]
            if (alt && i % 7 == 0) t = t.lowercase().replace("olanzapine", "olanzapine").replace("palpitations", "pal patients").replace(",", "")
            Segment(i * 5_000L, i * 5_000L + 4_800, t)
        })

    /** The spelling rules as they were, building each regex on every word, to time against. */
    private fun oldKey(word: String): String {
        val k = word.lowercase().filter { it.isLetterOrDigit() }
        if (k.length < 3) return k
        var x = k
        if (x == "mum" || x == "mums") return x.replace("mum", "mom")
        x = x.replace("ae", "e").replace("oe", "e")
        x = x.replace(Regex("""isation(s?)$"""), "ization$1").replace(Regex("""is(e|ed|es|ing)$"""), "iz$1")
        x = x.replace(Regex("""our(s?)$"""), "or$1").replace(Regex("""tre(s?)$"""), "ter$1")
        x = x.replace(Regex("""ogue(s?)$"""), "og$1").replace(Regex("""lled$"""), "led").replace(Regex("""lling$"""), "ling")
        return x
    }

    @Test fun keyingTheMedicalWordsBeforeAndAfter() {
        val all = MedicalTerms::class.java.getResourceAsStream("/medical_terms.txt")!!.bufferedReader().readLines()
            .filter { !it.startsWith("#") }.flatMap { it.trim().split(" ") }
        val t0 = SystemClock.elapsedRealtime()
        val before = all.map(::oldKey)
        val t1 = SystemClock.elapsedRealtime()
        val after = all.map { Words.key(it, SessionMode.CONVERSATION) }
        val t2 = SystemClock.elapsedRealtime()
        Log.i("ScriberTiming", "keying ${all.size} medical words: old way ${t1 - t0} ms, now ${t2 - t1} ms")
        assertTrue(before.size == after.size)
    }

    @Test fun openingAHalfHourConversation() {
        val words = MedicalTerms::class.java.getResourceAsStream("/medical_terms.txt")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
        val t0 = SystemClock.elapsedRealtime()
        val terms = MedicalTerms(words)
        val t1 = SystemClock.elapsedRealtime()
        val meds = Medicines.bundled()
        val base = run("b", "parakeet", false); val alt = run("m", "medasr", true)
        val t2 = SystemClock.elapsedRealtime()
        val reading = Compare.reading(base, alt, SessionMode.CONVERSATION, meds, terms)
        val t3 = SystemClock.elapsedRealtime()
        Log.i("ScriberTiming", "medical terms ${t1 - t0} ms (${terms.size}); compare 30 min ${t3 - t2} ms (${reading.words.size} words, ${reading.differences.size} differences)")
        assertTrue(reading.words.size > 5_000)
    }
}
