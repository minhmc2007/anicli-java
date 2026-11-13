package com.minhmc2077.aniclijava.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.exoplayer.offline.Download;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.Version;
import com.minhmc2077.aniclijava.cache.CacheActivity;
import com.minhmc2077.aniclijava.data.SyncStore;
import com.minhmc2077.aniclijava.download.EpisodeDownloads;
import com.minhmc2077.aniclijava.model.HistoryEntry;
import com.minhmc2077.aniclijava.sync.SyncAuth;
import com.minhmc2077.aniclijava.util.Settings;
import com.minhmc2077.aniclijava.util.Ui;

import java.util.List;

public final class SettingsActivity extends BaseActivity {
    private static final int CACHE_STEPS = 1000;

    private SyncStore syncStore;
    private TextView malStatus;
    private TextView anilistStatus;
    private TextView syncLast;
    private MaterialButton malButton;
    private MaterialButton anilistButton;
    private MaterialButton syncNowButton;
    private MaterialButton downloadNextButton;
    private SeekBar bufferSeek;
    private TextView bufferValue;
    private SeekBar cacheSeek;
    private TextView cacheValue;
    private TextView cacheUsage;
    private long pendingCacheLimit;
    private boolean syncing;
    private boolean preparingDownload;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable applyCacheLimit = new Runnable() {
        @Override
        public void run() {
            final long bytes = pendingCacheLimit;
            App.get().async(
                    () -> {
                        App.get().setCacheLimitBytes(bytes);
                        return null;
                    },
                    ignored -> refreshCacheUsage(),
                    error -> {
                    }
            );
        }
    };

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
        setUpSkipButtonGroup();
        setUpBuffer();
        setUpCache();
        setUpDownloadNext();

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
        refreshCacheUsage();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(applyCacheLimit);
        applyCacheLimit.run();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(applyCacheLimit);
        super.onDestroy();
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

    private void setUpSkipButtonGroup() {
        MaterialButtonToggleGroup group = findViewById(R.id.skip_button_group);
        int mode = Settings.skipButtonMode(this);
        group.check(mode == Settings.SKIP_OFF
                ? R.id.skip_button_off
                : mode == Settings.SKIP_ON_TIME
                ? R.id.skip_button_on_time
                : R.id.skip_button_early);

        group.addOnButtonCheckedListener((buttonGroup, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            int newMode;
            if (checkedId == R.id.skip_button_off) {
                newMode = Settings.SKIP_OFF;
            } else if (checkedId == R.id.skip_button_on_time) {
                newMode = Settings.SKIP_ON_TIME;
            } else {
                newMode = Settings.SKIP_EARLY;
            }
            Settings.setSkipButtonMode(this, newMode);
        });
    }

