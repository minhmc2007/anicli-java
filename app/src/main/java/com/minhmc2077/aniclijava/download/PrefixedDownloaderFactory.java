package com.minhmc2077.aniclijava.download;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.offline.HlsDownloader;
import androidx.media3.exoplayer.offline.Downloader;
import androidx.media3.exoplayer.offline.DownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.ProgressiveDownloader;

import com.minhmc2077.aniclijava.cache.ContentCacheKeyFactory;

import java.util.concurrent.Executor;

public final class PrefixedDownloaderFactory implements DownloaderFactory {
    private final SimpleCache cache;
    private final DataSource.Factory upstreamFactory;
    private final Executor executor;

    public PrefixedDownloaderFactory(SimpleCache cache, DataSource.Factory upstreamFactory,
                                     Executor executor) {
        this.cache = cache;
        this.upstreamFactory = upstreamFactory;
        this.executor = executor;
    }

    @Override
    public Downloader createDownloader(DownloadRequest request) {
        CacheDataSource.Factory sourceFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheKeyFactory(new ContentCacheKeyFactory(request.id));
        int type = Util.inferContentTypeForUriAndMimeType(request.uri, request.mimeType);
        if (type == C.CONTENT_TYPE_HLS) {
            return new HlsDownloader.Factory(sourceFactory)
                    .setExecutor(executor)
                    .create(new MediaItem.Builder()
                            .setUri(request.uri)
                            .setStreamKeys(request.streamKeys)
                            .build());
        }
        DownloadRequest.ByteRange byteRange = request.byteRange;
        return new ProgressiveDownloader(
                new MediaItem.Builder().setUri(request.uri).build(),
                sourceFactory,
                executor,
                byteRange == null ? 0 : byteRange.offset,
                byteRange == null ? C.LENGTH_UNSET : byteRange.length);
    }
}
