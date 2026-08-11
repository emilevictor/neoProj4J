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
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;

/**
 * Pins every branch of {@link GnomonicAzimuthalProjection}: all four spherical aspects (north
 * polar, south polar, equatorial, oblique) forward and inverse, the horizon refusal that makes the
 * gnomonic projection a hemisphere-only operator, the degenerate inverse at the projection centre,
 * the two {@code asin} clamps in the spherical inverse, and both arms of the separate ellipsoidal
 * geodesic kernel.
 *
 * <h2>Why these cases and not a grid of ordinary points</h2>
 *
 * <p>{@code gnom} is four different formulas wearing one name. {@code AzimuthalProjection.initialize}
 * turns {@code +lat_0} into one of {@code NORTH_POLE}, {@code SOUTH_POLE}, {@code EQUATOR} or
 * {@code OBLIQUE}, and {@link GnomonicAzimuthalProjection#project} and
 * {@link GnomonicAzimuthalProjection#projectInverse} each switch on it <em>twice</em> - once to
 * compute the cosine of the angular distance from the centre and once to finish the ordinates. A
 * test that only ever exercises one {@code +lat_0} leaves three quarters of the class untouched,
 * and the polar arms are exactly the ones where a sign slip is invisible on a round-trip (see
 * {@code NORTH_POLE}'s fallthrough into {@code SOUTH_POLE} via {@code coslam = -coslam}).
 *
 * <p>On top of that the class carries a <b>second, entirely separate kernel</b>: when the figure is
 * an ellipsoid it runs Karney's geodesic gnomonic instead, with its own forward, its own Newton
 * inverse, and - crucially - <b>a different domain</b>. On a sphere the polar aspect cannot see the
 * equator at all; on the ellipsoid it can. Both contracts are pinned below.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every coordinate in this file was produced by the {@code proj} binary of <b>PROJ 9.8.1</b>
 * ({@code Rel. 9.8.1, April 10th, 2026}), and each test records the exact command. Nothing here is
 * "whatever our code printed" unless it says so in as many words - and one case does say so, the
 * ellipsoidal north-polar view of the equator, where the value is enormous and PROJ's twelve
 * printed decimals are not enough to pin it absolutely.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>JaCoCo measured this class at <b>34.1% of 545 instructions</b> before this test existed. A
 * wrong sign in a polar arm, a lost {@code coslam = -coslam} fallthrough, a horizon test written
 * as {@code fabs(xy.y) <= EPS10} instead of {@code xy.y <= EPS10} (which silently accepts the whole
 * far hemisphere and mirrors it onto the near one), a spherical kernel used for an ellipsoid, or a
 * Newton inverse that stops iterating - none of those would be caught by anything else in the tree.
 */
public class GnomonicAzimuthalAspectTest {

    /**
     * Unit-radius outputs are O(1) and PROJ prints twelve decimals, so this is the resolution of
     * the reference itself, not a tolerance chosen to make anything pass.
     */
    private static final double UNIT_RADIUS_TOL = 1e-11;

    /** Degrees out of an inverse. Same reasoning: PROJ prints twelve decimals. */
    private static final double DEGREE_TOL = 1e-11;

    /** Relative bound for metre-scale ellipsoidal outputs; PROJ's printout limits us to this. */
    private static final double METRE_REL_TOL = 1e-12;

    private static final CRSFactory FACTORY = new CRSFactory();

    private static final String EQUATORIAL = "+proj=gnom +R=1 +lat_0=0 +lon_0=0";
    private static final String NORTH_POLAR = "+proj=gnom +R=1 +lat_0=90 +lon_0=0";
    private static final String SOUTH_POLAR = "+proj=gnom +R=1 +lat_0=-90 +lon_0=0";
    private static final String OBLIQUE = "+proj=gnom +R=1 +lat_0=40 +lon_0=0";

    // ------------------------------------------------------------------ the aspects are distinct

