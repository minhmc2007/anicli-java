package com.minhmc2077.aniclijava.model;

import java.util.Collections;
import java.util.List;

public final class StreamInfo {
    public final String masterUrl;
    public final String subtitleUrl;
    public final String referer;
    public final String malId;
    public final List<Quality> qualities;
    public final long durationMs;
    public final String mimeType;

    public StreamInfo(String masterUrl, String subtitleUrl, String referer,
                      String malId, List<Quality> qualities) {
        this(masterUrl, subtitleUrl, referer, malId, qualities, 0L, null);
    }

    public StreamInfo(String masterUrl, String subtitleUrl, String referer,
                      String malId, List<Quality> qualities, long durationMs) {
        this(masterUrl, subtitleUrl, referer, malId, qualities, durationMs, null);
    }

    public StreamInfo(String masterUrl, String subtitleUrl, String referer,
                      String malId, List<Quality> qualities, long durationMs,
                      String mimeType) {
        this.masterUrl = masterUrl;
        this.subtitleUrl = subtitleUrl;
        this.referer = referer;
        this.malId = malId;
        this.qualities = qualities == null ? Collections.emptyList() : qualities;
        this.durationMs = Math.max(0L, durationMs);
        this.mimeType = mimeType;
    }

    public Quality bestQuality() {
        if (qualities.isEmpty()) {
            return new Quality("Auto", 0, masterUrl);
        }
        return qualities.get(0);
    }
}
