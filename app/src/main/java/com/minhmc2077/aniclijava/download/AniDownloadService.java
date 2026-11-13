package com.minhmc2077.aniclijava.download;

import android.app.Notification;

import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Scheduler;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;

import java.util.List;

public final class AniDownloadService extends DownloadService {
    public static final String CHANNEL_ID = "episode_downloads";
    private static final int NOTIFICATION_ID = 41;

    public AniDownloadService() {
        super(NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
                CHANNEL_ID, R.string.download_channel_name,
                R.string.download_channel_description);
    }

    @Override
    protected DownloadManager getDownloadManager() {
        return App.get().downloadManager();
    }

    @Override
    protected Scheduler getScheduler() {
        return null;
    }

    @Override
    protected Notification getForegroundNotification(List<Download> downloads,
                                                     int notMetRequirements) {
        return App.get().downloadNotifications().buildProgressNotification(
                this, R.drawable.ic_download, null, null, downloads, notMetRequirements);
    }
}
