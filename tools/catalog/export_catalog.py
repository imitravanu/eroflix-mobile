#!/usr/bin/env python3
"""Build an immutable offline catalog from a consistent desktop SQLite backup."""
import argparse,collections,gzip,hashlib,json,os,re,shutil,sqlite3,tempfile,time
from pathlib import Path
from urllib.parse import unquote,urlparse
from catalog_common import *

def slug(s):return re.sub(r'[^\w]+','-',fold(unquote(s)),flags=re.UNICODE).strip('-') or stable_digest(s)[:20]
def build(source,output,kind='fixture',epoch=0,generation=None):
 output=Path(output);output.mkdir(parents=True,exist_ok=True)
 # Original file is never copied raw while WAL may be live.
 snapshot=output/'input-snapshot.db'
 src=sqlite3.connect(Path(source).resolve().as_uri()+'?mode=ro',uri=True)
 dst=sqlite3.connect(snapshot)
 try:src.backup(dst)
 finally:dst.close();src.close()
 input_hash=digest(snapshot);generation=generation or f'{kind}-{input_hash[:24]}'
 require(re.fullmatch('[A-Za-z0-9_-]{1,96}', generation),'unsafe generation id')
 temp=Path(tempfile.mkdtemp(prefix='.catalog-stage-',dir=output))
 try:
  c=sqlite3.connect(temp/'catalog.db');c.executescript('PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA foreign_keys=ON;'+(ROOT/'core/data/src/main/assets/schema/catalog-v1.sql').read_text())
  src=sqlite3.connect(snapshot);src.row_factory=sqlite3.Row
  counts=collections.Counter();mismatch=collections.Counter();aliases=collections.defaultdict(set);ids=hashlib.sha256()
  def relation(table,row,key,label):
   if not key:return
   c.execute(f'INSERT OR IGNORE INTO {table}(key,label) VALUES (?,?)',(key,label))
   c.execute(f'INSERT OR IGNORE INTO title_{table}(title_row,key) VALUES (?,?)',(row,key))
  for row_number,r in enumerate(src.execute('SELECT * FROM movies ORDER BY id'),1):
   m=json.loads(r['data']);require(isinstance(m, dict) and m.get('id') == r['id'],'JSON/key title identity mismatch')
   require(isinstance(m.get('title'), str) and m['title'].strip(),'empty title')
   require(isinstance(m.get('sources', []), list),'invalid sources')
   # Stored JSON is the complete record used by desktop's own reader. Preserve
   # column discrepancies as an explicit audit and the original JSON verbatim.
   for field,col in [('title','title'),('year','year'),('country','country'),('source','source'),('externalId','external_id'),('createdAt','created_at'),('updatedAt','updated_at')]:
    if m.get(field)!=r[col]:mismatch[field]+=1
   tid=r['id'];title=m['title'];year=m.get('year');duration=m.get('duration_ms')
   require(year is None or isinstance(year, int),'invalid year')
   require(duration is None or (isinstance(duration, int) and duration >= 0),'invalid duration')
   provenance=m.get('source');provider=provenance or '@unknown'
   c.execute('INSERT INTO titles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',(row_number,tid,title,fold(title),year,duration,m.get('description'),m.get('poster'),m.get('backdrop'),m.get('createdAt'),m.get('updatedAt'),m.get('license'),provenance,m.get('externalId'),r['data']))
   relation('providers',row_number,provider,provenance or 'Unknown')
   country=m.get('country');relation('countries',row_number,fold(country.strip()) if country and country.strip() else None,country.strip() if country else '')
   texts=[m.get('studio') or '',str(year or '')];studio_keys=set()
   for tag in sorted(set(m.get('tags') or [])):
    require(isinstance(tag, str),'invalid tag')
    origin,sep,value=tag.partition(':');typed=origin if sep and origin in ('star','studio','series') else 'generic'
    c.execute('INSERT OR IGNORE INTO tags VALUES (?,?,?)',(tag,tag,typed));c.execute('INSERT INTO title_tags VALUES (?,?)',(row_number,tag));counts['tagLinks']+=1
    texts.append(tag.replace('-',' ').replace(':',' ').replace('_',' '))
    if typed!='generic' and value:
     key=value;label=unquote(value).replace('-',' ').title()
     if typed=='star':
      c.execute('INSERT OR IGNORE INTO performers VALUES (?,?,NULL,?)',(key,label,'Inferred from star: tag'))
      c.execute('INSERT OR IGNORE INTO title_performers VALUES (?,?)',(row_number,key))
     elif typed=='series':relation('series',row_number,key,label)
     else:
      studio_keys.add(key);aliases[slug(value)].add(key);relation('studios',row_number,key,label)
   studio=m.get('studio')
   if studio and studio.strip():
    normalized=slug(studio);matching=[key for key in studio_keys if slug(key)==normalized]
    key=matching[0] if len(matching)==1 else normalized
    aliases[normalized].add(key);relation('studios',row_number,key,studio.strip())
   for ordinal,s in enumerate(m.get('sources',[])):
    require(s.get('type') in ('file', 'hls', 'embed') and isinstance(s.get('url'), str) and s['url'],'invalid source')
    original=s['url'];parsed=urlparse(original);require(parsed.scheme in ('http', 'https') and parsed.netloc,'non-original/invalid source URL')
    require('/api/relay?' not in original,'desktop relay URL is not an original source')
    source_provider=provider;kind_name=s['type'].upper();sid=stable_digest(tid,source_provider,kind_name,original)
    # The primary key fails on collisions or duplicate handles; no row is dropped.
    c.execute('INSERT INTO sources VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',(sid,row_number,source_provider,m.get('externalId'),original,kind_name,s.get('label'),s.get('quality'),source_provider,source_provider,provenance,json_text(s)))
    counts['sourceCount']+=1;counts[kind_name]+=1
   document=(row_number,fold(title),fold(' '.join(texts)),fold(m.get('description') or ''))
   c.execute('INSERT INTO search_documents VALUES (?,?,?,?)',document)
   for gram in grams(' '.join(document[1:])):c.execute('INSERT INTO cjk_grams VALUES (?,?)',(gram,row_number))
   ids.update((json_text(tid)+'\n').encode());counts['titleCount']+=1
  src.close()
  for alias,keys in sorted(aliases.items()):
   if len(keys)==1:c.execute('INSERT INTO studio_aliases VALUES (?,?)',(alias,next(iter(keys))))
  for table in ('providers','countries','performers','studios','series','tags'):
   c.execute(f'CREATE INDEX ix_{table}_reverse ON title_{table}(key,title_row)')
  c.executescript('''CREATE INDEX ix_titles_sort ON titles(sort_title,id);
CREATE INDEX ix_titles_facets ON titles(row_id,id,year,duration_ms);
CREATE INDEX ix_titles_year_new ON titles(coalesce(year,-1) DESC,id);
CREATE INDEX ix_titles_year_old ON titles(coalesce(year,99999),id);
CREATE INDEX ix_titles_year ON titles(year,id);
CREATE INDEX ix_sources_title ON sources(title_row,id);
INSERT INTO titles_fts(titles_fts) VALUES ('rebuild');
INSERT INTO titles_fts(titles_fts) VALUES ('optimize');''')
  for group,table in [('provider','providers'),('country','countries'),('studio','studios')]:
   c.execute(f"INSERT INTO facet_counts SELECT ?,coalesce(r.key,'@unknown'),coalesce(v.label,'Unknown'),count(DISTINCT t.row_id) FROM titles t LEFT JOIN title_{table} r ON r.title_row=t.row_id LEFT JOIN {table} v ON v.key=r.key GROUP BY r.key",(group,))
  c.execute("INSERT INTO facet_counts SELECT 'decade',coalesce(CAST(year/10*10 AS TEXT),'@unknown'),coalesce(CAST(year/10*10 AS TEXT),'Unknown'),count(*) FROM titles GROUP BY year/10")
  # Compact build-only projection avoids reading large original JSON for each pair.
  c.execute("CREATE TEMP TABLE facet_projection AS SELECT t.row_id,coalesce(p.key,'@unknown') provider,coalesce(c.key,'@unknown') country,coalesce(CAST(t.year/10*10 AS TEXT),'@unknown') decade,coalesce(s.key,'@unknown') studio FROM titles t LEFT JOIN title_providers p ON p.title_row=t.row_id LEFT JOIN title_countries c ON c.title_row=t.row_id LEFT JOIN title_studios s ON s.title_row=t.row_id")
  for scope in ('provider','country','decade','studio'):
   for group in ('provider','country','decade','studio'):
    if scope!=group:
     c.execute(f"INSERT INTO facet_cross_counts SELECT ?,p.{scope},?,p.{group},f.label,count(DISTINCT p.row_id) FROM facet_projection p JOIN facet_counts f ON f.facet=? AND f.key=p.{group} GROUP BY p.{scope},p.{group}",(scope,group,group))
  c.execute('DROP TABLE temp.facet_projection')
  c.execute('INSERT INTO facet_options(facet,key,label) SELECT facet,key,label FROM facet_counts ORDER BY facet,key')
  for group,table in [('provider','providers'),('country','countries'),('studio','studios')]:
   c.execute(f"INSERT INTO facet_members SELECT t.row_id,o.id FROM titles t LEFT JOIN title_{table} r ON r.title_row=t.row_id JOIN facet_options o ON o.facet=? AND o.key=coalesce(r.key,'@unknown')",(group,))
  c.execute("INSERT INTO facet_members SELECT t.row_id,o.id FROM titles t JOIN facet_options o ON o.facet='decade' AND o.key=coalesce(CAST(t.year/10*10 AS TEXT),'@unknown')")
  c.execute('CREATE INDEX ix_facet_members_option ON facet_members(option_id,title_row)')
  bitmap_bytes=(counts['titleCount']+8)//8
  # Bounded optional acceleration; normalized relations remain the authority.
  option_count=c.execute('SELECT count(*) FROM facet_options').fetchone()[0]
  if bitmap_bytes*option_count<=32*1024*1024:
   for (option_id,) in c.execute('SELECT id FROM facet_options ORDER BY id').fetchall():
    bits=bytearray(bitmap_bytes)
    for (title_row,) in c.execute('SELECT title_row FROM facet_members WHERE option_id=?',(option_id,)):
     bits[title_row//8]|=1<<(title_row%8)
    c.execute('INSERT INTO facet_bitmaps VALUES (?,?)',(option_id,bytes(bits)))
  counts['knownDurationCount']=c.execute('SELECT count(*) FROM titles WHERE duration_ms IS NOT NULL').fetchone()[0]
  sh=hashlib.sha256()
  for row in c.execute('select t.id,s.provider,s.kind,s.original_url from sources s join titles t on t.row_id=s.title_row order by s.id'):sh.update((json_text(row)+'\n').encode())
  meta={'formatVersion':FORMAT,'catalogSchemaVersion':SCHEMA,'generationId':generation,'createdAt':epoch*1000,'exporterVersion':EXPORTER,'sourceSnapshotSha256':input_hash,'titleCount':counts['titleCount'],'sourceCount':counts['sourceCount'],'kind':kind,'titleIdsSha256':ids.hexdigest(),'sourceRecordsSha256':sh.hexdigest(),'knownDurationCount':counts['knownDurationCount']}
  for key,val in meta.items():c.execute('INSERT INTO catalog_meta VALUES (?,?)',(key,str(val)))
  c.execute('PRAGMA user_version=1');c.commit();c.execute('ANALYZE');c.commit();c.execute('VACUUM');c.close()
  with open(temp/'catalog.db','rb') as inp,open(temp/'catalog.db.gz','wb') as out:
   with gzip.GzipFile(filename='',mode='wb',fileobj=out,mtime=0,compresslevel=6) as gz:shutil.copyfileobj(inp,gz)
  manifest={**meta,'minAppVersionCode':1,'artifactUrl':'catalog.db.gz','compression':'gzip','compressedBytes':(temp/'catalog.db.gz').stat().st_size,'uncompressedBytes':(temp/'catalog.db').stat().st_size,'compressedSha256':digest(temp/'catalog.db.gz'),'uncompressedSha256':digest(temp/'catalog.db')}
  (temp/'manifest.json').write_text(json_text(manifest)+'\n')
  audit={'counts':dict(counts),'columnJsonDiscrepancies':dict(mismatch),'reconciliationPolicy':'JSON is authoritative, as desktop loadFromDb; columns and JSON compared, exact JSON retained. IDs must match.','studioAliasCollisions':{k:sorted(v) for k,v in sorted(aliases.items()) if len(v)>1},'noPersonalTables':True}
  (temp/'audit.json').write_text(json_text(audit)+'\n');verify_artifacts(temp,kind=='full')
  for name in ('catalog.db','catalog.db.gz','manifest.json','audit.json'):os.replace(temp/name,output/name)
  return manifest
 finally:shutil.rmtree(temp)
def main():
 p=argparse.ArgumentParser(description=__doc__);g=p.add_mutually_exclusive_group(required=True);g.add_argument('--source-db');g.add_argument('--source-sql-gz');p.add_argument('--output-dir',required=True);p.add_argument('--kind',choices=['fixture','full'],default='full');p.add_argument('--generation');p.add_argument('--epoch',type=int,default=int(os.environ.get('SOURCE_DATE_EPOCH','0')));a=p.parse_args()
 source=a.source_db
 if a.source_sql_gz:
  staging=Path(a.output_dir);staging.mkdir(parents=True,exist_ok=True);source=staging/'restored-input.db'
  if source.exists():source.unlink()
  c=sqlite3.connect(source,isolation_level=None)
  # Execute complete SQL statements one at a time; never materialize the full dump.
  statement=''
  with gzip.open(a.source_sql_gz,'rt') as f:
   for line in f:
    statement+=line
    if sqlite3.complete_statement(statement):c.execute(statement);statement=''
  require(not statement.strip(),'truncated SQL dump');c.close()
 m=build(source,a.output_dir,a.kind,a.epoch,a.generation);print(json.dumps({k:m[k] for k in ('generationId','titleCount','sourceCount','compressedBytes','uncompressedBytes')},sort_keys=True))
if __name__=='__main__':main()
