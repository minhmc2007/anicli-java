package com.minhmc2077.aniclijava.model;

public final class SkipTime {
    public final String type;
    public final long startMs;
    public final long endMs;

    public SkipTime(String type, long startMs, long endMs) {
        this.type = type == null ? "" : type;
        this.startMs = Math.max(0L, startMs);
        this.endMs = Math.max(this.startMs, endMs);
    }

    public boolean isEnding() {
        return "ed".equals(type);
    }

    public boolean contains(long positionMs, long leadMs) {
        long start = Math.max(0L, startMs - Math.max(0L, leadMs));
        // Keep the last second clear so a click never seeks backwards.
        return positionMs >= start && positionMs < Math.max(startMs, endMs - 1000L);
    }
}
