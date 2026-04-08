// ── ANDROID BRIDGES (synchronous, run immediately) ──

// ── CLIPBOARD FALLBACK ──
if (window.AndroidBridge) {
    const origClipboard = navigator.clipboard?.writeText?.bind(navigator.clipboard);
    if (navigator.clipboard) {
        navigator.clipboard.writeText = function(text) {
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
                writeText: function(text) {
                    window.AndroidBridge.copyToClipboard(text);
                    return Promise.resolve();
                }
            }
        });
    }
}

// ── OAUTH / WINDOW.OPEN OVERRIDE ──
// Redirect window.open to Android's in-app browser (Chrome Custom Tab)
if (window.AndroidBridge) {
    const origOpen = window.open.bind(window);
    window.open = function(url, target, features) {
        if (url && url.startsWith('http')) {
            window.AndroidBridge.openInBrowser(url);
            // Return a mock window object so OAuth polling code doesn't crash
            return {
                closed: false,
                close: function() { this.closed = true; },
                location: { _url: url, get href() { return this._url; }, set href(v) { this._url = v; window.AndroidBridge.openInBrowser(v); } },
            };
        }
        if (url === '' || !url) {
            // OAuth flow: opens blank first, then sets location.href
            const mockWindow = {
                closed: false,
                close: function() { this.closed = true; },
                location: {
                    _href: '',
                    get href() { return this._href; },
                    set href(v) {
                        this._href = v;
                        if (v && v.startsWith('http')) {
                            window.AndroidBridge.openInBrowser(v);
                        }
                    }
                },
            };
            return mockWindow;
        }
        return origOpen(url, target, features);
    };
}

// ── TOUCH DRAG POLYFILL for queue reordering ──
// HTML5 drag-and-drop doesn't work on mobile touch. Convert touch events to drag events.
if ('ontouchstart' in window) {
    let _dragEl = null;
    let _dragStartY = 0;

    document.addEventListener('touchstart', (e) => {
        const item = e.target.closest('[draggable="true"]');
        if (!item) return;
        _dragEl = item;
        _dragStartY = e.touches[0].clientY;
        item.style.opacity = '0.5';
    }, { passive: true });

    document.addEventListener('touchmove', (e) => {
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
    }, { passive: true });

    document.addEventListener('touchend', () => {
        if (_dragEl) {
            _dragEl.style.opacity = '';
            // Trigger a "drop" by dispatching a custom event with the new order
            const container = _dragEl.closest('.queue-list, .track-list, .playlist-tracks');
            if (container) {
                const items = container.querySelectorAll('[data-queue-index]');
                items.forEach((item, i) => { item.dataset.queueIndex = i; });
            }
            _dragEl = null;
        }
    }, { passive: true });
}

// ── DOWNLOAD HANDLER (synchronous, runs immediately) ──

const _blobStore = new Map();

const _origCreate = URL.createObjectURL.bind(URL);
URL.createObjectURL = function(obj) {
    const url = _origCreate(obj);
    if (obj instanceof Blob) _blobStore.set(url, obj);
    return url;
};

const _origRevoke = URL.revokeObjectURL.bind(URL);
URL.revokeObjectURL = function(url) {
    setTimeout(() => _blobStore.delete(url), 10000);
    return _origRevoke(url);
};

const _origClick = HTMLAnchorElement.prototype.click;
HTMLAnchorElement.prototype.click = function() {
    if (this.hasAttribute('download') && this.href && this.href.startsWith('blob:')) {
        const filename = this.download || 'download';
        const blob = _blobStore.get(this.href);
        if (blob && window.AndroidDownload) {
            _saveBlobNative(blob, filename);
            return;
        }
        // Fallback: try fetch
        if (window.AndroidDownload) {
            fetch(this.href).then(r => r.blob()).then(b => _saveBlobNative(b, filename)).catch(() => {});
            return;
        }
    }
    return _origClick.call(this);
};

function _saveBlobNative(blob, filename) {
    const reader = new FileReader();
    reader.onloadend = () => {
        if (!reader.result) return;
        const dataUri = reader.result;
        const base64 = dataUri.substring(dataUri.indexOf(',') + 1);
        let mime = 'application/octet-stream';
        if (dataUri.startsWith('data:') && dataUri.includes(';')) {
            mime = dataUri.substring(5, dataUri.indexOf(';'));
        }
        window.AndroidDownload.saveBase64(base64, filename, mime);
    };
    reader.readAsDataURL(blob);
}

