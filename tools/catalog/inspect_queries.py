#!/usr/bin/env python3
"""Host query-plan evidence; never a substitute for phone measurements."""
import argparse,json,sqlite3,time,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--database',required=True);p.add_argument('--output',required=True);a=p.parse_args()
c=sqlite3.connect(':memory:');c.execute('CREATE TABLE user_state(title_id TEXT PRIMARY KEY,hidden INTEGER)');c.execute('CREATE INDEX ix_hidden ON user_state(hidden,title_id)')
c.execute('ATTACH DATABASE ? AS catalog',(Path(a.database).resolve().as_uri()+'?mode=ro',))
visible='NOT EXISTS(SELECT 1 FROM user_state u WHERE u.title_id=t.id AND u.hidden=1)'
queries={
'browse':f'SELECT t.id FROM catalog.titles t WHERE {visible} ORDER BY t.sort_title,t.id LIMIT 60',
'search':f"SELECT t.id FROM catalog.titles t WHERE {visible} AND t.row_id IN (SELECT rowid FROM catalog.titles_fts WHERE titles_fts MATCH '\"classic\"*') ORDER BY t.sort_title,t.id LIMIT 60",
'combined':f"SELECT t.id FROM catalog.titles t WHERE {visible} AND t.row_id IN (SELECT title_row FROM catalog.title_providers WHERE key='eporner') AND t.row_id IN (SELECT title_row FROM catalog.title_countries WHERE key='usa') AND t.year BETWEEN 1980 AND 1989 ORDER BY t.sort_title,t.id LIMIT 60",
'facet_totals':"SELECT key,label,title_count FROM catalog.facet_counts WHERE facet='country'",
'entity':f"SELECT t.id FROM catalog.titles t WHERE {visible} AND t.row_id IN (SELECT title_row FROM catalog.title_studios WHERE key=(SELECT key FROM catalog.studios ORDER BY key LIMIT 1)) ORDER BY t.sort_title,t.id LIMIT 60"}
report={'platform':'host, warm runs; not a Pixel 6 budget result','sqlite':sqlite3.sqlite_version,'queries':{}}
for name,sql in queries.items():
 c.execute(sql).fetchall();values=[]
 for _ in range(30):
  t=time.perf_counter();rows=c.execute(sql).fetchall();values.append((time.perf_counter()-t)*1000)
 s=sorted(values);report['queries'][name]={'n':len(s),'rows':len(rows),'p50_ms':statistics.median(s),'p95_ms':s[28],'max_ms':max(s),'plan':[list(r) for r in c.execute('EXPLAIN QUERY PLAN '+sql)]}
Path(a.output).write_text(json.dumps(report,indent=2));print(json.dumps({k:round(v['p95_ms'],2) for k,v in report['queries'].items()}))
