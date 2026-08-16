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

package org.locationtech.proj4j.util;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjectionException;

/**
 * Meridional distance on the unit ellipsoid and its inverse, a port of PROJ 9.8.1's
 * {@code src/proj_mdist.cpp} ({@code proj_mdist_ini}, {@code proj_mdist},
 * {@code proj_inv_mdist}).
 *
 * <p><b>This is not {@link MeridianArc}, and the two must not be swapped for one
 * another.</b> {@code MeridianArc} is {@code mlfn.cpp}: a fixed 6th-order expansion in
 * the third flattening {@code n}, closed-form in both directions, and accurate only
 * while {@code |f| <= 1/150}. This class is Evenden's {@code es}-series: the
 * coefficient count is <em>data dependent</em> — the generator stops as soon as a term
 * makes no difference to the running sum — so it converges for any {@code es < 1}, and
 * its inverse is a Newton iteration rather than a series. The two agree to about a
 * nanometre on GRS80, which is precisely why porting the wrong one would go unnoticed
 * until an exotic ellipsoid arrived.
 *
 * <p>Its only upstream consumer at 9.8.1 is {@code src/projections/rouss.cpp}; the
 * whole of {@code rouss}'s 33-coefficient series is written against <em>this</em>
 * definition of meridional distance, so parity requires this series and not a better
 * one.
 *
 * <p><b>One deliberate deviation from upstream.</b> {@code proj_inv_mdist} reports
 * non-convergence by setting {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
 * and then <em>returning the latitude anyway</em> ({@code proj_mdist.cpp:122-125}). In
 * PROJ that errno turns the transform's output into {@code HUGE_VAL}, so the plausible
 * looking latitude never escapes; in a library that has no ambient errno it would, so
 * {@link #invMdist(double)} throws instead. Same refusal, same trigger, no coordinate
 * that looks like an answer.
 *
 * <p>Instances are immutable and thread safe. Construct one per ellipsoid and reuse it.
 *
 * <p><b>Math vs StrictMath:</b> construction is exact arithmetic and no transcendentals
 * at all. {@link #mdist(double, double, double)} takes its sine and cosine as arguments
 * and uses only {@code sqrt}, which IEEE 754 makes exact. The Newton loop in
 * {@link #invMdist(double)} needs a sine and a cosine of its own and uses
 * {@link FastStrictTrig}, so the iterate — and therefore the trip count, and therefore
 * the answer — is identical on every platform.
 *
 * @see MeridianArc
 * @since 2.2.0
 */
