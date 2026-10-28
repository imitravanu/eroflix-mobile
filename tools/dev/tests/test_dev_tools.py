"""Offline regression tests for SDK mutation ordering and serial-safe diagnostics."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

DEV = Path(__file__).resolve().parents[1]

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, DEV / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

setup = load('p00_setup', 'setup.py')
baseline = load('p00_baseline', 'device-baseline.py')


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / 'sdk'
        self.lock = {'sdk_packages': ['platform-tools', 'platforms;android-37.0'],
                     'sdk_expected_revisions': {'platform-tools': '37.0.1', 'platforms;android-37.0': '2'}}
        patcher = patch.object(setup, 'LOCK', self.lock)
        patcher.start()
        self.addCleanup(patcher.stop)

    def install_fixture(self, package, revision):
        p = self.sdk.joinpath(*package.split(';'), 'source.properties')
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('Pkg.Revision=' + revision + '\n')

    def test_matching_sdk_is_reused_without_installer_or_network(self):
        for package, version in self.lock['sdk_expected_revisions'].items():
            self.install_fixture(package, version)
        with patch.object(setup.subprocess, 'run') as run:
            setup.ensure_sdk_packages('sdkmanager', self.sdk, {})
        run.assert_not_called()

    def test_installed_mismatch_fails_before_any_mutation(self):
        self.install_fixture('platform-tools', '99.0.0')
        with patch.object(setup.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'no SDK packages changed'):
                setup.ensure_sdk_packages('sdkmanager', self.sdk, {})
        run.assert_not_called()
        self.assertEqual(setup.sdk_revision(self.sdk, 'platform-tools'), '99.0.0')

    def test_repository_drift_fails_before_installer(self):
        self.install_fixture('platform-tools', '37.0.1')
        listing = 'Available Packages:\nplatforms;android-37.0 | 3 | Android platform\n'
        with patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, listing)) as run:
            with self.assertRaisesRegex(RuntimeError, 'No SDK packages changed'):
                setup.ensure_sdk_packages('sdkmanager', self.sdk, {})
        self.assertEqual(run.call_count, 1)
        self.assertIn('--list', run.call_args.args[0])
        self.assertEqual(setup.sdk_revision(self.sdk, 'platform-tools'), '37.0.1')
        self.assertIsNone(setup.sdk_revision(self.sdk, 'platforms;android-37.0'))

    def test_only_missing_locked_packages_are_installed(self):
        self.install_fixture('platform-tools', '37.0.1')
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            if '--list' in command:
                return subprocess.CompletedProcess(command, 0, 'Available Packages:\nplatforms;android-37.0 | 2 | Android platform\n')
            self.assertNotIn('platform-tools', command)
            self.assertNotIn('--licenses', command)
            self.assertIn('platforms;android-37.0', command)
            self.install_fixture('platforms;android-37.0', '2')
            return subprocess.CompletedProcess(command, 0)
        with patch.object(setup.subprocess, 'run', side_effect=run):
            setup.ensure_sdk_packages('sdkmanager', self.sdk, {})
        self.assertEqual(len(calls), 2)

    def test_local_properties_preserves_other_configuration(self):
        p = self.root / 'local.properties'
        p.write_text('# local configuration\ncustom.input=/a/catalog\nsdk.dir=/old\nndk.dir=/a/ndk')
        setup.update_local_properties(p, Path('/new/sdk'))
        self.assertEqual(p.read_text(), '# local configuration\ncustom.input=/a/catalog\nndk.dir=/a/ndk\nsdk.dir=/new/sdk\n')

    def test_bad_download_digest_never_publishes_archive(self):
        source = self.root / 'source.zip'
        source.write_bytes(b'not the expected archive')
        item = {'filename': 'fixture.zip', 'url': source.as_uri(), 'sha256': hashlib.sha256(b'expected').hexdigest()}
        with patch.object(setup, 'LOCAL', self.root / 'toolchain'):
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 mismatch'):
                setup.fetch(item)
        self.assertFalse((self.root / 'toolchain/downloads/fixture.zip').exists())


class BaselineTests(unittest.TestCase):
    serial = 'PRIVATE_TEST_SERIAL'

    def response(self, command, **kwargs):
        if command == ['adb', 'devices']:
            return subprocess.CompletedProcess(command, 0, 'List of devices attached\n' + self.serial + '\tdevice\n', '')
        # Never permit measuring an unselected device.
        self.assertEqual(command[:4], ['adb', '-s', self.serial, 'shell'])
        parts = command[4:]
        props = {'ro.product.model': 'Pixel 6', 'ro.product.device': 'oriole',
                 'ro.build.version.release': '17', 'ro.build.version.sdk': '37',
                 'ro.build.version.security_patch': '2026-10-05', 'ro.product.cpu.abilist': 'arm64-v8a'}
        if parts[0] == 'getprop':
            value = props[parts[1]]
        else:
            value = {('getconf', 'PAGE_SIZE'): '4096', ('wm', 'size'): 'Physical size: 1080x2400',
                ('wm', 'density'): 'Physical density: 420', ('df', '-h', '/data'): '110G 94G 16G',
                ('settings', 'get', 'system', 'min_refresh_rate'): '0.0',
                ('settings', 'get', 'system', 'peak_refresh_rate'): 'Infinity',
                ('dumpsys', 'display'): 'mBaseDisplayInfo=DisplayInfo{"Built-in", displayId 0, real 1080 x 2400, density 420 (420.0 x 420.0) dpi}',
                ('dumpsys', 'SurfaceFlinger', '--latency'): '16666667\n0 0 0'}[tuple(parts)]
        return subprocess.CompletedProcess(command, 0, value + '\n', '')

    def test_complete_measurement_includes_logical_display_and_refresh(self):
        with patch.object(baseline.subprocess, 'run', side_effect=self.response):
            result = baseline.collect(self.serial)
        self.assertEqual(result['status'], 'measured')
        self.assertEqual(result['measured']['logical_size'], '1080 x 2400')
        self.assertEqual(result['measured']['logical_density_dpi'], '420')
        self.assertEqual(result['measured']['surfaceflinger_refresh_hz'], 60.0)
        self.assertEqual(result['measured']['peak_refresh_rate_setting'], 'Infinity')
        self.assertIn('captured_at_utc', result)
        self.assertNotIn(self.serial, json.dumps(result))

    def test_failed_shell_is_safe_even_when_stderr_contains_serial(self):
        def run(command, **kwargs):
            if command == ['adb', 'devices']:
                return self.response(command, **kwargs)
            return subprocess.CompletedProcess(command, 1, '', 'device ' + self.serial + ' disconnected')
        with patch.object(baseline.subprocess, 'run', side_effect=run):
            result = baseline.collect(self.serial)
        self.assertEqual(result['status'], 'error')
        self.assertNotIn(self.serial, json.dumps(result))

    def test_timeout_does_not_expose_subprocess_arguments(self):
        def run(command, **kwargs):
            if command == ['adb', 'devices']:
                return self.response(command, **kwargs)
            raise subprocess.TimeoutExpired(command, 30)
        with patch.object(baseline.subprocess, 'run', side_effect=run):
            result = baseline.collect(self.serial)
        self.assertEqual(result['status'], 'error')
        self.assertNotIn(self.serial, json.dumps(result))

    def test_unauthorized_phone_is_not_queried(self):
        with patch.object(baseline.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0,
                'List of devices attached\n' + self.serial + '\tunauthorized\n', '')) as run:
            result = baseline.collect(self.serial)
        self.assertEqual(result['status'], 'pending')
        self.assertEqual(run.call_count, 1)
        self.assertNotIn(self.serial, json.dumps(result))

    def test_missing_refresh_is_partial_not_verified(self):
        def run(command, **kwargs):
            if '--latency' in command:
                return subprocess.CompletedProcess(command, 0, '0\n', '')
            return self.response(command, **kwargs)
        with patch.object(baseline.subprocess, 'run', side_effect=run):
            result = baseline.collect(self.serial)
        self.assertEqual(result['status'], 'partial')
        self.assertIn('refresh interval sample', result['missing_measurements'])

    def test_failed_rerun_preserves_successful_physical_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'baseline.json'
            old = '{"status":"measured","measured":{"api":"37"}}\n'
            path.write_text(old)
            result = {'status': 'error', 'reason': 'phone disconnected'}
            baseline.save_result(path, result)
            self.assertEqual(path.read_text(), old)
            self.assertTrue(result['previous_output_preserved'])


if __name__ == '__main__':
    unittest.main()
