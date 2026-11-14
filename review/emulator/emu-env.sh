# The environment for the emulator: . ./emu-env.sh
# Everything stays in one folder (system images, virtual phones, the emulator's settings).
. "$HOME/north-build/env.sh"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$HOME/north-build/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
mkdir -p "$ANDROID_AVD_HOME"
