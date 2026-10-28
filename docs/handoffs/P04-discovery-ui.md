# P04 discovery UI — implementation handoff

Owner: Sol implementation / Codex integration review. 2026-10-28.
Status: IMPLEMENTED; coordinator review and automated Pixel checks pass;
focused manual accessibility/gesture acceptance remains. No
commit, push, publication or device mutation was performed by the implementer.
P00–P03 statuses are unchanged. Pixel remote access must remain enabled under
the current user instruction; this phase does not authorize its shutdown.

## Delivered behavior and ownership

`feature/catalog/` is now a deliberate Android/Compose module. It owns
`BrowseViewModel`, `BrowseState`, `CatalogScreen`, `TitleCard` and `CatalogPoster`.
`settings.gradle.kts` and `app/build.gradle.kts` wire it using the existing pinned
dependencies. `feature/catalog/gradle.lockfile` locks the resolved configurations;
no library/toolchain version was upgraded. The app package keeps ViewModel/state
typealiases for foundation source compatibility.

Each Browse/Entity navigation entry has its own SavedStateHandle and model.
Search, providers, country, decade, studio, duration, sort, density, anchor ID,
index/offset and window-start cursor survive recreation. Country/decade entity
routes retain explicit kind/key. Search edits debounce 250 ms and cancel obsolete
work; results, facets and counts must share the expected generation/query/revision.
Loading replaces stale pages immediately on invalidation. Generation/query changes
reset cursors. A personal-only revision preserves the stable keyset location in
the same immutable generation, with a fresh revision stamp, and restores the
visible stable title ID where it remains visible.

Pages use the repository's 60-item default and exact keyset cursors. Loaded title
state is bounded to 600 entries. Explicit Load more/append-error retry can browse
past that window; removed heads advance a saved window-start cursor. Activity
recreation reloads only that bounded window, seeking the saved title/offset.
Restoration stops on append errors instead of retrying indefinitely. Stable grid
keys preserve position across head removals; the UI does not double-subtract the
removed index after Compose applies stable-key anchoring. Top clears the window
cursor and returns to the first page. Density choices use adaptive minimum card
widths of 110/150/220 dp. Sorting uses CatalogSort directly.

Left Providers and right Filters use one modal panel state, scrim dismissal,
platform Dialog focus/Back behavior, persistent visible buttons and 48 dp in-content
hold/tap handles with long-press haptic feedback. No system-edge gesture exclusion
or whole-edge touch interception is installed. In short windows the search and
both drawer buttons share a compact row; sort/density/Top are in the scrollable
drawer. The drawer body, explanatory text, reset and duration choices scroll.
IME/safe-drawing insets are applied. Predictive-Back cancellation and actual
TalkBack focus return remain physical checks, not host claims.

Facets come from CatalogRepository and preserve OR within / AND across groups.
A deliberate provider toggle checks compatibility against actual provider/search/
entity/hidden scope, preserves positive compatible optional selections and
visibly reports groups/counts cleared. Restored zero-count selections are retained
and removable; a zero intersection has a visible explanation/reset. Failed
compatibility validation preserves selections. Unknown duration is SQL NULL,
never a fabricated metadata value. Short means strictly under 30 minutes;
30 minutes belongs to the long range. Missing known durations are explained.

`CatalogPoster` limits public HTTPS requests to two, each to 4 MiB with 5-second
connect/read timeouts, no automatic redirects and no source credentials. Decode
size follows measured card width (120–720 pixel buckets), with sampled decoding,
input-dimension limits and a 16 MiB memory LRU. Missing, HTTP, unsupported,
redirected or failed posters use decorative placeholders with accessible title
card labels. There is no persisted poster cache: cold offline use retains title
placeholders. Poster assets are not inferred from source/provider capabilities.

`app/Shell.kt` owns composition and typed navigation. Details show poster, nullable
metadata, description, exact source/provenance fields, real entity links, country/
decade scopes, and transactional Favorite/Saved/Hide/Unhide results/errors. Details
reload on generation replacement and observe PersonalRepository state. Entity
screens reuse the same paging/facet state with an immutable EntityRef. Series
pages explicitly state title sort is not verified episode order.

## Shared boundary changes

