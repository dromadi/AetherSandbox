# AetherSandbox

VirtuBox Core — laboratorium Android non-root untuk container berbasis framework dan video uji.

Versi awal: **0.1.0-alpha.13**. Android 8–16 (minSdk 26, targetSdk 36).

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

## Pembaruan alpha.13 — profil Android inti dan perbaikan boot TCG

- Log alpha.12 menunjukkan boot pertama masih memverifikasi Chrome dan Google Play Services lewat dex2oat setelah 15 menit. Profil TCG menunda pekerjaan itu ke pemuatan kelas ART, memakai filter `extract` sebelum zygote dimulai.
- Initrd sumber tetap utuh. File turunan menambahkan satu arsip initramfs dengan skrip konfigurasi tetap; ISO tetap diverifikasi melalui jalur impor yang sama.
- Profil **Android inti** (default) menyembunyikan paket Google tambahan dengan mount sementara di dalam guest. Profil **image lengkap** mempertahankan paket bawaan ISO. Guest memakai RAM 2 GB dan animasi boot dinonaktifkan untuk mengurangi beban software rendering.
- Guest mendapat perangkat RNG virtio. Pembacaan konsol dibatasi per byte dan interupsi ketika stop ditangani di dalam coroutine.
- Tes tetap mewajibkan boot selesai, Settings aktif, serta profil boot yang benar. Build atau animasi boot saja bukan hasil lulus. Status aktual ada di PR #11/CI; HP ARM64 fisik dan data permanen belum diverifikasi.

## Pembaruan alpha.12 — engine VM dan jalur boot Android

- Engine QEMU 11.1.2 statis dibangun dari source untuk host ARM64/x86_64, dikemas dalam APK engine. Build dasar CI tidak membawa engine; pilih artifact `AetherSandbox-0.1.0-alpha.13-engine` dari workflow Android guest appliance.
- Layar Android guest mengimpor ISO resmi Android-x86 9.0-r2 dengan checksum rilis, mengekstrak kernel/initrd secara terbatas dan memulai PC guest melalui TCG.
- Display RFB memakai socket Unix privat; sentuhan dan teks diteruskan ke guest. Sesi awal memakai data sementara, tanpa jaringan, audio, kamera atau background VM. Meninggalkan layar menghentikan VM.
- Tes engine menjalankan boot Linux ARM64 sepuluh kali di dalam Android. Tes guest mengarsipkan framebuffer dan serial; status sys.boot_completed dicatat terpisah. Hasil aktual ada pada PR/CI, bukan diasumsikan dari adanya layar display.
- Image Android tidak termasuk APK dan Android 9 adalah milestone kompatibilitas lama. Belum klaim dukungan aplikasi populer atau HP fisik. [Batas engine dan bukti](docs/VM_ENGINE.md).

## Pembaruan alpha.11 — Aether VM, tahap kelayakan G0

- Laboratorium VM pada aplikasi menjalankan diagnostik native dalam proses `:vm_probe`, memeriksa kode RW→RX yang mengembalikan 42, page size, seccomp, akses `/dev/kvm`, fitur/permission AVF, RAM dan penyimpanan. Hasil dapat diekspor sebagai JSON.
- Probe tidak mengunduh/menjalankan mesin VM. KVM API yang dapat dibaca bukan bukti izin membuat VM. Keberhasilan RW→RX tidak membuktikan port QEMU atau performa TCG.
- Workflow `VM Linux boot laboratory` membangun kernel ARM64 Linux dan initramfs PID 1 dari source yang dipatok, kemudian melakukan sepuluh boot/poweroff nyata dengan QEMU/TCG pada runner Linux. Ini bukti Linux guest pada Linux CI, bukan Android guest pada HP.
- APK belum membawa QEMU, image Android, launcher guest atau Android VM aktif. G0 fisik belum selesai. Status rinci: [docs/VM_FEASIBILITY.md](docs/VM_FEASIBILITY.md).

## Pembaruan alpha.10 — fokus menjalankan APK nyata

- Identitas paket guest pada PackageManager (PackageInfo/ApplicationInfo/Activity/Provider/Service metadata, launcher, UID proses) tersedia sebelum Application/Provider mulai; paket lain tetap ditangani Android.
- ContentResolver CRUD/call guest diarahkan ke Provider lokal proses, tanpa menerbitkan authority ke sistem atau berbagi data antar instance.
- Context/Application/ActivityInfo dipasang sebelum Activity.attachBaseContext; tema, konfigurasi resource dan jalur APK tetap milik guest, termasuk pemanggilan konfigurasi dari AndroidX.
- Pencocokan intent-filter manifest, navigasi dari Application context, Parcelable guest, serta pool sembilan jenis Activity per proses untuk menjaga identitas komponen dan flag CLEAR_TOP/SINGLE_TOP.
- Intent eksternal/file picker diteruskan ke Android; export URI dari Provider guest ke aplikasi lain belum didukung.
- Tolok ukur baru: APK rilis Simple Calculator 5.12.0 yang tidak dimodifikasi (pin SHA-256, diunduh dari rilis upstream hanya untuk tes). Tes mencoba perhitungan di dua task guest dan stop/relaunch. Hasil aktual wajib dilihat di CI; bukan klaim semua aplikasi kompatibel.

