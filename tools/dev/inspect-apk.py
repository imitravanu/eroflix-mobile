from pathlib import Path
import argparse,gzip,json,subprocess,zipfile,struct,hashlib
r=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--output-dir',default='artifacts/p01')
p.add_argument('--variant',choices=('debug','release'),default='debug')
p.add_argument('--expected-catalog-dir',help='Require the exact selected catalog manifest, not merely an internally consistent APK.')
a=p.parse_args()
report_dir=r/a.output_dir;report_dir.mkdir(parents=True,exist_ok=True)
release=a.variant=='release'
if release:report_dir=report_dir/'release';report_dir.mkdir(parents=True,exist_ok=True)
apk=r/('app/build/outputs/apk/release/app-release-unsigned.apk' if release else 'app/build/outputs/apk/debug/app-debug.apk')
package='tech.imitravanu.eroflix'+('' if release else '.debug')
tools=r/'local-data/toolchain/android-sdk/build-tools/36.0.0'

# apksigner needs the pinned JDK, selected by the P00 command wrapper.
wrapper=r/'tools/dev/with-toolchain.sh'
def command(name,args,log):
 result=subprocess.run([str(wrapper),str(tools/name),*args],capture_output=True,text=True,check=True,timeout=60)
 (report_dir/log).write_text(result.stdout+result.stderr)
 return result.stdout
badging=command('aapt2',['dump','badging',str(apk)],'apk-badging.txt')
manifest_tree=command('aapt2',['dump','xmltree',str(apk),'--file','AndroidManifest.xml'],'apk-manifest.txt')
for field in [f"name='{package}'", "minSdkVersion:'37'", "targetSdkVersion:'37'", "compileSdkVersion='37'"]:
 assert field in badging,field
if not release:command('apksigner',['verify','--verbose',str(apk)],'apk-signature.txt')
command('zipalign',['-c','-P','16','-v','4',str(apk)],'apk-zipalign.txt')
def sha_file(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
libs=[]
with zipfile.ZipFile(apk) as z:
 manifest=json.loads(z.read('assets/catalog/manifest.json'))
 if release:
  assert manifest['kind']=='full' and manifest['titleCount']>=100000,'Release must contain the full catalog.'
  assert not any(name.startswith('assets/source-lab/') for name in z.namelist()),'Source lab leaked into release.'
  debug_types=('SourceLabActivity','ProductionPlayerHarnessActivity','ProductionHarness','UiValidationContainer','LabFixtureServer','RealSourceProbe')
  assert not any(name in manifest_tree for name in debug_types),'Debug harness component leaked into release manifest.'
  for name in z.namelist():
   if name.startswith('classes') and name.endswith('.dex'):
    dex=z.read(name)
    assert not any(('Ltech/imitravanu/eroflix/'+kind+';').encode() in dex for kind in debug_types),'Debug harness code leaked into release.'
 if a.expected_catalog_dir:
  expected=json.loads((r/a.expected_catalog_dir/'manifest.json').read_text())
  assert manifest==expected,'Packaged catalog differs from the selected input.'
 compressed_hash=hashlib.sha256();compressed_size=0
 with z.open('assets/catalog/catalog.bin') as f:
  for b in iter(lambda:f.read(1024*1024),b''):compressed_hash.update(b);compressed_size+=len(b)
 assert compressed_hash.hexdigest()==manifest['compressedSha256'] and compressed_size==manifest['compressedBytes']
 uncompressed_hash=hashlib.sha256();uncompressed_size=0
 with z.open('assets/catalog/catalog.bin') as source,gzip.GzipFile(fileobj=source) as f:
  for b in iter(lambda:f.read(1024*1024),b''):uncompressed_hash.update(b);uncompressed_size+=len(b)
 assert uncompressed_hash.hexdigest()==manifest['uncompressedSha256'] and uncompressed_size==manifest['uncompressedBytes']
 assert not any(name.startswith('assets/fixture-v') for name in z.namelist())
 names=[name for name in z.namelist() if name.startswith('lib/') and name.endswith('.so')]
 assert {name.split('/')[1] for name in names}==({'arm64-v8a'} if release else {'arm64-v8a','x86_64'})
 for name in names:
  data=z.read(name)
  assert data[:4]==b'\x7fELF' and data[4:6]==bytes([2,1]),name
  offset=struct.unpack_from('<Q',data,32)[0]
  size,count=struct.unpack_from('<HH',data,54)
  alignments=[]
  for n in range(count):
   ph=offset+n*size
   if struct.unpack_from('<I',data,ph)[0]==1:
    alignments.append(struct.unpack_from('<Q',data,ph+48)[0])
  assert alignments and min(alignments)>=16384,(name,alignments)
  libs.append({'path':name,'load_alignments':alignments})
result={'package':package,'compile_min_target_api':37,
 'apk_bytes':apk.stat().st_size,'apk_sha256':sha_file(apk),
 'catalog_kind':manifest['kind'],'catalog_titles':manifest['titleCount'],'catalog_generation':manifest['generationId'],'catalog_asset_integrity':'pass',
 'debug_harness_exclusion':'pass' if release else 'debug variant; harness permitted',
 'debug_signature':'unsigned release; signing belongs to P11' if release else 'verified','zip_16k_alignment':'pass','native_libraries':libs,
 'runtime_16k_test':'pending supplemental device/emulator'}
(report_dir/'apk-inspection.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
