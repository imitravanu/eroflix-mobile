# Standalone export evidence

P07 ACTIVE; source implemented, acceptance pending.

| Input | Intended path | Evidence |
| --- | --- | --- |
| Progressive H264/AAC MP4 | Cache-only Media3 transmux → validated MP4 → SAF copy | Prepared production test; not run |
| Clear finite continuous TS HLS | Media3 HLS extraction/timestamps → MP4 transmux | Prepared production test; old P03 platform sample-copy spike was invalid and is not reused |
| Clear finite continuous fMP4 HLS | Media3 init/segment extraction → MP4 transmux | Prepared production test; old narrow P03 spike is background only |
| Separate audio | Selected stream keys into same asset loader | Candidate only; needs fixture proof |
| Encryption/DRM, discontinuities/live/partial HLS | Explicit failure, input retained | Conservative manifest/Transformer admission; runtime matrix pending |
| Subtitle-selected downloads | Explicit unsupported explanation | No silent subtitle-loss success; sidecar not implemented |
| Read-denied/non-seekable destination | Failed/unverified, download retained | Runtime provider matrix pending |

`artifacts/p07/first-compile.log`: PASS4m9s, first production/test compilation and 75 existing JVM cases; pinned media3-transformer1.11.1 dependency added without upgrading versions. Media3 source API inspected from its official 1.11.1 tag. `artifacts/p07/full-host-gate.log`: PASS5m4s/710 tasks: full debug/test/data-test/release/lint/JVM gate, including new pure export reservation and manifest-policy regressions. JVM aggregate [77, 0, 0, 0] (tests/failures/errors/skips). Final queued-lease/sanitized-error and standalone-decode test gate follows in artifacts/p07/final-lease-output-gate.log; hashes follow completion.

No P07 phone mutation was performed by implementation agent. No input is fetched during export; cache miss fails with input unchanged. No runtime or format acceptance is claimed from successful compilation.

Final cache-only gate PASS4m12s/568 tasks (`artifacts/p07/cache-only-final-gate.log`), following queued-lease/output gate PASS2m30s. Every asset-loader branch, including bitmap fallback, uses the cache-only factory; encoder creation is forbidden. Production test app SHA256 `33b7c205b729c5c924d62639a05a00033d0828126b1da482e1205c29d594e406`; test APK `779b7bcd70b6214e611c74eb8670962e68fc0fcd6c6188da645349bef5975ba1`. Neutral device suite started by coordinator; no result claimed yet.
