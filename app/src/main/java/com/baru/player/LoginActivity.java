package com.baru.player;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.Python;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Masuk ke akun lewat WebView, lalu cookie-nya diekspor sebagai cookies.txt (format Netscape)
 * ke folder cookie aplikasi. Pengganti "salin cookies.txt dari browser" di baru.sh.
 */
public class LoginActivity extends AppCompatActivity {

    public static final String EXTRA_SITE = "site";
    public static final String EXTRA_COOKIE_DIR = "cookie_dir";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private String site;
    private String cookieDir;

    /** YouTube/Google menolak login lewat WebView, jadi tidak didukung. */
    static boolean supports(String site) {
        switch (site) {
            case "facebook":
            case "instagram":
            case "tiktok":
            case "twitter":
            case "twitch":
                return true;
            default:
                return false;
        }
    }

    private static String loginUrl(String site) {
        switch (site) {
            case "facebook":  return "https://m.facebook.com/login";
            case "instagram": return "https://www.instagram.com/accounts/login/";
            case "tiktok":    return "https://www.tiktok.com/login";
            case "twitter":   return "https://x.com/i/flow/login";
            default:          return "https://www.twitch.tv/login";
        }
    }

    /** Tiap grup: {domain cookie, url untuk membaca cookie, ...}. */
    private static String[][] groups(String site) {
        switch (site) {
            case "facebook":
                return new String[][]{{".facebook.com", "https://www.facebook.com", "https://m.facebook.com"}};
            case "instagram":
                return new String[][]{{".instagram.com", "https://www.instagram.com"}};
            case "tiktok":
                return new String[][]{{".tiktok.com", "https://www.tiktok.com"}};
            case "twitter":
                return new String[][]{{".x.com", "https://x.com"}, {".twitter.com", "https://twitter.com"}};
            default:
                return new String[][]{{".twitch.tv", "https://www.twitch.tv"}};
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        Ui.applySystemBarPadding(findViewById(R.id.root));

        site = getIntent().getStringExtra(EXTRA_SITE);
        cookieDir = getIntent().getStringExtra(EXTRA_COOKIE_DIR);
        if (site == null || cookieDir == null || !supports(site)) {
            finish();
            return;
        }

        web = findViewById(R.id.webview);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        // hilangkan penanda "; wv" supaya tidak dikenali sebagai WebView
        st.setUserAgentString(st.getUserAgentString().replace("; wv", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            /**
             * TikTok/Instagram/dll kadang nyoba redirect ke skema khusus app
             * (mis. "snssdk1180://...", "intent://...") buat buka app aslinya.
             * WebView tidak bisa memuat itu dan berakhir di error
             * "net::ERR_UNKNOWN_URL_SCHEME", memutus proses login sebelum
             * cookie session sempat terbentuk. Abaikan saja skema non-http(s)
             * supaya WebView tetap di halaman login web.
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // biarkan WebView yang muat seperti biasa
                }
                return true; // abaikan skema app-only, jangan sampai nyasar
            }
        });
        web.loadUrl(loginUrl(site));

        findViewById(R.id.btn_done).setOnClickListener(v -> exportCookies());
    }

    private void exportCookies() {
        CookieManager cm = CookieManager.getInstance();
        long exp = System.currentTimeMillis() / 1000 + 365L * 24 * 3600;

        StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n# Dibuat oleh Baru Player\n\n");
        int n = 0;
        for (String[] g : groups(site)) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 1; i < g.length; i++) {
                String raw = cm.getCookie(g[i]);
                if (raw == null) continue;
                for (String part : raw.split(";")) {
                    String kv = part.trim();
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    map.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(g[0]).append("\tTRUE\t/\tTRUE\t").append(exp).append('\t')
                        .append(e.getKey()).append('\t').append(e.getValue()).append('\n');
                n++;
            }
        }
        if (n == 0) {
            Toast.makeText(this, R.string.login_belum, Toast.LENGTH_LONG).show();
            return;
        }

        final String text = sb.toString();
        io.execute(() -> {
            boolean ok;
            try {
                String json = Python.getInstance().getModule("baru_core")
                        .callAttr("save_cookie", cookieDir, site, text).toString();
                ok = new JSONObject(json).optBoolean("ok");
            } catch (Throwable t) {
                ok = false;
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (saved) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, R.string.login_gagal_simpan, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (web != null) web.destroy();
    }
}
