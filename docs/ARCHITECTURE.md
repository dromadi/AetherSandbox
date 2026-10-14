# Arsitektur alpha

## Modul

| Modul | Tanggung jawab |
|---|---|
| app | UI, impor APK, indeks, profil, runtime kooperatif, media, panel overlay, JNI diagnostik |
| domain | Validasi path, ZIP, streaming berbatas, digest, dan ID sintetis. Java 17 tanpa dependensi Android agar dapat diuji mandiri |
| guest-api | Kontrak Java GuestEntry yang diberikan host kepada fixture |
| fixture | APK QA opt-in untuk memeriksa resource, database, preferensi, serta persistensi per instance |

## Alur impor dan peluncuran

Pemilih dokumen → direktori UUID baru → stream berbatas ke descriptor APK yang dikunci read-only → SHA-256 → validasi ZIP → metadata PackageManager → indeks AtomicFile. Kegagalan impor menghapus direktori parsial dan tidak menambahkan instance pada indeks.

Peluncuran fixture → Activity proses terpisah → baca indeks → verifikasi SHA-256/sertifikat/metadata → DexClassLoader dengan API parent-first → resource APK via API PackageManager → VirtualContext → GuestEntry.createView.

Host UI memiliki satu mutex untuk mutasi indeks. Proses fixture hanya membaca indeks dan menulis penyimpanan instance. `Application` guest dan library JNI sederhana dijalankan pada runtime umum alpha.6. Activity/Service/Provider guest dan framework global belum divirtualisasi.

## Penyimpanan

```text
files/sandbox-index.json
files/sandboxes/<uuid>/guest.apk
files/sandboxes/<uuid>/data/files/
files/sandboxes/<uuid>/data/databases/
files/sandboxes/<uuid>/data/cache/
shared_prefs/guest_<uuid>_<name>.xml
code_cache/<uuid>/
```

Canonical path validation menolak traversal, absolute paths, dan symlink yang keluar dari root. Nilai profil dan metadata disimpan hanya di perangkat. Host tidak meminta akses internet, daftar semua aplikasi, kamera, atau akses storage menyeluruh. Dokumen APK/video dipilih dengan Storage Access Framework.

## Kontrol lifecycle alpha.2

`GuestLifecycle` meneruskan start/resume/pause/stop/destroy ke kontrak opt-in `GuestEntry`. Callback default menjaga kompatibilitas fixture sebelumnya. State dispatcher mencegah callback ganda dan menyinkronkan guest yang selesai dimuat setelah host resumed. Cleanup stop/destroy tetap dicoba jika callback pause gagal.

`RuntimeLease` memakai exclusive FileChannel lock di `files/runtime-locks/<uuid>.lock`. Runtime mengambil lease sebelum membaca indeks dan melepasnya setelah teardown guest. Repository mengambil lease yang sama sebelum menghapus instance atau mengganti profil. Lock file tidak dihapus agar inode lock tetap stabil; OS melepas lease jika proses mati. Saat loading dibatalkan, lease baru dilepas setelah job IO selesai. Ini koordinasi kooperatif, bukan isolasi keamanan.

## Runtime aplikasi umum alpha.5 (milestone 1)

`GeneralGuestLoader.prepare` dan `GeneralGuestLoader.start` dipakai untuk APK non-fixture. `SandboxRepository.verifyIntegrity` me-rehash APK (tanpa syarat sertifikat fixture) dan mengembalikan `PackageInfo` dengan daftar Activity. Classloader guest adalah `DexClassLoader` dengan parent boot classloader, sehingga kelas aplikasi host tidak bocor ke guest dan library guest menang atas host. Resource dimuat via `getResourcesForApplication`.

`Application` guest (`applicationInfo.className`, atau `android.app.Application` bila kosong) diinstansiasi, di-`attachBaseContext` ke `VirtualContext` instance lewat refleksi, lalu `onCreate()` dipanggil. `VirtualContext.getApplicationContext()` mengembalikan Application tersebut dan `getApplicationInfo()` mengembalikan info guest.

