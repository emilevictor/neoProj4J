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

package org.locationtech.proj4j.numerics.wiring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_A;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_E;
import static org.locationtech.proj4j.numerics.wiring.GieCase.GRS80_ES;
import static org.locationtech.proj4j.numerics.wiring.GieCase.MM;
import static org.locationtech.proj4j.numerics.wiring.GieCase.NM;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code MercatorProjection} re-pointed at {@link ConformalLat}, plus {@code +lat_ts}.
 *
 * <p>Three separate things are asserted here, and the first is the reason this projection was worth
 * doing first: {@code builtins.gie:4262} is the only {@code tolerance 0 m} row in the corpus that
 * proj4j could not satisfy.
 */
public class MercatorWiringTest {

    private static final GieCase GRS80 = GieCase.grs80("+proj=merc +ellps=GRS80");

    /**
     * {@code builtins.gie:4262-4265}:
     * <pre>
     *   operation +proj=merc   +ellps=GRS80
     *   tolerance 0 m
     *   accept  0 0
     *   expect  0 0
     * </pre>
     * and the matching {@code direction inverse} row at {@code :4283-4285}.
     *
     * <p>{@code tolerance 0 m} admits nothing but a bit-exact zero. The forward is now
     * {@code k0 * (asinh(sin/cos) - e*atanh(e*sin))}, both terms of which are exactly zero at the
     * equator. The old route was {@code -k0*log(tsfn(0))} with
     * {@code ProjectionMath.tsfn(0) == 0.9999999999999999}, whose logarithm is {@code -1.11e-16} —
     * a northing of <b>7.081154551613622e-10 m</b>. Small, and still a failure.
     */
    @Test
    public void forwardOfTheOriginIsBitExactlyZero() {
        ProjCoordinate got = GRS80.forward(0, 0);
        assertEquals("builtins.gie:4262 is tolerance 0 m; the easting must be exactly zero",
                0L, Double.doubleToRawLongBits(got.x));
        assertEquals("builtins.gie:4262 is tolerance 0 m; the northing must be exactly zero, "
                        + "not " + got.y,
                0L, Double.doubleToRawLongBits(got.y));

        double before = Math.abs(Legacy.mercNorthing(0.0, GRS80_A, GRS80_ES, 1.0));
        assertEquals("the pre-change path produced 7.081154551613622e-10 m here, which is what "
                        + "failed the row", 7.081154551613622e-10, before, 0.0);
        assertTrue("the deprecated tsfn must be one ulp short of 1.0, else the row was already "
                        + "passing and this test proves nothing",
                ProjectionMath.tsfn(0.0, 0.0, GRS80_E) != 1.0);

        // The inverse side of the same row, builtins.gie:4283-4285.
        ProjCoordinate back = GRS80.inverse(0, 0);
        assertEquals(0L, Double.doubleToRawLongBits(back.x));
        assertEquals(0L, Double.doubleToRawLongBits(back.y));
    }

    /**
     * {@code builtins.gie:4266-4274}, {@code tolerance 50 nm}: the four sign combinations of
     * {@code (+/-2, +/-1) -> (+/-222638.981586547, +/-110579.965218249)}.
     *
     * <p>Both paths satisfy this row; the new one is closer, from 1.433 nm to 0.642 nm.
     */
    @Test
    public void forwardMatchesTheFiftyNanometreRows() {
        GRS80.expectForward(2, 1, 222638.981586547, 110579.965218249, 50 * NM);
        GRS80.expectForward(2, -1, 222638.981586547, -110579.965218249, 50 * NM);
        GRS80.expectForward(-2, 1, -222638.981586547, 110579.965218249, 50 * NM);
        GRS80.expectForward(-2, -1, -222638.981586547, -110579.965218249, 50 * NM);

        double now = GRS80.forwardDeviation(2, 1, 222638.981586547, 110579.965218249);
        double before = Math.hypot(
                Legacy.mercEasting(2.0, GRS80_A, 1.0) - 222638.981586547,
                Legacy.mercNorthing(1.0, GRS80_A, GRS80_ES, 1.0) - 110579.965218249);
        GieCase.assertStrictlyBetter("builtins.gie:4267 merc forward", before, now, 50 * NM);
    }

