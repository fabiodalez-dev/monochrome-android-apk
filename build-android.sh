#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────
# Monochrome / Fabiodalez Music — Android Build Script
# Pulls latest from GitHub, applies Android patches, builds APK.
#
# Patches applied temporarily during build (reverted after):
#   index.html   — script tag, viewport-fit, brand, CDN preconnect
#   package.json — Capacitor dependencies
#   js/app.js    — search debounce 3000→500 ms
#   js/api.js    — search result limit = 100 (was backend default ≈25)
#   js/cache.js  — per-type TTL + query normalization (trim/lowercase/diacritics)
#
# All reverted automatically on exit. Upstream repo stays clean.
# ─────────────────────────────────────────────────────────

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_OUTPUT="$PROJECT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
APK_COPY="$PROJECT_DIR/Monochrome-debug.apk"

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

cd "$PROJECT_DIR"

cleanup() {
    echo ""
    echo "▶ Cleaning up patched files..."
    # Revert every tracked file we may have touched.
    # NOTE: do this file-by-file because `git checkout -- a b c` fails entirely
    # if any single file is not tracked (e.g. package-lock.json which upstream
    # doesn't track — they use bun.lock).
    for f in \
        index.html \
        package.json \
        js/app.js \
        js/api.js \
        js/cache.js \
        android/app/src/main/java/tf/monochrome/music/BackgroundAudioPlugin.java
    do
        if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
            git checkout -- "$f" 2>/dev/null || true
        fi
    done
    # Untracked files we generated during the build
    rm -f js/android-service.js package-lock.json
    rm -rf node_modules/@capgo/capacitor-media-session 2>/dev/null || true
    echo "  ✓ Source restored to upstream."
}
trap cleanup EXIT

echo "══════════════════════════════════════════"
echo "  Fabiodalez Music — Android Build"
echo "══════════════════════════════════════════"

# ── 1. Pull latest ──
echo ""
echo "▶ Pulling latest from upstream/main..."
cleanup 2>/dev/null || true
git fetch upstream
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse upstream/main)

if [ "$LOCAL" = "$REMOTE" ]; then
    echo "  Already up to date."
    read -p "  Build anyway? (y/N) " -n 1 -r
    echo
    [[ ! $REPLY =~ ^[Yy]$ ]] && exit 0
else
    echo "  $(git rev-list --count HEAD..upstream/main) new commits."
    git pull upstream main
    echo "  ✓ Updated."
fi

# ── 2a. Pre-npm patch: fix upstream broken override ──
# (Upstream package.json "overrides" forces sourcemap-codec@^1.4.14, but
# npm registry only has up to 1.4.8 — registry package is deprecated in favor
# of @jridgewell/sourcemap-codec. Downgrade the override so npm install works.)
if grep -q '"sourcemap-codec": "\^1.4.14"' package.json; then
    sed -i '' 's|"sourcemap-codec": "\^1.4.14"|"sourcemap-codec": "^1.4.8"|' package.json
    echo "  ✓ package.json: sourcemap-codec override 1.4.14 -> 1.4.8 (broken upstream)"
fi

# ── 2b. Install deps + add wrapper-only Capacitor plugins ──
echo ""
echo "▶ Installing dependencies..."
npm install 2>&1 | tail -3
npm install --save @capacitor/status-bar @capacitor/splash-screen 2>&1 | tail -3
echo "  ✓ Done."

# ── 2c. Shim @capgo/capacitor-media-session with no-op ──
# Upstream player.js now imports @capgo/capacitor-media-session for MediaSession.
# We use our own AudioForegroundService instead (full foreground service, notification,
# Bluetooth auto-pause, hardware keys). This shim makes the import resolve at
# Vite build time but all calls become silent no-ops.
SHIM_DIR="node_modules/@capgo/capacitor-media-session"
if [ -d "$SHIM_DIR" ]; then
    rm -rf "$SHIM_DIR"
fi
mkdir -p "$SHIM_DIR"
cat > "$SHIM_DIR/index.js" <<'SHIMEOF'
export const MediaSession = {
    setActionHandler: async () => {},
    setMetadata: async () => {},
    setPlaybackState: async () => {},
    setPositionState: async () => {},
};
SHIMEOF
cat > "$SHIM_DIR/package.json" <<'SHIMEOF'
{"name":"@capgo/capacitor-media-session","version":"0.0.0-shim","main":"index.js","module":"index.js","type":"module"}
SHIMEOF
echo "  ✓ @capgo/capacitor-media-session shimmed (no-op → our AudioForegroundService handles it)."

