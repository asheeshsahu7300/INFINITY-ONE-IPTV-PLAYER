# T-028 Synchronous MediaCodec queueing for 4K HEVC 10-bit

Status: implemented in the working tree, debug APK built. Not committed, not tested on a real TV.

## Goal

Maintainer report (19 Nov 2026): 4K HEVC 10-bit movies (e.g. `hvc1.2.4.L150.90`, 3840×2080) show "This device cannot play this video format" in INFINITY ONE, also on a real TV, while the maintainer's previous app plays them on the same TV.

## Findings

- The emulator's HEVC decoders (`c2.goldfish.hevc.decoder`, `c2.android.hevc.decoder`) report `NoSupport` for HEVC Main 10 at 4K. No player setting changes that; the emulator log is not usable for this problem.
- Comparison with the previous app's player setup (notes supplied by the maintainer): hardware-only video renderers with decoder fallback, `setExceedRendererCapabilitiesIfNecessary`, `setExceedVideoConstraintsIfNecessary` (Media3 default), SurfaceView (Media3 `PlayerView` default), time-over-size buffering, `largeHeap` are all already the same in INFINITY ONE. The only difference: the previous app calls `forceDisableMediaCodecAsynchronousQueueing()`.
- The bundled `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` contains audio decoders only; there is no software HEVC decoder in INFINITY ONE.

## Change

`SoftAudio.kt` (`CustomRenderersFactory`): `forceDisableMediaCodecAsynchronousQueueing()`. Media3 queues buffers asynchronously by default on API 31+; with this call all video and audio decoders are fed synchronously, as in the previous app.

Not changed (maintainer chose only point 1): retry without MKV Cues for damaged files, clearer error texts, external-player button.

## Acceptance criteria

1. On the real TV, a 4K HEVC 10-bit movie that failed before (e.g. "Hotel Transylvania: Transformania") starts and plays.
2. 1080p movies, Live TV and zapping work as before (no slower start, no stutter).

## Risks

- Applies to all playback. On devices where asynchronous queueing worked well, synchronous queueing can cost a little throughput; not measured.
- Whether this is the cause on the maintainer's TV is **unverified**; it is the only remaining difference to the previous app.

## Test results

`assembleDebug` successful (19 Nov 2026), APK installed on the `Television_4K_9` emulator. Not tested on a real TV. No unit test (one Media3 builder call, no logic of its own).

## Device test steps (maintainer)

1. Install `app/build/outputs/apk/debug/app-debug.apk` on the real TV (`adb -s <tv-ip>:5555 install -r ...`).
2. Play the 4K movie that failed before. Expected: picture and sound, no "cannot play this video format".
3. Play a 1080p movie, a Live TV channel, zap 5 channels: unchanged.
4. If the 4K movie still fails: send `adb -s <tv-ip>:5555 logcat -d | findstr /C:"NoSupport" /C:"format_supported" /C:"Decoder failed"` and the TV model.
