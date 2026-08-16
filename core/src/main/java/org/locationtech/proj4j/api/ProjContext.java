/*
 * Copyright 2026, PROJ4J contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.api;

import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.EsriDatumPolicy;
import org.locationtech.proj4j.parser.Proj4Parser.ParseMode;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Every policy that can change a numeric answer, in one immutable object.
 *
 * <p>This is the <b>only</b> place these policies are set. There is no system property, no
 * environment variable, no static setter beyond {@link Proj#setDefaultContext(ProjContext)} (which
 * refuses once anything has been built), and no {@code ServiceLoader}-discovered configuration.
 * That is the point: in a Spark or Flink executor the environment is chosen by the cluster
 * operator, not by the pipeline author, and a coordinate reference system whose axis order depends
 * on who launched the JVM is not reproducible. Ambient state must never change a number.
 *
 * <pre>{@code
 * ProjContext ctx = ProjContext.builder()
 *         .axisOrderPolicy(AxisOrderPolicy.AUTHORITY)   // deliberately lat-first, re-baselined
 *         .ballparkPolicy(BallparkPolicy.ALLOW)         // we accept an unshifted datum here
 *         .build();
 *
 * CrsOperation op = Proj.createCrsToCrs("EPSG:4326", "EPSG:3857", ctx);
 * }</pre>
 *
 * <h2>Precedence</h2>
 *
 * <p>Three levels, increasing, all introspectable:
 * <ol>
 * <li>the <b>built-in default</b>, {@link #DEFAULT} &mdash; hard-coded, no flag, reproducing
 *     proj4j 1.4.3's observable behaviour;</li>
 * <li>the <b>process default</b>, via {@link Proj#setDefaultContext(ProjContext)}, which throws
 *     {@code IllegalStateException} once any {@link Crs} or {@link CrsOperation} exists, so one
 *     library on the classpath cannot flip the semantics under another;</li>
 * <li>the <b>per-call</b> context, passed to {@link Proj#createCrs(String, ProjContext)} or
 *     {@link Proj#createCrsToCrs(String, String, ProjContext)}, or derived with
 *     {@link Crs#withAxisOrderPolicy(AxisOrderPolicy)}. This is the preferred level.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@code ProjContext} is deeply immutable and safe to share between any number of threads.
 * {@link Builder} is mutable and thread-confined: build it on one thread, then share the result.
 *
 * @see AxisOrderPolicy
 * @see BallparkPolicy
 * @see GridPolicy
 * @see BestOperationPolicy
 * @see DomainErrorPolicy
 * @see ParseMode
 * @since 2.0.0
 */
public final class ProjContext {

    /**
     * The built-in default: {@link AxisOrderPolicy#LEGACY} (longitude-first, 1.4.3 behaviour),
     * {@link BallparkPolicy#REJECT}, {@link GridPolicy#REQUIRE_ALL},
     * {@link BestOperationPolicy#REQUIRE_BEST}, {@link DomainErrorPolicy#THROW},
     * {@link ParseMode#PROJ_COMPATIBLE}.
     *
     * <p>Every one of those is the strict choice except the axis order and the parse mode, which
     * are the <em>compatible</em> choices. The axis-order asymmetry is explained on
     * {@link AxisOrderPolicy}: adopting authority axis order is a silent behavioural change that
     * is invisible near the equator and the prime meridian, so it cannot be a default. The parse
     * mode's is explained on {@link #parseMode()}: PROJ itself has no parameter allow-list at all,
     * so {@link ParseMode#STRICT} rejects definitions PROJ accepts and cannot be a default either.
     * Everything else fails loudly, which is safe to default to because a caller notices
     * immediately.
     */
    public static final ProjContext DEFAULT = new ProjContext(AxisOrderPolicy.LEGACY,
            BallparkPolicy.REJECT, GridPolicy.REQUIRE_ALL, BestOperationPolicy.REQUIRE_BEST,
            DomainErrorPolicy.THROW, ParseMode.PROJ_COMPATIBLE, EsriDatumPolicy.REJECT, null,
            null, SpatialCriterion.PARTIAL_INTERSECTION, SourceTargetCRSExtentUse.SMALLEST);

