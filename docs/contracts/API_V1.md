# Foundation API v1

Owner: integration owner. Implementations live in their phase-owned core modules.
The source of these interfaces is `core/model/src/main/kotlin/tech/imitravanu/eroflix/model/`.
This supplements DATA.md and MEDIA.md with the initial compiled Kotlin boundary.

- `Identity.kt`: exact title/source/generation/job identities, typed errors, `Outcome`.
- `Catalog.kt`: query/facet/cursor/snapshot shapes; catalog, generation and update APIs.
- `Personal.kt`: personal state, library shelves, progress observations and invalidation revision.
- `Media.kt`: original source handles, purpose-specific resolution, scoped credentials,
  capabilities/tracks, player, download and export control interfaces.

All wall-clock and media time fields are explicitly milliseconds. Nullable fields
represent unknown metadata. Title IDs are preserved verbatim. Domain types contain
no Android URI, Activity, SQLite connection or ExoPlayer implementation type.

## Stable identity and query rules

Source IDs are lowercase SHA-256 of four length-prefixed UTF-8 fields in order:
exact title ID, provider key, `StoredSourceKind.name` (`FILE`, `HLS`, `EMBED`), and
exact original URL. Each prefix is the UTF-8 byte count as a four-byte big-endian
integer. Source query parameters and encoding are preserved. P02 reproduces this
algorithm in the exporter and checks for collisions.

`CatalogQuery.key()` excludes the cursor and display-only entity label. Paging
cursors include the query key, catalog generation and personal revision. Repositories
reject a stale stamp. `PersonalRepository.revision` drives discovery invalidation;
P02 persists the corresponding revision with personal state writes. Page size is
60 by default, capped at 120. Fakes exercise these invariants; production SQL and
multilingual search remain P02 work.

## Fake ownership and production boundary

`core:testing` contains three neutral titles, an in-memory personal repository,
query/facet fake, and explicitly unsupported source/media/download/export fakes.
It is a debug/test-only dependency. The debug container is constructor-injected
and visibly marked as a test catalog. Its personal state lasts only for that
application process. P02 replaces this with durable storage.

Release builds currently fail with a production-catalog provisioning error.
P02 must replace that gate with full generated-catalog verification and replace
the release container. Production must never silently ship the fixture catalog.

## Change policy

Changes to stable IDs, query semantics, timestamps, error meaning or API ownership
require a dated entry here, affected phase consumers, and a compatibility/migration
decision. Implementations may add private helpers without changing these APIs.
Source capability is measured per source/purpose; the presence of an interface
or a fake is not proof of native playback/download support.

### 2026-10-27 — P03 source validators and transport

`Resolution.NativeMedia` gains a trailing optional `validators: ResourceValidators?`
with a default of null. `ResourceValidators` records ETag, Last-Modified, complete
resource length and byte-range support. Existing constructors remain compatible;
there is no catalog or personal schema migration. P05/P06 consume these fields.
Only a matching strong ETag, known equal length and range support permit the
P03 resume helper to accept existing bytes. A URL, timestamp or length alone is
insufficient. P06 must still enforce this check and invalidate stale partial data.

`CatalogSourceResolver` implements the existing purpose-specific interface.
`scopedMediaDataSource` adapts a native resolution to Media3 using the same
immutable host/header policy for manifests, variants, segments, init maps and keys.
Transport/manifest acceptance is a candidate capability, not evidence of device
decoding, completed downloads or MP4 export. The P03 debug lab is isolated until
P05/P06 replace its engine/cache ownership with the production singletons.

## Initial change record

### 2026-10-28 — provider follow-up

Domain interfaces and persisted schemas are unchanged. P03's network module adds
data-only clear-player parsing, measured Tizam/Eporner hosts, final-origin Referer
scoping, initial WebView entry headers and bounded diagnostic properties. Optional
network-wrapper constructor parameters preserve existing source call sites; all
consumers were rebuilt. The real-source debug experiment changes its gesture
measurement and records blocked hosts without logging URLs/credentials. Earlier
Pixel results apply to their recorded APK; this revision needs its own phone run.

2026-10-27: v1 Kotlin contracts introduced for P01. DATA/MEDIA ownership retained.
Added observable personal revision to make invalidation implementable without
exposing database handles. P02 and P03 consume these interfaces; P05–P09 fill their
owned media/personal/update implementations. No persisted app schema exists yet.

2026-10-27 device verification: no domain contract change. Test-only Espresso
3.7.0 pin fixes API 37 framework compatibility; diagnostics inventory and all
four connected smoke tests passed.

### 2026-10-27 — P02 storage implementation

The v1 model interfaces remain unchanged. Catalog schema 1 now has a deterministic
Python producer and bundled-SQLite repositories. Personal schema 2 adds the
manual-unhide timestamp in a preserving transaction from schema 1. Favorite,
saved, hidden and resume progress use distinct fields and a durable revision.

