package com.monochrome.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge for selecting local music folders on Android and exposing their
 * content via content:// URIs (no base64 round-trip, zero OOM risk).
 *
 * Accessible from JS as window.AndroidLocalFiles
 */
public class LocalFilesBridge {

    private static final String TAG = "LocalFilesBridge";
    private static final int PICK_FOLDER_REQUEST = 42;
    private static final int MAX_SCAN_DEPTH = 12; // Safety against symlink loops
    private static final int BATCH_YIELD_MS = 20;

    private final Activity activity;
    private final WebView webView;

    public LocalFilesBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    @JavascriptInterface
    public void pickFolder() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            activity.startActivityForResult(intent, PICK_FOLDER_REQUEST);
        });
    }

    /**
     * Called from MainActivity.onActivityResult when folder is picked.
     * Scans for audio files and streams their content:// URIs to JS.
     *
     * Post-optimization: files are NOT loaded into RAM here. The JS side
     * receives the URI string and plays it directly via <audio src=content://>.
     */
    public void handleFolderResult(Uri treeUri) {
        new Thread(() -> {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(activity, treeUri);
                if (dir == null) {
                    callJs("window._androidLocalFilesError('Could not open folder')");
                    return;
                }

                List<DocumentFile> audioFiles = new ArrayList<>();
                scanForAudio(dir, audioFiles, 0);

                callJs("window._androidLocalFilesStart(" + audioFiles.size() + ")");

                int failedCount = 0;
                for (int i = 0; i < audioFiles.size(); i++) {
                    DocumentFile file = audioFiles.get(i);
                    try {
                        String name = file.getName();
                        if (name == null) {
                            failedCount++;
                            continue;
                        }
                        Uri fileUri = file.getUri();
                        if (fileUri == null) {
                            failedCount++;
                            continue;
                        }

                        // #45: correct escape order — backslash BEFORE apostrophe.
                        String safeName = escapeJsString(name);
                        String safeUri = escapeJsString(fileUri.toString());

                        callJs("window._androidLocalFileReady('"
                                + safeName + "','"
                                + safeUri + "',"
                                + i + ","
                                + audioFiles.size() + ")");

                        // Yield periodically so the UI stays responsive
                        if (i > 0 && i % 25 == 0) {
                            try { Thread.sleep(BATCH_YIELD_MS); } catch (InterruptedException ignored) {}
                        }
                    } catch (Exception e) {
                        failedCount++;
                        Log.w(TAG, "Failed to enumerate file", e);
                    }
                }

                // #44: report partial failures to the user
                if (failedCount > 0) {
                    callJs("window._androidLocalFilesWarning(" + failedCount + ")");
                }
                callJs("window._androidLocalFilesDone()");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "unknown error";
                callJs("window._androidLocalFilesError('" + escapeJsString(msg) + "')");
            }
        }).start();
    }

    // #43: null-check getName() once per file (no TOCTOU).
    // Depth-limited recursion guards against symlink loops.
    private void scanForAudio(DocumentFile dir, List<DocumentFile> results, int depth) {
        if (dir == null || !dir.isDirectory() || depth > MAX_SCAN_DEPTH) return;
        DocumentFile[] children;
        try {
            children = dir.listFiles();
        } catch (Exception e) {
            Log.w(TAG, "listFiles failed", e);
            return;
        }
        if (children == null) return;
        for (DocumentFile file : children) {
            if (file == null) continue;
            if (file.isDirectory()) {
                scanForAudio(file, results, depth + 1);
            } else if (file.isFile()) {
                String name = file.getName();
                if (name == null) continue;
                String nameLower = name.toLowerCase();
                if (nameLower.endsWith(".flac")
                        || nameLower.endsWith(".mp3")
                        || nameLower.endsWith(".m4a")
                        || nameLower.endsWith(".wav")
                        || nameLower.endsWith(".ogg")
                        || nameLower.endsWith(".opus")
                        || nameLower.endsWith(".aac")
                        || nameLower.endsWith(".alac")) {
                    results.add(file);
                }
            }
        }
    }

    // #45: proper JS string escape — backslash first, then apostrophe, then newlines.
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

    private void callJs(String js) {
        activity.runOnUiThread(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "evaluateJavascript failed", e);
            }
        });
    }
}