public final strictfp class MDist implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code proj_mdist.cpp:37}. Bounds both the series generator and the Newton loop. */
    private static final int MAX_ITER = 20;

    /** {@code proj_mdist.cpp:38}. The Newton loop's convergence bar, on the step. */
    private static final double TOL = 1e-14;

    private final double es;

    /** {@code MDIST.E}, the coefficient of {@code phi} in the linear term. */
    private final double bigE;

    /** {@code MDIST.b[0 .. nb]}. Its length is {@code nb + 1} and is data dependent. */
    private final double[] b;

    /** {@code MDIST.nb}, the index of the last coefficient, i.e. {@code b.length - 1}. */
    private final int nb;

    /**
     * Builds the series for a given first eccentricity squared, {@code proj_mdist_ini}
     * ({@code proj_mdist.cpp:48-93}).
     *
     * <p>The generator runs until a term leaves the running sum bit-identical
     * ({@code if (Es == El) break}), so the number of coefficients depends on
     * {@code es}: 1 for a sphere, 8 for GRS80. That early exit is an exact
     * floating-point equality on purpose and is reproduced as written — a tolerance
     * here would change the coefficient count and so the answer.
     *
     * @param es the square of the first eccentricity, {@code 0 <= es < 1}
     */
    public MDist(double es) {
        double numf, numfi, twon1, denf, denfi, ens, T, twon;
        double den, El = 1.0, Es = 1.0;
        final double[] terms = new double[MAX_ITER];
        terms[0] = 1.0;
        int i;

        /* generate E(e^2) and its terms terms[] */
        ens = es;
        numf = twon1 = denfi = 1.0;
        denf = 1.0;
        twon = 4.0;
        for (i = 1; i < MAX_ITER; ++i) {
            numf *= (twon1 * twon1);
            den = twon * denf * denf * twon1;
            T = numf / den;
            Es -= (terms[i] = T * ens);
            ens *= es;
            twon *= 4.0;
            denf *= ++denfi;
            twon1 += 2.0;
            if (Es == El) /* jump out if no change */
                break;
            El = Es;
        }
        this.nb = i - 1;
        this.es = es;
        this.bigE = Es;

        /* generate b_n coefficients--note: collapse with prefix ratios */
        final double[] bs = new double[i];
        bs[0] = Es = 1.0 - Es;
        numf = denf = 1.0;
        numfi = 2.0;
        denfi = 3.0;
        for (int j = 1; j < i; ++j) {
            Es -= terms[j];
            numf *= numfi;
            denf *= denfi;
            bs[j] = Es * numf / denf;
            numfi += 2.0;
            denfi += 2.0;
        }
        this.b = bs;
    }

    /**
     * Distance from the equator along the meridian to {@code phi}, on the unit
     * ellipsoid. {@code proj_mdist} ({@code proj_mdist.cpp:94-106}).
     *
     * <p>The sine and cosine are arguments rather than computed here because every
     * caller already has them, and because it keeps this method free of
     * transcendentals.
     *
     * @param phi  the latitude, radians
     * @param sphi {@code sin(phi)}
     * @param cphi {@code cos(phi)}
     * @return the meridional arc length, in units of the semi-major axis
     */
    public double mdist(double phi, double sphi, double cphi) {
        final double sc = sphi * cphi;
        final double sphi2 = sphi * sphi;
        final double D = phi * bigE - es * sc / Math.sqrt(1.0 - es * sphi2);
        int i = nb;
        double sum = b[i];
        while (i != 0) {
            sum = b[--i] + sphi2 * sum;
        }
        return D + sc * sum;
    }

    /**
     * The inverse of {@link #mdist(double, double, double)}: the latitude whose
     * meridional arc length is {@code dist}. {@code proj_inv_mdist}
     * ({@code proj_mdist.cpp:107-126}).
     *
     * <p>Newton, at most {@link #MAX_ITER} steps, seeded with {@code dist} itself and
     * stopping on a step smaller than {@link #TOL}. Note that upstream tests the
     * <em>step</em> after applying it, so a converged call always performs one more
     * evaluation than strictly needed; that is reproduced, because the extra step
     * changes the last bits of the result.
     *
     * @param dist the meridional arc length, in units of the semi-major axis
     * @return the latitude, radians
     * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} if the
     *         iteration has not converged after {@link #MAX_ITER} steps; see the class
     *         comment for why this is a throw and not a return
     */
    public double invMdist(double dist) {
        final double k = 1.0 / (1.0 - es);
        double phi = dist;
        for (int i = MAX_ITER; i-- != 0;) {
            final double s = FastStrictTrig.sin(phi);
            double t = 1.0 - es * s * s;
            t = (mdist(phi, s, FastStrictTrig.cos(phi)) - dist) * (t * Math.sqrt(t)) * k;
            phi -= t;
            if (Math.abs(t) < TOL) /* that is no change */
                return phi;
        }
        /* convergence failed */
        throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                "inverse meridional distance did not converge in " + MAX_ITER
                        + " steps for dist = " + dist + " (es = " + es + ")");
    }

    /** The {@code es} this series was built for. */
    public double getEs() {
        return es;
    }

    /**
     * The number of coefficients the generator produced, {@code nb + 1}. Exposed
     * because it is the one observable that distinguishes this series from
     * {@link MeridianArc}'s fixed six.
     */
    public int coefficientCount() {
        return b.length;
    }

    @Override
    public String toString() {
        return "MDist[es=" + es + ", terms=" + b.length + "]";
    }
}
