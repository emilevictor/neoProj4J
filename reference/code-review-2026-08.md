# Code review — August 2026

This is the first static-analysis pass ever run against this tree. Before it, a search for
`spotbugs|checkstyle|errorprone|pmd|jacoco|forbiddenapis|revapi|japicmp|sonar` across every
`pom.xml` and every file under `.github/` returned nothing: the eleven plugins in the root POM are
all packaging and publishing plugins. So this document starts from zero instrumentation, and its
first job is to record what each instrument was and what it said, so the next reviewer re-derives
rather than re-discovers.

Every command below was run on 2026-08-07 against commit `2eff108`, on JDK 21 (Temurin
21.0.11, aarch64). **No instrument was added to any POM.** Each runs as a fully-qualified goal from
the command line and writes only to `target/`, which is why instrumenting the project cost the build
nothing.

## How to re-derive every number in this document

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # or any JDK 21

# Bug patterns over bytecode. `compile` MUST be in the same invocation — see Gotchas below.
mvn -B -ntp compile com.github.spotbugs:spotbugs-maven-plugin:4.10.3.0:spotbugs \
    -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.xmlOutput=true \
    -Dmaven.javadoc.skip=true

# Copy-paste detection, and the general PMD rule set.
mvn -B -ntp compile org.apache.maven.plugins:maven-pmd-plugin:3.28.0:cpd -Dminimum.tokens=100
mvn -B -ntp compile org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd

# Coverage. The `report` goal needs its own invocation once `jacoco.exec` exists.
mvn -B -ntp org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent test -Dmaven.javadoc.skip=true
mvn -B -ntp -pl core org.jacoco:jacoco-maven-plugin:0.8.15:report

# Javadoc correctness.
mvn -B -ntp org.apache.maven.plugins:maven-javadoc-plugin:3.5.0:javadoc \
    -Ddoclint=all -Dmaven.javadoc.failOnError=false

# API compatibility against the PUBLISHED 2.0.0. Run from the japicmp CLI, not the Maven plugin.
jar cf new.jar -C core/target/classes .
curl -sfLO https://repo1.maven.org/maven2/io/github/emilevictor/neoproj4j/neoproj4j/2.0.0/neoproj4j-2.0.0.jar
curl -sfL -o japicmp.jar https://repo1.maven.org/maven2/com/github/siom79/japicmp/japicmp/0.26.1/japicmp-0.26.1-jar-with-dependencies.jar
java -jar japicmp.jar -o neoproj4j-2.0.0.jar -n new.jar --ignore-missing-classes --semantic-versioning

# Reachability from the public API. See "Reachability" below for the walk itself.
jdeps -verbose:class -filter:none -cp <all module classes> core/target/classes
```

### Gotchas that cost time, recorded so they cost nobody else any

1. **SpotBugs, PMD and forbiddenapis need a lifecycle phase in the same Maven invocation.** Running
   `mvn com.github.spotbugs:...:spotbugs` alone reports *"No files found to run spotbugs; check
   compile phase has been run"* and then exits 0, so it looks like a clean result. It is not a
   result at all. Put `compile` first.

2. **jgitver puts the branch name into the Maven version**, so on a branch called
   `review/full-code-review` the reactor version becomes `2.0.1-review_full_code_review-SNAPSHOT`.
   Any goal that resolves an inter-module dependency then fails with *"Could not find artifact
   io.github.emilevictor.neoproj4j:neoproj4j-epsg:jar:2.0.1-review_full_code_review-SNAPSHOT"*,
   because the installed artifact carries a different version. The fix is to let the whole reactor
   build in one invocation rather than relying on anything previously installed.

3. **jdeps defaults to `-filter:package`, which silently drops every same-package edge.** Without
   `-filter:none` the graph claimed `CoordinateTransformFactory` does not reference
   `BasicCoordinateTransform`, and the reachability walk consequently reported 171 unreachable
   classes instead of 11. If a reachability result looks implausibly large, this is why.

4. **PMD's CPD report is namespaced** (`xmlns="https://pmd-code.org/schema/cpd-report"`), so an
   ElementTree `findall('duplication')` matches nothing while `grep -c '<duplication'` finds plenty.
   Strip the `xmlns` attribute before parsing.

5. **Three source files contain raw NUL bytes**, so `grep` treats them as binary and skips them
   without saying so. Any grep-based audit of this repo has a blind spot at
   `db/src/main/java/.../gen/GenerateIndex.java` (6 NULs, used as map-key separators),
   `golden/src/main/java/.../GoldenGenerator.java` (2), and
   `core/src/test/java/.../security/readers/HostileGrids.java` (1). They are legal in char literals
   and they compile; writing them as `'\0'` would restore greppability. Use `grep -a` until then.

### The one instrument that could not be run

**forbiddenapis was attempted and abandoned.** Its `bundledSignatures` parameter is a list with no
user property, so it cannot be set with `-D` — it has to be configured inside a POM, which is
exactly what this review agreed not to do. Without signatures the goal fails outright: *"No
signatures were added to mojo."* The locale-and-charset questions it would have answered were
instead answered by SpotBugs (`DM_DEFAULT_ENCODING`, three findings) and by reading the call sites,
so the gap is narrow but it is a gap, and it is recorded here rather than papered over.

**Error Prone was deliberately not attempted.** It only runs when wired into
`maven-compiler-plugin`, which is a POM edit, and it interacts badly with this project's
`<release>8</release>`.

## The baseline, measured before any change

Captured with `./docker/run.sh ci conformance golden determinism` on the untouched branch point.

```
CHECK         VERDICT        TIME      MEASURED
ci            PASS           0m25s     2320 tests, 0 failures
conformance   PASS           0m05s     7441/7900 genuine passes, regressed 0, 7923 assertions
golden        FAIL           0m06s     2291 UNEXPLAINED plus COUNT_MISMATCH(2)
determinism   PASS           0m04s     22 tests, 0 failures (one leg: aarch64 / Temurin 21)
```

`japicmp` against published 2.0.0 reports **"No changes"**, semantic-versioning verdict `0.0.1`. The
branch point is API-identical to what is on Maven Central, which is what makes it usable as a gate:
any later change that alters the public surface will show up as a diff against this same command.

### A pre-existing failure that the review did not introduce

`golden` fails, and one part of that failure is a defect rather than a backlog item:

```
COUNT_MISMATCH DATUM-TYPE-UNKNOWN-HOISTED expected_rows=251 but matched 253
    -- the count is exact and two-sided on purpose; something else moved with it
golden diff: 12010 UNCHANGED, 41420 CHANGED, 0 ADDED, 0 REMOVED;
             39129 INTENDED, 2291 UNEXPLAINED (baseline 53430 rows, current 53430 rows)
