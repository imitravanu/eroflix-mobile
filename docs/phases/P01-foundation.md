# P01 — Application foundation and shared contracts

**Role:** Android foundation/integration agent
**Prerequisites:** P00 toolchain ready
**Owns:** Gradle root/wrapper/version catalog, `app/`, initial core modules,
manifest, test aggregates, basic theme/navigation/DI
**Requirements:** R01, R21, R25

## Goal

Build and launch an Android-17-only Compose application with stable module
boundaries that let data, source, and UI agents work independently.

## Read first

[Architecture](../ARCHITECTURE.md), [platform](../ANDROID17_PIXEL6.md),
[data contract](../contracts/DATA.md), [media contract](../contracts/MEDIA.md),
[validation](../VALIDATION.md), and P00 handoff.

## Work packages

### P01.01 — Reproducible Gradle project

- Create Kotlin DSL build/settings, the verified Gradle wrapper including its
  JAR/scripts/checksum configuration, and `gradle/libs.versions.toml`.
- Set `minSdk = 37`, `compileSdk = 37`, `targetSdk = 37` in shared build logic
  or one clearly owned convention. No silent downgrade to an earlier SDK.
- Configure `tech.imitravanu.eroflix`, `.debug` suffix, debug/release build
  types, and arm64 release packaging; debug may include x86_64 for emulator use.
- Use AGP 9 built-in Kotlin correctly and match the Compose compiler version
  to the chosen compiler. Pin library/plugin versions and repositories.
- Start a small constructor-injected application container; keep it readable
  instead of introducing a framework that every phase must independently configure.

### P01.02 — Domain models and public APIs

- Define stable `TitleId`, `SourceId`, generation ID, query/filter/cursor/value
  types, library state, resolution outcomes and error/result types in `core:model`.
- Declare repository/resolver/player-control/download/export/update interfaces
  from DATA/MEDIA with fake implementations and neutral fixtures.
- Encode milliseconds/unknown values explicitly. Avoid Android URI, SQLite
  handles or ExoPlayer instances in general domain types.
- Publish a short contract change policy and demonstrate one fake repository
  consumed by a ViewModel. P02/P03 refine only through shared contract review.

### P01.03 — Shell, navigation and design primitives

- Single-activity edge-to-edge Compose shell with Browse, Downloads, Library
  and Settings destinations; details/entity/player as typed routes.
- Add dark theme tokens, typography, common loading/error/empty states, a
  poster placeholder, and accessible action/control components.
- Scaffold top-bar Providers/Filters buttons and small-screen layout; implement
  actual data/drawers in P04.
- Enable predictive Back-compatible navigation and save/restore route state.
- Set secure window behavior from launch. Production assets and actual browsing
  are wired in P02/P04; sample data remains visibly a fixture build.

### P01.04 — Platform services and configuration boundaries

- Establish manifest/service contribution conventions for P05/P06/P07 instead
  of allowing every feature to rewrite the app manifest concurrently.
- Set network security configuration with production HTTPS behavior and an
  isolated test-only fixture configuration.
- Define notification channel IDs, FileProvider authority, DataStore settings
  owner and simple diagnostics screen hooks.
- Complete pending P00 codec/biometric diagnostics on the actual phone when
  available. Package bundled SQLite through standard native dependency tooling.

### P01.05 — Build and test entry points

- Implement `verifyUnitTests` and `verifyAndroidTests` aggregates over real
  module tasks. Document device selection and required environment.
- Add a meaningful shell smoke check: launch, navigate, rotate, restore state,
  and verify accessible control labels and expected SDK/package configuration.
- Establish CI/host commands using only fixture assets initially. No production
  build silently falls back to sample media/catalog data.
- Update README with the commands that now actually exist.

## Acceptance

- [x] `./gradlew assembleDebug lintDebug verifyUnitTests` succeeds.
- [x] API 37 app installs/launches and navigation survives recreation.
- [x] Manifest/APK package, min/target SDK and native ABIs match the baseline.
- [x] `verifyAndroidTests` runs the smoke check on the recorded API 37 target.
- [x] Data and source agents can implement against versioned shared interfaces.
- [x] Sensitive surfaces start secured and test-fixture networking is isolated.
- [x] New native libraries have documented 16 KB packaging verification plan.

## Evidence and handoff

Create `docs/handoffs/P01-foundation.md` with build output paths, resolved
versions, module/API map, test commands and device results. Next independent
assignments: **P02.01** and **P03.01**.

## Current evidence

All P01 acceptance criteria are verified. The [host report](../reports/FOUNDATION.md)
and [device report](../reports/P01_DEVICE_VALIDATION.md) record the commands,
14 passing unit tests and four passing instrumented tests on the API 37 Pixel 6.
Native 16 KiB static alignment passes; supplemental 16 KiB runtime acceptance
remains P10 work under the documented plan.
