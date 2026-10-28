# P07 — Standalone media export

**Role:** Media export agent
**Prerequisites:** P06 complete-download cache and lease API
**Owns:** ExportEngine in `core/media/`, export UI within assigned downloads
paths, export-job schema through data owner, format/recovery fixtures
**Requirements:** R17, R18

## Goal

Export supported downloaded media to a valid independently playable file using
Android's file picker, with correct format handling and recoverable failure.

## Read first

[Media contract](../contracts/MEDIA.md), [validation](../VALIDATION.md), P03
format spike, P06 handoff, and pinned-version Media3 Transformer/muxer APIs.

## Work packages

### P07.01 — Select and prove the export path

- Inventory actual downloaded formats/codecs/track combinations and the result
  of the P03 export spike.
- Choose a supported Media3 Transformer/muxer or explicit extractor/sample-copy
  implementation; prove that the selected version handles required input.
- Record supported progressive MP4, TS HLS and fMP4 HLS baseline cases, with
  separate audio. Define the exact behavior for discontinuities and init maps.
- Keep unsupported codecs/encryption/DRM and subtitle-in-container limitations
  explicit. Offer a supported original container where appropriate; never just
  rename a file extension.
- A new native dependency requires recorded packaging/ABI/16 KB implications;
  prefer supported packaged libraries over invoking a nonexistent ffmpeg binary.

### P07.02 — Durable export job and staging

- Add persistent export intent/state independent of download completion.
- Lease the downloaded content during export; prevent concurrent remove from
  corrupting input. Run one export at a time on the Pixel baseline.
- Create an app-owned staging file and perform sample copy/remux, preserving
  timestamps, audio alignment, duration, metadata and seekability.
- Validate staged output before presenting it as complete. If the process dies,
  retry/restart the export safely while preserving the original download.
- Run longer work under a documented eligible `mediaProcessing` lifecycle and
  handle its service timeout/cancellation without corrupt state.

### P07.03 — SAF destination and user-visible state

- Select a destination with ACTION_CREATE_DOCUMENT or the appropriate SAF
  contract, using content URIs and actual provider capabilities.
- Copy validated staging output through ContentResolver, with visible progress,
  safe filename/MIME, cancellation, duplicate-name and grant handling.
- Handle non-seekable/remote document providers; complete the MP4 in internal
  staging before copying instead of assuming provider random access/rename.
- On revocation, disconnection, full destination or process death, retain the
  download and clean/label partial destination output where the provider permits.
- Mark Exported only after close/validation as supported; distinguish exported
  user files from in-app cache and avoid implicit deletion of exported files.

### P07.04 — Space and container checks

- Account for simultaneous cached input, staged output, destination duplication,
  active download reservations, catalog updates and the 1 GiB floor.
- Test large-file (>4 GiB) byte counters/copying and destination capacity errors.
- Verify independently playable output with duration, A/V sync, seek positions
  and selected quality/audio. Do not verify export solely with the app's cache.
- Test embedded/external subtitle handling as documented, including sidecar
  export if container inclusion is unsupported.

### P07.05 — Pixel export evidence

- Validate local Documents/Downloads provider on Pixel 6. USB storage is
  supplemental when available; Pixel 6 has no built-in SD card.
- Run cancellation, out-of-space, revoked URI, export interruption and retry.
- Measure export time/CPU/thermal behavior on the sample-copy path; unexpected
  re-encoding is an explicit finding, not silently part of normal downloads.

## Acceptance

- [ ] V18/V19 and export-related V14/V17 pass.
- [ ] Baseline progressive MP4, TS HLS and fMP4 HLS export paths are proven.
- [ ] Independently opened output has valid duration, seeking and A/V alignment.
- [ ] Export failure/cancel never changes a completed download to missing/failed.
- [ ] SAF provider limits, subtitle behavior and unsupported formats are documented
  and represented accurately in the UI.
- [ ] No cache internals are concatenated or relabeled as an MP4 file.

## Evidence and handoff

Create `docs/reports/EXPORT_FORMATS.md` and `docs/handoffs/P07-export.md` with
the format matrix, independent validation method, recovery evidence and resource
measurements. Next: **P10** after update/library dependencies are ready.
