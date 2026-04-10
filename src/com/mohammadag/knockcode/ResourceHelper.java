package com.mohammadag.knockcode;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.lang.reflect.Method;

public class ResourceHelper {

    private static final String MODULE_PACKAGE = "com.mohammadag.knockcode";

    /**
     * Module APK path stored in initZygote() — available before MIUI restrictions apply.
     * Used to build an AssetManager directly from the APK file.
     */
    private static String sModulePath;

    /** Cached Resources built from sModulePath. */
    private static Resources sModuleResources;

    /** Called once from XposedMod.initZygote(). */
    public static void setModulePath(String path) {
        sModulePath = path;
    }

    public static String getString(Context context, int id) {
        return getOwnResources(context).getString(id);
    }

    public static String getString(Context context, int id, Object... args) {
        return getOwnResources(context).getString(id, args);
    }

    public static Resources getOwnResources(Context context) {
        // Best: build Resources from module APK via AssetManager reflection.
        // This works even when createPackageContext is blocked by MIUI.
        if (sModuleResources == null && sModulePath != null) {
            try {
                AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
                Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
                addAssetPath.setAccessible(true);
                addAssetPath.invoke(am, sModulePath);
                sModuleResources = new Resources(am,
                        context.getResources().getDisplayMetrics(),
                        context.getResources().getConfiguration());
            } catch (Exception ignored) {}
        }
        if (sModuleResources != null) return sModuleResources;

        // Fallback A: createPackageContext with IGNORE_SECURITY
        try {
            Context moduleCtx = context.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            return moduleCtx.getResources();
        } catch (NameNotFoundException ignored) {}

        // Fallback B: PackageManager (works when caller is the module app itself)
        try {
            return context.getPackageManager().getResourcesForApplication(MODULE_PACKAGE);
        } catch (NameNotFoundException ignored) {}

        return context.getResources();
    }
}
