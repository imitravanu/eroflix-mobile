#!/usr/bin/env python3
"""Bounded host HTTP observations only; never claims video playback/download proof."""
import argparse,concurrent.futures,datetime,hashlib,json,ssl,time,urllib.error,urllib.request
from pathlib import Path
from urllib.parse import urlsplit,urljoin
from inventory import PROTECTED
class PolicyRedirect(urllib.request.HTTPRedirectHandler):
 def __init__(self,allowed):self.allowed=allowed;self.count=0
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  self.count+=1;p=urlsplit(newurl)
  if self.count>4 or p.scheme!='https' or p.hostname not in self.allowed or p.port not in (None,443) or p.username or p.password:raise ValueError('redirect_outside_policy')
  # header_items also contains urllib's unredirected Host header. Reusing it
  # sends the previous hostname to the new server and can cause redirect loops.
  safe={k:v for k,v in req.header_items() if k.lower() in {'user-agent','accept','accept-encoding','referer','range'}}
  return urllib.request.Request(newurl,headers=safe,method='GET')
def probe(item):
 family,index,record=item;url=record['url'];host=urlsplit(url).hostname;allowed=PROTECTED if host in PROTECTED else {host,host.removeprefix('www.'),'www.'+host.removeprefix('www.')}
 limit=256*1024 if record['kind']=='EMBED' else 8192
 headers={'User-Agent':'EroflixMobile/0.2','Accept-Encoding':'identity'}
 if record['kind']!='EMBED':headers['Range']='bytes=0-8191'
 headers['Referer']='https://erogarga.com/' if host in PROTECTED else f'https://{host}/'
 started=time.monotonic();result={'family':family,'index':index,'sourceId':record['sourceId'],'kind':record['kind'],'attempts':1}
 try:
  opener=urllib.request.build_opener(PolicyRedirect(allowed),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
  with opener.open(urllib.request.Request(url,headers=headers),timeout=12) as response:
   parts=[];remaining=limit
   while remaining>0 and time.monotonic()-started<20:
    block=response.read1(min(8192,remaining))
    if not block:break
    parts.append(block);remaining-=len(block)
   data=b''.join(parts);mime=response.headers.get('Content-Type','').split(';')[0].lower();status=response.status
   media='hls' if data.lstrip().startswith(b'#EXTM3U') else 'mp4' if data[4:8]==b'ftyp' else 'webm' if data[:4]==bytes.fromhex('1a45dfa3') else None
   html=mime=='text/html' or data.lstrip()[:30].lower().startswith((b'<!doctype html',b'<html'))
   result.update(status=status,mime=mime,bytesRead=len(data),mediaSignature=media,html=html,outcome='media_prefix' if media else 'html_page' if html else 'unrecognized',finalHost=urlsplit(response.url).hostname)
 except urllib.error.HTTPError as e:result.update(status=e.code,outcome='http_error')
 except urllib.error.URLError as e:result.update(outcome='tls_error' if isinstance(e.reason,ssl.SSLError) else 'network_error')
 except (TimeoutError,ValueError,OSError):result.update(outcome='network_or_policy_error')
 result['elapsedMs']=round((time.monotonic()-started)*1000,1);return result
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--sample-dir',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args();source=Path(a.sample_dir);out=Path(a.output_dir);out.mkdir(parents=True,exist_ok=True)
 raw=(source/'samples.json').read_bytes();inventory=json.loads((source/'inventory.json').read_text())
 if hashlib.sha256(raw).hexdigest()!=inventory['sampleSha256']:raise ValueError('Frozen sample changed')
 tasks=[(family,index,record) for family,records in json.loads(raw)['samples'].items() for index,record in enumerate(records)]
 results=[]
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  for result in pool.map(probe,tasks):results.append(result)
 report={'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'Linux host through phone hotspot; not Android/WebView/decoder evidence','sampleSha256':inventory['sampleSha256'],'results':results}
 (out/'host-probe.json').write_text(json.dumps(report,indent=2))
 from collections import Counter
 summary={family:dict(Counter(x['outcome'] for x in results if x['family']==family)) for family in inventory['families']}
 (out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2))