    /**
     * The four {@code +lat_0} values below really do select four different code paths. Without
     * this, the four forward tests could all be exercising {@code EQUATOR} and still pass, because
     * a wrongly classified aspect usually still produces <em>a</em> coordinate.
     */
    @Test
    public void eachLat0SelectsTheAspectWhoseFormulaTheTestsBelow() {
        assertEquals("+lat_0=0 must select the equatorial formula",
                AzimuthalProjection.EQUATOR, aspectOf(EQUATORIAL));
        assertEquals("+lat_0=90 must select the north polar formula",
                AzimuthalProjection.NORTH_POLE, aspectOf(NORTH_POLAR));
        assertEquals("+lat_0=-90 must select the south polar formula",
                AzimuthalProjection.SOUTH_POLE, aspectOf(SOUTH_POLAR));
        assertEquals("+lat_0=40 must select the oblique formula",
                AzimuthalProjection.OBLIQUE, aspectOf(OBLIQUE));
    }

    private static int aspectOf(String definition) {
        return ((AzimuthalProjection) projection(definition)).mode;
    }

    // ---------------------------------------------------------------------------------- forwards

    /**
     * Equatorial aspect, {@code xy.y = cos(phi) cos(lam)} then {@code xy.y *= sin(phi)}.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=gnom +R=1 +lat_0=0 +lon_0=0}
     * (PROJ 9.8.1).
     */
    @Test
    public void theEquatorialForwardMatchesProj() {
        assertForward(EQUATORIAL, 10, 20, 0.176326980708, 0.369585061808, UNIT_RADIUS_TOL);
        assertForward(EQUATORIAL, -30, -15, -0.577350269190, -0.309401076759, UNIT_RADIUS_TOL);
        // The centre itself: cos(0)cos(0) = 1, so both ordinates are 0.
        assertForward(EQUATORIAL, 0, 0, 0.000000000000, 0.000000000000, UNIT_RADIUS_TOL);
        // On the central meridian, 45 degrees out: tan(45) = 1 exactly.
        assertForward(EQUATORIAL, 0, 45, 0.000000000000, 1.000000000000, UNIT_RADIUS_TOL);
    }

    /**
     * North polar aspect, {@code xy.y = sin(phi)}, finished through the
     * {@code coslam = -coslam} fallthrough into the south polar tail.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=gnom +R=1 +lat_0=90 +lon_0=0}
     * (PROJ 9.8.1).
     */
    @Test
    public void theNorthPolarForwardMatchesProj() {
        assertForward(NORTH_POLAR, 10, 80, 0.030618858874, -0.173648177667, UNIT_RADIUS_TOL);
        assertForward(NORTH_POLAR, -120, 45, -0.866025403784, 0.500000000000, UNIT_RADIUS_TOL);
        // One degree from the equator, still visible from the north pole, and very nearly the
        // horizon: cot(1 deg) = 57.29. This is the value the sign of the fallthrough decides.
        assertForward(NORTH_POLAR, 0, 1, 0.000000000000, -57.289961630759, UNIT_RADIUS_TOL);
    }

    /**
     * South polar aspect, {@code xy.y = -sin(phi)}, finished without the negation of
     * {@code coslam}. Its outputs are the north polar ones with the northing reflected, which is
     * the specific relation a lost fallthrough would destroy.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=gnom +R=1 +lat_0=-90 +lon_0=0}
     * (PROJ 9.8.1).
     */
    @Test
    public void theSouthPolarForwardMatchesProj() {
        assertForward(SOUTH_POLAR, 10, -80, 0.030618858874, 0.173648177667, UNIT_RADIUS_TOL);
        assertForward(SOUTH_POLAR, -120, -45, -0.866025403784, -0.500000000000, UNIT_RADIUS_TOL);
        assertForward(SOUTH_POLAR, 0, -1, 0.000000000000, 57.289961630759, UNIT_RADIUS_TOL);
    }