```

`docker/run.sh`'s own header states the rule: *"A COUNT_MISMATCH, DEAD_RULE, EXPIRED_RULE or
PENDING_RULE_FIRED is a real failure … because those mean the rule set has stopped describing the
tree."* By that rule this is a rule-set defect on `master`, categorically different from the 2291
unexplained rows, which are a known triage backlog. It should be fixed before the bit-identity gate
below is relied on, because that gate compares golden reports and one of the reports is already
inconsistent with the tree it describes.

### What the bit-identity gate actually is

An earlier draft of this review described the gate as "golden byte-identical to the baseline." That
was wrong, and the correction matters. `golden` compares the tree against **released 1.4.3**, so
41420 of 53430 rows legitimately differ — 39129 of them claimed by a rule as intended. The workable
gate is that **the golden report is unchanged between the branch point and the change**, not that it
is empty.

## Findings

### Ranked by consequence

Ordered by what a user notices, not by what a tool scored.

---

#### 1. `+ellps=NWL9D` and `+ellps=andrae` return coordinates from an ellipsoid that is nearly a flat disc

`core/src/main/java/org/locationtech/proj4j/Registry.java:99-100`

```java
new Ellipsoid("NWL9D",  6378145.0,  298.25, 0.0, "Naval Weapons Lab., 1965"),
new Ellipsoid("andrae", 6377104.43, 300.0,  0.0, "Andrae 1876 (Den., Iclnd.)"),
```

Both pass the inverse flattening in the **`poleRadius`** slot and zero in the
`reciprocalFlattening` slot. Per the constructor at `datum/Ellipsoid.java:296-320`, a zero
`reciprocalFlattening` selects the `eccentricity2 = 1 - (poleRadius² / equatorRadius²)` branch, so
the pole radius is taken literally as 298 metres.

Measured, not inferred — resolving each name through `new Registry().getEllipsoid(name)`:

```
NWL9D        a=6378145.0    b=298.25               e=0.999999998906693
andrae       a=6377104.43   b=300.0                e=0.9999999988934642
GRS80        a=6378137.0    b=6356752.314140356    e=0.08181919104281579   <- for scale
```

The correct values already exist in the same tree, as `Ellipsoid.NWL9D` (`datum/Ellipsoid.java:129`)
and `Ellipsoid.ANDRAE` (`:135`), which pass the figure in the right slot and yield pole radii of
6356759.77 and 6355847.42. `Registry.ellipsoids` is the list `+ellps=` resolves against, and it is
the broken one.

This is a wrong-answer bug, not a refactor, and fixing it changes output for those two names by
design. It therefore cannot ride along with any bit-identity-constrained change.

#### 2. `+ellps=australian` does not resolve, though the ellipsoid is defined

`Ellipsoid.AUSTRALIAN` (`datum/Ellipsoid.java:114-115`, shortName `"australian"`) appears in
`Ellipsoid.ellipsoids`, which only the WKT reader consults. It is absent from
`Registry.ellipsoids`, so `new Registry().getEllipsoid("australian")` returns null. The same figure
is reachable under a different name — `Registry.java:101` registers `"aust_SA"` with the same
6378160.0 and 298.25 — so this is a missing alias rather than a missing ellipsoid, and it is
low-severity on its own. It matters because it is the same root cause as finding 1: the two lists are
hand-synchronised and have drifted.

`Registry.datums` (`Registry.java:53-72`) already does this correctly, referencing `Datum.`
constants rather than re-declaring them inline. The fix for the ellipsoid list is a pattern already
established in the same file.

#### 3. `Ellipsoid.INTERNATIONAL` and `Ellipsoid.INTL` are the same ellipsoid, declared twice

`datum/Ellipsoid.java:70` and `:183` are identical in all five constructor arguments — shortName
`"intl"`, 6378388.0, 0.0, 297.0, `"International 1909 (Hayford)"`. Two distinct objects with equal
state, so `INTERNATIONAL.equals(INTL)` is true (`Ellipsoid.equals` at `:407-420` compares by value)
while `INTERNATIONAL == INTL` is false.

**Correction, 2026-08-10.** As first written this finding said both constants were listed in
`Ellipsoid.ellipsoids` and that "deleting one is bit-for-bit safe." Both halves were wrong, and the
second one dangerously so.

The array held **only `INTL`**; `Registry.ellipsoids` (`Registry.java:132`) held **only
`INTERNATIONAL`**. There was no duplicate array entry — there were two tables disagreeing about
which object represents the same ellipsoid, so `+ellps=intl` and the WKT writer's reverse lookup
returned different objects for it.

And deleting `INTL` from the array would have **moved bits**. It was the array's only member with
`a = 6378388.0, rf = 297.0`, so `WktNames.projEllipsoidCode` (`WktNames.java:271-295`), which scans
numerically and keeps the smallest `rf` delta, would have returned `null` and the writer would have
emitted explicit `+a=`/`+rf=` in place of `+ellps=intl`.

What was done instead, in the dead-code change: the array entry was changed from `INTL` to
`INTERNATIONAL`, and `INTL` was deprecated in place rather than removed — `datum` is an exported
package. That swap **is** bit-identical, because both the WKT reverse lookup and
`GieEllipsoidResolver` (`:181-183`) key off `shortName`, which is `"intl"` either way. `HOUGH`
shares `rf = 297.0` but has `a = 6378270.0`, so there is no tie-break sensitivity to worry about.

#### 4. `+proj=rpoly` constructs successfully and then silently ignores both its parameters

`core/src/main/java/org/locationtech/proj4j/proj/RectangularPolyconicProjection.java`

```java
28:  private double phi0;    // read at :45, :48 — never assigned
30:  private double fxa;     // read at :41      — never assigned
32:  private boolean mode;   // read at :39      — never assigned
...
54:  public void initialize() { // rpoly
55:      super.initialize();
56:  /*FIXME
57:      if ((mode = (phi1 = Math.abs(pj_param(params, "rlat_ts").f)) > EPS)) {
58:          fxb = 0.5 * Math.sin(phi1);
59:          fxa = 0.5 / fxb;
60:      }
61:  */
62:  }
```

The whole body of `initialize()` is commented out, so `mode` is permanently `false` and `phi0`
permanently `0.0`. The projection is registered at `Registry.java:468` and listed in
`io/wkt/WktMethods.java:425`, so `+proj=rpoly` builds without complaint and then discards `+lat_ts`
and `+lat_0`. It returns plausible-looking wrong coordinates rather than refusing. `hasInverse()` is
not overridden, so it inherits `false`.

PMD independently flags the same file twice — `UnusedPrivateField` on `phi1` and
`UselessOverridingMethod` on the `initialize()` that now only calls `super`.

#### 5. `LambertAzimuthalEqualAreaProjection(boolean south)` ignores its argument

`proj/LambertAzimuthalEqualAreaProjection.java:94-100`. The constructor's entire body is commented
out, so `new …(true)` is indistinguishable from `new …(false)`. In-tree the only caller is the
no-arg overload delegating `this(false)`, so nothing is currently wrong — but it is public API, and
an external caller passing `true` gets silently wrong output.

#### 6. `ContradictoryParameterException` is public, documented, and never thrown

Nothing in any main source constructs it. The only `new ContradictoryParameterException(...)` calls
in the repository are in tests. It sits in the documented hierarchy at
`Proj4jException.java:39` and is cross-referenced from `ErrorCause.java:109` and
`InvalidValueException.java:25`, so a consumer writing `catch (ContradictoryParameterException e)`
writes a branch that can never be taken. Note that the *cause* is live —
`pipeline/PipelineErrorCode.java:44` maps `MUTUALLY_EXCLUSIVE_ARGS` to
`ErrorCause.CONTRADICTORY_PARAMS` — but it is reported through a different exception type. So this
is either a missing throw site or an exception that should not exist.

**Resolved in 2.0.1: deprecated as never thrown**, since it cannot be removed from an exported
package. The type that is actually raised is `PipelineDefinitionException`, a *sibling* — it extends
`InvalidValueException` too, and carries the same cause — thrown from exactly two places,
`AxisSwapOperator.java:67-68` and `DeformationOperator.java:148-149`. A caller who wants the
condition should catch `InvalidValueException` or switch on the cause.

Worse than the dead type was what its Javadoc offered as examples. It named `+ellps=GRS80 +rf=300`
and `+rf=298.257 +f=0.00335`, and `ErrorCause.java:106` repeated the first. **The library
deliberately accepts both**, following PROJ's `ell_set.cpp` and letting the later shape parameter
win — recorded at `StepEllipsoid.java:50-53` and `Proj4Parser.java:885-887`. So the two definitions
a reader was most likely to try, on the strength of that Javadoc, were the two guaranteed to raise
nothing at all. Both examples are corrected.

#### 7. `Proj4FileReader` reads files in the platform default charset

SpotBugs `DM_DEFAULT_ENCODING`, priority 1, at `io/Proj4FileReader.java:52` (twice) and
`io/InitFileCache.java:113`. On a machine whose default charset is not UTF-8 the authority files
parse differently. This matters more here than in most projects because **`core/pom.xml` configures
no surefire plugin at all**, so core's 218 test files run under whatever locale, timezone and
charset the developer's machine happens to have. `conformance/pom.xml:120` pins all four
deliberately, with a comment explaining why it must be `argLine` rather than
`systemPropertyVariables`; core does not. There are 57 locale- or charset-sensitive call sites in
core's tests and nothing holding them steady.

The related locale defect is already fixed but still documented as broken — see the documentation
section.

#### 8. `ProjectionMath.toRad` and `ProjectionMath.degToRad` are not the same function

`util/ProjectionMath.java:99` is `deg * DTR` where `DTR = Math.PI/180.0`; `:366` is
`v * Math.PI / 180.0`. The first multiplies by a pre-rounded constant, the second multiplies then
divides, and they differ in the last bit. At a live call site — `AlbersProjection.java:57`
converting 29.5° — `degToRad` gives 0.5148721293383272 and `toRad` gives 0.5148721293383273. The
same asymmetry exists for `toDeg` (`:145`) versus `radToDeg` (`:370`).

This is the clearest example of why the change bar for this review is bit-identity. Consolidating
these two into one function is a one-line change that moves output.

---

### Static analysis, by the numbers

| Instrument | Result |
|---|---|
| SpotBugs (Max effort, Low threshold) | **600** findings — 23 priority 1, 196 priority 2, 381 priority 3 |
| PMD | **191** violations — 139 Code Style, 23 Best Practices, 17 Error Prone, 12 Design |
| CPD (100-token minimum) | **19** duplications — core 10, db 4, geoapi 5 |
| JaCoCo (core) | **79.7%** instructions (98386/123467), **67.8%** branches (8105/11957), 414 classes |
| javadoc `-Ddoclint=all` | 14 warnings/errors |
| japicmp vs published 2.0.0 | **No changes** |
| forbiddenapis | not run — see above |

#### SpotBugs: the 23 priority-1 findings

| Pattern | Sites |
|---|---|
| `MS_SHOULD_BE_FINAL` | `ProjCoordinate:38` (×2), `ProjectionException:39` |
| `MS_MUTABLE_ARRAY` | `Registry:44`, `datum.Ellipsoid:61`, `units.Units:54` (×2), `db.PjdxFormat:126` |
| `DM_BOXED_PRIMITIVE_FOR_PARSING` | `datum.PrimeMeridian:36`, `units.Angle:20`, `units.AngleFormat:57` |
| `DM_DEFAULT_ENCODING` | `io.InitFileCache:113`, `io.Proj4FileReader:52` (×2) |
| `FE_FLOATING_POINT_EQUALITY` | `io.wkt.ParameterDefinition:32`, `io.wkt.PrimeMeridianDefinition:25`, `pipeline.CartOperator:78`, `proj.SineTangentSeriesProjection:36` |
| `CNT_ROUGH_CONSTANT_VALUE` | `pipeline.Cs2csOperator:240` |
| `SE_BAD_FIELD` | `geoapi.AuthorityFactoryWrapper:46`, `geoapi.OperationFactoryWrapper:47` |
| `CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE` | `geoapi.OperationMethodWrapper:41`, `geoapi.ParameterWrapper:36` |

`MS_MUTABLE_ARRAY` on `Registry:44` deserves its own note: `Registry.ellipsoids` is a `public
final static Ellipsoid[]`, so any consumer can overwrite an entry in the global ellipsoid table.
The Javadoc already says "the array is public and its contents are mutable; treat it as read-only,"
which documents the hazard without removing it. Fixing it properly is an API break.

The largest priority-2 and priority-3 groups are `IAOM_DO_NOT_INCREASE_METHOD_ACCESSIBILITY` (131 —
the projection classes widening `project`/`inverseProject`, deliberate),
`NM_METHOD_NAMING_CONVENTION` (53 — the PROJ-derived names like `mlfn`, `enfn`, `pj_param`, also
deliberate), `SIC_INNER_SHOULD_BE_STATIC_ANON` (45), `EI_EXPOSE_REP` (42), and
`FE_FLOATING_POINT_EQUALITY` (42). The floating-point-equality group needs reading case by case: in
a numerics library, comparing against exactly `0.0` to detect a sphere is correct, and comparing two
computed doubles for equality is not.

#### PMD: the findings that can be bugs rather than style

139 of 191 are Code Style, dominated by `UselessParentheses` (94) — noise in a tree ported from C,
where the parentheses often match the upstream source deliberately. The ones worth reading:

```
UnusedNullCheckInEquals        BasicCoordinateTransform.java:343, :344
UselessPureMethodCall          datum/GridCache.java:460, io/InitFileCache.java:275, util/CRSCache.java:474
UnusedFormalParameter          api/OperationSelector.java:596 ('op'), io/wkt/WktMethods.java:862 ('conv'),
                               io/wkt/WktReader.java:342 ('wkt2')
