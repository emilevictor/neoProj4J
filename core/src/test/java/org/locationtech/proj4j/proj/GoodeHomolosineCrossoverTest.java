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
import org.locationtech.proj4j.ProjCoordinate;

/**
 * The sinusoidal/Mollweide crossover of {@code +proj=goode}, at latitude 40&deg;44&prime; — the
 * {@code PHI_LIM} test in {@link GoodeProjection#project} and
 * {@link GoodeProjection#projectInverse}.
 *
 * <h2>What was uncovered</h2>
 *
 * <p>Goode's homolosine is a sinusoidal between 40&deg;44&prime;S and 40&deg;44&prime;N and a
 * Mollweide outside it, the two joined by shifting the Mollweide half towards the equator by
 * {@code Y_COR = 0.05280} radii. {@link GoodeProjection} is four lines of arithmetic and one
 * comparison, and the whole of its behaviour is which side of that comparison a point falls on —
 * including the sign of the shift, which is {@code -Y_COR} in the south and {@code +Y_COR} in the
 * north.
 *
 * <p>Before this file no test in the suite crossed 40&deg;44&prime; with {@code goode} at all. The
 * Mollweide arm, the {@code Y_COR} shift and its sign flip were unexecuted; so was the boundary
 * itself, which is a genuinely delicate place — the two halves do not meet exactly, and the
 * forward and inverse do not choose the same side of the seam for the same point.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Three independent sources, marked case by case:
 * <ul>
 * <li><b>PROJ 9.8.1</b> ({@code proj +proj=goode +R=6400000 -f "%.9f"}, {@code Rel. 9.8.1, April
 *     10th, 2026}) for every projected coordinate;</li>
 * <li><b>an analytic identity</b> on the sinusoidal side, where the spherical sinusoidal is
 *     exactly {@code x = R*lambda*cos(phi)}, {@code y = R*phi} — asserted independently of PROJ,
 *     so the equatorward arm is checked against mathematics and not only against a table;</li>
 * <li><b>{@code +proj=moll} run through PROJ at the same points</b>, to show that the poleward arm
 *     really is a Mollweide displaced by exactly {@code Y_COR * R} and nothing else.</li>
 * </ul>
 *
 * <p>The one place where a value is "what the code does today" is
 * {@link #theBoundaryIsNotExactlyInvertible}, and it says so; PROJ 9.8.1 reproduces the same
 * numbers, so it is upstream's answer rather than proj4j's, but it is not a value anyone designed.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>{@code goode} reverts to being covered only where it is indistinguishable from
 * {@code +proj=sinu}. Deleting the {@code else} branch outright, dropping {@code Y_COR}, or
 * reversing its sign in the southern hemisphere would all pass the remaining suite.
 */
public class GoodeHomolosineCrossoverTest {

    private static final CRSFactory CRS = new CRSFactory();

    /** A sphere, because {@code goode} has no ellipsoidal form. Round, so the arithmetic reads. */
    private static final double R = 6400000.0;

    private static final String OPERATION = "+proj=goode +R=6400000 +no_defs";

    /**
     * {@code PHI_LIM}, {@link GoodeProjection}'s own constant and
     * {@code 9.8.1:src/projections/goode.cpp}'s: 40&deg;44&prime; in radians. Repeated here so the
     * boundary can be addressed exactly rather than approached in degrees.
     */
    private static final double PHI_LIM = .71093078197902358062;

    /** {@code Y_COR}, the equatorward shift applied to the Mollweide arm, in radii. */
    private static final double Y_COR = 0.05280;

    /** A micrometre. proj4j and PROJ agree to about a nanometre at these points. */
    private static final double TIGHT_METRES = 1.0e-6;

    /**
     * 40.733333333333334 is {@code PHI_LIM} to the last bit: {@code Math.toRadians} of it returns
     * {@code 0.7109307819790236}, which is the {@code double} {@code PHI_LIM} names. So the
     * degree-valued API can address the boundary exactly, and this is asserted rather than assumed
     * because the rest of the file leans on it.
     */
    private static final double PHI_LIM_DEGREES = 40.733333333333334;

