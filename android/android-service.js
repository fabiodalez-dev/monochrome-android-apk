// ═══════════════════════════════════════════════════════════════════
// Fabiodalez Music — Android service JS
// Injected into the Monochrome web app at build time.
// ═══════════════════════════════════════════════════════════════════

// (fm-logger.js is loaded separately in <head> as a synchronous script
//  so it captures ALL console output from the very start, before any module.)

// ── UNREGISTER SERVICE WORKER ──
// The upstream workbox SW uses CacheFirst for audio/video, but Tidal CDN
// streams don't serve CORS headers → workbox can't read the response →
// "no-response" error → audio won't play. The Android WebView already has
// its own HTTP cache (configured in MainActivity), so we don't need SW at all.
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.getRegistrations().then((regs) => {
        regs.forEach((r) => r.unregister());
    }).catch(() => {});
    // Also clear the SW cache storage so stale entries don't linger
    if (typeof caches !== 'undefined') {
        caches.keys().then((names) => {
            names.forEach((name) => caches.delete(name));
        }).catch(() => {});
    }
}

// ── CLIPBOARD FALLBACK ──
if (window.AndroidBridge) {
    const origClipboard = navigator.clipboard?.writeText?.bind(navigator.clipboard);
    try {
        const desc = Object.getOwnPropertyDescriptor(navigator.clipboard || {}, 'writeText');
        if (navigator.clipboard && (!desc || desc.writable !== false)) {
            navigator.clipboard.writeText = function (text) {
                try {
                    window.AndroidBridge.copyToClipboard(text);
                    return Promise.resolve();
                } catch (e) {
                    if (origClipboard) return origClipboard(text);
                    return Promise.reject(e);
                }
            };
        } else {
            Object.defineProperty(navigator, 'clipboard', {
                value: {
                    writeText: function (text) {
                        window.AndroidBridge.copyToClipboard(text);
                        return Promise.resolve();
                    },
                },
                configurable: true,
            });
        }
    } catch {
        /* leave default */
    }
}

// ── OAUTH / WINDOW.OPEN OVERRIDE ──
if (window.AndroidBridge) {
    const origOpen = window.open.bind(window);
    window.open = function (url, target, features) {
        if (url && typeof url === 'string' && url.startsWith('http')) {
            window.AndroidBridge.openInBrowser(url);
            return {
                closed: false,
                close: function () {
                    this.closed = true;
                },
                location: {
                    _url: url,
                    get href() {
                        return this._url;
                    },
                    set href(v) {
                        this._url = v;
                        window.AndroidBridge.openInBrowser(v);
                    },
                },
            };
        }
        if (url === '' || !url) {
            return {
                closed: false,
                close: function () {
                    this.closed = true;
                },
                location: {
                    _href: '',
                    get href() {
                        return this._href;
                    },
                    set href(v) {
                        this._href = v;
                        if (v && v.startsWith('http')) {
                            window.AndroidBridge.openInBrowser(v);
                        }
                    },
                },
            };
        }
        return origOpen(url, target, features);
    };
}

// ── TOUCH DRAG POLYFILL for queue reordering ──
if ('ontouchstart' in window) {
    let _dragEl = null;
    const _endDrag = () => {
        if (_dragEl) {
            _dragEl.style.opacity = '';
            const container = _dragEl.closest('.queue-list, .track-list, .playlist-tracks');
            if (container) {
                const items = container.querySelectorAll('[data-queue-index]');
                items.forEach((item, i) => {
                    item.dataset.queueIndex = i;
                });
            }
            _dragEl = null;
        }
    };

    document.addEventListener(
        'touchstart',
        (e) => {
            const item = e.target.closest('[draggable="true"]');
            if (!item) return;
            _dragEl = item;
            item.style.opacity = '0.5';
        },
        { passive: true },
    );

    document.addEventListener(
        'touchmove',
        (e) => {
            if (!_dragEl) return;
            const touch = e.touches[0];
            const target = document.elementFromPoint(touch.clientX, touch.clientY);
            const overItem = target?.closest('[draggable="true"]');
            if (overItem && overItem !== _dragEl && overItem.parentNode === _dragEl.parentNode) {
                const rect = overItem.getBoundingClientRect();
                const mid = rect.top + rect.height / 2;
                if (touch.clientY < mid) {
                    overItem.parentNode.insertBefore(_dragEl, overItem);
                } else {
                    overItem.parentNode.insertBefore(_dragEl, overItem.nextSibling);
                }
            }
        },
        { passive: true },
    );

    document.addEventListener('touchend', _endDrag, { passive: true });
    document.addEventListener('touchcancel', _endDrag, { passive: true });
}

