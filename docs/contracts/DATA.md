# Data contract v1

**Owners:** P01 declares interfaces; P02 implements storage/export; P09 owns
update orchestration. Shared changes require a recorded contract revision.

This is a schema/interface contract, not a claim that the database already
exists. P02 commits the actual DDL, migrations, fixtures, and verification tools.

## Stable identities and units

- `TitleId` is the existing `movies.id` string copied unchanged. Do not recreate
  it from display text or merge records by similar names in the mobile exporter.
- Numeric row IDs inside one catalog generation are private implementation
  details. Personal records, routes, cache intent and handoffs use `TitleId`.
- `SourceId` must be reproducible across identical exports. Use a documented
  deterministic identity from title ID, original provider, kind and exact
  original URL, with a collision check. Persist the original handle in download
  metadata; signed URL changes may legitimately produce a new source ID.
- Preserve external/provider identifiers and origin/provenance strings.
- Timestamp fields are UTC epoch **milliseconds**. Playback position/duration
  are **milliseconds**; catalog duration is explicitly named `duration_ms`.
- Missing metadata is SQL NULL / typed `Unknown`, never fabricated zero-year,
  duration, country or performer information.
- Count titles with distinct title identity when a filter joins multiple sources
  or entities. A source count is labeled separately.

## Catalog schema requirements

| Table | Required identity / fields |
| --- | --- |
| `catalog_meta` | format/schema version, generation ID, build timestamp, exporter version, source snapshot digest, record counts |
| `titles` | `row_id INTEGER PRIMARY KEY`, `id TEXT UNIQUE NOT NULL`, title, normalized sort key, year, duration_ms, description, poster/backdrop URL, created/updated timestamps, license/provenance |
| `providers` | stable provider key and display label, explicit unknown mapping |
| `title_providers` | title row/provider key relation, including metadata-only records |
| `sources` | source ID, title row ID, catalog provider key, external ID, original URL, stored kind, label/quality hint, adapter/header-policy key, provenance |
| `countries`, `title_countries` | normalized known country identities and title relations; unknown represented deliberately |
| `performers`, `title_performers` | stable tag slug IDs, best-available label, optional verified image/provenance |
| `studios`, `title_studios` | stable identity with alias mapping for studio string and `studio:` tag |
| `series`, `title_series` | stable tag slug ID, display label, nullable verified part/sequence value |
| `tags`, `title_tags` | preserve generic tags and their typed origins |
| `search_documents`, `titles_fts` | explicit row-ID mapping to titles; indexed title/entity/tag/description text |

Foreign keys inside a catalog are allowed and validated at build time. They
must not reference the personal database. P02 must define ordered indexes for
supported sorts and both lookup directions of entity joins; inspect plans for
provider + country + decade + hidden-exclusion queries.

### FTS mapping

Use a generation-local integer row ID shared by `titles`, `search_documents`,
and the FTS index. For example, the implementation can build:

```sql
CREATE TABLE search_documents (
  row_id INTEGER PRIMARY KEY REFERENCES titles(row_id),
  title TEXT NOT NULL,
  entity_text TEXT NOT NULL,
  description TEXT NOT NULL
);
CREATE VIRTUAL TABLE titles_fts USING fts5(
  title, entity_text, description,
  content='search_documents', content_rowid='row_id',
  tokenize='unicode61'
);
```

Populate/rebuild FTS in the exporter and verify MATCH results against expected
title IDs. The catalog is immutable at runtime, so there are no runtime FTS
maintenance triggers. Test punctuation, accents, empty input, user-entered FTS
operators, long text, and CJK queries. Unicode61 alone does not promise CJK
substring search; P02 must document and test a bounded n-gram/fallback strategy
for the product's multilingual search requirements.

## Conversion from the desktop database

1. Open the source read-only and take a consistent SQLite backup into ignored
   `local-data/`. A raw copy of a live WAL database is not a consistent snapshot.
