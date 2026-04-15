package com.monochrome.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct JavaScript bridge for file downloads.
 * Accessible from JS as window.AndroidDownload.saveBase64(data, filename, mimeType)
 */
public class DownloadBridge {

    private static final String TAG = "DownloadBridge";
    private static final String DOWNLOAD_CHANNEL_ID = "fabiodalez_downloads";
    // #47: single reusable notification ID
    private static final int DOWNLOAD_NOTIF_ID = 998;
    // #36: channel creation guard
    private static volatile boolean channelCreated = false;

    // #40: background executor for file I/O (don't block the JS binder thread)
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();

    private final Context context;

    public DownloadBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void saveBase64(String base64Data, String filename, String mimeType) {
        if (base64Data == null || filename == null) {
            return;
        }
        IO_EXECUTOR.execute(() -> saveBase64Internal(base64Data, filename, mimeType));
    }

    private void saveBase64Internal(String base64Data, String filename, String mimeType) {
        try {
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE,
                        mimeType != null ? mimeType : "application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FabiodalezMusic");
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
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
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(
                        new java.io.File(dir, filename))) {
                    fos.write(data);
                }
            }

            showNotification(filename, true);
        } catch (Exception e) {
            Log.w(TAG, "saveBase64 failed", e);
            showNotification(filename, false);
        }
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    // #36: thread-safe channel creation
    private void createChannelIfNeeded() {
        if (channelCreated) return;
        synchronized (DownloadBridge.class) {
            if (channelCreated) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel channel = new NotificationChannel(
                            DOWNLOAD_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription("File download notifications");
                    nm.createNotificationChannel(channel);
                }
            }
            channelCreated = true;
        }
    }

    private void showNotification(String filename, boolean success) {
        createChannelIfNeeded();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(success ? "Download complete" : "Download failed")
                .setContentText(success
                        ? filename + " \u2192 Downloads/FabiodalezMusic"
                        : "Failed to save " + filename)
                .setAutoCancel(true);

        // #47: reuse single ID — notifications replace each other
        nm.notify(DOWNLOAD_NOTIF_ID, builder.build());
    }
}
