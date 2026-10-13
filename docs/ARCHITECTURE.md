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

Host UI memiliki satu mutex untuk mutasi indeks. Proses fixture hanya membaca indeks dan menulis penyimpanan instance. `Application` guest, manifest Activity/Service/Provider, JNI guest, dan framework global tidak dieksekusi atau diganti.

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

## Media dan lifecycle

Pemilih video menyimpan izin URI persisten. MediaExtractor membaca track, MediaCodec mendecode ke Surface milik TextureView, dan timestamp media mengendalikan pacing. File diputar ulang saat akhir track. Pipeline dihentikan saat preview tidak resumed atau Surface dihancurkan. Decoder, extractor, dan Surface dilepas di blok finally worker.

Overlay menggunakan foreground service mediaPlayback yang dimulai dari tindakan pengguna. Panel dipindahkan melalui drag handle dan dihentikan secara eksplisit. Tidak ada auto-start setelah reboot. Preview memakai transformasi TextureView untuk rotasi/cermin. EGL/shader/OpenGL/YUV belum diimplementasikan.

## Risiko teknis yang masih terbuka

- Proses berbeda dengan UID sama tidak membatasi akses file, permission, Binder, dan memori native secara menyeluruh. Guest hanya fixture tepercaya.
- SharedPreferences Android bersifat process-local/cache. Alpha tidak mendukung dua proses menulis preference instance yang sama secara bersamaan.
- Lease per instance menolak peluncuran ganda, penghapusan, dan perubahan profil selama runtime aktif, termasuk multi-window. Hentikan guest melalui layar runtime sebelum mutasi. IPC stop dari host dan registry status runtime masih belum tersedia.
- Instalasi split APK/APKS/XAPK dan ekstraksi library native guest belum didukung.
- Codec dan format video bergantung perangkat. Belum ada klaim semua MP4/MKV, 60 FPS, latensi, atau hardware decoder tertentu.
- Ukuran APK universal meningkat karena menyertakan runtime Compose serta tiga ABI. Pengukuran, bukan asumsi ukuran, menentukan optimasi berikutnya.

## Gerbang pengembangan berikutnya

1. Buktikan fixture pada API 26/34/36 dan arm64 asli, termasuk resource, SQLite, data, siklus lifecycle, serta clean uninstall.
2. Tambah koordinasi proses, IPC kontrol lifecycle, registry package/component, native library loading, dan test fixture multi-component.
3. Evaluasi virtualisasi framework/Binder per versi Android. Buat matriks aplikasi/fitur yang benar-benar bekerja, termasuk pembatasan hidden API dan layanan sistem.
4. Baru integrasikan kamera virtual pada target QA yang diizinkan. Uji preview/capture/Camera1/Camera2/CameraX/ImageReader secara terpisah.
5. Jalankan benchmark reproducible dan ubah angka target setelah hasil di perangkat tersedia.

## Referensi platform

- [AGP 8.13 kompatibilitas Gradle/JDK/SDK](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Android 14: dynamic code loading read-only](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
- [DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)
- [Android Process dan UID](https://developer.android.com/reference/android/os/Process)
- [Android Application Sandbox](https://source.android.com/docs/security/app-sandbox)
- [Foreground service mediaPlayback](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
