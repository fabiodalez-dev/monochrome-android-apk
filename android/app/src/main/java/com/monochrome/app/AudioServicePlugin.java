package com.monochrome.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.OutputStream;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AudioService")
public class AudioServicePlugin extends Plugin {

    private static final String TAG = "AudioServicePlugin";
    private static final String DOWNLOAD_CHANNEL_ID = "fabiodalez_downloads";
    // #47: single reusable notification ID — replaces previous instead of accumulating
    private static final int DOWNLOAD_NOTIF_ID = 999;
    // #36: create channel once, guarded by this flag
    private static volatile boolean downloadChannelCreated = false;

    private BroadcastReceiver mediaCommandReceiver;

    @Override
    public void load() {
        mediaCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String command = intent.getStringExtra("command");
                if (command != null) {
                    JSObject data = new JSObject();
                    data.put("command", command);
                    notifyListeners("mediaCommand", data);
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.monochrome.app.MEDIA_COMMAND");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(mediaCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(mediaCommandReceiver, filter);
        }
    }

    @PluginMethod()
    public void start(PluginCall call) {
        String title = call.getString("title", "Monochrome");
        String text = call.getString("text", "Playing music");
        String cover = call.getString("cover", null);
        Boolean playing = call.getBoolean("playing", true);
        long position = Math.max(0L, call.getData().optLong("position", 0L));
        long duration = Math.max(0L, call.getData().optLong("duration", 0L));

        Intent intent = new Intent(getContext(), AudioForegroundService.class);
        intent.putExtra("title", title);
        intent.putExtra("text", text);
        intent.putExtra("cover", cover);
        intent.putExtra("playing", playing);
        intent.putExtra("position", position);
        intent.putExtra("duration", duration);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }

        call.resolve();
    }

    @PluginMethod()
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), AudioForegroundService.class);
        intent.putExtra("playing", false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod()
    public void isBatteryOptimized(PluginCall call) {
        JSObject result = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            boolean isOptimized = pm != null && !pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
            result.put("optimized", isOptimized);
        } else {
            result.put("optimized", false);
        }
        call.resolve(result);
    }

    @PluginMethod()
    public void requestBatteryExclusion(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch battery exclusion intent", e);
                }
            }
        }
        call.resolve();
    }

    @PluginMethod()
    public void saveFile(PluginCall call) {
        String dataUri = call.getString("data", null);
        String filename = call.getString("filename", "download");

        if (dataUri == null || !dataUri.contains(",")) {
            call.reject("No data provided");
            return;
        }

        try {
            String base64Data = dataUri.substring(dataUri.indexOf(",") + 1);
            byte[] data = Base64.decode(base64Data, Base64.DEFAULT);

            String mimeType = "application/octet-stream";
            if (dataUri.startsWith("data:")) {
                int semi = dataUri.indexOf(";");
                if (semi > 5) mimeType = dataUri.substring(5, semi);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FabiodalezMusic");
                Uri uri = getContext().getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContext().getContentResolver().openOutputStream(uri)) {
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

            showDownloadNotification(filename, true);
            call.resolve();
        } catch (Exception e) {
            showDownloadNotification(filename, false);
            call.reject("Save failed: " + e.getMessage());
        }
    }

    // #36: create notification channel only once
    private void createDownloadChannelIfNeeded() {
        if (downloadChannelCreated) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        DOWNLOAD_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("File download notifications");
                nm.createNotificationChannel(channel);
            }
        }
        downloadChannelCreated = true;
    }

    private void showDownloadNotification(String filename, boolean success) {
        createDownloadChannelIfNeeded();
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String title = success ? "Download complete" : "Download failed";
        String text = success
                ? filename + " saved to Downloads/FabiodalezMusic"
                : "Failed to save " + filename;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // #47: reuse same ID — replaces previous notification, no tray accumulation
        nm.notify(DOWNLOAD_NOTIF_ID, builder.build());
    }

    @Override
    protected void handleOnDestroy() {
        if (mediaCommandReceiver != null) {
            try {
                getContext().unregisterReceiver(mediaCommandReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "mediaCommandReceiver not registered", e);
            }
            mediaCommandReceiver = null;
        }
    }
}
