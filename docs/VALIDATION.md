# Validation and Pixel 6 acceptance

**Target:** Android 17 / API 37, arm64 Pixel 6.
**Status:** P00–P02 verified. P02 final build/lint,20 JVM,9 exporter,16 helper and14 physical checks pass; unchanged query/facet/details/entity/memory targets pass. Network control was disabled with hotspot/internet preserved. See [P02 report](reports/CATALOG_BUILD.md). P10 release-like acceptance remains pending.

Phase owners implement meaningful tests for data/media/lifecycle behavior and
run the checks appropriate to their changes. UI-only presentation changes need
focused UI review, not tests that merely repeat the implementation.

## Verification levels

1. **Host:** exporter/manifest/query-fixture tests and JVM logic tests.
2. **Android integration:** SQLite driver, Media3 fixtures, storage/lifecycle,
   Compose navigation and resource behavior on API 37.
3. **Physical Pixel 6:** actual codec, source network, audio, gestures, thermal,
   memory and background operation. An emulator cannot establish these results.
4. **Release artifact:** inspect/install the final signed arm64 APK and test an
   in-place upgrade from the previous release/rehearsal build.

## Expected build commands

P01 creates and documents these commands from `eroflix/mobile/`; they are not
available before the Gradle project exists. Test aggregate tasks must use the
real module tasks and fail on any child failure.

```sh
./gradlew --version
./gradlew assembleDebug lintDebug
./gradlew verifyUnitTests
./gradlew verifyAndroidTests
```

- `verifyUnitTests`: all applicable Kotlin/JVM and Android local-unit suites.
- `verifyAndroidTests`: connected Android integration suites on the explicitly
  selected device. Record model, OS, build type and fixture generation.
- P02 exposes host conversion tests as specified in [DATA.md](contracts/DATA.md).
- P10 adds documented Macrobenchmark/profileable-build commands and captures.
- P11 adds `assembleRelease`, APK verification, and signing input documentation.

Do not use parent desktop `npm test` as Android validation. If a task modifies
desktop code, run its relevant existing tests from the parent project too.

## Controlled fixtures

Use small neutral titles and generated or freely usable short media. Fixture
servers have explicit start/stop ownership and controlled endpoints for:

- Progressive MP4 with HTTP Range, validators, partial response and changed bytes.
- Multivariant HLS, separate audio/subtitles, TS and fMP4 segments, init maps,
  permitted AES-128 key requests, discontinuities and a truncated/missing segment.
- A required header on every media request and a redirect between controlled hosts.
- An expiring URL that can refresh, a 200 HTML response, 403/404/429/5xx errors,
  lost connectivity, slow responses and canceled requests.
- An embed page attempting a popup, external intent, permission request, and
  unexpected navigation; renderer exit and return to native UI.

Tests may use a local fixture server through a documented debug-only transport.
Android 17 LAN rules must be accounted for. Real-source and standalone acceptance
runs with development tunnels/servers removed. Synthetic media on the device
can verify offline paths without any server.

## Performance budgets

These are project targets, not measurements or platform guarantees. Measure
release-like/profileable builds with the recorded full catalog generation.

| Metric | Acceptance target / method |
| --- | --- |
| Cold launch after catalog provision | p95 ≤2 s to interactive catalog shell; posters may load separately |
| Warm catalog search | p95 ≤250 ms for first page after debounce completes, on representative query suite |
| Facet update | p95 ≤500 ms across representative combined filters including hidden exclusion |
| Details / entity first page | p95 ≤250 ms with local metadata |
| Main-thread work | No DB, network or large file work on main; no ANRs in acceptance journeys |
| List scroll | <5% janky frames in recorded 60/90 Hz steady-scroll journeys; report actual frame interval and frame-overrun metrics |
| Browse memory | Steady total PSS ≤250 MiB under the bounded poster-cache fixture |
| Player + two downloads | Steady total PSS ≤450 MiB on baseline 1080p fixtures; identify codec buffers separately |
| Repeated navigation | After 20 browse/player/back cycles, stabilized PSS within 10% of warm baseline; no leaked player/WebView/cache |
| Controlled playback start | p95 ≤3 s on stable fixture network; report provider timings independently |
| Local resume | Stored position restored within ±2 s of intended checkpoint |
| Playback reliability | 30-minute supported 1080p session without crash/ANR; post-start dropped-frame ratio <1% on controlled media |
| Combined-use thermal run | 20 minutes playback + two downloads, no app-driven sustained SEVERE/CRITICAL thermal status; report temperature/status and adaptation |

