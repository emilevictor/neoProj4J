# proj4j-db

PROJ 9.8.1's authority database — **EPSG v12.029**, ESRI ArcGIS Pro 3.6, IGNF 3.1.0, IAU_2015,
NKG 1.0.w, NRCAN — transcoded at build time into a deterministic read-only binary index, plus the
pure-Java reader that serves it through `org.locationtech.proj4j.spi.ProjDatabase`.

| | |
|---|---:|
| index, unpacked | **6,746,280 B** (6.43 MiB) |
| index, `gzip -9` | **1,720,110 B** (1.64 MiB) |
| raw `proj.db` for comparison | 10,223,616 B / 1,858,856 B |
| saving | **−34.0 % unpacked, −7.5 % compressed** |
| dependencies | `proj4j` only |
| Java level | 8, same as core (this artifact ships to consumers) |

## Why not just ship `proj.db`

A raw SQLite file inside a jar cannot be opened by JDBC SQLite without **a native library and an
extraction to a filesystem we do not control**. Core has zero runtime dependencies and must keep them:
a downstream consumer is here specifically to delete Apache SIS and the `catch (LinkageError)` over a
duplicate `org.opengis.util.CodeList` that kills their Spark executors, and a native dependency would be
worse than what they have.

Reading SQLite's b-tree pages in pure Java is possible — 4096-byte pages, 2496 of them — but it means
implementing a write-oriented format, carrying its indexes, and inheriting a header whose change-counter
fields differ between two runs that produce identical rows.

Transcoding buys three things instead:

1. **Determinism.** Every ordering in the file is a total order over the data, so two generations from
   the same input are byte-identical, and `git diff --exit-code` after a regeneration proves it. That
   is a contributor step and deliberately not a CI gate — no workflow under `.github/` regenerates.
   See "Why regeneration is not a CI gate", below, for the decision and its cost. This
   runs in Spark executors that require bit-reproducible output.
2. **The bytes we do not need are gone** — the write path, the b-tree interior pages, the page slack, and
   the 798 KB `idx_usage_object` whose job is done here by a 20-byte-per-row sorted array.
3. **Strings are shared.** `'EPSG'` appears in tens of thousands of rows upstream; here it appears once,
   referenced by a varint. 97,937 distinct strings, 2,480,497 B of UTF-8.

## The format, `.pjdx` v1

Full specification in `PjdxFormat`'s javadoc — writer and reader share that class, so the two cannot
drift. In outline: a 64-byte header carrying a SHA-256 of the content, a section directory, one shared
string pool, 28 keyed row tables and 7 sorted indexes.

Two rules do most of the work:

- **String ids are assigned in ascending unsigned UTF-8 byte order.** So `id -> string` is an array
  index and `string -> id` is a binary search over the same array — no second index, no hash table
  anywhere in the file.
- **Rows are sorted by key tuple, ties broken by encoded row bytes.** A lookup is a binary search that
  touches only the key array and decodes exactly one row; and the order is total even for the tables
  whose keys are genuinely non-unique (aliases, supersessions), so nothing about the input's order can
  leak into the output.

### Where the bytes go

| section | bytes | | section | bytes |
|---|---:|---|---|---:|
| `S_STRINGS` | 2,872,257 | | `X_USAGE_BY_OBJECT` | 488,108 |
| `S_CONVERSION` | 562,587 | | `X_CRS_BY_NAME` | 470,488 |
| `S_HELMERT_TRANSFORMATION` | 449,616 | | `X_CRS_BY_CODE` | 220,648 |
| `S_ALIAS` | 439,389 | | `X_OP_BY_SOURCE_TARGET` | 114,032 |
| `S_PROJECTED_CRS` | 334,376 | | `X_OP_BY_TARGET_SOURCE` | 114,032 |
| `S_EXTENT` | 208,559 | | `X_CRS_BY_DATUM` | 66,176 |
| `S_GEODETIC_CRS` | 67,573 | | 21 smaller sections | 337,387 |

## The schema subset

**Transcoded (32 tables read, 28 sections written):** `metadata`, `unit_of_measure`, `celestial_body`,
`ellipsoid`, `prime_meridian`, `geodetic_datum`, `vertical_datum`, both `*_datum_ensemble_member`
tables, `coordinate_system`, `axis`, `geodetic_crs`, `projected_crs`, `vertical_crs`, `compound_crs`,
`engineering_crs`, `conversion_table` (names resolved from `conversion_method` and `conversion_param`),
`helmert_transformation_table` (method name from `coordinate_operation_method`), `grid_transformation`,
`other_transformation`, `concatenated_operation`, `concatenated_operation_step`, `usage`, `extent`,
`grid_alternatives`, `alias_name`, `supersession`, `deprecation`,
`authority_to_authority_preference`.

