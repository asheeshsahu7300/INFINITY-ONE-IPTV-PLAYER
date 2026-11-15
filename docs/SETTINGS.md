# Settings overview

All settings are located on the **Settings** tab of the sidebar. They are arranged in a sleek **category-wise vertical stack** with a pure black background:

### 1. System & General
- **General**: Start screen, exit behaviour, autostart, landscape on phones.
- **Language & region**: App language (10 languages), clock format, time zone.
- **About INFINITY ONE**: App version, update checks, sources.
- **Reset all**: Restore all app settings to defaults with confirmation.

### 2. Appearance & Interface
- **View**: Card size and artwork with live preview, sidebar position (left, right, top) and labels, grid, default cinema view.
- **Design and color**: Color theme, fonts (Outfit, Inter, system), display size, animations.
- **Menu**: Order and visibility of sidebar navigation entries.
- **Customize home**: Choose and reorder home rows and widgets.
- **Boomer Mode**: Simple, oversized tile layout.

### 3. Playback & Media
- **Player**: Seek steps, skip intro/credits, video quality, 4K check, broken link failover, frame-rate matching.
- **Audio & subtitles**: Preferred audio/subtitle languages, subtitle styling, persistent track memory.
- **Live TV & guide**: TV guide source, channel lists, reminders, live buffer, recordings storage.
- **Genres & categories**: Automatic genres or provider categories, hide categories.

### 4. Playlists & Content
- **Playlists**: Add, edit, remove playlists, multi-playlist view, language filters, auto-refresh.
- **Import lists**: Import external watchlists from Letterboxd, MyAnimeList, and Trakt.
- **Storage**: Cache management, watch history, resume points, local & Wi-Fi backup.

### 5. Account & Security
- **Profiles**: Per-user watch history, favorites, and parental controls.
- **API keys**: Personal TMDB and OMDb API keys (optional; IPTV provider metadata is used automatically when no key is set).
- **Parental controls**: PIN protection and adult content filtering.
- **Diagnostics**: Network and stream status, system troubleshooting.

## API keys
Step-by-step instructions for the free TMDB and OMDb keys are in the [README](../README.md#your-own-api-keys-optional). Enter them under *Settings > API keys* and press **Save and check**. Without keys the app uses the information of your Xtream provider.

## Typing on a TV
Use the phone import: *Playlists > Enter from phone* shows an address; open it on a phone in the same Wi-Fi and type the login there.

## Several playlists at once
*Playlists > Also show* adds a playlist to the active one. Movies and series appear together: the same movie only once,
the same series as one series whose episodes have several sources. Filter by **Source** in Movies and Series; in Live TV
the **Source** entry at the top of the category list switches between all playlists and a single one.

## Rewind and record live TV
*Live TV & guide > Rewind live TV* keeps the running channel in a buffer on the device (256 MB – 2 GB). Pause without
missing anything, jump back with the rewind keys or "30 s back", and return with "Back to live". The red button in the
player records the current show until its end in the TV guide (from its beginning if it is still in the buffer).
Recordings are listed under *Live TV > Recordings* and in the settings, where you can also choose internal storage or a USB stick.

## Transfer to another device
*Storage > Start transfer over Wi-Fi* shows an address and a code. On the old device download the backup on your phone
(optionally with playlists), on the new device start the transfer as well and upload the file. Optional: Google Drive
(see [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md)). The same page shows the last crash report so you can copy it on the phone.
