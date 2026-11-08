package com.fadysaeed.scriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResampleTest {
    private fun tone(hz: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size) =
        sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))

    @Test fun keepsLengthAndToneWhenGoingDown() {
        val out = Resample.mono(tone(440.0, 24_000, 1.0), 24_000, 16_000)
        assertEquals(16_000, out.size)
        val expected = tone(440.0, 16_000, 1.0)
        val err = (200 until 15_800).maxOf { abs(out[it] - expected[it]) }
        assertTrue("max error $err", err < 0.02)
    }

    @Test fun removesSoundAboveTheNewLimit() {
        // 10 kHz cannot exist at 16 kHz (limit 8 kHz); it must be filtered, not folded back.
        val out = Resample.mono(tone(10_000.0, 48_000, 1.0), 48_000, 16_000)
        assertTrue("leftover ${rms(out, 200, out.size - 200)}", rms(out, 200, out.size - 200) < 0.02)
    }

    @Test fun goingUpKeepsTheTone() {
        val out = Resample.mono(tone(300.0, 8_000, 0.5), 8_000, 16_000)
        assertEquals(8_000, out.size)
        assertEquals(0.5 / sqrt(2.0), rms(out, 200, out.size - 200), 0.02)
    }

    @Test fun sameRateIsACopy() {
        val a = floatArrayOf(0.1f, 0.2f)
        val b = Resample.mono(a, 16_000, 16_000)
        assertTrue(a.contentEquals(b) && a !== b)
    }
}
