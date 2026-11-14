#!/bin/zsh
# Makes a running emulator trust the test authority as a system authority, until its next restart.
# Usage: trust_ca.sh emulator-5560
# The wallet trusts system authorities only (as every app does by default), and this is what lets the
# unchanged, release-signed package talk TLS to the test bench. Works on emulators without Google Play only.
. "$HOME/north-build/emu/emu-env.sh"
serial="$1"
ca="$HOME/north-build/emu/tls/ca.pem"
name="$(openssl x509 -inform PEM -subject_hash_old -in "$ca" -noout).0"
adb -s "$serial" root >/dev/null; sleep 2
adb -s "$serial" push "$ca" "/data/local/tmp/$name" >/dev/null
adb -s "$serial" shell "
set -e
rm -rf /data/local/tmp/ca-copy; mkdir -p -m 700 /data/local/tmp/ca-copy
if [ -d /apex/com.android.conscrypt/cacerts ]; then src=/apex/com.android.conscrypt/cacerts; else src=/system/etc/security/cacerts; fi
cp \$src/* /data/local/tmp/ca-copy/
if ! mount | grep -q 'tmpfs on /system/etc/security/cacerts'; then mount -t tmpfs tmpfs /system/etc/security/cacerts; fi
cp /data/local/tmp/ca-copy/* /system/etc/security/cacerts/
cp /data/local/tmp/$name /system/etc/security/cacerts/
chown root:root /system/etc/security/cacerts/*
chmod 644 /system/etc/security/cacerts/*
chcon u:object_r:system_file:s0 /system/etc/security/cacerts/*
if [ -d /apex/com.android.conscrypt/cacerts ]; then
  for z in \$(pidof zygote zygote64); do
    nsenter --mount=/proc/\$z/ns/mnt -- /system/bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts
    for p in \$(ps -o PID -P \$z | grep -v PID); do
      nsenter --mount=/proc/\$p/ns/mnt -- /system/bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts || true
    done
  done
fi
ls /system/etc/security/cacerts | wc -l
ls -la /system/etc/security/cacerts/$name
"
