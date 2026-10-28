> Historical checkpoint. For current runtime results, transfer status and retained
> phone-access policy, read [source capabilities](../reports/SOURCE_CAPABILITIES.md)
> and [runtime handoff](P03-runtime-closeout.md). Earlier APK outcomes and shutdown
> statements below apply only to their dated batches.

# P03 — source feasibility handoff

## Identity

- Date: 2026-10-27
- Owner: Codex / Sol
- Checkout: existing `/home/mitravanu/Projects/eroflix/mobile`; uncommitted work preserved.
- Tasks: P03.01 inventory and host observations; P03.02 resolver; P03.03/P03.04
  compiled controlled harness; P03.05 report with physical evidence pending.
- State: IMPLEMENTED; controlled Pixel acceptance passes, provider coverage keeps Gate A open.
- Environments tested: Linux host with project-local JDK 17 / API 37 toolchain;
  physical Pixel 6 / API 37 over the user-authorized hotspot connection.

## Delivered behavior

| Task / requirement | Result | Implementation paths |
| --- | --- | --- |
| P03.01 / R08, R09 | Frozen 45-case sample, separate title/source counts and bounded HTTP observations | tools/sources/ |
| P03.02 / R08, R09 | Purpose-specific native/web/unavailable resolution; exact-host redirect/header policy, expiry/validators/cancellation | core/network/, core/model/Media.kt |
| P03.03 / R14–R17 feasibility | Optional compiled native/HLS, selected download/cache-only and sample-copy lab | app/src/debug/, app/src/androidTest/SourceFeasibilityTest.kt, core/media/ScopedMediaDataSource.kt |
| P03.04 / R22 | Contained WebView wrapper, cleanup and neutral abuse fixtures | core/network/ContainedWebPlayer.kt, tools/sources/generate_fixtures.py |
| P03.05 | Dated provider table with all Android capabilities explicitly untested | reports/SOURCE_CAPABILITIES.md |

## Shared interfaces and integration

`Resolution.NativeMedia` gains a defaulted optional ResourceValidators field;
existing constructors remain compatible. API_V1.md and MEDIA.md record P05/P06
consumers and strict strong-ETag resume validation. No persisted schema changes.
Media3 HLS and OkHttp data source use the existing pinned 1.11.1 family; module
dependency locks are updated. OkHttp remains 5.5.1. The optional debug activity is
nonexported; release has no lab activity/assets or frozen real-source sample.

The lab is separate from the production player/download controllers, which remain
pending their phases. P05/P06 must replace the lab's isolated engine/cache/index
with their production singletons. Public native capability flags represent
validated transport candidates, not proof of Android decoding/downloads/export.

## Verification evidence

| Check | Result | Ignored evidence |
| --- | --- | --- |
| Fixed input inventory and frozen-denominator checks | PASS, 2 Python tests | artifacts/p03/inventory-tests.log |
| Real source HTTP probe | 45 attempted, mixed observations; no playback claim | local-data/source-samples/p03-v1/host-probe/ |
| JVM aggregate | PASS, 37 tests including 17 resolver tests, zero failures/errors/skips | artifacts/p03/unit-diagnostic.log, jvm-summary.json |
| Host fixture decode | PASS, H.264/AAC MP4 metadata; two seconds TS, fMP4 and AES-128 HLS decode | artifacts/p03/host-fixture-decode.json |
| Android assembly/lint/JVM integration | PASS; 37 JVM tests, no lint errors (45 warnings) | artifacts/p03/final-host-rerun.log, packaging-summary.json |
| Clean debug/test/unsigned-release assembly | PASS | artifacts/p03/clean-packaging-build.log |
| Both APKs against exact full input; hashes, gzip, ABI, alignment | PASS; 286,207 titles, full-20261027-r4; release excludes lab | artifacts/p03/apk-inspection.json, release/apk-inspection.json |
| Device-helper regressions | PASS, 16 tests | artifacts/p03/dev-helper-tests.log |
| V05/V06, controlled download and Android sample-copy | PASS, 3 final Pixel tests; fMP4 output plays, TS output unsupported | artifacts/p03/device-final/ |
| Real Pixel source matrix | 45 cases recorded; 12 native video/audio successes; no proven sampled web path; full provider downloads pending | artifacts/p03/real-device/ |

