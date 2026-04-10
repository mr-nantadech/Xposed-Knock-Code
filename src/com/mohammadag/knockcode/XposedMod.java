package com.mohammadag.knockcode;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * Called by Zygote before any app process is forked.
     * Store the module APK path so ResourceHelper can build an AssetManager from it,
     * bypassing MIUI's createPackageContext restriction.
     */
    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        ResourceHelper.setModulePath(startupParam.modulePath);
        XposedBridge.log(TAG + " initZygote modulePath=" + startupParam.modulePath);
    }

    private static final String TAG = "[KnockCode]";

    // ── Lockscreen state ──────────────────────────────────────────────────────
    private static SettingsHelper sSettingsHelper;
    private KnockCodeUnlockView mLockscreenView;

    // ── App locker state ──────────────────────────────────────────────────────
    /** Packages whose current session has been unlocked (cleared when app backgrounds). */
    private static final Set<String> sSessionUnlocked =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Pending re-lock runnables, cancelled when a new Activity resumes in the same app. */
    private static final Map<String, Runnable> sRelockPending = new ConcurrentHashMap<>();

    private static Handler sMainHandler;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // ── 1. Lockscreen replacement (System UI) ─────────────────────────────
        if ("com.android.systemui".equals(lpparam.packageName)) {
            try {
                hookSecurityContainerController(lpparam);
                XposedBridge.log(TAG + " lockscreen hook registered");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " lockscreen hook failed: " + t.getMessage());
            }
            try { hookMiuiPath(lpparam); } catch (Throwable ignored) {}
            return;
        }

        // ── 2. App locker ─────────────────────────────────────────────────────
        if (SettingsHelper.PACKAGE_NAME.equals(lpparam.packageName)) return;
        if ("android".equals(lpparam.packageName)) return;

        Set<String> lockedApps = SettingsHelper.readLockedApps();
        XposedBridge.log(TAG + " pkg=" + lpparam.packageName
                + " lockedApps=" + lockedApps.toString());

        if (lockedApps.contains(lpparam.packageName)) {
            hookAppForLocking(lpparam);
            XposedBridge.log(TAG + " app lock hook SET: " + lpparam.packageName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lockscreen — AOSP Android 12
    // ─────────────────────────────────────────────────────────────────────────

    private void hookSecurityContainerController(LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        final Class<?> SecurityMode = XposedHelpers.findClass(
                "com.android.keyguard.KeyguardSecurityModel$SecurityMode", cl);
        final Class<?> ContainerClass = XposedHelpers.findClass(
                "com.android.keyguard.KeyguardSecurityContainer", cl);
        final Class<?> ControllerClass = XposedHelpers.findClass(
                "com.android.keyguard.KeyguardSecurityContainerController", cl);

        // Grab Context early from the container constructor
        XposedBridge.hookAllConstructors(ContainerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (sSettingsHelper != null) return;
                Context ctx = ((FrameLayout) param.thisObject).getContext();
                initSettingsHelper(ctx);
            }
        });

        findAndHookMethod(ControllerClass, "showSecurityScreen", SecurityMode,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleShowSecurityScreen(param, SecurityMode);
                    }
                });

        try {
            findAndHookMethod(ControllerClass, "onResume", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mLockscreenView != null)
                        mLockscreenView.onResume((Integer) param.args[0]);
                }
            });
        } catch (Throwable ignored) {}

        try {
            findAndHookMethod(ControllerClass, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (mLockscreenView != null) mLockscreenView.onPause();
                }
            });
        } catch (Throwable ignored) {}
    }

    private void handleShowSecurityScreen(
            XC_MethodHook.MethodHookParam param, Class<?> SecurityMode) throws Throwable {
        Object requestedMode = param.args[0];
        Object patternMode   = XposedHelpers.getStaticObjectField(SecurityMode, "Pattern");
        if (!patternMode.equals(requestedMode)) return;

        FrameLayout container = resolveContainer(param.thisObject);
        if (container == null) return;

        Context ctx = container.getContext();
        if (sSettingsHelper == null) initSettingsHelper(ctx);

        // Reuse or create the lockscreen view
        if (mLockscreenView == null || mLockscreenView.getParent() != container) {
            if (mLockscreenView != null && mLockscreenView.getParent() instanceof ViewGroup)
                ((ViewGroup) mLockscreenView.getParent()).removeView(mLockscreenView);

            mLockscreenView = new KnockCodeUnlockView(ctx);
            if (sSettingsHelper != null) mLockscreenView.setSettingsHelper(sSettingsHelper);
            container.addView(mLockscreenView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        Object callback = resolveCallback(param.thisObject);
        if (callback != null) mLockscreenView.setKeyguardCallback(callback);

        try {
            mLockscreenView.setLockPatternUtils(getObjectField(param.thisObject, "mLockPatternUtils"));
        } catch (Throwable ignored) {}

        mLockscreenView.setVisibility(View.VISIBLE);
        mLockscreenView.bringToFront();
        mLockscreenView.onResume(KeyguardSecurityView.VIEW_REVEALED);

        try { XposedHelpers.setObjectField(param.thisObject, "mCurrentSecurityMode", requestedMode); }
        catch (Throwable ignored) {}
        try { XposedHelpers.setObjectField(param.thisObject, "mCurrentSecuritySelection", requestedMode); }
        catch (Throwable ignored) {}

        param.setResult(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lockscreen — MIUI 14 fallback
    // ─────────────────────────────────────────────────────────────────────────

    private void hookMiuiPath(LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        String[] candidates = {
                "com.miui.keyguard.MiuiKeyguardSecurityContainerController",
                "com.miui.keyguard.MiuiKeyguardHostView",
                "miui.keyguard.MiuiKeyguardHostView",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(className, cl);
                final Class<?> sm = findSecurityModeClass(cl);
                if (sm == null) break;
                findAndHookMethod(cls, "showSecurityScreen", sm, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        handleShowSecurityScreen(param, sm);
                    }
                });
                XposedBridge.log(TAG + " MIUI hooked: " + className);
                break;
            } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App locker hooks
    // ─────────────────────────────────────────────────────────────────────────

    private void hookAppForLocking(final LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;

        // Initialise SettingsHelper as early as possible in the target process
        findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (sSettingsHelper == null)
                            initSettingsHelper((Context) param.thisObject);
                    }
                });

        // onResume — show lock overlay if the session is not unlocked
        findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ensureMainHandler();
                        Runnable pending = sRelockPending.remove(pkg);
                        if (pending != null) sMainHandler.removeCallbacks(pending);

                        Activity activity = (Activity) param.thisObject;
                        XposedBridge.log(TAG + " onResume: " + activity.getClass().getName()
                                + " unlocked=" + sSessionUnlocked.contains(pkg));

                        if (sSessionUnlocked.contains(pkg)) return;

                        showAppLockOverlay(activity, pkg);
                    }
                });

        // onPause — schedule re-lock; cancelled if another Activity in the same
        //           app resumes quickly (navigation within the app)
        findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onPause", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sSessionUnlocked.contains(pkg)) return;
                        ensureMainHandler();
                        Runnable task = new Runnable() {
                            @Override public void run() {
                                sSessionUnlocked.remove(pkg);
                                sRelockPending.remove(pkg);
                            }
                        };
                        sRelockPending.put(pkg, task);
                        // 800 ms window — enough to cover Activity transitions
                        sMainHandler.postDelayed(task, 800);
                    }
                });

        // Back button — go home while the lock overlay is visible
        findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onBackPressed", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sSessionUnlocked.contains(pkg)) return;
                        Activity a = (Activity) param.thisObject;
                        // Only intercept if our overlay is actually showing
                        if (!hasAppLockView(a)) return;
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(home);
                        param.setResult(null);
                    }
                });
    }

    private void showAppLockOverlay(final Activity activity, final String pkg) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        // Don't stack overlays
        if (hasAppLockView(activity)) return;

        AppLockView lockView = new AppLockView(activity);
        if (sSettingsHelper != null) lockView.setSettingsHelper(sSettingsHelper);

        XposedBridge.log(TAG + " showAppLockOverlay: adding overlay for " + pkg);

        lockView.setOnUnlockListener(new AppLockView.OnUnlockListener() {
            @Override public void onUnlocked() {
                XposedBridge.log(TAG + " UNLOCKED: " + pkg);
                // Cancel any pending re-lock and mark session as unlocked
                Runnable pending = sRelockPending.remove(pkg);
                if (pending != null && sMainHandler != null)
                    sMainHandler.removeCallbacks(pending);
                sSessionUnlocked.add(pkg);

                // Remove overlay and restore system bars
                ViewGroup parent = (ViewGroup) lockView.getParent();
                if (parent != null) parent.removeView(lockView);
                exitFullScreen(activity);
            }
        });

        decor.addView(lockView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        lockView.bringToFront();
        enterFullScreen(activity);
    }

    @SuppressWarnings("deprecation")
    private static void enterFullScreen(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = activity.getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static void exitFullScreen(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = activity.getWindow().getInsetsController();
                if (c != null) c.show(WindowInsets.Type.systemBars());
            } else {
                activity.getWindow().getDecorView()
                        .setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean hasAppLockView(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (decor.getChildAt(i) instanceof AppLockView) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void initSettingsHelper(Context ctx) {
        String uuid = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        sSettingsHelper = new SettingsHelper(uuid);
    }

    private FrameLayout resolveContainer(Object controller) {
        for (String f : new String[]{"mView", "mKeyguardSecurityContainer", "mContainer"}) {
            try {
                Object obj = getObjectField(controller, f);
                if (obj instanceof FrameLayout) return (FrameLayout) obj;
            } catch (Throwable ignored) {}
        }
        if (controller instanceof FrameLayout) return (FrameLayout) controller;
        return null;
    }

    private Object resolveCallback(Object controller) {
        for (String f : new String[]{"mKeyguardSecurityCallback", "mCallback", "mSecurityCallback"}) {
            try { return getObjectField(controller, f); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private Class<?> findSecurityModeClass(ClassLoader cl) {
        for (String n : new String[]{
                "com.android.keyguard.KeyguardSecurityModel$SecurityMode",
                "com.miui.keyguard.KeyguardSecurityModel$SecurityMode"}) {
            try { return XposedHelpers.findClass(n, cl); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static void ensureMainHandler() {
        if (sMainHandler == null)
            sMainHandler = new Handler(Looper.getMainLooper());
    }
}
