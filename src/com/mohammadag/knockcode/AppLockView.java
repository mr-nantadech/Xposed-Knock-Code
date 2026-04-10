package com.mohammadag.knockcode;

import java.util.ArrayList;

import android.content.Context;
import android.graphics.Color;
import android.widget.FrameLayout;

import com.mohammadag.knockcode.KnockCodeView.Mode;
import com.mohammadag.knockcode.KnockCodeView.OnPositionTappedListener;
import com.mohammadag.knockcode.SettingsHelper.OnSettingsReloadedListener;

/**
 * Full-screen overlay shown when the user opens a locked app.
 * Added directly to the activity's DecorView — no SYSTEM_ALERT_WINDOW needed.
 * No hint text: just the knock-code grid on a black background.
 */
public class AppLockView extends FrameLayout
        implements OnPositionTappedListener, OnSettingsReloadedListener {

    public interface OnUnlockListener {
        void onUnlocked();
    }

    private final KnockCodeView mKnockCodeView;
    private OnUnlockListener mUnlockListener;
    private SettingsHelper mSettingsHelper;

    private final ArrayList<Integer> mTappedPositions = new ArrayList<>();
    private final ArrayList<Integer> mPasscode = new ArrayList<>();

    public AppLockView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setClickable(true);   // block touches reaching the app behind
        setFocusable(true);

        // Default passcode until SettingsHelper is wired
        mPasscode.add(1); mPasscode.add(2);
        mPasscode.add(3); mPasscode.add(4);

        mKnockCodeView = new KnockCodeView(context);
        addView(mKnockCodeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mKnockCodeView.setOnPositionTappedListener(this);
    }

    public void setOnUnlockListener(OnUnlockListener l) {
        mUnlockListener = l;
    }

    public void setSettingsHelper(SettingsHelper helper) {
        mSettingsHelper = helper;
        mKnockCodeView.setSettingsHelper(helper);
        helper.addOnReloadListener(this);
        onSettingsReloaded();
    }

    @Override
    public void onSettingsReloaded() {
        if (mSettingsHelper == null) return;
        mPasscode.clear();
        mPasscode.addAll(mSettingsHelper.getPasscode());
    }

    // ── Input handling ────────────────────────────────────────────────────────

    @Override
    public void onPositionTapped(int pos) {
        mTappedPositions.add(pos);
        mKnockCodeView.setMode(Mode.READY);

        if (mTappedPositions.size() < mPasscode.size()) return;

        if (mTappedPositions.size() == mPasscode.size()) {
            boolean correct = true;
            for (int i = 0; i < mPasscode.size(); i++) {
                if (!mTappedPositions.get(i).equals(mPasscode.get(i))) {
                    correct = false;
                    break;
                }
            }
            mTappedPositions.clear();

            if (correct) {
                mKnockCodeView.setMode(Mode.CORRECT);
                if (mUnlockListener != null) mUnlockListener.onUnlocked();
            } else {
                mKnockCodeView.setMode(Mode.INCORRECT);
            }
        } else {
            mTappedPositions.clear();
        }
    }

    public void resetInput() {
        mTappedPositions.clear();
        mKnockCodeView.setMode(Mode.READY);
    }
}
