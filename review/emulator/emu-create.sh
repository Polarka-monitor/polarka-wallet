#!/bin/zsh
# Makes three virtual phones (Android 12, 14, 16). It starts nothing.
. "$HOME/north-build/emu/emu-env.sh"
ls "$ANDROID_HOME/cmdline-tools"
for api in 31 34 36; do
  name="polarka-api$api"
  if [ -d "$ANDROID_AVD_HOME/$name.avd" ]; then echo "$name exists"; continue; fi
  echo no | avdmanager create avd -n "$name" -k "system-images;android-$api;default;arm64-v8a" -d pixel_6 2>&1 | tail -3
done
avdmanager list avd 2>&1 | grep -E 'Name|Based|Error' 
ls -la "$ANDROID_AVD_HOME"
ls -d "$HOME/.android" 2>/dev/null && echo "home .android exists" || echo "no ~/.android"