    private final AxisOrderPolicy axisOrderPolicy;
    private final BallparkPolicy ballparkPolicy;
    private final GridPolicy gridPolicy;
    private final BestOperationPolicy bestOperationPolicy;
    private final DomainErrorPolicy domainErrorPolicy;
    private final ParseMode parseMode;
    private final EsriDatumPolicy esriDatumPolicy;
    private final ProjDatabase database;
    private final AreaOfUse areaOfInterest;
    private final SpatialCriterion spatialCriterion;
    private final SourceTargetCRSExtentUse sourceTargetCrsExtentUse;

    private ProjContext(AxisOrderPolicy axisOrderPolicy, BallparkPolicy ballparkPolicy,
                        GridPolicy gridPolicy, BestOperationPolicy bestOperationPolicy,
                        DomainErrorPolicy domainErrorPolicy, ParseMode parseMode,
                        EsriDatumPolicy esriDatumPolicy, ProjDatabase database,
                        AreaOfUse areaOfInterest, SpatialCriterion spatialCriterion,
                        SourceTargetCRSExtentUse sourceTargetCrsExtentUse) {
        this.axisOrderPolicy = axisOrderPolicy;
        this.ballparkPolicy = ballparkPolicy;
        this.gridPolicy = gridPolicy;
        this.bestOperationPolicy = bestOperationPolicy;
        this.domainErrorPolicy = domainErrorPolicy;
        this.parseMode = parseMode;
        this.esriDatumPolicy = esriDatumPolicy;
        this.database = database;
        this.areaOfInterest = areaOfInterest;
        this.spatialCriterion = spatialCriterion;
        this.sourceTargetCrsExtentUse = sourceTargetCrsExtentUse;
    }

    /**
     * A builder pre-loaded with {@link #DEFAULT}'s values.
     *
     * @return a fresh builder; never null
     */
    public static Builder builder() {
        return new Builder(DEFAULT);
    }

    /**
     * A builder pre-loaded with this context's values.
     *
     * @return a fresh builder; never null
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * How the axis order a CRS declares affects the coordinates this context's operations consume
     * and produce.
     *
     * @return the policy; never null
     */
    public AxisOrderPolicy axisOrderPolicy() {
        return axisOrderPolicy;
    }

    /**
     * What happens when the only available datum change is one that would not actually be applied.
     *
     * @return the policy; never null
     */
    public BallparkPolicy ballparkPolicy() {
        return ballparkPolicy;
    }

    /**
     * What happens when an ESRI-flavoured WKT document names a reference frame this library cannot
     * place and supplies no {@code TOWGS84[]} of its own.
     *
     * @return the policy; never null
     * @since 2.2.0
     */
    public EsriDatumPolicy esriDatumPolicy() {
        return esriDatumPolicy;
    }

    /**
     * What happens to a declared grid file that cannot be found.
     *
     * @return the policy; never null
     */
    public GridPolicy gridPolicy() {
        return gridPolicy;
    }

    /**
     * Whether a degraded operation may be substituted for an unavailable better one. Recorded and
     * reported; see {@link BestOperationPolicy} for what it does not yet do.
     *
     * @return the policy; never null
     */
    public BestOperationPolicy bestOperationPolicy() {
        return bestOperationPolicy;
    }

    /**
     * What a per-coordinate failure does &mdash; throw, or write {@code NaN} into every ordinate.
     * The same enum the legacy {@link org.locationtech.proj4j.CoordinateTransformFactory} takes, so
     * a caller does not learn two vocabularies for one decision.
     *
     * @return the policy; never null
     */
    public DomainErrorPolicy domainErrorPolicy() {
        return domainErrorPolicy;
    }

