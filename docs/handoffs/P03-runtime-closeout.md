# P03 runtime closeout — 2026-10-28

Owner: Codex / Sol, existing checkout. P03 VERIFIED; Gate A passes with the documented provider/format limits. No commit, push or release publication.
The [capability report](../reports/SOURCE_CAPABILITIES.md) is the current coverage
record; older handoffs are dated checkpoints.

## Result and changes

Tube public metadata and data-only transport decoding now resolve the selected
public video identity to validated media. Five frozen cases advance H264/AAC on
Pixel. Eporner's measured resource policy and provider-player gestures give five
advancing unmuted web videos with own-app active audio output. Tizam native support
improves to three of five bounded playback successes in the complete rerun.
Fullerotic/PornTrex dynamic players produce explicit unsupported outcomes;
preview files cannot masquerade as complete titles.

A real large-file attempt revealed an inherited 20-second resolver HTTP deadline
terminating media transfers. SourceHttp now exposes a distinct media client with
no whole-call deadline, preserving connect/idle limits and scoped redirects and
headers. A delayed-server regression verifies this separation. AppContainer now
supplies the real CatalogSourceResolver; production player/download/export still
belong to P05/P06/P07.

Changed paths: core/network resolver/public Tube adapter/provider and web policy,
contained WebView diagnostics and tests; core/media/ScopedMediaDataSource.kt;
app ContainerFactory and integration test; debug source lab/probe/full-transfer
harness; instrumentation and source/dev helpers; contracts/status/reports.
No persisted schema/source identity, catalog input or dependency version changes.
No original desktop code or unrelated parent README changes were made.

## Evidence

| Check | Result / artifact under ignored artifacts/p03/ |
| --- | --- |
| Current debug/test/release assembly + lint + JVM aggregate | PASS; source-integration-final-build.log |
| JVM cases | 53 passed, zero failures/errors/skips: network 33, data 6, model 7, testing 5, app 2 |
| Source / development helper tests | 4 / 17 pass |
| Current debug and unsigned release exact full catalog inspection | PASS; runtime-final/apk-inspection.json and runtime-final/release/apk-inspection.json |
| Earlier controlled Pixel gate | 3 pass; provider-device-v2/; headers, formats, resume/cache-only, web cleanup and renderer death |
| Complete frozen-source Pixel rerun | 45 recorded, 17 native successes; provider-real-v1/ |
| Focused original Tube cases | 5/5 native video/audio; tube-pixel-v3/ |
| Focused original Eporner cases | 5/5 advancing unmuted web video + own-app audio output; eporner-pixel-v2/ |
| Full real-provider transfer/cache-only midpoint seek | PASS; 67,226,884 bytes, cache-only video/audio + midpoint seek, zero upstream; provider-complete-download-v5/ |
| Current four-test controlled/integration suite | PASS; 4 tests in 45.99 seconds; runtime-controlled-final/ |

The full 45-case rerun and later focused cases are separate revisions. Each
summary records actual tested app/test APK hashes. Passing sample collection
means the experiment recorded all results; it does not mean all providers worked.
Eporner audio-output state is measured, while human audibility/full-title visual
identity is not verified. Preview, playable, downloadable and exportable remain
separate. fMP4 sample-copy is narrowly proven; TS sample-copy remains unsupported.

Exact current host command:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease lintDebug verifyUnitTests -PsourceLab=true -PsourceSamplesDir=local-data/source-samples/p03-v1 -PcatalogDir=local-data/catalog-full-r4
python3 -m unittest discover -s tools/sources/tests -v
python3 -m unittest discover -s tools/dev/tests -v
python3 tools/dev/inspect-apk.py --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03/runtime-final
python3 tools/dev/inspect-apk.py --variant release --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03/runtime-final
```

Device commands use the explicitly selected authorized Pixel endpoint:

```sh
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-source-tests.py --real-sources --output-dir artifacts/p03/provider-real-v1
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-source-tests.py --real-sources --family tubepornclassic.com --output-dir artifacts/p03/tube-pixel-v3
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-source-tests.py --real-sources --family www.eporner.com --output-dir artifacts/p03/eporner-pixel-v2
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-source-tests.py --real-download --output-dir artifacts/p03/provider-complete-download-v5
ANDROID_SERIAL=<authorized Pixel> python3 tools/dev/run-source-tests.py --reuse-installed-app --output-dir artifacts/p03/runtime-controlled-final
```

Failed attempts remain retained. The first full-file proof rejected its cap before
transfer; the second exposed the HTTP timeout, the third exceeded the original
four-minute budget, and the fourth was canceled at ~50 MB following activity pause.
The retry has a nine-minute bounded transfer budget and a 72 MiB read ceiling.

## Next integration and cleanup

P04 is READY; its detailed Sol prompt is in the chat outputs. P05/P06 consume the
real resolver and scopedMediaDataSource, integrate their singletons and use typed
Unavailable explanations. P06 must own fresh-resolution/validator/cache recovery
and production background lifetime. P07 must handle TS timestamps correctly;
never generalize the narrow fMP4 spike into a full export engine.

Retain Pixel remote access across phases per the user's current instruction.
Stop test jobs, restore screen timeout and clean only own lab data after the batch.
Preserve personal.db, catalog generations, hotspot/data/internet and the Realme.
Final restoration/cleanup passes in runtime-final/retained-access-cleanup.json:
lab data/test APK removed, own process stopped/audio outputs zero, personal DB and
catalog generations present, timeout restored to 1,800,000 ms, network unchanged
and TCP 5555 shell retained. Final controlled and full-transfer app SHA256:
`e9b2647fc16a8a34c117922ad637a5fe0c34f667ce2bc654d75643c2eedcecd1`.
Test SHA256: `686a8d36890cf806939e0cb244cafbf87c7d1124c6de4fd8a16918f1c69e3c5c`.
The current full build/lint has zero errors/fatals and 41 warnings.
