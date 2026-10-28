#!/usr/bin/env python3
"""Gradle entry point. Verify full inputs or generate a labelled neutral fixture."""
import argparse,shutil,json
from pathlib import Path
from make_fixture import make
from export_catalog import build
from catalog_common import verify_artifacts
p=argparse.ArgumentParser();p.add_argument('--output-dir',required=True);p.add_argument('--catalog-dir');p.add_argument('--test-assets',action='store_true');a=p.parse_args()
out=Path(a.output_dir);out.mkdir(parents=True,exist_ok=True)
def fixture(destination,variant):
 destination.mkdir(parents=True,exist_ok=True);source=destination/'input.db'
 if source.exists():source.unlink()
 make(source,variant);build(source,destination,'fixture',0,'fixture-v'+str(variant));return destination
if a.test_assets:
 for v in (1,2):
  source=fixture(out.parent/('catalog-test-build-'+str(v)),v);dest=out/('fixture-v'+str(v));dest.mkdir(exist_ok=True)
  for name in ('catalog.db.gz','manifest.json'):shutil.copyfile(source/name,dest/("catalog.bin" if name=="catalog.db.gz" else name))
else:
 source=Path(a.catalog_dir) if a.catalog_dir else fixture(out.parent/'catalog-fixture-build',1)
 verify_artifacts(source,bool(a.catalog_dir));dest=out/'catalog';dest.mkdir(exist_ok=True)
 for name in ('catalog.db.gz','manifest.json'):shutil.copyfile(source/name,dest/("catalog.bin" if name=="catalog.db.gz" else name))
