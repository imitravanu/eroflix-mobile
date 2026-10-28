# Implementation status

**Updated:** 2026-10-29
**Current milestone:** P07 controlled MP4 and HLS export pass on Pixel; P08 library and settings work is active
**Next assignment:** Continue P08 library, settings and privacy. P07 real document-picker and recovery checks remain pending. Advanced download features are deferred to the next version. Work directly; ask the user if an Astra audit would be useful. P04–P06 manual and recovery checks remain pending. See [working instructions](../AGENTS.md).
**Integration owner:** Codex

## State definitions

- `NOT_STARTED`: Work has not begun; inspect prerequisites before claiming it.
- `READY`: Its required inputs exist and it can be claimed.
- `ACTIVE`: One named owner is implementing the phase.
- `IMPLEMENTED`: Deliverables exist; one or more required checks remain pending.
- `VERIFIED`: All required acceptance checks passed with linked evidence.
- `BLOCKED`: A named dependency or failure prevents the next required step.

Only the integration owner edits this table during parallel agent work.
Individual agents report proposed status changes in their own handoff.

## Phase board

| ID | Phase | State | Owner | Prerequisites | Evidence / next action |
| --- | --- | --- | --- | --- | --- |
| P00 | Environment and Pixel 6 baseline | VERIFIED | Codex | None | Host build passed; authorized Pixel 6 on Android 17 / API 37 measured — [handoff](handoffs/P00-environment.md) |
| P01 | Application foundation | VERIFIED | Codex / Sol | P00 toolchain | Host checks and four real Pixel 6 tests passed — [device report](reports/P01_DEVICE_VALIDATION.md) |
| P02 | Catalog and personal data | VERIFIED | Codex / Sol | P01 verified | 20 JVM + 14 physical checks pass; facets p95 415.54 ms, PSS 161.17 MiB; P02 batch access was closed (now retained per current policy) — [handoff](handoffs/P02-catalog-data.md) |
| P03 | Source feasibility | VERIFIED | Codex / Sol | P01 verified | 45-case rerun: 17 native; focused Tube 5/5 native and Eporner 5/5 web/audio. 53 JVM + four current Pixel tests pass; complete 67 MB provider file/cache-only seek passes — [handoff](handoffs/P03-runtime-closeout.md) |
| P04 | Discovery UI | IMPLEMENTED | Sol / Codex review | P02 | 62 JVM + five Pixel UI tests + 1.3/2.0 font checks, isolated data and full catalog performance pass; gesture and three-button/narrow input suites pass; manual TalkBack/actual split checks pending — [handoff](handoffs/P04-discovery-ui.md) |
| P05 | Player | IMPLEMENTED | Sol / Codex review | P02, P03 | 71 JVM; controlled3 + race3 + bounded real1 Pixel tests pass; broader/manual acceptance pending — [report](reports/PLAYER_VALIDATION.md) |
| P06 | Downloads | IMPLEMENTED | Sol / Codex review | P04, P05 | Controlled progressive download and cache-only playback pass on Pixel; broader recovery and HLS checks pending |
| P07 | Standalone export | IMPLEMENTED | Codex | P06 | Controlled MP4, TS HLS and fMP4 export pass on Pixel; real SAF and recovery checks pending |
| P08 | Library and privacy | ACTIVE | Codex | P04, P05, P06 | Library, settings, PIN and fingerprint/recovery code integrated; real fingerprint, privacy/lifecycle and other device acceptance pending; see [handoff](handoffs/P08-library-privacy.md) |
| P09 | Updates | ACTIVE | Codex | P02, P06, P08 | Signed catalog and app metadata verifier fixtures pass (`artifacts/p07/app-update-verifier-final.log`); owner identified public `imitravanu/eroflix-mobile` repository, currently without releases; transport, artifact verification, trust configuration and recovery pending |
| P10 | Device validation | NOT_STARTED | Unassigned | P00, P04–P09 | Run the real Pixel 6 acceptance matrix |
| P11 | Signed release | ACTIVE | Codex | P10 | First release confirmed; signing configuration prepared, owner key and release acceptance pending |

Device-independent implementation may proceed after the specific predecessor
build/contracts it needs have passed. Carry outstanding physical-device checks
explicitly; all predecessor acceptance gates must be VERIFIED before P11.

## Verified during planning