    private void setUpBuffer() {
        bufferSeek = findViewById(R.id.buffer_seek);
        bufferValue = findViewById(R.id.buffer_value);
        int stored = Settings.bufferMinutes(this);
        bufferSeek.setMax(Settings.BUFFER_MAX_MINUTES);
        bufferSeek.setProgress(progressForMinutes(stored), false);
        bufferValue.setText(bufferLabel(stored));

        bufferSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int minutes = minutesForProgress(progress);
                bufferValue.setText(bufferLabel(minutes));
                if (fromUser) {
                    Settings.setBufferMinutes(SettingsActivity.this, minutes);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Settings.setBufferMinutes(SettingsActivity.this,
                        minutesForProgress(seekBar.getProgress()));
            }
        });
    }

    private String bufferLabel(int minutes) {
        return minutes == Settings.BUFFER_UNLIMITED
                ? getString(R.string.settings_buffer_unlimited)
                : getString(R.string.settings_buffer_value, minutes);
    }

    private static int minutesForProgress(int progress) {
        return progress >= Settings.BUFFER_MAX_MINUTES
                ? Settings.BUFFER_UNLIMITED
                : progress + Settings.BUFFER_MIN_MINUTES;
    }

    private static int progressForMinutes(int minutes) {
        return minutes == Settings.BUFFER_UNLIMITED
                ? Settings.BUFFER_MAX_MINUTES
                : Math.max(0, Math.min(Settings.BUFFER_MAX_MINUTES - 1,
                minutes - Settings.BUFFER_MIN_MINUTES));
    }

    private void setUpCache() {
        cacheSeek = findViewById(R.id.cache_seek);
        cacheValue = findViewById(R.id.cache_value);
        cacheUsage = findViewById(R.id.cache_usage);
        cacheSeek.setMax(CACHE_STEPS);
        cacheSeek.setProgress(progressForBytes(Settings.cacheMaxBytes(this)), false);
        cacheValue.setText(Formatter.formatShortFileSize(this, Settings.cacheMaxBytes(this)));
        pendingCacheLimit = Settings.cacheMaxBytes(this);

        cacheSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                long bytes = bytesForProgress(progress);
                cacheValue.setText(Formatter.formatShortFileSize(SettingsActivity.this, bytes));
                if (!fromUser) {
                    return;
                }
                pendingCacheLimit = bytes;
                Settings.setCacheMaxBytes(SettingsActivity.this, bytes);
                handler.removeCallbacks(applyCacheLimit);
                handler.postDelayed(applyCacheLimit, 250L);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                pendingCacheLimit = bytesForProgress(seekBar.getProgress());
                Settings.setCacheMaxBytes(SettingsActivity.this, pendingCacheLimit);
                handler.removeCallbacks(applyCacheLimit);
                applyCacheLimit.run();
            }
        });

        findViewById(R.id.view_cache_button).setOnClickListener(v ->
                startActivity(new Intent(this, CacheActivity.class)));
    }

    private void refreshCacheUsage() {
        if (cacheUsage == null) {
            return;
        }
        App.get().async(
                () -> App.get().cacheUsage(),
                bytes -> {
                    if (isFinishing() || isDestroyed() || cacheUsage == null) {
                        return;
                    }
                    cacheUsage.setText(getString(R.string.settings_cache_usage,
                            Formatter.formatShortFileSize(this, bytes),
                            Formatter.formatShortFileSize(this, Settings.cacheMaxBytes(this))));
                },
                error -> {
                }
        );
    }

    private static long bytesForProgress(int progress) {
        long span = Settings.CACHE_MAX_BYTES - Settings.CACHE_MIN_BYTES;
        int clamped = Math.max(0, Math.min(CACHE_STEPS, progress));
        return Settings.CACHE_MIN_BYTES + span * clamped / CACHE_STEPS;
    }

    private static int progressForBytes(long bytes) {
        long span = Settings.CACHE_MAX_BYTES - Settings.CACHE_MIN_BYTES;
        long clamped = Math.max(Settings.CACHE_MIN_BYTES,
                Math.min(Settings.CACHE_MAX_BYTES, bytes));
        return (int) ((clamped - Settings.CACHE_MIN_BYTES) * CACHE_STEPS / span);
    }

    private void setUpDownloadNext() {
        downloadNextButton = findViewById(R.id.download_next_button);
        downloadNextButton.setOnClickListener(v -> downloadNextEpisode());
    }

    private void downloadNextEpisode() {
        if (preparingDownload) {
            return;
        }
        List<HistoryEntry> entries = App.get().history().getAll();
        if (entries.isEmpty()) {
            Ui.toast(this, getString(R.string.settings_download_no_history));
            return;
        }
        HistoryEntry entry = entries.get(0);
        preparingDownload = true;
        downloadNextButton.setEnabled(false);
        downloadNextButton.setText(R.string.settings_download_preparing);

        App.get().async(
                () -> EpisodeDownloads.resolveNext(entry.animeId, entry.episodeNumber,
                        entry.episodeId, entry.mode),
                target -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (target == null) {
                        finishDownloadAttempt();
                        Ui.toast(this, getString(R.string.settings_download_no_next));
                        return;
                    }
                    Download existing = EpisodeDownloads.download(entry.animeId,
                            target.episode.number, entry.mode);
                    if (existing != null && existing.state == Download.STATE_COMPLETED) {
                        finishDownloadAttempt();
                        Ui.toast(this, getString(R.string.player_download_done,
                                target.episode.number));
                        return;
                    }
                    if (existing != null && (existing.state == Download.STATE_QUEUED
                            || existing.state == Download.STATE_DOWNLOADING)) {
                        finishDownloadAttempt();
                        Ui.toast(this, getString(R.string.settings_download_queued,
                                target.episode.number));
                        return;
                    }
                    EpisodeDownloads.enqueue(this, entry.animeId, target.episode, entry.mode,
                            target.info, new EpisodeDownloads.Callback() {
                                @Override
                                public void onQueued() {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    finishDownloadAttempt();
                                    Ui.toast(SettingsActivity.this,
                                            getString(R.string.settings_download_queued,
                                                    target.episode.number));
                                }

                                @Override
                                public void onError(Exception error) {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    finishDownloadAttempt();
                                    Ui.toast(SettingsActivity.this,
                                            getString(R.string.player_download_failed));
                                }
                            });
                },
                error -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    finishDownloadAttempt();
                    Ui.toast(this, getString(R.string.player_download_failed));
                }
        );
    }

    private void finishDownloadAttempt() {
        preparingDownload = false;
        if (downloadNextButton != null) {
            downloadNextButton.setEnabled(true);
            downloadNextButton.setText(R.string.settings_download_next);
        }
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
