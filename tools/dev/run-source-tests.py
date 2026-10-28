#!/usr/bin/env python3
"""Explicit P03 controlled-device gate; never invoked by host checks."""
import argparse,hashlib,importlib.util,json,os,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('instrumentation',ROOT/'tools/dev/run-device-tests.py');parser=importlib.util.module_from_spec(s);s.loader.exec_module(parser)
def main():
 options=argparse.ArgumentParser(description=__doc__);modes=options.add_mutually_exclusive_group();modes.add_argument('--real-sources',action='store_true');modes.add_argument('--real-download',action='store_true');options.add_argument('--output-dir');options.add_argument('--reuse-installed-app',action='store_true');options.add_argument('--family',choices=('abv.tizam.org','direct-file','direct-hls','protected-file','protected-hls','tubepornclassic.com','www.eporner.com','www.fulleroticmovies.net','www.porntrex.com'));args=options.parse_args()
 if args.family and not args.real_sources:raise ValueError('--family requires the explicit real-source experiment.')
 serial=os.environ.get('ANDROID_SERIAL')
 if not serial:raise ValueError('Explicit authorized ANDROID_SERIAL is required.')
 adb=ROOT/'local-data/toolchain/android-sdk/platform-tools/adb';base=[str(adb),'-s',serial];package='tech.imitravanu.eroflix.debug'
 out=ROOT/(args.output_dir or ('artifacts/p03/real-download' if args.real_download else 'artifacts/p03/real-device' if args.real_sources else 'artifacts/p03/device'));out.mkdir(parents=True,exist_ok=True)
 def run(args,check=True,timeout=120):
  r=subprocess.run(base+args,capture_output=True,text=True,timeout=timeout)
  if check and r.returncode:raise RuntimeError('Source-lab device command failed; connection may be unavailable.')
  return r.stdout.strip()
 def shell(*args,**kw):return run(['shell',*args],**kw)
 if shell('getprop','ro.build.version.sdk')!='37':raise ValueError('P03 gate requires API37.')
 if shell('getprop','ro.product.device')!='oriole':raise ValueError('P03 physical acceptance requires the selected Pixel 6 (oriole).')
 apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
 with zipfile.ZipFile(apk) as z:
  if 'assets/source-lab/fixture-manifest.json' not in z.namelist():raise ValueError('Build with -PsourceLab=true before running this gate.')
  if (args.real_sources or args.real_download) and 'assets/source-lab/samples.json' not in z.namelist():raise ValueError('The explicit frozen sample debug asset is required.')
 before={key:shell('settings','get','global',key) for key in ('airplane_mode_on','wifi_on','mobile_data')};timeout=shell('settings','get','system','screen_off_timeout')
 def digest(path):
  with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()
 result={'model':shell('getprop','ro.product.model'),'api':37,'apkSha256':digest(apk),
  'testApkSha256':digest(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')}
 if args.family:result['familyFilter']=args.family
 if args.reuse_installed_app:
  paths=shell('pm','path',package).splitlines()
  installed=next((line.removeprefix('package:') for line in paths if line.startswith('package:') and line.endswith('/base.apk')),None)
  if not installed or shell('sha256sum',installed).split()[0]!=result['apkSha256']:raise ValueError('Installed app differs from the selected APK; run without --reuse-installed-app.')
  result['installedAppHashVerified']=True
 try:
  shell('settings','put','system','screen_off_timeout','3600000' if args.real_sources else '1200000');shell('input','keyevent','KEYCODE_WAKEUP')
  install_paths=[ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
  if not args.reuse_installed_app:install_paths.insert(0,apk)
  for path in install_paths:
   if 'Success' not in run(['install','-r',str(path)],timeout=300):raise RuntimeError('Source-lab installation failed.')
  if args.real_sources or args.real_download:shell('run-as',package,'rm','-f','files/source-lab-download.json' if args.real_download else 'files/source-lab-real.json')
  test='RealSourceDownloadTest' if args.real_download else 'RealSourceSampleTest' if args.real_sources else 'SourceFeasibilityTest'
  command=['am','instrument','-w','-r','-e','class','tech.imitravanu.eroflix.'+test]
  if args.family:command+=['-e','sourceFamily',args.family]
  text=shell(*command,package+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=2500 if args.real_sources else 720 if args.real_download else 300)
  (out/'controlled-suites.log').write_text(text);result['passedTests']=parser.parse_instrumentation(text)
 finally:
  own=run(['logcat','-d','-s','EroflixP03:I','*:S']);(out/'own-logcat.log').write_text(own)
  if args.real_sources:
   saved=shell('run-as',package,'cat','files/source-lab-real.json',check=False)
   if saved:
    (out/'sample-results.json').write_text(saved)
    try:result['sampleCasesRecorded']=len(json.loads(saved).get('cases',[]))
    except (ValueError,TypeError):result['sampleResultIncomplete']=True
  if args.real_download:
   saved=shell('run-as',package,'cat','files/source-lab-download.json',check=False)
   if saved:
    (out/'download-results.json').write_text(saved)
    try:result['providerDownload']=json.loads(saved)
    except (ValueError,TypeError):result['downloadResultIncomplete']=True
  entries=[line.split('LAB_RESULT ',1)[1] for line in own.splitlines() if 'LAB_RESULT ' in line]
  if entries and not args.real_sources and not args.real_download:result['controlledMedia']=json.loads(entries[-1])
  if timeout=='null':shell('settings','delete','system','screen_off_timeout')
  else:shell('settings','put','system','screen_off_timeout',timeout)
  result['displayRestored']=shell('settings','get','system','screen_off_timeout')==timeout
  result['networkUnchanged']={key:shell('settings','get','global',key) for key in before}==before
  run(['uninstall',package+'.test'],check=False);shell('am','force-stop',package)
  (out/'summary.json').write_text(json.dumps(result,indent=2))
 if not result['networkUnchanged'] or not result['displayRestored']:raise RuntimeError('Settings restoration failed.')
 print(json.dumps(result))
if __name__=='__main__':
 try:main()
 except (ValueError,RuntimeError,subprocess.TimeoutExpired,OSError):
  print('P03 device gate failed or incomplete; inspect ignored artifacts/p03/device/. No physical success is inferred.',file=sys.stderr);sys.exit(1)
