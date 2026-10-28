# P01 foundation verification

Date: 2026-10-27. Owner: Codex / Sol. State: VERIFIED after physical device acceptance; see [P01_DEVICE_VALIDATION](P01_DEVICE_VALIDATION.md).

## Delivered foundation

- Gradle 9.6.0 wrapper with official wrapper-JAR checksum and pinned distribution
  checksum. Linux launcher selects the P00 JDK/SDK before locating Java.
- AGP 9.4.0 with built-in Kotlin, Kotlin/Compose compiler 2.2.10, Java 17 bytecode,
  numeric compile/min/target API 37. No legacy Kotlin Android plugin is applied.
- Modules: app; core:model, core:testing, core:data, core:network, core:media, core:ui.
- Versioned typed domain contracts, deterministic source IDs, query stamps,
  observable personal revision and small constructor-injected container.
- Dark Compose shell with Browse, Downloads, Library and Settings; typed
  details/entity/player/diagnostics routes. The preview contains three neutral
  fixture titles and process-local test favorites/saved items.
- Reusable dark theme/empty/error/poster components, search ViewModel with saved
  query and cancellation/invalidation, provider/filter preview affordances.
- FLAG_SECURE before content rendering, HTTPS production policy, debug-only
  loopback cleartext, explicit backup/transfer exclusions, FileProvider scoped to
  export staging, notification channel IDs and DataStore ownership.
- Device diagnostic hook for decoders, hardware/software classification,
  biometric capability, page size and available storage. No phone measurements
  were collected in P01 because the phone was disconnected.

This foundation provides a buildable interface for P02/P03. It does not provide
the full catalog, real streaming, durable downloads or personal-state persistence.
Those implementations remain assigned to their roadmap phases. Release assembly
fails explicitly until production catalog provisioning replaces the P01 gate.

## Host checks actually run

| Check | Result | Evidence |
| --- | --- | --- |
| Official wrapper JAR digest | PASS; matches official Gradle digest | gradle/wrapper/gradle-wrapper.jar.sha256 |
| `./gradlew --version` | PASS; launcher and daemon JDK 17.0.21 | artifacts/p01/wrapper-version.log |
| `assembleDebug lintDebug verifyUnitTests :app:assembleDebugAndroidTest --write-locks` | PASS; seven module locks and settings lock generated | artifacts/p01/verification.log |
| `tools/dev/verify-foundation.sh` without rewriting locks | PASS; full host checks plus APK inspection | artifacts/p01/host-checks.log |
| Unit suites | 14 passed: 7 domain, 5 fixture repository, 2 ViewModel | module build/test-results XML |
| Android instrumentation suite | Compiled into test APK; 3 tests NOT RUN on device | app/build/outputs/apk/androidTest/debug/ |
| Debug lint | Zero errors; 8 app warnings, zero core warnings | app/build/reports/lint-results-debug.xml |
| Connected-test preflight | Correctly rejects an unset ANDROID_SERIAL before installation | artifacts/p01/device-selection-guard.log |
| Production catalog preflight | `:app:assembleRelease` correctly fails with a provisioning message | artifacts/p01/production-catalog-guard.log |

The eight lint warnings are seven available-version notices and one intentional
ChromeOS ABI notice for the arm64-only release configuration. Versions remain
pinned to the compiled configuration. The instrumented Compose rule also has a
nonblocking deprecation notice; its migration can be made alongside actual device
validation. No lint error was hidden with a baseline or broad suppression.

## Dependency baseline

Exact declared versions are in `gradle/libs.versions.toml`; resolved configurations
are in module `gradle.lockfile` files and `settings-gradle.lockfile`.

| Component | Declared pin |
| --- | --- |
| AGP / Kotlin compiler / Compose compiler | 9.4.0 / 2.2.10 / 2.2.10 |
| Compose BOM | 2026.09.00 |
| Activity / Lifecycle / Navigation | 1.13.0 / 2.11.0 / 2.10.2 |
| Media3 / bundled SQLite | 1.11.1 / 2.7.1 |
| Coroutines / OkHttp | 1.11.0 / 5.5.1 |
| Serialization JSON | 1.9.0 |
| Core / DataStore / Biometric | 1.19.1 / 1.3.0 / 1.1.0 |

Serialization JSON 1.9.0 was deliberately selected for the Kotlin 2.2.10
compiler baseline instead of blindly taking P00's newer candidate. The actual
combined build passed. Resolved standard-library patch versions vary by
configuration and are captured in locks; Gradle's embedded Kotlin version is
not used as the application compiler version.

## APK inspection

- Package: `tech.imitravanu.eroflix.debug`; compile/min/target API 37.
- Label: Eroflix Preview. Version: 0.1.0-foundation-debug fixture variant.
- Size: 47,038,505 bytes.
- SHA-256: `cac64002df422a4048c661052ef27256def73ad162f3cd01f2cee76780d638bb`.
- Debug signature: verified. This is not a production release signer.
- Packaged native ABIs: arm64-v8a and x86_64. Release configuration is arm64 only.
- Six native libraries (graphics path, DataStore counter and SQLite for each ABI)
  have PT_LOAD alignment at least 16,384 bytes; APK zipalign 16 KiB check passed.
- Supplemental 16 KiB runtime and actual native SQLite load/FTS5 checks remain
  pending on their recorded runtime targets. Static alignment is not runtime proof.

Reproduce with `python3 tools/dev/inspect-apk.py` after assembly.

## Initial pending device acceptance (subsequently completed)

Connect/authorize the API 37 Pixel 6, explicitly select its serial locally, then
run `./gradlew verifyAndroidTests`. The compiled suite covers launch, navigation,
query restoration, rotation/recreation, package/API/secure-window state,
contained debug networking and bundled SQLite FTS5. Open Settings → Device
diagnostics and record the actual codec and biometric results separately.

P00's earlier measured phone baseline remains valid. P01 has not installed or
launched an app on that phone. P10/P11 retain physical performance, real provider,
long-duration, lock, final-signing and upgrade acceptance.

## Completed device follow-up

The [device report](P01_DEVICE_VALIDATION.md) supersedes the initial pending
statements in this historical host report. All four device tests passed on the
Pixel 6 / API 37, including diagnostics, navigation/rotation/recreation and
native FTS5. Espresso is now explicitly pinned to 3.7.0. Host lint/14 unit tests
passed again. P01 is VERIFIED. The current APK size/hash and cleanup evidence
are in the device report.
