# Kamera video untuk aplikasi video call

Target produk: video pilihan pengguna dapat menjadi gambar kamera aplikasi video call yang dijalankan di dalam Aether. Gambar rekaman harus dinyatakan sebagai rekaman kepada peserta panggilan. Audio bukan bagian dari jalur ini.

## Milestone alpha.9

Prototipe non-root, process-local Camera2 openCamera + synthetic CameraDevice/CaptureSession + real-time decoder ke Surface preview/encoder. User mengaktifkan sakelar eksplisit; tidak ada fallback kamera fisik bila setup gagal. Metadata karakteristik masih dari kamera perangkat, sedangkan pengaturan request selain target Surface belum diterapkan. Host tidak meminta izin kamera fisik.

Acceptance test memakai APK normal tanpa API Aether, memanggil Camera2 standar, mengirim preview ke TextureView dan video ke encoder AVC. Lebih dari 30 frame harus ter-encode dari clip 30-frame, menunjukkan loop; Back dan stop guest tetap bekerja. Ini belum membuktikan transmisi ke peserta panggilan atau keutuhan gambar di aplikasi produksi.

## Gerbang kompatibilitas berikutnya

1. Buktikan input ImageReader/YUV, timestamps monoton, ukuran output sesuai request, orientasi/mirror, dan capture callbacks.
2. Jalankan APK target yang dipercaya di HP ARM64; dokumentasikan package/version, jalur Camera1/Camera2/NDK/WebRTC, dan kegagalan startup.
3. Lengkapi runtime dan permission/mikrofon yang dibutuhkan setiap aplikasi, kemudian uji panggilan dua perangkat dan rekaman output di penerima.
4. Ukur CPU/PSS/latensi/FPS, siklus hidup sesi, foreground/background, perubahan sumber, dan pemulihan kegagalan.

## Dependency

AliuHook 1.1.4: https://github.com/Aliucord/hook (OSL-3.0; Xposed-derived files also carry Apache-2.0 notices). LSPlant: https://github.com/LSPosed/LSPlant. Dependency binary is unmodified and sources/licenses remain available from upstream. Internal Android APIs and ART method hooks can fail on vendor devices; emulator success alone is insufficient.

Build lokal tidak tersedia karena unduhan Gradle diblokir jaringan. Build/lint/R8 dan tes emulator API 26/34/36 harus diperiksa pada PR alpha.9 sebelum mengklaim hasil lulus.
