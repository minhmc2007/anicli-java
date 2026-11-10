package com.minhmc2077.aniclijava.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.util.Settings;
import com.minhmc2077.aniclijava.util.Ui;

public final class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setupEdgeToEdge(this);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        Ui.applyTopInset(toolbar);
        Ui.applyBottomInset(findViewById(R.id.scroll));
        toolbar.setNavigationOnClickListener(v -> finish());

        buildAccentRow();
        setUpThemeGroup();

        findViewById(R.id.github_button).setOnClickListener(v -> openRepository());
    }

    private void buildAccentRow() {
        LinearLayout row = findViewById(R.id.accent_row);
        int selected = Settings.accentIndex(Settings.accent(this));
        int size = dp(40);
        int margin = dp(8);

        for (int i = 0; i < Settings.ACCENT_KEYS.length; i++) {
            TextView swatch = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(margin);
            swatch.setLayoutParams(params);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextSize(18);
            swatch.setTextColor(getColor(R.color.onPrimary));
            swatch.setText(i == selected ? "✓" : "");
            swatch.setContentDescription(getString(Settings.ACCENT_LABELS[i]));

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(dp(2));
            background.setColor(getColor(Settings.ACCENT_SWATCHES[i]));
            background.setStroke(i == selected ? dp(2) : dp(1),
                    i == selected ? getColor(R.color.onSurface) : getColor(R.color.outline));
            swatch.setBackground(background);

            final int index = i;
            swatch.setOnClickListener(v -> selectAccent(index));
            row.addView(swatch);
        }
    }

    private void selectAccent(int index) {
        String accent = Settings.ACCENT_KEYS[index];
        if (accent.equals(Settings.accent(this))) {
            return;
        }
        Settings.setAccent(this, accent);
        recreate();
    }

    private void setUpThemeGroup() {
        MaterialButtonToggleGroup group = findViewById(R.id.theme_group);
        int mode = Settings.themeMode(this);
        group.check(mode == AppCompatDelegate.MODE_NIGHT_YES
                ? R.id.theme_dark
                : mode == AppCompatDelegate.MODE_NIGHT_NO
                ? R.id.theme_light
                : R.id.theme_system);

        group.addOnButtonCheckedListener((buttonGroup, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            int newMode;
            if (checkedId == R.id.theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode == Settings.themeMode(this)) {
                return;
            }
            Settings.setThemeMode(this, newMode);
            AppCompatDelegate.setDefaultNightMode(newMode);
        });
    }

    private void openRepository() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_repo_url)));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.settings_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
