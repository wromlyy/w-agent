#!/bin/sh
set -eu
mkdir -p smoke-results
# Prove the published 0.2 APK can be upgraded without uninstalling its private data.
curl -fL --retry 2 --max-time 90 https://github.com/wromlyy/w-agent/releases/download/v0.2.0/W-Agent.apk -o smoke-results/previous.apk
adb install -r smoke-results/previous.apk
adb shell run-as dev.wagent mkdir -p files
adb shell run-as dev.wagent touch files/upgrade-smoke
adb install -r smoke-apks/release/app-release.apk
adb install -r smoke-apks/androidTest/debug/app-debug-androidTest.apk
status=0
timeout 540s adb shell am instrument -w dev.wagent.test/dev.wagent.DeviceSmokeInstrumentation > smoke-results/instrumentation.txt || status=$?
cat smoke-results/instrumentation.txt
adb logcat -d -s AndroidRuntime > smoke-results/runtime.txt
adb shell screencap -p /sdcard/w-agent-smoke.png
adb pull /sdcard/w-agent-smoke.png smoke-results/screen.png
if [ "$status" -ne 0 ]; then exit "$status"; fi
if ! grep -q 'PASS: Android service' smoke-results/instrumentation.txt; then
  exit 1
fi
