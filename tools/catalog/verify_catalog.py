#!/usr/bin/env python3
import argparse,json
from catalog_common import verify_artifacts
p=argparse.ArgumentParser();p.add_argument('--artifact-dir',required=True);p.add_argument('--require-full',action='store_true');a=p.parse_args()
m=verify_artifacts(a.artifact_dir,a.require_full);print(json.dumps({'verified':True,'generation':m['generationId'],'titles':m['titleCount'],'sources':m['sourceCount']}))
