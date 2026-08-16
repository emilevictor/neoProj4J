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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.MDist;

/**
 * Roussilhe Stereographic, a port of PROJ 9.8.1's {@code src/projections/rouss.cpp}
 * ({@code +proj=rouss}).
 *
 * <p>Azimuthal, ellipsoidal, with an inverse. Both directions are truncated power
 * series in two variables — the meridional arc from the origin latitude, and a scaled
 * longitude — with 33 coefficients (A1-A6, B1-B8, C1-C8, D1-D11) precomputed from the
 * ellipsoid and {@code +lat_0}. Nothing iterates except the inverse's one call to
 * {@link MDist#invMdist(double)}.
 *
 * <p><b>The meridional arc is {@link MDist}, not
 * {@link org.locationtech.proj4j.util.MeridianArc}.</b> Upstream calls
 * {@code proj_mdist}, and this projection's whole coefficient set is written against
 * that particular series; substituting the more accurate {@code mlfn} expansion would
 * change the answer in the last few digits for no gain in fidelity, which is the wrong
 * trade under a parity rule. See {@link MDist} for how far apart the two actually are.
 *
 * <p><b>{@code PJ_PROJECTION(rouss)} has no guards at all.</b> Beyond allocation, the
 * setup function ({@code rouss.cpp:101-158}) rejects nothing — not {@code lat_0} at a
 * pole, where {@code tan(phi0)} overflows, and not a sphere, where the series
 * degenerates but stays finite. So there is deliberately no
 * {@code ProjOperatorSetup.validate} branch for {@code rouss}: PROJ accepts every
 * definition it is given, and so do we.
 *
 * <p><b>Math vs StrictMath:</b> {@link FastStrictTrig} throughout, for the usual
 * cross-architecture determinism reason.
 *
 * @since 2.2.0
 */
public class RoussilheStereographicProjection extends Projection {

    private static final long serialVersionUID = 1L;

    /** The {@code proj_mdist} series for this ellipsoid. Built in {@link #initialize()}. */
    private MDist en;

    /** {@code Q->s0}: the meridional arc from the equator to {@code +lat_0}. */
    private double s0;

    private double A1, A2, A3, A4, A5, A6;
    private double B1, B2, B3, B4, B5, B6, B7, B8;
    private double C1, C2, C3, C4, C5, C6, C7, C8;
    private double D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11;