    @Test
    public void theBoundaryLatitudeInDegreesIsTheConstantItself() {
        assertEquals("40.733333333333334 degrees is no longer bit-identical to PHI_LIM, so the "
                        + "degree-valued probes below no longer sit on the boundary",
                Double.doubleToLongBits(PHI_LIM),
                Double.doubleToLongBits(Math.toRadians(PHI_LIM_DEGREES)));
    }

    /**
     * Equatorward of the crossover, {@code goode} is the spherical sinusoidal exactly.
     *
     * <p>Checked twice: against PROJ 9.8.1's output, and against the closed form
     * {@code (R*lambda*cos(phi), R*phi)}. The second is the stronger statement — it says the arm is
     * the sinusoidal, not merely that it agrees with a table of five numbers.
     */
    @Test
    public void equatorwardOfTheCrossoverIsTheSinusoidal() {
        // PROJ 9.8.1, +proj=goode +R=6400000
        assertForward(20, 30, 1934719.321849832, 3351032.163829112);
        assertForward(20, 40.7, 1693688.363401044, 4546233.635594830);
        assertForward(20, -30, 1934719.321849832, -3351032.163829112);

        for (double[] probe : new double[][] {{20, 30}, {20, 40.7}, {-100, 0}, {20, -30},
                {-45, -40.7}}) {
            double lam = Math.toRadians(probe[0]);
            double phi = Math.toRadians(probe[1]);
            ProjCoordinate got = forward(probe[0], probe[1]);
            assertEquals("goode is not the sinusoidal at (" + probe[0] + ", " + probe[1]
                            + "): easting",
                    R * lam * Math.cos(phi), got.x, TIGHT_METRES);
            assertEquals("goode is not the sinusoidal at (" + probe[0] + ", " + probe[1]
                            + "): northing",
                    R * phi, got.y, TIGHT_METRES);
        }
    }

    /**
     * The boundary latitude itself belongs to the sinusoidal arm: the test is
     * {@code |phi| <= PHI_LIM}, not {@code <}.
     *
     * <p>Asserted in both hemispheres and against the analytic sinusoidal, so a change from
     * {@code <=} to {@code <} — which would move the boundary point by 32 m east and 23 m south —
     * fails here.
     */
    @Test
    public void theBoundaryLatitudeItselfIsSinusoidal() {
        Projection goode = goode();
        for (double sign : new double[] {1.0, -1.0}) {
            ProjCoordinate out = new ProjCoordinate();
            goode.projectRadians(
                    new ProjCoordinate(Math.toRadians(20), sign * PHI_LIM), out);
            String where = "at phi = " + (sign > 0 ? "+" : "-") + "PHI_LIM exactly";
            assertEquals(where + ", goode must still be the sinusoidal (the test is <=, not <): "
                            + "easting",
                    R * Math.toRadians(20) * Math.cos(PHI_LIM), out.x, TIGHT_METRES);
            assertEquals(where + ", goode must still be the sinusoidal (the test is <=, not <): "
                            + "northing",
                    sign * R * PHI_LIM, out.y, TIGHT_METRES);
        }

        // PROJ 9.8.1: echo "20 40.733333333333334" | proj +proj=goode +R=6400000 -f "%.9f"
        assertForward(20, PHI_LIM_DEGREES, 1692840.543881016, 4549957.004665751);
    }

