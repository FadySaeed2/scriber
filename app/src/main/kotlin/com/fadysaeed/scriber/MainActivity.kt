package com.fadysaeed.scriber

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fadysaeed.scriber.ui.Frame
import com.fadysaeed.scriber.ui.LabScreen
import com.fadysaeed.scriber.ui.LockScreen
import com.fadysaeed.scriber.ui.ModelsScreen
import com.fadysaeed.scriber.ui.ScriberTheme
import com.fadysaeed.scriber.ui.SecurityScreen
import com.fadysaeed.scriber.ui.Tab

/**
 * One activity. It never shows anything until the phone's owner proves who they are, blocks
 * screenshots and the recent-apps preview, and locks again after two minutes in the background.
 */
class MainActivity : FragmentActivity() {
    private val vm: ScriberViewModel by viewModels()
    private var unlocked by mutableStateOf(false)
    private var lockProblem by mutableStateOf<String?>(null)
    private var backgroundedAt = 0L
    private var prompting = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        setContent {
            ScriberTheme {
                if (!unlocked) {
                    LockScreen(lockProblem) { authenticate("Unlock Scriber") { unlocked = true } }
                } else {
                    var tab by rememberSaveable { mutableStateOf(Tab.Models) }
                    Frame(tab, { tab = it; vm.refreshAsync() }) {
                        when (tab) {
                            Tab.Models -> ModelsScreen(
                                ui = vm.models.collectAsState().value,
                                onDownload = ::download,
                                onCancel = vm::cancelDownload,
                                onVerify = { vm.verifyAll() },
                                onChooseLlm = { vm.chooseLlm(it) },
                                onWifiOnly = { vm.setWifiOnly(it) },
                                onDismissMessage = vm::dismissMessage,
                            )
                            Tab.Lab -> LabScreen(vm.lab.collectAsState().value, onRun = vm::runLab, onCopy = ::copy)
                            Tab.Security -> SecurityScreen(
                                vm.security.collectAsState().value,
                                onUnlock = { authenticate("Allow model downloads") { vm.unlockDownloads() } },
                                onLock = { vm.lockDownloads() },
                                onCheckVault = { vm.checkVault() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS) unlocked = false
        if (!unlocked) authenticate("Unlock Scriber") { unlocked = true }
        vm.refreshAsync()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun download() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startDownload()
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Scriber lab report", text))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun authenticate(title: String, onSuccess: () -> Unit) {
        if (prompting) return
        val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        when (BiometricManager.from(this).canAuthenticate(allowed)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                lockProblem = "Set a screen lock on this phone first. Scriber keeps patient recordings and will not open without one."
                return
            }
            else -> {
                lockProblem = "This phone cannot confirm your identity right now."
                return
            }
        }
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                lockProblem = null
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED) lockProblem = errString.toString()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    private companion object { const val RELOCK_AFTER_MS = 120_000L }
}
