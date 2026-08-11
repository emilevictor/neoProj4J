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

#### 9. `+proj=geos` reports "numerical failure" for a point that is simply behind the globe

Both forward arms of `proj/GeostationarySatelliteProjection.java` wrote `out.x = out.y = NaN` and
returned when the visibility test failed. `Projection.projectRadians`' finiteness postcondition
(`Projection.java:473-478`) then turned that into `ProjectionException(NUMERICAL_FAILURE)`, so the
library did fail closed — but it named the wrong reason. Nothing failed numerically. The point is
outside the projection's domain, which is what upstream reports
(`PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN`, `geos.cpp:99`).

**Resolved in 2.0.1: both arms throw `COORDINATE_OUT_OF_DOMAIN` directly**, with a message naming the
longitude, the latitude and the orbit height it refused for. This is a cause reclassification, not a
new throw — the call already failed, one frame up, under a different name.

Two details are worth recording. First, `project_e` overwrites its `lpphi` parameter with the
geocentric latitude before the check runs, so the message saves the caller's value first; reporting
the overwritten one would name a latitude about 0.19° from anything the caller supplied. Second,
**the spherical check is a deliberate divergence from PROJ 9.8.1 and stays.** Upstream's
`geos_s_forward` has a `/* Check visibility*/` comment with no body (`geos.cpp:65`); the check was
dropped by `dbba67bd` ("Converted geos. Expanded tabs.", 2016-04-18), a tab-expansion pass that left
the ellipsoidal equivalent intact in the same diff, and has not been restored in the ten years since.
Behind the globe upstream's two `atan` calls both return finite numbers, so `+proj=geos +R=…` answers
an invisible point with a plausible, wrong coordinate. No gie assertion can cover a divergence in
either direction, so the pin is a unit test:
`core/src/test/java/org/locationtech/proj4j/domain/GeostationaryVisibilityTest.java`.

#### 10. `+gamma` and `+no_uoff` were dispatched through the base class, to no effect

`parser/Proj4Parser.java` read `+gamma`, `+no_uoff` and `+no_off` unconditionally and pushed them
into `Projection.setGamma` / `setGammaDegrees` / `setNoUoff` — base methods with empty bodies,
overridden only by `ObliqueMercatorProjection`. So on any other projection the value was parsed,
dispatched, and discarded.

**Resolved in 2.0.1: the dispatch moved inside the existing `instanceof ObliqueMercatorProjection`
block**, which is the set of classes that read the keys, and — per `omerc.cpp:137` and `:140-144` —
also exactly the set of PROJ 9.8.1 operators that read them. `somerc.cpp` calls `pj_param` zero
times. The three base methods are **deprecated, not deleted**: `proj` is an exported package with no
`Export-Package`/`Private-Package` directive in `core/pom.xml`, so removing a public method is a
binary break. `ObliqueMercatorProjection` gains a `setGammaDegrees` override so the parser's call on
the concrete reference does not land on a deprecated method.

One off-corpus behaviour change, and it moves toward PROJ: `+proj=merc +gamma=nonsense` used to be
rejected, because `parseAngle` ran unconditionally, and is now accepted and ignored. PROJ does the
same — `src/init.cpp` has no validation pass, and the `used` bit `pj_param` sets (`src/param.cpp:99`,
`:172`) has exactly one consumer, `pj_get_def` at `src/pr_list.cpp:74-76`. There is in-tree precedent
already asserting this shape: `OperatorScopedParameterTest.aziAndRotAreIgnoredOnEveryOtherProjection`.

#### 11. `+proj=leac +south` was refused, though `leac` reads it

`Projection.setSouthernHemisphere` throws `UnsupportedParameterException(PROJECTION_NOT_IMPLEMENTED)`
unless a subclass overrides it, and `LambertEqualAreaConicProjection` did not — although it carries
its own `setSouth`/`isSouth` pair and `aea.cpp:223` reads `bsouth` for `+proj=leac`. So this was an
over-refusal, not a silently-wrong number: the transform raised rather than answering.

**Resolved in 2.0.1**: the class overrides `setSouthernHemisphere`/`getSouthernHemisphere` and
forwards to its own pair. Same family as the `nsper` `+h` case, and pinned the same inverted way, in
`ProjectionParameterRefusalTest.southIsNowHONOUREDOnLambertEqualAreaConic`. The sibling refusals stay
— `southIsRefusedOnAlbers` and `southIsRefusedOnLongLat` still hold, because `aea.cpp` reads `bsouth`
only in the `leac` entry point.

Three stale documentation claims fell out of tracing this. `Projection.setSouthernHemisphere`'s
Javadoc named two overriding classes where there are four. Two test Javadocs called `+alpha`,
`+lonc`, `+gamma` and `+pm` "a known defect" for using `parseDouble`; all four go through
`parseAngle` (`Proj4Parser.java:181`, `:185`, `:616`, `:821`). And `Proj4jCapabilities`' `HONOURED`
comment said the `Projection` base "carries" `gamma`, `no_uoff`, `h` and `south`, none of which was
true. All three are corrected.

**Blast radius of findings 9-11: zero rows in every corpus**, measured per corpus rather than
argued. `golden/probes.tsv` has 60 `gamma`/`no_uoff` rows across six non-omerc hosts and all 60 are
bit-identical — the value formerly reached an empty method, and is now never fetched, so projection
state is byte-for-byte what it was. `golden/pairs.tsv`: 0. The five `proj4/nad/` dictionaries carry
`+gamma`/`+no_uoff` only on `omerc`, and `+proj=leac` not at all. `leac` has 5 canonical probe rows
and is not one of the six modifier-sweep hosts. The one GIGS `somerc +no_uoff +gamma` case is
quarantined as `*.gie.failing`. **So no golden rule was written for this change** — an `active` rule
matching zero rows fails the build as `DEAD_RULE`, which is the correct outcome for a change that
moves nothing.

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

