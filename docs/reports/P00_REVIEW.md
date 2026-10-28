# P00 implementation review

Date: 2026-10-27. Reviewer: Codex. Scope: P00 setup/diagnostics, acceptance
criteria, retained host/APK evidence and the recorded Pixel 6 measurements.

## Outcome

P00 remains VERIFIED and P01 is READY. The retained API 37 build evidence and
actual Pixel 6 baseline support that state. The improvements below address
repeatability and error handling in the helpers. The phone was not reconnected
or queried during this review; the previously measured physical evidence remains
unchanged. In-app codec and biometric diagnostics remain P01 work as permitted
by P00.03.

## Findings and fixes

| Finding | Consequence before repair | Repair |
| --- | --- | --- |
| SDK revision checks happened after installation | A rerun could upgrade a matching installed SDK package before discovering that it no longer matched the lock | Validate installed revisions first, reuse matching packages, preflight the stable repository revisions for missing packages, and install only missing packages |
| adb failures used uncaught subprocess exceptions | A disconnect or timeout could put the selected phone serial in a traceback | Catch command failures and timeouts; return structured diagnostic errors with safe text |
| Logical display and refresh sampling existed only in one-off commands | Rerunning the documented helper did not reproduce the complete P00 physical baseline | Integrate logical dimensions/density and SurfaceFlinger refresh sampling; incomplete results return `partial` |
| Failed reruns overwrote prior device evidence | A later disconnected check could erase the last successful baseline JSON | Preserve an existing successful report on failed, pending, partial or mismatched attempts; print the latest attempt with `previous_output_preserved` |
| Setup replaced all of local.properties | Future developer settings could be lost on setup reruns | Replace only sdk.dir and retain other properties/comments |
| P00 acceptance checklist remained unchecked | The phase document contradicted the verified status board | Mark the evidence-backed criteria complete and link this review |

SDK preflight is intentionally fail-closed when the locked revision is no
longer offered for a missing package. It does not claim upstream archives will
remain available forever or silently select a new revision. Existing matching
packages are reused without entering the SDK installer. A final revision check
still catches a repository change between listing and installation.

## Validation

- `python3 -m unittest discover -s tools/dev/tests -v`: **12 passed**. Checks cover
  existing SDK reuse, installed/repository revision drift before mutation,
  missing-package installation, property preservation, corrupt-download rejection,
  complete display/refresh sampling, adb failure/timeout privacy, unauthorized
  devices, incomplete refresh data and preservation of successful evidence.
- `python3 tools/dev/setup.py`: **PASS** against the installed pinned environment.
- Existing smoke APK `apksigner verify --verbose`: **PASS**, v2 signature valid.
- Saved initial build log: API 37 APK build succeeded. This review did not
  rebuild unchanged app sources or claim a new device run.
- Existing physical records still show Pixel 6 / `oriole`, Android 17 / API 37,
  4 KiB page size, 1080×2400 logical display, 420 dpi, a 60 Hz sample and about
  16 GiB free data space. They were not replaced by fixture output.

Logs: ignored `artifacts/p00/review-tests.log`, `review-setup.log`, and
`review-signature.log`. The original device evidence remains in ignored
`artifacts/device-baseline.json` and `artifacts/device-display-baseline.json`.

## Scope limits and next assignment

No actual Eroflix application exists yet. The neutral smoke APK proves host
build/packaging configuration, not Compose, combined dependency compatibility,
bundled SQLite, native-library alignment, playback, or physical app behavior.
Those gates remain with P01 and later phases. Next: **P01.01 — application
foundation**, using the existing toolchain and its documented resource limits.
