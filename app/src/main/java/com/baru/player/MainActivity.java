package com.baru.player;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.Python;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<JSONObject> options = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private Spinner spPlatform;
    private EditText etUrl;
    private Button btnGo;
    private ProgressBar progress;
    private TextView tvStatus;
    private TextView tvCookieLoc;
    private Button btnStorage;
    private ArrayAdapter<String> listAdapter;
    private String videoTitle = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spPlatform = findViewById(R.id.sp_platform);
        etUrl = findViewById(R.id.et_url);
        btnGo = findViewById(R.id.btn_go);
        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tv_status);
        tvCookieLoc = findViewById(R.id.tv_cookie_loc);
        btnStorage = findViewById(R.id.btn_storage);
        btnStorage.setOnClickListener(v -> requestStorage());
        ListView lv = findViewById(R.id.lv_options);

        ArrayAdapter<CharSequence> pa = ArrayAdapter.createFromResource(
                this, R.array.platforms, android.R.layout.simple_spinner_item);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPlatform.setAdapter(pa);
        spPlatform.setSelection(4); // "Lainnya (otomatis)"

        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(listAdapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> play(pos));
        lv.setOnItemLongClickListener((parent, view, pos, id) -> {
            copyLink(pos);
            return true;
        });

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            String t = clipboardText();
            if (!t.isEmpty()) etUrl.setText(extractUrl(t));
        });
        btnGo.setOnClickListener(v -> startAnalyze());

        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStorageUi(); // setelah kembali dari layar izin
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStorageUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- input

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) etUrl.setText(extractUrl(t));
        }
    }

    private static String extractUrl(String text) {
        Matcher m = Patterns.WEB_URL.matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private String clipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return t == null ? "" : t.toString();
        }
        return "";
    }

    // -------------------------------------------------- lokasi cookie

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Folder lama (internal, privat). Dipakai sebagai cadangan dan sumber migrasi. */
    private File legacyCookieDir() {
        return new File(getFilesDir(), "cookies");
    }

    /** /Documents/BaruPlayer/cookies kalau izin ada, kalau tidak folder internal. */
    @SuppressWarnings("deprecation")
    private File cookieDir() {
        if (hasStorageAccess()) {
            File d = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "BaruPlayer/cookies");
            if (d.isDirectory() || d.mkdirs()) return d;
        }
        return legacyCookieDir();
    }

    private void refreshStorageUi() {
        tvCookieLoc.setText(getString(R.string.lokasi_cookie, cookieDir().getAbsolutePath()));
        btnStorage.setVisibility(hasStorageAccess() ? View.GONE : View.VISIBLE);
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    /** Binary QuickJS opsional (untuk YouTube). Kosong kalau tidak disertakan. */
    private String jsRuntimePath() {
        File f = new File(getApplicationInfo().nativeLibraryDir, "libqjs.so");
        return f.isFile() ? f.getAbsolutePath() : "";
    }

    // --------------------------------------------------------------- python

    private String callPython(String fn, Object... args) {
        return Python.getInstance().getModule("baru_core").callAttr(fn, args).toString();
    }

    private static String errorJson(Throwable t) {
        try {
            return new JSONObject().put("ok", false).put("error", String.valueOf(t)).toString();
        } catch (JSONException e) {
            return "{\"ok\":false}";
        }
    }

    private void setBusy(boolean busy, String status) {
        btnGo.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvStatus.setText(status);
    }

    private void startAnalyze() {
        final String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.link_kosong, Toast.LENGTH_SHORT).show();
            return;
        }
        final String platform = String.valueOf(spPlatform.getSelectedItemPosition() + 1);

        options.clear();
        labels.clear();
        listAdapter.notifyDataSetChanged();
        setBusy(true, getString(R.string.mengambil_info));

        final String cDir = cookieDir().getAbsolutePath();
        final String lDir = legacyCookieDir().getAbsolutePath();
        final String js = jsRuntimePath();
        io.execute(() -> {
            String json;
            try {
                json = callPython("analyze", url, platform, cDir, lDir, js);
            } catch (Throwable t) {
                json = errorJson(t);
            }
            final String res = json;
            runOnUiThread(() -> onAnalyzed(res));
        });
    }

    private void onAnalyzed(String json) {
        setBusy(false, "");
        try {
            JSONObject r = new JSONObject(json);
            if (r.optBoolean("ok")) {
                videoTitle = r.optString("title", "");
                JSONArray arr = r.getJSONArray("options");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    options.add(o);
                    labels.add(o.optString("label", "?"));
                }
                listAdapter.notifyDataSetChanged();
                tvStatus.setText(videoTitle + "\n(yt-dlp " + r.optString("ytdlp") + ")\n"
                        + getString(R.string.petunjuk));
            } else if (r.optBoolean("need_cookie")) {
                showCookieDialog(r.optString("site", "lain"), r.optString("error"));
            } else {
                tvStatus.setText(r.optString("error", "Gagal."));
            }
        } catch (JSONException e) {
            tvStatus.setText(e.toString());
        }
    }

    // --------------------------------------------------------------- cookie

    private void showCookieDialog(final String site, String error) {
        View content = getLayoutInflater().inflate(R.layout.dialog_cookie, null);
        final EditText et = content.findViewById(R.id.et_cookie);

        String clip = clipboardText();
        if (clip.toLowerCase().contains("cookie file")) et.setText(clip);

        String msg = (error == null || error.isEmpty() ? "" : error + "\n\n")
                + getString(R.string.cookie_pesan);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cookie_judul)
                .setMessage(msg)
                .setView(content)
                .setPositiveButton(R.string.cookie_simpan,
                        (d, w) -> saveCookie(site, et.getText().toString()))
                .setNeutralButton(R.string.tempel, null)
                .setNegativeButton(R.string.batal, null)
                .create();
        dlg.show();
        // supaya tombol "Tempel" tidak menutup dialog
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> et.setText(clipboardText()));
    }

    private void saveCookie(final String site, final String text) {
        setBusy(true, "");
        final String cDir = cookieDir().getAbsolutePath();
        io.execute(() -> {
            String json;
            try {
                json = callPython("save_cookie", cDir, site, text);
            } catch (Throwable t) {
                json = errorJson(t);
            }
            final String res = json;
            runOnUiThread(() -> {
                setBusy(false, "");
                try {
                    JSONObject r = new JSONObject(res);
                    if (r.optBoolean("ok")) {
                        Toast.makeText(this, R.string.cookie_tersimpan, Toast.LENGTH_SHORT).show();
                        startAnalyze(); // coba lagi dengan cookie baru
                    } else {
                        tvStatus.setText(r.optString("error"));
                    }
                } catch (JSONException e) {
                    tvStatus.setText(e.toString());
                }
            });
        });
    }

    // --------------------------------------------------------------- putar

    private static final String LEONE_PACKAGE = "com.genuine.leone";

    private void play(final int pos) {
        if (pos < 0 || pos >= options.size()) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_SHORT).show();
            return;
        }
        final JSONObject o = options.get(pos);

        // Video & audio terpisah tidak bisa jadi satu link: hanya bisa di pemutar bawaan.
        if ("merge".equals(o.optString("kind"))) {
            openBuiltIn(pos);
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.buka_di)
                .setItems(new String[]{
                        getString(R.string.buka_leone),
                        getString(R.string.putar_bawaan)
                }, (d, which) -> {
                    if (which == 0) openLeone(o);
                    else openBuiltIn(pos);
                })
                .show();
    }

    private void openBuiltIn(int pos) {
        JSONArray arr = new JSONArray();
        for (JSONObject x : options) arr.put(x);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_OPTIONS, arr.toString());
        i.putExtra(PlayerActivity.EXTRA_INDEX, pos);
        i.putExtra(PlayerActivity.EXTRA_TITLE, videoTitle);
        startActivity(i);
    }

    private void openLeone(JSONObject o) {
        String url = streamUrl(o);
        if (url == null) {
            Toast.makeText(this, R.string.server_gagal, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(LEONE_PACKAGE);
        i.setDataAndType(Uri.parse(url), mimeFor(o.optString("kind")));
        i.putExtra("title", videoTitle);
        String[] h = headerArray(o.optJSONObject("headers"));
        if (h.length > 0) i.putExtra("headers", h);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            // coba lagi tanpa mime type, siapa tahu Leone hanya mendaftarkan filter URL
            i.setData(Uri.parse(url));
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.leone_gagal, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Link yang dikirim ke pemutar. Untuk hls_split: master .m3u8 lokal. */
    private String streamUrl(JSONObject o) {
        if ("hls_split".equals(o.optString("kind"))) {
            File dir = new File(getFilesDir(), "streams");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "master.m3u8")), StandardCharsets.UTF_8)) {
                w.write(o.optString("master_text"));
            } catch (IOException e) {
                return null;
            }
            if (!LocalServer.ensureStarted(dir)) return null;
            return "http://127.0.0.1:" + LocalServer.PORT + "/master.m3u8";
        }
        String u = o.optString("url", "");
        return u.isEmpty() ? null : u;
    }

    private static String mimeFor(String kind) {
        switch (kind) {
            case "hls":
            case "hls_master":
            case "hls_split":
                return "application/x-mpegURL";
            case "dash":
                return "application/dash+xml";
            default:
                return "video/*";
        }
    }

    private static String[] headerArray(JSONObject h) {
        List<String> l = new ArrayList<>();
        if (h != null) {
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                l.add(k);
                l.add(h.optString(k));
            }
        }
        return l.toArray(new String[0]);
    }

    private void copyLink(int pos) {
        if (pos < 0 || pos >= options.size()) return;
        JSONObject o = options.get(pos);
        if ("merge".equals(o.optString("kind"))) {
            Toast.makeText(this, R.string.tidak_bisa_salin, Toast.LENGTH_LONG).show();
            return;
        }
        String url = streamUrl(o);
        if (url == null) {
            Toast.makeText(this, R.string.server_gagal, Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", url));
            Toast.makeText(this, R.string.link_disalin, Toast.LENGTH_SHORT).show();
        }
    }
}
