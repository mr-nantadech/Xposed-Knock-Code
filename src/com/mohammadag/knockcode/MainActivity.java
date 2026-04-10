package com.mohammadag.knockcode;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;

public class MainActivity extends Activity implements View.OnClickListener {

    private SettingsHelper mSettingsHelper;
    private View mWarningBanner;
    private Switch mSwitchDrawFill;
    private Switch mSwitchDrawLines;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSettingsHelper = new SettingsHelper(getApplicationContext());

        mWarningBanner  = findViewById(R.id.warning_banner);
        mSwitchDrawFill = (Switch) findViewById(R.id.switch_draw_fill);
        mSwitchDrawLines = (Switch) findViewById(R.id.switch_draw_lines);

        mSwitchDrawFill.setChecked(mSettingsHelper.shouldDrawFill());
        mSwitchDrawLines.setChecked(mSettingsHelper.shouldDrawLines());

        findViewById(R.id.row_change_code).setOnClickListener(this);
        findViewById(R.id.row_locked_apps).setOnClickListener(this);
        findViewById(R.id.row_draw_fill).setOnClickListener(this);
        findViewById(R.id.row_draw_lines).setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh switch states in case user came back from ChangeKnockCodeActivity
        mSwitchDrawFill.setChecked(mSettingsHelper.shouldDrawFill());
        mSwitchDrawLines.setChecked(mSettingsHelper.shouldDrawLines());
        // Show warning if no passcode has ever been explicitly set
        mWarningBanner.setVisibility(
                mSettingsHelper.getPasscodeOrNull() == null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.row_change_code) {
            startActivity(new Intent(this, ChangeKnockCodeActivity.class));
        } else if (id == R.id.row_locked_apps) {
            startActivity(new Intent(this, LockedAppsActivity.class));
        } else if (id == R.id.row_draw_fill) {
            boolean next = !mSwitchDrawFill.isChecked();
            mSwitchDrawFill.setChecked(next);
            mSettingsHelper.setShouldDrawFill(next);
        } else if (id == R.id.row_draw_lines) {
            boolean next = !mSwitchDrawLines.isChecked();
            mSwitchDrawLines.setChecked(next);
            mSettingsHelper.setShouldDrawLines(next);
        }
    }
}