    /**
     * How strictly a <b>PROJ.4 parameter string</b> is checked. The same enum
     * {@link org.locationtech.proj4j.parser.Proj4Parser} takes, so a caller does not learn two
     * vocabularies for one decision.
     *
     * <h4>What {@link ParseMode#STRICT} actually changes &mdash; one thing, and no others</h4>
     *
     * <ol>
     * <li>A key outside {@link org.locationtech.proj4j.parser.Proj4Keyword#supportedParameters()}
     *     raises {@link org.locationtech.proj4j.UnsupportedParameterException}, <b>naming the
     *     key</b>, instead of being retained and ignored.</li>
     * </ol>
     *
     * <p><b>This list used to have a second entry, for {@code +units}.</b> An unresolvable
     * {@code +units} value now raises {@link org.locationtech.proj4j.InvalidValueException}
     * in <em>both</em> modes, because that is what PROJ itself does
     * ({@code init.cpp:679} resolves {@code +units} against the ids of
     * {@code pj_list_linear_units()} and nothing else) &mdash; so it is parity rather than
     * added strictness, and gating it behind {@code STRICT} left the default mode returning
     * metres for a value PROJ refuses. {@link org.locationtech.proj4j.units.Units#linearUnitIds()}
     * is the accepted set.
     *
     * <p><b>Duplicate keys are not affected.</b> {@code Proj4Parser}'s parameter map keeps the
     * <em>first</em> occurrence of a repeated key in both modes, which is PROJ's own rule
     * ({@code pj_param_exists} walks the list front-to-back), so {@code +lon_0=1 +lon_0=2} means
     * {@code 1} under {@code STRICT} exactly as it does under {@code PROJ_COMPATIBLE}. Neither
     * mode reports the duplicate.
     *
     * <h4>Where it applies, and where it deliberately does not</h4>
     *
     * <p>It applies to {@link Proj#createCrs(String, ProjContext)} when the definition is a
     * PROJ.4 parameter string, and therefore to
     * {@link Proj#createCrsToCrs(String, String, ProjContext)} and
     * {@link Crs#withContext(ProjContext)} when they are given one. That is where the untrusted
     * text is.
     *
     * <p>It does <b>not</b> apply to:
     * <ul>
     * <li><b>{@code authority:code} names.</b> Those resolve to a definition this library ships,
     *     not one the caller wrote, so an allow-list buys nothing there &mdash; and it would cost
     *     something: of the <b>9,013</b> definitions in the shipped {@code proj4/nad}
     *     dictionaries, exactly <b>one</b> carries a key outside the allow-list, {@code world:malay}
     *     with {@code +rot_conv}. That token is in PROJ 9.8.1's own {@code data/world} (line 113,
     *     the {@code malay} entry) and is read nowhere in its {@code src/} &mdash; {@code NEWS.md}
     *     records it under <b>4.8.0</b> as having stopped working when {@code omerc} was
     *     reimplemented from libproj4, with the {@code epsg} init file updated accordingly and
     *     {@code data/world} left alone. So PROJ retains and ignores it exactly as this library
     *     does, and refusing {@code malay} under {@code STRICT} would break a CRS that behaves the
     *     same in both.</li>
     * <li><b>WKT and PROJJSON.</b> Those are different grammars with their own readers; the
     *     allow-list is a list of PROJ.4 keys.</li>
     * <li><b>{@link org.locationtech.proj4j.CRSFactory} and
     *     {@link org.locationtech.proj4j.CoordinateTransformFactory}.</b> The 1.x API is frozen at
     *     {@link ParseMode#PROJ_COMPATIBLE} and is not re-routed by any context.</li>
     * </ul>
     *
     * <p><b>{@code STRICT} is stricter than PROJ, not a bug-for-bug match.</b> PROJ has no
     * parameter allow-list anywhere: {@code init.cpp} retains every token and recognition is
     * pull-based. So a definition rejected here is usually one PROJ accepts and ignores, which is
     * exactly why this is opt-in and why the conformance corpus &mdash; which feeds a literal
     * {@code +unknown_keyword} &mdash; runs under the default.
     *
     * @return the parse mode; never null
     * @see ParseMode
     */
    public ParseMode parseMode() {
        return parseMode;
    }

    /**
     * The authority database this context resolves CRSs and selects operations against, or
     * {@code null} for none.
     *
     * <p><b>{@code null} in {@link #DEFAULT}, and that is deliberate.</b> Core never scans for one:
     * an implicit {@code ServiceLoader} walk touches a classpath Proj4J does not control, which is
     * exactly how a library minding its own business triggers a {@code LinkageError} in somebody
     * else's jar. Opening a database is one line of application code, and it is visible in the
     * application's own source:
     *
     * <pre>{@code
     * ProjDatabase db = Proj4jDb.open();                       // from the proj4j-db artifact
     * ProjContext ctx = ProjContext.builder().database(db).build();
     * }</pre>
     *
     * <p>With a database this context selects real published operations, reports their accuracy and
     * their area of use, and names the grid files a rejected candidate needs. Without one it falls
     * back to the legacy datum model, where there is exactly one synthesised operation per CRS pair
     * &mdash; and it says so rather than degrading quietly.
     *
     * @return the database, or null
     * @see org.locationtech.proj4j.spi.ProjDatabaseProvider#discover(ClassLoader)
     * @see Proj#candidateOperations(Crs, Crs)
     */
    public ProjDatabase database() {
        return database;
    }

