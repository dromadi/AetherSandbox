# Roadmap mengejar kemampuan kompetitor

Tanggal: 2026-10-14. Basis: alpha.5 di PR #3; batch pertama alpha.6.

Tujuan produk: APK umum dapat dipakai dalam beberapa instance independen, dengan kontrol runtime dan alat pengujian yang dapat diukur. Tabel ini adalah backlog, bukan klaim bahwa fitur sudah selesai. Tidak ada angka persentase atau tanggal selesai tanpa bukti perangkat.

| Kemampuan pesaing / sasaran | Status alpha.6 | Kriteria penerimaan berikutnya |
|---|---|---|
| Impor dan banyak instance | Ada; duplikasi APK terverifikasi menghasilkan data kosong | Split APK/APKS, pemilihan aplikasi terpasang, update APK tanpa hilang data |
| Multi-instance aktif | Empat slot proses dengan lease instance dan slot | Dua guest background terus bekerja; stop satu tidak mematikan yang lain; batas slot jelas |
| Startup aplikasi umum | Persiapan IO, constructor/attach/onCreate di main thread | Application AndroidX/Firebase/Unity, initialization provider, multidex dan native library pada perangkat ARM |
| Layar aplikasi umum | **Belum** | Launcher yang benar terbuka; Activity kedua, Back, rotasi, task/result dan lifecycle benar |
| Service / Broadcast / Provider | Registry saja; **belum dieksekusi** | start/bind service, receiver dinamis, provider authority per instance, lifecycle dan proses mati |
| Library native | Ekstraksi bounded untuk ABI yang cocok dengan proses | Library JNI benar dimuat, native dependency, ELF 16 KB, pengujian ARM64; syscall redirect belum ada |
| Akun dan konektivitas | Penyimpanan kooperatif; **login belum dibuktikan** | Dua akun pada aplikasi uji dengan server lokal, sesi bertahan setelah restart, izin jaringan eksplisit |
| Notifikasi / permission / Google services | **Belum** | Namespace notifikasi, per-instance permission UX, kompatibilitas service dan account manager; tidak mengklaim Play Integrity |
| Kelola instance | Nama, duplikasi bersih, reset data, hapus, shortcut pin, laporan | Snapshot/restore atomik yang mencakup files/DB/preferences; ekspor/impor; kuota penyimpanan |
| Background / jendela kecil | Runtime dapat berada di background, panel video tersedia | PiP runtime, foreground policy yang sah, tes Doze dan vendor battery policy; tidak menjanjikan anti-kill |
| Kamera virtual | API opt-in fixture; **belum Camera1/2/CameraX** | Camera1 preview, Camera2 capture session, CameraX, audio/video pacing, ganti sumber tanpa restart |
| Identitas virtual | Profil test ID saja | Audit seluruh Settings/Build/Telephony call yang didukung, error eksplisit untuk API tak didukung |
| Root guest / ROM / desktop Android | **Belum; membutuhkan backend berbeda** | Proof of concept backend sistem Android terpisah, lisensi image dan kompatibilitas hardware; tidak menaikkan izin/root host |
| Isolasi keamanan | **Tidak ada**; semua slot tetap UID host | Threat model dan backend batas UID/OS; uji akses silang; tidak digunakan sebagai sandbox malware |
| Instrumentasi / QA | Hash APK, event runtime terakhir, ekspor laporan, kamera deterministik | Log terstruktur, crash native, metrik CPU/PSS/FPS/startup, skenario replay dan hasil yang bisa dibandingkan |
| Performa / distribusi | Debug dan R8 CI; **tanpa benchmark pembanding** | Benchmark perangkat sama dengan workload sama, RAM/storage/baterai/thermal, release signing dan pembaruan |

## Urutan implementasi

1. Alpha.6: kendali proses dan pengelolaan instance (batch ini), validasi regresi pada API 26/34/36.
2. Runtime Activity: buat proof of concept yang diuji pada API 26/34/36 dan HP ARM64 sebelum mengaktifkan tombol membuka layar APK umum. Delegasi Application saja tidak dihitung sebagai keberhasilan.
3. Jalur komponen: Service/Provider/Receiver, permission dan notifikasi, kemudian login dua instance dengan aplikasi uji yang memiliki backend.
4. Kompatibilitas: split APK, native libraries nyata, update data, backup/snapshot, recovery crash dan storage quota.
5. Kamera standar serta alat pengukuran/replay. API kamera fixture tidak dihitung sebagai dukungan kamera aplikasi pihak ketiga.
6. Backend Android virtual penuh untuk ROM/root/desktop dan isolasi. Ini proyek arsitektur terpisah, bukan toggle pada DexClassLoader. Pertahankan container sebagai backend ringan bila lolos benchmark.

## Bukti yang wajib ada

Setiap kemampuan memiliki tes positif, skenario gagal dan hasil fisik bila bergantung hardware/vendor. CI emulator tidak membuktikan kompatibilitas semua APK, bank/game, root, keamanan, atau performa. Klaim bahwa sumber daya lebih ringan dari VMOS/Virtual Master hanya boleh diterbitkan setelah benchmark yang setara.