    /**
     * Oblique aspect, the only arm that reads both {@code sinphi0} and {@code cosphi0}.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=gnom +R=1 +lat_0=40 +lon_0=0}
     * (PROJ 9.8.1).
     */
    @Test
    public void theObliqueForwardMatchesProj() {
        assertForward(OBLIQUE, 10, 45, 0.124283501931, 0.095206686026, UNIT_RADIUS_TOL);
        assertForward(OBLIQUE, -20, 30, -0.313503860434, -0.148261000835, UNIT_RADIUS_TOL);
        // The centre of an oblique aspect must still be the origin of the plane.
        assertForward(OBLIQUE, 0, 40, 0.000000000000, 0.000000000000, UNIT_RADIUS_TOL);
    }

    // ------------------------------------------------------------------------------- the horizon

    /**
     * A gnomonic plane touches the sphere at one point and can show strictly less than a
     * hemisphere. {@code gnom.cpp:50} refuses anything at or past 90 degrees of angular distance
     * from the centre, and the test is {@code xy.y <= EPS10} on the <em>signed</em> cosine of that
     * distance - not on its absolute value. Every aspect must refuse, in both the "exactly on the
     * horizon" and the "past it" cases.
     * <p>
     * Reference: {@code proj} 9.8.1 prints {@code *\t*} for every one of these
     * ({@code echo "<lon> <lat>" | proj -f "%.12f" <definition>}).
     */
    @Test
    public void everyAspectRefusesAPointAtOrBeyondNinetyDegreesFromTheCentre() {
        // Exactly on the horizon. cos(90 deg) is 6.1e-17 in doubles, which is <= EPS10.
        assertRefused(EQUATORIAL, 90, 0);
        assertRefused(EQUATORIAL, 0, 90);
        assertRefused(NORTH_POLAR, 0, 0);
        assertRefused(SOUTH_POLAR, 0, 0);
        assertRefused(OBLIQUE, 0, -50);

        // Past the horizon: the signed cosine is negative.
        assertRefused(EQUATORIAL, 100, 0);
        assertRefused(NORTH_POLAR, 0, -10);
        assertRefused(SOUTH_POLAR, 0, 10);
        assertRefused(OBLIQUE, 180, 0);
    }

    /**
     * The refusal is sign sensitive, not magnitude sensitive. Written {@code fabs(xy.y) <= EPS10}
     * the guard would accept every point in the far hemisphere and project it, mirrored, onto the
     * near one - the far point would come out at the coordinates of its antipode instead of being
     * refused. This asserts that the antipode's coordinates are what a refused point does
     * <em>not</em> produce.
     */
    @Test
    public void aPointBeyondTheHorizonIsRefusedRatherThanMirroredOntoItsAntipode() {
        // (100, 0) is 100 degrees from the equatorial centre; its antipode (-80, 0) is 80 degrees
        // away and projects perfectly well. Under the abs()-guard bug the two would collide.
        ProjCoordinate antipode = forward(EQUATORIAL, -80, 0);
        assertTrue("the antipode of the refused point must itself be projectable",
                Math.abs(antipode.x) > 1);
        assertRefused(EQUATORIAL, 100, 0);
    }

    // ---------------------------------------------------------------------------------- inverses

    /**
     * The inverse of each aspect's own forward output, taken from PROJ rather than from our
     * forward, so that a matched pair of forward and inverse errors cannot cancel.
     * <p>
     * Reference: {@code echo "<x> <y>" | proj -I -f "%.12f" <definition>} (PROJ 9.8.1). The
     * fourteenth digit of drift below is PROJ's own; it is what the binary prints.
     */
    @Test
    public void eachAspectInverseMatchesProj() {
        assertInverse(EQUATORIAL, 0.176326980708, 0.369585061808,
                9.999999999974, 19.999999999992, DEGREE_TOL);
        assertInverse(EQUATORIAL, 0, 1, 0.000000000000, 45.000000000000, DEGREE_TOL);

        assertInverse(NORTH_POLAR, 0.030618858874, -0.173648177667,
                10.000000000144, 79.999999999992, DEGREE_TOL);
        assertInverse(NORTH_POLAR, -0.866025403784, 0.5,
                -120.000000000013, 45.000000000011, DEGREE_TOL);

        assertInverse(SOUTH_POLAR, 0.030618858874, 0.173648177667,
                10.000000000144, -79.999999999992, DEGREE_TOL);

        assertInverse(OBLIQUE, 0.124283501931, 0.095206686026,
                9.999999999966, 45.000000000006, DEGREE_TOL);
        assertInverse(OBLIQUE, -0.313503860434, -0.148261000835,
                -19.999999999975, 29.999999999996, DEGREE_TOL);
    }

