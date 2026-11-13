package com.minhmc2077.aniclijava.download;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.StreamInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EpisodeDownloads {
    private static final int MAX_COMPLETED_DOWNLOADS = 4;

    public interface Callback {
        void onQueued();

        void onError(Exception error);
    }

    public static final class Target {
        public final Episode episode;
        public final StreamInfo info;

        Target(Episode episode, StreamInfo info) {
            this.episode = episode;
            this.info = info;
        }
    }

    private EpisodeDownloads() {
    }

    public static Target resolveNext(String animeId, String episodeNumber, String episodeId,
                                     String mode) throws IOException {
        List<Episode> episodes = App.get().repository().episodes(animeId);
        int index = -1;
        for (int i = 0; i < episodes.size(); i++) {
            Episode candidate = episodes.get(i);
            if ((episodeId != null && episodeId.equals(candidate.id))
                    || (episodeNumber != null && episodeNumber.equals(candidate.number))) {
                index = i;
                break;
            }
        }
        if (index < 0 || index + 1 >= episodes.size()) {
            return null;
        }
        Episode next = episodes.get(index + 1);
        StreamInfo info = App.get().repository().resolveStream(next.id, mode);
        return new Target(next, info);
    }

    public static String id(String animeId, String episodeNumber, String mode) {
        return animeId + "|" + episodeNumber + "|"
                + ("dub".equalsIgnoreCase(mode) ? "dub" : "sub");
    }

    public static Download download(String animeId, String episodeNumber, String mode) {
        try {
            DownloadIndex index = App.get().downloadManager().getDownloadIndex();
            return index.getDownload(id(animeId, episodeNumber, mode));
        } catch (IOException ignored) {
            return null;
        }
    }

    public static void enqueue(Context context, String animeId, Episode episode, String mode,
                               StreamInfo info, Callback callback) {
        App.get().setDownloadReferer(info.referer);
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.parse(info.masterUrl))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        DownloadHelper helper = new DownloadHelper.Factory()
                .setDataSourceFactory(App.get().downloadDataSourceFactory())
                .setRenderersFactory(new DefaultRenderersFactory(context))
                .create(item);
        helper.prepare(new DownloadHelper.Callback() {
            @Override
            public void onPrepared(DownloadHelper prepared, boolean tracksInfoAvailable) {
                try {
                    DownloadRequest request = prepared.getDownloadRequest(
                            id(animeId, episode.number, mode), null);
                    prepared.release();
                    App.get().offline().putStream(animeId, episode.number, mode, info);
                    DownloadService.sendAddDownload(
                            context, AniDownloadService.class, request, true);
                    prune(context);
                    callback.onQueued();
                } catch (RuntimeException error) {
                    prepared.release();
                    callback.onError(error);
                }
            }

            @Override
            public void onPrepareError(DownloadHelper failed, IOException error) {
                failed.release();
                callback.onError(error);
            }
        });
    }

    private static void prune(Context context) {
        List<Download> completed = new ArrayList<>();
        DownloadIndex index = App.get().downloadManager().getDownloadIndex();
        try (DownloadCursor cursor = index.getDownloads(Download.STATE_COMPLETED)) {
            while (cursor.moveToNext()) {
                completed.add(cursor.getDownload());
            }
        } catch (IOException ignored) {
            return;
        }
        if (completed.size() <= MAX_COMPLETED_DOWNLOADS - 1) {
            return;
        }
        Collections.sort(completed, (first, second) ->
                Long.compare(first.startTimeMs, second.startTimeMs));
        int remove = completed.size() - (MAX_COMPLETED_DOWNLOADS - 1);
        for (int i = 0; i < remove; i++) {
            DownloadService.sendRemoveDownload(context, AniDownloadService.class,
                    completed.get(i).request.id, false);
        }
    }
}
