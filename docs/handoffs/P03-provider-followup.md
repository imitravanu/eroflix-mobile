> Historical checkpoint. For current runtime results, transfer status and retained
> phone-access policy, read [source capabilities](../reports/SOURCE_CAPABILITIES.md)
> and [runtime handoff](P03-runtime-closeout.md). Earlier APK outcomes and shutdown
> statements below apply only to their dated batches.

# P03 provider follow-up — 2026-10-28

Owner: Codex / Sol. Existing checkout; work remains uncommitted. P03 stays
IMPLEMENTED and Gate A remains open. Pixel 6 / API 37 remains the target, explicitly
reconfirmed by the user. No phone installation or test ran during this follow-up.

## Result

The previous Tizam failure was caused by an unapproved host redirect. The original
`abv.tizam.org` entries redirect to `adv.tizam.org`, preserving the selected path.
The five frozen pages publish source tags on `video1.tizam.cc`, `video2.tizam.cc`
and `video4.tizam.cc`. These exact observed hosts are now permitted, and native
media uses the final page's origin Referer without query/fragment tokens.
All five original Tizam cases returned HTTP 206, valid MP4 signatures and byte-range
metadata in the laptop follow-up. This is transport evidence, not Android playback.

The WebView's first load now sends its provider Referer through the supported
[loadUrl header overload](https://developer.android.com/reference/android/webkit/WebView#loadUrl(java.lang.String,%20java.util.Map%3Cjava.lang.String,%20java.lang.String%3E)).
Previously only native resolver requests supplied it. Eporner's observed script
hosts `i.eporner.com` and `static-eu-cdn.eporner.com` are allowed as resources;
resource hosts cannot become top-level navigation destinations. The browser's
fixed Eporner sample reached 34.22 seconds / 4,292.49-second duration at 480p while
muted. This disproves a global dead-source assumption, but establishes neither
Android behavior nor audible audio. Tube's separate browser attempt failed; no
success is inferred. Browser network differences are not attributed to ECH/TLS
without evidence.

Media resolution now reads an 8 KiB prefix instead of 512 KiB for every candidate.
A large HLS manifest is fetched separately within its existing 512 KiB bound and
revalidated, so HTML returned during that second request cannot become media.
Up to three in-policy alternatives can be tried under the resolver deadline.

The bounded HTML reader handles actual `video/src` and `source/src` attributes,
including numeric entities and unquoted attributes. It skips comments, script and
style bodies, custom/lazy attributes and oversized tags without executing code.
Clear Fullerotic `flashvars` literals have a data-only adapter. Dynamic encrypted
replacement configurations remain web-only; current sampled Fullerotic pages are
not claimed as newly native. PornTrex embed preview files are explicitly prevented
from becoming the selected full title or a native download.

## Phone experiment improvements

The next real-source run can target visible Play buttons inside the provider's
player container, retry at most three gestures and map CSS viewport coordinates
to actual WebView pixels. It no longer commits one tap to a zero-sized/video-only
target. Results include observed entry Referer, gesture count, up to 32 blocked
hostnames and typed unavailable explanations. No full URL/credential is added to
these records. Accessible video advancement remains separate from audible audio
and verified content identity; ads and inaccessible cross-origin players cannot
be treated as a proven full-title path merely because the page loaded.

The controlled web test now asserts that its actual initial request exposes the
expected Referer. It is compiled, not run on this revision. The source-test helper
also rejects a non-Pixel device before installing or changing settings; an offline
regression proves the guard. The connected Realme was left untouched.

## Verification

| Check | Result | Ignored evidence |
| --- | --- | --- |
| Debug/test/unsigned-release assembly, lint, JVM aggregate | PASS, 44 JVM tests including 24 network cases; zero errors/skips/failures; no lint errors, 45 warnings | artifacts/p03/provider-verified-build.log, provider-jvm-summary.json, provider-lint-summary.json |
| Frozen inventory and redirect helper tests | PASS, 4 tests | provider-python-tests-final.log |
| Development helpers, including wrong-device rejection | PASS, 17 tests | provider-helper-tests.log |
| Both APKs against exact full catalog, hashes, API/ABI/alignment | PASS, 286,207 titles, full-20261027-r4; release excludes lab/sample | provider-final/apk-inspection.json, provider-final/release/apk-inspection.json |
| Same five Tizam records, normal TLS, bounded reads | 5/5 HTTP 206 MP4 prefixes, no playback claim | provider-followup-final/tizam-http.json |
| Eporner browser observation | One muted sample advanced; script hosts observed | provider-browser-observations.json |
| Current Pixel controlled and real-source rerun | PENDING | Prior phone revision remains in device-final/ and real-device/ |

The host observation helper originally forwarded urllib's old Host header across
redirects, producing a false redirect loop during diagnosis. It now forwards only
explicit read headers and lets the transport derive the new Host. Failed
diagnostic attempts are retained; the final five-case result uses the corrected
helper. Runtime OkHttp already rebuilt headers per hop and did not have this bug.

Exact final build:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease lintDebug verifyUnitTests -PsourceLab=true -PsourceSamplesDir=local-data/source-samples/p03-v1 -PcatalogDir=local-data/catalog-full-r4
python3 -m unittest discover -s tools/sources/tests -v
python3 -m unittest discover -s tools/dev/tests -v
python3 tools/sources/probe_tizam.py --sample-dir local-data/source-samples/p03-v1 --output-dir artifacts/p03/provider-followup-final
python3 tools/dev/inspect-apk.py --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03/provider-final
python3 tools/dev/inspect-apk.py --variant release --expected-catalog-dir local-data/catalog-full-r4 --output-dir artifacts/p03/provider-final
```

## Changed paths and compatibility

- `core/network/`: resolver, public player-data reader, HTTP/provider/web policies,
  WebView entry headers and blocked-host diagnostics, resolver regressions.
- `app/src/debug/RealSourceProbe.kt` and the controlled web instrumentation test.
- `tools/sources/probe_samples.py`, new `probe_tizam.py` and redirect regressions.
- `tools/dev/run-source-tests.py` and wrong-target regression.
- Status, contracts, source capability report and this handoff.

No dependency versions, catalog/personal schemas, durable source identities,
original URLs or domain-interface shapes change. All consumers were rebuilt.
The new APK was not installed over the phone preview or copied over the delivered
P02 artifact. Raw pages/handles and diagnostic responses remain ignored.

## Next gate and cleanup

On the next connected Pixel session, run the controlled three-test gate and retain
the same 45-case real-source denominator. Verify initial Referer, Tizam decoding,
Eporner resource/gesture behavior and any remaining blocked media hosts. Add only
measured provider-specific hosts. Fullerotic/Tube opaque player data and PornTrex
full-source selection still need provider work. Full real-provider downloads and
TS export remain pending; the old 12/45 Pixel result is not increased by the five
laptop MP4-prefix observations.

Pixel network debugging was not reactivated. The connected non-target Realme was
not controlled or reconfigured. No hotspot/data/airplane-mode settings changed.
No test servers/emulators remain; the temporary Eporner browser tab was closed and
the failed Tube tab is browser-managed temporary work. P00–P02 remain VERIFIED;
P04 remains READY, and no later phase was started.