// ── DOWNLOAD HANDLER ──
const _blobStore = new Map();
const _BLOB_STORE_MAX = 50;

const _origCreate = URL.createObjectURL.bind(URL);
URL.createObjectURL = function (obj) {
    const url = _origCreate(obj);
    if (obj instanceof Blob) {
        if (_blobStore.size >= _BLOB_STORE_MAX) {
            const firstKey = _blobStore.keys().next().value;
            _blobStore.delete(firstKey);
        }
        _blobStore.set(url, obj);
    }
    return url;
};

const _origRevoke = URL.revokeObjectURL.bind(URL);
URL.revokeObjectURL = function (url) {
    setTimeout(() => _blobStore.delete(url), 10000);
    return _origRevoke(url);
};

const _origClick = HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && this.href && this.href.startsWith('blob:')) {
        const filename = this.download || 'download';
        const blob = _blobStore.get(this.href);
        if (blob && window.AndroidDownload) {
            _saveBlobNative(blob, filename);
            return;
        }
        if (window.AndroidDownload) {
            fetch(this.href)
                .then((r) => r.blob())
                .then((b) => _saveBlobNative(b, filename))
                .catch((err) => {
                    console.error('Download fetch failed:', err);
                    _showToast('Download failed: ' + filename, true);
                });
            return;
        }
    }
    return _origClick.call(this);
};

function _saveBlobNative(blob, filename) {
    const reader = new FileReader();
    reader.onerror = () => _showToast('Download failed: ' + filename, true);
    reader.onloadend = () => {
        if (!reader.result) return;
        const dataUri = reader.result;
        const base64 = dataUri.substring(dataUri.indexOf(',') + 1);
        let mime = 'application/octet-stream';
        if (dataUri.startsWith('data:') && dataUri.includes(';')) {
            mime = dataUri.substring(5, dataUri.indexOf(';'));
        }
        try {
            window.AndroidDownload.saveBase64(base64, filename, mime);
        } catch {
            _showToast('Download failed: ' + filename, true);
        }
    };
    reader.readAsDataURL(blob);
}

function _showToast(text, isError) {
    const toast = document.createElement('div');
    toast.textContent = text;
    toast.style.cssText =
        'position:fixed;bottom:120px;left:50%;transform:translateX(-50%);' +
        'background:' +
        (isError ? '#b34040' : '#2a2a2a') +
        ';color:#fff;padding:10px 18px;border-radius:8px;' +
        'z-index:99999;font-size:0.9rem;box-shadow:0 4px 12px rgba(0,0,0,0.4);' +
        'animation:fmToastIn 200ms ease-out;';
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2200);
}

// Safe SVG builder (avoids innerHTML)
function _mkSvg(attrs, pathDefs) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    for (const k in attrs) svg.setAttribute(k, attrs[k]);
    pathDefs.forEach((d) => {
        const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        p.setAttribute('d', d);
        svg.appendChild(p);
    });
    return svg;
}