UnusedPrivateField             datum/Grid.java:119 ('gridOffset'), io/wkt/WktReader.java:124 ('dialect'),
                               datum/tiff/GeoTiffImage.java:69 ('dir'), + 12 more
PrimitiveWrapperInstantiation  units/AngleFormat.java:207  — `new Double(...)`
EmptyControlStatement          geodesic/Geodesic.java:1419, util/FastStrictTrig.java:816
```

The three `UselessPureMethodCall` sites are all `get` on a cache with the result discarded, which is
either a deliberate warm-up or a dropped assignment; each needs a look. `io/wkt/WktReader.java:124`
holding an unused `dialect` field in a reader that takes a dialect is worth a closer look than its
rule name suggests.

#### Dead private members, confirmed by two instruments

PMD's `UnusedPrivateField` and an independent comment-stripped parse agree on these. Six dead
private constants:

- `proj/BoggsProjection.java:32` — `ONETOL`
- `proj/CassiniProjection.java:100` — `EPS10`
- `proj/LoximuthalProjection.java:31,32` — `FC`, `RP`
- `proj/PutninsP2Projection.java:35` — `PI_DIV_3`
- `proj/VanDerGrintenProjection.java:32` — `TWO_THRD`

And five fields that are assigned but never read:

- `datum/Grid.java:119` — `gridOffset`, written as `grid.gridOffset = 0` at `:215` and `:247`. Its
  trailing comment says "for delayed loading," describing a feature that does not exist.
- `proj/EqualAreaAzimuthalProjection.java:53` — `mmf`
- `proj/LambertAzimuthalEqualAreaProjection.java:77` — `mmf`
- `proj/EquidistantAzimuthalProjection.java:76,79` — `N1`, `G`

Removing all eleven is bit-for-bit safe: they are private, and nothing reads them.

#### Commented-out code

Six blocks of five lines or more. Two are load-bearing (findings 4 and 5 above). The others:

- **`proj/ModStereoProjection.java`** — an entire 214-line class commented out line by line. The
  file's only live content is the licence header, the `package` statement, and the comment. The
  commented class ends with `public String toString() { return "Nell"; }` — the wrong projection
  name, so it was already a broken copy-paste when it was disabled. It is referenced only from a
  Javadoc disclaimer at `ModifiedStereographicProjection.java:88` that points at its continued
  existence. **Deleted in 2.0.1.** It produced no `.class` file, and its five `ENTRY0` names all
  resolve to live classes registered elsewhere: `mil_os`, `lee_os`, `gs48`, `gs50` and `alsk`.
- `util/ProjectionMath.java:567-651` — 85 lines: `distance`, intersection helpers, `negate`.
  **Deleted in 2.0.1.** The four public `sameSigns`/`takeSign` overloads it was the only caller of
  are still there, now documented as having no caller — they are public statics in an exported
  package, so removing them is a binary break.
- `util/ProjectionMath.java:514-526` — 13 lines referencing a `MapMath` class that no longer exists.
  **Deleted in 2.0.1.**
- `proj/LongLatProjection.java:30-38` — a dead `transformRadians` using `java.awt.geom.Point2D`.
  **Deleted in 2.0.1**, along with the `TODO` above it asking for methods that the base class has
  already supplied since before the fork.

## Reachability from the public API to the backend

The question "is there a dead code path from the public API to the backend" has no off-the-shelf
tool, so it got a purpose-built walk over the `jdeps` class graph: **541 classes on disk, 2424
internal edges.**

Roots were the 22 entry points a consumer actually starts from — `CRSFactory`,
`CoordinateTransformFactory`, `Registry`, `BulkCoordinateTransform`, `DomainErrorPolicy`, the `api`
facade (`Proj`, `Crs`, `ProjContext`, `LegacyAdapters`), the WKT and ProjJSON readers and writers,
`PipelineFactory`, the `spi` database interfaces, `VerticalCrsRegistry`, `Geodesic`,
`GeodesicLine`, `ResourceResolvers`, `CRSCache` and `GieComparator` — **plus 148 reflective roots
scraped from `Registry`'s name-keyed projection table and 3 from `META-INF/services`.** That
reflective step is not optional: a projection reached only by its `+proj=` short name has no static
edge and would look dead while being entirely live.

Result: **472 of 541 classes reached. 11 top-level classes unreached**, every one then cleared by
hand.

| Class | Verdict |
|---|---|
| `db.gen.GenerateIndex`, `db.gen.VerifyIndex`, `db.gen.PjdxWriter`, `db.gen.QuoteDump` | **Correct.** Build-time generators wired to `<mainClass>` in `db/pom.xml:243` and `:264`. Verified they do **not** ship in the published `db` jar — zero `gen/` entries. |
| `geoapi.Services` | **Correct.** A `public final` convenience facade over the two `META-INF/services` providers. User-facing by design, so internally unreferenced. |
| `resource.DirectoryResourceResolver` | **Correct.** Ten main-source references, all Javadoc — the library documents it extensively and never instantiates it, because the user does. Well covered: 11 test files. |
| `gie.GieTolerance` | **Correct-ish.** Published API used by 7 test files and referenced from main only in a `@param` tag. A utility for consumers of the `gie` package. |
| `proj.EqualAreaAzimuthalProjection` | **Deliberate.** `AzimuthalProjection.java:63` explicitly calls it "unregistered." Absent from `Registry`, so unreachable via `+proj=`, reachable as API. |
| `proj.LinearProjection` | **Worth a decision.** A concrete `public class` with no subclasses, absent from `Registry`, referenced from main only in two Javadoc tables. Reachable as API, unreachable via any `+proj=` string. **Kept and documented in 2.0.1** — `NoInverseGateTest` constructs it as the one in-tree projection whose real inverse is the base-class identity, which is why the no-inverse gate has to consult `hasInverse()`. Three of its five methods, it turns out, override nothing: `project(ProjCoordinate, ProjCoordinate)`, `transform(double[]…)` and `inverseTransform(double[]…)` are JHLabs signatures the base class dropped. That is now said in its Javadoc. |
| `ContradictoryParameterException` | **A genuine dead end.** See finding 6. **Deprecated in 2.0.1** as never thrown. |
| `util.ProjectionUtil` | **Dead in main.** A 4-line class whose only method is `toString(ProjCoordinate)`. Zero main-source references, **zero JaCoCo instruction coverage**, and used only by two test helpers (`ProjectionGridRoundTripper`, `MetaCRSTestCase`) for `System.out.println`. It is in an exported package, so deleting it is an API break. **Kept and documented in 2.0.1**, pointing new callers at `ProjCoordinate.toString()`, which does not drop `z`. |

So the answer to the question as asked is: **no dead path from the public API into the backend, with
two exceptions** — `ContradictoryParameterException`, which the API documents and the backend never
raises, and `util.ProjectionUtil`, which the API exports and nothing in the library uses. Neither
could be deleted, both being in exported packages; in 2.0.1 the first is deprecated and the second
is documented, so in each case reading the Javadoc now tells a caller what the reachability walk had
to be run to discover.

### Dead ends: paths that are reachable but lead nowhere

Separate from unreachability, and worse, because they look supported. 30 public methods in main
sources have a body that only throws.

**Zero of them are stubs in `core`.** The four throw-only public methods in
`proj/Projection.java` (`setSouthernHemisphere`/`getSouthernHemisphere` at `:1168`/`:1185`,
`getHeightOfOrbit`/`setHeightOfOrbit` at `:1340`/`:1368`) each carry a long Javadoc explaining why
they refuse rather than silently ignoring, and each is overridden where it applies. The single
`UnsupportedOperationException` in core, `datum/Datum.java:586` `setGrids`, is a guard on singleton
datums with a declared `+nadgrids`, and it is called from `parser/Proj4Parser.java:832` and
`parser/DatumParameters.java:194`.

**Twelve real functional gaps are in `geoapi`**, all visible to any GeoAPI consumer:

- `AuthorityFactoryWrapper.java:158,163,168,173,178,183,188,193` — `createGeocentricCRS`,
  `createVerticalCRS`, `createTemporalCRS`, `createEngineeringCRS`, `createImageCRS`,
  `createDerivedCRS`, `createCompoundCRS`, `getAuthorityCodes` all throw `FactoryException`. Reached
  through `META-INF/services/org.opengis.referencing.crs.CRSAuthorityFactory`.
- `OperationFactoryWrapper.java:149,156` — `createConcatenatedOperation`, `createDefiningConversion`.
- `Wrapper.java:173` — `toWKT()` throws `UnsupportedOperationException("Not supported.")`, in a tree
  that has a complete WKT writer.
- `Alias.java:101` — `push(GenericName)`.

The remaining throw-only methods are contract-correct: `ParameterWrapper`'s ten type-mismatch
throws, `OperationMethodWrapper`'s two `ParameterNotFoundException`s, and the two `derivative(...)`
`TransformException`s.

**No unexplained swallowed failure anywhere in main sources.** 139 catch blocks, 18 that discard the
exception, and every one of the 18 either carries an inline comment or names the variable to state
the reason (`notImplemented`, `notUsable`, `unreadable`, `notWkt`, `expected`, `ignored`), inside a
method whose contract is a predicate or an optional. This category came back empty, which is worth
recording as a positive result.

**31 bare `return null` methods, all in `geoapi`,** each with Javadoc of the form "the default
implementation assumes that there is none." **Zero occurrences anywhere of `return null` paired with
a TODO.** `api/`, `pipeline/`, `io/wkt/`, `io/projjson/`, `spi/`, `vertical/` and `bulk/` contain no
stub methods at all.

## Redundancy

19 CPD duplications across roughly 94k lines of main source, and **9 of the 19 are a file duplicating
itself** (`db/gen/VerifyIndex.java`, `db/gen/GenerateIndex.java`, `geoapi/TransformWrapper3D.java`,
`geoapi/TransformWrapper2D.java`, `pipeline/Triangulation.java:440`↔`:479`,
`geodesic/GeoMath.java:182`↔`:218`, `proj/GeostationarySatelliteProjection.java:142`↔`:172`). A
separate shingling pass found **not a single verbatim 15-line block shared by three or more files
anywhere in main.** By the standards of a 169-projection port of a C library, this tree is not
repetitive.

What is genuinely worth consolidating, split by whether it can move a bit.

### Safe — no floating-point arithmetic involved

| What | Where | Scale |
|---|---|---|
| Two complete JSON parsers | `pipeline/PipelineJson.java` (415 lines) vs `io/projjson/Json.java` (406). Structurally identical recursive descent over the same grammar; `PipelineJson:234-251` ≡ `Json:92-109` and `PipelineJson:334-360` ≡ `Json:196-222` verbatim. Real differences are only the thrown exception type, the root depth base, and a writer on one side. | ~400 lines |
| Pipeline unit plumbing | The same 11-line `declaredLeft`/`declaredRight`/`overrideUnits` block plus paired fields in `AffineOperator`, `AxisSwapOperator`, `Cs2csOperator`, `PushPopOperator`, `UnitConvertOperator`, `SetOperator` | ~100 lines |
| `Registry` descriptions | 122 of 154 `register(name, cls, description)` calls pass a description byte-identical to the class's own `toString()`. 23 differ — `aeqd` is registered as "Azimuthal Equidistant" while its `toString()` says "Equidistant Azimuthal" — which is exactly the manual-sync failure the pattern invites. | 122 strings |
| Duplicated constants | 33 files declare a `1e-10` under seven different names; 14 declare `Math.PI/2.0` because `Projection.HALF_PI` at `proj/Projection.java:274` is **private**; 20 declare `1e-7`. A literal is a literal and `Math.PI/2.0` is exact, so these round identically wherever they sit. | ~120 declarations |
| Convergence-failure message | The identical message string is built in seven projection classes. Note the text is asserted in tests, so a shared formatter must reproduce it exactly, including the space before `rad`. | 7 copies |
| `lon1`/`lat1`/`lon2`/`lat2` accessors | `TwoPointEquidistantProjection.java:100-137` vs `ObliqueCylindricalEqualAreaProjection.java:88-125` — byte-identical apart from one Javadoc word. Blocked only by unrelated superclasses. | ~40 lines |
| `ProjectionMath.distance` → `MathHelpers.norm2` | Byte-identical bodies (`Math.sqrt(a*a + b*b)`). | 1 method |
| Dead utilities | `util/ProjectionMath.java:546` and `:550 sameSigns`, `:554` and `:561 takeSign` — zero main callers. Four overloads, not the three an earlier count gave; the `int` overload of `sameSigns` was missed. | 4 methods |

### Not safe — reported as candidates and deliberately not changed

- **`toRad` vs `degToRad`** — finding 8. A demonstrated 1-ULP difference at a live call site.
- **Six hand-rolled `aasin` clamps** at `AdamsProjection.java:294`,
  `GeneralSinusoidalSeriesProjection.java:206`, `PutninsP6FamilyProjection.java:159`,
  `Urmaev5Projection.java:143`, `MolleweideProjection.java:161`, plus
  `OrthographicAzimuthalProjection.java:299` as `clampAsin`. A shared `ProjectionMath.asinChecked`
  already exists, but it calls `Math.asin` while several clones call `StrictMath.asin` — a
  math-backend difference, not a formatting one, since `Math.asin` is a HotSpot intrinsic whose last
  bit differs between x86-64 and AArch64. The correct consolidation is **two** helpers, not one, and
  `clampAsin` has no tolerance band at all so it cannot merge into either.
- **The Mollweide Newton loop** in `BoggsProjection`, `HatanoProjection`, `MolleweideProjection`,
  `NellProjection` — same step `(θ + sin θ − c)/(1 + cos θ)`, but Hatano damps the correction and
  Boggs forms `c` differently upstream. Extractable bit-for-bit only if the helper preserves each
  call site's exact token order, at which point it is barely shorter.
- **Four parallel linear-unit tables** — `units/Units.java:130-137`, `pipeline/PipelineUnits.java:58-79`,
  `io/wkt/WktNames.java:88-121`, `io/projjson/UnitDefinition.java:34-64`. The divergence is
  load-bearing: `PipelineUnits` uses `1200/3937.0` for the US survey foot while `Units` uses
  `0.304800609601219`, and `PipelineUnits`' own Javadoc **documents that 1e-16 difference as
  intentional PROJ fidelity**. Merging forces a choice. The ids and display names are pure strings
  and can be shared; the factors cannot.

### Looks redundant, is not

`units/DegreeUnit.java:39-53` overrides three `format` methods with bodies character-for-character
identical to `units/Unit.java:89-103`. They are not dead. `Unit.java:41` declares `public static
final NumberFormat format`; `DegreeUnit.java:23` declares `private static AngleFormat format = new
AngleFormat(AngleFormat.ddmmssPattern, true)` — a **static field hide**, resolved at compile time by
declaring class. The identical bodies call two different formatters. Deleting the overrides silently
switches degree output from degrees-minutes-seconds to a two-decimal number.

CPD flags this pair. It is the one CPD hit that must be left alone.

## Test sufficiency

Core: **79.7% of instructions and 67.8% of branches**, over 414 classes and 2320 tests. That is a
respectable number for a library of this kind and it is not evenly spread.

Ten classes have **zero** instruction coverage:

```
   81 instr  spi.DbConversion            29 instr  proj.NullProjection
   72 instr  spi.DbParam                 20 instr  util.ProjectionUtil
   50 instr  spi.DbCelestialBody         14 instr  util.CRSCache$1
                                          7 instr  io.projjson.JsonLimits
                                          7 instr  io.wkt.WktLimits
                                          4 instr  geodesic.GeographicErr
                                          3 instr  geodesic.GeodesicMask
