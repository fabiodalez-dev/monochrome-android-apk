#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────
# Fabiodalez Music — Android overlay installer
# Copies Android-specific files into a Monochrome clone
# ─────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${1:-}" ]; then
    echo "Usage: ./install.sh /path/to/monochrome"
    echo ""
    echo "Example:"
    echo "  git clone https://github.com/monochrome-music/monochrome.git"
    echo "  ./install.sh ./monochrome"
    exit 1
fi

TARGET="$(cd "$1" && pwd)"

if [ ! -f "$TARGET/index.html" ] || [ ! -f "$TARGET/package.json" ]; then
    echo "Error: $TARGET doesn't look like a Monochrome project"
    exit 1
fi

echo "Installing Fabiodalez Music Android overlay into: $TARGET"

# Copy build script and config
cp "$SCRIPT_DIR/build-android.sh" "$TARGET/"
cp "$SCRIPT_DIR/capacitor.config.ts" "$TARGET/"
chmod +x "$TARGET/build-android.sh"

# Copy wrapper JS files
mkdir -p "$TARGET/android"
cp "$SCRIPT_DIR/android/android-service.js" "$TARGET/android/"
cp "$SCRIPT_DIR/android/fm-logger.js" "$TARGET/android/"

# Copy Java sources
JAVA_DEST="$TARGET/android/app/src/main/java/com/monochrome/app"
mkdir -p "$JAVA_DEST"
cp "$SCRIPT_DIR/android/app/src/main/java/com/monochrome/app/"*.java "$JAVA_DEST/"

# Copy AndroidManifest
mkdir -p "$TARGET/android/app/src/main"
cp "$SCRIPT_DIR/android/app/src/main/AndroidManifest.xml" "$TARGET/android/app/src/main/"

# Copy resources
RES_DEST="$TARGET/android/app/src/main/res"
for dir in values values-v31 drawable; do
    mkdir -p "$RES_DEST/$dir"
    cp "$SCRIPT_DIR/android/app/src/main/res/$dir/"* "$RES_DEST/$dir/" 2>/dev/null || true
done

# Copy icons
for dir in mipmap-mdpi mipmap-hdpi mipmap-xhdpi mipmap-xxhdpi mipmap-xxxhdpi; do
    mkdir -p "$RES_DEST/$dir"
    cp "$SCRIPT_DIR/android/app/src/main/res/$dir/"* "$RES_DEST/$dir/" 2>/dev/null || true
done

# Copy build.gradle customizations
cp "$SCRIPT_DIR/android/app/build.gradle" "$TARGET/android/app/"

echo ""
echo "Done! Now run:"
echo "  cd $TARGET"
echo "  ./build-android.sh"
