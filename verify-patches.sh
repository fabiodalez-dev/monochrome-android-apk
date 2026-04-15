#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────
# verify-patches.sh
# Dry-run dei patch upstream di build-android.sh su una
# copia temporanea di monochrome. Non tocca il vero clone,
# non fa npm install, non builda nulla.
#
# Uso:
#   ./verify-patches.sh /path/to/monochrome
#
# Exit code:
#   0 = tutte le patch upstream matchano il codice corrente
#   1 = una o più patch saltate / errori di validazione
# ─────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${1:-}" ]; then
    echo "Usage: $0 /path/to/monochrome"
    echo ""
    echo "Example:"
    echo "  $0 ../monochrome"
    exit 1
fi

MONOCHROME="$(cd "$1" && pwd)"

if [ ! -f "$MONOCHROME/index.html" ] || [ ! -f "$MONOCHROME/js/app.js" ]; then
    echo "✗ $MONOCHROME doesn't look like a monochrome checkout"
    echo "  (expected index.html and js/app.js at the root)"
    exit 1
fi

DRY="$(mktemp -d -t fm-verify.XXXXXX)"
cleanup() {
    rm -rf "$DRY"
}
trap cleanup EXIT

echo "══════════════════════════════════════════"
echo "  Fabiodalez Music — Patch Verification"
echo "══════════════════════════════════════════"
echo ""
echo "▶ Target: $MONOCHROME"
echo "▶ Workspace: $DRY"
echo ""

# ── 1. Copy only the files we touch ──
mkdir -p "$DRY/js"
cp "$MONOCHROME/index.html"  "$DRY/index.html"
cp "$MONOCHROME/js/app.js"   "$DRY/js/app.js"
cp "$MONOCHROME/js/api.js"   "$DRY/js/api.js"
cp "$MONOCHROME/js/cache.js" "$DRY/js/cache.js"

echo "▶ Running HTML sed patches..."
cd "$DRY"

HTML_OK=0
HTML_TOTAL=0

try_sed() {
    local label="$1"
    local pattern="$2"
    local replacement="$3"
    local file="$4"
    HTML_TOTAL=$((HTML_TOTAL + 1))
    # Check if pattern exists first (so we can report "already applied" noops)
    if grep -q -F "$pattern" "$file"; then
        sed -i '' "s|$pattern|$replacement|" "$file"
        echo "  + $label"
        HTML_OK=$((HTML_OK + 1))
    else
        # Check if the effect is already present (idempotent noop)
        if echo "$replacement" | head -c 80 | xargs -I {} grep -q -F "{}" "$file" 2>/dev/null; then
            echo "  = $label (already present in upstream, noop)"
            HTML_OK=$((HTML_OK + 1))
        else
            echo "  ! $label — pattern not found"
        fi
    fi
}

# 3a
try_sed "index.html: inject <script android-service.js>" \
    "</body>" \
    "<script type=\"module\" src=\"./js/android-service.js\"></script></body>" \
    index.html

# 3b — removed (upstream ships viewport-fit=cover; the old sed was a noop)

# 3c
try_sed "index.html: brand rename Monochrome -> Fabiodalez" \
    "<span>Monochrome</span>" \
    "<span>Fabiodalez</span>" \
    index.html

# 3d — CDN preconnect — multi-line sed, use a dedicated approach
HTML_TOTAL=$((HTML_TOTAL + 1))
if grep -q '<link rel="preconnect" href="https://resources.tidal.com" crossorigin />' index.html; then
    sed -i '' 's|<link rel="preconnect" href="https://resources.tidal.com" crossorigin />|<link rel="preconnect" href="https://resources.tidal.com" crossorigin />\
        <link rel="preconnect" href="https://api.tidal.com" crossorigin />\
        <link rel="dns-prefetch" href="https://streams.tidal.com" />\
        <link rel="dns-prefetch" href="https://cdn.tidal.com" />\
        <link rel="dns-prefetch" href="https://manifests.tidal.com" />|' index.html
    echo "  + index.html: CDN preconnect + dns-prefetch"
    HTML_OK=$((HTML_OK + 1))
else
    echo "  ! index.html: CDN preconnect — anchor not found"
fi

echo ""
echo "▶ Running JS Python patches..."

