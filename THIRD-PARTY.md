# Libraries in Polarka Wallet and their licences

The app itself is under the MIT licence (`LICENSE`). It is built from the libraries below. The licence named
for each is the one the library declares in its own `.pom` file, read from the Gradle cache on 14 November 2026;
for two libraries the `.pom` inherits the declaration from a parent file, and the licence is the one their
projects publish (marked *).

## Chosen by this project

| Library | Version | Licence | What it does here |
|---|---|---|---|
| `fr.acinq.bitcoin:bitcoin-kmp` | 0.32.0 | Apache-2.0 | BIP32, BIP39, bech32, transactions, the BIP-341 signature hash |
| `fr.acinq.secp256k1:secp256k1-kmp` and `-jni-android` | 0.24.0 | Apache-2.0 | elliptic-curve signatures; it contains libsecp256k1 of the Bitcoin Core developers (MIT) as native code |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | Apache-2.0 | the language's own library |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | work off the main thread |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | Apache-2.0 | reading what the node answers |
| `androidx.compose` (BOM 2026.06.01: ui, material3, foundation, runtime, animation) | 1.11.4, material3 1.4.0 | Apache-2.0 | the screens |
| `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | the activity |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.10.0 | Apache-2.0 | the model that outlives the screen |
| `androidx.camera` (camera2, lifecycle, view, core) | 1.6.1 | Apache-2.0 (camera-core also names BSD-3-Clause) | the camera picture for reading QR codes |
| `com.google.zxing:core` | 3.5.4 | Apache-2.0 * | drawing and reading QR codes |

## Brought in by those

All under Apache-2.0 unless noted: the other `androidx` libraries (core, annotation, collection, savedstate,
lifecycle, emoji2, tracing, window, profileinstaller, startup, media3 and the older support libraries they still
name), `com.google.guava` (*), `com.google.dagger`, `com.google.errorprone:error_prone_annotations`,
`com.google.j2objc:j2objc-annotations`, `com.google.auto.value:auto-value-annotations`,
`com.google.code.findbugs:jsr305`, `org.jspecify:jspecify`, `org.jetbrains:annotations`,
`org.jetbrains.kotlinx:atomicfu`, `javax.inject` and `jakarta.inject`; and `org.checkerframework:checker-qual`
under the MIT licence.

The exact set with versions is what `gradle :app:dependencies --configuration releaseRuntimeClasspath` prints,
and every file of it is pinned by its SHA-256 in `gradle/verification-metadata.xml`.

The Apache licence, version 2.0: https://www.apache.org/licenses/LICENSE-2.0
