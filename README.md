# AetherSandbox

VirtuBox Core — laboratorium Android non-root untuk container berbasis framework dan video uji.

Versi awal: **0.1.0-alpha.4**. Android 8–16 (minSdk 26, targetSdk 36).

## Yang sudah diimplementasikan

- Antarmuka Kotlin/Compose Material 3 untuk mengimpor APK universal melalui pemilih berkas Android.
- Validasi ZIP, batas impor 256 MiB, SHA-256, APK read-only, dan penyimpanan indeks atomik.
- Beberapa instance untuk APK yang sama. Setiap instance memiliki direktori, database, dan test Android ID sendiri.
- Runtime opt-in untuk **APK fixture milik proyek**: DexClassLoader, resource APK, VirtualContext, dan ProxyActivity dalam proses `:virtual_process`.
- Verifikasi ulang hash dan sertifikat sebelum fixture dijalankan. Hanya `com.aether.fixture` dengan sertifikat yang sama seperti host yang diterima.
- Decoder video lokal MediaExtractor/MediaCodec ke Surface: loop, pause, ulang, rotasi, dan cermin pada preview.
- Panel video melayang dengan kontrol pause, tutup, geser, dan transparansi. Izin overlay diminta saat pengguna mengaktifkan panel.
- Library native C++20 untuk diagnostik ABI. APK universal menyertakan arm64-v8a, armeabi-v7a, dan x86_64 untuk emulator.
- Unit test domain, instrumented test fixture, build/lint CI, dan workflow pengujian perangkat.

## Pembaruan alpha.2

- Callback lifecycle fixture: start, resume, pause, stop, destroy; termasuk pemuatan yang selesai setelah Activity sudah resumed.
- Lease file lintas proses mencegah dua runtime memakai instance yang sama, serta menolak hapus/perubahan profil saat runtime masih aktif.
- Tombol **Hentikan guest** menutup runtime dan melepas lease setelah cleanup; kematian proses juga melepas lock melalui OS.
- Lease tetap dipegang sampai pemuatan IO selesai jika Activity ditutup saat loading.
- Unit test lease/lifecycle dan pengujian perangkat untuk mutasi yang ditolak saat guest berjalan.

## Pembaruan alpha.4 — kamera virtual deterministik (opt-in)

- Kontrak baru `VirtualCamera` dan `VirtualCameraFrame` di `guest-api`. Guest yang mengimplementasikan `GuestEntry.onVirtualCamera` menerima video uji yang dipilih di host sebagai input kamera.
- **Deterministik:** setiap frame didecode berurutan, tidak ada yang dibuang, dan timestamp berasal dari video, bukan jam. Video yang sama di perangkat yang sama menghasilkan urutan dan byte frame yang sama setiap run.
- Frame dikirim sebagai I420 rapat (padding stride dan chroma interleaved dihapus oleh `YuvPacker`).
- Fixture menampilkan thumbnail grayscale, progres frame, dan **sidik CRC32** seluruh frame untuk membandingkan run.
- Hanya untuk guest opt-in. Kamera aplikasi lain dan kamera fisik tidak disentuh.

## Pembaruan alpha.3 — kontrol runtime dari host

- Status **Berjalan/Berhenti** per instance dan jumlah guest aktif di layar utama, diperbarui setiap host kembali ke depan.
- Tombol **Hentikan** dan **Hentikan semua** dari host. Perintah dikirim lewat broadcast yang terbatas pada paket ini ke receiver yang tidak di-export, lalu host menunggu lease dilepas (maks. 5 detik).
- **Hapus instance** menghentikan guest yang masih berjalan terlebih dahulu.
- Status dibaca dari lease OS yang sama dengan runtime, sehingga proses guest yang mati tidak meninggalkan status "berjalan" palsu. Runtime mengambil lease di thread IO dan mencoba ulang hingga 1 detik bila bertabrakan dengan probe status.

## Batas alpha ini