`core/model/Catalog.kt` adds default-compatible `metadata()` and `count(query)`
methods plus CatalogMetadata/CatalogCount. Production `CatalogStorage` implements
metadata from leased active catalog_meta and matching count from the identical
hidden/filter/entity predicate as paging. Total metadata count deliberately
includes hidden records; query count excludes them. No schema/migration changed.

DurationFilter gains trailing default `onlyUnknown=false`, and query keys include
it. SQL and neutral fakes implement NULL-only semantics directly, without numeric
sentinels. DATA.md/API_V1.md record the dated semantics/cursor compatibility change.
Tests cover key distinction, SQL predicate, filtered/hidden/entity counts and
leased generation stamps. `core/testing` reports explicit neutral metadata/counts.

Play events carry the exact TitleSummary/SourceHandle to the app callback and
`PlayerControl.open(source)`. The route retains both title ID and source ID after
controller acceptance. Download delegates exact `DownloadIntent(title, source,
TrackSelection(emptySet()))` to the app's DownloadController. Empty selection is
a request for controller-owned default/selection handling, not invented quality
metadata. Pending controller errors are displayed next to source actions. No
player, resolver, download manager, cache or export engine is created by P04.
The optional P03 source lab remains separate.

## Verification

Pinned wrapper, JDK17, numeric API37 and full catalog were used. One Gradle
process ran at a time. Initial compile passed; an intermediate full host gate
passed in 5m42s. Final gate and APK inspection results are appended below once
completed. Commands from mobile/:

```sh
./tools/dev/with-toolchain.sh ./gradlew :app:compileDebugKotlin -PcatalogDir=local-data/catalog-full-r4 --write-locks
./tools/dev/with-toolchain.sh ./gradlew verifyUnitTests :app:assembleDebug :app:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest :app:assembleRelease lint -PcatalogDir=local-data/catalog-full-r4 --write-locks
python3 tools/dev/inspect-apk.py --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p04/apk-final
python3 tools/dev/inspect-apk.py --variant release --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p04/apk-final
```

Host logs: ignored artifacts/p04/compile.log, host-gate.log,
final-host-gate.log. Unit XML: 62 cases, zero failures/errors/skips at final JVM
gate (app9, model8, data7, testing5, network33). The seven new paged-state cases
cover deep-window recreation/offset/memory bound, personal revision/hidden anchor,
restoration append error/retry, search cancellation/generation, obsolete stamps,
provider compatibility/zero removal and typed filter/entity restoration. Existing
two Browse tests remain. Model/SQL additions cover Unknown-only key/predicate.

`DataIntegrationTest.discoveryMetadataAndFilteredCountsUseLeasedGenerationAndPersonalRevision`
compiles into the data test APK; it uses disposable isolated fixture storage and
checks total metadata, matching count, entity/filter/Unknown scopes, hidden count
and activation generation. Coordinator ran this exact method on Pixel: **PASS 1/1**. Evidence:
artifacts/p04/data-device/summary.json and instrumentation.log. Data test APK
SHA-256: ac4e880a2d977b49aa3f122a07eb1a504e1c9a54b1c57015b2072f98b0479ff9.
The isolated test package was removed, network flags were unchanged and remote
ADB was retained. Production catalog/personal databases were not replaced.

`DiscoveryDeviceTest` compiles into the full-catalog app test APK, does not skip
based on fixture mode and does not replace production personal/catalog databases.
Its five methods cover search→details→entity→Back/recreate with query-specific readiness and
visible-title assertions; drawers/Back/zero reset; exact-source pending-controller
explanations; paging/landscape/recreation at the active font scale; and a 12-page/720-title
journey that drops old pages, retains at most 600 titles and verifies a visible
stable title after details/Back and activity recreation. It logs
sanitized EroflixP04 layout/font information. It performs no personal writes or
provider playback/download. Compilation is not device acceptance.

## Coordinated physical gate and remaining checks

Coordinator owns tools/dev/run-discovery-tests.py and all phone work. The final
APKs must be used, selected by actual Pixel6/oriole/API37 endpoint; never select
the unrelated Realme. Suggested coordinator commands, **not yet run here**:

