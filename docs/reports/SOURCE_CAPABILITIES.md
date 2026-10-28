# P03 source capabilities — 2026-10-28

P03 is VERIFIED and Gate A passes with the measured limitations below. The largest-family playback blocker now has measured Pixel
paths: Tube 5/5 native video/audio and Eporner 5/5 web video with unmuted own-app
audio output. The full-provider transfer/cache-only seek and current four-test controlled
integration gate both pass on the same final APK.
P04 is independently READY. Production player, queue and export remain P05–P07.

## Fixed sample and coverage

Input: full-20261027-r4, 286,207 titles / 321,999 sources. Freeze five distinct-title
records per group before probing, ordered by SHA256(family NUL sourceId), retaining
all failures. There are 45 unique source/title records. Sample SHA256:
`54ecef86fd6574f875561cce9ae7eeceda8a898ac54f2a553c367e82fad3556e`.
Populations overlap. This small fixed sample is not a population success rate.

Physical environment: Pixel 6 / Android 17 API 37, phone-side resolution and media
on the existing hotspot/mobile-data network, without desktop relay or adb reverse.
No airplane mode or network-setting interruption was used.

The complete 45-case rerun in `artifacts/p03/provider-real-v1/` records 17 native
video/audio successes. Later focused runs retain the same five selected records
per family. They used different APK revisions and are deliberately separate.
Do not present their sum as a complete same-revision 45-case rerun.

| Group | Catalog sources / titles | Complete rerun: native / 5 | Later focused Pixel evidence |
| --- | ---: | ---: | --- |
| tubepornclassic.com | 129,341 / 129,336 | 0 | 5/5 native H264/AAC with advancing frames/audio, tube-pixel-v3/ |
| www.eporner.com | 78,356 / 78,355 | 0 | 5/5 web video advanced; each unmuted with one own-app active audio output, eporner-pixel-v2/ |
| abv.tizam.org | 24,147 / 24,147 | 3 | All five resolved native; two bounded playback attempts did not pass |
| www.fulleroticmovies.net | 15,692 / 15,692 | 0 | Sampled dynamic player unsupported by current resolver |
| www.porntrex.com | 11,985 / 11,985 | 0 | Full dynamic player unsupported; previews never count as full titles |
| direct-file | 33,959 / 33,954 | 1 | Three native candidates, two provider-changed results |
| direct-hls | 536 / 536 | 3 | Three native candidates, two not-found results |
| protected-file | 703 / 703 | 5 | Five independent native download-resolution candidates |
| protected-hls | 340 / 340 | 5 | Three download-resolution candidates, two unsupported download outcomes |

The complete rerun's collection test passes because it recorded all 45 outcomes,
not because every source works. Native success requires advancing position, video
frames and audio decoder output. Short bounded playback does not establish a
whole-session guarantee. Eporner evidence includes provider-player controls,
advancing decoded frames, unmuted volume and Android own-UID audio output state;
human audible perception and full-title visual identity remain unverified.
A resolved download candidate is not a completed transfer.

## Provider implementation and limits

`CatalogSourceResolver` is now installed in the real application container. It
returns typed NativeMedia/WebEmbed/Unavailable outcomes per purpose. Production
player/download controllers remain pending their own phases; the lab does not
silently implement those screens.

- Tube reads bounded public JSON metadata for the selected numeric ID, requires
  active/public state and decodes its published custom-base64 transport value as
  data. It checks MP4/HLS signatures and permits only observed media hosts. Private,
  deleted, mismatched or opaque entries do not become media. No provider scripts,
  credentials, challenges or DRM are bypassed.
- Tizam follows the measured abv→adv redirect and its three observed video hosts.
  Media Referer comes from the final page origin without query/fragment secrets.
- Eporner permits the measured script hosts and a bounded HTTPS/443 numbered CDN
  hostname shape for resources only. Navigation remains scoped to the provider's
  entry route. POST metrics, popups, intents and arbitrary hosts stay blocked.
- Fullerotic clear public literals may resolve if validated. Its sampled dynamic
  configurations and PornTrex's full dynamic player return explicit UNSUPPORTED
  with a user-facing explanation to choose another source. Network failures retain
  their network classification. No preview receives full-playback/download flags.

Resolver calls retain a 20-second HTTP call limit and 25-second resolution deadline,
8 KiB media-prefix checks, bounded full-manifest reads, four redirects, bounded
alternatives/variants/references and coroutine cancellation. A real transfer found
that the probe's 20-second call limit was also terminating long media bodies.
`SourceHttp.mediaClient` now has no whole-call deadline, retaining 10-second
connect / 15-second idle-read limits, exact redirect/header policy and caller
cancellation. Media3 owns long transfer lifetime; a slow-response regression proves
that the short resolver limit no longer truncates media.

