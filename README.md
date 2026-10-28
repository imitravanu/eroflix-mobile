# Eroflix Mobile — Android 17 / Pixel 6

**Canonical Android project root:** `eroflix/mobile/` within the existing
Eroflix Git repository.

**Planning revision:** 3.0 · 2026-10-27
**Implementation status:** P00–P03 verified. The full offline catalog and persistent personal storage pass host and Pixel 6 checks. Final facets p95: 415.54 ms; instrumented PSS: 161.17 MiB. P02 evidence is recorded in [its report](docs/reports/CATALOG_BUILD.md).

P03 source feasibility is VERIFIED; Gate A passes with measured limits.
A full Pixel rerun records 17 native successes across 45 fixed cases. Later focused
checks prove Tube 5/5 native and Eporner 5/5 web video with own-app unmuted audio
output. A complete 67 MB real-provider download/cache-only seek and four final Pixel
integration tests pass. See [source capabilities](docs/reports/SOURCE_CAPABILITIES.md).

P04 browsing/search/filter/details UI is implemented. Five Pixel UI tests, text
scales1.0/1.3/2.0, deep paging/restoration and full-catalog timing/memory checks
pass. Focused manual accessibility/gesture checks remain before P04 verification.
See [P04 handoff](docs/handoffs/P04-discovery-ui.md).

P05 production player is implemented. Controlled native formats, gain processing,
resume/session controls, subtitles/gestures/PiP and one bounded real source pass
on Pixel. Broader lifecycle/hardware acceptance remains; see
[player validation](docs/reports/PLAYER_VALIDATION.md). Downloads/export remain
P06/P07 work.

**Current phone access (2026-10-28):** Pixel remote debugging is active by user request. Keep it enabled across phases and disable only after all phases are complete, or an earlier explicit user request. See AGENTS.md.

Build a standalone, sideloaded Android application with an offline catalog,
phone-side source resolution, native playback, durable downloads, personal
library, and independent app/catalog updates.

## Target

| Setting | Project baseline |
| --- | --- |
| Primary phone | Google Pixel 6, device codename `oriole` |
| Release OS | Android 17, API 37 |
| SDK configuration | `compileSdk = 37`, `targetSdk = 37`, `minSdk = 37` |
| Release ABI | `arm64-v8a` |
| UI / media | Kotlin, Jetpack Compose, Material 3, AndroidX Media3 |
| Catalog | Indexed, read-only SQLite generation on the phone |
| Personal state | Independent writable `personal.db` |
| Distribution | Signed APK; stable package and signing identity |

`minSdk = 37` is the initial project decision for this Android-17-focused
release. The actual OS installed on the user's Pixel 6 must be recorded in
P00. Google lists Pixel 6 as supported for Android 17. See the verified
[platform baseline](docs/ANDROID17_PIXEL6.md).

## Start here

1. Read [AGENTS.md](AGENTS.md).
2. Read [STATUS.md](docs/STATUS.md) to find the next available task.
3. Read [ROADMAP.md](docs/ROADMAP.md) for dependencies and feature coverage.
4. Read [ARCHITECTURE.md](docs/ARCHITECTURE.md), then the assigned phase.
5. Use the [assignment prompt](docs/agents/PHASE_PROMPT.md) and
   [handoff template](docs/agents/HANDOFF_TEMPLATE.md).

**Next assignments:** [P04 focused acceptance](docs/phases/P04-discovery-ui.md) and [P05 player](docs/phases/P05-player.md).

## Implementation phases

| Phase | Result |
| --- | --- |
| [P00](docs/phases/P00-environment.md) | Reproducible Android 17 toolchain and Pixel 6 test profile |
| [P01](docs/phases/P01-foundation.md) | Launchable Compose app, module boundaries, shared contracts |
| [P02](docs/phases/P02-catalog-data.md) | Catalog exporter, SQLite search/filter engine, personal state |
| [P03](docs/phases/P03-source-feasibility.md) | Proven phone-side source strategies and playback/download capabilities |
| [P04](docs/phases/P04-discovery-ui.md) | Browse, search, provider/filter drawers, details, filmographies |
| [P05](docs/phases/P05-player.md) | Native player, gestures, PiP, quality/mirror controls, audio gain |
| [P06](docs/phases/P06-downloads.md) | Durable download queue and genuinely offline playback |
| [P07](docs/phases/P07-export.md) | Valid standalone media export through Android's file picker |
| [P08](docs/phases/P08-library-privacy.md) | Personal library, hidden-title manager, settings, fingerprint/PIN lock |
| [P09](docs/phases/P09-updates.md) | Recoverable catalog updates and verified APK update flow |
| [P10](docs/phases/P10-device-validation.md) | Pixel 6 reliability, Android 17 compatibility, performance evidence |
| [P11](docs/phases/P11-release.md) | Signed, install-tested release APK and maintenance handoff |

## Engineering references

- [Actual catalog audit](docs/CATALOG_AUDIT.md): 286,207 titles; 251,717 are
  embed-only in the current dataset. Provider support is an early feasibility gate.
- [Data contract](docs/contracts/DATA.md): stable IDs, catalog transformation,
  FTS mapping, hidden filtering, migrations, generation replacement.
- [Media contract](docs/contracts/MEDIA.md): resolution results, playback,
  download state ownership, offline cache, export.
- [Validation guide](docs/VALIDATION.md): required scenarios, budgets, commands,
  and evidence requirements.
- [Original blueprint](BLUEPRINT.md): product intent and initial screen sketches.

## Working directory and commands

Run Android commands from `eroflix/mobile/`; Git commands use the enclosing
Eroflix repository. The parent package.json belongs to the desktop application.

```sh
./gradlew --version
tools/dev/verify-foundation.sh
# Real aggregate tasks:
./gradlew assembleDebug lintDebug verifyUnitTests
./gradlew :app:assembleDebugAndroidTest
# After explicitly selecting an authorized API 37 device through ANDROID_SERIAL:
./gradlew verifyAndroidTests
```

The Linux launcher selects the local P00 toolchain before starting Java. Host
checks build a preview with five neutral test records, compile phone tests and
inspect the APK. Connected tests require explicit device selection before APK
installation. See [foundation commands and ownership](docs/FOUNDATION.md),
[verification evidence](docs/reports/FOUNDATION.md), and [API v1](docs/contracts/API_V1.md).

Full builds use `-PcatalogDir=local-data/catalog-full-r4` (ignored local artifact).
Release assembly requires a verified full catalog input; default debug builds use
a generated neutral fixture. The neighboring eroflix-mobile folder
contains the historical blueprint; current implementation lives here.

P03 lab commands and the fixed real-source sample workflow are in
[tools/sources](tools/sources/README.md). After a full build, verify the packaged
catalog against the selected input in both variants:

```sh
python3 tools/dev/inspect-apk.py --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03
python3 tools/dev/inspect-apk.py --variant release --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03
```

Release inspection includes full-catalog and lab-exclusion checks. The release
APK is unsigned until P11; assembly alone does not establish install readiness.

## Available host setup

```sh
python3 tools/dev/setup.py
tools/dev/with-toolchain.sh java -version
tools/dev/verify-toolchain.sh
```

See [setup instructions](tools/dev/README.md). The isolated toolchain check
builds a neutral APK without launcher UI; it is not the Eroflix application.
The actual foundation modules, Gradle wrapper and preview application now exist;
use the application verification commands above.