APK umum dapat diimpor dan ditampilkan metadatanya, tetapi **belum dapat dijalankan sebagai aplikasi Android penuh**. APK fixture menerapkan kontrak `GuestEntry`, bukan Activity/Service pihak ketiga yang tidak dimodifikasi.

`VirtualContext` mengarahkan operasi file dan SQLite yang dipanggil melalui context tersebut. SharedPreferences menggunakan namespace per instance di penyimpanan host. Pemanggilan filesystem langsung, JNI, Binder, PackageManager, dan framework singleton belum divirtualisasi.

Proses guest memakai UID host yang sama. Pemisahan proses/namespace ini **bukan isolasi keamanan untuk menjalankan malware**. APK tak tepercaya hanya dibaca, tidak dieksekusi.

Profil mengandung nilai sintetis yang diberikan secara eksplisit kepada fixture. Alpha ini belum mengganti ANDROID_ID sistem, IMEI, MAC, atau `android.os.Build`.

Kamera virtual hanya tersedia bagi fixture opt-in melalui `VirtualCamera` (frame I420, tanpa pacing real-time). Alpha ini tidak mengganti kamera aplikasi lain, tidak mengemulasikan API Camera1/Camera2/CameraX, dan tidak melakukan hot-swap kamera fisik. Angka RAM, FPS, ukuran APK, waktu launch, dan perbandingan VMOS/VPhone pada grand design adalah target yang belum dibenchmark.

## Build

Gunakan JDK 17 dan Android SDK. Gradle Wrapper 8.13 sudah disertakan dengan SHA-256 distribusi.

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.0.12077973' 'cmake;3.22.1'
./gradlew :domain:test :app:lintDebug :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
```

Output:

- `app/build/outputs/apk/debug/app-debug.apk`: APK alpha yang dapat dipasang.
- `fixture/build/outputs/apk/debug/fixture-debug.apk`: fixture untuk diimpor ke host.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: hasil release/R8, belum ditandatangani untuk distribusi.

Build host dan fixture pada komputer/CI yang sama agar keduanya menggunakan debug certificate yang sama. Untuk release nanti, gunakan signing konfigurasi sendiri dan jangan simpan private key di Git.

## Uji di HP

1. Pasang `app-debug.apk`.
2. Salin `fixture-debug.apk` ke Download, lalu **Impor APK** di AetherSandbox.
3. Tekan **Jalankan**. Resource dari guest, PID proses, direktori, test ID, dan penghitung akan tampil.
4. Naikkan penghitung, kembali, lalu jalankan lagi. Nilainya harus bertahan.
5. Impor fixture yang sama lagi. Instance kedua harus memiliki direktori, ID, dan penghitung yang berbeda.
6. Pilih video lokal. Uji pause/play, ulang, rotasi, cermin, keluar/masuk layar, dan loop.
7. Tekan **Panel**, berikan izin overlay, lalu tekan lagi. Uji geser/transparansi dan tutup panel melalui panel/notifikasi.
8. Dengan video terpilih, jalankan fixture dan tekan **Mulai kamera virtual** dua kali. Kedua hasil harus menunjukkan jumlah frame dan sidik yang sama.
9. Kembali ke host saat guest masih berjalan: kartu harus menunjukkan **● Berjalan**. Tekan **Hentikan** dari host; status harus kembali **○ Berhenti**.
10. Tekan **Hentikan guest** atau kembali dari layar guest. Kemudian hapus instance. APK, direktori, database, preference namespace, dan cache kode instance tersebut dihapus.

Untuk instrumented tests:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Fixture otomatis dibuild dan dibundel sebagai aset APK pengujian. CI perangkat menguji API 26/34/36 pada pull request dan dapat dijalankan ulang melalui **Actions → Android device tests → Run workflow**.

Lihat [arsitektur dan roadmap](docs/ARCHITECTURE.md), [pemetaan grand design](docs/GRAND_DESIGN_STATUS.md), dan [protokol benchmark](docs/BENCHMARK.md).
