package com.minhmc2077.aniclijava.net;

import android.content.Context;

import com.minhmc2077.aniclijava.Version;
import com.minhmc2077.aniclijava.model.ReleaseInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class GitHubReleases {
    private static final String LATEST_URL =
            "https://api.github.com/repos/minhmc2007/anicli-java/releases/latest";

    private GitHubReleases() {
    }

    public static ReleaseInfo latest(CronetHttp http) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        CronetHttp.Response response = http.get(LATEST_URL, headers, true);
        if (!response.isSuccessful()) {
            throw new IOException("GitHub HTTP " + response.statusCode);
        }

        try {
            JSONObject release = new JSONObject(response.text());
            String version = Version.normalize(release.optString("tag_name"));
            if (version.isEmpty()) {
                throw new IOException("Release has no version tag");
            }

            String apkUrl = null;
            String fallbackUrl = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name");
                    if (!name.endsWith(".apk")) {
                        continue;
                    }
                    String url = asset.optString("browser_download_url");
                    if (fallbackUrl == null) {
                        fallbackUrl = url;
                    }
                    if (name.contains("universal")) {
                        apkUrl = url;
                        break;
                    }
                }
            }
            if (apkUrl == null) {
                apkUrl = fallbackUrl;
            }
            if (apkUrl == null || apkUrl.isEmpty()) {
                throw new IOException("Release has no APK");
            }

            return new ReleaseInfo(version, release.optString("body"), apkUrl);
        } catch (JSONException error) {
            throw new IOException("Invalid GitHub response", error);
        }
    }

    public static File apkFile(Context context, String version) {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        String safeVersion = version.replaceAll("[^0-9A-Za-z._-]", "_");
        return new File(directory, "anicli-java-" + safeVersion + ".apk");
    }

    public static void deleteCachedApks(Context context) {
        File[] files = new File(context.getCacheDir(), "updates").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
