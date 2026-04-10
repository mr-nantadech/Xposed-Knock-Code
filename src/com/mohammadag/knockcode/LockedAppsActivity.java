package com.mohammadag.knockcode;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LockedAppsActivity extends Activity {

    private static class AppItem {
        final String packageName;
        final String label;
        final Drawable icon;
        boolean locked;

        AppItem(String packageName, String label, Drawable icon, boolean locked) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.locked = locked;
        }
    }

    private final List<AppItem> mItems = new ArrayList<>();
    private ListView mListView;
    private AppAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mListView = new ListView(this);
        mListView.setDivider(new ColorDrawable(0xFFE0E0E0));
        mListView.setDividerHeight(1);
        setContentView(mListView);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        loadApps();

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppItem item = mItems.get(position);
                item.locked = !item.locked;
                CheckBox cb = (CheckBox) view.findViewById(R.id.app_checkbox);
                if (cb != null) cb.setChecked(item.locked);
            }
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all;
        if (Build.VERSION.SDK_INT >= 33) {
            all = pm.queryIntentActivities(launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0));
        } else {
            all = pm.queryIntentActivities(launcherIntent, 0);
        }

        Set<String> locked = SettingsHelper.readLockedApps(this);

        mItems.clear();
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (SettingsHelper.PACKAGE_NAME.equals(pkg)) continue;
            mItems.add(new AppItem(
                    pkg,
                    ri.loadLabel(pm).toString(),
                    ri.loadIcon(pm),
                    locked.contains(pkg)));
        }

        Collections.sort(mItems, new Comparator<AppItem>() {
            @Override public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        mAdapter = new AppAdapter();
        mListView.setAdapter(mAdapter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Set<String> selected = new HashSet<>();
        for (AppItem item : mItems) {
            if (item.locked) selected.add(item.packageName);
        }
        SettingsHelper.saveLockedApps(this, selected);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class AppAdapter extends ArrayAdapter<AppItem> {
        AppAdapter() {
            super(LockedAppsActivity.this, R.layout.item_app, mItems);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_app, parent, false);
            }
            AppItem item = getItem(position);
            ((ImageView) convertView.findViewById(R.id.app_icon)).setImageDrawable(item.icon);
            ((TextView)  convertView.findViewById(R.id.app_name)).setText(item.label);
            ((CheckBox)  convertView.findViewById(R.id.app_checkbox)).setChecked(item.locked);
            return convertView;
        }
    }
}
