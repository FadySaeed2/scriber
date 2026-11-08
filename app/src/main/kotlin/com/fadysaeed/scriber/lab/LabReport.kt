package com.fadysaeed.scriber.lab

import com.fadysaeed.scriber.core.Units
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val android: String,
    val cores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val thermal: String,
    val thermalHeadroom: Float?,
)

@Serializable
data class AsrSample(val file: String, val seconds: Double, val decodeMs: Long, val wer: Double, val heard: String, val reference: String)

@Serializable
data class AsrRun(
    val engine: String,
    val title: String,
    val threads: Int,
    val loadMs: Long,
    val audioSeconds: Double,
    val decodeMs: Long,
    val referenceWords: Int,
    val errors: Int,
    val samples: List<AsrSample>,
) {
    /** How many seconds of speech one second of work transcribes. */
    val speedFactor: Double get() = if (decodeMs == 0L) 0.0 else audioSeconds * 1000.0 / decodeMs
    val wer: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
}

@Serializable
data class VadRun(val audioSeconds: Double, val ms: Long, val speechSegments: Int)

@Serializable
data class DiarizationRun(val audioSeconds: Double, val loadMs: Long, val processMs: Long, val speakersFound: Int, val speakersExpected: Int, val turns: Int, val rawChanges: Int = -1)

@Serializable
data class LlmRun(
    val model: String,
    val backend: String,
    val loadMs: Long,
    val firstWordMs: Long,
    val pieces: Int,
    val piecesPerSecond: Double,
    val output: String,
    val note: String = "",
)

@Serializable
data class VaultRun(val ok: Boolean, val megabytes: Int, val writeMBps: Double, val readMBps: Double, val detail: String)

@Serializable
data class LabReport(
    val atMillis: Long,
    val appVersion: String,
    val device: DeviceSnapshot,
    val thermalAfter: String = "",
    val vault: VaultRun? = null,
    val asr: List<AsrRun> = emptyList(),
    val vad: VadRun? = null,
    val diarization: DiarizationRun? = null,
    val llm: LlmRun? = null,
    val problems: List<String> = emptyList(),
) {
    /** Plain text Fady can paste into a chat. It holds test recordings only, never patient data. */
    fun toText(): String = buildString {
        val f = { d: Double -> String.format(Locale.ROOT, "%.1f", d) }
        appendLine("Scriber lab report, ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(atMillis))}, app $appVersion")
        appendLine("Device: ${device.manufacturer} ${device.model}, ${device.soc}, Android ${device.android}, ${device.cores} cores")
        appendLine("Memory: ${Units.bytes(device.totalRamBytes)} total, ${Units.bytes(device.availableRamBytes)} free; storage free ${Units.bytes(device.freeStorageBytes)}")
        appendLine("Heat: ${device.thermal} before, $thermalAfter after")
        vault?.let { appendLine("Encryption: ${if (it.ok) "OK" else "FAILED"}, ${it.megabytes} MB, write ${f(it.writeMBps)} MB/s, read ${f(it.readMBps)} MB/s. ${it.detail}") }
        for (a in asr) {
            appendLine("${a.title}: word error ${f(a.wer * 100)}% (${a.errors}/${a.referenceWords}), ${f(a.speedFactor)}x real time, load ${a.loadMs} ms, ${a.threads} threads")
            for (s in a.samples) appendLine("  ${s.file} ${f(s.seconds)}s in ${s.decodeMs} ms, error ${f(s.wer * 100)}%: ${s.heard}")
        }
        vad?.let { appendLine("Voice activity: ${it.speechSegments} speech segments in ${f(it.audioSeconds)} s of audio, ${it.ms} ms") }
        diarization?.let { appendLine("Speaker turns: found ${it.speakersFound} of ${it.speakersExpected} voices, ${it.turns} speaker changes after cleaning (${it.rawChanges} before), ${f(it.audioSeconds)} s audio in ${it.processMs} ms (load ${it.loadMs} ms)") }
        llm?.let {
            appendLine("Language model ${it.model} on ${it.backend}: load ${it.loadMs} ms, first words after ${it.firstWordMs} ms, ${f(it.piecesPerSecond)} pieces/s over ${it.pieces} pieces. ${it.note}")
            appendLine("Output:"); appendLine(it.output)
        }
        for (p in problems) appendLine("Problem: $p")
    }
}
