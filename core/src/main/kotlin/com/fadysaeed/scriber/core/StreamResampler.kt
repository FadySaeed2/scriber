package com.fadysaeed.scriber.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * The same windowed-sinc conversion as [Resample.mono], fed in pieces, so an hour of 48 kHz audio
 * never has to sit in memory at once. Feeding a signal in any split gives exactly the samples
 * [Resample.mono] gives for the whole signal.
 */
class StreamResampler(private val from: Int, private val to: Int) {
    private val ratio = to.toDouble() / from
    private val cutoff = min(1.0, ratio) * 0.95
    private val half = 24
    private var buffer = FloatArray(0)
    private var bufferStart = 0L // absolute index of buffer[0]
    private var totalIn = 0L
    private var nextOut = 0L

    /** Adds samples and returns every output sample that no longer depends on future input. */
    fun feed(input: FloatArray): FloatArray {
        if (from == to) { totalIn += input.size; return input.copyOf() }
        buffer = buffer + input
        totalIn += input.size
        return produce(final = false)
    }

    /** Returns the remaining output once the input has ended. */
    fun finish(): FloatArray {
        if (from == to) return FloatArray(0)
        return produce(final = true)
    }

    private fun produce(final: Boolean): FloatArray {
        val outTotal = if (final) maxOf(1L, (totalIn * ratio).toLong()) else Long.MAX_VALUE
        val limit = if (final) outTotal else ((totalIn - half) * ratio).toLong() + 2
        var out = FloatArray(((limit - nextOut).coerceIn(0, 1L shl 26)).toInt())
        var n = 0
        while (nextOut < outTotal) {
            val center = nextOut / ratio
            val c = floor(center).toLong()
            if (!final && c + half >= totalIn) break
            var acc = 0.0
            var norm = 0.0
            for (k in c - half + 1..c + half) {
                val x = center - k
                val w = 0.5 + 0.5 * cos(PI * x / half)
                if (w <= 0.0 || x <= -half || x >= half) continue
                val s = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                val coef = s * w
                norm += coef
                if (k in 0 until totalIn) acc += buffer[(k - bufferStart).toInt()] * coef
            }
            if (n == out.size) out = out.copyOf(maxOf(16, out.size * 2))
            out[n++] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            nextOut++
        }
        // Drop input that no future output will need.
        val keepFrom = (floor(nextOut / ratio).toLong() - half).coerceAtLeast(bufferStart)
        if (keepFrom > bufferStart) {
            buffer = buffer.copyOfRange((keepFrom - bufferStart).toInt(), buffer.size)
            bufferStart = keepFrom
        }
        return out.copyOf(n)
    }
}
