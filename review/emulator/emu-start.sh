#!/bin/zsh
# Starts a virtual phone without a window: emu-start.sh 34 5560
# Waits for the system to come up, 150 s at the most, and prints where it stands.
. "$HOME/north-build/emu/emu-env.sh"
api="$1"; port="${2:-5560}"
name="polarka-api$api"
log="$HOME/north-build/emu/emu-$api.log"
serial="emulator-$port"
if adb devices | grep -q "^$serial"; then echo "$serial already running"; else
  nohup emulator -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -netdelay none -netspeed full > "$log" 2>&1 &
  echo "started pid $!"
fi
for i in $(seq 1 50); do
  s=$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$s" = "1" ]; then echo "booted after about $((i*3)) s"; break; fi
  sleep 3
done
adb devices
adb -s "$serial" shell getprop ro.build.version.release 2>/dev/null
adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null
tail -5 "$log" | cut -c1-200