    /**
     * The {@code |rh| <= EPS10} short circuit: the origin of the plane is the projection centre,
     * for every aspect, and the longitude is 0 rather than whatever {@code atan2(0, 0)} would
     * return. This is the only place {@code projectionLatitude} is read directly by the inverse.
     * <p>
     * Reference: {@code echo "0 0" | proj -I -f "%.12f" <definition>} (PROJ 9.8.1) prints
     * {@code 0.000000000000  <lat_0>} for all four.
     */
    @Test
    public void theInverseAtTheOriginReturnsTheProjectionCentre() {
        assertInverse(EQUATORIAL, 0, 0, 0.0, 0.0, DEGREE_TOL);
        assertInverse(NORTH_POLAR, 0, 0, 0.0, 90.0, DEGREE_TOL);
        assertInverse(SOUTH_POLAR, 0, 0, 0.0, -90.0, DEGREE_TOL);
        assertInverse(OBLIQUE, 0, 0, 0.0, 40.0, DEGREE_TOL);
    }

    /**
     * Both {@code asin} clamps, {@code if (|lp.y| >= 1) lp.y = +/- pi/2}.
     *
     * <p>They are not decoration. In the oblique arm the clamp is reached at an ordinary,
     * finite, perfectly reasonable point: the <b>north pole</b>, whose oblique gnomonic northing
     * is exactly {@code cot(lat_0)} - {@code 1.19175359259421} for {@code +lat_0=40} - at which
     * {@code (sin(phi0) + y cos(phi0)) / sqrt(1 + y^2)} is algebraically 1. Without the clamp,
     * a rounding excursion past 1 makes {@code Math.asin} return {@code NaN} and the pole of an
     * oblique gnomonic map becomes a numerical failure.
     *
     * <p>In the equatorial arm the quotient is {@code y / sqrt(1 + x^2 + y^2)}, which reaches 1
     * only in the limit; a large northing gets there in double arithmetic.
     * <p>
     * Reference: {@code echo "0 1.19175359259421" | proj -I -f "%.12f" +proj=gnom +R=1 +lat_0=40}
     * and {@code echo "0 1000000000" | proj -I -f "%.12f" +proj=gnom +R=1 +lat_0=0} (PROJ 9.8.1).
     */
    @Test
    public void bothAsinClampsInTheSphericalInverseAreReachedAndAgreeWithProj() {
        // Oblique: the north pole, exactly on the clamp.
        assertInverse(OBLIQUE, 0, 1.19175359259421, 0.0, 90.000000000000, DEGREE_TOL);
        // Oblique, the other side of the same expression: not clamped, and not symmetric with it.
        assertInverse(OBLIQUE, 0, -1.19175359259421, 0.0, -10.000000000000, DEGREE_TOL);
        // Oblique, far out: the quotient tends to cos(lat_0), so the latitude tends to
        // 90 - lat_0 = 50 on the far side of the map.
        assertInverse(OBLIQUE, 0, 1e9, 180.000000000000, 50.000000000000, DEGREE_TOL);
        // Equatorial: both signs of the clamp.
        assertInverse(EQUATORIAL, 0, 1e9, 0.0, 90.000000000000, DEGREE_TOL);
        assertInverse(EQUATORIAL, 0, -1e9, 0.0, -90.000000000000, DEGREE_TOL);
    }

