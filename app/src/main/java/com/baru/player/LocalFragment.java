package com.baru.player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab Local: video di perangkat. Awalnya tampil semua video (grid 2 kolom); ikon folder di
 * toolbar berpindah ke daftar folder (3 kolom); ketuk folder untuk melihat isinya.
 * Menu titik tiga: urutkan berdasarkan Date / Size / Duration / Name, naik atau turun.
 */
public class LocalFragment extends Fragment {

    // ------------------------------------------------------------ model

    static final class VideoItem {
        final long id;
        final Uri uri;
        final String name;
        final long size;
        final long dateMs;
        final long duration;
        final String bucketId;
        final String bucketName;

        VideoItem(long id, Uri uri, String name, long size, long dateMs, long duration,
                  String bucketId, String bucketName) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.dateMs = dateMs;
            this.duration = duration;
            this.bucketId = bucketId;
            this.bucketName = bucketName;
        }
    }

    static final class Folder {
        final String id;
        final String name;
        int count;

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final int SORT_DATE = 0, SORT_SIZE = 1, SORT_DURATION = 2, SORT_NAME = 3;

    // ------------------------------------------------------------ state

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final List<VideoItem> all = new ArrayList<>();
    private String currentFolder;          // bucketId; null = tidak sedang di dalam folder
    private String currentFolderName = "";
    private boolean foldersMode;           // false = semua video, true = daftar folder
    private int sort = SORT_DATE;
    private boolean asc = true;
    private boolean loaded, loading, denied;

    private RecyclerView gallery, rvFolders;
    private View welcome, rationale, loadingView;
    private TextView noMedia;
    private VideoAdapter videoAdapter;
    private FolderAdapter folderAdapter;
    private OnBackPressedCallback backCallback;

    private final ActivityResultLauncher<String> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                denied = !granted;
                if (granted) loadVideos();
                updateUi();
            });

    private static String permission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), permission())
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        prefs = requireContext().getSharedPreferences("local", Context.MODE_PRIVATE);
        sort = prefs.getInt("sort", SORT_DATE);
        asc = prefs.getBoolean("asc", true);

        gallery = v.findViewById(R.id.gallery);
        rvFolders = v.findViewById(R.id.rv_folders);
        welcome = v.findViewById(R.id.welcome_view);
        rationale = v.findViewById(R.id.permission_rationale_view);
        loadingView = v.findViewById(R.id.loading);
        noMedia = v.findViewById(R.id.no_media);

        gallery.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        rvFolders.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        videoAdapter = new VideoAdapter();
        folderAdapter = new FolderAdapter();
        gallery.setAdapter(videoAdapter);
        rvFolders.setAdapter(folderAdapter);

        v.findViewById(R.id.open_album).setOnClickListener(x -> permLauncher.launch(permission()));
        v.findViewById(R.id.grant_permission_button).setOnClickListener(x -> {
            if (shouldShowRequestPermissionRationale(permission())) {
                permLauncher.launch(permission());
            } else { // ditolak permanen: hanya bisa dari pengaturan aplikasi
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + requireContext().getPackageName())));
            }
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeFolder();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(R.menu.menu_local, menu);
            }

            @Override
            public void onPrepareMenu(@NonNull Menu menu) {
                boolean hasData = hasPermission() && !all.isEmpty();
                MenuItem toggle = menu.findItem(R.id.action_toggle_view);
                toggle.setVisible(hasData && currentFolder == null);
                toggle.setIcon(foldersMode ? R.drawable.ic_grid_view : R.drawable.ic_folder);
                toggle.setTitle(foldersMode ? R.string.action_all : R.string.action_folders);
                menu.setGroupVisible(R.id.group_sort, hasData);
                menu.setGroupVisible(R.id.group_order, hasData);
                int[] sortIds = {R.id.sort_date, R.id.sort_size, R.id.sort_duration, R.id.sort_name};
                menu.findItem(sortIds[sort]).setChecked(true);
                menu.findItem(asc ? R.id.order_asc : R.id.order_desc).setChecked(true);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.action_toggle_view) {
                    foldersMode = !foldersMode;
                    updateUi();
                    return true;
                }
                if (id == R.id.sort_date) sort = SORT_DATE;
                else if (id == R.id.sort_size) sort = SORT_SIZE;
                else if (id == R.id.sort_duration) sort = SORT_DURATION;
                else if (id == R.id.sort_name) sort = SORT_NAME;
                else if (id == R.id.order_asc) asc = true;
                else if (id == R.id.order_desc) asc = false;
                else return false;
                prefs.edit().putInt("sort", sort).putBoolean("asc", asc).apply();
                refreshList();
                updateUi();
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        if (hasPermission() && !loaded) loadVideos();
        refreshList();
        updateUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        // kembali dari layar pengaturan izin
        if (getView() != null && hasPermission() && !loaded && !loading) {
            denied = false;
            loadVideos();
            updateUi();
        }
    }

    @Override
    public void onDestroyView() {
        ActionBar ab = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (ab != null) ab.setDisplayHomeAsUpEnabled(false);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ------------------------------------------------------------ data

    private void loadVideos() {
        loading = true;
        updateUi();
        final Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            final List<VideoItem> list = query(app);
            main.post(() -> {
                loading = false;
                loaded = true;
                all.clear();
                all.addAll(list);
                if (getView() != null && isAdded()) {
                    refreshList();
                    updateUi();
                }
            });
        });
    }

    private static List<VideoItem> query(Context ctx) {
        List<VideoItem> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        try (Cursor c = ctx.getContentResolver().query(collection, proj, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long taken = c.getLong(3);
                long modified = c.getLong(4) * 1000L;
                String bucketId = c.getString(6);
                String bucketName = c.getString(7);
                out.add(new VideoItem(id, ContentUris.withAppendedId(collection, id),
                        name == null ? "?" : name, c.getLong(2),
                        taken > 0 ? taken : modified, c.getLong(5),
                        bucketId == null ? "" : bucketId,
                        bucketName == null ? "" : bucketName));
            }
        } catch (Exception ignored) {
            // izin dicabut di tengah jalan, dan sebagainya: tampilkan daftar kosong
        }
        return out;
    }

    private List<VideoItem> sorted(List<VideoItem> src) {
        List<VideoItem> l = new ArrayList<>(src);
        Comparator<VideoItem> cmp;
        switch (sort) {
            case SORT_SIZE:
                cmp = (a, b) -> Long.compare(a.size, b.size);
                break;
            case SORT_DURATION:
                cmp = (a, b) -> Long.compare(a.duration, b.duration);
                break;
            case SORT_NAME:
                cmp = (a, b) -> a.name.compareToIgnoreCase(b.name);
                break;
            default:
                cmp = (a, b) -> Long.compare(a.dateMs, b.dateMs);
        }
        Collections.sort(l, asc ? cmp : Collections.reverseOrder(cmp));
        return l;
    }

    private void refreshList() {
        if (videoAdapter == null) return;
        List<VideoItem> src = new ArrayList<>();
        for (VideoItem v : all) {
            if (currentFolder == null || currentFolder.equals(v.bucketId)) src.add(v);
        }
        videoAdapter.submit(sorted(src));

        Map<String, Folder> map = new LinkedHashMap<>();
        for (VideoItem v : all) {
            Folder f = map.get(v.bucketId);
            if (f == null) {
                f = new Folder(v.bucketId, v.bucketName);
                map.put(v.bucketId, f);
            }
            f.count++;
        }
        List<Folder> folders = new ArrayList<>(map.values());
        Collections.sort(folders, (a, b) -> a.name.compareToIgnoreCase(b.name));
        folderAdapter.submit(folders);
    }

    // ------------------------------------------------------------ UI

    private void updateUi() {
        if (getView() == null || !isAdded()) return;
        boolean perm = hasPermission();
        boolean hasData = perm && !loading && !all.isEmpty();

        welcome.setVisibility(!perm && !denied ? View.VISIBLE : View.GONE);
        rationale.setVisibility(!perm && denied ? View.VISIBLE : View.GONE);
        loadingView.setVisibility(perm && loading ? View.VISIBLE : View.GONE);
        noMedia.setVisibility(perm && !loading && loaded && all.isEmpty() ? View.VISIBLE : View.GONE);
        gallery.setVisibility(hasData && (currentFolder != null || !foldersMode)
                ? View.VISIBLE : View.GONE);
        rvFolders.setVisibility(hasData && currentFolder == null && foldersMode
                ? View.VISIBLE : View.GONE);

        backCallback.setEnabled(currentFolder != null);
        ActionBar ab = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(currentFolder != null);
            ab.setTitle(currentFolder != null ? currentFolderName : getString(R.string.nav_local));
        }
        requireActivity().invalidateMenu();
    }

    private void openFolder(Folder f) {
        currentFolder = f.id;
        currentFolderName = f.name;
        refreshList();
        updateUi();
        gallery.scrollToPosition(0);
    }

    private void closeFolder() {
        currentFolder = null;
        refreshList();
        updateUi();
    }

    private void play(VideoItem v) {
        Intent i = new Intent(requireContext(), PlayerActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setDataAndType(v.uri, "video/*");
        i.putExtra(PlayerActivity.EXTRA_TITLE, v.name);
        startActivity(i);
    }

    private void share(VideoItem v) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/*");
        send.putExtra(Intent.EXTRA_STREAM, v.uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.video_share)));
    }

    // ------------------------------------------------------------ format

    private static String formatSize(long bytes) {
        double kb = bytes / 1024.0, mb = kb / 1024.0, gb = mb / 1024.0;
        if (gb >= 1) return String.format(Locale.getDefault(), "%.2f GB", gb);
        if (mb >= 1) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.0f KB", kb);
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private static String formatDate(long ms) {
        return new java.text.SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------ thumbnail

    private static final class Thumbs {
        private static final LruCache<Long, Bitmap> CACHE = new LruCache<Long, Bitmap>(
                (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        static void load(Context ctx, final VideoItem v, final ImageView iv) {
            iv.setTag(v.id);
            Bitmap hit = CACHE.get(v.id);
            if (hit != null) {
                iv.setImageBitmap(hit);
                return;
            }
            iv.setImageDrawable(null);
            final Context app = ctx.getApplicationContext();
            POOL.execute(() -> {
                Bitmap b = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        b = app.getContentResolver().loadThumbnail(v.uri, new Size(360, 202), null);
                    } else {
                        b = MediaStore.Video.Thumbnails.getThumbnail(app.getContentResolver(),
                                v.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                    }
                } catch (Exception ignored) {
                }
                if (b == null) return;
                final Bitmap bm = b;
                CACHE.put(v.id, bm);
                MAIN.post(() -> {
                    Object t = iv.getTag();
                    if (t instanceof Long && (Long) t == v.id) iv.setImageBitmap(bm);
                });
            });
        }
    }

    // ------------------------------------------------------------ adapter

    private final class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {
        private List<VideoItem> items = new ArrayList<>();

        void submit(List<VideoItem> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final VideoItem v = items.get(position);
            h.name.setText(v.name);
            h.size.setText(formatSize(v.size));
            h.date.setText(formatDate(v.dateMs));
            h.duration.setText(formatDuration(v.duration));
            Thumbs.load(h.itemView.getContext(), v, h.thumb);
            h.itemView.setOnClickListener(x -> play(v));
            h.more.setOnClickListener(x -> {
                PopupMenu pm = new PopupMenu(x.getContext(), x);
                pm.getMenu().add(0, 1, 0, R.string.video_play);
                pm.getMenu().add(0, 2, 1, R.string.video_share);
                pm.setOnMenuItemClickListener(mi -> {
                    if (mi.getItemId() == 1) play(v);
                    else share(v);
                    return true;
                });
                pm.show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView thumb, more;
            final TextView name, size, date, duration;

            VH(@NonNull View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.thumb);
                more = itemView.findViewById(R.id.more);
                name = itemView.findViewById(R.id.name);
                size = itemView.findViewById(R.id.size);
                date = itemView.findViewById(R.id.date);
                duration = itemView.findViewById(R.id.duration);
            }
        }
    }

    private final class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {
        private List<Folder> items = new ArrayList<>();

        void submit(List<Folder> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_folder, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final Folder f = items.get(position);
            h.name.setText(f.name);
            h.count.setText(getString(R.string.videos_count, f.count));
            h.itemView.setOnClickListener(x -> openFolder(f));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name, count;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.folder_name);
                count = itemView.findViewById(R.id.folder_count);
            }
        }
    }
}