```

The three `spi.Db*` records are the largest, and they are the data carriers between the database
provider and the library — worth a test each. `util.ProjectionUtil` at zero corroborates the
reachability result above.

Weakest packages, by instruction coverage, over 400 instructions:

```
  61.6%    778/1263    units
  62.2%   1418/2281    spi
  65.1%   2727/4189    io.projjson
  70.9%   3161/4458    datum.tiff
  75.7%   4567/6030    geodesic
  75.8%   1589/2097    resource
  76.9%  30591/39759   proj
```

`io.projjson` at 65% is the notable one: it is undocumented in the README (see below) *and*
under-tested, so the ProjJSON reader and writer are the least-defended public surface in the tree.
`datum.tiff` at 71% is a binary parser handling untrusted input, which argues for more than average
coverage rather than less.

Lowest-covered individual classes with 300 or more instructions:

```
  12.0%   585 missed  pipeline.DeformationOperator
  23.3%   289 missed  units.AngleFormat
  31.1%   363 missed  proj.GeostationarySatelliteProjection
  34.1%   359 missed  proj.GnomonicAzimuthalProjection
  37.7%   294 missed  proj.VanDerGrintenProjection
  48.2%   655 missed  geodesic.GeodesicLine
  50.8%   209 missed  ProjCoordinate
  58.7%   722 missed  io.projjson.ProjJsonReader
