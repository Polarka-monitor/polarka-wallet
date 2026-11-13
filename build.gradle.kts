// Versions of the build plugins, in one place. The modules apply them without a version.
plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.android.application") version "8.13.2" apply false
}