2. Stream `movies` and parse `data`; reconcile key columns and JSON identity.
3. Preserve every valid title. Quarantine/report malformed records and fail
   publication if counts/identity do not reconcile; never quietly drop rows.
4. Convert every source. Desktop `file` maps to progressive media, not necessarily
   MP4. `embed` remains an embed handle until a resolver proves more capability.
5. Derive entities from `star:`, `studio:`, `series:` and explicit studio values;
   report ambiguous aliases. Do not invent avatars or series ordering.
6. Build the indexes, FTS documents, statistics and metadata. Verify source
   cardinality, foreign keys, integrity and representative queries.
7. Produce a closed, checkpoint-free prebuilt DB and deterministic gzip, plus
   a manifest with compressed and uncompressed sizes and SHA-256 hashes.
8. Confirm the output contains no desktop `user_state` records. Production APK
   asset assembly consumes only the validated output.

Reproducibility means identical output for the same consistent input, exporter/
schema version and explicit generation/build metadata. Fix ordering, gzip mtime
and build timestamp (for example via `SOURCE_DATE_EPOCH`); record these inputs
instead of embedding the current wall clock in an otherwise deterministic build.

The production asset is a compressed **prebuilt DB**, not SQL text. The build
task takes an explicit local input path, verifies it, and copies it into generated
assets. Small fixture builds work from a clean checkout; production build without
the full catalog must fail with a useful input error rather than ship a fixture.

Proposed host CLI, implemented in P02:

```sh
python3 tools/catalog/export_catalog.py --source-db ../data/eroflix.db --output-dir local-data/catalog
python3 tools/catalog/verify_catalog.py --artifact-dir local-data/catalog
python3 -m unittest discover -s tools/catalog/tests
```

Support restoring `../export/catalog.sql.gz` into ignored staging as an alternate
build input when the live DB is unavailable. Only conversion outputs become assets.

## Personal schema requirements

| Table/domain | Required information |
| --- | --- |
| `user_state` | title ID, favorite, saved-for-later, hidden/hidden_at, position/duration, last watched, watched state |
| `watched_intervals` (P08) | title/timeline/watch-cycle identity, merged played ranges in milliseconds, auto-hide suppression for the current cycle |
| `retained_titles` | title ID, title/year and entity/source snapshot sufficient for library/offline display; local poster reference if retained |
| `download_intents` | job ID, Media3 request ID, title/source handle snapshot, selected tracks, enqueue ordering, persistent user stop reason and desired state |
| `export_jobs` | export ID, download ID, destination URI, MIME/container choice, staging reference, state/error, timestamps |
| `source_observations` | optional local observed capability/duration/expiry and bounded diagnostic data; not authoritative catalog metadata |
| `personal_meta` | schema version and durable data-revision marker |

Saved-for-later/bookmark is distinct from favorite and from playback progress.
Retain metadata when an item first acquires personal state or a download job.
Remote poster URLs alone do not guarantee offline posters; store a thumbnail
when available and always render a placeholder if it is absent.

Settings such as Wi-Fi-only, lock policy, auto-hide, and download concurrency
may live in DataStore. Their persistence and upgrade behavior are tested too.

### Watch progress and visibility

P05 emits playback observations and discontinuities; P08 owns the watched-
coverage policy and its versioned storage integration with the data owner.

- Resume position and watched coverage are separate values. Count only media
  intervals actually advanced while playing; exclude buffering, seek jumps,
  restored positions and incompatible source timelines. Playback speed changes
  affect elapsed media time but do not make a jump into watched content.
- Merge overlapping played intervals across sessions for a compatible finite
  timeline; replayed intervals count once. Bound storage without filling unwatched
  gaps. Unknown/live duration cannot trigger percentage-based auto-hide.
- With auto-hide enabled, transition once when unique coverage / known duration
  is strictly greater than 0.90. Persist the hidden marker and watch-cycle state
  transactionally. Exactly 90% does not trigger it.
