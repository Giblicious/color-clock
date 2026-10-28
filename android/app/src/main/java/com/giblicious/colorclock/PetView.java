package com.giblicious.colorclock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shows the web version of the display, with the gecko living on it, over the native screen.
 * The page is bundled with the app (the repo's public/ folder) and served from a private
 * https origin so its ES modules load; settings are pushed in from the app.
 */
@SuppressLint("ViewConstructor")
final class PetView extends WebView {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String PAGE = "https://" + HOST + "/index.html?embed=1";

    private String pendingSettings;
    private boolean loaded;

    @SuppressLint("SetJavaScriptEnabled")
    PetView(Context context, Settings settings) {
        super(context);
        pendingSettings = settings.toJson();
        setBackgroundColor(Color.parseColor(settings.dayColor));
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setLongClickable(false);
        setOnLongClickListener(v -> true);
        setHapticFeedbackEnabled(false);

        WebSettings web = getSettings();
        web.setJavaScriptEnabled(true);
        web.setDomStorageEnabled(true);
        web.setAllowFileAccess(false);
        web.setAllowContentAccess(false);
        web.setSupportZoom(false);
        web.setMediaPlaybackRequiresUserGesture(true);

        setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!HOST.equals(url.getHost())) return blocked();
                String path = url.getPath() == null || url.getPath().equals("/") ? "index.html" : url.getPath().substring(1);
                try {
                    InputStream in = getContext().getAssets().open(path);
                    return new WebResourceResponse(mimeType(path), "utf-8", in);
                } catch (IOException e) {
                    Log.w("ColorClock", "No bundled file for " + path);
                    return blocked();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // Nothing in the display navigates anywhere.
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                push();
            }
        });
        loadUrl(PAGE);
    }

    /** Hands the display its settings; called on every change. */
    void apply(Settings settings) {
        pendingSettings = settings.toJson();
        if (loaded) push();
    }

    private void push() {
        // The settings JSON is a valid JavaScript object literal.
        evaluateJavascript("window.colorClock && window.colorClock.apply(" + pendingSettings + ")", null);
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
    }

    private static String mimeType(String path) {
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        return "text/html";
    }
}
