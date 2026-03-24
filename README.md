# Fabiodalez Music — Android App

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/fabiodalez)

Android wrapper for [Monochrome](https://github.com/monochrome-music/monochrome), a privacy-respecting music streaming application.

## Features

- **Background playback** — Foreground Service keeps audio playing when the screen is off
- **Media controls** — Play/pause/skip in the notification shade, lock screen, and Bluetooth
- **Battery optimization bypass** — Requests exclusion from Android's battery killer on first launch
- **Downloads** — Saves tracks to `Downloads/FabiodalezMusic/` with Android notification
- **Local files** — Select Music Folder works on Android (native folder picker)
- **OAuth** — Last.fm/Libre.fm authentication via Chrome Custom Tab
- **Clipboard** — Copy to clipboard works natively
- **Immersive mode** — Navigation bar hidden, status bar visible with safe area padding
- **Back navigation** — Back button in header for album/artist/playlist navigation
- **Branding** — "Fabiodalez Music" name, custom splash screen

## Requirements

- macOS (with Homebrew)
- JDK 21 (`brew install openjdk@21`)
- Android command-line tools (`brew install --cask android-commandlinetools`)

## Quick Start

```bash
# 1. Clone Monochrome
git clone https://github.com/monochrome-music/monochrome.git
cd monochrome
git remote rename origin upstream

# 2. Clone this overlay
cd ..
git clone https://github.com/fabiodalez-dev/Monochrome-Android-APK

# 3. Install overlay into Monochrome
cd Monochrome-Android-APK
chmod +x install.sh
./install.sh ../monochrome

# 4. Build APK
cd ../monochrome
./build-android.sh
```

The APK will be at `Monochrome-debug.apk`.

## Updating

When Monochrome releases updates:

```bash
cd monochrome
./build-android.sh
```

The script automatically pulls the latest from upstream, applies patches, builds, and restores all files. **No manual work needed.**

## How It Works

The build script temporarily patches these upstream files during build:
- `index.html` — adds viewport-fit, script tag, brand name
- `package.json` — adds Capacitor dependencies

All patches are **reverted after build**. The upstream repo stays clean.

The Android-specific code lives entirely in:
- `android/` — Native Java code (foreground service, download bridge, etc.)
- `android/android-service.js` — JS bridge (media controls, downloads, CSS, back button)
- `capacitor.config.ts` — Capacitor configuration
- `build-android.sh` — Build automation

## Architecture

```
Monochrome (upstream web app)
    │
    ├── Capacitor WebView (wraps the web app)
    │
    ├── android-service.js (injected at build time)
    │   ├── Download handler (monkey-patches <a download>)
    │   ├── Media controls (MutationObserver on document.title)
    │   ├── CSS injection (safe areas, layout fixes)
    │   ├── Back button (history.pushState hook)
    │   ├── Clipboard override (AndroidBridge)
    │   └── OAuth override (window.open → Chrome Custom Tab)
    │
    └── Native Java
        ├── AudioForegroundService (MediaSession + notification)
        ├── AudioServicePlugin (Capacitor bridge)
        ├── DownloadBridge (MediaStore file saving)
        ├── LocalFilesBridge (Android folder picker)
        └── AndroidBridge (clipboard, browser)
```

## License

Same as [Monochrome](https://github.com/monochrome-music/monochrome/blob/main/license).
