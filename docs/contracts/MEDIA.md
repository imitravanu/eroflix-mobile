# Media contract v1

**Owners:** P03 sources, P05 playback, P06 downloads, P07 export.
Shared models/interfaces are declared in P01 and integrated by their owners.

## Source handles and resolution results

`SourceHandle` is durable: title/source IDs, provider, original URL, stored kind,
external ID, adapter key, and optional static header-policy key. A catalog
quality string is only a hint, not a verified available rendition.

`SourceResolver.resolve(handle, purpose)` is suspendable, cancelable and bounded.
It returns exactly one typed outcome:

- `NativeMedia`: resolved URI, MIME/stream kind, scoped request headers/cookies,
  expiry if known, provenance, available track/capability information, and an
  identity fingerprint sufficient to validate resumed bytes.
- `WebEmbed`: provider entry URI, navigation/request policy ID, and an explicit
  explanation of which native controls/download operations are unavailable.
- `Unavailable`: reason (`NETWORK`, `EXPIRED`, `NOT_FOUND`, `UNSUPPORTED`,
  `AUTH_REQUIRED`, `PROVIDER_CHANGED`, `LOCAL_ONLY`, etc.) and retry policy.

Purpose is `PLAYBACK` or `DOWNLOAD`; a web-playable result is not a successful
download resolution. Export capability belongs to the actual downloaded format.

P03 adds optional `ResourceValidators` to native resolutions without changing
persisted schemas. The resume helper requires matching strong ETags, known equal
resource lengths and byte-range support. Last-Modified remains diagnostic;
timestamps, weak ETags and identical URLs do not prove byte identity. P06 must
integrate validation before reusing partial bytes. Resolver capability flags mean
transport candidates; measured Android results live in SOURCE_CAPABILITIES.md.

### Network/source rules

- Use original DB URLs. The desktop `/api/relay` transport is not part of the
  mobile architecture; translate needed header policies into the phone client.
- Scope headers per source/host. Validate redirects before forwarding cookies
  or authorization; avoid global mutable headers shared by concurrent jobs.
- HLS policies apply to master/variant manifests, segments, audio/subtitles,
  init maps, and permitted key requests, including authorized CDN redirects.
- Handle expiry with bounded re-resolution. Do not equate an HTTP 200 HTML page
  or working HEAD request with playable/complete media.
- Request only selected source information; avoid full-catalog probing on the
  phone. Fixture tests are deterministic; real-provider checks are a dated sample.
- Native resolver code ships in the signed APK. Catalog updates can provide
  validated data/policy IDs, not downloaded executable plugins.

P03's 2026-10-28 adapter reads bounded media tags and clear compiled-provider
configuration literals; it never executes downloaded scripts. Dynamically
replaced/opaque configurations remain web-only. A provider preview is not the
selected full title: PornTrex preview sources must not be promoted to native
playback/download. WebView entry loads receive the provider origin Referer;
resource hosts and top-level navigation hosts remain distinct. Real video
advancement, audible audio, content identity and a completed transfer remain
separate measurements. See P03-provider-followup.md in handoffs.

## Source support acceptance

P03 owns `docs/reports/SOURCE_CAPABILITIES.md`. For each high-volume provider/
host family record: dated sample size, native playback successes, web playback
successes, download successes, unavailable reasons, headers/expiry behavior,
tested network/device, and adapter ownership.

Test the top five embed host families plus representative direct HLS/file and
header-sensitive hosts. Choose at least five catalog records per high-volume
family before probing (or all if fewer), retaining failures in the sample
denominator. A family needs a proven user path or an explicit unsupported
result. Do not extrapolate a sample to "all titles work."

If the largest families remain unplayable, the feasibility gate reports that
coverage gap before promising the full native/download experience. A host
adapter outage is a bounded failure state, not a reason to discard catalog rows.

## Contained WebView path

- Enter through explicit source selection, show provider context, and maintain
  a visible way back to native navigation.
- Use an HTTPS provider-specific top-level navigation policy; block unrequested
  windows, external intents, downloads, redirects and permission requests.
- Disable local file/content access and native JavaScript bridges; keep Safe
  Browsing where available and terminate/release the WebView on departure.
- Request filtering covers the paths the app actually controls, including
  service workers when used. Do not claim device-wide DNS/ad blocking from
  WebView callbacks; document capability limits and tested hosts.
- Cookie requirements are scoped and documented. Clean up playback and stop
  audio on exit, lock, or renderer termination. No invisible background embeds.

## Player ownership and behavior

One `MediaSessionService` owns ExoPlayer. Activities/composables connect through
a controller/media adapter and attach/detach the surface. Recomposition and
rotation do not create another player. A selected WebView source pauses/releases
native playback ownership appropriately so two sources cannot play together.

- Native qualities come from actual tracks; include Auto. Progressive media
  with one rendition shows Source quality rather than fabricated resolutions.
- Mirror switching validates same-title identity and retains a safe playback
  position when timeline/duration are compatible; show restart when they differ.
- Persist position every roughly five seconds while playing, and on seek,
  pause, stop, source switch and lifecycle boundaries. Use milliseconds.
