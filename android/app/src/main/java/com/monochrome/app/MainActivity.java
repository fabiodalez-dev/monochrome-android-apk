package com.monochrome.app;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "MainActivity";
    private static boolean systemBarsConfigured = false;

    private LocalFilesBridge localFilesBridge;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AudioServicePlugin.class);
        super.onCreate(savedInstanceState);

        // ── #32: WebView hardening (cache, DOM storage, text zoom, media playback) ──
        // Enable Chrome remote debugging (chrome://inspect on desktop)
        WebView.setWebContentsDebuggingEnabled(true);

        try {
            WebView webView = getBridge().getWebView();
            WebSettings settings = webView.getSettings();
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setTextZoom(100);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    webView.getClass().getMethod("setOffscreenPreRaster", boolean.class).invoke(webView, true);
                } catch (Exception ignored) {
                    // setOffscreenPreRaster not available on this WebView build
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WebView hardening skipped", e);
        }

        // ── Tidal CDN Origin fix ──
        // Use custom WebViewClient that proxies Tidal audio requests with
        // Origin: https://listen.tidal.com (same as the Chrome extension).
        // Extends BridgeWebViewClient so Capacitor's bridge still works.
        getBridge().setWebViewClient(new TidalWebViewClient(getBridge()));

        // Register bridges
        getBridge().getWebView().addJavascriptInterface(
                new DownloadBridge(this), "AndroidDownload");

        localFilesBridge = new LocalFilesBridge(this, getBridge().getWebView());
        getBridge().getWebView().addJavascriptInterface(localFilesBridge, "AndroidLocalFiles");

        getBridge().getWebView().addJavascriptInterface(
                new AndroidBridge(this), "AndroidBridge");

        // Handle blob: and data: downloads from the WebView
        setupDownloadHandler();

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        // NOTE: battery optimization exclusion is intentionally NOT requested at launch.
        // User can enable it on-demand from settings via AudioServicePlugin.requestBatteryExclusion().
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null && localFilesBridge != null) {
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.w(TAG, "takePersistableUriPermission failed", e);
                }
                localFilesBridge.handleFolderResult(treeUri);
            }
        }
    }

    // #41: FIX for JS injection via filename (escape backslash BEFORE apostrophe)
    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private void setupDownloadHandler() {
        getBridge().getWebView().setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);

            if (url.startsWith("blob:")) {
                // Properly escape filename for JS string concatenation
                String safeFilename = escapeJsString(filename);
                String safeUrl = escapeJsString(url);
                String js = "fetch('" + safeUrl + "').then(r=>r.blob()).then(b=>{" +
                        "const reader=new FileReader();" +
                        "reader.onloadend=function(){" +
                        "window.Capacitor?.Plugins?.AudioService?.saveFile?.({" +
                        "data:reader.result," +
                        "filename:'" + safeFilename + "'" +
                        "});};" +
                        "reader.readAsDataURL(b);});";
                getBridge().getWebView().evaluateJavascript(js, null);
                Toast.makeText(this, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
            } else if (url.startsWith("data:")) {
                saveDataUri(url, filename, mimeType);
            } else {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(filename);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "FabiodalezMusic/" + filename);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(this, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void saveDataUri(String dataUri, String filename, String mimeType) {
        try {
            String base64Data = dataUri.substring(dataUri.indexOf(",") + 1);
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FabiodalezMusic");
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data);
                        }
                    }
                }
            } else {
                java.io.File dir = new java.io.File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "FabiodalezMusic");
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                java.io.File file = new java.io.File(dir, filename);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(data);
                }
            }
            Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // #38: back button handling — go back in WebView history, else minimize app
    @Override
    public void onBackPressed() {
        try {
            WebView wv = getBridge().getWebView();
            if (wv != null && wv.canGoBack()) {
                wv.goBack();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "WebView goBack failed", e);
        }
        // At root: minimize app instead of exiting
        moveTaskToBack(true);
    }

    // #38: only configure system bars once (avoid wasteful repeated calls)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !systemBarsConfigured) {
            setupSystemBars();
            systemBarsConfigured = true;
        }
    }

    // #50: migrate from deprecated SYSTEM_UI_FLAG_LAYOUT_STABLE to WindowInsetsController
    private void setupSystemBars() {
        try {
            getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#1a1a1a"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                // Legacy path for API < 30
                //noinspection deprecation
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Exception e) {
            Log.w(TAG, "setupSystemBars failed", e);
        }
    }
}
