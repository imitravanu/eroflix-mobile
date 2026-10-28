# P05 — Native player and Android 17 lifecycle

**Role:** Playback agent
**Prerequisites:** P02 personal/catalog APIs and P03 proven source strategies
**Owns:** Playback/session parts of `core/media/`, `feature/player/`, player
fixtures; manifest/navigation contributions through the integration owner
**Requirements:** R08–R13

## Goal

Play supported sources natively with correct Android 17 audio/lifecycle behavior,
one-handed controls, actual quality choices and reliable resume state.

## Read first

[Media contract](../contracts/MEDIA.md), [platform](../ANDROID17_PIXEL6.md),
[validation](../VALIDATION.md), and P02/P03 handoffs/capability report.

## Work packages

### P05.01 — Player/session ownership

- Create one MediaSessionService/ExoPlayer owner and a controller-facing UI adapter.
- Start from user-visible actions with the declared `mediaPlayback` service;
  attach/detach the surface independently of the player lifetime.
- Reuse P03 source policies. Enter WebEmbed through the contained source path
  and prevent simultaneous native/web audio.
- Render resolving, buffering, playing, paused, ended and typed failure states;
  bound fallback attempts and provide an explicit retry/source choice.
- Replace P03 harness-only player ownership with the production singleton.

### P05.02 — Native control surface

- Add play/pause, timeline/buffer progress, ±10 seconds, duration, speed,
  subtitle/audio tracks, display fit/crop, rotate and lock controls.
- Build quality choices from available native tracks; Auto plus only actual
  representations. A single progressive source gets an honest Source label.
- Implement mirror selection and fallback with same-title identity/position
  compatibility checks; show any unavoidable restart or unsupported track.
- Retain selected source/track preferences without pinning expired media URLs.
- Expose the in-player Download event with active source/selected-track context
  for P06, without constructing a second resolver/player instance.

### P05.03 — Gestures and gain

- Left-side vertical swipe changes player-window brightness; right-side changes
  system media volume and then the separate software gain stage.
- Center horizontal scrub, left/right double-tap skip, hold-to-preview 2x,
  pinch fit/crop and touch lock share an explicit gesture arbitration policy.
- Restore previous speed and brightness on cancel, exit and lifecycle teardown.
- Implement up to 2.0x amplitude gain with clipping protection through a proven
  audio processor/effect path. Record behavior for offload, PCM, speaker and
  Bluetooth output; do not assume `player.volume = 2` performs amplification.
- Use short controlled low-level audio fixtures to verify processing without
  relying on a perceived loudness claim or long maximum-volume playback.

### P05.04 — Resume, PiP and external subtitles

- Persist progress in milliseconds at documented checkpoints and lifecycle
  events. Treat unknown/live durations separately; seeking is not watch completion.
- Expose playing-interval, speed and discontinuity observations through the
  shared model API for P08's watched-coverage policy. Include a compatible
  timeline identity; last-known position alone cannot measure watched coverage.
- Add PiP controls/aspect ratio, auto-enter behavior where appropriate and
  correct return/close behavior. Keep one player through rotations.
- Default background without PiP pauses; explicit screen-off audio setting
  uses an eligible user-initiated media service under API 37 hardening.
- Load external SRT/VTT via SAF with content URI handling and recoverable parse
  failure. Test embedded and separate HLS subtitle/audio tracks.
- Define the lock-state hook used by P08: lock covers content and pauses video/
  leaves PiP appropriately before authentication can be bypassed.

### P05.05 — Real lifecycle and device verification

- Exercise audio focus loss/transient interruption, call, unplug, Bluetooth
  route change, stop/pause media notification and repeated recreation.
- Test Home → PiP → return/close, rotation, split-screen and background audio
  option on Pixel 6 API 37. Inspect relevant AudioHardening/log errors.
- Record codec choice, hardware acceleration, supported formats and any fallback.
- Run controlled playback startup/dropped-frame checks and player leak cycles.

## Acceptance

- [x] Native controlled and sampled supported real sources advance audio/video.
- [ ] V05, V07–V09 and V13's player-side stop/relaunch behavior pass.
- [ ] Actual quality/subtitle/mirror choices and same-title resume work.
- [x] Brightness/speed restore correctly on cancellation; touch lock can unlock.
- [ ] Gain processing is measured and Android 17 audio lifecycle is correct.
- [ ] No duplicate player/service or leftover WebView audio after navigation.
- [x] P06 has the in-player download context/event and shared network/source policy.

## Evidence and handoff

Create `docs/reports/PLAYER_VALIDATION.md` and `docs/handoffs/P05-player.md`.
Record device/codec/format results, lifecycle scenarios, controls and remaining
limits. Next: **P06.01** once P04's card/details action UI is ready.

## Implementation checkpoint — 2026-10-28

IMPLEMENTED with71 JVM cases and final Pixel controlled3/race3/real-source1
tests passing. Native format output, PCM gain buffers, gestures, content-URI VTT,
Home pause, harness PiP full surface, saved-position reopen and real media-session
pause/play/stop are measured. Broader MainActivity/web/focus/call/Bluetooth/
track/mirror/SAF/long-run acceptance remains; see
[PLAYER_VALIDATION](../reports/PLAYER_VALIDATION.md). Keep unchecked acceptance
items pending rather than infer a complete lifecycle gate.
