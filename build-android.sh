#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────
# Monochrome Android Build Script
# Pulls latest from GitHub, applies Android patches, builds APK
#
# Patches applied temporarily during build:
#   - index.html: script tag, viewport-fit, brand name
#   - js/android-service.js: foreground service + notch CSS
#   - package.json: Capacitor dependencies
# All reverted after build. Git stays clean.
# ─────────────────────────────────────────────────────────

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_OUTPUT="$PROJECT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
APK_COPY="$PROJECT_DIR/Monochrome-debug.apk"

export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

cd "$PROJECT_DIR"

cleanup() {
    echo ""
    echo "▶ Cleaning up patched files..."
    git checkout -- index.html package.json package-lock.json 2>/dev/null || true
    rm -f js/android-service.js
    echo "  ✓ Source restored to upstream."
}
trap cleanup EXIT

echo "══════════════════════════════════════════"
echo "  Monochrome Android Build"
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

# ── 2. Install deps + add Capacitor ──
echo ""
echo "▶ Installing dependencies..."
npm install --silent 2>/dev/null
npm install --save @capacitor/core @capacitor/cli @capacitor/android @capacitor/status-bar 2>/dev/null
echo "  ✓ Done."

# ── 3. Patches ──
echo ""
echo "▶ Patching for Android build..."

# 3a. Add script tag
sed -i '' 's|</body>|<script type="module" src="./js/android-service.js"></script></body>|' index.html

# 3b. Add viewport-fit=cover + disable pinch zoom
sed -i '' 's|initial-scale=1.0"|initial-scale=1.0, viewport-fit=cover, maximum-scale=1.0, user-scalable=no"|' index.html

# 3c. Brand: "Monochrome" → "Fabiodalez" in sidebar logo
sed -i '' 's|<span>Monochrome</span>|<span>Fabiodalez</span>|' index.html

echo "  ✓ index.html patched (script tag + viewport-fit + brand)."

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
# Upstream ships splash.png, Capacitor sync generates splash.xml — Gradle fails on duplicates
if [ -f "$PROJECT_DIR/android/app/src/main/res/drawable/splash.png" ] && \
   [ -f "$PROJECT_DIR/android/app/src/main/res/drawable/splash.xml" ]; then
    rm "$PROJECT_DIR/android/app/src/main/res/drawable/splash.png"
    echo "  ✓ Removed duplicate splash.png (keeping splash.xml)."
fi

# ── 8. Build APK ──
echo ""
echo "▶ Building APK..."
cd "$PROJECT_DIR/android"
./gradlew assembleDebug -q
cd "$PROJECT_DIR"

if [ -f "$APK_OUTPUT" ]; then
    cp "$APK_OUTPUT" "$APK_COPY"
    SIZE=$(du -h "$APK_COPY" | cut -f1)
    echo "  ✓ APK built ($SIZE)"
    echo ""
    echo "══════════════════════════════════════════"
    echo "  APK: $APK_COPY"
    echo "══════════════════════════════════════════"
else
    echo "  ✗ Build failed!"
    exit 1
fi

# cleanup runs automatically via trap EXIT
