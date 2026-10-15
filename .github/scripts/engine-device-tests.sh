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
if [ "${VM_FULL_GUEST:-0}" = 1 ]; then
    mapfile -t evidence_files < <(adb shell run-as com.aether.sandbox ls files/vm-android9 | tr -d '\r' | \
      grep -E '^(frame-([0-9]+|settings)\.png|console\.log|android-boot-report\.json)$' || true)
    if [ "${#evidence_files[@]}" -gt 0 ]; then
        adb exec-out run-as com.aether.sandbox tar -cf - -C files/vm-android9 "${evidence_files[@]}" \
          > build/engine-device-evidence/guest-evidence.tar || true
    fi
fi
# am instrument itself can return 0 even when JUnit failed. POSIX grep is available
# on the runner; ripgrep is not an assumed runtime dependency of this script.
if ! grep -Eq '^OK \([0-9]+ tests?\)' build/engine-device-evidence/instrumentation.txt; then
    adb logcat -d -b crash > build/engine-device-evidence/crash.log
    exit 1
fi
