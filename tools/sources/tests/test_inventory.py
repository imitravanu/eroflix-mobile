import sys,json,tempfile,sqlite3,unittest,hashlib
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from inventory import freeze,TOP,PROTECTED
class InventoryTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.db=self.root/'catalog.db';c=sqlite3.connect(self.db)
  c.executescript('create table catalog_meta(key text,value text);create table titles(row_id integer,id text,updated_at_ms integer);create table sources(id text,title_row integer,provider text,original_url text,kind text,external_id text);')
  hosts=TOP+[sorted(PROTECTED)[0]]
  n=0
  for host in hosts:
   for index in range(8):
    n+=1;kind='EMBED' if host in TOP else 'FILE' if index%2 else 'HLS'
    c.execute('insert into titles values (?,?,?)',(n,'neutral-'+str(n),1700000000000+index*1000))
    c.execute('insert into sources values (?,?,?,?,?,?)',('source-'+str(n),n,'neutral',f'https://{host}/neutral-{n}?token=a%2Bb',kind,str(n)))
  c.executemany('insert into catalog_meta values (?,?)',[('generationId','neutral-v1'),('titleCount',str(n)),('sourceCount',str(n))]);c.commit();c.close()
 def tearDown(self):self.temp.cleanup()
 def test_freeze_is_deterministic_and_reconciles_denominators(self):
  a=freeze(self.db,self.root/'a');b=freeze(self.db,self.root/'b');self.assertEqual(a,b)
  raw=(self.root/'a/samples.json').read_bytes();self.assertEqual(hashlib.sha256(raw).hexdigest(),a['sampleSha256'])
  samples=json.loads(raw)['samples']
  for family in TOP:
   self.assertEqual(a['families'][family],{'sourceCount':8,'titleCount':8,'selected':5})
   self.assertEqual(len({r['titleId'] for r in samples[family]}),5)
   self.assertTrue(all('?token=a%2Bb' in r['url'] for r in samples[family]))
 def test_frozen_denominator_cannot_be_overwritten(self):
  freeze(self.db,self.root/'a')
  with self.assertRaises(ValueError):freeze(self.db,self.root/'a')
if __name__=='__main__':unittest.main()
