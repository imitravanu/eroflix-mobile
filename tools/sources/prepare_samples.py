#!/usr/bin/env python3
"""Copy only an explicitly selected frozen sample into optional debug assets."""
import argparse,hashlib,json,shutil
from pathlib import Path

def prepare(source,output):
 source=Path(source);raw=(source/'samples.json').read_bytes();inventory=json.loads((source/'inventory.json').read_text())
 if hashlib.sha256(raw).hexdigest()!=inventory['sampleSha256']:raise ValueError('Frozen sample digest mismatch')
 doc=json.loads(raw)
 if doc['formatVersion']!=1 or sum(map(len,doc['samples'].values()))>200:raise ValueError('Unsupported sample format/size')
 output=Path(output)/'source-lab';output.mkdir(parents=True,exist_ok=True);(output/'samples.json').write_bytes(raw)
 return inventory['sampleSha256']
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--sample-dir',required=True);p.add_argument('--output-dir',required=True);a=p.parse_args();print('Prepared explicitly selected debug-only frozen samples: '+prepare(a.sample_dir,a.output_dir))
