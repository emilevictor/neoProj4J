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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.locationtech.proj4j.spi.DbExtent;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;

/**
 * One coordinate operation the authority publishes between two CRSs, with everything needed to
 * decide whether it can be used and how it compares with the others.
 *
 * <p>Produced by {@link Proj#candidateOperations(Crs, Crs)}, which requires a
 * {@link org.locationtech.proj4j.spi.ProjDatabase} on the {@link ProjContext}: without one there is
 * nothing to enumerate, because the legacy engine synthesises exactly one operation per CRS pair
 * from its datum model.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>{@code EPSG:4267} to {@code EPSG:4269} has <b>nine</b> published grid transformations, with
 * accuracies from 0.15&nbsp;m to 2.0&nbsp;m, and not one of them is ballpark. The historic defect was
 * never that the authority offered nothing; it was that Proj4J could not see the offer, so it applied
 * no shift and reported success &mdash; 95.573&nbsp;m at San Francisco, finite and plausible. This
 * class is the offer, made visible: which operations exist, which of them this deployment can
 * actually execute, which grid files each needs, and what each one claims for accuracy.
 *
 * <h2>Ranking is this library's policy, not the database's</h2>
 *
 * <p>{@link org.locationtech.proj4j.spi.ProjDatabase#operationsBetween} returns rows in
 * {@code (kind, authority, code)} order and <em>never</em> by accuracy: the database has no policy.
 * The ranking is {@link Proj#candidateOperations(Crs, Crs)}'s, it is a total order so that ties are
 * never left to chance, and it is documented on that method. {@link #rank()} is this candidate's
 * position in it, and {@link #rejectionReason()} says why a candidate that was not selected could
 * not be.
 *
 * <h2>Direction</h2>
 *
 * <p>The authority publishes an operation in exactly one direction. A candidate whose
 * {@link #isInverted()} is {@code true} was published the other way round and would have to be
 * executed inverted &mdash; which changes parameter signs, grid direction, and whether an inverse
 * exists at all. That fact is carried explicitly rather than folded into the operation, because
 * losing it is how a shift gets applied with the wrong sign: twice the error, still plausible.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @see Proj#candidateOperations(Crs, Crs)
 * @see CrsOperation#selectedOperation()
 * @since 2.0.0
 */
public final class CrsOperationCandidate implements Comparable<CrsOperationCandidate> {

    /**
     * Why a candidate cannot be used by this deployment. {@link #NONE} is the good case, and the
     * order of the remaining constants is <b>not</b> the ranking order &mdash; see
     * {@link Proj#candidateOperations(Crs, Crs)} for that.
     */
    public enum Rejection {

        /** Usable: the method is implemented, every grid resolves, and it is not ballpark. */
        NONE,

        /**
         * The authority's method maps to a PROJ operator this facade will not run for a
         * {@link Crs}-to-{@link Crs} datum change &mdash; {@code gridshift}, {@code xyzgridshift},
         * {@code tinshift}, {@code velocity_grid}, {@code defmodel}, a deformation pipeline needing
         * a time dimension, or a concatenated operation. Becomes
         * {@link org.locationtech.proj4j.ErrorCause#UNSUPPORTED_OPERATION_METHOD}.
         *
         * <p>"Will not run here" and "is not implemented" are not the same thing, and this list
         * mixes both. {@code tinshift} and, since 2.2.0, {@code xyzgridshift} exist in the pipeline
         * engine and can be reached through {@code +proj=pipeline}; they are refused here for
         * reasons of their own &mdash; xyzgridshift works in geocentric cartesian metres and a
         * {@code Crs} is two-dimensional by construction, so there is no height to convert with.
         * {@code gridshift} and {@code defmodel} are not implemented anywhere in this library. The
         * wording here used to call all of them unimplemented, which stopped being true as the
         * pipeline engine grew operators; the per-operator reason lives in
         * {@code OperationSelector.operatorNote} and is reported in the exception message.
         */
        UNSUPPORTED_METHOD,

