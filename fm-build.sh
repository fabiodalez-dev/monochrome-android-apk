#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────
# fm-build.sh — Fabiodalez Music one-shot build entry point
#
# Does everything needed to produce a fresh APK:
#   1. (optional) verify that upstream patches still match
#   2. install.sh  — copy wrapper files into monochrome clone
#   3. build-android.sh — apply upstream patches + build APK + revert
#
# Usage:
#   ./fm-build.sh [/path/to/monochrome]       # build (default: ../monochrome)
#   ./fm-build.sh --verify [/path/to/mono]    # dry-run verify only
#   ./fm-build.sh --skip-verify [/path]       # build without pre-verify
#   ./fm-build.sh --clean [/path]             # git clean monochrome first
#   ./fm-build.sh --help
#
# Exit code:
#   0 = build success (APK at monochrome/Monochrome-debug.apk)
#   1 = verify failed / install failed / build failed
# ─────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFY_ONLY=0
SKIP_VERIFY=0
DO_CLEAN=0
MONOCHROME=""

usage() {
    cat <<EOF
Usage: $0 [options] [/path/to/monochrome]

Options:
  --verify           Only run verify-patches.sh (no install, no build)
  --skip-verify      Skip pre-build verification
  --clean            git checkout -- && git clean -fdx in monochrome first
  --help             Show this help

Examples:
  $0                           # builds ../monochrome
  $0 ../monochrome             # explicit path
  $0 --verify                  # dry-run verify only
  $0 --skip-verify /tmp/mono   # build without verification step

The APK will end up at <monochrome>/Monochrome-debug.apk
and also at $SCRIPT_DIR/Monochrome-debug.apk (copied back).
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify) VERIFY_ONLY=1; shift ;;
        --skip-verify) SKIP_VERIFY=1; shift ;;
        --clean) DO_CLEAN=1; shift ;;
        --help|-h) usage; exit 0 ;;
        -*) echo "Unknown option: $1"; usage; exit 1 ;;
        *) MONOCHROME="$1"; shift ;;
    esac
done

# Default path: sibling ../monochrome
if [ -z "$MONOCHROME" ]; then
    MONOCHROME="$SCRIPT_DIR/../monochrome"
fi

if [ ! -d "$MONOCHROME" ]; then
    echo "✗ $MONOCHROME does not exist."
    echo ""
    echo "Clone monochrome first:"
    echo "  cd $(dirname "$MONOCHROME")"
    echo "  git clone https://github.com/monochrome-music/monochrome.git"
    echo "  cd monochrome && git remote rename origin upstream"
    exit 1
fi

MONOCHROME="$(cd "$MONOCHROME" && pwd)"

if [ ! -f "$MONOCHROME/index.html" ] || [ ! -f "$MONOCHROME/package.json" ]; then
    echo "✗ $MONOCHROME doesn't look like a Monochrome project"
    echo "  (missing index.html and/or package.json)"
    exit 1
fi

echo "══════════════════════════════════════════════════"
echo "  Fabiodalez Music — Build Pipeline"
echo "══════════════════════════════════════════════════"
echo ""
echo "▶ Wrapper:    $SCRIPT_DIR"
echo "▶ Monochrome: $MONOCHROME"
echo ""

# ── 0. (optional) clean monochrome ──
if [ "$DO_CLEAN" = "1" ]; then
    echo "▶ Cleaning monochrome checkout..."
    (
        cd "$MONOCHROME"
        git checkout -- . 2>/dev/null || true
        git clean -fdx 2>/dev/null || true
    )
    echo "  ✓ monochrome clean."
    echo ""
fi

# ── 1. Verify patches still apply ──
if [ "$VERIFY_ONLY" = "1" ] || [ "$SKIP_VERIFY" != "1" ]; then
    echo "▶ Step 1/3: Verifying upstream patches..."
    if [ -x "$SCRIPT_DIR/verify-patches.sh" ]; then
        if "$SCRIPT_DIR/verify-patches.sh" "$MONOCHROME"; then
            echo ""
        else
            echo ""
            echo "✗ verify-patches.sh reported failures."
            if [ "$VERIFY_ONLY" = "1" ]; then
                exit 1
            fi
            echo ""
            read -p "  Continue build anyway? (y/N) " -n 1 -r
            echo
            [[ ! $REPLY =~ ^[Yy]$ ]] && exit 1
        fi
    else
        echo "  ! verify-patches.sh not found or not executable, skipping"
    fi
fi

if [ "$VERIFY_ONLY" = "1" ]; then
    echo "✓ Verify-only mode: done."
    exit 0
fi

# ── 2. Install wrapper files into monochrome ──
echo "▶ Step 2/3: Installing wrapper files..."
if [ ! -x "$SCRIPT_DIR/install.sh" ]; then
    echo "✗ install.sh not found or not executable"
    exit 1
fi
"$SCRIPT_DIR/install.sh" "$MONOCHROME"
echo ""

# ── 3. Build APK ──
echo "▶ Step 3/3: Building APK..."
cd "$MONOCHROME"
./build-android.sh

# ── 4. Mirror APK back to wrapper repo for convenience ──
APK="$MONOCHROME/Monochrome-debug.apk"
if [ -f "$APK" ]; then
    cp "$APK" "$SCRIPT_DIR/Monochrome-debug.apk"
    SIZE=$(du -h "$SCRIPT_DIR/Monochrome-debug.apk" | awk '{print $1}')
    echo ""
    echo "══════════════════════════════════════════════════"
    echo "  ✓ Build complete"
    echo "══════════════════════════════════════════════════"
    echo "  APK: $SCRIPT_DIR/Monochrome-debug.apk ($SIZE)"
    echo "  also: $APK"
    echo ""
    echo "  Install on a connected device:"
    echo "    adb install -r \"$SCRIPT_DIR/Monochrome-debug.apk\""
else
    echo "✗ APK not found at $APK — build failed silently?"
    exit 1
fi
