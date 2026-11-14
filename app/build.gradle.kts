import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.polarka.wallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.polarka.wallet"
        minSdk = 31
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.1"
    }
    // The key that signs releases lives outside the project, on the owner's computer only (see make-signing-key.sh).
    // Without it a release cannot be built here, and that is intended. The file that names the key is opened only
    // when a release is being built: tests, lint and debug builds never touch it.
    val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    val signingFile = File(System.getProperty("user.home"), ".config/north-wallet/signing.properties")
    val signing = Properties().apply { if (wantsRelease && signingFile.exists()) signingFile.inputStream().use { load(it) } }
    signingConfigs {
        if (wantsRelease && signingFile.exists()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false          // the code that runs is the code that was tested; no shrinking yet
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true          // the screen tests run the real activity on the desktop (Robolectric)
            all {
                it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":node"))
    // libsecp256k1 for Android. The watch-only screens do not sign anything; it is here for the next stage.
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.24.0")

    // The newest AndroidX releases (Compose 1.12, lifecycle 2.11) need Android Gradle plugin 9.1 and API 37.
    // These are the last ones made for plugin 8.x and API 36, which is what this build uses.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // QR codes (pure Java, no camera code): drawing the receiving address now, reading codes later
    implementation("com.google.zxing:core:3.5.4")
    // the camera picture for reading QR codes (Google's CameraX); the code is found in it by zxing above
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}