```

`pipeline.DeformationOperator` at 12% is the single largest gap, and `ProjCoordinate` at 50.8% is
the most surprising one — it is the type every single user touches.

### The portability gap in the test setup

Covered as finding 7: `core/pom.xml` configures no surefire plugin, so core's suite inherits the
developer's locale, timezone and charset while `conformance/pom.xml:120` pins all four with a
comment explaining why. Pinning core's the same way is a build change with no effect on output, and
it would make 57 locale-sensitive call sites deterministic.

### The one skipped test

`core/src/test/java/org/locationtech/proj4j/Proj4VariousTest.java:203` — the only `@Ignore` in the
repository, on `testPconic()`. **Its stated reason is stale.** The comment says
"SimpleConicProjection hard-codes p1=30/p2=60 and ignores +lat_1/+lat_2," but
`proj/SimpleConicProjection.java:177-178` now reads

```java
double p1 = projectionLatitude1;
double p2 = projectionLatitude2;
```

and the class Javadoc describes the hard-coding in the past tense. The same file has two
commented-out `//checkTransform` calls at `:222` and `:229`. The test should be re-enabled or
deleted, not left ambiguous.

### Not dead, despite appearances

`core/src/test/java/org/locationtech/proj4j/builtins2/Decompose.java.txt` — a 13KB Java file parked
with a `.txt` extension so it never compiles. An earlier draft of this review listed it as dead
code. That was wrong: its own header explains the extension, because it compiles against the
**conformance** module's test classes and core is a dependency *of* conformance, not the reverse, so
it cannot be a `.java` in core without a dependency cycle. It has a `main()` and no assertions.

