# P08 — Library and privacy (in progress)

Library shelves support bounded searchable pages, Continue watching, Hidden,
individual and all-item unhide, and favorite/saved removal. Download Wi-Fi and
concurrency settings and preferred playback quality/background-audio defaults
are wired to their existing owners.

Grid size now has a persisted shared setting, selectable in Settings or Browse;
the catalog/entity grids and library use the same value. Physical UI acceptance
for this setting remains pending.

Personal database migration 3 adds an opt-in auto-hide setting. Played intervals
are stored transactionally per title, cycle, timeline and duration, merged without
counting gaps; seeks and unknown durations do not trigger auto-hide. Manual
unhide suppresses auto-hide until an explicit replay starts another watch cycle.
Replay is serialized behind queued progress observations.

Full-catalog debug build and media unit checks passed in `artifacts/p07/watch-final-build.log`.
Isolated Pixel data suite passed 17 tests in `artifacts/p07/watch-final-device.log`,
including migration failure/recovery, exact-90% boundary, replayed intervals,
unknown/incompatible timelines and manual unhide. UI replay and background-audio
settings still need device validation. Pixel settings/network were not changed.

P08 remains ACTIVE: PIN/biometric privacy lock, lifecycle/notification redaction,
remaining settings and full acceptance are not implemented or verified. Release
and signed-update phases remain pending.

Additional checkpoint: PIN verifier storage foundation uses a salted PBKDF2
verifier authenticated by a device Keystore key, with persisted bounded retry
delay. `artifacts/p07/pin-vault-device/summary.json` passed the isolated Pixel
test (including a missing-key failure); temporary key/preferences were removed.
It is not wired to enrollment or a UI and provides no app lock yet. Biometric,
lifecycle coverage, recovery and notification redaction remain required.

Privacy session policy is implemented and unit-tested as a standalone state
machine. It is not yet connected to app entry/lifecycle or biometric prompts;
it does not provide a working lock. Do not advertise privacy lock as complete.

Later checkpoint: the PIN controller and lock screen are now connected to app
entry, playback and lifecycle. Two isolated Pixel vault/controller tests passed
in `artifacts/p07/privacy-pin-device-v2/`; this is not end-to-end lifecycle
acceptance. Fingerprint and PIN recovery remain unfinished. The separate
BiometricVault is experimental and not connected to the app.

The Astra review identified failed PIN-disable persistence, navigation/result
loss on locking and wall-clock retry problems. Follow-up changes restore the
cached credential after a failed preference write, use boot-aware monotonic
retry deadlines, preserve the shell's saveable navigation state while locked,
and retain subtitle-picker results in the activity until the player returns.
`artifacts/p07/privacy-review-fixes-device/summary.json` records three passing
isolated Pixel vault/controller tests, including a simulated failed disable
and reboot cooldown. The preference rollback is best effort if storage remains
unwritable. Actual navigation and picker restoration, lifecycle security, and
fingerprint/recovery still need device evidence or implementation. The isolated
tests do not close these acceptance gates or prove all review findings resolved.

Fingerprint unlock and PIN recovery are now integrated behind AndroidX
BiometricPrompt with a separate authentication-per-use Keystore key. PIN
recovery prepares the replacement off the UI thread and only commits it after
the foreground and authentication epoch are rechecked. The final debug and
instrumentation APKs build (`artifacts/p07/fingerprint-recovery-final-build.log`).
Five isolated Pixel tests passed (`artifacts/p07/fingerprint-recovery-device/`),
including rejecting unauthenticated biometric operations and a late callback,
and changing a verifier only after commit. Two user-assisted test attempts
(`fingerprint-live-device/` and `fingerprint-live-device-v2/`) did not pass:
the first timed out while another app was in front; the second reported the
fingerprint prompt canceled or unavailable. Successful fingerprint unlock and
recovery have not been proven; the user's PIN and biometric enrollment were
not changed. This security-sensitive code still needs independent review,
real fingerprint and invalidation testing, and the full lifecycle acceptance.
Other phases, update distribution and release signing remain incomplete.

The user agreed to request independent Astra review of the fingerprint changes;
the prompt is saved outside the project at `outputs/eroflix-fingerprint-review.md`.
