package com.minhmc2077.aniclijava.download;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.cache.ContentCacheKeyFactory;
import com.minhmc2077.aniclijava.model.Episode;
import com.minhmc2077.aniclijava.model.StreamInfo;
import com.minhmc2077.aniclijava.net.CronetHttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NextEpisodePrefetcher {
    private static final String TAG = "NextEpisodePrefetch";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final String animeId;
    private final Episode episode;
    private final String mode;
    private final StreamInfo info;
    private final int preferredHeight;
    private final int minutes;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<String> fetched = new HashSet<>();
    private volatile CacheWriter activeWriter;

    public NextEpisodePrefetcher(String animeId, Episode episode, String mode, StreamInfo info,
                                 int preferredHeight, int minutes) {
        this.animeId = animeId;
        this.episode = episode;
        this.mode = mode;
        this.info = info;
        this.preferredHeight = preferredHeight;
        this.minutes = minutes;
    }

    public void cancel() {
        cancelled.set(true);
        CacheWriter writer = activeWriter;
        if (writer != null) {
            writer.cancel();
        }
    }

    public void run() {
        try {
            prefetch();
        } catch (IOException error) {
            if (!cancelled.get()) {
                Log.w(TAG, "Could not prefetch episode " + episode.number, error);
            }
        }
    }

    private void prefetch() throws IOException {
        if (minutes <= 0 || cancelled.get() || !TextUtils.isEmpty(info.mimeType)) {
            return;
        }
        App.get().offline().putStream(animeId, episode.number, mode, info);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CronetHttp.USER_AGENT);
        if (!TextUtils.isEmpty(info.referer)) {
            headers.put("Referer", info.referer);
        }
        CronetDataSource.Factory upstream = new CronetDataSource.Factory(
                App.get().cronetEngine(), App.get().cronetCallbacks());
        upstream.setDefaultRequestProperties(headers);

        String contentId = EpisodeDownloads.id(animeId, episode.number, mode);
        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(App.get().mediaCache())
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(new ContentCacheKeyFactory(contentId))
                .setCacheWriteDataSinkFactory(new CacheDataSink.Factory()
                        .setCache(App.get().mediaCache()));
        CacheDataSource source = factory.createDataSource();

        try {
            HlsPlaylist playlist = parse(source, info.masterUrl);
            if (playlist instanceof HlsMultivariantPlaylist) {
                HlsMultivariantPlaylist.Variant variant =
                        chooseVariant((HlsMultivariantPlaylist) playlist);
                if (variant == null) {
                    return;
                }
                playlist = parse(source, variant.url.toString());
            }
            if (!(playlist instanceof HlsMediaPlaylist)) {
                return;
            }
            long limitUs = minutes * 60_000_000L;
            long durationUs = 0L;
            for (HlsMediaPlaylist.Segment segment : ((HlsMediaPlaylist) playlist).segments) {
                if (cancelled.get() || durationUs >= limitUs) {
                    break;
                }
                prefetchInitialization(source, segment);
                prefetchKey(source, segment.fullSegmentEncryptionKeyUri);
                if (segment.url != null) {
                    cache(source, segmentSpec(segment));
                }
                durationUs += segment.durationUs;
            }
        } finally {
            source.close();
        }
    }

    private HlsPlaylist parse(CacheDataSource source, String url) throws IOException {
        byte[] body = read(source, url);
        return new HlsPlaylistParser().parse(Uri.parse(url), new ByteArrayInputStream(body));
    }

    private byte[] read(CacheDataSource source, String url) throws IOException {
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url)).build();
        long length = source.open(spec);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    length > 0 && length < BUFFER_SIZE ? (int) length : BUFFER_SIZE);
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                if (cancelled.get()) {
                    throw new IOException("Prefetch cancelled");
                }
                int count = source.read(buffer, 0, buffer.length);
                if (count == C.RESULT_END_OF_INPUT) {
                    return out.toByteArray();
                }
                out.write(buffer, 0, count);
            }
        } finally {
            source.close();
        }
    }

    private void prefetchInitialization(CacheDataSource source, HlsMediaPlaylist.Segment segment)
            throws IOException {
        HlsMediaPlaylist.Segment init = segment.initializationSegment;
        if (init == null) {
            return;
        }
        if (init.url != null) {
            cache(source, segmentSpec(init));
        }
        prefetchKey(source, init.fullSegmentEncryptionKeyUri);
    }

    private void prefetchKey(CacheDataSource source, String url) throws IOException {
        if (url == null || url.isEmpty()) {
            return;
        }
        cache(source, new DataSpec.Builder().setUri(Uri.parse(url)).build());
    }

    private void cache(CacheDataSource source, DataSpec spec) throws IOException {
        String id = spec.uri + "@" + spec.position + "+" + spec.length;
        if (!fetched.add(id)) {
            return;
        }
        CacheWriter writer = new CacheWriter(source, spec, null, null);
        activeWriter = writer;
        try {
            writer.cache();
        } finally {
            activeWriter = null;
        }
    }

    private static DataSpec segmentSpec(HlsMediaPlaylist.Segment segment) {
        DataSpec.Builder builder = new DataSpec.Builder().setUri(Uri.parse(segment.url));
        if (segment.byteRangeLength != C.LENGTH_UNSET) {
            builder.setPosition(segment.byteRangeOffset);
            builder.setLength(segment.byteRangeLength);
        }
        return builder.build();
    }

    private HlsMultivariantPlaylist.Variant chooseVariant(HlsMultivariantPlaylist playlist) {
        HlsMultivariantPlaylist.Variant best = null;
        for (HlsMultivariantPlaylist.Variant variant : playlist.variants) {
            if (preferredHeight > 0 && variant.format.height == preferredHeight) {
                return variant;
            }
            if (best == null || variant.format.height > best.format.height) {
                best = variant;
            }
        }
        return best;
    }
}
