# Gerbang kompatibilitas aplikasi alpha.10

Tujuan: membuktikan APK rilis nyata bisa dipakai di runtime Aether, bukan hanya menjalankan Application atau APK fixture.

## APK tolok ukur

- Simple Calculator 5.12.0, upstream https://github.com/SimpleMobileTools/Simple-Calculator/releases/tag/5.12.0
- Binary calculator-fdroid-release.apk tidak dimodifikasi; hanya aset pengujian, tidak dipasang ke PackageManager Android.
- SHA-256: 092629deaeaaea824dbf0cd824989e80b498b1fa07ad5b4c3e744fc64ce5ab2f
- Kode sumber tag 5.12.0 / commit 7ab1a8da5f56056883a5b5d4c4dfc38ec843de06. Lisensi aplikasi upstream tersedia di repositori tersebut; APK upstream tidak dimasukkan ke APK host distribusi.
- CI mengunduh melalui .github/scripts/prepare-real-apk.sh, memverifikasi hash, lalu membundelkannya hanya di APK instrumentasi.

## Skenario

Buka APK, hitung 2+3=5, buka instance kedua untuk 7x8=56, hentikan instance pertama tanpa menghentikan kedua, stop/relaunch dan hitung kembali. Semua tes lama (dua sesi HTTP, kamera, kontrol proses, integritas, data) tetap menjadi gerbang regresi. Provider CRUD/call serta intent implisit/Parcelable/Application context mendapat assertion tambahan pada APK contoh.

## Batas

Self-package PackageManager dan Provider hanya divirtualisasi dalam proses runtime. UID tetap UID host; tidak aman untuk mengeksekusi malware. Authority guest tidak diterbitkan di sistem. Service/receiver, komunikasi Provider lintas aplikasi, observers, permission/notifikasi lengkap, split APK dan Google services belum tersedia penuh. Pool menampung sembilan identitas Activity per slot. Flag yang merusak root task (CLEAR_TASK/MULTIPLE_TASK/NEW_DOCUMENT) ditolak secara eksplisit.

Belum ada klaim kompatibilitas HP fisik ARM64 maupun aplikasi sosial/bank/game. Hasil CI aktual harus dicatat di PR alpha.10. Build lokal tidak tersedia karena Android SDK tidak terpasang dan unduhan Gradle diblokir lingkungan.
