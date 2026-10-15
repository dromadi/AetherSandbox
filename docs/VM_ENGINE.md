# Android guest engine — alpha.12

Work in progress. The final PR records measured CI results and screenshots. Do not infer boot success from the existence of these files.

## Engine

Source-built QEMU 11.1.2, commit `4fc49f46dc95d4a27de2509e7fceb2931e91faeb`, compiled on matching ARM64/x86_64 Alpine hosts with static musl/GLib dependencies. Linux ELF executables are packaged as `libaether_qemu_*.so` solely to obtain Android's read-only extracted native executable location. They are not JNI libraries and are never passed to System.loadLibrary. No downloaded code is executed from a writable home directory. Android can still reject Linux syscalls or executable mappings; instrumented acceptance must prove the combination works.

Engine build provenance is pinned by repository/workflow head in `tools/vm/engine-lock.json`. Artifact checksums and source commit are checked before staging. The package build includes two guest engines (ARM64 and x86_64) for each supported 64-bit host ABI. The base CI APK intentionally lacks these binaries; the deliverable engine APK comes from **Android guest appliance**, not the base Android build artifact.

## Guest scope

First guest: upstream **android-x86_64-9.0-r2.iso**, Android 9, using the PC board rather than pretending a generic ARM64 Linux kernel is Android. Import verifies the upstream published SHA-1 `1cc85b5ed7c830ff71aecf8405c7281a9c995aa0`. It stages the whole ISO read-only and extracts only bounded root kernel/initrd records. The image is not bundled with the host APK. SHA-1 is an upstream compatibility pin, not a general modern image-signing design.

The live guest is experimental: **temporary data, no configured networking, audio, camera or host file sharing**. Leaving the VM screen stops the process. No background-VM promise, no snapshot, no APK/account compatibility claim. Android 9 is an old compatibility milestone, not a current secure daily-use guest. ARM applications and modern minSdk requirements remain separate compatibility problems. No Play Integrity, banking or GMS certification claim.

## Display and lifecycle

QEMU exports RFB over a Unix socket in the host private cache directory, not a TCP server. The bounded RFB 3.8 client requests raw 32-bit RGB rectangles and desktop-size changes. A native View scales the framebuffer and translates touch coordinates; input is serialized. Keyboard text and Back/Enter are forwarded as guest key events. Guest display does not itself prove Android ready.

The session controller starts the packaged executable using an argument array, captures bounded console logs and closes the display/process on stop. A host ViewModel preserves the session during rotation. There is no shell execution on the host and no external control endpoint. Host App Runtime guests still share UID with the host, so they are not a separate security boundary from these private files.

## Evidence gates

1. Packaged ARM64-guest engine must boot the pinned minimal Linux fixture ten times inside an Android app process, with nonce, PID 1, exit and poweroff checks.
2. API 34 full guest lab pushes the original ISO and extracted boot files into the debug app's private fixture directory. The test calls the production import pipeline on the staged ISO, including checksum and bounded boot extraction; domain tests also cover invalid extents and duplicate records.
3. The Android guest runs for up to fifteen minutes, with guest framebuffer PNGs and serial logs archived. A serial-shell `getprop sys.boot_completed` probe is recorded separately; echoed commands are not counted as success. Missing boot completion fails the test even if a framebuffer is visible. After boot, the lab starts the guest Settings Activity and checks its resumed component and framebuffer.
4. Inspect actual guest screenshots before claiming launcher/setup UI. Physical ARM64 phone boot remains unverified until tested on a device. Android-emulator-on-Linux results do not prove vendor-device compatibility.

## Source and licensing artifacts

QEMU's configured source and relinking objects are retained in `vm-engine-source-arm64-v8a` and `vm-engine-source-x86_64` artifacts from the pinned engine build run. The appliance workflow collects Alpine dependency source archives, verified recipe hashes and patches in a separate source artifact. The repository contains the engine build/staging recipe, kernel fixture source pin and host integration. QEMU COPYING and build provenance are embedded in the APK assets. The guest ISO remains an upstream user-selected download. There is no repository-wide relicensing in this change.

## Next after successful boot

Test touch/keyboard and launch a guest app; add an explicitly provisioned persistent userdata disk with recovery checks; implement guest network and audio; evaluate a newer maintained Android guest/image pipeline. Advance these as distinct evidence gates, keeping the existing App Runtime regressions.

Sources: [QEMU release](https://www.qemu.org/download/), [Android-x86 release and hash](https://www.android-x86.org/releases/releasenote-9-0-r2.html), [Android executable restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission), [QEMU license](https://www.qemu.org/docs/master/about/license.html).
