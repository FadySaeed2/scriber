package com.fadysaeed.scriber.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

data class PlayerState(val name: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

/**
 * Plays a WAV straight from the encrypted vault, decrypting as it goes, and can start at any
 * moment (tap a line of the transcript to hear it). Nothing decrypted is written to disk.
 */
class Player(private val vault: Vault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    fun play(name: String, fromMs: Long, untilMs: Long? = null) {
        stop()
        job = scope.launch {
            var track: AudioTrack? = null
            try {
                vault.openSeekable(name).use { ch ->
                    val head = ByteArray(Wav.HEADER_BYTES * 4)
                    ch.position(0); ch.read(ByteBuffer.wrap(head))
                    val stream = head.inputStream()
                    val info = Wav.readHeader(stream)
                    val dataStart = (head.size - stream.available()).toLong()
                    val bytesPerMs = info.sampleRate * 2 / 1000.0
                    val durationMs = (info.dataBytes / bytesPerMs).toLong()
                    var pos = (fromMs.coerceIn(0, durationMs) * bytesPerMs).toLong() and 1L.inv()
                    ch.position(dataStart + pos)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(info.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(info.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), info.sampleRate))
                        .build()
                    track!!.play()
                    val buf = ByteBuffer.allocate(info.sampleRate / 5 * 2)
                    _state.value = PlayerState(name, true, (pos / bytesPerMs).toLong(), durationMs)
                    val endBytes = untilMs?.let { minOf(info.dataBytes, (it * bytesPerMs).toLong()) } ?: info.dataBytes
                    while (isActive && pos < endBytes) {
                        buf.clear()
                        val n = ch.read(buf)
                        if (n <= 0) break
                        track!!.write(buf.array(), 0, n)
                        pos += n
                        _state.value = _state.value.copy(positionMs = (pos / bytesPerMs).toLong())
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { track?.stop() }
                track?.release()
                _state.value = _state.value.copy(playing = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(playing = false)
    }
}
