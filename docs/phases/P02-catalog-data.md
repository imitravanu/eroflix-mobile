# P02 — Catalog production pipeline and personal data

**Role:** Data/storage agent
**Prerequisites:** P01 model contracts and build
**Owns:** `tools/catalog/`, `core/data/`, SQL schema/migrations, data fixtures,
catalog generated-asset integration through the foundation owner
**Requirements:** R02–R05, R07, R19, R20, R23

## Goal

Transform the real desktop catalog into a correct, indexed offline phone
catalog, and prove personal-state preservation across local generations.

## Read first

[Catalog audit](../CATALOG_AUDIT.md), [data contract](../contracts/DATA.md),
[architecture](../ARCHITECTURE.md), [validation](../VALIDATION.md), P01 handoff,
and the desktop source files listed in the audit.

## Work packages

### P02.01 — Consistent inputs and deterministic exporter

- Implement the export/verify CLI contract with Python standard-library SQLite
  or another documented host tool if evidence requires it.
- Make a SQLite read-only backup into ignored staging, then stream catalog rows.
  Support the existing SQL gzip as an alternate input restored only in staging.
- Preserve exact title IDs, timestamps, original source URLs/types and provenance.
- Normalize provider/country/tag/entity relations; report unresolved metadata
  and alias collisions. Retain Unknown values and every valid source/title.
- Produce deterministic prebuilt `catalog.db.gz`, manifest, and counts/hash audit.
- Enforce that no desktop personal-state rows appear in the output.

### P02.02 — Physical schema, indexes and search

- Implement versioned DDL covering DATA's title/source/entity/search relations.
- Build FTS5 with explicit title row mapping; demonstrate bundled-driver FTS5
  support and read-only attachment on Android 17.
- Add indexed sort/tiebreaker/paging strategies and entity reverse lookups.
- Define tested normalization/query behavior for punctuation, Unicode and CJK.
- Implement disjunctive facet counts, OR-within/AND-across groups and Unknown.
- Inspect query plans and benchmark real-size data; avoid loading all titles
  or hidden IDs into a UI-side collection.

### P02.03 — Personal state and observable queries

- Add `personal.db` schema, writer serialization and migrations with explicit
  millisecond units, favorite/saved/hidden/progress fields and retained snapshots.
- Implement catalog/personal repositories with bounded paging and cancellation.
- Apply hidden exclusion before rows/counts/limit in every discovery query.
- Invalidate using catalog generation + personal revision + query key; prevent
  an earlier search from overwriting a newer request.
- Preserve absent-title personal rows and support retained library metadata.
- Reserve download/export intent schema fields with P06/P07 through model
  contracts; those phases own their transfer behavior.

### P02.04 — Asset provisioning and local generation activation

- Add fixture and full-catalog build-input wiring. Full release builds require
  the validated full catalog; fixture inputs stay explicitly labeled.
- First launch displays extraction/verification progress from bundled metadata;
  staging is recoverable after cancellation, crash or no space.
- Implement generation leases, read-only opening, durable pointer activation
  and local rollback according to DATA.
- Keep at least the last validated catalog until the new generation opens;
  close and recreate only catalog-related query readers.
- Expose stage/validate/activate APIs for P09's later remote update transport.

### P02.05 — Data evidence

- Verify fixture count/identity/source reconciliation, Unicode search, facet
  correctness, hidden rows, pagination boundaries and ordering.
- Test personal migration rollback/failure and concurrent hide/query behavior.
- Inject failures during local stage/verify/activation and compare semantic
  personal state before/after; do not compare live SQLite file bytes as a proxy.
- Run full-catalog phone queries and record actual compressed/uncompressed size,
  first-install time, p50/p95/max query timings and memory.

## Acceptance

- [x] Export/verify commands succeed from consistent inputs; counts reconcile.
- [x] Current 286,207-title snapshot preserves all IDs, with any later count
  difference explained by a dated input audit.
- [x] A fresh fixture/full APK provisions and searches in airplane mode (V01).
- [x] Query/filter/entity results, unknown values and hidden counts pass V02–V04.
- [x] Android bundled SQLite, FTS, migrations, attachments and concurrent reads
  pass actual Android integration tests.
- [x] Local generation swap and failure injection preserve personal state (V24/V25).
- [x] Pixel 6 query/facet/memory budgets pass or remain explicitly pending.

## Evidence and handoff

Create `docs/reports/CATALOG_BUILD.md` and `docs/handoffs/P02-catalog-data.md`.
Include exporter commands, input digest, counts, actual sizes, schema/API version,
test evidence and pending physical checks. Next: **P04.01** and data integration
for **P05** once P03 is ready.


Current state: **VERIFIED**. Final full host checks and 14 physical checks passed.
Query/facet/details/entity p95 and the explicit 250 MiB memory check pass on Pixel 6.
Remote debugging access was disabled afterward while preserving hotspot/internet.
See the [build report](../reports/CATALOG_BUILD.md) and
[handoff](../handoffs/P02-catalog-data.md) for exact evidence and P10 boundaries.
