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

import java.lang.reflect.Field;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Pins what {@link SwissObliqueMercatorProjection}'s five {@link ProjectionMath#asinChecked} calls
 * do, at the one longitude where it matters: {@code 89.69824704017273} degrees, where the double
 * projection turns.
 *
 * <h2>Why this longitude and no other</h2>
 *
 * <p>{@code somerc.cpp:33} computes {@code lampp = aasin(cp * sin(lamp) / cos(phipp))}. The exact
 * value of that quotient is at most 1 and reaches 1 on the turning locus {@code |lam| = 90 / c},
 * which on WGS84 at {@code lat_0=0} is {@code 89.698247040172731} degrees. Near it, rounding puts
 * the quotient one ulp <em>over</em> 1, and {@link Math#asin} of {@code 1 + 1 ulp} is {@code NaN}.
 * This class used to call {@link Math#asin} there, so it produced a {@code NaN} easting; upstream
 * has always called {@code aasin}, which clamps.
 *
 * <p>The band of longitudes affected is <b>5.579797274890552e-6 degrees</b> wide - measured, at
 * latitude 80, by walking one {@code double} at a time in both directions from the turning point:
 * every longitude strictly between {@code 89.6982442502741} and {@code 89.69824983007138} used to
 * fail, which is <b>392,643,326</b> consecutive {@code double}s. That is why a grid test cannot
 * find this and why every longitude below is written out to seventeen digits: at a tenth of a
 * degree, or a thousandth, the sweep passes between the teeth.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every easting and northing below is from the {@code proj} binary of <b>PROJ 9.8.1</b>
 * ({@code Rel. 9.8.1, April 10th, 2026}), printed at {@code -f "%.17g"}, and each test records the
 * command. Nine of the twelve eastings and ten of the twelve northings are asserted <b>bit for
 * bit</b>; the rest carry the size of the disagreement in the assertion message, along with PROJ's
 * number, so that a future change which moves one is read as a change and not as noise.
 *
 * <h2>The two latitudes where we refuse and PROJ answers</h2>
 *
 * <p>At latitudes -88 and 88 the Java quotient overshoots 1 by {@code 6.4e-14} and
 * {@code 5.8e-14}, which is past {@link ProjectionMath#ONE_TOL}, so {@code asinChecked} raises
 * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}. PROJ answers at both, its own {@code libm} never
 * having let the quotient reach 1. <b>That is a deliberate divergence from the oracle and it is
 * asserted here as one</b> - see {@link #theTwoLatitudesPastOneTolStillRefuseAndProjDoesNot()}. It is not a
 * regression: those two points raised before this change too, from the {@code NaN} the bare
 * {@link Math#asin} produced. What changed is the message and the {@link ErrorCause}.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>Nothing else in the repository visits this longitude. The nearest thing is the 0.1-degree
 * grid sweep in {@link ProjectionGridTest}, whose step is eighteen thousand times the width of the
 * band. Deleting this file returns {@code somerc} to the state it was in before: a 392-million-
 * {@code double} interval of longitude on which the forward answered {@code NaN}, with no test
 * anywhere that could see it.
 */
public class SwissObliqueMercatorClampTest {

    /** WGS84 at {@code lat_0=0}, the definition every figure in this class was measured with. */
    private static final String SOMERC = "+proj=somerc +lat_0=0 +lon_0=0 +ellps=WGS84";

    /**
     * {@code (pi/2) / c} in degrees, {@code c = 1.0033640898209764} - the turning locus, and the
     * exact centre of the band. Written as the {@code double} it is, not as an expression, because
     * the whole point of this class is that the neighbouring {@code double}s behave differently.
     */
    private static final double TURN_LON = 89.69824704017273;

    /** The last good longitude below the band; the band is everything strictly above it. */
    private static final double BAND_LOW = 89.6982442502741;

    /** The first good longitude above the band. */
    private static final double BAND_HIGH = 89.69824983007138;

    private static final CRSFactory FACTORY = new CRSFactory();

    // ------------------------------------------------------------- the twelve latitudes that answer

    /**
     * The nine latitudes at which the clamp fires and our easting is PROJ 9.8.1's to the bit. Every
     * one of these returned a {@code NaN} easting before the change, and through the public forward
     * that was a {@link ProjectionException} with {@link ErrorCause#NUMERICAL_FAILURE}, because the
     * funnel checks the kernel's result before applying the affine.
     * <p>
     * The nine eastings are the same {@code double}, {@code 9985163.185561286}, because the clamp
     * returns exactly {@code HALFPI} at all nine and the easting is {@code kR * HALFPI}. The
     * northings are all different, which is what shows the rows are not duplicates of one call.
     * <p>
     * Reference: {@code echo "89.69824704017273 <lat>" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} (PROJ 9.8.1).
     */
    @Test
    public void theNineLatitudesWhereTheClampGivesProjsEastingToTheBit() {
        assertBitIdentical(-86, 9985163.1855612863, -21353878.625651304);
        assertBitIdentical(-80, 9985163.1855612863, -15496570.739723729);
        assertBitIdentical(-43, 9985163.1855612863, -5282821.8241920937);
        assertBitIdentical(38, 9985163.1855612863, 4553116.2327020895);
        assertBitIdentical(56, 9985163.1855612863, 7522963.2412651209);
        assertBitIdentical(72, 9985163.1855612863, 11712494.454461088);
        assertBitIdentical(80, 9985163.1855612863, 15496570.739723718);
        assertBitIdentical(84, 9985163.1855612863, 18764656.231380597);
        // The ninth easting is bit-identical but its northing is 1.86e-9 m out, so it cannot go
        // through assertBitIdentical. Both halves are still pinned, the northing at 1e-8.
        ProjCoordinate xy = forward(TURN_LON, -68);
        assertEquals("lat -68: the easting must be PROJ's exactly",
                9985163.1855612863, xy.x, 0.0);
        assertEquals("lat -68: the northing is 1.862645149e-09 m from PROJ's "
                        + "-10407332.515149968, which is the last bit of a 10 000 km ordinate",
                -10407332.515149968, xy.y, 1e-8);
    }

    /**
     * The three latitudes where the clamp fires here and does not fire in PROJ, so the two answers
     * differ by a fraction of a metre. <b>PROJ's number is in every assertion message.</b>
     *
     * <p>The cause is one bit. The quotient at {@code somerc.cpp:33} is 1 within rounding at all
     * twelve of these latitudes; at nine of them both {@code libm}s put it at or over 1 and both
     * clamp, and at these three Java's puts it over 1 while the platform's puts it under, so we
     * return {@code kR * HALFPI} and PROJ returns {@code kR * asin(1 - eps)}. There is nothing to
     * choose between them numerically - the exact answer is the clamped one - but they are not the
     * same {@code double}, and this test says by how much rather than relaxing a tolerance until
     * the difference disappears.
     *
     * <p>Reference: {@code echo "89.69824704017273 <lat>" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} (PROJ 9.8.1).
     */
    @Test
    public void theThreeLatitudesWhereJavasLibmClampsAndProjsDoesNotDifferBySubMetreAmounts() {
        assertKnownEastingDivergence(43, 9985163.0908382945, 0.09472299181);
        assertKnownEastingDivergence(75, 9985162.9176442083, 0.2679170780);
        assertKnownEastingDivergence(82, 9985162.8311403077, 0.3544209786);

        // Northings, which are a separate question: two of these three agree with PROJ to the bit
        // and the third does not.
        assertEquals("lat 43: the northing must be PROJ's exactly",
                5282821.8241920918, forward(TURN_LON, 43).y, 0.0);
        assertEquals("lat 75: the northing must be PROJ's exactly",
                12890914.137293594, forward(TURN_LON, 75).y, 0.0);
        assertEquals("lat 82: the northing is 4.097819328e-08 m from PROJ's "
                        + "16925421.912056386, the only northing in the twelve more than one bit "
                        + "out", 16925421.912056386, forward(TURN_LON, 82).y, 1e-7);
    }

    /**
     * All twelve at once, as a property rather than a table: nothing on the turning locus may be a
     * {@code NaN}, and every easting must be within a metre of PROJ's. Before the change all twelve
     * of these calls raised.
     */
    @Test
    public void noneOfTheTwelveProducesANaNAnyMore() {
        int[] latitudes = {-86, -80, -68, -43, 38, 43, 56, 72, 75, 80, 82, 84};
        for (int lat : latitudes) {
            ProjCoordinate xy = forward(TURN_LON, lat);
            assertTrue("lat " + lat + ": the easting must be finite, and it was NaN before the "
                    + "clamp; got " + xy.x, !Double.isNaN(xy.x) && !Double.isInfinite(xy.x));
            assertTrue("lat " + lat + ": the northing must be finite; got " + xy.y,
                    !Double.isNaN(xy.y) && !Double.isInfinite(xy.y));
            assertEquals("lat " + lat + ": the easting is kR * HALFPI at every one of the twelve, "
                    + "because that is what the clamp returns", 9985163.185561286, xy.x, 0.4);
        }
    }

    // ------------------------------------------------------------- the two latitudes that refuse

    /**
     * <b>A known divergence from PROJ 9.8.1, asserted as one.</b> At latitudes -88 and 88 the
     * quotient at {@code somerc.cpp:33} comes out as {@code 1.000000000000064} and
     * {@code 1.0000000000000575} in Java. Both are past {@link ProjectionMath#ONE_TOL}
     * ({@code 1.00000000000001}), so {@code asinChecked} refuses, exactly as upstream's
     * {@code aasin} would refuse if it were handed the same number. PROJ's own quotient never gets
     * there, so PROJ answers: {@code 9985161.7458964642 -25776731.363608167} at latitude -88 and
     * {@code 9985161.5752704404 25776731.363608185} at 88.
     *
     * <p><b>The divergence is deliberate and is kept.</b> It runs against this project's usual
     * direction - normally we answer wherever PROJ answers - and it is accepted here because the
     * only alternative is to widen the tolerance band past upstream's {@code ONE_TOL}, which would
     * make every other site in the port answer outside the domain upstream refuses. Nothing
     * regressed by keeping it: the caller already got an exception at these two points before the
     * change. Do not "fix" it by loosening the band.
     *
     * <p>These two points also raised before the change - the {@code NaN} the bare
     * {@link Math#asin} produced was rejected by the funnel with
     * {@link ErrorCause#NUMERICAL_FAILURE}. So the caller-visible change is the cause and the
     * message, both of which now name the step that failed. This test pins the cause, because
     * {@code NUMERICAL_FAILURE} and {@code COORDINATE_OUT_OF_DOMAIN} mean different things to a
     * caller: the first says our arithmetic went wrong, the second says the point is not on the
     * map.
     *
     * <p>Reference: {@code echo "89.69824704017273 -88" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} prints {@code 9985161.7458964642 -25776731.363608167} (PROJ 9.8.1).
     */
    @Test
    public void theTwoLatitudesPastOneTolStillRefuseAndProjDoesNot() {
        assertRefusesWithOvershoot(-88, "1.000000000000064");
        assertRefusesWithOvershoot(88, "1.0000000000000575");

        // Both overshoots really are past ONE_TOL, which is the reason the clamp does not apply.
        // Asserted from the constant rather than from the literal above, so that a change to
        // ONE_TOL cannot leave this test passing while the behaviour it describes has moved.
        assertTrue("the -88 quotient must be past ONE_TOL, or asinChecked would clamp it",
                1.000000000000064 > ProjectionMath.ONE_TOL);
        assertTrue("the 88 quotient must be past ONE_TOL, or asinChecked would clamp it",
                1.0000000000000575 > ProjectionMath.ONE_TOL);
        // And the tolerance band they are outside is small, i.e. the two are not just barely
        // outside a band that is itself enormous. Asserted against the literal rather than against
        // 1 + 1e-14, which is a different double: ONE_TOL - 1.0 measures 9.992007221626409e-15.
        assertEquals("ONE_TOL must be upstream's 1.00000000000001, the literal at "
                + "9.8.1:src/aasincos.cpp:8", 1.00000000000001, ProjectionMath.ONE_TOL, 0.0);
    }

    // -------------------------------------------------------------------------------- the band

    /**
     * Longitudes strictly inside the old {@code NaN} band now answer, and they answer with PROJ's
     * number to the bit. Three probes: the first bad {@code double}, one a quarter of the way in,
     * and the last bad one. All three are within {@code 5.6e-6} of a degree of each other, which is
     * why they are written to seventeen digits.
     *
     * <p>Reference: {@code proj -f "%.17g" +proj=somerc +lat_0=0 +lon_0=0 +ellps=WGS84} on each of
     * the three prints {@code 9985163.1855612863 15496570.739723718} (PROJ 9.8.1) - the same pair,
     * because the clamp returns {@code HALFPI} across the whole band on both sides.
     */
    @Test
    public void aLongitudeInsideTheOldNaNBandNowAnswersAndAgreesWithProj() {
        double[] insideTheBand = {
            89.69824425027412,  // BAND_LOW + 1 ulp, the first longitude that used to fail
            89.69824564522341,  // a quarter of the way across
            89.69824983007136,  // BAND_HIGH - 1 ulp, the last one
        };
        for (double lon : insideTheBand) {
            ProjCoordinate xy = forward(lon, 80);
            assertEquals("lon " + lon + " is inside the old NaN band and must now give PROJ's "
                    + "easting exactly", 9985163.1855612863, xy.x, 0.0);
            assertEquals("lon " + lon + ": and PROJ's northing exactly",
                    15496570.739723718, xy.y, 0.0);
        }

        // The band's own arithmetic, so the three probes above are known to be inside it and the
        // figures in the class comment are checked rather than asserted in prose alone.
        long low = Double.doubleToRawLongBits(BAND_LOW);
        long high = Double.doubleToRawLongBits(BAND_HIGH);
        assertEquals("the band is 392,643,326 doubles wide, strictly between the endpoints",
                392643326L, high - low - 1);
        assertEquals("and 5.579797274890552e-6 degrees wide",
                5.579797274890552e-6, BAND_HIGH - BAND_LOW, 0.0);
        assertEquals("the turning point is the exact centre of the band",
                TURN_LON, Double.longBitsToDouble(low + (high - low) / 2), 0.0);
        for (double lon : insideTheBand) {
            long bits = Double.doubleToRawLongBits(lon);
            assertTrue("the probe " + lon + " must be strictly inside the band",
                    bits > low && bits < high);
        }
    }

    // ---------------------------------------------------------------------------- the round trip

    /**
     * The forward and the inverse at the turning point, both bit-identical to PROJ 9.8.1, and the
     * round trip through them.
     *
     * <p>Reference, forward: {@code echo "89.69824704017273 80" | proj -f "%.17g" +proj=somerc
     * +lat_0=0 +lon_0=0 +ellps=WGS84} prints {@code 9985163.1855612863 15496570.739723718}.
     * Inverse: {@code echo "9985163.185561286 15496570.739723718" | proj -I -f "%.17g" …} prints
     * {@code 89.698247040172731 80.000000000000014} (PROJ 9.8.1).
     *
     * <p>The latitude comes back as {@code 80.00000000000001} rather than 80, in PROJ as much as
     * here, and the two agree on which {@code double} that is - so this is asserted at zero
     * tolerance rather than at {@code 1e-9}, which would hide a change in the last bit.
     */
    @Test
    public void theRoundTripAtTheTurningPointIsBitIdenticalToProjBothWays() {
        ProjCoordinate xy = forward(TURN_LON, 80);
        assertEquals("forward easting must be PROJ's exactly",
                9985163.1855612863, xy.x, 0.0);
        assertEquals("forward northing must be PROJ's exactly",
                15496570.739723718, xy.y, 0.0);

        ProjCoordinate back = new ProjCoordinate();
        projection().inverseProject(xy, back);
        assertEquals("inverse longitude must be PROJ -I's exactly",
                89.698247040172731, back.x, 0.0);
        assertEquals("inverse latitude must be PROJ -I's exactly, which is 80 plus one bit",
                80.000000000000014, back.y, 0.0);
        assertEquals("and the longitude round trips onto the double it started from",
                TURN_LON, back.x, 0.0);
    }

    /**
     * The inverse had the same band as the forward, on the easting axis. Sweeping 801 consecutive
     * {@code double}s of easting about the turning point's easting, <b>all 801 raised before the
     * change and none do after</b>.
     *
     * <p><b>Which axis is swept is part of the claim.</b> Holding the easting and sweeping the
     * <em>northing</em> over the same 801 {@code double}s gave 231 refusals before and 0 after -
     * a different number for a different sweep, which is why both are here and both are named. A
     * report quoting one of them without its axis makes the other look like a refutation.
     */
    @Test
    public void bothInverseSweepsAtTheTurningPointNowAnswerAtEveryDouble() {
        assertEquals("sweeping the EASTING must refuse nowhere; 801 of 801 refused before the "
                + "clamp", 0, refusalsSweeping(true));
        assertEquals("sweeping the NORTHING must refuse nowhere; 231 of 801 refused before the "
                + "clamp", 0, refusalsSweeping(false));
    }

    // --------------------------------------------------------- the site in initialize(), :84

    /**
     * The fifth site, {@code phip0 = aasin(sp / c)} in {@link SwissObliqueMercatorProjection
     * #initialize()} ({@code somerc.cpp:84}). Its argument is bounded by construction -
     * {@code sp} is {@code sin(lat_0)} and {@code c} is {@code sqrt(1 + es cos^4(lat_0) / (1 - es))},
     * which is at least 1 - so the clamp cannot fire and routing it through the wrapper cannot
     * change any answer. This asserts that, rather than leaving it as a claim in a comment.
     *
     * <p>Two things are checked at five values of {@code lat_0}: that {@code |sp / c| <= 1}, and
     * that {@code asinChecked} of it is bit-identical to {@link Math#asin} of it. The five
     * projections are read by reflection because the fields are private, which is the right
     * visibility for them - the test reaches in rather than the class opening up.
     *
     * <p>{@code lat_0 = +/-90} is included because that is the one place the quotient is exactly
     * {@code 1.0}, where {@code asinChecked} takes its clamp branch rather than calling
     * {@link Math#asin} at all. It returns {@code HALFPI}, and {@code Math.asin(1.0)} returns
     * {@code HALFPI} too, so the branch is taken and the answer does not move.
     */
    @Test
    public void theInitializeSiteCannotChangeAnyAnswer() throws Exception {
        double[] centres = {-90, -46.9524055970347, 0, 46.9524055970347, 90};
        for (double lat0 : centres) {
            SwissObliqueMercatorProjection p = (SwissObliqueMercatorProjection) FACTORY
                    .createFromParameters("t",
                            "+proj=somerc +lat_0=" + lat0 + " +lon_0=0 +ellps=WGS84")
                    .getProjection();
            double sinp0 = field(p, "sinp0");
            double c = field(p, "c");
            assertTrue("lat_0 " + lat0 + ": c must be at least 1, which is what bounds the "
                    + "quotient; got " + c, c >= 1.0);
            assertTrue("lat_0 " + lat0 + ": |sin(lat_0) / c| must not exceed 1, so the clamp "
                    + "cannot fire; got " + sinp0, Math.abs(sinp0) <= 1.0);
            assertEquals("lat_0 " + lat0 + ": asinChecked must return the same double as "
                            + "Math.asin on the initialize() argument",
                    Double.doubleToRawLongBits(Math.asin(sinp0)),
                    Double.doubleToRawLongBits(ProjectionMath.asinChecked(sinp0)));
        }
        // The clamp branch really is the one taken at the pole, and it agrees with Math.asin.
        assertEquals("at lat_0 = 90 the quotient is exactly 1.0", 1.0,
                field((SwissObliqueMercatorProjection) FACTORY.createFromParameters("t",
                        "+proj=somerc +lat_0=90 +lon_0=0 +ellps=WGS84").getProjection(), "sinp0"),
                0.0);
        assertEquals("and asinChecked(1.0) is HALFPI, as Math.asin(1.0) is",
                Double.doubleToRawLongBits(ProjectionMath.HALFPI),
                Double.doubleToRawLongBits(ProjectionMath.asinChecked(1.0)));
    }

    // ----------------------------------------------------------- what the clamp does NOT change

    /**
     * The 1.15 m disagreement with PROJ on {@code +ellps=bessel} is a different thing, and the
     * clamp does not move it. It is here so that the two are not confused: someone reading the
     * class comment will meet both numbers, and only one of them is about {@code asinChecked}.
     *
     * <p>At {@code (-8.1, -43.1)} under {@code +proj=somerc +lat_0=46.9524055970347 +lon_0=0
     * +ellps=bessel}, {@code proj} gives {@code -10019820.590799341 -18875770.257259779} and we
     * give {@code -1.0019819438357947E7 -1.887577025725964E7}: <b>1.152441393584013 m</b> apart in
     * easting, {@code 1.4e-7} m in northing. The argument of the {@code asin} there is comfortably
     * inside the domain, so no clamp fires on either side; the gap is Java's transcendental
     * functions against the platform {@code libm}'s, magnified because {@code asin}'s derivative
     * grows without bound near 1. The easting was measured as the same {@code double} before and
     * after the change.
     *
     * <p>Reference: {@code echo "-8.1 -43.1" | proj -f "%.17g" +proj=somerc
     * +lat_0=46.9524055970347 +lon_0=0 +ellps=bessel} (PROJ 9.8.1).
     */
    @Test
    public void theBesselDisagreementIsUnchangedByTheClampAndIsNotAboutIt() {
        ProjCoordinate xy = new ProjCoordinate();
        FACTORY.createFromParameters("t",
                        "+proj=somerc +lat_0=46.9524055970347 +lon_0=0 +ellps=bessel")
                .getProjection().project(new ProjCoordinate(-8.1, -43.1), xy);
        assertEquals("the bessel easting must still be the double it was before the clamp",
                -1.0019819438357947E7, xy.x, 0.0);
        assertEquals("which is 1.152441393584013 m from PROJ's -10019820.590799341, and that gap "
                        + "is a libm difference, not a clamp",
                1.152441393584013, xy.x - -10019820.590799341, 1e-15);
        assertEquals("the northing agrees with PROJ to 1.4e-7 m",
                -18875770.257259779, xy.y, 2e-7);
    }

    /**
     * The fold, which is also not what the clamp was for and is deliberately unpatched: past
     * {@code 90 / c} degrees the recovered longitude is reflected about the turning point, because
     * {@code somerc.cpp} recovers {@code lampp} from its sine alone and contains no {@code atan2}
     * to get the quadrant from. PROJ folds identically, so under this project's parity doctrine so
     * do we.
     *
     * <p>This test is here because the clamp makes the fold <em>reachable</em> at the turning point
     * itself for the first time - before, that neighbourhood was a {@code NaN} - so a future
     * reading of these numbers should not mistake the fold for something the clamp introduced.
     *
     * <p>Reference: forward then {@code proj -I -f "%.17g" +proj=somerc +lat_0=0 +lon_0=0
     * +ellps=WGS84} (PROJ 9.8.1).
     */
    @Test
    public void theFoldPastTheTurningPointIsUnchangedAndMatchesProj() {
        assertFoldsTo(100, 79.396494080345477);
        assertFoldsTo(120, 59.396494080345505);
        assertFoldsTo(179, 0.39649408034546751);
        // Just inside the turn, where nothing is folded.
        assertFoldsTo(89.5, 89.499999999999702);
    }

    // ------------------------------------------------------------------------------------ plumbing

    private static Projection projection() {
        return FACTORY.createFromParameters("t", SOMERC).getProjection();
    }

    private static ProjCoordinate forward(double lonDeg, double latDeg) {
        ProjCoordinate out = new ProjCoordinate();
        projection().project(new ProjCoordinate(lonDeg, latDeg), out);
        return out;
    }

    private static double field(SwissObliqueMercatorProjection p, String name) throws Exception {
        Field f = SwissObliqueMercatorProjection.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(p);
    }

    private static void assertBitIdentical(int latDeg, double projX, double projY) {
        ProjCoordinate xy = forward(TURN_LON, latDeg);
        assertEquals("lat " + latDeg + ": the easting must be PROJ 9.8.1's exactly; this used to "
                + "be a NaN", projX, xy.x, 0.0);
        assertEquals("lat " + latDeg + ": the northing must be PROJ 9.8.1's exactly",
                projY, xy.y, 0.0);
    }

    /**
     * Asserts a latitude at which our easting is not PROJ's, naming PROJ's value and the measured
     * gap. The gap is pinned to 1e-9 m of itself, which is tight enough that a change in the clamp
     * moves it and loose enough not to depend on the last bit of a 10 000 km ordinate.
     */
    private static void assertKnownEastingDivergence(int latDeg, double projX, double expectedGap) {
        ProjCoordinate xy = forward(TURN_LON, latDeg);
        assertEquals("lat " + latDeg + ": KNOWN DIVERGENCE. PROJ 9.8.1 gives " + projX
                        + " and we give " + xy.x + ", because Java's libm puts the quotient at "
                        + "somerc.cpp:33 just over 1 and clamps where PROJ's puts it just under. "
                        + "The gap must stay " + expectedGap + " m",
                expectedGap, xy.x - projX, 1e-9);
    }

    private static void assertRefusesWithOvershoot(int latDeg, String quotient) {
        try {
            ProjCoordinate xy = forward(TURN_LON, latDeg);
            fail("lat " + latDeg + " must refuse: the quotient at somerc.cpp:33 is " + quotient
                    + ", past ONE_TOL. Got " + xy.x + ", " + xy.y);
        } catch (ProjectionException expected) {
            assertEquals("lat " + latDeg + ": the cause must say the point is off the map, not "
                            + "that our arithmetic failed",
                    ErrorCause.COORDINATE_OUT_OF_DOMAIN, expected.cause());
            assertTrue("lat " + latDeg + ": the message must name the overshoot " + quotient
                            + ", so a caller can see how far past 1 it went; got: "
                            + expected.getMessage(),
                    expected.getMessage().contains(quotient));
        }
    }

    /**
     * Counts refusals over 801 consecutive {@code double}s of one ordinate of the turning point's
     * projected coordinate, holding the other fixed.
     *
     * @param varyEasting sweep the easting if true, the northing if false
     */
    private static int refusalsSweeping(boolean varyEasting) {
        final double x = 9985163.185561286, y = 1.5496570739723718E7;
        Projection p = projection();
        long base = Double.doubleToRawLongBits(varyEasting ? x : y);
        int refusals = 0;
        for (int i = -400; i <= 400; i++) {
            double v = Double.longBitsToDouble(base + i);
            try {
                p.inverseProject(new ProjCoordinate(varyEasting ? v : x, varyEasting ? y : v),
                        new ProjCoordinate());
            } catch (ProjectionException e) {
                refusals++;
            }
        }
        return refusals;
    }

    private static void assertFoldsTo(double lonDeg, double expectedFoldedLon) {
        ProjCoordinate xy = forward(lonDeg, 45);
        ProjCoordinate back = new ProjCoordinate();
        projection().inverseProject(xy, back);
        assertEquals("longitude " + lonDeg + " must fold to PROJ's " + expectedFoldedLon,
                expectedFoldedLon, back.x, 1e-12);
        assertEquals("and the latitude is not disturbed by the fold at lat_0 = 0, because the "
                + "northing does not depend on the quadrant of lamp", 45.0, back.y, 1e-12);
    }
}
