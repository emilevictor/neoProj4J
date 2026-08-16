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
 * Oblated Equal Area, a port of PROJ 9.8.1's {@code src/projections/oea.cpp}
 * ({@code +proj=oea}).
 *
 * <p>Spherical, with an inverse. An oblique azimuthal equal-area whose graticule is
 * then squeezed into an oval by two shape exponents, {@code +n} and {@code +m}, with
 * {@code +theta} rotating the oval about the projection centre.
 *
 * <p><b>Both shape parameters are required and both are rejected at zero</b>
 * ({@code oea.cpp:64-72}, {@code "Invalid value for n: it should be > 0"} and the same
 * for {@code m}). Since {@code pj_param}'s {@code d} sigil returns 0 for an absent key,
 * "missing" and "given as zero" are the same refusal upstream, and they are the same
 * refusal here.
 *
 * <p><b>{@code +theta} has no guard and no default beyond zero.</b> It is read with the
 * {@code r} sigil ({@code oea.cpp:74}), so it accepts every angular syntax
 * {@code dmstor} does — including the bare number that {@code builtins.gie} uses,
 * {@code +theta=3}, which is three <em>degrees</em>.
 *
 * <p><b>Math vs StrictMath:</b> {@link FastStrictTrig} for the direct functions and
 * {@link ProjectionMath#asinChecked}/{@link ProjectionMath#acosChecked} for the inverse
 * ones, which are upstream's {@code aasin}/{@code aacos} with the errno raised as an
 * exception rather than carried in a plausible coordinate.
 *
 * @since 2.2.0
 */
public class OblatedEqualAreaProjection extends Projection {

    private static final long serialVersionUID = 1L;

    private double theta;
    private double m = 0.0;
    private double n = 0.0;

    private double two_r_m, two_r_n, rm, rn, hm, hn;
    private double cp0, sp0;

    /** {@code oea_s_forward}, {@code oea.cpp:18-35}. */
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        final double cp = FastStrictTrig.cos(lpphi);
        final double sp = FastStrictTrig.sin(lpphi);
        final double cl = FastStrictTrig.cos(lplam);
        final double Az = AdamsProjection.aatan2(cp * FastStrictTrig.sin(lplam),
                cp0 * sp - sp0 * cp * cl) + theta;
        final double shz = FastStrictTrig
                .sin(0.5 * ProjectionMath.acosChecked(sp0 * sp + cp0 * cp * cl));
        final double M = ProjectionMath.asinChecked(shz * FastStrictTrig.sin(Az));
        final double N = ProjectionMath.asinChecked(shz * FastStrictTrig.cos(Az)
                * FastStrictTrig.cos(M) / FastStrictTrig.cos(M * two_r_m));
        out.y = n * FastStrictTrig.sin(N * two_r_n);
        out.x = m * FastStrictTrig.sin(M * two_r_m) * FastStrictTrig.cos(N)
                / FastStrictTrig.cos(N * two_r_n);
        return out;
    }

    /** {@code oea_s_inverse}, {@code oea.cpp:37-55}. */
    public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
        final double N = hn * ProjectionMath.asinChecked(xyy * rn);
        final double M = hm * ProjectionMath.asinChecked(
                xyx * rm * FastStrictTrig.cos(N * two_r_n) / FastStrictTrig.cos(N));
        final double xp = 2.0 * FastStrictTrig.sin(M);
        final double yp = 2.0 * FastStrictTrig.sin(N) * FastStrictTrig.cos(M * two_r_m)
                / FastStrictTrig.cos(M);
        final double Az = AdamsProjection.aatan2(xp, yp) - theta;
        final double cAz = FastStrictTrig.cos(Az);
        final double z = 2.0 * ProjectionMath.asinChecked(0.5 * StrictMath.hypot(xp, yp));
        final double sz = FastStrictTrig.sin(z);
        final double cz = FastStrictTrig.cos(z);
        out.y = ProjectionMath.asinChecked(sp0 * cz + cp0 * sz * cAz);
        out.x = AdamsProjection.aatan2(sz * FastStrictTrig.sin(Az), cp0 * cz - sp0 * sz * cAz);
        return out;
    }

    /**
     * {@code +n} - the northing shape exponent, {@code oea.cpp:64-67}. Must be
     * {@code > 0}.
     * <p>
     * {@code Proj4Parser} dispatches {@code +n} per concrete class; this class needs
     * its own branch there or the key is silently dropped and the operator refuses
     * every definition as though {@code +n} were absent.
     */
    public void setN(double n) {
        this.n = n;
    }

    public double getN() {
        return n;
    }

    /** {@code +m} - the easting shape exponent, {@code oea.cpp:69-72}. Must be {@code > 0}. */
    public void setM(double m) {
        this.m = m;
    }

    public double getM() {
        return m;
    }

    /**
     * {@code +theta} - the rotation of the oval about the centre, radians
     * ({@code oea.cpp:74}, {@code r} sigil). Unguarded and defaulting to zero
     * upstream.
     */
    public void setTheta(double theta) {
        this.theta = theta;
    }

    public double getTheta() {
        return theta;
    }

    /**
     * {@code PJ_PROJECTION(oea)}, {@code oea.cpp:57-88}.
     * <p>
     * {@code P->es = 0.} goes <em>before</em> {@code super.initialize()}, which is
     * what derives {@code spherical}, {@code one_es} and {@code e} from it. Setting it
     * afterwards leaves the base class holding an ellipsoid it was never given.
     */
    public void initialize() {
        es = 0.0;
        super.initialize();
        if (n <= 0.0) {
            throw new ProjectionException(ErrorCause.INVALID_PARAM_VALUE, this,
                    "Invalid value for n: it should be > 0, but is " + n);
        }
        if (m <= 0.0) {
            throw new ProjectionException(ErrorCause.INVALID_PARAM_VALUE, this,
                    "Invalid value for m: it should be > 0, but is " + m);
        }
        sp0 = FastStrictTrig.sin(projectionLatitude);
        cp0 = FastStrictTrig.cos(projectionLatitude);
        rn = 1.0 / n;
        rm = 1.0 / m;
        two_r_n = 2.0 * rn;
        two_r_m = 2.0 * rm;
        hm = 0.5 * m;
        hn = 0.5 * n;
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Oblated Equal Area";
    }
}
