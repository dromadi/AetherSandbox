# Batasan arsitektur (Security Guardrails)

Status: disepakati 2026-10-21. Berlaku untuk branch `ccr-08eb172e-6m6hzh` (alpha.5).

AetherSandbox adalah **developer testing tooling / QA automation framework** non-root. Dokumen ini menetapkan batas teknis yang menjaga seluruh pengembangan dalam koridor itu. Batas ini bukan janji keamanan OS (guest tetap memakai UID host); ia menyatakan apa yang **sengaja tidak** dibangun.

## 1. Scope eksekusi APK (target whitelist)

- Runtime container hanya menjalankan **APK fixture internal** (`com.aether.fixture`) yang ditandatangani dengan debug certificate yang sama dengan host. Verifikasi di `SandboxRepository.verifyFixture`: hash SHA-256 cocok, package `com.aether.fixture`, signer sama dengan host, dan metadata `GuestEntry`.
- Tombol **Jalankan** untuk fixture memakai jalur penuh `GuestEntry`. APK pihak ketiga hanya diimpor dan dibaca metadatanya; `GeneralGuestLoader` memuat kode dan `Application.onCreate()` di instance terisolasi untuk inspeksi QA, **tanpa** menyuntik ke aplikasi komersial/tertutup milik orang lain.
- Sistem **tidak** menyediakan injeksi atau hook ke proses aplikasi pihak ketiga yang tidak dimodifikasi.

## 2. Scope mock media & video feed

- Modul `SyntheticMediaPipeline` (MediaExtractor/MediaCodec → Surface) dan `VideoCameraSource` adalah **pemutar video uji mandiri** (synthetic video test vector) untuk pengujian UI/rendering internal dan untuk fixture yang opt-in melalui kontrak `VirtualCamera`.
- Modul ini **tidak** mengimplementasikan stealth injection atau interceptor kamera hardware untuk mengelabui verifikasi biometrik/KYC pada aplikasi pihak ketiga. Jalur kamera hanya menjangkau guest yang secara eksplisit mengimplementasikan `GuestEntry.onVirtualCamera`.
- Setiap keluaran kamera virtual harus dapat dinyatakan sebagai rekaman uji kepada pihak yang menerimanya.

## 3. Arsitektur terisolasi

- Pemisahan tegas antara **modul runner** (`core/`: `ProxyActivity`, `VirtualContext`, `GeneralGuestLoader`, `RuntimeControl`, `RuntimeLease`) dan **media player diagnostik** (`media/`). Media tidak menjadi jalur intersepsi kamera lintas-aplikasi.
- Penyimpanan guest diarahkan ke direktori per instance; tidak ada akses ke data aplikasi lain di perangkat.

## Larangan teknis (tidak boleh ditambahkan tanpa meninjau ulang dokumen ini)

- Unlock hidden/non-SDK API (`HiddenApiBypass` dan sejenisnya) untuk menjalankan komponen aplikasi pihak ketiga.
- Hook ART/method (LSPlant, AliuHook, Xposed-derived) pada proses aplikasi lain.
- Penggantian `ActivityThread.mInstrumentation` atau proxy Binder `IActivityManager`/`IPackageManager`/`ServiceManager` untuk mengelabui `system_server` atas nama paket guest.
- Interceptor `CameraDevice`/`CaptureSession` sintetis yang menyamar sebagai kamera fisik di hadapan aplikasi pihak ketiga.
- Spoofing identitas perangkat tingkat sistem (IMEI, MAC, serial, `Build`) untuk mengelabui anti-fraud.

Refleksi ke metode/SDK publik yang `protected` (mis. `ContextWrapper.attachBaseContext`) diizinkan; itu bukan hidden API.

## Hasil audit — branch `ccr-08eb172e-6m6hzh` (alpha.5)

Metode: `grep -rnE 'HiddenApiBypass|LSPlant|AliuHook|ActivityThread|setAccessible|getDeclaredMethod|getDeclaredField|Class.forName|IActivityManager|IPackageManager|ServiceManager|Instrumentation|mInstrumentation|asInterface|Xposed'` pada sumber non-test `app/`, `domain/`, `guest-api/`, `fixture/`.

Temuan: **satu** pemanggilan refleksi — `GeneralGuestLoader` memanggil `ContextWrapper.attachBaseContext(Context)` (metode `protected` SDK publik) untuk memasang `VirtualContext` ke `Application` guest. Tidak ditemukan hidden-API unlock, ART hook, swap Instrumentation, proxy Binder sistem, atau interceptor kamera. Branch ini **patuh** pada batasan di atas.

## Kebijakan dua jalur (disepakati 2026-10-21)

Pengembangan dipisah menjadi dua jalur yang tidak boleh tercampur tanpa keputusan tertulis baru.

### Jalur produk — QA-safe (dipatuhi guardrails)

- Basis: alpha.5 (branch `ccr-08eb172e-6m6hzh`, PR #3). Patuh penuh pada batasan di atas.
- Hanya jalur ini yang boleh dirilis, dipasang, atau dipromosikan sebagai AetherSandbox.
- Setiap perubahan ke jalur ini wajib lulus audit yang sama (bagian "Hasil audit") sebelum merge: tidak ada hidden-API unlock, ART hook, swap Instrumentation, proxy Binder sistem, atau interceptor kamera.

### Jalur riset — tidak dirilis

- Branch `codex/aether-alpha6`–`alpha9` (PR #4–#7). Berisi kemampuan yang **melewati batas dokumen ini**: `GuestActivityRuntime` (HiddenApiBypass + penggantian `ActivityThread.mInstrumentation`) dan `Camera2VideoBridge` (HiddenApiBypass/LSPlant/AliuHook untuk `CameraDevice`/`CaptureSession` sintetis).
- Status: **eksperimen internal saja.** Tidak di-merge ke `main`, tidak dirilis, tidak dipasarkan, selama guardrails ini berlaku.
- Nilai sahnya adalah riset kompatibilitas runtime. Mengangkat salah satu kemampuannya ke jalur produk memerlukan keputusan tertulis baru yang menilai ulang scope dan risiko penyalahgunaan (KYC/anti-fraud), dan harus memperbarui dokumen ini lebih dulu.
- Interceptor kamera sintetis yang menyamar sebagai kamera fisik di hadapan aplikasi pihak ketiga tetap dilarang untuk diangkat ke produk, apa pun hasil risetnya.
