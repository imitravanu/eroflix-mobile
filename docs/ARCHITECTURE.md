# Current architecture and implementation decisions

**Revision:** 3.0 · **Date:** 2026-10-27
**Status:** Implementation baseline; feasibility results may refine the documented contracts.

This document turns the original [blueprint](../BLUEPRINT.md) into an Android 17
implementation. Read the [data](contracts/DATA.md) and [media](contracts/MEDIA.md)
contracts before implementing shared boundaries.

## Decisions

| ID | Decision | Reason / verification |
| --- | --- | --- |
| A01 | Native Kotlin/Compose, SDK 37, initial minSdk 37 | User's Android 17 / Pixel 6 target; P00/P01 verify build and device |
| A02 | `mobile/` is the Gradle root inside Eroflix's existing repo | One implementation/status location with access to catalog tooling |
| A03 | Catalog queries and source resolution execute on the phone | Standalone operation; P10 tests with the development machine disconnected |
| A04 | Separate replaceable catalog and persistent personal-data domains | Stable title IDs bridge them without cross-file foreign keys |
| A05 | AndroidX bundled SQLite with typed repositories and explicit SQL/migrations | Known SQLite feature set; avoids a bespoke C/JNI bridge; P02 proves FTS5 |
| A06 | Media3 owns playback, download index, and download cache | Native lifecycle, adaptive streams, durable segment state |
| A07 | Download completion and standalone export are separate operations | Media3 cache is not a ready-made MP4 file |
| A08 | Source capability is resolved per source and purpose | Most current catalog entries are embeds, as shown by the actual audit |
| A09 | Visible drawer buttons plus in-app peek handles; platform Back retained | The original full-edge hold/exclusion scheme cannot guarantee passthrough |
| A10 | Performance and preservation are measured gates | Replaces unmeasured 2–3 ms and absolute "zero-risk" claims |
| A11 | Full compressed catalog generations first; catalog deltas evaluated in P09 | Simple recoverable updates with measurable optional transfer savings |
| A12 | Production catalog metadata ships with the APK initially | Offline first-launch browse, with separate catalog updates afterward |

## Modules and dependency direction

P01 creates only needed skeletons; feature modules grow in their owning phase.
Paths below are planned artifacts until that phase creates them.

```text
mobile/
  app/                         # application composition, navigation, manifest
  core/
    model/                     # IDs, value models, repository interfaces
    data/                      # catalog/personal SQLite, migrations, activation
    network/                   # HTTP policies, source adapters, resolver
    media/                     # player/session, downloads/cache, export pipeline
    ui/                        # theme, common accessible Compose components
  feature/
    catalog/                   # browse, filters, search, details, entity pages
    player/                    # player screen and controls
    downloads/                 # queue, offline items, export presentation
    library/                   # favorites, saved, history, hidden titles
    settings/                  # settings, privacy lock, updates UI
  benchmark/                   # profileable build/device journeys, added P10
  tools/catalog/               # host-side conversion/verification, added P02
  fixtures/                    # small neutral test datasets/media definitions
  docs/                        # plans, contracts, sanitized reports/handoffs
  local-data/                  # ignored catalog build inputs/output
  artifacts/                   # ignored APKs, traces, raw device reports
```

- `core:model` is a Kotlin/JVM library with no Android UI, Media3, or SQLite
  implementation types in its public interfaces.
- `core:data` and `core:network` depend on model. The `CatalogUpdater` interface
  lives in model; data owns orchestration, and the app injects network transport.
- `core:media` depends on model and the source/data abstractions supplied at
  construction. It owns one player/session, one download manager, and one
  download cache per process.
- Feature modules depend on model and common UI; the player/download feature
  may use an explicit media UI adapter for the player surface.
- `app` wires concrete implementations through a small constructor-injected
  application container. Feature-to-feature navigation uses typed routes/events.
- UI events flow to ViewModels/repositories; observable state flows back to UI.
  Database handles and activities never escape through a global UI singleton.

## Data ownership

### Catalog

Host tools convert a consistent desktop catalog snapshot into a normalized,
indexed, prebuilt SQLite file. The APK contains its compressed form and an
integrity manifest. First launch decompresses to a staged internal file and
activates it after verification. First-launch progress is resumable/retryable.

Catalog files reside in generation directories in app-internal storage.
Queries lease a generation. Activation drains/invalidates readers and publishes
a durable current-generation pointer. Keep the previous validated generation
until the replacement can reopen successfully.

### Personal domain

`personal.db` owns favorites, saved-for-later state, hidden markers, progress,
retained metadata, and application download/export intent. Writes are serialized
and transactional; migrations are forward, versioned, and preservation-tested.

The two-domain guarantee does **not** mean exactly two database files exist:
Media3 owns its own durable download/cache metadata, and DataStore may hold
simple settings. These persistent stores are outside the catalog update path.
Use one process for the initial application and its services.

Read connections open `personal.db` and attach the current catalog read-only.
Apply `query_only` to the read connections; the personal writer never attaches
the catalog. Each connection is exclusively leased for a query/transaction.
P02 must prove this driver's read-only attachment and FTS behavior on Android.