// ── LOCAL FILES BRIDGE ──
if (window.AndroidLocalFiles) {
    let _waitForBtn = null;
    _waitForBtn = setInterval(() => {
        const btn = document.getElementById('select-local-folder-btn');
        const warn = document.getElementById('local-browser-warning');
        if (btn) {
            btn.style.display = 'flex';
            if (warn) warn.style.display = 'none';
            clearInterval(_waitForBtn);
            _waitForBtn = null;
        }
    }, 500);
    setTimeout(() => {
        if (_waitForBtn !== null) {
            clearInterval(_waitForBtn);
            _waitForBtn = null;
        }
    }, 30000);

    window.showDirectoryPicker = function () {
        return new Promise((resolve, reject) => {
            window._androidLocalFilesResolve = resolve;
            window._androidLocalFilesReject = reject;
            window.AndroidLocalFiles.pickFolder();
        });
    };

    let _collectedTracks = [];

    window._androidLocalFilesStart = function (count) {
        _collectedTracks = [];
        const btnText = document.getElementById('select-local-folder-text');
        const btn = document.getElementById('select-local-folder-btn');
        if (btnText) btnText.textContent = 'Scanning... (0/' + count + ')';
        else if (btn) btn.textContent = 'Scanning...';
    };

    // New protocol: native side passes filename + content:// URI (not base64).
    window._androidLocalFileReady = function (filename, contentUri, index, total) {
        try {
            _collectedTracks.push({
                id: 'local-' + index + '-' + filename,
                title: filename.replace(/\.[^.]+$/, ''),
                artist: { name: 'Local' },
                artists: [{ name: 'Local' }],
                album: { title: 'Local Files' },
                duration: 0,
                isLocal: true,
                audioUrl: contentUri,
                _localUri: contentUri,
            });
            const btnText = document.getElementById('select-local-folder-text');
            if (btnText) btnText.textContent = 'Scanning... (' + (index + 1) + '/' + total + ')';
        } catch {
            /* skip */
        }
    };

    // Back-compat shim
    window._androidLocalFileReadyBase64 = function (filename, base64Data, index, total) {
        try {
            const binary = atob(base64Data);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            const ext = filename.split('.').pop().toLowerCase();
            const mimeMap = {
                flac: 'audio/flac',
                mp3: 'audio/mpeg',
                m4a: 'audio/mp4',
                wav: 'audio/wav',
                ogg: 'audio/ogg',
            };
            const file = new File([bytes], filename, { type: mimeMap[ext] || 'audio/mpeg' });
            _collectedTracks.push({
                id: 'local-' + index + '-' + filename,
                title: filename.replace(/\.[^.]+$/, ''),
                artist: { name: 'Local' },
                artists: [{ name: 'Local' }],
                album: { title: 'Local Files' },
                duration: 0,
                isLocal: true,
                audioUrl: URL.createObjectURL(file),
            });
            const btnText = document.getElementById('select-local-folder-text');
            if (btnText) btnText.textContent = 'Scanning... (' + (index + 1) + '/' + total + ')';
        } catch {
            /* skip */
        }
    };

    window._androidLocalFilesDone = async function () {
        _collectedTracks.sort((a, b) => a.title.localeCompare(b.title));
        window.localFilesCache = _collectedTracks;
        const btn = document.getElementById('select-local-folder-btn');
        const btnText = document.getElementById('select-local-folder-text');
        if (btnText) btnText.textContent = _collectedTracks.length + ' tracks loaded';
        else if (btn) btn.textContent = _collectedTracks.length + ' tracks loaded';
        if (btn) btn.disabled = false;
        if (typeof window.ui?.renderLibraryPage === 'function') {
            window.ui.renderLibraryPage();
        }
    };

    window._androidLocalFilesWarning = function (failedCount) {
        if (failedCount > 0) {
            _showToast(failedCount + ' files could not be read', true);
        }
    };

    window._androidLocalFilesError = function () {
        const btn = document.getElementById('select-local-folder-btn');
        const btnText = document.getElementById('select-local-folder-text');
        if (btnText) btnText.textContent = 'Select Music Folder';
        else if (btn) btn.textContent = 'Select Music Folder';
        if (btn) btn.disabled = false;
    };
}

