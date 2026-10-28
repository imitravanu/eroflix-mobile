# Persistent data — P02

Catalog schema 1 and personal schema 2 live in `src/main/assets/schema/`.
`PersonalStorage.initialize()` creates v1 then migrates transactionally to v2
(manual-unhide timestamp). Failed migrations roll back; unsupported newer
personal schemas produce a recoverable error without deleting the database.

The app constructs one `GenerationStorage`, one `PersonalStorage` and one
`CatalogStorage` off the main thread. Only the personal writer opens a writable
connection. State plus its durable revision commit in one serialized transaction.
Retained snapshots include entity/source metadata and a placeholder-ready title;
poster bytes are not downloaded by P02. Watch coverage and transfer behavior stay
with P08/P06/P07; their schema fields are reserved.

Up to three catalog readers lease separate connections. Each opens personal data
read-only, attaches one immutable catalog URI read-only and sets query_only.
A transaction pins personal revision and visibility to the same snapshot. Queries
use keyset cursors with generation/revision/query identity and a hard limit of
120. Ordinary shelves return at most 120 entries; P08 adds library pagination/UI.
No personal rows or hidden-ID set are loaded for full-catalog filtering.

Catalog activation blocks admission, waits for old leases, opens the candidate,
syncs a pointer containing current+previous, atomically renames it and syncs its
directory. The commit cannot be interrupted by coroutine cancellation. Restart
cleans abandoned staging, opens current or falls back to previous. Clean-up drains
leases and removes only obsolete catalog directories. Catalog operations never
close/delete personal or media storage. Stage/activate/rollback are ready for P09's
transport; remote signature/sequence policy remains P09 work.

Compressed/uncompressed bounds, hashes, schema/application ID, integrity, foreign
keys and count/FTS smoke checks gate staging. First launch shows extraction and
verification progress. Equal/older bundled metadata does not replace a newer
active compatible generation. Native SQLite work finishes at a statement boundary;
coroutine cancellation prevents obsolete results being delivered, and leases close
in finally blocks. The driver does not expose a coroutine interrupt API.

Run host tests through `./gradlew verifyUnitTests`; real Android tests through
`ANDROID_SERIAL=<selected-device> ./gradlew verifyAndroidTests`.
The latter generates its own neutral generation assets and tests the bundled
native driver on-device, including read-only attachment and failure boundaries.


Exporter 1.0.2 adds optional facet_options/facet_members accelerators: integer
option IDs replace repeated relation/label lookups during broad shared-scope
aggregation. Each membership is unique by title/option, so COUNT(*) retains
per-facet distinct-title semantics even for multivalued studios. Unknown values
are explicit option rows. Older generations use the original normalized-relation
path. Hidden exclusion uses a SQL-side non-null hidden-ID subquery; it never
loads the hidden set into UI memory. Catalog IDs/schema version remain unchanged.


Exporter 1.0.3 adds optional facet_bitmaps. Each option stores a little-endian
membership mask indexed by generation-local title row ID (bit zero unused).
For shared search/entity/duration scopes, the data reader streams visible row IDs
into one BitSet, intersects each option mask and counts its bits. This preserves
multivalued/Unknown membership without repeatedly sorting broad result sets.
The producer emits masks only when their combined bytes fit 32 MiB; the reader
also enforces that bound and a two-million-row range before selecting this path.
Older/oversized generations retain SQL aggregation. No title or hidden-ID list is
sent to UI, and masks never cross catalog-generation leases. Full host validation
compares bitmap bytes against exact normalized memberships.
