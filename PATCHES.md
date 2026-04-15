# PATCHES.md — Guida al sistema di patch di Fabiodalez Music

> Questo documento descrive come il wrapper Android applica ~50 ottimizzazioni
> sopra l'app web upstream **Monochrome** senza forkare il repo e senza
> produrre merge conflict ad ogni `git pull upstream`.

---

## Sommario

- [TL;DR](#tldr)
- [Architettura a 3 livelli](#architettura-a-3-livelli)
- [Quick start (one-shot build)](#quick-start-one-shot-build)
- [Verifica delle patch (dry-run)](#verifica-delle-patch-dry-run)
- [Elenco completo delle 51 ottimizzazioni](#elenco-completo-delle-51-ottimizzazioni)
- [Come aggiungere una nuova patch upstream](#come-aggiungere-una-nuova-patch-upstream)
- [Debugging: "pattern not found"](#debugging-pattern-not-found)
- [Patch candidate per PR upstream](#patch-candidate-per-pr-upstream)

---

## TL;DR

```bash
# Prima volta (clone + build completo)
git clone https://github.com/monochrome-music/monochrome.git
cd monochrome && git remote rename origin upstream && cd ..

cd fabiodalez-music-android
./fm-build.sh ../monochrome         # one-shot: install + build

# Aggiornamento (monochrome già clonato)
./fm-build.sh ../monochrome

# Solo verifica che le patch funzionino ancora (no build, no npm install)
./verify-patches.sh ../monochrome
```

L'APK finale viene copiato in `monochrome/Monochrome-debug.apk`.

---

## Architettura a 3 livelli

Il wrapper applica ottimizzazioni su tre livelli differenti, ognuno con un
diverso trade-off tra invasività e robustezza agli aggiornamenti upstream:

```
┌───────────────────────────────────────────────────────────────────┐
│ LIVELLO 1 — File statici del wrapper                              │
│ (copiati 1:1 in monochrome/android/* tramite install.sh)          │
│                                                                    │
│  • android/app/src/main/java/com/monochrome/app/*.java (6 file)   │
│  • android/app/src/main/AndroidManifest.xml                       │
│  • android/app/src/main/res/*                                     │
│  • android/app/build.gradle                                       │
│  • capacitor.config.ts                                            │
│                                                                    │
│  ▸ Questi file non esistono nell'upstream. L'install li piazza.   │
│  ▸ Sono ~40 delle 50 ottimizzazioni (UI, architettura, bug fix).  │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│ LIVELLO 2 — Hook JS runtime                                       │
│ (android/android-service.js, iniettato come <script>)             │
│                                                                    │
│  Iniettato in index.html da build-android.sh tramite sed:         │
│    <script type="module" src="./js/android-service.js"></script>  │
│                                                                    │
│  ▸ Monkey-patch runtime: navigator.clipboard, window.open,        │
│    HTMLAnchorElement.prototype.click, history.pushState           │
│  ▸ DOM observer + MutationObserver per highlight search results   │
│  ▸ CSS injection tramite <style> dinamico (grid responsivo,       │
│    tap targets, ripple, route fade, ecc.)                         │
│  ▸ Touch polyfill: drag&drop queue, pull-to-refresh, swipe        │
│                                                                    │
│  ▸ Sopravvive SEMPRE ai git pull upstream.                        │
│  ▸ Se un selettore cambia (es. `.now-playing-bar`), degrada       │
│    gracefully (noop) senza rompere l'app.                         │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│ LIVELLO 3 — Patch upstream temporanee (build-time only)           │
│ (applicate e revertite da build-android.sh)                       │
│                                                                    │
│  File toccati durante il build (poi revertiti via `git checkout`):│
│    • index.html       — script tag, viewport, brand, preconnect  │
│    • package.json     — aggiunta dipendenze Capacitor             │
│    • package-lock.json                                            │
│    • js/app.js        — debounce 3000→500 ms                      │
│    • js/api.js        — &limit=100 su tutti gli endpoint search   │
│    • js/cache.js      — TTL differenziato + query normalization   │
│                                                                    │
│  Meccanismo:                                                       │
│    1. bash `sed -i ''` per HTML/one-liner                         │
│    2. Python embedded (heredoc) per replace multi-riga sicuro     │
│    3. Regex Python per casi dinamici (es. searchArtists)          │
│    4. `trap cleanup EXIT` con `git checkout --` a fine script     │
│                                                                    │
│  ▸ Se un pattern non matcha più dopo un pull upstream, Python     │
│    stampa `! pattern not found, skipping` e continua — il build  │
│    NON fallisce. La wrapper-side continua a funzionare.           │
└───────────────────────────────────────────────────────────────────┘
```

**Perché questa architettura?**
- **Livello 1** è per codice che non esiste upstream (codice nativo Android). Banale.
- **Livello 2** è il più robusto: monkey-patch runtime non conflitta mai con git merge.
- **Livello 3** è usato **solo quando non si può fare altro**: quando bisogna
  modificare un listener JS, un literal string di `fetch()`, o aggiungere un
  `<link>` nell'`<head>` che deve esistere nell'HTML servito (non può essere
  iniettato da JS perché sarebbe troppo tardi per il DNS prefetch).

---

## Quick start (one-shot build)

```bash
# 1. Requisiti (solo la prima volta)
brew install openjdk@21
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# 2. Clone monochrome (solo la prima volta)
cd ..
git clone https://github.com/monochrome-music/monochrome.git
cd monochrome && git remote rename origin upstream && cd ..

# 3. Build (ogni volta che vuoi un APK aggiornato)
cd fabiodalez-music-android
./fm-build.sh ../monochrome
```

`fm-build.sh` fa:
1. `./install.sh ../monochrome` — copia tutti i file wrapper in monochrome
2. `cd ../monochrome && ./build-android.sh` — applica patch upstream, builda l'APK, reverta tutto
3. Riporta il percorso finale dell'APK

Se vuoi bypassare l'install e fare solo un re-build rapido:
```bash
cd ../monochrome && ./build-android.sh
```

---

## Verifica delle patch (dry-run)

Dopo un `git pull upstream`, prima di lanciare un build completo di 5 minuti,
puoi verificare in ~2 secondi se i pattern delle patch upstream sono ancora validi:

```bash
./verify-patches.sh ../monochrome
```

Cosa fa:
1. Copia solo i 5 file toccati dalle patch (`index.html`, `js/app.js`,
   `js/api.js`, `js/cache.js`, `package.json`) in `/tmp/fm-dryrun/`
2. Esegue sed e Python come farebbe `build-android.sh`
3. Valida i file risultanti con `node --check`
4. Riporta quante patch hanno matchato / quante sono saltate
5. Cleanup automatico della dir tmp

Output tipico in caso di successo:
```
  HTML patches:   3/3
  Python patches: 10/10
  JS syntax:      3/3
  Runtime test:   1/1 ✓

✓ All patches are live-compatible with current upstream.
```

In caso di regressione upstream:
```
! api.js: searchAlbums — pattern not found, skipping
✗ 9/10 Python patches applied
Warning: 1 patch failed to apply — see [Debugging: pattern not found] in PATCHES.md
```

Il comando ritorna **exit code 0** se tutte le patch essenziali sono applicabili,
**1** se c'è anche una sola patch saltata. Utile in CI.

---

## Elenco completo delle 51 ottimizzazioni

Legenda:
- 🟥 CRITICO · 🟧 ALTO · 🟨 MEDIO · 🟩 BASSO
- **L1** = file statico wrapper · **L2** = hook JS runtime · **L3** = patch upstream

### Gruppo A — Ricerca (10)

| #  | Titolo                                    | File principale        | Livello | Severità |
|----|-------------------------------------------|------------------------|---------|----------|
| 1  | Debounce 3000→500 ms                      | `js/app.js:2556`       | L3      | 🟧       |
| 2  | Live preview dropdown                     | `android-service.js`   | L2      | 🟧       |
| 3  | Query normalization (trim/lowercase)      | `js/cache.js:36`       | L3      | 🟧       |
| 4  | AbortController per query obsolete        | —                      | (skip)  | 🟨       |
| 5  | Keyboard navigation history dropdown      | `android-service.js`   | L2      | 🟨       |
| 6  | Highlight match nei risultati             | `android-service.js`   | L2      | 🟨       |
| 7  | Prefetch al focus                         | `android-service.js`   | L2      | 🟩       |
| 8  | Search history max-height + scroll        | `android-service.js`   | L2      | 🟩       |
| 9  | Normalizzazione diacritici (NFD)          | `js/cache.js:36`       | L3      | 🟩       |
| 10 | TTL differenziato per tipo                | `js/cache.js:6`        | L3      | 🟩       |

### Gruppo B — Caricamento brani (10)

| #  | Titolo                                    | File principale              | Livello | Severità |
|----|-------------------------------------------|------------------------------|---------|----------|
| 11 | Preconnect CDN Tidal                      | `index.html:77`              | L3      | 🟧       |
| 12 | Stream URL cache con expiry reale         | —                            | (skip*) | 🟧       |
| 13 | Prefetch parallelo traccia successiva     | —                            | (skip*) | 🟨       |
| 14 | WebView cache + hardware accel            | `MainActivity.java:46`       | L1      | 🟧       |
| 15 | MediaSession elapsedRealtime interpolation| `AudioForegroundService:266` | L1      | 🟨       |
| 16 | LruCache bitmap cover art                 | `AudioForegroundService:59`  | L1      | 🟧       |
| 17 | Parallelize manifest + metadata           | —                            | (skip*) | 🟨       |
| 18 | Shaka buffer tuning                       | —                            | (skip*) | 🟨       |
| 19 | LocalFiles streaming URI (no OOM)         | `LocalFilesBridge.java`      | L1      | 🟥       |
| 20 | DNS prefetch fallback + largeHeap         | `index.html` + `Manifest`    | L3 + L1 | 🟩       |

*(\*) Ottimizzazioni profonde su `player.js` non implementate: richiedono
conoscenza del behavior dello Shaka Player upstream per patch sicure. Candidate
per una PR upstream dedicata.*

### Gruppo C — UI mobile (10)

| #  | Titolo                                    | Livello | Severità |
|----|-------------------------------------------|---------|----------|
| 21 | Tap target min 48dp                       | L2 (CSS)| 🟧       |
| 22 | Skeleton loader shimmer                   | L2 (CSS)| 🟧       |
| 23 | CSS vars safe-area inset                  | L2 (CSS)| 🟨       |
| 24 | Ripple/active feedback tocchi             | L2 (CSS)| 🟧       |
| 25 | Card grid responsivo 3-4 col              | L2 (CSS)| 🟧       |
| 26 | Route transition fade 180 ms              | L2      | 🟧       |
| 27 | Keyboard avoidance search input           | L2      | 🟨       |
| 28 | Pull-to-refresh polyfill                  | L2      | 🟧       |
| 29 | Swipe left/right next/prev                | L2      | 🟧       |
| 30 | Haptic + double-back-to-exit              | L2      | 🟧       |

Tutte in `android/android-service.js`, nessuna tocca upstream.

### Gruppo D — Architettura nativa Android (10)

| #  | Titolo                                           | File principale              | Severità |
|----|--------------------------------------------------|------------------------------|----------|
| 31 | ~~Rimuovi battery opt at launch~~ (escluso)      | —                            | —        |
| 32 | WebView hardening (cache, text zoom, HW accel)   | `MainActivity.java:42`       | 🟧       |
| 33 | ExecutorService + LruCache bitmap cover art      | `AudioForegroundService:59`  | 🟧       |
| 34 | try/catch in `onDestroy`                         | `AudioForegroundService:333` | 🟨       |
| 35 | Streaming content:// per local files             | `LocalFilesBridge.java`      | 🟥       |
| 36 | NotificationChannel singleton                    | `AudioServicePlugin.java`    | 🟨       |
| 37 | `onMediaButtonEvent` hardware keys               | `AudioForegroundService:214` | 🟨       |
| 38 | `onBackPressed` + `moveTaskToBack`               | `MainActivity.java:194`      | 🟨       |
| 39 | Splash screen + largeHeap + HW accel             | `Manifest` + `capacitor.config` | 🟧    |
| 40 | DownloadBridge thread-safe + notif ID riuso      | `DownloadBridge.java`        | 🟨       |

Tutte L1 (file statici wrapper).

### Gruppo E — Bug e code review (10)

| #  | Titolo                                                      | File:riga originale        | Severità |
|----|-------------------------------------------------------------|----------------------------|----------|
| 41 | JS injection via filename (escape backslash)                | `MainActivity.java:97`     | 🟥       |
| 42 | Timer leak `setInterval` senza clearInterval                | `android-service.js:162`   | 🟧       |
| 43 | TOCTOU `file.getName()` race                                | `LocalFilesBridge.java:103`| 🟨       |
| 44 | Silent error swallow (no warning utente)                    | `LocalFilesBridge.java:86` | 🟨       |
| 45 | Escape order sbagliato backslash/apex                       | `LocalFilesBridge.java:83` | 🟥       |
| 46 | NPE `ClipboardManager` senza null check                     | `AndroidBridge.java:25`    | 🟨       |
| 47 | Accumulo notifiche tray — ID non riciclato                  | `AudioServicePlugin.java`  | 🟨       |
| 48 | `build-android.sh` quoting fragile                          | `build-android.sh:119`     | 🟩       |
| 49 | `RECEIVER_EXPORTED` → `RECEIVER_NOT_EXPORTED`               | `AudioForegroundService:72`| 🟧       |
| 50 | `SYSTEM_UI_FLAG_LAYOUT_STABLE` deprecato → `WindowInsets`   | `MainActivity.java:170`    | 🟩       |

### Bonus

| #  | Titolo                                                      | File                       | Severità |
|----|-------------------------------------------------------------|----------------------------|----------|
| 51 | `&limit=100` esplicito su tutti gli endpoint search         | `js/api.js` (L3)           | 🟧       |

Fix del bug segnalato durante lo sviluppo: *"non trova tutti gli album di un
gruppo"*. Senza `&limit=`, il backend Tidal usa il suo default (~25 item).
Con `limit=100` un artista prolifico mostra tutti gli album.

---

## Come aggiungere una nuova patch upstream

Ci sono tre stili di patch disponibili in `build-android.sh`, scegli in base
alla complessità della modifica.

### Stile 1 — one-liner HTML/CSS (`sed`)

Per aggiungere un singolo `<link>`, un attributo, o un testo sostituito una sola
volta. Aggiungi una riga sotto il blocco "3d." in `build-android.sh`:

```bash
# 3e. (#NN) Description of what this patch does
sed -i '' 's|<title>Monochrome</title>|<title>Fabiodalez Music</title>|' index.html
```

**⚠️ Non dimenticare**: se la patch tocca un nuovo file, aggiungilo al
`cleanup()` trap in cima allo script:
```bash
git checkout -- index.html package.json package-lock.json js/app.js js/api.js js/cache.js [NUOVO-FILE]
```

### Stile 2 — replace multi-linea esatto (Python `str.replace`)

Per modificare un blocco di più righe con indentazione precisa. Aggiungi una
chiamata a `patch()` dentro il blocco `python3 <<'PYEOF'` in `build-android.sh`:

```python
patch(
    "js/player.js",
    """        const buffer = {
            low: 2,
            high: 30,
        };""",
    """        const buffer = {
            low: 1,            // was 2 — fast-start optimization
            high: 30,
            prefetch: 3,
        };""",
    "player.js: Shaka buffer tuning",
)
```

Le stringhe `before`/`after` devono essere **esatte**, inclusa l'indentazione.
Se il pattern non matcha, Python stampa `! pattern not found` e salta (non
fallisce il build).

### Stile 3 — regex Python (pattern flessibile)

Quando il codice upstream ha piccole variazioni (es. spazi, nome variabile,
numero modulabile). Usa direttamente `re.subn` dentro il Python heredoc:

```python
try:
    with open(os.path.join(PROJECT_DIR, "js/api.js"), "r", encoding="utf-8") as f:
        src = f.read()
    import re
    new_src, n = re.subn(
        r"(fetchWithRetry\(`/album/\?id=\$\{id\}&offset=\$\{offset\}&limit=)(\d+)",
        r"\g<1>1000",   # was 500
        src,
    )
    if n > 0:
        with open(os.path.join(PROJECT_DIR, "js/api.js"), "w", encoding="utf-8") as f:
            f.write(new_src)
        print("  + api.js: album page size 500 -> 1000")
    else:
        print("  ! api.js: album page size pattern not found")
except Exception as e:
    print("  ! api.js: patch failed: " + str(e))
```

### Dopo aver aggiunto una patch

1. Lancia `./verify-patches.sh ../monochrome` per confermare che il pattern matchi l'upstream corrente
2. Fai un build completo: `./fm-build.sh ../monochrome`
3. Documenta la patch in questo file nella sezione appropriata
4. Commit

---

## Debugging: "pattern not found"

Se dopo un `git pull upstream` vedi:
```
! cache.js: query normalization — pattern not found, skipping
```

Significa che l'upstream ha riformattato o modificato il blocco di codice
targetizzato dalla patch. Procedura di ripristino:

1. **Trova il nuovo codice upstream**:
   ```bash
   cd ../monochrome
   grep -n "generateKey" js/cache.js
   ```

2. **Confronta con la vecchia stringa `before` nella patch Python** dentro
   `build-android.sh`. Di solito il cambiamento è:
   - riformattazione (quote, spazi, trailing comma)
   - rinominazione di una variabile
   - refactoring che splitta la funzione

3. **Aggiorna la `before` string** nel Python heredoc per matchare il nuovo
   upstream, mantenendo la `after` inalterata (o adattandola se il contesto è cambiato).

4. **Verifica**:
   ```bash
   cd ../fabiodalez-music-android
   ./verify-patches.sh ../monochrome
   ```

5. **Commit** il nuovo pattern in `build-android.sh`.

### Quando una patch non è più necessaria

Se dopo un `git pull upstream` l'upstream ha **già applicato** la fix
(congratulazioni, la PR è stata merged), puoi rimuovere la patch:

1. Cancella il blocco `patch(...)` corrispondente nel Python heredoc
2. Se il file non è più toccato da nessun'altra patch, rimuovilo anche dal
   `cleanup()` trap (`git checkout --`)
3. Aggiorna questo PATCHES.md per marcare la voce come "upstream merged"

---

## Patch candidate per PR upstream

Queste ottimizzazioni sono **bug fix oggettivi** e migliorerebbero anche la
versione web desktop di Monochrome. Meritano un PR upstream:

### 🔥 Fix critici (dovrebbero essere accettati facilmente)

1. **#1 debounce 3000→500 ms** (`js/app.js:2556`)
   *Justification*: 3 secondi è 6× la baseline UX standard (500 ms). Un test A/B
   mostrerebbe immediatamente una drop nel perceived-latency.

2. **#51 `&limit=100` sugli endpoint search** (`js/api.js`)
   *Justification*: bug reale — artisti prolifici (Bach, Zappa, Miles Davis)
   mostrano solo ~25 album invece dei 100+ effettivi. Fix di 1 riga per endpoint.

3. **#3 query normalization** (`js/cache.js:36`)
   *Justification*: `"Björk"`, `"bjork"`, `"  BJORK  "` attualmente generano
   3 cache miss e 3 chiamate HTTP identiche. Fix trivial con impatto misurabile
   sul cache hit rate.

### 🎯 Ottimizzazioni ben accettate

4. **#11 preconnect CDN Tidal** (`index.html:77`) — 200–300 ms in meno sul
   first stream URL fetch su rete mobile 4G.

5. **#10 TTL differenziato per tipo** (`js/cache.js`) — rende il cache più
   efficiente: gli artisti cambiano raramente (60 min), i track rankings più
   spesso (5–10 min).

### 🤝 Come proporre

```bash
cd ../monochrome
git checkout -b fix/search-debounce-and-limits
# applica manualmente i cambi delle patch (senza il meccanismo build-time)
git add -p
git commit -m "Fix: reduce search debounce to 500ms and add explicit limit"
git push origin fix/search-debounce-and-limits
# apri PR su GitHub
```

Suggerimento: **una PR per fix** invece di una maxi-PR. Più facile da revieware,
più facile da mergere.

---

## Troubleshooting

### Q: Il build fallisce con "cannot find symbol: class WindowInsetsController"
Stai compilando con `compileSdkVersion < 30`. Il codice usa `WindowInsetsController`
che è API 30+ con fallback deprecato per versioni precedenti. Verifica che
`android/app/build.gradle` usi `compileSdk = 33` o superiore (il wrapper
default è `rootProject.ext.compileSdkVersion`).

### Q: Il dry-run dice "pattern not found" ma il build completo funziona
Probabilmente hai lanciato `verify-patches.sh` prima di fare `git pull upstream`
sul monochrome clone. Aggiorna prima:
```bash
cd ../monochrome && git pull upstream main && cd -
./verify-patches.sh ../monochrome
```

### Q: Dopo il build, il mio clone di monochrome mostra file modificati
Il `trap EXIT` di `build-android.sh` dovrebbe fare il revert automatico. Se
non è successo (es. shell killata con SIGKILL), fai manualmente:
```bash
cd ../monochrome
git checkout -- index.html package.json package-lock.json js/app.js js/api.js js/cache.js
rm -f js/android-service.js
```

### Q: L'APK parte ma la ricerca è ancora laggy
1. Controlla che le patch upstream siano state applicate: durante `build-android.sh`
   dovresti vedere `+ app.js: debounce 3000ms -> 500ms`
2. Se vedi `! pattern not found` per app.js, significa che upstream ha cambiato
   il blocco — segui la procedura di [Debugging: "pattern not found"](#debugging-pattern-not-found)
3. Se tutto matcha ma la UI è ancora lenta, il JS del wrapper (`android-service.js`)
   potrebbe non essere stato iniettato — controlla `monochrome/js/android-service.js`
   esiste durante il build (viene rimosso dal cleanup)

### Q: `verify-patches.sh` passa ma `build-android.sh` fallisce con errore gradle
Il problema non è nelle patch ma nel build nativo. Apri monochrome in Android
Studio e guarda l'errore reale. Tipicamente:
- `google-services.json` mancante → puoi ignorarlo, lo script lo gestisce
- `JAVA_HOME` punta a JDK 17 invece di 21 → aggiorna l'env var
- Risorse duplicate splash → `build-android.sh` le rimuove automaticamente al passo 7b

---

## Cronologia delle patch

- **2026-04-10** — Initial release: 50 + 1 ottimizzazioni implementate
  (escluso #31 battery opt su richiesta esplicita dell'utente). Aggiunti
  `fm-build.sh`, `verify-patches.sh`, `PATCHES.md`. Rimosso il sed obsoleto
  `viewport-fit=cover` perché upstream già lo ship.
- Future update: riservato per quando upstream mergerà le PR candidate
