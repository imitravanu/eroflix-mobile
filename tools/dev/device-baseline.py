#!/usr/bin/env python3
"""Read a selected API-37 Pixel 6 baseline without disclosing its serial."""
import argparse
from datetime import datetime, timezone
import json
import os
import pathlib
import re
import subprocess
import sys


class AdbError(RuntimeError):
    """Safe diagnostic text: never include subprocess arguments or stderr."""


def adb(*args):
    try:
        result = subprocess.run(['adb', *args], capture_output=True, text=True,
                                timeout=30, check=False)
    except FileNotFoundError:
        raise AdbError('adb is unavailable; use tools/dev/with-toolchain.sh.') from None
    except subprocess.TimeoutExpired:
        raise AdbError('An adb command timed out; check the selected phone connection.') from None
    except OSError:
        raise AdbError('adb could not be started.') from None
    if result.returncode:
        raise AdbError('An adb command failed; check the selected phone connection and authorization.')
    return result.stdout.strip()


def collect(serial):
    result = {'schema_version': 2,
              'captured_at_utc': datetime.now(timezone.utc).isoformat(),
              'expected': {'device': 'oriole', 'api': 37}}
    try:
        entries = [line.split()[:2] for line in adb('devices').splitlines()[1:]
                   if len(line.split()) >= 2]
        result.update(connected_count=len(entries), connection_states=[e[1] for e in entries])
        if not serial:
            return result | {'status': 'pending', 'reason':
                'Set ANDROID_SERIAL to explicitly select the owner’s Pixel 6.'
                if entries else 'No adb device is connected.'}
        if [serial, 'device'] not in entries:
            return result | {'status': 'pending', 'reason': 'Selected adb device is absent or unauthorized.'}

        def shell(*parts):
            return adb('-s', serial, 'shell', *parts)

        fields = {'model': 'ro.product.model', 'codename': 'ro.product.device',
                  'android_release': 'ro.build.version.release', 'api': 'ro.build.version.sdk',
                  'security_patch': 'ro.build.version.security_patch', 'abis': 'ro.product.cpu.abilist'}
        measured = {key: shell('getprop', prop) for key, prop in fields.items()}
        result['measured'] = measured
        if measured['codename'] != 'oriole' or measured['api'] != '37':
            return result | {'status': 'target_mismatch', 'reason': 'Selected phone does not match Pixel 6 / API 37.'}

        for key, command in {'page_size': ['getconf', 'PAGE_SIZE'],
                'display_size': ['wm', 'size'], 'display_density': ['wm', 'density'],
                'storage': ['df', '-h', '/data'],
                'min_refresh_rate_setting': ['settings', 'get', 'system', 'min_refresh_rate'],
                'peak_refresh_rate_setting': ['settings', 'get', 'system', 'peak_refresh_rate']}.items():
            measured[key] = shell(*command)

        pending = ['In-app codec inventory (P01)', 'In-app BiometricManager diagnostics (P01)']
        missing = [key for key, value in measured.items() if not value]
        if not measured['page_size'].isdigit() or int(measured['page_size']) <= 0:
            missing.append('valid page size')

        try:
            display_lines = [line for line in shell('dumpsys', 'display').splitlines()
                             if 'mBaseDisplayInfo=DisplayInfo{' in line]
            primary = next((line for line in display_lines if 'displayId 0' in line),
                           display_lines[0] if display_lines else '')
            for key, pattern in [('logical_size', r'real (\d+ x \d+)'),
                                 ('logical_density_dpi', r'density (\d+)')]:
                match = re.search(pattern, primary)
                if match:
                    measured[key] = match.group(1)
                else:
                    missing.append(key)
        except AdbError:
            missing.extend(['logical_size', 'logical_density_dpi'])

        try:
            latency = shell('dumpsys', 'SurfaceFlinger', '--latency').splitlines()
            if latency and latency[0].isdigit() and int(latency[0]) > 0:
                period = int(latency[0])
                measured['surfaceflinger_refresh_period_ns'] = period
                measured['surfaceflinger_refresh_hz'] = round(1e9 / period, 2)
            else:
                missing.append('refresh interval sample')
        except AdbError:
            missing.append('refresh interval sample')

        result.update(status='partial' if missing else 'measured', pending=pending)
        if missing:
            result['missing_measurements'] = sorted(set(missing))
        return result
    except AdbError as error:
        return result | {'status': 'error', 'reason': str(error)}


def save_result(path, result):
    # A failed/disconnected rerun must not erase the last successful physical evidence.
    if path.exists() and result['status'] != 'measured':
        try:
            previous = json.loads(path.read_text())
        except (ValueError, OSError):
            previous = {}
        if previous.get('status') == 'measured':
            result['previous_output_preserved'] = True
            return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    result = collect(os.environ.get('ANDROID_SERIAL'))
    if args.output:
        try:
            save_result(args.output, result)
        except OSError:
            print('Could not save baseline report; check the output location.', file=sys.stderr)
            return 3
    print(json.dumps(result, indent=2))
    if result['status'] == 'measured':
        return 0
    return 3 if result['status'] == 'error' else 2


if __name__ == '__main__':
    sys.exit(main())