    /**
     * Whether this context has an authority database.
     *
     * @return true iff {@link #database()} is non-null
     */
    public boolean hasDatabase() {
        return database != null;
    }

    /**
     * The area the caller cares about, against which an operation's declared extent is tested.
     *
     * <p><b>Empty is not "no filter".</b> When this is empty the area of interest is synthesised
     * from the two CRSs' own extents, according to {@link #sourceTargetCrsExtentUse()}, and the
     * spatial filter still runs &mdash; which is what PROJ does. The only configuration that
     * switches the filter off is {@link SourceTargetCRSExtentUse#NONE} <em>and</em> no area here.
     *
     * @return the caller-supplied area of interest, or empty
     * @see SourceTargetCRSExtentUse
     * @since 2.2.0
     */
    public java.util.Optional<AreaOfUse> areaOfInterest() {
        return java.util.Optional.ofNullable(areaOfInterest);
    }

    /**
     * How an operation's extent must relate to the area of interest.
     *
     * <p>The default is {@link SpatialCriterion#PARTIAL_INTERSECTION}, not
     * {@code STRICT_CONTAINMENT}. PROJ declares {@code STRICT_CONTAINMENT} as the default in its
     * own header, and that default survives only in {@code projinfo};
     * {@code proj_create_crs_to_crs_from_pj} overrides it to partial intersection
     * unconditionally, so partial intersection is what a caller transforming coordinates
     * actually gets from PROJ. This library matches the behaviour, not the header. Do not
     * "correct" this to strict containment without re-measuring against
     * {@code proj_create_crs_to_crs}.
     *
     * @return the criterion; never null, {@link SpatialCriterion#PARTIAL_INTERSECTION} by default
     * @since 2.2.0
     */
    public SpatialCriterion spatialCriterion() {
        return spatialCriterion;
    }

    /**
     * How the two CRSs' own extents synthesise an area of interest when the caller supplies none.
     *
     * @return the mode; never null, {@link SourceTargetCRSExtentUse#SMALLEST} by default
     * @since 2.2.0
     */
    public SourceTargetCRSExtentUse sourceTargetCrsExtentUse() {
        return sourceTargetCrsExtentUse;
    }

    /**
     * A copy of this context with a different area of interest.
     *
     * @param area the area, or null for none
     * @return a new context, or {@code this} if nothing changed
     * @since 2.2.0
     */
    public ProjContext withAreaOfInterest(AreaOfUse area) {
        if (area == null ? areaOfInterest == null : area.equals(areaOfInterest)) {
            return this;
        }
        return toBuilder().areaOfInterest(area).build();
    }

    /**
     * A copy of this context with a different authority database.
     *
     * @param db the database, or null for none
     * @return a new context, or {@code this} if nothing changed
     */
    public ProjContext withDatabase(ProjDatabase db) {
        return db == database ? this : toBuilder().database(db).build();
    }

    /**
     * A copy of this context with a different axis order policy.
     *
     * @param policy the new policy; null means {@link AxisOrderPolicy#LEGACY}
     * @return a new context, or {@code this} if nothing changed
     */
    public ProjContext withAxisOrderPolicy(AxisOrderPolicy policy) {
        AxisOrderPolicy p = policy == null ? AxisOrderPolicy.LEGACY : policy;
        return p == axisOrderPolicy ? this : toBuilder().axisOrderPolicy(p).build();
    }

    /**
     * A copy of this context with a different ballpark policy.
     *
     * @param policy the new policy; null means {@link BallparkPolicy#REJECT}
     * @return a new context, or {@code this} if nothing changed
     */
    public ProjContext withBallparkPolicy(BallparkPolicy policy) {
        BallparkPolicy p = policy == null ? BallparkPolicy.REJECT : policy;
        return p == ballparkPolicy ? this : toBuilder().ballparkPolicy(p).build();
    }

