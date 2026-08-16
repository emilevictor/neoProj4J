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
 * {@code +proj=vertoffset} ({@code 9.8.1:src/transformations/vertoffset.cpp:30-103}):
 * EPSG coordinate operation method 1046, "Vertical Offset and Slope". It changes the
 * height and nothing else.
 *
 * <pre>
 * h' = h + dh + slope_lat&middot;rho0&middot;(phi - phi0)
 *              + slope_lon&middot;nu0&middot;(lam - lam0)&middot;cos(phi)
 * </pre>
 *
 * <p>{@code rho0} and {@code nu0} are the meridional and prime-vertical radii of
 * curvature at the origin latitude ({@code vertoffset.cpp:96-100}):
 *
 * <pre>
 * s      = sin(phi0)
 * w      = 1 - es&middot;s&sup2;
 * rho0   = a&middot;(1 - es) / (w&middot;sqrt(w))
 * nu0    = a / sqrt(w)
 * </pre>
 *
 * <h2>{@code rho0} is not {@code molodensky}'s {@code RM}, and the difference is
 * deliberate</h2>
 *
 * <p>{@link MolodenskyOperator}'s {@code RM} computes the same quantity as
 * {@code a(1-es)/pow(1 - es&middot;s&sup2;, 1.5)} and carries three special cases —
 * {@code es == 0}, {@code phi == 0} and {@code |phi| == pi/2}. {@code vertoffset} has
 * neither the {@code pow} nor any of the special cases; it writes {@code w * sqrt(w)}
 * for the same power. The two forms are algebraically equal and differ in the last bit,
 * so the shared-looking arithmetic is transcribed separately in each operator rather
 * than factored out. Factoring it out is exactly the kind of tidying that silently
 * ports one operator's rounding into another.
 *
 * <h2>The longitude is relative to {@code +lon_0}, and the output longitude is not</h2>
 *
 * <p>This is the whole reason {@code forward_3d} and {@code reverse_3d} mention
 * {@code P->lam0} at all, and it is easy to get backwards. The generic
 * {@code fwd_prepare} subtracts {@code lam0} from the input longitude
 * ({@code 9.8.1:src/fwd.cpp:108}) but the generic {@code fwd_finalize} does <b>not</b>
 * add it back for a {@code RADIANS} output ({@code fwd.cpp:159-169}) — that asymmetry
 * is right for a map projection, whose output is an easting, and wrong for an operator
 * that must hand back the longitude it was given. So {@code vertoffset.cpp:59} adds
 * {@code P->lam0} to the outgoing {@code x} by hand, and {@code :70} subtracts it on
 * the way in, mirroring {@code inv_finalize} ({@code inv.cpp:113}).
 *
 * <p>Composed, the two cancel: the operator's <em>net</em> effect on longitude is
 * nothing, while the offset formula sees {@code lam - lam0}. Since this engine applies
 * no generic {@code lam0} handling to a pipeline step, that net behaviour is what is
 * written here directly — {@code coord[0]} is never assigned, and the slope term uses
 * {@code coord[0] - lam0}.
 *
 * <p>Verified against the installed 9.8.1 with a probe chosen to discriminate exactly
 * this: at {@code +lon_0=8.183333333333334}, an input longitude of {@code 100} comes
 * back as {@code 100} with {@code z} moved to {@code 471.631245628} — an output that is
 * only reproducible if the slope uses the relative longitude and the output keeps the
 * absolute one. Getting it backwards is a ~92&deg; error in one term and a silent
 * longitude shift in the other.
 *
 * <h2>{@code adjlon} is not applied, and the divergence is upstream's own</h2>
 *
 * <p>{@code fwd_prepare} normalises {@code lam - lam0} into {@code -pi..pi} unless
 * {@code +over} ({@code fwd.cpp:110-112}), while {@code reverse_3d}'s subtraction is
 * raw. So upstream's own forward and inverse stop being mirrors once
 * {@code |lam - lam0| > pi}, and there is no value of {@code +over} that makes both
 * agree. Rather than reproduce a one-sided wrap, this applies neither: the plain
 * subtraction is what both directions use. The regime is unreachable from the corpus —
 * the single {@code vertoffset} block is at {@code lam - lam0 = 1.48}&deg; — and the
 * choice keeps {@link #forward} and {@link #inverse} exact mirrors of each other.
 *
 * <h2>Parameters</h2>
 *
 * <p>{@code +slope_lat} and {@code +slope_lon} are <b>arcseconds per degree of arc</b>
 * and are scaled by {@code ARCSEC_TO_RAD = DEG_TO_RAD / 3600}
 * ({@code vertoffset.cpp:77}); {@code +dh} is metres. All three are {@code pj_param}
 * type {@code 'd'} and default to 0, so a bare {@code +proj=vertoffset} is the
 * identity. {@code +lat_0} and {@code +lon_0} are angular and go through
 * {@link StepAngle}.
 *
 * <p>Nothing in the setup function can fail, so there is no {@code ProjOperatorSetup}
 * branch for it. The only construction-time requirement is
 * {@code PJ_TRANSFORMATION(vertoffset, 1)}'s {@code need_ellps}, which
 * {@code append_default_ellipsoid_to_paralist} satisfies with GRS80 before either side
 * looks.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class VertoffsetOperator implements PipelineOperator {

    /** {@code Q->slope_lat}, radians per radian. */
    private final double slopeLat;

    /** {@code Q->slope_lon}, radians per radian. */
    private final double slopeLon;

    /** {@code Q->zoff}: {@code +dh} in metres. */
    private final double zoff;

    /** {@code P->phi0}: {@code +lat_0} in radians. */
    private final double phi0;

    /** {@code P->lam0}: {@code +lon_0} in radians. */
    private final double lam0;

    /** {@code Q->rho0}: meridional radius of curvature at {@code phi0}. */
    private final double rho0;

    /** {@code Q->nu0}: prime-vertical radius of curvature at {@code phi0}. */
    private final double nu0;

    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    VertoffsetOperator(final Registry registry, final ProjParams params) {
        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        final double a = ellipsoid[0];
        final double es = ellipsoid[1];

        this.slopeLon = params.doubleValue("slope_lon", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
        this.slopeLat = params.doubleValue("slope_lat", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
        this.zoff = params.doubleValue("dh", 0.0);
        this.phi0 = StepAngle.radians(params, "lat_0", 0.0);
        this.lam0 = StepAngle.radians(params, "lon_0", 0.0);

        // vertoffset.cpp:96-100, transcribed including the `w * sqrt(w)` spelling of
        // the 3/2 power.
        final double sinlat0 = Math.sin(phi0);
        final double w = 1 - es * (sinlat0 * sinlat0);
        this.rho0 = a * (1 - es) / (w * Math.sqrt(w));
        this.nu0 = a / Math.sqrt(w);

        this.description = "vertoffset dh=" + zoff
                + " slope_lat=" + params.doubleValue("slope_lat", 0.0)
                + " slope_lon=" + params.doubleValue("slope_lon", 0.0)
                + " lat_0=" + phi0 + " lon_0=" + lam0;
    }

    /** {@code P->left = PJ_IO_UNITS_RADIANS} ({@code vertoffset.cpp:89}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P->right = PJ_IO_UNITS_RADIANS} ({@code vertoffset.cpp:90}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /**
     * {@code get_forward_offset} ({@code vertoffset.cpp:47-52}).
     *
     * @param lam the <b>absolute</b> longitude in radians; the {@code lam0} subtraction
     *            that {@code fwd_prepare} would have done happens here
     * @param phi the latitude in radians
     * @return the height offset in metres
     */
    private double offset(final double lam, final double phi) {
        return zoff + slopeLat * rho0 * (phi - phi0)
                + slopeLon * nu0 * (lam - lam0) * Math.cos(phi);
    }

    /** {@code forward_3d} ({@code vertoffset.cpp:54-63}), net of the {@code lam0} pair. */
    @Override
    public void forward(final double[] coord) {
        coord[2] += offset(coord[0], coord[1]);
    }

    /** {@code reverse_3d} ({@code vertoffset.cpp:65-74}), net of the {@code lam0} pair. */
    @Override
    public void inverse(final double[] coord) {
        coord[2] -= offset(coord[0], coord[1]);
    }

    /** {@code P->inv3d} is always installed ({@code vertoffset.cpp:87}). */
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
        return "VertoffsetOperator[" + description + "]";
    }
}
