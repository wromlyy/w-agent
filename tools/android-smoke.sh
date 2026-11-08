#!/bin/sh
set -eu
mkdir -p smoke-results
timeout 60s adb install -r smoke-apks/release/app-release.apk
timeout 60s adb install -r smoke-apks/androidTest/debug/app-debug-androidTest.apk
timeout 560s adb logcat -v threadtime > smoke-results/live-logcat.txt 2>&1 &
smoke_log_pid=$!
trap 'kill "$smoke_log_pid" 2>/dev/null || true' EXIT
status=0
timeout 540s adb shell am instrument -w dev.wagent.test/dev.wagent.DeviceSmokeInstrumentation > smoke-results/instrumentation.txt || status=$?
cat smoke-results/instrumentation.txt
timeout 15s adb logcat -d -s AndroidRuntime > smoke-results/runtime.txt 2>&1 || true
if timeout 15s adb shell screencap -p /sdcard/w-agent-smoke.png; then
  timeout 15s adb pull /sdcard/w-agent-smoke.png smoke-results/screen.png || true
fi
if [ "$status" -ne 0 ]; then exit "$status"; fi
if ! grep -q 'PASS: Android service' smoke-results/instrumentation.txt; then
  exit 1
fi
