package com.minhmc2077.aniclijava.model;

/**
 * A quality variant found in an HLS master playlist.
 */
public final class Quality {
    public final String label;
    public final int height;
    public final String url;

    public Quality(String label, int height, String url) {
        this.label = label;
        this.height = height;
        this.url = url;
    }
}