- At normal app background without PiP, pause by default. Screen-off background
  playback is an explicit setting with an eligible foreground media service.
- An enabled app lock overrides background playback: locking pauses native/web
  media, covers/exits PiP and redacts session/notification metadata. Session and
  external-entry actions cannot resume playback until the app is authenticated.
  An already-authenticated visible PiP window remains in that session until an
  explicit lock, screen-off or loss of all app/PiP visibility triggers locking.
- Audio focus, calls, headset disconnect, session controls and final stop must
  work under target-37 hardening. Do not restart automatically after a final stop.
- Brightness changes use the player window and restore the prior value on exit.
- Normal volume uses Android media volume; 100–200% gain is a separate bounded
  software processing stage with clipping protection. Gain >1 is not implemented
  by assigning an unsupported ExoPlayer volume. Verify PCM/output/offload paths.
- Long-press 2x preview restores the previous speed on lift **or cancellation**.
- Touch lock has an accessible unlock control. Gestures start inside content,
  avoid system insets, and arbitrate scrub/vertical/double-tap/pinch collisions.
- External subtitles use SAF content URIs with retained permission where
  offered; parse failures do not discard playback state.

## Download state ownership

The Media3 `DownloadIndex` is authoritative for actual transfer state and
cached byte coverage. `personal.db` owns durable user intent, source snapshot,
selected tracks, ordering, stop reason, and export metadata. Its UI projection
must be reconciled from the index after process restart.

`DownloadRequest.id` is a stable job identity. Repeated download presses for
the same title/source/track selection resolve to the existing job. A changed
rendition is a new explicit request, not accidental duplicate work.

| UI state | Meaning / transition |
| --- | --- |
| `QUEUED` | Durable intent waiting for a slot |
| `RESOLVING` | Obtaining/verifying a fresh native source and track selection |
| `WAITING_NETWORK` | Requirements unmet, including Wi-Fi/unmetered policy |
| `DOWNLOADING` | Media3 reports active transfer |
| `PAUSED_USER` | Persisted user stop reason; no automatic resume |
| `PAUSED_SYSTEM` | Lifecycle/quota/system stop; restart only when eligible |
| `FAILED_RETRYABLE` | Bounded retry/backoff available, reason retained |
| `FAILED_FINAL` | Unsupported or exhausted failure; user action needed |
| `COMPLETED` | Selected tracks verified available in download cache |
| `REMOVING` | Media3 removal and metadata reconciliation in progress |

Export states are separate: `EXPORT_QUEUED`, `EXPORTING`, `EXPORT_FAILED`,
`EXPORTED`, `EXPORT_CANCELED`. Export failure must not erase a completed download.

### Scheduling and recovery

- FIFO ordering, two active transfers by default, setting 1–4; bounded segment
  concurrency and resolver attempts. Prioritize active playback network needs.
- Wi-Fi-only defaults on. Enforce actual underlying Wi-Fi transport, including
  VPN cases; Media3's unmetered requirement alone is not equivalent. Preserve
  the distinction if an unmetered-only preference is later introduced.
- Persist user pause using stop reasons. Media3's pause-all runtime flag alone
  does not survive process death; persist and reapply the app's queue intent.
- Use Media3-compatible scheduling for eligible network recovery. WorkManager
  is useful for coordination/short checks; it is not a limitless transfer service.
- Handle notification denial, service timeout, user stop, device reboot, storage
  loss, and exhausted quota without falsely reporting completion.
- Force-stop stops execution by design. On the next user launch reconcile the
  index and resume only jobs whose persisted intent/policy permits it.
- Resume HTTP Range only when validators/resource identity match. If a resolved
  URL now denotes different bytes, invalidate the affected partial content and
  restart with an explanation; never append to an unrelated resource.

## Download cache and genuine offline playback

- One process-wide `SimpleCache` per directory with `NoOpCacheEvictor` for
  durable downloads. Keep transient streaming/image caches separately bounded.
- Use the same Media3 database provider/cache instances across download and
  offline playback. Do not inspect/delete cache internals as ordinary MP4 files.
- Build playback from the stored `DownloadRequest.toMediaItem()` so stream keys
  match downloaded video/audio/subtitle tracks.
- Offline-only mode uses a cache data source with no upstream. Missing bytes
  give a recoverable incomplete-download error, never a hidden network request.
- Manifest URL changes do not justify dropping query parameters indiscriminately
  from cache keys. A stable cache identity requires proven equivalent resource
  bytes and consistent manifest/segment/key identities; test it explicitly.
- Queue metadata retains title/source/selected-track snapshots so a catalog
  replacement or a removed title cannot orphan a completed download.
- Removal is idempotent and respects an active player/export lease. Removing an
  in-app download does not delete a separately user-exported file implicitly.

## Storage and export

Private download files live under app-specific storage, with `.nomedia` as an
indexing hint. They are normally removed on uninstall. SAF-exported files are
user-owned and may be visible to other apps; app lock is not file encryption.

Space admission includes remaining download bytes, active reservations,
catalog staging and export duplication, plus **1 GiB reserve**. Recheck while
writing, since another app can consume space. Unknown lengths use a bounded
policy and recoverable pause; a one-time `free > 1 GiB` check is insufficient.

