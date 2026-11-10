package com.minhmc2077.aniclijava.model;

import java.util.Collections;
import java.util.List;

public final class StreamInfo {
    public final String masterUrl;
    public final String subtitleUrl;
    public final String referer;
    public final String malId;
    public final List<Quality> qualities;

    public StreamInfo(String masterUrl, String subtitleUrl, String referer,
                      String malId, List<Quality> qualities) {
        this.masterUrl = masterUrl;
        this.subtitleUrl = subtitleUrl;
        this.referer = referer;
        this.malId = malId;
        this.qualities = qualities == null ? Collections.emptyList() : qualities;
    }

    public Quality bestQuality() {
        if (qualities.isEmpty()) {
            return new Quality("Auto", 0, masterUrl);
        }
        return qualities.get(0);
    }
}