        /**
         * The method is implemented but at least one grid file it needs is not reachable through any
         * configured resolver. Becomes
         * {@link org.locationtech.proj4j.ErrorCause#BEST_OPERATION_UNAVAILABLE}, and
         * {@link #missingGrids()} names the files &mdash; <b>all</b> of them, including the second
         * grid of a NADCON {@code .las}/{@code .los} pair.
         */
        MISSING_GRID,

        /**
         * A datum change performed with no parameters and no stated accuracy, i.e. one that is not
         * actually performed. Becomes
         * {@link org.locationtech.proj4j.ErrorCause#BALLPARK_REJECTED} under
         * {@link BallparkPolicy#REJECT}.
         */
        BALLPARK,

        /** The authority has deprecated this operation. */
        DEPRECATED,

        /**
         * A {@code supersession} row names a replacement that connects the <em>same</em> CRS pair and
         * is itself a candidate here, so this one is not a substitute for anything.
         */
        SUPERSEDED
    }

    private final DbOperation operation;
    private final boolean inverted;
    private final boolean synthesisedBallpark;
    private final Accuracy accuracy;
    private final List<GridInfo> grids;
    private final AreaOfUse areaOfUse;
    private final Rejection rejection;
    private final String rejectionReason;
    private final String methodNote;
    private final int rank;

    CrsOperationCandidate(DbOperation operation, boolean inverted, boolean synthesisedBallpark,
                          Accuracy accuracy, List<GridInfo> grids, AreaOfUse areaOfUse,
                          Rejection rejection, String rejectionReason, String methodNote, int rank) {
        this.operation = operation;
        this.inverted = inverted;
        this.synthesisedBallpark = synthesisedBallpark;
        this.accuracy = accuracy;
        this.grids = Collections.unmodifiableList(grids);
        this.areaOfUse = areaOfUse;
        this.rejection = rejection;
        this.rejectionReason = rejectionReason;
        this.methodNote = methodNote;
        this.rank = rank;
    }

    /** A copy of this candidate with a rank assigned. Used once, after sorting. */
    CrsOperationCandidate withRank(int newRank) {
        return new CrsOperationCandidate(operation, inverted, synthesisedBallpark, accuracy,
                new ArrayList<GridInfo>(grids), areaOfUse, rejection, rejectionReason, methodNote,
                newRank);
    }

    // ------------------------------------------------------------------------------- identity

    /**
     * The authority's own row, verbatim and unconverted &mdash; method, parameters in the
     * authority's units, grid names as the authority spells them, steps.
     *
     * <p>For the one candidate Proj4J synthesises rather than reads (see
     * {@link #isSynthesisedBallpark()}) this is a stand-in row with authority {@code PROJ} and no
     * parameters, exactly as PROJ's own {@code projinfo} reports {@code unknown id} for it.
     *
     * @return the operation; never null
     */
    public DbOperation operation() {
        return operation;
    }

    /**
     * {@code "EPSG:1241"}.
     *
     * @return the authority-qualified code; never null
     */
    public String authorityCode() {
        return operation.authName() + ":" + operation.code();
    }

    /**
     * {@code "NAD27 to NAD83 (1)"} &mdash; the name a caller needs in order to know what was chosen,
     * and to look it up in the EPSG registry.
     *
     * @return the operation name; never null
     */
    public String name() {
        return operation.name();
    }

    /**
     * Whether this operation was published in the opposite direction and would have to be executed
     * inverted. See the class javadoc.
     *
     * @return true iff the authority publishes this as target-to-source
     */
    public boolean isInverted() {
        return inverted;
    }

    /**
     * Whether this candidate is the ballpark offset Proj4J synthesised because the datums differ and
     * the authority publishes no operation, rather than a row read from the database.
     *
     * <p>PROJ synthesises the same thing, and stores it no more than Proj4J does: there is not one
     * {@code Ballpark geographic offset} row anywhere in the shipped database. So a synthesised
     * ballpark is not a gap in the data; it is what "no published operation" looks like once it has
     * been made visible instead of silent.
     *
     * @return true iff this candidate was synthesised
     */
    public boolean isSynthesisedBallpark() {
        return synthesisedBallpark;
    }

    /**
     * Whether this is a ballpark transformation, i.e. a datum change with no parameters and no
     * stated accuracy.
     *
     * @return true iff ballpark
     */
    public boolean isBallpark() {
        return rejection == Rejection.BALLPARK || synthesisedBallpark;
    }

