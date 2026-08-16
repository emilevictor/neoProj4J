/*******************************************************************************
 * Copyright 2026
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
 *******************************************************************************/

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Chamberlin Trimetric, a port of PROJ 9.8.1's {@code src/projections/chamb.cpp}
 * ({@code +proj=chamb}).
 *
 * <p>Spherical, and <b>forward only</b> — {@code PJ_PROJECTION(chamb)} sets
 * {@code P->fwd} and never {@code P->inv} ({@code chamb.cpp:148}), so there is nothing
 * to port for the reverse direction and {@link #hasInverse()} is {@code false}.
 *
 * <p>The construction is the draughtsman's one it is named for: three control points
 * are chosen, their mutual great-circle distances fix a triangle in the plane, and each
 * mapped point is placed by striking an arc of true distance from each of the three and
 * averaging the three intersections. That average is why the projection is neither
 * conformal nor equal-area and why it has no closed-form inverse: {@code chamb.cpp:97-98}
 * literally multiplies the accumulated coordinate by a third.
 *
 * <p><b>The one refusal.</b> Two coincident control points give a zero-length side and
 * are rejected ({@code chamb.cpp:126-132}, {@code "Invalid value for control points:
 * they should be distinct"}). Note what is <em>not</em> rejected: upstream's own comment
 * at {@code :133} is {@code "co-linearity problem ignored for now"}, and
 * {@code builtins.gie}'s only {@code chamb} block is exactly that degenerate case —
 * {@code +lat_1=0.5 +lat_2=2} with every other control ordinate defaulting to zero, so
 * all three points sit on the prime meridian. PROJ answers it, so we answer it. The
 * law-of-cosines ratios come out at {@code ±1} to within a few ulps, which is inside
 * {@link ProjectionMath#ONE_TOL} and so clamps rather than raising.
 *
 * <p><b>Every control ordinate defaults to zero and none is required</b>, because all
 * six are read with {@code pj_param}'s {@code r} sigil and an absent {@code r} key
 * returns 0. A bare {@code +proj=chamb} therefore has three coincident points and is
 * refused by the distinctness test, not by a missing-parameter test.
 *
 * <p><b>Math vs StrictMath:</b> {@link FastStrictTrig} for the direct functions,
 * {@link ProjectionMath#asinChecked}/{@link ProjectionMath#acosChecked} for
 * upstream's {@code aasin}/{@code aacos}, and {@link StrictMath#atan2} for the azimuth
 * — upstream uses the <em>unguarded</em> {@code atan2} there ({@code chamb.cpp:48}),
 * not {@code aatan2}, and the guard it does apply is the {@code |v.r| > TOL} test one
 * line above.
 *
 * @since 2.2.0
 */
public class ChamberlinTrimetricProjection extends Projection {

    private static final long serialVersionUID = 1L;

    /** {@code chamb.cpp:30}. Written out rather than {@code 1.0 / 3.0}, as upstream is. */
    private static final double THIRD = 0.333333333333333333;

    /** {@code chamb.cpp:31}. The distance below which a control point counts as reached. */
    private static final double TOL = 1e-9;

    // The three control points, in the order chamb.cpp indexes them.
    private double lat3;
    private double lon1, lon2, lon3;

    private final double[] cPhi = new double[3];
    private final double[] cLam = new double[3];
    private final double[] cCosPhi = new double[3];
    private final double[] cSinPhi = new double[3];
    /** {@code c[i].v.r}, the great-circle distance from control point {@code i} to {@code i+1}. */
    private final double[] cvR = new double[3];
    /** {@code c[i].v.Az}, the azimuth of that same side. */
    private final double[] cvAz = new double[3];
    /** {@code c[i].p}, where control point {@code i} lands in the plane. */
    private final double[] cpX = new double[3];
    private final double[] cpY = new double[3];

    private double pX, pY;
    private double beta_1, beta_2;

    /**
     * {@code vect}, {@code chamb.cpp:34-52}: the great-circle distance and azimuth from
     * one point to another, written into {@code out[0]} and {@code out[1]}.
     * <p>
     * The short-distance branch is not an optimisation. {@code aacos} of a cosine near
     * 1 loses half the significant digits; the haversine form the {@code else} arm uses
     * does not, and one radian is where upstream switches.
     */
    private static void vect(double dphi, double c1, double s1, double c2, double s2,
            double dlam, double[] out) {
        final double cdl = FastStrictTrig.cos(dlam);
        double r;
        if (Math.abs(dphi) > 1.0 || Math.abs(dlam) > 1.0) {
            r = ProjectionMath.acosChecked(s1 * s2 + c1 * c2 * cdl);
        } else { /* more accurate for smaller distances */
            final double dp = FastStrictTrig.sin(0.5 * dphi);
            final double dl = FastStrictTrig.sin(0.5 * dlam);
            r = 2.0 * ProjectionMath.asinChecked(Math.sqrt(dp * dp + c1 * c2 * dl * dl));
        }
        double az;
        if (Math.abs(r) > TOL) {
            az = StrictMath.atan2(c2 * FastStrictTrig.sin(dlam), c1 * s2 - s1 * c2 * cdl);
        } else {
            r = az = 0.0;
        }
        out[0] = r;
        out[1] = az;
    }

    /** {@code lc}, the spherical law of cosines, {@code chamb.cpp:55-57}. */
    private static double lc(double b, double c, double a) {
        return ProjectionMath.acosChecked(0.5 * (b * b + c * c - a * a) / (b * c));
    }

    /** {@code chamb_s_forward}, {@code chamb.cpp:59-101}. */
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        final double sinphi = FastStrictTrig.sin(lpphi);
        final double cosphi = FastStrictTrig.cos(lpphi);
        final double[] vR = new double[3];
        final double[] vAz = new double[3];
        final double[] scratch = new double[2];

        int i;
        for (i = 0; i < 3; ++i) { /* dist/azimuths from control */
            vect(lpphi - cPhi[i], cCosPhi[i], cSinPhi[i], cosphi, sinphi, lplam - cLam[i],
                    scratch);
            vR[i] = scratch[0];
            vAz[i] = scratch[1];
            if (vR[i] == 0.0) {
                break;
            }
            vAz[i] = ProjectionMath.adjlon(vAz[i] - cvAz[i]);
        }
        if (i < 3) { /* current point at control point */
            out.x = cpX[i];
            out.y = cpY[i];
            return out;
        }
        /* point mean of intercepts */
        double x = pX;
        double y = pY;
        for (i = 0; i < 3; ++i) {
            final int j = i == 2 ? 0 : i + 1;
            double a = lc(cvR[i], vR[i], vR[j]);
            if (vAz[i] < 0.0) {
                a = -a;
            }
            if (i == 0) { /* coord comp unique to each arc */
                x += vR[i] * FastStrictTrig.cos(a);
                y -= vR[i] * FastStrictTrig.sin(a);
            } else if (i == 1) {
                a = beta_1 - a;
                x -= vR[i] * FastStrictTrig.cos(a);
                y -= vR[i] * FastStrictTrig.sin(a);
            } else {
                a = beta_2 - a;
                x += vR[i] * FastStrictTrig.cos(a);
                y += vR[i] * FastStrictTrig.sin(a);
            }
        }
        out.x = x * THIRD; /* mean of arc intercepts */
        out.y = y * THIRD;
        return out;
    }

    /**
     * {@code +lat_3} - the latitude of the third control point, radians
     * ({@code chamb.cpp:113-114}, {@code r} sigil). {@code +lat_1} and {@code +lat_2}
     * arrive through the parser's universal dispatch into
     * {@code projectionLatitude1}/{@code projectionLatitude2}; there is no third slot
     * on the base class, so this one is local.
     */
    public void setLat3(double lat3) {
        this.lat3 = lat3;
    }

    public double getLat3() {
        return lat3;
    }

    /** {@code +lon_1} - the longitude of the first control point, radians. */
    public void setLon1(double lon1) {
        this.lon1 = lon1;
    }

    public double getLon1() {
        return lon1;
    }

    /** {@code +lon_2} - the longitude of the second control point, radians. */
    public void setLon2(double lon2) {
        this.lon2 = lon2;
    }

    public double getLon2() {
        return lon2;
    }

    /** {@code +lon_3} - the longitude of the third control point, radians. */
    public void setLon3(double lon3) {
        this.lon3 = lon3;
    }

    public double getLon3() {
        return lon3;
    }

    /**
     * {@code PJ_PROJECTION(chamb)}, {@code chamb.cpp:103-151}.
     * <p>
     * {@code P->es = 0.} goes before {@code super.initialize()}, which is what derives
     * {@code spherical}, {@code one_es} and {@code e} from it.
     * <p>
     * The control longitudes are reduced by {@code +lon_0} and wrapped
     * ({@code chamb.cpp:117}), which matters because the forward kernel is handed a
     * longitude the funnel has already reduced by {@code +lon_0}: both sides of
     * {@code lp.lam - Q->c[i].lam} have to be in the same frame.
     */
    public void initialize() {
        es = 0.0;
        super.initialize();

        final double[] phis = {projectionLatitude1, projectionLatitude2, lat3};
        final double[] lams = {lon1, lon2, lon3};
        for (int i = 0; i < 3; ++i) { /* get control point locations */
            cPhi[i] = phis[i];
            cLam[i] = ProjectionMath.adjlon(lams[i] - projectionLongitude);
            cCosPhi[i] = FastStrictTrig.cos(cPhi[i]);
            cSinPhi[i] = FastStrictTrig.sin(cPhi[i]);
        }
        final double[] scratch = new double[2];
        for (int i = 0; i < 3; ++i) { /* inter ctl pt. distances and azimuths */
            final int j = i == 2 ? 0 : i + 1;
            vect(cPhi[j] - cPhi[i], cCosPhi[i], cSinPhi[i], cCosPhi[j], cSinPhi[j],
                    cLam[j] - cLam[i], scratch);
            cvR[i] = scratch[0];
            cvAz[i] = scratch[1];
            if (cvR[i] == 0.0) {
                throw new ProjectionException(ErrorCause.INVALID_PARAM_VALUE, this,
                        "Invalid value for control points: they should be distinct "
                                + "(points " + (i + 1) + " and " + (j + 1) + " coincide)");
            }
            /* co-linearity problem ignored for now */
        }
        final double beta_0 = lc(cvR[0], cvR[2], cvR[1]);
        beta_1 = lc(cvR[0], cvR[1], cvR[2]);
        beta_2 = Math.PI - beta_0;
        cpY[0] = cvR[2] * FastStrictTrig.sin(beta_0);
        cpY[1] = cpY[0];
        pY = 2.0 * cpY[0];
        cpY[2] = 0.0;
        cpX[1] = 0.5 * cvR[0];
        cpX[0] = -cpX[1];
        cpX[2] = cpX[0] + cvR[2] * FastStrictTrig.cos(beta_0);
        pX = cpX[2];
    }

    /** {@code chamb.cpp} never assigns {@code P->inv}. */
    public boolean hasInverse() {
        return false;
    }

    public String toString() {
        return "Chamberlin Trimetric";
    }
}