    /**
     * One bit of latitude past the boundary and the Mollweide arm takes over, discontinuously.
     *
     * <p>{@code Math.nextUp(PHI_LIM)} is 1.1e-16 rad further north — about 0.7 nm on the ground —
     * yet the projected point moves 31.7 m west and 22.6 m north. The gap is the homolosine's own:
     * the two halves are matched to about 30 m, not exactly. Measuring it is the sharpest way to
     * show that the branch is really there and that each side is the projection it should be.
     *
     * <p>The two jump sizes asserted here are proj4j's own, at a latitude no command line can
     * express. What anchors them is PROJ 9.8.1 either side of the seam at the finest resolution
     * {@code proj} will accept — 40.7333333&deg; gives (1692840.544729122, 4549957.000942381) and
     * 40.7333334&deg; gives (1692808.866851778, 4549979.583792308), a step of &minus;31.678 m and
     * +22.583 m across 1e-7&deg; of latitude. The 0.011 m difference from the figures below is
     * that 1e-7&deg;; the step is upstream's, not this implementation's.
     */
    @Test
    public void oneBitPolewardOfTheBoundarySwitchesToMollweide() {
        Projection goode = goode();

        ProjCoordinate onBoundary = new ProjCoordinate();
        goode.projectRadians(new ProjCoordinate(Math.toRadians(20), PHI_LIM), onBoundary);
        ProjCoordinate justPast = new ProjCoordinate();
        goode.projectRadians(
                new ProjCoordinate(Math.toRadians(20), Math.nextUp(PHI_LIM)), justPast);

        assertEquals("the crossover is no longer discontinuous in easting, which means the two "
                        + "arms are no longer two different projections",
                -31.675967387, justPast.x - onBoundary.x, 1.0e-6);
        assertEquals("the crossover is no longer discontinuous in northing, which means Y_COR is "
                        + "no longer being applied at the seam",
                22.571679682, justPast.y - onBoundary.y, 1.0e-6);

        // And the same, mirrored, in the south: -nextUp(PHI_LIM) must take the Mollweide arm with
        // the opposite sign of Y_COR, giving exactly the negated northing.
        ProjCoordinate justPastSouth = new ProjCoordinate();
        goode.projectRadians(
                new ProjCoordinate(Math.toRadians(20), -Math.nextUp(PHI_LIM)), justPastSouth);
        assertEquals("the southern Mollweide arm is not the mirror of the northern one: easting",
                justPast.x, justPastSouth.x, 0.0);
        assertEquals("the southern Mollweide arm is not the mirror of the northern one: northing",
                -justPast.y, justPastSouth.y, 0.0);
    }

    /**
     * Poleward of the crossover, {@code goode} is a Mollweide moved {@code Y_COR * R} = 337,920 m
     * towards the equator, and nothing else.
     *
     * <p>The comparison values are PROJ 9.8.1's {@code +proj=moll +R=6400000} at the same points,
     * so this pins the constant itself rather than the sum of the constant and whatever the
     * Mollweide happens to do. The easting must be untouched.
     */
    @Test
    public void polewardOfTheCrossoverIsMollweideShiftedByYCor() {
        // PROJ 9.8.1, +proj=goode +R=6400000
        assertForward(20, 40.8, 1691746.073903880, 4557425.057702659);
        assertForward(20, 50, 1526480.974537634, 5555685.063633497);
        assertForward(20, 70, 1019865.470350400, 7463198.961619282);

        // PROJ 9.8.1, +proj=moll +R=6400000, same two points.
        double[][] mollweide = {
            {20, 50, 1526480.974537634, 5893605.063633496},
            {20, 70, 1019865.470350400, 7801118.961619282},
        };
        for (double[] row : mollweide) {
            ProjCoordinate got = forward(row[0], row[1]);
            assertEquals("goode's poleward arm has stopped being a plain Mollweide in easting at ("
                            + row[0] + ", " + row[1] + ")",
                    row[2], got.x, TIGHT_METRES);
            assertEquals("goode's poleward arm is no longer the Mollweide shifted by exactly "
                            + "Y_COR * R = " + (Y_COR * R) + " m at (" + row[0] + ", " + row[1]
                            + ")",
                    row[3] - Y_COR * R, got.y, TIGHT_METRES);
        }
    }

    /**
     * The sign of the {@code Y_COR} shift flips in the southern hemisphere, so the map is
     * symmetric about the equator.
     *
     * <p>Written as an exact mirror rather than to a tolerance: {@code out.y -= lpphi >= 0.0 ?
     * Y_COR : -Y_COR} produces bit-identical magnitudes, and a code change that shifted both
     * hemispheres the same way would put the southern points 675,840 m out — but a change that
     * merely perturbed the sign handling might not, which is why the comparison is exact.
     */
    @Test
    public void theSouthernMollweideArmMirrorsTheNorthern() {
        for (double[] probe : new double[][] {{20, 50}, {20, 70}, {-140, 55}, {0, 89}}) {
            ProjCoordinate north = forward(probe[0], probe[1]);
            ProjCoordinate south = forward(probe[0], -probe[1]);
            assertTrue("(" + probe[0] + ", " + probe[1] + ") is not poleward of the crossover, so "
                            + "it does not test the sign of Y_COR",
                    Math.abs(Math.toRadians(probe[1])) > PHI_LIM);
            assertEquals("goode is not symmetric about the equator in easting at (" + probe[0]
                            + ", ±" + probe[1] + ")",
                    north.x, south.x, 0.0);
            assertEquals("Y_COR is not being negated in the southern hemisphere: the northing at ("
                            + probe[0] + ", -" + probe[1] + ") is not the negation of the one at +"
                            + probe[1],
                    -north.y, south.y, 0.0);
        }
    }