```sh
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-discovery-tests.py --font-scale 1.0 --output-dir artifacts/p04/pixel-font1
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-discovery-tests.py --font-scale 1.3 --output-dir artifacts/p04/pixel-font13
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-discovery-tests.py --font-scale 2.0 --output-dir artifacts/p04/pixel-font2
```

The isolated data count/metadata method has passed as recorded above.
Manual/physical checks remain: portrait and landscape at 1/1.3/2 font scale;
narrow and actual split-window width; TalkBack announcements/focus return;
48 dp reachability; IME and safe insets; gesture and three-button navigation;
commit/cancel predictive Back; long-hold haptic affordances; loaded-window
continuity on actual grid layouts; and full-catalog query/frame/memory measurements.
Host cancellation/generation tests do not replace actual generation replacement
while a Pixel query is active. P08 polishes interactions; P10 owns release-like
performance acceptance. No physical acceptance waiver is recorded.

After each batch stop test processes, remove only disposable test data and restore
temporary font/rotation/timeout settings. Never change airplane mode, hotspot,
Wi-Fi or mobile data. Retain Pixel remote debugging across all phases per user.

## P05/P06 follow-through

P05 replaces the pending PlayerControl and player presentation with one app-owned
engine/session; consume the selected exact source ID/handle and retain return-route
state. Resolution/capability failures must continue to surface; do not create a
second engine from discovery. P06 replaces the pending DownloadController, resolves
for DOWNLOAD, performs real capability/track selection before admission, and maps
both entry points to the same idempotent durable queue. It must define the empty
TrackSelection default/quality-picker behavior. P07 continues to own export.

P04 remains IMPLEMENTED until coordinator review and required Pixel evidence.

### Coordinator checkpoint — 2026-10-28

