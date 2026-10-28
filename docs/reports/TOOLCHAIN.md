# P00 toolchain report

Date: 2026-10-27. Owner: Codex. Target: Android 17 / API 37, Pixel 6.

## Host inventory

- Linux x86_64, 6.6 GiB RAM; approximately 58 GiB free disk at inventory.
- System Java: OpenJDK 25.0.5-ea; it is not selected by the project wrapper.
- Before setup: no adb, sdkmanager, Gradle or JDK 17 on PATH; SDK variables unset.
- Project-local archives and SDK are ignored under `local-data/toolchain/`.
- Build limit: 2 workers, no parallel projects, 1536 MiB Gradle heap, in-process
  Kotlin compiler; P01 owns the project Gradle properties enforcing these values.

## Locked tools

See `tools/dev/toolchain-lock.json` for exact official archive URLs and SHA-256.
JDK and Gradle archives passed checksum verification and both tools executed:

| Tool | Version | Result |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.21+9 | `java -version` PASS |
| Gradle | 9.6.0 | `gradle --version` PASS using pinned JDK 17 |
| Android Gradle plugin | 9.4.0 | Isolated API 37 APK built successfully |
| Command-line tools | 22.0 / archive 15859902 | Checksum verified; sdkmanager executed |
| Android platform | android-37.0 revision 2 | Installed; numeric compileSdk 37 build passed |
| Build tools | 36.0.0 | Installed; aapt2/APK signing verification passed |
| Platform tools | 37.0.1 | adb version and device inventory executed |

AGP 9.4.0's POM declares Kotlin Gradle plugin 2.2.10. Start P01 with built-in
Kotlin and Compose compiler plugin 2.2.10; do not infer its compiler version from
Gradle's embedded Kotlin 2.3.21. A newer supported Kotlin override requires an
explicit, build-verified decision.

## Stable dependency candidates

Resolved from official Google Maven/Maven Central metadata on this date. These
are exact proposed P01 pins, **not a verified combined build**. P01 must resolve
and build them and deliberately revise any Kotlin/metadata incompatibility.

| Dependency | Candidate version | State |
| --- | --- | --- |
| compose_bom | 2026.09.00 | Build compatibility pending P01 |
| activity | 1.13.0 | Build compatibility pending P01 |
| lifecycle | 2.11.0 | Build compatibility pending P01 |
| navigation | 2.10.2 | Build compatibility pending P01 |
| media3 | 1.11.1 | Build compatibility pending P01 |
| sqlite | 2.7.1 | Build compatibility pending P01 |
| coroutines | 1.11.0 | Build compatibility pending P01 |
| okhttp | 5.5.1 | Build compatibility pending P01 |
| agp | AGP metadata resolved | Build compatibility pending P01 |

## Setup and device commands

From `mobile/`:

```sh
python3 tools/dev/setup.py
tools/dev/with-toolchain.sh java -version
tools/dev/with-toolchain.sh gradle --version
tools/dev/with-toolchain.sh sdkmanager --list_installed
tools/dev/with-toolchain.sh adb devices -l
# Select the owner's serial locally; do not put it in reports.
tools/dev/with-toolchain.sh python3 tools/dev/device-baseline.py --output artifacts/device-baseline.json
```

`tools/dev/README.md` documents device selection, host limits and debug fixture
transport. The setup writes machine paths only in ignored `local.properties`.
No system Java, desktop application code, or catalog data is changed.

## Helper verification

- Python compilation and shell syntax: PASS.
- Download hash validation: matching fixture accepted; wrong digest rejected
  before extraction: PASS.
- Device-helper cases: absent, unselected and unauthorized devices stay pending,
  and private test serial is absent from output: PASS.
- Full application build and integration/device tests: pending P01.

## Official sources

- [AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [Google command-line tools and checksum](https://developer.android.com/studio#command-tools)
- [Adoptium JDK metadata](https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux)
- [Gradle distribution checksum](https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256)
- Dependency source URLs are recorded in `tools/dev/dependency-candidates.json`.

## Final installation and integration evidence

- Initial installation failed because the planning package used the unavailable
  SDK package ID `platforms;android-37`. Repository inspection showed the correct
  stable ID `platforms;android-37.0`, revision 2. The lock and platform baseline
  now use that published name. This changes package lookup, not the major API.
- Corrected `python3 tools/dev/setup.py`: PASS; reuse of checked archives and
  installed packages succeeded; ignored `local.properties` was written.
- Observed platform-tools 37.0.1, platform 37.0 revision 2, build-tools 36.0.0.
  Setup now reuses matching SDK packages and rejects installed or remote revision
  drift before installing missing packages (see P00_REVIEW.md). Package
  availability can change upstream; a future revision requires deliberate review.
- `tools/dev/verify-toolchain.sh`: PASS. Clean toolchain build succeeded in
  3m35s; adding a neutral Java Android API reference then succeeded in 16s.
  The check uses a 1536 MiB heap, 2 workers and a single-use Gradle daemon.
- `aapt2 dump badging`: smoke package `tech.imitravanu.eroflix.toolchainsmoke`,
  compile/min/target API 37. The smoke artifact has no launcher and no native
  libraries. It does not test Compose, bundled SQLite, app UI or device behavior.
- `apksigner verify`: completed without errors for the debug-signed smoke APK.
- Initial adb inventory: zero devices. A later read-only inspection measured an
  authorized Pixel 6 on Android 17 / API 37; see `DEVICE_BASELINE.md`.
  No emulator was installed or run.
- Ignore checks: toolchains, raw device report and local SDK paths are ignored.

Smoke output: `local-data/toolchain-smoke/build/outputs/apk/debug/` (ignored).
Sanitized command logs: `artifacts/p00/` (ignored). Full application build and
Compose/dependency compatibility checks remain P01 responsibilities.

## Connected-device follow-up — 2026-10-27

Pixel 6 identity, API, ABI, page size, display/density, refresh sample and storage
were measured after the user connected the phone. The physical P00 baseline now
passes. P01 still owns the combined application build and in-app codec/biometric
diagnostics. No app was installed by the baseline check.

## P00 helper review

The [review](P00_REVIEW.md) repaired SDK preflight ordering, preserved existing
local settings and device evidence, integrated the logical-display/refresh
measurements into the reusable helper, and sanitized adb failure output. All
12 offline regression checks, the actual setup rerun, and saved APK signature
verification passed. The complete application dependency build remains P01.
