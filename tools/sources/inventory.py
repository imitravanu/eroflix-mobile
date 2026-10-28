#!/usr/bin/env python3
"""Freeze deterministic source samples before any network probing."""
import argparse,collections,hashlib,json,re,sqlite3
from pathlib import Path
from urllib.parse import urlsplit
TOP=['tubepornclassic.com','www.eporner.com','abv.tizam.org','www.fulleroticmovies.net','www.porntrex.com']
PROTECTED={'7530883417654311.vip','fxcdndx.top','mp4hls.click','cyoucyou.top','pk112qr.vip','u2f85d3o9x.one','d1erix.vip','bubababaskababa.space','iotokia.click','pinkclk.click','classicpornmagazines.click','t0mbiks.click','112dxfx.sbs','43754389.click','digeek.click','9799112.vip','magazinecdn.click'}
def freeze(database,output,per_family=5):
 out=Path(output);out.mkdir(parents=True,exist_ok=True)
 if (out/'samples.json').exists():raise ValueError('Sample already frozen; use a new output directory, never overwrite a probed denominator.')
 c=sqlite3.connect(Path(database).resolve().as_uri()+'?mode=ro',uri=True)
 selected=collections.defaultdict(list);counts=collections.Counter();titles=collections.defaultdict(set);seen=collections.defaultdict(set)
 meta=dict(c.execute('select key,value from catalog_meta'))
 for row in c.execute('select t.id,s.id,s.provider,s.original_url,s.kind,s.external_id,t.updated_at_ms from sources s join titles t on t.row_id=s.title_row order by coalesce(t.updated_at_ms,0),t.id,s.id'):
  title,sid,provider,url,kind,external,updated=row;host=(urlsplit(url).hostname or '').lower()
  groups=[]
  if host in TOP:groups.append(host)
  if kind in ('FILE','HLS'):groups.append('direct-'+kind.lower())
  if host in PROTECTED:groups.append('protected-'+kind.lower())
  for family in groups:
   counts[family]+=1;titles[family].add(title)
   # Distinct titles first; fixed oldest/newest and ranked identity coverage follows below.
   if title not in seen[family]:
    seen[family].add(title)
    candidate={'titleId':title,'sourceId':sid,'provider':provider,'url':url,'kind':kind,'externalId':external,'updatedAt':updated}
    rank=hashlib.sha256((family+'\0'+sid).encode()).hexdigest()
    selected[family].append((rank,candidate))
    selected[family].sort(key=lambda x:x[0]);del selected[family][per_family:]
 families=TOP+sorted(k for k in counts if k not in TOP)
 samples={family:[record for _,record in selected[family]] for family in families}
 # Samples are JSON handles only; no titles/descriptions/personal records are exported.
 doc={'formatVersion':1,'selection':'lowest SHA256(family NUL sourceId), distinct title; source rows ordered by update/title/source; fixed before probing','inputGeneration':meta['generationId'],'perFamily':per_family,'samples':samples}
 raw=(json.dumps(doc,sort_keys=True,separators=(',',':'))+'\n').encode();(out/'samples.json').write_bytes(raw)
 audit={'inputGeneration':meta['generationId'],'inputTitleCount':int(meta['titleCount']),'inputSourceCount':int(meta['sourceCount']),'sampleSha256':hashlib.sha256(raw).hexdigest(),'families':{k:{'sourceCount':counts[k],'titleCount':len(titles[k]),'selected':len(samples[k])} for k in families}}
 (out/'inventory.json').write_text(json.dumps(audit,indent=2)+'\n');c.close();return audit
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--catalog-db',required=True);p.add_argument('--output-dir',required=True);p.add_argument('--per-family',type=int,default=5);a=p.parse_args()
 if not 5<=a.per_family<=20:p.error('per-family must be between 5 and 20')
 print(json.dumps(freeze(a.catalog_db,a.output_dir,a.per_family),indent=2))