- The planning package includes 12 phase specifications, 60 numbered work
  packages, 26 requirement IDs and 29 validation scenarios. Local document links,
  phase/task references, prerequisite consistency and Git ignore behavior were
  checked; implementation had not started at planning time. See the current phase board above.
- Both original blueprint copies were identical before adding implementation
  notices. The canonical implementation root is `eroflix/mobile/`.
- Official Android documentation identifies Android 17 as API 37 and includes
  Pixel 6 among supported devices. Sources: [platform baseline](ANDROID17_PIXEL6.md).
- A read-only catalog inspection found 286,207 titles and 321,999 source records.
  Full statistics and interpretation: [catalog audit](CATALOG_AUDIT.md).
- `java` is available as OpenJDK `25.0.5-ea`; this is not the pinned build JDK.
- `adb`, `sdkmanager`, and `gradle` were absent from PATH. `ANDROID_HOME` and
  `ANDROID_SDK_ROOT` were unset; the common SDK directories checked were absent.
- The mobile project contained only its original blueprint before planning.

## Unverified inputs and release configuration

| Input | Owner phase | Current state |
| --- | --- | --- |
| Phone's installed Android build/API and USB authorization | P00 | Verified: authorized Pixel 6, Android 17 / API 37 |
| Phone page size and storage | P00 | Measured: 4 KiB pages, approximately 16 GiB free |
| In-app codec and biometric diagnostics | P01 / P03 | Measured on Pixel 6: 26 video codec/MIME entries, 11 hardware; strong biometric available |
| Resolved build/dependency lock | P00 / P01 | Version catalog and module locks created; full host foundation build passed |
| Real provider playback and download coverage | P03 | 45-case rerun records 17 native; later focused Tube 5/5 native and Eporner 5/5 web/audio; one complete real file/cache-only seek proven; remaining source transfers untested |
| Normalized mobile DB sizes and timings | P02 | Full generation verified; facet p95 415.54 ms and PSS 161.17 MiB pass; [report](reports/CATALOG_BUILD.md) |
| APK signing configuration and certificate | P11 | Owner configuration needed |
| Release repository/channel and update public key | P09 | Owner identified `imitravanu/eroflix-mobile`; release assets, channel and trusted public key still needed |

## Task claim format

Add a short entry below when work starts:

`Pxx.yy | owner | date | claimed paths | dependency evidence | next gate`

P00.01/P00.02/P00.04/P00.05 | Codex | 2026-10-27 | tools/dev/, docs/reports/, docs/handoffs/P00-environment.md | Host build evidence in P00 handoff | Delivered; P00.03 subsequently measured on connected Pixel 6.

## P00 implementation update — 2026-10-27

The project-local host toolchain and isolated numeric API 37 APK build passed.
See [TOOLCHAIN](reports/TOOLCHAIN.md) and [device baseline](reports/DEVICE_BASELINE.md).
The planning inventory above describes the pre-setup state. JDK/SDK tools now
exist through `tools/dev/with-toolchain.sh`. Physical phone measurements were
subsequently completed below; the combined application dependency build remains P01 work.

### Connected Pixel 6 update — 2026-10-27

The authorized Pixel 6 runs Android 17 / API 37 and matches `oriole`. Read-only
adb measurements recorded 4 KiB pages, 1080×2400 logical/physical display,
420 dpi, a 60 Hz refresh sample and about 16 GiB free storage. P00 is VERIFIED.
P01 codec/biometric app diagnostics remain pending as explicitly allowed by P00.03.
See [DEVICE_BASELINE](reports/DEVICE_BASELINE.md).

### P00 helper review — completed

P00-review | Codex | 2026-10-27 | tools/dev/, P00 reports/handoff | Saved host and device evidence reviewed | Repaired SDK preflight, diagnostic error/measurement coverage, evidence preservation and local settings; 12 regression checks and setup rerun passed.

See [P00_REVIEW](reports/P00_REVIEW.md). Existing physical measurements remain
valid and unchanged; P00 stays VERIFIED and P01 stays READY.

P01.01–P01.05 | Codex / Sol | 2026-10-27 | mobile Gradle root, app/, core/, foundation tests and handoff | P00 verified | Host build/lint/tests; device tests pending phone reconnection.

### P01 implementation update — 2026-10-27

