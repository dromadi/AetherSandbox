# Protokol benchmark perangkat

Belum ada angka performa terukur. Jangan menggunakan angka grand design sebagai hasil alpha.

Catat perangkat/chipset, RAM total, versi Android, versi APK/SHA, ABI, resolusi/FPS/codec/durasi video, mode baterai, suhu awal, dan aplikasi latar.

| Pengujian | Metode | Catatan |
|---|---|---|
| Ukuran APK | Ukuran artefak build debug dan release terpisah | Sertakan semua ABI, jangan samakan APK dengan total storage setelah impor |
| RAM idle | dumpsys meminfo host setelah 60 detik | Laporkan total PSS, bukan Java heap saja |
| RAM guest | Jumlah PSS proses host dan virtual_process | Fixture terbuka; simpan jumlah instance |
| RAM video/overlay | PSS kedua proses dan service aktif | Sertakan native/graphics; bandingkan dengan baseline |
| Launch | am start -W, 10 cold dan 10 warm runs | Laporkan median/p95. Verifikasi guest benar-benar tampil |
| Video | Frame/timestamp trace dan rekaman Perfetto | Video 30 FPS tidak menjadi 60 FPS hanya karena decoder hardware |
| Stabilitas | Loop 30 menit, pause/resume, background, rotasi, ganti video, tutup overlay | Catat ANR, crash, leaked Surface, CPU, suhu, baterai |
| Persistensi | Relaunch, process kill, restart host, reboot, dua instance | Data/ID harus bertahan dan terpisah |

Contoh perintah:

```sh
adb shell dumpsys meminfo com.aether.sandbox
adb shell dumpsys meminfo com.aether.sandbox:virtual_process
adb shell am start -W -n com.aether.sandbox/.MainActivity
adb shell dumpsys activity processes
```

Perbandingan VMOS/VPhone hanya boleh disajikan setelah memakai versi aplikasi, perangkat, workload, serta metode yang sama. RAM total 4 GB tidak berarti 4 GB tersedia bagi aplikasi. OS, layanan, graphics, dan aplikasi lain juga memakai memori.
