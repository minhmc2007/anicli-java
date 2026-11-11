package com.minhmc2077.aniclijava.sync;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.data.SyncStore;

import java.io.IOException;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SyncAuth {
    public interface Callback {
        void onFinished(boolean success, String message);
    }

    private static final String SCHEME = "aniclijava";
    private static final String HOST = "oauth";
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private SyncAuth() {
    }

    public static String start(Activity activity, String service) {
        SyncStore store = App.get().syncStore();
        String clientId = store.clientId(service);
        if (clientId == null || clientId.isEmpty()) {
            return activity.getString(R.string.sync_configure_first);
        }

        String state = UUID.randomUUID().toString();
        String verifier = verifier();
        store.setPending(service, state, verifier);

        Uri.Builder builder;
        if (SyncStore.MAL.equals(service)) {
            builder = Uri.parse("https://myanimelist.net/v1/oauth2/authorize").buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("redirect_uri", store.redirectUri(service))
                    .appendQueryParameter("code_challenge", verifier)
                    .appendQueryParameter("code_challenge_method", "plain");
        } else {
            builder = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("response_type", "token")
                    .appendQueryParameter("redirect_uri", store.redirectUri(service));
        }

        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, builder.build()));
            return null;
        } catch (ActivityNotFoundException error) {
            store.clearPending(service);
            return activity.getString(R.string.sync_no_browser);
        }
    }

    public static void complete(Context context, Uri uri, Callback callback) {
        App.get().async(
                () -> exchange(context, uri),
                message -> callback.onFinished(true, message),
                error -> callback.onFinished(false, error.getMessage())
        );
    }

    private static String exchange(Context context, Uri uri) throws IOException {
        if (uri == null || !SCHEME.equals(uri.getScheme()) || !HOST.equals(uri.getHost())) {
            throw new IOException(context.getString(R.string.sync_redirect_invalid));
        }
        String service = uri.getLastPathSegment();
        if (SyncStore.MAL.equals(service)) {
            return exchangeMal(context, uri);
        }
        if (SyncStore.ANILIST.equals(service)) {
            return exchangeAniList(context, uri);
        }
        throw new IOException(context.getString(R.string.sync_redirect_invalid));
    }

    private static String exchangeMal(Context context, Uri uri) throws IOException {
        SyncStore store = App.get().syncStore();
        Map<String, String> params = parse(uri.getEncodedQuery());
        String error = params.get("error");
        if (error != null && !error.isEmpty()) {
            throw new IOException(error);
        }
        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            throw new IOException(context.getString(R.string.sync_login_cancelled));
        }
        String expected = store.pendingState(SyncStore.MAL);
        String state = params.get("state");
        if (expected.isEmpty() || !expected.equals(state)) {
            throw new IOException(context.getString(R.string.sync_state_mismatch));
        }

        MalApi api = new MalApi(App.get().http());
        MalApi.Token token = api.exchange(
                store.clientId(SyncStore.MAL),
                store.clientSecret(SyncStore.MAL),
                code,
                store.pendingVerifier(SyncStore.MAL),
                store.redirectUri(SyncStore.MAL)
        );
        String name = "";
        try {
            name = api.userName(token.accessToken);
        } catch (Exception ignored) {
        }
        long expiresAt = token.expiresInSeconds > 0
                ? System.currentTimeMillis() + token.expiresInSeconds * 1000L
                : 0L;
        store.saveSession(SyncStore.MAL, token.accessToken, token.refreshToken, expiresAt, name);
        store.clearPending(SyncStore.MAL);
        return context.getString(R.string.sync_connected, context.getString(R.string.sync_mal));
    }

    private static String exchangeAniList(Context context, Uri uri) throws IOException {
        SyncStore store = App.get().syncStore();
        Map<String, String> params = parse(uri.getEncodedFragment());
        if (params.isEmpty()) {
            params = parse(uri.getEncodedQuery());
        }
        String error = params.get("error");
        if (error != null && !error.isEmpty()) {
            throw new IOException(error);
        }
        String token = params.get("access_token");
        if (token == null || token.isEmpty()) {
            throw new IOException(context.getString(R.string.sync_login_cancelled));
        }
        String expected = store.pendingState(SyncStore.ANILIST);
        String state = params.get("state");
        if (!expected.isEmpty() && state != null && !state.isEmpty() && !expected.equals(state)) {
            throw new IOException(context.getString(R.string.sync_state_mismatch));
        }

        String name = "";
        try {
            name = new AniListApi(App.get().http()).viewer(token).name;
        } catch (Exception ignored) {
        }
        long expiresAt = 0L;
        try {
            String expiresIn = params.get("expires_in");
            if (expiresIn != null && !expiresIn.isEmpty()) {
                expiresAt = System.currentTimeMillis() + Long.parseLong(expiresIn) * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        store.saveSession(SyncStore.ANILIST, token, "", expiresAt, name);
        store.clearPending(SyncStore.ANILIST);
        return context.getString(R.string.sync_connected, context.getString(R.string.sync_anilist));
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                String key = URLDecoder.decode(pair.substring(0, equals), "UTF-8");
                String value = URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                params.put(key, value);
            } catch (Exception ignored) {
            }
        }
        return params;
    }

    private static String verifier() {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }
}
