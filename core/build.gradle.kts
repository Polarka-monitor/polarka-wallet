import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

// Built with JDK 21. The library itself is compiled for Java 17, because the Android app uses it as it is.
// The tests stay on 21: the desktop build of libsecp256k1 they use is published for Java 21 and later.
kotlin {
    jvmToolchain(21)
}
tasks.named<KotlinCompile>("compileKotlin") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
tasks.named<JavaCompile>("compileJava") { options.release.set(17) }

dependencies {
    // Bitcoin primitives from ACINQ (used in the Phoenix wallet): BIP32, BIP39, bech32m, transactions, BIP-341 hashes.
    api("fr.acinq.bitcoin:bitcoin-kmp-jvm:0.32.0")
    // libsecp256k1 through JNI: the elliptic-curve code is the reference C library, not code written here.
    // The desktop build of it is needed for the tests only; the Android app brings secp256k1-kmp-jni-android.
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
