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
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The second lobe of {@code +proj=bipc} — the {@code tag == true} half of
 * {@link BipolarProjection#project}, the cone whose apex is pole B and whose azimuth constant is
 * {@code Azab}.
 *
 * <h2>What was uncovered</h2>
 *
 * <p>The bipolar oblique conic conformal is two conics glued along a great-circle arc. Pole A sits
 * at 45&deg;N, 19.9930&deg;W and serves North America; pole B sits at 20&deg;S, 110&deg;W and
 * serves South America. {@link BipolarProjection#project} chooses between them on one line —
 * {@code tag = (Az > Azba)} — and everything after it differs: the polar angle {@code z} is
 * measured from a different pole ({@code S20}/{@code C20} against {@code S45}/{@code C45}), the
 * azimuth is recomputed about {@code lam + R110} rather than {@code lamB - lam}, {@code Av} becomes
 * {@code Azab} rather than {@code Azba}, the cone's origin is {@code +rhoc} rather than
 * {@code -rhoc}, and the final radius is added with the opposite sign.
 *
 * <p>Before this test the only {@code bipc} forwards anywhere in the suite were the four rows in
 * {@code numerical.NumericalDefectsTest} at {@code (±2, ±1)} — off West Africa, where {@code Az} is
 * about &minus;2.61 rad. Every one of them takes {@code tag == false}. The whole pole-B arm, about
 * half the operator, ran unexecuted.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every projected coordinate below is the output of the installed PROJ 9.8.1
 * ({@code proj +proj=bipc +ellps=GRS80 -f "%.9f"}, {@code Rel. 9.8.1, April 10th, 2026}). None of
 * them is a proj4j value copied back into an assertion.
 *
 * <p>The branch predicate is not taken on trust either. {@link #probesReallyReachTheAzabLobe}
 * recomputes {@code Az} from the same published constants and asserts on which side of
 * {@code Azba} each probe falls, so a future change that quietly moved the seam would fail here
 * rather than silently returning this test to covering only the pole-A cone again.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>{@code bipc}'s pole-B cone goes back to having no test at all: its five constants, its
 * recomputed azimuth, its sign flips and its {@code r /= cos(al + t)} rescaling could all be
 * wrong, or removed, without any assertion noticing.
 */
public class BipolarAzabLobeTest {

    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateTransformFactory TRANSFORMS = new CoordinateTransformFactory();

    private static final String OPERATION = "+proj=bipc +ellps=GRS80 +no_defs";
    private static final String GEOGRAPHIC = "+proj=longlat +ellps=GRS80 +no_defs";

    /** A micrometre. PROJ's printed values and proj4j's agree to about a nanometre. */
    private static final double TIGHT_METRES = 1.0e-6;

    /** A nanodegree, about 0.1 mm of ground distance. */
    private static final double TIGHT_DEGREES = 1.0e-9;

    /*
     * The branch-selection constants, repeated here so that the predicate can be recomputed
     * independently of the class under test. They are BipolarProjection's own values, which are in
     * turn byte-identical to 9.8.1:src/projections/bipc.cpp.
     */

    /** {@code lamB}: pole A's longitude, &minus;19.9930&deg;, in radians. */
    private static final double LAM_B = -.34894976726250681539;

    /** {@code Azba}: the azimuth at which the pole-A cone hands over to the pole-B cone. */
    private static final double AZBA = 1.82261843856185925133;

    /** {@code C45}: {@code cos(45&deg;)}, pole A's colatitude term. */
    private static final double C45 = .70710678118654752469;

    /**
     * Five points that reach the pole-B cone, chosen to spread the arm's behaviour rather than to
     * cluster: two well inside South America, one near the equator, one in the northern hemisphere
     * (the lobe is <em>not</em> "the southern half"), and one that additionally takes the
     * {@code r /= cos(al + t)} rescaling — see {@link #theRescalingArmOfTheAzabLobeIsReached}.
     *
     * <p>Columns: longitude, latitude, easting, northing. Eastings and northings from PROJ 9.8.1.
     */
    private static final double[][] AZAB_ROWS = {
        {-60, -20, -3882638.512295415, 1712324.479290619},
        {-70, -30, -4098911.197678836, 3165593.854649330},
        {-45,  -5, -3807222.309398470,  -551695.815694716},
        {-50,  10, -2212876.193280407, -1273151.026041133},
        {-100, -20, -1189089.037926224, 5493585.132568961},
    };

    /**
     * The subset of {@link #AZAB_ROWS} that survives a forward/inverse cycle. The rescaling probe
     * does not, in PROJ either; see {@link #theRescalingArmOfTheAzabLobeIsReached}.
     */
    private static final int ROUND_TRIPPABLE_ROWS = 4;

    /**
     * The probes are on the pole-B side of the seam and the corpus's existing probe is not.
     *
     * <p>{@code Az} is recomputed here from {@code lamB}, {@code C45} and the input, exactly as the
     * opening block of {@link BipolarProjection#project} does, and compared with {@code Azba}. This
     * is what makes the rest of the file a test of the {@code tag == true} arm rather than a set of
     * coordinates that merely happen to be correct.
     */
    @Test
    public void probesReallyReachTheAzabLobe() {
        for (double[] row : AZAB_ROWS) {
            double az = azimuthAboutPoleA(row[0], row[1]);
            assertTrue("(" + row[0] + ", " + row[1] + ") has Az = " + az + " rad, which is not "
                            + "greater than Azba = " + AZBA + ", so it does not reach the pole-B "
                            + "cone and this file tests nothing it claims to test",
                    az > AZBA);
        }
        // The only bipc forwards that existed before this file, from numerical.NumericalDefectsTest.
        for (double[] row : new double[][] {{2, 1}, {2, -1}, {-2, 1}, {-2, -1}}) {
            double az = azimuthAboutPoleA(row[0], row[1]);
            assertTrue("the pre-existing corpus probe (" + row[0] + ", " + row[1] + ") has Az = "
                            + az + " rad, which is already past Azba = " + AZBA + "; if that is "
                            + "true then the pole-B cone was covered all along and the premise of "
                            + "this file is wrong",
                    az <= AZBA);
        }
    }

    /**
     * The pole-B cone's forward, against PROJ 9.8.1.
     *
     * <p>A regression in any of the arm's five constants, in the recomputed azimuth, or in either
     * of its two sign flips moves these by kilometres.
     */
    @Test
    public void azabLobeForwardMatchesProj() {
        for (double[] row : AZAB_ROWS) {
            ProjCoordinate got = forward(row[0], row[1]);
            String where = "+proj=bipc pole-B cone at (" + row[0] + ", " + row[1] + ")";
            assertEquals(where + ": easting disagrees with PROJ 9.8.1", row[2], got.x,
                    TIGHT_METRES);
            assertEquals(where + ": northing disagrees with PROJ 9.8.1", row[3], got.y,
                    TIGHT_METRES);
        }
    }

    /**
     * {@code (-100, -20)} additionally takes {@code if (|t| < al) r /= cos(al + t)} — the
     * correction that keeps the cone conformal where it approaches the seam — while the other four
     * probes do not.
     *
     * <p>With the sign as written, {@code t} is added inside the cosine on this arm and subtracted
     * on the other; getting that backwards is a silent error of about 1,600 km here, which is why
     * the two cases are separated rather than left mixed in one loop.
     *
     * <p>{@code |t| = 0.4945} against {@code al = 0.5375} at this point, computed from the same
     * constants; the four probes above sit at {@code |t|} between 0.24 and 1.23 with {@code al}
     * between 0.14 and 0.41, all outside.
     *
     * <h4>This point does not round trip, and that is upstream's answer too</h4>
     *
     * <p>Inverting the forward here returns {@code (-99.9853750534, -19.9995881702)} — 1.5 km out
     * in longitude. That is not a proj4j defect: the same two numbers come back from
     * {@code proj -I +proj=bipc +ellps=GRS80} on 9.8.1, to all ten printed decimals. The inverse
     * re-derives the rescaling from a ten-trip fixed-point loop on the radius rather than
     * inverting it in closed form, and near the seam the loop settles somewhere slightly else.
     * Pinned so that a future change to that loop has to be a deliberate one.
     */
    @Test
    public void theRescalingArmOfTheAzabLobeIsReached() {
        // PROJ 9.8.1: echo "-100 -20" | proj +proj=bipc +ellps=GRS80 -f "%.9f"
        ProjCoordinate got = forward(-100, -20);
        assertEquals("the pole-B cone's r /= cos(al + t) rescaling is wrong: easting",
                -1189089.037926224, got.x, TIGHT_METRES);
        assertEquals("the pole-B cone's r /= cos(al + t) rescaling is wrong: northing",
                5493585.132568961, got.y, TIGHT_METRES);

        // PROJ 9.8.1: echo "-1189089.037926224 5493585.132568961" | proj -I +proj=bipc
        //             +ellps=GRS80 -f "%.10f"  ->  -99.9853750534  -19.9995881702
        ProjCoordinate back = inverse(got.x, got.y);
        assertEquals("proj4j's inverse no longer reproduces PROJ 9.8.1's own non-round-trip near "
                        + "the seam: longitude", -99.9853750534, back.x, 1.0e-9);
        assertEquals("proj4j's inverse no longer reproduces PROJ 9.8.1's own non-round-trip near "
                        + "the seam: latitude", -19.9995881702, back.y, 1.0e-9);
    }

    /**
     * The pole-B cone's forward and the {@code x < 0} arm of the inverse agree with each other.
     *
     * <p>{@link BipolarProjection#projectInverse} picks its cone on {@code neg = (x &lt; 0)}, and
     * all five probes project to a negative easting, so this exercises the inverse's
     * {@code Av = Azab} arm as well. The expected values are the inputs — an identity, not a
     * reference table — which is what makes this independent of the PROJ figures above.
     *
     * <p>The rescaling probe is excluded: it does not round trip in PROJ either, and
     * {@link #theRescalingArmOfTheAzabLobeIsReached} pins what it does instead.
     */
    @Test
    public void azabLobeRoundTripsThroughTheMatchingInverseArm() {
        for (int i = 0; i < ROUND_TRIPPABLE_ROWS; i++) {
            double[] row = AZAB_ROWS[i];
            ProjCoordinate xy = forward(row[0], row[1]);
            assertTrue("(" + row[0] + ", " + row[1] + ") projects to easting " + xy.x
                            + "; the inverse selects its cone on x < 0, so a non-negative easting "
                            + "would mean the two directions disagree about which cone the point "
                            + "belongs to",
                    xy.x < 0.0);
            ProjCoordinate lp = inverse(xy.x, xy.y);
            assertEquals("the pole-B cone does not round trip: longitude", row[0], lp.x,
                    TIGHT_DEGREES);
            assertEquals("the pole-B cone does not round trip: latitude", row[1], lp.y,
                    TIGHT_DEGREES);
        }
    }

    /**
     * {@code Az} as {@link BipolarProjection#project} computes it before choosing a cone:
     * {@code atan2(sin(lamB - lam), C45 * (tan(phi) - cos(lamB - lam)))}.
     *
     * @param lonDegrees geographic longitude
     * @param latDegrees geographic latitude, away from the poles
     * @return the azimuth about pole A, in radians
     */
    private static double azimuthAboutPoleA(double lonDegrees, double latDegrees) {
        double lam = Math.toRadians(lonDegrees);
        double phi = Math.toRadians(latDegrees);
        double dlam = LAM_B - lam;
        return Math.atan2(Math.sin(dlam), C45 * (Math.tan(phi) - Math.cos(dlam)));
    }

    private static ProjCoordinate forward(double lonDegrees, double latDegrees) {
        CoordinateReferenceSystem geographic = CRS.createFromParameters("bipc-geog", GEOGRAPHIC);
        CoordinateReferenceSystem projected = CRS.createFromParameters("bipc", OPERATION);
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(geographic, projected)
                .transform(new ProjCoordinate(lonDegrees, latDegrees), out);
        return out;
    }

    private static ProjCoordinate inverse(double easting, double northing) {
        CoordinateReferenceSystem geographic = CRS.createFromParameters("bipc-geog", GEOGRAPHIC);
        CoordinateReferenceSystem projected = CRS.createFromParameters("bipc", OPERATION);
        ProjCoordinate out = new ProjCoordinate();
        TRANSFORMS.createTransform(projected, geographic)
                .transform(new ProjCoordinate(easting, northing), out);
        return out;
    }
}
