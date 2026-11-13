package com.minhmc2077.aniclijava.cache;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaylistFreshDataSourceFactory implements DataSource.Factory {
    private final DataSource.Factory cached;
    private final DataSource.Factory network;
    private final boolean bypassPlaylists;

    public PlaylistFreshDataSourceFactory(DataSource.Factory cached, DataSource.Factory network,
                                          boolean bypassPlaylists) {
        this.cached = cached;
        this.network = network;
        this.bypassPlaylists = bypassPlaylists;
    }

    @Override
    public DataSource createDataSource() {
        return new DispatchDataSource(cached.createDataSource(), network.createDataSource(),
                bypassPlaylists);
    }

    private static final class DispatchDataSource implements DataSource {
        private final DataSource cached;
        private final DataSource network;
        private final boolean bypassPlaylists;
        private DataSource active;

        DispatchDataSource(DataSource cached, DataSource network, boolean bypassPlaylists) {
            this.cached = cached;
            this.network = network;
            this.bypassPlaylists = bypassPlaylists;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            active = bypassPlaylists && isPlaylist(dataSpec.uri) ? network : cached;
            return active.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return active == null ? C.RESULT_END_OF_INPUT : active.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            if (active != null) {
                try {
                    active.close();
                } finally {
                    active = null;
                }
            }
        }

        @Override
        public Uri getUri() {
            return active == null ? null : active.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return active == null ? Collections.emptyMap() : active.getResponseHeaders();
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            cached.addTransferListener(transferListener);
            network.addTransferListener(transferListener);
        }

        private static boolean isPlaylist(Uri uri) {
            if (uri == null) {
                return false;
            }
            String path = uri.getLastPathSegment();
            return path != null && path.toLowerCase(Locale.US).endsWith(".m3u8");
        }
    }
}
