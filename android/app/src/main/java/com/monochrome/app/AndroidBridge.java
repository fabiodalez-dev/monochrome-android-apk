package com.monochrome.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

/**
 * General-purpose Android bridge for clipboard, browser, etc.
 * Accessible from JS as window.AndroidBridge
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";
    private final Context context;

    public AndroidBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void copyToClipboard(String text) {
        if (text == null) return;
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            // #46: null guard — getSystemService can legally return null
            if (clipboard == null) {
                Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData clip = ClipData.newPlainText("Copied", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "copyToClipboard failed", e);
            Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public void openInBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder().build();
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            customTabsIntent.launchUrl(context, Uri.parse(url));
        } catch (Exception e) {
            Log.w(TAG, "CustomTabs failed, falling back to system browser", e);
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception inner) {
                Log.e(TAG, "No browser could open the URL", inner);
            }
        }
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }
}