    /**
     * {@code builtins.gie:4287-4294}, {@code tolerance 50 nm}:
     * {@code (200, 100) -> (0.00179663056824, 0.00090436947704)} and its three reflections.
     *
     * <p><b>This is the row the old inverse failed.</b> The 15-step Newton loop in
     * {@code ProjectionMath.phi2} stops when {@code |dphi| <= 1e-10} radians, which is 0.64 mm on
     * the ground, so it returns as soon as it is roughly a millimetre out and the residual error is
     * whatever it happens to be — measured here <b>201.7 nm</b> against a 50 nm bar, i.e. four times
     * over. Newton on {@code tau} converges to 0.240 nm.
     */
    @Test
    public void inverseMatchesTheFiftyNanometreRows() {
        GRS80.expectInverse(200, 100, 0.00179663056824, 0.00090436947704, 50 * NM);
        GRS80.expectInverse(200, -100, 0.00179663056824, -0.00090436947704, 50 * NM);
        GRS80.expectInverse(-200, 100, -0.00179663056824, 0.00090436947704, 50 * NM);
        GRS80.expectInverse(-200, -100, -0.00179663056824, -0.00090436947704, 50 * NM);

        double now = GRS80.inverseDeviation(200, 100, 0.00179663056824, 0.00090436947704);
        double before = GRS80.angularDeviation(0.00179663056824, 0.00090436947704,
                new ProjCoordinate(0.00179663056824,
                        Legacy.mercLatitude(100.0, GRS80_A, GRS80_ES, 1.0)));
        assertTrue("the pre-change inverse must miss the 50 nm bar, else the row was already "
                        + "passing; measured " + before + " m", before > 50 * NM);
        GieCase.assertStrictlyBetter("builtins.gie:4288 merc inverse", before, now, 50 * NM);
    }

    /**
     * {@code builtins.gie:4295-4303}: the near-pole and beyond-pole inverse rows.
     * <pre>
     *   accept  0 235805185.015130176   expect  0 89.99999999999999
     *   accept  0 1e10                  expect  0 90
     * </pre>
     * The second is why the inverse works from {@code sinh(y/k0)} rather than
     * {@code exp(-y/k0)}: {@code sinh} of {@code 1567.8} is infinity, the {@code |taup| > 70} branch
     * of {@code sinhpsi2tanphi} returns it unchanged, and {@code atan(inf)} is exactly
     * {@code pi/2}.
     */
    @Test
    public void inverseReachesThePole() {
        GRS80.expectInverse(0, 235805185.015130176, 0, 89.99999999999999, 50 * NM);
        GRS80.expectInverse(0, -235805185.015130176, 0, -89.99999999999999, 50 * NM);
        assertEquals("builtins.gie:4301 expects exactly 90", 90.0, GRS80.inverse(0, 1e10).y, 0.0);
        assertEquals("builtins.gie:4303 expects exactly -90", -90.0,
                GRS80.inverse(0, -1e10).y, 0.0);
    }

    /**
     * {@code builtins.gie:4306-4333}, the whole {@code +R=6400000} block.
     *
     * <p>The spherical branch never touched {@code phi2} or {@code tsfn}, but it did change shape:
     * {@code asinh(tan(phi))} for {@code merc.cpp:23-27} and {@code atan(sinh(y/k0))} for
     * {@code :37-41}, in place of {@code log(tan(pi/4 + phi/2))} and
     * {@code pi/2 - 2*atan(exp(-y/k0))}. Algebraically identical; both {@code tolerance 0 m} rows
     * and all eight {@code tolerance 50 nm} rows still hold.
     */
    @Test
    public void sphericalBranchStillMatchesGie() {
        GieCase r = GieCase.sphere("+proj=merc +R=6400000", 6400000.0);
        ProjCoordinate origin = r.forward(0, 0);
        assertEquals("builtins.gie:4308 is tolerance 0 m", 0L,
                Double.doubleToRawLongBits(origin.x));
        assertEquals("builtins.gie:4308 is tolerance 0 m", 0L,
                Double.doubleToRawLongBits(origin.y));
        ProjCoordinate back = r.inverse(0, 0);
        assertEquals("builtins.gie:4323 is tolerance 0 m", 0L,
                Double.doubleToRawLongBits(back.x));
        assertEquals("builtins.gie:4323 is tolerance 0 m", 0L,
                Double.doubleToRawLongBits(back.y));

        r.expectForward(2, 1, 223402.144255274, 111706.743574944, 50 * NM);
        r.expectForward(2, -1, 223402.144255274, -111706.743574944, 50 * NM);
        r.expectForward(-2, 1, -223402.144255274, 111706.743574944, 50 * NM);
        r.expectForward(-2, -1, -223402.144255274, -111706.743574944, 50 * NM);
        r.expectInverse(200, 100, 0.00179049310978, 0.00089524655486, 50 * NM);
        r.expectInverse(200, -100, 0.00179049310978, -0.00089524655486, 50 * NM);
        r.expectInverse(-200, 100, -0.00179049310978, 0.00089524655486, 50 * NM);
        r.expectInverse(-200, -100, -0.00179049310978, -0.00089524655486, 50 * NM);
        r.expectRoundtrip(2, 1, 1, 50 * NM);
        r.expectRoundtrip(2, 85, 1, 50 * NM);
    }

