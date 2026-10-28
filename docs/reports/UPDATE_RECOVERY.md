# Update foundation — in progress

The catalog signature validator accepts only an injected trusted Ed25519 key,
a matching channel/key ID, supported version, bounded sizes, valid digests and
HTTPS on an allowed host. It checks the signature over exact bytes and rejects
catalog sequence rollback or different bytes at the same accepted sequence.
Fixture unit tests cover tampering, wrong key, rollback and untrusted URLs.
No production trust key is configured, no remote check/download/activation or
APK update is wired, and rollback-floor persistence is pending. Settings says
updates are unconfigured instead of falsely reporting that none are available.

The owner confirmed this will be Eroflix's first release. The repository origin
is identified locally, but the production release channel and manifest key are
not configured. No APK signing identity or secret has been created yet; signing
and update acceptance remain prerequisites before release.

Local tests: `artifacts/p07/update-verifier-final.log` passed all network tests;
`artifacts/p07/privacy-updates-build-v2.log` passed app privacy-policy tests.
`artifacts/p07/release-setup-build.log` passed unsigned debug/release assembly.
The verifier is not connected to a live updater and is not production acceptance.
