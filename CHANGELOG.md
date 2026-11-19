# Changelog - INFINITY ONE

## 1.0.2

- Fixed: after adding a playlist, the next app start no longer downloads the whole list again in the background (T-026).
- Fixed: Stalker portals: playback, episode lists and Live TV no longer hang while the list reloads in the background, and a background reload keeps the running login (T-026).
- Stalker portals: movies and series loaded while browsing a category are now saved and still there after a restart or reload (T-026).
- Stalker portals: the app now reports itself as a MAG270 box instead of MAG250 (T-026).
- Player: video and audio decoders are fed synchronously, which helps 4K HEVC 10-bit playback on some TV chips (T-028).
- Fixed: Stalker series whose portal lists the episodes directly (instead of seasons) now show their episodes (T-029).
- Fixed: switching to another playlist could leave an empty "No channels yet" screen until the add screen was opened and closed (T-030).

## 1.0.1

- Fixed: Android TV orientation issue where portrait toggle button appeared on TV devices.
- Enhanced TV detection using `UI_MODE_TYPE_TELEVISION`, `FEATURE_LEANBACK`, and touchscreen checks.
- Safeguarded player orientation to prevent unwanted portrait rotation on TV and tablet devices.

## 1.0.0

- Rebranded app to **INFINITY ONE** with package `com.infinity.one`.
- Modern neon infinity emblem and sidebar logo integration.
- Google TV-style cinematic poster backgrounds: ambient backdrop wash and featured artwork edge feathering.
- IPTV provider metadata fallback: automatic retrieval of description, rating, runtime, and genres when TMDB is not connected.
- Redesigned Settings UI: category-wise vertical stack with deep black background and subtle dividers.
- Cleaned interface: removed Sports, Donate, and My List for a streamlined viewing experience.
- Fast and modern IPTV streaming interface for Android TV, Google TV, and Fire TV.
- Support for Live TV, Movies, and Series with Xtream, Stalker, and M3U sources.
- Built-in TV Guide (EPG), multi-audio, and subtitle management.