    /**
     * {@code rouss_e_forward}, {@code rouss.cpp:45-65}.
     * <p>
     * {@code +k_0} is applied here rather than by the caller: proj4j's forward funnel
     * multiplies by {@code totalScale} and adds the false origin, but never by
     * {@code scaleFactor}, so a projection that uses {@code P->k0} has to do it itself.
     */
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        final double cp = FastStrictTrig.cos(lpphi);
        final double sp = FastStrictTrig.sin(lpphi);
        final double s = en.mdist(lpphi, sp, cp) - s0;
        final double s2 = s * s;
        final double al = lplam * cp / Math.sqrt(1.0 - es * sp * sp);
        final double al2 = al * al;
        out.x = scaleFactor * al
                * (1.0 + s2 * (A1 + s2 * A4) - al2 * (A2 + s * A3 + s2 * A5 + al2 * A6));
        out.y = scaleFactor
                * (al2 * (B1 + al2 * B4)
                        + s * (1.0 + al2 * (B3 - al2 * B6) + s2 * (B2 + s2 * B8)
                                + s * al2 * (B5 + s * B7)));
        return out;
    }

    /**
     * {@code rouss_e_inverse}, {@code rouss.cpp:67-86}.
     * <p>
     * The one place this can refuse is {@link MDist#invMdist(double)}, whose Newton
     * loop throws where upstream sets an errno and returns the last iterate. See
     * {@link MDist} for why that is the same refusal and not a divergence.
     */
    public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
        final double x = xyx / scaleFactor;
        final double y = xyy / scaleFactor;
        final double x2 = x * x;
        final double y2 = y * y;
        final double al = x * (1.0 - C1 * y2
                + x2 * (C2 + C3 * y - C4 * x2 + C5 * y2 - C7 * x2 * y)
                + y2 * (C6 * y2 - C8 * x2 * y));
        double s = s0 + y * (1.0 + y2 * (-D2 + D8 * y2))
                + x2 * (-D1 + y * (-D3 + y * (-D5 + y * (-D7 + y * D11)))
                        + x2 * (D4 + y * (D6 + y * D10) - x2 * D9));
        out.y = en.invMdist(s);
        s = FastStrictTrig.sin(out.y);
        out.x = al * Math.sqrt(1.0 - es * s * s) / FastStrictTrig.cos(out.y);
        return out;
    }

    /**
     * {@code PJ_PROJECTION(rouss)}, {@code rouss.cpp:101-158}.
     * <p>
     * Every coefficient is a pure function of {@code es} and {@code +lat_0}, so a
     * second call is a no-op beyond rebuilding the series — which
     * {@code Proj4Parser} does make, and which is harmless.
     */
    public void initialize() {
        super.initialize();
        en = new MDist(es);

        double es2 = FastStrictTrig.sin(projectionLatitude);
        s0 = en.mdist(projectionLatitude, es2, FastStrictTrig.cos(projectionLatitude));
        double t = 1.0 - (es2 = es * es2 * es2);
        final double N0 = 1.0 / Math.sqrt(t);
        final double R_R0_2 = t * t / one_es;
        final double R_R0_4 = R_R0_2 * R_R0_2;
        t = FastStrictTrig.tan(projectionLatitude);
        final double t2 = t * t;

        C1 = A1 = R_R0_2 / 4.0;
        C2 = A2 = R_R0_2 * (2 * t2 - 1.0 - 2.0 * es2) / 12.0;
        A3 = R_R0_2 * t * (1.0 + 4.0 * t2) / (12.0 * N0);
        A4 = R_R0_4 / 24.0;
        A5 = R_R0_4 * (-1.0 + t2 * (11.0 + 12.0 * t2)) / 24.0;
        A6 = R_R0_4 * (-2.0 + t2 * (11.0 - 2.0 * t2)) / 240.0;
        B1 = t / (2.0 * N0);
        B2 = R_R0_2 / 12.0;
        B3 = R_R0_2 * (1.0 + 2.0 * t2 - 2.0 * es2) / 4.0;
        B4 = R_R0_2 * t * (2.0 - t2) / (24.0 * N0);
        B5 = R_R0_2 * t * (5.0 + 4.0 * t2) / (8.0 * N0);
        B6 = R_R0_4 * (-2.0 + t2 * (-5.0 + 6.0 * t2)) / 48.0;
        B7 = R_R0_4 * (5.0 + t2 * (19.0 + 12.0 * t2)) / 24.0;
        B8 = R_R0_4 / 120.0;
        C3 = R_R0_2 * t * (1.0 + t2) / (3.0 * N0);
        C4 = R_R0_4 * (-3.0 + t2 * (34.0 + 22.0 * t2)) / 240.0;
        C5 = R_R0_4 * (4.0 + t2 * (13.0 + 12.0 * t2)) / 24.0;
        C6 = R_R0_4 / 16.0;
        C7 = R_R0_4 * t * (11.0 + t2 * (33.0 + t2 * 16.0)) / (48.0 * N0);
        C8 = R_R0_4 * t * (1.0 + t2 * 4.0) / (36.0 * N0);
        D1 = t / (2.0 * N0);
        D2 = R_R0_2 / 12.0;
        D3 = R_R0_2 * (2 * t2 + 1.0 - 2.0 * es2) / 4.0;
        D4 = R_R0_2 * t * (1.0 + t2) / (8.0 * N0);
        D5 = R_R0_2 * t * (1.0 + t2 * 2.0) / (4.0 * N0);
        D6 = R_R0_4 * (1.0 + t2 * (6.0 + t2 * 6.0)) / 16.0;
        D7 = R_R0_4 * t2 * (3.0 + t2 * 4.0) / 8.0;
        D8 = R_R0_4 / 80.0;
        D9 = R_R0_4 * t * (-21.0 + t2 * (178.0 - t2 * 26.0)) / 720.0;
        D10 = R_R0_4 * t * (29.0 + t2 * (86.0 + t2 * 48.0)) / (96.0 * N0);
        D11 = R_R0_4 * t * (37.0 + t2 * 44.0) / (96.0 * N0);
    }

    public boolean hasInverse() {
        return true;
    }

    public String toString() {
        return "Roussilhe Stereographic";
    }
}
