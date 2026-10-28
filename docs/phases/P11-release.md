# P11 — Signed APK, upgrade rehearsal and maintenance handoff

**Role:** Release integration agent
**Prerequisites:** P10 and required P00–P09 acceptance gates VERIFIED
**Owns:** Release build/signing wiring, `tools/release/` publishing integration,
`docs/RELEASE_GUIDE.md`, `CHANGELOG.md`, final release report and artifacts
**Requirements:** R01, R24 and final-artifact confirmation of R01–R26

## Goal

Deliver the exact signed arm64 APK tested on the user's Android 17 Pixel 6,
with working update continuity and instructions for building the next release.

## Read first

[Architecture](../ARCHITECTURE.md), [platform](../ANDROID17_PIXEL6.md),
[validation](../VALIDATION.md), [P09 publishing/update contract](P09-updates.md),
P10 acceptance report and [status](../STATUS.md).

## Work packages

### P11.01 — Stable release identity and configuration

- Freeze `tech.imitravanu.eroflix`, release versionName/increasing versionCode,
  SDK 37 and arm64 packaging. Mobile versions are independent of desktop and
  planning-document version numbers.
- Configure the owner's stable APK signing key via ignored local properties/
  environment or release secret storage. Record the public certificate digest
  and restore procedure; keep the private key/password out of source and reports.
- Configure P09's production channel, trusted manifest public key and publisher
  key reference. APK signing and manifest signing are distinct identities.
- Confirm the next versionCode exceeds every distributed build on this channel.
  Coordinate signing continuity before any production-package rehearsal install.
- Document missing owner inputs as exact release blockers; test keys establish
  fixture behavior but cannot establish production signing continuity.

### P11.02 — Production catalog and optimized build

- Build from the validated clean source revision using the pinned wrapper/JDK
  and explicit full-catalog input. Regenerate/verify the catalog via P02 tools;
  record input digest, exporter version, generation, counts and output hashes.
- Assemble the release APK with production assets, HTTPS policy and public
  update configuration. Verify no fixture catalog, test channel, debug launcher
  or local-development source URL is selected by the production build.
- Run release lint and relevant unit/integration checks, including shrinker-
  sensitive SQLite, Media3, BiometricPrompt, SAF and installer paths. Record
  mapping/native symbol artifacts needed to diagnose this release.
- Verify the APK signature, package, version/min/target SDK, supported ABI,
  native alignment and first-run asset contents using Android SDK tooling.
- Name/hash the final artifact once; record build/revision/toolchain inputs.
  A later rebuild creates a new candidate requiring its affected checks.

### P11.03 — Final APK install and upgrade rehearsal

- Fresh-install the signed candidate into a clean test environment and verify
  bundled catalog provision/browse in airplane mode. Preserve any existing user
  installation; use a dedicated test profile/device for destructive setup.
- On Pixel 6, upgrade from the prior signed release. For the first release,
  create a lower-versionCode rehearsal build with the same package/signer and
  seed controlled favorite/saved/hidden/progress/download/settings/lock state.
- Stage complete immutable candidate assets and signed manifests before this
  rehearsal. The lower rehearsal build can point to that candidate feed with
  the trusted production verification key; promote stable discovery metadata
  only after the rehearsal passes. Keep the candidate APK bytes unchanged.
- Perform the in-app update through the configured channel and system installer,
  then verify semantic state, completed offline playback and eligible queue
  reconciliation. No uninstall/clear-data step counts as an upgrade test.
- Check a newer active catalog survives an APK containing an older compatible
  bundle. Confirm lock enrollment and missing-catalog-title management survive.
- Run the final Pixel smoke: browse/search, supported real-source playback,
  PiP/lock, both download entry points, cache-only playback and standalone export.
  Repeat V26/V29's final-artifact scope and record the installed APK digest.

### P11.04 — Publish and install documentation

- Produce the APK, checksums, release notes, signed app/catalog manifests and
  catalog assets expected by P09. Release notes include measured provider/format
  coverage and the explicit full-generation/delta decision.
- Promote the tested candidate to the configured stable release channel,
  publishing discovery metadata last so only complete, hash-verifiable releases
  are exposed. Verify the downloadable artifact matches the tested phone APK.
- Write `docs/RELEASE_GUIDE.md` with clean-checkout build inputs/commands, key
  references, manifest publishing, Pixel 6 installation and in-place updating.
- Explain recovery accurately: local catalog fallback preserves personal state;
  app rollback after a schema migration requires a compatible forward fix or a
  proven migration strategy, not an assumed safe APK downgrade.
- Document app-private download lifetime and user-owned exported-file locations
  so installation/update instructions do not accidentally prescribe data removal.

### P11.05 — Maintenance and completion

- Document catalog refresh/audit cadence, source adapter fixture upkeep,
  provider outage diagnosis, schema migration tests and Android SDK revalidation.
- Record how to map an APK hash/version to source, symbols, catalog and signer;
  provide the next agent's exact build/test/update rehearsal commands.
- List open nonblocking limitations and named follow-ups with owners and
  acceptance criteria. Include R26 if delta transport was deferred.
- Link the final release report to all requirement evidence; update README,
  CHANGELOG and STATUS to the actual delivered state and artifact location.
- Hand the user the tested APK path/download URL, version and SHA-256, plus
  concise install instructions and the maintenance entry point.

## Expected verification commands

The release agent supplies actual SDK paths, artifact names and production
catalog/signing inputs. These commands become executable after P01/P02/P09:

```sh
./gradlew assembleRelease lintRelease verifyUnitTests
apksigner verify --verbose --print-certs "$APK"
zipalign -c -P 16 -v 4 "$APK"
apkanalyzer manifest application-id "$APK"
apkanalyzer manifest min-sdk "$APK"
apkanalyzer manifest target-sdk "$APK"
sha256sum "$APK"
```

ZIP alignment is one packaging check; use P10's ELF/runtime evidence too.
Record installer/upgrade results separately from host APK inspection.

## Acceptance

- [ ] Release identity, signer, channel and manifest verification keys are configured.
- [ ] Full-catalog optimized APK passes build/lint/tests and APK/native inspection.
- [ ] The signed candidate installs on Pixel 6 API 37 and passes final smoke.
- [ ] Same-signer in-place update preserves personal state, lock and downloads.
- [ ] Published artifact hash equals the tested artifact hash; channel update works.
- [ ] Install/build/recovery/maintenance guide and accurate release notes exist.
- [ ] Final report maps every R requirement to evidence or an explicitly permitted
  owned deferral; STATUS reflects actual completion.

## Evidence and handoff

Create `docs/reports/RELEASE.md` and `docs/handoffs/P11-release.md`. Include
versionCode/name, source revision, APK SHA-256, public signer digest, catalog
generation/counts, Pixel build, install/upgrade evidence and delivery location.
Next work is maintenance or the specifically recorded follow-up tasks.