    // ------------------------------------------------------------------------------- quality

    /**
     * The accuracy the authority published, in metres.
     *
     * <p>Empty means the authority published none, which for a ballpark operation is permanent and
     * structural. It is never substituted with {@code 0.0} or an estimate: an invented accuracy is
     * exactly what lets a ballpark candidate win a ranking.
     *
     * @return the accuracy, or empty
     */
    public Optional<Accuracy> accuracy() {
        return Optional.ofNullable(accuracy);
    }

    /**
     * The extent over which the authority declares this operation valid, <b>database-derived</b>, so
     * {@link AreaOfUse#isDatabaseDerived()} is true.
     *
     * <p>When an operation declares several usages this is the smallest by
     * {@link org.locationtech.proj4j.spi.DbExtent#rankingArea()}, with ties broken on the extent
     * code, because that is the one used for ranking. Empty when the operation declares no usage or
     * when its extent publishes no bounding box &mdash; 18 upstream extents do not, and they are
     * reported as absent rather than as the whole world.
     *
     * @return the area of use, or empty
     */
    public Optional<AreaOfUse> areaOfUse() {
        return Optional.ofNullable(areaOfUse);
    }

    // ------------------------------------------------------------------------------- grids

    /**
     * Every grid file this operation needs, one entry per authority grid slot, in slot order.
     *
     * <p><b>Both slots.</b> NADCON splits the latitude and longitude shifts across a
     * {@code .las}/{@code .los} pair, and 150 of the 1,062 grid transformations in the shipped
     * database have a second grid. {@code EPSG:1241}, the most important transformation in the
     * consumer's workload, is one of them: it needs {@code conus.las} <em>and</em> {@code conus.los}.
     * A selector that reads only the first slot applies half the shift and reports success.
     *
     * <p>What a slot resolves <em>to</em> is a separate question, and the answer is often one file for
     * two slots: PROJ's {@code grid_alternatives} maps {@code conus.las} to the GeoTIFF
     * {@code us_noaa_conus.tif}, which carries both shifts, so the pair collapses. That collapse is
     * reported by {@link GridInfo#satisfiedBy()} rather than by dropping the second slot, so the
     * authority's requirement and this deployment's substitution are both visible.
     *
     * @return an unmodifiable list in slot order; never null, and empty for a parameterised operation
     */
    public List<GridInfo> grids() {
        return grids;
    }

    /**
     * The grid files this operation needs and no configured resolver can find.
     *
     * @return an unmodifiable list; never null, and empty is the good case
     */
    public List<GridInfo> missingGrids() {
        List<GridInfo> missing = new ArrayList<GridInfo>(grids.size());
        for (int i = 0; i < grids.size(); i++) {
            if (!grids.get(i).isAvailable()) {
                missing.add(grids.get(i));
            }
        }
        return Collections.unmodifiableList(missing);
    }

    // ------------------------------------------------------------------------------- usability

    /**
     * Whether this deployment can execute this operation right now.
     *
     * @return true iff {@link #rejection()} is {@link Rejection#NONE}
     */
    public boolean isUsable() {
        return rejection == Rejection.NONE;
    }

    /**
     * Why this candidate cannot be used, or {@link Rejection#NONE}.
     *
     * @return the rejection category; never null
     */
    public Rejection rejection() {
        return rejection;
    }

    /**
     * Why this candidate cannot be used, in words, naming the method or the files.
     *
     * @return the reason, or empty iff {@link #isUsable()}
     */
    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    /**
     * What the authority's method maps to here &mdash; the PROJ operator name for a grid operation,
     * or the reason there is no mapping.
     *
     * @return the note, or empty
     */
    public Optional<String> methodNote() {
        return Optional.ofNullable(methodNote);
    }

    /**
     * This candidate's position in the ranking, {@code 0} being best.
     *
     * @return a zero-based rank
     */
    public int rank() {
        return rank;
    }

    // ------------------------------------------------------------------------------- ordering