### What was actually consolidated

Seven changes were made. Every one is confined to non-numeric code, adds no public or protected
member, and carries a test that fails if it is undone. Sizes are measured, not estimated.

| Change | Duplication removed | Guard added |
|---|---|---|
| `Registry.ellipsoids` now references the 49 `Ellipsoid` constants instead of re-declaring 27 of them. **The array's order is unchanged**, which matters because ties are broken by position — see below. | 27 objects → 0 | `EllipsoidTableAgreementTest`, 6 tests |
| Six pipeline operators — `Affine`, `AxisSwap`, `Cs2cs`, `PushPop`, `Set`, `UnitConvert` — now extend a new package-private `OverridableUnitsOperator` (73 lines) holding the `declaredLeft`/`declaredRight`/`overrideUnits` block they each carried. | ~100 lines | `OverridableUnitsOperatorTest`, 8 tests |
| `geoapi`'s `TransformWrapper2D`/`3D` array methods share a private `transform(ProjCoordinate, ProjCoordinate)` per class instead of repeating the try/catch five and four times. | 45 lines, net −17 | `TransformArrayOverloadsTest`, 6 tests |
| `api` and `io.wkt`: one parameter-matching rule in `Crs` replaces `Proj.hasKey`, `LegacyAdapters.hasAxis` and two inline copies; `DatabaseCrsFactory` shares its refusal guards; `OperationSelector` shares its message prefix and accuracy text; `WktNode.find`/`findAll` and `WktWriter.unit`/`unitWithoutId` each share a helper. | ~66 lines, net +7 | `ParameterMatchingTest`, 3 tests |
| `Proj4FileReader`'s two streaming dictionary lookups share one `scan` method. | ~14 lines | covered by existing `io` tests |
| `Resources.readFully` delegates to `readAtMost` rather than repeating the loop. | 9 lines | `ResourcesReadTest`, 8 tests |
| `db`: `PjdxFile` shares one `stringBytes`; `PjdxDatabase` shares `crsEntry`, `rowByAuthCode` (six accessors) and `objectPrefix` (five methods); `GenerateIndex` shares four emitters, including one that covers both `grid_transformation` and `other_transformation`; `VerifyIndex` shares four comparers. Nothing is shared *between* generator and verifier — see below. | net −45 lines | `PjdxSupersessionTest`, 6 tests |

The net line count barely moves — 757 inserted against 707 deleted across 24 files — because most of
what replaces the duplication is the Javadoc explaining why the surviving copy is the only one. That
is the intended trade. The point of this work is not fewer lines; it is that a change to a rule now
has one place to be made, and a test that notices when it is made in only one of two.

Two of these are worth more than their line counts. The ellipsoid one removes a genuine drift
hazard: the two tables had already drifted, and the comment preserved in `Registry` records what it
cost — `+ellps=NWL9D` and `+ellps=andrae` were re-declared with the inverse flattening in the pole
radius slot, which selects a different branch of the constructor and takes 298.25 metres literally,
giving an eccentricity of 0.999999998906693 against GRS80's 0.0818. Every transform through either
was computed on a near-flat disc. The `api` one collapses four independently-written spellings of
"does this parameter list mention `+axis=`" into the rule they were all approximating.

The new tests are pinned to the mechanism, not to a convenient string. `EllipsoidTableAgreementTest`
asserts **reference identity** rather than equal fields, because comparing fields would pass for a
freshly written correct literal and so would not stop the next one being written wrongly. It also
pins the four ties that `WktNames.projEllipsoidCode` decides by array position — `clrk80`/`NAD27`,
`WGS66`/`NWL9D`, `GRS80`/`NAD83`, `australian`/`aust_SA` — which had no coverage anywhere, and it
asserts each pair is still genuinely tied before asserting the order, so the test cannot quietly
become vacuous. Non-vacuity was checked rather than assumed: compiled against the pre-change
`Registry` and exactly one test fails, naming all 27 copies.

`DegreeUnitFormatTest` (5 tests) was added for the CPD hit in the section above, which had no test at
all. It now fails if the "redundant" overrides are deleted.

Forty-two tests were added in total, and the three touched modules pass: **core 1953, `db` 58,
`geoapi` 12**. Two checks are worth naming because they are stronger than a green suite. The `db`
work was verified by regenerating the index from the same `proj.db` dump with the old and new
generators and diffing the output: byte-identical, SHA-256
`8a82064783a07132f31e42ffe51cdc2bbb48c3835a01ab015485dfe7a456d389`, which is the value already pinned
in `db/pom.xml` and the hash of the checked-in artifact. `VerifyIndex` reports the same **486,491**
field comparisons before and after, so no assertion was silently dropped. (That 486,491 was
re-measured during the test-sufficiency work rather than carried forward on trust — see below, where
the verifier gains eleven field checks and the count becomes 502,422.) And `javap` over every
non-`gen` class in `db` is identical either side of the change — the only class-level difference
anywhere is one fewer anonymous class in `GenerateIndex`, which `db/pom.xml:120` excludes from the
jar.