    /**
     * A copy of this context with a different ESRI datum policy.
     *
     * @param policy the new policy; null means {@link EsriDatumPolicy#REJECT}
     * @return a new context, or {@code this} if nothing changed
     * @since 2.2.0
     */
    public ProjContext withEsriDatumPolicy(EsriDatumPolicy policy) {
        EsriDatumPolicy p = policy == null ? EsriDatumPolicy.REJECT : policy;
        return p == esriDatumPolicy ? this : toBuilder().esriDatumPolicy(p).build();
    }

    /**
     * A copy of this context with a different grid policy.
     *
     * @param policy the new policy; null means {@link GridPolicy#REQUIRE_ALL}
     * @return a new context, or {@code this} if nothing changed
     */
    public ProjContext withGridPolicy(GridPolicy policy) {
        GridPolicy p = policy == null ? GridPolicy.REQUIRE_ALL : policy;
        return p == gridPolicy ? this : toBuilder().gridPolicy(p).build();
    }

    /**
     * A copy of this context with a different per-coordinate failure policy.
     *
     * @param policy the new policy; null means {@link DomainErrorPolicy#THROW}
     * @return a new context, or {@code this} if nothing changed
     */
    public ProjContext withDomainErrorPolicy(DomainErrorPolicy policy) {
        DomainErrorPolicy p = policy == null ? DomainErrorPolicy.THROW : policy;
        return p == domainErrorPolicy ? this : toBuilder().domainErrorPolicy(p).build();
    }

    /**
     * A copy of this context with a different PROJ.4 parse mode.
     *
     * @param mode the new mode; null means {@link ParseMode#PROJ_COMPATIBLE}
     * @return a new context, or {@code this} if nothing changed
     * @see #parseMode()
     */
    public ProjContext withParseMode(ParseMode mode) {
        ParseMode m = mode == null ? ParseMode.PROJ_COMPATIBLE : mode;
        return m == parseMode ? this : toBuilder().parseMode(m).build();
    }