// ── LOCAL FILES BRIDGE ──
// Hook into the "Select Music Folder" button to use Android's folder picker
if (window.AndroidLocalFiles) {
    // Make the button visible (upstream hides it on mobile)
    const _waitForBtn = setInterval(() => {
        const btn = document.getElementById('select-local-folder-btn');
        const warn = document.getElementById('local-browser-warning');
        if (btn) {
            btn.style.display = 'flex';
            if (warn) warn.style.display = 'none';
            clearInterval(_waitForBtn);
        }
    }, 500);

    // Override showDirectoryPicker for Android
    window.showDirectoryPicker = function() {
        return new Promise((resolve, reject) => {
            // Set up callbacks that the native bridge will call
            window._androidLocalFilesResolve = resolve;
            window._androidLocalFilesReject = reject;
            window.AndroidLocalFiles.pickFolder();
        });
    };

    // Callbacks from native LocalFilesBridge
    let _collectedFiles = [];
    // eslint-disable-next-line no-unused-vars
    let _pendingResolve = null;

    window._androidLocalFilesStart = function(count) {
        _collectedFiles = [];
        const btn = document.getElementById('select-local-folder-btn');
        const btnText = document.getElementById('select-local-folder-text');
        if (btnText) btnText.textContent = 'Scanning... (0/' + count + ')';
        else if (btn) btn.textContent = 'Scanning...';
    };

    window._androidLocalFileReady = function(filename, base64Data, index, total) {
        try {
            const binary = atob(base64Data);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            const ext = filename.split('.').pop().toLowerCase();
            const mimeMap = { flac: 'audio/flac', mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', ogg: 'audio/ogg' };
            const file = new File([bytes], filename, { type: mimeMap[ext] || 'audio/mpeg' });
            _collectedFiles.push(file);

            const btnText = document.getElementById('select-local-folder-text');
            if (btnText) btnText.textContent = 'Scanning... (' + (index + 1) + '/' + total + ')';
        } catch {
            // Skip unreadable file
        }
    };

    window._androidLocalFilesDone = async function() {
        const tracks = [];
        for (let i = 0; i < _collectedFiles.length; i++) {
            const f = _collectedFiles[i];
            tracks.push({
                id: 'local-' + i + '-' + f.name,
                title: f.name.replace(/\.[^.]+$/, ''),
                artist: { name: 'Local' },
                artists: [{ name: 'Local' }],
                album: { title: 'Local Files' },
                duration: 0,
                isLocal: true,
                audioUrl: URL.createObjectURL(f),
            });
        }

        tracks.sort((a, b) => a.title.localeCompare(b.title));

        window.localFilesCache = tracks;
        const btn = document.getElementById('select-local-folder-btn');
        const btnText = document.getElementById('select-local-folder-text');
        if (btnText) btnText.textContent = tracks.length + ' tracks loaded';
        else if (btn) btn.textContent = tracks.length + ' tracks loaded';
        if (btn) btn.disabled = false;

        // Trigger library re-render if the function exists
        if (typeof window.ui?.renderLibraryPage === 'function') {
            window.ui.renderLibraryPage();
        }
    };

    window._androidLocalFilesError = function() {
        const btn = document.getElementById('select-local-folder-btn');
        const btnText = document.getElementById('select-local-folder-text');
        if (btnText) btnText.textContent = 'Select Music Folder';
        else if (btn) btn.textContent = 'Select Music Folder';
        if (btn) btn.disabled = false;
    };
}

// ── MEDIA CONTROLS + CSS (async, loads after Capacitor bridge) ──
(async () => {
    let AudioService;
    try {
        const { Capacitor, registerPlugin } = await import('@capacitor/core');
        if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== 'android') return;
        AudioService = registerPlugin('AudioService');
    } catch { return; }

    // CSS: notch + notifications position
    const style = document.createElement('style');
    style.textContent = `
        .main-content { padding-bottom: 120px !important; }
        .side-panel { padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px)) !important; }
        #queue-modal { padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px)) !important; }
        #download-notifications { top: 20px !important; bottom: auto !important; }
        #sidebar-nav-download-bottom { display: none !important; }
        #cast-btn, #fs-cast-btn { display: none !important; }
        .track-item-cover { width: 56px !important; height: 56px !important; border-radius: 6px !important; }
        .track-item-details .title { font-size: 1.05rem !important; }
        .track-item-details .artist { font-size: 0.85rem !important; }
        html {
            padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px)) !important;
            padding-bottom: 0 !important;
            background: #000 !important;
        }
        .main-header { gap: 4px !important; padding: 6px 8px !important; flex-wrap: nowrap !important; overflow: hidden !important; max-width: 100vw !important; box-sizing: border-box !important; }
        .main-header .search-bar { min-width: 0 !important; flex: 1 1 0% !important; width: 0 !important; }
        .main-header .header-account-control { margin-right: 2px !important; flex-shrink: 0 !important; }
        .main-header .hamburger-menu { flex-shrink: 0 !important; }
        #android-back-btn { flex-shrink: 0 !important; }
        #android-back-btn:active { background: var(--muted); border-radius: 50%; }
        .card-grid { grid-template-columns: repeat(2, 1fr) !important; gap: 12px !important; padding: 0 8px !important; }
        .card { overflow: hidden !important; }
        .card .card-image-wrapper { aspect-ratio: 1 !important; }
        .card .card-image { width: 100% !important; height: 100% !important; object-fit: cover !important; }
        .card .card-info { text-align: center !important; padding: 8px 4px !important; }
        .card .card-title { font-size: 0.9rem !important; white-space: normal !important; word-wrap: break-word !important; line-height: 1.3 !important; display: -webkit-box !important; -webkit-line-clamp: 2 !important; -webkit-box-orient: vertical !important; overflow: hidden !important; }
        .card .card-subtitle { font-size: 0.8rem !important; }
        .card.compact { flex-direction: column !important; align-items: center !important; padding: 8px !important; }
        .card.artist .card-image-wrapper { border-radius: 50% !important; overflow: hidden !important; margin: 0 auto !important; width: 140px !important; height: 140px !important; }
        .card.artist .card-image { border-radius: 50% !important; width: 140px !important; height: 140px !important; object-fit: cover !important; }
    `;
    document.head.appendChild(style);

    // ── BACK BUTTON (inside header, before hamburger) ──
    const hamburger = document.getElementById('hamburger-btn');
    if (hamburger) {
        const backBtn = document.createElement('button');
        backBtn.id = 'android-back-btn';
        backBtn.title = 'Back';
        backBtn.style.cssText = 'display:none;background:none;border:none;color:var(--foreground);padding:6px;cursor:pointer;flex-shrink:0;';
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('width', '24');
        svg.setAttribute('height', '24');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        pathEl.setAttribute('d', 'm15 18-6-6 6-6');
        svg.appendChild(pathEl);
        backBtn.appendChild(svg);
        hamburger.parentNode.insertBefore(backBtn, hamburger);

        function updateBackButton() {
            const p = window.location.pathname;
            const isSubPage = p !== '/' && p !== '/home' && p !== '';
            backBtn.style.display = isSubPage ? 'inline-flex' : 'none';
        }

        backBtn.addEventListener('click', () => {
            window.history.back();
        });

        const origPushState = history.pushState.bind(history);
        history.pushState = function() {
            origPushState.apply(this, arguments);
            updateBackButton();
            // Force scroll to top on navigation
            const mc = document.querySelector('.main-content');
            if (mc) mc.scrollTop = 0;
            window.scrollTo(0, 0);
        };
        const origReplaceState = history.replaceState.bind(history);
        history.replaceState = function() {
            origReplaceState.apply(this, arguments);
            updateBackButton();
        };
        window.addEventListener('popstate', () => {
            updateBackButton();
        });
        updateBackButton();
    }

    // Media commands from notification
    AudioService.addListener('mediaCommand', (data) => {
        const audio = document.getElementById('audio-player');
        if (!audio) return;
        switch (data.command) {
            case 'play': audio.play().catch(() => {}); break;
            case 'pause': audio.pause(); break;
            case 'next': document.getElementById('next-btn')?.click(); break;
            case 'prev': document.getElementById('prev-btn')?.click(); break;
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
            title: info.title, text: info.artist, cover: info.cover,
            playing, position: info.position, duration: info.duration,
        }).catch(() => {});
    }

    // Start with placeholder
    AudioService.start({
        title: 'Fabiodalez Music', text: 'Select a song', cover: null,
        playing: false, position: 0, duration: 0,
    }).catch(() => {});

    const audio = document.getElementById('audio-player');
    if (!audio) return;

    let lastUpdate = 0;
    audio.addEventListener('timeupdate', () => {
        const now = Date.now();
        if (now - lastUpdate < 3000) return;
        lastUpdate = now;
        sendUpdate(true);
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
