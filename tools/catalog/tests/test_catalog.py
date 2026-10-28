import sys,tempfile,unittest,sqlite3,json,gzip,hashlib,subprocess
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from export_catalog import build
from make_fixture import make
from catalog_common import verify_artifacts,stable_digest

class CatalogTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.db=self.root/'input.db';make(self.db)
 def tearDown(self):self.tmp.cleanup()
 def test_reproducible_identity_original_sources_and_no_personal(self):
  a=build(self.db,self.root/'a',generation='fixture-v1');b=build(self.db,self.root/'b',generation='fixture-v1')
  self.assertEqual(a,b);verify_artifacts(self.root/'a')
  c=sqlite3.connect(self.root/'a/catalog.db');s=c.execute('select t.id,s.provider,s.kind,s.original_url,s.id from sources s join titles t on t.row_id=s.title_row limit 1').fetchone()
  self.assertEqual(stable_digest(*s[:4]),s[4]);self.assertIn('?token=exact%2Bvalue',s[3]);self.assertEqual(a['titleCount'],5)
  self.assertNotIn('user_state',{r[0] for r in c.execute('select name from sqlite_master')});c.close()
  with gzip.open(self.root/'a/catalog.db.gz','rb') as gz:self.assertEqual(hashlib.sha256(gz.read()).hexdigest(),a['uncompressedSha256'])
 def test_invalid_json_identity_stops_publication(self):
  c=sqlite3.connect(self.db);r=json.loads(c.execute('select data from movies limit 1').fetchone()[0]);r['id']='wrong';c.execute('update movies set data=? where id=?',(json.dumps(r),'fixture:aurora'));c.commit();c.close()
  with self.assertRaises(ValueError):build(self.db,self.root/'a')
  self.assertFalse((self.root/'a/manifest.json').exists())
 def test_tampered_assets_and_fixture_release_rejected(self):
  build(self.db,self.root/'a')
  with self.assertRaises(ValueError):verify_artifacts(self.root/'a',True)
  with open(self.root/'a/catalog.db.gz','ab') as f:f.write(b'tamper')
  with self.assertRaises(ValueError):verify_artifacts(self.root/'a')
 def test_fts_cjk_mapping_and_unknown_metadata(self):
  build(self.db,self.root/'a');c=sqlite3.connect(self.root/'a/catalog.db')
  self.assertEqual(c.execute('select t.id from titles t where row_id in (select rowid from titles_fts where titles_fts match ?)',('"cafe"*',)).fetchone()[0],'fixture:unicode')
  self.assertEqual(c.execute('select t.id from titles t join cjk_grams g on g.title_row=t.row_id where gram=?',('京物',)).fetchone()[0],'fixture:unicode')
  self.assertEqual(c.execute('select year from titles where id=?',('fixture:observatory',)).fetchone()[0],None)
  self.assertEqual(c.execute('select count(*) from title_performers where key=?',('alex-sky',)).fetchone()[0],2);c.close()
 def test_sql_gzip_alternate_restores_only_in_staging(self):
  c=sqlite3.connect(self.db)
  with gzip.open(self.root/'input.sql.gz','wt') as f:f.write('\n'.join(c.iterdump())+'\n')
  c.close()
  result=subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'export_catalog.py'),'--source-sql-gz',str(self.root/'input.sql.gz'),'--output-dir',str(self.root/'a'),'--kind','fixture'],capture_output=True,text=True)
  self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(verify_artifacts(self.root/'a')['titleCount'],5)
 def test_cross_facet_counts_match_distinct_title_relations(self):
  build(self.db,self.root/'a');c=sqlite3.connect(self.root/'a/catalog.db')
  for scope,key,group,value,count in c.execute('select scope_facet,scope_key,facet,key,title_count from facet_cross_counts'):
   def membership(facet,selected):
    if facet=='decade':return {r[0] for r in c.execute("select row_id from titles where coalesce(cast(year/10*10 as text),'@unknown')=?",(selected,))}
    table={'provider':'providers','country':'countries','studio':'studios'}[facet]
    if selected=='@unknown':return {r[0] for r in c.execute(f"select row_id from titles t where not exists(select 1 from title_{table} r where r.title_row=t.row_id)")}
    return {r[0] for r in c.execute(f'select title_row from title_{table} where key=?',(selected,))}
   self.assertEqual(count,len(membership(scope,key)&membership(group,value)),(scope,key,group,value))
  c.close()
 def test_compact_memberships_preserve_distinct_multistudio_and_unknown_counts(self):
  c=sqlite3.connect(self.db);m=json.loads(c.execute('select data from movies where id=?',('fixture:aurora',)).fetchone()[0]);m['tags'].append('studio:second-studio');c.execute('update movies set data=? where id=?',(json.dumps(m),'fixture:aurora'));c.commit();c.close()
  build(self.db,self.root/'a');c=sqlite3.connect(self.root/'a/catalog.db')
  expected=c.execute('select facet,key,label,title_count from facet_counts order by facet,key').fetchall()
  actual=c.execute('select o.facet,o.key,o.label,count(*) from facet_members m join facet_options o on o.id=m.option_id group by o.id order by o.facet,o.key').fetchall()
  self.assertEqual(expected,actual)
  # One title can belong to two studios, but must count once in every other group.
  rid=c.execute('select row_id from titles where id=?',('fixture:aurora',)).fetchone()[0]
  counts=dict(c.execute('select o.facet,count(*) from facet_members m join facet_options o on o.id=m.option_id where m.title_row=? group by o.facet',(rid,)))
  self.assertEqual(counts,{'provider':1,'country':1,'decade':1,'studio':2});c.close()
 def test_bitmaps_match_membership_exactly_including_high_bits(self):
  c=sqlite3.connect(self.db);row=list(c.execute('select * from movies limit 1').fetchone())
  for i in range(70):
   copy=row.copy();copy[0]='fixture:bit-'+str(i);m=json.loads(copy[-1]);m['id']=copy[0];copy[-1]=json.dumps(m);c.execute('insert into movies values (?,?,?,?,?,?,?,?,?,?)',copy)
  c.commit();c.close()
  build(self.db,self.root/'a');c=sqlite3.connect(self.root/'a/catalog.db')
  for option,bits in c.execute('select option_id,bits from facet_bitmaps'):
   decoded={i*8+j for i,byte in enumerate(bits) for j in range(8) if byte&(1<<j)}
   self.assertEqual(decoded,{r[0] for r in c.execute('select title_row from facet_members where option_id=?',(option,))})
  c.close()
 def test_json_column_discrepancies_are_explicit(self):
  c=sqlite3.connect(self.db);c.execute('update movies set year=1900 where id=?',('fixture:aurora',));c.commit();c.close();build(self.db,self.root/'a')
  audit=json.loads((self.root/'a/audit.json').read_text());self.assertEqual(audit['columnJsonDiscrepancies']['year'],1)
  c=sqlite3.connect(self.root/'a/catalog.db');self.assertEqual(c.execute('select year from titles where id=?',('fixture:aurora',)).fetchone()[0],1984);c.close()
if __name__=='__main__':unittest.main()