    /**
     * A multi-line, human-readable rendering of every policy in this context, plus the fact that
     * none of them can be reached from the environment.
     *
     * <p>Intended to be logged once at the start of a job, next to
     * {@link Proj#describeResolution()}: between them they state everything that could make two
     * executors disagree about a coordinate.
     *
     * @return the description, newline-terminated; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("proj4j context:\n");
        sb.append("  axisOrderPolicy     = ").append(axisOrderPolicy);
        if (axisOrderPolicy == AxisOrderPolicy.LEGACY) {
            sb.append("   (longitude-first; EPSG:4326 takes (lon, lat), as in 1.4.3)");
        } else if (axisOrderPolicy == AxisOrderPolicy.AUTHORITY) {
            sb.append("   (authority order; EPSG:4326 takes (lat, lon), as in PROJ 6+/cs2cs)");
        } else {
            sb.append("   (normalised for visualisation: E/N, lon/lat, up-positive)");
        }
        sb.append('\n');
        sb.append("  ballparkPolicy      = ").append(ballparkPolicy).append('\n');
        sb.append("  esriDatumPolicy     = ").append(esriDatumPolicy)
                .append(esriDatumPolicy == EsriDatumPolicy.REJECT
                        ? "   (an ESRI D_ reference frame this library cannot place, with no "
                                + "TOWGS84[], is refused at parse time -- a divergence from PROJ, "
                                + "which answers with the ellipsoid alone)\n"
                        : "   (as PROJ: the ellipsoid alone, with the datum shift absent and "
                                + "unreported here; BallparkPolicy still governs the transform)\n");
        sb.append("  gridPolicy          = ").append(gridPolicy).append('\n');
        sb.append("  bestOperationPolicy = ").append(bestOperationPolicy)
                .append(database == null
                        ? "   (recorded; nothing to rank without an authority database)\n"
                        : "   (enforced: it refuses a selection strictly less accurate than a "
                                + "candidate that cannot be executed here)\n");
        sb.append("  domainErrorPolicy   = ").append(domainErrorPolicy).append('\n');
        sb.append("  parseMode           = ").append(parseMode)
                .append(parseMode == ParseMode.STRICT
                        ? "   (a PROJ.4 parameter string with a key outside the allow-list is "
                                + "refused -- stricter than PROJ, which has no allow-list; applies "
                                + "to PROJ.4 strings only, not to authority codes, WKT, PROJJSON "
                                + "or the 1.x CRSFactory)\n"
                        : "   (as PROJ: an unrecognised +key is retained and ignored)\n")
                .append("                        both modes: +units must be one of PROJ's 21 "
                        + "linear unit ids (Units.linearUnitIds()); anything else is refused\n");
        sb.append("  areaOfInterest      = ").append(areaOfInterest == null
                ? "NONE supplied -- synthesised from the two CRSs' own extents (the spatial "
                        + "filter still runs)"
                : areaOfInterest.toString()).append('\n');
        sb.append("  spatialCriterion    = ").append(spatialCriterion)
                .append(spatialCriterion == SpatialCriterion.STRICT_CONTAINMENT
                        ? "   (an operation must cover the whole area of interest, as PROJ; "
                                + "projinfo's larger counts are --spatial-test intersects)\n"
                        : "   (an operation need only overlap the area of interest)\n");
        sb.append("  crsExtentUse        = ").append(sourceTargetCrsExtentUse)
                .append(sourceTargetCrsExtentUse == SourceTargetCRSExtentUse.NONE
                        ? "   (with no area of interest supplied, the spatial filter does not "
                                + "run at all)\n"
                        : "   (used only when no area of interest is supplied)\n");
        sb.append("  database            = ").append(database == null
                ? "NONE -- operation selection falls back to the legacy datum model, which "
                        + "synthesises exactly one operation per CRS pair"
                : database.name()).append('\n');
        sb.append("  settable from the environment: NO -- no system property, no environment "
                + "variable, no service loader\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ProjContext[axisOrder=" + axisOrderPolicy + ", ballpark=" + ballparkPolicy
                + ", grid=" + gridPolicy + ", bestOperation=" + bestOperationPolicy
                + ", domainError=" + domainErrorPolicy
                + ", parseMode=" + parseMode
                + ", esriDatum=" + esriDatumPolicy
                + ", areaOfInterest=" + (areaOfInterest == null ? "none" : areaOfInterest)
                + ", spatialCriterion=" + spatialCriterion
                + ", crsExtentUse=" + sourceTargetCrsExtentUse
                + ", database=" + (database == null ? "none" : database.name()) + "]";
    }

    /**
     * Equal iff every policy is equal <em>and</em> both contexts hold the same database instance.
     *
     * <p>Database identity rather than equality, because a {@link ProjDatabase} is a handle to bytes
     * and two handles onto the same file are still two handles: one of them can be closed. Identity
     * is the only comparison that cannot be wrong here.
     *
     * @param o the other object
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjContext)) {
            return false;
        }
        ProjContext that = (ProjContext) o;
        return axisOrderPolicy == that.axisOrderPolicy
                && ballparkPolicy == that.ballparkPolicy
                && gridPolicy == that.gridPolicy
                && bestOperationPolicy == that.bestOperationPolicy
                && domainErrorPolicy == that.domainErrorPolicy
                && parseMode == that.parseMode
                && esriDatumPolicy == that.esriDatumPolicy
                && spatialCriterion == that.spatialCriterion
                && sourceTargetCrsExtentUse == that.sourceTargetCrsExtentUse
                && (areaOfInterest == null
                        ? that.areaOfInterest == null
                        : areaOfInterest.equals(that.areaOfInterest))
                && database == that.database;
    }

    @Override
    public int hashCode() {
        int h = axisOrderPolicy.hashCode();
        h = 31 * h + ballparkPolicy.hashCode();
        h = 31 * h + gridPolicy.hashCode();
        h = 31 * h + bestOperationPolicy.hashCode();
        h = 31 * h + domainErrorPolicy.hashCode();
        h = 31 * h + parseMode.hashCode();
        h = 31 * h + esriDatumPolicy.hashCode();
        h = 31 * h + spatialCriterion.hashCode();
        h = 31 * h + sourceTargetCrsExtentUse.hashCode();
        h = 31 * h + (areaOfInterest == null ? 0 : areaOfInterest.hashCode());
        return 31 * h + System.identityHashCode(database);
    }

    /**
     * Assembles a {@link ProjContext}. Mutable and <b>thread-confined</b>; the context it produces
     * is immutable and shareable.
     *
     * <p>Every setter treats {@code null} as "leave at the built-in default" rather than throwing,
     * because the common caller is reading configuration and a null there should not become a
     * different failure than a missing key.
     */
    public static final class Builder {