One near-miss in that work is worth recording, because it is the shape of mistake this kind of
refactor invites. The first version of `emitNameAndMethod` took the resolved method name as an
argument: `emitNameAndMethod(e, t, row, resolvedMethodName(t, row, methodNames))`. Java evaluates
arguments left to right, so that reads `method_auth_name` *before* `name` — and
`QuoteDump.Table.text` throws naming the first missing column. The output would have been identical
today and the *error message* would have named a different column under a future upstream schema
change. It was restructured so the helper does its own lookup and the read order matches both
originals.

### The candidates, and why each was left

- **The two JSON parsers.** Reported and not merged; `PipelineJson`'s own Javadoc now carries the
  reasoning, replacing an earlier claim that visibility was the only obstacle. There are three, and
  two are not plumbing. The two report failures with unrelated exception types
  (`PipelineDefinitionException`, a subclass of `InvalidValueException`, against 19
  `WktParseException` sites, which descends directly from `Proj4jException`), so a shared parser
  needs a failure factory threaded through every throw or a corrupt `+proj=tinshift` model starts
  reporting a WKT parse error. And because core's bundle declares no `Export-Package`, there is
  nowhere to put shared code that is not published API — so the merge means publishing a JSON parser
  as permanently supported surface, in a library whose advantage is having no JSON dependency. The
  overlap is also smaller than 415 + 406 lines suggests: half of `Json` is a writer, and a third of
  `PipelineJson` is typed accessors.
- **The `TransformWrapper2D`/`3D` array methods themselves.** After the helper extraction the residue
  is the four `{double,float} × {double,float}` combinations, and Java 8 cannot abstract over
  primitive arrays. Collapsing them needs `Object` plus per-point casts in the hottest loop in the
  module, at exactly the float↔double conversion points that are worth protecting. Their nine
  cross-file clusters have no home either: the only sensible one is the shared supertype, and a
  package-private static in `2D` called from `3D` would make 3D depend on 2D, which is worse than the
  duplication.
- **`WktMethods.java:265` and `:276`.** These read as near-identical and are not: `P_OMERC_A` ends
  with EPSG 8806/8807, "False easting"/"False northing", while `P_OMERC_B` ends with 8816/8817,
  "Easting/Northing at projection centre". Merging would silently swap EPSG parameter codes on every
  Hotine oblique Mercator variant. `P_MERCATOR_B` and `P_EQC` are likewise textually identical but
  belong to two different methods, and keeping them apart is what lets either change alone.
- **The Landsat / Space Oblique Mercator family.** Do not merge — and the reason is a live trap
  rather than a preference. `SpaceObliqueMercatorProjection.java:317`/`:375` call `Math.log`/`Math.exp`
  where `LandsatProjection.java:176`/`:237` call `StrictMath`, so `+proj=som` and `+proj=misrsom`
  output is architecture-dependent where `+proj=lsat`'s is not. Worse, SOM's own Javadoc at
  `:54-67` lists "two differences to reconcile" when there are six, and two of the four it omits move
  bits: the iteration bound is `l > 0` against `l >= 0`, and Landsat applies `adjlon` on the inverse,
  worth about 6 nanometres. Executing that Javadoc as written would re-pin `+proj=lsat` output.
- **Three CPD clusters whose named constants differ.** `FXC` is 0.85 in `HatanoProjection` and
  0.31245971410378249250 in `McBrydeThomasFlatPolarQuarticProjection`; `CTABLEV2` is
  `LITTLE_ENDIAN` where `NTV1` is `BIG_ENDIAN`; and `GeoTiffGrid.boundingTable` omits the
  `GridExtents.checkedAxis` guard that NTv2 applies. Token-identical is not value-identical.
- **Anything shared between `db`'s generator and its verifier.** CPD's two cross-file pairs are real:
  `GenerateIndex`'s parameter loop and method-name resolution agree line for line with
  `VerifyIndex`'s, and the two `nameMap` methods are the same six lines. They were consolidated
  *within* each file and deliberately not across, because a verifier that runs the generator's code
  cannot catch the generator's mistakes. Merging them would delete the only independent
  re-derivation the build has. `VerifyIndex.nameMap`'s Javadoc now says so, so the next reader finds
  the reason rather than the temptation. The two differing failure messages — `" missing from the
  index"` against `" missing"` — are passed in from the call sites verbatim, because they are what
  somebody reads in a failing build.
- **`Crs`'s two constructors**, `toProjString`/`toProjParameterString`, and
  `containsIgnoreCase`/`indexOfIgnoreCase`. The first would need a ten-argument canonical constructor
  decoded positionally at every read; the other two are cross-package, so sharing them publishes new
  API for no behavioural gain.
- Everything already listed under "Not safe" above stands unchanged.

### Defects found while consolidating, and deliberately not fixed here

None of these are duplication, so none belong in a bit-identity-constrained change. They are recorded
for the wrong-answers group.

- **`PrimeMeridian.forName` fails open.** `+pm=pari` silently becomes Greenwich — a 260 km error —
  where PROJ refuses at `src/init.cpp:774-778`. Its own pull request, after this one, so this one's
  bit-identity claim stays clean.
- **`Units.DEGREES.format(-0.5)`** returns `"0d30 deg"`, losing the sign whenever the whole-degree
  part is zero.
- **`Angle.parse("123d")`** returns 123.0 where `AngleFormat.parse("123d")` throws
  `NumberFormatException`.
- **`InitFileCache` retains failed loads forever**, unaccounted and unevictable — the exact hole
  `GridCache`'s Javadoc claims to have closed — and charges phantom bytes on interrupt, on a path
  that then does more I/O rather than less.
