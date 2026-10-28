# P02 — verified catalog and personal storage

Date: 27 October 2026. Owner: Codex / Sol. **P02: VERIFIED.**

The full catalog builds and provisions on Pixel 6, and persistent personal state
survives process death and catalog replacement. All required P02 checks have
passing evidence. The final application remains a development preview; complete
discovery UI, real source support, playback, transfers, app lock and signing
remain in their assigned later phases.

## Catalog identity and actual artifacts

| Property | Result |
| --- | --- |
| Active generation | `full-20261027-r4` |
| Catalog / personal schema | 1 / 2 |
| Exporter | 1.0.3 |
| Title IDs preserved | 286,207 |
| Original source records preserved | 321,999 |
| Source kinds | 287,504 embed; 33,959 file; 536 HLS |
| Prebuilt database bytes | 814,837,760 |
| Deterministic gzip bytes | 206,446,668 |
| Debug preview APK bytes | 252,877,476 |

Input consistent-backup SHA-256: `fee4ca0926e6f0274eadf0d1e209af5a046ff6255605edfb7038e20a97817721`.
Title-ID digest: `0164b09620dc13f13596d6772dcff84aee52258f9461470fca9896fae61756a9`.
Source-record digest: `680189a215928536697bfd4c3101d641a08f26ea69e5630ef73c2cc80b38b511`.
DB SHA-256: `d71bc284e09b85cdc25413f66733769491824fa9b978534d7712fdc8aa6b1dfa`.
Gzip SHA-256: `d3267316e60e86a9e9405f94c9cecc475b012e6524fc0a3848cfb2605b788d4c`.
APK SHA-256: `7d7bc5a5e47ea6c2316fd5f2d3a41ca369b6b674e04f03dd93dede782475bf7c`.

The live SQLite input and archived SQL gzip were both converted successfully;
their title-ID/source-record digests reconcile. Read-only SQLite backup or staged
SQL restore supplies a consistent input. Desktop personal rows never enter the
output. JSON is authoritative, as in desktop loadFromDb: 22 scalar-column year
and 20 updatedAt differences are explicitly audited, and original JSON is retained.
Exact IDs and source URL/type/provenance are preserved; no valid title or source
was dropped. Studio alias collisions in this snapshot: zero.

## Storage and query behavior

The catalog has indexed title/source/provider/country/entity relations, stable
keyset paging, explicit FTS5 row mapping, literal-word search and indexed CJK
substring candidates. Unknown metadata remains discoverable. Filters are OR
within a group and AND across groups, with disjunctive distinct-title counts.
Hidden exclusion occurs before rows, limits and counts using a SQL-side subquery.
Query stamps include catalog generation, personal revision and query key.

The separate personal WAL database serializes transactional writes and revision
updates. Favorite, saved, hidden and playback progress are distinct. Retained
snapshots include title/entity/source data when catalog titles disappear. Schema
1→2 migration failure rolls back without deleting user data. Watch-coverage policy,
transfer execution and app-lock behavior remain P08/P06/P07 work. Current ordinary
shelf streams are capped at 120; P08 adds the complete library UI/pagination.

Read connections lease one immutable generation, attach it read-only to personal
readers, enable query_only and hold a consistent snapshot. A serialized writer
never attaches the catalog. Activation drains leases, validates/open-checks the
candidate, syncs its files and the atomic current+previous pointer, and preserves
personal storage. Restart reconciles staging and falls back to the previous
validated generation. Older bundled metadata does not replace a newer compatible
generation. P09 will add signed remote transport and monotonic sequence policy.

First launch shows extraction/verification progress off main. Default clean builds
generate labelled neutral fixture assets; explicit full inputs are verified for
full/debug/release builds. Releases reject missing or fixture inputs. The compressed
APK asset uses the name catalog.bin to avoid AGP's automatic .gz expansion.

## Performance correction

Broad search `classic` matches 157,215 titles. Earlier SQL facet p95 measurements
failed at 1,320 ms, 805 ms, 653 ms and 594 ms. No target was relaxed. Host-only
speed improvements did not establish Android performance, so candidates were
measured on the phone.

The final path uses bounded, generation-local membership masks for broad shared
scopes. Empty/small scopes use SQL directly, avoiding reading all masks and excess
allocation. Masks are optional, limited to 32 MiB total and two million row IDs;
older/oversized catalogs retain SQL. Integer membership and Unknown options preserve
multivalued facets. Counting reads little-endian words directly without allocating
a second bitmap for each option. A full-catalog reference comparison matched all
12 scope/hidden combinations, including 2,862 hidden titles. Host tests cover
multistudio and byte/word boundaries; artifact verification checks every bitmap
against its normalized membership.

An initial mask run passed timing but measured 308,503 KiB PSS. Sparse-scope
selection and allocation reduction fixed that issue. The phone test now explicitly
asserts the 250 MiB memory budget as well as all timing budgets.

## Final Pixel 6 evidence

