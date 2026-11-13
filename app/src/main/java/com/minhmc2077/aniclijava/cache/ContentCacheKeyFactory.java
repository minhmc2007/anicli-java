package com.minhmc2077.aniclijava.cache;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheKeyFactory;

public final class ContentCacheKeyFactory implements CacheKeyFactory {
    private final String contentId;

    public ContentCacheKeyFactory(String contentId) {
        this.contentId = contentId;
    }

    @Override
    public String buildCacheKey(DataSpec dataSpec) {
        String key = dataSpec.key != null ? dataSpec.key : dataSpec.uri.toString();
        return contentId + "|" + key;
    }

    public static String contentId(String cacheKey) {
        int first = cacheKey.indexOf('|');
        if (first <= 0) {
            return null;
        }
        int second = cacheKey.indexOf('|', first + 1);
        if (second <= first + 1) {
            return null;
        }
        int third = cacheKey.indexOf('|', second + 1);
        if (third <= second + 1) {
            return null;
        }
        return cacheKey.substring(0, third);
    }
}
