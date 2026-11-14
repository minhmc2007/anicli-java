package com.minhmc2077.aniclijava.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;

import com.google.android.material.appbar.MaterialToolbar;
import com.minhmc2077.aniclijava.App;
import com.minhmc2077.aniclijava.R;
import com.minhmc2077.aniclijava.data.MkissaRepository;
import com.minhmc2077.aniclijava.net.CronetHttp;
import com.minhmc2077.aniclijava.util.Ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MkissaVerifyActivity extends BaseActivity {
    public static final String EXTRA_MEDIA_URL = "media_url";

    private static final String EMBED_REFERER = "https://filelotion.fyi/";
    private static final String EMBED_PATTERN =
            "https?://[^\"']+\\.mp4upload\\.com[^\"']*?/video\\.mp4";
    private static final String SPOOF_JS = """
            (() => {
              try { Object.defineProperty(navigator, 'webdriver', {get: () => false}); } catch (e) {}
              if (window.__mkissaTabs) return;
              window.__mkissaTabs = setInterval(() => {
                const tab = [...document.querySelectorAll('[role=tab]')]
                    .find(x => (x.textContent || '').trim() === 'Mp4');
                if (tab && tab.getAttribute('aria-selected') !== 'true') tab.click();
              }, 1500);
            })()
            """;

    private final AtomicBoolean embedStarted = new AtomicBoolean();
    private boolean finished;
    private WebView webView;
    private ProgressBar progress;
    private String browserUserAgent;

    public static Intent intent(Context context, String animeId, String episode, String mode) {
        Intent intent = new Intent(context, MkissaVerifyActivity.class);
        intent.putExtra("anime_id", animeId);
        intent.putExtra("episode", episode);
        intent.putExtra("mode", mode);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.setupEdgeToEdge(this);
        setContentView(R.layout.activity_mkissa_verify);

        String animeId = getIntent().getStringExtra("anime_id");
        String episode = getIntent().getStringExtra("episode");
        String mode = getIntent().getStringExtra("mode");
        if (animeId == null || episode == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        TextView hint = findViewById(R.id.hint);
        webView = findViewById(R.id.web_view);
        Ui.applyTopInset(toolbar);
        Ui.applyBottomInset(hint);
        toolbar.setNavigationOnClickListener(v -> finish());

        browserUserAgent = browserUserAgent(this);
        setUpWebView();
        webView.loadUrl(MkissaRepository.watchUrl(animeId, episode, mode));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private void setUpWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(browserUserAgent);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                spoof(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spoof(view);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri == null ? "" : uri.toString();
                if (isEmbed(url)) {
                    resolveEmbed(url);
                    return null;
                }
                if (isMedia(url)) {
                    deliver(url);
                    String type = url.toLowerCase(Locale.US).contains(".m3u8")
                            ? "application/vnd.apple.mpegurl"
                            : "video/mp4";
                    return new WebResourceResponse(type, "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    Ui.toast(MkissaVerifyActivity.this,
                            getString(R.string.mkissa_verify_failed));
                }
            }
        });
    }

    private void spoof(WebView view) {
        view.evaluateJavascript(SPOOF_JS, null);
        view.postDelayed(() -> {
            if (!finished && webView == view) {
                view.evaluateJavascript(SPOOF_JS, null);
            }
        }, 700);
    }

    private void resolveEmbed(String url) {
        if (!embedStarted.compareAndSet(false, true)) {
            return;
        }
        App.get().async(
                () -> embedVideo(url),
                resolved -> {
                    if (resolved == null) {
                        embedStarted.set(false);
                    } else {
                        deliver(resolved);
                    }
                },
                error -> embedStarted.set(false)
        );
    }

    private String embedVideo(String url) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", EMBED_REFERER);
        CronetHttp.Response response = App.get().http().get(url, headers, true);
        if (!response.isSuccessful()) {
            return null;
        }
        Matcher matcher = Pattern.compile(EMBED_PATTERN, Pattern.CASE_INSENSITIVE)
                .matcher(response.text());
        return matcher.find() ? matcher.group() : null;
    }

    private static boolean isEmbed(String url) {
        return url.contains("mp4upload.com/embed-");
    }

    private static boolean isMedia(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http")) {
            return false;
        }
        String host = Uri.parse(lower).getHost();
        if (host != null && (host.equals("mkissa.to") || host.endsWith(".mkissa.to"))) {
            return false;
        }
        if (lower.contains("fast4speed") || lower.contains("youtube-anime")
                || lower.contains("authorization=")) {
            return true;
        }
        String path = Uri.parse(lower).getPath();
        return path != null && (path.endsWith(".mp4") || path.endsWith(".m3u8"));
    }

    private void deliver(String url) {
        runOnUiThread(() -> {
            if (finished || url == null || url.isEmpty()) {
                return;
            }
            finished = true;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_MEDIA_URL, url));
            finish();
        });
    }

    private static String browserUserAgent(Context context) {
        return WebSettings.getDefaultUserAgent(context)
                .replace("; wv)", ")")
                .replace(" wv", "")
                .replace("Version/4.0 ", "");
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
