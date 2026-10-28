# P10 — Pixel 6 acceptance, performance and reliability

**Role:** Device QA/performance agent
**Prerequisites:** P00 physical Android 17 baseline; integrated P04–P09 features
and predecessor build/fixture evidence
**Owns:** `benchmark/`, `tools/device/`, acceptance fixtures/reports; fixes in
feature/data/media code coordinated with the relevant owner
**Requirements:** R01–R26 validation coverage; final signing/delivery proof in P11

## Goal

Prove the integrated app on the user's actual Pixel 6 running Android 17,
including its full-size catalog, real source behavior and interrupted-work
recovery, with reproducible evidence for the release agent.

## Read first

[Validation matrix and budgets](../VALIDATION.md),
[platform baseline](../ANDROID17_PIXEL6.md), [roadmap](../ROADMAP.md),
[status](../STATUS.md), and all predecessor reports/handoffs.

## Work packages

### P10.01 — Reproducible acceptance candidate

- Record source revision, resolved toolchain, app/build ID, APK hash, catalog
  generation/counts, Pixel model/build/API, page size and free space.
- Run build/lint/unit/Android integration gates on the integrated revision.
  Use release-like optimization for performance, with a profileable variant
  and controlled test settings distinct from the production release identity.
- Create benchmark journeys and exact commands for launch, search/facets,
  details, steady scrolling and repeated player/WebView navigation.
- Set up deterministic fixture transfer/update endpoints and device selection.
  Record initial network, refresh, brightness, charging/power and thermal state.
- If Pixel 6 or Android 17 is unavailable, finish independent tooling/tests
  and leave physical acceptance pending. A different phone/emulator is not a pass.

### P10.02 — End-to-end device matrix

- Run V01–V29 from VALIDATION with their specified fixture and device scope.
  Link existing results only when their revision/configuration remains valid;
  rerun checks impacted by integration or fixes.
- Cover fresh airplane-mode catalog provision; large catalog search/filter/
  entity/hidden counts; back-stack/scroll restoration; and absent-title library.
- Exercise native HLS/file and contained web playback, tracks/mirrors/subtitles,
  gain/gestures, PiP, audio interruption, calls and Bluetooth route change.
- Exercise both download entry points, user pause, network loss, reboot,
  force-stop/reopen, service timeout, notification denial, >4 GiB and low-space
  handling with controlled fixtures in the isolated test app.
- Verify independent exported-file seeking/A/V sync and failure retry; then
  catalog/update failures, fingerprint/PIN and external-entry lock behavior.
- Record V26's test-signer upgrade results here. P11 owns the additional
  production-signer/final-APK installation rehearsal.

### P10.03 — Performance and resource measurement

- Measure VALIDATION's startup/query/facet/detail p50/p95/max with required
  sample counts, cache conditions and full-catalog generation identified.
- Record 60 Hz and 90 Hz list traces, large-font/TalkBack usability, memory PSS
  and 20 browse/player/back cycles. Include WebView renderer memory when active.
- Run the 30-minute supported 1080p playback session and 20-minute playback +
  two downloads thermal journey; record hardware decoder, dropped frames,
  battery delta, thermal status and any adaptation or stalled work.
- Separate provider/network startup delay from controlled fixture performance.
  First-run extraction is measured separately from provisioned cold launch.
- Attribute failures with traces/ApplicationExitInfo rather than increasing
  cache limits or declaring timings acceptable without evidence.
- Add a baseline profile for stable hot journeys if measurements justify it;
  document the generation/install method and verify any claimed improvement.

### P10.04 — Android 17 and standalone checks

- Confirm target-37 audio/foreground-service behavior, predictive Back, edge-to-
  edge cutout/IME insets, resize/rotation, storage and installer transitions.
- Verify packaged native libraries' ELF/APK alignment, then run V28 on a
  compatible 16 KB emulator/device. Record its page size and results separately
  from the measured Pixel 6; a 4 KB phone does not establish 16 KB compatibility.
- Remove test-only reverse tunnels/proxies, disconnect the laptop and run V29:
  catalog/search, sampled real-provider playback, a completed download and its
  airplane-mode playback. Record the network/host sample and observed limits.
- Test both navigation modes, font sizes 1.0/1.3/2.0 and secure recents/lock
  surfaces. Keep private-window behavior active during acceptance.
- Restore every temporary device setting and stop fixture servers/emulators
  started for these checks. Keep raw diagnostics in ignored artifact paths.

### P10.05 — Findings, fixes and release gate

- Produce a row for every V scenario and R requirement: result, tested revision,
  evidence, remaining final-artifact check and exact defect/owner if failing.
- Prioritize data loss/corruption, lock bypass, crash/ANR and unusable flows;
  reproduce and assign focused fixes to owners, then rerun the impacted checks.
- For a measured budget miss, fix the cause or record an explicit architecture/
  acceptance revision with evidence and user-visible effect. Pending is not pass.
- Record provider/format limits from P03/P07 and R26's explicit decision. Missing
  hardware/configuration cannot be disguised as an unsupported product feature.
- Reconcile predecessor status with real handoffs. P10 verification requires
  all its applicable checks and required P00–P09 gates complete; list P11's
  final signer/channel/artifact proof as the next release task.

## Acceptance

- [ ] Integrated build/lint/host/Android suites pass on the recorded revision.
- [ ] Physical Pixel 6 is measured running API 37; required device scenarios pass.
- [ ] V01–V29 evidence is complete for P10's scope with final-artifact repetition
  assigned explicitly to P11.
- [ ] Performance/resource targets pass or have a documented, evidence-backed
  revision; there are no unresolved required device measurements.
- [ ] Standalone operation passes with the laptop disconnected.
- [ ] Native alignment and supplemental 16 KB runtime checks pass.
- [ ] No unresolved data-loss, crash/ANR, lock-bypass or unusable-flow defect
  remains; limitations/deferred optimizations have accurate release-note inputs.

## Evidence and handoff

Create `docs/reports/PIXEL6_ACCEPTANCE.md` and
`docs/handoffs/P10-device-validation.md` with the complete matrix, benchmark
summary, artifact/revision IDs, defect closure and cleanup record. Next:
**P11.01** using this validated source revision.
