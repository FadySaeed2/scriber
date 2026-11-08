package com.fadysaeed.scriber.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class Phase1CoreTest {
    @Test fun streamResamplerMatchesWholeSignalForAnySplit() {
        val rnd = Random(7)
        val signal = FloatArray(30_000) { rnd.nextFloat() * 2 - 1 }
        for ((from, to) in listOf(48_000 to 16_000, 44_100 to 16_000, 24_000 to 16_000, 8_000 to 16_000)) {
            val whole = Resample.mono(signal, from, to)
            val r = StreamResampler(from, to)
            val parts = ArrayList<Float>()
            var i = 0
            while (i < signal.size) {
                val n = minOf(signal.size - i, 1 + rnd.nextInt(3000))
                parts += r.feed(signal.copyOfRange(i, i + n)).toList()
                i += n
            }
            parts += r.finish().toList()
            assertEquals("$from->$to length", whole.size, parts.size)
            for (k in whole.indices) assertEquals("$from->$to at $k", whole[k], parts[k], 1e-5f)
        }
    }

    @Test fun wavHeaderRoundTrips() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.99f, -1f)
        val bytes = Wav.header(16_000, samples.size * 2L) + Wav.floatsToShorts(samples)
        val input = ByteArrayInputStream(bytes)
        val info = Wav.readHeader(input)
        assertEquals(Wav.Info(16_000, 1, 16, 10), info)
        val back = Wav.blocks(input).flatMap { it.asSequence() }.toList()
        assertEquals(samples.size, back.size)
        for (k in samples.indices) assertEquals(samples[k], back[k], 1f / 16_000)
    }

    @Test fun sealDetectsAnyChange() {
        val run = TranscriptRun("r", "s", "medasr", "Google MedASR", "a".repeat(64), mapOf("vad" to "silero"), "b".repeat(64), 1, 2, RunStatus.DONE,
            listOf(Segment(0, 1200, "Olanzapine fifteen milligrams", listOf(Token("Olanzapine", 0)))))
        val sealed = Seal.sealed(run)
        assertTrue(Seal.intact(sealed))
        assertFalse(Seal.intact(sealed.copy(segments = listOf(Segment(0, 1200, "Olanzapine fifty milligrams")))))
        assertFalse(Seal.intact(run))
    }

    @Test fun dictationBecomesReadable() {
        val medasr = "[EXAM TYPE] CT chest PE protocol {period} [FINDINGS] {colon} The main PA is patent {period} {new paragraph} [IMPRESSION] {colon} Acute PE {period}"
        assertEquals("Exam type: CT chest PE protocol.\n\nFindings: The main PA is patent.\n\nImpression: Acute PE.",
            TranscriptFormats.readable(medasr, SessionMode.DICTATION))
        val parakeet = "mental state colon calm and settled period new paragraph plan colon continue olanzapine period"
        assertEquals("Mental state: calm and settled.\n\nPlan: continue olanzapine.", TranscriptFormats.readable(parakeet, SessionMode.DICTATION))
    }

    @Test fun conversationKeepsTheWordPeriod() {
        val t = "Her last period was two weeks ago."
        assertEquals(t, TranscriptFormats.readable(t, SessionMode.CONVERSATION))
    }

    @Test fun subtitlesAndJson() {
        val run = TranscriptRun("r", "s", "parakeet", "Parakeet", "a".repeat(64), emptyMap(), "b".repeat(64), 1, 2, RunStatus.DONE,
            listOf(Segment(1_500, 3_250, "hello there"), Segment(3_661_001, 3_662_000, "bye")))
        val srt = TranscriptFormats.srt(run, SessionMode.CONVERSATION, readable = false)
        assertTrue(srt.startsWith("1\n00:00:01,500 --> 00:00:03,250\nhello there\n\n2\n01:01:01,001 --> 01:01:02,000\nbye"))
        assertTrue(TranscriptFormats.vtt(run, SessionMode.CONVERSATION, false).startsWith("WEBVTT\n\n00:00:01.500 --> 00:00:03.250\n"))
        assertTrue(TranscriptFormats.plain(run, SessionMode.CONVERSATION, false).startsWith("[00:00:01] hello there\n[01:01:01] bye"))
        assertTrue(TranscriptFormats.json(run).contains("\"engine\": \"parakeet\""))
    }

    @Test fun retention() {
        val day = 86_400_000L
        assertFalse(Retention().audioExpired(0, 1000 * day))
        assertTrue(Retention(keepForever = false, deleteAfterDays = 30).audioExpired(0, 30 * day))
        assertFalse(Retention(keepForever = false, deleteAfterDays = 30).audioExpired(0, 29 * day))
    }

    @Test fun shortsAndFloats() {
        val f = floatArrayOf(0.25f, -0.25f)
        assertArrayEquals(f, Wav.shortsToFloats(Wav.floatsToShorts(f)), 1e-4f)
    }
}
