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
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Pins every place {@link VanDerGrintenProjection}'s general formula degenerates and a special case
 * takes over: the centre, the equator, the central meridian, the poles, and the two tolerance
 * bands around them - forward and inverse. It also records <b>two disagreements with PROJ 9.8.1</b>
 * that this coverage exposed; see "Findings" below.
 *
 * <h2>Why the degenerate cases and not a grid of ordinary points</h2>
 *
 * <p>Snyder's (29-1) through (29-6) divide by {@code lam}, by {@code P}, and by
 * {@code p2 + cos(theta) - 1}. Every one of those denominators is zero somewhere a user will
 * actually go: on the central meridian, at the poles, and on the equator. {@code vandg.cpp} handles
 * them with three explicit branches ahead of the general one, and the inverse mirrors two of them.
 * A test that samples the interior of the map never enters any of the four, so the arithmetic that
 * a user hits at longitude 0 is exactly the arithmetic nothing checks.
 *
 * <p>The bands matter as much as the exact cases: the branches trigger on {@code |phi| <= 1e-10}
 * and {@code |lam| <= 1e-10} <em>radians</em>, which is about 6e-9 degrees, so a point a nanodegree
 * off the equator still takes the degenerate path. Two tests below sit inside those bands
 * deliberately.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every coordinate is from the {@code proj} binary of <b>PROJ 9.8.1</b>
 * ({@code Rel. 9.8.1, April 10th, 2026}) unless the test says otherwise in as many words. Each
 * test records the exact command. The three tests whose values are <em>ours</em> rather than
 * PROJ's say so in their first sentence, and say by how much the two differ.
 *
 * <h2>Findings</h2>
 *
 * <ol>
 * <li><b>The inverse is missing upstream's {@code r > PISQ} branch.</b> {@code vandg.cpp}'s
 *     {@code vandg_s_inverse} contains, between (29-17) and (29-18),
 *     {@code if (r > PISQ) { d = M_TWOPI - d; }} with the comment "This code path is triggered for
 *     coordinates generated in the forward path when |long|&gt;180deg and +over".
 *     {@link VanDerGrintenProjection#projectInverse} has no such test. The consequence is that the
 *     {@code +over} round trip is broken in latitude: our own forward of {@code (200, 45)} under
 *     {@code +over} matches PROJ to the last printed digit, and inverting it gives
 *     <b>41.1257</b> where PROJ gives <b>45.0000</b> - a <b>3.87 degree</b> error. It also affects
 *     any inverse of a point outside the map circle, by up to tens of degrees. See
 *     {@link #theInverseIsMissingUpstreamsRGreaterThanPiSquaredBranch()}.</li>
 * <li><b>The forward funnel does not wrap longitude when {@code +lon_0} is absent or zero.</b>
 *     This is not a {@code vandg} defect - it is in {@code Projection}, whose
 *     {@code projectRadians} guards <em>both</em> the {@code lam0} subtraction and the
 *     {@code adjlon} wrap with {@code if (projectionLongitude != 0)}, while {@code fwd_prepare}
 *     ({@code 9.8.1:src/fwd.cpp:117-122}) subtracts unconditionally and wraps whenever
 *     {@code +over} is off. It surfaces here because {@code vandg} is a whole-world projection
 *     people feed unnormalised longitudes to. See
 *     {@link #theForwardDoesNotWrapLongitudeWhenLon0IsZero()}.</li>
 * </ol>
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>JaCoCo measured this class at <b>37.7% of 472 instructions</b> before this test existed. The
 * two findings above become invisible, and so does any regression in the four degenerate branches
 * - none of which the general formula would notice, because the general formula never runs on
 * those inputs.
 */
public class VanDerGrintenDegenerateCaseTest {

    /** Unit-radius outputs are O(1) and PROJ prints twelve decimals. */
    private static final double UNIT_RADIUS_TOL = 1e-11;

    /** Degrees out of an inverse. Same reasoning. */
    private static final double DEGREE_TOL = 1e-11;

    /** {@code TOL} in {@code vandg.cpp}: the half width of every degenerate band, in radians. */
    private static final double KERNEL_TOL = 1e-10;

    private static final String VANDG = "+proj=vandg +R=1";

    private static final CRSFactory FACTORY = new CRSFactory();

    // ------------------------------------------------------------------- forward degenerate cases

    /**
     * The centre. {@code |phi| <= TOL} wins over everything else, so the point leaves through the
     * equator branch with {@code x = lam = 0}.
     * <p>
     * Reference: {@code echo "0 0" | proj -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theCentreProjectsToTheOrigin() {
        assertForward(0, 0, 0.000000000000, 0.000000000000);
    }

    /**
     * The equator, where van der Grinten is the identity in longitude - the whole general formula
     * collapses to {@code x = lam}, {@code y = 0}. Nothing else in the class produces that, so if
     * this branch were lost the equator would come out curved.
     * <p>
     * Reference: {@code echo "<lon> 0" | proj -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theEquatorIsTheIdentityInLongitude() {
        assertForward(30, 0, 0.523598775598, 0.000000000000);
        assertForward(-179, 0, -3.124139361070, 0.000000000000);
        // And it really is the identity, to the bit: 30 degrees in radians.
        assertEquals("the equator branch must copy the longitude through untouched",
                Math.toRadians(30.0), forward(30, 0).x, 0.0);
    }

    /**
     * The central meridian, where {@code A = (pi/lam - lam/pi)/2} is a division by zero. The branch
     * replaces the whole of (29-1)/(29-2) with {@code x = 0}, {@code y = pi tan(asin(P)/2)}.
     * <p>
     * Reference: {@code echo "0 <lat>" | proj -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theCentralMeridianUsesTheDegenerateFormula() {
        assertForward(0, 45, 0.000000000000, 0.841787214477);
        assertForward(0, -45, 0.000000000000, -0.841787214477);
        // pi * tan(asin(0.5)/2) is the closed form the branch computes; asserting it separately
        // shows the branch is running rather than the general formula happening to agree.
        assertEquals("the central meridian northing must be pi*tan(asin(p2)/2)",
                Math.PI * Math.tan(0.5 * Math.asin(0.5)), forward(0, 45).y, 1e-15);
    }

    /**
     * The poles, where {@code p2 == 1} makes {@code g = sqrt(1 - p2^2)} zero and {@code G} a 0/0.
     * The branch is entered by the <em>second</em> half of the same condition,
     * {@code |p2 - 1| < TOL}, so it fires at any longitude - the pole is a single point on the map
     * however you arrive at it, and the northing is {@code pi tan(asin(1)/2)}, which is
     * algebraically {@code pi} and in doubles exactly one ulp below it, because
     * {@code Math.tan(Math.PI/4)} is {@code 0.9999999999999999}.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1) prints
     * {@code 0.000000000000  3.141592653590} for every longitude at latitude 90.
     */
    @Test
    public void thePolesGoToPlusOrMinusPiRegardlessOfLongitude() {
        assertForward(0, 90, 0.000000000000, 3.141592653590);
        assertForward(0, -90, 0.000000000000, -3.141592653590);
        assertForward(30, 90, 0.000000000000, 3.141592653590);
        assertForward(-120, -90, 0.000000000000, -3.141592653590);
        // Bit for bit, so that the branch cannot be replaced by something merely close. The
        // longitude is discarded entirely, which is what makes all four rows above identical.
        assertEquals("the pole's northing must be pi*tan(asin(1)/2), one ulp below pi",
                Math.PI * Math.tan(0.5 * Math.asin(1.0)), forward(30, 90).y, 0.0);
        assertEquals("the south pole must be the exact negation of the north pole",
                -forward(30, 90).y, forward(30, -90).y, 0.0);
        assertEquals("the pole must not depend on the longitude it was reached from",
                forward(0, 90).y, forward(-120, 90).y, 0.0);
    }

    /**
     * The bands, not just the exact cases. {@code TOL} is {@code 1e-10} <em>radians</em>, about
     * 5.7e-9 degrees, so a point a nanodegree off the equator or off the central meridian still
     * takes the degenerate path. If the tests only ever used exact zeros, a comparison rewritten
     * as {@code == 0} would pass them all.
     * <p>
     * Reference: {@code echo "30 1e-9" | proj -f "%.12f" +proj=vandg +R=1} prints
     * {@code 0.523598775598  0.000000000000} - the same as latitude 0 - and
     * {@code echo "1e-9 45" | proj -f "%.12f" +proj=vandg +R=1} prints
     * {@code 0.000000000000  0.841787214477} - the same as longitude 0 (PROJ 9.8.1).
     */
    @Test
    public void aPointInsideTheToleranceBandTakesTheDegenerateBranch() {
        // 1e-9 degrees is 1.7e-11 radians, comfortably inside TOL = 1e-10.
        assertTrue("the probe must be inside the band this test is about",
                Math.toRadians(1e-9) < KERNEL_TOL);
        assertForward(30, 1e-9, 0.523598775598, 0.000000000000);
        assertForward(1e-9, 45, 0.000000000000, 0.841787214477);
        // Exactly the values the exact cases give, which is the point of the band.
        assertEquals("a nanodegree off the equator must equal the equator itself",
                forward(30, 0).y, forward(30, 1e-9).y, 0.0);
        assertEquals("a nanodegree off the central meridian must equal the meridian itself",
                forward(0, 45).y, forward(1e-9, 45).y, 0.0);
    }

    /**
     * The general formula, so that the degenerate branches above are pinned against something that
     * is not itself degenerate. Includes a point a tenth of a degree from the pole and from the
     * antimeridian, where the general arm is at its worst conditioned but is still the arm that
     * runs.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theGeneralFormulaMatchesProj() {
        assertForward(30, 45, 0.486470084203, 0.847302391342);
        assertForward(-60, -30, -1.018359554876, -0.548779701935);
        assertForward(179.9, 89.9, 0.209158312875, 3.134505845916);
    }

    /**
     * The kernel's own latitude refusal, {@code (p2 - TOL) > 1}.
     *
     * <p>This branch is <b>unreachable through the public forward</b>: {@code Projection}'s funnel
     * runs {@code fwd_prepare}'s angular contract first and rejects a latitude more than
     * {@code 1e-12} radians past the pole before {@code vandg} ever sees it. It is covered here by
     * calling the kernel directly, and both halves are asserted - that the kernel refuses, and
     * that the funnel refuses earlier and for its own reason - so that a future change which
     * removes the funnel guard does not silently leave the coordinate unchecked.
     */
    @Test
    public void theKernelRefusesALatitudeBeyondThePoleAndSoDoesTheFunnelFirst() {
        VanDerGrintenProjection kernel = (VanDerGrintenProjection) projection();
        try {
            kernel.project(Math.toRadians(10), Math.toRadians(95), new ProjCoordinate());
            fail("the vandg kernel must refuse a latitude 5 degrees past the pole");
        } catch (ProjectionException expected) {
            // vandg.cpp sets PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN here.
        }
        try {
            forward(10, 95);
            fail("the forward funnel must refuse a latitude 5 degrees past the pole");
        } catch (ProjectionException expected) {
            assertTrue("the funnel's refusal must name the latitude, not the vandg kernel's 'F': "
                    + expected.getMessage(),
                    expected.getMessage().contains("latitude"));
        }
    }

    /**
     * The sliver between the two, {@code 1 < p2 <= 1 + TOL}, where the kernel does not refuse but
     * <em>clamps</em> {@code p2} to 1 and falls into the pole branch. It is a two-line window: the
     * refusal is at {@code p2 - 1e-10 > 1} and the funnel's own guard is at {@code 1e-12} radians
     * past the pole, so nothing arriving through the public forward can land in it, and only a
     * direct call can show that the clamp works rather than producing {@code asin} of something
     * greater than 1, which is {@code NaN}.
     */
    @Test
    public void theKernelClampsALatitudeAHairPastThePoleInsteadOfRefusingIt() {
        double phi = ProjectionMath.HALFPI * (1 + 5e-11); // p2 = 1 + 5e-11, inside the sliver
        assertTrue("the probe must be past the pole", phi > ProjectionMath.HALFPI);
        assertTrue("the probe must be inside the sliver, not past the refusal",
                Math.abs(phi / ProjectionMath.HALFPI) - KERNEL_TOL <= 1.0);
        ProjCoordinate out = new ProjCoordinate();
        ((VanDerGrintenProjection) projection()).project(Math.toRadians(30), phi, out);
        assertEquals("a latitude inside the sliver must be clamped onto the pole, not turned into "
                + "asin of a number greater than 1", forward(30, 90).y, out.y, 0.0);
        assertEquals("and its easting is the pole's", 0.0, out.x, 0.0);
    }

    // ------------------------------------------------------------------- inverse degenerate cases

    /**
     * The inverse's own equator branch, {@code |y| < TOL}, and the {@code |x| <= TOL} short circuit
     * nested inside it that produces the centre. Both are separate from the general cubic solve.
     * <p>
     * Reference: {@code echo "<x> <y>" | proj -I -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theInverseOnTheEquatorAndAtTheCentreMatchProj() {
        assertInverse(0, 0, 0.000000000000, 0.000000000000);
        assertInverse(0.523598775598, 0, 29.999999999983, 0.000000000000);
        assertInverse(-3.124139361070, 0, -179.000000000009, 0.000000000000);
        // Inside the band rather than exactly on it: 1e-13 is well under TOL = 1e-10.
        assertInverse(0.523598775598, 1e-13, 29.999999999983, 0.000000000000);
    }

    /**
     * The inverse's central meridian short circuit, {@code |x| <= TOL ? 0 : ...}, in the general
     * arm. Without it the longitude would be {@code 0/0}.
     * <p>
     * Reference: {@code echo "<x> 0.5" | proj -I -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1) prints
     * {@code 0.000000000000  27.940157304237} for both {@code x = 0} and {@code x = 1e-13}.
     */
    @Test
    public void theInverseOnTheCentralMeridianMatchesProj() {
        assertInverse(0, 0.5, 0.000000000000, 27.940157304237);
        assertInverse(1e-13, 0.5, 0.000000000000, 27.940157304237);
        assertInverse(0, 0.841787214477, 0.000000000000, 45.000000000003);
        assertInverse(0, -0.841787214477, 0.000000000000, -45.000000000003);
    }

    /**
     * The poles, inverted from exactly {@code (0, +/-pi)} - the boundary of the map circle, where
     * {@code r == PISQ} to the bit and the cubic's root is very nearly triple. PROJ and we agree
     * here to the last printed digit.
     * <p>
     * Reference: {@code echo "0 3.141592653589793" | proj -I -f "%.12f" +proj=vandg +R=1} (PROJ
     * 9.8.1) prints {@code 0.000000000000  90.000000000000}.
     */
    @Test
    public void theInverseAtThePolesReturnsNinetyDegrees() {
        assertInverse(0, Math.PI, 0.000000000000, 90.000000000000);
        assertInverse(0, -Math.PI, 0.000000000000, -90.000000000000);
    }

    /**
     * The general inverse, which is the cubic solve of (29-11) through (29-18).
     * <p>
     * Reference: {@code echo "<x> <y>" | proj -I -f "%.12f" +proj=vandg +R=1} (PROJ 9.8.1).
     */
    @Test
    public void theGeneralInverseMatchesProj() {
        assertInverse(0.486470084203, 0.847302391342, 29.999999999985, 44.999999999984);
        assertInverse(-1.018359554876, -0.548779701935, -60.000000000024, -30.000000000011);
        assertInverse(0.209158312875, 3.134505845916, 179.899999999838, 89.899999997005);
    }

    /**
     * A round trip through every degenerate case and a spread of ordinary ones. Where a reference
     * value is only printed to twelve decimals, this is the stronger check of the two, so it is
     * here as well as the value comparisons above.
     */
    @Test
    public void everyDegenerateCaseRoundTrips() {
        double[][] points = {
            {0, 0},            // the centre
            {30, 0}, {-179, 0}, {179, 0},          // the equator
            {0, 45}, {0, -45}, {0, 89},            // the central meridian
            {0, 90}, {0, -90}, {30, 90}, {-120, -90}, // the poles
            {30, 45}, {-60, -30}, {179.9, 89.9}, {-179.9, -89.9}, // the general arm
        };
        Projection p = projection();
        for (double[] q : points) {
            ProjCoordinate xy = forward(q[0], q[1]);
            ProjCoordinate back = new ProjCoordinate();
            p.inverseProject(xy, back);
            String where = "vandg round trip at (" + q[0] + ", " + q[1] + ")";
            // At a pole the longitude is not recoverable - the whole parallel is one point - so
            // only the latitude is checked there, which is upstream's contract too.
            if (Math.abs(q[1]) < 89.99) {
                assertEquals(where + ": longitude must survive", q[0], back.x, 1e-7);
            }
            assertEquals(where + ": latitude must survive", q[1], back.y, 1e-7);
        }
    }

    // ------------------------------------------------------------------------------------ +over

    /**
     * {@code +over} past the antimeridian, which is the one place the forward's {@code sign} flip
     * and the outer {@code Math.abs} in (29-1) both matter. Our forward agrees with PROJ to the
     * last printed digit; the inverse of the same point does not, which is finding 1 - see
     * {@link #theInverseIsMissingUpstreamsRGreaterThanPiSquaredBranch()}.
     * <p>
     * Reference: {@code echo "200 45" | proj -f "%.12f" +proj=vandg +R=1 +over} (PROJ 9.8.1).
     */
    @Test
    public void theOverForwardPastTheAntimeridianMatchesProj() {
        assertForward("+proj=vandg +R=1 +over", 200, 45, 3.294371871840, 1.096188185107);
        assertForward("+proj=vandg +R=1 +over", 200, -30, 3.423172112316, -0.649485035242);
        assertForward("+proj=vandg +R=1 +over", -200, 45, -3.294371871840, 1.096188185107);
    }

    // --------------------------------------------------------------------------------- findings

    /**
     * <b>Finding 1. The values asserted here are ours, not PROJ's, and they are wrong.</b>
     *
     * <p>{@code vandg.cpp}'s {@code vandg_s_inverse} contains, immediately after computing
     * {@code 3*theta1} from (29-17) and before (29-18):
     *
     * <pre>    if (r &gt; PISQ) {
     *        // This code path is triggered for coordinates generated in the
     *        // forward path when |long|&gt;180deg and +over
     *        d = M_TWOPI - d;
     *    }</pre>
     *
     * <p>{@link VanDerGrintenProjection#projectInverse} has no equivalent. {@code r} is
     * {@code x^2 + y^2} in units of the sphere's radius, so the branch fires exactly outside the
     * map circle of radius {@code pi} - which is where {@code +over} puts everything past the
     * antimeridian, and where any inverse of an out-of-map coordinate lands.
     *
     * <p>The sizes, all measured against {@code proj -I -f "%.12f" +proj=vandg +R=1 [+over]}
     * (PROJ 9.8.1). Longitude is correct in every case; only latitude is affected:
     *
     * <table border="1">
     * <caption>latitude, ours against PROJ 9.8.1</caption>
     * <tr><th>input</th><th>ours</th><th>PROJ 9.8.1</th><th>error</th></tr>
     * <tr><td>(3.294371871840, 1.096188185107), the {@code +over} forward of (200, 45)</td>
     *     <td>41.125734607170</td><td>45.000000000000</td><td><b>3.874 deg</b></td></tr>
     * <tr><td>(0, 4)</td><td>87.436463098346</td><td>89.347491137743</td>
     *     <td><b>1.911 deg</b></td></tr>
     * <tr><td>(3.2, 3.2)</td><td>66.299275009994</td><td>76.330075167970</td>
     *     <td><b>10.031 deg</b></td></tr>
     * <tr><td>(100, 100)</td><td>2.826724968727</td><td>22.189549318108</td>
     *     <td><b>19.363 deg</b></td></tr>
     * <tr><td>(10, 10)</td><td>27.492061638620</td><td>60.170243400006</td>
     *     <td><b>32.678 deg</b></td></tr>
     * </table>
     *
     * <p>The practical consequence is that the {@code +over} round trip does not close: our own
     * forward of {@code (200, 45)} is bit-for-bit PROJ's, and inverting it loses 3.87 degrees of
     * latitude.
     *
     * <p>The diagnosis was confirmed by experiment rather than by reading: adding upstream's one
     * line to a throwaway copy of {@link VanDerGrintenProjection} reproduces the PROJ column
     * <b>to the last printed digit</b> in all five rows, and closes the {@code +over} round trip
     * at exactly {@code 45.000000000000}. The assertions below pin <em>our</em> answers so that
     * the defect cannot change size unnoticed; when it is fixed they must be replaced by the PROJ
     * column.
     *
     * <p>One class of point is deliberately <b>not</b> asserted: a hair outside the circle, say
     * {@code (0, 3.141592653590)}, which is {@code 2e-13} beyond it. There PROJ prints exactly
     * {@code 90.000000000000}, we print {@code 89.999999269995} and the patched copy prints
     * {@code 90.000000730005} - the two of ours straddling PROJ's by the same 7.3e-7 degrees. In
     * that sliver the argument of {@code acos} is within rounding of 1, so which of
     * {@code acos(d)} and the {@code t > 1} short circuit runs is decided by the last bit, and
     * neither answer is meaningful enough to pin.
     */
    @Test
    public void theInverseIsMissingUpstreamsRGreaterThanPiSquaredBranch() {
        // The +over round trip. Longitude is right, latitude is 3.874 degrees short.
        ProjCoordinate xy = forward("+proj=vandg +R=1 +over", 200, 45);
        ProjCoordinate back = new ProjCoordinate();
        projection("+proj=vandg +R=1 +over").inverseProject(xy, back);
        assertEquals("the +over longitude does round trip", 200.0, back.x, 1e-9);
        assertEquals("OUR CURRENT ANSWER, not PROJ's. PROJ 9.8.1 gives 45.0 here; the 3.874 degree "
                + "gap is the missing `if (r > PISQ) d = 2*pi - d;` in projectInverse",
                41.125734607170, back.y, 1e-9);

        // Outside the map circle, where the same branch fires without +over. Growing with
        // distance from the circle, so the ordering here is also the shape of the defect.
        assertInverseIsOurs(0, 4, 0.000000000000, 87.436463098346,
                "PROJ 9.8.1 gives 89.347491137743; 1.911 degrees");
        assertInverseIsOurs(3.2, 3.2, -61.484514844288, 66.299275009994,
                "PROJ 9.8.1 gives 76.330075167970; 10.031 degrees");
        assertInverseIsOurs(100, 100, -63.670833130583, 2.826724968727,
                "PROJ 9.8.1 gives 22.189549318108; 19.363 degrees");
        assertInverseIsOurs(10, 10, 38.338473392280, 27.492061638620,
                "PROJ 9.8.1 gives 60.170243400006; 32.678 degrees");

        // And the boundary itself, r == PISQ exactly, where NEITHER takes the branch. Our answer
        // and PROJ's still differ by 4.7e-7 degrees (PROJ gives 74.558440755248) because the cubic
        // has a nearly triple root there, so this is a conditioning difference rather than the
        // missing branch. Bounded at 1e-6 degrees, which is chosen to be larger than the observed
        // 4.7e-7 and is NOT a relaxation of any assertion above.
        ProjCoordinate onCircle = new ProjCoordinate();
        projection().inverseProject(
                new ProjCoordinate(2.221441469079183, 2.221441469079183), onCircle);
        assertEquals("on the map circle the longitude is the antimeridian",
                180.000000000000, onCircle.x, 1e-9);
        assertEquals("on the map circle, where r == pi^2 and neither side flips d",
                74.558440755248, onCircle.y, 1e-6);
    }

    /**
     * <b>Finding 2. The value asserted here is ours, not PROJ's, and it is wrong. The defect is
     * not in this class.</b>
     *
     * <p>{@code Projection.projectRadians} guards the {@code lam0} subtraction and the
     * {@code adjlon} wrap together:
     *
     * <pre>    if ( projectionLongitude != 0 ) {
     *        x -= projectionLongitude;
     *        if ( !over ) x = ProjectionMath.normalizeLongitude( x );
     *    }</pre>
     *
     * <p>{@code fwd_prepare} ({@code 9.8.1:src/fwd.cpp:117-122}) subtracts unconditionally and then
     * wraps whenever {@code +over} is off, with no reference to {@code lam0} at all. So when
     * {@code +lon_0} is absent - the commonest case there is - a longitude outside
     * {@code [-180, 180]} reaches the kernel unwrapped, and every projection behaves as though
     * {@code +over} were on.
     *
     * <p>Measured, with {@code echo "200 45" | proj -f "%.12f" <definition>} (PROJ 9.8.1):
     *
     * <table border="1">
     * <caption>forward of (200, 45)</caption>
     * <tr><th>definition</th><th>ours</th><th>PROJ 9.8.1</th></tr>
     * <tr><td>{@code +proj=vandg +R=1}</td><td>(2.662432276087, 1.007604496732)</td>
     *     <td>(-2.629152107471, 1.003469362960)</td></tr>
     * <tr><td>{@code +proj=merc +R=1}</td><td>(3.490658503989, 0.881373587020)</td>
     *     <td>(-2.792526803191, 0.881373587020)</td></tr>
     * </table>
     *
     * <p>Adding {@code +lon_0=0.0000001} restores the wrap and PROJ agreement in both, which is
     * the cleanest demonstration that the guard, not the arithmetic, is the cause - and it is
     * asserted below so that this test states the diagnosis rather than merely the symptom.
     * {@code vandg} is only where it was noticed; it is a {@code Projection} defect and it affects
     * every operator.
     */
    @Test
    public void theForwardDoesNotWrapLongitudeWhenLon0IsZero() {
        // Our answer, pinned. PROJ 9.8.1 gives (-2.629152107471, 1.003469362960).
        ProjCoordinate unwrapped = forward(VANDG, 200, 45);
        assertEquals("OUR CURRENT ANSWER, not PROJ's, and it is the projection of longitude 200 "
                + "rather than of -160. PROJ 9.8.1 gives -2.629152107471",
                2.662432276087, unwrapped.x, UNIT_RADIUS_TOL);
        assertEquals("OUR CURRENT ANSWER, not PROJ's. PROJ 9.8.1 gives 1.003469362960",
                1.007604496732, unwrapped.y, UNIT_RADIUS_TOL);

        // The diagnosis: a lon_0 of a ten-millionth of a degree turns the wrap back on, and then
        // we agree with PROJ. The shift itself is 1.7e-9 radians, far below the 1e-11 bound.
        ProjCoordinate wrapped = forward("+proj=vandg +R=1 +lon_0=0.0000001", 200, 45);
        assertEquals("with a non-zero lon_0 the wrap happens and we match PROJ",
                -2.629152109135, wrapped.x, UNIT_RADIUS_TOL);
        assertEquals("with a non-zero lon_0 the wrap happens and we match PROJ",
                1.003469363165, wrapped.y, UNIT_RADIUS_TOL);

        // Same projection, same point, two answers 5.3 radians apart because of one keyword that
        // moves the central meridian by a ten-millionth of a degree.
        assertTrue("the two answers must differ by far more than the shift in lon_0 could explain",
                Math.abs(unwrapped.x - wrapped.x) > 5.0);
    }

    // ------------------------------------------------------------------------------------ plumbing

    private static Projection projection() {
        return projection(VANDG);
    }

    private static Projection projection(String definition) {
        return FACTORY.createFromParameters("t", definition).getProjection();
    }

    private static ProjCoordinate forward(double lonDeg, double latDeg) {
        return forward(VANDG, lonDeg, latDeg);
    }

    private static ProjCoordinate forward(String definition, double lonDeg, double latDeg) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).project(new ProjCoordinate(lonDeg, latDeg), out);
        return out;
    }

    private static void assertForward(double lonDeg, double latDeg,
            double expectedX, double expectedY) {
        assertForward(VANDG, lonDeg, latDeg, expectedX, expectedY);
    }

    private static void assertForward(String definition, double lonDeg, double latDeg,
            double expectedX, double expectedY) {
        ProjCoordinate xy = forward(definition, lonDeg, latDeg);
        String where = definition + " at (" + lonDeg + ", " + latDeg + ")";
        assertEquals(where + " easting", expectedX, xy.x, UNIT_RADIUS_TOL);
        assertEquals(where + " northing", expectedY, xy.y, UNIT_RADIUS_TOL);
    }

    private static void assertInverse(double x, double y,
            double expectedLon, double expectedLat) {
        ProjCoordinate lp = new ProjCoordinate();
        projection().inverseProject(new ProjCoordinate(x, y), lp);
        String where = "vandg inverse at (" + x + ", " + y + ")";
        assertEquals(where + " longitude", expectedLon, lp.x, DEGREE_TOL);
        assertEquals(where + " latitude", expectedLat, lp.y, DEGREE_TOL);
    }

    /** As {@link #assertInverse}, but the expected values are ours and the note says why. */
    private static void assertInverseIsOurs(double x, double y,
            double ourLon, double ourLat, String note) {
        ProjCoordinate lp = new ProjCoordinate();
        projection().inverseProject(new ProjCoordinate(x, y), lp);
        String where = "vandg inverse at (" + x + ", " + y + ")";
        assertEquals(where + " longitude (this one is correct)", ourLon, lp.x, DEGREE_TOL);
        assertEquals(where + " latitude: OUR CURRENT ANSWER, not PROJ's. " + note,
                ourLat, lp.y, DEGREE_TOL);
    }
}
