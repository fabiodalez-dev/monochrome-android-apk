package com.monochrome.app;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.URLUtil;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private LocalFilesBridge localFilesBridge;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AudioServicePlugin.class);
        super.onCreate(savedInstanceState);

        // Register download bridge (available as window.AndroidDownload in JS)
        getBridge().getWebView().addJavascriptInterface(
                new DownloadBridge(this), "AndroidDownload");

        // Register local files bridge (available as window.AndroidLocalFiles in JS)
        localFilesBridge = new LocalFilesBridge(this, getBridge().getWebView());
        getBridge().getWebView().addJavascriptInterface(localFilesBridge, "AndroidLocalFiles");

        // Register general bridge (clipboard, browser - available as window.AndroidBridge in JS)
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

        // Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null && localFilesBridge != null) {
                // Persist permission
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                localFilesBridge.handleFolderResult(treeUri);
            }
        }
    }

    private void setupDownloadHandler() {
        getBridge().getWebView().setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);

            if (url.startsWith("blob:")) {
                // For blob URLs, inject JS to convert blob to base64 and save via plugin
                String js = "fetch('" + url + "').then(r=>r.blob()).then(b=>{" +
                        "const reader=new FileReader();" +
                        "reader.onloadend=function(){" +
                        "window.Capacitor?.Plugins?.AudioService?.saveFile?.({" +
                        "data:reader.result," +
                        "filename:'" + filename.replace("'", "\\'") + "'" +
                        "});};" +
                        "reader.readAsDataURL(b);});";
                getBridge().getWebView().evaluateJavascript(js, null);
                Toast.makeText(this, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
            } else if (url.startsWith("data:")) {
                // Save data: URI directly
                saveDataUri(url, filename, mimeType);
            } else {
                // Regular HTTP(S) URL — use Android DownloadManager
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(filename);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "FabiodalezMusic/" + filename);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(this, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveDataUri(String dataUri, String filename, String mimeType) {
        try {
            String base64Data = dataUri.substring(dataUri.indexOf(",") + 1);
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — use MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FabiodalezMusic");
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.close();
                    }
                }
            } else {
                // Older Android — direct file write
                java.io.File dir = new java.io.File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "FabiodalezMusic");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, filename);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                fos.write(data);
                fos.close();
            }
            Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupSystemBars();
        }
    }

    private void setupSystemBars() {
        // Keep navigation bar visible with transparent background
        getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#1a1a1a"));
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
