# T-030 Switching playlists shows "No channels yet"

Status: implemented in the working tree, debug APK built. Not committed, not tested on a device.

## Goal

Maintainer report (19 Nov 2026): after switching to another playlist, the home screen shows "Noch keine Sender" with "Playlist hinzufügen". Opening the add screen and going back loads the data.

## Findings

- The empty screen with that text shows only when no load runs and no error is set (`HomeScreen.kt`, `!loading && all.isEmpty()`, `err == null`). A failed load would show "Die Playlist konnte nicht geladen werden". So the load was stopped, not failed.
- `PlaylistStore.switchTo` empties the list and then loads. It is called from `scope.launch` in the playlist picker (`Header.kt`, two places) and `plScope.launch` in `LiveTab.kt`; both scopes come from `rememberCoroutineScope()`. With the list empty, the screen changes to the loading view, the picker leaves the composition, its scope is cancelled and the running load with it (`load` resets `loading` in `finally`). Likely cause; not reproduced on a device.
- Add + Back works because `MainActivity` reloads from its own `LaunchedEffect` (`AddFlowLogic.shouldReloadAfterAddClosed`), which is not cancelled.

## Change

`PlaylistStore.kt`: new `switchScope` (own `SupervisorJob`, main thread); `switchTo` starts the load there and waits for it (`launch { load(...) }.join()`), with the application context. When the caller's scope is cancelled, only the waiting stops, not the load. `PlaylistStore.cancel()` still stops the load (it cancels the load's own job).

Not changed: the call sites, the load itself, the empty screen.

Follow-up the same day (maintainer: "playlist not switching"): `switchTo` returned at once while any load held `loadLock`. With the load no longer cancelled by the picker, a long load (a Stalker portal fetching the first page of every category, about 0.4 s per request, seen on the emulator) blocked every further switch until it finished. Now `switchTo` cancels the running load (`cancel()`), waits up to 15 s (`SWITCH_WAIT_MS`) until the lock is free, then switches. If the lock is still held after that, the switch is skipped as before.

## Acceptance criteria

1. Switching playlists (Live TV picker, header picker) shows the loading view and then the new playlist, never "Noch keine Sender".
2. The cancel button on the loading screen still stops the load.

## Risks

- A switch that is no longer wanted (user leaves the screen) still finishes loading; that matches the intended behaviour (the active playlist changed already).
- A switch now stops any running load: also a background refresh or the first full load of the playlist being left. A stopped Stalker load keeps its old stored copy (the partial download is deleted).
- If a load does not stop within 15 s (an open request that hangs), the switch is skipped.

## Test results

19 Nov 2026: `assembleDebug` successful; `AddFlowLogicTest` 4/4. Not run: device test. No new unit test (coroutine scope behaviour with Android main dispatcher).

## Device test steps (maintainer)

1. Have two playlists. In Live TV, open the playlist picker and switch to the other one. Expected: loading view, then the channels; no "Noch keine Sender".
2. Same via the playlist picker in the header (Movies or Series tab).
3. Switch to a large playlist and press cancel on the loading screen: loading stops.
4. Switch back and forth three times quickly: the last chosen playlist is shown.
