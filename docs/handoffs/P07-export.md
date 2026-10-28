# P07 standalone export — in progress

P07 ACTIVE. Source implementation is present; host/device acceptance is not yet complete. No device/network setting changes or headphone work are part of this phase.

`ProductionExports` owns one durable export worker and separate export states in existing personal `export_jobs`. It leases completed DownloadManager input, stages output privately, then copies via ContentResolver to the file-picker document. A separate mediaProcessing foreground service bounds execution and handles cancellation/timeout. Restart marks interrupted jobs failed, deletes owned staging and leaves the completed download intact; a partial chosen document may need manual deletion if its provider cannot remove it. Retry is a fresh explicit Export action, not an automatic overwrite.

Media3 Transformer is pinned to the existing 1.11.1 version. Its asset loader uses the download's selected stream keys and a CacheDataSource with **no upstream**. Transmuxing is requested; the encoder factory rejects any requested encoding and outputs with encoder names are rejected. H264/AAC MP4 is the validated target. TS and fMP4 are input formats, never filenames to concatenate/rename. Manifest inspection rejects encrypted, discontinuous, live and partial HLS; init maps are supported candidates. Subtitle-selected jobs fail explicitly because this path has no container/sidecar subtitle implementation yet. Unsupported codec/DRM/format results retain the original download.

Validation stages reopen MP4 tracks, require positive durations and actual samples, seek to the midpoint, then independently reopen the final destination and compare duration. Read-denied/non-seekable providers fail as unverified, rather than display Exported. Destination providers need writable/readable seekable content URIs for current validation. Cache remains an internal download implementation, never a standalone-file directory.

Storage reserves staging plus possible local destination duplication alongside download reservations and the 1 GiB floor. During staging/copy, free-space checks can cancel safely. Export input leases block download removal. This still needs controlled concurrent/remove/cancel and OS/process-death device evidence; catalog-update reservations belong to P09 integration.

Prepared controlled instrumentation: `ProductionExportsDeviceTest` downloads neutral progressive MP4, TS HLS and fMP4 HLS into an isolated production manager, stops only its own fixture server, exports through the real worker into a temporary app FileProvider destination, independently reopens/seeks output tracks, actually decodes standalone file video/audio on the one service/surface with a plain file data source, and confirms no server requests and unchanged completed download states. This proves an app-owned document provider, not Android Documents/Downloads picker grants. Actual Documents provider and output playback/AV alignment/thermal behavior remain pending until measured. All temporary owners/files/prefs must be restored after the test.

Evidence: `docs/reports/EXPORT_FORMATS.md`. No VERIFIED claim.

## 2026-10-29 export checkpoint

A storage reservation fix allows normal MP4 container overhead: both staging and
destination reserve the input size plus 5% (at least 1 MiB). Overflow and small
input unit checks pass. The full-catalog debug/test build succeeds. On Pixel,
`artifacts/p07/export-device-v5` successfully remuxed and independently decoded
the progressive MP4 with no upstream requests. The next TS HLS case failed at
cached-manifest opening (`PlaceholderDataSource`), so HLS export remains open.
The test's original download was retained. An earlier run without source-lab
fixtures did not execute the test. Do not mark P07 verified; investigate cached
manifest identity/completeness without introducing an upstream fallback.


## 2026-10-29 controlled export pass

Pixel test `artifacts/p07/export-device-v7` passed for progressive MP4, TS HLS
and fMP4 HLS. Each output was independently read, decoded and seeked, with no
upstream fetch and its download retained. Cache metadata entries with no bytes
were excluded from manifest inspection; real cached manifests remain inspected.
This is an isolated FileProvider test, not proof of a real Documents picker,
process recovery or all codecs. P07 is IMPLEMENTED, not VERIFIED.
