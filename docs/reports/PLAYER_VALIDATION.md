# P05 production player validation — 2026-10-28

**State: IMPLEMENTED.** The production player and current automated acceptance
pass on Pixel 6 / Android 17 API 37. Remaining manual/hardware and broader lifecycle
checks below keep P05 from VERIFIED. P04 remains IMPLEMENTED with manual
TalkBack/actual split-screen acceptance pending. P06/P07 engines are not implemented.

## Implemented and reviewed

One MediaSessionService creates/releases one production ExoPlayer/MediaSession;
Compose borrows its native surface. Exact-source resolver outcomes produce visible
resolving/native/web/failure states. Admission tokens reject late results after
Home, route exit, lock or Stop; service creation rejects missing app preparation
or visible user admission. A restored route restores context without autoplay.

The screen provides pause/play, timeline and skip, speed, fit/crop, rotate, touch
lock, actual track choices, source choice, content-URI subtitle loading and explicit
per-session background audio. The pointer router arbitrates one-finger zones,
center scrub, two-finger pinch, double-tap and temporary hold speed without blocking
system Back edges. PiP presentation uses a full-window surface and hides chrome;
MainActivity supplies aspect/actions and lock precedence hooks.

PCM16 gain uses bounded soft saturation, unity byte preservation and explicit
empty/partial/reused-buffer handling. Float output and offload are disabled in this
chain. The output-buffer fix was required by an actual Media3 sink failure, not
inferred from a pure arithmetic test. No perceived-loudness claim is made.

Progress/checkpoints are serialized and admitted only after an established native
timeline/resume decision. Exact source/fingerprint and compatible finite duration
are required for resume. Failed resolution cannot replace a saved position with
zero. Observations include real advancing intervals, speed and discontinuities;
P08 still owns durable watched-coverage/completion/auto-hide storage. No personal
or catalog schema migration occurred.

Inactive discovery now cancels repository/append work during background progress
revisions and refreshes current stamps on return, retaining its bounded window and
stable visible anchor. Narrow/IME layouts use accessible compact controls.

## Host and artifacts

- Full debug/test/unsigned-release build, lint and 71 JVM tests pass. The applicable
  full frozen gate is pip-view-dependency-host.log, following prior full passes.
  Source-only test additions subsequently pass resume-session-real-test-build.log
  and session-stop-test-build.log. No dependency version was upgraded; Media3 UI
  and test-session declarations use pinned 1.11.1.
- All 19 helper regressions pass, including wrong-phone rejection and restoration
  of navigation/display/font/rotation after instrumentation timeout.
- Both exact full-catalog APK inspections pass compressed/expanded hashes,
  286,207 titles / full-20261027-r4, API/ABI, debug signing and static 16 KiB
  alignment. Release DEX/manifest/assets exclude debug source/player harnesses.
  Release is unsigned; P11 owns signing. Runtime 16 KiB acceptance is supplemental.

Ignored logs/results live under artifacts/p05/. Each phone summary records the
actual installed app/test hashes. Final current app SHA256: edc38167fe3d2c4ce831829361b1254bf700c1b7cad746b42dc0ed69aa58e198.
Final test APK SHA256: ec484a2c48bf6dd0cea31174bb26e4137a52cf2941c244c290819279ad05f611.
Unsigned release SHA256: 7385494a275f7727d06f1d2c38e15310d3a43459ab14b9c284a0b50ae54c78f8.

## Physical evidence

| Check | Result | Evidence under artifacts/ |
| --- | --- | --- |
| Controlled production player / actual PCM pipeline | 3/3 tests pass | p05/production-device-v6/ |
| Delayed-result admission, lock, failed resolve and stale route | 3/3 pass | p05/race-device-final/ |
| Explicit retained real source through production service | 1/1 pass | p05/real-provider-v1/ |
| Discovery integration regression | 5/5 pass on its recorded combined APK | p04/player-integration-regression-v1/ |
| Gesture Back cancel/commit, labels/48 dp/hold/keyboard/Home | 3/3 pass | p04/gesture-access-v2/ |
| Three-button navigation and narrow 720x1600 display | 3/3 pass; original settings restored | p04/three-button-narrow-v2/ |

The controlled harness substitutes only its isolated catalog/personal/checkpoint
owner and uses the actual production MediaSessionService/ExoPlayer/PlayerScreen.
It does not run the old lab's separate player or write user personal.db.

MP4, TS-HLS, fMP4-HLS and identity AES-128 HLS each advance actual H264 video frames
and AAC audio output buffers. Required fixture headers remain present. Selected
video/audio tracks and active PCM processing are observed. The same engine is
reused through recreations. Controlled snapshots range about 138–165 MiB PSS;
these are short debug tests, not a 1080p/two-download thermal/reliability run.

A saved same-source position of 3,000 ms is restored after reopen with the same
engine. A real MediaController pauses, plays and stops the MediaSession. Subsequent
play has no admitted route, no media items and no own-app active audio. ExoPlayer
retains playWhenReady intent across Stop; that flag alone is not evidence of audio.

Physical surface input proves hold cancellation restores speed, left brightness
cancellation restores the previous window value, pinch avoids scrub, and touch
lock can unlock. A content-URI VTT renders an actual cue after text was disabled.
Default Home pauses without PiP and rejects external-style resume. Manual harness
PiP enter/return preserves the same player; an attached native PlayerView fills
598x336 content pixels without normal chrome. This does not establish every
MainActivity auto-entry/RemoteAction/process-relaunch case.

