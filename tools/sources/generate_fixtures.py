#!/usr/bin/env python3
"""Generate small neutral audiovisual/HLS fixtures; ffmpeg is a host build tool only."""
import argparse,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path

def generate(output):
 out=Path(output);out.mkdir(parents=True,exist_ok=True)
 ff=shutil.which('ffmpeg')
 if not ff:raise ValueError('Install ffmpeg to generate the optional source-lab assets.')
 def command(args):subprocess.run([ff,'-hide_banner','-loglevel','error','-y',*args],check=True,timeout=120)
 base=out/'neutral.mp4'
 command(['-f','lavfi','-i','testsrc2=size=320x180:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','8','-c:v','libx264','-preset','veryfast','-threads','2','-pix_fmt','yuv420p','-g','24','-sc_threshold','0','-c:a','aac','-b:a','64k','-movflags','+faststart',str(base)])
 for group,width,height,fmt in [('ts/low',160,90,'mpegts'),('ts/high',320,180,'mpegts'),('fmp4/low',160,90,'fmp4'),('encrypted',160,90,'mpegts')]:
  folder=out/group;folder.mkdir(parents=True,exist_ok=True)
  args=['-i',str(base),'-vf',f'scale={width}:{height}','-c:v','libx264','-preset','veryfast','-threads','2','-g','24','-sc_threshold','0','-c:a','aac','-b:a','64k','-hls_time','1','-hls_playlist_type','vod','-hls_segment_type',fmt]
  if fmt=='fmp4':args+=['-hls_fmp4_init_filename','init.mp4']
  if group=='encrypted':
   key=out/'key.bin';key.write_bytes(bytes(range(16)))
   with tempfile.NamedTemporaryFile(mode='w',suffix='.txt') as keyinfo:
    keyinfo.write('../key.bin\n'+str(key.resolve())+'\n00000000000000000000000000000000\n');keyinfo.flush()
    command(args+['-hls_key_info_file',keyinfo.name,'-hls_segment_filename',str(folder/'seg%03d.ts'),str(folder/'index.m3u8')])
  else:command(args+['-hls_segment_filename',str(folder/('seg%03d.m4s' if fmt=='fmp4' else 'seg%03d.ts')),str(folder/'index.m3u8')])
 (out/'ts/master.m3u8').write_text('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=160x90,CODECS="avc1.64000b,mp4a.40.2"\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=320x180,CODECS="avc1.64000c,mp4a.40.2"\nhigh/index.m3u8\n')
 (out/'fmp4/master.m3u8').write_text('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=160x90\nlow/index.m3u8\n')
 web=out/'web';web.mkdir(exist_ok=True)
 (web/'index.html').write_text('''<!doctype html><html><meta name="viewport" content="width=device-width"><title>Neutral containment fixture</title><body><h1>Controlled web player</h1><video id="v" controls width="240" src="../neutral.mp4"></video><button id="play" onclick="v.play()">Play neutral clip</button><p>Play this neutral clip, then return to the app; audio must stop.</p><button id="popup" onclick="window.open('https://external.invalid/popup')">Attempt popup</button><a id="intent" href="intent://external/#Intent;scheme=test;end">External intent</a><a id="navigate" href="https://external.invalid/redirect">External navigation</a><a id="download" href="../neutral.mp4" download>Attempt download</a><button id="permission" onclick="navigator.geolocation.getCurrentPosition(()=>{},()=>{})">Attempt permission</button><input id="file" type="file"><script>if('serviceWorker' in navigator) navigator.serviceWorker.register('worker.js').catch(()=>{});</script></body></html>''')
 (web/'worker.js').write_text("self.addEventListener('fetch',e=>e.respondWith(fetch(e.request)));")
 manifest={'formatVersion':1,'description':'Generated neutral test pattern + sine, no external media','ffmpeg':subprocess.check_output([ff,'-version'],text=True).splitlines()[0],'files':{str(p.relative_to(out)):{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.rglob('*')) if p.is_file() and p.name!='fixture-manifest.json'}}
 (out/'fixture-manifest.json').write_text(json.dumps(manifest,indent=2));return manifest
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',required=True);a=p.parse_args();m=generate(a.output_dir);print(json.dumps({'fixtureFiles':len(m['files']),'bytes':sum(x['bytes'] for x in m['files'].values()),'ffmpeg':m['ffmpeg']}))