    /**
     * A round trip through every aspect, over a spread of points inside each one's visible
     * hemisphere. This is the strongest check available for the interior of the domain without
     * quoting a reference value for each point, and it is the check that would catch a forward and
     * an inverse that had drifted apart.
     */
    @Test
    public void everyAspectRoundTrips() {
        String[][] cases = {
            {EQUATORIAL, "0"}, {NORTH_POLAR, "90"}, {SOUTH_POLAR, "-90"}, {OBLIQUE, "40"},
        };
        int checked = 0;
        for (String[] c : cases) {
            Projection p = projection(c[0]);
            double lat0 = Double.parseDouble(c[1]);
            for (double dLon = -60; dLon <= 60; dLon += 20) {
                for (double dLat = -60; dLat <= 60; dLat += 20) {
                    double lat = lat0 + dLat;
                    if (lat > 89 || lat < -89) {
                        continue;
                    }
                    ProjCoordinate xy;
                    try {
                        xy = forward(c[0], dLon, lat);
                    } catch (ProjectionException e) {
                        continue; // outside this aspect's hemisphere; covered separately above
                    }
                    ProjCoordinate back = new ProjCoordinate();
                    p.inverseProject(xy, back);
                    String where = c[0] + " at (" + dLon + ", " + lat + ")";
                    assertEquals(where + " longitude must survive the round trip",
                            dLon, back.x, 1e-9);
                    assertEquals(where + " latitude must survive the round trip",
                            lat, back.y, 1e-9);
                    checked++;
                }
            }
        }
        // Without this the loop could reject every probe and pass by doing nothing.
        assertTrue("only " + checked + " round trips ran, which is too few to be a measurement",
                checked > 40);
    }

    // -------------------------------------------------------------------- the ellipsoidal kernel

    /**
     * {@code gnom.cpp} dispatches on {@code P-&gt;es == 0} and the ellipsoidal side is a wholly
     * different algorithm - one geodesic inverse solution per point, not a closed form. If the
     * dispatch were lost, an ellipsoidal {@code gnom} would silently answer the spherical formulas
     * and be wrong by metres.
     * <p>
     * Reference: {@code echo "<lon> <lat>" | proj -f "%.12f" +proj=gnom +ellps=WGS84 +lat_0=<x>}
     * (PROJ 9.8.1).
     */
    @Test
    public void theEllipsoidalKernelIsUsedForAnEllipsoidAndMatchesProj() {
        assertForwardRelative("+proj=gnom +ellps=WGS84 +lat_0=40 +lon_0=0", 10, 45,
                794033.992835516576, 606135.247744990978);
        assertForwardRelative("+proj=gnom +ellps=WGS84 +lat_0=40 +lon_0=0", -20, 30,
                -2001311.663046872942, -941573.728803981096);
        assertForwardRelative("+proj=gnom +ellps=WGS84 +lat_0=0 +lon_0=0", 10, 20,
                1124713.736254138872, 2342041.514079806395);
        assertForwardRelative("+proj=gnom +ellps=WGS84 +lat_0=90 +lon_0=0", 10, 80,
                195927.936689492461, -1111162.545302328654);
        assertForwardRelative("+proj=gnom +ellps=WGS84 +lat_0=-90 +lon_0=0", 10, -80,
                195927.936689492519, 1111162.545302328654);

        // And the spherical formula does NOT produce these. cos(distance) for +lat_0=40 at
        // (10, 45) is 0.9884, so the spherical kernel would answer 0.1243 * 6378137 = 792817 m -
        // 1.2 km from the ellipsoidal answer. Asserting the gap makes "the dispatch is alive" a
        // claim this test can fail on rather than one it assumes.
        double sphericalEasting = 6378137.0 * 0.124283501931;
        assertTrue("the ellipsoidal easting must not coincide with the spherical one",
                Math.abs(794033.992835516576 - sphericalEasting) > 1000.0);
    }

