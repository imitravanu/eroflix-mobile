# P06 durable downloads — implementation handoff

Status: implemented source, full host gate and final isolation rebuild passed; **not verified**. The neutral progressive production-device test passed (v4, 1/1); broader acceptance remains pending. No device/network settings were changed.

## Ownership and persistence

`ProductionDownloads` owns one Media3 DownloadManager, DownloadIndex, StandaloneDatabaseProvider and NoOpCacheEvictor SimpleCache under private `files/downloads`. Cache keys are namespaced by stable exact-source/selected-stream job identity. This cache is independent of browsing and is not a directory of standalone export files. `ProductionDownloadService` provides bounded dataSync foreground ownership; `DownloadRecoveryJob` schedules eligible persisted recovery. Both permissions required by this library are declared in its manifest and merged with app permissions.

The existing personal `download_intents` table stores a versioned source snapshot, retained title, actual typed period/group/track choices, actual DownloadHelper stream keys, ordering and desired RUN/PAUSE_USER/PAUSE_SYSTEM/REMOVE. No schema or destructive migration was needed. Media3 index supplies actual byte progress/state. New records precede manager requests so reconciliation can repair an interrupted enqueue. Explicit user pause and pause-all are durable; delayed resolver results are fenced by per-job epochs. Missing-intent index entries are removed conservatively.

## Entry points and policy

Details and player Download enter the same fresh DOWNLOAD resolution and DownloadHelper selection flow. The old flat Format.id set is advisory only: real typed group/period selection produces actual stream keys, and progressive content presents Source quality. WebEmbed/unsupported results explain why downloading is unavailable. Selection does not start playback. Queue controls expose one/all pause, resume, remove, byte progress, 1–4 concurrency (default two), and persisted Wi-Fi-only policy. Active playback limits transfers to one.

The policy requires a validated active network and actual Wi-Fi transport by default. Unmetered cellular does not qualify. A VPN exposing Wi-Fi capability qualifies; an unknown VPN-only underlying transport fails closed. Every upstream open/read also requires active foreground ownership and policy admission. Permission denial/background-start restriction/timeout yields an actionable stop rather than assumed unlimited execution. Reboot/force-stop scheduling behavior still needs device evidence; Android user force-stop requires explicit reopening.

Storage uses 64-bit arithmetic, remaining reservations and a 1 GiB floor; unknown-size admission reserves 512 MiB with bounded transfer size. Progressive partial reuse requires strong equal validator/length/range/fingerprint. Changed resources and partial HLS are discarded before restart; cached resource validators guard partial append. This favors safe restart over claiming seamless HLS expiry recovery.

Completed offline playback uses stored DownloadRequest/stream keys and a CacheDataSource without upstream or writer, through the existing single playback service. Removal is blocked while the same source is attached to playback. Catalog loss cannot remove retained source/title metadata.

## Checks and next evidence

Commands/results are recorded in `docs/reports/DOWNLOAD_RECOVERY.md`. The controlled `ProductionDownloadsDeviceTest` is opt-in instrumentation using neutral loopback fixtures, an isolated cache/index/store and temporary owner substitution; it never clears production personal/catalog databases. It covers partial transfer, persisted user pause, owner/index reopening, resume, dedup, actual offline video/audio decode with its fixture server interrupted, and remove. The real PersonalStorage reopen test covers durable typed choices/5 GiB metadata and preservation of favorite state. The production download instrumentation passed on Pixel in v4; the new PersonalStorage instrumentation method remains unrun.

Pending acceptance: actual controlled device suite; HLS TS/fMP4 and multi-track offline playback; two simultaneous jobs/FIFO; process kill/reboot/task stop/notification denial/FGS timeout; expiry/changed-ETag/low-space runtime matrix; VPN transport on hardware. No airplane-mode test is authorized. A fixture server outage is the permitted isolated interruption. Missing-cache playback errors and offline resume UX need device inspection.

P07 must add explicit export leases (currently playback removal guard only), export/staging/update reservation coordination, validated standalone remux and SAF lifecycle. Download cache must never be copied/renamed as MP4. Downloads currently reserve against other download intents, not future export/update staging. These are outstanding cross-phase hooks, not verified capabilities.
