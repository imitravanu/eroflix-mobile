# Desktop catalog audit for the mobile plan

**Observed:** 2026-10-27, through a read-only SQLite connection and read
transaction. No source hosts were contacted; counts do not imply live playback.

**Input:** `eroflix/data/eroflix.db`.

## Measured inventory

| Metric | Observed value |
| --- | ---: |
| Movie rows | 286,207 |
| Source records | 321,999 |
| `embed` source records | 287,504 |
| `file` source records | 33,959 |
| `hls` source records | 536 |
| Titles with only embed sources | 251,717 |
| Titles with at least one file/HLS source | 34,490 |
| Relative source URLs | 0 |
| Plain HTTP source URLs | 0 |
| Desktop DB main-file bytes | 284,270,592 |
| Existing desktop SQL gzip bytes | 30,356,611 |

Approximately **89.3% of source records** are embeds; **88.0% of titles** are
embed-only. These are different denominators. A direct file's presence still
requires probing for expiry, headers, codec support, and reachability.

The file sizes above are not the future normalized mobile database sizes.
The desktop gzip is a SQL dump, not a compressed prebuilt `catalog.db`.

## Top catalog providers

| Provider tag | Titles |
| --- | ---: |
| `tubepornclassic` | 129,158 |
| `eporner` | 76,889 |
| `tizam` | 24,147 |
| `fullerotic` | 15,692 |
| `porntrex` | 11,985 |
| `pornissimo` | 7,645 |
| `erogarga` | 4,487 |
| `allclassic` | 4,000 |
| `watcherotic` | 2,802 |
| `mp4-porn` | 1,938 |
| `vintagepornfun` | 1,544 |
| `pinkueiga` | 1,034 |

The original blueprint's illustrative provider counts differ from the real
catalog. UI totals must be queried from the active generation.

## Top embed hosts

| Host | Embed source records |
| --- | ---: |
| `tubepornclassic.com` | 129,341 |
| `www.eporner.com` | 78,355 |
| `abv.tizam.org` | 24,147 |
| `www.fulleroticmovies.net` | 15,692 |
| `www.porntrex.com` | 11,985 |
| `www.pornissimo.org` | 7,645 |
| `allclassic.porn` | 4,000 |
| `watcherotic.com` | 3,681 |
| `vk.com` | 2,043 |
| `mp4-porn.org` | 1,938 |
| `bysewihe.com` | 1,544 |
| `piq.re` | 1,544 |

Catalog provider and media host are separate identities. A single title can
have multiple sources or mirrors; these rows are not unique-title counts.

## Metadata availability

| Field | Titles with a nonempty value |
| --- | ---: |
| Country | 43,834 |
| Year | 47,582 |
| Studio | 14,482 |
| Structured duration / durationSec | 0 |
| Structured actors / performers arrays | 0 |

Tag occurrences across titles: `star:` **85,927**, `studio:` **14,468**,
`series:` **2,499**. These counts are occurrences, not unique entities or
verified names. Existing slugs provide navigation identity; an inferred display
label must not be presented as independently verified biographical metadata.

Implications:

- Unknown country/year/duration remains discoverable. Filters need explicit
  unknown options, rather than silently dropping most of the catalog.
- Duration cannot be a meaningful populated slider on this dataset initially.
  Show its unavailable/unknown state; runtime duration discovery can be retained
  locally without rewriting the catalog generation.
- Performer/studio/series navigation derives from tags and studio strings;
  images and series part ordering need explicit provenance or neutral defaults.

## Existing schema and code reference

The desktop schema has `movies`, `user_state`, and `meta`; `sqlite_stat1` is
SQLite's statistics table. Each `movies.data` value stores the fuller record.

Desktop JSON fields observed:

```text
id, title, year, country, studio, description, poster, backdrop, tags,
adult, license, source, externalId, sources, createdAt, updatedAt
```

Source fields observed:

```text
url, type, label, quality, sandbox
```

Read these files when implementing conversion and source behavior:

- [Desktop SQLite store](../../lib/store-sqlite.js): schema, persisted JSON,
  stable IDs, state fields, and timestamps.
- [Catalog normalization](../../lib/catalog.js): source types, unknown metadata,
  tag normalization, and ID rules.
- [Desktop relay](../../lib/relay.js): protected-host header rules; serialized
  API responses may use `/api/relay`, while DB values remain original URLs.
- [Series/entity indexing](../../lib/series-api.js): current entity-tag behavior.
- [Export notes](../../export/RESTORE.md): existing snapshot format and provenance.

Mobile conversion preserves `movies.id`, sources, and provenance, then builds
its own indexed relational schema. Never feed the full desktop SQL dump into
the application as its production database: it includes a different schema
and a desktop personal-state table.

## P02 follow-up output

Create a deterministic machine-readable audit from a consistent snapshot with:
counts, ID/source reconciliation, per-field coverage, provider/host coverage,
artifact hashes/sizes, schema version, and exporter version. Record the input
generation/date. A live catalog may have changed since these observations.

Keep raw records, full source URLs and media outside committed reports. The
small checked-in conversion fixtures use neutral synthetic titles and hosts.
