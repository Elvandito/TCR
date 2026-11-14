#!/usr/bin/env bash
# Runs on the GitHub Actions emulator runner. The emulator action executes each
# line of its `script` input in a separate shell, so all connected checks live here.
set -u

fail() {
  echo "APP_CHECK_FAILED: $*"
  exit 1
}

capture_evidence() {
  adb shell ps -A > ps.txt 2>&1 || true
  adb shell dumpsys activity activities > activity.txt 2>&1 || true
  adb shell dumpsys window displays > windows.txt 2>&1 || true
  adb logcat -d -v threadtime > logcat.txt 2>&1 || true
  adb logcat -d -b crash -v threadtime > crash.txt 2>&1 || true
  adb exec-out screencap -p > app-screen.png 2>&1 || true
}

trap capture_evidence EXIT

echo "Installing APK"
adb install -r apk/app-debug.apk || fail "adb install returned an error"

adb shell pm list packages > packages.txt 2>&1 || true
grep -q '^package:app\.gic$' packages.txt || fail "app.gic is not installed"

adb logcat -c || true
echo "Launching app.gic/.MainActivity"
adb shell am start -W -n app.gic/.MainActivity || fail "activity manager rejected the launch"
sleep 10
capture_evidence

pid="$(adb shell pidof app.gic 2>/dev/null | tr -d '\r' || true)"
if [ -z "$pid" ]; then
  pid="$(adb shell ps -A | tr -d '\r' | awk '$9 == "app.gic" { print $2; exit }')"
fi
echo "pid=${pid:-<none>}"

top_line="$(tr -d '\r' < activity.txt | grep -m1 'topResumedActivity=' || true)"
echo "top=${top_line:-<none>}"

[ -n "$pid" ] || fail "app.gic process is not running after launch"
echo "$top_line" | grep -q 'app\.gic/\.MainActivity' || fail "app.gic is not the top resumed activity"

# A screenshot that cannot be decoded would have been written as an adb error text.
[ -s app-screen.png ] || fail "screenshot was not captured"
file app-screen.png | grep -q 'PNG image data' || fail "screenshot is not a PNG"

echo "APP_CHECK_OK pid=$pid"
