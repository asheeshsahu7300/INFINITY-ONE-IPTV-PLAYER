# T-029 Stalker series: episodes listed directly at the season level

Status: implemented in the working tree, debug APK built. Not committed, not tested with the portal.

## Goal

Maintainer report (19 Nov 2026) with a captured answer of a `stalker_portal` portal: `get_ordered_list&movie_id=…&season_id=0&episode_id=0` returns the **episodes** of the series directly: each row has `"is_episode": true`, its own `id`, `season_id`, `series_number`, `series_files: "1"`, no `cmd`. Series of such portals must show their episodes.

## Findings

`StalkerApi.episodesOf` expected seasons at that level. For these rows it took every episode as a season (`seasonNumber` fell back to the row index, so 13 seasons), added nothing (no `cmd`, and the episode ID is only used one level deeper), and then asked for the episodes of "season" = episode ID. Result: empty or broken episode list.

## Change

| File | Change |
|---|---|
| `StalkerLogic.kt` | New pure rules `isEpisodeRow` (`is_episode` true/1), `seasonsById` (distinct season IDs in ascending order become season 1, 2, …), `episodeRowSeason` (season only from the portal's season fields, not from the title). |
| `StalkerApi.kt` (`episodesOf`) | When **all** rows of the season level are episode rows: each becomes an episode with season from `episodeRowSeason`/`seasonsById` and number from `episodeNumber` (`series_number`). Without `cmd`, the episode is stored as `efile` with movie, season and episode ID, so its file is looked up at play time (existing path `StalkerLink.Play` / `fileCmd`). |
| `StalkerEpisodeRowTest.kt` | New, 5 tests with the shape of the captured answer. |

Not changed: answers with seasons (`is_season`), mixed answers.

### Extension after the maintainer's previous app (`getSeriesInfo`, 20 Nov 2026)

Compared with the previous app's episode reader; taken over:

- Season rows that carry their episodes as objects in `series`, `episodes`, `data` or `list` (list or dictionary): read directly, with the episode's own `cmd`; without one, the season's `cmd` plus episode number, or the episode `id` with the season `id` (file lookup). Before, such a season was asked for again with `season_id`.
- Dictionary answer `{info, seasons, episodes: {"1": [...]}}` also from `get_ordered_list` with `movie_id` only (before: only from `get_series_info`). Both use one reader (`readDict`), same play addresses as before.
- Real episode titles from the portal (`title`/`name`) instead of "Episode N" (`StalkerLogic.episodeTitle`: blank, "null" or a bare number fall back to "Episode N"; longer than 150 characters is cut).

Not taken over: the previous app's reading of direct episode rows (it turns each row into a season with all numbers from `series`, i.e. 13 × 13 entries for the captured series); INFINITY ONE keeps the T-029 reading. The previous app always asks `type=series` first; INFINITY ONE keeps its order by area (`vod` first for series filed in the video club, then `series`).

Tests: `StalkerEpisodeRowTest` now 7 (title rules added). Not run: device test.

## Acceptance criteria

1. A series of that portal shows its episodes in the right order (season 1, episodes 1–13 for the captured series).
2. An episode starts (file looked up via `movie_id` + `season_id` + `episode_id`, then `create_link`).
3. Series of other portals (seasons first) are unchanged.

## Risks

- Season numbers come from the order of the season IDs when the rows carry no season number; a portal whose IDs are not in season order would show seasons in the wrong order. Not seen yet.
- Playing these episodes relies on the file lookup returning the episode's file; **unverified** for this portal.
- Episode titles now come from the portal; some portals send poor titles (e.g. the file name). Visible change for existing users.
- A season row with a `data`/`list` array of objects that are not episodes would be misread; not seen.

## Test results

19 Nov 2026: `assembleDebug` successful; `StalkerEpisodeRowTest` 5/5, `StalkerCategoryTest` 13/13, `StalkerChunkTest` 11/11, `StalkerLinkTest` 18/18, `StalkerLogicTest` 4/4. Not run: device test with the portal.

## Device test steps (maintainer)

1. Open the series from the capture (category with id 77) in the test app.
2. Expected: one season with episodes 1–13, titles in order.
3. Start episode 1 and episode 13: both play.
4. Open a series of another Stalker portal: unchanged.
5. If episodes are missing: the detail page shows a short masked trace under the series (`StalkerApi.lastSeriesDetail`); send it.
