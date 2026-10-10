package com.baru.player;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

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
import java.util.regex.Matcher;

public class HomeFragment extends Fragment {

    private HomeViewModel vm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> loginLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Toast.makeText(requireContext(), R.string.cookie_tersimpan, Toast.LENGTH_SHORT).show();
                    startAnalyze(); // coba lagi dengan cookie hasil login
                }
            });

    private Spinner spPlatform;
    private EditText etUrl;
    private Button btnGo;
    private ProgressBar progress;
    private TextView tvStatus;
    private TextView tvCookieLoc;
    private Button btnStorage;
    private ArrayAdapter<String> listAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        vm = new ViewModelProvider(this).get(HomeViewModel.class);

        spPlatform = v.findViewById(R.id.sp_platform);
        etUrl = v.findViewById(R.id.et_url);
        btnGo = v.findViewById(R.id.btn_go);
        progress = v.findViewById(R.id.progress);
        tvStatus = v.findViewById(R.id.tv_status);
        tvCookieLoc = v.findViewById(R.id.tv_cookie_loc);
        btnStorage = v.findViewById(R.id.btn_storage);
        btnStorage.setOnClickListener(x -> requestStorage());
        v.findViewById(R.id.btn_cookies).setOnClickListener(x -> showCookieManager());
        ListView lv = v.findViewById(R.id.lv_options);

        ArrayAdapter<CharSequence> pa = ArrayAdapter.createFromResource(
                requireContext(), R.array.platforms, android.R.layout.simple_spinner_item);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPlatform.setAdapter(pa);
        spPlatform.setSelection(4); // "Lainnya (otomatis)"

        listAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, vm.labels);
        lv.setAdapter(listAdapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> play(pos));
        lv.setOnItemLongClickListener((parent, view, pos, id) -> {
            copyLink(pos);
            return true;
        });

        v.findViewById(R.id.btn_paste).setOnClickListener(x -> {
            String t = clipboardText();
            if (!t.isEmpty()) etUrl.setText(extractUrl(t));
        });
        btnGo.setOnClickListener(x -> startAnalyze());

        // pulihkan tampilan setelah pindah tab
        btnGo.setEnabled(!vm.busy);
        progress.setVisibility(vm.busy ? View.VISIBLE : View.GONE);
        tvStatus.setText(vm.status);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStorageUi(); // setelah kembali dari layar izin
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStorageUi();
    }

    /** Jalankan di main thread; aman walau fragment sudah dilepas. */
    private void ui(Runnable r) {
        main.post(r);
    }

    private boolean viewAlive() {
        return getView() != null && isAdded();
    }

    // ---------------------------------------------------------------- input

    static String extractUrl(String text) {
        Matcher m = Patterns.WEB_URL.matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private String clipboardText() {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
            return t == null ? "" : t.toString();
        }
        return "";
    }

    // -------------------------------------------------- lokasi cookie

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return requireContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Folder lama (internal, privat). Dipakai sebagai cadangan dan sumber migrasi. */
    private File legacyCookieDir() {
        return new File(requireContext().getFilesDir(), "cookies");
    }

    /**
     * Folder cookie utama: selalu privat internal aplikasi, seperti baru.sh
     * menulis ke folder "cookies" lokalnya sendiri. Tidak butuh izin apa pun,
     * jadi baca/tulis cookie selalu berhasil di semua versi Android.
     */
    private File cookieDir() {
        File d = legacyCookieDir();
        d.mkdirs();
        return d;
    }

    private void refreshStorageUi() {
        tvCookieLoc.setText(getString(R.string.lokasi_cookie, cookieDir().getAbsolutePath()));
        // Cookie disimpan di folder internal privat, tidak butuh izin apa pun lagi.
        btnStorage.setVisibility(View.GONE);
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + requireContext().getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    /** Binary QuickJS opsional (untuk YouTube). Kosong kalau tidak disertakan. */
    private String jsRuntimePath() {
        File f = new File(requireContext().getApplicationInfo().nativeLibraryDir, "libqjs.so");
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
        vm.busy = busy;
        vm.status = status;
        if (!viewAlive()) return;
        btnGo.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvStatus.setText(status);
    }

    private void setStatus(String status) {
        vm.status = status;
        if (viewAlive()) tvStatus.setText(status);
    }

    private void startAnalyze() {
        final String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), R.string.link_kosong, Toast.LENGTH_SHORT).show();
            return;
        }
        final String platform = String.valueOf(spPlatform.getSelectedItemPosition() + 1);

        vm.options.clear();
        vm.labels.clear();
        listAdapter.notifyDataSetChanged();
        setBusy(true, getString(R.string.mengambil_info));

        final String cDir = cookieDir().getAbsolutePath();
        final String lDir = legacyCookieDir().getAbsolutePath();
        final String js = jsRuntimePath();
        vm.io.execute(() -> {
            String json;
            try {
                json = callPython("analyze", url, platform, cDir, lDir, js);
            } catch (Throwable t) {
                json = errorJson(t);
            }
            final String res = json;
            ui(() -> onAnalyzed(res));
        });
    }

    private void onAnalyzed(String json) {
        setBusy(false, "");
        try {
            JSONObject r = new JSONObject(json);
            if (r.optBoolean("ok")) {
                vm.videoTitle = r.optString("title", "");
                JSONArray arr = r.getJSONArray("options");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    vm.options.add(o);
                    vm.labels.add(o.optString("label", "?"));
                }
                if (!isAdded()) return; // pengguna sudah pindah tab; hasil tetap tersimpan di vm
                listAdapter.notifyDataSetChanged();
                setStatus(vm.videoTitle + "\n(yt-dlp " + r.optString("ytdlp") + ")\n"
                        + getString(R.string.petunjuk));
            } else if (r.optBoolean("need_cookie")) {
                if (viewAlive()) showCookieDialog(r.optString("site", "lain"), r.optString("error"));
            } else {
                setStatus(r.optString("error", "Gagal."));
            }
        } catch (JSONException e) {
            setStatus(e.toString());
        }
    }

    // --------------------------------------------------------------- cookie

    private void showCookieDialog(final String site, String error) {
        View content = getLayoutInflater().inflate(R.layout.dialog_cookie, null);
        final EditText et = content.findViewById(R.id.et_cookie);
        final AlertDialog[] ref = new AlertDialog[1];
        Button login = content.findViewById(R.id.btn_login);
        if (LoginActivity.supports(site)) {
            login.setOnClickListener(v -> {
                if (ref[0] != null) ref[0].dismiss();
                openLogin(site);
            });
        } else {
            login.setVisibility(View.GONE); // YouTube/Google menolak login lewat WebView
        }

        String clip = clipboardText();
        if (clip.toLowerCase().contains("cookie file")) et.setText(clip);

        String msg = (error == null || error.isEmpty() ? "" : error + "\n\n")
                + getString(R.string.cookie_pesan);

        AlertDialog dlg = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cookie_judul)
                .setMessage(msg)
                .setView(content)
                .setPositiveButton(R.string.cookie_simpan,
                        (d, w) -> saveCookie(site, et.getText().toString(), true))
                .setNeutralButton(R.string.tempel, null)
                .setNegativeButton(R.string.batal, null)
                .create();
        ref[0] = dlg;
        dlg.show();
        // supaya tombol "Tempel" tidak menutup dialog
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> et.setText(clipboardText()));
    }

    private void openLogin(String site) {
        Intent i = new Intent(requireContext(), LoginActivity.class);
        i.putExtra(LoginActivity.EXTRA_SITE, site);
        i.putExtra(LoginActivity.EXTRA_COOKIE_DIR, cookieDir().getAbsolutePath());
        loginLauncher.launch(i);
    }

    private void saveCookie(final String site, final String text, final boolean retry) {
        setBusy(true, "");
        final String cDir = cookieDir().getAbsolutePath();
        vm.io.execute(() -> {
            String json;
            try {
                json = callPython("save_cookie", cDir, site, text);
            } catch (Throwable t) {
                json = errorJson(t);
            }
            final String res = json;
            ui(() -> {
                setBusy(false, "");
                if (!isAdded()) return;
                try {
                    JSONObject r = new JSONObject(res);
                    if (r.optBoolean("ok")) {
                        String msg = getString(R.string.cookie_tersimpan_di,
                                r.optString("path"), r.optInt("bytes"));
                        setStatus(msg);
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                        if (retry) startAnalyze(); // coba lagi dengan cookie baru
                    } else {
                        String err = r.optString("error");
                        setStatus(err);
                        Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show();
                    }
                } catch (JSONException e) {
                    setStatus(e.toString());
                }
            });
        });
    }

    // ---------------------------------------------------- kelola cookie

    private static final String[] SITES = {
            "youtube", "facebook", "instagram", "tiktok", "twitter", "twitch", "lain"
    };

    private void showCookieManager() {
        final File dir = cookieDir();
        String[] labels = new String[SITES.length];
        for (int i = 0; i < SITES.length; i++) {
            File f = new File(dir, SITES[i] + ".txt");
            labels[i] = SITES[i] + (f.isFile() && f.length() > 0
                    ? "   ✓ " + f.length() + " B" : "   (belum ada)");
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.kelola_cookie)
                .setItems(labels, (d, which) -> showCookieActions(SITES[which]))
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void showCookieActions(final String site) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.tempel_clipboard));
        actions.add(() -> {
            String t = clipboardText();
            if (t.trim().isEmpty()) {
                Toast.makeText(requireContext(), R.string.clipboard_kosong, Toast.LENGTH_LONG).show();
            } else {
                saveCookie(site, t, false);
            }
        });

        if (LoginActivity.supports(site)) {
            labels.add(getString(R.string.masuk_browser));
            actions.add(() -> openLogin(site));
        }

        final File f = new File(cookieDir(), site + ".txt");
        if (f.isFile()) {
            labels.add(getString(R.string.hapus_file));
            actions.add(() -> {
                if (f.delete()) {
                    Toast.makeText(requireContext(), R.string.cookie_dihapus, Toast.LENGTH_SHORT).show();
                }
            });
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(site)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    // --------------------------------------------------------------- putar

    private static final String LEONE_PACKAGE = "com.genuine.leone";

    private void play(final int pos) {
        if (pos < 0 || pos >= vm.options.size()) {
            Toast.makeText(requireContext(), R.string.opsi_tidak_valid, Toast.LENGTH_SHORT).show();
            return;
        }
        final JSONObject o = vm.options.get(pos);

        // Video & audio terpisah tidak bisa jadi satu link: hanya bisa di pemutar bawaan.
        if ("merge".equals(o.optString("kind"))) {
            openBuiltIn(pos);
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
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
        for (JSONObject x : vm.options) arr.put(x);
        Intent i = new Intent(requireContext(), PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_OPTIONS, arr.toString());
        i.putExtra(PlayerActivity.EXTRA_INDEX, pos);
        i.putExtra(PlayerActivity.EXTRA_TITLE, vm.videoTitle);
        startActivity(i);
    }

    private void openLeone(JSONObject o) {
        String url = streamUrl(o);
        if (url == null) {
            Toast.makeText(requireContext(), R.string.server_gagal, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(LEONE_PACKAGE);
        i.setDataAndType(Uri.parse(url), mimeFor(o.optString("kind")));
        i.putExtra("title", vm.videoTitle);
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
                Toast.makeText(requireContext(), R.string.leone_gagal, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Link yang dikirim ke pemutar. Untuk hls_split: master .m3u8 lokal. */
    private String streamUrl(JSONObject o) {
        if ("hls_split".equals(o.optString("kind"))) {
            File dir = new File(requireContext().getFilesDir(), "streams");
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
        if (pos < 0 || pos >= vm.options.size()) return;
        JSONObject o = vm.options.get(pos);
        if ("merge".equals(o.optString("kind"))) {
            Toast.makeText(requireContext(), R.string.tidak_bisa_salin, Toast.LENGTH_LONG).show();
            return;
        }
        String url = streamUrl(o);
        if (url == null) {
            Toast.makeText(requireContext(), R.string.server_gagal, Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", url));
            Toast.makeText(requireContext(), R.string.link_disalin, Toast.LENGTH_SHORT).show();
        }
    }
}
