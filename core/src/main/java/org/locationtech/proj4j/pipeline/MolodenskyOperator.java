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

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=molodensky}
 * ({@code 9.8.1:src/transformations/molodensky.cpp:52-354}): the Molodensky datum
 * transformation, standard and abridged, applied directly in geodetic coordinates.
 *
 * <p>It computes an offset {@code (dphi, dlam, dh)} from a three-parameter translation
 * {@code (dx, dy, dz)} plus the differences in semi-major axis and flattening
 * {@code (da, df)}, and adds it to the coordinate. Both declared sides are
 * {@code PJ_IO_UNITS_RADIANS} ({@code :317-318}).
 *
 * <h2>The inverse is not the inverse</h2>
 *
 * <p>{@code pj_molodensky_reverse_3d} ({@code :271-293}) evaluates the offset at the
 * <b>target</b> coordinate and subtracts it, rather than solving for the source. The two
 * directions therefore do not compose to the identity; they compose to something within
 * a metre or so of it. This is why {@code more_builtins.gie}'s two real blocks assert
 * {@code roundtrip 100 1 m} — a hundred round trips against a one-metre budget — while
 * the all-zero-parameter block, where the offset is identically zero and the
 * approximation is exact, gets {@code roundtrip 1} at 1 mm. Reproduced as written; an
 * iterative "improvement" here would fail the very assertions that pin the behaviour.
 *
 * <h2>{@code RM} and {@code RN}, transcribed with their special cases</h2>
 *
 * <p>{@code RN} ({@code :68-83}) is {@code a / sqrt(1 - es sin(phi)^2)}, with an
 * {@code es == 0} short circuit. {@code RM} ({@code :85-118}) is
 * {@code a(1 - es) / pow(1 - es sin(phi)^2, 1.5)} with <em>three</em> short circuits from
 * Krakiwsky &amp; Thomson's eq. 13, 13a and 13b: {@code es == 0}, {@code phi == 0}
 * exactly, and {@code |phi| == pi/2} exactly. All three are exact floating-point
 * comparisons and all three are kept. They are not redundant: at {@code phi == 0} the
 * general form evaluates {@code pow(1.0, 1.5)}, which is exactly 1, so the branch is a
 * no-op there — but at {@code |phi| == pi/2} the general form and {@code a/sqrt(1-es)}
 * differ in the last bits, and dropping the branch would move results that upstream
 * pins.
 *
 * <p>{@link VertoffsetOperator} needs the same meridional radius and deliberately does
 * <b>not</b> call this one: it spells the 3/2 power as {@code w * sqrt(w)} and has none
 * of the special cases. The forms are algebraically equal and differ in the last bit, so
 * each operator keeps its own transcription.
 *
 * <h2>{@code P->f} is derived from {@code es}, and that costs 0.3 nanometres</h2>
 *
 * <p>Both branches read {@code P->f} directly ({@code :131}, {@code :198}), and
 * {@code pj_calc_ellipsoid_params} keeps the {@code +rf} the user gave verbatim rather
 * than round-tripping it. {@link StepEllipsoid} hands back only {@code (a, es)}, so
 * {@code f} is recovered as {@code 1 - cos(asin(sqrt(es)))} — which is the expression
 * {@code ell_set.cpp:597-598} itself uses on the other path, and the one
 * {@link CartConversion} already relies on.
 *
 * <p>Measured against the corpus definition {@code +a=6378160 +rf=298.25}: the recovered
 * {@code f} differs from {@code 1/298.25} by {@code 4.510e-17} absolute,
 * {@code 1.345e-14} relative, which propagates to at most {@code 2.877e-10} metres of
 * coordinate. The tightest tolerance on any {@code molodensky} assertion is 1 mm, so the
 * margin is six orders of magnitude. Recorded as a measurement rather than an assurance,
 * because "negligible" is the word under which real errors hide.
 *
 * <h2>{@code +abridged} is a presence test, not a boolean</h2>
 *
 * <p>{@code :349} reads it as {@code pj_param(… "tabridged").i} — {@code 't'}, which
 * returns 1 if the key appears <em>at all</em>. So {@code +abridged=no} and
 * {@code +abridged=false} both select the abridged branch, exactly as
 * {@code +abridged} alone does. Reading it as a boolean would silently pick the other
 * formula for those spellings, so {@link ProjParams#has} is used rather than
 * {@code booleanValue}.
 *
 * <h2>All five of {@code dx dy dz da df} are required</h2>
 *
 * <p>{@code :320-348} tests each with {@code 't'} in that order and refuses with
 * {@code PROJ_ERR_INVALID_OP_MISSING_ARG} naming the first one missing. Two corpus
 * assertions cover it: no arguments at all, and {@code +dx=0} alone. A value of zero is
 * present, so the all-zeros block is legal and is the identity.
 *
 * <h2>Out-of-domain coordinates throw</h2>
 *
 * <p>Three denominators are checked against exact zero and set {@code lam = HUGE_VAL},
 * which the callers turn into
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} ({@code :249-252},
 * {@code :283-286}): {@code rho + z} and {@code (nu + z) cos(phi)} in the standard
 * branch ({@code :141-155}), and {@code RN cos(phi)} in the abridged one
 * ({@code :199-204}). This engine reports failure by throwing, so each becomes a throw.
 * The abridged branch's division by {@code RM} is <b>not</b> guarded upstream and is not
 * guarded here.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class MolodenskyOperator implements PipelineOperator {

    private static final double HALF_PI = Math.PI / 2.0;

    private final double dx;
    private final double dy;
    private final double dz;
    private final double da;
    private final double df;
    private final boolean abridged;

    private final double a;
    private final double es;

    /** {@code P->f}, recovered from {@code es} the way {@code ell_set.cpp:597-598} forms it. */
    private final double f;

    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     * @throws PipelineDefinitionException if any of {@code dx dy dz da df} is absent
     */
    MolodenskyOperator(final Registry registry, final ProjParams params) {
        // molodensky.cpp:320-348, in upstream's order so the reported name matches.
        require(params, "dx");
        this.dx = params.doubleValue("dx", 0.0);
        require(params, "dy");
        this.dy = params.doubleValue("dy", 0.0);
        require(params, "dz");
        this.dz = params.doubleValue("dz", 0.0);
        require(params, "da");
        this.da = params.doubleValue("da", 0.0);
        require(params, "df");
        this.df = params.doubleValue("df", 0.0);

        this.abridged = params.has("abridged");

        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        this.a = ellipsoid[0];
        this.es = ellipsoid[1];
        this.f = 1.0 - Math.cos(Math.asin(Math.sqrt(es)));

        this.description = "molodensky dx=" + dx + " dy=" + dy + " dz=" + dz
                + " da=" + da + " df=" + df + (abridged ? " abridged" : "");
    }

    /** {@code if (!pj_param(P->ctx, P->params, "t<key>").i)}. */
    private static void require(final ProjParams params, final String key) {
        if (!params.has(key)) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "missing " + key);
        }
    }

    /** {@code RN} ({@code molodensky.cpp:68-83}): prime vertical radius of curvature. */
    private double rn(final double phi) {
        final double s = Math.sin(phi);
        if (es == 0) {
            return a;
        }
        return a / Math.sqrt(1 - es * s * s);
    }

    /** {@code RM} ({@code molodensky.cpp:85-118}): meridian radius of curvature. */
    private double rm(final double phi) {
        final double s = Math.sin(phi);
        if (es == 0) {
            return a;
        }
        /* eq. 13a */
        if (phi == 0) {
            return a * (1 - es);
        }
        /* eq. 13b */
        if (Math.abs(phi) == HALF_PI) {
            return a / Math.sqrt(1 - es);
        }
        /* eq. 13 */
        return (a * (1 - es)) / Math.pow(1 - es * s * s, 1.5);
    }

    /**
     * {@code calc_standard_params} ({@code molodensky.cpp:120-167}).
     *
     * @param lam longitude in radians
     * @param phi latitude in radians
     * @param z   height in metres
     * @return {@code {dlam, dphi, dh}} — note the array order is the coordinate's, while
     *         upstream's struct order in the source text is {@code phi} first
     * @throws CrsTransformException if either guarded denominator is exactly zero
     */
    private double[] standard(final double lam, final double phi, final double z) {
        final double slam = Math.sin(lam);
        final double clam = Math.cos(lam);
        final double sphi = Math.sin(phi);
        final double cphi = Math.cos(phi);

        final double rho = rm(phi);
        final double nu = rn(phi);

        double dphi = (-dx * sphi * clam) - (dy * sphi * slam) + (dz * cphi)
                + ((nu * es * sphi * cphi * da) / a)
                + (sphi * cphi * (rho / (1 - f) + nu * (1 - f)) * df);
        final double dphiDenom = rho + z;
        if (dphiDenom == 0.0) {
            throw outsideDomain("rho + z");
        }
        dphi /= dphiDenom;

        final double dlamDenom = (nu + z) * cphi;
        if (dlamDenom == 0.0) {
            throw outsideDomain("(nu + z) * cos(phi)");
        }
        final double dlam = (-dx * slam + dy * clam) / dlamDenom;

        final double dh = dx * cphi * clam + dy * cphi * slam + dz * sphi - (a / nu) * da
                + nu * (1 - f) * sphi * sphi * df;

        return new double[] {dlam, dphi, dh};
    }

    /**
     * {@code calc_abridged_params} ({@code molodensky.cpp:169-208}).
     *
     * @param lam longitude in radians
     * @param phi latitude in radians
     * @return {@code {dlam, dphi, dh}}
     * @throws CrsTransformException if {@code RN cos(phi)} is exactly zero
     */
    private double[] abridged(final double lam, final double phi) {
        final double slam = Math.sin(lam);
        final double clam = Math.cos(lam);
        final double sphi = Math.sin(phi);
        final double cphi = Math.cos(phi);

        final double adffda = (a * df + f * da);

        double dphi = -dx * sphi * clam - dy * sphi * slam + dz * cphi
                + adffda * Math.sin(2 * phi);
        // Upstream does not guard this division; RM is non-zero for any ellipsoid
        // StepEllipsoid will accept.
        dphi /= rm(phi);

        double dlam = -dx * slam + dy * clam;
        final double dlamDenom = rn(phi) * cphi;
        if (dlamDenom == 0.0) {
            throw outsideDomain("RN * cos(phi)");
        }
        dlam /= dlamDenom;

        final double dh = dx * cphi * clam + dy * cphi * slam + dz * sphi - da
                + adffda * sphi * sphi;

        return new double[] {dlam, dphi, dh};
    }

    /** {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}. */
    private static CrsTransformException outsideDomain(final String denominator) {
        return new CrsTransformException(ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                "molodensky: " + denominator + " is zero, so the coordinate is outside "
                        + "the projection domain");
    }

    /** {@code pj_molodensky_forward_3d} ({@code :237-260}). */
    @Override
    public void forward(final double[] coord) {
        final double[] d = abridged
                ? abridged(coord[0], coord[1])
                : standard(coord[0], coord[1], coord[2]);
        coord[0] += d[0];
        coord[1] += d[1];
        coord[2] += d[2];
    }

    /** {@code pj_molodensky_reverse_3d} ({@code :271-293}): the offset at the target. */
    @Override
    public void inverse(final double[] coord) {
        final double[] d = abridged
                ? abridged(coord[0], coord[1])
                : standard(coord[0], coord[1], coord[2]);
        coord[0] -= d[0];
        coord[1] -= d[1];
        coord[2] -= d[2];
    }

    /** {@code P->left = PJ_IO_UNITS_RADIANS} ({@code molodensky.cpp:317}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P->right = PJ_IO_UNITS_RADIANS} ({@code molodensky.cpp:318}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code P->inv4d}, {@code P->inv3d} and {@code P->inv} are all installed ({@code :307-312}). */
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
        return "MolodenskyOperator[" + description + "]";
    }
}
