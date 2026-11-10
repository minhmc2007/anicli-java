package com.minhmc2077.aniclijava.model;

/**
 * Latest GitHub release that ships an installable APK.
 */
public final class ReleaseInfo {
    public final String version;
    public final String notes;
    public final String apkUrl;

    public ReleaseInfo(String version, String notes, String apkUrl) {
        this.version = version;
        this.notes = notes;
        this.apkUrl = apkUrl;
    }
}
