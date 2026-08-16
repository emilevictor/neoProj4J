/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=topocentric} ({@code 9.8.1:src/conversions/topocentric.cpp:32-164}):
 * geocentric cartesian {@code (X, Y, Z)} to topocentric east-north-up about a fixed
 * origin. Formulas from IOGP Publication 373-7-2, Geomatics Guidance Note 7 part 2.
 *
 * <p>Both declared sides are {@code PJ_IO_UNITS_CARTESIAN}
 * ({@code topocentric.cpp:161-162}), which is why a geographic input has to be spelled
 * as a pipeline with an explicit {@code +proj=cart} in front of it — and why
 * {@code builtins.gie} has one block of each shape for the same point.
 *
 * <h2>The rotation</h2>
 *
 * <p>{@code topocentric_fwd} ({@code :50-60}) is a translation to the origin followed by
 * the standard ENU rotation; {@code topocentric_inv} ({@code :63-73}) is its transpose,
 * written out rather than derived. Both are transcribed term by term. The matrix is
 * orthogonal, so the inverse is exact rather than iterative, and the corpus asserts
 * {@code roundtrip 1} on both blocks.
 *
 * <h2>The origin can be given two ways, and mixing them is refused</h2>
 *
 * <p>{@code :92-116} spells out presence algebra that is easy to approximate wrongly:
 *
 * <ul>
 * <li>{@code X_0} or {@code lon_0} must be present — otherwise
 *     {@code invalid_op_missing_arg}.</li>
 * <li>Any of {@code X_0}/{@code Y_0}/{@code Z_0} together with any of
 *     {@code lon_0}/{@code lat_0}/{@code h_0} is
 *     {@code invalid_op_mutually_exclusive_args}. Note this is checked <b>before</b> the
 *     completeness checks below, so {@code +X_0=0 +lon_0=0} reports the exclusivity
 *     error and not the missing {@code Y_0}.</li>
 * <li>{@code X_0} requires <em>both</em> {@code Y_0} and {@code Z_0}.</li>
 * <li>{@code lon_0} requires {@code lat_0}, but <b>not</b> {@code h_0} — the comment at
 *     {@code :112} says "allow missing h_0" and it defaults to 0.</li>
 * </ul>
 *
 * <p>Presence is {@code pj_param_exists}, so {@code +X_0} with no value counts as
 * present. Four corpus assertions cover exactly these four refusals.
 *
 * <p>This duplicates what {@code ProjOperatorSetup} already checks on the conformance
 * path. It is duplicated deliberately: {@code ProjOperatorSetup} lives in the
 * conformance module and models the refusal so the gie harness can assert it, while a
 * plain {@code CoordinateTransformFactory} user reaches this constructor with no such
 * gate in front of it. Dropping the check here would turn a refusal into a silently
 * wrong origin for every caller outside the harness.
 *
 * <h2>The origin conversion is {@code +proj=cart} on the inherited ellipsoid</h2>
 *
 * <p>{@code :119-123} creates {@code +proj=cart +a=1} and immediately overwrites its
 * ellipsoid with {@code pj_inherit_ellipsoid_def} ({@code ell_set.cpp:509}), which
 * brute-force copies {@code a}, {@code b}, {@code es} and every derived eccentricity
 * from the outer {@code PJ}. So the {@code +a=1} never takes effect and the conversion
 * runs on this step's own ellipsoid — {@link CartConversion} built from
 * {@link StepEllipsoid}'s {@code (a, es)}, which derives the same quantities by the same
 * route {@code pj_calc_ellipsoid_params} does.
 *
 * <p>The calls are {@code pj_inv3d}/{@code pj_fwd3d}, which do run the generic
 * prepare/finalize on that inner {@code PJ} ({@code inv.cpp:187-219},
 * {@code fwd.cpp}). On a bare {@code +proj=cart} they reduce to nothing: {@code to_meter}
 * and {@code vto_meter} are 1, {@code lam0} and {@code z0} are 0, and the only surviving
 * step is {@code inv_finalize}'s {@code adjlon}, which cannot change a longitude that
 * {@code atan2} has already put in {@code -pi..pi}. Checked rather than assumed, because
 * an inner {@code PJ} that quietly applied the outer step's {@code +to_meter} would
 * scale the origin and nothing else.
 *
 * <h2>{@code +to_meter} scales the topocentric side only</h2>
 *
 * <p>{@code fwd_finalize} multiplies the output by {@code fr_meter} and
 * {@code inv_prepare} multiplies the input by {@code to_meter} — both of which are the
 * <em>right</em> side. The geocentric side is untouched, because neither
 * {@code fwd_prepare} nor {@code inv_finalize} has a {@code CARTESIAN} scaling case.
 * The asymmetry is upstream's and is shared with {@link CartOperator}; the reading of
 * the keys is {@link LinearUnits}.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class TopocentricOperator implements PipelineOperator {

    /** {@code Q->X0}, metres. */
    private final double x0;

    /** {@code Q->Y0}, metres. */
    private final double y0;

    /** {@code Q->Z0}, metres. */
    private final double z0;

    private final double sinphi0;
    private final double cosphi0;
    private final double sinlam0;
    private final double coslam0;

    private final double toMeter;
    private final double frMeter;
    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     * @throws PipelineDefinitionException if the origin is absent, incomplete, or given
     *                                     both ways at once
     */
    TopocentricOperator(final Registry registry, final ProjParams params) {
        final boolean hasX0 = params.has("X_0");
        final boolean hasY0 = params.has("Y_0");
        final boolean hasZ0 = params.has("Z_0");
        final boolean hasLon0 = params.has("lon_0");
        final boolean hasLat0 = params.has("lat_0");
        final boolean hasH0 = params.has("h_0");

        // topocentric.cpp:98-116, in upstream's order. The exclusivity test comes
        // second, so `+X_0=0 +lon_0=0` is an exclusivity error, not a missing Y_0.
        if (!hasX0 && !hasLon0) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "missing X_0 or lon_0");
        }
        if ((hasX0 || hasY0 || hasZ0) && (hasLon0 || hasLat0 || hasH0)) {
            throw new PipelineDefinitionException(
                    PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS,
                    "(X_0,Y_0,Z_0) and (lon_0,lat_0,h_0) are mutually exclusive");
        }
        if (hasX0 && (!hasY0 || !hasZ0)) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "missing Y_0 and/or Z_0");
        }
        if (hasLon0 && !hasLat0) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "missing lat_0");
        }

        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        final CartConversion cart = new CartConversion(ellipsoid[0], ellipsoid[1]);

        final double phi0;
        final double lam0;
        if (hasX0) {
            this.x0 = params.doubleValue("X_0", 0.0);
            this.y0 = params.doubleValue("Y_0", 0.0);
            this.z0 = params.doubleValue("Z_0", 0.0);
            final double[] origin = {x0, y0, z0, 0};
            cart.inverse(origin);
            lam0 = origin[0];
            phi0 = origin[1];
        } else {
            lam0 = StepAngle.radians(params, "lon_0", 0.0);
            phi0 = StepAngle.radians(params, "lat_0", 0.0);
            final double[] origin = {lam0, phi0, params.doubleValue("h_0", 0.0), 0};
            cart.forward(origin);
            this.x0 = origin[0];
            this.y0 = origin[1];
            this.z0 = origin[2];
        }

        this.sinphi0 = Math.sin(phi0);
        this.cosphi0 = Math.cos(phi0);
        this.sinlam0 = Math.sin(lam0);
        this.coslam0 = Math.cos(lam0);

        this.toMeter = LinearUnits.toMeter(params);
        this.frMeter = 1.0 / toMeter;
        this.description = "topocentric X_0=" + x0 + " Y_0=" + y0 + " Z_0=" + z0
                + (toMeter == 1.0 ? "" : " to_meter=" + toMeter);
    }

    /** {@code P->left = PJ_IO_UNITS_CARTESIAN} ({@code topocentric.cpp:161}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.CARTESIAN;
    }

    /** {@code P->right = PJ_IO_UNITS_CARTESIAN} ({@code topocentric.cpp:162}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.CARTESIAN;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code topocentric_fwd} ({@code :50-60}), then {@code fwd_finalize}'s {@code fr_meter}. */
    @Override
    public void forward(final double[] coord) {
        final double dx = coord[0] - x0;
        final double dy = coord[1] - y0;
        final double dz = coord[2] - z0;
        coord[0] = -dx * sinlam0 + dy * coslam0;
        coord[1] = -dx * sinphi0 * coslam0 - dy * sinphi0 * sinlam0 + dz * cosphi0;
        coord[2] = dx * cosphi0 * coslam0 + dy * cosphi0 * sinlam0 + dz * sinphi0;
        if (frMeter != 1.0) {
            coord[0] *= frMeter;
            coord[1] *= frMeter;
            coord[2] *= frMeter;
        }
    }

    /** {@code inv_prepare}'s {@code to_meter}, then {@code topocentric_inv} ({@code :63-73}). */
    @Override
    public void inverse(final double[] coord) {
        if (toMeter != 1.0) {
            coord[0] *= toMeter;
            coord[1] *= toMeter;
            coord[2] *= toMeter;
        }
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        coord[0] = x0 - x * sinlam0 - y * sinphi0 * coslam0 + z * cosphi0 * coslam0;
        coord[1] = y0 + x * coslam0 - y * sinphi0 * sinlam0 + z * cosphi0 * sinlam0;
        coord[2] = z0 + y * cosphi0 + z * sinphi0;
    }

    /** {@code P->inv4d} is always installed ({@code topocentric.cpp:160}). */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "TopocentricOperator[" + description + "]";
    }
}
