package com.mohammadag.knockcode;

import java.util.ArrayList;

import android.content.Context;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mohammadag.knockcode.KnockCodeView.Mode;
import com.mohammadag.knockcode.KnockCodeView.OnPositionTappedListener;
import com.mohammadag.knockcode.SettingsHelper.OnSettingsReloadedListener;

import de.robv.android.xposed.XposedHelpers;

public class KnockCodeUnlockView extends LinearLayout
        implements OnPositionTappedListener, KeyguardSecurityView,
        OnSettingsReloadedListener, OnLongClickListener {

    private KnockCodeView mKnockCodeUnlockView;
    private Object mCallback;
    private Object mLockPatternUtils;

    @SuppressWarnings("unused")
    private CountDownTimer mCountdownTimer;

    protected Context mContext;

    @SuppressWarnings("unused")
    private int mTotalFailedPatternAttempts = 0;
    private int mFailedPatternAttemptsSinceLastTimeout = 0;

    private final ArrayList<Integer> mTappedPositions = new ArrayList<>();
    private TextView mTextView;
    private SettingsHelper mSettingsHelper;

    private static final ArrayList<Integer> mPasscode = new ArrayList<>();

    static {
        mPasscode.add(1);
        mPasscode.add(2);
        mPasscode.add(3);
        mPasscode.add(4);
    }

    public KnockCodeUnlockView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        mContext = context;

        mTextView = new TextView(context);
        mTextView.setGravity(Gravity.CENTER);
        mTextView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.1f));
        int spacing = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 5, getResources().getDisplayMetrics());
        mTextView.setPadding(mTextView.getPaddingLeft(), mTextView.getPaddingTop(),
                mTextView.getPaddingRight(), mTextView.getPaddingBottom() + spacing);
        addView(mTextView);

        mKnockCodeUnlockView = new KnockCodeView(context);
        mKnockCodeUnlockView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.9f));
        mKnockCodeUnlockView.setOnPositionTappedListener(this);
        mKnockCodeUnlockView.setOnLongClickListener(this);
        addView(mKnockCodeUnlockView);
    }

    // ── KeyguardSecurityView interface ────────────────────────────────────────

    @Override
    public void setKeyguardCallback(Object callback) {
        mCallback = callback;
    }

    @Override
    public void setLockPatternUtils(Object lockPatternUtils) {
        mLockPatternUtils = lockPatternUtils;
    }

    @Override
    public Object getCallback() {
        return mCallback;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void reset() {
        mTappedPositions.clear();
    }

    @Override
    public void showUsabilityHint() {}

    @Override
    public void onPause() {
        mTappedPositions.clear();
    }

    @Override
    public void onResume(int type) {
        mTappedPositions.clear();
    }

    // ── Callback helpers — support both Android 12 and older API signatures ───

    /**
     * Extend device-on timeout. Android 12: userActivity() no args.
     * Older: userActivity(long timeout).
     */
    public void extendTimeout() {
        if (mCallback == null) return;
        try {
            XposedHelpers.callMethod(mCallback, "userActivity");                 // Android 12+
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(mCallback, "userActivity", 7000L);      // Android 8-11
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Report a failed unlock attempt.
     * Android 12: reportUnlockAttempt(int userId, boolean success, int timeoutMs)
     * Older: reportFailedUnlockAttempt()
     */
    private void reportFailedUnlockAttempt() {
        if (mCallback == null) return;
        try {
            XposedHelpers.callMethod(mCallback, "reportUnlockAttempt", 0, false, 0); // Android 12+
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(mCallback, "reportFailedUnlockAttempt");    // older
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Set a lockout deadline after too many failed attempts.
     * Android 12: setLockoutAttemptDeadline(int userId, int timeoutMs) → long
     * Older: setLockoutAttemptDeadline() → long
     */
    private long setLockoutAttemptDeadline() {
        if (mLockPatternUtils == null) {
            return SystemClock.elapsedRealtime() + 30_000L; // 30 s fallback
        }
        try {
            // Android 12 signature
            return (Long) XposedHelpers.callMethod(
                    mLockPatternUtils, "setLockoutAttemptDeadline", 0, 30_000);
        } catch (Throwable t) {
            try {
                return (Long) XposedHelpers.callMethod(
                        mLockPatternUtils, "setLockoutAttemptDeadline");
            } catch (Throwable t2) {
                return SystemClock.elapsedRealtime() + 30_000L;
            }
        }
    }

    /**
     * Passcode matched — report success and dismiss the keyguard.
     *
     * Android 12 dismiss: dismiss(boolean authenticated, int targetUserId,
     *                              SecurityMode expectedSecurityMode)
     * Android 9-11:       dismiss(boolean authenticated, int targetUserId)
     * Older:              dismiss(boolean authenticated)
     */
    private void verifyPasscodeAndUnlock() {
        // 1. Report success
        try {
            XposedHelpers.callMethod(mCallback, "reportUnlockAttempt", 0, true, 0); // Android 12+
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(mCallback, "reportSuccessfulUnlockAttempt"); // older
            } catch (Throwable ignored) {}
        }

        // 2. Dismiss
        boolean dismissed = false;
        // Android 12: dismiss(boolean, int, SecurityMode)
        if (!dismissed) {
            try {
                Class<?> SecurityMode = XposedHelpers.findClass(
                        "com.android.keyguard.KeyguardSecurityModel$SecurityMode",
                        mCallback.getClass().getClassLoader());
                Object patternMode = XposedHelpers.getStaticObjectField(SecurityMode, "Pattern");
                XposedHelpers.callMethod(mCallback, "dismiss", true, 0, patternMode);
                dismissed = true;
            } catch (Throwable ignored) {}
        }
        // Android 9-11: dismiss(boolean, int)
        if (!dismissed) {
            try {
                XposedHelpers.callMethod(mCallback, "dismiss", true, 0);
                dismissed = true;
            } catch (Throwable ignored) {}
        }
        // Legacy: dismiss(boolean)
        if (!dismissed) {
            try {
                XposedHelpers.callMethod(mCallback, "dismiss", true);
            } catch (Throwable ignored) {}
        }

        mFailedPatternAttemptsSinceLastTimeout = 0;
        mKnockCodeUnlockView.setMode(Mode.READY);
    }

    // ── Touch / input handling ────────────────────────────────────────────────

    @Override
    public void onPositionTapped(int pos) {
        mTappedPositions.add(pos);
        mKnockCodeUnlockView.setMode(Mode.READY);
        mTextView.setText("");

        if (mTappedPositions.size() == mPasscode.size()) {
            mKnockCodeUnlockView.setEnabled(false);

            boolean correct = true;
            for (int i = 0; i < mPasscode.size(); i++) {
                if (!mTappedPositions.get(i).equals(mPasscode.get(i))) {
                    correct = false;
                    break;
                }
            }
            mTappedPositions.clear();

            if (correct) {
                mKnockCodeUnlockView.setMode(Mode.CORRECT);
                mKnockCodeUnlockView.setEnabled(true);
                verifyPasscodeAndUnlock();
            } else {
                reportFailedUnlockAttempt();
                mTotalFailedPatternAttempts++;
                mFailedPatternAttemptsSinceLastTimeout++;

                if (mFailedPatternAttemptsSinceLastTimeout >= 5) {
                    handleAttemptLockout(setLockoutAttemptDeadline());
                    mKnockCodeUnlockView.setMode(Mode.DISABLED);
                } else {
                    mKnockCodeUnlockView.setEnabled(true);
                    mKnockCodeUnlockView.setMode(Mode.INCORRECT);
                    mTextView.setText(
                            ResourceHelper.getString(getContext(), R.string.incorrect_pattern));
                }
            }
        } else if (mTappedPositions.size() > mPasscode.size()) {
            mTappedPositions.clear();
        }
    }

    private void handleAttemptLockout(long deadline) {
        long now = SystemClock.elapsedRealtime();
        onAttemptLockoutStart();
        mCountdownTimer = new CountDownTimer(deadline - now, 1000L) {
            @Override
            public void onFinish() {
                onAttemptLockoutEnd();
            }
            @Override
            public void onTick(long millisUntilFinished) {
                int secs = (int) (millisUntilFinished / 1000L);
                if (mContext != null) {
                    mTextView.setText(getEnablingInSecs(secs));
                }
            }
        }.start();
    }

    private String getEnablingInSecs(int secs) {
        return ResourceHelper.getString(getContext(), R.string.device_disabled, secs);
    }

    protected void onAttemptLockoutEnd() {
        mKnockCodeUnlockView.setEnabled(true);
        mFailedPatternAttemptsSinceLastTimeout = 0;
        mKnockCodeUnlockView.setMode(Mode.READY);
    }

    protected void onAttemptLockoutStart() {
        mKnockCodeUnlockView.setEnabled(false);
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    public void setSettingsHelper(SettingsHelper settingsHelper) {
        mSettingsHelper = settingsHelper;
        mKnockCodeUnlockView.setSettingsHelper(settingsHelper);
        mSettingsHelper.addInProcessListener(getContext());
        mSettingsHelper.addOnReloadListener(this);
        onSettingsReloaded();
    }

    @Override
    public void onSettingsReloaded() {
        mPasscode.clear();
        mPasscode.addAll(mSettingsHelper.getPasscode());
    }

    @Override
    public boolean onLongClick(View v) {
        mTextView.setText(ResourceHelper.getString(getContext(), R.string.long_pressed_hint));
        mTappedPositions.clear();
        return true;
    }
}
