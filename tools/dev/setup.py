#!/usr/bin/env python3
"""Install the checksum-pinned Linux x86_64 P00 toolchain without system changes."""
import hashlib, json, os, pathlib, re, shutil, subprocess, sys, tarfile, tempfile, urllib.request, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCAL = ROOT / 'local-data/toolchain'
LOCK = json.loads((pathlib.Path(__file__).with_name('toolchain-lock.json')).read_text())

def fetch(item):
    target = LOCAL / 'downloads' / item['filename']
    target.parent.mkdir(parents=True, exist_ok=True)
    def valid(p):
        if not p.is_file():
            return False
        with p.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest() == item['sha256']
    if not valid(target):
        partial = target.with_suffix(target.suffix + '.part')
        print('Downloading ' + item['filename'], flush=True)
        with urllib.request.urlopen(item['url'], timeout=60) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        if not valid(partial):
            partial.unlink(missing_ok=True)
            raise RuntimeError('SHA-256 mismatch: ' + item['filename'])
        partial.replace(target)
    return target

def install(name):
    item = LOCK[name]
    archive = fetch(item)
    destination = LOCAL / item['directory']
    if destination.exists():
        return destination
    with tempfile.TemporaryDirectory(dir=LOCAL) as tmp:
        stage = pathlib.Path(tmp)
        if archive.name.endswith('.zip'):
            with zipfile.ZipFile(archive) as z:
                for entry in z.infolist():
                    if not (stage / entry.filename).resolve().is_relative_to(stage.resolve()):
                        raise RuntimeError('Unsafe ZIP member')
                z.extractall(stage)
                for entry in z.infolist():
                    mode = entry.external_attr >> 16
                    if mode & 0o111:
                        (stage / entry.filename).chmod(0o755)
        else:
            with tarfile.open(archive) as t:
                t.extractall(stage, filter='data')
        source = stage / item['archive_root']
        destination.parent.mkdir(parents=True, exist_ok=True)
        source.rename(destination)
    return destination

def sdk_revision(sdk, package):
    directory = sdk.joinpath(*package.split(';'))
    if not directory.exists():
        return None
    properties = directory / 'source.properties'
    if not properties.is_file():
        raise RuntimeError(f'{package} is incomplete; inspect its installation before retrying.')
    values = dict(line.split('=', 1) for line in properties.read_text().splitlines()
                  if '=' in line and not line.lstrip().startswith(('#', '!')))
    return values.get('Pkg.Revision', '').strip()

def ensure_sdk_packages(manager, sdk, env):
    """Reuse matching installs and reject revision drift before running an installer."""
    missing = []
    for package in LOCK['sdk_packages']:
        expected = LOCK['sdk_expected_revisions'][package]
        actual = sdk_revision(sdk, package)
        if actual is None:
            missing.append(package)
        elif actual != expected:
            raise RuntimeError(f'{package} revision {actual} differs from locked {expected}; no SDK packages changed.')
    if not missing:
        return

    listing = subprocess.run([manager, '--sdk_root=' + str(sdk), '--list', '--channel=0'],
                             capture_output=True, text=True, env=env, check=True)
    available = {}
    in_available = False
    for line in listing.stdout.splitlines():
        if line.strip() == 'Available Packages:':
            in_available = True
        elif line.strip().endswith(':'):
            in_available = False
        elif in_available:
            cells = [cell.strip() for cell in line.split('|')]
            if len(cells) >= 2:
                available[cells[0]] = cells[1]
    for package in missing:
        expected = LOCK['sdk_expected_revisions'][package]
        actual = available.get(package)
        if actual != expected:
            raise RuntimeError(f'{package}: repository revision {actual!r} differs from locked {expected}; '
                               'review the lock before installation. No SDK packages changed.')
    # Only requested missing packages enter the installer and its license prompts.
    subprocess.run([manager, '--sdk_root=' + str(sdk), *missing],
                   input='y\n' * 100, text=True, env=env, check=True)
    for package in LOCK['sdk_packages']:
        expected = LOCK['sdk_expected_revisions'][package]
        if sdk_revision(sdk, package) != expected:
            raise RuntimeError(f'{package} changed during installation; inspect the SDK before building.')

def update_local_properties(path, sdk):
    """Change only sdk.dir, preserving other local build configuration."""
    lines = path.read_text().splitlines(keepends=True) if path.exists() else []
    lines = [line for line in lines if not re.match(r'^\s*sdk\.dir(?:\s*[:=]|\s+)', line)]
    if lines and not lines[-1].endswith('\n'):
        lines[-1] += '\n'
    value = str(sdk).replace('\\', '\\\\')
    path.write_text(''.join(lines) + 'sdk.dir=' + value + '\n')

def main():
    if sys.version_info < (3, 12):
        raise SystemExit('Python 3.12 or newer is required for the toolchain setup.')
    if sys.platform != 'linux' or os.uname().machine != 'x86_64':
        raise SystemExit('This lock is for Linux x86_64 only.')
    LOCAL.mkdir(parents=True, exist_ok=True)
    jdk = install('jdk')
    gradle = install('gradle')
    cli = install('command_line_tools')
    sdk = LOCAL / 'android-sdk'
    env = os.environ | {'JAVA_HOME': str(jdk), 'ANDROID_HOME': str(sdk),
        'ANDROID_SDK_ROOT': str(sdk), 'ANDROID_USER_HOME': str(LOCAL / 'android-user'),
        'GRADLE_USER_HOME': str(LOCAL / 'gradle-user'),
        'PATH': str(jdk / 'bin') + os.pathsep + os.environ.get('PATH', '')}
    for key in ('ANDROID_USER_HOME', 'GRADLE_USER_HOME'):
        pathlib.Path(env[key]).mkdir(parents=True, exist_ok=True)
    manager = str(cli / 'bin/sdkmanager')
    ensure_sdk_packages(manager, sdk, env)
    subprocess.run([manager, '--sdk_root=' + str(sdk), '--list_installed'], env=env, check=True)
    subprocess.run([str(jdk / 'bin/java'), '-version'], env=env, check=True)
    subprocess.run([str(gradle / 'bin/gradle'), '--version'], env=env, check=True)
    subprocess.run([str(sdk / 'platform-tools/adb'), 'version'], env=env, check=True)
    update_local_properties(ROOT / 'local.properties', sdk)
    print('Toolchain ready. Run tools/dev/with-toolchain.sh COMMAND [ARGS].')

if __name__ == '__main__':
    main()
