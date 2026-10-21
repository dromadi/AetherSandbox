# Android guest engine — alpha.13

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

## Boot investigation — 2026-10-21

The user reaffirmed full Android-in-Android as the project goal and authorized continued VM work. This follow-up remains on the VM branch; it does not promote inherited App Runtime hooks or synthetic Camera2 interception into the QA product.

At alpha.12 commit 7788336 the archived final framebuffer shows the Android boot animation. Guest serial replies report Android 9 and live system_server/surfaceflinger PIDs, but sys.boot_completed is empty. This is partial userspace startup, not a finished boot or proof of a specific cause.

The follow-up bounds console reads by bytes instead of unbounded readLine(), cancels the drain before destroying the process, and handles shutdown IOExceptions within the child coroutine. Unexpected console errors terminate the VM session rather than escaping to the host's uncaught exception handler. The device gate now exercises two start/stop cycles before boot and captures read-only guest properties, processes, memory and recent logcat during the wait and on timeout. The boot-complete and Settings gates remain mandatory; diagnostics never set boot properties or bypass setup.

No new successful Android boot, persistent userdata or physical-device result is claimed by these source changes. Inspect the follow-up workflow results before installing or describing the milestone as complete.

## TCG first-boot profile — alpha.13

Run 39594972176 on 776d8b3 completed the start/stop regression without the prior unhandled read-interrupted host crash. Its guest still failed boot completion. The two serial snapshots show sustained `dex2oat` work on Chrome and Google Play Services, low-memory-killer activity, and changing zygote/system-server PIDs. This is measured pre-boot workload and process churn; lack of guest entropy was a preliminary hypothesis, not the demonstrated main cause.

`VmInitrd` preserves the imported `initrd.img` byte-for-byte and atomically creates `initrd-aether.img` by appending a compressed newc archive. It adds only `scripts/99-aether`. Android-x86's early init sources this directory after unpacking the Android ramdisk. The script appends a post-fs-data action to set first-boot and boot dexopt to `extract` before zygote startup. ART performs class verification when code is loaded; this does not select `assume-verified`. The tradeoff is more interpreter/verification work when an app is first opened instead of optimizing the entire bundled application set before the first UI. The original ISO is not modified or repackaged.

The QEMU command also supplies `virtio-rng-pci` backed by `rng-builtin`. Device/backend construction was checked using the exact packaged x86_64 engine on Linux; Android-host acceptance remains a separate CI gate.

The device report records verified stop cycles and a nonce-tagged readback of the first-boot compiler filter, in addition to boot completion and Settings. Diagnostics include guest memory, /data space and RNG state. Network, audio, persistence and physical ARM64 phone validation remain future gates.

Validation before CI: production Java compiles locally through the JDK compiler module; a JVM harness checks source preservation, repeated preparation, missing/empty/oversized inputs, old-target preservation and cleanup; an independent Python reader checks gzip/newc framing, entry modes and paths; shell syntax and diff checks pass. CI unit tests cover the archive and failure behavior. Full Android boot success is pending the alpha.13 device run.

Format reference: https://www.kernel.org/doc/html/latest/driver-api/early-userspace/buffer-format.html
Android-x86 early init reference: https://github.com/BlissRoms-x86/bootable_newinstaller/blob/pie-x86/initrd/init

## Core guest follow-up

Run 39599875966 on f5f9400 verified both stop cycles and the extract profile, but still failed Android boot completion. Deferring dexopt alone was insufficient; it is not a successful-boot result. The verified ISO's initrd and ramdisk were inspected in runs 39602462163 and 39602750605, and optional package paths were inventoried in run 39603593120.

The next profile offers Core (default) and Full in the VM screen. Core temporarily masks a fixed list of optional Google packages, Chrome, Gmail and search inside the guest using bind mounts over their APK directories. Framework, Settings, SystemUI, launcher, package installer, providers and WebView remain available. Full retains the bundled packages. The verified source ISO is unchanged. Core uses Android-x86's existing SETUPWIZARD=0 option for this fresh test guest; it does not provision or modify the host.

Both profiles use 2 GiB guest RAM and disable the software-rendered boot animation through debug.sf.nobootanimation before SurfaceFlinger starts. The test host emulator has 4 GiB RAM. Actual sys.boot_completed and Settings remain mandatory gates. Core mode must read back sys.aether.profile=core; failed bind mounts report core-error. Diagnostics also capture the guest crash buffer and ANR tails, including a second mid-boot snapshot. Boot completion remains unverified until the new device run finishes.
