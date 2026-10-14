# Validasi alpha 0.1.0-alpha.1

Tanggal: 2026-10-13. Hasil lokal untuk implementasi alpha, bukan benchmark seluruh grand design.

| Pemeriksaan | Hasil |
|---|---|
| Unit test domain | 10 test lulus, 0 failure, 0 error |
| app:lintDebug | 0 fatal/error, 17 warning (terutama teks native UI dan saran versi dependensi) |
| app:assembleDebug | Lulus |
| app:assembleRelease | Lulus termasuk R8; APK release unsigned |
| fixture:assembleDebug | Lulus |
| app:assembleDebugAndroidTest | Lulus; fixture dibundel dalam aset APK test |
| Native C++20 | arm64-v8a, armeabi-v7a, x86_64 berhasil dikompilasi |
| zipalign -c -P 16 4 | APK debug dan release lulus |
| apksigner verify | APK debug dan fixture valid; SHA-256 sertifikat keduanya sama |
| Android instrumented tests | Dijalankan oleh workflow device tests; lihat hasil CI PR untuk status per API |
| Media/overlay pada HP fisik | Belum diuji |
| RAM/FPS/thermal/latensi | Belum dibenchmark |

APK debug lokal berukuran 10.46 MB desimal (9.98 MiB). Release unsigned berukuran 1.44 MB desimal (1.38 MiB). Ukuran ini berlaku untuk alpha dengan runtime fixture terbatas, bukan keseluruhan engine virtualisasi yang direncanakan.

Pengujian Android mencakup integritas/read-only APK, pemuatan resource/entrypoint, file/database/pref namespace, persistensi profil dan penghitung, beberapa instance, penghapusan data, rollback impor rusak, serta tampilan fixture dalam proses terpisah dengan UID host yang sama.

Tidak ada hasil perangkat yang dinyatakan lulus sebelum workflow selesai. Hasil CI dan artefak tersedia di PR #1.

## Alpha.2 — validasi perubahan

Perubahan lifecycle/lease menambahkan 4 unit test domain (termasuk subprocess JVM), 3 unit test lifecycle, serta assertion instrumentasi untuk penolakan hapus/perubahan profil ketika proses guest berjalan dan pelepasan lease melalui tombol stop. Hasil alpha.1 di atas adalah historis dan tidak otomatis berlaku untuk alpha.2. Build Android lokal alpha.2 belum dapat dijalankan karena unduhan Gradle diblokir jaringan; hasil build/lint dan perangkat alpha.2 harus dilihat pada CI PR terkait.

Harness Java lokal berhasil memeriksa eksklusi antarproses setelah percobaan duplikat lokal, instance berbeda, release/reacquire, urutan lifecycle, serta cleanup ketika callback gagal. Harness memakai stub Android untuk signature Context/View, sehingga tidak menggantikan build atau pengujian perangkat Android.

## Alpha.3 — validasi perubahan

Tanggal: 2026-10-14. Build Android lokal kini berjalan (JDK 17, SDK 36, NDK 27).

| Pemeriksaan | Hasil |
|---|---|
| Unit test domain | 15 test lulus (termasuk `isHeld` dan probe antarproses) |
| app:testDebugUnitTest | 3 test lulus |
| app:lintDebug | 0 error, 32 warning (teks UI hardcoded dan saran versi dependensi) |
| assembleDebug / assembleRelease (R8) / fixture / assembleDebugAndroidTest | Lulus |
| Instrumented test baru `hostReportsRunningGuestAndStopsItRemotely` | Belum dijalankan lokal (tidak ada KVM); lihat workflow device tests |
| Kontrol host pada HP fisik | Belum diuji |

## Alpha.4 — kamera virtual

Tanggal: 2026-10-14.

| Pemeriksaan | Hasil |
|---|---|
| Unit test domain | 19 test lulus (termasuk 4 test `YuvPacker`: padding, crop, chroma semi-planar, batas buffer) |
| app:lintDebug | 0 error |
| assembleDebug / assembleRelease (R8, API guest dipertahankan) / fixture / assembleDebugAndroidTest | Lulus; `camera-test.mp4` (160x120, 30 frame H.264) dibundel di APK test |
| `VirtualCameraTest` (2 run identik, stop dari listener, sidik fixture end-to-end) | Belum dijalankan lokal (tidak ada KVM); lihat workflow device tests |
| Determinisme pada HP fisik / decoder hardware | Belum diuji |

## Alpha.5 — runtime aplikasi umum (milestone 1)

Tanggal: 2026-10-14.

| Pemeriksaan | Hasil |
|---|---|
| Unit test domain | 19 test lulus |
| app:testDebugUnitTest | 3 test lulus |
| app:lintDebug | 0 error, 32 warning |
| assembleDebug / assembleRelease (R8) / fixture / sampleapp / assembleDebugAndroidTest | Lulus; `fixture.apk` dan `sampleapp.apk` dibundel di APK test |
| `GeneralGuestTest` (sampleapp: resource, `Application.onCreate`, storage terpisah, registry komponen) | Belum dijalankan lokal (tidak ada KVM); lihat workflow device tests |
| Eksekusi Activity/Service guest | Tidak ada; di luar lingkup milestone 1 |

## Alpha.6 — validasi batch kendali proses

Build lokal tidak dapat dimulai: Gradle 8.13 belum tersedia dan unduhan distribusi diblokir jaringan. Tidak ada klaim build lokal lulus.

Pengujian baru: native ABI selection / incompatible ABI / Java-only / traversal, Application main thread, launcher alias yang berbeda dari Activity pertama, reset/duplikasi/persistensi profil, dua worker dalam proses berbeda dan penghentian salah satunya, serta fallback stop ketika startup memblokir main thread. Hasil aktual harus dilihat pada CI PR alpha.6; status pengujian versi sebelumnya tidak otomatis berlaku.

Belum diuji: HP fisik ARM64, shortcut pada launcher vendor, native library produksi, login akun, Activity/Service/Provider guest, seluruh benchmark kompetitor.

## Alpha.7 — Activity proxy

Validasi baru diperlukan pada API 26/34/36: UI APK umum tanpa GuestEntry, resource/theme per guest, Intent ke Activity kedua, startActivityForResult, Back, dua sesi contoh lokal independen, pemindahan task dan stop/relaunch. Belum ada klaim lulus sebelum CI selesai. Library AndroidHiddenApiBypass dipin 6.1 dan non-SDK signature dipertahankan pada R8.

Belum dibuktikan: HP fisik ARM64/vendor, AppCompat/Compose guest, akun jaringan, Play services, Service/Provider, intent implisit/flag task khusus dan keamanan isolasi.

## Alpha.8 — jaringan dan startup Provider

Tes baru: Provider main thread sebelum Application.onCreate, context Application guest, penyimpanan Provider hanya pada instance terkait; dua sesi HTTP dengan token server berbeda dan persistensi setelah stop/relaunch kedua proses.

Build lokal belum dapat berjalan: unduhan Gradle 8.13 tidak terjangkau dari lingkungan ini. Hasil aktual build/lint/R8 dan emulator API 26/34/36 harus diperiksa pada CI PR alpha.8. Belum diuji HP fisik, HTTPS eksternal, aplikasi produksi, ContentResolver, Service/receiver atau notifikasi guest.