Headers are rebuilt at every validated hop and scoped across manifest, variant,
segment, init-map and key reads. Strong matching ETag, known equal length and range
support are required for byte-identity resume. P06 still owns provider re-resolution,
partial-cache invalidation and durable background queue recovery. Live HLS and DRM
beyond identity AES-128 are unsupported. No resolver flag promises export.

## Controlled physical evidence

The three-test provider-device-v2/ gate passed MP4, TS-HLS, fMP4-HLS and identity
AES-128 advancing video/audio; required headers reached all relevant HLS resources.
One selected-track download paused, retained stop reason 37 after manager reopen,
resumed, completed and played 2,002 ms cache-only with upstream absent. It did not
turn off the user's internet. Initial web Referer was observed on the actual request.

Six web abuse actions remain contained. Neutral web video and own-app audio were
active before exit; closing left no own-app active audio. Renderer death is handled.
The wrapper disables cookies, mixed/local content, permissions/file pickers, native
bridges, external intents and service-worker network access. These choices limit
provider compatibility. WebView callbacks are not a device-wide firewall.

The latest four-test suite passes in runtime-controlled-final/ on the same final
APK as the full-file proof. It verifies the real resolver after catalog provisioning,
all four native formats (2,001–2,017 ms), selected-track pause/reopen/resume and
2,048 ms cache-only playback, scoped headers/init/key requests, web containment,
exit audio cleanup and renderer death. Narrow fMP4 output plays at 2,098 ms;
TS sample-copy remains unsupported. Installed APK hash reuse was verified.

The narrow fMP4 sample-copy spike produces a standalone two-track MP4, plays on
Pixel and seeks. The TS spike produces invalid AAC timestamps and is explicitly
unsupported. P07 must implement proper extraction/timestamps, separate audio,
discontinuities, validation and SAF. These spikes are not the export feature.

## Full provider transfer

A separate explicit experiment selects frozen Tube record 0, permits a known-length
progressive file up to 72 MiB, uses its own Media3 index/cache and requires complete
byte count, cache-only advancing video/audio and midpoint seek with upstream null.
It does not write personal.db. The selected resource is 67,226,884 bytes.

Attempts are preserved: v1 rejected the old 64 MiB cap before transfer; v2 exposed
the inherited 20-second HTTP timeout; v3 exceeded the initial four-minute test
budget; v4 was canceled after 49,652,751 bytes when the lab activity paused.
The nine-minute-budget retry passes in provider-complete-download-v5/: all
67,226,884 bytes completed, cache-only H264/AAC advanced to 2,100 ms (54 video frames,
120 audio buffers), and a seek reached 547,043 ms of the 1,092,011 ms file with
zero upstream bytes. Settings were restored and network flags unchanged.
This proves one selected real file, not every DOWNLOAD candidate or a production
background queue.

## Host and artifact verification

Current debug/test/unsigned-release assembly, lint and 53 JVM tests pass (33 network,
6 data, 7 model, 5 testing, 2 app; zero errors/failures/skips). Source helpers pass
4 tests and development helpers pass 17. The latest exact-input APK inspections
pass catalog hashes, full generation/count, API/ABI and static 16 KiB alignment;
release excludes the optional lab and real-source samples. Release signing is P11.
Runtime 16 KiB-device acceptance remains supplemental and untested.

Evidence: source-integration-final-build.log, runtime-final/apk-inspection.json,
runtime-final/release/apk-inspection.json, plus each dated device directory's
summary, test log and sample report under ignored artifacts/p03/. Each phone
summary records the actual app/test APK hashes; subsequent builds do not rewrite
those identities. Raw URLs/pages/tokens remain ignored.

The optional nonexported debug lab owns only its isolated player/cache/index.
P05/P06 must integrate production singletons. No catalog/personal schema, dependency
pin or persisted source identity changes. Old 12/45 evidence and failed attempts
remain historical, superseded by the dated reruns above.

## Access and cleanup policy

Per the user's 2026-10-28 instruction, Pixel remote debugging remains enabled across
phases until the whole project completes or an earlier shutdown request. This
supersedes older per-batch shutdown reports. Each batch still stops its jobs,
restores screen timeout, removes test packages and cleans its own disposable data.
Hotspot/mobile data/internet remain intact; the Realme is untouched.

Final batch cleanup is verified in runtime-final/retained-access-cleanup.json:
own lab cache/index/input/conversion/report files removed, test APK removed,
app process stopped and zero own-app active audio outputs. Personal DB and catalog
generations remain present. Screen timeout restored to 1,800,000 ms; both final
helpers confirm network flags unchanged. Pixel network ADB remains active on TCP
5555 and a fresh shell verifies access, as requested. No all-phase completion or
release readiness is implied by the P03 feasibility gate.