    /**
     * {@code builtins.gie:4336-4347}, the block whose own comment is
     * "Test the numerical stability of the inverse spherical Mercator", at
     * <b>{@code tolerance 1e-17 m}</b> on a unit sphere — the tightest non-zero bar in the corpus:
     * <pre>
     *   operation +proj=merc +R=1
     *   accept  0   57.295779513e-15   expect  0   1e-15
     *   direction inverse
     *   accept  0   1e-15              expect  0   57.295779513e-15
     * </pre>
     *
     * <p>Both directions used to lose the answer to cancellation against a leading 1.
     * {@code log(tan(pi/4 + phi/2))} evaluates {@code log(1 + 1e-15)} and
     * {@code pi/2 - 2*atan(exp(-1e-15))} subtracts two nearly equal quantities;
     * {@code asinh}'s {@code log1p} branch and {@code atan(sinh(y))} have no such term.
     */
    @Test
    public void unitSphereKeepsFullRelativeAccuracyAtTheEquator() {
        GieCase r = GieCase.sphere("+proj=merc +R=1", 1.0);
        r.expectForward(0, 57.295779513e-15, 0, 1e-15, 1e-17);
        r.expectInverse(0, 1e-15, 0, 57.295779513e-15, 1e-17);

        double phi = Math.toRadians(57.295779513e-15);
        double before = Math.abs(Math.log(Math.tan(ProjectionMath.QUARTERPI + 0.5 * phi)) - 1e-15);
        double now = Math.abs(r.forward(0, 57.295779513e-15).y - 1e-15);
        assertTrue("the pre-change spherical forward should miss the 1e-17 bar here, measured "
                + before, before > 1e-17);
        assertTrue("asinh's log1p branch must land inside it, measured " + now, now <= 1e-17);
    }

    /**
     * {@code gigs/5112.gie}, Mercator variant B — EPSG:3388, Pulkovo 1942 / Caspian Sea Mercator,
     * which is {@code +proj=merc +lat_ts=42 +lon_0=51 +ellps=krass}.
     *
     * <p>proj4j read {@code +lat_ts} into a field and never used it, leaving {@code k0 = 1}. Every
     * row here was therefore out by the factor {@code msfn(42 deg, krass) = 0.744260894} — up to
     * <b>1.30 million metres</b> on the last row — and {@code gigs/5112} passed 7 of 15: the two
     * equator rows in each direction, where {@code lat_ts} is a no-op, plus the five
     * self-consistent {@code roundtrip 1000} blocks, which close regardless of {@code k0}.
     *
     * <p>Both non-roundtrip blocks of {@code 5112} are at {@code tolerance 50 mm}; the expectations
     * are printed to 2 decimals, so the residuals below are dominated by that.
     */
    @Test
    public void latTsIsAppliedForMercatorVariantB() {
        // Krassovsky 1940: a = 6378245, rf = 298.3.
        GieCase b = GieCase.ellipsoid("+proj=merc +lat_ts=42 +lon_0=51 +ellps=krass",
                "krass", 6378245.0, 298.3);
        double[][] rows = {
            {51.0, 42.0, 0.0, 3819897.85},
            {51.0, 0.0, 0.0, 0.0},
            {57.0, 0.0, 497112.88, 0.0},
            {54.0, 20.5, 248556.44, 1724781.5},
            {67.0, -41.0, 1325634.35, -3709687.25},
        };
        double worstBefore = 0.0;
        for (double[] r : rows) {
            b.expectForward(r[0], r[1], r[2], r[3], 50 * MM);
            b.expectInverse(r[2], r[3], r[0], r[1], 50 * MM);
            b.expectRoundtrip(r[0], r[1], 1000, 6 * MM);
            // What k0 = 1 gave: the same shape, unscaled.
            worstBefore = Math.max(worstBefore, Math.hypot(
                    Legacy.mercEasting(r[0] - 51.0, 6378245.0, 1.0) - r[2],
                    Legacy.mercNorthing(r[1], 6378245.0, krassEs(), 1.0) - r[3]));
        }
        assertTrue("with k0 = 1 the worst gigs/5112 row should be over a megametre out, "
                + "measured " + worstBefore + " m", worstBefore > 1.0e6);
    }