Method: at least 20 measured cold starts where practical; at least 30 timed
queries across common/rare/multilingual/no-result/faceted cases after warm-up.
Report p50, p95, max, sample count, cache conditions, power mode, brightness,
refresh rate and temperature. Separate first-run decompression cost from startup.

A reproducible budget miss remains an open issue until fixed or explicitly
revised with evidence and user-visible impact. Do not substitute a desktop SQL
microbenchmark for these phone timings.

## Functional and failure matrix

| ID | Scenario | Required result | Phase |
| --- | --- | --- | --- |
| V01 | Fresh APK, no catalog provisioned, airplane mode | Bundled catalog activates; correct count, search and details available | P02/P10 |
| V02 | Unicode/quotes/operators/empty/long query and fast typing | Correct bounded results; no SQL/FTS error or stale overwrite | P02/P04 |
| V03 | OR/AND filters, unknown metadata, hide while filtering | Correct rows and distinct facet counts; remove/reset remains reachable | P02/P04/P08 |
| V04 | Performer/studio/series navigation and Back | Correct tag-derived relationships and restored scroll/query context | P04 |
| V05 | Native direct/HLS, expiring/header-sensitive source | Media advances; required requests carry correct policy; bounded failure | P03/P05 |
| V06 | Embed navigation, exit, renderer death | Contained navigation; correct unavailable controls; no residual audio | P03/P08 |
| V07 | Rotation, Home/PiP/return/close, split-screen | One player, correct surface/state, no accidental pause/restart loop | P05/P10 |
| V08 | Call/focus loss, Bluetooth disconnect, volume/gain | Policy-correct pause/duck/stop; no AudioHardening violation | P05/P10 |
| V09 | Scrub/double-tap/pinch/hold cancel/lock and Back | No gesture collision, restored speed/brightness, accessible unlock | P05/P08 |
| V10 | Both download entry points, repeated taps | One correct job/track selection; playback continues | P06 |
| V11 | Pause all / pause one, process death and relaunch | User pause remains paused; eligible work reconciles exactly once | P06 |
| V12 | Airplane mode, Wi-Fi/cellular/VPN change, reboot | Actual Wi-Fi-only policy honored; no falsely completed or duplicate job | P06 |
| V13 | Force-stop and next user launch | Execution stops; intent/index preserved and deliberately reconciled | P06/P10 |
| V14 | Service timeout / notification denial / system stop | Prompt service shutdown; durable state; visible recoverable reason | P06/P07 |
| V15 | Complete HLS/file, cache-only player, catalog removed | Selected content plays without network; retained metadata usable | P06/P09 |
| V16 | Expired URL / changed ETag / altered HLS resource | Safe re-resolution or restart, never mixed-resource byte corruption | P06 |
| V17 | Low disk / unknown size / >4 GiB file | Accurate reservations and 64-bit accounting, resumable failure | P06/P07 |
| V18 | TS/fMP4 export, separate audio, seeking | Valid independent MP4 with aligned tracks and reported format limits | P07 |
| V19 | Export cancel/revoked URI/disconnect/out-of-space | Original download retained; partial destination cleaned/labeled | P07 |
| V20 | Favorite/saved/history/hidden after process and APK update | Persistent state; absent title has retained metadata; bulk unhide correct | P02/P08/P09 |
| V21 | Auto-hide natural playback vs seek to end, then unhide/replay | DATA's >90% unique watched-coverage rule; seek-only remains unwatched; unhide is stable for the cycle | P08 |
| V22 | Fingerprint/PIN cancel/lockout/relaunch/deep link/PiP | Protected content stays covered until authenticated; recovery usable | P08/P10 |
| V23 | Bad/truncated/hash-mismatch/wrong-schema catalog | Previous generation remains queryable; personal data untouched | P09 |
| V24 | Kill between each stage and activation step | Old or fully validated new generation opens after restart | P02/P09 |
| V25 | Catalog removes favorite/hidden/downloaded title | Records and offline playback survive; no cross-file FK loss | P09 |
| V26 | APK update wrong signer/package/version, canceled install | Rejected or canceled correctly; old app and personal state remain | P09/P11 |
| V27 | TalkBack, font 1.0/1.3/2.0, both nav modes, keyboard | Controls labeled/reachable; no clipped actions or lost state | P04/P08/P10 |
| V28 | Native library alignment and supplemental 16 KB runtime | SQLite/media dependencies load and pass smoke tests | P01/P10 |
| V29 | Laptop disconnected, no adb reverse/fixture tunnel | Catalog/search, sampled real playback, download and offline playback work | P10/P11 |

