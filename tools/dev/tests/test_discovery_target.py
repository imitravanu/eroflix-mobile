import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch


class DiscoveryTargetTest(unittest.TestCase):
    def test_wrong_phone_stops_before_install_or_settings_changes(self):
        source = Path(__file__).resolve().parents[1] / "run-discovery-tests.py"
        spec = importlib.util.spec_from_file_location("discovery_gate", source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            value = "37\n" if command[-1] == "ro.build.version.sdk" else "RMX1825\n"
            return subprocess.CompletedProcess(command, 0, value, "")

        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"ANDROID_SERIAL": "neutral-target"}), \
                patch.object(sys, "argv", ["run-discovery-tests.py"]), \
                patch.object(module.subprocess, "run", side_effect=run):
            module.ROOT = Path(temp)
            with self.assertRaisesRegex(ValueError, "Pixel 6"):
                module.main()
        self.assertEqual([command[-1] for command in calls], ["ro.build.version.sdk", "ro.product.device"])

    def test_instrumentation_timeout_restores_navigation_size_and_settings(self):
        source = Path(__file__).resolve().parents[1] / "run-discovery-tests.py"
        spec = importlib.util.spec_from_file_location("discovery_cleanup", source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        settings = {"screen_off_timeout": "1800000", "font_scale": "1.0",
                    "accelerometer_rotation": "0", "user_rotation": "0",
                    "airplane_mode_on": "0", "wifi_on": "1", "mobile_data": "1"}
        original = settings.copy()
        mode = "gestural"
        display = None
        calls = []

        def run(command, **kwargs):
            nonlocal mode, display
            args = command[3:]
            calls.append(args)
            reply = ""
            if args[0] == "install":
                reply = "Success"
            elif args[0] == "shell":
                action = args[1:]
                if action[0] == "getprop":
                    reply = {"ro.build.version.sdk": "37", "ro.product.device": "oriole", "ro.product.model": "Pixel 6"}[action[1]]
                elif action[0] == "settings":
                    if action[1] == "get":
                        reply = settings[action[3]]
                    elif action[1] == "put":
                        settings[action[3]] = action[4]
                    else:
                        settings.pop(action[3], None)
                elif action[:3] == ["cmd", "overlay", "list"]:
                    reply = "\n".join(f"[{'x' if mode == name else ' '}] com.android.internal.systemui.navbar.{name}"
                                      for name in ("gestural", "threebutton"))
                elif action[:3] == ["cmd", "overlay", "enable-exclusive"]:
                    mode = action[-1].rsplit(".", 1)[1]
                elif action[:2] == ["wm", "size"]:
                    if len(action) > 2:
                        display = None if action[2] == "reset" else action[2]
                    reply = "Physical size: 1080x2400" + (f"\nOverride size: {display}" if display else "")
                elif action[:2] == ["am", "instrument"]:
                    self.assertEqual("threebutton", mode)
                    self.assertEqual("720x1600", display)
                    self.assertEqual("2.0", settings["font_scale"])
                    raise subprocess.TimeoutExpired(command, 420)
            return subprocess.CompletedProcess(command, 0, reply, "")

        with tempfile.TemporaryDirectory() as temp:
            module.ROOT = Path(temp)
            apk = module.ROOT / "app/build/outputs/apk/debug/app-debug.apk"
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/catalog/manifest.json", json.dumps(
                    {"kind": "full", "generationId": "neutral", "titleCount": 5}))
            test_apk = module.ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
            test_apk.parent.mkdir(parents=True)
            test_apk.write_bytes(b"neutral-test")
            with patch.dict(os.environ, {"ANDROID_SERIAL": "neutral-target"}), \
                    patch.object(sys, "argv", ["run-discovery-tests.py", "--font-scale", "2.0", "--navigation-mode", "three-button", "--narrow"]), \
                    patch.object(module.subprocess, "run", side_effect=run):
                with self.assertRaises(subprocess.TimeoutExpired):
                    module.main()
            report = json.loads((module.ROOT / "artifacts/p04/device/summary.json").read_text())
        self.assertFalse(report["passed"])
        self.assertTrue(all(report[name] for name in ("settingsRestored", "networkUnchanged", "navigationRestored", "displaySizeRestored")))
        self.assertEqual(original, settings)
        self.assertEqual("gestural", mode)
        self.assertIsNone(display)
        self.assertFalse(any(args[:4] == ["shell", "settings", "put", "global"] for args in calls))
        self.assertFalse(any("clear" in args for args in calls))