The first combined host invocation failed in model test compilation despite the
isolated suite passing. Logs are retained. The later JVM aggregate passed; no test
was disabled or assertion removed. Use final closeout evidence for build status.

### Final host closeout

The passing integration command was:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease lintDebug verifyUnitTests -PsourceLab=true -PsourceSamplesDir=local-data/source-samples/p03-v1 -PcatalogDir=local-data/catalog-full-r4 --write-locks
```

APK inspection subsequently found stale incremental release assets containing a
neutral fixture despite the full-catalog build argument. A clean app rebuild
corrected the output. `tools/dev/inspect-apk.py` now accepts `--variant release`
and `--expected-catalog-dir`, rejecting a fixture release or a packaged manifest
different from the selected input. This check is required in addition to assembly.
No runtime/catalog schema change was needed for this build-artifact correction.

```sh
./gradlew :app:clean :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease -PsourceLab=true -PsourceSamplesDir=local-data/source-samples/p03-v1 -PcatalogDir=local-data/catalog-full-r4
python3 tools/dev/inspect-apk.py --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03
python3 tools/dev/inspect-apk.py --variant release --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03
```

All three final commands passed. Debug APK: 254,290,516 bytes; unsigned release:
242,362,054 bytes. Both contain the exact full generation and pass compressed and
expanded catalog hashes, API/ABI and static 16 KiB alignment checks. Runtime
16 KiB compatibility is supplemental and untested. Release manifest and assets
exclude SourceLabActivity/source-lab; debug deliberately includes the 45-case
sample and neutral fixtures. Lint warnings are primarily lab text localization,
view-constructor/tooling and existing API/style advisories; zero errors/fatals.

## Remaining checks and exact next work

1. On the next user-authorized API 37 phone session, run the controlled gate:

   ```sh
   ANDROID_SERIAL=<explicit authorized target> ./gradlew verifySourceFeasibility -PsourceLab=true -PcatalogDir=local-data/catalog-full-r4
   ```

2. Record V05 actual audio/video, codecs/tracks, selected-track durable pause and
   reopen/resume/cache-only proof, and headers on all resource types. Record TS
   and fMP4 sample-copy success or explicit limits; export is not presumed.
3. Run manual V06 fixtures for popup/intent/permission/audio exit, plus renderer
   recovery/native return. Automated renderer cleanup alone does not prove audio.
4. Optionally include the same frozen real inputs using
   `-PsourceSamplesDir=local-data/source-samples/p03-v1`. Test each family's
   native/web/download outcomes separately and keep failures in denominators.
   Required CDN/cookie/service-worker policies need measured provider-specific work.
5. Keep Gate A/P05–P07 pending until a functioning user path and format proof are
   demonstrated. P04 remains independently READY from verified P02.

The helper changes screen timeout temporarily, restores it, checks network flags,
force-stops the lab and removes its test package. Simulated network interruption
is local to the fixture server. Never change airplane mode, hotspot or mobile data.
At the end of any future phone testing, perform and verify AGENTS.md network-ADB
shutdown separately; disconnecting the host alone is insufficient.

## Resources and artifacts

Raw source handles/probe responses and generated media remain ignored in
local-data/. Build/device logs remain ignored in artifacts/. Host FFmpeg is not an
Android dependency. HTTP unit servers shut down in test teardown; no emulator or
phone access was started. P02 access shutdown remains the last phone change;
its already delivered preview APK has not been replaced by an untested P03 build.

See [source capabilities](../reports/SOURCE_CAPABILITIES.md) for exact source
counts, scope limits and the current feasibility decision.

## Physical follow-up in progress — 2026-10-27

The user enabled USB-to-hotspot ADB and authorized phone testing. Attempt 01
played all four controlled formats but failed download-helper setup. Supplying
DefaultRenderersFactory fixed selected-track preparation. Attempt 02 passed two
tests. Attempt 03 exposed a JUnit return-type error in the new web test; returning
Unit fixed test discovery. Attempt 04 passed all three controlled tests, including
six blocked web actions, actual playing web audio before exit, no remaining own-app
active audio after destruction, renderer termination, four native formats, durable
selected download pause/reopen/resume and cache-only video/audio.

Both initial sample-copy failures were caused by the spike rejecting reordered
presentation timestamps. MediaMuxer documents B-frame support; the revised spike
keeps extractor order and measures reordered timestamps instead of sorting them,
then requires output playback. Final phone evidence follows after the real sample.
[MediaMuxer reference](https://developer.android.com/reference/android/media/MediaMuxer).

The real-provider experiment records all 45 fixed cases with separate PLAYBACK
and DOWNLOAD resolution, bounded native playback and visible contained-web
candidates. Full provider downloads remain pending and are not silently started.
A passing collection test means 45 outcomes were recorded, not 45 working sources.
Network interruption remains confined to the neutral local server.

## Final physical handoff — 2026-10-27

This section supersedes earlier pending-phone instructions and in-progress notes.
P03 remains IMPLEMENTED because the five large embed families have no proven user
path with the current adapters/measurement. Gate A is open. P04 remains READY;
no P04/P05 implementation was started during this batch.

Final controlled command: `ANDROID_SERIAL=<authorized endpoint> python3
tools/dev/run-source-tests.py`. It passed three tests with all four controlled
formats advancing H264/AAC, one selected video+audio download preserving stop
reason across manager reopen, completion/resume and 2,080 ms cache-only playback,
headers on every resource class, six blocked web actions, visible Return to app,
no residual own-app active audio and renderer termination. Final APK/test SHA256
are recorded in artifacts/p03/device-final/summary.json.

The fMP4 sample-copy spike preserves B-frame extractor order and rejects invalid
AAC timestamps. Its two-track, 568-sample, 161,975-byte output seeks and advances
video/audio to 2,063 ms on Android; host decode is clean. TS output fails Android
playback and host timestamp validation. P07 needs a dedicated tested TS extraction/
timestamp path; there is no universal export or SAF feature here. A playback
deadline now reports promptly rather than leaving a pending result until the outer
test timeout. Attempt 05 is retained as failure evidence; it is not a passing gate.

Real command: `ANDROID_SERIAL=<authorized endpoint> python3
tools/dev/run-source-tests.py --real-sources`. One collection test passed with all
45 unique fixed sources/titles. Native video/audio: direct-HLS 3/5, protected-file
4/5, protected-HLS 5/5; other groups 0 proven. Eighteen web candidates had no
advancing accessible video in the bounded experiment; full real downloads remain
NOT TESTED. Independent DOWNLOAD resolution results are in the dated capability
table. A completed collection is not provider acceptance. The real sample ran
before unrelated final sample-copy changes; its actual installed artifact hashes
are preserved in real-device/installed-artifact.json.

Final host command: `./gradlew lintDebug verifyUnitTests -PsourceLab=true
-PsourceSamplesDir=local-data/source-samples/p03-v1
-PcatalogDir=local-data/catalog-full-r4`; PASS, 37 JVM tests and zero lint errors.
APK/catalog/signature/alignment inspection is repeated for the final installed
debug revision; exact catalog generation remains full-20261027-r4.

At completion, test processes were stopped, own lab input/cache/index/conversion
files removed and the temporary display timeout restored. The user's personal
data was preserved. Wireless debugging is off; manual TCP was returned to USB
mode; listener 5555 is closed; network ADB entry is removed. Hotspot route and
phone network flags are unchanged and internet is reachable. Actual cleanup is
in artifacts/p03/network-access-shutdown.json. Remote access is disabled.

Exact next source work: provider-specific public media/iframe/play-control and
approved CDN policies for TubePornClassic, Eporner, Tizam, Fullerotic and Porntrex,
using the retained failures. Add matching fixtures and rerun the same denominator,
then complete real-provider transfer/offline proof. A new phone session is needed
only for its remaining device checks; host adapter work can continue independently.