**Dropped on purpose**, each with a reason, in `GenerateIndex`'s javadoc: `coordinate_metadata`
(point-motion epochs for a capability proj4j does not have — 921,600 B of the SQLite file), `scope`,
`sqlite_stat1`, `grid_packages`, `builtin_authorities`, `versioned_auth_name_mapping`,
`geoid_model`, and the free-text `description`/`anchor` columns on
datums and CRSs. `extent.description` **is** kept: it is the string a human is shown as the area of use.

### Helmert parameters are ported, not remembered

`helmert_transformation_table` stores parameters as named columns, not `paramN` slots. The mapping to
EPSG parameter codes — 8605–8611, 1040–1047, 1049, 8617/8618/8667 — and the conditional structure
deciding which exist for a 3-, 7-, 8-, 10-, 15-parameter or Molodensky-Badekas case are transcribed from
`9.8.1:src/iso19111/factory.cpp:6337-6450`, with the codes read from
`9.8.1:src/proj_constants.h:509-559`. A wrong code here binds a value to the wrong slot silently.

## The round-trip proof

`gen/VerifyIndex` reads the SQLite dump and the generated index and compares **every row of every
transcoded table, field by field**, with doubles compared by `Double.compare` so a value that came back
as a neighbouring representable number fails.

```
VerifyIndex: 502,422 field comparisons in 6.3 s
VerifyIndex: OK -- every transcoded row matches the SQLite source
```

It runs inside `-Pregen-db` and fails the build on any mismatch. A size measurement and a handful of
spot checks cannot catch a field read in the wrong order; this can — and on its first run it caught two
real problems, both upstream data quirks rather than transcoder bugs:

### Two upstream quirks this pinned

1. **`PROJ:ENh` is a 3-dimensional Cartesian coordinate system whose three axes are numbered 1, 2 and
   2.** Easting (`PROJ:1`, order 1), Northing (`PROJ:2`, order 2) and Ellipsoidal height (`PROJ:3`,
   **order 2**). Keying axes on `(cs, order)` alone left those two tied, and the tiebreak put
   *Ellipsoidal height* before *Northing* — silently reordering the axes of a 3D system. The axis key is
   therefore `(cs authority, cs code, order, axis authority, axis code)`, which reproduces the order
   PROJ's own `ORDER BY coordinate_system_order` yields, since SQLite returns equal keys in primary-key
   order. Every other one of the 149 coordinate systems is well-formed.
2. **Two concatenated operations number their steps 2 and 3, with no step 1** —
   `NKG:ITRF2000_TO_NKG_ETRF00` and `NKG:ITRF2014_TO_NKG_ETRF14`. So `DbOperationStep.stepNumber()` is
   **not** a 1-based index into `steps()`, and a consumer that treats it as one reads the wrong step for
   those two. Pinned by `PjdxDatabaseTest.stepNumbersAreNotNecessarilyOneBased`.

## Provenance: two sources that must agree

`Proj4jDb.open()` cross-checks the `metadata` table *inside* the index against the build-stamped
`db.properties` sidecar next to it, and **throws if they disagree**. An EPSG version string alone cannot
do this — the same EPSG version can be packaged differently.

The sidecar carries two digests, deliberately:

- `artifactSha256` — the whole file. This is what the `maven-enforcer-plugin` `requireFileChecksum` gate
  compares in **every** default build, so a hand-edited artifact fails.
- `contentSha256` — the digest embedded in the header at offset 32, covering bytes `[64, len)`. The
  reader verifies this against the bytes it actually read on every open, so it is the value a Spark job
  logs per executor to **prove all executors ran the same data**.

Comparing the wrong one of those two fails on every correct artifact — which is exactly what happened on
the verifier's first run, and is why they are now named apart.

## Building

**Default build, on every machine and in CI:**

```
mvn -B -pl db -am install -Dmaven.javadoc.skip=true
```

Nothing but a SHA-256 check happens: **no `sqlite3`, no `cmake`, no Python, no PROJ checkout, no
network.** A machine without the toolchain builds successfully and cannot silently use a stale artifact.
`-am` is needed because the root pom sets `maven.install.skip=true`, so `core` is never placed in the
local repository and a `-pl db` on its own has nothing to resolve against.

