# Catalog build tools — P02

Python standard library only. Run from `mobile/`. Conversion never edits the
source database: SQLite's backup API reads it in read-only mode into ignored
staging. Input snapshots can contain desktop personal rows; only the normalized
catalog database is packaged. Keep every build directory ignored.

```sh
python3 tools/catalog/export_catalog.py --source-db ../data/eroflix.db \
  --output-dir local-data/catalog-full --kind full \
  --generation full-20261027 --epoch 1793059200
python3 tools/catalog/verify_catalog.py --artifact-dir local-data/catalog-full --require-full
python3 -m unittest discover -s tools/catalog/tests -v
./gradlew assembleDebug -PcatalogDir=local-data/catalog-full
```

Alternative input: replace `--source-db` with
`--source-sql-gz ../export/catalog.sql.gz`. SQL is restored only into the output
staging directory. The original export is never changed.

Without `-PcatalogDir`, debug builds generate a five-title neutral fixture.
Release builds require a verified full input and reject a fixture directory.
The app asset is named `catalog.bin`: its bytes are the deterministic gzip,
renamed to prevent Android's asset merger automatically expanding `.gz` files.
The host artifacts retain their normal `catalog.db.gz` name.

Output: `catalog.db`, `catalog.db.gz`, `manifest.json`, `audit.json`. Preserve exact
input bytes/explicit epoch/generation/exporter and SQLite versions to reproduce
the same output. `SOURCE_DATE_EPOCH` supplies the epoch when `--epoch` is omitted;
otherwise the default is zero. No current wall-clock time enters the artifact.

JSON records are authoritative, matching desktop `loadFromDb()`. Key/JSON IDs
must match or publication fails. Scalar column/JSON differences are counted in
the audit; original JSON, source data, timestamps, provenance and URLs are retained.
Unknown provider uses the reserved `@unknown` key; country/year/studio absence
has explicit query semantics. Typed tag slugs remain entity identities. Studio
aliases are only accepted when unambiguous; collisions appear in the audit.
Source IDs use P01's exact four-field length-prefixed SHA-256 algorithm.

Search uses NFKD/lowercase/mark removal, literal Unicode word prefixes joined by
AND, and unicode61 FTS5. Punctuation separates words; user FTS operators are
literal terms. CJK single/bigrams narrow candidates before a literal substring
check in normalized search documents. Search accepts at most 256 characters/16
words, with no unbounded SQL text interpolation. Tests cover diacritics, Cyrillic,
CJK substring lookup and original signed-query URL bytes using neutral fixtures.

Catalog schema is version 1. Stable title-ID and source-record digests reconcile
all records. Verification checks gzip expansion against the declared DB digest,
size, integrity, foreign keys, metadata/counts, FTS mapping and personal-table
exclusion. Failed conversion does not publish its staging directory.
