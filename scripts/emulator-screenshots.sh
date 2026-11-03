#!/usr/bin/env bash
# Installs the debug APK on the running emulator, flies a run and takes
# screenshots along the way. Used by the screenshots workflow.
set -x
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "screenshots/$1.png"; }
adb logcat -c
adb shell am start -n com.skyalt.canopy/.MainActivity
shot 01-settings 8
# Screen size, to tap relative positions.
read W H < <(adb shell wm size | sed -E 's/.* ([0-9]+)x([0-9]+).*/\2 \1/')
echo "landscape ${W}x${H}"
# Close the settings drawer by tapping the dimmed left side.
adb shell input tap $((W / 5)) $((H / 2))
shot 02-paused 2
# Start the run with the centre button.
adb shell input tap $((W * 45 / 100)) $((H / 2))
shot 03-flying 4
# Hold the right toggle half-way for a turn.
adb shell input swipe $((W - 60)) $((H / 2)) $((W - 60)) $((H * 55 / 100)) 3000
shot 04-turn 1
adb shell input keyevent KEYCODE_WAKEUP
shot 05-later 20
shot 06-later 30
adb shell am start -n com.skyalt.canopy/.MainActivity
adb logcat -d -s AndroidRuntime:E CanopyGL:E '*:F' > screenshots/logcat.txt || true
adb logcat -d > screenshots/logcat-full.txt || true
