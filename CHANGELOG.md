
# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-08-14

Twenty-two merged pull requests — #2 through #23 — plus three changes folded in at the end: #117,
#122 and #123. 2.0.0 was the engine change. 2.1.0 is the pass that followed it: a static-analysis
run, a line-by-line code review, and the defects both of them found. Most of the entries below are
one of two shapes — a parameter that was read and then ignored, or a parameter whose absence could
not be told apart from an explicit zero.

**Every measured behaviour change, with its magnitude, is in
[RELEASE-NOTES.md](RELEASE-NOTES.md)** — this file lists what changed, that file tells you how far
your coordinates move. Read it before upgrading.

### Breaking — read these first

These change the answer, the reported error, or whether a definition is accepted at all.

- **A `+units=` value outside PROJ's 21 linear ids now raises `InvalidValueException` naming the
  value. It used to become metres in silence.** This is the entry to read if you have proj strings in
  a database or a config file. The spellings that stop working include the plurals and full names
  `feet`, `metre`, `metres`, `inches` and `kilometres`, the angular ids `deg`, `degree` and
  `degrees`, and misspellings of a real id such as `ftUS`, `usft`, `ft-us` and `survey-ft`. The
  lookup is case-sensitive, as PROJ's is, so `us-ft` is accepted and `US-FT` is refused. Measured on
  `+proj=utm +zone=18 +datum=WGS84 +units=<U>` at (−75, 40) against `cs2cs` 9.8.1, where the right
  answer is `500000.0000 4427757.2187`: an unresolvable name returned easting `500000.0000`, about
  1.14 million units from what the caller asked for; `+units=deg`, `degree` and `degrees` returned
  `(0.0000, 39.7752)`, a pair that reads like a lon/lat and is wrong in both components. 41 spellings
  PROJ refuses were accepted in all — 23 single tokens and 18 containing a space. **No registry row,
  no golden row and no conformance row moves**, because every `+units=` value in the shipped
  dictionaries and both corpora is already an id: `m` (6,478), `us-ft` (762), `ft` (143), `link` (1),
  and nothing else across 7,384 occurrences. **The cost falls entirely on stored definitions written
  by hand.** If yours say `+units=metres` or `+units=degrees`, they will now throw where they used to
  transform. On `+proj=longlat` the token was a no-op and can simply be deleted; anywhere else,
  replace it with the id — `m`, `ft`, `us-ft` — and check the number that comes out.
  `Units.findUnits`, `Units.isKnownUnit` and `Units.units` are unchanged, so `Units.isKnownUnit("deg")`
  is still `true`; the refusal is built at parse level (#21, closes #116)
- **`+lat_ts=0` is now told apart from no `+lat_ts` at all, and on `merc` it discards `+k`.**
  Presence is what upstream tests; a value test cannot express "zero is a real latitude of true
  scale". **48,880.69 m of easting** on the three shipped definitions that pair the two — `esri:1816
  <2934>`, `esri:2668 <21100>` and `esri:3015 <25700>`, which are one definition under three keys —
  measured against `proj` 9.8.1 at longitude 110 E, latitude 0. **`Projection.equals` and `hashCode`
  now include whether `+lat_ts` was given**, so `+proj=merc +lat_ts=0` and bare `+proj=merc` compare
  unequal where they used to compare equal. That costs a cache miss, never a wrong answer. A stream
  serialised by an older build still deserialises — `serialVersionUID` is unchanged and an absent
  boolean reads as `false` — but it will not be equal to the same definition parsed fresh here, and
  its `scaleFactor` keeps the old value (#23, closes #112)
- **`+south` is read for its value, not its presence.** `+proj=utm +zone=33 +south=f` — which PROJ
  reads as northern — was projected into the southern hemisphere. **10,000 km, failing open.** It now
  goes through the same rule as `pj_param`'s `b` sigil. Two deliberate divergences: `+south=` with an
  empty value is `false`, matching what 9.8.1 does in practice, and **`+south=0` throws** rather than
  guessing, because the sigil reads the first character only and so calls `+south=tomato` southern.
  All 722 occurrences of `+south` in the shipped registries are bare (#15, closes #97)
- **`tmerc` and `etmerc` no longer read `+zone`.** Upstream reads it in `PJ_PROJECTION(utm)` alone.
  proj4j read it for all three because one class is bound to two names. **434 km** either way. And
  `+zone=0` and `+zone=61` were accepted, computing a central meridian of ∓183°; anything outside
  1..60 is now refused at parse time. `setUTMZone` survives as a public setter, just not reachable
  from a proj string (#15, closes #96)
- **Six operators stopped answering from parameters nobody supplied.** Two returned wrong numbers:
  bare `+proj=leac` ran the conterminous-US parallels 45.5°/29.5° where upstream runs 0, **1,724 km**
  at (12, 56) on WGS84; and `+proj=wag1 +n=0.5` honoured an `+n` PROJ never reads for that operator,
  **525,401.03 m** in a straight line at (10, 55) on GRS80. Four now refuse where they used to answer:
  bare `+proj=urmfps` (answered as `wag1`), bare `+proj=geos` (answered from the nominal
  geostationary height of 35,785,831 m the caller never named), `+proj=gn_sinu +n=1` with no `+m`,
  and `+proj=imw_p` with exactly one parallel. Every refusal is tested from both sides, because
  refusing an explicit zero would trade upstream's defect for one of our own (#20, closes #114, #115)
- **The seven `sconics` members read `+lat_1` and `+lat_2` for the first time.** `SimpleConicProjection`
  used hard-coded 30° and 60° behind a `FIXME`, and six of the seven were registered and live,
  returning plausible coordinates for the wrong standard parallels. **2,336 km** on `tissot`, whose
  sixteen rows all failed on it, and 112 assertions in `builtins.gie` were failing on that one defect.
  An absent `+lat_2` is now refused rather than treated as zero — **1,191 km** on
  `+proj=murd2 +a=6400000` at (10, 20), where 9.8.1 exits with "Missing parameter: lat_2 should be
  specified". An explicit `+lat_2=0` stays legal, because upstream accepts it (#16)
- **The forward longitude wrap no longer depends on `+lon_0` being non-zero.** With no `+lon_0` every
  forward projection behaved as though `+over` were set. **37,098 km** at longitude 180.1 on `vandg`,
  worst case measured. Nothing inside ±180 moves, including ±180.0 exactly (#16, closes #99)
- **`+ellps=NWL9D` and `+ellps=andrae` were computed on a near-flat disc.** `Registry` re-declared
  both with the inverse flattening in the pole-radius slot, so the constructor took 298.25 literally
  as a pole radius in metres and derived an eccentricity of 0.999999998906693 against GRS80's 0.0818.
  Every transform through either name was wrong. Both now reference the already-correct `Ellipsoid`
  constants (#6)
- **ESRI-flavoured WKT is read the way PROJ reads it, and an oblique Mercator stops losing 181 km.**
  PROJ sets a flag when a document says something only ESRI's exporter says, and under that flag the
  two `Hotine_Oblique_Mercator_*` method names take the skew angle from the azimuth and discard any
  skew parameter present. There are **two** triggers, not one — a `DATUM` named `D_something` and a
  `GEOGCS` named `GCS_something` — and both comparisons are case-sensitive, as upstream's are.
  Measured on the reporting team's own document: **181,695.126 m → 0.263 m** forward and
  **181,984.105939 m → 0.263168 m** reverse, the residual being a separate NAD83-to-WGS84 gap. One
  case is pinned as a divergence rather than fixed: a GDAL parameter name inside an ESRI method name,
  a spelling no exporter writes (#17 and #18, closes #110)
- **Eighteen inverse sines stopped inventing a latitude.** Each is now routed through
  `ProjectionMath.asinChecked`, this project's port of upstream's `aasin`: `NaN` raises
  `NUMERICAL_FAILURE`, an argument just past 1 clamps while it stays inside upstream's own tolerance,
  and anything further out raises `COORDINATE_OUT_OF_DOMAIN`. Swiss oblique Mercator returned `NaN`
  for **both** coordinates over a band containing 15,753,267 measured input points; those points now
  clamp or raise. **One divergence is kept and recorded**: at latitudes of ±88° on the turning locus
  this library refuses where PROJ 9.8.1 answers, because Java's quotient overshoots 1 by 6.4e-14 —
  past upstream's tolerance — and closing it would mean inventing a tolerance PROJ does not have
  (#19, closes #111, #113)
- **`+proj=geos` behind the globe reports `COORDINATE_OUT_OF_DOMAIN`, not `NUMERICAL_FAILURE`.**
  Nothing failed numerically; the point is outside the domain, which is what upstream reports. A cause
  reclassification, not a new throw — the call already failed closed. If you match on `ErrorCause`,
  update it. The message now names the longitude, the latitude and the orbit height (#8)
- **`+proj=leac +south` is honoured instead of refused**, since `aea.cpp` reads it for that entry
  point. This was an over-refusal: the transform raised where it should have answered. `+south` stays
  refused on `aea` and `longlat` (#8)
- **`+gamma`, `+no_uoff` and `+no_off` are dispatched only to the operators that read them.** On
  anything else the value was parsed, dispatched into an empty base-class method and discarded. One
  off-corpus behaviour change, toward PROJ: `+proj=merc +gamma=nonsense` used to be rejected and is
  now accepted and ignored, because PROJ ignores unread parameters silently. The three base methods
  are deprecated, not deleted (#8)
- **Van der Grinten's inverse gained upstream's `r > PISQ` branch.** A point outside the map circle
  came back as a plausible in-range coordinate rather than the reflected one — up to **32.678°** of
  latitude at (10, 10), measured before the fix (#16, closes #100)
- **`calcofi` keeps its `+over` turn.** `+proj=calcofi +R=6400000` → `+proj=longlat +R=6400000` at
  (−200, 100) returned **152.4550931861857** where PROJ 9.8.1 returns **−207.544906814** — **a full
  turn of longitude, 360.000000000°, silently wrong**. Two defects, and the second is why the first
  looked fixed: the prime meridian was applied one stage after `adjlon` instead of inside the inverse
  funnel with it, and then the target's own forward wrapped the turn away again. The prime meridian
  now lives in the forward and inverse funnels, associated exactly as `fwd.cpp:108` and
  `inv.cpp:113-117` associate it, and a plain Greenwich geographic target emits no step to wrap with —
  which is what PROJ does, verified with `projinfo` and `cs2cs` rather than reasoned about. The
  forward direction is bit-identical. Two deliberate approximations are recorded: `+lon_0` is tested
  by value rather than presence, so a hand-written `+proj=longlat +lon_0=0` earns a step in PROJ and
  not here; and an explicit `+over` on the source is not thrown away here as it is upstream. Six
  golden rows move, by one or two ULP in the recovered longitude and nothing else, and
  `NUM-KARNEY-LATITUDE-CORE` is re-pinned 19303 → 19309 (#117)

### Added

- **`Units.linearUnitIds()`** — an unmodifiable `Set<String>` of the 21 ids `+units=` accepts, in
  `pj_units` order. The parser reads the same set, so the accepted set and the published set cannot
  drift. Prefer it to the `Units.LINEAR_UNITS` array, which is a fork-only public field absent from
  upstream 1.4.3: a consumer that read it from a static initialiser got `NoSuchFieldError` out of
  `<clinit>`, an `Error` that escaped every `catch (Exception)` on its path (#21)
- **`Projection.clearTrueScaleLatitude()`** — passing zero to the setter can no longer mean "never
  given", so there has to be a way to say it. `proj4j-geoapi`'s `ParameterAccessor.reset` needs
  exactly that (#23)
- **`Projection.hasInverseImplementation()`**, public and overridable — upstream's
  `pj_has_inverse(P)`, asked of a class hierarchy instead of a function pointer. The reflection walk
  moves here out of `BasicCoordinateTransform`, where it was private and so unreachable from the
  pipeline engine, and `inverseAvailable` delegates to it. It was `final` earlier in this release;
  the javadoc at `Projection.java:1017-1032`, under the heading "When overriding is allowed", now
  states who may override it. An ordinary projection
  must not: the walk already knows whether the class implements `projectInverse` and that answer
  cannot go stale, while a re-declared one drifts exactly as `hasInverse()` does. A wrapper whose
  real inverse belongs to a child chosen at runtime has to, because the walk answers about the
  wrapper's own unconditional `projectInverse` when the honest answer is the child's —
  `ObliqueTransformationProjection` is the only one today, since `+proj=ob_tran +o_proj=guyou` has no
  inverse and `+o_proj=merc` does and no reflection over the wrapper can tell them apart. Such an
  override may forward the question to the child and do nothing else (#123)
- **`reference/code-review-2026-08.md`** — the first static-analysis pass ever run over this tree,
  recording each instrument, the exact command that produced its output, the nine defects worth
  acting on, the two traps a future run needs, and the one instrument that could not be run (#3)
- **205 tests across 14 new files** over paths nobody had looked at, plus the revival of the
  repository's only skipped test. Writing them is what found `#94`, `#96`, `#97`, `#98`, `#99`,
  `#100` and `#101`, all of which are fixed above (#10)
- **`RegistryRoundTripAuditTest`** — round-trip is asserted for the first time. Golden pins values
  and never asserts the identity, so an inverse wrong since 1.4.3 sits in that baseline reading
  `UNCHANGED` forever. 7,992 assertions over the registry, and the failing set is pinned by equality,
  so a new failure, a fixed projection and a doubled error are three distinct messages (#10)
- **The golden gate's six headline figures are asserted rather than printed.** `UNCHANGED`,
  `CHANGED`, `ADDED`, `REMOVED`, `INTENDED` and `UNEXPLAINED` were computed, formatted, printed and
  discarded; they were pinned as prose in five files and checked by eye, so 41,425 `CHANGED` could
  have become 44,000 and nothing automated would have noticed. They now read from
  `golden/baseline/1.4.3/golden-expect.txt`, and a mismatch reports `FIGURES_MOVED` with expected,
  actual and delta side by side. It is deliberately a pin and not "assert 0 UNEXPLAINED" (#12, #13,
  closes #104)
- **Ten new oracle rows and 42 new conformance oracle rows** covering the guards ported in #20 and
  #22, deliberately five rejects and five near-misses per operator, so an over-refusal fails as
  loudly as an under-refusal

### Fixed

Silent wrong answers and refusals are under Breaking above. What follows moves no coordinate.

- **The release itself was blocked, and this is the fix that unblocked it.** `mvn clean verify` and
  `mvn -Pcentral deploy` both failed in `maven-javadoc-plugin` on four `<h2>` tags inside field and
  method Javadoc, where doclint's implicit preceding heading is `<h3>` and only `<h4>` passes. Every
  CI job and container build passes `-Dmaven.javadoc.skip=true`; the deploy profile does not. 2.1.0
  could not have been published without this (#14)
- **40 exceptions that said nothing, across 20 files.** `new ProjectionException()` with no message
  at all (13), `"I"` (13), `"F"` (8), `"I_ERROR"`, `"F_ERROR"`, and three bare legacy `pj_errno`
  codes — `-21`, `-40`, `-43`. Each now names the projection, says which quantity left its range and
  what the limit was, prints the offending value and cites the upstream file and function. The three
  numeric ones were decoded against the last PROJ revision that still carried the table, and each
  matches its Java guard exactly. Every one keeps its one-argument constructor, so no `cause()`
  changes (#14)
- **Two runtime messages named a parameter the caller could not have written.**
  `HorizontalGrids.outsideGrid()` hardcoded `+grids=` although `+proj=deformation` writes
  `+xy_grids=` and is separately refused `+grids=` outright; `VGridShiftOperator` had the same defect
  for `+geoidgrids=` (#14)
- **`+pm` on an otherwise invertible projection reported "pipeline is not invertible".** The one
  genuine regression against 1.4.3 in the whole 7,923-assertion corpus, at `gie/builtins.gie:137:1`,
  scored as a round-trip deviation of Infinity mm against an expected 11 mm. `+pm` was never the
  cause: it decides *which engine runs*, and the two engines asked different questions —
  `BasicCoordinateTransform` interrogated the class hierarchy, `Cs2csOperator` read the
  hand-maintained `hasInverse()` declaration, which is wrong in both directions and was read nowhere
  in `core/src/main` before this fork. `KrovakProjection` and `NewZealandMapGridProjection` are
  exactly where the two diverge. There is now one predicate, so they cannot drift apart again by
  construction rather than by discipline. **This cannot move a number**: the predicate gates a throw
  and appears in no arithmetic (#123)
- **The inverse grid-shift loop now moves to the grid its iterate landed in, instead of giving up.**
  When interpolation cannot produce a value the iterate has stepped outside the grid the *input* was
  found in, which is not the same event as the iteration having failed — PROJ says so in a comment
  and then acts on it at `grids.cpp:3451-3476`. proj4j had only the `break`, so it fell to the
  first-approximation escape hatch and returned the unconverged iterate, off by roughly one whole
  grid shift, with every round trip restarting from the previous one's approximation. Measured on
  `+nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat` at (−130.516041667, 50.0002461111), where
  `conus` ends at 50° N and the iterate crosses into `alaska`: **12.94 mm at one round trip and
  6,054.00 mm at 1,000**, against 0.000000 mm from PROJ 9.8.1 at every count, now 0.000001 mm. A
  neighbouring point ran to 648.89 mm at 100 round trips and is now 0.000000 mm. The escape hatch is
  intact where there is genuinely nowhere to move to, and a test pins that (#122)
- **The golden gate had been reporting a rule-level failure since the rule set landed** —
  `COUNT_MISMATCH DATUM-TYPE-UNKNOWN-HOISTED expected_rows=251 but matched 253`. The pin was stale on
  arrival, dated to a snapshot taken four days before the file was committed, and `golden/README.md`
  had carried the measured 253 all along. Re-derived on this tree and re-pinned from the measurement
  (#4)
- **The golden expectation file PR #12 asserted against was never staged**, so the golden run errored
  out instead of checking anything. `mvn verify` was never affected — golden is not in the default
  reactor (#13)
- **A coverage build could not be run at all**, and each half failed while looking like something
  else's fault. JaCoCo weaves a non-final synthetic field into every instrumented class, which failed
  `ProjContextTest`'s all-fields-final assertion; and `conformance/pom.xml` and `golden/pom.xml` each
  declared a literal `<argLine>` with no `@{argLine}`, so `prepare-agent` was silently replaced
  rather than added to and those modules' coverage read zero with nothing failing to say so. That is
  why the largest exercise of `core` in the repository contributed to no figure anyone had looked at.
  Measured afterwards with the corpus on: core 86.2 % instruction / 73.7 % branch, against
  84.5 % / 71.4 % at the branch point with the same plumbing and none of the new tests (#10)
- **`db`'s index reproducibility proof never ran.** All three `-Pregen-db` executions bind to
  `generate-resources`, and the documented command was `mvn -Pregen-db validate` — which runs first,
  so the plugin never fired: `BUILD SUCCESS` in 0.541 s with no output, after which
  `git diff --exit-code` passes because nothing was regenerated. Vacuous in the direction that
  reports "reproducible" (#10, #12)
- **`VerifyIndex` compared eleven fields fewer than it appeared to** — 486,491 field comparisons,
  now 502,422, each added comparison carrying a comment saying what a wrong reader would have done to
  pass without it. Two fields stay uncheckable because no reader path reaches them, and the class
  Javadoc names them rather than leaving the count looking complete (#10)
- **`RectangularPolyconicProjection` dropped `+lat_0`** — it read a private field the class declared
  and never assigned, so both reads returned 0.0. Its `initialize()` body was also commented out
  under a `FIXME`, taking `P->es = 0` with it (#6)
- **`+ellps=australian` failed lookup** although `Ellipsoid.AUSTRALIAN` has always existed. It is a
  proj4j extra, not a PROJ name, and is numerically identical to `aust_SA` (#6)
- **Seven angle parsing and formatting defects** (#15, closes #96): a trailing seconds `s` read as
  South, so `12d34m57s` lost its seconds; rounding and truncation disagreeing, so 12.99999 printed as
  `12d60'00"`; the sign lost below one degree in both directions; `123d` parsing in one parser and
  throwing in the other; minutes checked with `> 59` while seconds used `>= 60`; a "decimal" pattern
  that appended unscaled arcseconds, so 12.5 formatted as `12.1800`; and a parse position set on
  already-truncated text, so a resuming caller re-read the hemisphere letter. The sign rule follows
  `dmstor`: a trailing cardinal *assigns* the sign and a leading minus is discarded, which corrects
  two long-standing wrong readings — `-1d30E` was −1.5 against upstream's +1.5, and `-12d34S` was
  +12.5667 against −12.5667. Of 323 distinct DMS values in the registries, 77 open with a minus and
  56 close with a cardinal, and none does both, so nothing shipped depends on the choice
- **A lookup at the end of `proj4/nad/epsg` cost 7.9 MB.** `Proj4FileReader`'s forward and reverse
  lookups now share one scan and allocate the parameter array only in the branch that needs it (#9)
- **`reference/code-review-2026-08.md` landed as a binary blob.** The document's own note about three
  source files containing raw NUL bytes was written with a literal NUL instead of the escape
  sequence, so the warning about NUL bytes breaking `grep` contained a NUL byte that broke `grep` on
  itself. One byte, at offset 4538 (#5)
- **Two vacuous conformance rows became genuine passes.** PROJ refuses the definition, proj4j also
  refuses it, and gie would score that a pass — but the bridge classified our refusal
  `NOT_IMPLEMENTED` rather than `INVALID_DEFINITION`, so the row demonstrated nothing. `lcc`'s two
  secant-cone guards and `omerc`'s two two-point guards are ported verbatim, along with `msfn` and
  `tsfn`. **The brief said three assertions; the honest figure is two** — `eqdc`'s is declined, with
  the reasoning written into the Javadoc: the tempting argument that an exactly-zero numerator implies
  a rejection is wrong, and wrong in the direction that manufactures false passes, because the
  denominator runs `pj_mlfn` far outside its stated convergence domain (#22, closes #124)

### Changed

- **The build prints zero javadoc warnings and zero `[ERROR]` lines**, down from about 2,250 warnings
  of which 2,117 were missing-comment notices — a figure that had to be measured with `-Xmaxwarns`,
  because javadoc caps its own output at 100 per module. doclint moves to `all,-missing`, keeping
  every check that catches a real defect. The two packages a caller actually touches,
  `org.locationtech.proj4j` and `.api`, are documented by hand and pass at full `-Xdoclint:all`
  strictness. 37 empty `<p>` tags were a real HTML defect and are deleted; the root package gained
  the `package-info.java` it never had, and 85 documentation gaps across 19 files are written (#2)
- **Three plugins were resolved from repository metadata, so their versions depended on the contents
  of the local repository** — in one reactor, `maven-resources-plugin` 3.5.0 for three modules and
  3.4.0 for two others. The project had no `pluginManagement` section at all; there is now one.
  `project.build.sourceEncoding` moves to the root POM, where it covers all seven modules rather than
  `core` alone (#2)
- **Seven duplicated rules have one place to live, and a test each** — `Registry.ellipsoids` now
  references `Ellipsoid`'s table rather than duplicating 27 objects by value; six pipeline operators
  extend one package-private base; `TransformWrapper2D`/`3D` funnel through one method; one
  parameter-matching rule replaces five copies; `db` loses 45 duplicated lines and regenerates
  byte-identically. 800 lines in, 707 out, plus 1,292 lines of tests written to fail if a
  consolidation is undone (#9)
- **Dead code is deleted and the rest says why it has to stay** — `proj/ModStereoProjection.java`,
  236 lines whose only live statement was the package declaration; two commented blocks in
  `ProjectionMath`; a stale `TODO` in `LongLatProjection`. `ContradictoryParameterException`,
  `Projection.geocentric` and `Ellipsoid.INTL` are deprecated rather than removed, because these are
  exported packages and removal is a binary break (#7)
- **Three verification floors had stopped being floors.** `CI_MIN_TESTS` 1,700 → 2,500 in #12,
  against a measured 2,573, and 2,500 → 2,600 on the released tree, against a measured 2,667. The
  figure is chosen, not rounded: the floor exists so that the gate still fails when the `db`
  module's 75 tests drop out of the reactor, and on the released tree that db-less run reads 2,592,
  which is under 2,600. **The margin that matters is 8, not 67.** 67 is the gap from the floor up
  to the total, and it measures nothing — this floor is not there to catch a handful of missing
  tests. 8 is the gap from the db-less reading up to the floor: add 8 tests anywhere outside `db`
  and a db-less run reaches 2,600, at which point the floor stops catching a missing `db` module at
  all. The remedy then is 2,650, which is the threshold and the remedy `docker/run.sh` and
  `docker/README.md` carry. The golden module floor 40 → 55 against 64 tests, and the bench arm
  floor 20 → 200 against 245 arms (#12, closes #103)
- **41 citations pointed at four documents that have never existed in this repository.** Most were
  repointed at something real, some restated as the project policy they had always been describing,
  and five were deleted outright as unverifiable. A dangling citation was concealing a wrong figure at
  least once: the pure-Java rewrite of `StrictMath.sin/cos/tan` was attributed to JDK 17 and reached
  them in JDK 21, so the claim was inverted rather than imprecise, and no reader could have caught it
  by following the link (#11)
- **`golden.yaml` said the golden report "is gated".** It is not, and the claim had already been
  copied into two other files. All four sites now say what the four genuinely automatic checks catch
  and what they do not (#11, filed as #104, fixed in #12)
- `Ellipsoid.ellipsoids` held `INTL` while `Registry.ellipsoids` held `INTERNATIONAL`, so `+ellps=intl`
  and the WKT writer's reverse lookup returned different objects for the same ellipsoid. Bit-identical
  either way (#7)
- `Ellipsoid.AUSTRALIAN` was the only one of 50 declarations passing both a pole radius and a
  reciprocal flattening as non-zero. The constructor discarded the pole radius, and it was rounded —
  19.2 mm out. Normalised to 0.0; all derived values are bit-identical (#14)
- `LambertAzimuthalEqualAreaProjection`'s boolean-south constructor never had any effect — its body
  was commented out, and the two parameters it would have set belong to a different projection. Now
  deprecated, with `+lat_0=-90` named as the replacement. No behaviour change (#6)

### Conformance

- **PROJ 9.8.1 gie corpus: 7,449 / 7,902 — 94.27 %**, from a gate run on the released tree.
  Remainder: 451 failing, 2 skipped. For reference, master at `2fc5989` — before #117, #122 and
  #123 — measured **7,448 / 7,902 — 94.25 %**, against 2.0.0's 7,441 / 7,900 — 94.19 %
- **The denominator moved, and the reason is worth stating.** It was 7,900 in 2.0.0: 7,923 in-block
  assertions less 23 vacuous rows, which are excluded from numerator and denominator alike because
  "both engines failed" is evidence about neither. #22 converted two of those vacuous rows into
  genuine passes, which adds to both sides at once — hence 7,902. The corpus itself is unchanged at
  **7,923 assertions**; no assertion appeared or disappeared
- **Two pinned-failure rows were deleted from `gie-expected-failures.tsv` by #22 and one by #123.**
  Removing a row makes the gate stricter, because a key absent from that file is expected to pass.
  #16 removed five more, all named in its own commit: `builtins.gie#343:5` and `#345` at `:5`, `:7`,
  `:9` and `:11`
- **GIGS: 1,170 / 1,170 — 100 %**, unchanged
- **Two GIGS rows stay failing on purpose**, and this is the one place where making a test pass would
  be a move away from correct. `gigs/5206.gie.failing:454` and `gigs/5207.2.gie.failing:386` ask for
  5.6e-8 m over 1,000 round trips and get about **5.6 m** — but that 5.6 m is PROJ's own answer, not
  ours: on the same grid bytes 9.8.1 gives 5,599.885471 mm to our 5,599.885472 mm, and both engines
  produce the identical coordinate. Nothing fails to converge; the forward and inverse legs
  legitimately land in different national grids, and the two disagree by that much along that
  parallel. It does not accumulate — the same figure at 1 round trip and at 1,000, in both engines.
  The fix belongs upstream (#122)

### Gate status, stated honestly

*Figures below are from a gate run on the released tree; where one is not yet measured it says so.
The last measured point before the three in-flight changes is master at `2fc5989`, in the pinned
container (Temurin 21.0.11 / aarch64).*

- **ci** — **green**, **2,667 tests / 0 failures**. Master at `2fc5989` measured 2,640 tests and
  0 failures, against 2.0.0's 2,320. Every PR in this release states its own delta and the count is
  treated as a signal in its own right — it is what caught #17 merging four of the files it needed
  as two (#18)
- **conformance** — **green**, **7,449 / 7,902**, against a committed 7,923-key index. Master at
  `2fc5989` measured 7,448 / 7,902
- **golden** — on this branch, **live and RED on 2,287 UNEXPLAINED rows** of 53,430, with
  **49 of 49** rules pinned. Master at `2fc5989` measured 12,002 UNCHANGED · 41,428 CHANGED ·
  0 ADDED · 0 REMOVED · 39,141 INTENDED · 2,287 UNEXPLAINED, 48 of 48 rules pinned — those are
  master's readings and are left as measured. **Red is the intended state**: the
  gate fails on any changed row that no rule claims with a named mechanism and a pinned count, so
  those are changes somebody must *explain*, not changes somebody must *undo*. It runs weekly and
  on demand, not on every push
- **determinism** — **green**, 22 tests and 0 failures; master at `2fc5989` measured the same 22 and
  0 failures. The count is a floor, `DET_FLOOR_TESTS=22`, and upward drift is reported as a notice
- **bench**, and the **allocation** figures inside it — **green**, **0 breaches**, **245 gated,
  0 EXCLUDED**, 245 arms, **245 of 245** arms carrying an allocation measurement. Measured 2026-08-14
  on this branch in the pinned container (Temurin 21.0.11 / aarch64) from `./docker/run.sh bench`, in
  **21m16s**. `bench` is opt-in, so that was a second command on the same branch rather than part of
  the four-gate run above; allocation is not a separate check, and its figures come from inside the
  bench run. One advisory, and it is an improvement rather than a breach:
  `TransformCacheBenchmark.createTransformUncached` at 96.000 B/op against a ratchet of 112
- **No CI run backs any figure in this file.** The workflow files are committed; everything above was
  measured locally

### Corrections to the 2.0.0 documents

- **The `[Unreleased]` entry describing a relaxation of the resource-name guard as "in flight" was
  stale on the day it was written.** `ResourceNames` already permits interior path segments at tag
  `v2.0.0` — the rule shipped with the fork commit, and no commit between `v2.0.0` and `2fc5989`
  touches the file. The entry is dropped rather than carried forward. **No conformance figure is
  quoted for it**, then or now: the roughly 100 assertions it was said to unlock have not been
  measured on a quiesced tree, and the corresponding corpus files are a separate question from the
  guard

## [2.0.0] - 2026-08-06

The first neoProj4J release: PROJ 9.8.1 parity, forked from upstream Proj4J 1.4.3. Published to Maven
Central under `io.github.emilevictor.neoproj4j` — a new groupId, so this is not a drop-in version bump
of `org.locationtech.proj4j:proj4j`.

**Every measured behaviour change, with its magnitude, is in [RELEASE-NOTES.md](RELEASE-NOTES.md)** —
this file lists what changed, that file tells you how far your coordinates move. Read it before
upgrading.

### Breaking — read these first

These change the answer, or the reported error, for existing callers.

- **An out-of-grid `+nadgrids` point is refused instead of echoed back.** `Grid.shift`'s no-table
  `else` branch returned the input coordinate unchanged while reporting success; it now raises
  `CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID)`. **1,995 golden-master rows change** —
  `REG` 1,673 · `CSV` 148 · `PAIR` 144 · `SYN` 30 — of which **1,949 had reported `OK` in 1.4.3** and
  **202 were bit-identical to 1.4.3**, so the behaviour-diffing gate could not see them at all. Layer
  nuance, verified with `PROJ_DEBUG=2`: at the *operator* level PROJ errors too, but at the *CRS* level
  with `proj.db` it selects a **declared ballpark**, so proj4j is now stricter than PROJ on **131 known
  rows** (all `+datum=NAD27`, measured by cross-tabbing all 4,280 `proj4-epsg.csv` rows against
  `cs2cs` 9.8.1). In the other direction: **zero rows** where proj4j answers and PROJ refuses
- **`ErrorCause` for an unreadable or missing grid changed** from `INVALID_PARAM_VALUE` (`Group.CRS`)
  to **`MISSING_GRID` (`Group.OPERATION`)**. Anyone matching on the old cause must update. Failing to
  read a file is a statement about proj4j's readers, not about the definition, so
  `PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID` (errno 1029) also carries `rejectedByProj = false`. A
  new **`PipelineErrorCode.INVALID_INIT_KEY`, errno 1027**, took the `+init=` population and still
  reports `ErrorCause.INVALID_PARAM_VALUE` — upstream's `get_init_string` (`9.8.1:src/init.cpp`) uses
  1027, not 1029, for all three of its failure paths
- **`tmerc` defaults to Poder/Engsager** — **0.83 mm at 6° from the central meridian, 4 m at 20°,
  kilometres beyond 45°**, claiming **14,038 golden-master rows**. `+approx` / `+algo=evenden_snyder`
  are the documented escape; spheres keep Evenden/Snyder automatically
- **The Karney auxiliary-latitude core** (`9.8.1:src/latitudes.cpp`, plus rewritten `mlfn` / `phi2` /
  `tsfn`) is wired into `tmerc poly laea cea aea etmerc stere`. **19,336 golden-master rows move, every
  one of them below 1 µm.** The error it removes is larger than the movement: `authlat` was **1.58 mm
  at latitude 20.8°** against a 0.1 mm tolerance class, which is what moved `laea`, `aea` and `cea`
- **A 2D datum shift no longer invents a height.** `EPSG:4326 → EPSG:27700` at (−2.0, 53.0) with no
  input Z returned `z = −49.84606796130538`; it now returns **`NaN`**, single-point and bulk alike.
  Explicit `0.0` and explicit `100.0` are **bit-identical to before**, and x/y are bit-identical in
  every case
- **Axis order is configurable and defaults to `AxisOrderPolicy.LEGACY`**, i.e. longitude-first, i.e.
  exactly 1.4.3. Adopting authority order is a **silent** breaking change that is **invisible near
  (0, 0)** — plausible in the Gulf of Guinea, a thrown `INVALID_COORDINATE` at San Francisco, and a
  confidently wrong answer hundreds of kilometres out at (45, 30). See RELEASE-NOTES.md
- **`Proj4jException` no longer captures a Java stack trace by default.** `fillInStackTrace()` returns
  `this` unless `-Dproj4j.exceptions.stackTraces=true` is set at startup or
  `Proj4jException.setStackTraceCaptureEnabled(true)` is called. **Nothing a caller can act on
  programmatically is lost** — the exception *type*, `cause()`'s `ErrorCause`, `getMessage()` (which
  for the grid and domain refusals names the grids, the coordinate in degrees and the failing
  predicate) and `getCause()` all survive; what is lost is the Java call site. It is **not** a shared
  preallocated instance: every throw still constructs a fresh object with its own message, so two
  threads refusing two different coordinates get two different accurate messages. **Why:** in this
  library an exception is frequently the *answer*, not a bug report — `COORDINATE_OUTSIDE_GRID` fires
  once per point outside the declared coverage — and the frame walk was measured at **1,440 B/op and
  585 ns per refusal** by `GridShiftBenchmark.noGridHit`, against a dispatch path the same benchmark
  prices in tens of nanoseconds. The arm now reads **576 B/op**. If you log proj4j stack traces, set
  the flag
- **The vertical-grid file ceiling dropped from a hardcoded 512 MiB to 128 MiB**, unifying it with
  `Grid`'s under one knob, `-Dproj4j.grids.maxFileBytes`. Raise it if you ship a geoid model larger
  than that; the largest grid PROJ publishes is three orders of magnitude below it. GeoTIFF keeps a
  separate *decoded*-heap budget, `-Dproj4j.grids.maxDecodedBytes`, defaulting to 4× the file ceiling,
  because DEFLATE means file length is not an upper bound on decoded size
- **`GridCache`'s byte budget is now process-wide rather than per instance.** Two caches previously
  each received the full `-Dproj4j.grids.cacheBytes` budget, so the real ceiling was double the
  configured one

### Added
- **Public API facade** `org.locationtech.proj4j.api` — `Proj`, `Crs`, `CrsOperation`, `ProjContext`,
  policy enums, and immutable `AreaOfUse` / `Accuracy` / `GridInfo` / `DatabaseInfo` /
  `ProjectionInfo`. Zero runtime dependencies. Additive: the legacy types are **not** deprecated and
  `createTransform` output is bit-identical
- **Introspection that declines to guess.** `Proj.databaseVersion()` returns `Optional.empty()`
  rather than naming a version the dictionaries do not stamp; `availableGrids()` is probe-verified and
  reported separately from `declaredGrids()`; `axisOrder()` is paired with
  `isAxisOrderAuthoritative()`; `version()` is read from the JAR manifest, never a constant
- **Bulk transform API** — `BulkCoordinateTransform`, four signatures, interleaved and
  struct-of-arrays, with a per-point status byte array and a documented zero-allocation contract
- **Pipeline engine** `org.locationtech.proj4j.pipeline`, and with it GIGS conformance at
  **1,170 / 1,170**
- **WKT2 / WKT1-OGC / WKT1-ESRI and PROJJSON** readers and writers
- **Grid resolution SPI** `org.locationtech.proj4j.resource`, replacing a resolver that searched the
  working directory first — both a determinism hazard and, on untrusted input, an arbitrary-file-open
  primitive. PROJ puts the working directory last
- **Byte-bounded grid cache** `datum/GridCache`, LRU, default 64 MiB
  (`-Dproj4j.grids.cacheBytes`), with no lock held across I/O
- **GTX vertical grid reader** (`datum/VerticalGrid`) and NTv2 multi-subgrid support
- **GeoTIFF grid reader** — `datum/tiff/**` plus `datum/GeoTiffGrid`, **zero dependencies** (DEFLATE is
  `java.util.zip.Inflater`). Classic TIFF and BigTIFF, both endiannesses, strips and tiles, predictors
  1/2/3. Verified **bit-identical to `cct` 9.8.1 to 12 decimals** on all 35 vendored fixtures, both
  subgrid hierarchies, all seven real US grids, and the real `us_nga_egm96_15.tif` and
  `us_nga_egm08_25.tif`. Every unsupported feature is **rejected by name**, including a short DEFLATE
  stream — which previously left the block tail zero, i.e. a geoid reading exactly 0 m
- **`util/FastStrictTrig`** — an allocation-free, pure-Java transcription of the JDK's
  `FdLibm.Sin/Cos/Tan`, verified bit-identical to `StrictMath` over 221,970 raw-bit comparisons on
  five JDK/architecture combinations. **Faster than `StrictMath` on every JDK tested**, by 3.97× on
  Java 8 — which is why no multi-release JAR is needed
- **Conformance harness** for PROJ's own `gie` corpus and the IOGP GIGS suite: 7,923 assertions across
  42 files, with a checked-in expected-outcome manifest (`gie-expected-failures.tsv`, 482 rows, and
  `gie-corpus-index.tsv`, 7,923 keys) so a pass→fail regression fails the build. Current verdict
  against the full index: `regressed 0, unexpected passes 0, new 0, disappeared 0`
- **Golden-master behaviour-diffing gate** (`golden/`) over 53,430 rows against a frozen 1.4.3
  baseline, with `golden/rules.yaml` requiring every changed row to be claimed by a rule that names a
  mechanism and pins an exact row count. **44 of 44 rules are pinned**, and a rule that matches the
  wrong number of rows fails the build rather than silently absorbing another rule's rows
- **Allocation and operation-count gate** (`benchmark/`), **245 arms, 245 gated, 0 excluded**, with a
  recorded baseline of 25 rules and 170 per-benchmark ratchets
- **`ProjContext.parseMode` / `withParseMode` / `Builder.parseMode`**, exposing
  `Proj4Parser.ParseMode.STRICT` through the `Proj` facade. **The default is unchanged
  (`PROJ_COMPATIBLE`)** and must stay so — PROJ has no allow-list, and `builtins.gie` feeds a literal
  `unknown_keyword`. `STRICT` changes exactly one thing: a key outside
  `Proj4Keyword.supportedParameters()` raises `UnsupportedParameterException` naming the key.
  (It used to change two. The second was that an unresolvable `+units` raised
  `InvalidValueException`, because by default the parser went through `Units.findUnits`, which
  substitutes metres for anything unknown and never returns null. That is now refused in **both**
  modes — it is parity with PROJ, not a stricter-than-PROJ policy — so it is no longer something
  `STRICT` adds. See the `+units` entry under [2.1.0].) **Duplicate-key precedence is *not*
  gated** — `+lon_0=11 +lon_0=22` yields 11.0 in both
  modes, and neither reports the duplicate. Across the full shipped dictionary, 9,013 definitions:
  8,969 parse in both modes, 43 are refused in both, **exactly one parses by default and is refused
  under `STRICT` — `world:malay`, for `rot_conv`** (dropped by PROJ in 4.8.0), and none goes the other
  way
- **Explicit `serialVersionUID` on 188 `Serializable` types** in `core`, including `Projection` and
  its 167 subclasses, plus `readResolve` on `AxisOrder`. Every value is `serialver` output against the
  pre-change classes, so **existing serialised forms stay readable**; none was invented. A test fails
  the build if a `Serializable` type in `core/src/main` lacks one. **Known gap, not fixed here:** a UID
  does not make a class serialisable, and **9 of the 151 registered projections still throw
  `NotSerializableException`** outright — pinned and enumerated by class, not globbed:
  `AdamsWorldInASquareIIProjection`, `AlaskaModifiedStereographicProjection`,
  `EquidistantAzimuthalProjection`, `LeeOblatedStereographicProjection`,
  `MillerOblatedStereographicProjection`, `ModifiedStereographic48Projection`,
  `ModifiedStereographic50Projection`, `PeirceQuincuncialProjection`, `SpilhausProjection`, with the
  other 142 serialising cleanly as the non-vacuity control. They hold a `geodesic.Geodesic`, a
  `util.Complex` or an anonymous `Forward2D`, none of which is `Serializable`. Separately and worse,
  **`+proj=geocent` serialises cold and stops serialising after its first transform**, because
  `GeocentProjection$Cached` is built lazily — a driver that broadcasts a warm CRS fails where one
  that broadcasts a cold one does not. This fires on the Spark driver at first broadcast, which is a
  harder failure than the `InvalidClassException` the UID sweep addresses
- **Depth limits on the untrusted-text parsers.** `io/wkt/**` and `io/projjson/**` cap nesting at
  `MAX_DEPTH = 64` syntactic / `MAX_CRS_DEPTH = 24` semantic, throwing in-family rather than letting a
  `StackOverflowError` — an `Error`, which nothing in `core/src/main` catches — escape to the caller.
  Measured against all 5,671 shipped EPSG WKT1 definitions, the deepest real document is **7 read, 8
  written, 3 nested CRSs**, so the caps are 8× real data
- **Integer-overflow guards in every binary grid reader** — `CTABLEV2`, `NTV1`, `NTV2`,
  `datum/tiff/**` and `db/PjdxFile` now compute extents in `long` and check the product against the
  actual file or section length before allocating, via the new `datum/GridExtents`. Refusals are a
  named `GridFormatException`, not an `OutOfMemoryError` and not a `java.lang.Error`
- **`io/InitFileCache`** — each `+init=` dictionary is parsed **once** into
  `Map<authority, Map<code, String[]>>` plus a reverse index, byte-bounded
  (`-Dproj4j.initFiles.cacheBytes`, default 32 MiB), LRU. `createFromName` allocation falls
  **16× / 1,394× / 6,971×** depending on where the code sits in the file; the 200× position-dependent
  ramp is gone. `golden.tsv` is **byte-identical across all 53,430 rows** with the cache on and off
- **Determinism test suite** `org.locationtech.proj4j.determinism` — a committed table of 54,265
  raw-bit `StrictMath` results, plus `.github/workflows/determinism.yaml`, a six-leg
  x86-64 × AArch64 matrix that compares bits across architectures rather than only checking that
  tests pass
- **Many projections**, including the `adams` family, `guyou`, `peirce_q`, `spilhaus`, `mod_ster`, the
  interrupted family and `ups`

### Fixed

Numerical core, all measured against PROJ 9.8.1 — the four helpers behind the auxiliary-latitude
change listed under Breaking, whose combined effect is **19,336 golden-master rows, all below 1 µm**:
- `authlat` — **1.58 mm at latitude 20.8° → 0.7 nm**, against a 0.1 mm tolerance class. Moves `laea`,
  `aea`, `cea`, `eqearth`, `nzmg`
- `mlfn` — **4,920 nm at latitude 72.6° → under 1 nm** (50 nm tolerance class)
- `phi2` — **4,145 nm at latitude 2.8° → 2.1 nm**
- `tsfn` — returned `0.9999999999999999` at φ=0 where the answer is exactly `1.0`, against a
  `tolerance 0 m` assertion

Silent wrong answers:
- **NTv1 reader: data offset 176 instead of 192, *and* the latitude/longitude shift components
  transposed — ~13 m on every NTv1 shift ever computed.** Neither error alone nor the pair moved a
  result far enough to look like a bug
- **NAD27 → NAD83 in CONUS: 95.573 m at San Francisco.** The code half is fixed — the parser called
  `setGrids(null)` on the *static* `Datum.NAD27` singleton, destroying the grid list process-wide.
  **The data half is settled too**: `neoproj4j-epsg` now ships PROJ 9.8.1's `conus` verbatim at
  `proj4/nad/conus` alongside `ntv1_can.dat`, and `neoproj4j-grids-us-legacy` adds `alaska`
- **NTv2**: "only 1 subfile supported" silently used subgrid 1 for the whole file, and interpolation
  read the captured *parent* table after descending into a child. A point in Alberta got no shift at
  all while the transform reported success
- **Grid-edge clamp in `nad_intr`, `1e-11` → `1e-4`** — the old value was 10⁷× too tight, so points
  PROJ shifts were returned unchanged
- **Grid containment tolerance `1e-4` → `1e-5`** (`REL_TOLERANCE_HGRIDSHIFT`) — proj4j accepted and
  **extrapolated** 2e-5° outside `conus`'s south edge where PROJ reports a transformation error
- **Antimeridian grid extents** were unhandled: `us_noaa_alaska.tif` declares `west = -194°`, so its
  whole western half was unreachable
- **The inverse grid-shift loop declared success when only one ordinate had converged** — `&&` where
  PROJ tests the squared 2-norm — and on exhaustion returned the input unchanged. It now throws
  `ConvergenceFailureException` (`ErrorCause.NUMERICAL_FAILURE`)
- **`+rf` and `+f` setters were exactly transposed.** `world:palestine` round-tripped to latitude
  −3.3e205°
- **`GeocentProjection` read its destination coordinate instead of its source, in both directions** —
  it could not produce a correct answer for any input, and had no test
- **`ObliqueMercatorProjection`**: `+alpha` without `+gamma` got **zero rotation** (215,218 m E /
  303,073 m N), and `u_0` used `cos(Gamma)` where upstream uses `cos(alpha)` (2,532 m E on RSO Borneo)
- **`CoordinateReferenceSystem.createGeographic()` dropped `+pm`** — 187,739 m of easting, affecting
  all 94 `+pm=` definitions
- **`AzimuthalProjection` defaulted `lat_0`/`lon_0` to 45°/45°** where PROJ defaults 0/0, so every
  azimuthal projection string omitting them was silently oblique
- **`MercatorProjection` never read `+lat_ts`** — 1.3 million metres of error
- **`CassiniProjection`** wrote 17 instance fields inside `project()`, making it unsafe to share
  across threads, and its forward and inverse were not mutual inverses (68 mm at 3°, growing as `lon⁴`)
- **`AlbersProjection`'s spherical inverse took `asin(rho/dd)`** — the radius rather than `sin φ` —
  an 89.96° error
- **Six per-projection inverse bugs were one bug**: a 2006 C→Java conversion turned "mutate the
  argument then read it" into "write the output struct then read the original parameter" —
  `aea`, `collg`, `fahey`, `bonne`, `bipc`, `SimpleConicProjection` and its seven subclasses
- **`ProjectionMath.asin`/`acos` did not clamp NaN** — `Math.abs(NaN) > 1.` is `false`, so NaN passed
  straight through
- **`+proj=aitoff` had no inverse at all** and fell through to an ungated base-class identity; `wintri`
  discarded `+lat_1`, making the *forward* off by 40,590 m
- **`Ellipsoid.SPHERE`** was the GRS80 authalic radius, not PROJ's Normal Sphere — **0.41 m at 222 km**
- **`Ellipsoid.AIRY`** carried a rounded `b` instead of the exact inverse flattening — 0.76 mm
- **Datum corrections**: `carthage` was bound to the wrong ellipsoid — upstream is `clrk80ign` —
  worth **20.45 mm N** at EPSG:22391 Tunis; `OSGB36` now carries `9.8.1:src/datums.cpp`'s values
  rather than EPSG:1314's, **3.085 mm E** and ~3.5 mm max across GB; and `potsdam` declares its
  `BETA2007.gsb` grid alongside the Helmert, because upstream's live definition is the grid and
  dropping the Helmert instead would have been a **74.9 m E / 127.7 m N** regression where the grid is
  absent
- **`+vunits=m` was unknown to the parser**, so it rejected the whole definition — one defect that
  made 144 dictionary rows fail and moved 964 golden-master rows when fixed

Determinism and locale:
- **`io/Proj4FileReader` called `toLowerCase()` with no locale.** Under `tr_TR`, `"ESRI"` becomes
  `"esrı"` — **all 2,954 ESRI codes were unreachable in a Turkish-locale JVM**
- `ProjCoordinate.DECIMAL_FORMAT`, `units/Unit` and `units/AngleFormat` used the default locale
- `ProjCoordinate.equals`/`hashCode` ignored `z`, silently making every coordinate comparison 2D
- `datum/AxisOrder` deserialised to a new instance, breaking `== AxisOrder.ENU`

### Changed
- **Failures are no longer expressed as plausible coordinates.** `aasin`/`aacos` throw rather than
  clamping, and a domain guard matching PROJ's (`|λ| > 10` **radians**, pole latitudes clamped within
  `1e-12` rad) rejects genuinely out-of-domain input. A `[-180, 180]` rejection would be *stricter*
  than PROJ and is deliberately not what this does
- **Forward-only projections no longer return the input as if it were lon/lat.** They throw.
  **13 projections × 5 probes = 65 golden-master rows** today — `airy august boggs denoy larr lask
  nicol rpoly tcc wag7 adams_hemi adams_ws1 guyou` — plus a separate 40 rows in projections added this
  release. When the rule was written it claimed **90 rows, of which 75 had reported `OK` in 1.4.3**;
  that 75 is the measurement that proves this was a fix rather than a new restriction, and the other
  15 had been `InvalidValueException: Unknown projection`. The set shrank to 65 because five names —
  `lagrng`, `aitoff`, `hammer`, `nsper`, `wintri` — turned out to have an inverse **upstream**, which
  they now have here too.
  **The gate does not key on `hasInverse()`**: that declaration is wrong in both directions
  (`KrovakProjection` and `NewZealandMapGridProjection` implement `projectInverse` without declaring
  it, `LandsatProjection` declares it while overriding nothing), and a `hasInverse()`-keyed gate
  rejected **EPSG:2065, EPSG:5514 and EPSG:27200 — three working CRS**. The shipped gate looks for a
  declared `projectInverse(double, double, ProjCoordinate)` in the class hierarchy instead
- `adjlon` replaces `normalizeLongitude`, fixing a potential hang and a dateline sign flip
- Old numerical helpers in `ProjectionMath` are deprecated, not removed

### Conformance

- **PROJ 9.8.1 gie corpus: 7,441 / 7,900 — 94.19 %.** **30 of the 42 active corpus files are at
  100 %.** Remainder: 457 failing, 2 skipped
- **The denominator excludes vacuous rows, and says so.** The corpus holds **7,923** assertions
  (6,962 `expect` + 961 `roundtrip`, counted with a port of gie's own lexer, not with `grep`). **23
  are vacuous `expect failure` rows** — proj4j could not construct the operation at all, so "both
  failed" is evidence about neither engine — and they are excluded from **numerator and denominator
  alike** rather than banked as passes, giving 7,900. **2 skips are reported separately and are never
  passes.** 94 out-of-block lines in `DHDN_ETRS89.gie` are reported as excluded
- **No 1.4.3 conformance baseline is quoted, because none can be reproduced.** Earlier drafts
  headlined a `1,066 / 6,845 — 15.6 %` baseline; nothing in the tree reproduces those two numbers, so
  they are withdrawn rather than reworded. What *can* be derived is an upper bound: 1.4.3 could only
  build an operation whose `+proj=` name appears in the `register(…)` list of `Registry.java` — 93
  names at tag `v1.4.3` — and it had no `+proj=pipeline`. Matching that list against the `operation`
  governing each of the 7,923 in-block assertions leaves **at most 1,830, or 23 %**, counting every
  ambiguous case in its favour. See [RELEASE-NOTES.md](RELEASE-NOTES.md) for the derivation
- **GIGS: 1,170 / 1,170 — 100 %**, all 20 files
- **Zero rows in the 4,280-row MetaCRS corpus where proj4j and PROJ 9.8.1 both produce a coordinate
  and the coordinates differ.** The ~1,195 apparent regressions against 1.4.3 are a stale reference
  file: 775 `tmerc` rows agree with `cs2cs` 9.8.1, 280 are refused by both engines, 28 `cass`, 24
  `tmerc`+`+datum=` and 3 `eqc` agree

### Known limitations

Operator families that are **not implemented**. Each is a refusal, not a silent omission.

- **The DGGS group — `airocean`, `s2`, `isea`** — absent from `Registry`, together **188 failing
  assertions in `builtins.gie`** (92 / 56 / 40) and declined on ratio
- **`+proj=helmert` as a user-facing operator.** It exists only as the hidden static
  `+exact +convention=position_vector` helper the `cs2cs` emulation builds. Exposing a subset would
  silently ignore `convention=coordinate_frame`, `transpose` and seven time-dependent rates, all of
  which the corpus exercises. Costs 3 assertions in `GDA.gie` and 1 in `4D-API_cs2cs-style.gie`
- **`gridshift` (the unified operator) and `defmodel`** — both need the GeoTIFF reader wired into the
  pipeline layer; the reader itself ships
- **`+proj=deformation +grids=`**, the single-file three-channel form. The two-grid form works
- **`nkg`** — 33 assertions; needs `PROJ:PROJString` pipelines plus a transformed time dimension, not
  more data
- **The time dimension is not transformed** — no `+proj=unitconvert +t_in`, no `+proj=set +t`. But
  `+t_epoch` / `+t_final` on `hgridshift` / `vgridshift` **are** honoured, so a time-*gated* grid
  shift behaves as upstream's does
- **NADCON is deliberately not implemented.** It is not a 9.8.1 format: `grids.cpp` dispatches on
  NTv1, CTABLE V2, NTv2 and TIFF, and the `us_noaa_nadcon5_*` grids are NADCON 5 data *in GeoTIFF*

Other boundaries:

- **Vertical and height support is thinly evidenced.** GTX and GeoTIFF readers ship, but **only 356 of
  7,923 corpus assertions — 4.5 % — score a third ordinate at all** (225 with three numbers, 131
  with four); for
  the other 5,419 coordinate expects `gie.cpp` zeroes the third ordinate on both sides, so z
  contributes exactly zero to the deviation. For a height through the *datum* stage specifically the
  corpus reaches **8 assertions and none passes**. Test your own heights
- **The legacy path has no `proj.db`.** `+datum=OSGB36` differs from PROJ by **1.784 m** (PROJ picks
  OSTN15), `nzgd49` by **2.248 m**. This is a **data-vintage gap, not an arithmetic defect** — given
  the same parameter strings the two engines agree. A pure-Java zero-dependency reader for a
  transcoded 9.8.1 database exists (`neoproj4j-db`, Phase 1), but operation *selection* is not yet wired
  through it
- **The shipped EPSG dictionary is v9.2-era (2017)** against PROJ 9.8.1's v12.029
- **NaN sign and payload are architecture-dependent and outside the bit-for-bit determinism
  guarantee.** Finite results and signed zero are inside it. Measured: `Inf - Inf` is
  `0xfff8000000000000` on x86-64 and `0x7ff8000000000000` on AArch64 with the JDK held fixed

### Gate status, stated honestly

*Re-measured 2026-08-03 in the pinned container (Temurin 21.0.11 / aarch64).*

- **ci** — **green**: whole 7-module reactor, `BUILD SUCCESS` with javadoc, **2,320 tests / 0 failures
  / 4 skipped** (`core` 1,917 · `conformance` 345 · `db` 52 · `geoapi` 6). The `MetaCRSTest`
  expectation that used to make this red no longer applies
- **conformance** — live and CI-wired, **green** against a committed 7,923-key index, **7,441 / 7,900**
- **golden** — live and **RED on 2,291 UNEXPLAINED rows** of 53,430
  (12,005 UNCHANGED · 41,425 CHANGED · 0 ADDED · 0 REMOVED · 39,134 INTENDED), down from 18,168 →
  3,304 → 2,291 over two triage passes, with **44 of 44** rules pinned. Since commit `052e627`
  (2026-08-05) it runs weekly and on demand rather than on every push and pull request.
  **Red is the intended state**:
  the gate fails on any changed row that no rule claims with a named mechanism and a pinned count, so
  those 2,291 are changes somebody must *explain*, not changes somebody must *undo*
- **allocation** — **0 breaches, 245 gated, 0 EXCLUDED, 245/245 arms**. The claim that
  `gc.alloc.rate.norm` does not flake was **false for 11 of 181 arms** at the time: two independent
  runs agreed to within 0.0001 B/op on 170 arms, while the 11 `CrsParseBenchmark` arms above 1 KB/op
  drifted by up to 0.121 %. That was resolved first by ungating that rule's nine arms — a stated
  reduction in coverage — and then, properly, by **removing the cause**: `io/InitFileCache` made the
  arm fixed-shape, it rejoined Tier 1, and **there are no exclusions today**. Separately,
  `BulkTransformBenchmark` left the `staged` package, so the bulk API is gated for the first time,
  at a hard 0 B/op across 56 arms
- **determinism** — runs per leg, **22** tests, 0 failures, 0 skips (the workflow's exact-count guard
  became a floor, `DET_FLOOR_TESTS=22`, and reports upward drift as a notice)
- **bench** — baseline re-captured 2026-08-02: **170 per-benchmark ratchets, all enforced**, 25 rules,
  8 CRS pairs × 19 operations pinned
- **No CI run backs any figure in this file.** The workflow files are committed; everything above was
  measured locally

## [1.4.3] - 2026-06-02

### Added
- JPMS Automatic-Module-Name to the core and epsg JAR manifests [#129](https://github.com/locationtech/proj4j/pull/129)

## [1.4.2] - 2026-05-24

### Fixed
- Transformation one projection to another in one step should not skip the datum shift [#128](https://github.com/locationtech/proj4j/pull/128)
- GRS80 should be recognized as a WGS84 transformation type [#127](https://github.com/locationtech/proj4j/pull/127)

### Added
- JPMS Automatic-Module-Name to JAR manifest [#123](https://github.com/locationtech/proj4j/pull/123)

## [1.4.1] - 2025-06-15

### Fixed 
- External GridDefinition read fix [#121](https://github.com/locationtech/proj4j/pull/121)

## [1.4.0] - 2025-03-31

### Fixed 
- LCC ProjectInverse adjustment, BasicCoordinateTransform.transform cleanup [#117](https://github.com/locationtech/proj4j/pull/117)

### Added
- GeoAPI wrappers for PROJ4J [#115](https://github.com/locationtech/proj4j/pull/115)

## [1.3.0] - 2023-05-30

### Added
- Parsing NTv2 Improvement [#99](https://github.com/locationtech/proj4j/pull/99)
- GH-89: initial support for NTv2 [#98](https://github.com/locationtech/proj4j/pull/98)

## [1.2.3] - 2023-01-25

### Fixed
- Fix the inverse Krovak transformation [#97](https://github.com/locationtech/proj4j/pull/97)

## [1.2.2] - 2022-12-12

### Fixed
- Move all core resources to epsg submodule [#95](https://github.com/locationtech/proj4j/pull/95)

## [1.2.1] - 2022-12-12

### Fixed
- Fix maven pom.xml release metadata

## [1.2.0] - 2022-12-04

### Fixed
- Fix EquidistantAzimuthalProjection through add geodesic package [#84](https://github.com/locationtech/proj4j/issues/84)
- Fix RobinsonProjection [#87](https://github.com/locationtech/proj4j/issues/87)
- Backport: Stop after successfully applying grid [#91](https://github.com/locationtech/proj4j/pull/91)

### Added
- Added support for EPSG:9054 [#93](https://github.com/locationtech/proj4j/pull/93)
- Split projects into proj4j and proj4j-epsg [#92](https://github.com/locationtech/proj4j/pull/92)

## [1.1.5] - 2022-03-25

### Fixed
- Fix Grid equals [#78](https://github.com/locationtech/proj4j/pull/78)

## [1.1.4] - 2021-11-03

### Fixed
- Adjustment to OSGB36 datum transform e.g. EPSG: 27700
- GeocentricConverter equality check after grid shift WGS param override e.g. EPSG: 27700 [#32]
- +nadgrids=@null support e.g. EPSG: 3857

## [1.1.3] - 2021-06-17

### Fixed
- Problem with omerc projection e.g. EPSG: 3375 [#21](https://github.com/locationtech/proj4j/issues/21)

## [1.1.2] - 2021-04-12

### Fixed
- Fix NZ Map projection and add a test for it [#62](https://github.com/locationtech/proj4j/issues/62)
- Update OrthographicAzimuthalProjection [#63](https://github.com/locationtech/proj4j/pull/63)
- Fix UTM, LCC, Krovak and Stere projections [#71](https://github.com/locationtech/proj4j/pull/71)
- *2 in stereographic projection near the equator [#58](https://github.com/locationtech/proj4j/issues/58)
- WebMercator EPSG code retrieved from proj4 parameters returns a legacy value [#61](https://github.com/locationtech/proj4j/issues/61)

### Added
- Geocent projection support [#60](https://github.com/locationtech/proj4j/pull/60)

## [1.1.1] - 2020-03-08

### Added
- A projection may have a radius, support `+R=` parameter [#54](https://github.com/locationtech/proj4j/issues/54)

## [1.1.0] - 2019-09-05

### Added
- Added `GeostationarySatelliteProjection`/`geos` projection [#27](https://github.com/locationtech/proj4j/pull/27)
- Registry.getProjections exposes all available projects [#31](https://github.com/locationtech/proj4j/pull/31)
- OSGi compatibility [#44](https://github.com/locationtech/proj4j/pull/44)

### Changed
- Parse `geos` (Geostationary Satellite Projection) proj4 strings [#27](https://github.com/locationtech/proj4j/pull/27)
- Projection units reported as meters by default [#28](https://github.com/locationtech/proj4j/pull/28)
- BasicCoordinateTransform now thread-safe [#29](https://github.com/locationtech/proj4j/pull/29)
- Improve CRS Caching performance [#33](https://github.com/locationtech/proj4j/pull/33), [#34](https://github.com/locationtech/proj4j/pull/34), [#36](https://github.com/locationtech/proj4j/pull/36)
- CoordinateReferenceSystem.equals considered logical equality [#45](https://github.com/locationtech/proj4j/pull/45)
- Projection.equals considered logical equality [#45](https://github.com/locationtech/proj4j/pull/45)

## [1.0.0] - 2019-12-12

### Added
- Added support for Extended Transverse Mercator [#6](https://github.com/locationtech/proj4j/pull/6)

### Changed
- Update EPSG DB v9.2 [#7](https://github.com/locationtech/proj4j/pull/7)
- Increasing accuracy of `etmerc` projection [#14](https://github.com/locationtech/proj4j/pull/14)

### Fixed
- Fix possible `null` dereference [#16](https://github.com/locationtech/proj4j/pull/16)
- Fix `cea` (Cylindrical Equal Area) projection [#10](https://github.com/locationtech/proj4j/pull/10)

[Unreleased]: https://github.com/emilevictor/neoProj4J/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/emilevictor/neoProj4J/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/emilevictor/neoProj4J/compare/v1.4.3...v2.0.0
[1.4.3]: https://github.com/locationtech/proj4j/compare/v1.4.2...v1.4.3
[1.4.2]: https://github.com/locationtech/proj4j/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/locationtech/proj4j/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/locationtech/proj4j/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/locationtech/proj4j/compare/v1.2.3...v1.3.0
[1.2.3]: https://github.com/locationtech/proj4j/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/locationtech/proj4j/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/locationtech/proj4j/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/locationtech/proj4j/compare/v1.1.5...v1.2.0
[1.1.5]: https://github.com/locationtech/proj4j/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/locationtech/proj4j/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/locationtech/proj4j/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/locationtech/proj4j/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/locationtech/proj4j/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/locationtech/proj4j/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/locationtech/proj4j/compare/def8d6f3a1408676969eb7ce20c1f1eafa1ce010...v1.0.0
