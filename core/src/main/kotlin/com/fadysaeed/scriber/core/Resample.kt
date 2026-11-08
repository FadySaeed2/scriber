package com.fadysaeed.scriber.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Converts mono audio to another sample rate with a windowed-sinc filter, so every recording and
 * import reaches the speech models as 16 kHz regardless of how it was made (phones record at
 * 44.1 or 48 kHz; some files are 24 kHz). Lowering the rate also filters out sound above the new
 * limit instead of folding it back as noise.
 */
object Resample {
    private const val HALF_TAPS = 24

    fun mono(input: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || input.isEmpty()) return input.copyOf()
        val ratio = to.toDouble() / from
        val outLen = max(1, (input.size * ratio).toLong().toInt())
        val cutoff = min(1.0, ratio) * 0.95
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val center = i / ratio
            val c = floor(center).toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in c - HALF_TAPS + 1..c + HALF_TAPS) {
                val x = center - k
                val w = 0.5 + 0.5 * cos(PI * x / HALF_TAPS)
                if (w <= 0.0 || x <= -HALF_TAPS || x >= HALF_TAPS) continue
                val s = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                val coef = s * w
                norm += coef
                if (k in input.indices) acc += input[k] * coef
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }
}
