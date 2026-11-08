import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.fadysaeed.scriber.fetch"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    implementation(project(":vault"))
    api("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
