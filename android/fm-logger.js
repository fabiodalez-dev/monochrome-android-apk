// fm-logger.js — Console logger to file (loaded FIRST, before any other scripts)
// Saves all console output to Downloads/FabiodalezMusic/fm-console-log.txt
(function () {
    var _logBuffer = [];
    var _MAX = 3000;
    var _origLog = console.log;
    var _origWarn = console.warn;
    var _origError = console.error;
    var _origInfo = console.info;
    var _origDebug = console.debug;
    var _hasErrors = false;

    var _ts = function () {
        return new Date().toISOString().slice(11, 23);
    };

    var _capture = function (level, args) {
        try {
            var parts = [];
            for (var i = 0; i < args.length; i++) {
                try {
                    if (typeof args[i] === 'object' && args[i] !== null) {
                        if (args[i] instanceof Error) {
                            parts.push(args[i].message + (args[i].stack ? '\n' + args[i].stack : ''));
                        } else {
                            parts.push(JSON.stringify(args[i], null, 0));
                        }
                    } else {
                        parts.push(String(args[i]));
                    }
                } catch (e) {
                    parts.push('[unserializable]');
                }
            }
            var msg = _ts() + ' [' + level + '] ' + parts.join(' ');
            if (_logBuffer.length >= _MAX) _logBuffer.shift();
            _logBuffer.push(msg);
            if (level === 'ERR' || level === 'UNCAUGHT' || level === 'UNHANDLED') {
                _hasErrors = true;
            }
        } catch (e) {
            // logger itself must never throw
        }
    };

    console.log = function () {
        _capture('LOG', arguments);
        return _origLog.apply(console, arguments);
    };
    console.warn = function () {
        _capture('WARN', arguments);
        return _origWarn.apply(console, arguments);
    };
    console.error = function () {
        _capture('ERR', arguments);
        return _origError.apply(console, arguments);
    };
    console.info = function () {
        _capture('INFO', arguments);
        return _origInfo.apply(console, arguments);
    };
    console.debug = function () {
        _capture('DBG', arguments);
        return _origDebug.apply(console, arguments);
    };

    window.addEventListener('error', function (e) {
        _capture('UNCAUGHT', [
            e.message || 'unknown error',
            'at ' + (e.filename || '?') + ':' + (e.lineno || '?') + ':' + (e.colno || '?'),
        ]);
    });

    window.addEventListener('unhandledrejection', function (e) {
        var reason = e.reason;
        if (reason instanceof Error) {
            _capture('UNHANDLED', [reason.message, reason.stack || '']);
        } else {
            _capture('UNHANDLED', [reason]);
        }
    });

    // Intercept fetch errors globally
    var _origFetch = window.fetch;
    if (_origFetch) {
        window.fetch = function () {
            var url = arguments[0];
            if (typeof url === 'object' && url.url) url = url.url;
            return _origFetch.apply(this, arguments).then(
                function (resp) {
                    if (!resp.ok && resp.status >= 400) {
                        _capture('FETCH', [resp.status + ' ' + resp.statusText + ' ' + url]);
                    }
                    return resp;
                },
                function (err) {
                    _capture('FETCH_ERR', [String(url).substring(0, 200) + ' → ' + (err.message || err)]);
                    throw err;
                }
            );
        };
    }

    // Save function
    var _saveLog = function () {
        if (!_logBuffer.length) return;
        // Wait for AndroidDownload bridge to be available
        if (!window.AndroidDownload) {
            // Retry in 2s
            setTimeout(_saveLog, 2000);
            return;
        }
        try {
            var text =
                '=== Fabiodalez Music Console Log ===\n' +
                'Saved: ' + new Date().toISOString() + '\n' +
                'URL: ' + location.href + '\n' +
                'UA: ' + navigator.userAgent + '\n' +
                'Entries: ' + _logBuffer.length + '\n' +
                'Errors: ' + (_hasErrors ? 'YES' : 'none') + '\n' +
                '===================================\n\n' +
                _logBuffer.join('\n');
            var base64 = btoa(unescape(encodeURIComponent(text)));
            window.AndroidDownload.saveBase64(base64, 'fm-console-log.txt', 'text/plain');
        } catch (err) {
            _origError.call(console, '[fm-logger] save failed:', err);
        }
    };

    // Save on visibility change (app minimized)
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') _saveLog();
    });

    // Save every 30 seconds
    setInterval(_saveLog, 30000);

    // Expose manual save
    window._fmSaveLog = _saveLog;
    window._fmGetLog = function () {
        return _logBuffer.join('\n');
    };

    _capture('INFO', ['fm-logger v2 initialized (early-load, fetch interceptor, max ' + _MAX + ')']);
})();
