#!/usr/bin/env python3
"""Require explicit device selection before Gradle can install instrumentation APKs."""
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial:
        print("Set ANDROID_SERIAL to the authorized API 37 test device before running connected tests.", file=sys.stderr)
        return 1
    root = Path(__file__).resolve().parents[2]
    adb = root / "local-data/toolchain/android-sdk/platform-tools/adb"
    def run(*args):
        result = subprocess.run([str(adb), *args], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError("adb failed; check the selected device connection and authorization.")
        return result.stdout.strip()
    try:
        rows = [line.split()[:2] for line in run("devices").splitlines()[1:] if len(line.split()) >= 2]
        if [serial, "device"] not in rows:
            raise RuntimeError("Selected test device is absent or unauthorized.")
        api = run("-s", serial, "shell", "getprop", "ro.build.version.sdk")
        if api != "37":
            raise RuntimeError("Connected tests require API 37; the selected device does not match.")
        model = run("-s", serial, "shell", "getprop", "ro.product.model")
        print(json.dumps({"test_api": 37, "test_model": model, "selection": "explicit"}))
        return 0
    except (OSError, subprocess.TimeoutExpired):
        print("Could not inspect the selected test device; no test APK was installed.", file=sys.stderr)
        return 1
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