P07 introduces `ExportEngine` behind a stable interface:

1. Take a lease on a complete download and probe selected codecs/container.
2. Produce the file in app-owned staging using public Media3 cache/extractor
   APIs. Select a tested Media3 Transformer/muxer or explicit sample-copy path.
   Verify transmux behavior; no assumption of universal adaptive-input support.
3. For supported HLS, assemble selected video/audio with correct init data,
   time bases, discontinuities, duration and seeking. Prefer sample copy.
4. Test both MPEG-TS and fMP4 HLS. List any unsupported codecs/encryption and
   subtitle export behavior explicitly; never relabel bytes as MP4.
5. Validate the finished file, then copy to a SAF-created destination URI.
   Filesystem paths and atomic rename are not assumed for document providers.
6. On cancel, out-of-space, revoked grant or disconnection, retain the download,
   mark export failure, and clean or label any partial destination as supported.

Pixel 6 has no microSD slot. Test the local Documents/Downloads provider;
USB-attached storage is a supplemental destination if available.

## References

- [Media3 downloads](https://developer.android.com/media/media3/exoplayer/downloading-media)
- [MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Transformer formats](https://developer.android.com/media/media3/transformer/supported-formats)
- [Android 17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio)
- [Foreground-service timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)

### 2026-10-28 — measured Tube/Eporner source strategies

Tube's public metadata must match the selected numeric video ID, report active
public access and fit bounded JSON. Its compiled custom-Base64/percent transport
decoder follows the public player format without executing provider scripts.
Permitted media destinations are exact measured hosts. Metadata/private/deleted/
unknown states do not become native successes. A long video can use the provider's
public HLS parameter with a progressive fallback. Eporner web resources also accept
its measured numbered regional CDN naming shape within its owned domain; this
does not permit top-level navigation, URL credentials, HTTP or nonstandard ports.
The bounded debug experiment distinguishes video, unmuted player state, actual
own-app active audio and unverified full-title identity.

Unproven Fullerotic/PornTrex dynamic players now return an explicit UNSUPPORTED
state with another-source guidance, unless a supported clear media candidate
validates. PornTrex previews remain ineligible as full native titles.

A full real transfer exposed the probe client's 20-second total-call deadline
being reused for Media3. SourceHttp.mediaClient removes only the total media-call
deadline, retaining 10-second connection and 15-second idle-read timeouts, exact
redirect/header policies and cancellation through Media3 ownership. Probe calls
retain their 20-second HTTP and 25-second resolver bounds. P06 owns production
transfer scheduling/lifecycle; the explicit proof has its own byte/time ceilings.

### 2026-10-28 — P05 implementation boundary, acceptance pending

ProductionPlayback is the app-facing bridge; PlaybackService creates one
ExoPlayer/MediaSession, and feature/player attaches presentation. A visible user
source selection is cancelable resolving state, not proof of media advancement.
Repeated source attempts are explicit; unsupported-provider and source-expiry
outcomes remain typed. No implicit mirror retry loop is added.

Resume is deliberately conservative: retained personal position alone is
insufficient. Exact source ID, resolution fingerprint and finite durations within
one second must match the private successful-checkpoint adjunct. Incompatible
mirrors restart with explanation. Stopped/process-restored routes do not autoplay.
No pending/buffering checkpoint may replace a prior ready progress point.

The gain stage processes PCM16 at 1–2 amplitude with symmetric soft saturation;
unity copies bytes unchanged. Float output and audio offload are disabled so gain
is not implemented with unsupported player.volume>1. Phone/speaker/Bluetooth and
output-path evidence remains required. Public poster/web/source capabilities are
unaffected. P06 durable transfer and P07 export ownership are unchanged.

## P06 implementation boundary (2026-10-29)

Download intents now retain a versioned native source/request-policy snapshot, typed period/group/track metadata and actual DownloadHelper stream keys in the existing download_intents columns. Flat format IDs are advisory and never identify a selection on their own. Personal data remains unchanged by migration; retained titles survive queue removal. The durable desired state is independent of Media3 index transfer state. One non-evicting namespaced cache/index belongs to ProductionDownloads; completed playback has no upstream. Export leases and non-download staging reservations are assigned to P07 before concurrent export/removal can be enabled. See P06 handoff for conservative partial-HLS restart and pending runtime evidence.

## P07 implementation boundary (2026-10-29)

Export metadata is persisted in the existing export_jobs table, separate from download completion; new optional job byte counters default to zero/unknown. ProductionDownloads now provides completed-content read leases and shared export-staging reservations. Removal refuses leased content. ProductionExports uses selected-stream cache-only input, private validated MP4 staging, then ContentResolver destination copy and independent reopen/seek validation. Exactly one mediaProcessing worker runs. Process interruption retains input and marks the separate export failed for explicit retry; no stale document is silently overwritten. Encrypted/discontinuous/live/partial HLS and subtitle-selected downloads are currently rejected for export. Encoder creation is prohibited; P07 actual format/runtime evidence remains in EXPORT_FORMATS.md, not inferred from resolver capabilities.
