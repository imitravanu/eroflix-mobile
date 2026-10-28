#!/usr/bin/env python3
"""Run selected Eroflix discovery/player acceptance without clearing app data."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APP = "tech.imitravanu.eroflix.debug"
spec = importlib.util.spec_from_file_location("device_results", ROOT / "tools/dev/run-device-tests.py")
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", default="artifacts/p04/device")
    parser.add_argument("--test-class", default="tech.imitravanu.eroflix.DiscoveryDeviceTest")
    parser.add_argument("--font-scale", choices=("1.0", "1.3", "2.0"))
    parser.add_argument("--navigation-mode", choices=("current", "gesture", "three-button"), default="current")
    parser.add_argument("--narrow", action="store_true", help="Temporarily use a 720x1600 display, restoring the original override afterward.")
    parser.add_argument("--log-tag", choices=("EroflixP04", "EroflixP05", "EroflixP07"), default="EroflixP04")
    parser.add_argument("--allow-real-provider", action="store_true", help="Explicitly enable only the bounded P05 real-source experiment.")
    parser.add_argument("--allow-bluetooth-disconnect", action="store_true", help="Enable the user-coordinated physical headphone disconnect test.")
    args = parser.parse_args()
    if not re.fullmatch(r"tech\.imitravanu\.eroflix\.[A-Za-z0-9_.#]+", args.test_class):
        raise ValueError("Select a concrete Eroflix instrumentation class or method.")
    real_source_class = "tech.imitravanu.eroflix.ProductionPlayerRealSourceTest"
    if args.allow_real_provider and args.test_class.split("#")[0] != real_source_class:
        raise ValueError("Real-provider mode requires the explicit production real-source test class.")
    if args.test_class.split("#")[0] == real_source_class and not args.allow_real_provider:
        raise ValueError("Select --allow-real-provider explicitly before running the real-source experiment.")
    bluetooth_class = "tech.imitravanu.eroflix.ProductionBluetoothDisconnectTest"
    if args.allow_bluetooth_disconnect != (args.test_class.split("#")[0] == bluetooth_class):
        raise ValueError("Bluetooth disconnect mode requires the explicit Bluetooth test class and opt-in flag together.")
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial:
        raise ValueError("Explicit authorized ANDROID_SERIAL is required.")
    base = [str(ROOT / "local-data/toolchain/android-sdk/platform-tools/adb"), "-s", serial]

    def run(command, check=True, timeout=60):
        value = subprocess.run(base + command, capture_output=True, text=True, timeout=timeout)
        if check and value.returncode:
            raise RuntimeError("Discovery device command failed; inspect local evidence.")
        return value.stdout.strip()

    def shell(*command, **kwargs):
        return run(["shell", *command], **kwargs)

    if shell("getprop", "ro.build.version.sdk") != "37":
        raise ValueError("Physical acceptance requires API 37.")
    if shell("getprop", "ro.product.device") != "oriole":
        raise ValueError("Physical acceptance requires the selected Pixel 6 (oriole).")

    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    with zipfile.ZipFile(apk) as archive:
        manifest = json.loads(archive.read("assets/catalog/manifest.json"))
        if args.allow_real_provider and "assets/source-lab/samples.json" not in archive.namelist():
            raise ValueError("The bounded real-provider experiment requires the explicit frozen debug sample asset.")
    if manifest.get("kind") != "full":
        raise ValueError("Physical discovery/player gate requires the full catalog APK.")

    def digest(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()

    output = ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    settings = [("system", "screen_off_timeout"), ("system", "font_scale"),
                ("system", "accelerometer_rotation"), ("system", "user_rotation")]
    before = {name: shell("settings", "get", space, name) for space, name in settings}
    network = {name: shell("settings", "get", "global", name)
               for name in ("airplane_mode_on", "wifi_on", "mobile_data")}
    # Use package keys so both enabled and disabled entries survive restoration.
    def navigation_overlays():
        return {package: state == "x" for state, package in re.findall(
            r"^\[(x| )\] (com\.android\.internal\.systemui\.navbar\.(?:gestural|threebutton))$",
            shell("cmd", "overlay", "list", "--user", "0"), re.M)}

    nav_before = navigation_overlays()
    active_before = [name for name, enabled in nav_before.items() if enabled]
    nav_target = {"gesture": "com.android.internal.systemui.navbar.gestural",
                  "three-button": "com.android.internal.systemui.navbar.threebutton"}.get(args.navigation_mode)
    if nav_target and (nav_target not in nav_before or len(active_before) != 1):
        raise ValueError("Selected navigation overlay is unavailable or cannot be restored exactly.")
    size_before = shell("wm", "size") if args.narrow else None
    size_override = re.search(r"Override size: (\d+x\d+)", size_before or "")
    summary = {"model": shell("getprop", "ro.product.model"), "api": 37,
               "generation": manifest["generationId"], "titleCount": manifest["titleCount"],
               "apkSha256": digest(apk), "testApkSha256": digest(test_apk),
               "testClass": args.test_class, "passed": False}
    if args.allow_real_provider:
        summary["realProviderExperiment"] = "explicit bounded P05 experiment"
    if args.allow_bluetooth_disconnect:
        summary["bluetoothDisconnectExperiment"] = "user-coordinated physical disconnect"
    try:
        shell("settings", "put", "system", "screen_off_timeout", "1200000")
        if args.font_scale:
            shell("settings", "put", "system", "font_scale", args.font_scale)
        if nav_target:
            shell("cmd", "overlay", "enable-exclusive", "--user", "0", "--category", nav_target)
        if args.narrow:
            shell("wm", "size", "720x1600")
        active_nav = [name for name, enabled in navigation_overlays().items() if enabled]
        summary["testedNavigationMode"] = "gesture" if active_nav == ["com.android.internal.systemui.navbar.gestural"] else "three-button" if active_nav == ["com.android.internal.systemui.navbar.threebutton"] else "current"
        if nav_target and active_nav != [nav_target]:
            raise RuntimeError("Selected navigation mode did not activate.")
        if args.narrow:
            summary["testedDisplaySize"] = shell("wm", "size")
        summary["testedFontScale"] = shell("settings", "get", "system", "font_scale")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        installed = shell("pm", "path", "--user", "0", APP, check=False).splitlines()
        path = next((line.removeprefix("package:") for line in installed
                     if line.startswith("package:") and line.endswith("/base.apk")), None)
        matching = path is not None and shell("sha256sum", path).split()[0] == summary["apkSha256"]
        summary["reusedMatchingApp"] = matching
        for artifact in ([test_apk] if matching else [apk, test_apk]):
            if "Success" not in run(["install", "-r", str(artifact)], timeout=300):
                raise RuntimeError("Discovery test installation failed.")
        shell("am", "force-stop", APP)
        instrumentation = ["am", "instrument", "-w", "-r", "-e", "class", args.test_class,
                           "-e", "navigationMode", summary["testedNavigationMode"]]
        if args.allow_real_provider:
            instrumentation += ["-e", "allowRealProvider", "true"]
        if args.allow_bluetooth_disconnect:
            instrumentation += ["-e", "allowBluetoothDisconnect", "true"]
        log = shell(*instrumentation, APP + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=420)
        (output / "instrumentation.log").write_text(log)
        summary["passedTests"] = results.parse_instrumentation(log)
        summary["passed"] = True
    finally:
        (output / "own-logcat.log").write_text(run(["logcat", "-d", "-s", args.log_tag + ":I", "*:S"], check=False))
        if args.log_tag == "EroflixP05":
            own_uid = shell("run-as", APP, "id", "-u", check=False)
            if own_uid.isdigit():
                (output / "own-app-logcat.log").write_text(run(
                    ["logcat", "-d", "--uid=" + own_uid, "-t", "1500"], check=False))
        for space, name in settings:
            if before[name] == "null":
                shell("settings", "delete", space, name)
            else:
                shell("settings", "put", space, name, before[name])
        if nav_target:
            shell("cmd", "overlay", "enable-exclusive", "--user", "0", "--category", active_before[0])
        if args.narrow:
            shell("wm", "size", size_override.group(1) if size_override else "reset")
        summary["navigationRestored"] = navigation_overlays() == nav_before
        summary["displaySizeRestored"] = not args.narrow or shell("wm", "size") == size_before
        summary["settingsRestored"] = all(shell("settings", "get", space, name) == before[name]
                                          for space, name in settings)
        summary["networkUnchanged"] = all(shell("settings", "get", "global", name) == value
                                          for name, value in network.items())
        run(["uninstall", APP + ".test"], check=False)
        shell("am", "force-stop", APP)
        summary["remoteAccessRetained"] = shell("getprop", "ro.product.device") == "oriole"
        summary["passed"] = all(summary[name] for name in ("passed", "settingsRestored", "networkUnchanged", "navigationRestored", "displaySizeRestored"))
        (output / "summary.json").write_text(json.dumps(summary, indent=2))
    if not all(summary[name] for name in ("settingsRestored", "networkUnchanged", "navigationRestored", "displaySizeRestored")):
        raise RuntimeError("Device settings restoration did not pass.")
    print(json.dumps(summary))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired):
        print("Device acceptance failed or incomplete; inspect its ignored output directory.", file=sys.stderr)
        sys.exit(1)