Belum tersedia penuh: Service/receiver guest, notifikasi/permission umum, split APK, Google services dan isolasi UID. Kamera alpha.9 tetap eksperimental dan bukan prioritas upgrade ini.

## Pembaruan alpha.9 — prototipe Camera2 dari video

- Sakelar Camera2 video terpisah, default mati. Pilih video, aktifkan sakelar, lalu mulai ulang guest. Hanya proses guest tersebut yang di-hook; aplikasi terpasang di luar Aether tidak berubah.
- Intersepsi openCamera API Handler/Executor mengembalikan CameraDevice sintetis tanpa membuka kamera fisik. Hook dipasang sebelum Application guest mulai. Kegagalan hook menolak startup mode video.
- Preview/record repeating request dengan satu atau dua Surface menerima decoder video real-time yang loop. Surface milik guest tidak dilepas oleh decoder.
- Aplikasi contoh menggunakan Camera2 standar, TextureView dan input Surface encoder H.264, tanpa GuestEntry/API Aether. Tes memeriksa output encoder melewati satu loop dan navigasi setelah kamera ditutup, sementara izin CAMERA host tetap tidak diminta/diberikan.
- Dependency hook dipin AliuHook 1.1.4 (OSL-3.0, LSPlant/Xposed bindings), aktif hanya bila mode video dipilih. Lihat docs/CAMERA_VIDEO_STATUS.md.

**Belum didukung:** ImageReader/YUV/JPEG, Camera1, NDK, capture result metadata, capture callback, CameraX lengkap, rotasi/mirror untuk output guest, pacing FPS dari CaptureRequest, audio, penggantian sumber tanpa restart. WhatsApp/Meet/Instagram belum diuji dan tidak diklaim kompatibel. Ini prototipe output Surface, bukan kamera virtual sistem Android.

## Pembaruan alpha.8 — jaringan dan startup Provider

- Host memiliki izin INTERNET. Release tetap menolak cleartext HTTP; debug mengizinkan HTTP hanya ke localhost/127.0.0.1 untuk tes.
- Provider manifest yang aktif dalam proses Application diinisialisasi pada main thread, mengikuti initOrder menurun, sebelum Application.onCreate. Context/resource/metadata tetap milik guest; provider proses lain ditolak dengan pesan jelas.
- Provider dipertahankan selama runtime dan shutdown saat cleanup. Authorities tidak didaftarkan ke sistem: ContentResolver/CRUD, IPC dan FileProvider belum divirtualisasi.
- Tes baru memakai server HTTP loopback nyata: dua instance mendapat token akun alpha/beta berbeda, sesi diperiksa ulang setelah stop/relaunch tanpa login ulang.
- Ini bukti aplikasi contoh dengan backend uji, bukan bukti WhatsApp/Instagram/Google services berjalan. HTTP jaringan produksi, izin per instance, cookie framework global dan backend eksternal belum diuji.

## Pembaruan alpha.7 — Activity aplikasi umum (eksperimental)

- Activity guest dibuat melalui Instrumentation pada komponen proxy terdaftar, dengan token sistem Android nyata; bukan View yang meniru UI aplikasi.
- Context, Application, resource/theme dan ActivityInfo diarahkan ke guest sebelum onCreate. Intent eksplisit antar Activity guest memakai stub dalam proses instance yang sama.
- Lifecycle, Back, dan startActivityForResult mengikuti transaksi Android; tombol Buka memindahkan task instance yang berjalan ke depan tanpa membongkar stack.
- APK contoh tetap aplikasi Android biasa, tidak menggunakan GuestEntry/Aether API. Tes menjalankan dua akun contoh lokal berbeda, navigasi/result, pindah task dan persistensi setelah stop/relaunch.
- Bergantung API internal Android dengan AndroidHiddenApiBypass 6.1 (Apache-2.0), hanya dipasang di proses guest dengan prefix exemption terbatas. Gagal dengan pesan jelas jika hook tidak tersedia. Kompatibilitas OS/vendor harus dibuktikan; belum disiapkan untuk distribusi Play Store.

