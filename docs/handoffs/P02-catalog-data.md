# P02 — verified catalog and personal-data handoff

Date: 27 October 2026. Owner/integration owner: Codex / Sol. **State: VERIFIED.**
Existing checkout; pre-existing root README modification preserved. No commit/PR.

P02.01–P02.05 is delivered: consistent exporter/verifier, normalized catalog and
personal schemas, bounded queries/FTS/CJK/disjunctive facets, persistent personal
state/retained metadata, local generation provisioning/activation/recovery and
full/fixture build wiring. See [CATALOG_BUILD](../reports/CATALOG_BUILD.md) for exact
counts/digests/commands, final metrics and measured limits.

## Ownership and contracts

- tools/catalog/: Python standard library, exporter 1.0.3, format/catalog schema 1.
- core/data/: personal schema 2, serialized WAL writer, leased read-only catalog
  attachments, query stamps, local generation lifecycle and bounded facet masks.
- app/: async provisioning and composition; generated full/fixture assets;
  unavailable media/remote-update implementations remain explicit.
- Shared model interfaces and stable title/source algorithms remain v1. See API_V1.md.
- P04 consumes paged catalog/search/facets/details/entity APIs. P05 consumes source
  handles and persistent progress after P03 proves source capability.
- P08 owns watched coverage/auto-hide policy, library UI/pagination and app lock.
  Current shelves are capped at 120; future migrations preserve personal schema 2.
- P09 adds signed transport/sequence policy around local stage/activate APIs;
  catalog lifecycle never owns personal/media storage.

## Verified evidence

Full generation full-20261027-r4 preserves 286,207 titles and 321,999 sources. Both
live DB and archived SQL inputs reconciled identities. Actual artifact sizes and
hashes are in the report. Full builds/lint, 20 JVM tests, 9 exporter tests and 16
helper tests pass. The final canonical verifyAndroidTests command passed 14
physical checks (11 data, 1 full catalog/performance, 2 process-restart methods).
Query p95 is 212.68ms; facet 415.54ms; details 6.92ms; entity 26.04ms; PSS 165,041KiB.
All unchanged P02 budgets pass. The actual test includes the memory assertion.

The AGP 9.4 connected runner false exit is recorded honestly; the canonical
aggregate now uses strict direct AndroidJUnitRunner reporting and retains all
suite failures/budgets. No upstream nonzero run is counted as a pass. Full P10
release-like cold-start/poster-cache/scroll/thermal and 16KiB runtime remain later.

## Access and cleanup

User requested network control be disabled after testing. Completed: Wireless
debugging off, manual TCP ADB returned to USB mode, both debug network listeners
closed, host session disconnected. Hotspot and mobile internet were kept on;
network settings unchanged and temporary display timeout restored. Test packages,
neutral persistence probe and owned catalog backups are cleared. The final debug
preview stays installed; no active network phone-control access remains.

Future phone testing requires fresh user-authorized setup. Do not blindly reuse
old scratch phone helpers: they may contain now-prohibited airplane/radio changes.
Use run-device-tests.py, preserve internet and follow AGENTS.md required cleanup.

Next: P03 sources and/or P04 discovery UI. No next phase was started in this task.
