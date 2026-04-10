package com.mohammadag.knockcode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import de.robv.android.xposed.XSharedPreferences;

/**
 * Central settings store for the module.
 *
 * Two contexts exist:
 *  1. Module app (MainActivity / ChangeKnockCodeActivity) — has a real Context,
 *     writes via Context.getSharedPreferences().
 *  2. Xposed / hooked process — no writable Context, reads via XSharedPreferences.
 *
 * We use a single plain (unencrypted) world-readable file so that the same data
 * is accessible in both contexts.  SecurePreferences is intentionally avoided:
 * Android 8+ scopes ANDROID_ID per-app-per-cert, so the encryption key derived
 * from ANDROID_ID would differ between the module app and every hooked process.
 */
public class SettingsHelper {

    public static final String PACKAGE_NAME  = "com.mohammadag.knockcode";
    /** Shared-prefs file name — plain, world-readable (LSPosed allows this). */
    public static final String PREFS_NAME    = PACKAGE_NAME + "_settings";
    public static final String INTENT_SETTINGS_CHANGED = PACKAGE_NAME + ".SETTINGS_CHANGED";

    // Keys
    private static final String KEY_PASSCODE      = "passcode";
    private static final String KEY_DRAW_LINES    = "should_draw_lines";
    private static final String KEY_DRAW_FILL     = "should_draw_fill";
    // CSV string — more reliable than StringSet across XSharedPreferences
    private static final String KEY_LOCKED_APPS   = "locked_apps_csv";

    // ── In-app (writable) ─────────────────────────────────────────────────────
    private SharedPreferences mPrefs;   // set when constructed with Context
    private Context mContext;

    // ── Xposed (read-only) ────────────────────────────────────────────────────
    private XSharedPreferences mXPrefs; // set when constructed with uuid (Xposed path)

    private Set<OnSettingsReloadedListener> mReloadListeners;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    /** Xposed context: no writable Context available, uuid unused (kept for API compat). */
    public SettingsHelper(String uuid) {
        mXPrefs = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
        // makeWorldReadable() is deprecated / no-op in LSPosed — omitted.
        reloadSettings();
    }

    /** Module app context. */
    public SettingsHelper(Context context) {
        mContext = context;
        mPrefs   = getWritablePreferences(context);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public void reloadSettings() {
        if (mXPrefs != null) mXPrefs.reload();
        notifyListeners();
    }

    public void addInProcessListener(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) { reloadSettings(); }
        }, new IntentFilter(INTENT_SETTINGS_CHANGED));
    }

    public static void emitSettingsChanged(Context context) {
        context.sendBroadcast(new Intent(INTENT_SETTINGS_CHANGED));
    }

    public void addOnReloadListener(OnSettingsReloadedListener l) {
        if (mReloadListeners == null) mReloadListeners = new HashSet<>();
        mReloadListeners.add(l);
    }

    private void notifyListeners() {
        if (mReloadListeners == null) return;
        try {
            for (OnSettingsReloadedListener l : mReloadListeners) l.onSettingsReloaded();
        } catch (Throwable ignored) {}
    }

    public interface OnSettingsReloadedListener {
        void onSettingsReloaded();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static helper — returns writable prefs for use in the module app
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    public static SharedPreferences getWritablePreferences(Context context) {
        // MODE_WORLD_READABLE: LSPosed suppresses the SecurityException for modules
        // that declare xposedsharedprefs=true, so XSharedPreferences can read this file.
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
    }

    public SharedPreferences.Editor edit() {
        if (mPrefs == null) mPrefs = getWritablePreferences(mContext);
        return mPrefs.edit();
    }

    public Context getContext() { return mContext; }

    // ─────────────────────────────────────────────────────────────────────────
    // Generic getters (app context OR Xposed context)
    // ─────────────────────────────────────────────────────────────────────────

    private String getString(String key, String def) {
        if (mPrefs  != null) return mPrefs.getString(key, def);
        if (mXPrefs != null) return mXPrefs.getString(key, def);
        return def;
    }

    private boolean getBoolean(String key, boolean def) {
        if (mPrefs  != null) return mPrefs.getBoolean(key, def);
        if (mXPrefs != null) return mXPrefs.getBoolean(key, def);
        return def;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getStringSet(String key, Set<String> def) {
        if (mPrefs  != null) return mPrefs.getStringSet(key, def);
        if (mXPrefs != null) return mXPrefs.getStringSet(key, def);
        return def;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Passcode
    // ─────────────────────────────────────────────────────────────────────────

    public void setPasscode(ArrayList<Integer> passcode) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passcode.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(passcode.get(i));
        }
        edit().putString(KEY_PASSCODE, sb.toString()).commit();
        emitSettingsChanged(mContext);
    }

    public ArrayList<Integer> getPasscode() {
        return parsePasscode(getString(KEY_PASSCODE, "1,2,3,4"));
    }

    public ArrayList<Integer> getPasscodeOrNull() {
        String s = getString(KEY_PASSCODE, null);
        return s != null ? parsePasscode(s) : null;
    }

    private static ArrayList<Integer> parsePasscode(String s) {
        ArrayList<Integer> list = new ArrayList<>();
        for (String part : s.split(",")) {
            try { list.add(Integer.parseInt(part.trim())); }
            catch (NumberFormatException ignored) {}
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Appearance
    // ─────────────────────────────────────────────────────────────────────────

    public boolean shouldDrawLines() { return getBoolean(KEY_DRAW_LINES, true); }

    public void setShouldDrawLines(boolean v) {
        edit().putBoolean(KEY_DRAW_LINES, v).commit();
        emitSettingsChanged(mContext);
    }

    public boolean shouldDrawFill() { return getBoolean(KEY_DRAW_FILL, true); }

    public void setShouldDrawFill(boolean v) {
        edit().putBoolean(KEY_DRAW_FILL, v).commit();
        emitSettingsChanged(mContext);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Locked apps
    // ─────────────────────────────────────────────────────────────────────────

    /** Save the set of locked package names (module app context). */
    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    public static void saveLockedApps(Context context, Set<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (sb.length() > 0) sb.append(',');
            sb.append(pkg);
        }
        getWritablePreferences(context).edit()
                .putString(KEY_LOCKED_APPS, sb.toString())
                .commit();
        emitSettingsChanged(context);
    }

    /** Read locked apps — Xposed context (no writable Context available). */
    public static Set<String> readLockedApps() {
        XSharedPreferences prefs = new XSharedPreferences(PACKAGE_NAME, PREFS_NAME);
        prefs.reload();
        return parseCsvSet(prefs.getString(KEY_LOCKED_APPS, ""));
    }

    /** Read locked apps — module app context. */
    public static Set<String> readLockedApps(Context context) {
        return parseCsvSet(
                getWritablePreferences(context).getString(KEY_LOCKED_APPS, ""));
    }

    private static Set<String> parseCsvSet(String csv) {
        Set<String> result = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) return result;
        for (String part : csv.split(",")) {
            String pkg = part.trim();
            if (!pkg.isEmpty()) result.add(pkg);
        }
        return result;
    }
}
