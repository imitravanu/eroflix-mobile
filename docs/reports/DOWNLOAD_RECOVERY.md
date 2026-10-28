# Download recovery validation

P06 is not device-verified. All evidence paths below are ignored local artifacts.

- `artifacts/p06/first-compile.log`: initial compile failed on pinned SDK/API mismatches; fixed without dependency upgrades.
- `artifacts/p06/compile-tests.log`: SUCCESS (2m35s), debug Kotlin, verifyUnitTests, core:data instrumentation assembly. Four DownloadPolicy regressions cover exact-source/typed stream dedup, overflow-safe >4 GiB reservations, actual Wi-Fi versus unmetered policy, and strong validator admission.
- `artifacts/p06/full-host-gate.log`: compile caught ConcurrentHashMap contains ambiguity; changed to explicit containsKey.
- `artifacts/p06/full-host-gate-v2.log`: FAILED lint because library manifest lacked ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED despite app declarations. Added actual library permission requirements; no suppression/baseline.
- Final frozen gate: `./tools/dev/with-toolchain.sh ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest :app:assembleRelease lint verifyUnitTests -PsourceLab=true -PcatalogDir=local-data/catalog-full-r4 --write-locks`, log `artifacts/p06/full-host-gate-v3.log`. PASS (2m9s, 710 tasks). Final intent-guard targeted rebuild follows this full passing gate; hashes to be appended.

Prepared, not run: `ProductionDownloadsDeviceTest#selectedQueueDedupPauseReopenCompleteAndNoUpstreamPlayback` and `DataIntegrationTest#durableDownloadIntentPreservesExactSourceTypedTracksAndPersonalStateAfterReopen`. Discovery's exact-source control test now asserts displayed clickable actions without invoking real-provider resolution. Ordinary discovery tests must never initiate provider playback/downloads.

Runtime recovery, cache-only decode, concurrent transfer and OS scheduling claims remain pending actual evidence. No Bluetooth work or network-setting changes were performed for this phase.

Host XML aggregate: {'tests': 75, 'failures': 0, 'errors': 0, 'skipped': 0}.

Final intent guard gate (`artifacts/p06/final-intent-guard.log`) PASS4m32s/568 tasks. Refresh persistence admission is checked inside the same mutex as user intent writes, so a late partial-removal/resolution cannot overwrite pause. A final database-isolation rebuild follows: `artifacts/p06/isolated-index-final.log`, same targeted command, explicit Context database open/delete redirection to the owner directory.

Final isolated-index gate PASS1m13s/568 tasks: debug/release APKs, app instrumentation, media lint, verifyUnitTests. Core library declares actual recovery/network permissions, and both SQLite open overloads plus deletion target the owner directory. Stable debug app SHA256: `71eb3b3e0780a9218d5ca2e81eef640f9c9ed7d81b2c2dd0b141cff7b958d811`; app-test: `fc45f6048936190f1a1db2ed4616163310d60702a281e444b64fc099442614dd`; core:data-test: `db8e4a6e1fd256eb7e7da5ed82fc8c73ee316862fe9f7ac2af6320daeb02ec64`. Debug APK exact-full-catalog inspection PASS; release inspection PASS (full catalog, debug harness exclusion, native ZIP/ELF 16 KiB alignment).

First device invocation did not execute a test: JUnit rejected the expression-body method's inferred Boolean return (cleanup result). Explicit Unit signature fixes this; test-APK-only assembly `artifacts/p06/test-void-fix.log` passed. No runtime download claims from the rejected invocation.

Device v2 exposed preparation catch self-cancellation masking original error; internal cleanup no longer cancels its own caller. Device v3 then identified IllegalStateException. Pinned Media3 bytecode establishes that progressive helpers with a data source have a prepared period, but getTracks/getTrackGroups assert non-progressive mode. Fixed progressive classification using actual URI/MIME content type and excluded adaptive-only calls. These failed attempts prove neither transfer nor cache-only playback. Follow-up debug/test/media-lint gates passed.

Pixel controlled v4 PASS1/1: `artifacts/p06/download-device-v4/`, debug app f126b41ceb9d4a7d20ed689a8c5135418d0cad93e2afe9ec3dea523c103fa72a / test c76b059f73520b5764f71d4815e9786019cf7bceed758572e8e798f5aae58a90. Neutral partial-transfer/user-pause/reopen/resume/dedup/completion/cache-only decoded video+audio/remove passed. Settings/network restored by coordinator, remote access retained. This does not verify HLS, multi-job concurrency or OS interruption matrix.