// ═══════════════════════════════════════════════════════════════════
// MEDIA CONTROLS + CSS + UI ENHANCEMENTS
// ═══════════════════════════════════════════════════════════════════
(async () => {
    let AudioService;

    try {
        const { Capacitor, registerPlugin } = await import('@capacitor/core');
        if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;
        AudioService = registerPlugin('AudioService');
    } catch {
        return;
    }

    // Haptic feedback removed (issue #4)
    const hapticLight = () => {};
    const hapticMedium = () => {};

    // ── CSS INJECTION ──
    const style = document.createElement('style');
    style.textContent = `
        :root {
            --fm-safe-top: env(safe-area-inset-top, 0px);
            --fm-safe-bottom: env(safe-area-inset-bottom, 0px);
            --fm-safe-left: env(safe-area-inset-left, 0px);
            --fm-safe-right: env(safe-area-inset-right, 0px);
        }
        html {
            padding-top: var(--fm-safe-top) !important;
            padding-bottom: 0 !important;
            background: #000 !important;
        }
        .main-content { padding-bottom: calc(120px + var(--fm-safe-bottom)) !important; }
        .side-panel { padding-top: var(--fm-safe-top) !important; }
        #queue-modal { padding-top: var(--fm-safe-top) !important; }
        #download-notifications {
            top: calc(20px + var(--fm-safe-top)) !important;
            bottom: auto !important;
        }
        #sidebar-nav-download-bottom { display: none !important; }
        #cast-btn, #fs-cast-btn { display: none !important; }

        #android-back-btn,
        .hamburger-menu,
        #hamburger-btn,
        .main-header .btn-icon {
            min-width: 48px !important;
            min-height: 48px !important;
            display: inline-flex !important;
            align-items: center !important;
            justify-content: center !important;
        }

        .track-item-cover { width: 56px !important; height: 56px !important; border-radius: 6px !important; }
        .track-item-details .title { font-size: 1.05rem !important; }
        .track-item-details .artist { font-size: 0.85rem !important; }

        .main-header {
            gap: 4px !important;
            padding: 8px 10px !important;
            flex-wrap: nowrap !important;
            overflow: hidden !important;
            max-width: 100vw !important;
            box-sizing: border-box !important;
        }
        .main-header .search-bar {
            min-width: 0 !important;
            flex: 1 1 0% !important;
            width: 0 !important;
            position: relative !important;
        }
        .main-header .header-account-control { margin-right: 2px !important; flex-shrink: 0 !important; }
        .main-header .hamburger-menu { flex-shrink: 0 !important; }
        #android-back-btn { flex-shrink: 0 !important; }
        #android-back-btn:active { background: var(--muted); border-radius: 50%; }

        .card-grid {
            grid-template-columns: repeat(2, 1fr) !important;
            gap: 12px !important;
            padding: 0 8px !important;
        }
        @media (min-width: 600px) and (max-height: 500px) {
            .card-grid { grid-template-columns: repeat(3, 1fr) !important; }
        }
        @media (min-width: 820px) {
            .card-grid { grid-template-columns: repeat(3, 1fr) !important; gap: 16px !important; }
        }
        @media (min-width: 1100px) {
            .card-grid { grid-template-columns: repeat(4, 1fr) !important; gap: 18px !important; }
        }

        .card { overflow: hidden !important; position: relative !important; }
        .card .card-image-wrapper { aspect-ratio: 1 !important; }
        .card .card-image { width: 100% !important; height: 100% !important; object-fit: cover !important; }
        .card .card-info { text-align: center !important; padding: 8px 4px !important; }
        .card .card-title {
            font-size: 0.9rem !important;
            white-space: normal !important;
            word-wrap: break-word !important;
            line-height: 1.3 !important;
            display: -webkit-box !important;
            -webkit-line-clamp: 2 !important;
            -webkit-box-orient: vertical !important;
            overflow: hidden !important;
        }
        .card .card-subtitle { font-size: 0.8rem !important; }
        .card.compact { flex-direction: column !important; align-items: center !important; padding: 8px !important; }
        .card.artist .card-image-wrapper {
            border-radius: 50% !important;
            overflow: hidden !important;
            margin: 0 auto !important;
            width: 140px !important;
            height: 140px !important;
        }
        .card.artist .card-image {
            border-radius: 50% !important;
            width: 140px !important;
            height: 140px !important;
            object-fit: cover !important;
        }

        @media (hover: none) {
            .card:active,
            .track-item:active,
            .now-playing-bar:active,
            .search-history-item:active {
                background: rgba(255, 255, 255, 0.08) !important;
                transition: background 80ms ease-out !important;
            }
            .card:active {
                transform: scale(0.97) !important;
                transition: transform 100ms ease-out, background 80ms ease-out !important;
            }
        }

        @media (prefers-reduced-motion: no-preference) {
            .main-content {
                transition: opacity 180ms ease-out !important;
            }
            .main-content.fm-route-leaving {
                opacity: 0.3 !important;
            }
        }

        @keyframes fmSkeletonShimmer {
            0% { background-position: 200% 0; }
            100% { background-position: -200% 0; }
        }
        .fm-skeleton {
            background: linear-gradient(90deg, #1a1a1a 25%, #2a2a2a 50%, #1a1a1a 75%) !important;
            background-size: 200% 100% !important;
            animation: fmSkeletonShimmer 1.5s linear infinite !important;
            border-radius: 8px !important;
        }

        #search-history {
            max-height: 320px !important;
            overflow-y: auto !important;
            -webkit-overflow-scrolling: touch !important;
        }
        #search-history .search-history-item.fm-selected {
            background: var(--accent, rgba(255,255,255,0.12)) !important;
        }


        #fm-pull-refresh {
            position: fixed;
            top: calc(var(--fm-safe-top) + 4px);
            left: 50%;
            transform: translateX(-50%) translateY(-60px);
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: rgba(20, 20, 20, 0.92);
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 99998;
            transition: transform 200ms ease-out;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
            pointer-events: none;
        }
        #fm-pull-refresh.fm-active { transform: translateX(-50%) translateY(10px); }
        #fm-pull-refresh svg { color: #fff; }
        #fm-pull-refresh.fm-loading svg { animation: fmSpin 800ms linear infinite; }
        @keyframes fmSpin { to { transform: rotate(360deg); } }

        @keyframes fmToastIn {
            from { opacity: 0; transform: translateX(-50%) translateY(20px); }
            to   { opacity: 1; transform: translateX(-50%) translateY(0);    }
        }

    `;
    document.head.appendChild(style);

    // ── BACK BUTTON ──
    const hamburger = document.getElementById('hamburger-btn');
    if (hamburger) {
        const backBtn = document.createElement('button');
        backBtn.id = 'android-back-btn';
        backBtn.title = 'Back';
        backBtn.setAttribute('aria-label', 'Back');
        backBtn.style.cssText =
            'display:none;background:none;border:none;color:var(--foreground);padding:6px;cursor:pointer;flex-shrink:0;';
        backBtn.appendChild(
            _mkSvg(
                {
                    width: '24',
                    height: '24',
                    viewBox: '0 0 24 24',
                    fill: 'none',
                    stroke: 'currentColor',
                    'stroke-width': '2',
                    'stroke-linecap': 'round',
                    'stroke-linejoin': 'round',
                },
                ['m15 18-6-6 6-6'],
            ),
        );
        hamburger.parentNode.insertBefore(backBtn, hamburger);

        const updateBackButton = () => {
            const p = window.location.pathname;
            const isSubPage = p !== '/' && p !== '/home' && p !== '';
            backBtn.style.display = isSubPage ? 'inline-flex' : 'none';
        };

        let _backPressTime = 0;

        // ── Dismiss any open overlay / modal / fullscreen pane ──
        // Returns true if something was dismissed, false otherwise.
        const fmDismissTopOverlay = () => {
            // 1. Live search preview dropdown
            const preview = document.getElementById('fm-search-preview');
            if (preview && preview.classList.contains('fm-visible')) {
                preview.classList.remove('fm-visible');
                return true;
            }

            // 2. Fullscreen cover / lyrics pane
            //    Upstream puts the lyrics pane INSIDE the fullscreen cover.
            const fsCover = document.getElementById('fullscreen-cover-overlay');
            if (fsCover && getComputedStyle(fsCover).display !== 'none') {
                // If lyrics mode is active inside the cover, toggle it off first
                const lyricsPane = document.getElementById('fullscreen-lyrics-pane');
                if (lyricsPane && getComputedStyle(lyricsPane).display !== 'none') {
                    const lyricsToggle =
                        document.getElementById('toggle-fullscreen-lyrics-btn') ||
                        document.getElementById('toggle-fullscreen-lyrics-mobile-btn');
                    if (lyricsToggle) {
                        lyricsToggle.click();
                        return true;
                    }
                }
                // Otherwise close the fullscreen cover entirely
                const closeBtn = document.getElementById('close-fullscreen-cover-btn');
                if (closeBtn) {
                    closeBtn.click();
                    return true;
                }
                if (window.location.hash === '#fullscreen') {
                    window.history.back();
                    return true;
                }
            }

            // 3. Queue modal
            const queueModal = document.getElementById('queue-modal');
            if (queueModal) {
                const qs = getComputedStyle(queueModal);
                const isOpen =
                    queueModal.classList.contains('active') ||
                    queueModal.classList.contains('open') ||
                    (qs.display !== 'none' && qs.visibility !== 'hidden' && qs.opacity !== '0');
                if (isOpen) {
                    const queueClose =
                        document.getElementById('close-queue-btn') ||
                        queueModal.querySelector('.close-btn, .close-modal-btn, [data-action="close"]');
                    if (queueClose) {
                        queueClose.click();
                        return true;
                    }
                    queueModal.classList.remove('active', 'open');
                    queueModal.style.display = 'none';
                    return true;
                }
            }

            // 4. Any generic .modal.active — click its close button, otherwise strip .active
            const openModals = Array.from(document.querySelectorAll('.modal.active'));
            if (openModals.length > 0) {
                const topModal = openModals[openModals.length - 1];
                const closeBtn = topModal.querySelector(
                    '.close-modal-btn, .btn-close, [data-action="close"], .email-auth-modal-close',
                );
                if (closeBtn) {
                    closeBtn.click();
                } else {
                    topModal.classList.remove('active');
                }
                return true;
            }

            // 5. Side panel
            const sidePanel = document.querySelector('.side-panel.open, .side-panel.active, .sidebar.open');
            if (sidePanel) {
                sidePanel.classList.remove('open', 'active');
                return true;
            }

            // 6. Search history dropdown
            const historyEl = document.getElementById('search-history');
            if (historyEl && historyEl.style.display !== 'none' && historyEl.children.length > 0) {
                historyEl.style.display = 'none';
                return true;
            }

            return false;
        };

        backBtn.addEventListener('click', () => {
            if (fmDismissTopOverlay()) return;
            hapticLight();
            window.history.back();
        });

        updateBackButton();
    } // end if (hamburger)

    // ── SEARCH HOOKS REMOVED ──
    // Previous versions had: dropdown live preview, match highlight observer,
    // keyboard history nav, cache cleanup, skeleton monkey-patch, prefetch.
    // All removed to avoid interfering with the upstream search flow.
    // The upstream debounce (700ms) + limit=100 + min-3-char patch handles
    // the search UX entirely; no wrapper-side JS needed.

    // (search hooks block starts here — will be removed)
    // ── pull-to-refresh ──
    const pullEl = document.createElement('div');
    pullEl.id = 'fm-pull-refresh';
    pullEl.appendChild(
        _mkSvg(
            {
                width: '22',
                height: '22',
                viewBox: '0 0 24 24',
                fill: 'none',
                stroke: 'currentColor',
                'stroke-width': '2.2',
                'stroke-linecap': 'round',
                'stroke-linejoin': 'round',
            },
            ['M21 12a9 9 0 1 1-3-6.7L21 8', 'M21 3v5h-5'],
        ),
    );
    document.body.appendChild(pullEl);

    let _pullStartY = 0;
    let _isPulling = false;
    let _pullActive = false;
    document.addEventListener(
        'touchstart',
        (e) => {
            const mc = document.querySelector('.main-content');
            if (!mc || mc.scrollTop > 2) return;
            if (e.target.closest('.side-panel, #queue-modal, #fm-search-preview, #search-history, .modal')) return;
            _pullStartY = e.touches[0].clientY;
            _isPulling = true;
        },
        { passive: true },
    );
    document.addEventListener(
        'touchmove',
        (e) => {
            if (!_isPulling) return;
            const dy = e.touches[0].clientY - _pullStartY;
            if (dy > 70) {
                if (!_pullActive) {
                    _pullActive = true;
                    pullEl.classList.add('fm-active');
                }
            } else if (dy < 40 && _pullActive) {
                _pullActive = false;
                pullEl.classList.remove('fm-active');
            }
        },
        { passive: true },
    );
    document.addEventListener(
        'touchend',
        () => {
            if (!_isPulling) return;
            _isPulling = false;
            if (_pullActive) {
                pullEl.classList.add('fm-loading');
                try {
                    const path = window.location.pathname;
                    if (typeof window.ui?.renderLibraryPage === 'function' && path.startsWith('/library')) {
                        window.ui.renderLibraryPage();
                    } else {
                        window.dispatchEvent(new PopStateEvent('popstate'));
                    }
                } catch {
                    /* ignore */
                }
                setTimeout(() => {
                    pullEl.classList.remove('fm-active');
                    pullEl.classList.remove('fm-loading');
                    _pullActive = false;
                }, 900);
            }
        },
        { passive: true },
    );
    document.addEventListener(
        'touchcancel',
        () => {
            _isPulling = false;
            _pullActive = false;
            pullEl.classList.remove('fm-active');
        },
        { passive: true },
    );

    // ── MEDIA COMMANDS FROM NOTIFICATION ──
    AudioService.addListener('mediaCommand', (data) => {
        const audio = document.getElementById('audio-player');
        if (!audio) return;
        switch (data.command) {
            case 'play':
                audio.play().catch(() => {});
                break;
            case 'pause':
                audio.pause();
                break;
            case 'next':
                document.getElementById('next-btn')?.click();
                break;
            case 'prev':
                document.getElementById('prev-btn')?.click();
                break;
        }
    });

    function getTrackInfo() {
        const titleEl = document.querySelector('.now-playing-bar .title');
        const artistEl = document.querySelector('.now-playing-bar .artist');
        const coverEl = document.querySelector('.now-playing-bar .track-info img.cover');
        const audio = document.getElementById('audio-player');
        return {
            title: titleEl?.textContent?.trim() || 'Fabiodalez Music',
            artist: artistEl?.textContent?.trim() || 'Music',
            cover: coverEl?.src || null,
            position: Math.floor((audio?.currentTime || 0) * 1000),
            duration: Math.floor((audio?.duration || 0) * 1000),
        };
    }

    function sendUpdate(playing) {
        const info = getTrackInfo();
        AudioService.start({
            title: info.title,
            text: info.artist,
            cover: info.cover,
            playing,
            position: info.position,
            duration: info.duration,
        }).catch(() => {});
    }

    AudioService.start({
        title: 'Fabiodalez Music',
        text: 'Select a song',
        cover: null,
        playing: false,
        position: 0,
        duration: 0,
    }).catch(() => {});

    const audio = document.getElementById('audio-player');
    if (!audio) return;

    // Adaptive timeupdate throttle: rely on MediaSession elapsedRealtime interpolation.
    let lastUpdate = 0;
    let lastKnownPosition = 0;
    audio.addEventListener('timeupdate', () => {
        const now = Date.now();
        const pos = Math.floor(audio.currentTime * 1000);
        const drift = Math.abs(pos - lastKnownPosition - (now - lastUpdate));
        if (drift > 1200 || now - lastUpdate >= 5000) {
            lastUpdate = now;
            lastKnownPosition = pos;
            sendUpdate(!audio.paused);
        }
    });
    audio.addEventListener('seeking', () => {
        lastUpdate = 0;
    });

    const titleObserver = new MutationObserver(() => {
        if (document.title && document.title.includes('\u2022')) {
            setTimeout(() => sendUpdate(!audio.paused), 500);
        }
    });
    const titleTag = document.querySelector('title');
    if (titleTag) {
        titleObserver.observe(titleTag, { childList: true, characterData: true, subtree: true });
    }

    audio.addEventListener('playing', () => sendUpdate(true));
    audio.addEventListener('pause', () => sendUpdate(false));
    audio.addEventListener('ended', () => sendUpdate(false));
})();
