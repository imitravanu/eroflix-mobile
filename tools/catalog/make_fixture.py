#!/usr/bin/env python3
"""Produce neutral desktop-shaped inputs; no live catalog is checked in."""
import json,sqlite3,sys
from pathlib import Path

def make(path,variant=1):
 c=sqlite3.connect(path);c.executescript('CREATE TABLE movies(id TEXT PRIMARY KEY,seq INTEGER,source TEXT,external_id TEXT,country TEXT,year INTEGER,title TEXT,created_at INTEGER,updated_at INTEGER,data TEXT); CREATE TABLE user_state(id TEXT,favorite INTEGER); INSERT INTO user_state VALUES (\'private-desktop-state\',1);')
 rows=[('fixture:aurora','Aurora Field Notes',1984,'Fixture country','fixture-archive',['studio:fixture-archive','star:alex-sky']),('fixture:river','River Journey',1992,'Fixture country','fixture-archive',['studio:fixture-archive','series:field-notes']),('fixture:observatory','Night Observatory',None,None,None,[]),('fixture:unicode','Café 東京物語 — Записки',2001,'日本','archive',['star:alex-sky']),('fixture:quotes','Notes "OR" stars*',1984,None,None,[])]
 if variant==2:rows=rows[1:]+[('fixture:new','New Generation',2020,None,None,[])]
 for i,(tid,title,year,country,studio,tags) in enumerate(rows):
  m=dict(id=tid,title=title,year=year,country=country,studio=studio,tags=tags,source='fixture',externalId=str(i),createdAt=1700000000000,updatedAt=1700000000000,description='Neutral test catalog.',poster=None,backdrop=None,license='Fixture',sources=[{'url':'https://example.invalid/'+tid+'?token=exact%2Bvalue','type':'embed','label':'Neutral source'}])
  c.execute('INSERT INTO movies VALUES (?,?,?,?,?,?,?,?,?,?)',(tid,i,m['source'],m['externalId'],country,year,title,m['createdAt'],m['updatedAt'],json.dumps(m,ensure_ascii=False)))
 c.commit();c.close()
if __name__=='__main__':
 p=Path(sys.argv[1]);p.parent.mkdir(parents=True,exist_ok=True)
 if p.exists():p.unlink()
 make(p,int(sys.argv[2]) if len(sys.argv)>2 else 1)