The actual AudioProcessingPipeline regression passes empty polls, partial input
limits, repeated chunks and prior-output aliasing. Unity PCM bytes match exactly;
2x processing amplifies low-level samples and remains bounded. Own application
logs for the final controlled run have no AudioHardening, foreground-service
start-denial, SecurityException or fatal-exception markers. This is limited to
that run, not proof for untested routes/devices.

The real-source experiment retains Tube frozen index 0's exact title/source IDs
and original URL from sample SHA256
54ecef86fd6574f875561cce9ae7eeceda8a898ac54f2a553c367e82fad3556e.
It uses neutral display metadata, isolated personal state and the actual resolver,
service and native surface. H264/AAC advances beyond two seconds in 8,316 ms;
media reads total 3,163,546 bytes, below 5 MiB. Resolver/WebView traffic is outside
that media-byte count. It requests a 360p selection ceiling where available and
has a 25-second bound. No full provider file, population success rate, audible
perception, full-title identity or 1080p coverage is inferred.

## Failures retained and corrected

Earlier logs are retained. The first native service attempt exposed self-copy of
Media3's empty buffer at unity gain; pipeline handling/regression fixed it. A
separate gesture detector consumed one-finger motion and prevented brightness;
the explicit pointer router fixes it. PiP inspection initially used Compose's
registry, which excludes paused roots; the final test waits for the actual attached
native view and compares measured/global content bounds. The first new session
assertion incorrectly treated retained playWhenReady as resumed playback; the
final assertion checks real media/admission/audio state. None of those failed
batches is counted as passing.

## Remaining acceptance and P06 handoff

Keep these specific checks open: actual MainActivity auto-PiP and pause/play/skip
RemoteActions, lock/close/process relaunch combinations, full native→contained-web
transition and renderer/audio cleanup, real SAF picker/grant/parse recovery,
SRT and separate HLS audio/subtitle variants, focus/call/unplug/Bluetooth route
changes, actual quality/mirror switching, startup/dropped-frame/leak-cycle and
long 1080p/thermal workloads. TalkBack perception/focus and true split-screen
remain manual P04/P08/P10 work. A narrow display override is not split-screen.

The in-player download event retains exact TitleSummary/SourceHandle and shared
scoped network policy. Its flat Format.id set is advisory: IDs may collide across
groups/types or be null. P06 must re-resolve DOWNLOAD, show/confirm real tracks and
map typed group/kind/format choices before durable queue admission. No selected-
quality guarantee, background download queue or MP4 export is claimed here.

Sol's next detailed assignment is saved in chat outputs as
`eroflix-p05-sol-closeout-prompt.md`. Sol stopped at a usage limit after implementation;
coordinator completed the saved production tests and added resume/session checks.

## Phone cleanup

retained-access-cleanup.json confirms no test package or own running test/app
process, zero active own audio, no leftover own P05 cache/checkpoint files, and
personal DB/catalog preserved. Original font1.0, rotation0/0, timeout1,800,000 ms,
gesture navigation and physical1080x2400 display are restored. Airplane0/WiFi1/
mobile-data1 unchanged. TCP ADB5555 stays active until all phases finish or the
user explicitly requests earlier shutdown; Realme is untouched.

## New MainActivity closeout checkpoint (physical run pending)

Sol added MainPlayerIntegrationTest against real MainActivity/Shell with disposable
in-process repositories and the production service. The full host gate passes
in2m25s (main-web-interval-host.log), and both exact full catalog/release exclusion
inspections pass (main-closeout-apk/). Current app SHA256
bb681599d048ce3e264573a77777004fe7f3f97152fb5c49565989f875b9ae17,
test e706f42f1a3342f3f20056d8aa367fa6ec3431ce42b03842395e644217dac44b,
unsigned release4c24124f14a1df00e1ffc1b98013c4959a72a8c428569e9e54b6ecba8f71dc6d.
No new physical acceptance is inferred here; prior passing hashes above remain
their dated evidence. The coordinator has the stable APKs for an explicit run.

This test adds MainActivity auto-PiP/actions/lock and native→neutral-web audio
cleanup, real transient focus/noisy injection, and no repeated paused personal
revisions. Periodic progress is gated on actual playing, and seek/speed boundaries
reset/split pending observations. The new test asserts actual unmuted advancing
web video plus own-UID audio before Home and detached WebView/zero own audio after.
A debug-only UI container switch avoids modifying user catalog/personal files;
its object is excluded from release. Details and unresolved manual/hardware cases
are in the updated P05 handoff. P05 stays IMPLEMENTED pending recorded outcomes.

## Physical closeout attempts — 2026-10-28

The frozen `main-focus-web-host.log` build passed. Full-catalog debug and unsigned
release APK inspections passed in `closeout-apk-final/`; the release excludes the
UI test container. On Pixel, the Bluetooth attempt detected a connected media output
and one active app audio stream but Android did not expose a route for the test to
prove that stream used Bluetooth. The test reported inconclusive and did not ask
for a disconnect (`bluetooth-device-v1/`). The MainActivity test reached the web
check, where its JavaScript callback crashed while parsing a null result; this is
an incomplete test, not evidence that the web player passed or failed
(`main-player-device-v2/`). Earlier synthetic noisy broadcast was rejected by
Android as protected; it is not a valid headphone-disconnect test. Sol is fixing
the test polling and investigating direct observation of the playing audio route.
Both runs report restored settings, unchanged network and retained remote access.
