# P01 — verified on Pixel 6

Date: 27 October 2026. Owner: Codex / Sol. **P01 state: VERIFIED.**

The application foundation now passes both host checks and its required tests
on the authorized Pixel 6 (`oriole`), Android 17 / API 37. The user unlocked the
phone for the final successful navigation/rotation run. No device serials are
included here. The tested Eroflix Preview APK is installed on the phone.

## Device test results

`./gradlew verifyAndroidTests` completed successfully in 31 seconds. All four
tests passed with the saved dependency locks:

| Test | Observed result |
| --- | --- |
| Navigation and state restoration | Browse search, details/entity navigation, Back, tab switching, landscape rotation and activity recreation retained the query and correct results |
| Platform and privacy baseline | Correct debug package, min/target API 37, FLAG_SECURE present, fixture label and drawer controls accessible; HTTPS policy rejects general cleartext and permits debug loopback |
| Bundled SQLite | Native library loaded on the real arm64 phone; FTS5 table creation, insert and search returned the expected fixture result |
| In-app diagnostics | Settings → Device diagnostics rendered and reported actual API/page/storage/codec/biometric capability values |

## Measured diagnostic results

| Property | Result |
| --- | --- |
| Phone | Pixel 6, Android 17 / API 37 |
| Memory page size | 4096 bytes (4 KiB) |
| Free app-volume storage at measurement | 17,937,498,112 bytes (about 16.7 GiB) |
| Biometric capability | Strong biometric authentication is available |
| Reported video decoder / MIME entries | 26; 11 marked hardware-accelerated |

Decoder entries count codec/MIME pairs, not unique decoder hardware. This
inventory is not proof that every catalog stream is playable. Biometric
capability was measured; actual app-lock authentication belongs to P08.

## Corrections made during device verification

The first run failed before tests could execute because the transitive Espresso
3.5.0 dependency reflected a framework method removed in API 37. Explicitly pinned
stable Espresso 3.7.0 and updated the app's test dependency locks. AndroidX's
[official release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)
document replacing that reflective InputManager call with getSystemService.
Only instrumentation dependencies and tests changed; product behavior was not
weakened or skipped.

Added a fourth smoke test to exercise the diagnostics screen and capture its
actual reader results in app-scoped diagnostic log lines. Kept logical
rotation-state polling tied to the scenario's current activity. An intermediate
run passed three tests but timed out on rotation while the lock screen was
active. After the user unlocked the phone, all four passed. No test was removed
or disabled to obtain a pass.

Host revalidation after the dependency/test changes:

- `:app:assembleDebugAndroidTest lintDebug verifyUnitTests` succeeded; 14 unit
  tests pass and lint reports zero errors.
- A normal connected-test run succeeded without rewriting locks.
- Final APK package/min/target/compile API, debug signature, native ABIs and
  static 16 KiB ZIP/ELF alignment inspection passed again.
- Final APK SHA-256: `c6dc9108a40652cf3111878e61f9d4281bb4fdb8eff4726b86c005016ec32fc3`; 46,068,362 bytes.

The Gradle connected-test runner removes its test installations during cleanup.
The verified preview was therefore installed again for the user afterward.
No test-only APK was left installed. The original auto-rotation setting (off)
and user rotation (portrait/0) were checked and preserved. No app data outside
the isolated preview was touched, and no emulator or fixture server was run.

## Evidence and remaining phase boundaries

Saved ignored evidence: `artifacts/p01/connected-tests.log`,
`device-host-checks.log`, `pixel6-diagnostics.json`, `device-cleanup.json`,
`apk-inspection.json`, and Gradle's connected XML/logcat results. Initial failed
runs remain recorded separately for diagnosis; the final run is authoritative.

P01 is complete. P02 catalog/storage and P03 source feasibility are next. The
preview contains three neutral test titles, process-local personal state and
placeholder player/download surfaces. Production catalog, actual media,
durable downloads, app lock and release signing remain in their assigned phases.
The separate 16 KiB runtime gate remains P10 work; this Pixel 6 uses 4 KiB pages.
You can disconnect the phone now; later device-dependent phases will need it again.
