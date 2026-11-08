#!/bin/sh
set -eu
mkdir -p smoke-results
adb install -r smoke-apks/debug/app-debug.apk
adb install -r smoke-apks/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.wagent.test/dev.wagent.DeviceSmokeInstrumentation > smoke-results/instrumentation.txt
cat smoke-results/instrumentation.txt
adb logcat -d -s AndroidRuntime > smoke-results/runtime.txt
adb shell screencap -p /sdcard/w-agent-smoke.png
adb pull /sdcard/w-agent-smoke.png smoke-results/screen.png
if ! grep -q 'PASS: Android service' smoke-results/instrumentation.txt; then
  exit 1
fi