    /**
     * The ratio that identified the defect as a missing line rather than a wrong formula:
     * every failing {@code gigs/5112} row was out by exactly {@code msfn(42 deg, krass)}.
     */
    @Test
    public void theScaleFactorIsMsfnOfLatTs() {
        double phits = Math.toRadians(42.0);
        double expected = ProjectionMath.msfn(Math.sin(phits), Math.cos(phits), krassEs());
        assertEquals("msfn(42 deg, krass) is the observed error ratio to nine digits",
                0.744260894, expected, 5e-10);

        GieCase b = GieCase.ellipsoid("+proj=merc +lat_ts=42 +lon_0=51 +ellps=krass",
                "krass", 6378245.0, 298.3);
        // At lon 57, lat 0, the easting is k0 * a * lam exactly.
        double lam = Math.toRadians(6.0);
        assertEquals(expected * 6378245.0 * lam, b.forward(57, 0).x, 1e-6);
    }

    /**
     * {@code merc.cpp:50-55} rejects {@code |lat_ts| >= 90}. proj4j silently ignored the parameter,
     * so there was nothing to reject.
     */
    @Test(expected = InvalidValueException.class)
    public void latTsBeyondThePoleIsRejected() {
        new CRSFactory().createFromParameters("bad", "+proj=merc +lat_ts=90 +ellps=GRS80 +no_defs");
    }

    /** {@code +lat_ts} on a sphere is {@code cos(lat_ts)}, {@code merc.cpp:64-66}. */
    @Test
    public void latTsOnASphereIsCosine() {
        GieCase s = GieCase.sphere("+proj=merc +lat_ts=42 +R=6400000", 6400000.0);
        double lam = Math.toRadians(2.0);
        assertEquals(Math.cos(Math.toRadians(42.0)) * 6400000.0 * lam, s.forward(2, 0).x, 1e-9);
    }

    /**
     * Round-trip closure across the working range, at the 50 nm bar the corpus sets for this
     * projection rather than the 0.1 mm one it sets for most.
     */
    @Test
    public void roundTripClosesAtFiftyNanometres() {
        for (double lat : new double[] {0.0, 0.00090436947704, 1, 2.8, 30, 60, 85}) {
            GRS80.expectRoundtrip(2, lat, 100, 50 * NM);
            GRS80.expectRoundtrip(-2, -lat, 100, 50 * NM);
        }
    }

    /**
     * {@code +lat_ts=0} is a parameter that was given, not a parameter that was omitted, and it
     * discards {@code +k} exactly as any other {@code +lat_ts} does.
     *
     * <p>{@code merc.cpp} keeps {@code pj_param(..., "tlat_ts").i} in {@code is_phits} and guards
     * both scale-factor assignments with it, so presence is the test and zero is a real answer:
     * {@code msfn(0, 1, es)} and {@code cos(0)} are both 1. A {@code trueScaleLatitude != 0.0}
     * guard cannot see the difference, and three shipped ESRI definitions depend on it —
     * {@code esri:2934}, {@code esri:21100} and {@code esri:25700}, all carrying
     * {@code +lat_ts=0} together with {@code +k=0.997000}. The first two are byte-identical; the
     * third adds a {@code +towgs84}, which is why enumerating byte-identical definitions found only
     * two of them. See {@link #theShippedEsriDefinitionsGetProjsScaleFactor()}.
     *
     * <p>All four expectations below are PROJ 9.8.1's own output for this definition,
     * {@code proj -f "%.6f"} and {@code proj -I -f "%.10f"}. The forward gap the old guard left is
     * <b>48,880.69 m</b> of easting at longitude 110 east, measured here rather than described.
     * That is 0.3 percent of the {@code 20193564.578396 - 3900000} that {@code +k} multiplies, not
     * 0.3 percent of the easting: {@code +x_0} is added after the scale.
     */
    @Test
    public void explicitLatTsZeroDiscardsK() {
        GieCase b = GieCase.ellipsoid(ESRI_2934, "bessel", BESSEL_A, BESSEL_RF);
        b.expectForward(110, 0, 20193564.578396, 900000.0, MM);
        b.expectForward(110, -6, 20193564.578396, 235385.936338, MM);
        b.expectInverse(20193564.578396, 235385.936338, 110, -6, MM);

        assertEquals("+lat_ts=0 must reset the scale factor to 1, discarding +k=0.997",
                1.0, scaleFactorOf(ESRI_2934), 0.0);

        // The size of what the old value-based guard gave away, in metres of easting.
        GieCase old = GieCase.ellipsoid(ESRI_2934_WITHOUT_LAT_TS, "bessel", BESSEL_A, BESSEL_RF);
        assertEquals("keeping +k=0.997 here costs this much easting", 48880.69,
                Math.abs(old.forward(110, -6).x - 20193564.578396), 0.01);
    }

