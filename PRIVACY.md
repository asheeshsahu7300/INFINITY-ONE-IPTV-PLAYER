# Privacy Policy for INFINITY ONE

**Last updated:** February 2026

INFINITY ONE ("we", "our", or "the app") is committed to respecting and protecting your privacy. This Privacy Policy explains our practices regarding data collection, storage, and handling when you use the INFINITY ONE application on Android and Android TV devices.

---

### 1. Overview & Core Principles

- **Client-Side Media Player:** INFINITY ONE is strictly a media player application. It does not provide, host, sell, or distribute any video streams, channels, playlists, or media content.
- **No Remote Tracking or Profiling:** We operate no central user accounts, tracking servers, or telemetry collection.
- **Local-First Storage:** All personal configurations, credentials, and playback data are stored locally on your device.

---

### 2. Data Stored on Your Device

All of the following data is saved locally on your device using Android private app storage (`SharedPreferences` and local app directory) and is never transmitted to us:
- **Playlists & Logins:** M3U URLs, Xtream Codes login credentials (host, username, password), and Stalker portal settings (portal URL, MAC address, optional device IDs).
- **Playback & Watch History:** Bookmarks/favorites, resume playback positions, and watch history.
- **App Settings:** UI customization options, audio/subtitle language preferences, display layout, and parental control PINs.

You can completely remove this data at any time via **Settings > Reset All Settings** in the app or via Android's **Settings > Apps > INFINITY ONE > Clear Storage**.

---

### 3. External Network Communications

INFINITY ONE communicates over the network solely to perform user-requested features:

#### A. Your IPTV Provider
- When you stream a channel, movie, or series, or refresh your playlist/EPG, your device establishes a direct connection to your provider's server.
- We have no access to your provider's URL, your credentials, or your playback traffic.

#### B. Public Metadata & Artwork (TMDB & OMDb)
- To display posters, descriptions, ratings, and cast details, the app queries The Movie Database (TMDB) and OMDb API using title searches.
- No personal user information is sent with these requests.

#### C. Subtitles (Subdl & OpenSubtitles)
- When you search for subtitles, the app queries third-party subtitle databases. Only the media title, year, and season/episode numbers are transmitted.

#### D. Live Sports Information (ESPN)
- Public ESPN endpoints are queried to display live scores, standings, and match statistics.

#### E. Connection Speed Test (Cloudflare)
- When you launch the built-in speed test, data is downloaded from Cloudflare's public speed test endpoints (`speed.cloudflare.com`).

#### F. In-App Updates (GitHub)
- The app checks GitHub Releases (`https://api.github.com/repos/asheeshsahu7300/infinity-one/releases/latest`) to inform you when a newer version of the app is available. No user data or device identifiers are sent.

---

### 4. Google Drive Backup & Google User Data Policy

INFINITY ONE includes an optional backup feature that allows you to sync settings and playlists between your own devices using your personal Google Drive account.

- **Limited Scope (`drive.file`):** The app only requests access to files that it has created itself. It **cannot** view, read, modify, or delete any other documents, photos, or files in your Google Drive.
- **Compliance with Google API Services User Data Policy:** INFINITY ONE's use and transfer of information received from Google APIs adhere to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.
- **No Third-Party Sharing or Sale:** We never sell, share, or transfer your Google user data or credentials to third parties.
- **No Advertising or AI Training:** Your Google user data is never used for advertising, marketing, or training machine learning or AI models.
- **Revoking Access:** You can disconnect Google Drive at any time in **Settings > Transfer**, or revoke access via your [Google Account Security Settings](https://myaccount.google.com/permissions).

---

### 5. Local Wi-Fi Network Transfer (Phone Import & Remote)

The app includes an embedded web server (`http://<local-ip>:<port>/`) to allow convenient playlist setup and remote control from a mobile phone or computer:
- This server is accessible **strictly within your local Wi-Fi / LAN network**.
- It does not route through external servers or the public internet.

---

### 6. Children’s Privacy

INFINITY ONE does not knowingly collect any personally identifiable information from children under the age of 13. Furthermore, the app includes a local **Parental Control** feature with a PIN lock to restrict adult content categories.

---

### 7. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Any changes will be posted directly to our GitHub repository:  
https://github.com/asheeshsahu7300/infinity-one/blob/dev/PRIVACY.md

---

### 8. Contact & Open Source

If you have questions or suggestions regarding this Privacy Policy, please open an issue on our GitHub repository:  
https://github.com/asheeshsahu7300/infinity-one
