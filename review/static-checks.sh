#!/bin/sh
# static-checks.sh: what can be read off the sources and off the built release without running the app.
# Usage (from the project folder, after "gradle :app:assemblePublicRelease" or ":app:assemblePersonalRelease"):
#     sh review/static-checks.sh OUTPUT_DIR [public|personal] [APK]
# The edition is public when it is not named. APK names another package of that edition than the one just built.
set -eu
OUT=${1:?output folder}
EDITION=${2:-public}
case "$EDITION" in public|personal) ;; *) echo "the edition is public or personal" >&2; exit 2;; esac
mkdir -p "$OUT"
APK=${3:-app/build/outputs/apk/$EDITION/release/app-$EDITION-release.apk}
BT="$ANDROID_HOME/build-tools/36.0.0"
MAIN="app/src/main app/src/$EDITION core/src/main node/src/main"

{
    echo "== 1. Lines in the wallet's own code that print or log (expected: none)"
    grep -rnE '(^|[^A-Za-z])(Log\.[a-z]+\(|println\(|print\(|printStackTrace|System\.(out|err))' $MAIN --include=*.kt || echo "none"
    echo
    echo "== 2. Internet addresses written into the wallet's own code of the $EDITION edition (expected: the two servers the first screen offers, the public Northbound server at its author's request and Polarka's own; polarka.org under More; in the personal edition alone the faucet. No node: a node comes from the user. Beside them the bare beginning of an address is listed, in a text and where a typed address is completed)"
    grep -rnoE 'https?://[A-Za-z0-9._~:/?#@!$&*+,;=%-]+' $MAIN --include=*.kt --include=*.xml | grep -v 'schemas.android.com' || echo "none"
    echo
    echo "== 3. Places where the clipboard is written (expected: addresses, transaction ids and the public descriptor only)"
    grep -rn 'copyToClipboard(' app/src/main --include=*.kt | grep -v 'fun copyToClipboard' | sed 's/^\([^:]*:[0-9]*\):.*copyToClipboard(context, \("[^"]*"\).*/\1 label \2/'
} > "$OUT/01-sources.txt"

{
    echo "== Release package: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
    "$BT/aapt2" dump badging "$APK" | grep -E "^package|sdkVersion|application-debuggable|native-code"
    echo
    echo "== Permissions asked for"
    "$BT/aapt2" dump permissions "$APK"
    echo
    echo "== Application flags (debuggable must be absent)"
    "$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml | grep -E "debuggable|allowBackup|fullBackupContent|dataExtractionRules|usesCleartextTraffic|extractNativeLibs" | sed 's/^ *//'
    echo
    echo "== Signature"
    "$BT/apksigner" verify --verbose --print-certs "$APK" 2>/dev/null | grep -E "Verified using v[23]|certificate DN|certificate SHA-256"
} > "$OUT/02-package.txt"

"$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml | python3 -c '
import re, sys
kind = name = None
rows = []
cur = None
for line in sys.stdin:
    m = re.match(r"\s*E: (activity|activity-alias|service|receiver|provider) ", line)
    if m:
        cur = {"kind": m.group(1), "name": "?", "exported": "not set", "permission": "-"}
        rows.append(cur)
        continue
    if re.match(r"\s*E: ", line) and not re.match(r"\s*E: (intent-filter|action|category|meta-data|data)", line):
        cur = None
    if cur is None:
        continue
    a = re.match(r"\s*A: .*?:(name|exported|permission)\(0x[0-9a-f]+\)=(.*)", line)
    if a and (a.group(1) != "name" or cur["name"] == "?"):
        v = a.group(2).strip()
        v = re.sub(r"\s*\(Raw: .*", "", v).strip("\"")
        cur[a.group(1)] = v
print("== Components of the release package (who can start what)")
for r in rows:
    print("%-9s exported=%-7s permission=%-45s %s" % (r["kind"], r["exported"], r["permission"], r["name"]))
' > "$OUT/03-components.txt"