# ── 3. HTML patches (sed) ──
echo ""
echo "▶ Patching for Android build..."

# 3a. Add script tag
sed -i '' 's|</body>|<script type="module" src="./js/android-service.js"></script></body>|' index.html

# 3b. (upstream already has viewport-fit=cover — previous pinch-zoom-disable
#      sed was a noop and removed; rely on meta viewport as upstream ships it.)

# 3c. Brand: "Monochrome" → "Fabiodalez" in sidebar logo
sed -i '' 's|<span>Monochrome</span>|<span>Fabiodalez</span>|' index.html

# 3d. (#11/#20) Extra CDN preconnect + DNS prefetch for Tidal / api.tidal.com /
#     streams.tidal.com — reduces first-byte latency on stream URL fetch.
# Idempotent: only apply if api.tidal.com preconnect is not already present.
if ! grep -q 'preconnect.*api\.tidal\.com' index.html; then
    sed -i '' 's|<link rel="preconnect" href="https://resources.tidal.com" crossorigin />|<link rel="preconnect" href="https://resources.tidal.com" crossorigin />\
        <link rel="preconnect" href="https://api.tidal.com" crossorigin />\
        <link rel="dns-prefetch" href="https://streams.tidal.com" />\
        <link rel="dns-prefetch" href="https://cdn.tidal.com" />\
        <link rel="dns-prefetch" href="https://manifests.tidal.com" />|' index.html
fi

echo "  ✓ index.html patched (script tag + viewport-fit + brand + CDN preconnect)."

# ── 3e. JS upstream optimizations via Python (multi-line, safer than sed) ──
# These patches apply *only at build time*; git checkout in the cleanup trap
# above restores the original files afterwards.
python3 <<'PYEOF'
import sys
import os

PROJECT_DIR = os.environ.get("PROJECT_DIR", os.getcwd())

def patch(path, before, after, label):
    full = os.path.join(PROJECT_DIR, path)
    with open(full, "r", encoding="utf-8") as f:
        src = f.read()
    if before not in src:
        print("  ! " + label + ": pattern not found, skipping (upstream may have changed)")
        return False
    src = src.replace(before, after, 1)
    with open(full, "w", encoding="utf-8") as f:
        f.write(src)
    print("  + " + label)
    return True

# ── #1: search debounce 3000 → 700 ms ──
# 3000ms was absurdly slow; 500ms felt too twitchy (navigated for partial
# queries typed 1 letter at a time). 700ms is the sweet spot between
# "instantly after pasting" and "give me a beat to finish typing".
patch(
    "js/app.js",
    """    const debouncedSearch = debounce((query) => {
        if (query && query === searchInput.value.trim()) {
            performSearch(query);
        }
    }, 3000);""",
    """    const debouncedSearch = debounce((query) => {
        if (query && query === searchInput.value.trim()) {
            performSearch(query);
        }
    }, 700);""",
    "app.js: debounce 3000ms -> 700ms",
)

# ── #2: auto-navigate with min 3 chars ──
# Previously #2 disabled auto-navigate entirely, forcing users to hit Enter.
# Restored, but gated on queries >= 3 chars so the search page doesn't render
# for "r"/"ra" and flash an empty result state.
patch(
    "js/app.js",
    """    searchInput.addEventListener('input', (e) => {
        const query = e.target.value.trim();
        if (!query) return;

        if (handleExternalLink(query)) {
            return;
        }

        debouncedSearch(query);
    });""",
    """    searchInput.addEventListener('input', (e) => {
        const query = e.target.value.trim();
        if (!query) return;

        if (handleExternalLink(query)) {
            return;
        }

        // Only auto-navigate for typed-out queries. Short prefixes produce
        // empty backend results and flash a confusing "no artists found".
        if (query.length >= 3) {
            debouncedSearch(query);
        }
    });""",
    "app.js: auto-navigate with min 3 chars",
)

