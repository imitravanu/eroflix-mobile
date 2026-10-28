# P06 — Durable downloads and offline playback

**Role:** Offline/download agent
**Prerequisites:** P04 card/details action UI, P05 integrated media owner and
resolver; P02 personal schema inherited through those phases
**Owns:** Download/cache parts of `core/media/`, `feature/downloads/`, recovery
fixtures; personal schema changes through the data owner
**Requirements:** R09, R14–R16, R18

## Goal

Provide a usable download queue that retains correct intent through interruptions
and plays completed selected tracks without any network request.

## Read first

[Media contract](../contracts/MEDIA.md), [data contract](../contracts/DATA.md),
[validation](../VALIDATION.md), P03/P05 reports, and Android foreground-service
timeout documentation linked in the platform baseline.

## Work packages

### P06.01 — Durable index, cache and intent

- One Media3 DownloadManager/DownloadIndex/database provider and download
  SimpleCache per process; persistent downloads use NoOpCacheEvictor.
- Record durable download intent/ordering/source and retained title metadata
  in personal data; reconcile actual transfer state from Media3 at startup.
- Implement idempotent request IDs for the same title/source/track choice.
- Persist user stop reasons and pause-all intent; reconstruct them after restart.
- Keep transient stream cache separate and prevent download eviction by browsing.

### P06.02 — Both entry points and track selection

- Card/details Download resolves a source and loads available tracks without
  starting playback. Use DownloadHelper or the verified equivalent for the
  pinned Media3 version.
- In-player Download consumes existing source/track context and opens the same
  quality/audio/subtitle selection flow without disrupting the player.
- Produce matching DownloadRequest stream keys and scoped request policy.
- Show single-source quality for progressive media; WebEmbed and unsupported
  source results show a clear reason instead of a broken queue item.
- Wire both feature callbacks through the integration owner.

### P06.03 — Queue controls and Android service lifecycle

- Implement FIFO, two active downloads by default, setting 1–4, queue/retry,
  pause one/all, resume, remove and selected-track/byte progress.
- Separate waiting-network, user pause, system/quota stop and retryable/final
  failure states; retain the reason after process restart.
- Implement the default Wi-Fi-only transport policy through requirements and
  network observation; unmetered-only is not the same policy. Test VPN-backed
  Wi-Fi and unmetered cellular, and expose its persisted control to P08.
- Create the `dataSync` DownloadService/notification channels/permissions;
  handle denied notification permission and `onTimeout` promptly.
- Use eligible scheduling to recover network requirements; never assume
  WorkManager or a service can run indefinitely after force-stop/user stop.
- Bound network/segment concurrency and yield resources to active playback.

### P06.04 — Resume, storage and offline path

- Handle interrupted Range responses, HLS segments, expiry/re-resolution and
  changed-resource validators without mixing old and new content bytes.
- Reserve remaining job bytes plus other active staging/export/update needs
  and 1 GiB floor; handle unknown sizes and recheck during writes.
- Use 64-bit sizes and test >4 GiB media metadata/streaming behavior.
- Build cache-only playback from stored DownloadRequest stream keys with no
  upstream data source; missing data is an explicit incomplete state.
- Preserve source/title snapshots so catalog updates/removals cannot orphan
  an offline item. Respect active player/export leases on remove.

### P06.05 — Recovery matrix

- Run airplane mode during transfer, resume, app process death, relaunch,
  reboot, forced stop then explicit reopen, and user-paused persistence.
- Test notification denial, system/task stop, foreground-service timeout,
  no space, expired URL, changed ETag/manifest and duplicate actions.
- Verify partial jobs are retained, eligible work resumes once, and completed
  items play selected video/audio/subtitles with network disabled.
- Confirm progress UI reads actual Media3 byte/state information and limits its
  refresh frequency to visible UI/notification needs.

## Acceptance

- [ ] V10–V17 pass using deterministic controlled fixtures.
- [ ] Both entry points produce the same job/track semantics and deduplicate.
- [ ] Two jobs transfer concurrently; FIFO and paused state survive restart.
- [ ] Every tested interruption yields a correct persistent state and recovery.
- [ ] Completed file/HLS downloads play with upstream disabled, even if the
  catalog title has disappeared.
- [ ] Storage admission/removal is correct and downloaded cache is never
  mistaken for an ordinary exportable MP4 directory.
- [ ] Pixel 6 background/combined-use evidence exists or is explicitly pending.

## Evidence and handoff

Create `docs/reports/DOWNLOAD_RECOVERY.md` and `docs/handoffs/P06-downloads.md`.
Include state-transition evidence, Media3 index ownership, caches, format/track
limits and API hooks for export/library. Next independent assignments:
**P07.01** and **P08.01** once P04 is ready.