- Manual Unhide/Undo/Unhide all suppresses further auto-hide in the current
  watch cycle. An explicit Replay-from-start action starts a fresh cycle; normal
  pause/resume or seeking does not reset it. Favorite/saved/history state remains.
- Hidden titles disappear from browse/search/facets/entities and ordinary
  favorites/saved/history/continue-watching shelves. Hidden manager and Downloads
  retain explicit management access, including retained metadata for absent titles.

Tests cover natural play, seek-only progress, restart, duration change, replay,
manual unhide and process death. This defines the blueprint's “watched >90%”
behavior without equating a near-end seek with having watched the title.

### Writer/read connection contract

- One serialized personal writer; transactions update state and revision together.
- Bounded leased read connections open personal data and attach one catalog
  generation read-only using supported SQLite URI/open flags.
- `PRAGMA query_only=ON` on read connections; `foreign_keys=ON` where relevant;
  configured busy timeout and deterministic close/cancellation behavior.
- A statement/connection is never concurrently used by unrelated coroutines.
- A catalog generation change recreates attached readers after their leases
  finish; no writer or media cache handle is closed by catalog activation.
- Test WAL/concurrent-read behavior with the selected bundled driver on-device.

## Query and facet semantics

`CatalogQuery` contains search text, provider set, country set, decade set,
studio set, performer/series scope, duration bounds/unknown choice, sort,
page cursor, and limit. Default page size 60, hard cap 120. Prefer indexed
keyset cursors with stable ID tiebreakers; invalidate cursors when query or
generation changes.

1. OR within one facet; AND across facets. Search and entity scope also apply.
2. Exclude hidden rows using a SQL anti-join / `NOT EXISTS` before LIMIT/counts.
3. Compute each facet's counts with all constraints **except that facet's own
   selections** (disjunctive faceting), preserving search/provider scope as
   appropriate. Count distinct title IDs.
4. Keep selected options visible even when their count becomes zero; allow
   removal. Do not trap a user by hiding the active selection.
5. A provider change retains compatible filters and reports any filters cleared
   because they became incompatible. Restored zero-result state gets a visible
   reset action; a zero-dead-end slogan is not a data invariant.
6. Always offer Unknown when the scoped data contains missing values. Duration
   controls explain absence of known durations in the current catalog.
7. Return results tagged with query key, personal revision and catalog generation.
   Cancel obsolete work and reject stale completion before rendering.

Example hidden exclusion shape (actual schema qualification follows P02):

```sql
SELECT t.id, t.title
FROM catalog.titles AS t
WHERE NOT EXISTS (
  SELECT 1 FROM main.user_state AS u
  WHERE u.title_id = t.id AND u.hidden = 1
)
ORDER BY t.sort_title, t.id
LIMIT ?;
```

Use an identical visibility predicate across search, browse, facets, entity
pages, discovery shelves and ordinary library lists. Hidden manager opts into hidden rows.
Downloads remain manageable independently of discovery visibility.

## Repository interface responsibilities

P01 declares the typed shapes; P02 provides the implementation and fakes.

| Interface | Responsibilities |
| --- | --- |
| `CatalogRepository` | Paged query, facet counts, details, entity query, active-generation flow |
| `PersonalRepository` | Favorite/saved/hidden writes, progress writes, library/hidden streams, retained snapshots |
| `CatalogGenerationStore` | Stage, validate, activate, reopen, roll back, list/clean old generations |
| `CatalogUpdater` | Check manifest, fetch generation through injected transport, expose progress/result |

Errors distinguish invalid query, canceled query, busy database, missing
generation, corrupt/incompatible catalog, storage full, and migration failure.
UI can retry recoverable errors without clearing personal data.

## Generation manifest and activation

Manifest v1 fields: `formatVersion`, `catalogSchemaVersion`, `generationId`,
`createdAt`, `titleCount`, `sourceCount`, `minAppVersionCode`, artifact URL,
compression type, compressed/uncompressed byte lengths and SHA-256 digests.
P09 adds `channel`, `catalogSequence` (a monotonic integer per channel), `keyId`
and a detached Ed25519 signature verified with an embedded trusted public key.
Sign the exact manifest bytes; do not rely on JSON reserialization ordering.
Publication tooling validates these fields before producing a remote release.