The Compose debug preview, typed shared APIs and platform boundaries are built.
The canonical host command passes, with 14 unit tests and zero lint errors.
The instrumented test APK is compiled; phone installation, rotation/recreation,
native SQLite runtime and in-app diagnostic measurements remain pending. P01 is
IMPLEMENTED, not VERIFIED. P02/P03 may proceed on their host-side prerequisites.
See [FOUNDATION](reports/FOUNDATION.md) and [P01 handoff](handoffs/P01-foundation.md).

P01 device verification | Codex / Sol | 2026-10-27 | app instrumentation, foundation reports | Authorized connected Pixel 6 / API 37 | Run actual connected tests and record diagnostic evidence.

### P01 device verification complete — 2026-10-27

All four instrumented tests passed on the unlocked API 37 Pixel 6: navigation,
rotation/recreation, secure-window/network policy, SQLite FTS5, and diagnostics.
Espresso is explicitly pinned to 3.7.0 for Android 17 compatibility. Host lint
and 14 unit tests passed again. P01 is now VERIFIED; earlier pending-state
entries above describe the initial host-only implementation. See
[P01_DEVICE_VALIDATION](reports/P01_DEVICE_VALIDATION.md). Preview is installed;
original phone rotation settings are preserved.

P02.01–P02.05 | Codex / Sol | 2026-10-27 | tools/catalog/, core/data/, generated assets, app composition, data tests/reports | P01 host and Pixel 6 verification passed | Consistent export, persistent repositories, local activation, full-data/device acceptance.


### P02 safe stopping point — 2026-10-27

Full catalog and persistent repositories are IMPLEMENTED. Full host assembly,
lint, 19 JVM, seven exporter and 12 helper tests passed after the latest SQL
revision. The phone's latest facet p95 improved from 804.6 ms to 652.98 ms, still
above 500 ms; no acceptance waiver. Query/details/entity p95 passed. The previous
11-test physical data suite passed; rerun it after the final SQL change. The final
two-process personal restart probe is pending. Phone work stopped for the user's
disconnection; original network/display settings were restored and checked.
See [CATALOG_BUILD](reports/CATALOG_BUILD.md) and [P02 handoff](handoffs/P02-catalog-data.md).
P03 stays READY; P04 remains NOT_STARTED until P02 acceptance. P10/P11 gates remain
pending. No new phase was started.

P02 host-only follow-up | Codex / Sol | 2026-10-27 | core/data/CatalogStorage.kt, host profiling/tests/reports | Measured broad-search facet p95 652.98 ms; user requested work without a connected phone | Compare indexed aggregation plans and preserve semantics; physical verification remains pending.

Host-only profiling compared alternate index plans against the measured broad-search case. The first candidate was slower and was rejected; production SQL remains at the last validated host revision. Further profiling stopped while arranging cable-free phone testing to free the laptop charging port.

P02 hotspot verification | Codex / Sol | 2026-10-27 | direct device tests and reports | Current11-test data suite and two-process persistence pass; latest facet p95 593.58 ms still misses500; Gradle wireless runner exit needs diagnosis | Remains IMPLEMENTED; hotspot/network unchanged, display timeout restored, probe/backups/test APKs cleaned.

P02 final optimization | Codex / Sol | 2026-10-27 | compact facet accelerator, data queries/tests and runner diagnostics | Current hotspot tests pass functional gates; measured facet p95 593.58 ms needs improvement | Exact host comparisons, host build, hotspot phone budgets, access shutdown.

P02 verification complete | Codex / Sol | 2026-10-27 | full catalog/storage/test reporting and handoff | Current full build/lint/20 JVM,9 exporter,16 helper and14 physical checks pass | All P02 timing/memory gates pass; user-required network access shutdown verified, hotspot/internet preserved. Earlier pending/miss entries are historical.

P03.01–P03.05 host work | Codex / Sol | 2026-10-27 | tools/sources/, core/network/, isolated debug harness/fixtures/reports | P01/P02 verified | Fixed sample and bounded scoped resolution tests; build harness without device use. Keep physical V05/V06/download/export feasibility pending, network debugging off.

### P03 host implementation — 2026-10-27

Fixed 45 source cases from nine groups before probing; implemented typed scoped
resolution, validator helpers, contained web playback and an optional compiled
Android feasibility lab. Host JVM aggregate passes 37 tests including 17 resolver
cases; two inventory and 16 helper tests pass. Full debug/release assembly and
Android test compilation are host evidence only. Pixel V05/V06, resumable/cache-only
download, sample-copy and all real native/web/download results remain pending.
Gate A has not passed. No phone access was enabled and P02 remains VERIFIED.
See [SOURCE_CAPABILITIES](reports/SOURCE_CAPABILITIES.md) and the
[P03 handoff](handoffs/P03-source-feasibility.md) for exact commands and limits.

