# P09 — Catalog and application updates

**Role:** Update/release infrastructure agent
**Prerequisites:** P02 generation store, P06 storage/download coordination,
P08 settings/lock integration
**Owns:** Update transport in `core/network/`, orchestration in `core/data/`,
update-only paths in `feature/settings/`, `tools/release/` manifest tooling;
app/manifest changes through the integration owner
**Requirements:** R03, R23, R24, R26; preservation checks for R16, R19–R21

## Goal

Deliver independently versioned catalog and APK updates that can be checked,
downloaded, canceled and recovered without losing personal or offline state.

## Read first

[Data contract](../contracts/DATA.md), [media contract](../contracts/MEDIA.md),
[platform](../ANDROID17_PIXEL6.md), [validation](../VALIDATION.md), and P02/P06/P08
handoffs. P07 can continue in parallel through shared reservation/lease APIs.

## Work packages

### P09.01 — Update channel and signed manifests

- Obtain the release repository/channel, HTTPS asset locations and public
  manifest-verification key from the owner configuration. Keep signing secrets
  outside the repository; an APK must not contain a GitHub access token.
- Implement versioned catalog and app manifest schemas and detached Ed25519
  signatures over the exact published UTF-8 bytes. Embed trusted public keys
  with stable key IDs; reject unknown keys, invalid signatures and unknown formats.
- Catalog fields follow DATA, including channel, monotonic catalog sequence,
  generation/schema/app compatibility, sizes and both artifact hashes. App
  fields include format/channel/key ID, package name, versionCode/versionName,
  minSdk, ABI, APK URL/length/hash and expected signing-certificate digest.
- Bound metadata/artifact sizes and redirects, validate HTTPS locations against
  the configured release hosts, and use the artifact hash from the verified
  manifest. A checksum fetched beside an unsigned artifact is not authentication.
- Separate app version, catalog schema version and catalog sequence. Reject
  lower remote catalog sequences, equal sequence with different content, and
  non-increasing APK versionCodes. Known-good local catalog recovery is allowed
  without lowering the recorded remote acceptance floor.
- Create deterministic publisher/verification fixtures with test keys. An
  unconfigured production channel has a visible unavailable state; fixture
  channel/signers never silently become production defaults.

### P09.02 — Catalog download and activation

- Add visible Check/download/cancel/retry/progress actions and optional inexact
  periodic metadata checks. Use WorkManager for bounded checks; large transfers
  follow an eligible, timeout-aware Android 17 transfer lifecycle.
- Coordinate the shared `dataSync` budget and disk reservations with downloads;
  no duplicate updater or unlimited parallel staging. Check both compressed
  input and decompressed size limits while writing.
- Fetch into staging and reuse P02's verified stage/activate/reopen/rollback
  protocol. Validate manifest/hash/schema/counts before reader cutover.
- Reconcile interrupted transport/activation after process restart. Preserve
  the last valid catalog and report failure without clearing personal data.
- Keep player/source snapshots and download/cache leases alive across catalog
  replacement. Invalidate paging/facets only after successful activation.
- On an APK upgrade, compare its bundled catalog with the active generation;
  an older bundle must not overwrite a newer compatible downloaded catalog.
  An incompatible schema requests a compatible generation/app version explicitly.

### P09.03 — APK transfer and system installer

- Check signed release metadata for the installed package/channel and a higher
  versionCode. Show version, size and release notes before the user starts it.
- Download to app-owned staging; verify length/hash and archive package, SDK,
  ABI and signer compatibility. Compare with the installed signing identity,
  not only with a certificate string supplied by the remote manifest.
- Launch Android's installer through PackageInstaller or a correctly scoped
  FileProvider URI from a visible user action. The system performs final APK
  signature verification; archive metadata inspection alone is not that check.
- Handle install-source authorization, denied permission, canceled install,
  low space and stale staged artifacts. Return through P08's lock gate while
  preserving pending attempt identity/status and the old working app.
- Do not restart an installer from a background check or automatically uninstall
  an existing app. On successful in-place upgrade, reconcile migration/update
  state and existing download intent before admitting new work.
- Exercise the flow with a dedicated test package/signer and two increasing
  versions. P11 repeats it with the production signer and delivered APK.

### P09.04 — Fault injection and preservation evidence

- Test invalid signature/hash, truncated artifact, wrong schema/minimum app,
  unsupported compression, excess expanded size, replayed sequence and offline
  check. Previously installed catalog/search/library remain usable.
- Kill/cancel at transfer, decompression, validation, reader drain, pointer
  publication, first reopen and old-generation cleanup boundaries. Verify the
  restart result is an old or fully validated new generation, never half-written.
- Run replacement while a query, playback and two downloads are active. Include
  a generation that removes a favorite, hidden, watched and downloaded title.
- Compare semantic personal rows, settings, lock enrollment, Media3 index and
  cache-only playback before/after. APK upgrade additionally exercises personal
  migrations; catalog update has no authority to migrate/delete personal state.
- Test wrong APK package/signer/version, tampered bytes, installer denial/cancel
  and successful in-place fixture upgrade. Preserve caller/installer URI scope.

### P09.05 — Delta decision and publishing handoff

- Measure two real full-generation artifacts: transfer size, compressed patch
  size, on-phone apply time, temporary space and recovery complexity.
- Full-generation updates are the first-release baseline. If a delta provides
  worthwhile savings, specify base/target hashes and schema, bounded patch
  format, signed metadata and deterministic staged output verification. Never
  mutate the live generation or execute arbitrary SQL supplied by a server.
- A wrong base, interrupted patch or unsupported delta falls back to a verified
  full generation. It must pass the same activation/recovery gates.
- If deferred, record R26 as a named follow-up with owner, acceptance tasks and
  measured rationale; label shipped behavior as full-generation updates.
- Document publisher commands, release asset layout, public-key rotation via a
  trusted app release, test-channel configuration and cleanup/rollback procedure.
  Pass these inputs and production configuration still needed to P11.

## Acceptance

- [ ] Signed manifest verification and channel/version/size rules pass fixtures.
- [ ] A newer full catalog downloads/activates with recoverable cancellation.
- [ ] V23–V25 pass at every specified boundary; V15/V20 still pass after updates.
- [ ] V26 passes on the isolated update-test identity, including successful
  in-place upgrade, wrong signer/package and canceled installation.
- [ ] No existing user state, downloads or newer compatible catalog is overwritten
  by bundled-asset initialization during an upgrade.
- [ ] Android 17 lifecycle/notification/lock integration is verified or the exact
  remaining Pixel check is recorded before P10.
- [ ] R26 has an explicit tested implementation or owned deferral with evidence.
- [ ] Production channel/public-key inputs are configured or named as release
  blockers; fixture evidence is distinguished from production-channel evidence.

## Evidence and handoff

Create `docs/reports/UPDATE_RECOVERY.md` and `docs/handoffs/P09-updates.md` with
manifest formats, fault-boundary results, semantic preservation comparisons,
delta decision and publisher commands. Next: **P10.01** after P07/P08; production
artifact signing and channel installation evidence are completed by **P11**.