    /**
     * <b>The two kernels have different domains, and this is the case that proves it.</b> On a
     * sphere the north polar gnomonic cannot see the equator at all - the equator is exactly 90
     * degrees away, so {@code cos = 0} and the point is refused. On an ellipsoid the geodesic
     * scale {@code M12} at the equator is still positive, so the point is finite. A class with only
     * the spherical kernel cannot satisfy both.
     * <p>
     * Reference: {@code echo "0 0" | proj -f "%.12f" +proj=gnom +a=1 +rf=200 +lat_0=90 +lon_0=0}
     * (PROJ 9.8.1) prints {@code 0.000000000000  -127.483508426376}, while
     * {@code echo "0 0" | proj -f "%.12f" +proj=gnom +R=1 +lat_0=90 +lon_0=0} prints {@code *  *}.
     */
    @Test
    public void theEllipsoidalPolarAspectSeesTheEquatorWhereTheSphericalOneCannot() {
        // rf=200 is an absurd flattening, chosen because it keeps the answer a printable size.
        assertForward("+proj=gnom +a=1 +rf=200 +lat_0=90 +lon_0=0", 0, 0,
                0.000000000000, -127.483508426376, 1e-11);
        // The same figure, an ordinary point, to show the flattening is not doing something silly.
        assertForward("+proj=gnom +a=1 +rf=200 +lat_0=90 +lon_0=0", 10, 80,
                0.030767952706, -0.174493730807, 1e-11);
        // The sphere refuses the same point.
        assertRefused(NORTH_POLAR, 0, 0);
    }

    /**
     * The ellipsoidal refusal is a different test from the spherical one: it is
     * {@code !(M12 > 0)}, on the geodesic scale, not a cosine.
     * <p>
     * Reference: {@code echo "0 -10" | proj -f "%.12f" +proj=gnom +ellps=WGS84 +lat_0=90 +lon_0=0}
     * (PROJ 9.8.1) prints {@code *  *}.
     */
    @Test
    public void theEllipsoidalKernelRefusesAPointWhereTheGeodesicScaleIsNotPositive() {
        assertRefused("+proj=gnom +ellps=WGS84 +lat_0=90 +lon_0=0", 0, -10);
    }

    /**
     * A near-WGS84 north polar view of the equator, which is finite but enormous, and is the
     * closest the ellipsoidal kernel gets to its own singularity.
     * <p>
     * <b>This value is what our code does today, not a reference value.</b> PROJ 9.8.1
     * ({@code echo "0 0" | proj -f "%.12f" +proj=gnom +ellps=WGS84 +lat_0=90 +lon_0=0}) prints
     * {@code 0.000000148436  -1212074801.191077470779} and we produce
     * {@code -1212074801.191084}: agreement to 5.6e-15 relative, which is the printout's own
     * resolution rather than a demonstrated agreement, so the digits below are pinned only against
     * a change in our own arithmetic.
     */
    @Test
    public void theEllipsoidalKernelNearItsSingularityIsPinnedToOurCurrentAnswer() {
        ProjCoordinate xy = forward("+proj=gnom +ellps=WGS84 +lat_0=90 +lon_0=0", 0, 0);
        assertEquals("easting at the ellipsoidal polar singularity",
                0.000000148436, xy.x, 1e-9);
        assertEquals("northing at the ellipsoidal polar singularity",
                -1212074801.191084, xy.y, 1e-3);
    }