P03 physical verification | Codex / Sol | 2026-10-27 | app debug lab, source/device evidence | User explicitly enabled USB-to-hotspot debugging and requested tests | Controlled V05/V06 then real-source matrix; preserve hotspot/data and verify network access shutdown at completion.

### P03 physical batch complete — 2026-10-27

Three final controlled Pixel tests pass: four native formats, durable selected
download pause/reopen/resume/cache-only playback, six blocked web actions, actual
Return to app, no residual own-app audio and renderer termination. fMP4 sample
copy plays on Android; TS output fails validation and is unsupported. All 45 fixed
real sources were recorded in a separate collection test: 12 native video/audio
successes; 18 web candidates yielded no proven advancing accessible video. Full
real-provider downloads remain untested. Largest embed families have no proven
user path, so P03 stays IMPLEMENTED and Gate A remains open.

Host lint and 37 JVM tests pass after the final change. Display timeout was
restored; own lab files/test packages were removed; network ADB listeners and
connection were shut down and verified. Hotspot/data/network flags and internet
were preserved. See SOURCE_CAPABILITIES.md and the P03 handoff.

P03 provider follow-up | Codex / Sol | 2026-10-28 | core/network/, neutral provider fixtures, tools/sources/diagnostics and reports | 45-case Pixel evidence shows largest embed families unproven | Inspect public page/player structure, implement bounded provider parsing with exact policies and tests; phone debugging stays disabled.

### P03 provider follow-up — 2026-10-28

Fixed Tizam's abv→adv redirect and added its three observed media hosts. All five
retained Tizam records now return HTTP 206 MP4 prefixes on the laptop. Fixed the
initial web Referer, added observed Eporner script hosts, separated resource and
navigation hosts, reduced initial media reads to 8 KiB, and improved bounded player
parsing and real-source Play diagnostics. Preview/opaque media are not claimed as
full native titles. Eporner's fixed sample advanced in the browser while muted.

Full debug/test/unsigned-release build, lint and 44 JVM tests pass; four source
helper tests and 17 development-helper tests pass. Both APKs contain the verified
full catalog. This revision was not installed or tested on the phone. The user
confirmed Pixel 6 remains the target; the connected Realme was left untouched.
Pixel network control was not reactivated. Gate A stays open. See the
[P03 provider handoff](handoffs/P03-provider-followup.md).

### Updated access instruction — 2026-10-28

The user connected the Pixel and explicitly requested remote activation and
retention across individual phases. Shut it down only after all project phases
are complete, or if the user asks earlier. This supersedes older per-batch cleanup
instructions; AGENTS.md and current tool documentation record the new rule. Test
process/resource cleanup and temporary-setting restoration still happen per batch.
Preserve hotspot/data/internet and leave unrelated Realme access untouched.

Activation result: Pixel 6 / API 37 network shell verified; hotspot/data flags unchanged. Access remains active until all phases complete or the user requests earlier shutdown. Evidence: ignored artifacts/p03/remote-debug-retained-activation.json.

P03 provider Pixel rerun | Codex / Sol | 2026-10-28 | controlled/source evidence and fixes | Current 44-JVM provider build; authorized Pixel remote session | Controlled initial-header/playback/download/web gate, then same 45 real-source records; retain remote access across phases.

P03 provider rerun attempt 1: native/download/cache-only and narrow fMP4 checks pass; the web test hit an early-page readiness race (fixture control not yet present). The fixture is correct in the APK. The test now waits for the actual fixture URL and controls, then verifies bounded touch coordinates; rerun in progress. Evidence: provider-device-v1/. Remote access remains enabled.

P03 public Tube adapter / Eporner diagnostics | Codex / Sol | 2026-10-28 | compiled source adapter, scoped web request diagnostics/policies and neutral fixtures | Current controlled three-test Pixel gate passes; real run reveals native Tizam progress and exact blocked CDN hosts | Implement public data-only Tube URL decoding and measured Eporner resources; rerun focused frozen families, retain failures and remote connection.

