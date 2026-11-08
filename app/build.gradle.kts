import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// CI decrypts the release key into a temporary file and passes its path and password here.
// The key never exists unencrypted in the repository.
val keystorePath: String? = System.getenv("SCRIBER_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("SCRIBER_SIGNING_PASSWORD")
val hasSigning = keystorePath != null && !keystorePassword.isNullOrEmpty() && file(keystorePath).exists()

android {
    namespace = "com.fadysaeed.scriber"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fadysaeed.scriber"
        minSdk = 31
        targetSdk = 36
        versionCode = ciRun
        versionName = "0.0.$ciRun"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "scriber"
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            // The phone is 64-bit ARM; one ABI keeps the APK small.
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            // Debug builds also run on the x86_64 emulator in CI.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Native libraries stay uncompressed so they load straight from the APK.
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(12))
}

dependencies {
    implementation(project(":core"))
    implementation(project(":vault"))
    implementation(project(":fetch"))

    // Speech: sherpa-onnx (Parakeet, MedASR, Silero VAD, speaker turns). Fetched and checked by
    // tools/fetch-deps.sh from the official release.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    // Language model runtime from Google AI Edge (Gemma 4 on the phone's GPU or CPU).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
