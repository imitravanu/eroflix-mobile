# P03 — Phone-side source and media feasibility

**Role:** Media/source agent
**Prerequisites:** P01 shared source/media models and Android build
**Owns:** `core/network/` source adapters, isolated feasibility media harness,
resolver fixtures, source capability report
**Requirements:** R08, R09, R22; feasibility proof for R14–R17

## Goal

Prove the actual catalog's source families can be used from Android, quantify
native/web/download capability, and identify format limits before integration.

## Read first

[Catalog audit](../CATALOG_AUDIT.md), [media contract](../contracts/MEDIA.md),
[platform](../ANDROID17_PIXEL6.md), [validation](../VALIDATION.md), P01 handoff,
and desktop `lib/relay.js` / relevant importers as behavioral references.

## Work packages

### P03.01 — Measured source inventory and sample selection

- Inspect original DB/source snapshots read-only. Keep full URLs/records in
  ignored local fixture inputs; committed fixtures use neutral examples.
- Prioritize `tubepornclassic.com`, `www.eporner.com`, `abv.tizam.org`,
  `www.fulleroticmovies.net`, and `www.porntrex.com` using the actual audit.
- Include direct file/HLS and header-sensitive EroGarga-derived sources.
- Select at least five records per high-volume family (or all if fewer),
  accounting for different kinds/ages/mirrors where present. Fix the sample
  before probing and keep failed records in its denominator.
- Track title and source coverage separately; document offline/missing hosts.

### P03.02 — Typed resolver and HTTP policy implementation

- Implement `SourceResolver` with purpose-specific NativeMedia/WebEmbed/
  Unavailable outcomes, cancellation, bounded retries and timeouts.
- Implement progressive/HLS direct handling and compiled provider adapters.
- Apply scoped Referer/User-Agent/cookie policy through redirects and all HLS
  subrequests; test concurrent jobs do not exchange credentials/headers.
- Add expiry/re-resolution and resource validator capture. Distinguish HTML,
  unsupported MIME/codec, login, expired URL, provider change and network errors.
- Preserve source provenance and avoid a runtime dependency on the desktop relay.

### P03.03 — Native playback and resumable-download proof

- Use a small Android harness to play controlled progressive MP4 and multivariant
  HLS with video/audio advancing; record actual selected codec/track.
- Use the same resolver policy to create one selected-track DownloadRequest,
  interrupt the network, resume, and play it with upstream disabled.
- Prove headers reach manifest, variant, segments, init maps and keys.
- Spike one TS-HLS and one fMP4-HLS sample-copy export path to inform P07.
  Record capability limitations rather than assuming `-c copy` exists on Android.
- The harness delegates through core interfaces and is removed/integrated when
  P05/P06 supply the production singletons; it must not create a second engine.

### P03.04 — Contained web-player path

- Build the provider-policy WebView wrapper with visible context and return path.
- Exercise popup, permission, external-intent, redirect and renderer failure
  fixtures; manage cookies and request filtering according to MEDIA.
- Pause native playback when entering a web source; stop web audio and release
  the renderer/view on exit. Test returning to the app's native controls.
- Report unsupported native features in this mode. An embed-only result does
  not enable a fake native-quality selector or download button.

### P03.05 — Pixel capability report and feasibility decision

- Test sampled real hosts on the user's Pixel 6/network when available, with
  relevant Android 17 CT/ECH/audio/network behavior intact.
- Record sample counts and independent native/web/download results per family.
- Document each family's adapter owner, fixture coverage and observed expiry.
- If the largest families have no functioning user path, record the concrete
  coverage blocker and required next adapter work before claiming Gate A passed.

## Acceptance

- [x] Controlled native HLS and progressive playback passes V05.
- [x] One resumable fixture download completes and plays cache-only.
- [x] Top five embed families and header-sensitive/direct sources have dated
  capability entries; unsupported outcomes are explicit.
- [x] Each supported adapter has deterministic failure/header/expiry tests.
- [x] Web source navigation/cleanup passes V06 with no residual audio.
- [x] Real-device source results are identified separately from emulator tests.
- [x] P05/P06/P07 have proven interface examples and known format boundaries.

## Evidence and handoff

Create `docs/reports/SOURCE_CAPABILITIES.md` and
`docs/handoffs/P03-source-feasibility.md`. Include V05/V06 and proof-download
evidence, sampled coverage, fixture inputs and pending Pixel checks. Next:
**P05.01** after P02, plus provider work required by any open coverage blocker.

## Feasibility decision — 2026-10-28

The [current capability report](../reports/SOURCE_CAPABILITIES.md) records the
complete 45-case rerun (17 native successes) separately from focused Tube 5/5
native and Eporner 5/5 web/unmuted audio-output measurements. Dynamic Fullerotic
and PornTrex players are explicitly unsupported; TS export remains unsupported.
P03 is VERIFIED: full-provider transfer/cache-only seek and all four final Pixel
controlled/application-integration tests pass on the same current APK.

- [x] Gate A: measured largest-family paths, complete real-provider transfer with
  cache-only seek, and current four-test controlled/application-integration suite.

See [runtime handoff](../handoffs/P03-runtime-closeout.md). P04 remains READY.
Current user policy keeps Pixel remote access enabled across phases, while jobs,
lab data and temporary settings are cleaned after every batch.
