# P03 source feasibility tools

Run from `mobile/`. Phone access is not used by the host commands.

```sh
python3 tools/sources/inventory.py --catalog-db local-data/catalog-full-r4/catalog.db --output-dir local-data/source-samples/p03-v1
python3 tools/sources/probe_samples.py --sample-dir local-data/source-samples/p03-v1 --output-dir local-data/source-samples/p03-v1/host-probe
python3 -m unittest discover -s tools/sources/tests -v
python3 tools/sources/generate_fixtures.py --output-dir local-data/source-fixtures
```

The inventory takes the five lowest SHA256(family NUL sourceId) ranks with
unique title IDs per family. Selection is fixed before probing and cannot be
overwritten in place. It includes each of the top five embed-host families,
direct file/HLS, and protected EroGarga-derived file/HLS families. Counts are
source records and distinct titles separately. Families can overlap: do not sum
their counts into whole-catalog coverage. Original URLs/types/IDs stay in ignored
local-data; committed tests contain neutral data. Sample ages and source kinds
are retained for review; this is a dated fixed sample, not a statistical promise.

Host probing reads at most 256 KiB per embed and 8 KiB per direct source, with three
workers, normal TLS validation and a bounded redirect policy. It retains failures
in the sample denominator. HTML or a media prefix is HTTP evidence only, not
proof of browser/native playback, complete download, codec support or export.
Provider network errors can be local-network/ISP/proxy issues; they do not prove
the provider is globally unavailable. No challenge or authentication is bypassed.

The optional fixture generator uses the installed ffmpeg with two encoding
threads to create a neutral test pattern and sine audio: progressive MP4,
multivariant TS-HLS, fMP4-HLS, clear AES-128 HLS and containment pages. The actual
ffmpeg version/file hashes are recorded in fixture-manifest.json. ffmpeg is a
host-only development tool, never bundled into the APK. No real media is included.

Build the optional debug lab with `-PsourceLab=true`. Normal builds do not need
ffmpeg and release assets/classes never include the lab. An explicitly selected
frozen sample can additionally be included using `-PsourceSamplesDir=<directory>`;
its digest is validated by prepare_samples.py before copying into debug assets.
Never add raw sample files to tracked assets or release packaging.

Physical acceptance, when the user reauthorizes a connection:

```sh
ANDROID_SERIAL=<selected locally> ./gradlew verifySourceFeasibility -PsourceLab=true -PcatalogDir=local-data/catalog-full-r4
```

This is an explicit device command; it has not been run during host-only work.
It checks controlled MP4/HLS playback, selected-track pause/reopen/resume,
cache-only playback, headers and WebView renderer cleanup. The HTML fixture also
needs manual popup/permission/external-intent/audio-exit checks. Sample-copy spikes
report format-specific success/limits separately. Real provider playback and
download sampling remain separate from both host and controlled-fixture evidence.
Never turn off hotspot/mobile data or enable airplane mode; use the fixture
server's local failure switch. Keep Pixel network control enabled across phases under the current user
instruction in AGENTS.md; disable it only after all phases or an earlier explicit
user request. Do not reuse older radio-changing scratch helpers.

### Explicit real-device sample collection

After the controlled gate and with the optional frozen sample already built:

```sh
ANDROID_SERIAL=<authorized target> python3 tools/dev/run-source-tests.py --real-sources
```

This invokes RealSourceSampleTest only, retains every one of the 45 cases and
writes a sanitized per-case result after each attempt. PLAYBACK and DOWNLOAD
resolution have independent 15-second deadlines. Native playback attempts are
bounded to 15 seconds and 5 MiB across media reads; web candidates get 12 seconds
to expose advancing accessible video. Cross-origin frames can prevent measurement.
A web video observation does not claim audible audio. Full real-provider videos
are not downloaded automatically; native DOWNLOAD candidates remain pending an
actual completed provider transfer. The controlled suite proves download mechanics
separately. Passing sample collection means all records were attempted, not that
providers worked. Raw handles stay in the debug APK/ignored inputs; result JSON
contains family/index/outcomes rather than URLs or titles.

### Provider follow-up (2026-10-28)

```sh
python3 tools/sources/probe_tizam.py --sample-dir local-data/source-samples/p03-v1 --output-dir artifacts/p03/provider-followup-final
```

This retains the same five Tizam cases, permits only the observed abv→adv and
video1/video2/video4 host paths, and reports bounded MP4-prefix/range observations.
It is host HTTP evidence only. The generic host redirect helper now drops the
previous request's Host/authentication/cookies so the transport derives the new
Host correctly. Raw source handles stay ignored. No helper enables phone access.
The next real-source Android run records initial Referer, bounded Play gestures
inside the provider container, blocked hostnames and unavailable reasons; it still
does not turn page/video advancement into verified full-title audio/download proof.

### Current focused and complete-transfer experiments

The sample helper supports `--family <one of the frozen groups>` only with
`--real-sources`; focused results retain their original five-record denominator
and are not a replacement full 45-case run. `--real-download` is a separate
explicit full-file experiment on frozen Tube record 0: known progressive length
up to 72 MiB, nine-minute transfer budget, complete byte count, cache-only video
/audio and midpoint seek with upstream absent. It may transfer about 67 MB.

`--output-dir` preserves named attempts. `--reuse-installed-app` verifies the
installed APK hash matches the selected built APK before reusing it; test APKs
are installed for the run and removed afterward. Normal cleanup restores screen
timeout and verifies network flags without disabling the retained remote access.
See the current capability report for actual results and audio/identity limits.