        private AxisOrderPolicy axisOrderPolicy;
        private BallparkPolicy ballparkPolicy;
        private GridPolicy gridPolicy;
        private BestOperationPolicy bestOperationPolicy;
        private DomainErrorPolicy domainErrorPolicy;
        private ParseMode parseMode;
        private EsriDatumPolicy esriDatumPolicy;
        private ProjDatabase database;
        private AreaOfUse areaOfInterest;
        private SpatialCriterion spatialCriterion;
        private SourceTargetCRSExtentUse sourceTargetCrsExtentUse;

        private Builder(ProjContext from) {
            this.axisOrderPolicy = from.axisOrderPolicy;
            this.ballparkPolicy = from.ballparkPolicy;
            this.gridPolicy = from.gridPolicy;
            this.bestOperationPolicy = from.bestOperationPolicy;
            this.domainErrorPolicy = from.domainErrorPolicy;
            this.parseMode = from.parseMode;
            this.esriDatumPolicy = from.esriDatumPolicy;
            this.database = from.database;
            this.areaOfInterest = from.areaOfInterest;
            this.spatialCriterion = from.spatialCriterion;
            this.sourceTargetCrsExtentUse = from.sourceTargetCrsExtentUse;
        }

        /**
         * Sets the axis order policy. Read {@link AxisOrderPolicy} before setting
         * {@link AxisOrderPolicy#AUTHORITY}: it is a silent behavioural change.
         *
         * @param policy the policy, or null to leave it at {@link AxisOrderPolicy#LEGACY}
         * @return this builder
         */
        public Builder axisOrderPolicy(AxisOrderPolicy policy) {
            this.axisOrderPolicy = policy == null ? AxisOrderPolicy.LEGACY : policy;
            return this;
        }

        /**
         * Sets the ballpark policy.
         *
         * @param policy the policy, or null to leave it at {@link BallparkPolicy#REJECT}
         * @return this builder
         */
        public Builder ballparkPolicy(BallparkPolicy policy) {
            this.ballparkPolicy = policy == null ? BallparkPolicy.REJECT : policy;
            return this;
        }

        /**
         * Sets the ESRI datum policy. Read {@link EsriDatumPolicy} before setting
         * {@link EsriDatumPolicy#ALLOW}: it restores a silently missing datum shift.
         *
         * @param policy the policy, or null to leave it at {@link EsriDatumPolicy#REJECT}
         * @return this builder
         * @since 2.2.0
         */
        public Builder esriDatumPolicy(EsriDatumPolicy policy) {
            this.esriDatumPolicy = policy == null ? EsriDatumPolicy.REJECT : policy;
            return this;
        }

        /**
         * Sets the missing-grid policy.
         *
         * @param policy the policy, or null to leave it at {@link GridPolicy#REQUIRE_ALL}
         * @return this builder
         */
        public Builder gridPolicy(GridPolicy policy) {
            this.gridPolicy = policy == null ? GridPolicy.REQUIRE_ALL : policy;
            return this;
        }

        /**
         * Sets the best-operation policy.
         *
         * @param policy the policy, or null to leave it at
         *               {@link BestOperationPolicy#REQUIRE_BEST}
         * @return this builder
         */
        public Builder bestOperationPolicy(BestOperationPolicy policy) {
            this.bestOperationPolicy = policy == null ? BestOperationPolicy.REQUIRE_BEST : policy;
            return this;
        }

        /**
         * Sets what a per-coordinate failure does.
         *
         * @param policy the policy, or null to leave it at {@link DomainErrorPolicy#THROW}
         * @return this builder
         */
        public Builder domainErrorPolicy(DomainErrorPolicy policy) {
            this.domainErrorPolicy = policy == null ? DomainErrorPolicy.THROW : policy;
            return this;
        }

        /**
         * Sets how strictly a PROJ.4 parameter string is checked.
         *
         * <p>Read {@link ProjContext#parseMode()} before setting {@link ParseMode#STRICT}: it
         * refuses definitions PROJ itself accepts, it covers PROJ.4 strings only, and it does not
         * touch the frozen 1.x {@link org.locationtech.proj4j.CRSFactory}.
         *
         * <pre>{@code
         * ProjContext ctx = ProjContext.builder().parseMode(ParseMode.STRICT).build();
         * Proj.createCrs("+proj=merc +ellps=GRS80 +units=m", ctx);          // fine
         * Proj.createCrs("+proj=merc +ellps=GRS80 +unknown_keyword=1", ctx); // UnsupportedParameterException
         * }</pre>
         *
         * @param mode the mode, or null to leave it at {@link ParseMode#PROJ_COMPATIBLE}
         * @return this builder
         */
        public Builder parseMode(ParseMode mode) {
            this.parseMode = mode == null ? ParseMode.PROJ_COMPATIBLE : mode;
            return this;
        }

