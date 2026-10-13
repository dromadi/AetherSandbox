#!/usr/bin/env bash
set -u
if ! ./gradlew :app:connectedDebugAndroidTest --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process; then
    adb logcat -d -s AndroidRuntime AetherRuntime
    exit 1
fi