    /**
     * The total order described on {@link Proj#candidateOperations(Crs, Crs)}, ported criterion by
     * criterion from PROJ 9.8.1's {@code SortFunction::compare}
     * ({@code src/iso19111/operation/coordinateoperationfactory.cpp}), whose own comment says
     * <em>"the order of the comparisons is extremely important"</em>. It is taken literally here: the
     * chain below is upstream's sequence, in upstream's sequence, and each step names the upstream
     * criterion it came from. Reasoning about which criterion <em>ought</em> to dominate is how the
     * two orders drift apart.
     *
     * <p>The one thing to know before reading it: PROJ has <b>two</b> accuracy criteria and they
     * straddle area. Whether an accuracy is <em>known</em> is asked before area; how <em>good</em> it
     * is, after. So a 21&nbsp;m operation covering all of Great Britain outranks a 1&nbsp;m one
     * covering one estuary, and that is deliberate on both sides: an accuracy figure is only
     * meaningful where the operation applies at all.
     *
     * <h4>Deliberate divergences from 9.8.1</h4>
     *
     * <ul>
     * <li><b>The usability tier comes first</b>, where PROJ spreads the same information over its
     *     criteria 1 ({@code isPROJExportable}), 2 ({@code isApprox}) and 5
     *     ({@code gridsAvailable}). Folding them into one tier keeps the ranked list readable as an
     *     answer to "what can I do about this?", which is what {@link #describe()} is for. The tier
     *     order agrees with PROJ everywhere except one pair: PROJ ranks an unusable method
     *     <em>above</em> a ballpark, this ranks it below. See {@link #usabilityPenalty()} for why,
     *     and note that {@link OperationSelector} picks by category rather than by rank, so the
     *     disagreement does not change what gets selected.</li>
     * <li><b>The final tiebreak is {@link #ref()}, not PROJ's {@code return a_name > b_name}.</b>
     *     Upstream's last criterion is a name comparison, deliberately inverted so that
     *     {@code "Amersfoort to WGS 84 (4)"} precedes {@code "(3)"}. It is a guess, it is documented
     *     upstream as arbitrary, and it is not a total order: two operations can share a name. This
     *     library treats determinism as a gate rather than a preference, so it ends on the authority
     *     reference and then the direction, which cannot tie. Every criterion above it is PROJ's.</li>
     * <li><b>Criterion 12 (the IOGP 373-07-7 ETRF2000 hub rule) is not ported.</b> Two reasons. It
     *     needs the names of the operation's source and target CRSs, which the index does not carry
     *     on a {@link DbOperation}; and upstream's first branch tests
     *     {@code uses_ETRF2000_ && ... && !uses_ETRF2000_}, a contradiction, so only its mirror
     *     branch can ever fire. Porting half a rule faithfully is worse than not porting it.</li>
     * <li><b>Criterion 14 (fewer PROJ pipeline steps) is not ported</b>: candidates are not exported
     *     to PROJ pipeline strings here, so there is no step count to compare. Criterion 13, the
     *     authority's own step count, is ported and carries the same intent.</li>
     * <li><b>Criterion 15 ({@code BALLPARK_GEOGRAPHIC_OFFSET_FROM} similar-CRS preference) is not
     *     ported</b>: it only fires when two candidates are both ballpark offsets, and exactly one
     *     ballpark is ever synthesised per CRS pair — the shipped database contains no ballpark rows
     *     at all.</li>
     * <li>PROJ's criteria 3 and 4 (ballpark-vertical, and the {@code NULL_}/{@code BALLPARK_} name
     *     prefixes) are already applied upstream of the sort, by {@link OperationSelector}, which
     *     classifies those names as {@link Rejection#BALLPARK}.</li>
     * </ul>
     *
     * @param other the candidate to compare against
     * @return a negative number if this candidate ranks better
     */
    @Override
    public int compareTo(CrsOperationCandidate other) {
        // [proj4j, PROJ 1+2+5] Usability tier. See usabilityPenalty() and the divergence note above.
        int c = Integer.compare(usabilityPenalty(), other.usabilityPenalty());
        if (c != 0) {
            return c;
        }
        // [PROJ 6] A grid the database can at least name a source for beats one we know nothing
        //          about, even when neither is on disk.
        c = Integer.compare(gridsKnown() ? 0 : 1, other.gridsKnown() ? 0 : 1);
        if (c != 0) {
            return c;
        }
        // [PROJ 7] A known accuracy beats an unknown one. Note this asks only *whether* it is known.
        c = compareAccuracyKnown(accuracy, other.accuracy);
        if (c != 0) {
            return c;
        }
        // [PROJ 8] Both unknown: prefer the one that at least uses a grid, since a parameterless
        //          operation of unstated accuracy is doing nothing at all.
        if (accuracy == null && other.accuracy == null) {
            c = Integer.compare(hasGrids() ? 0 : 1, other.hasGrids() ? 0 : 1);
            if (c != 0) {
                return c;
            }
        }
        // [PROJ 9] The larger non-zero area of use, in upstream's exact branch shape, asymmetric
        //          zero handling included. See compareArea.
        c = compareArea(areaOfUse, other.areaOfUse);
        if (c != 0) {
            return c;
        }
        // [PROJ 10] Now, and only now, the accuracy magnitude, ascending.
        c = compareAccuracyMagnitude(accuracy, other.accuracy);
        if (c != 0) {
            return c;
        }
        // [PROJ 11] Same accuracy: prefer the one *without* grids, which needs no files to run.
        if (accuracy != null && other.accuracy != null
                && Double.compare(accuracy.metres(), other.accuracy.metres()) == 0) {
            c = Integer.compare(hasGrids() ? 1 : 0, other.hasGrids() ? 1 : 0);
            if (c != 0) {
                return c;
            }
        }
        // [PROJ 13] The fewer intermediate steps, the better.
        c = Integer.compare(stepCount(), other.stepCount());
        if (c != 0) {
            return c;
        }
        // [PROJ 16] The shorter name, the better. Upstream's own comment ends in a question mark;
        //           it is kept because dropping it would reorder ties upstream does not tie.
        c = Integer.compare(name().length(), other.name().length());
        if (c != 0) {
            return c;
        }
        // [PROJ 17] The one hardcoded name preference upstream carries: NTF (Paris) to NTF (1) over
        //           (2), because the remarks on (2) say OGP prefers the IGN Paris value.
        c = compareNtfParis(name(), other.name());
        if (c != 0) {
            return c;
        }
        // [PROJ 18, diverged] The authority reference, then direction. Nothing is left tied, so the
        //           result cannot depend on the order the database returned rows in. See above for
        //           why this is not upstream's name comparison.
        c = ref().compareTo(other.ref());
        if (c != 0) {
            return c;
        }
        return Boolean.compare(inverted, other.inverted);
    }

