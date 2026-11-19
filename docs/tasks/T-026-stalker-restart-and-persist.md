# T-026 Playback hangs after a restart; Stalker titles loaded on demand are lost

Status: implemented in the working tree, debug APK built. Not committed, not tested on a device.

## Goal

Maintainer report (19 Nov 2026):

1. After adding a playlist everything works. After closing and reopening the app, the list is empty at first and then fills in. Starting a movie, opening episodes or zapping Live TV then hangs on "loading" and the player ends with an error.
2. With a portal address containing `stalker_portal`, movies and series come from one API (type `vod`, told apart by `is_series`). The extra titles loaded while browsing must stay after a restart.

## Findings

- Adding a playlist (`PlaylistStore.load(save = true)`) never called `General.markRefreshed`. `last_refresh` stayed 0, so the next start counted as "refresh due" (default: daily). `MainActivity` showed the cache and started a full network load 15 s later. The raw file had already been deleted after the first load (`commitGz`), so the download started from scratch.
- For a Stalker portal that load means hundreds of `get_ordered_list` requests. `StalkerApi.call` ran the 250 ms pause **and** the HTTP request while holding `callLock`. A play request (`resolve`, create_link) or episode request (`episodeList`) queued behind the reload, and the player hit its time limit.
- `StalkerApi.write` always logged in again (`login(force = true)`), which gets a new token. Many portals then reject the old token, so a playback request that was already running could get "Authorization failed". This is likely but was not seen on a real portal.
- Titles loaded on demand (`StalkerMore`, PR #47) were kept only in memory, and the page position (`stalker_more_<key>.tsv`) was written only by a full load. After a restart or reload every category was back to its first page.

## Change

| File | Change |
|---|---|
| `PlaylistStore.kt` | `loadInner`: after a successful network load (also right after adding), `General.markRefreshed`. `downloadStalker`: passes `freshLogin = rawChannels.isEmpty()`. New `stalkerItemsFile` (`stalker_more_<key>.m3u`), deleted in `deleteFiles`. `show` adds the saved titles in front of the list (`withStalkerItems`): Stalker sources only, each saved portion parsed on its own with the same language filter as the cache, IDs already present skipped. |
| `StalkerApi.kt` | `call`: only the time slot is reserved under the lock; the wait and the request run outside it (same 250 ms spacing). New counter of user requests (`urgent`): `resolve` and `episodeList` count; `pageRange` and `paged` wait while it is above 0 (at most 20 s at a time). `write(freshLogin)`: `false` reuses the running session and logs in again only on "Authorization failed". |
| `StalkerMore.kt` | After each portion: appends the M3U lines to `stalker_more_<key>.m3u` (only if it brought new titles, file limit 32 MiB) and rewrites the page positions in `stalker_more_<key>.tsv`. |
| `StalkerLogic.kt` | Pure helpers `CHUNK_MARK`, `chunkBlock`, `chunksNewestFirst`, `newestChunkFirst`. An incomplete last portion (app stopped while writing) is dropped. |
| `StalkerChunkTest.kt` | New, 8 tests. |

Decision (maintainer approved the recommendation): a full reload **keeps** the saved titles.

### Recheck against a real `stalker_portal` capture (19 Nov 2026)

The maintainer supplied captured traffic of a `stalker_portal` portal. Observed: `type=series&action=get_categories` answers `{"js":false}` (no series area, all series are in `vod` categories); movies carry `is_movie:true`, `is_series:"0"`, `series:[]`, `has_files:1`, `cmd:"/media/<id>.mpg"`; the portal's own client asks `get_ordered_list` without `sortby`; one category has 16672 titles at 14 per page. Checked: movie/series detection (`Emitter.isSeries`, `M3uParser.classify`), trimmed category names on both sides. Three corrections:

- `StalkerApi.pageRange`: when page 1 of a run only answers without `sortby`, the rest of that run is asked without `sortby` as well. Before, the next page came back empty and the category counted as complete; with T-026 that wrong state would have been stored.
- Full reload: the page positions are **kept** (`StalkerLogic.keepPositions`, used in `downloadStalker`) as long as the saved-titles file exists, instead of starting after page 1 again. The portal lists the newest first, so nothing is skipped; pages seen again only bring duplicates. Categories that were complete start after page 1 again.
- `StalkerMore.save`: when the file has reached 32 MiB, the page position is not stored either, so a restart fetches those pages again instead of leaving a gap.

### Requests one at a time again (19 Nov 2026, evening)

On the emulator, opening a series while the playlist load downloaded the channel list (`get_all_channels`, about 12 MB, 16 s) failed: the episode requests sent at the same time got "Authorization failed", the following re-login got HTTP 429. The playlist load went on with the same session without errors, so the session was valid; the portal rejected only the parallel request (likely, not proven). `StalkerApi.call` runs the request under the lock again (one at a time, as before T-026). The lock is now a fair `ReentrantLock`, and list loading still pauses before each page while a play or episode request runs (`yieldToUrgent`), so such a request waits only for the request that is running. Trade-off: behind a long single request (the channel list) a play request now waits until it is done.

### Episode list waited 20 s (fixed 20 Nov 2026)

Maintainer: "why the request hit the api so late". Emulator log: login done at 00:06:40.9, the episode list arrived at 00:07:01.8 with no other request in between. Cause (introduced by T-026): `yieldToUrgent` was also called in `paged()`, which the episode list uses. `episodeList` counts as a user request (`urgent`), so its own pages waited for it until `URGENT_MAX_WAIT_MS` (20 s), before every page and every season. Now `paged(bulk = true)` pauses only for list loading (channel fallback in `write`); `pageRange` is used by list loading only and keeps the pause. `resolve` was not affected (it uses neither).

### Box model MAG270 (maintainer decision, 19 Nov 2026)

On the emulator, adding the portal failed with "Authorization failed" after `get_all_channels`. The working client of that portal sends `X-User-Agent: Model: MAG270`, the app sent MAG250. New constant `StalkerLogic.MODEL = "MAG270"`, used in `X_UA` and as `stb_type` in `profileExtra` (both always match). Not changed: user agent string, `ver`/`image_version`, `Referer` (app: portal address as entered; working client: `…/stalker_portal/c/`), `timezone` cookie (app: `Europe/Berlin`; working client: `GMT`). Whether the model was the cause is **unverified**: the log also showed "Device conflict - device_id mismatch" when serial/device IDs were entered, and another client with the same MAC was active.

Risk: this applies to **all** Stalker playlists. A portal that registered an existing user's box as MAG250 could reject the new model ("device conflict") after the update.

## Not changed

SharedPreferences names and keys, existing file names and formats (`pl_*`, `stalker_more_*.tsv` keeps its format), the backup, `PlayerScreen.kt`, Xtream and M3U loading, the 15 s delay and the refresh schedule in `MainActivity.kt`, UI strings.

## Acceptance criteria

1. Add a playlist, close the app, reopen it: no full reload in the background (Settings > Playlist aktualisieren shows "Zuletzt aktualisiert" at the time of adding).
2. Stalker: playback, episode lists and Live TV start normally, also while a background reload is running.
3. Stalker: titles loaded by scrolling a movie or series category are still there after a restart, and scrolling goes on from where it stopped.
4. Deleting the playlist deletes `stalker_more_<key>.m3u`.

## Risks

- New stored file `stalker_more_<key>.m3u` (additive; old installs start without it). It is not in the backup (the other cache files are not either).
- Titles the provider removes stay in the saved file until the playlist is deleted; playing them fails with the normal Stalker error.
- After a full reload, categories that were already complete are walked again from page 2 when scrolled (pages already known bring no new titles).
- Titles of a hidden language that arrive in a portion are not saved, but the page position moves on; if that language is shown later, those titles come only with the next full reload.
- When a category answers only without `sortby`, its order differs from the first page (sorted by date added); duplicates are dropped by ID.
- Requests no longer run strictly one at a time: a play request can overlap one list request. The spacing of 250 ms between request starts stays.
- A background reload while something plays runs slower (it waits for play and episode requests).
- `General.markRefreshed` now also runs for a manual reload. The next scheduled refresh then counts from that time.

## Test results

Ran (19 Nov 2026): `StalkerChunkTest` 8/8 on the JVM (Kotlin 2.2.10 compiler and JUnit 4.13.2 from the Gradle cache, JDK 21 from Android Studio). `StalkerLogic.kt` and `StalkerLink.kt` compiled with stand-ins for `Item`, `Kind`, `Variant`, `Episode`, `PlaylistLogic.normalizeUrl` and `buildImageUrl`.

Ran later the same day with the project's Gradle wrapper (Android Studio JDK 21, local SDK): `assembleDebug` successful, `app/build/outputs/apk/debug/app-debug.apk`. `testDebugUnitTest`: 387 tests, 10 failed. The same 10 fail on the clean commit without T-026 (379 tests), so they already failed before this task: `CoreLogicTest` (5), `LoadLogicTest.vorauswahlNachGeraetesprache`, `ProviderLogicTest.rating_zehnerSkala`, `Round17Test.menue_verlaufVorhanden`, `Round20Test.picksApk`, `SubSearchLogicTest.subdlHitsDropKeyAndOtherEpisodes`. All T-026 tests and `StalkerCategoryTest` pass.

After the recheck: `assembleDebug` successful; `StalkerChunkTest` 10/10, `StalkerCategoryTest` 13/13, `StalkerLinkTest` 18/18 under Gradle. APK installed on the `Television_4K_9` emulator as `com.infinity.one.test`; the app starts.

Not run: device test with a real portal.

## Device test steps (maintainer)

1. Clear app data, add a Stalker playlist (ideally a `stalker_portal` address). Wait until the list is shown.
2. Open two movie categories and one series category and scroll down until more titles load. Note the last titles.
3. Close the app completely (recent apps > swipe away) and start it again.
4. Within the first minute: start a movie, open a series and an episode, zap three Live TV channels. Expected: all start; no endless "loading".
5. Open the categories from step 2: the titles loaded earlier are there; scrolling loads further titles.
6. Settings > Playlists > reload by hand. During the reload start a channel: it starts (perhaps a little slower).
7. After the reload: the titles from step 2 are still there.
8. Delete the playlist: no crash; adding it again starts with the first pages only.
9. Repeat steps 1, 3 and 4 with an Xtream playlist: after a restart there is no reload in the background.
10. If a step fails: logcat lines with `StalkerHttp` and `StalkerMore` (remove the MAC address and host before sending).
