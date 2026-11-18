# INFINITY ONE

A modern, high-performance, and feature-rich media streaming player designed for **Android TV, Google TV, Fire TV, Tablets, and Smartphones**.

[![Android CI](https://github.com/asheeshsahu7300/infinity-one/actions/workflows/ci.yml/badge.svg)](https://github.com/asheeshsahu7300/infinity-one/actions/workflows/ci.yml)

---

## ✨ Features

- **Universal Device Support**: Fully responsive across Android TV remotes (D-Pad) and touchscreen smartphones and tablets.
- **Live TV, Movies & Series**: High-performance streaming support for Xtream Codes API, Stalker portals, and M3U / M3U8 playlists.
- **Interactive TV Guide (EPG)**: Grid and timeline electronic program guide with channel logos, catchup support, and quick search.
- **Multi-View Mode**: Watch multiple live streams simultaneously in customizable grid layouts.
- **Live Recording & Catchup**: Record live broadcasts directly to local or external storage in the background.
- **Audio & Subtitles**: Hardware-accelerated multi-track audio selection, external subtitle search, and subtitle customization.
- **Multi-Profile & Cloud Sync**: Individual watch histories, favorites, and parental PIN control per profile, with optional Google Drive backup.
- **Modern Dark UI**: Clean, high-contrast monochrome design optimized for OLED displays and long viewing sessions.

---

## 🚀 Getting Started

1. **Install**: Download and install **INFINITY ONE** on your TV, tablet, or phone.
2. **Add Playlist**: Connect your own playlist via:
   - Xtream Codes Login (Server URL, Username, Password)
   - M3U / M3U8 Playlist URL
   - Stalker Portal MAC Address
3. **Enjoy Streaming**: Navigate effortlessly using your TV remote control or touch screen.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 34+)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/asheeshsahu7300/infinity-one.git
cd infinity-one

# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build production App Bundle (AAB)
./gradlew bundleRelease
```

---

## ⚖️ Disclaimer

**INFINITY ONE** is strictly a media player application. 
- It does **not** provide, host, include, or distribute any media content, channels, or playlists.
- Users must provide their own legally obtained content or playlists.
- We do not endorse or support the streaming of copyrighted material without permission of the copyright holder.
