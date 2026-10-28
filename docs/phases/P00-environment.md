# P00 — Environment and Pixel 6 baseline

**Role:** Platform/toolchain agent
**Prerequisites:** None
**Owns:** `docs/reports/TOOLCHAIN.md`, `docs/reports/DEVICE_BASELINE.md`,
`tools/dev/` setup/diagnostic helpers
**Requirements:** R01

## Goal

Provide P01 with a reproducible, supported API 37 build environment and a
truthful description of the available test device.

## Read first

- [Agent instructions](../../AGENTS.md)
- [Platform baseline](../ANDROID17_PIXEL6.md)
- [Status](../STATUS.md)

## Work packages

### P00.01 — Inventory and toolchain lock

- Inspect PATH, installed JDKs, SDK paths, disk space, and existing tooling.
  The planning audit found OpenJDK 25 EA but no adb/sdkmanager on PATH.
- Resolve the documented starting combination: AGP 9.4.0, Gradle 9.6.0,
  JDK 17 LTS, API 37. Record official download/release sources and checksums.
- Select the supported stable AndroidX/Compose/Media3/SQLite dependency set for
  P01; record any still-to-be-proven plugin compatibility explicitly.
- Configure per-project JDK/SDK selection so it does not depend on the system's
  early-access Java being selected by accident.

### P00.02 — SDK and build prerequisites

- Install/locate Android command-line tools, platform-tools, API 37 platform,
  and the build-tools version required by the pinned AGP.
- Complete required SDK license setup through the normal tooling.
- Verify `sdkmanager --list_installed`, `adb version`, and the selected JDK.
- Provide a minimal repeatable setup command/script and environment-variable
  instructions. P01 owns the real project Gradle wrapper.
- Use ignored `local.properties` for machine paths; track the required versions.

### P00.03 — Actual Pixel 6 baseline

- Discover authorized adb devices; select the owner's Pixel 6 explicitly.
- Record model/codename, installed Android/API/security patch, ABI list, page
  size, logical display size/density, free storage, and current refresh mode.
- Record fingerprint availability and codec inventory as pending in-app
  diagnostics if P01 is not yet built.
- If the phone is disconnected/unauthorized or runs an older OS, state that
  precise condition. The hardware profile is not a substitute for measurements.

### P00.04 — Test execution profile

- Document one API 37 test target and how it is selected for Gradle tests.
- A Pixel-like emulator can unblock Android integration checks; actual Pixel
  codec/thermal/gesture claims wait for the physical phone.
- Document the debug fixture network transport and its Android 17 LAN behavior.
- Set bounded Gradle workers/memory for this machine; do not run competing full
  builds or leave an emulator running after the session's checks finish.

### P00.05 — Handoff

- Write the resolved `TOOLCHAIN.md` with actual command output summaries.
- Write `DEVICE_BASELINE.md`, separating measured values from planned checks.
- List exact P01 setup inputs and any unresolved compatibility issues.

## Acceptance

- [x] API 37 platform and build tools are present; adb and pinned JDK execute.
- [x] Versions/paths/setup commands are reproducible and documented.
- [x] Device identity/API baseline is measured on Pixel 6, or clearly pending.
- [x] Test device selection and fixture transport are documented.
- [x] P01 can consume a pinned configuration without guessing tool versions.

The toolchain portion can be handed off while phone access is pending. P00
becomes VERIFIED only when its physical baseline is complete; P10/P11 retain
the physical-device requirement.

## Evidence and handoff

Create `docs/handoffs/P00-environment.md` using the
[template](../agents/HANDOFF_TEMPLATE.md). Include actual command summaries,
local setup paths, missing phone measurements, and the next task **P01.01**.

## Acceptance evidence

The checked criteria are backed by the [toolchain report](../reports/TOOLCHAIN.md),
[measured Pixel 6 baseline](../reports/DEVICE_BASELINE.md), and
[2026-10-27 helper review](../reports/P00_REVIEW.md). In-app codec and biometric
checks remain P01 responsibilities under P00.03; this checklist does not claim
application installation or device playback validation.
