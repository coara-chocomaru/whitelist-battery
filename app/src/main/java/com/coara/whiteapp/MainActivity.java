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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private Button appListButton;
    private LinearLayout mainLayout;
    private RecyclerView appRecyclerView;
    private AppAdapter appAdapter;
    private List<AppItem> appItems = new ArrayList<>();
    private final Set<String> whitelistSet = Collections.synchronizedSet(new HashSet<String>());
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL = 5000;
    private static final String WHITELIST_FILE = "whitelist_sync.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.main_layout);
        appListButton = findViewById(R.id.app_list_button);
        appListButton.setVisibility(View.GONE);
        checkRootAccess();
    }

    private void checkRootAccess() {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                List<String> out = execSuCommandLines("echo root_test");
                if (out == null) return false;
                for (String s : out) {
                    if ("root_test".equals(s.trim())) return true;
                }
                return false;
            }

            @Override
            protected void onPostExecute(Boolean isRooted) {
                if (isRooted) {
                    initializeApp();
                } else {
                    Toast.makeText(MainActivity.this, "Root access is required. Exiting.", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void initializeApp() {
        loadWhitelistFromFile();
        appListButton.setVisibility(View.VISIBLE);
        appListButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadAppList();
            }
        });
        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                Executors.newSingleThreadExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        syncWhitelistAndPackages(false);
                        saveWhitelistToFile();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (appAdapter != null) {
                                    appAdapter.updateItems(appItems);
                                }
                            }
                        });
                    }
                });
                syncHandler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL);
    }

    private void loadAppList() {
        new AsyncTask<Void, Void, List<AppItem>>() {
            @Override
            protected List<AppItem> doInBackground(Void... voids) {
                syncWhitelistAndPackages(true);
                return new ArrayList<>(appItems);
            }

            @Override
            protected void onPostExecute(List<AppItem> items) {
                setupRecyclerView(items);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void syncWhitelistAndPackages(boolean forceReloadPackages) {
        Set<String> newWhitelist = getWhitelistFromDumpsys();
        synchronized (whitelistSet) {
            whitelistSet.clear();
            whitelistSet.addAll(newWhitelist);
        }
        if (forceReloadPackages || appItems.isEmpty()) {
            List<AppItem> packages = getAllPackages();
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
    }

    private List<AppItem> getAllPackages() {
        List<AppItem> list = new ArrayList<>();
        List<String> pmLines = execSuCommandLines("pm list packages");
        if (pmLines == null) pmLines = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (String line : pmLines) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty()) continue;
            String pkg = line;
            if (pkg.startsWith("package:")) pkg = pkg.substring(8);
            if (pkg.isEmpty()) continue;
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence lab = ai.loadLabel(pm);
                if (lab != null) label = lab.toString();
            } catch (Exception e) {
            }
            boolean wh = whitelistSet.contains(pkg);
            list.add(new AppItem(label, pkg, wh));
        }
        return list;
    }

    private Set<String> getWhitelistFromDumpsys() {
        Set<String> set = new HashSet<>();
        List<String> lines = execSuCommandLines("dumpsys deviceidle whitelist");
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

    private List<String> execSuCommandLines(String command) {
        List<String> out = new ArrayList<>();
        Process proc = null;
        BufferedReader br = null;
        BufferedReader bre = null;
        DataOutputStream os = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            bre = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null) {
                out.add(line);
            }
            StringBuilder errBuilder = new StringBuilder();
            while ((line = bre.readLine()) != null) {
                errBuilder.append(line).append("\n");
            }
            proc.waitFor();
            return out;
        } catch (Exception e) {
            return out;
        } finally {
            try {
                if (os != null) os.close();
                if (br != null) br.close();
                if (bre != null) bre.close();
                if (proc != null) proc.destroy();
            } catch (IOException e) {
            }
        }
    }

    public void updateWhitelist(final String packageName, final boolean add) {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                String cmd = "dumpsys deviceidle whitelist " + (add ? "+" + packageName : "-" + packageName);
                execSuCommandLines(cmd);
                synchronized (whitelistSet) {
                    if (add) whitelistSet.add(packageName); else whitelistSet.remove(packageName);
                }
                saveWhitelistToFile();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (appAdapter != null) appAdapter.notifyDataSetChanged();
                    }
                });
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
        if (syncHandler != null && syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
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
            this.items = new ArrayList<>(items);
            this.activity = activity;
        }
        void updateItems(List<AppItem> newItems) {
            this.items = new ArrayList<>(newItems);
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
}
