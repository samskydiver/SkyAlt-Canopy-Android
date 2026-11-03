#!/usr/bin/env bash
# Installs the debug APK on the running emulator, flies a run and takes
# screenshots along the way. Used by the screenshots workflow.
set -x
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "screenshots/$1.png"; }
# Skip the one-time "Viewing full screen" hint, which would cover the app.
adb shell settings put secure immersive_mode_confirmations confirmed
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
adb shell input tap $((W / 2)) $((H / 2))
shot 03-flying 4
# Hold the right toggle half-way for a turn.
adb shell input swipe $((W - 60)) $((H / 2)) $((W - 60)) $((H * 55 / 100)) 3000
shot 04-turn 1
adb shell input keyevent KEYCODE_WAKEUP
shot 05-later 20
shot 06-later 30
# Taps the element whose text or description matches, from a UI dump.
tap_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - "$1" <<'PY' > /tmp/xy || return 1
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
want = sys.argv[1]
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    text = re.search(r' text="([^"]*)"', n).group(1)
    desc = re.search(r'content-desc="([^"]*)"', n).group(1)
    if want in (text, desc):
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((a + c) // 2, (b + d) // 2)
        sys.exit(0)
sys.exit(1)
PY
  adb shell input tap $(cat /tmp/xy)
}
# Motion capture: the drawer, the camera on, then the 3D view with the picture.
adb shell pm grant com.skyalt.canopy android.permission.CAMERA
adb shell input keyevent KEYCODE_WAKEUP
tap_ui "Motion capture" || echo "no motion capture button"
shot 07-mocap-drawer 2
tap_ui "ENABLE CAMERA" || echo "no camera button"
shot 08-mocap-on 5
adb shell input tap $((W / 5)) $((H / 2))
shot 09-mocap-hud 3
# Casting to a simulated second display.
adb shell settings put global overlay_display_devices 1280x720/240
sleep 3
tap_ui "Cast" || echo "no cast button"
shot 10-casting 5
adb shell settings put global overlay_display_devices null
shot 11-cast-disconnected 3
# The release build counts launches: the third one shows AltiX Pro.
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  adb install -r app/build/outputs/apk/release/app-release.apk
  for i in 1 2 3; do
    adb shell am force-stop com.skyalt.canopy
    adb shell am start -n com.skyalt.canopy/.MainActivity
    sleep 8
  done
  shot 12-paywall 2
fi
adb logcat -d -s AndroidRuntime:E CanopyGL:E '*:F' > screenshots/logcat.txt || true
adb logcat -d > screenshots/logcat-full.txt || true
