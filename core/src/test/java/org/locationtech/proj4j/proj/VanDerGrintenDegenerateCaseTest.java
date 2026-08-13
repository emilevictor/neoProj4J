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
 * bands around them - forward and inverse. It also recorded <b>two disagreements with PROJ
 * 9.8.1</b> that this coverage exposed; <b>both are now fixed</b> and the two tests that used to
 * pin our wrong answers now pin PROJ's. See "Findings" below - they are kept, in the past tense,
 * because the sizes are the argument for why the fixes were worth making.
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
 * test records the exact command.
 *
 * <p>Before the two fixes, three assertions carried <em>our</em> values rather than PROJ's and said
 * so in their first sentence. <b>One is left</b>, and it is not a disagreement about the formula:
 * on the map circle itself, where {@code r == PISQ} and neither side flips {@code d}, the cubic has
 * a nearly triple root and the two answers differ by 4.7e-7 degrees. It is bounded rather than
 * pinned exactly, and it says why in place.
 *
 * <h2>Findings</h2>
 *
 * <ol>
 * <li><b>The inverse was missing upstream's {@code r > PISQ} branch. Fixed.</b>
 *     {@code vandg.cpp}'s {@code vandg_s_inverse} contains, between (29-17) and (29-18),
 *     {@code if (r > PISQ) { d = M_TWOPI - d; }} with the comment "This code path is triggered for
 *     coordinates generated in the forward path when |long|&gt;180deg and +over".
 *     {@link VanDerGrintenProjection#projectInverse} had no such test, so the {@code +over} round
 *     trip was broken in latitude: our own forward of {@code (200, 45)} under {@code +over}
 *     matched PROJ to the last printed digit, and inverting it gave <b>41.1257</b> where PROJ
 *     gives <b>45.0000</b> - a <b>3.87 degree</b> error - and any inverse of a point outside the
 *     map circle was wrong by up to <b>32.7 degrees</b>. The branch is now present and all five
 *     rows below agree with PROJ to the last printed digit. See
 *     {@link #theInverseTakesUpstreamsRGreaterThanPiSquaredBranch()}.</li>
 * <li><b>The forward funnel did not wrap longitude when {@code +lon_0} was absent or zero.
 *     Fixed.</b> This was never a {@code vandg} defect - it was in {@code Projection}, whose
 *     {@code projectRadians} guarded <em>both</em> the {@code lam0} subtraction and the
 *     {@code adjlon} wrap with {@code if (projectionLongitude != 0)}, while {@code fwd_prepare}
 *     ({@code 9.8.1:src/fwd.cpp:108} and {@code :111-112}) subtracts unconditionally and wraps
 *     whenever {@code +over} is off, mentioning {@code lam0} in neither line. It surfaced here
 *     because {@code vandg} is a whole-world projection people feed unnormalised longitudes to.
 *     Both statements are now unconditional. See
 *     {@link #theForwardWrapsLongitudeWithNoLon0AtAll()}.</li>
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
     * last printed digit. The inverse of this same point used to disagree - that was finding 1, and
     * it is fixed; the round trip is asserted in
     * {@link #theInverseTakesUpstreamsRGreaterThanPiSquaredBranch()}.
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
     * <b>Finding 1, fixed. The values asserted here are PROJ 9.8.1's; they used to be ours and
     * ours were wrong.</b>
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
     * <p>{@link VanDerGrintenProjection#projectInverse} had no equivalent. {@code r} is
     * {@code x^2 + y^2} in units of the sphere's radius, so the branch fires exactly outside the
     * map circle of radius {@code pi} - which is where {@code +over} puts everything past the
     * antimeridian, and where any inverse of an out-of-map coordinate lands.
     *
     * <p>The sizes it was wrong by, all measured against
     * {@code proj -I -f "%.12f" +proj=vandg +R=1 [+over]} (PROJ 9.8.1). Longitude was correct in
     * every case; only latitude was affected, which is why a round trip that checked longitude
     * alone would not have caught it:
     *
     * <table border="1">
     * <caption>latitude, ours before the fix against PROJ 9.8.1</caption>
     * <tr><th>input</th><th>ours, before</th><th>PROJ 9.8.1</th><th>error</th></tr>
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
     * <p>The practical consequence was that the {@code +over} round trip did not close: our own
     * forward of {@code (200, 45)} is bit-for-bit PROJ's, and inverting it lost 3.87 degrees of
     * latitude. <b>The whole PROJ column above is now what we return</b>, to the last printed
     * digit, and the round trip closes at exactly {@code 45.000000000000}. That was measured on a
     * throwaway patched copy before the fix landed and again on the fix itself, and the assertions
     * below are the PROJ column.
     *
     * <p>One class of point is deliberately <b>not</b> asserted: a hair outside the circle, say
     * {@code (0, 3.141592653590)}, which is {@code 2e-13} beyond it. There PROJ prints exactly
     * {@code 90.000000000000}, we used to print {@code 89.999999269995} and we now print
     * {@code 90.000000730005} - the two of ours straddling PROJ's by the same 7.3e-7 degrees, so
     * the fix did not make that sliver better or worse, it moved it to the other side. In there the
     * argument of {@code acos} is within rounding of 1, so which of {@code acos(d)} and the
     * {@code t > 1} short circuit runs is decided by the last bit, and neither answer is
     * meaningful enough to pin.
     */
    @Test
    public void theInverseTakesUpstreamsRGreaterThanPiSquaredBranch() {
        // The +over round trip, which is the whole point: it closes in latitude as well as
        // longitude now. Before the fix the latitude came back 3.874 degrees short.
        ProjCoordinate xy = forward("+proj=vandg +R=1 +over", 200, 45);
        ProjCoordinate back = new ProjCoordinate();
        projection("+proj=vandg +R=1 +over").inverseProject(xy, back);
        assertEquals("the +over longitude round trips", 200.0, back.x, 1e-9);
        assertEquals("and so does the latitude, which used to come back as 41.125734607170",
                45.000000000000, back.y, 1e-9);

        // Outside the map circle, where the same branch fires without +over. PROJ's values. The
        // ordering here was the shape of the defect: the further out, the worse it was.
        assertInverse(0, 4, 0.000000000000, 89.347491137743);
        assertInverse(3.2, 3.2, -61.484514844288, 76.330075167970);
        assertInverse(100, 100, -63.670833130583, 22.189549318108);
        assertInverse(10, 10, 38.338473392280, 60.170243400006);

        // And the boundary itself, r == PISQ exactly, where NEITHER side takes the branch - so
        // this row is unmoved by the fix, and it is the one place in the file where the asserted
        // latitude is still ours. It differs from PROJ's 74.558440755248 by 4.7e-7 degrees because
        // the cubic has a nearly triple root there: a conditioning difference, not a formula
        // difference. Bounded at 1e-6, chosen to be larger than the observed 4.7e-7, and NOT a
        // relaxation of any assertion above - every one of those is pinned at 1e-11.
        ProjCoordinate onCircle = new ProjCoordinate();
        projection().inverseProject(
                new ProjCoordinate(2.221441469079183, 2.221441469079183), onCircle);
        assertEquals("on the map circle the longitude is the antimeridian",
                180.000000000000, onCircle.x, 1e-9);
        // The one place in this class where the asserted value is still not reproduced exactly.
        // 2.221441469079183 is pi/sqrt(2), so r is pi^2 to within a rounding error and the new
        // branch does not fire on either side. PROJ prints 74.558440755248 and we compute
        // 74.558441227157 - 4.7e-7 of a degree apart, which is why this one bound is 1e-6 and every
        // other in the class is DEGREE_TOL. It is a conditioning difference, not a branch
        // difference: on the circle the cubic's discriminant is near zero, so acos is being asked
        // for an angle whose cosine is one ulp from the edge of its domain and the last few digits
        // depend on the order the compiler evaluated c0..c3 in.
        assertEquals("on the map circle, where r == pi^2 and neither side flips d",
                74.558440755248, onCircle.y, 1e-6);
    }

    /**
     * <b>Finding 2, fixed. The value asserted here is PROJ 9.8.1's; it used to be ours and ours was
     * wrong. The defect was never in this class.</b>
     *
     * <p>{@code Projection.projectRadians} used to guard the {@code lam0} subtraction and the
     * {@code adjlon} wrap together:
     *
     * <pre>    if ( projectionLongitude != 0 ) {
     *        x -= projectionLongitude;
     *        if ( !over ) x = ProjectionMath.normalizeLongitude( x );
     *    }</pre>
     *
     * <p>{@code fwd_prepare} subtracts unconditionally ({@code 9.8.1:src/fwd.cpp:108}) and then
     * wraps whenever {@code +over} is off ({@code :111-112}), with no reference to {@code lam0} in
     * either line. So when {@code +lon_0} was absent - the commonest case there is - a longitude
     * outside {@code [-180, 180]} reached the kernel unwrapped and every projection behaved as
     * though {@code +over} were on. Both statements are now unconditional.
     *
     * <p>Measured, with {@code echo "200 45" | proj -f "%.12f" <definition>} (PROJ 9.8.1):
     *
     * <table border="1">
     * <caption>forward of (200, 45)</caption>
     * <tr><th>definition</th><th>ours, before</th><th>PROJ 9.8.1, and ours now</th></tr>
     * <tr><td>{@code +proj=vandg +R=1}</td><td>(2.662432276087, 1.007604496732)</td>
     *     <td>(-2.629152107471, 1.003469362960)</td></tr>
     * <tr><td>{@code +proj=merc +R=1}</td><td>(3.490658503989, 0.881373587020)</td>
     *     <td>(-2.792526803191, 0.881373587020)</td></tr>
     * </table>
     *
     * <p>The {@code +lon_0=0.0000001} control below is what identified the guard rather than the
     * arithmetic as the cause, back when only that spelling wrapped. It is kept, inverted: the two
     * answers now have to <em>agree</em>, and to agree to within the {@code 1.7e-9} radians that a
     * ten-millionth of a degree of {@code lon_0} actually moves them. Before the fix they were 5.3
     * radians apart. An assertion that they agree exactly would be wrong, and one that merely
     * bounded them loosely would pass again if the guard came back for {@code lon_0 == 0} only, so
     * the bound is two-sided.
     *
     * <p>{@code vandg} is only where this was noticed. It was a {@code Projection} defect and it
     * affected every operator, which is why {@code RawLongitudeDomainCheckTest} carries the
     * general version of the assertion and this one keeps only the {@code vandg} numbers.
     */
    @Test
    public void theForwardWrapsLongitudeWithNoLon0AtAll() {
        // PROJ 9.8.1's answer. Before the fix this was (2.662432276087, 1.007604496732) - the
        // projection of longitude 200 rather than of -160.
        ProjCoordinate unwrapped = forward(VANDG, 200, 45);
        assertEquals("longitude 200 must be wrapped to -160 with no +lon_0 present at all",
                -2.629152107471, unwrapped.x, UNIT_RADIUS_TOL);
        assertEquals("and the northing goes with it, from 1.007604496732 to PROJ's value",
                1.003469362960, unwrapped.y, UNIT_RADIUS_TOL);

        // The control that identified the guard: a lon_0 of a ten-millionth of a degree used to be
        // the only spelling that wrapped. It still wraps, and now so does the line above.
        ProjCoordinate wrapped = forward("+proj=vandg +R=1 +lon_0=0.0000001", 200, 45);
        assertEquals("with a non-zero lon_0 the wrap still happens and we still match PROJ",
                -2.629152109135, wrapped.x, UNIT_RADIUS_TOL);
        assertEquals("with a non-zero lon_0 the wrap still happens and we still match PROJ",
                1.003469363165, wrapped.y, UNIT_RADIUS_TOL);

        // Two-sided, and this assertion is the inverse of the one it replaces. The two answers
        // must now differ by the lon_0 shift and by nothing else: 1e-7 degrees is 1.745e-9 radians,
        // and the measured gap is 1.664e-9. Bounded below as well as above, because a gap of
        // exactly zero would mean lon_0 had stopped being subtracted.
        double gap = Math.abs(unwrapped.x - wrapped.x);
        assertTrue("the two answers must now agree to within the lon_0 shift, not be 5.3 radians "
                + "apart as they were before the fix; measured gap " + gap,
                gap < 5e-9);
        assertTrue("but they must not be identical - a ten-millionth of a degree of lon_0 is still "
                + "subtracted, and a gap of zero would mean it was not; measured gap " + gap,
                gap > 1e-10);
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

    // assertInverseIsOurs used to live here, taking an extra `note` argument to say why an
    // asserted latitude was ours rather than PROJ's. Every caller now asserts PROJ's value through
    // assertInverse, so it is deleted rather than kept unused.
}