Discovery queries and ordinary library shelves exclude hidden titles before
paging and counts. Hidden manager and Downloads explicitly retain management
access. Changes invalidate results using
`(catalogGeneration, personalRevision, queryKey)`.
Favorites/library/download screens can display retained snapshots when a title
is no longer in the active catalog.

## Source and media flow

```text
Title + stored source handle
  -> SourceResolver(purpose = PLAYBACK or DOWNLOAD)
  -> NativeMedia | WebEmbed | Unavailable
  -> Media3 player / Media3 DownloadRequest / contained WebView

Completed DownloadRequest + Cache
  -> cache-only playback
  -> ExportEngine -> validated standalone file -> SAF destination
```

The source handle contains provenance and original location. Expiring media
URLs and credentials are runtime resolution results. A source listed in the
catalog is not automatically live, downloadable, or compatible with MP4 export.
Prioritize hosts using measured counts, not the illustrative providers/counts
in the original blueprint.

## Android lifecycle

- Start playback from a user-visible action. Media3 `MediaSessionService` owns
  playback lifetime and `mediaPlayback` foreground-service behavior.
- PiP is visible playback. Leaving player/PiP pauses by default; optional
  screen-off audio is an explicit setting implemented under Android 17 rules.
  An enabled app lock takes precedence: locking pauses native/web playback,
  covers/exits PiP, and prevents media-session controls from restarting it.
- Downloads use a distinct `dataSync` service with persistent intent and
  timeout handling. User pause, OS stop, requirements wait, and network failure
  remain distinguishable. Force-stop recovery happens after the user opens the app.
- Media export has its own resumable/retryable intent and appropriate
  `mediaProcessing` service for long work. A transfer service is not relabeled
  as playback to obtain a longer lifetime.
- Network changes can trigger eligible retries, not surprise autoplay or
  background permission/installer screens.

## Product defaults for Pixel 6

- Dark cinematic UI; two-column phone grid at normal font scale, responsive
  spacing, at least 48 dp touch targets, and accessible labeled controls.
- Actual measured window/insets govern layout; 1080×2400 hardware pixels are
  not hardcoded Compose dimensions.
- Adaptive playback with a default 1080p ceiling; show higher supported tracks
  only when actually present and selected by the user.
- Two active downloads by default; setting range 1–4. One export at a time.
- Wi-Fi-only downloads default on and use actual network transport; unmetered
  cellular is not automatically Wi-Fi. Background audio and auto-hide default off.
- Keep at least 1 GiB unreserved disk space, plus estimate remaining download,
  staging, update, and export bytes before admitting more work.
- App-private media under app-specific storage; user exports via SAF.
  Pixel 6 has no microSD slot; a USB/document provider is an optional destination.
- Fingerprint via `BiometricPrompt` and app-PIN fallback; do not promise Face ID
  on Pixel 6. Enable the lock after PIN enrollment, with unlock required on cold
  launch/background return. Secure window flags begin with the app foundation.
- Auto-hide is opt-in after **greater than 90%** actual watched coverage of a
  known finite timeline, using [DATA's watch-cycle rule](contracts/DATA.md).
  Resume position/seek-to-end does not establish completion; manual Unhide
  suppresses repeat auto-hide for the current watch cycle.

## Blueprint corrections agents must apply

- The ~29 MB SQL gzip/~270 MB DB figures describe the desktop export/database,
  not a measured normalized mobile database. Measure the new artifacts in P02.
- The desktop catalog has no structured duration/actor arrays. Preserve unknown
  metadata and derive entity links from existing `star:`/`studio:`/`series:` tags.
- No built-in Media3 HLS-to-MP4 conversion or seamless mirror guarantee is assumed.
- Whole-edge system gesture exclusions do not allow reliable delayed hand-back
  of the same touch stream. Implement the tested in-app interaction contract.
- `.nomedia` controls media indexing; it is not encryption or guaranteed
  confidentiality for a user-exported shared file.
- An app lock and `FLAG_SECURE` require explicit PiP, notification, and resume
  behavior; hiding a single thumbnail is insufficient.

## Change record

For a future change, append: date, decision ID, evidence, affected contracts,
consumer phases, migration impact, and acceptance checks. Keep the original
blueprint as design history and this document as the current technical baseline.

### 2026-10-27 — resolved SDK package identifier

P00 repository inspection and an actual AGP 9.4.0 build resolved API 37 through
`platforms;android-37.0` revision 2. This replaces the planning package name
`platforms;android-37`; compile/min/target SDK remain numeric 37. See the
[P00 toolchain report](reports/TOOLCHAIN.md). No data migration is involved.


### 2026-10-27 — P02 local data acceptance

A04/A05/A10 are implemented with catalog schema1, personal schema2, leased
read-only bundled-SQLite attachments and validated local activation. Optional
bounded membership masks accelerate broad shared-scope facets; sparse scopes
retain SQL. Exact normalized membership and stable public identities remain
authoritative. All P02 Pixel6 timing/memory/preservation gates passed. See
CATALOG_BUILD.md and API_V1.md. P09 remote policy and P10 release-like acceptance
remain separate; no personal-data migration is caused by optional catalog indexes.
