package com.minhmc2077.aniclijava.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.Version;
import com.minhmc2077.aniclijava.model.ReleaseInfo;
import com.minhmc2077.aniclijava.net.GitHubReleases;
import com.minhmc2077.aniclijava.util.Settings;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class UpdatePrompt {
    private static final int MAX_NOTES_LENGTH = 600;
    private static boolean checking;

    private UpdatePrompt() {
    }

    public static void check(Activity activity, boolean manual) {
        if (checking) {
            return;
        }
        checking = true;

        App.get().async(
                () -> GitHubReleases.latest(App.get().http()),
                release -> {
                    checking = false;
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (!Version.isNewer(release.version, Version.NAME)) {
                        GitHubReleases.deleteCachedApks(activity);
                        if (manual) {
                            Toast.makeText(activity, R.string.update_up_to_date,
                                    Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    show(activity, release);
                },
                error -> {
                    checking = false;
                    if (!manual || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    Toast.makeText(activity, R.string.update_check_failed,
                            Toast.LENGTH_LONG).show();
                });
    }

    private static void show(Activity activity, ReleaseInfo release) {
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > MAX_NOTES_LENGTH) {
            notes = notes.substring(0, MAX_NOTES_LENGTH) + "…";
        }
        String message = activity.getString(R.string.update_message, release.version);
        if (!notes.isEmpty()) {
            message = message + "\n\n" + notes;
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_download,
                        (dialog, which) -> download(activity, release))
                .setNeutralButton(R.string.update_disable,
                        (dialog, which) -> Settings.setUpdateCheckEnabled(activity, false))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void download(Activity activity, ReleaseInfo release) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage(activity.getString(R.string.update_downloading));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        File target = GitHubReleases.apkFile(activity, release.version);
        App.get().async(
                () -> {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Accept", "application/vnd.android.package-archive");
                    App.get().http().download(release.apkUrl, headers, target);
                    return target;
                },
                file -> {
                    dismiss(progress, activity);
                    install(activity, file);
                },
                error -> {
                    dismiss(progress, activity);
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, R.string.update_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static void dismiss(ProgressDialog progress, Activity activity) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            progress.dismiss();
        }
    }

    private static void install(Activity activity, File apk) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_install_title)
                    .setMessage(R.string.update_install_message)
                    .setPositiveButton(R.string.update_open_settings, (dialog, which) -> {
                        Intent settings = new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
        }
    }
}
