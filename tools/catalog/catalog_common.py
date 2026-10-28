"""Versioned catalog format, exact identity and validation shared by host CLIs."""
import gzip,hashlib,json,sqlite3,unicodedata
from pathlib import Path
def require(condition,message):
 if not condition:raise ValueError(message)

FORMAT=1
SCHEMA=1
EXPORTER='1.0.3'
ROOT=Path(__file__).resolve().parents[2]
def digest(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def stable_digest(*fields):
 h=hashlib.sha256()
 for field in fields:
  b=field.encode('utf-8');h.update(len(b).to_bytes(4,'big'));h.update(b)
 return h.hexdigest()
def fold(s):
 return ''.join(c for c in unicodedata.normalize('NFKD',s.lower()) if unicodedata.category(c)!='Mn')
def is_cjk(c):
 return '\u3400'<=c<='\u9fff' or '\u3040'<=c<='\u30ff' or '\uac00'<=c<='\ud7af' or '\U00020000'<=c<='\U0003134f'
def grams(s):
 return sorted({s[i:i+n] for i,c in enumerate(s) if is_cjk(c) for n in (1,2) if i+n<=len(s) and all(is_cjk(x) for x in s[i:i+n])})
def json_text(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def verify_db(path,manifest):
 c=sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True)
 try:
  require(c.execute('pragma integrity_check').fetchone()[0] == 'ok','integrity failure')
  require(not c.execute('pragma foreign_key_check').fetchall(),'foreign key failure')
  require(c.execute('pragma user_version').fetchone()[0] == SCHEMA,'schema mismatch')
  require(c.execute('pragma application_id').fetchone()[0] == 1163021388,'application mismatch')
  meta=dict(c.execute('select key,value from catalog_meta'))
  for key in ('generationId','titleCount','sourceCount','catalogSchemaVersion','kind','titleIdsSha256','sourceRecordsSha256'):
   require(str(manifest[key]) == meta[key],f'metadata mismatch: {key}')
  require(c.execute('select count(*) from titles').fetchone()[0] == manifest['titleCount'],'title count')
  require(c.execute('select count(*) from sources').fetchone()[0] == manifest['sourceCount'],'source count')
  require(not c.execute("select name from sqlite_master where name in ('user_state','retained_titles','personal_meta')").fetchall(),'personal data leaked')
  ids=hashlib.sha256();sources=hashlib.sha256()
  for (tid,) in c.execute('select id from titles order by id'):ids.update((json_text(tid)+'\n').encode())
  for row in c.execute('select t.id,s.provider,s.kind,s.original_url from sources s join titles t on t.row_id=s.title_row order by s.id'):
   sources.update((json_text(row)+'\n').encode())
  require(ids.hexdigest() == manifest['titleIdsSha256'],'title identity mismatch')
  require(sources.hexdigest() == manifest['sourceRecordsSha256'],'source identity mismatch')
  require(c.execute('select count(*) from search_documents').fetchone()[0] == manifest['titleCount'],'search mapping')
  has_bitmaps=c.execute("select count(*) from sqlite_master where name='facet_bitmaps'").fetchone()[0]
  if has_bitmaps and c.execute('select count(*) from facet_bitmaps').fetchone()[0]:
   require(c.execute('select count(*) from facet_bitmaps').fetchone()[0]==c.execute('select count(*) from facet_options').fetchone()[0],'incomplete facet bitmap options')
   width=(c.execute('select max(row_id) from titles').fetchone()[0]+8)//8
   for option,bits in c.execute('select option_id,bits from facet_bitmaps'):
    require(len(bits)==width,'bitmap width mismatch');expected=bytearray(width)
    for (row_id,) in c.execute('select title_row from facet_members where option_id=?',(option,)):expected[row_id//8]|=1<<(row_id%8)
    require(bits==expected,'facet bitmap membership mismatch')
  # An actual FTS MATCH, even for an empty catalog, catches absent/unreadable FTS.
  c.execute('select rowid from titles_fts where titles_fts match ? limit 1',('"catalogprobe"',)).fetchall()
 finally:c.close()
def verify_artifacts(directory,require_full=False):
 p=Path(directory);m=json.loads((p/'manifest.json').read_text())
 require(m['formatVersion'] == FORMAT and m['catalogSchemaVersion'] == SCHEMA,'incompatible format')
 require(m['compression'] == 'gzip' and m['minAppVersionCode'] <= 1,'incompatible manifest')
 if require_full:require(m['kind'] == 'full' and m['titleCount'] >= 100000,'full catalog required')
 for name,size,key in [('catalog.db.gz','compressedBytes','compressedSha256'),('catalog.db','uncompressedBytes','uncompressedSha256')]:
  f=p/name;require(f.stat().st_size == m[size],f'{name} size mismatch');require(digest(f) == m[key],f'{name} hash mismatch')
 h=hashlib.sha256();count=0
 with gzip.open(p/'catalog.db.gz','rb') as gz:
  for b in iter(lambda:gz.read(1024*1024),b''):
   count+=len(b);require(count <= m['uncompressedBytes'],'gzip expansion too large');h.update(b)
 require(count == m['uncompressedBytes'] and h.hexdigest() == m['uncompressedSha256'],'gzip/database mismatch')
 verify_db(p/'catalog.db',m)
 return m
