# Baru Player (Android, Java + Chaquopy)

Port dari `baru.sh`. Logika yt-dlp tetap Python (`app/src/main/python/extractor.py`),
UI dan pemutar ditulis Java (ExoPlayer / Media3).

## Cara build
1. Pasang **Android Studio** (terbaru) dan **Python 3.10+** di komputer (dipakai Chaquopy saat build untuk `pip`).
2. File > Open > pilih folder ini. Tunggu Gradle sync (butuh internet, pertama kali agak lama).
   Kalau Android Studio menawarkan upgrade Gradle/AGP, boleh diterima.
3. Colok HP (USB debugging) atau pakai emulator, lalu Run. Untuk APK: Build > Build APK(s).

## Yang berubah dari script
| baru.sh | Di app |
|---|---|
| `read` nomor platform / link | Spinner + kolom link (juga bisa lewat menu Bagikan) |
| yt-dlp -J | `yt_dlp.YoutubeDL` di `extractor.py` |
| cookie dari clipboard (`termux-clipboard-get`) | Dialog tempel cookies.txt, disimpan per situs |
| Playlist .m3u + http.server :8080 | Daftar kualitas di layar, tap untuk memutar |
| Proxy ffmpeg :8090/:8091 (video+audio terpisah) | `MergingMediaSource` di ExoPlayer, **tanpa ffmpeg** |
| HLS terpisah (master buatan sendiri) | Sama, ditulis ke cache lalu dimainkan |
| Satu strategi saja per platform | Semua strategi ditampilkan, urutan prioritas sama dengan script |

## Batasan yang perlu kamu tahu
- **YouTube**: yt-dlp sekarang butuh runtime JavaScript (Deno/Node/QuickJS) untuk YouTube.
  Di Android tidak ada, jadi YouTube bisa gagal. Opsi: taruh binary QuickJS ARM sebagai
  `app/src/main/jniLibs/arm64-v8a/libqjs.so` (kode sudah mencari file ini). Belum saya uji.
  Platform lain (Facebook, TikTok, Instagram, X, Twitch) tidak butuh ini.
- **Update yt-dlp**: situs sering berubah. Build ulang aplikasi secara berkala supaya
  `pip install yt-dlp` mengambil versi terbaru (versi terpasang tampil di layar).
- Video yang bukan H.264 (VP9/AV1) diputar langsung; script lama meng-encode ulang lewat ffmpeg.
  Kalau HP tidak bisa decode codec itu, pilih kualitas lain.
- Aplikasi ini **belum saya build/jalankan** (tidak ada Android SDK di lingkungan saya).
  Logika Python sudah diuji dengan data tiruan; kode Java dan konfigurasi Gradle belum dikompilasi.
- APK ukurannya besar (Python + 3 ABI). Hapus `armeabi-v7a` di `abiFilters` kalau tidak perlu.

## Build lewat GitHub Actions
1. Upload isi folder ini ke repo GitHub (branch `main`).
2. Tab **Actions** > workflow **Build APK** jalan otomatis tiap push (atau klik *Run workflow*).
3. Setelah hijau, buka run-nya dan unduh **BaruPlayer-debug-apk** di bagian *Artifacts*.
4. Mau APK muncul di halaman Releases: `git tag v1.0 && git push --tags`.

APK yang dihasilkan adalah *debug* (ditandatangani kunci debug), cukup untuk dipasang sendiri.
