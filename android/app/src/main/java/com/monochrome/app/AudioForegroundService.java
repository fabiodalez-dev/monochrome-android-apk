package com.monochrome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AudioForegroundService extends Service {

    private static final String TAG = "AudioForegroundService";
    private static final String CHANNEL_ID = "monochrome_playback";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY = "com.monochrome.app.PLAY";
    public static final String ACTION_PAUSE = "com.monochrome.app.PAUSE";
    public static final String ACTION_NEXT = "com.monochrome.app.NEXT";
    public static final String ACTION_PREV = "com.monochrome.app.PREV";

    private MediaSessionCompat mediaSession;
    private BroadcastReceiver noisyReceiver;
    private String currentTitle = "Fabiodalez Music";
    private String currentArtist = "Music";
    private Bitmap currentCover = null;
    private String currentCoverUrl = null;
    private boolean isPlaying = true;
    private long currentPosition = 0;
    private long currentDuration = 0;

    // #33: Bounded thread pool + LRU cache for cover art
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> COVER_CACHE = new LruCache<>(10);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupMediaSession();
        registerNoisyReceiver();
    }

    // #49: use RECEIVER_NOT_EXPORTED — noisy broadcast is system-originated,
    // but keeping it unexported reduces attack surface (no third-party app can forge it).
    private void registerNoisyReceiver() {
        noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    isPlaying = false;
                    sendCommandToWebView("pause");
                    updateMediaSessionState();
                    updateNotification();
                }
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                isPlaying = true;
                sendCommandToWebView("play");
            } else if (ACTION_PAUSE.equals(action)) {
                isPlaying = false;
                sendCommandToWebView("pause");
            } else if (ACTION_NEXT.equals(action)) {
                sendCommandToWebView("next");
            } else if (ACTION_PREV.equals(action)) {
                sendCommandToWebView("prev");
            } else {
                String title = intent.getStringExtra("title");
                String artist = intent.getStringExtra("text");
                String coverUrl = intent.getStringExtra("cover");
                isPlaying = intent.getBooleanExtra("playing", true);
                currentPosition = Math.max(0, intent.getLongExtra("position", 0));
                currentDuration = Math.max(0, intent.getLongExtra("duration", 0));
                if (title != null) currentTitle = title;
                if (artist != null) currentArtist = artist;

                // #33: cache-aware async cover download (no more new Thread per call)
                if (coverUrl != null && !coverUrl.isEmpty()) {
                    if (!coverUrl.equals(currentCoverUrl)) {
                        currentCoverUrl = coverUrl;
                        Bitmap cached = COVER_CACHE.get(coverUrl);
                        if (cached != null) {
                            currentCover = cached;
                        } else {
                            final String urlToFetch = coverUrl;
                            DOWNLOAD_EXECUTOR.execute(() -> {
                                Bitmap bmp = downloadBitmap(urlToFetch);
                                if (bmp != null) {
                                    COVER_CACHE.put(urlToFetch, bmp);
                                    // Only update if the cover URL is still the current one
                                    if (urlToFetch.equals(currentCoverUrl)) {
                                        currentCover = bmp;
                                        updateMediaSessionState();
                                        updateNotification();
                                    }
                                }
                            });
                        }
                    }
                }
            }
        }

        updateMediaSessionState();
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    private Bitmap downloadBitmap(String urlStr) {
        HttpURLConnection conn = null;
        InputStream input = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoInput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setUseCaches(true);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            input = conn.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap failed: " + e.getMessage());
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "MonochromeMedia");
        mediaSession.setActive(true);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                isPlaying = true;
                sendCommandToWebView("play");
                updateMediaSessionState();
                updateNotification();
            }

            @Override
            public void onPause() {
                isPlaying = false;
                sendCommandToWebView("pause");
                updateMediaSessionState();
                updateNotification();
            }

            @Override
            public void onSkipToNext() {
                sendCommandToWebView("next");
            }

            @Override
            public void onSkipToPrevious() {
                sendCommandToWebView("prev");
            }

            // #37: hardware media button support (headphones, bluetooth, car)
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonIntent == null) return super.onMediaButtonEvent(mediaButtonIntent);
                String action = mediaButtonIntent.getAction();
                if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                        int keyCode = event.getKeyCode();
                        switch (keyCode) {
                            case KeyEvent.KEYCODE_MEDIA_PLAY:
                                onPlay();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                onPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            case KeyEvent.KEYCODE_HEADSETHOOK:
                                if (isPlaying) onPause();
                                else onPlay();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_NEXT:
                                onSkipToNext();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                onSkipToPrevious();
                                return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
    }

    // #15: use elapsedRealtime so Android interpolates position smoothly
    // between our (infrequent) sync calls — reduces wakeup cost.
    private void updateMediaSessionState() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_PLAY_PAUSE;

        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        float speed = isPlaying ? 1.0f : 0.0f;

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, currentPosition, speed, SystemClock.elapsedRealtime())
                .build());

        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration);

        if (currentCover != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentCover);
        }

        mediaSession.setMetadata(metaBuilder.build());
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPending = buildActionPending(ACTION_PREV, 1);
        PendingIntent playPausePending = buildActionPending(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPending = buildActionPending(ACTION_NEXT, 3);

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPending)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
                .addAction(playPauseIcon, isPlaying ? "Pause" : "Play", playPausePending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT);

        if (currentCover != null) {
            builder.setLargeIcon(currentCover);
        }

        return builder.build();
    }

    private PendingIntent buildActionPending(String action, int requestCode) {
        Intent intent = new Intent(this, AudioForegroundService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void sendCommandToWebView(String command) {
        Intent intent = new Intent("com.monochrome.app.MEDIA_COMMAND");
        intent.putExtra("command", command);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // #34: safe onDestroy — guarded unregister + executor shutdown
    @Override
    public void onDestroy() {
        if (noisyReceiver != null) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "noisyReceiver not registered", e);
            }
            noisyReceiver = null;
        }
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (Exception e) {
                Log.w(TAG, "mediaSession release failed", e);
            }
        }
        // Don't fully shutdown the static executor — it is shared across service lifetimes.
        // Just wait for in-flight tasks briefly.
        try {
            //noinspection ResultOfMethodCallIgnored
            DOWNLOAD_EXECUTOR.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monochrome music playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
