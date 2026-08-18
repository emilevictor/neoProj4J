# neoProj4J 2.3.0 release notes

Released 2026-08-18 to Maven Central under the same groupId and artifactId as 2.2.0 — see the README
for the coordinates. Every figure below was measured in the pinned container or counted out of a
committed file at this commit, **not read off a published artifact**; where a number is still pending
it says so.

**2.2.0 was about which operation runs. 2.3.0 is about the two operators that were not there at all.**
`+proj=gridshift` and `+proj=defmodel` were the two largest holes left in the pipeline package, and
between them they accounted for 90 of the 92 conformance rows that were not passing. Both are now
ported, and both files run clean. Around them: the last of the cheap conformance rows, four CRS codes
that were missing from the legacy dictionary, and eight findings from the first consumer to try
`neoproj4j-db` in anger — six of which were library defects and four of which were text.

Conformance moves from **7,819 / 7,911 to 7,915 / 7,922 genuine passes, with zero regressions**. Read
the next paragraph before you quote that.

**Some of the gain came from correcting the instrument, not the library.** Our gie harness was building
`crs_src`/`crs_dst` CRSs through the legacy lon-first path while `gie` means authority axis order. Two
of the rows this release gains were failing for no other reason, and three more needed that harness
change together with new dictionary codes before they would land. The harness was wrong and is now
right, and real `gie` 9.8.1 passes all six rows in that file — but a measurement change and a library
change are different things, and this project exists not to blur them. It is
[item 2 of compatibility](#2-the-gie-harness-now-asks-for-authority-axis-order) and the
[conformance section](#230-conformance) says which rows.

If you only read one section, read [Compatibility](#230-compatibility-what-moves-and-by-how-much). If
you only read one item, read item 1 if you catch `UnknownAuthorityCodeException`, and item 3 otherwise.

**Where the numbers come from.** One full container run on 2026-08-17,
`./docker/run.sh ci conformance golden determinism`, aarch64 / Temurin 21.0.11, **exit 0** with
`golden` failing as designed. Bench was not run and nothing in this release starts it. The CI workflow
files are committed; **nothing here should be read as a green CI run**.

---

## 2.3.0 compatibility: what moves, and by how much

> **japicmp 0.26.1 has been run, from its command line**; it is still not wired into the Maven build.
> It compared the `core` jar built from a clean checkout of the 2.2.0 release commit `12a5db4` against
> the `core` jar built at this branch tip, at public visibility. In incompatible-changes-only mode the
> report is **"No changes"**. The full public report is **2 new public methods, 0 new public types, 0
> removed anything, and 3 modified classes** — `api/LegacyAdapters` gaining `crsFactory`, `api/ProjContext`
> gaining `permissive()`, and `api/Proj`, which japicmp marks modified with nothing to show at public
> visibility because its changes are all in non-public members. The two new grid operators add **no**
> public types: they are package-private inside `pipeline`, reached through `+proj=` strings rather
> than through Java.
>
> **And the one change in this release most likely to break a build is invisible to that report.**
> `UnknownAuthorityCodeException extends CrsCreationException`, so item 1 below replaces a thrown
> exception with its own **superclass**. No signature changed, so japicmp has nothing to say, and a
> `catch (UnknownAuthorityCodeException)` that used to fire now does not.
>
> Same caveat as 2.2.0: japicmp skipped its semantic-versioning check, because a build from a detached
> worktree stamps the version as `0.0.0-NO_WORKTREE_AND_INDEX` and japicmp will not reason about
> semantic versioning at major version zero. The compatibility verdict was measured and is unaffected;
> the version stamp was not machine-checked.

Items 1 and 2 change observable behaviour. Items 3 through 6 are additions that can only turn a refusal
into an answer. Item 7 is a default that deliberately did not change.

### 1. A code the database knows and this library cannot build now throws `CrsCreationException`

**What changed.** `Proj.createCrs` / `Proj.fromName`, given an authority code that the attached
`neoproj4j-db` **has** and that `DatabaseCrsFactory` declines to build, now throws
`CrsCreationException` with `ErrorCause.CRS_TYPE_NOT_SUPPORTED`, naming the CRS type. It used to throw
one of two things, and which one you got depended on something irrelevant: whether the authority
happened to have a dictionary file at all. `ESRI:102100` had one, so the code came out as
`UnknownAuthorityCodeException` — "unknown code" for a code the configured database knew perfectly
well. `IGNF:LAMB93` had none, so it came out as `DATABASE_UNAVAILABLE` — "no database" while one was
attached and answering. Neither was the cause. The cause, for both, is that the database holds the
code as a type this library builds only for geodetic CRSs.

**What it breaks.** `UnknownAuthorityCodeException` is a subclass of `CrsCreationException`, so this is
a widening. `catch (CrsCreationException)`, `catch (Proj4jException)` and `catch (RuntimeException)`
are unaffected. **`catch (UnknownAuthorityCodeException)` stops matching.** The population is not
small: across the shipped database, 5,048 projected CRSs plus 1,279 non-projected ones cannot be built
from a code, and every one of them in an authority that has a dictionary file used to arrive as
`UnknownAuthorityCodeException`.

**Why it was worth doing anyway.** This makes the failure honest without making it resolve. A wrong
reason is worse than a blunt one, because it sends the reader to the wrong place — the consumer report
behind this change spent its effort on the dictionary and the classpath, which were both fine.

### 2. The gie harness now asks for authority axis order

**This is a change to how we measure, and it is not a change to the library.**
`Proj4jGieOperationFactory.createCrs` built `crs_src`/`crs_dst` CRSs at the default
`AxisOrderPolicy.LEGACY`, which guarantees lon-first, while `gie` means the authority's own axis
order. Two rows were failing with got-values that are bit-exact matches for a lon-first misread —
`proj +proj=utm +zone=32 +ellps=GRS80` on `55 12` reproduces our old answer to the digit. Upstream
deliberately omits `proj_normalize_for_visualization` in its own `crs_to_crs_operation()`
(`gie.cpp:1119-1158`), and real `gie` 9.8.1 runs `epsg_no_grid.gie` at 6 succeeded, 0 failed.

Blast radius is bounded at the **8 corpus assertions that use `crs_src`** — nothing else in the
harness reads a CRS by name. **Nothing shipped in the library changed here.** If you were relying on
the old conformance figures as a like-for-like series, this is the discontinuity, and it is worth
five rows of the ninety-six.

### 3. `+proj=gridshift` and `+proj=defmodel` exist

Additive in the sense that matters: a pipeline definition naming either used to throw
`Unknown projection: gridshift` at construction, and now builds and transforms. No definition that
worked before now behaves differently — the operator lookup only ever ran after every other route had
declined.

`gridshift` brings upstream's sample-role vocabulary (`latitude_offset`/`longitude_offset` for a
geographic grid, `easting_offset`/`northing_offset` for a projected one, with the positional fallback
that swaps the first two bands between those cases), projected grids, and the biquadratic NADCON5
kernel. Two upstream behaviours that look like defects were reproduced rather than "improved": the
biquadratic inverse **does not iterate**, which is what stops the San Francisco extract diverging, and
the band-role reader **ignores** `positive_value="west"`, which our own `GeoTiffGrid` honours —
copying that code instead would have sign-flipped longitude on nine rows.

`defmodel` reads its master file with `PipelineJson` and its grids on the same generic layer as
`xyzgridshift`. It requires the coordinate's epoch and refuses a coordinate that has none, which makes
it the one operator in the package where the time ordinate is mandatory. Upstream's `isGeographicCRS`
defaults to `true` when the lookup fails — the one place `defmodel_impl.hpp` is deliberately not
fail-closed — and that is reproduced too, because tightening it kills a ten-row block.

If you catch `Proj4jException` around pipeline construction to detect "not implemented", the two names
have moved out of that bucket. `ConstructionThrowableKindTest` moved with them.

### 4. Four CRS codes now resolve that did not

`EPSG:4979`, `EPSG:7843`, `EPSG:7912` and `ESRI:102100`. All four were absent from all five legacy
dictionaries; the three EPSG ones were already in the shipped `neoproj4j-db`, and `ESRI:102100` is
absent from upstream's `esri` file too — it jumps 102108 → 102110 — so that one is an omission the
fork inherited rather than created. **Every parameter string is PROJ's own**, from
`projinfo <code> -o PROJ` on 9.8.1; `ESRI:102100` emits byte-identically to `EPSG:3857`, which is also
what PROJ names as its replacement.

Dictionary definitions go **9,013 → 9,017** (`epsg` 5,758, `esri` 2,955). The pinned count in
`StrictParseModeTest` moved with them, and so did the golden table — see [gates](#230-gates).

A code that used to throw now returns a CRS. If you were treating the exception as "this code does not
exist", it no longer fires for these four.

### 5. Message text changed in five places, and one message was a lie

- **`proj4j-epsg` → `neoproj4j-epsg`** in four user-facing strings. The old names resolve on Maven
  Central to upstream 1.4.3, so the advice was not merely stale, it pointed at a different library.
- **"the legacy PROJ.4 dictionary is not on the classpath" was emitted without checking.** It sat on a
  `catch (IllegalStateException)` branch, and `CRSFactory.createFromName` raises that for two different
  situations: the dictionary is genuinely absent, *and* the dictionary is present with no file for that
  authority. `IGNF`, `IAU_2015` and `NKG` are the second case. It is now a branch, and says which.
- **The vintage note described the wrong context.** The message appended `databaseInfo().vintageNote()`,
  and the no-argument form reads the *default* context, not the `ctx` whose lookup just failed. Attach a
  database to an explicit `ProjContext` and the exception's tail described a deployment you were not
  using — "An authority database is configured" and "No proj.db." could appear in the same sentence.
  It is `databaseInfo(ctx)` now. 2.2.0 shipped the same bug.
- **"adding the database cannot move a coordinate that already worked" was answering a different
  question than the one a reader asks.** It is true about CRS *resolution*: the dictionary is tried
  first, so a code the dictionary knows resolves to byte-identical parameters either way. It is false
  about *coordinates*, because the database also decides which operation runs between two CRSs, and
  that path does not care where either CRS came from. Both sites now say both halves, with the
  consumer's own measured counts: of 5,162 codes the dictionary can produce, **529 answers move and
  1,072 are withdrawn** under the strict defaults.

Parse no message text. These are diagnostics.

### 6. Two new entry points, both opt-in

- **`ProjContext.permissive()`** — one call for `BallparkPolicy.ALLOW` + `GridPolicy.PROJ4_COMPAT` +
  `BestOperationPolicy.ALLOW_DEGRADED`. A consumer who wanted "answer if you can" had to find three
  knobs and had no way to learn there were three. **`DEFAULT` stays strict**; this has to be asked for
  by name. It is `permissive()` and not `projCompatible()` on purpose: what the triple has been shown
  to do is *not refuse* — the strict triple withdrew 1,664 answers in a consumer's measurement and this
  one withdraws none — and that is a different claim from *returns PROJ 9.8.1's answer*.
- **`LegacyAdapters.crsFactory(ProjContext)`** — a `CRSFactory` that can resolve a code only the
  attached database knows, while still handing back legacy `CoordinateReferenceSystem` objects. One new
  static method rather than eight overloads across `CRSFactory` and `CoordinateTransformFactory`, which
  would also have dragged `api.ProjContext` into the root package's signatures. Documented limit: the
  reader and `Registry` inside `CRSFactory` are `private static` and shared across instances, so a
  subclass can change behaviour in the methods it overrides but cannot give itself a different
  dictionary.

### 7. `CRSFactory.createFromName`'s default is unchanged, and that is deliberate

It stays `AxisOrderPolicy.LEGACY`. `LEGACY` exists to guarantee lon-first, and **1,657 of 5,708 EPSG
projected CRSs plus 675 of 689 geographic 2D are not east-first under authority order**. Every existing
consumer of the legacy API assumes lon-first. Item 2 changed the harness, not this.

`ProjContext.DEFAULT` also still has no database, and `setDefaultContext` still does not govern the
legacy factories. The reason is sharper than "silent movement": nothing distinguishes "never set" from
"set to `DEFAULT`", so re-routing would hand every legacy caller `ProjContext.DEFAULT`, whose
`BallparkPolicy` is `REJECT`, and `EPSG:4267 → EPSG:4269` would start throwing. That pair is the first
assertion in `LegacyApiUnchangedTest`, and it is the stated reason the bridge exists.

---

## 2.3.0 conformance

| | pass / denominator | fail | skip | vacuous |
|---|---|---:|---:|---:|
| branch point `12a5db4` | 7,819 / 7,911 | 90 | 2 | 12 |
| **2.3.0** | **7,915 / 7,922** | **6** | **1** | **1** |

**Quote the pair, never the percentage alone.** GIGS is unchanged at **1,170 / 1,170**.

**The arithmetic, so it can be checked rather than believed.** `7,923 − 1 vacuous = 7,922`, then
`7,922 − 6 FAIL − 1 SKIP = 7,915`. The branch point reproduces its own published figure the same way
— `7,923 − 12 = 7,911`, then `7,911 − 90 − 2 = 7,819` — which is the control that says the method is
right. The corpus index is byte-identical at **7,923 assertion keys across 42 files**, so no assertion
appeared or disappeared; what moved is the manifest. `gie-expected-failures.tsv` goes from **104 data
rows to 8: 96 removed, none added.** A key absent from that file is expected to pass, so removing rows
makes the gate stricter, not looser.

**Where the 96 went, by cause, summing to 96 exactly.**

| file | rows | of which vacuous | what closed it |
|---|---:|---:|---|
| `gridshift.gie` | 57 | 6 | the operator |
| `defmodel.gie` | 33 | 4 | the operator |
| `epsg_no_grid.gie` | 5 | 0 | three changes, only sufficient together — see below |
| `more_builtins.gie#74:0` | 1 | 1 | an `affine` message that was already right and only arrived at construction |

The five `epsg_no_grid` rows are blocks 0, 1, 2, 3 and 5. Blocks 1, 2 and 3 were failing at CRS
creation with `UnknownAuthorityCodeException` for `EPSG:4979` and `EPSG:7843`, so the new dictionary
codes were necessary. Blocks 0 and 5 were failing on deviation alone, with got-values that are
bit-exact lon-first misreads, so the harness's axis-order switch is what closed those. Teaching
`Proj.applyAxisPolicy` to ask the database was needed so a projected CRS comes out in its own order at
all. **Which of the three closed which of the five, beyond that two-and-three split, was not measured
separately and is not claimed here.**

**Eleven of the twelve vacuous rows became real measurements, which is why the denominator moved from
7,911 to 7,922.** A vacuous row is one where PROJ built the operation and rejected the coordinate while
proj4j could not build the operation at all: a naive harness scores that a pass, so it is evidence about
neither engine and is excluded from both sides. De-vacuating is triggered by **registration**, not by
correctness, so there was a real window during this work where the headline could have fallen while the
library improved.

**The six failures that remain, named.**

- **Four krovak assertions, `builtins.gie#135:5-8`** — a deliberate divergence, already ruled on, and
  permanent. PROJ's krovak inverse is not the inverse of PROJ's own forward: `krovak_setup` never sets
  `P->ra` and never calls `pj_calc_ellipsoid_params`, so PROJ's own round trip misses by 138.6 m where
  ours is exact. We keep ours correct and pin PROJ's as upstream. Worth 0.0505 pp of headroom that will
  never be recovered.
- **`epsg_no_grid.gie#4:0`** — the blocker is **operation discovery, not arithmetic**. Fed PROJ's own
  pipeline string for the pair, our engine lands at about 0.09 mm against a 0.1 mm tolerance, so the
  15-parameter time-dependent Helmert is already right. `db.operationsBetween("EPSG","7843","EPSG","7912")`
  returns 0, because EPSG:8049 is published between the *geocentric* EPSG:7789 and EPSG:7842 and the
  selector does not compose across that. Block 3 of the same file passes on the same CRS pair only
  because EPSG:8049's static terms are all zero, so at its own reference epoch of 2020.0 doing nothing
  is the right answer; block 4 is the same request six years later, where it is not. That is written
  into the manifest reason rather than left to be rediscovered.
- **`epsg_grid.gie#1:0` — a `SKIP` until this release, and the reason it did not become a pass is the
  interesting part.** `fr_ign_RAF20.tif` (342,920 bytes, IGN France open licence, recorded in
  `NOTICE-gie.md` §4) is now vendored under `proj-data-cdn/`, and `GieGridAvailability.OnClasspath`
  searches that directory as well as `proj-data/` — it searched only the first, so a grid the library
  could already resolve was still reported missing to `require_grid`. With the grid there the block
  finally runs, and refuses at `crs_src EPSG:9785`: RGF93 v2b + NGF-IGN69 height, a **compound** CRS,
  and `DatabaseCrsFactory` builds only geodetic types. Closing it needs compound and vertical CRS
  construction from the database plus a selector that can pair a compound source with a 3D geographic
  target through a geoid grid — its own release, alongside projected CRSs from the database. **Nothing
  was lost by finding out**: `SKIP` and `FAIL` both sit in the denominator and neither is a pass, so
  the ratio is identical either way.

**The one remaining `SKIP` is `us_nga_egm08_25.tif`, deliberately not vendored: 80,585,622 bytes.** The
licence is not the obstacle — NGA public domain, and PROJ marks it `direct_download=1`,
`open_license=1` — and neither is the code. GitHub warns above 50 MB per file and a vendored copy could
never be taken back out of the history. **Only one of the two grid files this release could have
vendored was vendored, on purpose**, and the manifest says so in those words.

**One row stays permanently vacuous.** `more_builtins.gie#39:0` is
`+proj=ob_tran +o_proj=helmert`, which needs `ob_tran` to wrap something that is not a `Projection`;
`helmert` is a pipeline step operator, not a `Projection`. One assertion, and the branch that would
make it pass could also make it silently succeed on nonsense. Left alone.

---

## 2.3.0 gates

One run, `./docker/run.sh ci conformance golden determinism --out /tmp/neoproj4j-out-230 --volume neoproj4j-m2-230`,
2026-08-17, aarch64 / Temurin 21.0.11. **Exit 0.** Total 0m41s.

| gate | result | figure |
|---|---|---|
| `ci` | PASS | **3,079** tests, 0 failures, 6 skipped, 280 report files |
| `conformance` | PASS | **7,915 / 7,922**, regressed 0, 7,923 assertions evaluated |
| `golden` | **FAIL, as designed** | 2,083 `UNEXPLAINED` rows, **55 of 55** rules pinned |
| `determinism` | PASS | 22 tests, 0 failures, one leg |
| `bench` | **not run** | `bench.yaml` is `workflow_dispatch` only and nothing here starts it |

### ci — 3,079 against 3,029, both read off a run

Neither number is the other plus a delta. That single integer once caught a half-merged PR when four
green gates did not, so it is measured on both sides every time. Module split: core 2,621, conformance
355, db 89, geoapi 14 — which sums to 3,079 as a second, independent count. The 2.2.0 release commit
`12a5db4`, measured the same way, is 3,029 (core 2,573, conformance 354, db 88, geoapi 14).

**The `CI_MIN_TESTS` floor stays at 3,000, and its safety margin is now 10 tests.** The floor is not a
round number for its own sake: it is chosen as the highest multiple of 100 that still *fails* when the
`db` module drops out of the reactor, which is the failure this gate exists to catch. `3,079 − 89 =
2,990`, still under 3,000, so the property holds. It held by 59 tests at 2.2.0; this release added 50
tests and spent 49 of that margin. **The next release that adds an ordinary test class has to raise the
floor to 3,100 in the same change** — at a total of 3,089 a db-less run reads exactly 3,000 and passes.
The durable fix is to assert the property directly instead of relying on a comment being read, and it
is filed.

### conformance — and the three counts that have to agree

`notrun=0` against a 7,923-key index, and `skips == still_failing == manifest rows == 8`. When those
three disagree the harness is measuring something other than what it reports, which is why all three
are printed.

### golden — red on purpose, and now 20 rows longer than its own baseline

`11,944 UNCHANGED · 41,486 CHANGED · 20 ADDED · 0 REMOVED · 39,423 INTENDED · 2,083 UNEXPLAINED` over
53,450 rows, 55 of 55 rules pinned, and none of `FIGURES_MOVED`, `COUNT_MISMATCH`, `DEAD_RULE`,
`EXPIRED_RULE` or `PENDING_RULE_FIRED`.

The baseline `golden/baseline/1.4.3/golden.tsv` is frozen at released upstream 1.4.3 and holds 53,430
rows, while the sweep enumerates the *current* dictionaries. The four definitions this release adds
therefore emit 20 rows (4 × 5 probes) that the baseline cannot contain. One new rule,
`DICT-2.3.0-FOUR-DEFS-ADDED`, claims exactly those 20. The line-count check used to be a bare equality
and is now `baseline + a declared surplus of 20`, still two-sided, so both 19 rows and 21 rows fail.
**Set the surplus back to 0 when the baseline is refreshed.**

**`golden/pairs.tsv` has drifted, for a reason that predates this release.** Regenerating the golden
inputs also rewrites `pairs.tsv`, and 60 of its 200 rows change. That was proved pre-existing by
regenerating against `git show HEAD:` copies of the dictionaries — the same 60 rows move — and the
cause is that the curator's geographic `TYPE_GRIDSHIFT` bucket grew while its stride is size-sensitive.
No test regenerates `pairs.tsv`, so the committed file stays valid and was left alone.

### determinism — 22 tests, unchanged

The same 22 as the branch point. Nothing in this release touches `determinism.yaml` or the
`Math`-versus-`StrictMath` question, and the gate still finds at least one probe where they diverge,
which is what stops it going vacuously green.

### bench — not run, and nothing here touches a figure

Seven allocation figures were re-pinned in a separate change *before* this one, with the reason
recorded. Nothing in this release changes a bench figure or a bench baseline.

---

## The consumer report behind the database work

Workstream 5 came from a defect report written by the first consumer to try `neoproj4j-db` against the
published 2.2.0 artifacts. Eight findings; six were library defects and four of those were text. Two
things in it deserve recording alongside the fixes.

**The credit, because a changelog that lists only what was broken misrepresents the release.** The
report records that `accuracy()`, `areaOfUse()`, `isBallparkTransformation()`, `ballparkReason()`,
`missingGrids()` and `warnings()` supply the one thing the consumer could not compute for itself, and
that this is why they intend to adopt the database eventually. They chose **not** to adopt it in their
current change, because adoption moves coordinates — which is the correct reading of the strict
defaults, not a complaint about them.

**The caveat, which is closed but was real.** The report claimed the legacy API cannot reach the
database at all. That is false: `LegacyAdapters.transformFactory(ProjContext)` has routed through
`CrsOperation.create` and therefore through `OperationSelector` since 2.2.0, and the selector
special-cases a legacy CRS explicitly. But **no test had ever exercised that path with a database
attached** — all 15 `.database(` sites in the test tree went through the `Proj`/`CrsOperation` API.
Coded, reachable, documented and unproven end to end is a gap worth naming rather than closing
quietly, and `LegacyAdaptersWithDatabaseTest` now closes it. The most likely reason the consumer missed
the method is that the failure messages fixed in item 5 sent them somewhere else.

One finding is still open: whether the `NRCAN` authority earns its mention in the database
documentation needs someone to read the transcoder first, so it was not guessed at.

---

## 2.3.0 documentation corrections

Each of these was a statement in the repository that was measurably false, not a wording preference.

- **`db/README.md` said the module is "deliberately not in the root `<modules>`".** It is in them, and
  it is published. The genuinely profile-gated modules are `golden`, `benchmark` and `benchmark-ab`.
- **`ProjDatabaseProvider`'s javadoc left the service file looking like an oversight.** Nothing in any
  `src/main` calls `discover` or `openFirst`, and that is the design rather than a gap: core must never
  scan the classpath for a database, because presence would then change which operation is selected.
  The contrast that proves the distinction is `ResourceResolvers`, which *does* self-activate from the
  classpath with no application call — safe precisely because the default `GridPolicy` is
  `REQUIRE_ALL`, so an added grid pack turns a refusal into an answer and can never move an answer
  already being returned. Same mechanism, opposite safety profile.
- **`GenericGridSet` named the wrong `gridAt` overload for `defmodel`.** `defmodel.cpp:250-253` calls
  the untyped two-argument form, so a deformation model picks whichever root covers the point
  regardless of its `TYPE`. `gridshift` is the typed overload's only caller.
- **`GieOperation` cited `dist.cpp:69` and "its first ordinate".** It is line 76, and the second
  ordinate.
- **The `ci` test count, the ESRI dictionary count and the golden row count were stale in five files.**
  Now 3,079 / 2,955 / 53,450 everywhere they are asserted as current. Figures inside pasted transcripts
  of past runs were left alone on purpose — editing a number inside a transcript fabricates a run that
  never happened — and so were the sites that correctly describe the *frozen* 53,430-row baseline.
- **Two gate tiers printed the word "blocking" while their own class javadoc said they block no merge.**
  The measurement is trustworthy; where the gate is wired is a separate decision, and the printed text
  now says which is which.

---

## 2.3.0 upgrade guidance

1. **If you catch `UnknownAuthorityCodeException`** — read item 1. Widen to `CrsCreationException`, or
  branch on `ErrorCause`. This is the only change here that can break a compile-clean build at runtime.
2. **If you parse exception message text** — stop. Four strings changed name, one grew a branch, and
  one now describes the context you actually passed.
3. **If you pinned a count of dictionary definitions** — it is 9,017, up from 9,013.
4. **If you use the gie harness or read our conformance figures as a series** — the axis-order change in
  item 2 is a discontinuity in the instrument. Five of the ninety-six rows are on that side of the line.
5. **If you build pipelines by string** — `gridshift` and `defmodel` now construct instead of throwing.
  Definitions you were treating as permanently unsupported will start running.
6. **If you wanted PROJ-like permissiveness** — `ProjContext.permissive()` is one call instead of three
  knobs. It is opt-in and `DEFAULT` is still strict.
7. **If you are on the legacy `CRSFactory` / `CoordinateTransformFactory` API** — nothing moved.
  `LegacyAdapters.crsFactory(ProjContext)` is there when you want database-backed resolution while
  keeping legacy types, and the transform side has worked that way since 2.2.0.
8. **If you were waiting on `IGNF:LAMB93`** — still absent, and that is a decision. Its parameters are
  as verifiable as `ESRI:102100`'s, bit-identical to `EPSG:2154`, but it needs a new `proj4/nad/ignf`
  resource and that immediately raises the question of the other 259 IGNF codes. A packaging decision
  should not be made as a side effect of fixing an error message.

---

# neoProj4J 2.2.0 release notes

Released 2026-08-16 to Maven Central under the same groupId and artifactId as 2.1.0 — see the
README for the coordinates. Figures are measured unless labelled otherwise; where a number is still
pending it says so.

**2.1.0 was the review pass. 2.2.0 is about which operation actually runs.** Ten numbered items, and
the first of them is the one to read: the library named a transformation, published its accuracy, and
then computed the coordinate with something else. The rest follow from fixing that — if the named
operation is the one that runs, then how operations get ranked, which authorities they may come from,
and which ones the area of interest rules out all start to matter, and all three now follow PROJ.
Alongside that: vertical and compound CRSs are read rather than half-read, nineteen operators that were
refused now answer, ESRI documents that name a reference frame without saying where it is are now
refused instead of quietly treated as WGS 84, `+czech` and `+lat_2` and `+lat_0` are read the way PROJ
reads them rather than being discarded or tested for a value, and a pipeline step's `+units` is
resolved through the column PROJ's own `+units=` handler reads.

If you only read one section, read
[Compatibility](#220-compatibility-what-moves-and-by-how-much). If you only read one item, read item 1
if you parse ESRI WKT and item 2 otherwise.

**Where the numbers come from.** Everything below was measured locally in the pinned container, or read
out of a committed file at this commit. The CI workflow files are committed; **nothing here should be
read as a green CI run**.

---

## 2.2.0 compatibility: what moves, and by how much

Every row is **measured**. The magnitude is the point.

> **Do not read a clean API-compatibility report as "nothing moved".** No public signature was removed
> or changed in this release — the new types are additions — so a binary-compatibility check has
> nothing to report. It cannot have anything to report, because **none of the changes in this section
> are signature changes.** Candidate operations are now ranked in PROJ's own order, and an
> area-of-use filter is on by default; both change *which operation you get back* from the same call
> with the same arguments, and one of them changes the coordinate that call returns. An empty API
> report is silent about all of it. Read items 2 through 5.
>
> **japicmp 0.26.1 has now been run**, from its command line; it is not wired into the Maven build. It
> compared the `core` jar built from the clean checkout at the 2.1.0 release commit `6ae23b7`, which
> names itself `neoproj4j-2.1.0.jar`, against the `core` jar built at this branch tip, at public
> visibility. In incompatible-changes-only mode the report is **"No changes"** — no binary-incompatible
> change at public visibility. The full report is **16 new public types, 181 new public members, 15
> modified classes, 1 modified interface, and nothing removed.** The one modified interface is
> `spi/ProjDatabase`, which gains `allowedAuthorities(String, String)`. That is a **`default`** method,
> in the source and in the compiled class, where `javap` prints `public default`, and it is what keeps
> this a minor version bump rather than a breaking one.
>
> One caveat: japicmp skipped its semantic-versioning check, because the worktree build stamps the
> version as `0.0.0-NO_WORKTREE_AND_INDEX` and japicmp will not reason about semantic versioning when
> the major version is zero. The compatibility verdict above was measured and is unaffected, but the
> version stamp itself was not machine-checked.

The first item is the one most likely to break an existing deployment on input you have been feeding
the library for years. Items 2 through 9 change coordinates or change which operation you get. Item 10
changes nothing that already worked.

### 1. ESRI WKT1 naming a `D_` reference frame with no `TOWGS84` is now refused

Read this first if you parse ESRI WKT — from a `.prj` sidecar, a geodatabase, or anything ArcGIS
wrote.

A document like `DATUM["D_European_1950", SPHEROID["Airy_1830", ...]]` names a reference frame. The
spheroid says what shape the earth is. **Nothing in the document says where that frame sits relative to
WGS 84.** Reading it as though it were WGS 84 does not fail — it answers, with the entire datum shift
missing.

`EsriDatumPolicy` has two values, `REJECT` and `ALLOW`, and **`REJECT` is the default.**

**What was being silently omitted**, one probe point per frame:

| ESRI name | probe | omitted shift |
|---|---|---|
| `D_European_1950` | 5°E 52°N | **124.286 m** |
| `D_Tokyo` | 139.7°E 35.7°N | **462.853 m** |
| `D_Pulkovo_1942` | 37.6°E 55.75°N | **117.802 m** |
| `D_CH1903` | 7.44°E 46.95°N | **163.878 m** |

Each figure is a property of its probe point, not of the frame — a different point gives a different
number. The point of the table is the order of magnitude.

**This is the one place in this release where the library deliberately does not do what PROJ does.**
PROJ resolves these names through `proj.db` and answers. proj4j now resolves the name too — the
generated `EsriDatumTable` covers **475** distinct `D_` names, all of them — but resolving the name
only tells you *which published frame it is*, and proj4j has nowhere to carry a frame's identity into
operation selection. `ProjDatabase.crsUsingDatum`, the pivot that would turn a frame identity into
candidate operations, has no caller. So the resolution makes the refusal **specific** rather than
turning it into an answer, and wiring it through is the follow-on work.

**What to do.** Add an explicit `TOWGS84` to the document, which is the honest fix and is what the
refusal message asks for. Or set `EsriDatumPolicy.ALLOW` to get the old behaviour back — with the
figures above in mind.

### 2. The operation the library names is now the operation it runs

Before this release, `selectedOperation()`, `accuracy()`, `areaOfUse()` and `describe()` reported a
chosen candidate that had been stored in a field, while the engine built its transform from
`source.legacy()` and `target.legacy()` exactly as handed in. **The chosen operation was never an
argument to anything that computed a coordinate.** So the facade named one operation and the engine ran
another, with a published accuracy attached to the wrong arithmetic.

Where the two disagree, your coordinates move by the difference between the authority's published
parameters and proj4j's own built-in datum table. At the Cheshire point this repository already audits
— lon −2.0301713578021983, lat 53.35168607080468 — into
`+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000` on the Airy ellipsoid. Every
figure below is **`cs2cs` 9.8.1's own output**, never proj4j's:

| | easting | northing |
|---|---|---|
| **is** — EPSG:1314's published parameters, the operation selection chooses | `398089.000827863` | `383867.000380436` |
| **was** — proj4j's `Datum.OSGB36` table, what the engine ran instead | `398089.003912952` | `383867.000589373` |
| for scale — PROJ with the OSTN15 grid present, which it is not on this classpath | `398088.964408128` | `383865.216031245` |

So the two Helmerts differ by **3.085 mm of easting and 0.209 mm of northing**, and the grid differs
from either Helmert by **1.784 m of northing**.

**3 mm is worth a section not for its size but for its cause.** It is the whole of the difference
between "we ran the operation we named" and "we ran something else that happens to be close" — and the
same code path carries the 1.784 m case. The 1.784 m case is deliberately **not** pinned by a test:
`uk_os_OSTN15_NTv2_OSGBtoETRS.tif` ships in PROJ-data and not in this repository, so a test asserting
it would either fail in CI or quietly assert nothing.

**The rewrite lands on whichever side carries the datum**, source or target. Here the target does, and
the published operation is `EPSG:4277 → EPSG:4326` used in reverse — the orientation an
on-the-source-only implementation would silently miss.

### 3. Candidates are ranked in PROJ's own order, so the first one can be a different operation

`SortFunction::compare`'s criteria are ported, and **they are deliberately not accuracy-ascending.**

For `EPSG:4314 → EPSG:4326`, `projinfo --summary --spatial-test intersects` at 9.8.1 lists three, all
EPSG, in this order:

| rank | operation | accuracy | extent |
|---|---|---|---|
| 1 | `EPSG:15949` | 1.0 m | all Germany onshore |
| 2 | `EPSG:1777` | 3.0 m | former West Germany |
| 3 | `EPSG:15869` | 2.0 m | former East Germany |

**3.0 m sits above 2.0 m** because West Germany's extent is the larger of the two, and a larger
intersection with the area of interest outranks a smaller accuracy number. This library now agrees. The
one difference is `EPSG:15949`, which this library demotes below both because its grid is not reachable
on this classpath — an existing usability tier, not part of the ordering — so the first **usable**
operation is `EPSG:1777`, which is also PROJ's first usable one.

**If you were reading the first candidate and assuming best-accuracy-first, that assumption was never
PROJ's.**

### 4. Where an authority preference exists, non-preferred operations are no longer offered

PROJ's `authority_to_authority_preference` table is now in the index, as section
`S_AUTHORITY_PREFERENCE`, and it is read as a search **order** — not as a filter over the target
authority, which is the plausible wrong port.

For `EPSG:4277 → EPSG:4326` the EPSG prefix answers, so the walk stops before it reaches ESRI, and two
operations disappear from the candidate list:

| dropped | what it is | its own declared accuracy |
|---|---|---|
| `ESRI:108089` | `OSGB_1936_To_WGS_1984_8_BAD_DX` | 5 m |
| `ESRI:108336` | `OSGB_1936_To_WGS_1984_NGA_7PAR` | 21 m |

Neither is deprecated, so nothing else in the pipeline would have removed them. The list comes to
**ten**, against `projinfo`'s nine; the one extra is `EPSG:5339`, which this library reports as a
`SUPERSEDED` candidate rather than omitting, by design.

**An ESRI source still sees ESRI operations.** `ESRI:104105 → EPSG:4326` keeps all three of
`projinfo`'s, because `ESRI|EPSG` resolves to the order `PROJ, ESRI, EPSG` and the walk stops at ESRI.
That is the control that fails loudly if the search order is ever collapsed into a set-shaped filter.

**And a pair with no preference row keeps everything it had**: `EPSG:4267 → EPSG:4269` stays at ten,
which says this change removes only what it is meant to.

The visible effect for `EPSG:4314 → EPSG:4326` is larger than a shorter list. Five ESRI Helmerts each
declare 0.1 m — better than every EPSG operation for that pair — so before this change one of them
ranked first. Now the best usable operation comes from EPSG.

### 5. An area-of-use filter is on by default, and it removes candidates

When you supply no area of interest, one is now synthesised from the source and target CRS extents,
and candidates whose own extent does not meet it are dropped. Two new settings on `ProjContext` say
how:

| setting | values | default |
|---|---|---|
| `SpatialCriterion` | `PARTIAL_INTERSECTION` — keep anything that overlaps at all; `STRICT_CONTAINMENT` — keep only what covers the whole area | **`PARTIAL_INTERSECTION`** |
| `SourceTargetCRSExtentUse` | `SMALLEST`, `BOTH`, `INTERSECTION`, `NONE` | **`SMALLEST`** |

The two criteria genuinely disagree on the same input, which is why both exist. A caller-supplied area
of interest wins over any extent-use setting, and `SourceTargetCRSExtentUse.NONE` turns the filter off
entirely — with no area of interest, nothing is filtered out.

**PROJ declares `STRICT_CONTAINMENT` as its documented default and then does not use it** for
transformation lookup; every PROJ transformation path passes `PARTIAL_INTERSECTION`. This library
follows the behaviour rather than the documentation, and `ProjContext.spatialCriterion()`'s javadoc
names both values and says which one is real. Copying the documentation instead would have silently
discarded candidates PROJ keeps.

### 6. A compound CRS read from a document keeps its vertical half

`CrsDefinitions` took `horizontalComponent()` on its first working line and threaded only that through
every append step, so the height definition was still unread when the parameter list came back.

Across the **5,671** rows of `proj4/wkt/epsg.properties` that silently dropped the height of **72**
compound CRSs, and a standalone `VERT_CS` — **177** rows — could not be read at all.

**No horizontal coordinate moves.** What changes is the parameter list those 72 definitions produce, so
anything that compared, cached or hashed one of them will see it change, and a height that used to be
dropped now survives. `+vunits`, `+vto_meter` and `+z_0` follow `init.cpp`, including that **`+vunits`
wins over `+vto_meter`** when both are present. EPSG:5754, Poolbeg height, is the one row in the
dictionary whose unit has no PROJ identifier — `UNIT["m*0.3048007491", 0.3048007491]` — and it is
carried as the bare factor rather than forced onto a named unit.

### 7. Both axes change sign: `+proj=krovak +czech` was accepted and then ignored

`+czech` asks for Krovak with the sign of both axes flipped — the projection's native convention is
southing and westing, and the flag asks for the opposite. The setter for it has been on
`KrovakProjection` since 1.4.3, but `czech` was absent from `Proj4Keyword`'s allow-list and nothing
dispatched it, so the flag parsed, changed nothing, and raised no complaint.

On `+proj=krovak +lat_0=49.5 +lon_0=42.5 +k=0.9999 +x_0=0 +y_0=0 +ellps=bessel +pm=ferro` at
(16.849771944444445, 50.20901166666667):

| | easting | northing |
|---|---|---|
| was, with `+czech` | `-568990.9954373120` | `-1050538.6308460608` |
| is, with `+czech` | `568990.9954373120` | `1050538.6308460608` |
| `proj` 9.8.1, with `+czech` | `568990.995437` | `1050538.630846` |
| either build, without `+czech` | `-568990.9954373120` | `-1050538.6308460608` |

**If you have `+czech` in a stored definition, your coordinates change sign on both axes.** That is the
point of the flag and it is what PROJ does, but it is a change, so check anything downstream that
assumed the old numbers. **If you do not use `+czech`, nothing moves** — plain `+proj=krovak` returns
exactly what it returned before, and all five of its golden rows are byte-identical after the refactor
that introduced `mod_krovak`. The same fix applies to `+proj=mod_krovak +czech`.

The key is read for its **presence**, matching `pj_param`'s `t` sigil, so any value at all means on; it
carries no number, so it is deliberately in neither the angle nor the double conversion table.

### 8. `lcc` tested whether `+lat_2` was zero, where PROJ tests whether it was given

**4,714.84 km** of northing: `+lat_2=0` and `+lat_0=0` on `lcc` are now told apart from silence.
`lcc.cpp:88-95` tests whether the tokens were supplied; proj4j tested their values, so an explicit
`+lat_2=0` built a cone tangent at `lat_1` instead of one secant at `lat_1` and the equator, and an
explicit `+lat_0=0` was overwritten with `lat_1`.

Measured against `proj` 9.8.1 at 10 E, 40 N on GRS80. No shipped definition moves: none of the 1,885
`+proj=lcc` definitions carries `+lat_2=0`, and the 12 carrying `+lat_0=0` also carry a non-zero
`+lat_2`.

### 9. A pipeline step's `+units` was resolved through the wrong column of PROJ's unit table

**`+units` and `+vunits` on a pipeline step read the column PROJ reads.** PROJ records each linear
unit's conversion to metres twice — a `to_meter` string and a `factor` double (`9.8.1:src/proj.h:258`)
— and on the five U.S. survey rows the two are different doubles. `+units` and `+vunits` are parsed
from the string (`init.cpp:689` and `:726`); only `+proj=unitconvert` reads the factor. The pipeline
layer read the factor for both, so a step written `+units=us-ft` or `+vunits=us-ft` was off by 3 ulps.

Measured with `cct -d 12` at 9.8.1: `+proj=geocent +ellps=GRS80 +units=us-ft` gives
`-8356380.535945920274`, which is `+to_meter=0.304800609601219` exactly and not
`+to_meter=1200/3937`'s `-8356380.535945915617`; the same holds inside `+proj=pipeline +step`, because
a step is built by `pj_init_ctx` like any other definition (`pipeline.cpp:496` → `create.cpp:303`).
The gap between the two columns is 1 to 3 ulps on `us-in`, `us-ft`, `us-yd`, `us-ch` and `us-mi` and
**exactly zero on the other sixteen linear rows and all three angular ones**, which is why nothing
caught it.

`+xy_in`, `+xy_out`, `+z_in` and `+z_out` still read the factor column, because that is the column
PROJ reads for them — the rule is that each key reads its own column, not that one column wins.
Nothing in the golden corpus or the conformance corpus moves: both exercise the CRS path, which was
already correct. `PipelineUnitColumnTest` pins both columns and both directions, so swapping either
back fails with the offending row named.

### 10. Nineteen operators that used to be refused now answer, and nothing that already worked changes

**Nothing here moves an existing coordinate.** Every name below was absent, so `+proj=<name>` was
refused outright. A definition that was refused and now works cannot have returned a wrong number in
the meantime.

**Ten registry projections**, taking `Registry`'s registration count from **151 to 161**:

| operator | what it is | notes |
|---|---|---|
| `+proj=airocean` | Fuller/Dymaxion icosahedral net | `+orient=vertical` (default) or `horizontal` |
| `+proj=isea` | Icosahedral Snyder Equal Area | seven new parameter keys; inverse only where PROJ installs one |
| `+proj=s2` | S2 cube | |
| `+proj=healpix`, `+proj=rhealpix` | the HEALPix pair | |
| `+proj=qsc` | quadrilateralized spherical cube | |
| `+proj=chamb` | Chamberlin trimetric, positioned from three control points | forward only, as upstream; `+lat_3` and `+lon_3` are new keys |
| `+proj=mod_krovak` | Krovak with upstream's tenth-degree polynomial correction | plain `+proj=krovak` is unchanged |
| `+proj=oea` | oblated equal area | `+m`, `+n` and `+theta` are new keys |
| `+proj=rouss` | Roussilhe stereographic | brings a new meridian-distance series, `MDist` |

**Nine pipeline operators**, taking `PIPELINE_ONLY_OPERATORS` from 11 to
`20`: `geoc`, `geogoffset`, `helmert`, `molobadekas`, `molodensky`,
`noop`, `topocentric`, `vertoffset` and `xyzgridshift`. These are `PJ_CONVERSION` and
`PJ_TRANSFORMATION` operators, so they route through `PipelineFactory` rather than through `Registry`,
and a paired allow-list and deny-list is what stops the two routes from overlapping.

**Between them these account for 319 conformance assertions** — 276 for the projections, 43 for the
pipeline operators — and `krovak +czech` above takes 2 more. NKG's `urn:ogc:def:…` syntax takes a
further 33, and 9 one-line parser and engine gaps take the rest, for 361 in all. The full split is in
[conformance](#220-conformance). **Read the pair there rather than adding 370 to a number you
remember**: the denominator moved by 9 at the same time.

### Known limitations, measured and left as they are

- **`oea` and `rouss` are accurate near their origin and nowhere else, and PROJ is no better.** Both
  are series projections — a polynomial fitted about a centre, and a polynomial fitted about a centre
  stops meaning anything a long way from it. Neither PROJ nor this port says so at the boundary; they
  answer, and the answer is not a position. **Both are reproduced against PROJ 9.8.1 and pinned as they
  are rather than worked around**, because diverging from PROJ to paper over a shared limitation would
  hide the evidence.
  - **`oea` misses 16 of 68 global round-trip probes.** The worst is (−135, −45), where the forward
    answers **−1.5920900043274394e22** — bit-identical to PROJ 9.8.1 for the same input. Handing that
    back to the inverse asks for the arc sine of −2.63e7, so the inverse refuses; PROJ 9.8.1 prints
    `*`. Both engines decline, for the same reason.
  - **`rouss` misses 63 of 68.** At (−179.9, −15) the series answers −4.9e7 m and inverts to a latitude
    of **−21625.6 degrees** — sixty turns of the globe — **without refusing**. PROJ 9.8.1 prints
    `21625d36'21.56"S` for the same round trip, agreeing to the digits the intermediate was written
    with.
  - The practical rule for both: use them near `+lat_0` and `+lon_0`, and check a known point before
    you trust a distant one. There is no error you can catch to tell you that you have gone too far, in
    either implementation.
- **`+proj=isea` on an ellipsoid does not round-trip in PROJ 9.8.1 either**, by **14.5 km** at
  (−100, 40) on GRS80 — 11.8 km of it in latitude and 8.5 km in longitude — while the same probe on a
  sphere round-trips to 1e-9 degrees. Pinned as an upstream finding rather than worked around.
- **`KrovakProjection` still does not override `hasInverse()`**, so both `krovak` and `mod_krovak`
  report themselves as having no inverse despite having a working one. This predates the present change
  and is out of scope here; it is already pinned by a test that asserts the wrong answer deliberately
  and names the class whose declaration is wrong.
- **Resolving an ESRI `D_` name does not yet make it usable.** Item 1 says why: the table says which
  published frame a name names, and there is no path from a frame identity to a candidate operation.

---

## 2.2.0 conformance

| | |
|---|---|
| **gie corpus** | **7,819 / 7,911 genuine passes** |
| **GIGS** | **1,170 / 1,170**, unchanged |
| corpus size | **7,923 assertions**, unchanged — no assertion appeared or disappeared |
| the rest | 90 failing · 2 skipped · 94 out of block |
| regressions | **0** |

The branch point measured **7,449 / 7,902**. **Quote the pair, never one number of it** — the
denominator is not a constant, and this release moves it.

**What actually changed is the manifest, not the corpus.** `gie-corpus-index.tsv` is byte-identical at
7,923 keys across 42 files. `gie-expected-failures.tsv` goes from **492 lines to 122**: **370 rows
removed** — 361 `FAIL` and 9 `VACUOUS_EXPECTED_FAILURE` — and **none added**. A key absent from that
file is expected to pass, so removing rows makes the gate stricter, not looser.

**The denominator moved because nine rows stopped being vacuous.** A vacuous row is one where PROJ built
the operation and rejected the coordinate while proj4j could not build the operation at all — both
"failed", and a naive harness scores that a pass, so it is evidence about neither engine and is
excluded from numerator and denominator alike. Nine of them became real measurements this release,
each with its own cause:

| row | why it stopped being vacuous |
|---|---|
| `builtins.gie` blocks 63, 64 | `airocean` now exists |
| `builtins.gie` block 129 | `isea` now exists |
| `4D-API_cs2cs-style.gie` block 19 | `helmert` now exists |
| `builtins.gie` block 167 | `+lat_1=2D32` is parsed as DMS instead of refused |
| `builtins.gie` block 84 | the degenerate cone constant is now reached and refused for PROJ's own reason, not for want of an operator |
| `geotiff_grids.gie` blocks 20, 21 · `more_builtins.gie` block 22 | a missing vertical grid now refuses with the same cause PROJ gives |

That is `7,902 → 7,911`. **Twelve vacuous rows remain and they are concentrated** — six in
`gridshift.gie`, four in `defmodel.gie`, two in `more_builtins.gie` — all blocked on the two grid
operators deferred to 2.3.0. Whoever lands those should expect the denominator to grow by up to
twelve in the same change.

**The arithmetic, so it can be checked rather than believed.** `7,923 − 12 vacuous = 7,911`, then
`7,911 − 90 failing − 2 skipped = 7,819`. The same arithmetic applied to the branch point reproduces
its published figure exactly — `7,923 − 21 = 7,902`, then `7,902 − 451 − 2 = 7,449`. That is the
control that says the method is right rather than fitted.

**Where the 361 removed `FAIL` rows went**, by the cause named in each removed row's own reason, so
the parts sum to 361 exactly rather than to an estimate:

| | rows |
|---|---|
| `airocean` | 92 |
| `s2` | 56 |
| `isea` | 40 |
| NKG — the `urn:ogc:def:…` coordinate-operation syntax | 33 |
| `healpix` | 26 |
| `rhealpix` | 22 |
| `qsc` | 16 |
| `helmert` | 11 |
| `oea`, `rouss`, `geogoffset` | 8 each |
| `molodensky` | 6 |
| `geoc` | 5 |
| `chamb`, `mod_krovak`, `topocentric` | 4 each |
| `noop` | 3 |
| `molobadekas`, `vertoffset`, `xyzgridshift` | 2 each |
| `krovak +czech` · `vgridshift` reading a vertical grid (`4D-API_cs2cs-style.gie` block 38) · GeoTIFF deformation-grid reading (`deformation.gie` block 1, all three channels in one `+grids=` file) | 2 each |
| `+to_meter=2.0/0.2` · `+lon_wrap=180` · `geocent` honouring `+to_meter`/`+units`, which closed a 999 m departure | 1 each |
| **total** | **361** |

Read as three groups: **new projections 276**, **new pipeline operators 43**, **NKG 33**, and **9**
one-line parser and engine gaps.

**GIGS is unchanged at 1,170 / 1,170**, and the two rows that stay failing on purpose stay failing for
the reason 2.1.0 gave. Nothing in this release touches them.

---

## 2.2.0 gates

*Figures are from measurement on this tree in the pinned container (Temurin 21.0.11 / aarch64), or read
out of a committed file at this commit; where one is not yet measured it says so.*

| gate | state | figure |
|---|---|---|
| **ci** | **green** | **`3,029` tests / 0 failures / 6 skipped**. The branch point — the 2.1.0 release commit — measured 2,667 / 0 / 3, against 2.0.0's 2,320. `docker/run.sh`'s `CI_MIN_TESTS` floor **rises 2,600 → 3,000 in this change**; see below |
| **conformance** | **green** | **7,819 / 7,911** against a committed 7,923-key index. The branch point measured 7,449 / 7,902 |
| **golden** | **live, RED on purpose** | **11,944 UNCHANGED · 41,486 CHANGED · 0 ADDED · 0 REMOVED · 39,403 INTENDED · 2,083 UNEXPLAINED** of 53,430 rows, **54 of 54** rules pinned. The branch point read 11,994 · 41,436 · 0 · 0 · 39,149 · 2,287 and 49 of 49 |
| **determinism** | **green** | 22 tests, 0 failures — the same 22 as the branch point. Nothing in this release touches `determinism.yaml` or the `Math`-versus-`StrictMath` question |
| **bench** | **not run, and nothing starts it** | No figure in this section comes from it. See below |

### The 254 golden rows that got claimed, and why that is the intended outcome

Two separate things happened, and the arithmetic has to be read as two halves rather than one net
figure. INTENDED goes 39,149 → 39,403, which is **+254**. UNEXPLAINED goes 2,287 → 2,083, which is
**−204**. The difference between them, 50, is the number of rows that moved from UNCHANGED to CHANGED.

**Fifty rows moved from UNCHANGED to CHANGED, and all fifty were claimed.** That is ten
newly-registered projections at five golden probe rows each:

| rule | rows |
|---|---|
| `PROJ-NEW-PROJECTIONS-REGISTERED` — 44 names and 220 rows becomes 49 and 245, as `healpix`, `qsc`, `rhealpix`, `s2` and `airocean` join its `keys:` list | +25 |
| `PROJ-ISEA-NO-INVERSE-OFF-THE-DEFAULT-ORIENTATION`, new | 5 |
| `PROJ-CHAMB-MOD-KROVAK-OEA-ROUSS-REGISTERED`, new | 20 |
| **total** | **+50** |

**A further 204 rows were already CHANGED and had never been explained.** Three new rules account for
them, and this is the first release in which the backlog shrank because rows were *understood* rather
than because they stopped moving:

| rule | rows |
|---|---|
| `PROJ-CASS-EASTING-A4-SIGN-CORRECTED`, new | 156 |
| `DICT-ESRI-OMERC-VARIANT-A-NEEDS-NO-UOFF`, new | 43 |
| `PARSE-UNITS-LINK-NOW-RECOGNISED`, new | 5 |
| **total** | **−204 from the backlog** |

The rule count goes 49 → 54 and the pinned rows sum to exactly 39,403, which is the INTENDED figure.
Count the rules with a grep anchored on the four-space indent — `grep -Eo '^ +expected_rows: [0-9]+'`
picks up a commented `expected_rows: 944` in a note further down the file and reports one rule and
944 rows too many.

**A row moving to CHANGED with a rule that names its mechanism and pins its count is the intended
outcome.** A row moving to CHANGED with no rule to claim it is what the gate exists to catch, and none
did.

### Red on golden is still the intended state

For the reason 2.0.0 gave: the gate fails on any changed row that no rule claims with a named mechanism
and a pinned count, so those are rows somebody must *explain*, not rows somebody must *undo*. It runs
weekly and on demand.

### The performance gate still does not run in CI

`.github/workflows/bench.yaml` has one trigger, `workflow_dispatch` — no push, no pull request, no
schedule. This was done in 2.1.0 for cost, not correctness, and nothing about it has been softened
since: no `continue-on-error`, no saved figure loosened. **Nothing starts this job automatically.** A
rise in the memory the library allocates per operation, and a change in how many `sin`, `cos` or `log`
calls a transform makes, will go unnoticed until somebody runs `gh workflow run bench.yaml` or
`./docker/run.sh bench`. The release checklist in `HOWTORELEASE.txt` calls for the local run, and that
is the only cover before a tag.

### Two workflow comments have been brought up to date

`.github/workflows/conformance.yaml` recorded `7,584 / 7,905` and `.github/workflows/golden.yaml`
recorded 50 rules and a pinned-row sum of 39,159. Both were measured partway through this release; the
tree measures **7,819 / 7,911**, **54** rules and **39,403**. Both comments already said the right thing
about method — quote the pair, the denominator is not a constant — and were simply stale on the numbers.
They are corrected in this change.

### The determinism evidence, unchanged and worth restating

`StrictMathGoldenTableTest` and `NanBitPatternTest` were run outside CI on five JDK and
instruction-set combinations — Temurin 8.0.502 x86-64, Temurin 11.0.32 on both x86-64 and aarch64,
Temurin 21.0.11 aarch64, OpenJDK 26.0.2 aarch64, spanning both the native and the pure-Java
`StrictMath` implementations — at **54,265 passing comparisons** each: **271,325 `StrictMath` and
221,970 `FastStrictTrig` raw-bit comparisons, zero value mismatches**. The YAML itself has still never
executed.

### No CI run backs any figure here

The workflow files are committed. Everything above was measured locally.

---

## 2.2.0 documentation corrections

One, carried over from 2.1.0's notes because it was never fixed here.

**2.1.0 moved a coordinate by 5,009,377 m and these release notes never said so.**
`RectangularPolyconicProjection` dropped `+lat_0`: the class declared a field for it and never
assigned it, so every read returned 0.0, and applying the parameter now shifts northing by the meridian
arc for that latitude. **Anyone who passed `+lat_0` to `+proj=rpoly` before 2.1.0 was getting it
ignored, and now gets it applied.** The 2.1.0 notes name the change once, in the preamble list at the
top — *"`+lat_0` on `rpoly`"*, three words, no figure — and give it **no numbered compatibility item
and no mention in upgrade guidance**, while every other parameter in that same list has a numbered item
with a measurement: `+lat_ts` is item 2, `+south` is 3, `+zone` is 4, `sconics` is 6. The magnitude
lived only in `golden/README.md`, where the change has its own rule,
`PROJ-RPOLY-LAT0-NOW-APPLIED`, pinned at 5 rows with a band of 5.0e6 .. 5.1e6 m around the arithmetic
that predicts it: the probe's `+lat_0` is 45°, and `0.7853981633974483 × 6378137 = 5,009,377.08 m`.
**Why it stayed hidden is worth knowing, because the same trap will catch the next reader**: both notes
files name the Java class and never the projection name near it, so searching either document for
`rpoly` finds nothing, and searching for the magnitude finds only `golden/README.md`, which is not
where a caller looks. The other half of that fix — restoring the commented-out `P->es = 0` — genuinely
does move no coordinate, since `rpoly`'s formulae never read its ellipsoid. Recorded here rather than
by re-sectioning the shipped 2.1.0 notes. `CHANGELOG.md` carries the same correction.

---

## 2.2.0 upgrade guidance

1. **If you parse ESRI WKT, screen your documents for a `DATUM["D_...", ...]` with no `TOWGS84` before
   you upgrade.** Those now throw. Item 1 has the magnitudes of what was being omitted — 124 m to
   463 m at the probe points measured. Add an explicit `TOWGS84`, or set `EsriDatumPolicy.ALLOW` if you
   would rather keep the old answers than change them today.
2. **If you read `selectedOperation()` or `accuracy()` and cared that they described the arithmetic
   that ran, they now do — and where they previously did not, your coordinates move.** Item 2. The
   OSGB36 case is 3.085 mm; the mechanism is not bounded by that figure, and the same code path carries
   a 1.784 m case where a grid is present.
3. **If you read the first entry of a candidate list and assumed best accuracy first, stop.** Items 3
   and 4. Ranking now follows PROJ's order, in which a larger extent can outrank a smaller accuracy
   number, and where an authority preference applies, operations from a non-preferred authority are no
   longer in the list at all.
4. **If you relied on getting every candidate back regardless of geography, set
   `SourceTargetCRSExtentUse.NONE`.** Item 5. Otherwise an area of interest is synthesised from the two
   CRS extents and candidates that do not meet it are dropped.
5. **If you compare, cache or hash the parameter list of a compound CRS**, 72 of the dictionary's
   definitions now carry a height they used to drop. Item 6. No horizontal coordinate moves.
6. **If you have `+czech` in a stored `krovak` or `mod_krovak` definition, both your axes change
   sign.** Item 7. If you do not use `+czech`, nothing moves.
7. **If you have a stored `lcc` definition that spells out `+lat_2=0` or `+lat_0=0`, read it again.**
   Item 8. An explicit `+lat_2=0` now builds the secant cone PROJ builds, and an explicit `+lat_0=0`
   now leaves the latitude of origin at the equator. No definition in the shipped dictionaries carries
   either, so this falls on definitions written by hand.
8. **If you write `+units=` or `+vunits=` on a pipeline step, the scale it resolves to may move by up
   to 3 ulps.** Item 9. Only the five U.S. survey ids are affected, and `+xy_in`, `+xy_out`, `+z_in`
   and `+z_out` are unchanged.
9. **Do not add 370 to a conformance number you remember.** The denominator moved by 9 in the same
   release. The pair is 7,819 / 7,911.
10. **Do not read a clean API-compatibility report as a quiet release.** Items 2 through 5 change
    behaviour without changing a single signature.

---

# neoProj4J 2.1.0 release notes

Released 2026-08-14 to Maven Central under the same groupId and artifactId as 2.0.0 — see
the README for the coordinates. Figures are measured unless labelled otherwise; where a number is an
estimate or is still pending it says so.

**2.0.0 was the engine change. 2.1.0 is the pass that followed it.** Twenty-two merged pull requests —
#2 through #23 — plus three changes folded in at the end. Nothing here re-architects anything: it is
the output of the first static-analysis run ever made over this tree and of a line-by-line review that
followed it, and the two of them together found defects on paths that no test had ever reached.

Almost every behaviour change below is one of two shapes, and it is worth knowing them before you read
the list:

- **A parameter that was read and then thrown away.** Parsed, dispatched into a method that ignored it,
  and the caller told nothing. `+south`, `+zone` on the wrong operator, `+gamma` on anything that does
  not read it, `+lat_0` on `rpoly`, and the seven `sconics` members' `+lat_1` / `+lat_2`.
- **A parameter whose absence could not be told apart from an explicit zero.** Upstream tests for
  *presence* — `pj_param`'s `t` sigil — and this library tested the value. Zero is a legal latitude, a
  legal `+lat_ts` and a legal `+n`, so testing the value silently substitutes a default for a value the
  caller actually gave, or answers from a default the caller never supplied.

If you only read one section, read [Compatibility](#210-compatibility-what-moves-and-by-how-much). If you
only read one item, read item 1.

**Where the numbers come from.** Everything below was measured locally, most of it with an A/B against
an otherwise identical tree. The CI workflow files are committed; **nothing here should be read as a
green CI run**.

---

## 2.1.0 compatibility: what moves, and by how much

Every row is **measured**. The magnitude is the point.

The first item is the one most likely to break an existing deployment, and unlike the rest it can break
it on input you have been feeding the library for years. The items after it change coordinates.

### 1. A `+units=` value PROJ does not know now throws, where it used to become metres in silence

This is the entry to read if you have proj strings stored in a database, a config file or a
spreadsheet.

`+units=` accepts the 21 linear ids in PROJ's `pj_units` table and nothing else. Anything else now
raises `InvalidValueException: Unknown unit: <value>`, naming the value. The lookup is case-sensitive,
because upstream's is: `us-ft` is accepted, `US-FT` is refused.

**The spellings that stop working are the plausible ones**, which is what makes this worth a section of
its own:

| what you may have written | what it is |
|---|---|
| `feet`, `metre`, `metres`, `inches`, `kilometres` | the plural or the full name where PROJ wants the id — `ft`, `m`, `in`, `km` |
| `deg`, `degree`, `degrees` | angular; `+units=` is linear only |
| `ftUS`, `usft`, `ft-us`, `survey-ft`, `US-FT` | near-misses for `us-ft` |

41 spellings PROJ refuses were accepted in all: 23 single tokens and 18 containing a space.

**What the old behaviour actually did**, measured on `+proj=utm +zone=18 +datum=WGS84 +units=<U>` at
(−75, 40) against `cs2cs` 9.8.1, where the correct output is `500000.0000 4427757.2187`:

| `+units=` | proj4j 2.0.0 | PROJ 9.8.1 | proj4j 2.1.0 |
|---|---|---|---|
| `ftUS` and the other 40 | `500000.0000 4427757.2187` — the metre value, about **1.14 million units** from what the caller asked for | `Error 1027 … utm: Invalid value for units` | `InvalidValueException: Unknown unit: ftUS` |
| `deg` / `degree` / `degrees` | `0.0000 39.7752` — a pair that reads like a lon/lat and is **wrong in both components** | rejected | `InvalidValueException` |
| `us-ft` | correct | correct | correct, unchanged |

**No registry row, no golden row and no conformance row moves.** Every `+units=` value across the
shipped dictionaries and both corpora is already an id — `m` (6,478), `us-ft` (762), `ft` (143),
`link` (1), nothing else, across 7,384 occurrences. **The whole cost falls on definitions written by
hand**, which is precisely where the bad spellings are.

**What to do.** On `+proj=longlat` the token was a no-op and can be deleted. Anywhere else, replace it
with the id — `m`, `ft`, `us-ft` — and then check the number that comes out, because if the old
definition said `feet` the old output was in metres and your stored coordinates are wrong by the
conversion factor. `Units.linearUnitIds()` is new and returns the accepted set, so you can screen a
definition store before you upgrade. `Units.findUnits`, `Units.isKnownUnit` and `Units.units` are
unchanged, so `Units.isKnownUnit("deg")` is still `true` — the refusal is built at parse level, not in
the lookup.

### 2. **48,880.69 m** of easting: `+lat_ts=0` is now told apart from no `+lat_ts` at all

On `merc`, an explicit `+lat_ts` also discards `+k`, so the two cannot be confused about scale. Testing
the value cannot express "zero is a real latitude of true scale", so a definition that said
`+lat_ts=0` was treated as though it had said nothing.

Measured against `proj` 9.8.1 at 110° E, 0° on the three shipped definitions that pair `+lat_ts=0` with
a `+k`: `esri:1816 <2934>`, `esri:2668 <21100>` and `esri:3015 <25700>`, which are one definition under
three keys.

**`Projection.equals` and `hashCode` now include whether `+lat_ts` was given.** `+proj=merc +lat_ts=0`
and bare `+proj=merc` compare unequal where they used to compare equal. That costs a cache miss, never
a wrong answer. A stream serialised by an older build still deserialises — `serialVersionUID` is
unchanged and the absent boolean reads as `false` — but it will not equal the same definition parsed
fresh here, and it keeps the old `scaleFactor`. `Projection.clearTrueScaleLatitude()` is new, because
passing zero to the setter can no longer mean "never given".

### 3. **10,000 km**: `+south=f` put your coordinates in the southern hemisphere

`+south` was read for its presence. `+proj=utm +zone=33 +south=f` — which PROJ reads as *northern* —
was projected south. Failing open, in the most literal sense.

It now goes through the same rule as `pj_param`'s `b` sigil. Two deliberate divergences: `+south=` with
an empty value is `false`, matching what 9.8.1 does in practice, and **`+south=0` throws** rather than
guessing, because the sigil reads the first character only and so reads `+south=tomato` as southern.
All 722 occurrences of `+south` in the shipped registries are bare, so nothing shipped moves.

### 4. **434 km**: `tmerc` and `etmerc` read a `+zone` upstream does not give them

Upstream reads `+zone` in `PJ_PROJECTION(utm)` alone. proj4j read it for all three, because one class
is bound to two names. Either direction — a `+zone` silently applied, or the central meridian you
expected being overridden — is 434 km.

`+zone=0` and `+zone=61` were also accepted, computing a central meridian of ∓183°. Anything outside
1..60 is refused at parse time now. `setUTMZone` survives as a public setter; it is simply no longer
reachable from a proj string.

### 5. Six operators stopped answering from parameters nobody supplied

Two returned wrong numbers, four now refuse.

| definition | was | is |
|---|---|---|
| bare `+proj=leac` | ran the conterminous-US parallels 45.5° / 29.5° where upstream runs 0 — **1,724 km** at (12, 56) on WGS84 | upstream's parallels |
| `+proj=wag1 +n=0.5` | honoured an `+n` PROJ never reads for that operator — **525,401.03 m** in a straight line at (10, 55) on GRS80 | `+n` ignored, as upstream ignores it |
| bare `+proj=urmfps` | answered — as `wag1` | refused |
| bare `+proj=geos` | answered, from the nominal geostationary height of 35,785,831 m the caller never named | refused |
| `+proj=gn_sinu +n=1` with no `+m` | answered | refused |
| `+proj=imw_p` with exactly one parallel | answered | refused |

Every refusal is tested from both sides, because refusing an explicit zero would trade upstream's
defect for one of our own.

### 6. **2,336 km**: the seven `sconics` members never read `+lat_1` or `+lat_2`

`SimpleConicProjection` used hard-coded 30° and 60° behind a `FIXME`, and six of the seven were
registered and live — so they returned plausible coordinates for standard parallels nobody chose. The
figure is `tissot`, whose sixteen corpus rows all failed on it; 112 assertions in `builtins.gie` were
failing on this one defect.

An absent `+lat_2` is now refused rather than read as zero: **1,191 km** on `+proj=murd2 +a=6400000` at
(10, 20), where 9.8.1 exits with "Missing parameter: lat_2 should be specified". An explicit
`+lat_2=0` stays legal, because upstream accepts it.

### 7. **37,098 km**: with no `+lon_0`, every forward projection behaved as though `+over` were set

The forward longitude wrap was guarded on `+lon_0` being non-zero, so omitting `+lon_0` — the common
case — skipped the wrap entirely. Worst case measured is `vandg` at longitude 180.1. **Nothing inside
±180 moves**, including ±180.0 exactly.

Van der Grinten's *inverse* had a related hole, now closed with upstream's `r > PISQ` branch: a point
outside the map circle came back as a plausible in-range coordinate rather than the reflected one, up
to **32.678°** of latitude at (10, 10).

### 8. **A full turn of longitude, 360.000000000°**: `calcofi` lost its `+over`

`+proj=calcofi +R=6400000` → `+proj=longlat +R=6400000` at (−200, 100) returned
**152.4550931861857**. PROJ 9.8.1 returns **−207.544906814**. Silently, and `calcofi` is the one
shipped projection that sets `+over` from its own `initialize()` — precisely the case the flag exists
for.

Two defects, and the second is why the first looked fixed. The prime meridian was applied one stage
after `adjlon` rather than inside the inverse funnel with it, so this library computed
`adjlon(lam + lam0) + from_greenwich` where `inv.cpp:113-117` computes
`adjlon((lam + from_greenwich) + lam0)`; those differ whenever the sum crosses the antimeridian. And a
composed transform runs the *target's* forward next, which wrapped the turn straight back.

The fix is not to stop `longlat` wrapping — that would undo an improvement in 2.0.0 that is pinned by a
test. **PROJ emits no step at all for a plain Greenwich geographic CRS**, so there is nothing there to
wrap; that was verified with `projinfo` and `cs2cs`, not reasoned about. The forward direction is
bit-identical.

Two deliberate approximations, both unobservable in anything shipped:

- **`+lon_0` is tested by value, not by presence.** A hand-written `+proj=longlat +lon_0=0` earns a
  step in PROJ and does not here. Zero shipped geographic definitions carry `+lon_0`.
- **An explicit `+over` on the *source* is not thrown away**, where PROJ discards it as an artefact of
  `+over` not surviving its round trip through a CRS description. Reproducing that would mean
  discarding a turn in the one case where the user spelled out that they wanted it kept.

Six golden rows move, by one or two ULP in the recovered longitude and nothing else — the cause is
association, not magnitude.

### 9. **6,054 mm over 1,000 round trips**: the inverse grid shift gave up at a grid boundary

When interpolation cannot produce a value, the iterate has stepped outside the grid the *input* was
found in. That is not the same event as the iteration having failed, and PROJ says so in a comment and
then acts on it at `grids.cpp:3451-3476` by fetching the grid the iterate landed in and continuing.
proj4j had only the `break`, so it fell through to the first-approximation escape hatch and returned
the unconverged iterate — off by roughly one whole grid shift, with every round trip restarting from
the previous one's approximation.

Measured with `+nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat`, where `conus` ends at 50° N and the
iterate crosses into `alaska`:

| point | PROJ 9.8.1 | proj4j before | proj4j after |
|---|---|---|---|
| −130.516041667, 50.0002461111 | 0.000000 mm at 1, 100 and 1,000 round trips | 12.94 mm at 1, **6,054.00 mm at 1,000** | 0.000001 mm |
| a neighbouring point | 0.000000 mm | 12.98 mm at 1, **648.89 mm at 100** | 0.000000 mm |

The escape hatch is intact where there is genuinely nowhere to move to, and a test pins that. **If you
have stored coordinates computed through a multi-grid `+nadgrids` list near a grid boundary, they can
be metres out**, and recomputing them is a data migration rather than a library upgrade.

### 10. **181,695.126 m → 0.263 m**: ESRI-flavoured WKT is now read the way PROJ reads it

PROJ sets a flag when a document says something only ESRI's exporter says, and under that flag the two
`Hotine_Oblique_Mercator_*` method names take the skew angle from the azimuth and discard any skew
parameter present. There are **two** triggers, not one — a `DATUM` named `D_something` and a `GEOGCS`
named `GCS_something` — and both comparisons are case-sensitive, as upstream's are. proj4j honoured
neither.

Measured on the reporting user's own document: 181,695.126 m → 0.263 m forward, and
181,984.105939 m → 0.263168 m reverse. The residual 0.26 m is a separate NAD83-to-WGS84 gap, not this
defect. One case is pinned as a divergence rather than fixed: a GDAL parameter name inside an ESRI
method name, a spelling no exporter writes.

### 11. Eighteen inverse sines stopped inventing a latitude

Each now routes through this project's port of upstream's `aasin`. `NaN` raises `NUMERICAL_FAILURE`, an
argument just past 1 clamps while it stays inside upstream's own tolerance, and anything further out
raises `COORDINATE_OUT_OF_DOMAIN`. Swiss oblique Mercator returned `NaN` for **both** coordinates over
a band containing 15,753,267 measured input points; those points now clamp or raise.

**One divergence is kept and recorded.** At latitudes of ±88° on the turning locus this library refuses
where 9.8.1 answers, because Java's quotient overshoots 1 by 6.4e-14 — past upstream's tolerance — and
closing it would mean inventing a tolerance PROJ does not have.

### 12. `+ellps=NWL9D` and `+ellps=andrae` were computed on a near-flat disc

`Registry` re-declared both with the inverse flattening in the pole-radius slot, so the constructor
took 298.25 literally as a pole radius in metres and derived an eccentricity of 0.999999998906693,
against GRS80's 0.0818. **Every transform through either name was wrong**, not slightly. Both now
reference the already-correct `Ellipsoid` constants.

In the same area: `+ellps=australian` failed lookup although the constant has always existed, and
`Ellipsoid.AUSTRALIAN` was the only one of 50 declarations passing both a pole radius and a reciprocal
flattening as non-zero — the discarded pole radius was rounded, 19.2 mm out. Normalising it to 0.0
leaves every derived value bit-identical.

### 13. Errors, causes and refusals that changed shape

| change | what it means for you |
|---|---|
| **`+proj=geos` behind the globe reports `COORDINATE_OUT_OF_DOMAIN`, not `NUMERICAL_FAILURE`** | a cause reclassification, not a new throw — the call already failed closed. Update any `ErrorCause` match. The message now names longitude, latitude and orbit height |
| **`+proj=leac +south` is honoured instead of refused** | an over-refusal: `aea.cpp` reads `+south` for that entry point. `+south` stays refused on `aea` and `longlat` |
| **`+gamma`, `+no_uoff` and `+no_off` are dispatched only to operators that read them** | one off-corpus change, toward PROJ: `+proj=merc +gamma=nonsense` was rejected and is now accepted and ignored, because PROJ ignores unread parameters silently |
| **`+pm` on an invertible projection no longer reports "pipeline is not invertible"** | see [Conformance](#210-conformance) — this was the only genuine regression against 1.4.3 in the whole corpus |
| **40 exceptions that said nothing now say something** | `new ProjectionException()` with no message (13), `"I"` (13), `"F"` (8), `"I_ERROR"`, `"F_ERROR"`, and three bare legacy `pj_errno` codes. Each now names the projection, the quantity that left its range, the limit and the offending value. All keep their one-argument constructor, so no `cause()` changes |
| **Two messages named a parameter the caller could not have written** | `outsideGrid()` said `+grids=` where `+proj=deformation` writes `+xy_grids=`; `VGridShiftOperator` did the same for `+geoidgrids=` |
| **Seven angle parsing and formatting defects** | a trailing seconds `s` read as South, so `12d34m57s` lost its seconds; 12.99999 printing as `12d60'00"`; the sign lost below one degree; `123d` parsing in one parser and throwing in the other; minutes checked `> 59` against seconds' `>= 60`; a "decimal" pattern that formatted 12.5 as `12.1800`; and a parse position set on already-truncated text. **The sign rule follows `dmstor`**: a trailing cardinal assigns the sign and a leading minus is discarded, which corrects two long-standing wrong readings — `-1d30E` was −1.5 against upstream's +1.5, `-12d34S` was +12.5667 against −12.5667. Of 323 distinct DMS values in the registries, 77 open with a minus and 56 close with a cardinal, and none does both |

### 14. Deprecations, and one allocation

Nothing here moves a coordinate.

- `ContradictoryParameterException`, `Projection.geocentric`, `Ellipsoid.INTL` and
  `LambertAzimuthalEqualAreaProjection`'s boolean-south constructor are **deprecated, not removed** —
  these are exported packages and removal is a binary break. The constructor never had any effect: its
  body was commented out, and the two parameters it would have set belong to a different projection.
  Use `+lat_0=-90`.
- The three base-class `+gamma` / `+no_uoff` / `+no_off` setters are deprecated for the same reason.
- **Prefer `Units.linearUnitIds()` to the `Units.LINEAR_UNITS` array.** `LINEAR_UNITS` is a fork-only
  public field absent from upstream 1.4.3, and a consumer that read it from a static initialiser got
  `NoSuchFieldError` out of `<clinit>` — an `Error`, so it escaped every `catch (Exception)` on its
  path.
- A lookup at the end of `proj4/nad/epsg` allocated **7.9 MB**; the forward and reverse lookups now
  share one scan.

---

## 2.1.0 conformance

| | |
|---|---|
| **gie corpus** | **7,449 / 7,902 genuine passes — 94.27 %** |
| **GIGS** | **1,170 / 1,170 — 100 %**, unchanged |
| corpus size | **7,923 assertions**, unchanged — no assertion appeared or disappeared |
| the rest | 451 failing · 2 skipped |

For reference, master before the three changes folded in at the end measured **7,448 / 7,902 —
94.25 %**, against 2.0.0's 7,441 / 7,900 — 94.19 %.

**The denominator moved, and the reason is the interesting part.** Vacuous `expect failure` rows —
where PROJ built the operation and rejected the coordinate while proj4j failed to build it at all, so
both "failed" and a naive harness scores a pass — are excluded from numerator and denominator alike,
because "both engines failed" is evidence about neither. Two of those rows became **genuine** passes
this release, which adds to both sides at once: 7,900 → 7,902. `lcc`'s two secant-cone guards and
`omerc`'s two two-point guards are now ported verbatim, so proj4j refuses those definitions for the
reason PROJ refuses them rather than because it could not build them.

**The brief for that work said three assertions; the honest figure is two.** `eqdc`'s guard is
declined, with the reasoning written into the Javadoc: the tempting argument that an exactly-zero
numerator implies a rejection is wrong, and wrong in the direction that manufactures false passes.

**Eight pinned-failure rows were deleted from `gie-expected-failures.tsv`**, which makes the gate
stricter, not looser — a key absent from that file is expected to pass.

### The one genuine regression against 1.4.3, and it is fixed

Across all 7,923 assertions there was exactly one place where 1.4.3 was right and 2.0.0 was wrong:
`gie/builtins.gie:137:1`, a Krovak definition with `+pm=ferro`, which reported
`PipelineDefinitionException: pipeline is not invertible` and scored a round-trip deviation of Infinity
mm against an expected 11 mm.

**`+pm` was never the cause.** It decides *which engine runs*, and the two engines asked different
questions: one interrogated the class hierarchy for a declared `projectInverse`, the other read the
hand-maintained `hasInverse()` declaration — which is wrong in both directions and was read nowhere in
`core/src/main` before this fork. `KrovakProjection` and `NewZealandMapGridProjection` are exactly
where the two diverge. There is now one predicate,
`Projection.hasInverseImplementation()`, public and overridable. It was `final` earlier in this
release and is not any more, because `+proj=ob_tran` has to forward the question to its run-time
child and no reflection over the wrapper class can answer it. So what stops the two from drifting
apart is not that the method cannot be overridden: it is that there is only one question, asked in
one place, and the javadoc names the single shape of override that is allowed. **This cannot move a number**: the predicate gates a throw and appears in no
arithmetic.

### Two GIGS rows stay failing on purpose

This is the one place in this release where making a test pass would be a move *away* from correct.

`gigs/5206.gie.failing:454` and `gigs/5207.2.gie.failing:386` ask for 5.6e-8 m over 1,000 round trips
and get about **5.6 m**. That 5.6 m is **PROJ's own answer**: on the same grid bytes 9.8.1 gives
5,599.885471 mm to our 5,599.885472 mm, and both engines produce the identical coordinate.

Nothing fails to converge. Within about 3e-7 degrees of `conus`'s northern edge the forward shift
carries the point across that edge, so the inverse's grid lookup — which can only work from the
coordinate it is handed, and that coordinate is already shifted — legitimately answers an NTv2 subgrid.
Inverting NTv2 is not inverting NADCON, and 5.6 m is what those two national realisations disagree by
along that parallel. **It is bounded, not divergent**: the same figure at 1 round trip and at 1,000, in
both engines. Neither engine reports anything, because on its own terms nothing failed. The fix belongs
upstream.

---

## 2.1.0 gates

*Figures are from a gate run on the released tree; where one is not yet measured it says so. The last
measured point before the three in-flight changes is master immediately before them, in the pinned
container (Temurin 21.0.11 / aarch64).*

| gate | state | figure |
|---|---|---|
| **ci** | **green** | **2,667 tests / 0 failures**. Master measured 2,640 tests / 0 failures, against 2.0.0's 2,320 |
| **conformance** | **green** | **7,449 / 7,902** against a committed 7,923-key index. Master measured 7,448 / 7,902 |
| **golden** | **live, RED on purpose** | on this branch, **2,287 UNEXPLAINED** of 53,430 rows, **49 of 49** rules pinned. Master measured 12,002 UNCHANGED · 41,428 CHANGED · 0 ADDED · 0 REMOVED · 39,141 INTENDED · 2,287 UNEXPLAINED, and 48 of 48 rules — those six figures and that rule count are master's readings, not this branch's |
| **determinism** | **green** | 22 tests, 0 failures; master measured the same 22 and 0. The count is a floor and upward drift is a notice, not a failure |
| **bench**, and the **allocation** figures inside it | **green, then turned off — see below** | **0 breaches**; **245 gated, 0 EXCLUDED**, 245 individual benchmarks, and **245 of 245** carry an allocation measurement. Measured 2026-08-14 on this branch in the pinned container (Temurin 21.0.11 / aarch64) from `./docker/run.sh bench`, in **21m16s**. One advisory, and it is an improvement rather than a breach: `TransformCacheBenchmark.createTransformUncached` at **96.000 B/op** against a saved figure of 112 |

**Why the performance gate no longer runs in CI at all** (2026-08-14, and meant to be temporary).
`.github/workflows/bench.yaml` used to run on every push and every pull request. It now has one
trigger, `workflow_dispatch`: no push, no pull request, and no schedule either. The reason is cost,
not correctness — the job is green, as the row above records, and it takes about 21 minutes of
measurement per commit, which is longer than anyone was prepared to wait. Nothing was softened to
achieve that: there is no `continue-on-error`, no saved number was loosened, and the job still fails
on any breach.

State the loss plainly, because it is not small. **Nothing starts this job automatically.** An
increase in the memory the library allocates per operation, and a change in how many `sin`, `cos`
or `log` calls a transform makes, will now go unnoticed until a person asks for a run — either
`gh workflow run bench.yaml`, or `./docker/run.sh bench` locally, which `HOWTORELEASE.txt`'s
release checklist calls for and which is the only remaining cover before a tag. Restore the
triggers to get it back; do not soften the job instead.

**Red on golden is still the intended state**, for the reason 2.0.0 gave: the gate fails on any changed
row that no rule claims with a named mechanism and a pinned count, so those are rows somebody must
*explain*, not rows somebody must *undo*. It runs weekly and on demand.

**The workflow files are committed but no CI run backs any figure here.** Everything above was measured
locally.

### What is now checked that was not

- **The golden gate's own six headline figures are asserted rather than printed.** `UNCHANGED`,
  `CHANGED`, `ADDED`, `REMOVED`, `INTENDED` and `UNEXPLAINED` were computed, formatted, printed and
  discarded; they were pinned as prose in five files and checked by eye, so 41,425 `CHANGED` could have
  become 44,000 with nothing automated noticing. A mismatch now reports `FIGURES_MOVED` with expected,
  actual and delta side by side.
- **Round-trip is asserted for the first time**, over 7,992 registry assertions. Golden pins values and
  never asserts the identity, so an inverse wrong since 1.4.3 sits in that baseline reading `UNCHANGED`
  forever.
- **A coverage build can be run at all.** Both halves were broken and each failed while looking like
  something else's fault: JaCoCo's synthetic field failed an all-fields-final assertion, and two
  modules declared a literal `<argLine>` with no `@{argLine}`, so their coverage read zero with nothing
  failing to say so — which is why the largest exercise of `core` in the repository contributed to no
  figure anyone had looked at. Measured afterwards: core 86.2 % instruction / 73.7 % branch, against
  84.5 % / 71.4 % at the branch point with the same plumbing and none of the new tests.
- **`db`'s index reproducibility proof never ran.** The documented command bound to a phase that runs
  before the plugin, so it reported `BUILD SUCCESS` in 0.541 s having regenerated nothing — vacuous in
  the direction that reports "reproducible". `VerifyIndex` also compared eleven fields fewer than it
  appeared to: 486,491 comparisons, now 502,422.
- **Three verification floors had stopped being floors** and are raised against measurement:
  `CI_MIN_TESTS` 1,700 → 2,500 in #12 and 2,500 → 2,600 on the released tree, the golden module
  floor 40 → 55, and the bench arm floor 20 → 200. The `CI_MIN_TESTS` figure is chosen, not rounded.
  The floor exists so that the gate still fails when the `db` module's 75 tests drop out of the
  reactor: the released tree measures 2,667 tests and 2,592 without `db`, so 2,600 still catches it.
  The margin that matters is 8 — what is left before a db-less run reaches the floor and the floor
  stops catching a missing module — not the 67 between the floor and the total. The remedy at that
  point is 2,650, which is what `docker/run.sh` and `docker/README.md` carry.
- **205 tests across 14 new files** over paths nobody had looked at, plus the revival of the
  repository's only skipped test. Writing them is what found six of the defects listed above.
- **The first static-analysis pass ever run over this tree** is recorded in
  `reference/code-review-2026-08.md`, with each instrument, the exact command that produced its output,
  and the one instrument that could not be run.

---

## 2.1.0 documentation corrections

Two, both of which concealed something.

- **41 citations pointed at four documents that have never existed in this repository.** A dangling
  citation was hiding a wrong figure at least once: the pure-Java rewrite of `StrictMath.sin/cos/tan`
  was attributed to JDK 17 and landed in JDK 21, so the claim was inverted rather than imprecise, and
  no reader could have caught it by following the link. Most citations were repointed at something
  real; five were deleted as unverifiable.
- **`golden.yaml` said the golden report "is gated".** It was not, and the claim had been copied into
  two other files. All four sites now say what the four genuinely automatic checks catch and what they
  do not — and the missing check was then written.

### The 2.0.0 "pending, in flight" placeholder is withdrawn

2.0.0 listed the relaxation of `ClasspathResourceResolver.isSafeName` as in flight. **It was stale on
the day it was written**: `ResourceNames` already permits interior path segments at tag `v2.0.0`, the
rule shipped with the fork commit, and no commit since touches the file. The entry is dropped rather
than carried forward. **No conformance figure is quoted for it, then or now** — the roughly 100
assertions it was said to unlock have not been measured on a quiesced tree, and the corpus files they
need are a separate question from the guard.

---

## 2.1.0 upgrade guidance

1. **Search your stored proj strings for `+units=` before you upgrade.** Anything that is not one of
   the 21 ids now throws. `Units.linearUnitIds()` gives you the list to screen against. If a definition
   said `feet` or `metres`, the coordinates you computed from it are in metres and may be wrong.
2. **If you write `+lat_ts=0`, `+south=<value>`, `+zone=` on `tmerc`/`etmerc`, bare `+proj=leac`,
   `+proj=wag1 +n=`, any of the seven `sconics` members, or a forward projection without `+lon_0`** —
   your coordinates move, in some cases by hundreds of kilometres. Items 2 through 7 above give the
   figures.
3. **If you use `+ellps=NWL9D` or `+ellps=andrae`, everything you computed through them is wrong.**
   Recompute it.
4. **If you have stored coordinates from a multi-grid `+nadgrids` list near a grid boundary**, check
   them against item 9.
5. **If you cache `Projection` objects by equality**, `+lat_ts` presence now participates in `equals`
   and `hashCode`. You will see cache misses, never wrong answers.
6. **If you match on `ErrorCause`**, `+proj=geos` behind the globe is now `COORDINATE_OUT_OF_DOMAIN`.
7. **If you parse or format DMS strings with a leading minus and a trailing cardinal**, read item 13 —
   two readings that were wrong are now right, and the change is in the sign.
8. **If you read `Units.LINEAR_UNITS`**, move to `Units.linearUnitIds()`.

---

# neoProj4J 2.0.0 release notes

Released 2026-08-06 to Maven Central as `io.github.emilevictor.neoproj4j:neoproj4j:2.0.0`, forked from
LocationTech Proj4J 1.4.3. Figures are measured unless labelled otherwise; where a number is an
estimate or is still pending it says so, because on this project laundering an estimate into a fact
has cost real rework.

**2.0.0 is the engine flip.** The corrected numerical core, the corrected defaults and fail-closed
error semantics are the behaviour of the existing API, not an opt-in alongside it. The new
`org.locationtech.proj4j.api` facade is additive and costs you nothing, but the numbers underneath the
old API have moved. Upgrading means reading this document.

An earlier plan staged this work as an additive 1.5.0 followed by a behaviour-changing 2.0.0. That
split was abandoned and everything shipped in 2.0.0; there is no 1.5.0 and there will not be one. The
groupId changed too, so this is not a version bump of `org.locationtech.proj4j:proj4j` — nothing
upgrades into it by accident, which is the protection the two-release split was there to provide.

If you only read one section, read [Compatibility](#compatibility-what-moves-and-by-how-much).

**Where the numbers come from.** Every figure below was measured locally, most of them on a frozen
snapshot with an A/B against an otherwise identical tree. The CI workflow files are committed, but
**nothing here should be read as a green CI run** — see
[The gates](#the-gates-what-is-enforced-and-what-is-red).

---

## Compatibility: what moves, and by how much

Every row is **measured**. The magnitude is the point — "improved accuracy" is not a release note, it
is a way of not telling you that your output changed by four metres.

The first six items **change the answer for existing callers**, so they lead. Everything after them is
a correction that moves values without changing the shape of the API.

### 1. An out-of-grid `+nadgrids` point is now refused instead of echoed back

`Grid.shift`'s no-table `else` branch returned the **input coordinate unchanged while reporting
success**. It now raises `CrsTransformException` with `ErrorCause.COORDINATE_OUTSIDE_GRID` — the cause
its own comment already named.

| measure | value |
|---|---:|
| golden-master rows that change | **1,995** — `REG` 1,673 · `CSV` 148 · `PAIR` 144 · `SYN` 30 |
| of those, reported `OK` in 1.4.3 | **1,949** |
| the remaining 46 | 34 `IllegalStateException`, 8 `UnsupportedParameterException`, 2 `InvalidValueException`, 2 `NumberFormatException` |
| of the 1,995, **bit-identical to 1.4.3** beforehand | **202** |

**Read the 202 twice.** For those rows the fail-open was so faithful that a behaviour-diffing gate saw
no change at all — 1.4.3 and the fixed tree produced the same bytes, because the defect was to return
the input and the input does not change. A defect that has always been present produces nothing to
diff. That is the sharpest argument on record that a change-detecting gate cannot be the only net.

The change is not merely "an exception where a number used to be": the number was **wrong**. The 40
witness rows that motivated the fix all probe latitude exactly `40.000000` — the southern edge of
`ntv1_can.dat`, the Canadian NAD27 grid `neoproj4j-epsg` ships. The forward finds the point inside the box
and shifts it *south* of 40°N, out of the box; the `else` branch then returned it unchanged, so the
inverse leg never ran. `epsg:26721` probe 2 moved `fx` by −92.756 m and `ix` by +0.001090° ≈ 93.1 m —
the residue *equals* the forward shift, which is what proves the inverse was not running rather than
failing to converge.

**These points do not round-trip after the fix, and they must not.** PROJ 9.8.1 does not round-trip
them either — verified with `cct`, not inferred: `-I +proj=hgridshift +grids=ntv1_can.dat` at
(−49.928932188, 40.0) lands at 39.9999534467, and the forward on *that* point answers
`TRANSFORMATION ERROR (Coordinate to transform falls outside grid)`.

#### The layer nuance, and where proj4j is now stricter than PROJ

**Quote the layer with the claim.** Verified with `PROJ_DEBUG=2`:

- At the **operator** level (`+proj=hgridshift`) PROJ 9.8.1 **errors** — that is the measurement above.
- At the **CRS** level *with `proj.db`*, `cs2cs +datum=NAD27` at an out-of-area point selects
  **"Ballpark geographic offset"**: a **declared** no-op, not a silent one.

proj4j's legacy path has no such operation factory and **is** the operator path, so erroring is
faithful to the layer proj4j occupies. Both statements are true at once.

The consequence is measurable. Cross-tabbed over **all 4,280** rows of `proj4-epsg.csv`, proj4j against
`cs2cs` 9.8.1 on the dictionary strings on both sides:

| | PROJ answers | PROJ refuses (`* * inf`) |
|---|---:|---:|
| **proj4j answers** | 3,869 | 0 |
| **proj4j throws** | **131** | 280 |

**Not one row where proj4j returns a coordinate and PROJ refuses.** The residual risk is the mirror
image: **131 rows where proj4j throws `COORDINATE_OUTSIDE_GRID` and PROJ 9.8.1 answers.** All 131 carry
`+datum=NAD27`, and all 131 are PROJ selecting the declared ballpark described above. That zero is a
measurement, not a blind instrument: short-circuiting the new `throw` in a scratch copy flips exactly
131 rows back to answering.

The **`@`-optional** wart is untouched, with a negative control proving the scope: 17 `datum=potsdam`
rows did **not** move, because `BETA2007.gsb` is absent, the grid list resolves *empty*, and an empty
list is correctly a no-op. *"The grid file is not there"* stays silent; only *"outside a grid that
loaded"* errors.

**What to do about it** is to make sure the grid your data needs is reachable: `neoproj4j-epsg` ships
`conus` and `ntv1_can.dat`, and `neoproj4j-grids-us-legacy` adds `alaska`. A point outside every grid on
your classpath is now refused rather than silently echoed back, and that is the intended answer.

### 2. `ErrorCause` for an unreadable or missing grid changed — update any code matching on it

If you match on `ErrorCause`, this is a source-compatible but **behaviour-visible** break.

| | was | is |
|---|---|---|
| a `+grids=` / `+xy_grids=` / `+file=` value proj4j cannot find, read or parse | `ErrorCause.INVALID_PARAM_VALUE` (`Group.CRS`) | **`ErrorCause.MISSING_GRID`** (`Group.OPERATION`) |
| a malformed or unresolvable `+init=<file>:<section>` | folded into the same `INVALID_PARAM_VALUE` | **new `PipelineErrorCode.INVALID_INIT_KEY`**, errno **1027**, still surfacing `ErrorCause.INVALID_PARAM_VALUE` |

The reasoning is that the two are statements about different things. `PipelineErrorCode
.FILE_NOT_FOUND_OR_INVALID` (errno 1029) carries `rejectedByProj = false`, because **proj4j failing to
read a file is a statement about proj4j's readers, not about your definition** — the `+grids=` value
you wrote may be perfectly valid and simply unreadable here. Reporting that as an invalid parameter
turned a capability gap into apparent conformance. An unresolvable `+init=` key, by contrast, really is
a bad *parameter value*, and upstream agrees: `get_init_string` (`9.8.1:src/init.cpp:105,119,134`) sets
`PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE` — **1027, not 1029** — for all three of its failure paths.

`INVALID_INIT_KEY` is kept distinct from `ILLEGAL_ARG_VALUE` so a caller can tell an unresolvable init
key from a bad `+order` or unit id without parsing the message, even though both report the same errno
and the same `ErrorCause`.

### 3. `tmerc` defaults to Poder/Engsager

| change | movement | escape hatch |
|---|---|---|
| **`tmerc` default algorithm** → Poder/Engsager | **0.83 mm at 6° from the central meridian; 4 m at 20°; kilometres beyond 45°** | `+approx`, or `+algo=evenden_snyder`. Spheres keep Evenden/Snyder automatically — no flag needed. |

**This is the widest-reaching numerical change in the release.** It claims **14,038** golden-master rows
on its own — nearly every projected CRS in the corpus moves by a fraction of a millimetre.

It is small in the zone a Transverse Mercator is *supposed* to be used in and unbounded outside it,
which means the users who see metres are the ones already outside the intended domain, and who are
therefore least likely to be watching. `+approx` reproduces 1.4.3.

Two independent corroborations that this is the algorithm change and not something hiding behind it:

- The rule was pinned **predictively** rather than by listing rows. Bucketing every `tmerc` probe by
  distance from *its own* `lon_0` gives a median movement of **2.53e-4 m at 5–10°**, against **37 m at
  0–5°** and **120 m at 60–65°**. The latter two are impossible for series truncation — they are the
  NADCON shift — and the band `1e-6 .. 1e-2 m` separates the two populations completely. Per-column
  maxima across the whole set: `fx` 3.5 mm, `ix` 1.2e-6°.
- `MetaCRSTest`'s reference file (`proj4-epsg.csv`) says in its own header that it was *"auto-generated
  from proj.4 epsg database"* — **its series is what 9.8.1 now spells `+approx`.** Canonical row
  `4326→2000`: the file's `9413505.328467` is `cs2cs 9.8.1 +approx` **exactly**, and the new
  `9523653.022922916` is `cs2cs 9.8.1` **exactly**.

### 4. The auxiliary-latitude core is PROJ 9.4–9.6's, and it moves `laea` / `aea` / `cea`

`9.8.1:src/latitudes.cpp` plus the rewritten `mlfn.cpp` / `phi2.cpp` / `tsfn.cpp` are now wired into
`tmerc poly laea cea aea etmerc stere`.

**Measured movement: 19,336 golden-master rows, every one of them below 1 µm** (the rule's magnitude
band is `0 .. 1e-6` in raw column units, and the measured distribution is bimodal with an empty valley
between 1e-5 and 1e-3, so the band separates this change from everything else cleanly).

The *error being removed* is larger than the movement, because the old helpers were wrong in a way that
partially cancelled downstream. Measured against 9.8.1 on GRS80:

| helper | 1.4.3 | now | gie tolerance class |
|---|---|---|---|
| `authlat` (authalic latitude) | **1.58 mm at latitude 20.8°** | 0.7 nm | 0.1 mm |
| `mlfn` | 4,920 nm at latitude 72.6° | < 1 nm | 50 nm |
| `phi2` | 4,145 nm at latitude 2.8° | 2.1 nm | 50 nm |
| `tsfn` | `0.9999999999999999` at φ=0 | exactly `1.0` | **0 m** |

`authlat` is the one that matters for this item: 16× its own tolerance class, and it is what moves
`laea`, `aea`, `cea`, `eqearth` and `nzmg`. There is no escape hatch, because the old value was simply
wrong.

> **This rule is still marked provisional in the golden gate.** It is pinned at 19,336 so that a change
> to its size becomes an event rather than a silence, but ~19,000 rows of sub-micron drift is the
> easiest place in the suite for an unrelated change to hide, and the numerical-core owner is expected
> to replace it with a per-section band.

### 5. A 2D datum shift no longer invents a height

If you call the two-argument `ProjCoordinate` constructor — i.e. you have no Z — a datum shift used to
hand you back a fabricated one.

`EPSG:4326 → EPSG:27700` at (−2.0, 53.0):

| input z | before | after |
|---|---|---|
| `NaN` (2-arg constructor), single point | **`−49.84606796130538`** | **`NaN`** |
| `NaN`, bulk `transform3D` | **`−49.84606796130538`** | **`NaN`** |
| explicit `0.0` | `−49.84606796130538` | **unchanged** |
| explicit `100.0` | `50.15598100144416` | **unchanged** |

**x and y are bit-identical in every case.** If you pass an explicit height — including an explicit
`0.0` — nothing about your output changes. Only the "I never supplied a Z" case changes, and it changes
from a plausible-looking ellipsoidal height to `NaN`, which is what "you did not give me one" means.

If you were reading `.z` off a 2D transform and treating it as data, it was never data.

### 6. Axis order — read this even if nothing else applies to you

**`AxisOrderPolicy.LEGACY` is the default. It means longitude-first, which is exactly 1.4.3.** Nothing
changes unless you opt in.

**Opting in to authority order is a silent breaking change, and it is invisible near (0, 0).** `EPSG:4326`
is officially latitude-first. If you switch to `AxisOrderPolicy.AUTHORITY`, every call site that passes
`(lon, lat)` starts passing `(lat, lon)`, and:

- near the origin — the Gulf of Guinea — the two are numerically similar and the output looks plausible;
- at San Francisco, `(-122.4, 37.8)` under `AUTHORITY` is an invalid latitude and **throws**, which is
  the good case;
- at, say, `(45, 30)` both orders are valid coordinates and you get a **confidently wrong answer**
  hundreds of kilometres away, with no error.

So: **do not flip this flag globally and run your tests near the equator.** Verified in both
directions — `EPSG:4326 → EPSG:3857` at `(-122.4, 37.8)` under `LEGACY` and `(37.8, -122.4)` under
`AUTHORITY` agree **bit-for-bit**, and feeding `AUTHORITY` the legacy order at San Francisco throws
`INVALID_COORDINATE` rather than guessing.

---

### Other projection, ellipsoid and datum changes

| change | movement | escape hatch |
|---|---|---|
| **`Ellipsoid.SPHERE` corrected** | **0.41 m at 222 km** | pass an explicit `+R=` if you depended on the old value |
| **`Ellipsoid.AIRY` corrected** | **0.76 mm** | none |
| **`carthage` datum** — was bound to the wrong ellipsoid | **20.45 mm N** at EPSG:22391 Tunis | none |
| **`OSGB36` datum** — now `9.8.1:src/datums.cpp`'s values rather than EPSG:1314's rounded ones | **3.085 mm E**, ~3.5 mm max across GB | pass an explicit `+towgs84=` |
| **`potsdam` datum** now declares its grid | changes results where `BETA2007.gsb` is present | omit the grid to keep the Helmert-only path |
| **`MercatorProjection` now reads `+lat_ts`** | **1.3 million metres** on `EPSG:3388` | none — Mercator variant B did not previously exist |
| **`AzimuthalProjection` defaults `lat_0`/`lon_0` to 0/0**, matching PROJ, not 45°/45° | every azimuthal proj-string omitting them was silently oblique | state `+lat_0`/`+lon_0` explicitly |
| **`CoordinateReferenceSystem.createGeographic()` no longer drops `+pm`** | **187,739 m** of easting, across all 94 `+pm=` definitions | none |
| **`ObliqueMercatorProjection`**: `+alpha` without `+gamma` got zero rotation; `u_0` used `cos(Gamma)` where upstream uses `cos(alpha)` | 215,218 m E / 303,073 m N, and 2,532 m E on RSO Borneo | none |

Notes on two of these, because the size is misleading in both directions:

- **`Ellipsoid.SPHERE` was the GRS80 *authalic* radius**, not a normal sphere. It is now PROJ's Normal
  Sphere. If you were using `Ellipsoid.SPHERE` as a stand-in for "roughly the Earth" the change is
  immaterial; if you were using it to reproduce a specific pipeline, it is 0.41 m.
- **`Ellipsoid.AIRY` had a rounded `b`** where the definition gives an exact inverse flattening. 0.76 mm
  is below most tolerances and above a few.

### Errors where there used to be values

**This is the second most likely thing to break your build, and it is deliberate.** A failure expressed
as a plausible coordinate is worse than an exception, because nothing downstream can tell it apart from
an answer.

| change | what it means for you |
|---|---|
| **`aasin`/`aacos` throw** instead of clamping silently | an ill-conditioned intermediate now surfaces instead of producing a finite wrong answer |
| **Domain guard** on input coordinates | matches PROJ: rejects `\|λ\| > 10` **radians** (≈ ±573°) and *wraps* everything inside that; latitude within `1e-12` rad of a pole is *clamped*, not rejected. **A `[-180, 180]` rejection would be stricter than PROJ and is not what this does** — passing 200° still works. |
| **Forward-only projections stopped returning the input as if it were lon/lat.** They throw. | **13 projections × 5 probes = 65 golden rows** today: `airy august boggs denoy larr lask nicol rpoly tcc wag7 adams_hemi adams_ws1 guyou`. A separate rule covers 40 more rows in projections newly added this release. |

**The measurement that made the case, kept because it is what proves this was a fix rather than a new
restriction.** When the rule was first written it claimed **90 rows, of which 75 had been reported `OK`
in 1.4.3.** Those 75 were silently wrong answers and are now errors. The other 15 had been
`InvalidValueException: Unknown projection` — never wrong, merely absent.

The row set is now **65, not 90, and the shrinkage is itself the good news**: five names left the list —
`lagrng`, `aitoff`, `hammer`, `nsper`, `wintri` — every one of them because **upstream turned out to
have an inverse that 1.4.3 lacked** (`aitoff.cpp:200-201`, which is the shared setup for both `aitoff`
and `wintri`; `hammer.cpp:86-87`; `nsper.cpp:167-168`; `lagrng.cpp:101-102`). They are now invertible
here too.

> **The gate does *not* key on `hasInverse()`**, and that detail matters if you were relying on it.
> `hasInverse()` is a hand-maintained declaration that was **read nowhere in `core/src/main` in
> 1.4.3**, and it is wrong in both directions: `KrovakProjection` and `NewZealandMapGridProjection`
> implement `projectInverse` without declaring it, while `LandsatProjection` declares it while
> overriding nothing. A `hasInverse()`-keyed gate rejected **EPSG:2065, EPSG:5514 and EPSG:27200 —
> three working CRS**. The shipped gate interrogates the class hierarchy for a declared
> `projectInverse(double, double, ProjCoordinate)` instead, and those three still work.

If your code has `try { inverse } catch { }` or treats a finite result as success, that code was
relying on the old behaviour. Find out which before you upgrade: a call that used to return a
plausible-looking finite number now throws.

### Grid handling

| change | movement |
|---|---|
| **NTv1 reader**: data began at offset **192, not 176**, *and* the latitude/longitude shift components were **transposed** | **~13 m on every NTv1 shift ever computed.** 8 m E + 10 m N at Chicago |
| **NTv2 multi-subgrid**: "only 1 subfile supported" silently used subgrid 1 for the whole file, and interpolation read the captured **parent** table after descending into a child | a point in Alberta got **no shift at all** from `ntv2_0.gsb` while the transform reported success |
| **Grid-edge clamp in `nad_intr`**: `1e-11` → `1e-4` | the old value was **10⁷× too tight**; points PROJ shifts were returned unchanged |
| **Containment tolerance**: `1e-4` → `1e-5` (`REL_TOLERANCE_HGRIDSHIFT`) | proj4j accepted and **extrapolated** 2e-5° outside `conus`'s south edge where PROJ reports a transformation error |
| **Antimeridian extents** now handled | `us_noaa_alaska.tif` declares `west = -194°`, so its whole western half was unreachable |
| **Inverse grid shift** declared success when only *one* ordinate had converged (`&&` where PROJ tests the squared 2-norm), and on exhaustion returned the input unchanged | now throws `ConvergenceFailureException` (`ErrorCause.NUMERICAL_FAILURE`) |
| **Inverse grid shift gave up at the first grid**: when the iterate stepped *off* the grid the input point was found in, proj4j returned PROJ's "presumably at grid edge" first approximation even when another listed grid covered the iterate. PROJ re-runs its grid lookup for the iterate and continues in the grid it lands in (`grids.cpp:3451-3476`), which proj4j did not do | **6.05 m after 1,000 NAD27 round trips** near 50°N 130.5°W, growing from 13 mm after one, because each trip restarts from the last trip's approximation. `conus` ends at 50°N and the iterate crosses into `alaska`; PROJ 9.8.1's trace says `Switching from grid conus to grid alaska` and closes to 0.000000 mm. Now 0.000001 mm |
| **NAD27 → NAD83 in CONUS** | **95.573 m at San Francisco.** Two independent causes, both fixed: a parser bug that destroyed the grid list on a static singleton, and the absence of the `conus` grid. The second half was a packaging question, and it is settled — `neoproj4j-epsg` ships PROJ 9.8.1's `conus` verbatim at `proj4/nad/conus`, which is one of the built-in classpath grid prefixes. |

Neither NTv1 error alone, nor the pair together, moved a result far enough to look like a bug. That is
why it survived. **If you have stored coordinates computed through an NTv1 grid, they are wrong by
about 13 m**, and recomputing them is a data migration, not a library upgrade.

**A 16 m round-trip error next to a grid boundary is PROJ's own, and we keep it.** Within about
3e-7° of `conus`'s northern edge — the worst case we have is `-112, 49.9999997` — a NAD27 → NAD83 →
NAD27 round trip does not come back where it started. It is out by **5.364 m** after one trip and
settles at **16.092 m** from the third trip on. Nothing fails to converge; both directions converge,
in *different grids*. The forward shift carries the point across `conus`'s northern edge, and the
inverse's grid lookup — which can only work from the coordinate it is handed, already shifted —
lands in a Canadian grid instead. Inverting NTv1 is not inverting NADCON, and the gap is what those
two national realisations disagree by along that parallel.

Given the same grids, PROJ 9.8.1 does the same thing and reaches the same numbers. Measured with
`proj_roundtrip`'s own phasing, distances on GRS80:

| round trips | proj4j | PROJ 9.8.1, same grids |
|---|---|---|
| n = 1 | 5364.3054 mm | 5364.3055 mm |
| n ≥ 3 (fixed point) | 16092.0488 mm | 16092.0489 mm |

That is 0.000393 mm apart after one trip and 0.001037 mm apart at the fixed point. Four things
follow, and three of them correct what an earlier draft of this note claimed:

- **Which grid answers depends on which grids you have, and the answer here is `ntv1_can.dat`.**
  `neoproj4j-epsg` ships `conus` and `ntv1_can.dat`; `alaska` comes from the optional
  `grids-us-legacy` module and plays no part at all — the numbers above are identical with and
  without it on the classpath. Nothing here ships `ntv2_0.gsb`. Give PROJ its full data set, which
  does include `ntv2_0.gsb`, and its inverse lands there instead and gives a different answer:
  5474.4661 mm at n = 1, settling at 10948.7935 mm, which is 5.167 m from ours. That is a difference
  in grid coverage, not in arithmetic. Give PROJ only `conus` and `alaska` and its inverse refuses
  outright — *"Coordinate to transform falls outside grid"*.
- **It does accumulate, for the first three trips.** 5364 → 10728 → 16092 mm, then flat. Bounded,
  but not flat from n = 1.
- **Neither engine reports it.** PROJ's `errno` stays 0 and it emits no trace message; proj4j raises
  nothing either, because on its own terms nothing failed.
- **It is not new.** Reverting only `Grid.java` to its pre-2.1.0 state and re-measuring gives
  bit-identical figures, so the grid-switch fix in the table above neither caused this nor made it
  worse.

Two rows record it — `gigs/5206.gie.failing:454` and `gigs/5207.2.gie.failing:386`, both asking for
5.6e-8 m over 1,000 round trips — and they stay failing. PROJ 9.8.1's own `gie` cannot build the
operation those rows use: `+proj=pipeline +step +init=epsg:4267 +inv +step +init=epsg:4269` is
rejected with *"Bad step definition: init=epsg:4267"*, so upstream never scores these two rows at
all. Ours does build it, so ours scores them, and fails them. Agreeing with PROJ is this project's
standard for correct, and on the grids we ship we do agree — to a micron — so a local change that
made these two rows pass would be a move *away* from correct. The fix belongs in the grid lookup
upstream, and is written up for there.

**One deliberate divergence from PROJ is kept**, and it is worth distinguishing from the fail-open in §1
above, because the distinction is exactly why one was fixed and the other was not. `Grid.shift` falls
through to the *next* grid when interpolation fails, where PROJ commits to the first containing grid and
reports outside-grid. The fall-through can only ever return a value that **some grid the caller listed**
actually produced; the `else` branch **invented** one. Reaching the fall-through requires `resX > 9·resY`,
so it is pinned by a purpose-built 10°×0.1° CTABLE V2 fixture plus a plain grid, each alone as its own
control.

---

## Conformance: 93.5 % of the gie corpus, and what the denominator excludes

*Every figure in this section is **2.0.0's**, read off tag `v2.0.0`. For what the corpus scores **now**,
read [2.1.0 conformance](#210-conformance) instead — these are different releases and the numbers are
not interchangeable.*

*Restored 2026-08-14, for the same reason the 2.0.0 gate table below it was.* The 2.1.0 documentation
pass overwrote this section's figures in place with 2.1.0's readings — `7,378 / 7,895 — 93.5 %` →
`7,449 / 7,902 — 94.27 %` in both the heading and the table, `at least 29 of the 42` → `30 of the 42`,
`515 failing` → `451`, `28 vacuous` → `21`, and the derivation `7,923 − 28 = 7,895` → `7,923 − 21 =
7,902`, together with a line dating the measurement 2026-08-14, eleven days after 2.0.0 shipped.
Nothing there was false; **2.0.0's own conformance measurement simply stopped existing in this
document** and survived only at the tag, while the identical 2.1.0 figures already sat a few hundred
lines above. Do not refresh these again; the current release's own section is the place for a new
reading.

**One thing the 2.1.0 pass changed here is a correction and stays**, and it is the two paragraphs on the
1.4.3 baseline at the end of this section. Withdrawing a figure that nothing in the tree can reproduce
is not the same act as overwriting a measurement with a later one, and the replacement upper bound is a
property of tag `v1.4.3` and the corpus rather than of either release, so it reads the same in both.
That is why this section's heading no longer opens `15.6 % → 93.5 %` as the tag's does: the first of
those two numbers is withdrawn.

| | |
|---|---|
| **gie corpus** | **7,378 / 7,895 genuine passes — 93.5 %** |
| **GIGS** | **1,170 / 1,170 — 100 %**, all 20 files |
| complete files | **at least 29 of the 42** active corpus files are at 100 % |
| the rest | 515 failing · 2 skipped · 28 vacuous · 94 excluded (out of block) |

**The denominator is not the corpus size, and this is the honest part of the number.** The corpus holds
**7,923 assertions** across 42 active files — 6,962 `expect` plus 961 `roundtrip` — counted with a port
of gie's own lexer rather than with `grep`, because `grep '^expect'` models neither block boundaries nor
left-trimming. From that:

- **28 rows are *vacuous* `expect failure` rows and are excluded from both numerator and denominator.**
  A vacuous row is one where proj4j could not construct the operation *at all*: PROJ built it and
  rejected the coordinate, proj4j failed to build it, both "failed", and a naive harness scores a pass.
  **That is failure-to-implement counting as conformance**, and it is excluded rather than banked.
  7,923 − 28 = **7,895**, and 7,378 + 515 + 2 + 28 = 7,923 exactly. **The vacuous count is not a
  constant**: it falls as operators land, which is why 2.1.0 reports 21 and a denominator of 7,902.
  Comparing this release's ratio with a later one means comparing two different denominators.
- **2 skips are reported separately and are never passes.**
- **94 out-of-block lines** in `DHDN_ETRS89.gie` (which closes `</gie-strict>` at line 161 of 375) are
  reported as `94 excluded (out of block)`, not as "not run".

Under-counting is visible in the report; over-counting would not be, which is why the rule resolves
ambiguity to vacuous.

**There is no reproducible 1.4.3 baseline, and the one this section used to quote is withdrawn.**
Earlier drafts headlined a `1,066 / 6,845 — 15.6 %` baseline for a 1.4.3-era harness. Nothing in the
tree reproduces those two numbers — no harness, script or recorded run that produced them survives, and
6,845 matches no count in the corpus — so they are removed rather than reworded. A comparison that
cannot be re-derived is not evidence.

**What can be derived is an upper bound on what 1.4.3 could possibly have scored, and it is worth
having.** 1.4.3 could only build an operation whose `+proj=` name appears in the `register(…)` list of
`Registry.java` — 93 names at tag `v1.4.3` — and it had no `+proj=pipeline` at all. Matching that list
against the `operation` governing each of the 7,923 in-block assertions leaves **at most 1,830, or
23 %**, that 1.4.3 could have constructed, counting every ambiguous case in its favour. The other 6,093
name something it did not have: 1,279 are pipelines, and the largest blocks after those are `adams_ws2`
(724), `guyou` (705), `adams_hemi` (703), `adams_ws1` (703) and `peirce_q` (592). Constructing an
operation is not the same as agreeing with PROJ to tolerance, so the true 1.4.3 figure is below 1,830 —
by how much, this tree cannot say.

### Zero disagreements with PROJ 9.8.1 on the MetaCRS corpus

The strongest single piece of correctness evidence the project has produced. `MetaCRSTest`'s reference
file, `proj4-epsg.csv`, reports ~1,195 "regressions" against 1.4.3. Triaged row by row against
`cs2cs` 9.8.1 on the dictionary strings, with the target parameter strings verified byte-identical
between the two trees so that every one is a pure algorithm change and not a parse change:

| rows | cluster | vs `cs2cs` 9.8.1 |
|---:|---|---|
| 775 | `tmerc` | **agrees** |
| 280 | `tmerc` | **both refuse the point** (`cs2cs` prints `* * inf`) |
| 28 | `cass` | agrees |
| 24 | `tmerc` + `+datum=` | agrees |
| 22 | `tmerc`/`cass` + `NAD27`/`potsdam` | PROJ refuses, proj4j answers *(pre-existing; superseded by the fail-closed work in §1)* |
| 3 | `eqc` | agrees |
| **0** | — | **proj4j disagrees numerically** |

**Across all 4,280 rows of the corpus there is not one where proj4j and PROJ 9.8.1 both produce a
coordinate and the coordinates differ.** The reference file is stale, not the library — and regenerating
it is legitimate here precisely because `cs2cs` 9.8.1 is an independent oracle that agrees bit-for-bit.
Re-pinning it from proj4j's own output would make it circular and self-confirming forever, and is not
what will be done.

## The gates: what is enforced, and what is red

Five regimes now measure this work. Four of them — `ci`, `conformance`, `bench` and `determinism` —
gate every push and pull request and are green. The fifth, `golden`, is **red on purpose**, and since
2026-08-05 it is no longer a push or pull-request check. Reading a red gate as a defect would be exactly
backwards: they were all green once for the same reason a scan that cannot fail always passes.

*Every figure in the table below is **2.0.0's**, read off tag `v2.0.0` in the pinned container
(Temurin 21.0.11 / aarch64). For what the gates measure **now**, read the
[2.1.0 gates](#210-gates) table instead — these two tables are different releases and the numbers are
not interchangeable.*

*Restored 2026-08-14, and worth saying why.* During the 2.1.0 documentation pass four of these rows
were **overwritten in place with 2.1.0's readings** — `ci` 2,320 → 2,667, `conformance` 7,441/7,900 →
7,449/7,902, `golden`'s six headline figures and its rule count 42 → 49, and `bench`'s saved-count
total 171 → 170. A preamble was added disclosing the re-measurement, so nothing here was ever a lie.
It was worse than a lie in one specific way: **2.0.0's own measurements stopped existing in this
document** and survived only at the tag, while the same 2.1.0 numbers already sat in the 2.1.0 table
a few hundred lines above. A release-notes section that no longer reports what its release measured
has stopped being release notes. The figures below are restored from `git show v2.0.0:RELEASE-NOTES.md`.
Do not refresh them again; add a row to the current release's table instead.

| gate | state | figure |
|---|---|---|
| **ci** | **green** | whole 7-module reactor, `BUILD SUCCESS` with javadoc, **2,320 tests / 0 failures / 4 skipped** in 223 report files (`core` 1,917 · `conformance` 345 · `db` 52 · `geoapi` 6) |
| **conformance** | **live, CI-wired, green** | baseline pair committed — `gie-expected-failures.tsv` (545 rows) and `gie-corpus-index.tsv` (**7,923 keys**). **7,441 / 7,900**, verdict `regressed 0, unexpected passes 0, new 0, disappeared 0` against the full index |
| **golden** | **live, blocking, RED** | **12,012 UNCHANGED · 41,418 CHANGED · 0 ADDED · 0 REMOVED · 39,127 INTENDED · 2,291 UNEXPLAINED** over 53,430 rows; **42 of 42** rules pinned with `expected_rows` (was 38, then 41) |
| **allocation** | **live, green** | **0 breaches**; **245 gated, 0 EXCLUDED**, 245 / 245 individual benchmarks, Tier 2 green. Was `172 gated / 9 excluded / 181 arms`: `BulkTransformBenchmark` joined the gate (+64) and `crs-parse` rejoined Tier 1 (−9 exclusions) |
| **determinism** | **runs per leg, green** | **22** tests, 0 failures, 0 skips (was 15; the count is a floor, and the workflow now reports upward drift as a notice rather than failing) |
| **bench** | baseline captured 2026-08-02 | **171 saved per-benchmark byte counts, all enforced, none reported-only** — one per benchmark, each a number a run may match or come in under but never exceed; 25 rules; 8 CRS pairs × 20 operations pinned; 2 remaining `TBD`s are `targetBytesPerOp` policy cells on rules whose saved number is a real figure |

*The `bench` workflow was **turned off on 2026-08-14**, after 2.0.0 and after 2.1.0. That is not a
2.0.0 fact and it is not recorded here; it is under [2.1.0 gates](#210-gates), where it happened.*

**Why golden being red is the intended state.** The gate exits non-zero on any `UNEXPLAINED` row: a row
whose behaviour changed and for which no rule in `golden/rules.yaml` claims responsibility with a stated
mechanism and a pinned row count. 2,287 rows are in that state, down from **18,168 → 3,304 → 2,291 → 2,287**
as rules have landed (measured 2026-08-14 with `./docker/run.sh golden`). Every one of them is a change somebody must *explain*, not a change somebody must
*undo*, and the largest blocks are known: the reserved `proj4-epsg.csv` re-pin, `tmerc` rows probed
thousands of kilometres outside their own zones where both series are meaningless, and a handful of
`cass` / `omerc` / `merc` clusters. Turning the gate green by relaxing it would discard the only
instrument that has caught a silent behaviour change on this project — twice.

**Why it no longer runs on push and pull request** (commit `052e627`, 2026-08-05). A check that fails on
every PR for a known backlog trains people to ignore it. The two ways to make it pass — `continue-on-error`,
or a catch-all rule in `rules.yaml` — both make it report success while seeing nothing, and the second
would absorb genuine future regressions as well. Removing the trigger makes the gate *absent* rather than
dishonest: the job still exists, still fails on the real backlog, and now runs weekly and on demand
(`gh workflow run golden.yaml`). What that costs is stated in the workflow header, and it is not small —
golden is the only check that compares this tree against genuinely released 1.4.3, so while it is off the
PR path, breaking something that used to work will not be caught there until the next weekly run. What is
watched in the meantime is the report itself: the backlog figure must not grow. Restore the triggers once
the backlog is triaged; do not soften the job instead.

**Two honesty notes about the instruments themselves**, because a gate that cannot fail is worse than no
gate:

- **`gc.alloc.rate.norm` does flake, and it was previously claimed not to.** Two independent 16-minute
  runs in separate JVMs agreed to within 0.0001 B/op on **170 of the 181 arms**. The exception was
  real: the 11 `CrsParseBenchmark` arms above 1 KB/op drifted run-to-run by up to **0.121 %**, enough
  for two of them to fail the 0.1 % gate on an *unmodified* tree. That was resolved first by marking
  the `crs-parse` rule's nine arms `tier1Gated: false` — measured and reported every run, but not
  blocking — rather than by widening `ALLOC_RELATIVE_SLACK`, which would have weakened all 181 arms to
  accommodate nine. **The exclusion was written with an exit condition and the exit condition was met:**
  `io/InitFileCache` removed the per-call dictionary re-scan that made the arm's allocation
  data-dependent, the numbers fell to 2,480 / 2,872 / 1,136 B/op, and `tier1Gated`,
  `exclusionArmCount` and `exclusionReason` were deleted together. **There are no exclusions today —
  `245 gated, 0 EXCLUDED`** — and `ALLOC_RELATIVE_SLACK` was never widened. The two arms that remain
  bimodal vary by exactly 56 bytes and **only downward** across 21 forks, and are pinned at their
  maximum, which is safe because the gate fails only on exceeding.
- **The workflow files are committed but no CI run backs any figure here.** Everything above was
  measured locally. Do not read a green badge into this document.

---

## Determinism: the guarantee, and its one exception

proj4j targets **bit-for-bit identical results across JVMs and CPU architectures**, because the
motivating consumer caches one `CoordinateTransform` and shares it across Spark executors that are not
guaranteed to be on the same hardware. Implemented by routing every transcendental through
`StrictMath` — whose results are *specified to a bit*, unlike `Math`, which is specified to 1–2 ulp and
which HotSpot substitutes per architecture.

**This is not only a reproducibility policy; it decides a conformance verdict.** At
`+proj=adams_ws2 +ellps=WGS84` and `(179.999, 0)` the map's conditioning amplifies a last-bit
difference in `sin` by ~3×10⁸:

```
Math.sin(lam/2)        x = 16686159.3838 m    misses a 1 mm bar by 27.8 mm
StrictMath.sin(lam/2)  x = 16686159.3563 m    hits it, 0.35 mm
exact (60 digits)      x = 16686159.3639 m    <- sits BETWEEN the two
```

**Neither function is more accurate.** What is being preserved is *fidelity* to the fdlibm-equivalent
`sin` that generated PROJ's expected values.

### What is now verified rather than asserted

Until this release the guarantee was a design intention. It is now a test — `StrictMathGoldenTableTest`,
a committed table of **54,265 raw-bit results across 19 functions** — run on five JDK and instruction-set
combinations:

| JDK | `os.arch` | `StrictMath.sin` implementation | result |
|---|---|---|---|
| Temurin 8.0.502 | `x86_64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 11.0.32 | `x86_64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 11.0.32 | `aarch64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 21.0.11 | `aarch64` | pure Java `FdLibm` | 54,265 pass |
| OpenJDK 26.0.2 | `aarch64` | pure Java `FdLibm` | 54,265 pass |

**271,325 `StrictMath` and 221,970 `FastStrictTrig` raw-bit comparisons, zero value mismatches**, across
two instruction sets and both `StrictMath` implementations — **JDK 21** rewrote `StrictMath.sin/cos/tan`
from JNI into pure Java, so 8, 11 and 17 exercise a genuinely different code path and a pass on 21 is not
evidence about them. The table above spans that boundary in both directions, which is what makes the
guarantee empirical rather than inferred. JDK 17 is on the *native* side, alongside 8 and 11
(`Modifier.isNative(StrictMath.sin)` is `true` on Corretto and Temurin 17.0.20, and
`java.lang.FdLibm$Sin` does not exist in a JDK 17 image).

That the assertion is not vacuous is established two ways. `util/FastStrictTrig` is an independent
~800-line transcription of the JDK's `FdLibm` kernels and it matches the same table — which cannot
happen if the table is meaningless. And `Math` demonstrably departs from it: on Temurin 21/AArch64,
`Math.sin` differs on 2.14% of probes and `Math.cos` on 1.89%.

### The exception, stated plainly

> **NaN sign and payload are architecture-dependent and are NOT covered by the bit-for-bit guarantee.
> Every finite result is. Signed zero is.**

Measured with the JDK held fixed at Temurin 11.0.32 and only the instruction set varied:

| expression | x86-64 | AArch64 |
|---|---|---|
| `Inf - Inf`, `Inf * 0.0`, `Inf / Inf`, `sqrt(-1)` | `0xfff8000000000000` | `0x7ff8000000000000` |
| `0.0 / 0.0`, `Double.NaN` | `0x7ff8000000000000` | `0x7ff8000000000000` |

x86-64's default NaN has the sign bit set; AArch64's does not, and the JLS specifies only that these
expressions yield *a* NaN. **This matters concretely**, because the fail-closed sentinel policy writes
`NaN` to every output ordinate of a failed point — so error rows are exactly the rows that cannot be
compared on raw bits across architectures. If you checksum or raw-bit-compare transform output,
normalise NaN first. `Double.isNaN` is unaffected; so is every tolerance comparison; so is the
`gie` metric, which maps NaN-on-both-sides to zero distance.

---

## Capability boundary — what proj4j does not do

Stated as a boundary rather than buried in a footnote, because the consumer for this work explicitly
values legibility over coverage, and because a release note that lists only capabilities is an
advertisement.

### Operator families that are not implemented

Each is a **refusal**, not a silent omission: proj4j reports `PROJECTION_NOT_IMPLEMENTED` rather than
producing something.

- **`s2`.** Verified absent from `Registry`, **56 failing assertions in `builtins.gie`**, **declined
  on ratio**: a discrete-global-grid system whose implementation cost is disproportionate to any
  consumer need on record. If you need it, use PROJ. *Implemented in 2.2.0, which reverses the ratio
  judgement this entry records; see CHANGELOG.md's 2.2.0 section.* Its two former companions in this
  entry, `airocean` and `isea`, were implemented first — in 2.2.0 as well, not in the "Unreleased"
  section an earlier revision of this line pointed at. The
  figures this entry used to give for them, 92 and 40, counted only the rows scored `FAIL`; the
  corpus totals including the rows that were `VACUOUS` for want of the operator are **94 and 41**,
  and those are the numbers the two ports move.
- **`+proj=helmert` as a user-facing operator.** It exists only as the hidden static
  `+exact +convention=position_vector` helper the `cs2cs` emulation builds. Deliberately not exposed,
  because the user-facing operator additionally carries `convention=coordinate_frame`, `transpose` and
  seven time-dependent rates — **all of which appear in the corpus** — and shipping a subset would
  silently ignore a token PROJ acts on. Costs **11 failing assertions**: 7 in `more_builtins.gie`, 3
  in `GDA.gie`, 1 in `4D-API_cs2cs-style.gie`. It additionally makes 2 more vacuous — one in
  `4D-API_cs2cs-style.gie` and one in
  `more_builtins.gie` — where the row is an `expect failure` that proj4j satisfies by not building
  the operation at all, so it scores as a pass upstream while demonstrating nothing here. Counted
  2026-08-14 from `conformance/src/test/resources/gie-expected-failures.tsv`, whose `reason` column
  attributes each row: `grep helmert` returns 13 rows, 11 `FAIL` and 2 `VACUOUS_EXPECTED_FAILURE`.
  The figure previously read "3 in `GDA.gie` and 1 in `4D-API_cs2cs-style.gie`", which missed the
  whole `more_builtins.gie` block. *Implemented in 2.2.0, with `molobadekas`, `convention`,
  `transpose` and the rates — which are **eight**, not the seven this entry says: `dx dy dz drx dry
  drz dtheta ds`, plus `t_epoch`.*
- **`gridshift` (the unified operator) and `defmodel`.** Both need the GeoTIFF grid reader wired into
  the pipeline layer; the reader itself exists (below). *Still absent in 2.2.0 — but that reason was
  false when written, and repeating it would send a reader looking in the wrong place. The grid layer
  is not the obstacle: `gridshift` needs an iterative inverse that switches grids mid-loop plus
  biquadratic NADCON5 interpolation, and `defmodel` needs a JSON deformation-model reader.
  `PipelineFactory`'s javadoc carries the real reasons. Deferred to 2.3.0.*
- **`+proj=deformation +grids=`** — the single-file three-channel Geodetic TIFF Grid form. The two-grid
  form (`+xy_grids=` / `+z_grids=`) works. *`+grids` landed in 2.2.0, on the same generic grid layer
  `xyzgridshift` runs on.*
- **`nkg`** — 33 assertions. Unblocked in *data* terms but not in operators: the transformations are
  concatenations whose "method name" is itself a PROJ pipeline (`PROJ:PROJString`), needing
  `deformation` plus a transformed time dimension. *Wired in 2.2.0; all 33 assertions read, and the
  three grids are vendored rather than fetched.*
- **The time dimension is not transformed.** No `+proj=unitconvert +t_in`, no `+proj=set +t`. Note the
  distinction: `+t_epoch` / `+t_final` on `hgridshift` and `vgridshift` **are** honoured, so a
  time-*gated* grid shift behaves as upstream's does; it is time as a transformed *ordinate* that is
  absent.

Implemented and often assumed otherwise: `longlat` (and its three aliases), `geocent`, `unitconvert`,
`axisswap`, `cart`, `vgridshift`, `hgridshift`, `deformation` (two-grid), `tinshift`, `affine`, `push`,
`pop`, `set`, and every projection in `Registry`.

### Vertical and height support is thinly evidenced, and you should know how thinly

A **GTX vertical grid reader** and a **GeoTIFF grid reader** both ship (the latter verified bit-identical
to `cct` 9.8.1 to 12 decimals across 35 vendored fixtures, both subgrid hierarchies, all seven real US
grids, and the real `us_nga_egm96_15.tif` and `us_nga_egm08_25.tif`). But the *conformance evidence* for
heights is much weaker than the headline suggests, and the honest figures are:

- **Only 356 of 7,923 corpus assertions — 4.5 % — score a third ordinate at all** (225 with three
  numbers, 131 with four). For the other 5,419 coordinate expects,
  `gie.cpp:1117` zeroes the third ordinate on both sides, so **z contributes exactly zero to the
  deviation regardless of what the operation writes.** That is upstream's masking, not proj4j's
  insulation.
- All 961 roundtrips carry z unmasked and 878 pass — but a roundtrip only asserts that z returns where
  it started, which **any pass-through satisfies trivially**.
- **For a height through the *datum* stage specifically, the corpus evidence is essentially none.**
  `BasicCoordinateTransform` is reachable from the whole corpus through exactly one route, and
  `crs_src`/`crs_dst` appear in only two files — **8 assertions, 6 of them with 3-D expects, and none
  passes** (4 fail, 2 skip).

So: treat 3-D and vertical transforms as **supported but lightly covered**, and test your own heights.
The invented-height fix in §5 above is covered by a dedicated unit test, not by the corpus.

### Database vintage and operation selection

- **The legacy path has no `proj.db`.** PROJ 9.x resolves `+datum=` and operation search through it;
  proj4j's legacy path resolves against the shipped PROJ.4-style dictionaries. Concretely:
  `+datum=OSGB36` in PROJ picks OSTN15, **1.784 m** from the legacy Helmert proj4j applies; `nzgd49` is
  **2.248 m**. This is a **data-vintage gap, not an arithmetic defect** — on the parameter strings each
  engine is actually given, the two agree.
- **A pure-Java, zero-dependency reader for a transcoded 9.8.1 database exists** (`neoproj4j-db`, Phase 1),
  but wiring operation *selection* through it is not complete, so the numbers above still describe the
  default path. When it does land it changes an answer that is currently correct-by-accident: the facade
  throws `BALLPARK_REJECTED` for `EPSG:4267 → EPSG:4269`, which is right without the database and
  **wrong with it** — the authority publishes **nine** transformations for that pair, from 0.15 m to
  2.0 m accuracy, and not one is ballpark.
- **The shipped EPSG dictionary is v9.2-era (2017), against PROJ 9.8.1's v12.029.** The dictionary
  carries no version stamp, so **`Proj.databaseVersion()` returns `Optional.empty()` rather than
  guessing a version.** The prose lives in `DatabaseInfo.vintageNote()`. This is the pattern throughout
  the new introspection API: it declines to answer rather than answer plausibly.
  - `availableGrids()` is probe-verified and `declaredGrids()` is separate, so a grid that is declared
    but unreachable is *reported* rather than omitted.
  - `axisOrder()` is paired with `isAxisOrderAuthoritative()`, so an inference is never presented as an
    authority statement.
  - `version()` is read from the JAR manifest and says `unknown (no jar manifest on this classpath)`
    from exploded classes, rather than a compiled-in constant that can lie.
  - `Crs.toWkt(WKT1_*)` **throws** rather than emitting lossy WKT1.
- **NADCON is deliberately not implemented, and will not be.** It is not a PROJ 9.8.1 format:
  `grids.cpp`'s `HorizontalShiftGridSet::open` dispatches on exactly four things — NTv1, CTABLE V2,
  NTv2 and TIFF — and the `us_noaa_nadcon5_*` grids are NADCON 5 data *in GeoTIFF*, read by the TIFF
  path. Writing a NADCON reader would be a divergence from the target revision, not parity with it.

---

## Pending, not done: one change in flight

**The following is not in this build.** It is listed here so that nobody reads its absence as a
decision, and so that no figure is quoted before it exists.

*(The `conus` grid used to be listed here too. It landed: `neoproj4j-epsg` ships PROJ 9.8.1's
`data/tests/conus` verbatim — 264,424 bytes of CTABLE V2 — at `proj4/nad/conus`, and
`neoproj4j-grids-us-legacy` carries `conus` and `alaska` for anyone who does not want the EPSG
dictionaries. See [Grid handling](#grid-handling).)*

> ### ⏳ PLACEHOLDER — relaxing `ClasspathResourceResolver.isSafeName`
>
> **Status: in flight, not landed. No conformance figure is quoted for it, and none should be until it
> is measured on a quiesced tree.**
>
> The resolver rejects any resource name containing `/`. The corpus writes
> `+file=tests/tinshift_simplified_kkj_etrs.json`, `+grids=tests/us_noaa_nadcon5_*.tif` and
> `+xy_grids=tests/nkgrf03vel_realigned_xy_extract.ct2`; PROJ resolves these by appending the whole
> token to a search directory. So proj4j can currently reach **no** `tests/…` file, and roughly **100
> assertions** sit behind that one rule — concentrated in `gridshift`, `geotiff_grids`, `defmodel` and
> `tinshift`. **No GeoTIFF reader unlocks them on its own**; the reader is already done and verified.
>
> The guard is a security boundary — CRS strings are untrusted per-row input — so relaxing it is a
> deliberate decision, not a cleanup. The proposed change permits interior path segments while
> continuing to reject a leading `/` or `\`, spaces, and any `.` / `..` / empty segment.

---

## Java baseline: staying on 8, and the multi-release JAR is **not** needed

The plan of record was **Java 11 plus a multi-release JAR with `META-INF/versions/17`**, on the
reasoning that `StrictMath` on Java 8 is a JNI call per transcendental while JDK 17 rewrote it into pure
Java, and that this is what makes the determinism guarantee affordable. **That premise is false: the
pure-Java rewrite of `StrictMath.sin/cos/tan` is JDK 21, not 17.**

**Recommendation: keep `<release>8`, drop the MR-JAR.** The pom currently targets 8; no change is
proposed. The recommendation is unchanged by the correction above, but **one of its two original reasons
is not**, so both are restated here.

**The logical one, as originally written, does not survive.** It ran: *a multi-release JAR can only
change behaviour on a newer runtime — `META-INF/versions/17` is never read by a Java 8 or 11 JVM — but
JNI `StrictMath` is slow only on 8 and 11, so the MR-JAR was aimed at the only JDKs that do not have the
problem.* **JNI `StrictMath` is slow on 8, 11 *and 17*.** `META-INF/versions/17` would therefore have
been read by exactly one runtime that *does* have the problem: the MR-JAR was aimed **one release early
at the boundary**, not at JDKs lacking the problem. Worse, it would have been actively wrong there —
switching JDK 17 onto "plain `StrictMath`, it is pure Java here" means switching it onto the JNI path.
So the logical argument now cuts the same way for a different reason: the MR-JAR would have shipped a
slow path to the one JDK it reached.

**The measured reason is the one that decides it, and it is unaffected.** `util/FastStrictTrig` — a pure-Java,
allocation-free transcription of `FdLibm.Sin/Cos/Tan`, compiled to Java 8 bytecode, and verified
bit-identical to `StrictMath` on all five combinations above — is faster than `StrictMath` on *every*
JDK, by the largest margin precisely where the MR-JAR was supposed to help:

| JDK / arch | `StrictMath` | `FastStrictTrig` | `Math` | `FastStrictTrig` vs `StrictMath` |
|---|---|---|---|---|
| 8 / x86-64 (JNI) | 29.83 ns | **7.51 ns** | 30.63 ns | **3.97×** |
| 11 / x86-64 (JNI) | 10.13 ns | **7.03 ns** | 4.53 ns | 1.44× |
| 11 / aarch64 (JNI) | 18.31 ns | **3.80 ns** | 34.62 ns | **4.82×** |
| 21 / aarch64 (pure Java) | 6.56 ns | **3.71 ns** | 2.39 ns | 1.77× |

> **Measurement caveat, because these numbers will be quoted.** Not JMH: best-of-5 after 3 warm-up
> rounds, 8 M calls per measurement, one JVM per row, single-threaded, `sin` + `cos` over ±π. The
> **within-row ratios** are the trustworthy part. **Cross-row comparisons between architectures are
> confounded** — the x86-64 rows ran under Rosetta 2 on Apple silicon, so their absolute times are not
> native x86-64 times. Nothing in the verdict depends on a cross-row comparison.

So the determinism tax is **~1.55× against `Math`** on JDK 21 with `FastStrictTrig`, not the 1.5–3×
that was priced in — and it is **allocation-free**, where `StrictMath.sin` costs ~62 B/op on JDK 21 and
later (a `double[]` carrier that escape analysis does not remove; on 8 through 17 it is native JNI and
allocates nothing, so the figure there is a structural 0). On JDK 11/AArch64 the policy is better than
free: `FastStrictTrig` is 9× faster than `Math` there, because `Math.sin` has no useful intrinsic on
that combination.

**What this buys the release:** the recorded risk *"MR-JAR × OSGi interaction is fiddly — `Multi-Release: true`
in the bnd instructions, versioned classes excluded from `Export-Package`. Budget for it; test the
bundle"* is **removed, not mitigated.** There is no second class-file version, so there is nothing for
bnd to double-export. One source root, one bytecode level, one artifact.

### One prerequisite for keeping Java 8, and it is still open

**`Math.toRadians` is not bit-stable across the Java 8 / 9 boundary.** Java 8 computes
`angdeg / 180.0 * PI`; Java 9 changed it to a multiply by a precomputed constant. Measured over the 721
whole degrees in [−360, 360]:

| comparison | Java 8 | Java 11 | Java 21 |
|---|---|---|---|
| `Math.toRadians(d)` vs `d * ProjectionMath.DTR` | **182 of 721 differ (25.2%)** | 0 | 0 |
| `d * DTR` vs PROJ's `PJ_TORAD` (`d * M_PI / 180.0`) | 186 of 721 | 186 | 186 |

proj4j uses **both** idioms — `ProjectionMath.DTR` on the projection path and `Math.toRadians` in 137
places across 42 files. On Java 11+ the two agree exactly. **On Java 8 they disagree by 1 ulp on a
quarter of whole-degree inputs.** Most of the 137 sites are exception messages and bound
initialisation, where 1 ulp is harmless, but they have not been individually audited and at least a few
(`RobinsonProjection` lines 99 and 162, inside the inverse; the geodesic package; the grid-shift
operators) are result-bearing.

**This is a Java-8-only internal inconsistency, and it is cheap to remove: never call
`Math.toRadians`/`Math.toDegrees` in `core/src/main`, use the explicit constant.** Doing so makes the
Java 8 baseline sound and is a smaller change than raising the floor. **It remains a release blocker for
the determinism claim on Java 8, and it is not yet done** — the audit of which of the 137 sites are
result-bearing is outstanding.

The last-ulp difference from PROJ's own `PJ_TORAD` in the third row is separate, pre-existing,
JDK-independent, and already documented in `gie/GieComparator`. It is not a defect: 1 ulp of a radian
is ~2 pm, eight orders inside the tightest corpus tolerance.

---

## Upgrade guidance

Coming from `org.locationtech.proj4j:proj4j:1.4.3`, change the groupId and artifactId as well as the
version — see the [README](README.md) for the coordinates. Package names are unchanged, so no imports
move: the new `org.locationtech.proj4j.api` facade is additive, has zero runtime dependencies, and the
legacy types are **not** deprecated. Verified: `EPSG:4267 → 4269` still transforms through the legacy
factory.

What to check, in order:

1. **If you use `+nadgrids` or `+datum=NAD27` anywhere near the edge of your grid coverage**, expect
   `COORDINATE_OUTSIDE_GRID` where you previously got the input coordinate back. 1,949 golden-master
   rows that reported `OK` now raise. Check that the grids your data needs are on the classpath —
   `neoproj4j-epsg` carries `conus` and `ntv1_can.dat`, `neoproj4j-grids-us-legacy` adds `alaska` — and
   decide whether refusing is the right answer where they are not.
2. **If you match on `ErrorCause`**, an unreadable or missing grid is now `MISSING_GRID`
   (`Group.OPERATION`), not `INVALID_PARAM_VALUE` (`Group.CRS`).
3. **Find every place you swallow an exception or treat a finite result as success.** The
   forward-only-inverse change alone moved 75 rows that had reported `OK`.
4. **If you use `tmerc`, `utm`, or anything derived from them beyond ~10° from the central meridian**,
   decide between the corrected default and `+approx`. Measure; do not assume the zone you are in.
5. **If you have stored coordinates computed through an NTv1 grid**, they are wrong by ~13 m.
6. **If you read `.z` off a transform you fed only x and y**, it is now `NaN`. It was never data.
7. **If you use `Ellipsoid.SPHERE`**, check whether you meant the authalic radius.
8. **Leave `AxisOrderPolicy` on `LEGACY`** unless you have a specific reason, and if you change it, test
   away from the equator.
9. **If you raw-bit-compare or checksum output**, normalise NaN first.

## Acknowledgements

The gie and GIGS conformance corpora are vendored from PROJ 9.8.1 and from the IOGP's Geospatial
Integrity of Geoscience Software project. Licences and notices: `conformance/NOTICE-gie.md`.
