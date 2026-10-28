# P08 — Personal library, settings and privacy

**Role:** Library/settings agent
**Prerequisites:** P04 discovery, P05 playback lifecycle, P06 download APIs
**Owns:** `feature/library/`, non-update settings/privacy paths in
`feature/settings/`; app lock/navigation hooks through the integration owner,
personal migrations through the data owner
**Requirements:** R06, R19–R22, R25

## Goal

Complete the personal experience: durable library management, predictable
hide/unhide behavior, useful settings and a Pixel 6 fingerprint/PIN lock that
covers every entry into protected content.

## Read first

[Data contract](../contracts/DATA.md), [media contract](../contracts/MEDIA.md),
[architecture](../ARCHITECTURE.md), [validation](../VALIDATION.md), and the
P04/P05/P06 handoffs. P07 may run in parallel through the declared export API.

## Work packages

### P08.01 — Library and retained metadata

- Implement Favorites, Saved for later, History and Continue watching using
  PersonalRepository streams, stable title IDs and bounded paging.
- Keep favorite, saved, hidden and watched states independent. Removing a
  favorite does not erase its progress, download or other personal markers.
- Apply hidden exclusion to ordinary library shelves; keep Hidden titles and
  Downloads as explicit management views. Hiding the currently playing item
  updates discovery without abruptly stopping playback or deleting its media.
- Display retained title/source metadata when a catalog record disappears;
  use a local poster or placeholder offline. A removed catalog title can still
  be unfavorited, unhidden, or played from its completed download.
- Persist mutations atomically and restore list/query/scroll state after
  navigation, recreation and process restart. Integrate offline play via P06.

### P08.02 — Hidden manager and watched policy

- Add searchable Hidden titles with retained metadata, one-item Unhide and
  transactional Unhide all; support large sets without loading every row in UI.
- Make card/details Hide consistent, with a short undo action. Invalidate
  browse, search, facets, entity pages and ordinary library shelves together.
- Implement opt-in auto-hide using DATA's watched-coverage rule: strictly more
  than 90% of a known finite timeline actually observed playing. A seek or
  restored resume position is not watched coverage; replayed ranges count once.
- Persist and merge played intervals across sessions through a versioned
  personal migration. Handle unknown duration, speed changes and incompatible
  mirror timelines explicitly; preserve the difference between watched and hidden.
- Provide an explicit Replay from start action that starts DATA's new watch
  cycle through the player API; scrubbing to zero is not the same action.
- Test exactly 90%, greater than 90%, seek-to-end, repeated playback, resume,
  toggle-off and manual Unhide. Unhide must not immediately be undone by an
  unchanged progress checkpoint; a new qualifying watch cycle can auto-hide again.

### P08.03 — Settings and resource controls

- Implement persisted playback quality ceiling (default 1080p), optional
  screen-off audio (default off), download concurrency (default 2, range 1–4),
  Wi-Fi-only downloads (default on), auto-hide (default off) and grid density.
- Define Wi-Fi-only using actual network transport, including a VPN over
  Wi-Fi; an unmetered cellular connection is not automatically Wi-Fi. Reconcile
  policy changes with eligible jobs while preserving explicit user pauses.
- Show catalog, download, transient-cache and export-staging storage separately.
  Clear-cache actions use owner APIs and protect durable downloads/active leases.
- Add lock setup/change controls and current app/catalog version information.
  Reserve the update section for P09's typed state/actions and assigned paths.
- Explain in the export UI that SAF files are user-owned; `.nomedia` and the
  app lock do not change another app's access to those exported files.

### P08.04 — Fingerprint/PIN state machine

- Provide first-run PIN enrollment with optional fingerprint unlock through
  AndroidX BiometricPrompt and runtime capability detection. Enable the lock
  after enrollment; changing/disabling it requires current authentication.
- Support a four-digit app PIN and longer numeric PINs. Store a salted,
  versioned derived verifier with Keystore-backed protection and a bounded,
  persisted retry delay; never store/log the PIN or restore an unlocked session.
  PIN-verifier protection remains usable when a biometric enrollment/key changes;
  keep those key purposes separate so PIN fallback still works.
- Model enrollment, locked, authenticating and unlocked states explicitly.
  Cold launch and return from the background require unlock by default. Cover
  protected content before its first frame; recreation and a biometric prompt
  must not create a repeated-authentication loop.
- An authenticated PiP window is still visible playback. Entering PiP or losing
  activity focus alone does not lock it. Trigger lock on explicit Lock, screen-
  off, or loss of all app/PiP visibility; returning from still-authenticated PiP
  retains the session unless one of those triggers occurred.
- Gate notifications, deep links, saved routes and return from SAF/installer
  screens. Retain the intended destination and resume it only after successful
  authentication; cancel leaves the lock screen usable.
- On lock, pause native/web playback, cover/exit PiP through the tested lifecycle
  hook, and redact media titles/artwork in notifications and session metadata.
  Existing downloads may continue with generic progress; locked controls cannot
  start playback. Lock takes precedence over the screen-off-audio setting.
- Handle fingerprint unavailable/unenrolled/lockout and invalidated key using
  app PIN. Allow PIN recovery through a still-valid enrolled biometric; document
  the case where neither credential is available without silently unlocking or
  automatically deleting personal data.

### P08.05 — Pixel privacy and interaction evidence

- Verify secure windows on launch, dialogs, player, authentication transitions
  and recents. Test screen-off, PiP, process death, notification/deep-link entry,
  and biometric/PIN cancellation and retry on the physical Pixel 6.
- Exercise P03 WebView containment/renderer cleanup through the final lock flow;
  no hidden web/native audio or exposed previous frame after returning.
- Polish the two in-app drawer handles with actual one-handed Pixel use;
  verify both system Back edges, predictive Back cancellation and home gesture.
- Run TalkBack, font scaling and focus restoration across library/settings/lock.
  Cover the final export UI when P07 is integrated, without blocking independent
  library implementation on P07 internals.
- Record privacy behavior, settings defaults, watched-state migration and all
  pending physical checks in `docs/reports/LIBRARY_PRIVACY.md`.

## Acceptance

- [ ] Favorites/saved/history/hidden state survives process restart and a local
  catalog replacement, including missing titles (V20/V25).
- [ ] V03/V21 pass: all discovery counts respect hidden state; auto-hide, undo
  and Unhide all follow the specified policy without erasing other state.
- [ ] Settings persist and change actual player/download behavior through owner APIs.
- [ ] V22 passes on Pixel 6: fingerprint/PIN, lifecycle, PiP and external entry
  paths preserve the lock boundary and correct media behavior.
- [ ] V06/V09/V27 integration checks pass for containment, gestures and accessibility.
- [ ] Shared schema/navigation changes and P09 update-section hooks are integrated.

## Evidence and handoff

Create `docs/reports/LIBRARY_PRIVACY.md` and
`docs/handoffs/P08-library-privacy.md`. Include persistence/lock scenario results,
defaults, migration version, gesture checks and pending Pixel evidence. Next:
**P09.01**; P07 export integration remains with its owner until complete.
