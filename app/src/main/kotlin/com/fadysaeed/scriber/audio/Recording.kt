package com.fadysaeed.scriber.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fadysaeed.scriber.MainActivity
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.core.StreamResampler
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.Audio
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault
import com.fadysaeed.scriber.work.Jobs
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

data class LiveLine(val atMs: Long, val text: String)

data class RecordingState(
    val sessionId: String? = null,
    val mode: SessionMode = SessionMode.CONVERSATION,
    val active: Boolean = false,
    val paused: Boolean = false,
    val finishing: Boolean = false,
    val recordedMs: Long = 0,
    /** Recent loudness, 0..1, newest last. */
    val levels: List<Float> = emptyList(),
    val live: List<LiveLine> = emptyList(),
    val liveNote: String? = null,
    val problem: String? = null,
)

/**
 * Records from the microphone as a foreground service, so it keeps going with the screen locked
 * or another app open. Audio is saved every five seconds as an encrypted piece; if the app or
 * phone stops, at most the last few seconds are lost and the rest is rescued on the next start.
 * A live preview transcribes each phrase as it ends; the proper transcript is made afterwards
 * from the whole file.
 */
class RecordingService : Service() {

    private var thread: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var paused = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (thread != null) return START_NOT_STICKY
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, notification("Recording"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scriber:recording").apply { acquire(6 * 60 * 60 * 1000L) }
                thread = Thread({ record(id) }, "scriber-recorder").also { it.start() }
            }
            else -> if (thread == null) { stopSelf(); return START_NOT_STICKY }
        }
        when (intent?.action) {
            ACTION_PAUSE -> { paused = true; _state.value = _state.value.copy(paused = true); notify("Paused") }
            ACTION_RESUME -> { paused = false; _state.value = _state.value.copy(paused = false); notify("Recording") }
            ACTION_STOP -> stopping = true
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun record(id: String) {
        val vault = Vault.get(this)
        val store = SessionStore.get(vault)
        val session = store.session(id) ?: run { stopSelf(); return }
        val rate = session.sampleRate
        val preview = Preview(this, session.mode, rate)
        preview.start()
        var recorder: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            recorder.startRecording()
            val frame = ByteArray(rate / 10 * 2) // 100 ms
            val piece = ByteArrayOutputStream(rate * 2 * PIECE_SECONDS)
            var pieceIndex = store.chunks(id).size
            var recordedSamples = 0L
            val levels = ArrayDeque<Float>()
            while (!stopping) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) continue
                if (paused) continue
                piece.write(frame, 0, n)
                recordedSamples += n / 2
                val floats = Wav.shortsToFloats(frame, n)
                levels.addLast(minOf(1f, sqrt(floats.fold(0.0) { a, x -> a + x * x } / floats.size).toFloat() * 4f))
                while (levels.size > 48) levels.removeFirst()
                preview.offer(floats)
                if (piece.size() >= rate * 2 * PIECE_SECONDS) {
                    vault.write(store.chunkName(id, pieceIndex++)) { it.write(piece.toByteArray()) }
                    piece.reset()
                    store.update(id) { it.copy(durationMs = recordedSamples * 1000 / rate) }
                }
                _state.value = _state.value.copy(recordedMs = recordedSamples * 1000 / rate, levels = levels.toList(), live = preview.lines(), liveNote = preview.note)
            }
            if (piece.size() > 0) vault.write(store.chunkName(id, pieceIndex)) { it.write(piece.toByteArray()) }
        } catch (t: Throwable) {
            _state.value = _state.value.copy(problem = t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            preview.close()
        }
        _state.value = _state.value.copy(finishing = true)
        notify("Saving")
        runCatching {
            if (Assembler(vault, store).finishRecording(id, recovered = false)) Jobs.transcribe(this, id, store.settings().engineFor(session.mode))
        }.onFailure { e -> store.update(id) { it.copy(status = SessionStatus.FAILED, problem = "Could not save: ${e.message}") } }
        _state.value = RecordingState()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(text: String) =
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        // No patient detail ever goes in the notification: it shows on the lock screen.
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Scriber")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        super.onDestroy()
    }

    /** Transcribes each phrase as it ends, on its own thread, so recording never waits for it. */
    private class Preview(private val context: Context, private val mode: SessionMode, private val rate: Int) {
        private val queue = LinkedBlockingQueue<FloatArray>(600)
        private val out = java.util.Collections.synchronizedList(ArrayList<LiveLine>())
        @Volatile var note: String? = "Starting live preview"
        @Volatile private var running = true
        private var worker: Thread? = null

        fun start() { worker = Thread({ loop() }, "scriber-preview").also { it.start() } }
        fun offer(samples: FloatArray) { if (!queue.offer(samples)) note = "Preview is behind; the full transcript will still be complete" }
        fun lines(): List<LiveLine> = synchronized(out) { out.takeLast(40) }
        /** Stops the preview and waits for its engine to be released before the full transcript starts. */
        fun close() { running = false; worker?.interrupt(); runCatching { worker?.join(15_000) } }

        private fun loop() {
            val catalog = ModelCatalog.bundled()
            val store = ModelStore(context)
            val settings = SessionStore.get(Vault.get(context)).settings()
            val engine = settings.engineFor(mode)
            val asr = catalog.byRole(PackRole.ASR).firstOrNull { it.engine == engine && store.state(it) is PackState.Verified }
                ?: catalog.byRole(PackRole.ASR).firstOrNull { store.state(it) is PackState.Verified }
            val vadPack = catalog.byRole(PackRole.VAD).firstOrNull { store.state(it) is PackState.Verified }
            if (asr == null || vadPack == null) { note = "Live preview needs the speech models"; drain(); return }
            var recognizer: OfflineRecognizer? = null
            var vad: Vad? = null
            try {
                recognizer = Speech.recognizer(asr.engine!!, store.dir(asr), threads = 2)
                vad = Speech.vad(store.file(vadPack, vadPack.files.first()))
                note = null
                val resampler = if (rate != Speech.SAMPLE_RATE) StreamResampler(rate, Speech.SAMPLE_RATE) else null
                var pending = FloatArray(0)
                while (running) {
                    val block = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    pending += resampler?.feed(block) ?: block
                    var i = 0
                    while (i + 512 <= pending.size) {
                        vad.acceptWaveform(pending.copyOfRange(i, i + 512))
                        i += 512
                        while (!vad.empty()) {
                            val seg = vad.front(); vad.pop()
                            val text = Speech.transcribe(recognizer, Audio(seg.samples, Speech.SAMPLE_RATE))
                            if (text.isNotBlank()) out += LiveLine(seg.start * 1000 / Speech.SAMPLE_RATE, text)
                        }
                    }
                    pending = pending.copyOfRange(i, pending.size)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                note = "Live preview stopped: ${t.message}"
                drain()
            } finally {
                vad?.release()
                recognizer?.release()
            }
        }

        private fun drain() { while (running) { queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) } }
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ID = "id"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 51
        const val PIECE_SECONDS = 5

        private val _state = MutableStateFlow(RecordingState())
        val state: StateFlow<RecordingState> = _state

        fun canRecord(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Creates the session and starts recording. Returns its id. */
        fun start(context: Context, mode: SessionMode): String {
            val vault = Vault.get(context)
            val store = SessionStore.get(vault)
            val now = System.currentTimeMillis()
            val rate = if (store.settings().highQuality) 48_000 else 16_000
            val s = Session(Ids.new(), Ids.defaultTitle(mode, now), mode, SessionSource.RECORDED, now, SessionStatus.RECORDING, sampleRate = rate)
            store.save(s)
            _state.value = RecordingState(sessionId = s.id, mode = mode, active = true)
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, s.id))
            return s.id
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }

        /**
         * Rescues sessions left unfinished by a crash, a force stop or a flat battery:
         * everything up to the last saved piece becomes a normal recording.
         */
        fun recover(context: Context): Int {
            if (_state.value.active) return 0
            val vault = Vault.get(context)
            val store = SessionStore.get(vault)
            var n = 0
            for (s in store.sessions()) {
                if (s.source == SessionSource.RECORDED && (s.status == SessionStatus.RECORDING || s.status == SessionStatus.FINALIZING)) {
                    if (Assembler(vault, store).finishRecording(s.id, recovered = true)) {
                        Jobs.transcribe(context, s.id, store.settings().engineFor(s.mode)); n++
                    }
                }
            }
            return n
        }
    }
}