Batas alpha.7: baru Intent eksplisit dan flag NEW_TASK dasar. Service/resolver Provider/receiver, intent implisit, flag task khusus, Application.startActivity, autofill, permission/notifikasi/Google services dan login aplikasi produksi belum divirtualisasi. Dua akun contoh lokal tidak membuktikan WhatsApp/Instagram bekerja. UID tetap sama dengan host.

## Pembaruan alpha.6 — kendali proses dan pengelolaan instance

- Empat slot runtime memakai proses berbeda. Slot dan instance memakai lease OS; proses yang ditempati tidak diganti ketika dua launch bertabrakan.
- Stop/Back menutup proses guest, bukan hanya layar, dan lease tetap dipegang sampai proses mati. Host memverifikasi PID/UID/nama proses sebelum fallback stop bila main thread guest tidak merespons.
- Constructor, attachBaseContext dan Application.onCreate guest umum dijalankan di main thread; hash/resource/DEX/library disiapkan di IO.
- Launcher dibaca dari intent-filter MAIN/LAUNCHER pada manifest biner, termasuk activity-alias dan disabled component. Tidak lagi menebak dari Activity pertama.
- Library native APK diekstrak dengan batas 128 MiB untuk ABI yang cocok dengan bitness proses. Ini belum native syscall virtualization atau bukti kompatibilitas semua JNI.
- Nama instance, duplikasi APK dengan data kosong, reset data sambil mempertahankan APK/profil, shortcut launcher (jika didukung), dan laporan runtime yang dapat diekspor.
- Laporan menyimpan event terakhir/startup/crash dan kegagalan terakhir; bukan tracing semua API guest.

**Batas alpha.6 historis:** layar Activity aplikasi umum belum dijalankan pada alpha.6; alpha.7 menambah jalur proxy eksperimental. Proses terpisah tetap memakai UID host, sehingga belum menjadi isolasi keamanan. Empat proses bukan empat ROM Android. Lihat [roadmap kompetitor](docs/COMPETITOR_ROADMAP.md) untuk seluruh backlog dan kriteria penerimaan.

## Pembaruan alpha.2

- Callback lifecycle fixture: start, resume, pause, stop, destroy; termasuk pemuatan yang selesai setelah Activity sudah resumed.
- Lease file lintas proses mencegah dua runtime memakai instance yang sama, serta menolak hapus/perubahan profil saat runtime masih aktif.
- Tombol **Hentikan guest** menutup runtime dan melepas lease setelah cleanup; kematian proses juga melepas lock melalui OS.
- Lease tetap dipegang sampai pemuatan IO selesai jika Activity ditutup saat loading.
- Unit test lease/lifecycle dan pengujian perangkat untuk mutasi yang ditolak saat guest berjalan.

## Pembaruan alpha.5 — runtime aplikasi umum (milestone 1)

- APK pihak ketiga (bukan fixture) kini dapat **dimuat dan dijalankan**, bukan hanya dibaca metadatanya. Tombol **Jalankan** aktif untuk semua APK.
- `GeneralGuestLoader` memuat kelas dan resource guest lewat classloader terpisah (parent = boot classloader, sehingga kelas host tidak terlihat), lalu menginstansiasi dan menjalankan `Application.onCreate()` guest di atas `VirtualContext` per instance.
- File, database, dan preferensi guest diarahkan ke penyimpanan instance. Modul contoh `sampleapp` membuktikan `Application.onCreate` berjalan dan datanya terpisah antar instance.
- **Peringatan keamanan:** guest berjalan dengan UID host. Kode APK yang dijalankan dapat membaca data semua instance dan data host. Ini virtualisasi kooperatif, **bukan** sandbox keamanan. Jangan jalankan APK yang tidak Anda percayai.
- **Batas milestone 1:** Activity, Service, Provider, dan Broadcast milik guest **belum** dijalankan. Itu butuh intersepsi ActivityManager/Binder dan menjadi milestone berikutnya.

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

APK umum menjalankan Application dan, sejak alpha.7, Activity melalui proxy eksperimental. Service/resolver Provider/Broadcast dan kompatibilitas APK produksi belum tersedia. APK fixture tetap mendapat jalur opt-in melalui GuestEntry.

`VirtualContext` mengarahkan operasi file dan SQLite yang dipanggil melalui context tersebut. SharedPreferences menggunakan namespace per instance di penyimpanan host. Pemanggilan filesystem langsung, JNI, Binder, PackageManager, dan framework singleton belum divirtualisasi.

Proses guest memakai UID host yang sama. Pemisahan proses/namespace ini **bukan isolasi keamanan untuk menjalankan malware**. Sejak alpha.5 APK umum dieksekusi kodenya; kode itu berjalan dengan UID host dan dapat membaca data instance lain dan data host. Jalankan hanya APK yang Anda percayai.

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