PY_COUNTERS=$(PROJECT_DIR="$DRY" python3 <<'PYEOF'
import os
import re
import sys

PROJECT_DIR = os.environ["PROJECT_DIR"]
applied = 0
total = 0

def patch(path, before, after, label):
    global applied, total
    total += 1
    full = os.path.join(PROJECT_DIR, path)
    with open(full, "r", encoding="utf-8") as f:
        src = f.read()
    if before not in src:
        # Check if already applied (after substring present)
        if after and after[:60] in src:
            print("  = " + label + " (already present)")
            applied += 1
            return True
        print("  ! " + label + " — pattern not found")
        return False
    src = src.replace(before, after, 1)
    with open(full, "w", encoding="utf-8") as f:
        f.write(src)
    print("  + " + label)
    applied += 1
    return True

# ── #1 debounce ──
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

# ── #2 auto-navigate with min 3 chars ──
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

# ── #10 per-type TTL ──
patch(
    "js/cache.js",
    "        this.ttl = options.ttl || 1000 * 60 * 30;",
    """        this.ttl = options.ttl || 1000 * 60 * 30;
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

# ── #3 + #9 normalization ──
patch(
    "js/cache.js",
    """    generateKey(type, params) {
        const paramString = typeof params === 'object' ? JSON.stringify(params) : String(params);
        return `${type}:${paramString}`;
    }""",
    """    generateKey(type, params) {
        let normalized = params;
        if (typeof params === 'string' && typeof type === 'string' && type.startsWith('search')) {
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

# ── #10b TTL lookup in get() ──
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

# ── #51 api.js search limits ──
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

# searchArtists via regex
try:
    total += 1
    with open(os.path.join(PROJECT_DIR, "js/api.js"), "r", encoding="utf-8") as f:
        api_src = f.read()
    new_src, n = re.subn(
        r"(`/search/\?a=\$\{encodeURIComponent\(query\)\})(`)",
        r"\1&limit=${(options && options.limit) || 100}\2",
        api_src,
    )
    if n > 0:
        with open(os.path.join(PROJECT_DIR, "js/api.js"), "w", encoding="utf-8") as f:
            f.write(new_src)
        print("  + api.js: searchArtists — limit=100")
        applied += 1
    elif "&limit=${(options && options.limit) || 100}" in api_src and "?a=${encodeURIComponent(query)}" in api_src:
        print("  = api.js: searchArtists (already present)")
        applied += 1
    else:
        print("  ! api.js: searchArtists — pattern not found")
except Exception as e:
    print("  ! api.js: searchArtists — exception: " + str(e))

# Emit counters for the bash caller to pick up
print("__PY_APPLIED__=" + str(applied))
print("__PY_TOTAL__=" + str(total))
PYEOF
)

# Parse counters from Python output
PY_APPLIED=$(echo "$PY_COUNTERS" | sed -n 's/^__PY_APPLIED__=\([0-9]*\).*/\1/p' | tail -1)
PY_TOTAL=$(echo "$PY_COUNTERS" | sed -n 's/^__PY_TOTAL__=\([0-9]*\).*/\1/p' | tail -1)
# Strip the counter lines from display
echo "$PY_COUNTERS" | grep -v '^__PY_'

echo ""
echo "▶ Syntax validation (node --check)..."
JS_OK=0
JS_TOTAL=0
for f in js/app.js js/cache.js js/api.js; do
    JS_TOTAL=$((JS_TOTAL + 1))
    if node --check "$f" 2>/dev/null; then
        echo "  ✓ $f"
        JS_OK=$((JS_OK + 1))
    else
        echo "  ✗ $f — SYNTAX ERROR"
        node --check "$f" 2>&1 | sed 's/^/      /' | head -5
    fi
done

echo ""
echo "▶ Runtime behavioral test: cache normalization..."
RUNTIME_OK=0
if node --input-type=module -e "
import { APICache } from '${DRY}/js/cache.js';
const cache = new APICache();
const keys = [
  cache.generateKey('search_tracks', 'Björk'),
  cache.generateKey('search_tracks', 'bjork'),
  cache.generateKey('search_tracks', '  BJORK  '),
];
if (keys[0] === keys[1] && keys[1] === keys[2]) {
  console.log('  ✓ Björk/bjork/BJORK -> same key (' + keys[0] + ')');
  const track = cache.generateKey('track', 'Björk');
  const track2 = cache.generateKey('track', 'bjork');
  if (track !== track2) {
    console.log('  ✓ Non-search types NOT normalized (preserved IDs)');
    process.exit(0);
  } else {
    console.log('  ✗ Non-search types incorrectly normalized');
    process.exit(1);
  }
} else {
  console.log('  ✗ Normalization failed: ' + JSON.stringify(keys));
  process.exit(1);
}
" 2>/dev/null; then
    RUNTIME_OK=1
else
    echo "  ✗ Runtime test failed"
fi

echo ""
echo "══════════════════════════════════════════"
echo "  Verification summary"
echo "══════════════════════════════════════════"
printf "  HTML patches:   %d/%d\n" "$HTML_OK" "$HTML_TOTAL"
printf "  Python patches: %s/%s\n" "${PY_APPLIED:-?}" "${PY_TOTAL:-?}"
printf "  JS syntax:      %d/%d\n" "$JS_OK" "$JS_TOTAL"
printf "  Runtime test:   %s\n" "$([ "$RUNTIME_OK" = "1" ] && echo '1/1 ✓' || echo '0/1 ✗')"
echo ""

# Exit code: 0 if everything is green, 1 otherwise
if [ "$HTML_OK" = "$HTML_TOTAL" ] \
   && [ "${PY_APPLIED:-0}" = "${PY_TOTAL:-0}" ] \
   && [ "$JS_OK" = "$JS_TOTAL" ] \
   && [ "$RUNTIME_OK" = "1" ]; then
    echo "✓ All patches are live-compatible with current upstream."
    exit 0
else
    echo "✗ One or more patches failed — see [Debugging: pattern not found] in PATCHES.md"
    exit 1
fi