    /**
     * The ellipsoidal inverse's Newton iteration takes two different forms either side of
     * {@code rho == 1}: it solves {@code rho(s) = rho} below the line and {@code 1/rho(s) = 1/rho}
     * above it, because only one of the two is well conditioned on each side. {@code rho} is in
     * units of the equatorial radius, so the line sits at {@code |xy| = a = 6378137 m} and a test
     * that stays inside a few hundred kilometres never sees the second form at all.
     * <p>
     * Reference: {@code echo "<x> 0" | proj -I -f "%.12f" +proj=gnom +ellps=WGS84 +lat_0=40
     * +lon_0=0} (PROJ 9.8.1).
     */
    @Test
    public void theEllipsoidalInverseCoversBothSidesOfRhoEqualsOne() {
        String def = "+proj=gnom +ellps=WGS84 +lat_0=40 +lon_0=0";
        // rho < 1: the "little" branch.
        assertInverse(def, 100000, 0, 1.170881023872, 39.994085321891, DEGREE_TOL);
        assertInverse(def, 6300000, 0, 52.135801263482, 27.189662850080, DEGREE_TOL);
        // rho > 1: the reciprocal branch.
        assertInverse(def, 6400000, 0, 52.571318504423, 26.959281886211, DEGREE_TOL);
        assertInverse(def, 10000000, 0, 63.874800055764, 20.180235084414, DEGREE_TOL);
        assertInverse(def, 50000000, 0, 84.296199160375, 4.590411086569, DEGREE_TOL);
        // rho == 0: the iteration starts at the answer.
        assertInverse(def, 0, 0, 0.000000000000, 40.000000000000, DEGREE_TOL);
        // And the plain round trip of an ordinary point, exact to twelve decimals in PROJ.
        assertInverse(def, 794033.992835516576, 606135.247744990978,
                10.000000000000, 45.000000000000, 1e-9);
    }

    // ------------------------------------------------------------------------------------ plumbing

    private static Projection projection(String definition) {
        return FACTORY.createFromParameters("t", definition).getProjection();
    }

    private static ProjCoordinate forward(String definition, double lonDeg, double latDeg) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).project(new ProjCoordinate(lonDeg, latDeg), out);
        return out;
    }

    private static void assertForward(String definition, double lonDeg, double latDeg,
            double expectedX, double expectedY, double tol) {
        ProjCoordinate xy = forward(definition, lonDeg, latDeg);
        String where = definition + " at (" + lonDeg + ", " + latDeg + ")";
        assertEquals(where + " easting", expectedX, xy.x, tol);
        assertEquals(where + " northing", expectedY, xy.y, tol);
    }

    /** Metre-scale outputs, where PROJ's twelve printed decimals only pin a relative agreement. */
    private static void assertForwardRelative(String definition, double lonDeg, double latDeg,
            double expectedX, double expectedY) {
        ProjCoordinate xy = forward(definition, lonDeg, latDeg);
        String where = definition + " at (" + lonDeg + ", " + latDeg + ")";
        assertEquals(where + " easting", expectedX, xy.x,
                Math.max(1e-9, Math.abs(expectedX) * METRE_REL_TOL));
        assertEquals(where + " northing", expectedY, xy.y,
                Math.max(1e-9, Math.abs(expectedY) * METRE_REL_TOL));
    }

    private static void assertInverse(String definition, double x, double y,
            double expectedLon, double expectedLat, double tol) {
        ProjCoordinate lp = new ProjCoordinate();
        projection(definition).inverseProject(new ProjCoordinate(x, y), lp);
        String where = definition + " inverse at (" + x + ", " + y + ")";
        assertEquals(where + " longitude", expectedLon, lp.x, tol);
        assertEquals(where + " latitude", expectedLat, lp.y, tol);
    }

    private static void assertRefused(String definition, double lonDeg, double latDeg) {
        try {
            ProjCoordinate xy = forward(definition, lonDeg, latDeg);
            fail(definition + " at (" + lonDeg + ", " + latDeg + ") is at or beyond the horizon "
                    + "and must be refused, but it returned (" + xy.x + ", " + xy.y + "); PROJ "
                    + "9.8.1 prints * * for it");
        } catch (ProjectionException e) {
            assertEquals(definition + " at (" + lonDeg + ", " + latDeg + ") must be reported as "
                    + "out of domain, not as some other failure",
                    ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        }
    }
}
