#!/usr/bin/env python3
"""Run real AndroidJUnitRunner suites with explicit device selection and strict results.

No radio changes. This avoids the AGP 9.4 connected-runner false exit observed
with network ADB while preserving all real instrumentation failures.
"""
import argparse,hashlib,json,os,re,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
APP='tech.imitravanu.eroflix.debug'
DATA='tech.imitravanu.eroflix.core.data.test'

def parse_instrumentation(text):
    summary=re.search(r'^OK \((\d+) tests?\)\s*$',text,re.M)
    statuses=[int(s) for s in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$',text,re.M)]
    # AndroidJUnitRunner's final -1 code is success. Negative test status codes
    # are errors, failures or skips and must not masquerade as a passing suite.
    valid=bool(summary) and bool(statuses) and all(s in (0,1) for s in statuses) and \
        len([s for s in statuses if s==0])==int(summary[1]) and \
        bool(re.search(r'^INSTRUMENTATION_CODE: -1\s*$',text,re.M)) and \
        not any(marker in text for marker in ('FAILURES!!!','INSTRUMENTATION_FAILED','INSTRUMENTATION_ABORTED'))
    if not valid:raise ValueError('Instrumentation did not report an entirely passing, completed suite.')
    return int(summary[1])

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',default='artifacts/p02/final-device');a=p.parse_args()
    serial=os.environ.get('ANDROID_SERIAL')
    if not serial:raise ValueError('Set ANDROID_SERIAL explicitly before running tests.')
    adb=ROOT/'local-data/toolchain/android-sdk/platform-tools/adb';base=[str(adb),'-s',serial]
    out=ROOT/a.output_dir;out.mkdir(parents=True,exist_ok=True)
    def run(args,check=True,timeout=180):
        r=subprocess.run(base+list(args),capture_output=True,text=True,timeout=timeout)
        if check and r.returncode:raise RuntimeError('Device command failed. Check connection/authorization; see local evidence.')
        return r.stdout.strip()
    def shell(*args,**kw):return run(['shell',*args],**kw)
    if shell('getprop','ro.build.version.sdk')!='37':raise ValueError('An authorized API 37 device is required.')
    app_apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    with zipfile.ZipFile(app_apk) as z:manifest=json.loads(z.read('assets/catalog/manifest.json'))
    old_timeout=shell('settings','get','system','screen_off_timeout')
    radios={key:shell('settings','get','global',key) for key in ('airplane_mode_on','wifi_on','mobile_data')}
    summary={'model':shell('getprop','ro.product.model'),'api':37,'generation':manifest['generationId'],'catalog_kind':manifest['kind'],'suites':{}}
    def install(apk):
        if 'Success' not in run(['install','-r',str(apk)],timeout=300):raise RuntimeError('Test APK installation failed.')
    def suite(component,cls,label,extra=()):
        args=['am','instrument','-w','-r']
        if cls:args+=['-e','class',cls]
        args+=list(extra)+[component+'/androidx.test.runner.AndroidJUnitRunner']
        text=shell(*args,timeout=300);(out/(label+'.log')).write_text(text)
        count=parse_instrumentation(text);summary['suites'][label]={'passed':count}
        print(f'{label}: {count} passed',flush=True)
    try:
        shell('settings','put','system','screen_off_timeout','1200000');shell('input','keyevent','KEYCODE_WAKEUP')
        install(ROOT/'core/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk')
        suite(DATA,None,'data')
        run(['uninstall',DATA],check=False)
        installed=shell('pm','path',APP).removeprefix('package:')
        current=shell('sha256sum',installed).split()[0] if installed else ''
        with app_apk.open('rb') as f:expected=hashlib.file_digest(f,'sha256').hexdigest()
        if expected!=current:install(app_apk)
        install(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
        shell('am','force-stop',APP)
        cls='FoundationSmokeTest' if manifest['kind']=='fixture' else 'FullCatalogDeviceTest'
        suite(APP+'.test','tech.imitravanu.eroflix.'+cls,'app')
        shell('am','force-stop',APP)
        suite(APP+'.test','tech.imitravanu.eroflix.PersonalRestartTest#seedBeforeProcessDeath','restart-seed',('-e','restartProbe','true'))
        shell('am','force-stop',APP)
        suite(APP+'.test','tech.imitravanu.eroflix.PersonalRestartTest#verifyAfterProcessDeathAndClearProbe','restart-verify',('-e','restartProbe','true'))
        summary['passed']=True
    finally:
        summary['network_unchanged']={key:shell('settings','get','global',key) for key in radios}==radios
        if old_timeout=='null':shell('settings','delete','system','screen_off_timeout')
        else:shell('settings','put','system','screen_off_timeout',old_timeout)
        summary['display_restored']=shell('settings','get','system','screen_off_timeout')==old_timeout
        logs=run(['logcat','-d','-s','EroflixP02:I','*:S']);(out/'own-logcat.log').write_text(logs)
        benchmarks=[l.split('BENCHMARK ',1)[1] for l in logs.splitlines() if 'BENCHMARK ' in l]
        if benchmarks:
            b=json.loads(benchmarks[-1])
            if b['generation']==manifest['generationId']:summary['benchmark']=b
        for package in (DATA,APP+'.test'):run(['uninstall',package],check=False)
        shell('am','force-stop',APP);shell('am','start','-n',APP+'/tech.imitravanu.eroflix.MainActivity')
        (out/'summary.json').write_text(json.dumps(summary,indent=2))
        if not summary['network_unchanged'] or not summary['display_restored']:raise RuntimeError('Settings restoration check failed.')
    print(json.dumps({'passed':True,'test_count':sum(s['passed'] for s in summary['suites'].values())}))

if __name__=='__main__':
    try:main()
    except (ValueError,RuntimeError,subprocess.TimeoutExpired,OSError):
        print('Device verification failed; inspect the local suite log and summary. No gate is waived.',file=sys.stderr);sys.exit(1)