`SandboxRepository.guestComponents` membaca Activity/Service/Provider/Receiver guest dari arsip (milestone 2a); `ProxyActivity` menampilkannya. Resolusi launcher alpha.6 membaca manifest biner dengan XmlResourceParser, menyeleksi MAIN/LAUNCHER pada satu filter yang sama dan mendukung activity-alias serta status enabled/exported.

Batas tegas milestone 1–2a: tidak ada eksekusi Activity/Service/Provider/Broadcast guest, tidak ada intersepsi ActivityManager/PackageManager/Binder, dan tidak ada emulasi hidden API. Guest berbagi UID host; ini virtualisasi kooperatif, bukan batas keamanan. Hidden API dan proxy komponen direncanakan untuk milestone berikutnya.

## Kendali proses alpha.6

RuntimeRouterActivity memilih salah satu empat ProxyActivity dengan task affinity dan proses berbeda. Runtime memegang lease slot dan instance selama hidupnya. Intent baru tidak mengganti guest yang sedang memakai slot. Batas empat slot eksplisit; pemilihan slot optimistis dapat menolak launch yang bertabrakan, sehingga pengguna perlu mencoba ulang.

Hash, metadata, resource, native ABI extraction dan classloader disiapkan pada IO. Constructor Application, attachBaseContext, dan onCreate dipanggil pada main thread. ApplicationInfo disalin dan dataDir/nativeLibraryDir diarahkan ke instance; ini tidak memvirtualisasi API Context/framework lain yang tidak dioverride.

Stop/Back menghancurkan layar dan watchdog mematikan proses guest beserta semua thread-nya. Lease tidak dilepas manual sebelum kematian proses. Bila main thread tidak merespons broadcast selama satu detik, host membaca PID laporan berbatas dan memverifikasi PID/UID/nama proses melalui ActivityManager sebelum kill same-UID. Tidak ada root atau kemampuan mematikan aplikasi lain.

RuntimeJournal menyimpan event terakhir, PID, waktu, dan kegagalan terakhir lewat AtomicFile. Ini laporan diagnostik kooperatif, bukan bukti keamanan atau log seluruh panggilan API. Reset data membutuhkan lease idle dan menghapus data/namespace preferences/cache kode/library tanpa menghapus APK atau profil. Duplikasi memverifikasi ulang APK dan membuat instance baru tanpa menyalin akun. Shortcut adalah permintaan pin kepada launcher; dukungan/konfirmasi bergantung launcher.

## Kontrol runtime alpha.3

`RuntimeLease.isHeld` menjadi registry status: host mencoba mengambil lease lalu langsung melepasnya. Karena sumbernya lock OS, status otomatis kembali "berhenti" ketika proses guest mati. Probe sesaat memegang lease yang kosong, sehingga `ProxyActivity` mengambil lease di thread IO dengan retry 1 detik.

`RuntimeControl` mengirim broadcast `STOP_GUEST` dengan `setPackage` ke receiver dinamis `RECEIVER_NOT_EXPORTED` di proses runtime. Receiver memanggil `finish()`, sehingga teardown memakai jalur lifecycle yang sama dengan tombol di layar guest. Host menunggu lease dilepas sebelum melanjutkan hapus. Saluran ini satu arah; status lifecycle detail (pause/resume) dan perintah selain stop belum tersedia.

## Kamera virtual alpha.4

Host meneruskan URI video terpilih ke `ProxyActivity`, yang membuat `VideoCameraSource` dan memanggil `GuestEntry.onVirtualCamera` sebelum `createView`. Guest memanggil `start(listener)`; decoding berjalan di thread sendiri:

MediaExtractor → MediaCodec (ByteBuffer, `COLOR_FormatYUV420Flexible`) → `getOutputImage` → `YuvPacker` (crop, hapus stride/pixel stride) → `VirtualCameraFrame` I420 → `onFrame`.