Catalog JSON is authoritative, matching the desktop reader; scalar discrepancies
are audited, while exact IDs and source URLs are reconciled. Unknown provider uses
`@unknown` internally. Source IDs retain P01's algorithm without URL normalization.
Search is limited to 256 characters/16 literal words, with FTS prefixes and indexed
CJK substring candidates. Paging is keyset-based with a 120-item hard cap; shelf
streams are also capped at 120 until P08 adds library paging. Retained snapshots
include entity/source metadata and support placeholder posters when bytes are absent.

`GenerationStorage` exposes local stage/activation/rollback, safe cleanup and a
bundled-generation age check for app composition/P09. Optional covering indexes
and precomputed facet accelerators do not change schema-1 identities or semantics;
queries fall back when an older generation lacks them. Cancellation closes leases
and rejects obsolete results at native statement boundaries. P09 still owns signed
remote transport, accepted-sequence floors and update policy.


### 2026-10-27 — bounded facet bitmap acceleration

Exporter 1.0.3 adds optional schema-1 facet_bitmaps derived from authoritative
normalized memberships. Bit position is the private generation-local title row
ID, with little-endian bytes and unused bit zero. The app limits total bitmap
bytes to 32 MiB and row IDs to two million for this acceleration; SQL remains the
fallback. Public query/result/identity APIs and personal schema 2 are unchanged.
Visibility is applied before constructing the data-layer mask. No membership
mask is reused across a generation or personal-revision boundary.

### 2026-10-28 — source runtime integration

AppContainer now supplies CatalogSourceResolver instead of the pending resolver.
Production player/download/export controllers remain phase-owned placeholders.
Public domain APIs, source IDs and schemas are unchanged. SourceHttp adds
mediaClient(policy), preserving policy/connect/idle limits while removing the
probe's whole-call deadline for media bodies. Optional host-only diagnostics and
WebView initial-Referer/blocked-request properties preserve existing constructors.
All consumers compile; 53 JVM tests pass. Pixel provider measurements remain
attached to their individual APK hashes rather than inferred from a later build.

### 2026-10-28 — P04 discovery metadata/count and unknown duration

`CatalogRepository.metadata()` returns `CatalogMetadata(generation, titleCount,
sourceCount)` from the leased active catalog's authoritative catalog_meta; it is
never a bundled-manifest approximation. Total catalog count includes hidden
records. `count(CatalogQuery)` returns a stamped `CatalogCount` using the same
SQL visibility/filter predicate as discovery pages, including entity scopes.
Default unsupported methods keep existing third-party/test repositories source
compatible. Production storage implements both; no persisted schema changes.

`DurationFilter` gains trailing default `onlyUnknown=false`. When true, only SQL
NULL durations match, without a numeric sentinel. Query keys include this flag;
old query cursors cannot be reused under the new key algorithm. Existing callers
retain their previous duration behavior. P04 resets generation/query cursors and
restamps only personal-revision keyset locations in the same immutable generation.
P05/P06 typed source actions receive the exact `TitleSummary`/`SourceHandle`;
Play delegates to `PlayerControl.open`, Download to `enqueue(DownloadIntent)` with
empty selection meaning controller-owned default/selection handling. P06 must
perform actual capability/track selection before admitting a request. P04 does
not infer qualities or manufacture media capabilities.

### 2026-10-28 — P05 production playback adapter (implementation in progress)

PlayerControl's stable interface remains source-compatible. `core:media` adds
ProductionPlayback/PlaybackUi as the Android-specific app adapter, and
PlaybackService owns its ExoPlayer/MediaSession. PlaybackApplication exposes a
nullable ready owner so stale service startup cannot initialize media before
provisioning/visible user admission. UI borrows the existing Player for surface
and real-track projection; it never constructs another player.

`open(exact SourceHandle)` accepts a visible selection and navigates to resolving
presentation; later source-resolution failures are observable typed state, not
synchronous acceptance of actual playback. PlayerRoute retains exact title/source
IDs. Process-restored routes load context only and require an explicit Retry.
P06 receives exact DownloadIntent plus real selected format IDs when available;
empty IDs require P06 default/quality selection rather than fabricated tracks.

PlaybackAdmission invalidates suspended selections on departure/background/lock.
The P08 `lock(Boolean)` hook closes web, pauses/gates native session controls and
preserves the lock across Stop. P08 still must integrate authentication and secure
PiP exit/cover. `observations` exposes successful serialized progress observations;
current PersonalRepository persists ordinary progress, with watched-interval
storage/completion remaining P08. A private fingerprint/source checkpoint adjunct
is written only after successful personal progress. No personal/catalog schema
migration is introduced. Production media uses existing P03 scoped HTTP; explicit
fixtureLoopback injection exists for isolated instrumentation only, default false.

P05 follow-up: PlaybackObservation gains trailing `speed: Float = 1f`, required
finite and positive. Position/playedFrom remain media milliseconds; speed is
supplementary observation context for P08, never multiplied into stored media
positions. Existing callers remain source-compatible and no schema migration is
introduced. Ordinary PersonalStorage ignores watched coverage until P08 implements
its preserving watched-interval integration.