**Regeneration**, manual. Substitute the path to your own `proj.db`:

```
SOURCE_DATE_EPOCH=1775865600 \
  mvn -Pregen-db -pl db -am process-classes -Dproj.db.source=/path/to/proj.db
git diff --exit-code
```

1. `src/gen/dump.sh` fails fast if `sqlite3` is absent, with a message pointing at omitting the profile.
   Never a silent fallback.
2. `GenerateIndex` transcodes, writes `db.properties` with `epsgVersion` **read back from the `metadata`
   table** and `generatedAtUtc` from `SOURCE_DATE_EPOCH`, and prints the SHA-256 to paste into
   `<proj4j.db.sha256>`.
3. `VerifyIndex` runs the exhaustive round-trip and fails the build on any mismatch.

`git diff --exit-code` is the reproducibility proof: a clean exit means regeneration reproduced the
checked-in bytes. Verified to exit 0.

**Both halves of that command line are load-bearing, and getting either wrong makes the proof
vacuous rather than failing loudly:**

- **`process-classes`, not `validate`.** The three `-Pregen-db` executions bind to
  `generate-resources`, and `validate` runs before it. `mvn -Pregen-db validate` is BUILD SUCCESS in
  half a second having run nothing at all, after which `git diff` passes because nothing was
  written — it reports "reproducible" in exactly the case where no regeneration occurred. This
  README documented that command until it was measured. Naming `generate-resources` as the target
  phase does not work either: with `-am` the reactor has not produced a `core` jar by then, and
  dependency resolution fails. `install` also works, and additionally runs the tests.
- **`SOURCE_DATE_EPOCH`.** It sets `generatedAtUtc`. Leave it out and that field takes the wall
  clock, so `git diff` is non-empty after every run, including a bit-perfect one. The value above
  is the one that reproduces the checked-in `db.properties`; the index itself does not depend on it.

### Why regeneration is not a CI gate — decided, 2026-08-13 (task #102)

No workflow under `.github/` runs regeneration, and none will. This was asked as an open question;
the answer is no, and the reasoning is recorded here so it is not re-litigated by someone who reads
"contributor step" as "not got round to it yet".

**What a gate would cost.** It needs `sqlite3` *and* a PROJ **9.8.1** `proj.db`. That file is not
checked into PROJ — it is built by `sqlite3` from the 52 SQL files listed in
`data/sql_filelist.cmake` — and Ubuntu's `proj-bin` does not ship it at 9.8.1. So a runner would
have to do a cmake source build of PROJ on every run, or cache one and then have the gate silently
verify against the wrong version when the cache went stale. A cmake toolchain in CI is exactly what
`db/pom.xml`'s "`mvn install` must not need sqlite3, cmake, Python, a PROJ checkout or a network"
promise exists to prevent, and a gate that quietly contradicts a module's central promise is worse
than no gate.

**What is already covered without any of that.** The `requireFileChecksum` enforcer bound to
`validate` runs in *every* default build, on every machine, with no toolchain. It fails on a
hand-edited artifact and on a stale one. That is the property that protects consumers of this
module.

**What stays uncovered, stated exactly.** One thing: *"regeneration no longer reproduces these
bytes"* — a determinism regression in `GenerateIndex` or in the format's orderings that nobody
notices because nobody regenerated. It would surface the next time a contributor regenerates, as a
non-empty diff, which is precisely the check documented above and verified to exit 0. Accepting that
gap is the decision. If it ever needs closing, the honest way is a scheduled workflow that builds
PROJ from source, not a step bolted onto the PR path.

| machine | invocation | outcome |
|---|---|---|
| no sqlite3, no PROJ checkout | `mvn install` | **builds**; verifies checksum |
| no sqlite3 | `mvn install -Pregen-db` | fails fast, message points at omitting the profile |
| full toolchain | `mvn install -Pregen-db` | regenerates; `git diff --exit-code` is the check |
| tampered artifact | `mvn install` | fails on SHA-256 mismatch |

`proj4j.db.max.jar.bytes` is 2,600,000: a data bump that blows the budget fails the build here rather
than surprising a downstream container image.

## Using it

Discovery is **opt-in**. Core never scans for a provider implicitly — an implicit `ServiceLoader` walk
touches a classpath proj4j does not control, and that is how a library minding its own business triggers
a `LinkageError` in somebody else's jar.

