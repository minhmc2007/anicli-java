package com.minhmc2077.aniclijava.cache;

import androidx.media3.common.C;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;

import java.util.TreeSet;

public final class AdjustableLruCacheEvictor implements CacheEvictor {
    private final TreeSet<CacheSpan> leastRecentlyUsed =
            new TreeSet<>(AdjustableLruCacheEvictor::compare);

    private long maxBytes;
    private long currentSize;

    public AdjustableLruCacheEvictor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public boolean requiresCacheSpanTouches() {
        return true;
    }

    @Override
    public void onCacheInitialized() {
    }

    @Override
    public void onStartFile(Cache cache, String key, long position, long length) {
        if (length != C.LENGTH_UNSET) {
            evict(cache, length);
        }
    }

    @Override
    public void onSpanAdded(Cache cache, CacheSpan span) {
        synchronized (this) {
            leastRecentlyUsed.add(span);
            currentSize += span.length;
        }
        evict(cache, 0);
    }

    @Override
    public void onSpanRemoved(Cache cache, CacheSpan span) {
        synchronized (this) {
            if (leastRecentlyUsed.remove(span)) {
                currentSize -= span.length;
            }
        }
    }

    @Override
    public void onSpanTouched(Cache cache, CacheSpan oldSpan, CacheSpan newSpan) {
        synchronized (this) {
            if (leastRecentlyUsed.remove(oldSpan)) {
                currentSize -= oldSpan.length;
            }
            leastRecentlyUsed.add(newSpan);
            currentSize += newSpan.length;
        }
        evict(cache, 0);
    }

    public void setMaxBytes(Cache cache, long maxBytes) {
        synchronized (this) {
            this.maxBytes = maxBytes;
        }
        evict(cache, 0);
    }

    private void evict(Cache cache, long requiredSpace) {
        while (true) {
            CacheSpan candidate;
            synchronized (this) {
                if (currentSize + requiredSpace <= maxBytes || leastRecentlyUsed.isEmpty()) {
                    return;
                }
                candidate = leastRecentlyUsed.pollFirst();
                currentSize -= candidate.length;
            }
            cache.removeSpan(candidate);
        }
    }

    private static int compare(CacheSpan first, CacheSpan second) {
        long delta = first.lastTouchTimestamp - second.lastTouchTimestamp;
        if (delta == 0) {
            return first.compareTo(second);
        }
        return first.lastTouchTimestamp < second.lastTouchTimestamp ? -1 : 1;
    }
}