Persist the highest accepted sequence/digest per channel outside the catalog.
Retrying that exact candidate is allowed; the acceptance floor and active
generation are distinct. Lower remote sequences or equal sequences with different
content are rejected, while local known-good recovery can reopen an older catalog
without lowering the remote acceptance floor. On APK upgrade, do not replace a
newer compatible active catalog with the APK's older bundled generation.

Activation protocol:

1. Enforce one updater; reserve space for compressed input, new DB, and rollback.
2. Download/decompress to a new same-filesystem generation directory, enforcing
   declared upper size bounds. Validate hashes, metadata, schema, integrity,
   foreign keys and smoke queries off the UI thread.
3. Close validation handles; sync files. Stop admitting new catalog read leases,
   drain/cancel old queries, and reopen the staged generation successfully.
4. Durably publish a small current-generation pointer via staged file + atomic
   rename and appropriate fsync. Release query admission and invalidate paging.
5. On restart, reconcile incomplete staging/pointer state and fall back to the
   last validated generation if needed. Keep previous data until new open passes.
6. Clean only unleased obsolete catalog generations. Personal databases,
   download/cache storage, exports and retained metadata are outside this path.

P02 proves local activation; P09 adds remote transport/signature/rollback flows.
Test cancellation/crash at each boundary. A two-step file rename alone is not
proof of crash-safe activation.

## P04 discovery additions — 2026-10-28

Catalog metadata/count API additions are recorded in API_V1.md. Total metadata
counts use the active generation's catalog_meta. Matching counts use the exact
query predicate and stamp, including hidden exclusion; neither loads title rows
into an application-wide list. Duration `onlyUnknown=true` means SQL NULL only;
it takes precedence over min/max and includeUnknown. It never means a synthetic
zero duration. The query identity includes this flag; no schema migration.

On an intentional provider toggle, P04 checks country/decade/studio selections
against real facets scoped to the new providers, current search/entity and hidden
visibility, without the other optional filter groups. Values with positive counts
remain; incompatible values are cleared with a visible group/count explanation.
Duration is retained if its stamped scoped count is positive. Failed validation
retains all selections for manual removal/retry. Combined retained groups can
still have zero intersection; the screen explains that state and offers reset.
Restored zero-count selections remain visible and removable rather than being
silently cleared on recreation.

## P06 implementation boundary (2026-10-29)

Download intents now retain a versioned native source/request-policy snapshot, typed period/group/track metadata and actual DownloadHelper stream keys in the existing download_intents columns. Flat format IDs are advisory and never identify a selection on their own. Personal data remains unchanged by migration; retained titles survive queue removal. The durable desired state is independent of Media3 index transfer state. One non-evicting namespaced cache/index belongs to ProductionDownloads; completed playback has no upstream. Export leases and non-download staging reservations are assigned to P07 before concurrent export/removal can be enabled. See P06 handoff for conservative partial-HLS restart and pending runtime evidence.

## P07 implementation boundary (2026-10-29)

Export metadata is persisted in the existing export_jobs table, separate from download completion; new optional job byte counters default to zero/unknown. ProductionDownloads now provides completed-content read leases and shared export-staging reservations. Removal refuses leased content. ProductionExports uses selected-stream cache-only input, private validated MP4 staging, then ContentResolver destination copy and independent reopen/seek validation. Exactly one mediaProcessing worker runs. Process interruption retains input and marks the separate export failed for explicit retry; no stale document is silently overwritten. Encrypted/discontinuous/live/partial HLS and subtitle-selected downloads are currently rejected for export. Encoder creation is prohibited; P07 actual format/runtime evidence remains in EXPORT_FORMATS.md, not inferred from resolver capabilities.
