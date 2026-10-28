#!/usr/bin/env python3
"""Observe the frozen Tizam redirect/source path on the host; never claims playback."""
import argparse,concurrent.futures,hashlib,json,time,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin,urlsplit
from probe_samples import PolicyRedirect

MEDIA_HOSTS={'video1.tizam.cc','video2.tizam.cc','video4.tizam.cc'}
HOSTS={'abv.tizam.org','adv.tizam.org','tizam.org','www.tizam.org'}|MEDIA_HOSTS
class SourceTags(HTMLParser):
 def __init__(self):super().__init__();self.urls=[]
 def handle_starttag(self,tag,attrs):
  if tag in ('video','source'):
   value=dict(attrs).get('src')
   if value and len(self.urls)<16:self.urls.append(value)
class Redirect(PolicyRedirect):
 def __init__(self):super().__init__(HOSTS);self.hops=[]
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  next_request=super().redirect_request(req,fp,code,msg,headers,newurl)
  self.hops.append({'status':code,'fromHost':urlsplit(req.full_url).hostname,'toHost':urlsplit(newurl).hostname})
  return next_request
def read_prefix(response,limit):
 parts=[];remaining=limit;deadline=time.monotonic()+18
 while remaining and time.monotonic()<deadline:
  block=response.read1(min(8192,remaining))
  if not block:break
  parts.append(block);remaining-=len(block)
 return b''.join(parts)
def probe(item):
 index,record=item;result={'family':'abv.tizam.org','index':index,'environment':'Linux HTTP only; not Android playback'}
 redirect=Redirect();opener=urllib.request.build_opener(redirect)
 def fetch(url,referer,limit,range_request=False):
  parsed=urlsplit(url)
  if parsed.scheme!='https' or parsed.hostname not in HOSTS or parsed.port not in (None,443) or parsed.username or parsed.password:raise ValueError('Unapproved source destination')
  headers={'User-Agent':'EroflixMobile/0.2','Referer':referer,'Accept-Encoding':'identity'}
  if range_request:headers['Range']='bytes=0-8191'
  redirect.count=0
  return opener.open(urllib.request.Request(url,headers=headers),timeout=6)
 try:
  with fetch(record['url'],'https://abv.tizam.org/',256*1024) as response:
   body=read_prefix(response,256*1024);page=response.url;result.update(pageStatus=response.status,redirects=redirect.hops,finalPageHost=urlsplit(page).hostname)
  parser=SourceTags();parser.feed(body.decode('utf-8','replace'))
  result['mediaTagHosts']=sorted({urlsplit(urljoin(page,u)).hostname for u in parser.urls if urlsplit(urljoin(page,u)).hostname})
  target=next((urljoin(page,u) for u in parser.urls if urlsplit(urljoin(page,u)).hostname in MEDIA_HOSTS),None)
  if not target:result['outcome']='no_approved_media_tag';return result
  with fetch(target,'https://'+urlsplit(page).hostname+'/',8192,True) as response:
   data=read_prefix(response,8192);mp4=data[4:8]==b'ftyp'
   result.update(mediaStatus=response.status,mediaHost=urlsplit(response.url).hostname,prefixBytes=len(data),mp4Signature=mp4,hasContentRange=bool(response.headers.get('Content-Range')),outcome='media_prefix' if mp4 else 'not_mp4')
 except urllib.error.HTTPError as error:result.update(outcome='http_error',status=error.code)
 except (OSError,ValueError,TimeoutError) as error:result.update(outcome='network_or_policy_error',errorClass=type(error).__name__)
 return result
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--sample-dir',required=True);parser.add_argument('--output-dir',required=True);args=parser.parse_args()
 root=Path(args.sample_dir);raw=(root/'samples.json').read_bytes();inventory=json.loads((root/'inventory.json').read_text())
 if hashlib.sha256(raw).hexdigest()!=inventory['sampleSha256']:raise ValueError('Frozen denominator changed')
 records=json.loads(raw)['samples']['abv.tizam.org']
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(probe,enumerate(records)))
 report={'sampleSha256':inventory['sampleSha256'],'environment':'Host HTTP observation only','results':results}
 out=Path(args.output_dir);out.mkdir(parents=True,exist_ok=True);(out/'tizam-http.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
if __name__=='__main__':main()