Sol stopped at an account usage limit after saving the implementation and passing
host gates. The coordinator continues validation; no unreviewed second writer
is active. Final production debug/test/release/lint build passed in 4m20s
(deep-window-final-build.log). Exact debug/release catalog inspections pass in
apk-final/. All 62 JVM cases pass. The first physical UI batch passed its first
four tests; its long deep-window test exposed a legacy Compose test dispatcher
resuming composition on an I/O worker. The batch was stopped, recorded as failed,
and its settings restored. The test now uses the v2 API already present in the
pinned Compose1.12.1 and exact lazy-grid index scrolling for known page boundaries;
no assertion or dependency pin is removed. Final rerun pending. See Android's
[testing migration guidance](https://developer.android.com/develop/ui/compose/testing/migrate-v2).

### Final coordinator evidence — 2026-10-28

The test thread correction uses `androidx.compose.ui.test.junit4.v2` from the
already-pinned1.12.1 (queued composition dispatcher). Known grid page boundaries
use exact index scrolling; the test still clicks the actual Load more action and
asserts page advancement/visible-title restoration. This removes legacy test
thread failures and unbounded row-scanning at large font sizes without changing
production code or weakening assertions. Failed attempts remain in pixel-font1/
and pixel-font2/; no pass is inferred from them.

| Check | Actual result | Ignored artifacts/p04/ evidence |
| --- | --- | --- |
| Full debug/test/data-test/release + lint | PASS, final production build4m20s; test-only final build38s | deep-window-final-build.log, font-test-index-build.log |
| JVM / helper cases | 62 /18 PASS, zero failures/errors/skips | jvm-summary.json, helper-tests-final.log |
| Exact full debug/release APK inspection | PASS,286207 titles,full-20261027-r4; lab excluded from release | apk-final/ and apk-final/release/ |
| Full Pixel UI suite, normal text | 5/5 PASS; details/entity/Back/recreation, drawers/reset, exact-source messages, landscape/paging, deep window | pixel-font1-v2/ |
| Font1.3 /2.0 layout checks | 1/1 each PASS; portrait→landscape→recreation/drawer reachability | pixel-font13/, pixel-font2-v2/ |
| Isolated data metadata/count/generation check | 1/1 PASS; personal/catalog app files untouched | data-device/ |
| Full catalog timing/memory | PASS;30 query/facet cases,27 details,12 entity cases | pixel-catalog-performance/ |

Full-catalog query p95=216.81ms, facets p95=414.20ms, details p95=5.20ms,
entity p95=25.81ms, PSS=202260KiB (197.52MiB). Deep-window test visits720 titles,
retains at most600, restores the chosen visible title through details/Back/recreate
and records197525KiB (192.90MiB) PSS. These are debug phone measurements, not
release frame/cold-start benchmarks. Poster memory LRU is16MiB.

All phone runs used production app SHA256
`c6f1acfa3753f360723a3597ed9a072325e358af7b805f55e6d00d56a8e474d4`.
The normal UI/1.3 test APK was
`67a427cff37fd7defb83dade570206eeb528b90edb654b7c4bf9987a237f38a3`;
a final test-only index-scroll correction used test APK
`2a37550e2991172f257b481ec45f3b52141bc89d64aed2359585878fd01ee1c5`
for the2.0 layout and full catalog performance runs. Each summary preserves the
actual test hash. Release unsigned SHA256 is
`ad99c9c8052a23d9b12b7a6067f49bbb78f903c4dbf7b43ed9ac632e983f2f76`.

Actual commands (with authorized Pixel explicitly selected):

```sh
python3 tools/dev/run-discovery-tests.py --font-scale 1.0 --output-dir artifacts/p04/pixel-font1-v2
python3 tools/dev/run-discovery-tests.py --font-scale 1.3 --test-class 'tech.imitravanu.eroflix.DiscoveryDeviceTest#currentFontScalePortraitLandscapeRecreationAndPagingRemainReachable' --output-dir artifacts/p04/pixel-font13
python3 tools/dev/run-discovery-tests.py --font-scale 2.0 --test-class 'tech.imitravanu.eroflix.DiscoveryDeviceTest#currentFontScalePortraitLandscapeRecreationAndPagingRemainReachable' --output-dir artifacts/p04/pixel-font2-v2
python3 tools/dev/run-discovery-tests.py --font-scale 1.0 --test-class tech.imitravanu.eroflix.FullCatalogDeviceTest --output-dir artifacts/p04/pixel-catalog-performance
```

The runner requires API37/oriole/full catalog before mutations, verifies exact
installed hashes, retains all results, restores font/rotation/timeout and checks
network flags. It removes the test APK and stops the own test/app process. A
wrong-target regression proves it cannot install/change settings on Realme.
Final retained-access-cleanup.json confirms both test packages gone, no own active
audio, own app stopped at closeout, personal DB/catalog present, original font1.0,
rotation0/0 and timeout1800000ms restored; airplane0/WiFi1/data1 unchanged. Network
ADB5555 remains active under current user instruction.

P04 remains IMPLEMENTED. Automated layout/state/Back dispatch checks do not prove
TalkBack spoken/focus behavior, actual split-screen/narrow windows, both navigation
modes, predictive-Back cancellation, or perceived haptic/hold affordance. Those
focused physical checks remain listed for P04/P08/P10; no gate is silently waived.
The deliverable discovery preview APK is copied to this chat's outputs without
overwriting the earlier P02 artifact. Production player/download/export remain
P05/P06/P07. A detailed P05 Sol prompt is prepared, not dispatched after Sol's
usage-limit interruption.

After the cleanup checkpoint, the normal MainActivity preview was opened on the
Pixel for the user. This is normal app use; no instrumentation/test package or
media lab process was left running. The remote connection remains enabled.

## P05 integration follow-up — subscription ownership (pending rebuilt checks)

P05 progress writes revealed that discovery's eager ViewModel collection could
requery pages/facets every personal revision even while the player route was
visible. The coordinator authorized a targeted presentation fix: discovery state
now uses WhileSubscribed with immediate stop/replay reset; only an active
lifecycle UI subscription admits repository collection. Unsubscribe cancels append
and normalizes Loading. Late completion also requires an active observing token.
Resume begins Loading and queries the latest generation/revision while restoring
the same-generation saved keyset window and stable anchor. Generation replacement
still resets stale cursors. Tests now establish real subscription lifetimes and
add zero-query/facet background-progress checks plus latest-stamp restoration.
This does not change repository revisions, SQLite schemas or visibility semantics.
These source changes require the next coordinated host/device gate; they are not
part of the already-tested P04 APK hashes above.
