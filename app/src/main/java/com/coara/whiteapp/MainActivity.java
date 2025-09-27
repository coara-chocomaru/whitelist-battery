package com.coara.whiteapp;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private Button systemAppListButton;
    private Button userAppListButton;
    private LinearLayout mainLayout;
    private RecyclerView appRecyclerView;
    private AppAdapter appAdapter;
    private final List<AppItem> appItems = Collections.synchronizedList(new ArrayList<AppItem>());
    private final Set<String> whitelistSet = Collections.synchronizedSet(new HashSet<String>());
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL = 5000;
    private static final String WHITELIST_FILE = "whitelist_sync.txt";
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private SuShellManager suShell;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.main_layout);
        systemAppListButton = findViewById(R.id.app_list_button);
        userAppListButton = findViewById(R.id.app_ulist_button);
        systemAppListButton.setVisibility(View.GONE);
        userAppListButton.setVisibility(View.GONE);
        suShell = new SuShellManager();
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                suShell.start();
                checkRootAccess();
            }
        });
    }

    private void checkRootAccess() {
        List<String> out = suShell.exec("echo root_test", 5000);
        boolean ok = false;
        if (out != null) {
            for (String s : out) {
                if ("root_test".equals(s != null ? s.trim() : null)) {
                    ok = true;
                    break;
                }
            }
        }
        final boolean rooted = ok;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (rooted) {
                    initializeApp();
                } else {
                    Toast.makeText(MainActivity.this, "Root access is required. Exiting.", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        });
    }

    private void initializeApp() {
        loadWhitelistFromFile();
        systemAppListButton.setVisibility(View.VISIBLE);
        userAppListButton.setVisibility(View.VISIBLE);
        systemAppListButton.setText("システムアプリ一覧");
        userAppListButton.setText("ユーザーアプリ一覧");
        systemAppListButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadPackagesWithCommand("pm list packages -s");
            }
        });
        userAppListButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadPackagesWithCommand("pm list packages -3");
            }
        });
        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                backgroundExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            syncWhitelistAndPackages(false);
                            saveWhitelistToFile();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFinishing() && !isDestroyed()) {
                                        if (appAdapter != null) appAdapter.updateItems(new ArrayList<AppItem>(appItems));
                                    }
                                }
                            });
                        } catch (Throwable t) {
                        }
                    }
                });
                if (syncHandler != null) syncHandler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL);
    }

    private void loadPackagesWithCommand(final String pmCommand) {
        new AsyncTask<Void, Void, List<AppItem>>() {
            @Override
            protected List<AppItem> doInBackground(Void... voids) {
                try {
                    List<String> pmLines = suShell.exec(pmCommand, 10000);
                    if (pmLines == null) pmLines = new ArrayList<String>();
                    Set<String> currentWhitelist = getWhitelistFromDumpsys();
                    synchronized (whitelistSet) {
                        whitelistSet.clear();
                        whitelistSet.addAll(currentWhitelist);
                    }
                    Map<String, String> labelMap = buildPackageLabelMap();
                    List<AppItem> list = buildAppItemsFromPackageLines(pmLines, labelMap);
                    synchronized (appItems) {
                        appItems.clear();
                        appItems.addAll(list);
                    }
                    return new ArrayList<AppItem>(list);
                } catch (Throwable t) {
                    synchronized (appItems) {
                        return new ArrayList<AppItem>(appItems);
                    }
                }
            }

            @Override
            protected void onPostExecute(List<AppItem> items) {
                if (!isFinishing() && !isDestroyed()) setupRecyclerView(items);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Map<String, String> buildPackageLabelMap() {
        Map<String, String> map = new HashMap<>();
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (apps != null) {
                for (ApplicationInfo ai : apps) {
                    try {
                        CharSequence lab = pm.getApplicationLabel(ai);
                        if (lab != null && lab.length() > 0) {
                            map.put(ai.packageName, lab.toString());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
        }
        return map;
    }

    private List<AppItem> buildAppItemsFromPackageLines(List<String> pmLines, Map<String, String> labelMap) {
        List<AppItem> list = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String line : pmLines) {
            if (line == null) continue;
            String l = line.trim();
            if (l.isEmpty()) continue;
            String pkg = l;
            if (pkg.startsWith("package:")) pkg = pkg.substring(8);
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            String label = null;
            if (labelMap != null) label = labelMap.get(pkg);
            if (label == null) label = resolveAppLabel(pkg, pm);
            boolean wh = whitelistSet.contains(pkg);
            list.add(new AppItem(label != null && label.length() > 0 ? label : pkg, pkg, wh));
        }
        return list;
    }

    private String resolveAppLabel(String pkg, PackageManager pm) {
        String label = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            if (ai != null) {
                CharSequence lab = pm.getApplicationLabel(ai);
                if (lab != null && lab.length() > 0) return lab.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_META_DATA);
            if (pi != null && pi.applicationInfo != null) {
                CharSequence lab = pm.getApplicationLabel(pi.applicationInfo);
                if (lab != null && lab.length() > 0) return lab.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            ApplicationInfo ai2 = pm.getApplicationInfo(pkg, 0);
            if (ai2 != null) {
                CharSequence lab2 = pm.getApplicationLabel(ai2);
                if (lab2 != null && lab2.length() > 0) return lab2.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pkgInfo = pm.getPackageInfo(pkg, 0);
            if (pkgInfo != null && pkgInfo.applicationInfo != null && pkgInfo.applicationInfo.sourceDir != null) {
                String src = pkgInfo.applicationInfo.sourceDir;
                PackageInfo apkInfo = pm.getPackageArchiveInfo(src, PackageManager.GET_META_DATA);
                if (apkInfo != null && apkInfo.applicationInfo != null) {
                    ApplicationInfo a = apkInfo.applicationInfo;
                    a.sourceDir = src;
                    a.publicSourceDir = src;
                    CharSequence lab = pm.getApplicationLabel(a);
                    if (lab != null && lab.length() > 0) return lab.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return label;
    }

    private void loadAppList() {
        new AsyncTask<Void, Void, List<AppItem>>() {
            @Override
            protected List<AppItem> doInBackground(Void... voids) {
                try {
                    syncWhitelistAndPackages(true);
                    return new ArrayList<AppItem>(appItems);
                } catch (Throwable t) {
                    return new ArrayList<AppItem>(appItems);
                }
            }

            @Override
            protected void onPostExecute(List<AppItem> items) {
                if (!isFinishing() && !isDestroyed()) setupRecyclerView(items);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void syncWhitelistAndPackages(boolean forceReloadPackages) {
        try {
            Set<String> newWhitelist = getWhitelistFromDumpsys();
            synchronized (whitelistSet) {
                whitelistSet.clear();
                whitelistSet.addAll(newWhitelist);
            }
            if (forceReloadPackages || appItems.isEmpty()) {
                List<String> pmLines = suShell.exec("pm list packages", 10000);
                if (pmLines == null) pmLines = new ArrayList<String>();
                Map<String, String> labelMap = buildPackageLabelMap();
                List<AppItem> packages = buildAppItemsFromPackageLines(pmLines, labelMap);
                synchronized (appItems) {
                    appItems.clear();
                    appItems.addAll(packages);
                }
            } else {
                synchronized (appItems) {
                    for (AppItem ai : appItems) {
                        ai.isWhitelisted = whitelistSet.contains(ai.packageName);
                    }
                }
            }
        } catch (Throwable t) {
        }
    }

    private Set<String> getWhitelistFromDumpsys() {
        Set<String> set = new HashSet<>();
        List<String> lines = suShell.exec("dumpsys deviceidle whitelist", 10000);
        if (lines == null) return set;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("+")) {
                String pkg = trimmed.substring(1).trim();
                if (isValidPackageName(pkg)) set.add(pkg);
                continue;
            }
            String[] parts = trimmed.split("[ ,\\[\\]]+");
            for (String p : parts) {
                if (isValidPackageName(p)) set.add(p);
            }
        }
        return set;
    }

    private boolean isValidPackageName(String s) {
        if (s == null) return false;
        s = s.trim();
        if (!s.contains(".")) return false;
        if (s.length() < 3) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) return false;
        }
        return true;
    }

    public void updateWhitelist(final String packageName, final boolean add) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String cmd = "dumpsys deviceidle whitelist " + (add ? ("+" + packageName) : ("-" + packageName));
                    suShell.exec(cmd, 8000);
                    synchronized (whitelistSet) {
                        if (add) whitelistSet.add(packageName); else whitelistSet.remove(packageName);
                    }
                    saveWhitelistToFile();
                    final String msg = add ? "バッテリー制限のwhitelistに追加しました on" : "バッテリー制限のwhitelistから削除しました。off";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                                if (appAdapter != null) appAdapter.notifyDataSetChanged();
                            }
                        }
                    });
                } catch (Throwable t) {
                    final String err = "Failed to update whitelist";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing() && !isDestroyed()) Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void setupRecyclerView(List<AppItem> items) {
        if (appRecyclerView == null) {
            appRecyclerView = new RecyclerView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            );
            mainLayout.addView(appRecyclerView, params);
        }
        appRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        appAdapter = new AppAdapter(items, this);
        appRecyclerView.setAdapter(appAdapter);
    }

    private void saveWhitelistToFile() {
        BufferedWriter bw = null;
        try {
            FileOutputStream fos = openFileOutput(WHITELIST_FILE, MODE_PRIVATE);
            bw = new BufferedWriter(new OutputStreamWriter(fos));
            synchronized (whitelistSet) {
                for (String pkg : whitelistSet) {
                    bw.write(pkg);
                    bw.newLine();
                }
            }
            bw.flush();
        } catch (Exception e) {
        } finally {
            try {
                if (bw != null) bw.close();
            } catch (IOException e) {
            }
        }
    }

    private void loadWhitelistFromFile() {
        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            fis = openFileInput(WHITELIST_FILE);
            br = new BufferedReader(new InputStreamReader(fis));
            String line;
            synchronized (whitelistSet) {
                whitelistSet.clear();
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && isValidPackageName(t)) whitelistSet.add(t);
                }
            }
        } catch (Exception e) {
        } finally {
            try {
                if (br != null) br.close();
                if (fis != null) fis.close();
            } catch (IOException e) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (syncHandler != null && syncRunnable != null) {
                syncHandler.removeCallbacks(syncRunnable);
            }
            backgroundExecutor.shutdownNow();
            if (suShell != null) suShell.stop();
        } catch (Throwable t) {
        }
    }

    private static class AppItem {
        String appName;
        String packageName;
        boolean isWhitelisted;
        AppItem(String appName, String packageName, boolean isWhitelisted) {
            this.appName = appName;
            this.packageName = packageName;
            this.isWhitelisted = isWhitelisted;
        }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        private List<AppItem> items;
        private MainActivity activity;
        AppAdapter(List<AppItem> items, MainActivity activity) {
            this.items = new ArrayList<AppItem>(items);
            this.activity = activity;
        }
        void updateItems(List<AppItem> newItems) {
            this.items = new ArrayList<AppItem>(newItems);
            notifyDataSetChanged();
        }
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_item, parent, false);
            return new ViewHolder(view);
        }
        @Override
        public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
            final AppItem item = items.get(position);
            holder.appNameText.setText(item.appName);
            holder.packageNameText.setText(item.packageName);
            holder.toggleSwitch.setOnCheckedChangeListener(null);
            holder.toggleSwitch.setChecked(item.isWhitelisted);
            holder.toggleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    item.isWhitelisted = isChecked;
                    activity.updateWhitelist(item.packageName, isChecked);
                }
            });
        }
        @Override
        public int getItemCount() {
            return items.size();
        }
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView appNameText;
            TextView packageNameText;
            Switch toggleSwitch;
            ViewHolder(View itemView) {
                super(itemView);
                appNameText = itemView.findViewById(R.id.app_name);
                packageNameText = itemView.findViewById(R.id.package_name);
                toggleSwitch = itemView.findViewById(R.id.toggle_switch);
            }
        }
    }

    private static class SuShellManager {
        private Process proc;
        private DataOutputStream os;
        private BufferedReader in;
        private BufferedReader err;
        private final Object lock = new Object();
        private volatile boolean started = false;
        private final Random rnd = new Random();

        boolean start() {
            synchronized (lock) {
                if (started) return true;
                try {
                    proc = Runtime.getRuntime().exec("su");
                    os = new DataOutputStream(proc.getOutputStream());
                    in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    err = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                    started = true;
                    return true;
                } catch (Throwable t) {
                    started = false;
                    try {
                        if (os != null) os.close();
                        if (in != null) in.close();
                        if (err != null) err.close();
                        if (proc != null) proc.destroy();
                    } catch (IOException e) {
                    }
                    os = null;
                    in = null;
                    err = null;
                    proc = null;
                    return false;
                }
            }
        }

        List<String> exec(String command, long timeoutMs) {
            List<String> out = new ArrayList<String>();
            synchronized (lock) {
                if (!started) {
                    if (!start()) return out;
                }
                String marker = "__END__" + Long.toHexString(System.nanoTime()) + Integer.toHexString(rnd.nextInt());
                try {
                    os.writeBytes(command + "\n");
                    os.writeBytes("echo " + marker + "\n");
                    os.flush();
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    String line;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            if (in != null && in.ready()) {
                                line = in.readLine();
                                if (line == null) break;
                                if (line.equals(marker)) break;
                                out.add(line);
                                continue;
                            }
                            if (err != null && err.ready()) {
                                err.readLine();
                                continue;
                            }
                        } catch (IOException ioe) {
                        }
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Throwable t) {
                }
                return out;
            }
        }

        void stop() {
            synchronized (lock) {
                try {
                    if (os != null) {
                        try {
                            os.writeBytes("exit\n");
                            os.flush();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable t) {
                } finally {
                    try {
                        if (os != null) os.close();
                        if (in != null) in.close();
                        if (err != null) err.close();
                        if (proc != null) proc.destroy();
                    } catch (IOException e) {
                    }
                    os = null;
                    in = null;
                    err = null;
                    proc = null;
                    started = false;
                }
            }
        }
    }
}