        /**
         * Sets the authority database, which is what turns operation selection from "the one
         * operation the legacy datum model synthesises" into "the nine the authority published,
         * ranked".
         *
         * <p><b>The context does not take ownership.</b> It never closes the database, because a
         * database handed to a context outlives the caller's stack frame and several contexts may
         * share one. Closing it is the application's business, at shutdown.
         *
         * <pre>{@code
         * ProjDatabase db = Proj4jDb.open();                  // proj4j-db artifact; null if absent
         * ProjContext ctx = ProjContext.builder().database(db).build();
         * CrsOperation op = Proj.createCrsToCrs("EPSG:4267", "EPSG:4269", ctx);
         * op.selectedOperation();                             // EPSG:1241, NAD27 to NAD83 (1)
         * op.accuracy().get().metres();                       // 0.15
         * }</pre>
         *
         * @param db the database, or null for none
         * @return this builder
         */
        public Builder database(ProjDatabase db) {
            this.database = db;
            return this;
        }

        /**
         * Sets the area the caller actually cares about, which operation selection uses to throw out
         * operations that do not cover it.
         *
         * <p>Leaving this unset does <b>not</b> switch the spatial filter off. With no area supplied
         * one is synthesised from the two CRSs' own declared extents, per
         * {@link #sourceTargetCrsExtentUse(SourceTargetCRSExtentUse)}, which is what PROJ does. To
         * get an unfiltered candidate list, set that to {@link SourceTargetCRSExtentUse#NONE} as
         * well as leaving this null.
         *
         * <pre>{@code
         * ProjContext ctx = ProjContext.builder()
         *         .database(db)
         *         .areaOfInterest(new AreaOfUse(-100.5, 40.0, -99.5, 41.0))   // W, S, E, N
         *         .build();
         * }</pre>
         *
         * @param area the area of interest, or null for none (the CRSs' own extents are then used)
         * @return this builder
         */
        public Builder areaOfInterest(AreaOfUse area) {
            this.areaOfInterest = area;
            return this;
        }

        /**
         * Sets how an operation's extent must relate to the area of interest.
         *
         * <p>Null means "the default", and the default is
         * {@link SpatialCriterion#PARTIAL_INTERSECTION} &mdash; the value every PROJ transformation
         * path uses, not the one {@code CoordinateOperationContext}'s header initialises. Coercing
         * null to {@link SpatialCriterion#STRICT_CONTAINMENT} instead would make
         * {@code spatialCriterion(null)} a policy change rather than a no-op, and on
         * {@code EPSG:4267} to {@code EPSG:4269} that particular change is the difference between a
         * transformation and a refusal. See {@link SpatialCriterion} for why the two differ.
         *
         * @param criterion the criterion, or null to leave it at
         *                  {@link SpatialCriterion#PARTIAL_INTERSECTION}
         * @return this builder
         */
        public Builder spatialCriterion(SpatialCriterion criterion) {
            this.spatialCriterion = criterion == null
                    ? SpatialCriterion.PARTIAL_INTERSECTION : criterion;
            return this;
        }

        /**
         * Sets how an area of interest is synthesised from the two CRSs when the caller supplies
         * none.
         *
         * @param use the policy, or null to leave it at {@link SourceTargetCRSExtentUse#SMALLEST}
         * @return this builder
         */
        public Builder sourceTargetCrsExtentUse(SourceTargetCRSExtentUse use) {
            this.sourceTargetCrsExtentUse = use == null
                    ? SourceTargetCRSExtentUse.SMALLEST : use;
            return this;
        }

        /**
         * Builds the immutable context.
         *
         * @return the context; never null
         */
        public ProjContext build() {
            ProjContext built = new ProjContext(axisOrderPolicy, ballparkPolicy, gridPolicy,
                    bestOperationPolicy, domainErrorPolicy, parseMode, esriDatumPolicy, database,
                    areaOfInterest, spatialCriterion, sourceTargetCrsExtentUse);
            return built.equals(DEFAULT) ? DEFAULT : built;
        }
    }
}
