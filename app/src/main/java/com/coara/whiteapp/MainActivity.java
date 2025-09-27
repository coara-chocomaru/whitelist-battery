package com.coara.whiteapp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private Button appListButton;
    private LinearLayout mainLayout;
    private RecyclerView appRecyclerView;
    private AppAdapter appAdapter;
    private List<AppItem> appItems;
    private Set<String> whitelistSet;
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainLayout = findViewById(R.id.main_layout);
        appListButton = findViewById(R.id.app_list_button);

        checkRootAccess();
    }

    private void checkRootAccess() {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                return isRootAvailable();
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
        }.execute();
    }

    private boolean isRootAvailable() {
        Process process = null;
        BufferedReader is = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo root_test"});
            is = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = is.readLine();
            process.waitFor();
            return line != null && line.equals("root_test");
        } catch (IOException | InterruptedException e) {
            return false;
        } finally {
            try {
                if (is != null) is.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
            }
        }
    }

    private void initializeApp() {
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
                refreshWhitelist();
                if (appItems != null) {
                    for (AppItem item : appItems) {
                        item.isWhitelisted = whitelistSet.contains(item.packageName);
                    }
                    if (appAdapter != null) {
                        appAdapter.notifyDataSetChanged();
                    }
                }
                syncHandler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL);
    }

    private void loadAppList() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                refreshWhitelist();
                appItems = getInstalledApps();
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                setupRecyclerView();
            }
        }.execute();
    }

    private List<AppItem> getInstalledApps() {
        List<AppItem> items = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo packageInfo : packages) {
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String appName = packageInfo.loadLabel(pm).toString();
                String packageName = packageInfo.packageName;
                boolean isWhitelisted = whitelistSet.contains(packageName);
                items.add(new AppItem(appName, packageName, isWhitelisted));
            }
        }
        return items;
    }

    private void setupRecyclerView() {
        if (appRecyclerView == null) {
            appRecyclerView = new RecyclerView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
            );
            mainLayout.addView(appRecyclerView, params);
        }
        appRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        appAdapter = new AppAdapter(appItems, this);
        appRecyclerView.setAdapter(appAdapter);
    }

    private void refreshWhitelist() {
        whitelistSet = getWhitelist();
    }

    private Set<String> getWhitelist() {
        Set<String> set = new HashSet<>();
        Process process = null;
        BufferedReader is = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys deviceidle whitelist"});
            is = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = is.readLine()) != null) {
                if (line.startsWith("+")) {
                    String pkg = line.substring(1).trim();
                    if (!pkg.isEmpty()) {
                        set.add(pkg);
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
        } finally {
            try {
                if (is != null) is.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
            }
        }
        return set;
    }

    public void updateWhitelist(String packageName, boolean add) {
        String command = add ? "+" + packageName : "-" + packageName;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys deviceidle whitelist " + command});
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            Toast.makeText(this, "Failed to update whitelist: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (process != null) process.destroy();
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
            this.items = items;
            this.activity = activity;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.app_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = items.get(position);
            holder.appNameText.setText(item.appName);
            holder.packageNameText.setText(item.packageName);
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