# ── #3 + #9 + #10: cache.js — TTL per type, query normalization ──
patch(
    "js/cache.js",
    "        this.ttl = options.ttl || 1000 * 60 * 30;",
    """        this.ttl = options.ttl || 1000 * 60 * 30;
        // Per-type TTL overrides (search results decay faster than detail pages).
        this.ttlByType = {
            search_all: 1000 * 60 * 10,
            search_tracks: 1000 * 60 * 10,
            search_artists: 1000 * 60 * 60,
            search_albums: 1000 * 60 * 30,
            search_playlists: 1000 * 60 * 60,
            search_videos: 1000 * 60 * 15,
        };""",
    "cache.js: per-type TTL map",
)

patch(
    "js/cache.js",
    """    generateKey(type, params) {
        const paramString = typeof params === 'object' ? JSON.stringify(params) : String(params);
        return `${type}:${paramString}`;
    }""",
    """    generateKey(type, params) {
        let normalized = params;
        if (typeof params === 'string' && typeof type === 'string' && type.startsWith('search')) {
            // Normalize: trim + lowercase + strip diacritics so "Björk"/"bjork"/" BJORK " hit the same key.
            normalized = params
                .trim()
                .toLowerCase()
                .normalize('NFD')
                .replace(/[\\u0300-\\u036f]/g, '');
        }
        const paramString = typeof normalized === 'object' ? JSON.stringify(normalized) : String(normalized);
        return `${type}:${paramString}`;
    }""",
    "cache.js: query normalization (trim/lowercase/NFD)",
)

patch(
    "js/cache.js",
    """    async get(type, params) {
        const key = this.generateKey(type, params);

        if (this.memoryCache.has(key)) {
            const cached = this.memoryCache.get(key);
            if (Date.now() - cached.timestamp < this.ttl) {
                return cached.data;
            }
            this.memoryCache.delete(key);
        }

        if (this.db) {
            try {
                const cached = await this.getFromIndexedDB(key);
                if (cached && Date.now() - cached.timestamp < this.ttl) {""",
    """    async get(type, params) {
        const key = this.generateKey(type, params);
        const effectiveTtl = (this.ttlByType && this.ttlByType[type]) || this.ttl;

        if (this.memoryCache.has(key)) {
            const cached = this.memoryCache.get(key);
            if (Date.now() - cached.timestamp < effectiveTtl) {
                return cached.data;
            }
            this.memoryCache.delete(key);
        }

        if (this.db) {
            try {
                const cached = await this.getFromIndexedDB(key);
                if (cached && Date.now() - cached.timestamp < effectiveTtl) {""",
    "cache.js: per-type TTL lookup in get()",
)

# ── #51 (bonus): api.js — explicit &limit=100 on all search endpoints so a
# prolific artist returns all their albums/tracks, not just the backend default (~25).
patch(
    "js/api.js",
    "const response = await this.fetchWithRetry(`/search/?q=${encodeURIComponent(query)}`, options);",
    "const response = await this.fetchWithRetry(`/search/?q=${encodeURIComponent(query)}&limit=${(options && options.limit) || 100}`, options);",
    "api.js: search() unified — limit=100",
)

patch(
    "js/api.js",
    "const response = await this.fetchWithRetry(`/search/?s=${encodeURIComponent(query)}`, options);",
    "const response = await this.fetchWithRetry(`/search/?s=${encodeURIComponent(query)}&limit=${(options && options.limit) || 100}`, options);",
    "api.js: searchTracks — limit=100",
)

patch(
    "js/api.js",
    "const response = await this.fetchWithRetry(`/search/?al=${encodeURIComponent(query)}`, options);",
    "const response = await this.fetchWithRetry(`/search/?al=${encodeURIComponent(query)}&limit=${(options && options.limit) || 100}`, options);",
    "api.js: searchAlbums — limit=100",
)

patch(
    "js/api.js",
    "const response = await this.fetchWithRetry(`/search/?p=${encodeURIComponent(query)}`, options);",
    "const response = await this.fetchWithRetry(`/search/?p=${encodeURIComponent(query)}&limit=${(options && options.limit) || 100}`, options);",
    "api.js: searchPlaylists — limit=100",
)

