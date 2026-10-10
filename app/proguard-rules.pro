# Kode aplikasi kecil; jangan diubah R8 (fragment dipanggil lewat nama dari nav_graph.xml / preferences XML).
-keep class com.baru.player.** { *; }

# yt-dlp dipanggil dari Python lewat Chaquopy (aturan Chaquopy sudah ikut di AAR-nya).
-dontwarn com.chaquo.python.**