    /**
     * The shipped definitions, reached the way a caller reaches them. Three entries in
     * {@code proj4/nad/esri} pair {@code +lat_ts=0} with a {@code +k} that is not 1, all three
     * {@code +k=0.997000}: {@code esri:2934}, {@code esri:21100} and {@code esri:25700}. The
     * third differs from the other two only by a {@code +towgs84}, which is why a search for
     * byte-identical definitions finds only two of them. All three must now scale by 1.
     *
     * <p>Two more entries in the same file carry {@code +lat_ts=0} beside a {@code +k},
     * {@code esri:53004} and {@code esri:54004}, but their {@code +k} is {@code 1.000000}, so they
     * scale by 1 either way and are here as the control. {@code epsg:3857} and {@code epsg:3785}
     * pair {@code +lat_ts=0} with a {@code +k=1.0} as well, so the count across every shipped
     * dictionary is four such definitions, not two.
     */
    @Test
    public void theShippedEsriDefinitionsGetProjsScaleFactor() {
        CRSFactory factory = new CRSFactory();
        for (String key : new String[] {"esri:2934", "esri:21100", "esri:25700",
                                        "esri:53004", "esri:54004"}) {
            assertEquals(key, 1.0,
                    factory.createFromName(key).getProjection().getScaleFactor(), 0.0);
        }
    }

    /**
     * The other side of the same rule, and the reason presence has to be carried rather than
     * inferred: with no {@code +lat_ts} at all, {@code +k} stands. Both expectations are PROJ
     * 9.8.1's output for this string, which is {@code esri:2934} minus its {@code +lat_ts=0}.
     */
    @Test
    public void absentLatTsLeavesKAlone() {
        assertEquals("no +lat_ts means +k is the scale factor",
                0.997, scaleFactorOf(ESRI_2934_WITHOUT_LAT_TS), 0.0);
        GieCase b = GieCase.ellipsoid(ESRI_2934_WITHOUT_LAT_TS, "bessel", BESSEL_A, BESSEL_RF);
        b.expectForward(110, 0, 20144683.884660, 900000.0, MM);
        b.expectForward(110, -6, 20144683.884660, 237379.778529, MM);

        // And on a plain ellipsoid, away from the ESRI definition's other parameters.
        assertEquals(0.997, scaleFactorOf("+proj=merc +k=0.997 +ellps=GRS80"), 0.0);
        assertEquals(1.0, scaleFactorOf("+proj=merc +lat_ts=0 +k=0.997 +ellps=GRS80"), 0.0);
    }

    /**
     * {@code esri:2934} and {@code esri:21100}, {@code epsg/src/main/resources/proj4/nad/esri}.
     * The two are byte-identical to each other.
     */
    private static final String ESRI_2934 = "+proj=merc +lat_ts=0 +lon_0=216.8077194444444 "
            + "+k=0.997000 +x_0=3900000 +y_0=900000 +ellps=bessel +pm=jakarta +units=m";

    /** The same definition with {@code +lat_ts} omitted, which is a different projection. */
    private static final String ESRI_2934_WITHOUT_LAT_TS = "+proj=merc +lon_0=216.8077194444444 "
            + "+k=0.997000 +x_0=3900000 +y_0=900000 +ellps=bessel +pm=jakarta +units=m";

    /** Bessel 1841, as PROJ's ellipsoid table gives it. */
    private static final double BESSEL_A = 6377397.155;
    private static final double BESSEL_RF = 299.1528128;

    /** The scale factor a definition ends up with, after {@code initialize()}. */
    private static double scaleFactorOf(String definition) {
        return new CRSFactory().createFromParameters("scale", definition + " +no_defs")
                .getProjection().getScaleFactor();
    }

    /** Krassovsky 1940 squared eccentricity, from {@code a} and {@code rf} as PROJ derives it. */
    private static double krassEs() {
        double f = 1.0 / 298.3;
        return 2.0 * f - f * f;
    }
}
