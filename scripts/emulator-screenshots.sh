#!/usr/bin/env bash
# Installs the debug APK on the running emulator, walks through the tools,
# flies a run and takes screenshots along the way. Used by the screenshots workflow.
set -x
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "screenshots/$1.png"; }
# Skip the one-time "Viewing full screen" hint, which would cover the app.
adb shell settings put secure immersive_mode_confirmations confirmed
# A 16:9 screen, the shape Google Play takes for phone screenshots.
adb shell wm size 1080x1920
adb shell pm grant com.skyalt.canopy android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant com.skyalt.canopy android.permission.POST_NOTIFICATIONS || true
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
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
# Whether the screen shows this text, from a UI dump.
has_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | grep -q "$1"
}
# The tools, in portrait.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c
adb shell am start -n com.skyalt.canopy/.MainActivity
shot 00-tools 8
tap_ui "Wingload Calculator" && shot 00a-wingload 2 && back
tap_ui "Repack Reminder" && shot 00b-repack 2 && back
tap_ui "Landing Pattern Assistant" && {
  shot 00c-landing 4
  adb shell input tap 540 1000
  shot 00d-landing-pattern 10
  back
}
tap_ui "Exit Spot" && {
  shot 00e-exit 4
  adb shell input tap 540 1000
  shot 00f-exit-ranges 10
  back
}
adb shell input swipe 540 1400 540 500 400
sleep 1
tap_ui "Cutaway Finder" && {
  shot 00g-cutaway 4
  tap_ui "Draw Cutaway Area" || echo "no draw button"
  # One closed stroke round the cutaway area.
  adb shell input motionevent DOWN 690 1000
  for a in $(seq 15 15 360); do
    read x y < <(python3 -c "import math; print(int(540 + 150 * math.cos(math.radians($a))), int(1000 + 150 * math.sin(math.radians($a))))")
    adb shell input motionevent MOVE "$x" "$y"
  done
  adb shell input motionevent UP 690 1000
  shot 00h-cutaway-drawn 10
  back
}
tap_ui "Settings" && shot 00i-settings 2 && tap_ui "Tools"
# The simulator, in landscape.
adb shell settings put system user_rotation 1
tap_ui "Canopy Simulator" || echo "no simulator row"
shot 01-settings 8
# Screen size, to tap relative positions.
read W H < <(adb shell wm size | tail -1 | sed -E 's/.* ([0-9]+)x([0-9]+).*/\2 \1/')
echo "landscape ${W}x${H}"
# The scene page's exit picture; four quick taps on it open the aircraft's markings and their side view.
shot 01a-exit-picture 2
adb shell "for i in 1 2 3 4; do input tap $((W * 3 / 4)) $((H / 2)) & sleep 0.15; done; wait"
sleep 4
if has_ui "pinch to size"; then
  shot 01b-markings 1
  back
else
  echo "no markings page"
fi
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
# Fly on to the ground, then replay the run with its profile.
shot 06b-landed 100
tap_ui "REPLAY" || echo "no replay button"
shot 06c-replay 6
tap_ui "EXIT REPLAY" || echo "no exit replay"
sleep 1
# The settings drawer over a drawn 3D view.
tap_ui "Settings" || echo "no settings button"
shot 06d-settings 3
adb shell input tap $((W / 5)) $((H / 2))
sleep 1
# Casting with the toggles, before motion capture.
adb shell settings put global overlay_display_devices 1280x720/240
sleep 3
tap_ui "Cast" || echo "no cast button"
shot 06e-casting 5
tap_ui "Stop casting" || echo "no stop casting"
adb shell settings put global overlay_display_devices null
sleep 2
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
# The dropzone picture: a test card taller than the screen, turned and off centre, with its settings
# (scripts/dropzone-card.py). On the start-point map, in the picture editor at every zoom and in the
# scene, the target rings must lie on the card's target mark and the runway outline on its painted runway.
# Whether the screen shows these words, whatever their case.
has_like() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | grep -qi "$1"
}
# Taps the element whose text or description is these words, whatever their case, or else holds them.
tap_like() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - "$1" <<'PY' > /tmp/xy || { echo "nothing like $1"; return 1; }
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
want = sys.argv[1].lower()
found = []
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    names = [m.group(1).lower() for m in (re.search(r' text="([^"]*)"', n), re.search(r'content-desc="([^"]*)"', n)) if m]
    if want in names:
        rank = 0
    elif any(want in name for name in names):
        rank = 1
    else:
        continue
    a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
    found.append((rank, (c - a) * (d - b), (a + c) // 2, (b + d) // 2))
if not found:
    sys.exit(1)
print(*min(found)[2:])
PY
  adb shell input tap $(cat /tmp/xy)
  sleep 1
}
# Drags the panel at this x up a step at a time until the words show; slow drags, so nothing is flung past.
find_down() {
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    has_like "$1" && return 0
    adb shell input swipe "$2" $((H * 7 / 10)) "$2" $((H * 3 / 10)) 1200
    sleep 1
  done
  has_like "$1"
}
# Puts a file into the debug build's own storage.
put() {
  adb push "$1" /data/local/tmp/put >/dev/null
  adb shell "run-as com.skyalt.canopy sh -c 'mkdir -p $(dirname "$2") && cp /data/local/tmp/put $2'" ||
    adb exec-in "run-as com.skyalt.canopy sh -c 'mkdir -p $(dirname "$2") && cat > $2'" < "$1"
  adb shell "run-as com.skyalt.canopy ls -l $2"
}
adb shell am force-stop com.skyalt.canopy
put scripts/dropzone-card.jpg files/CanopySimulator/dropzone.jpg
put scripts/dropzone-card-prefs.xml shared_prefs/canopy-simulator.xml
adb shell "run-as com.skyalt.canopy rm -f shared_prefs/canopy-simulator.xml.bak"
adb shell am start -n com.skyalt.canopy/.MainActivity
sleep 8
tap_like "Canopy Simulator"
sleep 6
# Free practice opens with the settings drawer: the start-point map first, then the picture editor at the drawer's end.
tap_like "Free practice"
sleep 4
if find_down "Start point" $((W - 400)) && tap_like "Start point"; then
  shot 13-dropzone-start-map 3
  tap_like "Done"
else
  echo "no start-point map"
fi
if find_down "Edit picture" $((W - 400)) && tap_like "Edit picture"; then
  shot 13a-dropzone-editor 3
  tap_like "Zoom out" && shot 13b-dropzone-editor-out 2
  tap_like "Zoom in" && tap_like "Zoom in" && shot 13c-dropzone-editor-in 2
  tap_like "Zoom out"
  tap_like "Set runway" && shot 13d-dropzone-runway 2
  tap_like "Set runway"
  find_down "Done" $((W - 300)) && tap_like "Done"
else
  echo "no picture editor"
fi
# The scene with the drawer closed: the chase view, then from above the jumper, who starts over the target.
adb shell input tap $((W / 5)) $((H / 2))
shot 13e-dropzone-scene 3
tap_like "Chase" && tap_like "Side" && tap_like "Front"
shot 13f-dropzone-top 3
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
adb logcat -d -s AndroidRuntime:E CanopyGL:E AircraftSide:E AircraftModel:E '*:F' > screenshots/logcat.txt || true
adb logcat -d > screenshots/logcat-full.txt || true