{
    echo "== Libraries in the release (group:name:version)"
    gradle --no-daemon -q :app:dependencies --configuration ${EDITION}ReleaseRuntimeClasspath 2>/dev/null | grep -oE '[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.+-]*( -> [0-9][A-Za-z0-9_.+-]*)?' | sed -E 's/:[^:]+ -> /:/' | sort -u > "$OUT/04-libraries.list"
    wc -l < "$OUT/04-libraries.list" | sed 's/^ */count: /'
    echo "by publisher:"
    cut -d: -f1 "$OUT/04-libraries.list" | sed -E 's/^(androidx)\..*/\1/; s/^(org\.jetbrains)\..*/\1/' | sort | uniq -c | sort -rn
    echo
    echo "== Advertising, analytics, crash reporting or Google Play services among them (expected: none)"
    grep -iE 'gms|firebase|play-services|analytics|crashlytics|sentry|bugsnag|facebook|appcenter|datatransport' "$OUT/04-libraries.list" || echo "none"
} > "$OUT/04-libraries.txt"

{
    echo "== Internet hosts that appear as text anywhere in the release's code (libraries included)"
    for d in $(unzip -Z1 "$APK" | grep -E '^classes[0-9]*\.dex$'); do unzip -p "$APK" "$d"; done | strings -n 8 | grep -oE 'https?://[A-Za-z0-9.-]+' | sort | uniq -c | sort -rn
} > "$OUT/05-hosts-in-code.txt"
# The two editions are one app to Android (one id, one key), so a package in the wrong place would install without
# a word. What the package is, is read from its code: the flavour and the default of the fixed address that the
# compiler put into BuildConfig, and whether the faucet's classes, address or texts are in it at all.
{
    echo "== Which edition $APK is, read from its code"
    tmp=$(mktemp -d)
    unzip -q -o "$APK" 'classes*.dex' resources.arsc -d "$tmp"
    for d in "$tmp"/classes*.dex; do "$BT/dexdump" -l plain "$d" 2>/dev/null; done > "$tmp/dump.txt"
    python3 -I - "$EDITION" "$tmp" <<'PY'
import glob, os, re, sys
edition, tmp = sys.argv[1], sys.argv[2]
dump = open(os.path.join(tmp, "dump.txt"), encoding="utf-8", errors="replace").read()
block = re.search(r"Class descriptor\s*: 'Lorg/polarka/wallet/BuildConfig;'(.*?)(?=\nClass #|\Z)", dump, re.S)
fields = {}
if block:
    for name, value in re.findall(r"name\s*: '([A-Z_]+)'.*?value\s*: ([^\n]*)", block.group(1), re.S):
        fields.setdefault(name, value.strip())
dex = b"".join(open(f, "rb").read() for f in sorted(glob.glob(os.path.join(tmp, "classes*.dex"))))
arsc = open(os.path.join(tmp, "resources.arsc"), "rb").read()
found = {
    "class Faucet": b"Lorg/polarka/wallet/Faucet;" in dex,
    "class FaucetModel": b"Lorg/polarka/wallet/FaucetModel;" in dex,
    "address faucet.polarka.org": b"faucet.polarka.org" in dex,
    "text faucet_go": b"faucet_go" in arsc,
}
print("BuildConfig.FLAVOR        %s" % fields.get("FLAVOR", "(not found)"))
print("BuildConfig.FIXED_ADDRESS %s" % fields.get("FIXED_ADDRESS", "(not found)"))
for what, there in found.items():
    print("%-27s %s" % (what, "in the package" if there else "not in the package"))
if edition == "public":
    right = fields.get("FLAVOR") == '"public"' and fields.get("FIXED_ADDRESS") == "false" and not any(found.values())
else:
    right = fields.get("FLAVOR") == '"personal"' and fields.get("FIXED_ADDRESS") == "true" and all(found.values())
print()
print(("%s edition: as it must be" % edition) if right else
      ("NOT the %s edition as it must be" % edition) + (": do not publish this file" if edition == "public" else ""))
PY
    rm -rf "$tmp"
} > "$OUT/06-edition.txt"
echo "static checks written to $OUT"