P03 provider Pixel evidence (2026-10-28): current controlled three-test gate passes after a fixture-readiness test correction. The same 45-case run records 17 native video/audio successes (Tizam 3/5, direct file 1/5, direct HLS 3/5, protected file 5/5, protected HLS 5/5); no web video proven. These dated results are preserved in provider-device-v2/ and provider-real-v1/. Later focused adapter experiments remain separate. Remote access retained by current instruction.

P03 runtime follow-up evidence: Tube public metadata/transport decoding plus measured CDN hosts gives 5/5 native video/audio on Pixel (tube-pixel-v3/). Eporner numbered-CDN web path gives 5/5 advancing unmuted video with one own-app active audio output each (eporner-pixel-v2/). Full-file proof exposed a 20-second inherited HTTP call deadline; media and probe timeout policies are being separated with a slow-response regression. Full/preview/unsupported outcomes remain distinct.

P03 runtime closeout | Codex / Sol | 2026-10-28 | source integration and evidence | 53 JVM tests and full builds pass; actual resolver wired into AppContainer | Final full-provider transfer and four-test controlled Pixel gate. Detailed P04 assignment prepared, not started.

P03 final gate — 2026-10-28: VERIFIED. Current same-APK four-test Pixel suite and full 67,226,884-byte provider transfer pass; cache-only video/audio and midpoint seek use zero upstream. 53 JVM, four source-helper and 17 development-helper tests pass; exact full debug/release catalog inspections pass. Largest-family focused playback measurements remain separately dated. Explicit unsupported providers and TS export limits remain. Lab cleanup/settings restoration verified; Pixel remote access retained until all phases per user instruction. P04/P05 READY; detailed P04 Sol prompt prepared, no P04 code claimed. See runtime handoff.

P04.01–P04.05 | Sol implementation / Codex integration review | 2026-10-28 | discovery UI, presentation state, app routing, necessary shared UI/contracts/build integration | P02 verified storage and P03 verified resolver/handoff | Complete discovery flow, meaningful JVM/UI checks, full catalog build inspection and Pixel acceptance; one implementation writer, one Gradle process.

P04 implementation checkpoint — 2026-10-28 | Sol / Codex review | feature/catalog/, app discovery composition/navigation, metadata/count API and NULL-only duration | P02/P03 verified; 62 JVM checks and full debug/test/release/lint host gate pass | IMPLEMENTED; final deep-window UI APK gate and coordinated Pixel discovery tests follow. No P05/P06 engine implementation, no personal schema migration, no dependency upgrades. Pixel remote access retained. See handoffs/P04-discovery-ui.md.

P04 automated Pixel closeout — 2026-10-28: five discovery UI tests pass (including720 visited/600 retained, stable visible identity through Back/recreation), layout repeats pass at font1.3/2.0, isolated metadata/count test and full catalog timing/memory test pass. Query p95 216.81ms; facets414.20ms; PSS197.52MiB in timing test,192.90MiB in deep-window test. 62 JVM and18 helper cases pass; full debug/test/release/lint and exact catalog inspections pass. P04 remains IMPLEMENTED until manual TalkBack, actual narrow/split, both navigation modes and predictive-Back/hold affordance checks complete. No human perception or physical gesture acceptance is inferred from automated state checks. Remote access retained, test packages/processes cleaned, original font/rotation/timeout and network flags restored. Sol implementation completed before its quota interruption; coordinator finished validation.

P05.01–P05.05 | Sol implementation / Codex review | 2026-10-28 | playback/session core/media, feature/player, typed app/manifest integration and tests | P02/P03 verified; P04 implementation/automated gates pass with manual checks retained | Follow detailed P05 assignment; one implementation writer and one Gradle process; coordinator owns phone validation.

P05 coordinator closeout — 2026-10-28: IMPLEMENTED. All four controlled production native formats, actual PCM pipeline, real surface cancellation/subtitles/Home/PiP/singleton, same-source saved-position reopen and actual MediaController controls pass in3 tests; race/stale-route suite3 tests and bounded retained real Tube source1 test pass on same final APK. Real test8.316s/3.164MB, no full transfer. 71 JVM/19 helper cases and exact full debug/release inspections pass. Detailed remaining physical/MainActivity/web/focus/route/long-run checks stay open in PLAYER_VALIDATION; no VERIFIED claim. Sol stopped at usage limit; coordinator finished current tests and prepared detailed follow-up. Phone settings/personal catalog preserved, own test jobs/audio cleaned; ADB retained.
