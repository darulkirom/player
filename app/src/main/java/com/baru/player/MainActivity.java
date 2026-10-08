package com.baru.player;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.View;
import android.view.Gravity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.Python;

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

    /** Binary QuickJS opsional (untuk YouTube). Kosong kalau tidak disertakan. */
    private String jsRuntimePath() {
        File f = new File(getApplicationInfo().nativeLibraryDir, "libqjs.so");
        return f.isFile() ? f.getAbsolutePath() : "";
    }

    // --------------------------------------------------------------- python

    private String callPython(String fn, Object... args) {
        return Python.getInstance().getModule("extractor").callAttr(fn, args).toString();
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

        io.execute(() -> {
            String json;
            try {
                json = callPython("analyze", url, platform,
                        getFilesDir().getAbsolutePath(), jsRuntimePath());
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
        final EditText et = new EditText(this);
        et.setHint(R.string.cookie_hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(5);
        et.setMaxLines(10);
        et.setHorizontallyScrolling(false);

        String clip = clipboardText();
        if (clip.toLowerCase().contains("cookie file")) et.setText(clip);

        String msg = (error == null || error.isEmpty() ? "" : error + "\n\n")
                + getString(R.string.cookie_pesan);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.cookie_judul)
                .setMessage(msg)
                .setView(et)
                .setPositiveButton(R.string.cookie_simpan, (d, w) -> saveCookie(site, et.getText().toString()))
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
        io.execute(() -> {
            String json;
            try {
                json = callPython("save_cookie", getFilesDir().getAbsolutePath(), site, text);
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

    private void play(int pos) {
        if (pos < 0 || pos >= options.size()) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_SHORT).show();
            return;
        }

        final JSONObject o = options.get(pos);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), dp(8));

        root.addView(playerChoice(
                android.R.drawable.ic_media_play,
                getString(R.string.player_bawaan),
                getString(R.string.player_bawaan_desc),
                v -> openBuiltinPlayer(o)));

        root.addView(playerChoice(
                0,
                getString(R.string.network_stream),
                getString(R.string.network_stream_desc),
                v -> openGenuinePlayer(o)));

        new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_pemutar)
                .setView(root)
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private View playerChoice(int icon, String title, String desc, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F2F7);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);

        ImageView image = new ImageView(this);
        if (icon == 0) {
            try {
                image.setImageDrawable(getPackageManager().getApplicationIcon("com.genuine.leone"));
            } catch (Exception e) {
                image.setImageResource(android.R.drawable.ic_media_play);
            }
        } else {
            image.setImageResource(icon);
        }
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(0xFFFFFFFF);
        iconBg.setShape(GradientDrawable.OVAL);
        image.setBackground(iconBg);
        card.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(0xFF202124);

        TextView sub = new TextView(this);
        sub.setText(desc);
        sub.setTextSize(13);
        sub.setTextColor(0xFF6B6B70);
        sub.setPadding(0, dp(3), 0, 0);

        texts.addView(name);
        texts.addView(sub);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void openBuiltinPlayer(JSONObject o) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_OPTION, o.toString());
        i.putExtra(PlayerActivity.EXTRA_TITLE, videoTitle);
        startActivity(i);
    }

    /**
     * Buka langsung aplikasi com.genuine.leone.
     * Tidak memakai chooser Android, jadi targetnya benar-benar aplikasi tersebut.
     */
    private void openGenuinePlayer(JSONObject o) {
        String url = streamUrl(o);
        if (url == null) {
            Toast.makeText(this, R.string.server_gagal, Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), mimeFor(o.optString("kind")));
        i.setPackage("com.genuine.leone");
        i.putExtra("title", videoTitle);
        i.putExtra(Intent.EXTRA_TEXT, url);
        i.putExtra("url", url);
        i.putExtra("referUrl", o.optString("referer", ""));

        String[] h = headerArray(o.optJSONObject("headers"));
        if (h.length > 0) {
            i.putExtra("headers", h);
            i.putExtra("http_headers", h);
        }

        try {
            if (i.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, R.string.genuine_tidak_ada, Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.genuine_tidak_ada, Toast.LENGTH_LONG).show();
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

    private void openExternal(String url, JSONObject o) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), mimeFor(o.optString("kind")));
        i.putExtra("title", videoTitle);
        String[] h = headerArray(o.optJSONObject("headers"));
        if (h.length > 0) i.putExtra("headers", h); // dibaca MX Player; app lain mengabaikan
        try {
            startActivity(Intent.createChooser(i, getString(R.string.buka_dengan)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.tidak_ada_player, Toast.LENGTH_LONG).show();
        }
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
