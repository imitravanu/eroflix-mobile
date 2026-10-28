# Android 17 and Pixel 6 platform baseline

**Verified against official documentation:** 2026-10-27.
**Physical phone measurements:** P00 measured the authorized Pixel 6 on Android 17 / API 37. See [DEVICE_BASELINE](reports/DEVICE_BASELINE.md); P01 app diagnostics remain pending.

## SDK and distribution decisions

| Item | Baseline |
| --- | --- |
| Android release / API | Android 17 / API 37 |
| `compileSdk` / `targetSdk` | 37 / 37 |
| `minSdk` | 37 for the initial Android-17-focused release |
| Application ID | `tech.imitravanu.eroflix` |
| Debug ID | `tech.imitravanu.eroflix.debug` |
| Primary ABI | `arm64-v8a` |
| Emulator ABI | `x86_64` debug/test when used on this Linux host |
| Delivery | Standalone signed APK installed on Pixel 6 |

Keep numeric API 37 as the required platform. Preview names and minor SDK
extensions are not prerequisites for this release.

### Toolchain starting point

Official AGP 9.4.0 notes list API 37 support, Gradle 9.6.0, JDK 17, and
Build Tools 36.0.0 as the default/minimum build-tools version. Use that
supported combination as the P00 starting point, then record the exact
resolved versions in `docs/reports/TOOLCHAIN.md`.

- AGP: **9.4.0** baseline.
- Gradle wrapper: **9.6.0** with distribution checksum verification.
- JDK: pinned **17 LTS** distribution; target Java/Kotlin bytecode consistently.
- Platform package: **`platforms;android-37.0`** (resolved package ID in the official SDK repository; major API remains 37).
- Build tools: use AGP's supported default; record what actually resolved.
- AGP 9 uses built-in Kotlin support. Follow its migration/configuration guide
  instead of blindly applying an older `org.jetbrains.kotlin.android` template.
- Pin a compatible Kotlin compiler/Compose compiler plugin, Compose BOM,
  Activity, Lifecycle, Navigation, Media3, SQLite, coroutines, and HTTP stack.
  Verify the combination with a real Gradle build; no dynamic `+` versions.
- Bundled SQLite introduces native libraries; verify arm64 packaging and 16 KB
  alignment rather than assuming a Kotlin-only APK.

The general Android 17 setup page contains older IDE/AGP suggestions. For a
new project, use the current AGP compatibility table and resolved SDK package
metadata as the toolchain authority. If a listed artifact cannot be resolved,
record the failure and choose a documented supported stable combination.

## Pixel 6 characteristics that affect the app

| Property | Planning baseline | Implementation consequence |
| --- | --- | --- |
| Device | Pixel 6, `oriole` | Physical acceptance reports identify the actual model/build |
| Processor | First-generation Google Tensor | Prefer supported hardware decoding; avoid routine transcoding |
| RAM | 8 GB LPDDR5 | Bounded paging/image caches; total RAM is not the app memory budget |
| Display | 6.4-inch, 1080×2400, 20:9 OLED, up to 90 Hz | Dark UI; test both 60/90 Hz; use dp/insets, not pixels |
| Storage | 128/256 GB UFS 3.1 depending on model/region | Read actual free bytes; reserve space for multiple generations/export |
| Removable storage | No built-in microSD slot | SAF works with available document/USB providers |
| Authentication | Fingerprint-capable phone | Use capability detection and BiometricPrompt; app PIN fallback |
| Audio | Stereo speakers and Bluetooth audio | Test gain, focus, headset disconnect, and route changes |

Query `MediaCodecList` and actual stream support at runtime. Camera recording
specifications are not proof that every video codec/profile is playable.
Record the actual memory page size; do not label Pixel 6 a 16 KB device without
measurement. Use a suitable emulator/device for the supplemental 16 KB check.

## Android 17 implementation obligations

