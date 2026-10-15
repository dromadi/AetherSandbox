# Aether VM alpha.11 — batas bukti G0

Rancangan: Android kedua dalam HP tanpa root. Implementasi tahap ini memisahkan probe host Android dari laboratorium engine Linux. Alpha.10 tetap menyediakan App Runtime; tidak ada migrasi atau pergantian mesin otomatis.

## Yang dikerjakan

1. Host Android: proses bound-service privat `:vm_probe` menjalankan potongan kode mesin RW→RX yang mengembalikan 42. Tidak memakai halaman RWX. Native crash dipisah dari UI; client memantau Binder death, disconnect dan timeout 15 detik, lalu melepas binding.
2. Probe melaporkan ABI, page size, mode seccomp, hasil open `/dev/kvm` dan KVM_GET_API_VERSION, fitur serta permission AVF, RAM dan ruang kosong. Tidak meminta permission istimewa, tidak membuat VM atau mengubah konfigurasi perangkat.
3. Laporan JSON dapat diekspor melalui SAF. `qemuPackaged`, `androidGuestBooted`, `physicalDeviceVerified` tetap false: laporan ini bukan sertifikat kompatibilitas VM. UID worker sama dengan host; proses terpisah adalah batas crash, bukan isolasi UID.
4. Laboratorium CI: Linux 6.12.1 test-only dari commit gregkh/linux `d390303b28dabbb91b2d32016a4f72da478733b9`, tinyconfig ARM64, initramfs statis buatan proyek. Kernel lama ini hanya fixture console tertutup, tanpa jaringan, tidak disertakan dalam APK atau dijadikan guest produksi.
5. QEMU TCG menjalankan board virt-8.2, CPU cortex-a53, RAM 256 MiB dan satu vCPU. PID 1 mengembalikan nonce per boot, versi kernel, arsitektur dan PID, lalu mematikan guest. Sepuluh cold boot diperlukan. Harness menolak log basi/nonce salah, crash, timeout, panic atau tidak adanya userspace dan poweroff.
6. CI mengarsipkan log tiap boot, exit code, durasi, versi QEMU, hash kernel, config dan source commit. Versi paket QEMU dari Ubuntu dicatat, belum byte-reproducible build engine. `androidGuestBooted` dan `physicalPhoneVerified` pada laporan CI selalu false.

## Menjalankan

Di HP: buka **Aether VM • Laboratorium → Periksa perangkat → Ekspor laporan**. Ketika probe gagal/crash/timeout, UI menampilkan kegagalan; tidak memberi status siap VM.

Di Ubuntu 24.04 dengan gcc-aarch64-linux-gnu, libc6-dev-arm64-cross, qemu-system-arm, flex, bison, bc, libssl-dev dan libelf-dev:

```sh
python3 -m unittest discover -s tools/vm -p 'test_*.py'
bash tools/vm/build-kernel.sh
python3 tools/vm/boot_lab.py --kernel build/vm-lab/Image --output build/vm-lab/results --boots 10
```

Emulator Android API 26/34/36 menguji probe lintas proses, hasil eksekusi 42, laporan kemampuan dan rebind. Seluruh tes runtime aplikasi yang lama tetap dijalankan. Hasil aktual ada di CI/PR, bukan diasumsikan oleh dokumen.

## Belum dikerjakan

Port/build/distribusi QEMU di APK Android; boot kernel melalui QEMU dalam sandbox HP; image AOSP dengan Binder/Zygote/SystemServer/SurfaceFlinger/HAL yang cocok; display, input, jaringan dan disk VM; aplikasi/Service di Android VM; GPU, background VM, notifikasi guest; GMS/Play Store; uji HP ARM64 fisik. AVF tidak menjadi baseline APK umum dan tidak diaktifkan oleh probe.

**G0 baru lulus bila engine memulai kernel 10 kali pada HP ARM64 tanpa root, tanpa bantuan ADB persisten dan tanpa SELinux permissive.** CI Linux dan sukses RW→RX Android tidak digabung menjadi klaim tersebut.

## Pekerjaan berikutnya

Patok source engine QEMU beserta dependency yang dapat dibangun dengan NDK, selesaikan lisensi/packaging, jalankan engine pada worker Android dan bawa fixture kernel/initramfs yang cocok. Uji pertama pada HP referensi; baru setelah berhasil bergerak ke image Android dan launcher. Jangan menambahkan tombol Start VM sebelum backend tersebut tersedia.

Referensi primer: [QEMU virt](https://www.qemu.org/docs/master/system/arm/virt.html), [KVM API](https://docs.kernel.org/virt/kvm/api.html), [AVF API](https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/libs/framework-virtualization/README.md), [Microdroid](https://source.android.com/docs/core/virtualization/microdroid).