Decoder tidak maju sebelum `onFrame` kembali, sehingga tidak ada frame yang dibuang dan timestamp adalah PTS video. Determinisme dijamin untuk video dan decoder yang sama; decoder perangkat lain dapat menghasilkan byte piksel berbeda. `start` ulang selalu mulai dari frame pertama, `stop` aman dipanggil dari listener, dan runtime menutup sumber setelah `onDestroy` guest.

Batas: tidak ada mode real-time/loop, tidak ada output ke `Surface` atau emulasi API Camera2/CameraX, dan resolusi besar disalin per piksel jika pixel stride > 1 (belum dioptimasi/dibenchmark).

## Media dan lifecycle

Pemilih video menyimpan izin URI persisten. MediaExtractor membaca track, MediaCodec mendecode ke Surface milik TextureView, dan timestamp media mengendalikan pacing. File diputar ulang saat akhir track. Pipeline dihentikan saat preview tidak resumed atau Surface dihancurkan. Decoder, extractor, dan Surface dilepas di blok finally worker.

Overlay menggunakan foreground service mediaPlayback yang dimulai dari tindakan pengguna. Panel dipindahkan melalui drag handle dan dihentikan secara eksplisit. Tidak ada auto-start setelah reboot. Preview memakai transformasi TextureView untuk rotasi/cermin. EGL/shader/OpenGL/YUV belum diimplementasikan.

## Risiko teknis yang masih terbuka

- Proses berbeda dengan UID sama tidak membatasi akses file, permission, Binder, dan memori native secara menyeluruh. Guest umum hanya dijalankan jika dipercaya pengguna.
- SharedPreferences Android bersifat process-local/cache. Alpha tidak mendukung dua proses menulis preference instance yang sama secara bersamaan.
- Lease per instance menolak peluncuran ganda, penghapusan, dan perubahan profil selama runtime aktif, termasuk multi-window. Host dapat menghentikan guest dan membaca status berjalan/berhenti (alpha.3). IPC dua arah dan perintah lifecycle selain stop belum tersedia.
- Instalasi split APK/APKS/XAPK belum didukung. Alpha.6 mengekstrak library native sesuai ABI proses; syscall redirect belum tersedia.
- Codec dan format video bergantung perangkat. Belum ada klaim semua MP4/MKV, 60 FPS, latensi, atau hardware decoder tertentu.
- Ukuran APK universal meningkat karena menyertakan runtime Compose serta tiga ABI. Pengukuran, bukan asumsi ukuran, menentukan optimasi berikutnya.

## Gerbang pengembangan berikutnya

1. Buktikan fixture pada API 26/34/36 dan arm64 asli, termasuk resource, SQLite, data, siklus lifecycle, serta clean uninstall.
2. Tambah IPC lifecycle dua arah (stop dari host dan status berjalan sudah ada sejak alpha.3), registry package/component, native library loading, dan test fixture multi-component.
3. Evaluasi virtualisasi framework/Binder per versi Android. Buat matriks aplikasi/fitur yang benar-benar bekerja, termasuk pembatasan hidden API dan layanan sistem.
4. Kamera virtual opt-in berbasis frame I420 tersedia sejak alpha.4. Berikutnya: output Surface, mode real-time, dan adaptor Camera2/CameraX khusus aplikasi uji yang mengizinkan.
5. Jalankan benchmark reproducible dan ubah angka target setelah hasil di perangkat tersedia.

## Referensi platform

- [AGP 8.13 kompatibilitas Gradle/JDK/SDK](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Android 14: dynamic code loading read-only](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
- [DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)
- [Android Process dan UID](https://developer.android.com/reference/android/os/Process)
- [Android Application Sandbox](https://source.android.com/docs/security/app-sandbox)
- [Foreground service mediaPlayback](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
