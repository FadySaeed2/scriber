package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/** Dictation: one voice reading a note aloud. Conversation: a consultation between people. */
@Serializable
enum class SessionMode { DICTATION, CONVERSATION }

@Serializable
enum class SessionSource { RECORDED, IMPORTED }

@Serializable
enum class SessionStatus {
    /** Audio is still coming in (or the app stopped while it was). */
    RECORDING,
    /** Joining the recorded pieces into one file. */
    FINALIZING,
    READY,
    /** Something went wrong; see [Session.problem]. */
    FAILED,
}

/** A file brought in from outside, kept exactly as it arrived. */
@Serializable
data class OriginalFile(val displayName: String, val mime: String, val size: Long, val sha256: String, val vaultName: String)

/**
 * One recording or import. Its audio is written once and never changed; transcription runs and
 * (later) notes sit on top of it in their own records.
 */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val mode: SessionMode,
    val source: SessionSource,
    val createdAtMillis: Long,
    val status: SessionStatus,
    /** Rate the audio was captured at (recordings) or decoded from (imports). */
    val sampleRate: Int = 16_000,
    val durationMs: Long = 0,
    /** The audio as recorded: 16-bit mono WAV in the vault. For imports, a 16 kHz decoded copy. */
    val audioName: String? = null,
    val audioSha256: String? = null,
    val audioBytes: Long = 0,
    /** 16 kHz mono WAV the speech models read. Same as [audioName] when recorded at 16 kHz. */
    val workAudioName: String? = null,
    val original: OriginalFile? = null,
    val recovered: Boolean = false,
    val audioDeletedAtMillis: Long? = null,
    val problem: String? = null,
    /** The run shown by default; the newest finished run when null. */
    val preferredRunId: String? = null,
) {
    val hasAudio: Boolean get() = audioName != null && audioDeletedAtMillis == null
}

@Serializable
data class Token(val text: String, val startMs: Long)

@Serializable
data class Segment(val startMs: Long, val endMs: Long, val text: String, val tokens: List<Token> = emptyList())

@Serializable
enum class RunStatus { RUNNING, DONE, FAILED }

/**
 * One transcription of one session's audio by one engine with one set of settings. Once DONE, it
 * is sealed: [seal] is the SHA-256 of everything else in the run, so any later change is visible,
 * and the app never writes a sealed run again.
 */
@Serializable
data class TranscriptRun(
    val id: String,
    val sessionId: String,
    val engine: String,
    val engineTitle: String,
    /** SHA-256 of the model file that did the work, from the catalogue. */
    val modelSha256: String,
    val settings: Map<String, String>,
    val audioSha256: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val status: RunStatus,
    val segments: List<Segment> = emptyList(),
    val problem: String? = null,
    val seal: String? = null,
) {
    val text: String get() = segments.joinToString(" ") { it.text }.trim()
    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** How long recordings are kept. Transcripts are never deleted by this; only audio. */
@Serializable
data class Retention(val keepForever: Boolean = true, val deleteAfterDays: Int = 30) {
    fun audioExpired(createdAtMillis: Long, nowMillis: Long): Boolean =
        !keepForever && nowMillis - createdAtMillis >= deleteAfterDays * 86_400_000L
}