    /**
     * The usability tier, lower being better. Ordered by <b>what a caller can do about it</b>, which
     * is the only ordering that makes a ranked list actionable:
     *
     * <ol start="0">
     * <li>{@link Rejection#NONE} &mdash; nothing to do.</li>
     * <li>{@link Rejection#MISSING_GRID} &mdash; add a file to the classpath and it works. The best
     *     kind of failure.</li>
     * <li>{@link Rejection#UNSUPPORTED_METHOD} &mdash; a capability boundary; nothing you add will
     *     change it.</li>
     * <li>{@link Rejection#BALLPARK} &mdash; executable, and useless: it applies no shift.</li>
     * <li>{@link Rejection#SUPERSEDED} &mdash; the authority has a better one for the same job.</li>
     * <li>{@link Rejection#DEPRECATED} &mdash; the authority has withdrawn it.</li>
     * </ol>
     *
     * <p>Note that this tier is <b>not</b> what {@link BestOperationPolicy#REQUIRE_BEST} compares. A
     * usable 2.0&nbsp;m operation ranks above an unavailable 0.15&nbsp;m one, because it is the one you
     * can have; whether choosing it is a <em>degradation</em> is a question about accuracy, answered
     * separately by {@link #isDegradedRelativeTo}. Conflating the two would either make the policy
     * unfireable or make the ranked list unreadable.
     */
    private int usabilityPenalty() {
        switch (rejection) {
            case NONE:
                return 0;
            case MISSING_GRID:
                return 1;
            case UNSUPPORTED_METHOD:
                return 2;
            case BALLPARK:
                return 3;
            case SUPERSEDED:
                return 4;
            default:
                return 5;
        }
    }