The dependency argument was sound and the conclusion drawn from it was not. The file did not have to
be parked; it had to be **in the other module**. In 2.0.1 it moved to
`conformance/src/test/java/org/locationtech/proj4j/conformance/tools/Decompose.java`, where the
classes it calls are on the compile path and it builds with everything else. Surefire does not pick
it up — no `@Test`, and the class name matches none of the default include patterns — so it stays a
hand-run tool, but a compiler now checks it against the APIs it calls instead of nobody checking it
at all.

Two other things went wrong in the same file and were fixed by the move. Its build recipe could
never have worked as written: **its first 39 lines were raw English prose, uncommented**, so the
`javac` invocation it documented would have failed on line 1. And the recipe was a transcript of one
machine — a hardcoded `/Library/Java/JavaVirtualMachines/temurin-21.jdk` `JAVA_HOME`, an
`/opt/homebrew/bin` `PATH`, a `-Dmaven.repo.local=/tmp/m2-btail`, an absolute `cd` into the
checkout. All of that is gone; the prose is class Javadoc.

Its header also contained a stale number: it quoted a "7,845-assertion corpus" while
`gie-corpus-index.tsv` holds 7,931 and `conformance/pom.xml:127` says 8,017 — three numbers in three
places. The figure was dropped rather than replaced, since a fourth guess would not help. Reconciling
the remaining three is a documentation item.

## Documentation

### Machine-specific content

