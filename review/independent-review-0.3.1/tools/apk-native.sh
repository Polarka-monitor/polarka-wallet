#!/bin/sh
# Compares the native secp256k1 library inside the APK with the one in the pinned AAR of the Gradle cache.
. "$HOME/north-build/env.sh"
A="$HOME/north-build/audit-0.3.1"
W=/tmp/north-audit-apk
mkdir -p "$W/apk" "$W/aar"
cd "$W/apk" && unzip -o -q "$A/north-wallet-0.3.1.apk" 'lib/*' 'classes*.dex' 'META-INF/version-control-info.textproto' 'META-INF/com/android/build/gradle/app-metadata.properties' 'DebugProbesKt.bin'
AAR=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/fr.acinq.secp256k1/secp256k1-kmp-jni-android/0.24.0" -name '*.aar')
echo "AAR: $AAR"
shasum -a 256 "$AAR"
cd "$W/aar" && unzip -o -q "$AAR"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  a=$(shasum -a 256 "$W/apk/lib/$abi/libsecp256k1-jni.so" | cut -d' ' -f1)
  b=$(shasum -a 256 "$W/aar/jni/$abi/libsecp256k1-jni.so" | cut -d' ' -f1)
  sa=$(wc -c < "$W/apk/lib/$abi/libsecp256k1-jni.so"); sb=$(wc -c < "$W/aar/jni/$abi/libsecp256k1-jni.so")
  if [ "$a" = "$b" ]; then echo "$abi: IDENTICAL $a"; else echo "$abi: DIFFERENT apk=$a ($sa bytes) aar=$b ($sb bytes)"; fi
done
echo "--- version control info / app metadata in the APK:"
cat "$W/apk/META-INF/version-control-info.textproto"; echo
cat "$W/apk/META-INF/com/android/build/gradle/app-metadata.properties"; echo
