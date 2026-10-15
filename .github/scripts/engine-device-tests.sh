#!/usr/bin/env bash
set -euo pipefail
APK=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r "$APK"
adb install -r "$TEST"
if [ "${VM_FULL_GUEST:-0}" = 1 ]; then
    adb shell run-as com.aether.sandbox mkdir -p files/vm-android9
    for name in guest.iso kernel initrd.img; do
        adb shell run-as com.aether.sandbox sh -c "'cat > files/vm-android9/$name'" < "build/android9/$name"
    done
fi
mkdir -p build/engine-device-evidence
adb shell am instrument -w -r -e requireVmEngine true -e class com.aether.sandbox.VmEngineTest \
    com.aether.sandbox.test/androidx.test.runner.AndroidJUnitRunner | tee build/engine-device-evidence/instrumentation.txt
# am instrument itself can return 0 even when JUnit failed.
if ! rg -q '^OK \(' build/engine-device-evidence/instrumentation.txt; then
    adb logcat -d -b crash > build/engine-device-evidence/crash.log
    exit 1
fi
if [ "${VM_FULL_GUEST:-0}" = 1 ]; then
    adb exec-out run-as com.aether.sandbox tar -cf - -C files/vm-android9 \
      console.log android-boot-report.json $(for i in $(seq 0 19); do printf 'frame-%s.png ' "$i"; done) \
      > build/engine-device-evidence/guest-evidence.tar || true
fi
