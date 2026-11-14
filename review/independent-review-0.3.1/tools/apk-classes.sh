#!/bin/sh
# Compares the names of the wallet's own classes inside the APK with the classes compiled from the audit copy of the sources.
# Needs tools/apk-native.sh to have unpacked the APK into /tmp/north-audit-apk/apk and a finished unit-test build.
. "$HOME/north-build/env.sh"
A="$HOME/north-build/audit-0.3.1"
W=/tmp/north-audit-apk
BT="$ANDROID_HOME/build-tools/36.0.0"
: > "$W/apk-classes.txt"
for d in "$W"/apk/classes*.dex; do
  "$BT/dexdump" "$d" 2>/dev/null | grep "Class descriptor" | sed -E "s/.*'L(.*);'.*/\1/" >> "$W/apk-classes.txt"
done
grep -E '^(io/github/severka_monitor/northwatch|north/core|north/node)/' "$W/apk-classes.txt" | grep -v -E '/R(\$[a-z]+)?$' | sort -u > "$W/apk-own.txt"
cd "$A/north-wallet"
( for root in core/build/classes/kotlin/main node/build/classes/kotlin/main app/build/tmp/kotlin-classes/debug; do
    [ -d "$root" ] && (cd "$root" && find . -name '*.class' | sed -e 's|^\./||' -e 's|\.class$||')
  done ) | grep -v -E '/R(\$[a-z]+)?$' | sort -u > "$W/src-own.txt"
echo "own classes in the APK: $(wc -l < "$W/apk-own.txt"), compiled from the sources: $(wc -l < "$W/src-own.txt")"
echo "--- only in the APK:"; comm -23 "$W/apk-own.txt" "$W/src-own.txt" | head -40
echo "--- only in the sources:"; comm -13 "$W/apk-own.txt" "$W/src-own.txt" | head -40
echo "--- packages in the APK (top level, class counts):"
sed -E 's|^([^/]+/[^/]+)/.*|\1|' "$W/apk-classes.txt" | sort | uniq -c | sort -rn | head -40
