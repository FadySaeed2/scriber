package com.fadysaeed.scriber

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.export.ExportKind
import com.fadysaeed.scriber.export.Exporter
import com.fadysaeed.scriber.ui.Frame
import com.fadysaeed.scriber.ui.LabScreen
import com.fadysaeed.scriber.ui.LockScreen
import com.fadysaeed.scriber.ui.ModelsScreen
import com.fadysaeed.scriber.ui.RecordingScreen
import com.fadysaeed.scriber.ui.ScriberTheme
import com.fadysaeed.scriber.ui.ReviewActions
import com.fadysaeed.scriber.ui.SessionActions
import com.fadysaeed.scriber.ui.SessionScreen
import com.fadysaeed.scriber.ui.SessionsScreen
import com.fadysaeed.scriber.ui.SettingsScreen
import com.fadysaeed.scriber.ui.Tab
import com.fadysaeed.scriber.ui.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One activity. It shows nothing until the phone's owner proves who they are, blocks screenshots
 * and the recent-apps preview, and locks again after two minutes in the background.
 */
class MainActivity : FragmentActivity() {
    private val vm: ScriberViewModel by viewModels()
    private var unlocked by mutableStateOf(false)
    private var lockProblem by mutableStateOf<String?>(null)
    private var backgroundedAt = 0L
    private var prompting = false

    private var tab by mutableStateOf(Tab.Sessions)
    private var mode by mutableStateOf(SessionMode.CONVERSATION)
    private var openSession by mutableStateOf<String?>(null)
    private var showRecording by mutableStateOf(false)
    private var pendingShare: Uri? = null
    private var pendingExport: Pair<String, ExportKind>? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) record() else vm.toastMicDenied() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDownload() }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importUri(it) } }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> uri?.let { finishExport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            ScriberTheme {
                Box(Modifier.fillMaxSize()) {
                    if (!unlocked) LockScreen(lockProblem, vm.settings.collectAsState().value.crash != null,
                        { authenticate("Copy error details") { vm.settings.value.crash?.let(::copy) } }) { authenticate("Unlock Scriber") { unlocked = true; consumeShare() } }
                    else Content()
                    vm.toast.collectAsState().value?.let { Toast(it, vm::dismissToast) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        val rec = vm.sessions.collectAsState().value.recording
        val sessionId = openSession
        when {
            showRecording && rec.active -> {
                BackHandler { showRecording = false }
                RecordingScreen(rec, { showRecording = false }, vm::pauseRecording, vm::resumeRecording, vm::stopRecording)
            }
            sessionId != null -> {
                BackHandler { close() }
                SessionScreen(vm.session.collectAsState().value, SessionActions(
                    onBack = ::close,
                    onSelectRun = vm::selectRun,
                    onReadable = vm::setReadable,
                    onPlay = vm::play,
                    onStopPlaying = vm::stopPlaying,
                    onTranscribeAgain = { vm.transcribeAgain(sessionId, it) },
                    onRename = { vm.rename(sessionId, it) },
                    onExport = { kind, share -> export(sessionId, kind, share) },
                    onDeleteAudio = { vm.deleteAudio(sessionId) },
                    onDeleteSession = { vm.deleteSession(sessionId); close() },
                    review = ReviewActions(
                        onAccept = vm::acceptDifference,
                        onKeep = { vm.keepDifference(it) },
                        onAcceptMedicine = vm::acceptMedicine,
                        onAcceptAllMedicines = { vm.acceptAllMedicines() },
                        onEdit = { from, to, segment, text, add -> vm.editWords(from, to, segment, text, add) },
                        onUndo = { vm.undoEdit() },
                        onShowEdited = vm::setShowEdited,
                        onReference = { vm.markReference(it) },
                        onSecondOpinion = { vm.secondOpinion(sessionId) },
                        onPlayRange = vm::playRange,
                        onFindSpeakers = { vm.findSpeakers(sessionId, it) },
                        onRenameSpeaker = { sp, name -> vm.renameSpeaker(sp, name) },
                        onMoveSpeaker = { from, to, sp -> vm.moveToSpeaker(from, to, sp) },
                    ),
                ))
            }
            else -> Frame(tab, { tab = it; vm.refreshAsync() }) {
                when (tab) {
                    Tab.Sessions -> SessionsScreen(vm.sessions.collectAsState().value, mode, { mode = it }, ::askRecord,
                        { pickAudio.launch(arrayOf("audio/*", "video/*")) }, { showRecording = true }, ::open)
                    Tab.Models -> ModelsScreen(vm.modelsUi.collectAsState().value, ::download, vm::cancelDownload, { vm.verifyAll() },
                        { vm.chooseLlm(it) }, { vm.setWifiOnly(it) }, vm::dismissMessage)
                    Tab.Lab -> LabScreen(vm.lab.collectAsState().value, vm::runLab, ::copy)
                    Tab.Settings -> SettingsScreen(vm.settings.collectAsState().value, { vm.updateSettings(it) },
                        { authenticate("Allow model downloads") { vm.unlockDownloads() } }, { vm.lockDownloads() }, { vm.checkVault() },
                        { vm.settings.value.crash?.let(::copy) }, { vm.clearCrash() })
                }
            }
        }
    }

    private fun open(id: String) { openSession = id; vm.open(id) }
    private fun close() { openSession = null; vm.open(null) }

    private fun askRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun record() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        vm.startRecording(mode)
        showRecording = true
    }

    private fun importUri(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.import(uri, mode) { open(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        if (unlocked) consumeShare()
    }

    /** Audio or video shared into Scriber from another app is imported once the app is unlocked. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        pendingShare = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun consumeShare() {
        val uri = pendingShare ?: return
        pendingShare = null
        vm.import(uri, mode) { open(it) }
    }

    private fun export(sessionId: String, kind: ExportKind, share: Boolean) {
        lifecycleScope.launch {
            val m = withContext(Dispatchers.IO) { vm.exportMaterial() } ?: return@launch
            if (share) {
                runCatching { withContext(Dispatchers.IO) { Exporter.shareIntent(this@MainActivity, m, kind) } }
                    .onSuccess { startActivity(it) }.onFailure { vm.toastError(it) }
            } else {
                pendingExport = sessionId to kind
                createDoc.launch(Exporter.fileName(m, kind))
            }
        }
    }

    private fun finishExport(uri: Uri) {
        val kind = pendingExport?.second ?: return
        pendingExport = null
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { Exporter.saveTo(this@MainActivity, uri, vm.exportMaterial() ?: error("Nothing to export"), kind) }
            }.onSuccess { vm.toastSaved() }.onFailure { vm.toastError(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) unlocked = false
        if (!unlocked) authenticate("Unlock Scriber") { unlocked = true; consumeShare() }
        vm.refreshAsync()
        vm.resumeJobs()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun download() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.startDownload()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Scriber", text))
        vm.toastCopied()
    }

    private fun authenticate(title: String, onSuccess: () -> Unit) {
        if (prompting) return
        val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        when (BiometricManager.from(this).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> { lockProblem = "Set a screen lock on this phone first. Scriber keeps patient recordings and will not open without one."; return }
            else -> { lockProblem = "This phone cannot confirm your identity right now."; return }
        }
        prompting = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; lockProblem = null; onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) lockProblem = errString.toString()
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(allowed).build())
    }

    private companion object { const val RELOCK_AFTER_MS = 120_000L }
}
