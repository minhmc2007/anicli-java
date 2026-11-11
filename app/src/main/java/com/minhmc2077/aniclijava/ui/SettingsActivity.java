package com.minhmc2077.aniclijava.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.Version;
import com.minhmc2077.aniclijava.data.SyncStore;
import com.minhmc2077.aniclijava.sync.SyncAuth;
import com.minhmc2077.aniclijava.util.Settings;
import com.minhmc2077.aniclijava.util.Ui;

public final class SettingsActivity extends BaseActivity {
    private SyncStore syncStore;
    private TextView malStatus;
    private TextView anilistStatus;
    private TextView syncLast;
    private MaterialButton malButton;
    private MaterialButton anilistButton;
    private MaterialButton syncNowButton;
    private boolean syncing;

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

        ((TextView) findViewById(R.id.about_version)).setText(Version.NAME);

        CheckBox autoCheck = findViewById(R.id.auto_update_check);
        autoCheck.setChecked(Settings.updateCheckEnabled(this));
        autoCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                Settings.setUpdateCheckEnabled(this, isChecked));

        findViewById(R.id.check_updates_button)
                .setOnClickListener(v -> UpdatePrompt.check(this, true));

        syncStore = App.get().syncStore();
        malStatus = findViewById(R.id.mal_status);
        anilistStatus = findViewById(R.id.anilist_status);
        syncLast = findViewById(R.id.sync_last);
        malButton = findViewById(R.id.mal_button);
        anilistButton = findViewById(R.id.anilist_button);
        syncNowButton = findViewById(R.id.sync_now_button);
        malButton.setOnClickListener(v -> onServiceButton(SyncStore.MAL));
        anilistButton.setOnClickListener(v -> onServiceButton(SyncStore.ANILIST));
        malButton.setOnLongClickListener(v -> onServiceLongPress(SyncStore.MAL));
        anilistButton.setOnLongClickListener(v -> onServiceLongPress(SyncStore.ANILIST));
        syncNowButton.setOnClickListener(v -> syncNow());
        refreshSyncRows();

        if (savedInstanceState == null) {
            handleSyncIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSyncIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (syncStore != null) {
            refreshSyncRows();
        }
    }

    private void onServiceButton(String service) {
        if (syncStore.isConnected(service)) {
            confirmDisconnect(service);
        } else if (syncStore.clientId(service).isEmpty()) {
            showSetup(service);
        } else {
            startAuth(service);
        }
    }

    private boolean onServiceLongPress(String service) {
        if (!syncStore.isConnected(service)) {
            showSetup(service);
        }
        return true;
    }

    private void startAuth(String service) {
        String error = SyncAuth.start(this, service);
        if (error != null) {
            Ui.toast(this, error);
        }
    }

    private void showSetup(String service) {
        int horizontal = dp(20);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontal, dp(4), horizontal, 0);

        TextView message = new TextView(this);
        message.setText(getString(R.string.sync_setup_message, serviceLabel(service),
                syncStore.redirectUri(service)));
        message.setTextSize(14);
        container.addView(message);

        EditText clientId = new EditText(this);
        clientId.setHint(R.string.sync_client_id);
        clientId.setSingleLine(true);
        clientId.setText(syncStore.clientId(service));
        container.addView(clientId);

        EditText secret = null;
        if (SyncStore.MAL.equals(service)) {
            secret = new EditText(this);
            secret.setHint(R.string.sync_client_secret);
            secret.setSingleLine(true);
            secret.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            secret.setText(syncStore.clientSecret(service));
            container.addView(secret);
        }
        EditText secretField = secret;

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.sync_setup_title, serviceLabel(service)))
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sync_save, (dialog, which) -> {
                    String id = clientId.getText().toString().trim();
                    if (id.isEmpty()) {
                        Ui.toast(this, getString(R.string.sync_configure_first));
                        return;
                    }
                    syncStore.setClient(service, id, secretField == null
                            ? ""
                            : secretField.getText().toString().trim());
                    refreshSyncRows();
                    startAuth(service);
                })
                .show();
    }

    private void confirmDisconnect(String service) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.sync_disconnect_title, serviceLabel(service)))
                .setMessage(R.string.sync_disconnect_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sync_disconnect, (dialog, which) -> {
                    syncStore.clearSession(service);
                    refreshSyncRows();
                })
                .show();
    }

    private void refreshSyncRows() {
        if (syncStore == null) {
            return;
        }
        updateServiceRow(SyncStore.MAL, malStatus, malButton);
        updateServiceRow(SyncStore.ANILIST, anilistStatus, anilistButton);

        long last = syncStore.lastSync();
        if (last <= 0) {
            syncLast.setText(R.string.sync_never);
        } else {
            syncLast.setText(getString(R.string.sync_last,
                    DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS)));
        }
        syncNowButton.setEnabled(syncStore.hasConnection() && !syncing);
    }

    private void updateServiceRow(String service, TextView status, MaterialButton button) {
        if (syncStore.isConnected(service)) {
            String name = syncStore.userName(service);
            status.setText(getString(R.string.sync_connected_as,
                    name.isEmpty() ? serviceLabel(service) : name));
            button.setText(R.string.sync_disconnect);
        } else {
            status.setText(R.string.sync_not_connected);
            button.setText(R.string.sync_connect);
        }
    }

    private void syncNow() {
        if (syncing) {
            return;
        }
        if (!syncStore.hasConnection()) {
            Ui.toast(this, getString(R.string.sync_not_connected_hint));
            return;
        }
        syncing = true;
        syncNowButton.setText(R.string.sync_syncing);
        syncNowButton.setEnabled(false);
        App.get().sync().syncNow(result -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            syncing = false;
            syncNowButton.setText(R.string.sync_now);
            refreshSyncRows();
            Ui.toast(this, result.hasError()
                    ? getString(R.string.sync_summary_error, result.pulled, result.pushed,
                    result.error)
                    : getString(R.string.sync_summary, result.pulled, result.pushed));
        });
    }

    private void handleSyncIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (data == null || !"aniclijava".equals(data.getScheme())) {
            return;
        }
        intent.setData(null);
        Ui.toast(this, getString(R.string.sync_connecting));
        SyncAuth.complete(this, data, (success, message) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            refreshSyncRows();
            if (success) {
                Ui.toast(this, message);
                syncNow();
            } else {
                Ui.toast(this, message == null || message.isEmpty()
                        ? getString(R.string.sync_failed)
                        : message);
            }
        });
    }

    private String serviceLabel(String service) {
        return getString(SyncStore.MAL.equals(service)
                ? R.string.sync_mal
                : R.string.sync_anilist);
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