patch(
    "js/api.js",
    """const response = await this.fetchWithRetry(`/search/?v=${encodeURIComponent(query)}`, {
                ...options,
            });""",
    """const response = await this.fetchWithRetry(`/search/?v=${encodeURIComponent(query)}&limit=${(options && options.limit) || 100}`, {
                ...options,
            });""",
    "api.js: searchVideos — limit=100",
)

# ── searchArtists uses a different query shape, handle separately ──
# (Pattern may vary — best-effort search by the /search/?a= shape.)
try:
    with open(os.path.join(PROJECT_DIR, "js/api.js"), "r", encoding="utf-8") as f:
        api_src = f.read()
    import re
    new_src, n = re.subn(
        r"(`/search/\?a=\$\{encodeURIComponent\(query\)\})(`)",
        r"\1&limit=${(options && options.limit) || 100}\2",
        api_src,
    )
    if n > 0:
        with open(os.path.join(PROJECT_DIR, "js/api.js"), "w", encoding="utf-8") as f:
            f.write(new_src)
        print("  + api.js: searchArtists — limit=100")
    else:
        print("  ! api.js: searchArtists pattern not found, skipping")
except Exception as e:
    print("  ! api.js: searchArtists patch failed: " + str(e))

print("  ✓ Upstream JS optimizations applied.")
PYEOF

echo "  ✓ JS upstream optimizations applied (debounce, cache, limits)."

# ── 4. Copy android-service.js from android/ storage ──
cp "$PROJECT_DIR/android/android-service.js" js/android-service.js
echo "  ✓ android-service.js copied."

# ── 5. Init Capacitor Android if needed ──
if [ ! -d "$PROJECT_DIR/android" ]; then
    npx cap add android 2>/dev/null
    echo "  ✓ Android platform added."
fi

# ── 6. Build web ──
echo ""
echo "▶ Building web app..."
npx vite build 2>&1 | tail -3
echo "  ✓ Web build complete."

# ── 7. Sync to Android ──
echo ""
echo "▶ Syncing to Android..."
npx cap sync android 2>&1 | tail -2
echo "  ✓ Synced."

# ── 7b. Fix duplicate splash resources ──
# Upstream ships splash.png, Capacitor sync generates splash.xml — Gradle fails on duplicates.
if [ -f "$PROJECT_DIR/android/app/src/main/res/drawable/splash.png" ] && \
   [ -f "$PROJECT_DIR/android/app/src/main/res/drawable/splash.xml" ]; then
    rm "$PROJECT_DIR/android/app/src/main/res/drawable/splash.png"
    echo "  ✓ Removed duplicate splash.png (keeping splash.xml)."
fi

# ── 7c. Fix upstream Java typo (Kotlin backticks in BackgroundAudioPlugin.java) ──
# Commit "mobile contribs" (493ac9f) accidentally used Kotlin-style backticks on
# @PluginMethod in a .java file. javac rejects them. Strip them before gradle.
BGPLUGIN="$PROJECT_DIR/android/app/src/main/java/tf/monochrome/music/BackgroundAudioPlugin.java"
if [ -f "$BGPLUGIN" ] && grep -q '`@PluginMethod`' "$BGPLUGIN"; then
    sed -i '' 's|`@PluginMethod`|@PluginMethod|g' "$BGPLUGIN"
    echo "  ✓ Stripped Kotlin backticks from BackgroundAudioPlugin.java (upstream typo fix)."
fi

# ── 8. Build APK ──
echo ""
echo "▶ Building APK..."
cd "$PROJECT_DIR/android"
./gradlew assembleDebug -q
cd "$PROJECT_DIR"

# ── 9. #48: safer APK copy + size check ──
if [ -f "$APK_OUTPUT" ]; then
    cp "$APK_OUTPUT" "$APK_COPY"
    if [ -f "$APK_COPY" ]; then
        SIZE=$(du -h "$APK_COPY" | awk '{print $1}')
        echo "  ✓ APK built (${SIZE})"
        echo ""
        echo "══════════════════════════════════════════"
        echo "  APK: $APK_COPY"
        echo "══════════════════════════════════════════"
    else
        echo "  ✗ Failed to copy APK to $APK_COPY"
        exit 1
    fi
else
    echo "  ✗ Build failed — no APK at $APK_OUTPUT"
    exit 1
fi

# cleanup() runs automatically via trap EXIT