    /**
     * The inverse chooses its arm on the projected northing, and round trips on both sides.
     *
     * <p>{@code projectInverse} tests {@code |y| <= PHI_LIM} on the northing <em>in units of the
     * sphere's radius</em>, which on the sinusoidal arm is the latitude itself and on the Mollweide
     * arm is a shade larger — so the two directions agree everywhere except exactly on the seam.
     * The expected values are the inputs, an identity rather than a table.
     */
    @Test
    public void bothArmsRoundTrip() {
        for (double[] probe : new double[][] {{20, 30}, {20, 40.7}, {20, -30}, {-100, 5},
                {20, 50}, {20, 70}, {20, -50}, {20, -70}, {-140, 80}}) {
            ProjCoordinate xy = forward(probe[0], probe[1]);
            ProjCoordinate lp = inverse(xy.x, xy.y);
            assertEquals("goode does not round trip at (" + probe[0] + ", " + probe[1]
                            + "): longitude", probe[0], lp.x, 1.0e-9);
            assertEquals("goode does not round trip at (" + probe[0] + ", " + probe[1]
                            + "): latitude", probe[1], lp.y, 1.0e-9);
        }
    }

    /**
     * On the seam itself the forward and the inverse disagree about which arm the point is on, and
     * the round trip misses by about 23 m.
     *
     * <p>The forward at exactly {@code PHI_LIM} takes the sinusoidal, giving a northing of
     * 4,549,957.004665751 m. The inverse divides that by the radius using a reciprocal multiply,
     * which lands one bit <em>above</em> {@code PHI_LIM}, so it takes the Mollweide arm and answers
     * 40.7331312650&deg; instead of 40.7333333333&deg;.
     *
     * <p><b>These two numbers are what the code does today, not a designed result.</b> They are
     * pinned here because PROJ 9.8.1 returns the identical pair to all ten printed decimals
     * ({@code echo "1692840.543881016 4549957.004665751" | proj -I +proj=goode +R=6400000}), so
     * proj4j is faithful and a change here would be a divergence from upstream — but nobody
     * intended a 23 m step, and if the seam is ever made consistent this assertion is the marker
     * to flip.
     */
    @Test
    public void theBoundaryIsNotExactlyInvertible() {
        ProjCoordinate xy = forward(20, PHI_LIM_DEGREES);
        ProjCoordinate lp = inverse(xy.x, xy.y);

        assertEquals("proj4j no longer matches PROJ 9.8.1's inverse on the goode seam: longitude",
                20.0003362155, lp.x, 1.0e-9);
        assertEquals("proj4j no longer matches PROJ 9.8.1's inverse on the goode seam: latitude",
                40.7331312650, lp.y, 1.0e-9);
        assertTrue("the goode seam has become exactly invertible; that is an improvement over "
                        + "PROJ 9.8.1, but it is a deliberate divergence and this assertion is "
                        + "where it should be recorded",
                Math.abs(lp.y - PHI_LIM_DEGREES) > 1.0e-6);
    }

    private void assertForward(double lonDegrees, double latDegrees, double x, double y) {
        ProjCoordinate got = forward(lonDegrees, latDegrees);
        String where = "+proj=goode at (" + lonDegrees + ", " + latDegrees + ")";
        assertEquals(where + ": easting disagrees with PROJ 9.8.1", x, got.x, TIGHT_METRES);
        assertEquals(where + ": northing disagrees with PROJ 9.8.1", y, got.y, TIGHT_METRES);
    }

    private static Projection goode() {
        return CRS.createFromParameters("goode", OPERATION).getProjection();
    }

    private static ProjCoordinate forward(double lonDegrees, double latDegrees) {
        ProjCoordinate out = new ProjCoordinate();
        goode().project(new ProjCoordinate(lonDegrees, latDegrees), out);
        return out;
    }

    private static ProjCoordinate inverse(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        goode().inverseProject(new ProjCoordinate(x, y), out);
        return out;
    }
}
