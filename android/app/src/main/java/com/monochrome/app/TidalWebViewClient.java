package com.monochrome.app;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extends Capacitor's BridgeWebViewClient to intercept audio requests to
 * Tidal CDN and re-send them with Origin: https://listen.tidal.com.
 *
 * Without this, the CDN returns 403 because the WebView sends
 * Origin: https://localhost (Capacitor's local server).
 *
 * This is the native equivalent of the Chrome extension
 * "Monochrome Tidal Bypass".
 */
public class TidalWebViewClient extends BridgeWebViewClient {

    private static final String TAG = "TidalWebViewClient";

    public TidalWebViewClient(Bridge bridge) {
        super(bridge);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();

        if (isTidalAudioUrl(url)) {
            try {
                return proxyWithTidalOrigin(url, request);
            } catch (Exception e) {
                Log.w(TAG, "Tidal CDN proxy failed, falling back: " + e.getMessage());
            }
        }

        // Everything else: delegate to Capacitor's bridge client
        return super.shouldInterceptRequest(view, request);
    }

    private boolean isTidalAudioUrl(String url) {
        return url.contains(".audio.tidal.com/")
                || url.contains(".tidal.com/mediatracks/");
    }

    private WebResourceResponse proxyWithTidalOrigin(String url, WebResourceRequest request)
            throws Exception {
        URL audioUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) audioUrl.openConnection();
        conn.setRequestMethod(request.getMethod());

        // The key fix: set Origin that Tidal CDN expects
        conn.setRequestProperty("Origin", "https://listen.tidal.com");
        conn.setRequestProperty("Referer", "https://listen.tidal.com/");

        // Copy headers from original request (except Origin/Referer)
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            String key = header.getKey();
            if (!key.equalsIgnoreCase("Origin")
                    && !key.equalsIgnoreCase("Referer")) {
                conn.setRequestProperty(key, header.getValue());
            }
        }

        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int responseCode = conn.getResponseCode();
        String responseMessage = conn.getResponseMessage();
        if (responseMessage == null) responseMessage = "OK";

        String contentType = conn.getContentType();
        String mimeType = (contentType != null)
                ? contentType.split(";")[0].trim()
                : "audio/flac";

        Map<String, String> responseHeaders = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                responseHeaders.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        // Allow the WebView to read the response
        responseHeaders.put("Access-Control-Allow-Origin", "*");

        InputStream stream;
        if (responseCode >= 400) {
            stream = conn.getErrorStream();
            if (stream == null) stream = conn.getInputStream();
        } else {
            stream = conn.getInputStream();
        }

        return new WebResourceResponse(mimeType, "identity",
                responseCode, responseMessage, responseHeaders, stream);
    }
}