Use logical row/value snapshots and playable-file checks to verify preservation;
SQLite file hashes change under normal writes/WAL, so they are not a substitute
for semantic personal-data comparison.

## Device evidence and cleanup

Capture diagnostics in ignored `artifacts/`; commit concise summaries under
`docs/reports/`. Record commands such as:

```sh
adb -s "$ANDROID_SERIAL" shell dumpsys meminfo tech.imitravanu.eroflix.debug
adb -s "$ANDROID_SERIAL" shell dumpsys gfxinfo tech.imitravanu.eroflix.debug
adb -s "$ANDROID_SERIAL" shell dumpsys thermalservice
adb -s "$ANDROID_SERIAL" shell dumpsys audio
adb -s "$ANDROID_SERIAL" shell dumpsys package tech.imitravanu.eroflix.debug
```

Destructive lifecycle scenarios use the isolated debug/test app and controlled
fixtures. Record the starting state before manipulating network, refresh rate,
service-timeout, audio-hardening or power settings. Test helpers must restore
previous settings and stop only the processes they launched, even on failure.

Do not paste unfiltered logcat, user title/history data, device identifiers,
signed URLs, tokens or file contents into tracked reports.

## Release gate

P10/P11 must link each applicable requirement R01–R26 to tests/evidence. Every
required phase is verified, build/lint/tests pass, Pixel 6 smoke/performance
reports exist, and no data-loss/crash/unusable-flow defect remains open.
Known provider/format limits appear in the UI and release notes with measured
coverage. Any deferred optimization is named with an owner and acceptance task.

P02 hotspot follow-up: current 11-test data suite and two process-restart methods passed by direct AndroidJUnitRunner. Latest facet p95 is 593.58 ms against 500 ms. Gradle wireless execution returned a runner error despite passing test XML; its command is not claimed as passed. See CATALOG_BUILD.md for current evidence.

P02 final follow-up supersedes its earlier pending entries: strict direct instrumentation aggregate passed all14 physical checks, including the memory assertion; required remote-access shutdown is verified.

P03 physical follow-up (2026-10-27): final three controlled tests and one 45-case
real-source collection test passed. V05/V06 controlled scenarios, selected
download recovery/cache-only playback and narrow fMP4 sample-copy playback have
Pixel evidence. TS export fails validation; largest embed-family playback and full
real-provider downloads remain open. P03 is not VERIFIED/Gate A has not passed.
Network access shutdown is verified with hotspot/data/internet preserved. See
[capabilities](reports/SOURCE_CAPABILITIES.md).
