# Pixel 6 device baseline

Date: 2026-10-27. Owner: Codex. Required target: Pixel 6 (`oriole`), API 37.
Status: P00 physical baseline measured and matches target.

## Measurements from the connected phone

| Property | Measured value |
| --- | --- |
| adb connection | One authorized device; explicitly selected by Pixel 6 codename |
| Model / codename | Pixel 6 / `oriole` |
| Installed Android / API | 17 / 37 |
| Security patch | 2026-10-05 |
| Reported ABIs | `arm64-v8a,armeabi-v7a,armeabi` |
| Memory page size | 4096 bytes (4 KiB) |
| Physical display size | 1080 × 2400 pixels |
| Logical display size | 1080 x 2400 pixels |
| Physical / logical density | 420 / 420 dpi |
| Data volume capacity / used / available | Approximately 110 / 94 / 16 GiB (rounded adb `df -h` values) |
| Refresh interval sample | 16666667 ns, approximately 60.0 Hz |
| Min / peak refresh settings | `0.0` / `Infinity` |

The refresh sample is an observation at measurement time, not a claim that the
phone always runs at that rate. `Infinity` is the returned setting value, not
a measured infinite refresh rate. The page-size measurement is 4 KiB; this phone
does not provide the separate 16 KiB runtime acceptance target.

## Commands and evidence

The serial was selected locally from the single authorized `device:oriole`
entry, then supplied as `ANDROID_SERIAL` to the baseline helper. The serial is
omitted from this report and the saved JSON.

- `adb devices -l`: one authorized Pixel 6.
- `tools/dev/with-toolchain.sh python3 tools/dev/device-baseline.py --output artifacts/device-baseline.json`: exit 0, `status=measured`.
- Read-only `getprop`, `getconf PAGE_SIZE`, `wm size`, `wm density`, `df -h /data`,
  refresh settings, `dumpsys SurfaceFlinger --latency`, and `dumpsys display`.
- Serial-free raw measurements: ignored `artifacts/device-baseline.json` and
  `artifacts/device-display-baseline.json`.

Earlier in P00 no device was connected. The measurements above supersede that
pending connection state. No device settings were changed and no APK was
installed for this baseline inspection.

## Remaining application diagnostics

P01 provides in-app codec inventory and BiometricManager capability diagnostics.
P00.03 explicitly permits those checks to remain pending until the foundation
app exists. Hardware-decoding support, fingerprint authentication, app UI,
performance and source playback have not yet been tested.

The listed P00 acceptance checks are now satisfied, including the actual device
identity/API, ABI, page-size, display and storage baseline. P00 is VERIFIED;
P01 is READY. Full Android 17 Pixel 6 application acceptance remains P10/P11.

## Helper review note

The [P00 review](P00_REVIEW.md) integrated the earlier supplemental display and
refresh commands into the reusable baseline helper. Its new schema includes a
UTC capture timestamp and error/partial states. The historical physical JSON
records above remain unchanged; the revised helper was tested using offline
fixtures while the phone was disconnected.

## P01 in-app diagnostics completed

The [P01 device report](P01_DEVICE_VALIDATION.md) records the actual diagnostics
reader and screen on Pixel 6 / API 37: 4 KiB pages, 26 video decoder/MIME entries
(11 marked hardware), and strong biometric authentication available. This
completes P00's deferred inventory hook; media compatibility and actual app-lock
authentication remain later-phase checks.