- **Two `api` routes to the same request disagree.** `fromLegacy(crs, AUTHORITY).axisOrder()` gives
  `"enu"` while `fromLegacy(crs, null).withAxisOrderPolicy(AUTHORITY).axisOrder()` gives `"neu"`,
  because the second has already recorded the source as `PROJ_STRING`. Separately,
  `DatabaseCrsFactory` computes `axisAuthoritative` before the LEGACY branch clears `axis`, so a
  database CRS under LEGACY reports its axis order as authoritative while `enu` is in force —
  disclosed by `axisOrderNote()`, but still wrong. Both pre-existing.
- **The three entry points report empty input differently.** `createCrs("")`,
  `createCrsFromWkt("")` and `createCrsFromProjJson("")` agree on the `ErrorCause` and give three
  different messages, because only the first checks for emptiness explicitly.
- **`db/gen/GenerateIndex.java` contains five raw NUL bytes in its source.** Five char literals at
  `:520`, `:552`, `:575`, `:760` and `:783` are a literal `0x00`, not `'\0'` and not a space, in the
  committed file. `VerifyIndex` uses a real space for the same composite key. Each file is
  self-consistent, so nothing is broken today, and NUL is the better of the two choices because it
  cannot occur in an authority name or a code where a space can. But the two disagree, and a NUL
  written as an invisible byte is one careless editor away from becoming a space. Writing it as
  `'\0'` would settle it. Neither file was changed here.
- **Four fields `GenerateIndex` writes are never checked by `VerifyIndex`**: `deprecated` on
  `conversion_table` and on `concatenated_operation`, and `method_auth_name`, `method_code` and
  `operation_version` on `helmert_transformation_table`. Every accessor exists, and
  `verifyTransformation` checks all of them for the grid and other-transformation tables, which is
  what makes the gaps look accidental. The verifier is the only thing standing between a generator
  bug and a wrong answer, so where it is silent nothing is watching. Adding assertions can turn a
  passing build red, so this is reported rather than fixed.

### One consolidation held back for a measurement rather than a doubt

`FastStrictTrig`'s three one-argument methods are their two-argument counterparts with `scratch`
fixed at `null` — same threshold, same `x - x` for NaN and infinity, same call into
`reduceAndKernel` — so `sin(x)` could read `return sin(x, null);` and the same for `cos` and `tan`.
That removes 18 lines of a dispatch protocol currently written six times, where the fast-path
threshold `0x3fe9_21fb` has to be changed in six places and agree with itself.

Bit-identity here is a proof, not a hope, and it was confirmed anyway: both versions compiled and
compared over 9,000,048 inputs — the sub-π/4 fast path, the medium and multi-word reduction ranges,
the `NPIO2_HW` table neighbourhood, a full exponent sweep, random bit patterns including NaN and
infinity, and the named specials — with **zero differing results**.

It is still not landed, because the risk is speed rather than correctness and this is the wrong
class to guess about. There are **394 one-argument call sites across 41 files and no two-argument
call sites in main at all** — the `Scratch` overloads are used only by three test classes. So the
delegation adds a frame to the only form the library actually uses, on paths that include projection
inner loops. C2 will almost certainly inline it; the interpreter and C1 will not. The patch is
prepared and applies cleanly, and it lands only if the benchmark gate shows no regression against
`master`. If it costs anything measurable, both bodies stay and this paragraph is the reason.

### A latent hazard the geoapi work turned up

`TransformWrapper2D`'s array methods leave `src.z` at `Double.NaN`, and
`BasicCoordinateTransform.transformClosed` reads that NaN as a deliberate sentinel —
`final boolean noHeightIn = Double.isNaN(src.z)` at `BasicCoordinateTransform.java:520`. So the 2D
and 3D loop bodies are one `src.z =` line apart textually and different in meaning: the 2D body is
asserting "no height". An arity-parameterised merge that wrote a zero height would flip that.

Measured, not assumed: a NaN height and a zero height currently give bit-identical easting and
northing on EPSG:4326 → 2154, 27700, 31467, 23032, 4269 and 28992, because the core substitutes zero
for the datum stage and restores NaN on output. The sentinel presently changes only the output `z`,
which the 2D wrapper never writes. That makes this latent rather than live — but if the core ever
stops substituting zero, a merged wrapper would move every datum-shifted result. Both helpers'
Javadoc says so and `testMissingHeightMatchesTwoDimensional` pins it.

Also found: `transform(Point2D, Point2D)` had no test coverage anywhere. GeoAPI 3.0.2's
`TransformTestCase` never mentions `Point2D` — checked against the conformance jar's bytecode — and
neither `TransformTest` nor `WrappersTest` calls it. `testPoint2D` now does.

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

### Every figure above counts core's own tests only

JaCoCo attributes coverage per module: each module writes its own `target/jacoco.exec` and
`jacoco:report` renders it against that module's own `target/classes`. `conformance` has no
`src/main` at all, so its report goal finds nothing to render and skips — but its tests execute
**core's** classes, and that execution is recorded in `conformance/target/jacoco.exec` and then
attributed to nobody.

So the 79.9% is a floor. Merging all four exec files and re-rendering against `core/target/classes`
gives the number core actually reaches:

```
core tests only        instr 98285/123083 = 79.9%   branch 8110/11921 = 68.0%
all modules merged     instr 99487/123083 = 80.8%   branch 8262/11921 = 69.3%
```

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get \
    -Dartifact=org.jacoco:org.jacoco.cli:0.8.15:jar:nodeps