The brief was that documentation should read as general-purpose instructions for any contributor,
not as a record of one machine. Several places fail that test.

**A hardcoded JDK path**, presented as the copy-paste setup line rather than as an example, in
`benchmark/README.md:17`, `golden/README.md:24`, `benchmark-ab/RESULTS.md:170`, and
`core/src/test/java/.../builtins2/Decompose.java.txt:17`.

**Hardcoded Homebrew paths** in `db/README.md:151`, `db/src/gen/dump.sh:23`, `golden/README.md:25`,
and — more consequentially — as the **default property value** at `db/pom.xml:71-72`. Also in six
test sources.

**"This machine" as the documented environment**, including the exact hardware SKU:
`docker/README.md:201,250,314,338`, `docker/run.sh:254`, `benchmark-ab/RESULTS.md:36` ("macOS 26.6
on an Apple M5 Max (18 cores). This is a laptop"), `golden/pom.xml:72`,
`.github/workflows/bench.yaml:80`, and `.github/workflows/README.md:226`.

**A corporate TLS-intercepting-firewall accommodation, documented as a repository feature** —
`docker/README.md:297-310` is an entire section headed "The corporate-CA accommodation — the machine
this was written on sits behind a TLS-intercepting firewall," and `docker/run.sh:152` repeats it.
This is a property of one network baked into shared build tooling.

**aarch64 presented as the reference platform** — `docker/README.md:256` ("the allocation baseline
was captured on aarch64/macOS"), `benchmark/README.md:456,464`, `RELEASE-NOTES.md:359,640`,
`CHANGELOG.md:335`. The committed `benchmark/src/main/resources/baseline/allocation-baseline.json`
is therefore an artefact of one architecture; the docs say so but do not make it reproducible
elsewhere.

**`~/.m2/settings.xml` assumed to be set up a particular way** — `HOWTORELEASE.txt:78,189-215`
requires a `<server id="central">` with a Portal token in the user's `settings.xml` and offers no
`-s` alternative.

Cleanly negative, and worth stating: **no `/Users/...` path, no email address, no GPG fingerprint or
key ID, no token, and no employer or company name appears in any tracked file.** `HOWTORELEASE.txt`
uses `you@example.com` as a placeholder and a `$FPR` shell variable throughout. The `<developers>`
block in `pom.xml:74-77` and the upstream-contributor entries are Apache-2.0 §4(c) attribution and
POM convention, not leaks.

### Documented behaviour that no longer matches the code

**The `tr_TR` locale defect is fixed, and `.github/workflows/ci.yaml:363-370` still says it is not.**
The comment reads "EXPECTED TO FAIL TODAY. Defect: … `Proj4FileReader.java:41` calls
`authorityCode.toLowerCase()` with no Locale … Delete `continue-on-error` from this leg the moment
the `toLowerCase(Locale.ROOT)` fix lands." That fix landed:
`io/Proj4FileReader.java:76` reads `return authorityCode.toLowerCase(Locale.ROOT);`. Line 41 of that
file is inside a Javadoc block, so even the citation is wrong. The leg is still
`continue-on-error`, so a genuinely fixed gate is still non-blocking. Mirrored in
`.github/workflows/README.md:54,113`.

**The MetaCRSTest expected-failure claim is stale in six places.** `ci` is green — 2320 tests, 0
failures. `docker/run.sh:17,23,27-30` still says otherwise in its header, though the script itself
re-derives the verdict per run and correctly printed "ci is GREEN. It was expected to fail…". The
same false premise appears unqualified at `.github/workflows/README.md:436`,
`.github/workflows/golden.yaml:130`, and `.github/workflows/bench.yaml:115`. In each workflow case
the mitigation is still sensible; the stated justification is not.

**`bench.yaml:21-29` says the gate is red because the baseline is uncaptured. It has been
captured.** The header claims "every one of the 158 entries in op-counts.json is TBD" and "187
unpinned thresholds"; `op-counts.json` contains **zero** TBD entries and
`allocation-baseline.json` has 25 rules with **two**. This directly contradicts
`.github/workflows/README.md:63`, which says the gate is "green today, 245 gated, 0 EXCLUDED." The
tree supports the README.

**`CHANGELOG.md:11-16` says the `conus` grid fold has not happened.** It has —
`epsg/src/main/resources/proj4/nad/conus` exists at 264,424 bytes. `README.md:32,59` and
`grids-us-legacy/README.md:6-24` correctly describe it as done.

**Two module READMEs say their module is not in the reactor. Both are.**
`grids-us-legacy/README.md:83-95` has a section headed "Not in the reactor" with instructions to add
it, and the suggested `<modules>` list also omits `db`. `db/README.md:202-212` says the same about
`db`. The root POM lists `core, epsg, geoapi, grids-us-legacy, db, conformance`.

**`.mvn/jgitver.config.xml`'s comment says "`main` is this repository's default branch."** The
default branch is `master` — `refs/remotes/origin/HEAD` points at `origin/master`. The
`nonQualifierBranches` value is `main,master`, so both are covered and there is **no live
misconfiguration**; the comment alone is wrong.

**`pom.xml:6-9` carries an upstream header comment that is now false**: "Version is set here
explicitly, since Jenkins doesn't work with the jgitver plugin," with a `-Drevision` example and a
link to an upstream issue. This fork uses jgitver, there is no Jenkins, and the documented override
is `-Djgitver.skip=true` (`HOWTORELEASE.txt:41`).

**`benchmark-ab/RESULTS.md`** cites the fork at `1.4.4-develop-SNAPSHOT` (`:10`) when 2.0.0 is
released; cites `Registry.java:448,499` for the `tmerc`/`utm` registrations when they are at `:478`
and `:529`; and its repro instructions at `:173-177` are broken — they copy the jars to `/tmp/` and
then invoke them without the path prefix.

### Contradictions between documents

Beyond those already listed: golden's row count is given as 53,430 everywhere and 53,431 twice at
`docker/README.md:192` (53,430 is right — the baseline file has 53,431 *lines*, one of them a
header); rules pinned is 41/41 at `docker/README.md:165-166` and 42/42 at three other places (42 is
right); the `bench` runtime is "~16 min" at `docker/run.sh:21,75,750` and 21m23s everywhere else;
`.github/workflows/README.md:6-12` retracts a claim that `:226` still makes 214 lines later;
`docker/README.md:22` gives the default-check runtime as 1m56s warm against `:324`'s re-measured
0m46s; and the db module's authority list is five items in `README.md:86` and six in
`db/README.md:3`.

### Dangling references

**The `reference/` directory did not exist**, and 42 references across roughly 29 files pointed into
it: `reference/performance.md` (27), `reference/numerics.md` (13), `reference/gie-comparator.md`
(1), `reference/param-semantics.md` (1). They are concentrated in `benchmark/README.md` (10 sites),
the benchmark sources' Javadoc, `benchmark/pom.xml:107`, `conformance/pom.xml`,
`.github/workflows/bench.yaml`, both baseline JSON files, and — published to users —
`core/src/main/java/.../util/CRSCache.java` and
`core/src/main/java/.../BasicCoordinateTransform.java`.

This document is the first file in that directory. It does not discharge any of the 42 references,
all of which point at other filenames.

Also dangling: `docker/README.md:52` cites `epsg/src/main/resources/proj4/proj4-epsg.csv` as
MetaCRSTest's input, which actually lives at `core/src/test/resources/proj4-epsg.csv`;
`RELEASE-NOTES.md:715` cites `conformance/NOTICE-gie.md`, which is at
`conformance/src/test/resources/NOTICE-gie.md`.

A systematic scan of every `[text](path)` link in every tracked Markdown file found **zero broken
links**. Every dangling reference above is prose or a `{@code}` span, never a hyperlink.

### The public surface a reader cannot discover

`README.md` is 201 lines, and its Basic Usage section shows only the 1.4.3-era
`CRSFactory`/`CoordinateTransformFactory`/`ProjCoordinate` path. It never mentions the modern
`org.locationtech.proj4j.api` facade — `Proj`, `Crs`, `CrsOperation`, `ProjContext`,
`LegacyAdapters` — which is where most of the fork's new capability is actually reachable.

| Subsystem | Files | README coverage |
|---|---|---|
| `pipeline/` | 28 | One clause at `README.md:21`. No API, no example, no entry point named. |
| `io/wkt/` | 23 | **None.** `Proj.createCrsFromWkt`, `Crs.toWkt(WktDialect)`, `WktDialect.guess` are all public. |
| `vertical/` | 7 | **None.** `CRSFactory.createCompound(String)` is undiscoverable. |
| `io/projjson/` | 6 | **None.** `Proj.createCrsFromProjJson`, `Crs.toProjJson`. |
| `bulk/` | 1 | **None.** `BulkCoordinateTransform` exposes the array API — the natural entry point for any high-throughput user — and is invisible. |

A user reading only `README.md` would conclude this is upstream Proj4J with better projection
coverage. `RELEASE-NOTES.md` is the only document that covers any of it, and it is a 48KB release
document, not a user guide.

Ten packages have no `package-info.java`: `bulk`, `datum`, `geodesic`, `gie`, `io`, `parser`,
`proj`, `resource`, `units`, `util`. `datum` and `proj` are the two largest and most user-visible.
(`pipeline`, `vertical`, `io/projjson`, `io/wkt` and `api` do have one.)

Positive result: **every public class in the top-level `org.locationtech.proj4j` package has
class-level Javadoc.** No gaps.

### Documentation that duplicates documentation, and will drift

The local-install instructions at `README.md:176-185` are a verbatim copy of
`HOWTORELEASE.txt:254-264`. The JDK-version caveat at `README.md:171-172` duplicates
`HOWTORELEASE.txt:270-276` word for word. `docker/run.sh`'s "TRAPS THIS SCRIPT IS WRITTEN AGAINST"
header is re-narrated at `docker/README.md:204-230` — and that pair **has already drifted**, since
the README documents the script's own header as wrong. The determinism JDK matrix appears three
times with identical rows. The now-false MetaCRSTest rationale is duplicated across five files. And
`README.md:46-125` hardcodes `2.0.0` in seven places, so every release needs seven edits.

### Javadoc quality

Essentially clean. `-Ddoclint=all` produces 14 warnings across the whole tree. Independent sweeps
for empty Javadoc, `@param` naming a vanished parameter, unresolvable `{@link}` targets, and
`@throws` for exceptions no longer thrown all came back at **zero genuine problems** — the 141
`@throws` candidates are false positives because `Proj4jException extends RuntimeException`, so its
whole subtree is unchecked and correctly documented without a `throws` clause.

The one real problem is the `{@code reference/…}` paths above, which are published-Javadoc pointers
to nothing.

Thirteen sites render `TODO`/`FIXME` into user-facing Javadoc. Twelve are legitimate historical
narrative describing an upstream defect this fork *fixed* — for example
`proj/LoximuthalProjection.java:47` explains that `+lat_1` **used to be** hard-coded to 40°. The one
live marker is `geoapi/src/main/java/.../AbstractCRS.java:76`: "TODO: there is a possibility that the
PROJ4J 'projection' is actually for something…" — an unresolved question inside shipped code.

## Deliberately out of scope

Recording the `Math`-versus-`StrictMath` limitation — 738 calls to seven platform-variant `Math`
methods across 76 classes, 49 of them hidden in `KrovakProjection` behind `import static
java.lang.Math.*` — is a known pending item with its own scope. Changing `Math` to `StrictMath`
anywhere moves bits by design, which contradicts this review's change bar, so the item is reported
and not started.

## What happens next

The changes fall into four groups that touch disjoint files, so they can land as independent pull
requests after this document.

1. **Wrong answers.** Findings 1, 2, 4 and 5 — the two broken ellipsoids, the two unreachable ones,
   `+proj=rpoly`, and the LAEA constructor. **These change output by design**, so each needs its own
   golden-report justification and none can ride along with a bit-identity-constrained change.
2. **Dead code and dead ends.** The eleven dead private members, the four commented-out blocks that
   are not load-bearing, `ModStereoProjection.java`, and a decision on
   `ContradictoryParameterException`, `util.ProjectionUtil` and `LinearProjection` — all three of
   which are exported, so removal is an API break and the alternative is to document or wire them up.
   The decision taken was to keep all three and say why in the Javadoc: the exception is
   `@Deprecated` as never thrown, and the other two are documented in place. `Projection.geocentric`
   — a `protected` field nothing assigns and nothing reads — is `@Deprecated` on the same grounds.
   `Decompose.java.txt` moved into the conformance module and compiles.
3. **Redundancy.** The safe table above, and nothing from the unsafe one.
4. **Tests and documentation.** Pin core's surefire locale/timezone/charset; cover the three
   zero-coverage `spi.Db*` records, `pipeline.DeformationOperator` and `io.projjson`; resolve the
   stale `@Ignore` on `testPconic`; and fix the stale claims, the machine-specific content and the
   dangling references catalogued above.

Every group is verified the same way, against the baseline in this document:

```bash
./docker/run.sh ci conformance golden determinism
java -jar japicmp.jar -o neoproj4j-2.0.0.jar -n new.jar --ignore-missing-classes --semantic-versioning
```

The pass criteria are: the golden report unchanged (not empty); `determinism` still 22 tests and
still finding `Math` diverging on at least one leg, which is the non-vacuity check that proves the
suite measures anything; `ci` green at 2320; `conformance` at 7441/7900 with 0 regressed; and
japicmp reporting no change for groups 2 through 4.
