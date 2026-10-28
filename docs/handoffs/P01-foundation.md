# P01 — application foundation handoff

## Identity

- Date: 2026-10-27. Owner/integration owner: Codex / Sol.
- Checkout: existing Eroflix checkout; preserved the pre-existing root README modification.
- Scope: P01.01–P01.05; host implementation delivered, device acceptance pending.
- State: VERIFIED after the connected-device follow-up below. Target built: API 37 debug APK, arm64-v8a + x86_64.
- Initial host handoff had no phone. Subsequent device run: four tests passed on Pixel 6 / API 37; no emulator used.

## Delivered behavior

| Requirement/task | Result | Paths |
| --- | --- | --- |
| P01.01 / R01 | Pinned Gradle wrapper, built-in Kotlin, SDK 37, version catalog and resolved locks | Gradle root, app/build.gradle.kts, core/*/build.gradle.kts |
| P01.02 | Compiled v1 data/media contracts and debug-only neutral fakes | core/model/, core/testing/, docs/contracts/API_V1.md |
| P01.03 / R25 | Compose shell, typed routes, saved search, preview catalog, dark primitives | app/.../Shell.kt, BrowseViewModel.kt, core/ui/ |
| P01.04 / R21 | Secure-window setup, contained networking, provider/backup/channel/settings boundaries and diagnostic hook | app/src/main/AndroidManifest.xml, res/xml/, Platform.kt |
| P01.05 | Real host/device aggregates, 14 passing unit tests, compiled 3-test device suite, reusable host/APK checks | tests, tools/dev/verify-foundation.sh, require-device.py, inspect-apk.py |

## Interfaces and ownership

- Domain APIs: `tech.imitravanu.eroflix.model` in core:model. See API_V1.md and
  DATA/MEDIA. No Android/SQLite/ExoPlayer types leak into the general contracts.
- Source ID algorithm: SHA-256 of four length-prefixed UTF-8 fields, exact title
  ID, provider, uppercase source enum name and exact original URL. P02 must match it.
- Personal invalidation: `PersonalRepository.revision` flow, included in query
  stamps and used by the search ViewModel. P02 must persist it transactionally.
- app owns composition, routes, manifest, settings DataStore and channel IDs.
- core:data owns the bundled SQLite driver factory; P02 adds real DB lifecycle.
- core:network owns the HTTP factory with automatic redirects disabled; P03 adds
  scoped header/redirect validation and source resolution.
- core:media packages Media3; player/session/cache singletons remain P05/P06 work.
- core:testing is a debug/test-only dependency. Production release cannot build
  until its catalog-provisioning gate and release container are replaced by P02.
- Feature modules grow in P04–P09. No production schema/migration is introduced here.

## Validation

All host checks, declared/resolved versions, APK identity/digest, native packaging
inspection and actual commands are recorded in [FOUNDATION](../reports/FOUNDATION.md).

- `tools/dev/verify-foundation.sh`: PASS using the persisted dependency locks.
- Unit suites: 14 passed, zero failures/errors. Debug lint: zero errors.
- Android tests: compiled, NOT RUN. Explicit missing-device guard tested.
- Release provisioning guard: tested through assembleRelease; correctly rejected.
- Native packaging: two intended ABIs, ZIP and all ELF PT_LOAD segments align for 16 KiB.
- Actual 16 KiB runtime, SQLite load/FTS5, phone navigation and diagnostics: PENDING.

## Remaining checks

1. Reconnect/authorize and select the API 37 Pixel 6; run verifyAndroidTests.
2. Record actual in-app codec/biometric diagnostic results to complete P01's
   deferred P00 diagnostic hooks. Phone page size remains the measured 4 KiB.
3. Keep P01 IMPLEMENTED until the required physical installation/lifecycle tests
   pass. Preserve the P10/P11 real-source, performance and release gates.
4. The debug fixture has three neutral records and process-local personal data;
   it is not the real catalog and does not perform playback/download/export.
5. The currently used instrumented Compose rule emits a nonblocking deprecation
   warning; coordinate its API migration with runtime validation rather than
   claiming the compiled suite has already passed on a phone.

## Resources and artifacts

- APK: app/build/outputs/apk/debug/app-debug.apk (ignored).
- Test APK: app/build/outputs/apk/androidTest/debug/ (ignored).
- Logs/inspection: artifacts/p01/ (ignored); no serials saved in tracked reports.
- No emulator, fixture HTTP server or playback process was started. Gradle used
  single-use daemons, 1536 MiB heap, two workers and disabled project parallelism.
- Production data and desktop source code were not changed.

## Next assignments

P02.01 — consistent catalog snapshot/export; P03.01 — source-family feasibility.
Their device-independent work can consume the built contracts/toolchain now.
Physical checks remain explicit, and all required gates must pass before P11.

## Final device handoff — 2026-10-27

P01 is VERIFIED. [P01_DEVICE_VALIDATION](../reports/P01_DEVICE_VALIDATION.md)
supersedes the initial pending-device statements above. All four instrumented
tests pass on the authorized, unlocked Pixel 6 / API 37; native SQLite FTS5,
secure-window/network policy, actual landscape rotation/recreation, restored
query, and in-app diagnostics are measured. The test dependency Espresso 3.5.0
was replaced with an explicit stable 3.7.0 pin and the locks were updated.
Host checks and 14 unit tests pass. Preview reinstalled for user access; test-only
package cleaned up and original rotation settings preserved.

Next: P02.01 and P03.01 with a verified P01 predecessor. Full provider playback,
app-lock authentication and P10/P11 release acceptance remain later-phase work.