CLI=~/.m2/repository/org/jacoco/org.jacoco.cli/0.8.15/org.jacoco.cli-0.8.15-nodeps.jar
java -jar $CLI merge */target/jacoco.exec --destfile /tmp/all.exec
java -jar $CLI report /tmp/all.exec --classfiles core/target/classes --csv /tmp/all.csv
```

**That +0.9 understates the real lift, and the reason matters more than the number.** The
measurement above ran with `gie.corpus.skip=true`, the default, so it contains conformance's 345
fast unit tests and *not* the 8,017-assertion corpus sweep. The sweep is the single largest exercise
of core in the repository and it contributes to no coverage figure anyone has ever looked at.

Re-run with `-Pconformance` so the corpus is in, and merged the same way, the honest figure is:

```
corpus on, all modules merged   instr 106120/123083 = 86.2%   branch 8786/11921 = 73.7%
```

which is **+5.4 points of instruction coverage over the same measurement with the corpus off** — the
whole of that gap being work the suite was already doing and nobody was counting.

Nothing needs fixing in the build for this. Per-module attribution is JaCoCo behaving correctly, and
adding `report-aggregate` to a POM is against this review's no-new-plugins decision. What was wrong
was reading 79.9% as though it were the whole answer.

### Before this change, a coverage build could not be run at all

Both of the following had to be fixed before any of the figures above could be produced, and each
failed in a way that reported success or looked like the code's fault rather than the harness's:

1. **`ProjContextTest.everyFieldOfTheContextIsFinal` fails under instrumentation.** JaCoCo weaves a
   `private static transient boolean[] $jacocoData` into every class it touches, and that field is
   not final, so a reflection test asserting every field is final fails on JaCoCo's own field:
   `AssertionError: $jacocoData must be final`. Measured at the branch point — `mvn
   jacoco:prepare-agent test` is a **BUILD FAILURE** there, in core, before conformance is even
   reached. `f.isSynthetic()` filters it and catches nothing an author can write.
2. **`conformance` and `golden` each declared a literal `<argLine>` with no `@{argLine}`.** JaCoCo's
   `prepare-agent` works by *setting* the `argLine` property, so a literal value replaces the agent
   rather than adding to it: the agent silently never attaches and that module's coverage reads as
   zero with nothing failing to say so. This is why the corpus contributed to no figure.

Which makes the coverage lift measurable in the first place. Isolating it needs the branch point
measured *with* this change's plumbing but *without* its new tests — a throwaway worktree at the
branch point with only the `ProjContextTest` skip and the three POMs copied in:

```
branch point, corpus on, merged   instr 104066/123083 = 84.5%   branch 8512/11921 = 71.4%
this change,  corpus on, merged   instr 106120/123083 = 86.2%   branch 8786/11921 = 73.7%
```

So the tests added here are worth **+1.7 points of instruction coverage and +2.3 of branch
coverage** — 2,054 more instructions and 274 more branches. The denominator is identical in both
rows (123,083 and 11,921), which is an independent check that nothing in core's main source moved.

### `inverse(forward(p)) ≈ p` was asserted nowhere, and golden does not cover it

The plan listed "missing round-trip tests where `hasInverse()` is true" as a gap. It is a real one,
and the reason is worth stating because the obvious rebuttal is wrong.

`errors/RegistryProjectionTest.java` does sweep the whole registry, but it asserts only that every
name resolves, that nothing touches `System.err`, and that no name lands on an abstract base. It
never inverts anything.

The golden suite *does* probe forward and inverse across the registry —
`GoldenFormat.java:158` declares `DIMENSIONS = {"fx","fy","fz","ix","iy","iz"}` and `:72-73`
documents "f\* is the forward transform's output; i\* is the inverse" — over 45,065 REG plus 1,000
PAIR plus 2,595 SYN probes. That looks like the same ground, and it is not. **Golden is a change
detector.** It diffs observed output against a table pinned from released 1.4.3. A projection whose
inverse has been wrong since 1.4.3 sits in that baseline and golden reports it as `UNCHANGED`
forever. Golden pins values; it never asserts the identity.

The two answer different questions and the round-trip audit is not the redundancy PR 3 removed.

#### What the audit found: 30 of 122 invertible projections do not close the loop

`roundtrip/RegistryRoundTripAuditTest.java` enumerates the registry — **151** instantiable names, of
which **122** report `hasInverse()` — and drives each through an 8×7 longitude/latitude grid plus 12
awkward cases, 68 points per projection. **8,296 probes, 303 skipped as forward-refused, 7,993
round-trip assertions, 0.051 s.** Eighteen operators that genuinely cannot initialize without extra
parameters (`lat_1`/`lat_2` conics, `+h` perspectives, `labrd +lat_0`, `misrsom +path`, `gn_sinu
+m`/`+n`, `tpeqd` control points) are listed in a `REQUIRED` table, and a **second test asserts every
entry in that table is still needed** — so nobody can make a projection pass by feeding it kinder
parameters.

The tolerance is **1e-5 degrees** (1.1 m of ground displacement) on `max(|dlat|, |dlon|·cos lat)`,
one value for everything. It is not tighter because at a projection's singular points `dy/dφ`
vanishes and one ulp of the forward is amplified to about `sqrt(eps)` in the recovered latitude —
measured worst of that class 1.5e-6 deg, on `putp6p`. More to the point the choice is **not
delicate**: sorting the 122 measured worst errors leaves a two-and-a-half-decade gap between the
largest passing (6.6e-6 deg, `eqearth`) and the smallest failing (1.7e-3 deg, `putp4p`), so any
tolerance in [1e-5, 1e-4] produces the identical failing set.

Most of the 30 are honest domain limits — a regional projection asked about the far side of the
planet, refusing or answering nonsense. `alsk`, `gs48`, `gs50` (modified stereographic for Alaska and
the 48/50 states), `labrd` (Madagascar), `imw_p` and `poly` (polyconic far from the central
meridian), `cass`, and the interrupted lobed projections `igh`, `igh_o`, `imoll`, `imoll_o` whose
lobe boundaries the inverse refuses by design.

**Five are not domain limits**, and each is filed for its own fix (#101):

| | what |
|---|---|
| `lcc`, `ccon`, `pconic`, `murd2` | the forward accepts the far pole. With `lat_1=30`/`lat_2=60` the south pole projects to a finite `y = 1.14e7` — the cone apex — and inverts to **+90**: the wrong hemisphere, silently. `ccon` and `pconic` fold whole southern rows onto one parallel, 180 deg out |
| `somerc`, `gstmerc` | fold the far side onto the near side. `(-179.9, lat)` forwards to a small easting and inverts to lon 0.5036 — plausible-looking, wholly wrong, no refusal, on 39 of 68 probes each |
| `nell_h` | the inverse throws `ConvergenceFailureException` at \|lat\| ≥ 89.9 on points its own forward accepts. A missing pole case in the Newton loop, not a domain limit |
| `putp4p`, `weren` | lose 190 m at the pole, recovering 89.9983 for an input of 90 — `asin` saturation. Werenskiold I is Putnins P4′ rescaled, so one clamp likely fixes both |
| `PolyconicProjection` | throws `new ProjectionException("I")` at `:109`, `:125` and `:140`. A one-character message, where `ccon`, `imw_p` and `labrd` all cite the upstream line and the offending value. Message-only, so this one is bit-safe |

The test pins the failing set by **equality**, not containment, so it is anti-rot in three
directions: an unpinned failure is red with the point, the forward output and the recovered values; a
pinned name that starts passing is red as `OVER-PINNED` with "DELETE the PINNED entry"; and a pinned
error that more than doubles is red as `REGRESSED`. Entries pinned at "inverse refused" have no upper
bound and the Javadoc says so rather than implying a guarantee it cannot give.

It was mutation-checked rather than assumed: scaling `MercatorProjection.projectInverse` by 1.000001
turns `merc` red with a 9.0e-5 deg error; flipping a sign in the audit's own comparison turns 100+
projections red, which is the check that the comparison is not vacuous; over-pinning a healthy
projection fires `OVER-PINNED`; tightening `poly`'s pin fires `REGRESSED`; and a bogus `REQUIRED`
entry fires the staleness test. Every mutation was compiled into a shadowing output directory ahead
of `core/target/classes` on the classpath, so no repo file was edited and nothing needed reverting.

The honest caveat is that the 30-entry table is what *this* 68-point ladder on GRS80 finds. A
different ladder, or a spherical base, would shift which projections get pushed outside their design
region. GRS80 was chosen because it lets `etmerc`, `ups` and `utm` build at all and exercises the
ellipsoidal iterative inverses.

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

**Done in this change, and it needed a fix as well as a re-enable.** The `@Ignore` is gone, the
`org.junit.Ignore` import with it, and both commented-out `checkTransform` calls are live. One of the
two was missing a longitude token — `p("0dN 0.000")`, which `p()` read as lon=`0dN`, lat=`0.000`,
i.e. the point (0,0) rather than the intended one — so it would have passed while testing nothing.
It now reads `p("20dW 0dN 0.000")`, and the forward tolerance is 0.1 m rather than 50 m, matching
every sibling in the file. Every expected value is `cs2cs` 9.8.1-verified rather than copied from
proj4j's own output.

**Core now reports 2,141 tests and zero skipped** — no ambiguous test remains anywhere in it.

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

### What the test-sufficiency change added, and what it found

The tests went in where the coverage numbers and the audit above pointed. The notable result is that
**writing tests for under-covered code found defects in it at a high rate** — the uncovered branches
were uncovered because nobody had looked, and several were wrong.

`gen/VerifyIndex` in `db` turned out to compare **eleven** fields fewer than it appeared to, not the
four first counted. Established by reading each `addX(...)` emitter in `GenerateIndex` in emission
order, matching it against the decode order in `PjdxDatabase` to confirm the field is reachable
through the SPI at all, then against the verifier's assertions per table:

```
vertical_datum                  publication_date
vertical_datum                  frame_reference_epoch
vertical_crs                    coordinate_system_auth_name / _code
vertical_crs                    deprecated
compound_crs                    deprecated
engineering_crs                 deprecated
conversion_table                deprecated
helmert_transformation_table    method_auth_name
helmert_transformation_table    method_code
helmert_transformation_table    operation_version
concatenated_operation          deprecated
```

Five of the eleven are `deprecated`, checked on ten tables through `eqDeprecated` and simply absent
from five — which is exactly why it read as covered. The sharpest gap is the Helmert method pair: the
verifier resolved the method *name* through the same `(auth, code)` pair it was failing to check, so
a swapped or dropped pair would still have produced the right name.

All eleven are now asserted, and the count is **measured, not derived**:

```
$ ./db/src/gen/dump.sh /opt/homebrew/share/proj/proj.db /tmp/proj-db-dump.quote
$ javac -d /tmp/db-classes -cp core/target/classes $(find db/src/main/java -name '*.java')
$ java -cp /tmp/db-classes:core/target/classes \
      org.locationtech.proj4j.db.gen.VerifyIndex /tmp/proj-db-dump.quote \
      db/src/main/resources/proj4j-data/db

  before   VerifyIndex: 486491 field comparisons in 6240 ms   OK
  after    VerifyIndex: 502422 field comparisons in 6306 ms   OK
