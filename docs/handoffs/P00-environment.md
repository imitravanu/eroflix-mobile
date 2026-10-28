# P00 — environment handoff

## Identity

- Date: 2026-10-27. Owner/integration owner: Codex.
- Checkout: existing Eroflix checkout; no branch change or commit.
- Host tasks P00.01, P00.02, P00.04, P00.05 delivered; P00.03 subsequently measured on the connected Pixel 6.
- State: VERIFIED for P00 acceptance; P01 in-app codec/biometric diagnostics remain deferred as permitted by P00.03.
- Host build target: isolated numeric API 37 debug APK on Linux x86_64.
  Read-only physical baseline: authorized Pixel 6 (`oriole`), Android 17 / API 37.
  No application installation, launch or emulator test was performed.

## Delivered behavior

| Task / requirement | Result | Paths |
| --- | --- | --- |
| P00.01 / R01 | Checksum-pinned JDK 17 and Gradle; official AGP/SDK baseline; dependency candidates with compatibility caveat | tools/dev/toolchain-lock.json, dependency-candidates.json |
| P00.02 / R01 | Project-local setup, SDK install, revision checks, environment wrapper, ignored local.properties | tools/dev/setup.py, with-toolchain.sh |
| P00.03 / R01 | Serial-free device helper; connected API 37 Pixel 6 baseline measured | tools/dev/device-baseline.py, docs/reports/DEVICE_BASELINE.md |
| P00.04 / R01 | Host memory limits, explicit device selection, future fixture transport, isolated APK build check | tools/dev/README.md, verify-toolchain.sh |
| P00.05 | Measured reports and this handoff | docs/reports/TOOLCHAIN.md |

## Changed paths and interfaces

- New `tools/dev/` helpers, tool lock, candidate dependency metadata and README.
- New `docs/reports/TOOLCHAIN.md`, `DEVICE_BASELINE.md` and this handoff.
- Corrected SDK package lookup to `platforms;android-37.0` in platform baseline.
- Updated STATUS and mobile README with observed implementation state.
- No production module, shared repository interface, schema or app manifest added.
- P01 consumes tools through `tools/dev/with-toolchain.sh`; machine paths remain
  in ignored local configuration. No desktop catalog data was changed.

## Verification evidence

| Command/check | Result | Evidence |
| --- | --- | --- |
| JDK and Gradle version | PASS: Temurin 17.0.21+9, Gradle 9.6.0 | TOOLCHAIN.md; artifacts/p00/setup-verified.log |
| Corrected setup rerun | PASS: installed API 37.0 rev2, platform-tools 37.0.1, build-tools 36.0.0 | artifacts/p00/setup-verified.log |
| Isolated API 37 APK build | PASS with Java Android API reference | artifacts/p00/smoke-build-compiled.log |
| APK configuration/signature | API 37 compile/min/target; debug signature verified | artifacts/p00/apk-badging.txt |
| Python compilation / shell syntax | PASS | TOOLCHAIN.md |
| Download mismatch guard | PASS: mismatch rejected before extraction | TOOLCHAIN.md |
| No-device/unselected/unauthorized fixture cases | PASS: pending status; serial omitted | TOOLCHAIN.md |
| Actual adb inventory and physical baseline | PASS: authorized Pixel 6 / API 37; 4 KiB pages, display/storage/ABI measured | DEVICE_BASELINE.md |
| Combined Compose/Media3/SQLite build | PENDING P01 | tools/dev/dependency-candidates.json |

## Remaining checks

- Physical identity/API, page size, display/storage and ABI are now measured.
  Use the explicitly selected Pixel 6 for P01 application testing when ready.
- P01 adds in-app biometric/codec diagnostics and validates the complete pinned
  dependency set. Candidate versions have not been claimed as build-compatible.
- No emulator, app installation, performance, provider playback, downloads,
  SQLite native alignment, application UI or release signing acceptance is claimed.
- The smoke APK has no launcher and is not the Eroflix application.

## Resource notes

- Downloads, SDK, Gradle caches, smoke APK and logs live in ignored local-data/
  and artifacts/. SDK machine path is in ignored local.properties.
- The single-use Gradle daemon exited after each build; adb server stopped.
- System Java and shell startup configuration were not changed.

## Next assignment

**P01.01 — reproducible Gradle application foundation**, followed by P01.02–05.
The host toolchain prerequisite is available. Use AGP built-in Kotlin; its POM
pins Kotlin Gradle plugin 2.2.10, which differs from Gradle's embedded Kotlin.
Start through the project environment wrapper and verify Compose compiler and
all library metadata compatibility in the actual foundation build.

P00 is VERIFIED and P01 is READY. Codec/biometric in-app diagnostics remain
P01 work. P10/P11 still require full application acceptance on the actual Pixel 6.

## Follow-up helper review

[P00_REVIEW](../reports/P00_REVIEW.md) records the repaired SDK revision preflight,
serial-safe adb errors, reproducible display/refresh sampling, retained successful
physical evidence and preservation of local build settings. The helper
regression suite (12 cases) and actual setup rerun passed; the existing APK
signature verified again. P00 remains VERIFIED and P01 READY. No new physical
measurements or app tests are claimed by this review.