```java
try (ProjDatabase db = Proj4jDb.open()) {          // null if the data is not on the classpath
    DbCrs crs = db.crs("EPSG", "4979");            // geographic 3D, which no +init= file can express
    DbCoordinateSystem cs = db.coordinateSystem(crs.coordinateSystem().authName(),
                                                crs.coordinateSystem().code());
    // cs.dimension() == 3, cs.axes().get(2).name() == "Ellipsoidal height"

    for (DbOperation op : db.operationsBetween("EPSG", "4267", "EPSG", "4269")) {
        // nine published grid transformations, 0.15 m to 2.0 m, not one of them a ballpark
    }
}
```

Or through the SPI, sorted by `(priority, name)` with duplicates **rejected** rather than ordered by
luck:

```java
ProjDatabase db = ProjDatabaseProvider.openFirst(myClassLoader);
```

`Proj4jDb.open(ResourceResolver)` takes any resolver, so an unpacked data directory works via
`DirectoryResourceResolver`.

### If it starts refusing things it used to answer

Attaching a database is an **opt-in behaviour change, not a purely additive one.** The dictionary
still owns the parameters of every code it knows, so those do not move — but the database also
decides *which operation runs between two CRSs*, and that path does not care where either CRS came
from. Measured by a consumer against the published 2.2.0 artifacts, over the 5,162 codes the
dictionary can produce: 529 answers move and 1,072 are withdrawn.

The withdrawals are the strict defaults doing their job — `BallparkPolicy.REJECT`,
`GridPolicy.REQUIRE_ALL` and `BestOperationPolicy.REQUIRE_BEST`, all three of which only have
something to refuse once there is an authority to refuse it against. Turning all three off is one
call as of 2.3.0:

```java
ProjContext ctx = ProjContext.permissive().database(Proj4jDb.open()).build();
```

It is called `permissive()` and not `projCompatible()` on purpose: what it has been shown to do is
*not refuse*, which is a different claim from *return PROJ 9.8.1's answer*. Ask
`isBallparkTransformation()`, `accuracy()` and `missingGrids()` on the operation to find out which
kind of answer you got.

## In the reactor, and published

This module is in the root `<modules>` at `pom.xml:353`, and it is published. An earlier version of
this section said it was "deliberately not in the root `<modules>`" and gave the XML to add. That was
wrong, and it is the sort of wrong that costs an afternoon: it reads as "this is not built here", so
the next person goes looking for a separate build. The genuinely profile-gated modules are `golden`,
`benchmark` and `benchmark-ab` (`pom.xml:307-333`).

What **is** opt-in is the runtime, not the build. Being on the classpath does nothing on its own; an
application has to call `Proj4jDb.open()` and hand the result to `ProjContext.Builder.database(..)`.
See "Using it" above, and the note on `ProjDatabaseProvider` for why core will not scan for you.

## Licensing

`proj.db` is **not** covered by `LICENSE.EPSG` alone. It also contains ESRI (ArcGIS Pro 3.6), IGNF
3.1.0, IAU_2015, NKG 1.0.w and NRCAN data — 2,991 ESRI, 2,201 IAU_2015 and 864 IGNF CRSs, 2 NKG CRSs,
and **no NRCAN CRSs at all**. Those have distinct terms, which is why this is a separate artifact with
its own aggregated `NOTICE` rather than something folded into `proj4j-epsg` and misrepresenting that
one.

NRCAN is in that list for two rows, and it is worth saying which two rather than letting the name
imply a body of Canadian CRSs that is not there. Measured against both `proj.db` and the shipped
index, NRCAN owns **zero CRSs and exactly two `grid_transformation` rows**:
`NRCAN:HT2_1997_NAD83CSRSV7` and `NRCAN:HT2_2002_NAD83CSRSV7`, both `NAD83(CSRS)v7 → CGVD28 height`
(EPSG:8254 → EPSG:5713) at 0.05 m by EPSG method 1060, over the geoid grids `HT2_1997.byn` and
`HT2_2002v70.byn`. They are the 1997 and 2002 epochs of a model EPSG itself publishes only at 2010
(`EPSG:9987`, `HT2_2010v70.byn`), so `operationsBetween` returns all three as alternatives for that
pair. Small, but not redundant — which is why the authority stays.

It is also why existing `proj4j-epsg` consumers can upgrade the code without taking on a 6.4 MB
dependency they did not ask for, and vice versa.