Command: `ANDROID_SERIAL=<selected locally> ./gradlew verifyAndroidTests -PcatalogDir=local-data/catalog-full-r4`.
It completed successfully in 59 seconds. **14 real device checks passed**:
11 data integration cases, one full catalog/UI/performance case and two separate
process-persistence methods, with force-stop between seed and verification.
The neutral test record was cleared. Four earlier persistent-fixture UI/navigation/
rotation tests also passed before the query-only performance revisions.

| Metric | n | p50 ms | p95 ms | max ms | Target |
| --- | ---: | ---: | ---: | ---: | --- |
| Query first page | 30 | 19.49 | 212.68 | 225.87 | p95 ≤250 ms |
| Facet update | 30 | 12.49 | 415.54 | 488.47 | p95 ≤500 ms |
| Details | 27 | 2.21 | 6.92 | 7.07 | p95 ≤250 ms |
| Entity first page | 12 | 15.93 | 26.04 | 26.04 | p95 ≤250 ms |

Instrumented total PSS: **165,041 KiB (161.17 MiB)**, below 250 MiB.
Queries/facets cover 10 cases ×3: empty, broad/rare/no-result/multilingual/accent
search, provider, Unknown country, decade and combined facets. This final run used
a provisioned debug build over phone-hotspot ADB, with mobile data/hotspot kept on.
Power saver was off, brightness setting was 16, external AC power was reported,
and thermal status after the final run was 1 (LIGHT). Refresh readout was not
resolved to a reliable Hz value; P10 must record controlled refresh conditions.
The 1,369 ms UI/provision wait is not a cold-start benchmark. Earlier fresh
provision of the current generation took about 31 seconds; earlier full/fixture
provision/search in airplane mode passed before the user prohibited radio changes.
No radio was disabled in the hotspot runs. P10 still owns release-like cold starts,
poster-cache/scroll/thermal journeys and supplemental 16 KiB runtime (this phone
uses 4 KiB pages). These P02 debug figures do not replace those later gates.

Data integration covers Unicode/FTS/CJK, exact sources, Unknown/disjunctive counts,
keyset boundaries, hide/query revision consistency, concurrent reads/writes,
actual read-only attachments, migration rollback, absent-title retention,
low-space/expansion limits, leases, generation swap/restart/rollback and injected
failure boundaries. Personal snapshots are compared semantically, not by file hash.

## Reliable test reporting and host checks

AGP 9.4's connected task returned exit 1 over hotspot ADB despite all 11 test cases
and its protobuf/XML suite status saying PASSED. Direct AndroidJUnitRunner also
passed. The precise upstream false-exit cause remains unconfirmed. The canonical
verifyAndroidTests task now builds the actual APKs and invokes the same real
instrumentation through tools/dev/run-device-tests.py. It validates success status,
completed count and final code, rejecting failures, skipped tests and truncation.
The original connected tasks remain available for upstream diagnosis; no nonzero
command is described as passed. This is a reporting workaround, not a waived gate.

Final host checks passed:

- Full debug + unsigned release assembly, debug lint and **20 JVM tests**.
- **9 exporter tests** and **16 developer-tool tests**, including false-pass guards.
- Gzip/manifest/database identities, integrity, foreign keys and full count reconciliation.
- Exact bitmap/reference comparison for 12 full-catalog scope/visibility cases.
- APK signature, SDK 37 identity, gzip asset bytes and static 16 KiB ZIP/ELF alignment.
- Expected release-input rejection without a validated full catalog was tested.

Host command: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest :app:assembleRelease lintDebug verifyUnitTests -PcatalogDir=local-data/catalog-full-r4`.
It completed in 1m45s. The installed APK hash was checked against the inspected
artifact and the delivered copy. Release signing remains P11; this is a debug APK.

## Required access shutdown and evidence

Testing is finished. Both test-only packages are absent; the restart probe was
cleared, test-owned catalog backups were cleaned up after a valid active catalog
was confirmed, and original screen timeout was restored. Personal data was kept.
As explicitly requested by the user, Android Wireless debugging was disabled,
manual TCP ADB was returned to USB mode and the laptop network session was closed.
Both debug network listeners were confirmed closed. Hotspot routing stayed present
and airplane/Wi-Fi/mobile-data settings matched before/after. USB debugging alone
may remain enabled, but this laptop no longer has a network debugging connection.
Re-enabling phone testing later requires fresh user-authorized setup.

Ignored evidence: artifacts/p02/final-device/ (suite logs, summary, own diagnostic
logcat, conditions-and-cleanup.json, network-access-shutdown.json),
r4-memory-final-phone.log, r4-memory-host-checks.log, r4-exporter-tests.log,
r4-dev-tests.log, r4-bitmap-equivalence.json and r4-final/apk-inspection.json.
Raw databases, APKs, device endpoints/identifiers and local config stay ignored.
No desktop source was modified, no commit/PR was requested, and the pre-existing
root README change was preserved.

Next available phases: **P03 source feasibility** and **P04 discovery UI**.
