# Pemetaan grand design rev00

Sumber: workbook pengguna, 2026-10-13. Kolom “SELESAI” pada workbook adalah pernyataan rancangan, bukan bukti implementasi repositori. Status di bawah mencatat lingkup kode alpha ini. Keberhasilan pada perangkat harus dibuktikan terpisah.

| Fase | Implementasi alpha | Belum diimplementasikan |
|---|---|---|
| 1. Proyek/manifest | Kotlin, Compose, version catalog, NDK C++20, Activity guest proses terpisah, overlay FGS | ProxyService/Provider aplikasi umum, QUERY_ALL_PACKAGES/kamera tidak diperlukan pada alpha |
| 2. Loader/resource | DexClassLoader dan VirtualContext untuk fixture bertanda tangan sama; resource APK independen | Lifecycle/framework aplikasi umum, isolasi framework global |
| 3. VFS | File/SQLite redirect kooperatif melalui Context; SharedPreferences namespace per UUID | PLT/libc hooking, syscall/native I/O interception, isolasi keamanan OS |
| 4. Identitas | Test Android ID acak persisten per instance, regenerasi profil | Hook Settings/Telephony/WiFi/Build, IMEI/MAC spoof |
| 5. Kamera | Kamera virtual opt-in (`VirtualCamera`): frame I420 deterministik dari video uji untuk fixture | Camera2 proxy, capture session interception, Camera1/CameraX, output Surface |
| 6. Media | MediaExtractor/MediaCodec → Surface, loop/pause/ulang/rotasi/cermin preview | EGL/OpenGL, video→kamera target, seek arbitrer, benchmark FPS/latensi |
| 7. Overlay | Panel foreground draggable, pause/tutup/transparansi | Compose overlay, hot-swap kamera fisik/virtual, pergantian video langsung dalam target |
| 8. Packaging | Gradle wrapper, universal APK tiga ABI, CMake/JNI diagnostics, R8 release build, CI | Production signing, profil RAM/thermal, angka target ukuran/performa |

Prompt yang menginstruksikan status selesai atau menyatakan performa tidak menggantikan pengujian. Dokumentasi implementasi menggunakan tujuan QA yang sebenarnya dan batas API yang dapat dibuktikan.