    private DbObjectRef ref() {
        return operation.ref();
    }

    /**
     * Whether every grid slot resolves to something the database can account for &mdash; on disk, or
     * named by a {@code grid_alternatives} row that gives a public URL. PROJ's criterion 6 also
     * counts membership of a distributable package; the index carries no package column, so that
     * arm is simply absent rather than guessed at.
     *
     * <p><b>A slot an earlier slot already carries is skipped, and leaving it in was a real
     * mis-ranking.</b> {@code EPSG:1241} declares {@code conus.las} and {@code conus.los}; upstream's
     * {@code gridsNeeded()} collapses those to one {@code us_noaa_conus.tif} before it computes
     * {@code gridsKnown_}, so upstream weighs one slot and this library holds two. Only the first has
     * a {@code grid_alternatives} row &mdash; and 84 of the 85 distinct {@code grid2_name}s in the
     * index are like that, so this is the normal case, not a data gap &mdash; which made the second
     * slot look unaccounted for and pushed {@code EPSG:1241} below {@code EPSG:1573} (Quebec,
     * pseudo-area 4.12, against CONUS-and-EEZ's 22.54) in the real-database ordering. Measured
     * against {@code projinfo -s EPSG:4267 -t EPSG:4269 --summary --spatial-test intersects} at
     * 9.8.1, skipping the carried slot is what puts {@code EPSG:1241} back at third where PROJ has it.
     * See {@link GridInfo#isCarriedByEarlierSlot()}.
     */
    private boolean gridsKnown() {
        for (int i = 0; i < grids.size(); i++) {
            GridInfo g = grids.get(i);
            if (g.isCarriedByEarlierSlot()) {
                continue;
            }
            if (!g.isAvailable() && !g.knownUrl().isPresent()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasGrids() {
        return !grids.isEmpty();
    }

    /**
     * PROJ's {@code getStepCount}: one, unless this is a concatenated operation, in which case the
     * number of steps the authority declares.
     */
    private int stepCount() {
        if (operation.kind() == DbObjectType.CONCATENATED_OPERATION) {
            int steps = operation.steps().size();
            return steps == 0 ? 1 : steps;
        }
        return 1;
    }

    /** PROJ criterion 7: a published accuracy, whatever it says, beats none. */
    private static int compareAccuracyKnown(Accuracy a, Accuracy b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : 0;
    }

    /**
     * PROJ criterion 10: the smaller figure wins. Only reached once both are known or both unknown,
     * so an unknown is never compared against a number and never treated as zero.
     */
    private static int compareAccuracyMagnitude(Accuracy a, Accuracy b) {
        if (a == null || b == null) {
            return 0;
        }
        return Double.compare(a.metres(), b.metres());
    }

    /**
     * The full ordering by accuracy: known before unknown, then ascending. Not a criterion of
     * {@link #compareTo} &mdash; there the two halves straddle area, which is the whole point of the
     * port &mdash; but it is what an accuracy-only question means, and
     * {@link #isBetterAccuracyThan} asks exactly that.
     */
    private static int compareAccuracy(Accuracy a, Accuracy b) {
        int c = compareAccuracyKnown(a, b);
        return c != 0 ? c : compareAccuracyMagnitude(a, b);
    }

    /**
     * PROJ criterion 9, transcribed: <em>"Operations with larger non-zero area of use go before
     * those with lower one"</em>. Upstream reads
     *
     * <pre>
     * if (areaA &gt; 0) {
     *     if (areaA &gt; areaB) return true;
     *     if (areaA &lt; areaB) return false;
     * } else if (areaB &gt; 0) {
     *     return false;
     * }
     * </pre>
     *
     * and the branch shape is kept rather than simplified, because the asymmetry is the interesting
     * part: a zero area never beats a positive one, but two zero areas are a tie rather than a
     * verdict, so the criteria below get to decide. An absent bounding box <b>is</b> zero here, not
     * the whole world &mdash; 18 upstream extents publish none, and treating those as global would
     * make them win every comparison.
     *
     * <p>This is <b>the reverse of what this comparator did before 2.2.0</b>, which preferred the
     * tighter extent on the theory that a specific operation is a better fit. Measured against
     * {@code projinfo}, that theory is wrong often enough to matter: for OSGB36 to WGS 84 it put the
     * ranked list in a different order from PROJ's at six of eight positions.
     */
    private static int compareArea(AreaOfUse a, AreaOfUse b) {
        double areaA = pseudoArea(a);
        double areaB = pseudoArea(b);
        if (areaA > 0) {
            if (areaA > areaB) {
                return -1;
            }
            if (areaA < areaB) {
                return 1;
            }
        } else if (areaB > 0) {
            return 1;
        }
        return 0;
    }

    /**
     * PROJ's {@code getPseudoArea}, which now lives in {@link Extents} because operation selection
     * grew a second caller: the spatial filter measures the two CRS extents with it to decide which
     * is {@link SourceTargetCRSExtentUse#SMALLEST}, and per-coordinate selection measures operation
     * extents with it to break an accuracy tie. One transcription of one upstream function, so that
     * "larger area wins" here and "smaller area wins" there cannot silently come to disagree about
     * what an area is.
     *
     * @param a the area, or null for an operation that declares no usable extent
     * @return the pseudo-area, or {@code 0.0} when there is no bounding box to measure
     */
    private static double pseudoArea(AreaOfUse a) {
        return Extents.pseudoArea(a);
    }

    /**
     * PROJ criterion 17. Two operations connect NTF (Paris) to NTF and upstream hardcodes a
     * preference between them, because the remarks on {@code EPSG:1764} record that OGP prefers the
     * IGN Paris value used by {@code EPSG:1763}. Ported explicitly rather than left to the tiebreak:
     * the reference order happens to give the same answer for the only two rows that exist, but it
     * would be giving it by accident, and the rule is an authority preference, not a tie.
     *
     * <p>Upstream carries a second pair for {@code "NTF (Paris) to RGF93 v1 (1)"} against
     * {@code "(2)"}. Ported alongside it for fidelity even though the 9.8.1 database contains no
     * such rows &mdash; the cost is two string comparisons and the alternative is a silent gap.
     */
    private static int compareNtfParis(String a, String b) {
        int c = preferNtf(a, b, "NTF (Paris) to NTF (1)", "NTF (Paris) to NTF (2)");
        if (c != 0) {
            return c;
        }
        return preferNtf(a, b, "NTF (Paris) to RGF93 v1 (1)", "NTF (Paris) to RGF93 v1 (2)");
    }

    private static int preferNtf(String a, String b, String preferred, String other) {
        if (a.contains(preferred) && b.contains(other)) {
            return -1;
        }
        if (a.contains(other) && b.contains(preferred)) {
            return 1;
        }
        return 0;
    }

    /**
     * Whether this candidate has a strictly better published accuracy than {@code other}, ties broken
     * on the authority reference so the comparison is a total order.
     *
     * <p>Accuracy <em>only</em>, unlike {@link #compareTo}: area does not enter into it. This answers
     * "which of these is the more accurate operation", which is a different question from "which
     * should be offered first".
     *
     * @param other the candidate to compare against
     * @return true iff this one should win an accuracy-only comparison
     */
    boolean isBetterAccuracyThan(CrsOperationCandidate other) {
        int c = compareAccuracy(accuracy, other.accuracy);
        if (c != 0) {
            return c < 0;
        }
        return ref().compareTo(other.ref()) < 0;
    }

    /**
     * Whether this candidate is strictly less accurate than {@code other}, which is the only thing
     * {@link BestOperationPolicy#REQUIRE_BEST} refuses.
     *
     * <p>Deliberately <em>strictly</em>. Two operations tied on accuracy are not a degradation of one
     * another, and refusing a tie would make the default policy reject
     * {@code EPSG:4267 -> EPSG:4269} outright: {@code EPSG:1241} (NADCON, 0.15&nbsp;m, executable
     * here) and {@code EPSG:8555} (NADCON5, 0.15&nbsp;m, not executable here) are tied, and rejecting
     * both would be a worse answer than either. An unknown accuracy on this side against a known one
     * on the other <em>is</em> a degradation, because "we do not know" cannot be shown to be as good.
     *
     * @param other the better-ranked candidate to compare against
     * @return true iff choosing this one over {@code other} loses accuracy
     */
    boolean isDegradedRelativeTo(CrsOperationCandidate other) {
        if (other == null) {
            return false;
        }
        if (accuracy == null) {
            return other.accuracy != null;
        }
        if (other.accuracy == null) {
            return false;
        }
        return accuracy.metres() > other.accuracy.metres();
    }

    // ------------------------------------------------------------------------------- describe

    /**
     * One line: code, name, accuracy, direction, usability, and the files.
     *
     * @return the description, without a trailing newline; never null
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(rank).append(' ').append(authorityCode()).append(", ").append(name());
        sb.append(", ").append(accuracy == null ? "accuracy unknown" : accuracy.metres() + " m");
        if (inverted) {
            sb.append(", INVERTED (published as ")
                    .append(operation.sourceCrs() == null ? "?" : operation.sourceCrs().authorityCode())
                    .append(" -> ")
                    .append(operation.targetCrs() == null ? "?" : operation.targetCrs().authorityCode())
                    .append(')');
        }
        if (methodNote != null) {
            sb.append(", ").append(methodNote);
        }
        if (areaOfUse != null && areaOfUse.description() != null) {
            sb.append(", ").append(areaOfUse.description());
        }
        sb.append(isUsable() ? ", USABLE" : ", " + rejection + ": " + rejectionReason);
        if (!grids.isEmpty()) {
            sb.append("\n        grids required by the authority (").append(grids.size())
                    .append(") --");
            for (int i = 0; i < grids.size(); i++) {
                sb.append("\n          slot ").append(i + 1).append(": ")
                        .append(grids.get(i).describe());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CrsOperationCandidate[" + authorityCode() + ", " + name()
                + (accuracy == null ? ", accuracy unknown" : ", " + accuracy.metres() + " m")
                + (inverted ? ", inverted" : "")
                + (isUsable() ? "" : ", " + rejection) + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrsOperationCandidate)) {
            return false;
        }
        CrsOperationCandidate that = (CrsOperationCandidate) o;
        return inverted == that.inverted && ref().equals(that.ref());
    }

    @Override
    public int hashCode() {
        return 31 * ref().hashCode() + (inverted ? 1 : 0);
    }

    /**
     * The smallest extent an operation declares, by ranking area, ties broken on the extent code so
     * the choice is deterministic. Package-private: {@link OperationSelector} builds candidates.
     *
     * <p>PROJ takes {@code domains[0]} instead &mdash; the first usage row, in whatever order the
     * database hands them over. This picks the smallest because that is reproducible, and because an
     * operation that declares several usages is generally best characterised by the tightest one.
     * Under {@link #compareTo}'s "larger area first" the two choices differ in direction: for a
     * multi-usage operation this understates the area PROJ would have measured, so such an operation
     * can rank below where {@code projinfo} puts it. Multi-usage transformations are rare in the
     * shipped database and none appeared in the pairs measured for 2.2.0, but the divergence is real
     * and is recorded here rather than discovered later.
     *
     * <p>Note also that this measures with {@link DbExtent#rankingArea()}, a flat
     * {@code lonSpan * latSpan} rectangle, whereas {@link #compareTo} measures with PROJ's
     * latitude-weighted pseudo-area. That is not an oversight: this one is only choosing
     * <em>which</em> of an operation's own extents to carry, where a cheap consistent measure is
     * enough, and changing it would change {@link #areaOfUse()} for callers.
     *
     * @param extents the extents the database returned, in its own order
     * @return the chosen extent, or null if none has a bounding box
     */
    static DbExtent smallestExtent(List<DbExtent> extents) {
        DbExtent best = null;
        for (int i = 0; i < extents.size(); i++) {
            DbExtent e = extents.get(i);
            if (!e.hasBoundingBox()) {
                continue;
            }
            if (best == null) {
                best = e;
                continue;
            }
            int c = Double.compare(e.rankingArea(), best.rankingArea());
            if (c < 0 || (c == 0 && e.ref().compareTo(best.ref()) < 0)) {
                best = e;
            }
        }
        return best;
    }
}
