# StylusHost

Aplikasi Android untuk HP utama. Proyek Kotlin/XML ini menggunakan `minSdk 26`, `targetSdk 34`, MediaProjection + foreground service untuk menangkap layar landscape 1280×720, encoding JPEG, dan server WebSocket lokal di port `8080`.

## Build APK lewat GitHub

1. Buat repository GitHub dan upload seluruh isi folder proyek ini.
2. Buka tab **Actions** dan jalankan workflow **Build APK**, atau push commit.
3. Unduh artifact `StylusHost-debug-apk` setelah workflow selesai. APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

Workflow memakai Java 17, Gradle 8.7, Android SDK, `gradle assembleDebug`, dan `actions/upload-artifact@v4`.

## Cara menjalankan

1. Install APK di HP Android utama dan sambungkan HP serta tablet ke Wi-Fi yang sama.
2. Buka StylusHost, aktifkan **StylusHost Remote Stylus** di Settings → Accessibility.
3. Tekan **Mulai Screen Cast** dan setujui dialog perekaman layar Android.
4. Catat IP lokal dan kode pairing yang tampil.
5. Buka ibisPaint X di HP, pilih orientasi landscape, lalu hubungkan tablet menggunakan IP dan kode pairing.
6. Biarkan notifikasi foreground service tetap aktif selama sesi. Tekan Stop di aplikasi atau notifikasi untuk mengakhiri sesi.

## Protokol WebSocket

- URL: `ws://<IP-HP>:8080`
- Client mengirim `{"type":"auth","token":"<kode>"}`.
- Server menjawab `{"type":"auth_ok"}` jika kode cocok.
- Perintah sentuh: `{"type":"touch","action":"down|move|up|cancel","x":0.0,"y":0.0,"pressure":0.5}`.
- Frame JPEG dikirim sebagai WebSocket binary message pada interval target sekitar 40 ms (maksimum sekitar 25 fps).

Server hanya mengirim frame dan menerima sentuhan dari koneksi yang berhasil melewati pairing. Gunakan hanya di jaringan tepercaya; WebSocket ini tidak memakai TLS.

## Batasan teknis

- `dispatchGesture()` menghasilkan gesture sintetis melalui Accessibility API. Ia bukan koneksi USB/HID atau digitizer eksternal.
- Pressure diterima dan diteruskan sebagai metadata, tetapi API `GestureDescription` tidak menyediakan setter tekanan stylus. Tilt, barrel button, eraser, dan palm rejection tingkat driver juga tidak diemulasikan.
- Gestur dapat dibatalkan Android atau aplikasi lain, dan kompatibilitas ibisPaint X bergantung pada cara aplikasi memproses input sintetis.
- Penggunaan Accessibility harus diaktifkan secara eksplisit oleh pemilik perangkat. MediaProjection juga meminta persetujuan pengguna setiap sesi.
- Stream 720p JPEG kualitas 65 pada 25 fps adalah target; throughput dan frame rate aktual bergantung pada HP, Wi-Fi, dan beban perangkat.