```

`VerifyIndexFieldCoverageTest` then pins, from the shipped index, that each newly compared field
actually *varies*, so a green comparison has discriminated rather than compared null to null: 197
vertical datums with a publication date, 2 with a frame epoch, 10 distinct vertical-CRS coordinate
systems, 11 of 609 and 11 of 702 deprecated vertical and compound CRSs, 784 deprecated conversions,
20 distinct Helmert method codes over 2,362 operation versions, 63 deprecated concatenated
operations. One field has nothing to discriminate — all 15 engineering CRSs are `deprecated=false` —
and the test asserts that premise explicitly rather than letting a reader assume the field is covered
like the others.

**Two fields cannot be checked by anything.** `alias_name.source` and `deprecation.source` are
written by the generator and no reader path reads them: `aliases()` reads only the alias key, and
`replacementsFor()` reads the replacement's authority and code and stops. They are dead payload, and
a wrong value in either is undetectable by any test until an accessor exists. That is now recorded in
the `VerifyIndex` class Javadoc rather than left as a silent omission.

On the ProjJSON side, the reader and writer had **11 of 56 reachable `throw` sites covered**; they
now have 56. Coverage was decided by execution rather than by reading: each malformed document was
run with `-Dproj4j.exceptions.stackTraces=true` and the topmost `io.projjson` frame recorded, so
every document is known to land on the site it claims and no other. Of the 11 already covered, only
four had their *message* asserted; the other seven were reached only by a loop that asserted nothing
beyond "a `WktParseException` came out", which made `datum "d" has no "ellipsoid"` and `null`
indistinguishable. Two sites are deliberately left uncovered, with the argument recorded in the test:
the `default:` branch of a switch whose seven enum constants all have a `case`, and an
`AssertionError` in a private constructor reachable only by reflection.

### The forward funnel forgets to wrap longitude when `+lon_0` is absent

The largest single defect this change surfaced is not in a projection at all. It is in the funnel
**every** forward projection passes through.

`proj/Projection.java:464` reads `if (projectionLongitude != 0) {`, and inside that guard sits both
the central-meridian subtraction **and** the `adjlon` wrap. Upstream's `fwd_prepare`
(`9.8.1:src/fwd.cpp:109-112`) subtracts unconditionally and guards only the wrap, on `+over` alone:

```c
coo.lp.lam = (coo.lp.lam - P->from_greenwich) - P->lam0;
if (0 == P->over)
    coo.lp.lam = adjlon(coo.lp.lam);
```

So with no `+lon_0`, or `+lon_0=0`, proj4j behaves exactly as though `+over` were set. Measured on
`merc` through `Projection.project`, input (200, 45):

| | x |
|---|---|
| proj4j, no `+lon_0` | 22263898.158654712 — unwrapped, 200 deg |
| **PROJ 9.8.1, no `+lon_0`** | **−17811118.526923772** — wrapped to −160 deg |
| proj4j, `+lon_0=0.0000001` | −17811118.538055720 — wraps, and agrees to 11 mm |
| proj4j, `+over` | 22263898.158654712 — matches PROJ's `+over` exactly |
| proj4j, `+lon_0=10` | −18924313.434856508 — matches PROJ exactly |

A full world width apart. The tiny-`lon_0` row is the proof of diagnosis: subtracting 1e-7 degrees
cannot move a coordinate 40,000 km, so what changed the answer was crossing the guard, not the
arithmetic inside it.

What makes this a clean call rather than a judgement is that **the inverse funnel in the same file
already does it correctly.** `:704-707`:

```java
dst.x = dst.x + projectionLongitude;
if (!over) {
    dst.x = ProjectionMath.adjlon(dst.x);
}
```

Unconditional add, wrap guarded only by `over` — upstream's shape exactly, with a long comment at
`:686-688` explaining that the old `projectionLongitude != 0` guard was half the defect. The inverse
was fixed and the forward was missed. The fix is to hoist the wrap out of the outer guard so the two
funnels agree. Filed as #99.

**It is not part of this change**, because it moves bits for every CRS with no `+lon_0` whose input
longitude falls outside [−180, 180], and that has to go through the golden gate on its own.

A second half turned up while verifying it, and is recorded in #99 rather than resolved: through
`BasicCoordinateTransform` the wrap happens anyway, because a geographic source CRS runs the input
through the *longlat* projection's inverse funnel — which wraps correctly. That masks the defect on
the transform path, but it also means `+over` on the target is defeated for a geographic source:
`+proj=merc +over` through a transform returned the wrapped −17811118.526923772, not 22263898.158654712.

### Van der Grinten's inverse is missing one upstream line

`VanDerGrintenProjection.projectInverse` omits `if (r > PISQ) { d = M_TWOPI - d; }`, which upstream
places in `vandg_s_inverse` between Snyder 29-17 and 29-18. Since `r = x² + y²`, the branch fires
outside the map circle of radius π — precisely where `+over` puts everything past the antimeridian.
Latitude only; longitude is correct throughout:

| input | ours | PROJ 9.8.1 | error |
|---|---|---|---|
| `+over` forward of (200, 45) | 41.125734607170 | 45.0 | **3.874 deg** |
| (0, 4) | 87.436463098346 | 89.347491137743 | 1.911 deg |
| (3.2, 3.2) | 66.299275009994 | 76.330075167970 | 10.031 deg |
| (100, 100) | 2.826724968727 | 22.189549318108 | 19.363 deg |
| (10, 10) | 27.492061638620 | 60.170243400006 | 32.678 deg |

Our forward of (200, 45) is bit-for-bit PROJ's, so the `+over` round trip does not close: forward and
back loses 3.87 degrees. Adding the single upstream line to a throwaway copy reproduced the PROJ
column to the last printed digit in all five rows and closed the round trip at exactly 45.0. The
pinning test fails against that patched copy, which is the check that the pin is real. Filed as #100;
moves bits, so not part of this change.

### Where proj4j is faithful to PROJ and PROJ is wrong

Three of the defects the new tests turned up are **not** proj4j's**.** Recording them matters because
the obvious response to each is a "fix" that would silently diverge from upstream and move bits.

**`DeformationOperator`'s inverse amplifies its residual rather than converging on it.** The loop
computes a residual and then *adds* it where the Newton step would subtract it, so each of the ten
passes roughly doubles the second-order term, and the `hypot(dif) > 1e-8` guard never lets it exit
early. Verified against the pinned tag rather than the working tree — the `/Volumes/git/PROJ`
checkout is at `9.5.0-746-g620ac364`, so `git show 9.8.1:src/transformations/deformation.cpp` is the
only trustworthy read — and upstream at `:235-241` really does write:

```c
dif.x = out.x + dt * delta.x - input.x;   /* the residual f(out) */
out.x += dif.x;                           /* f' ~= 1, so this should be -= */
```

The shape is worth understanding before anyone touches it: for a *position-independent* `delta` the
residual is exactly zero on the first pass, because `out` is initialised to `input - dt*delta`, and
the loop exits correctly. The error appears only where `delta` varies with position — which in this
operator it always does, since a constant ENU velocity is rotated into geocentric XYZ. Measured on
the new fixtures, a 100-year 5.385 m displacement closes to ~4.2 mm and a 10-year one to ~42 µm:
error growing with the *square* of the displacement, which is the signature of an amplified
second-order term rather than of ordinary numerical noise.

What *is* proj4j's own is the class Javadoc, which describes this as a "10-iteration fixed point"
with a tolerance — convergence it does not achieve. That claim should be corrected whatever is
decided about the arithmetic.

**Bipolar's lobe rescaling does not round-trip.** At (-100, -20), inside the pole-B cone and through
the `if (|t| < al) r /= cos(al + t)` rescaling, the inverse returns (-99.9853750534, -19.9995881702)
— 1.5 km out. PROJ 9.8.1 returns the identical pair to all ten printed decimals. The inverse
re-derives the rescaling with a ten-trip fixed-point loop instead of inverting it.

**Goode's 40°44′ seam is not exactly invertible.** Forward at exactly `PHI_LIM` gives
`y = 4549957.004665751`; the inverse's reciprocal multiply by `1/R` lands one bit *above* `PHI_LIM`,
takes the Mollweide arm, and returns 40.7331312650° — a 23 m step. PROJ agrees to ten decimals.

All three are pinned at the observed values with a note in the test saying these are what the code
does today, not a designed result. The round-trip assertions were scoped to the probes that do close
rather than loosened until everything passed.

Three items were deliberately **not** written, and the reasons are as much a part of the result:

- A registry-wide round-trip audit exists now, but only after establishing that golden does not
  cover it (above). Had that check gone the other way, the right answer was to write nothing.
- `AngleFormat`'s known sign defect was pinned, not fixed. The tests assert today's wrong output and
  say so, because fixing it belongs with the other six defects in the same family, in one change
  that can be reviewed as a behaviour change rather than smuggled in beside test additions.
- No committed test input was edited. Where a test needed malformed input, new fixtures were added.

### A verification gate that was not running

`db`'s index reproducibility proof — the thing that makes the checked-in binary trustworthy — did
not run, and reported success.

All three `regen-db` executions bind to the `generate-resources` phase. The command documented at
`db/README.md:151`, and the one `db/pom.xml:189-194` claims CI runs, is `mvn -Pregen-db ... validate`.
`validate` runs *before* `generate-resources`, so none of the three fire:

```
$ mvn -B -ntp -Pregen-db -pl db validate
[INFO] BUILD SUCCESS
[INFO] Total time:  0.541 s
```

No dump, no transcode, no verification. A `git diff --exit-code` after it passes because nothing was
regenerated — the gate is vacuous, and vacuous in the direction that reports "reproducible". The POM
comment's claim that CI runs it is also false: no workflow in `.github/` mentions `regen-db` at all.

The two rows at `db/README.md:166-167` use `mvn install -Pregen-db`, which does work, since `install`
passes through `generate-resources`. So the repair is to the documented command and the comment, not
to the phase bindings. The index itself is sound — both verifier runs above pass against the shipped
bytes.

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
3. **Redundancy.** The safe table above, and nothing from the unsafe one. Seven changes were made
   across `Registry`, the pipeline operators, `geoapi`, `api`, `io.wkt`, `io` and `db`; each carries a
   test that fails if it is undone; and the candidates left alone are listed with the specific reason
   each one risks a bit. See "What was actually consolidated" above. The `FastStrictTrig` delegation
   is prepared but gated on a benchmark run.
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