| Area | What agents implement | Owner / acceptance |
| --- | --- | --- |
| Background audio hardening | User-triggered playback with MediaSessionService; background audio requires eligible `mediaPlayback` FGS with while-in-use capability | P05/P10: Home, PiP, screen-off setting, focus/volume; inspect `AudioHardening` failures |
| App memory limits | Bounded data/images/buffers; inspect ApplicationExitInfo for memory-related termination | P02/P10: memory traces and repeated navigation |
| Local-network access | Production runtime uses internet sources and on-device data; future LAN/casting features must explicitly implement API 37 local-network permission | P01/P03/P10: no hidden dependency on development LAN; LAN test fixtures only in test configuration |
| TLS / CT / ECH | Keep TLS validation intact; handle provider connection failures with per-source diagnostics | P03/P09/P10: actual selected HTTP stack against fixtures/hosts; no global trust-all workaround |
| Native code loading | Package native libraries through supported Android tooling; load no downloaded executable resolver code | P01/P03/P10: library packaging/load tests and 16 KB alignment |
| New MessageQueue implementation | Use public lifecycle/dispatch APIs and compatible libraries | P01/P10: no reflection into private framework queue internals |
| Secure content capture | Set `FLAG_SECURE` on sensitive windows; verify dialogs, player and lock transitions | P01/P08/P10: recent-apps and screen-capture behavior |
| Insets and predictive Back | Edge-to-edge layouts, cutout/gesture/IME handling, state-correct Back cancellation | P04/P05/P08/P10: both edges, drawers, keyboard and player |
| Resizing/orientation | Handle activity resize and multi-window; no reliance on large-screen orientation opt-outs | P04/P05/P10: split-screen/rotation smoke checks |
| IME after rotation | Explicit search-field focus/keyboard behavior when needed | P04/P10: rotate during search without losing the query |
| Background activity launch | Installer/auth/error UI follows visible user action | P08/P09/P10: notification/deep-link/installer cancellation flows |

### Existing platform rules still apply at target 37

- Declare foreground-service types and their required permissions.
- `dataSync` and `mediaProcessing` background service budgets are limited
  (currently six hours each per 24 hours, shared per type). Handle
  `Service.onTimeout(int, int)` and stop promptly while preserving work.
- A denied notification permission is a state to handle; service notifications
  still need correct creation and behavior under platform rules.
- Scoped storage uses app-specific files and SAF/content URIs. Downloads need
  no blanket all-files permission.
- APK updates use Android's system installer and the user's install-source
  authorization; keep the same package, signer, and increasing versionCode.
- Android's 16 KB native page-size requirements concern native ELF/APK
  packaging. SQLite database page size is a different setting.

## Phone baseline commands

Run after P00 installs platform-tools and the user authorizes USB debugging.
Choose one explicit serial locally; redact it from committed reports.

```sh
adb devices -l
adb -s "$ANDROID_SERIAL" shell getprop ro.product.model
adb -s "$ANDROID_SERIAL" shell getprop ro.product.device
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.release
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.security_patch
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$ANDROID_SERIAL" shell getconf PAGE_SIZE
adb -s "$ANDROID_SERIAL" shell wm size
adb -s "$ANDROID_SERIAL" shell wm density
adb -s "$ANDROID_SERIAL" shell df -h /data
```

P01 implements the in-app codec/BiometricManager diagnostics and completes
P00's device report once the foundation app exists. If the phone is on an
earlier OS, record that fact; use API 37 emulator testing while the user arranges
the OS update. Pixel acceptance remains pending until tested on Android 17.

## Official references

1. [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
   — numeric compile/target SDK 37.
2. [Get Android 17](https://developer.android.com/about/versions/17/get)
   — Pixel 6 appears in the supported device list.
3. [Target-37 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
   — local network, TLS, native loading, audio, secure content and layout.
4. [Android 17 all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all)
   — memory limits, IME and audio behavior.
5. [Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
   — eligibility, MediaSessionService and diagnostic guidance.
6. [AGP 9.4.0 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
   — API, Gradle, JDK and build tools.
7. [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
   — P01 must consult before applying plugins.
8. [Pixel hardware specifications](https://support.google.com/pixelphone/answer/7158570?hl=en)
   — Pixel 6 section: display, memory, processor and storage.
9. [Foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
   — download/export timeout handling.
10. [Gesture navigation compatibility](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav)
    — system gesture regions and selective exclusion.
11. [16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
    — P01/P10 must verify packaged native dependencies.
12. [AndroidX SQLite releases](https://developer.android.com/jetpack/androidx/releases/sqlite)
    — bundled driver and supported API surface.

Recheck relevant platform documentation when pinning the implementation or
changing target SDK. Record a dated change rather than silently following a
different API baseline.
