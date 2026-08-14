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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
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
 * <h2>The turning locus is ill conditioned, and the oracle is not single-valued on it</h2>
 *
 * <p><b>This is the thing to understand before changing any number in this file.</b> On the turning
 * locus the quotient at {@code somerc.cpp:33} is 1 to within a few ulp, and {@code asin}'s
 * derivative is unbounded there, so <b>one ulp of the quotient is worth about 0.1 m of easting</b>.
 * Every transcendental feeding that quotient therefore decides a tenth of a metre with its last
 * bit. Three implementations of the same arithmetic give three different answers:
 *
 * <ul>
 *   <li><b>Apple libm, arm64</b> - what the {@code proj} binary on the development machine links.
 *   <li><b>Apple libm, x86-64</b> - the same {@code libSystem}, other slice.
 *   <li><b>fdlibm</b> - {@link StrictMath} and {@link FastStrictTrig}, which is what this class now
 *       uses.
 * </ul>
 *
 * <p>The first two were compared directly: {@code somerc.cpp}'s forward, transcribed to C with
 * PROJ's own {@code DEG_TO_RAD}, compiled from one source with {@code clang -O2 -arch arm64} and
 * {@code -arch x86_64}, fed identical input bits. The arm64 build reproduces the {@code proj}
 * binary's output at all fourteen latitudes below, bit for bit, which is what makes it a valid
 * stand-in. <b>The x86-64 build disagrees with it at two of the fourteen:</b>
 *
 * <table border="1">
 * <caption>PROJ 9.8.1's own forward easting, one source, two architectures</caption>
 * <tr><th>lat</th><th>arm64</th><th>x86-64</th><th>gap</th></tr>
 * <tr><td>43</td><td>{@code 9985163.0908382945} (asin)</td>
 *     <td>{@code 9985163.1855612863} (clamp)</td><td>0.0947 m</td></tr>
 * <tr><td>56</td><td>{@code 9985163.1855612863} (clamp)</td>
 *     <td>{@code 9985163.0516027473} (asin)</td><td>0.1340 m</td></tr>
 * </table>
 *
 * <p>So "PROJ 9.8.1's answer" is not one number here; it is a number per CPU. <b>Bit parity with
 * the oracle is therefore not an available goal on this locus</b>, and any test that demanded it
 * would be pinning the architecture of whoever last ran it. What <em>is</em> available, and what
 * this file now pins, is that <b>proj4j's answer does not move with the CPU</b>: every
 * transcendental on this projection's path goes through {@link FastStrictTrig} or
 * {@link StrictMath}, both of which are specified to the bit and identical on every JVM.
 *
 * <h2>What the fdlibm routing changed, and why</h2>
 *
 * <p>This class used to call {@link Math#sin}, {@link Math#cos}, {@link Math#tan},
 * {@link Math#log} and {@link Math#exp}. All five are {@code @IntrinsicCandidate}: HotSpot
 * substitutes a hand-written per-architecture implementation, so they are exactly the functions
 * that make an answer depend on the CPU. The symptom was a red CI job - the twelve eastings pinned
 * here were measured on aarch64 and three of them are different doubles on x86-64, one of them by
 * 0.51 m. The cause was not the clamp; the clamp is upstream's and is correct. The cause was
 * feeding the clamp a platform-variant quotient.
 *
 * <p>Routing the class through fdlibm moved it <em>closer</em> to the arm64 oracle, not further:
 * against the {@code proj} binary's fourteen forwards, bit-identical eastings went from <b>9 of
 * 14</b> (with two hard refusals and three sub-metre gaps) to <b>12 of 14</b>, with no refusals.
 * Northings went from 11 of 14 to 11 of 14, with latitude 82's improving from 4.1e-8 m out to
 * exact.
 *
 * <h2>The two latitudes that used to refuse do not any more</h2>
 *
 * <p>At latitudes -88 and 88 the {@link Math} quotient overshot 1 by {@code 6.4e-14} and
 * {@code 5.8e-14}, past {@link ProjectionMath#ONE_TOL}, so {@code asinChecked} raised
 * {@code COORDINATE_OUT_OF_DOMAIN} while PROJ answered. That was recorded here as a deliberate
 * divergence to be kept. <b>It was not a divergence. It was the arm64 {@code Math.sin}/{@code cos}
 * chain overshooting</b>, and the fdlibm quotient at those two latitudes is {@code 1 - 2.6e-14} and
 * {@code 1 - 3.2e-14} - comfortably inside the domain. Both now answer, and both answer with
 * exactly the {@code proj} binary's easting. There is no somerc/{@code ONE_TOL} divergence left to
 * decide.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>Nothing else in the repository visits this longitude. The nearest thing is the 0.1-degree
 * grid sweep in {@link ProjectionGridTest}, whose step is eighteen thousand times the width of the
 * band. Deleting this file returns {@code somerc} to the state it was in before: a 392-million-
 * {@code double} interval of longitude on which the forward answered {@code NaN}, with no test
 * anywhere that could see it - and it removes the only guard against the {@code Math} calls coming
 * back, which is {@link #nothingOnThisProjectionsPathCallsAPlatformVariantMathFunction()}.
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

    /**
     * The easting the clamp produces, {@code a * kR * HALFPI}, and the {@code proj} binary's own
     * value wherever it clamps too.
     */
    private static final double CLAMPED_EASTING = 9985163.1855612863;

    private static final CRSFactory FACTORY = new CRSFactory();

    // -------------------------------------------------- the eight latitudes where the clamp fires

    /**
     * The eight latitudes at which the clamp fires and our easting is PROJ 9.8.1's to the bit.
     * Every one of these returned a {@code NaN} easting before the clamp, and through the public
     * forward that was a {@link ProjectionException} with
     * {@link org.locationtech.proj4j.ErrorCause#NUMERICAL_FAILURE}, because the funnel checks the
     * kernel's result before applying the affine.
     * <p>
     * The eight eastings are the same {@code double}, {@link #CLAMPED_EASTING}, because the clamp
     * returns exactly {@code HALFPI} at all eight and the easting is {@code a * kR * HALFPI}. The
     * northings are all different, which is what shows the rows are not duplicates of one call.
     * <p>
     * Reference: {@code echo "89.69824704017273 <lat>" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} (PROJ 9.8.1, arm64).
     */
    @Test
    public void theEightLatitudesWhereTheClampFiresAndWeArePROJsToTheBit() {
        assertBitIdentical(-86, CLAMPED_EASTING, -21353878.625651304);
        assertBitIdentical(-80, CLAMPED_EASTING, -15496570.739723729);
        assertBitIdentical(38, CLAMPED_EASTING, 4553116.2327020895);
        assertBitIdentical(56, CLAMPED_EASTING, 7522963.2412651209);
        assertBitIdentical(72, CLAMPED_EASTING, 11712494.454461088);
        assertBitIdentical(80, CLAMPED_EASTING, 15496570.739723718);
        assertBitIdentical(84, CLAMPED_EASTING, 18764656.231380597);
        // The eighth easting is bit-identical but its northing is 1.86e-9 m out, so it cannot go
        // through assertBitIdentical. Both halves are still pinned, the northing at 1e-8.
        ProjCoordinate xy = forward(TURN_LON, -68);
        assertEquals("lat -68: the easting must be PROJ's exactly", CLAMPED_EASTING, xy.x, 0.0);
        assertEquals("lat -68: the northing is 1.862645149e-09 m from PROJ's "
                        + "-10407332.515149968, which is the last bit of a 10 000 km ordinate",
                -10407332.515149968, xy.y, 1e-8);
    }

    // --------------------------------------- the four latitudes where the clamp does not fire

    /**
     * The four latitudes where the fdlibm quotient stays strictly below 1, so no clamp fires - and
     * we are still the {@code proj} binary's easting to the bit at all four.
     *
     * <p><b>-88 and 88 are the two that used to refuse.</b> Under {@link Math} the quotient there
     * came out as {@code 1.000000000000064} and {@code 1.0000000000000575}, past
     * {@link ProjectionMath#ONE_TOL}, and {@code asinChecked} raised. Under fdlibm it is
     * {@code 1 - 2.6e-14} and {@code 1 - 3.2e-14}. The refusal was an artefact of the platform
     * {@code sin}/{@code cos}, not a property of the projection, and the "deliberate divergence
     * from the oracle" that used to be recorded here has gone with it.
     *
     * <p>Reference: {@code echo "89.69824704017273 <lat>" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} (PROJ 9.8.1, arm64).
     */
    @Test
    public void theFourLatitudesWhereNoClampFiresAndWeAreStillPROJsToTheBit() {
        assertBitIdentical(-88, 9985161.7458964642, -25776731.363608167);
        assertBitIdentical(43, 9985163.0908382945, 5282821.8241920918);
        assertBitIdentical(75, 9985162.9176442083, 12890914.137293594);
        // 88's easting is bit-identical; its northing is 7.45e-9 m out, the last bit of a 25 000 km
        // ordinate.
        ProjCoordinate xy = forward(TURN_LON, 88);
        assertEquals("lat 88: the easting must be PROJ's exactly", 9985161.5752704404, xy.x, 0.0);
        assertEquals("lat 88: the northing is 7.450580597e-09 m from PROJ's 25776731.363608185",
                25776731.363608185, xy.y, 1e-8);
    }

    // ------------------------------------------------- the two latitudes that differ from PROJ

    /**
     * The two latitudes where fdlibm and Apple's arm64 {@code libm} land on different sides of the
     * clamp, or on different {@code double}s below it. <b>PROJ's number is in every assertion
     * message.</b>
     *
     * <p>Read this together with the class comment's arm64/x86-64 table. At latitude 43 the two
     * {@code libm} slices of one {@code libSystem} already disagree with each other by exactly the
     * 0.0947 m carried here, and at 56 by 0.1340 m - so a gap of this size at this longitude is
     * what an implementation change costs, whoever makes it. There is nothing to choose between the
     * answers numerically: the exact value of the quotient is 1, so the exact easting is the
     * clamped one, and every implementation here is a few ulp away from it in one direction or the
     * other. This test says by how much rather than relaxing a tolerance until the difference
     * disappears.
     *
     * <ul>
     *   <li><b>-43</b>: our quotient is {@code 1 - 1 ulp} so we take the {@code asin}; PROJ's is
     *       exactly 1 so it clamps. 0.0947 m.
     *   <li><b>82</b>: both take the {@code asin}, ours from {@code 1 - 14.5 ulp} and PROJ's from
     *       {@code 1 - 7 ulp}. 0.1557 m. The <em>northing</em> at 82 became bit-exact with this
     *       change, having been 4.1e-8 m out.
     * </ul>
     *
     * <p>Reference: {@code echo "89.69824704017273 <lat>" | proj -f "%.17g" +proj=somerc +lat_0=0
     * +lon_0=0 +ellps=WGS84} (PROJ 9.8.1, arm64).
     */
    @Test
    public void theTwoLatitudesWhereFdlibmAndProjsLibmTakeDifferentSidesOfTheClamp() {
        assertKnownEastingDivergence(-43, 9985163.1855612863, -0.09472299180924892);
        assertKnownEastingDivergence(82, 9985162.8311403077, -0.1556779369711876);

        // Northings, which are a separate question: one of these two agrees with PROJ to the bit
        // and the other does not.
        assertEquals("lat -43: the northing is 2.793967724e-09 m from PROJ's -5282821.8241920937",
                -5282821.8241920937, forward(TURN_LON, -43).y, 1e-8);
        assertEquals("lat 82: the northing must be PROJ's exactly, which it was NOT before the "
                        + "fdlibm routing - it was 4.097819328e-08 m out",
                16925421.912056386, forward(TURN_LON, 82).y, 0.0);
    }

    /**
     * All fourteen at once, as a property rather than a table: nothing on the turning locus may be
     * a {@code NaN} or a refusal. Before the clamp all fourteen of these calls raised; before the
     * fdlibm routing two of them still did.
     *
     * <p>The easting bound is <b>0.2 m for twelve of the fourteen and 1.7 m for -88 and 88</b>, and
     * the two bars are not arbitrary. The departure from the clamped easting goes as the
     * <em>square root</em> of how far the quotient falls below 1: it is
     * {@code a * kR * (HALFPI - asin(1 - d))}, which for small {@code d} is
     * {@code a * kR * sqrt(2d)}. So one ulp of shortfall is worth <b>0.0947 m</b> - exactly the gap
     * carried at latitude -43 - while the 117 and 144 ulp at -88 and 88 are worth <b>1.4490 m</b>
     * and <b>1.6075 m</b>. Measured: 1.4397 m and 1.6103 m, so the law holds to a centimetre and
     * these two are ordinary {@code asin}, not a degeneracy.
     *
     * <p><b>For the twelve the bar is 0.55 m, and that is a loosening of the 0.4 m this test used
     * to carry. Saying so plainly, with the number that forced it:</b> the widest departure among
     * the twelve is latitude 82's, at <b>0.5101 m</b>, and its easting is now
     * {@code 9985162.67546237}. That is <em>the exact value the red CI job reported</em>. Under
     * {@link Math}, aarch64 put latitude 82's quotient above 1 so it clamped and sat inside 0.4 m,
     * while x86-64 put it 14.5 ulp below 1 and blew the bar - the 0.4 m was only ever satisfiable
     * on one of the two architectures. Under fdlibm both architectures produce the x86-64 number,
     * so the bar has to admit it. The alternative would be to keep 0.4 m and go on shipping a
     * projection whose answer depends on the CPU.
     */
    @Test
    public void noneOfTheFourteenProducesANaNOrRefuses() {
        for (int lat : ALL_FOURTEEN) {
            ProjCoordinate xy = forward(TURN_LON, lat);
            assertTrue("lat " + lat + ": the easting must be finite, and it was NaN before the "
                    + "clamp; got " + xy.x, !Double.isNaN(xy.x) && !Double.isInfinite(xy.x));
            assertTrue("lat " + lat + ": the northing must be finite; got " + xy.y,
                    !Double.isNaN(xy.y) && !Double.isInfinite(xy.y));
            boolean farFromTheLocus = (lat == -88 || lat == 88);
            assertEquals("lat " + lat + ": the easting must stay near a * kR * HALFPI, because the "
                    + "quotient at somerc.cpp:33 is 1 to within a few hundred ulp here and the "
                    + "easting departs as sqrt of the shortfall",
                    CLAMPED_EASTING, xy.x, farFromTheLocus ? 1.7 : 0.55);
        }

        // The square-root law itself, so the two bars above are derived rather than fitted, and so
        // that a future change to the clamp cannot quietly be absorbed by the looser one.
        double aKr = CLAMPED_EASTING / ProjectionMath.HALFPI;
        assertEquals("one ulp of shortfall is worth 0.0947 m of easting, which is why a libm "
                        + "difference in the last bit shows up as a tenth of a metre",
                0.0947, aKr * (ProjectionMath.HALFPI - StrictMath.asin(1 - Math.ulp(1.0) / 2)),
                1e-4);
        assertEquals("and the 117 ulp measured at latitude -88 is worth 1.449 m, which is the "
                        + "1.4397 m actually seen there",
                1.449, aKr * (ProjectionMath.HALFPI - StrictMath.asin(1 - 2.6e-14)), 1e-2);
    }

    /** The fourteen latitudes at which the turning locus used to produce a {@code NaN} easting. */
    private static final int[] ALL_FOURTEEN =
            {-88, -86, -80, -68, -43, 38, 43, 56, 72, 75, 80, 82, 84, 88};

    // ------------------------------------------------------------------ the determinism guard

    /**
     * <b>The regression guard for the defect this file was rewritten for.</b> No method of
     * {@link SwissObliqueMercatorProjection} may reference any of the seven
     * {@code @IntrinsicCandidate} {@link Math} functions - {@code sin cos tan log log10 exp pow} -
     * because HotSpot substitutes a per-architecture implementation for each of them and this
     * projection's turning locus converts one ulp into a tenth of a metre.
     *
     * <p>This is the probe that would have caught the red CI job before it was pushed, and the
     * existing determinism suite does not contain it: {@code StrictMathGoldenTableTest} pins
     * {@code StrictMath}'s <em>own</em> bits against a committed table and never calls a
     * {@link Projection}, so it is blind to a projection that simply does not use
     * {@code StrictMath}. Its own javadoc says as much and names this class as the second-heaviest
     * {@code Math} caller in {@code core}, at 33 sites. This test removes those 33.
     *
     * <p>It reads the constant pool of the compiled class rather than the source, so
     * {@code import static java.lang.Math.*} cannot hide a call from it - which is exactly how
     * {@code KrovakProjection}'s 49 calls are invisible to a grep for {@code Math\.}.
     *
     * <p><b>Two always-on controls, because a scanner that cannot fail is worthless.</b>
     * {@code KrovakProjection} is scanned with the same code and must report violations - it is the
     * heaviest {@code Math} caller left in the module - and this projection's own class file must
     * still show <em>some</em> {@code java/lang/Math} reference ({@code sqrt} and {@code abs}, both
     * exactly specified by IEEE 754 and therefore allowed), which proves the scan reached the class
     * and parsed its pool rather than finding nothing because it found nothing at all.
     */
    @Test
    public void nothingOnThisProjectionsPathCallsAPlatformVariantMathFunction() throws Exception {
        TreeSet<String> variant = mathMethodsReferencedBy(SwissObliqueMercatorProjection.class,
                VARIANT);
        assertEquals("SwissObliqueMercatorProjection must reference no platform-variant Math "
                + "function; each of these is @IntrinsicCandidate and decides ~0.1 m of easting on "
                + "the turning locus with its last bit. Use FastStrictTrig for sin/cos/tan and "
                + "StrictMath for log/exp/pow. Found: " + variant,
                "[]", variant.toString());

        // CONTROL 1: the scanner reaches this class and parses its pool. sqrt and abs are exactly
        // specified by IEEE 754, so they are allowed - but they must be SEEN.
        TreeSet<String> exact = mathMethodsReferencedBy(SwissObliqueMercatorProjection.class,
                new String[]{"sqrt", "abs"});
        assertTrue("the scan must still find the allowed Math.sqrt/Math.abs references in this "
                + "class, or it is reporting a clean result because it read nothing; found: "
                + exact, exact.contains("sqrt"));

        // CONTROL 2: the scanner can detect the needle. KrovakProjection is the heaviest remaining
        // Math caller in core (49 call sites) and reaches them through `import static
        // java.lang.Math.*`, so it is also the case a source grep misses.
        TreeSet<String> krovak = mathMethodsReferencedBy(KrovakProjection.class, VARIANT);
        assertTrue("the scanner must find platform-variant Math calls in KrovakProjection, which "
                + "has 49 of them - if this is empty the scanner is broken, not Krovak clean; "
                + "found: " + krovak, krovak.size() >= 3);
    }

    /**
     * The seven {@link Math} methods HotSpot may substitute per architecture. {@code asin},
     * {@code acos}, {@code atan}, {@code atan2}, {@code sqrt} and {@code abs} are deliberately
     * absent: the first four delegate to {@link StrictMath} and the last two are exactly specified
     * by IEEE 754.
     */
    private static final String[] VARIANT =
            {"sin", "cos", "tan", "log", "log10", "exp", "pow"};

    // -------------------------------------------------------------------------------- the band

    /**
     * Longitudes strictly inside the old {@code NaN} band now answer, and they answer with PROJ's
     * number to the bit. Three probes: the first bad {@code double}, one a quarter of the way in,
     * and the last bad one. All three are within {@code 5.6e-6} of a degree of each other, which is
     * why they are written to seventeen digits.
     *
     * <p>Reference: {@code proj -f "%.17g" +proj=somerc +lat_0=0 +lon_0=0 +ellps=WGS84} on each of
     * the three prints {@code 9985163.1855612863 15496570.739723718} (PROJ 9.8.1) - the same pair,
     * because the clamp returns {@code HALFPI} across the whole band on both sides. Unchanged by
     * the fdlibm routing: measured as the same two {@code double}s before and after.
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
                    + "easting exactly", CLAMPED_EASTING, xy.x, 0.0);
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
     * round trip through them. Unchanged by the fdlibm routing.
     *
     * <p>Reference, forward: {@code echo "89.69824704017273 80" | proj -f "%.17g" +proj=somerc
     * +lat_0=0 +lon_0=0 +ellps=WGS84} prints {@code 9985163.1855612863 15496570.739723718}.
     * Inverse: {@code echo "9985163.185561286 15496570.739723718" | proj -I -f "%.17g" ...} prints
     * {@code 89.698247040172731 80.000000000000014} (PROJ 9.8.1).
     *
     * <p>The latitude comes back as {@code 80.00000000000001} rather than 80, in PROJ as much as
     * here, and the two agree on which {@code double} that is - so this is asserted at zero
     * tolerance rather than at {@code 1e-9}, which would hide a change in the last bit.
     */
    @Test
    public void theRoundTripAtTheTurningPointIsBitIdenticalToProjBothWays() {
        ProjCoordinate xy = forward(TURN_LON, 80);
        assertEquals("forward easting must be PROJ's exactly", CLAMPED_EASTING, xy.x, 0.0);
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
     *
     * <p>{@code Math.asin} is the right comparand even after the fdlibm routing: {@code asin} is
     * not {@code @IntrinsicCandidate} and {@code Math.asin} is a straight delegation to
     * {@code StrictMath.asin}, so it is already architecture-independent.
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
     * The 1.15 m disagreement with PROJ on {@code +ellps=bessel} is a different thing, and neither
     * the clamp nor the fdlibm routing moves it. It is here so that the two are not confused:
     * someone reading the class comment will meet both numbers, and only one of them is about
     * {@code asinChecked}.
     *
     * <p>At {@code (-8.1, -43.1)} under {@code +proj=somerc +lat_0=46.9524055970347 +lon_0=0
     * +ellps=bessel}, {@code proj} gives {@code -10019820.590799341 -18875770.257259779} and we
     * give {@code -1.0019819438357947E7 -1.887577025725964E7}: <b>1.152441393584013 m</b> apart in
     * easting, {@code 1.4e-7} m in northing. The argument of the {@code asin} there is comfortably
     * inside the domain, so no clamp fires on either side; the gap is fdlibm against the platform
     * {@code libm}, magnified because {@code asin}'s derivative grows without bound near 1. The
     * easting was measured as the same {@code double} before and after the clamp <b>and before and
     * after the fdlibm routing</b>.
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
     * +ellps=WGS84} (PROJ 9.8.1). All four are unchanged by the fdlibm routing.
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
        assertEquals("lat " + latDeg + ": KNOWN DIVERGENCE. PROJ 9.8.1 on arm64 gives " + projX
                        + " and we give " + xy.x + ", because fdlibm and the platform libm put the "
                        + "quotient at somerc.cpp:33 on different sides of 1, or different "
                        + "distances below it. PROJ's own two architectures disagree with each "
                        + "other by 0.0947 m and 0.1340 m at latitudes 43 and 56, so a gap of this "
                        + "size here is not ours to remove. The gap must stay " + expectedGap
                        + " m", expectedGap, xy.x - projX, 1e-9);
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

    // ------------------------------------------------------- constant-pool scan, for the guard

    /**
     * The names of the {@code java/lang/Math} methods {@code type}'s compiled constant pool
     * references, restricted to {@code interesting}. Reads the class file rather than the source,
     * so {@code import static java.lang.Math.*} cannot hide a call.
     */
    private static TreeSet<String> mathMethodsReferencedBy(Class<?> type, String[] interesting)
            throws IOException {
        byte[] pool = classBytes(type);
        TreeSet<String> found = new TreeSet<String>();
        for (String name : methodrefNames(pool, "java/lang/Math")) {
            for (int i = 0; i < interesting.length; i++) {
                if (interesting[i].equals(name)) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            fail("cannot read the compiled class file for " + type.getName()
                    + " - the guard cannot run, and a guard that cannot run must not pass");
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Every method name a {@code CONSTANT_Methodref} names on {@code owner}. A minimal constant
     * pool walk - enough to resolve {@code Methodref -> Class -> Utf8} and
     * {@code Methodref -> NameAndType -> Utf8}, and to skip every other tag by its fixed width.
     */
    private static List<String> methodrefNames(byte[] classFile, String owner) throws IOException {
        DataInputStream in =
                new DataInputStream(new java.io.ByteArrayInputStream(classFile));
        in.readInt();     // magic
        in.readUnsignedShort();  // minor
        in.readUnsignedShort();  // major
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        int[] refClassIndex = new int[count];
        int[] refNatIndex = new int[count];
        int[] natNameIndex = new int[count];
        boolean[] isMethodref = new boolean[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:  // Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7:  // Class
                    classNameIndex[i] = in.readUnsignedShort();
                    break;
                case 10: // Methodref
                case 11: // InterfaceMethodref
                    isMethodref[i] = true;
                    refClassIndex[i] = in.readUnsignedShort();
                    refNatIndex[i] = in.readUnsignedShort();
                    break;
                case 9:  // Fieldref
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                    break;
                case 12: // NameAndType
                    natNameIndex[i] = in.readUnsignedShort();
                    in.readUnsignedShort();
                    break;
                case 3:  // Integer
                case 4:  // Float
                    in.readInt();
                    break;
                case 5:  // Long
                case 6:  // Double
                    in.readLong();
                    i++;  // eight-byte constants take two pool slots
                    break;
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.readUnsignedShort();
                    break;
                case 15: // MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag + " at index " + i
                            + " - the parser is out of step with the class file format and its "
                            + "clean results cannot be trusted");
            }
        }
        List<String> names = new ArrayList<String>();
        for (int i = 1; i < count; i++) {
            if (!isMethodref[i]) {
                continue;
            }
            String cls = utf8[classNameIndex[refClassIndex[i]]];
            if (owner.equals(cls)) {
                names.add(utf8[natNameIndex[refNatIndex[i]]]);
            }
        }
        return names;
    }
}
