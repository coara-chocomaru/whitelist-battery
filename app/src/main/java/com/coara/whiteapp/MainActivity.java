package com.coara.whiteapp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
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
    private List<AppItem> appItems = new ArrayList<>();
    private Set<String> whitelistSet = new HashSet<>();
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
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo root_test"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            return line != null && line.trim().equals("root_test");
        } catch (IOException | InterruptedException e) {
            return false;
        } finally {
            try {
                if (reader != null) reader.close();
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
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... voids) {
                        whitelistSet = getWhitelist();
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Void aVoid) {
                        if (appAdapter != null) {
                            appAdapter.notifyDataSetChanged();
                        }
                    }
                }.execute();
                syncHandler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL);
    }

    private void loadAppList() {
        new AsyncTask<Void, Void, List<AppItem>>() {
            @Override
            protected List<AppItem> doInBackground(Void... voids) {
                whitelistSet = getWhitelist();
                return getInstalledAppsViaSu();
            }

            @Override
            protected void onPostExecute(List<AppItem> items) {
                appItems = items;
                setupRecyclerView();
            }
        }.execute();
    }

    private List<AppItem> getInstalledAppsViaSu() {
        List<AppItem> items = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "pm list packages -3"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String pkg = line.substring("package:".length()).trim();
                    String appName = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        CharSequence label = pm.getApplicationLabel(ai);
                        if (label != null) appName = label.toString();
                    } catch (NameNotFoundException e) {
                    }
                    boolean isWhitelisted = whitelistSet.contains(pkg);
                    items.add(new AppItem(appName, pkg, isWhitelisted));
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
        } finally {
            try {
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
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

    private Set<String> getWhitelist() {
        Set<String> set = new HashSet<>();
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "dumpsys deviceidle whitelist"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("+")) {
                    String pkg = line.substring(1).trim();
                    if (!pkg.isEmpty() && pkg.contains(".")) set.add(pkg);
                } else {
                    String[] tokens = line.split("[\t ,;]+");
                    for (String t : tokens) {
                        t = t.replaceAll("[+,]", "").trim();
                        if (t.contains(".") && t.matches("[A-Za-z0-9_\\.\\-]+")) {
                            set.add(t);
                        }
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
        } finally {
            try {
                if (reader != null) reader.close();
                if (process != null) process.destroy();
            } catch (IOException e) {
            }
        }
        return set;
    }

    public void updateWhitelist(final String packageName, final boolean add) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                Process process = null;
                try {
                    String cmd = "dumpsys deviceidle whitelist " + (add ? "+" : "-") + packageName;
                    process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    int exit = process.waitFor();
                    return exit == 0;
                } catch (IOException | InterruptedException e) {
                    return false;
                } finally {
                    if (process != null) process.destroy();
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                whitelistSet = getWhitelist();
                if (appAdapter != null) appAdapter.notifyDataSetChanged();
                if (!success) Toast.makeText(MainActivity.this, "Failed to update whitelist", Toast.LENGTH_SHORT).show();
            }
        }.execute();
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
            this.items = items != null ? items : new ArrayList<AppItem>();
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
