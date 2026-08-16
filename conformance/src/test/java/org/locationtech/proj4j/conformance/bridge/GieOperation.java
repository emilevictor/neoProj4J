/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import org.locationtech.proj4j.gie.GieDirection;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * One gie {@code operation} (or {@code crs_src}/{@code crs_dst} pair), reduced
 * to what a corpus runner needs: can it run, in what units, and what does it
 * produce.
 *
 * <p>Instances are <em>not</em> thread-safe. {@link #transform} mutates
 * {@link #lastFailure()}, and the underlying proj4j {@code Projection} objects
 * are mutable (and, for {@code +proj=cass}, mutated on the hot path). Create one
 * per runner thread.
 *
 * <p><b>Everything here is radians-and-metres, never degrees.</b> The runner owns
 * the degree conversion that {@code gie}'s {@code torad_coord}/{@code todeg_coord}
 * perform; this interface does not repeat it. Feeding degrees into
 * {@link #transform} on a {@link GieIoUnits#RADIANS} side produces silent
 * nonsense.
 */
public interface GieOperation {

    /**
     * {@code true} when {@link #transform} may be called. When {@code false},
     * {@link #failure()} says why not and {@link #transform} always returns
     * {@code null}.
     */
    boolean isUsable();

    /**
     * The construction-time classification, or {@code null} when
     * {@link #isUsable()}.
     */
    GieFailure failure();

    /**
     * PROJ's {@code P->left} — the declared unit domain of the operation's
     * left-hand (angular, for a projection) side, <em>before</em>
     * {@link GieIoUnits#folded() folding} and <em>before</em> the {@code +inv}
     * swap. Feed it, {@link #rightUnits()} and {@link #isInverted()} to
     * {@link GieIoUnits#outputUnits} to pick the comparison metric.
     *
     * <p>For every {@code PROJ_HEAD} projection this is
     * {@link GieIoUnits#RADIANS} ({@code proj_internal.h:882-883}). For
     * {@code +proj=longlat} it is {@code RADIANS} on both sides.
     *
     * <p>An unusable operation still reports units, so a runner can format a
     * report without branching; treat them as unspecified in that case.
     */
    GieIoUnits leftUnits();

    /**
     * PROJ's {@code P->right}. For every {@code PROJ_HEAD} projection this is
     * {@link GieIoUnits#CLASSIC}, which {@link GieIoUnits#folded() folds} to
     * {@link GieIoUnits#PROJECTED} and therefore selects the Euclidean metric.
     * That single fact is why every forward projection row in the corpus is
     * compared in metres and every {@code direction inverse} row geodesically.
     */
    GieIoUnits rightUnits();

    /**
     * {@code P->inverted}: {@code +inv} appeared in the definition. Two
     * independent consequences, both already handled here — the unit sides swap
     * (see {@link GieIoUnits#pjLeft}), and {@link #transform} runs the opposite
     * of the requested direction ({@code proj_trans} negates {@code dir} when
     * {@code P->inverted}).
     */
    boolean isInverted();

    /**
     * gie's {@code T.crs_dst_is_lat_lon_or_y_x}, which triggers the ordinate
     * swap in the comparator. Upstream sets it from
     * {@code isLatOrNorthingFirst(pj_dst)} — an axis-name match on "latitude" or
     * "northing" — at {@code src/apps/gie.cpp:751}, and applies it in
     * <em>two</em> places, not one: the degrees branch at {@code gie.cpp:1147},
     * just before {@code proj_lpz_dist}, and the {@code else} (Euclidean) branch
     * at {@code gie.cpp:1155}, just before {@code proj_xyz_dist}. In the
     * Euclidean branch it cannot change the answer, because the swap is applied
     * to both operands and {@code proj_xyz_dist} is a hypot over their
     * differences ({@code src/dist.cpp:94}); {@code GieComparator} ports both
     * sites anyway, and says so at its {@code default:} case.
     *
     * <p><b>Known limitation: this always returns {@code false}.</b> proj4j
     * carries no axis-order metadata on a {@code CoordinateReferenceSystem} —
     * {@code Projection.axes} defaults to {@code AxisOrder.ENU} and is only ever
     * populated from an explicit {@code +axis=}, never from the EPSG database, so
     * there is nothing to read. Fixing that needs axis metadata in the EPSG
     * module, which is out of this bridge's scope.
     *
     * <p>What that limitation actually costs is not what an earlier version of
     * this comment claimed. It said "roughly one degree of arc", which is wrong
     * in both of the cases the corpus can reach:
     *
     * <ul>
     *   <li><b>A projected latitude-first target costs nothing at all.</b> The
     *   only such row reachable today is {@code epsg_no_grid.gie}'s
     *   {@code EPSG:4123 → EPSG:2393} ("Finland YKJ Northing, Easting"). Its
     *   expectation is in metres, so it takes the Euclidean branch, where the
     *   swap is the no-op described above — the flag's value cannot move that
     *   row's deviation by so much as an ulp. Its real deviation is 342.4 km,
     *   and that comes from axis order on <em>both</em> sides at once: the
     *   source is read longitude-first while a PROJ.4 {@code +proj=tmerc} emits
     *   {@code (E, N)} against a northing-first authority definition. The two
     *   errors partially cancel; each alone is about 4,700–5,000 km.
     *   <b>Implementing {@code CrsToCrsOperation.crsDstIsLatLonOrYX()} does not
     *   fix this row.</b> Anyone who wants it has to fix the source-side read as
     *   well, and should measure the two changes as a 2×2 rather than in
     *   sequence, or the first one will look worthless.</li>
     *   <li><b>A geographic latitude-first target costs a NaN, not a
     *   degree.</b> There the comparison is in degrees, so the swap is live and
     *   the deviation goes through {@code proj_lp_dist}, which hands its first
     *   ordinate to {@code geod_inverse} as a latitude
     *   ({@code src/dist.cpp:69}). With latitude and longitude transposed that
     *   argument leaves ±90° — {@code geod -I} on a "latitude" of 151.2077
     *   returns {@code nan} — and {@code proj_lpz_dist} then hypots the NaN, so
     *   the whole deviation is NaN and the row fails without a usable
     *   magnitude. The candidate rows are the {@code EPSG:7843 → EPSG:7912}
     *   pair, which currently fail earlier for an unrelated reason, so this is
     *   the mechanism rather than a present-day measurement.</li>
     * </ul>
     */
    boolean crsDstIsLatLonOrYX();

    /**
     * Run the operation on one coordinate.
     *
     * @param in  {@code {x, y, z, t}}; shorter arrays are zero-extended. On a
     *            {@link GieIoUnits#RADIANS} side, {@code x} is longitude in
     *            <em>radians</em> and {@code y} is latitude in radians.
     * @param dir the direction as written on the gie {@code direction} line.
     *            {@code +inv} is applied on top of this, not instead of it.
     * @return a fresh 4-element array, or {@code null} if this point failed — in
     *         which case {@link #lastFailure()} is set. Never returns a
     *         partially-valid coordinate: a failure is never expressed as a
     *         plausible number.
     *
     *         <p>A non-finite output is a {@link GieFailureKind#NUMERICAL}
     *         failure, because proj4j returns {@code NaN} silently in 62
     *         enumerated places. The one carve-out is PROJ's documented
     *         "when given NaNs, return NaNs" behaviour
     *         ({@code more_builtins.gie:791}): if the corresponding input
     *         ordinate was itself {@code NaN}, a {@code NaN} output is a
     *         <em>result</em> and is returned, so the comparator's
     *         NaN-both-sides branch can fire. Infinities are always failures.
     */
    double[] transform(double[] in, GieDirection dir);

    /**
     * The failure from the most recent {@link #transform} call, or {@code null}
     * if that call succeeded. Undefined before the first call.
     */
    GieFailure lastFailure();
}
