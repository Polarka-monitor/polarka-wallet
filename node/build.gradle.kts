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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
