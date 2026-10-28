# Local Android toolchain

Run from `mobile/` on Linux x86_64 with Python 3.12 or newer:

```sh
python3 tools/dev/setup.py
tools/dev/with-toolchain.sh java -version
tools/dev/with-toolchain.sh gradle --version
tools/dev/with-toolchain.sh sdkmanager --list_installed
tools/dev/with-toolchain.sh adb devices -l
```

The setup installs official JDK, Gradle and Android tools under ignored
`local-data/toolchain/`. JDK, Gradle and command-line tools archives are checked
against pinned SHA-256 hashes. SDK packages use sdkmanager repository verification.
Setup changes only `sdk.dir` in ignored `local.properties`, preserving other
local settings. Only missing SDK packages enter the installer and its license prompts. It does not replace system Java or change shell startup files. Reruns
reuse verified archives; changing the lock requires rechecking official sources.
Installed SDK revisions are checked before installation; matching packages are
reused. Missing-package revisions must match the lock in the stable repository
listing before any SDK installation runs. Repository drift fails clearly and
requires an explicit lock review; the setup does not silently upgrade existing
matching packages. A final revision check guards against changes during installation.

Run Android build commands through the wrapper to select the pinned JDK and SDK:

```sh
tools/dev/with-toolchain.sh ./gradlew assembleDebug lintDebug verifyUnitTests
```

P01 creates `gradlew` and the Android build. Before that phase, the preceding
command is a documented consumer command rather than a working build command.

## Pixel 6 selection

Enable USB debugging and authorize the computer on the phone. Inspect the local
`adb devices -l` result, then explicitly select that serial in your shell:

```sh
export ANDROID_SERIAL='YOUR_PIXEL_6_SERIAL'
tools/dev/with-toolchain.sh python3 tools/dev/device-baseline.py \
  --output artifacts/device-baseline.json
```

The helper only reads properties and reports measured API/codename mismatches;
it never changes or upgrades the phone. Exit status 2 means device evidence is
pending/incomplete or the selected target differs from API 37 / `oriole`.
Exit status 3 reports a command/output error with serial-free diagnostic text.
A full measurement includes logical display dimensions/density and a sampled
refresh interval. Missing required measurements produce `partial`, not success.
A failed rerun preserves an existing successful output report and prints
`previous_output_preserved: true`; use a different output path to save that
failed attempt separately. Keep raw device output in ignored `artifacts/`.

## Host limits and test profile

This host has about 6.6 GiB RAM. P01 should use `org.gradle.workers.max=2`,
`org.gradle.parallel=false`, `org.gradle.jvmargs=-Xmx1536m`, and the in-process
Kotlin compiler. Run one build at a time. Prefer the physical API 37 Pixel 6;
if absent, document connected tests as pending. An API 37 x86_64 emulator is an
optional integration target when adequate memory/KVM are available; no emulator
is installed or started by these helpers.

For future debug network fixtures, use a debug-only HTTPS fixture or explicit
loopback-only cleartext override and `adb reverse tcp:PORT tcp:PORT`; remove the
mapping after tests. Never enable production cleartext globally. Actual LAN
fixtures need Android 17 local-network access handling. P01 owns the manifest
and test network configuration. Standalone acceptance removes every tunnel.

## Host integration check

Run `tools/dev/verify-toolchain.sh` to build an isolated neutral API 37 APK under
ignored `local-data/toolchain-smoke/`. This verifies the toolchain; it has no
launcher UI and does not establish application or physical-device behavior.

## Offline helper regression checks

```sh
python3 -m unittest discover -s tools/dev/tests -v
```

These use temporary SDK and adb fixtures, require no phone/network, and do not
replace the recorded physical baseline. See [P00 review](../../docs/reports/P00_REVIEW.md).


### API 37 device verification over USB or hotspot

Set ANDROID_SERIAL explicitly and run `./gradlew verifyAndroidTests` (add
`-PcatalogDir=local-data/catalog-full-r3` for the current full local catalog).
The aggregate builds the actual test APKs, then runs the data, appropriate
fixture/full app suite, and separate-process persistence probe through
`run-device-tests.py`. Full-catalog performance assertions remain mandatory.
Logs/summaries are ignored under artifacts/p02/final-device/.

AGP 9.4's connected runner produced exit 1 over network ADB while its protobuf,
XML and direct AndroidJUnitRunner results all said PASSED. The aggregate therefore
uses AndroidJUnitRunner directly, validates every status and final completion,
and rejects failures, skips, truncation and count mismatch. This changes the
reporting transport, not the suite or its acceptance budget. The original
`:app:connectedDebugAndroidTest` and `:core:data:connectedDebugAndroidTest` tasks
remain available for diagnosing that upstream runner behavior; they are not
claimed as passing when their exit is nonzero.

The helper never changes airplane mode, hotspot, Wi-Fi or mobile-data settings.
It temporarily extends screen timeout and restores the original value in finally,
removes only its test packages and reopens the preview. Its result parser has
host regressions covering false-pass cases. The user now requires Pixel network access to stay enabled across phases. Perform
the final network-access shutdown only after all project phases, or an explicit
earlier user request, as recorded in AGENTS.md. A host disconnect alone does not
close the phone listener.

### Focused discovery input and player checks

`run-discovery-tests.py` accepts a concrete Eroflix instrumentation class/method
and a distinct ignored output directory. It verifies the actual Pixel/API and full
catalog input, records APK hashes, preserves installed app data and rejects partial
or skipped instrumentation results. `--font-scale` selects 1.0/1.3/2.0.

For focused P04 physical input checks, select `DiscoveryAccessibilityDeviceTest`.
`--navigation-mode gesture` exercises actual edge-Back cancellation/commit;
`--navigation-mode three-button` uses the observed system navigation overlay.
`--narrow` temporarily sets 720x1600 for a narrow full-screen layout check. This
is not an actual split-screen check. All modified navigation/display/font/rotation/
timeout values are restored in finally, even if instrumentation times out; an
injected-timeout host regression verifies restoration. No network radio is changed
and no app data is cleared. Do not run two phone helpers concurrently.

P05 controlled service/race tests can use the same strict transport with the
explicit `--test-class` and `--log-tag EroflixP05`, saving under artifacts/p05/.
The controlled player harness needs a debug build with `-PsourceLab=true`; release
must exclude its assets/activity. It substitutes a single production service owner
only for disposable neutral fixture storage and restores the real app owner.
Actual P05 outcomes are recorded separately from earlier P03 lab measurements.
