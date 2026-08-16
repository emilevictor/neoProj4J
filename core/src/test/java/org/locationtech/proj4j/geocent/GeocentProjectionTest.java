/*******************************************************************************
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
 *******************************************************************************/

package org.locationtech.proj4j.geocent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.GeocentProjection;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code +proj=geocent}, which had <b>no test at all</b> before this file.
 *
 * <h2>The defect these tests would have caught</h2>
 *
 * <p>{@code GeocentProjection.projectRadians} and {@code inverseProjectRadians} both read
 * {@code dst} instead of {@code src}, in both directions. It survived because the one caller in
 * {@code core} aliases the arguments — {@code BasicCoordinateTransform.transformClosed} does
 * {@code tgt.setValue(src)} then {@code projectRadians(tgt, tgt)} — so with {@code src == dst}
 * reading {@code dst} happens to read the input. Every test here that passes <b>distinct</b>
 * {@code src} and {@code dst} objects fails against the old code; every test that aliases them
 * passes against both, and those are here on purpose, because the aliased path is what 53,430
 * golden-master rows exercise and it must not move.
 *
 * <h2>Where the expected numbers come from</h2>
 *
 * <p>Not from proj4j. They are {@code 9.8.1:src/conversions/cart.cpp}'s {@code cartesian()}
 * evaluated independently:
 *
 * <pre>
 *   N = a / sqrt(1 - es sin^2(phi))
 *   X = (N + h) cos(phi) cos(lam)
 *   Y = (N + h) cos(phi) sin(lam)
 *   Z = (N (1 - es) + h) sin(phi)
 * </pre>
 *
 * <p>with WGS84 {@code a = 6378137.0} and {@code es = 2f - f^2}, {@code f = 1/298.257223563}.
 * That is the same expression, in the same order, as
 * {@code GeocentricConverter.convertGeodeticToGeocentric}, which is the finding recorded in
 * {@code GeocentProjection}'s javadoc: the two implementations of the <em>forward</em> agree bit
 * for bit, and only the inverse differs (Bowring's closed form upstream, the Toms/Hannover
 * iteration here). The tolerance is 1e-6 m, which is looser than either algorithm's own error and
 * covers the last-bit difference between deriving {@code es} from {@code rf} and quoting it.
 */
public class GeocentProjectionTest {

    /** Tighter than any algorithm here: both kernels agree to well under a micrometre. */
    private static final double MM = 1.0e-6;

    /** 1e-9 rad is about 6 mm of latitude; the iteration's own budget is 1e-12 rad. */
    private static final double RAD = 1.0e-9;

    private static Projection wgs84Geocent() {
        CoordinateReferenceSystem crs =
                new CRSFactory().createFromParameters("geocent-wgs84",
                        "+proj=geocent +ellps=WGS84 +units=m +no_defs");
        return crs.getProjection();
    }

    // -----------------------------------------------------------------------------------------
    // The defect itself: src must be read, dst must be written, and the two may be different
    // objects. Every assertion in this section fails against the pre-1.5.0 body.
    // -----------------------------------------------------------------------------------------

    @Test
    public void forwardReadsSrcNotDstWhenTheyAreDistinctObjects() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);
        // Deliberately poisoned: the old code converted THIS and returned it.
        ProjCoordinate dst = new ProjCoordinate(-1.0, -1.0, -1.0);

        p.projectRadians(src, dst);

        assertNotSame(src, dst);
        assertEquals(3571255.4410952283, dst.x, MM);
        assertEquals(597623.2032090913, dst.y, MM);
        assertEquals(5233194.16771844, dst.z, MM);
    }

    @Test
    public void forwardLeavesSrcUntouched() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(src, dst);

        assertEquals(Math.toRadians(9.5), src.x, 0.0);
        assertEquals(Math.toRadians(55.5), src.y, 0.0);
        assertEquals(100.0, src.z, 0.0);
    }

    @Test
    public void inverseReadsSrcNotDstWhenTheyAreDistinctObjects() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate(-1.0, -1.0, -1.0);

        p.inverseProjectRadians(src, dst);

        assertNotSame(src, dst);
        assertEquals(Math.toRadians(9.5), dst.x, RAD);
        assertEquals(Math.toRadians(55.5), dst.y, RAD);
        assertEquals(100.0, dst.z, 1.0e-4);
    }

    @Test
    public void inverseLeavesSrcUntouched() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate();

        p.inverseProjectRadians(src, dst);

        assertEquals(3571255.4410952283, src.x, 0.0);
        assertEquals(597623.2032090913, src.y, 0.0);
        assertEquals(5233194.16771844, src.z, 0.0);
    }

    /**
     * The degrees-in entry point is not virtual through {@code projectRadians(src, dst)} — the base
     * class routes it into a private two-ordinate funnel — so before 1.5.0 it never reached
     * {@code GeocentProjection} at all and returned the base identity plus the affine, i.e. the
     * input degrees back.
     */
    @Test
    public void degreesEntryPointReachesTheGeocentricConversion() {
        Projection p = wgs84Geocent();
        ProjCoordinate src = new ProjCoordinate(9.5, 55.5, 100.0);
        ProjCoordinate dst = new ProjCoordinate();

        p.project(src, dst);

        assertEquals(3571255.4410952283, dst.x, MM);
        assertEquals(597623.2032090913, dst.y, MM);
        assertEquals(5233194.16771844, dst.z, MM);
    }

    /** {@code inverseProject} is the radians inverse plus a RTD multiply on x and y only. */
    @Test
    public void degreesInverseEntryPointReturnsDegreesAndMetres() {
        Projection p = wgs84Geocent();
        ProjCoordinate src =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);
        ProjCoordinate dst = new ProjCoordinate();

        p.inverseProject(src, dst);

        assertEquals(9.5, dst.x, 1.0e-7);
        assertEquals(55.5, dst.y, 1.0e-7);
        assertEquals(100.0, dst.z, 1.0e-4);
    }

    // -----------------------------------------------------------------------------------------
    // The aliased path. These pass against the old body too, and that is the point: 1,058
    // golden-master rows go through it and must not move.
    // -----------------------------------------------------------------------------------------

    @Test
    public void aliasedForwardStillWorks() {
        Projection p = wgs84Geocent();
        ProjCoordinate both =
                new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 100.0);

        p.projectRadians(both, both);

        assertEquals(3571255.4410952283, both.x, MM);
        assertEquals(597623.2032090913, both.y, MM);
        assertEquals(5233194.16771844, both.z, MM);
    }

    @Test
    public void aliasedInverseStillWorks() {
        Projection p = wgs84Geocent();
        ProjCoordinate both =
                new ProjCoordinate(3571255.4410952283, 597623.2032090913, 5233194.16771844);

        p.inverseProjectRadians(both, both);

        assertEquals(Math.toRadians(9.5), both.x, RAD);
        assertEquals(Math.toRadians(55.5), both.y, RAD);
        assertEquals(100.0, both.z, 1.0e-4);
    }

    // -----------------------------------------------------------------------------------------
    // cart.cpp agreement across the ellipsoid and the domain.
    // -----------------------------------------------------------------------------------------

    /** Prime meridian on the equator: X is exactly {@code a}, Y and Z exactly zero. */
    @Test
    public void originIsTheSemiMajorAxis() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, 0.0, 0.0), dst);

        assertEquals(6378137.0, dst.x, 0.0);
        assertEquals(0.0, dst.y, 0.0);
        assertEquals(0.0, dst.z, 0.0);
    }

    /** The north pole: Z is exactly the semi-minor axis, {@code a (1 - f)}. */
    @Test
    public void northPoleIsTheSemiMinorAxis() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0, 0.0), dst);

        assertEquals(0.0, dst.x, MM);
        assertEquals(0.0, dst.y, 0.0);
        assertEquals(6356752.314245179, dst.z, MM);
    }

    @Test
    public void southernHemisphereAndNegativeLongitudeWithHeight() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        p.projectRadians(
                new ProjCoordinate(Math.toRadians(-77.0), Math.toRadians(-38.0), 1234.5), dst);

        assertEquals(1132269.1140115347, dst.x, MM);
        assertEquals(-4904396.350538061, dst.y, MM);
        assertEquals(-3906204.0025103916, dst.z, MM);
    }

    /**
     * {@code cosphi < 1e-6} in {@code cart.cpp:225} — poleward of 89.99994 degrees the height
     * comes from the geocentric radius rather than from a division by a vanishing cosine. The
     * iteration reaches the same answer by a different route, so this asserts the round trip
     * rather than the branch.
     */
    @Test
    public void nearPoleRoundTrips() {
        Projection p = wgs84Geocent();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(120.0), Math.toRadians(89.9), 0.0), xyz);
        assertEquals(-5584.696085303042, xyz.x, MM);
        assertEquals(9672.977364575885, xyz.y, MM);
        assertEquals(6356742.567109314, xyz.z, MM);

        p.inverseProjectRadians(xyz, back);
        assertEquals(Math.toRadians(120.0), back.x, RAD);
        assertEquals(Math.toRadians(89.9), back.y, RAD);
        assertEquals(0.0, back.z, 1.0e-4);
    }

    /**
     * A declared sphere: {@code es == 0}, so {@code N == a} at every latitude. Spelled
     * {@code +a}/{@code +b} rather than {@code +R} on purpose — {@code +R}'s parse path is being
     * rewritten by another stream (see {@code PARSE-R-DECLARES-SPHERE} in
     * {@code golden/rules.yaml}) and this test is about the conversion, not about the parser.
     */
    @Test
    public void sphereUsesTheRadiusAtEveryLatitude() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("geocent-sphere",
                "+proj=geocent +a=6371000 +b=6371000 +units=m +no_defs");
        ProjCoordinate dst = new ProjCoordinate();

        crs.getProjection().projectRadians(
                new ProjCoordinate(Math.toRadians(30.0), Math.toRadians(60.0), 0.0), dst);

        assertEquals(2758723.9237553305, dst.x, MM);
        assertEquals(1592750.0000000002, dst.y, MM);
        assertEquals(5517447.847510658, dst.z, MM);
    }

    @Test
    public void roundTripsOverAGridOfTheWholeEllipsoid() {
        Projection p = wgs84Geocent();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();
        for (double lon = -180.0; lon <= 180.0; lon += 15.0) {
            for (double lat = -89.0; lat <= 89.0; lat += 7.0) {
                for (double h : new double[] {-500.0, 0.0, 8848.0}) {
                    p.projectRadians(
                            new ProjCoordinate(Math.toRadians(lon), Math.toRadians(lat), h), xyz);
                    p.inverseProjectRadians(xyz, back);
                    String at = "(" + lon + ", " + lat + ", " + h + ")";
                    // atan2 answers -pi for a longitude of exactly 180 west; both are the
                    // antimeridian.
                    double dlon = Math.abs(back.x - Math.toRadians(lon)) % (2.0 * Math.PI);
                    assertEquals(at, 0.0, Math.min(dlon, 2.0 * Math.PI - dlon), RAD);
                    assertEquals(at, Math.toRadians(lat), back.y, RAD);
                    assertEquals(at, h, back.z, 1.0e-4);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // +lon_0, which upstream honours for a cartesian right-hand side and the override skipped.
    // -----------------------------------------------------------------------------------------

    /**
     * {@code fwd_prepare} ends the angular branch with {@code lam = (lam - from_greenwich) - lam0}
     * ({@code 9.8.1:src/fwd.cpp:105-112}) whatever {@code P->right} is, and {@code inv_finalize}
     * adds it back ({@code inv.cpp:110-118}). So {@code +lon_0=10} at longitude 10 must give the
     * same triple as {@code +lon_0=0} at longitude 0 — Y exactly zero.
     */
    @Test
    public void lonZeroRotatesTheCartesianFrame() {
        CoordinateReferenceSystem shifted = new CRSFactory().createFromParameters("geocent-lon0",
                "+proj=geocent +ellps=GRS80 +lon_0=10 +units=m +no_defs");
        ProjCoordinate dst = new ProjCoordinate();

        shifted.getProjection().projectRadians(
                new ProjCoordinate(Math.toRadians(10.0), Math.toRadians(45.0), 0.0), dst);

        assertEquals(4517590.878886053, dst.x, MM);
        assertEquals(0.0, dst.y, MM);
        assertEquals(4487348.4087547995, dst.z, MM);
    }

    @Test
    public void lonZeroRoundTrips() {
        CoordinateReferenceSystem shifted = new CRSFactory().createFromParameters("geocent-lon0",
                "+proj=geocent +ellps=GRS80 +lon_0=10 +units=m +no_defs");
        Projection p = shifted.getProjection();
        ProjCoordinate xyz = new ProjCoordinate();
        ProjCoordinate back = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(-13.25), Math.toRadians(7.5), 42.0), xyz);
        p.inverseProjectRadians(xyz, back);

        assertEquals(Math.toRadians(-13.25), back.x, RAD);
        assertEquals(Math.toRadians(7.5), back.y, RAD);
        assertEquals(42.0, back.z, 1.0e-4);
    }

    /** Without {@code +lon_0} nothing may be added, because {@code x + 0.0} is not the identity. */
    @Test
    public void withoutLonZeroNegativeZeroSurvives() {
        Projection p = wgs84Geocent();
        ProjCoordinate dst = new ProjCoordinate();

        // Longitude exactly 180 west: atan2(-0.0, -a) is -pi, and the sign carries which side of
        // the antimeridian the point is on.
        p.inverseProjectRadians(new ProjCoordinate(-6378137.0, -0.0, 0.0), dst);

        assertTrue("expected -pi, got " + dst.x, dst.x < 0.0);
        assertEquals(-Math.PI, dst.x, RAD);
    }

    // -----------------------------------------------------------------------------------------
    // The linear unit. fwd_finalize's PJ_IO_UNITS_CARTESIAN branch (9.8.1:src/fwd.cpp:133-136)
    // multiplies ALL THREE ordinates by fr_meter and adds no false easting; inv_prepare
    // (inv.cpp:67-69) divides all three back. The override used to skip both, so +to_meter and
    // +units were parsed into `fromMetres` and then never read on this path.
    //
    // The witness is conformance/src/test/resources/gie/4D-API_cs2cs-style.gie:488 (block #41),
    // which expected (0, 1, 0) and got (0, 1000, 0) -- 999 m of deviation on a 1000 m sphere, i.e.
    // the whole scale factor. Its sibling at :493 is the same test on +proj=cart and always passed,
    // because `cart` is not in Registry and therefore routes to the pipeline engine, whose
    // CartOperator already did this.
    // -----------------------------------------------------------------------------------------

    /**
     * The gie row itself. {@code +proj=geocent +a=1000 +b=1000 +to_meter=1000} at
     * {@code (90, 0, 0)}: X is the cosine of a right angle, Y is one thousandth of the radius in
     * metres, Z is zero.
     *
     * <p>Reference is PROJ 9.8.1's own answer, not proj4j's:
     * {@code echo "90 0 0" | cct -d 18 +proj=geocent +a=1000 +b=1000 +to_meter=1000} prints
     * {@code 0.000000000000000061  1.000000000000000000  0.000000000000000000}. Asserted through
     * both entry points because they are separate overrides: {@code project} takes degrees,
     * {@code projectRadians} radians, and only the second is what {@code fwd.cpp} corresponds to.
     */
    @Test
    public void toMeterScalesAllThreeOrdinatesOnTheForward() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("geocent-to-meter",
                "+proj=geocent +a=1000 +b=1000 +to_meter=1000 +no_defs");
        Projection p = crs.getProjection();
        ProjCoordinate radians = new ProjCoordinate();
        ProjCoordinate degrees = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.PI / 2.0, 0.0, 0.0), radians);
        p.project(new ProjCoordinate(90.0, 0.0, 0.0), degrees);

        // gie's tolerance on this row is 0.5 mm, which at this scale is 5e-7 of a unit.
        assertEquals(6.1e-17, radians.x, 5.0e-7);
        assertEquals(1.0, radians.y, 5.0e-7);
        assertEquals(0.0, radians.z, 5.0e-7);
        assertEquals(degrees.x, radians.x, 0.0);
        assertEquals(degrees.y, radians.y, 0.0);
        assertEquals(degrees.z, radians.z, 0.0);
    }

    /**
     * The same row's {@code roundtrip 1}, which <b>passed vacuously</b> before the forward was
     * fixed: forward and inverse were wrong by reciprocal factors, so the round trip closed while
     * neither direction was right. {@code echo "0 1 0" | cct -I -d 12 +proj=geocent +a=1000
     * +b=1000 +to_meter=1000} prints {@code 90 0 0}.
     *
     * <p>Note {@code fromMetres} holds {@code 1/to_meter} ({@code Proj4Parser:344,349}), so the
     * inverse's scale is a reciprocal of a reciprocal and getting it backwards is a factor of
     * {@code to_meter} squared -- 1e6 here, which is why this is asserted against PROJ rather than
     * against the forward.
     */
    @Test
    public void toMeterScalesAllThreeOrdinatesOnTheInverse() {
        CoordinateReferenceSystem crs = new CRSFactory().createFromParameters("geocent-to-meter",
                "+proj=geocent +a=1000 +b=1000 +to_meter=1000 +no_defs");
        Projection p = crs.getProjection();
        ProjCoordinate radians = new ProjCoordinate();
        ProjCoordinate degrees = new ProjCoordinate();

        p.inverseProjectRadians(new ProjCoordinate(0.0, 1.0, 0.0), radians);
        p.inverseProject(new ProjCoordinate(0.0, 1.0, 0.0), degrees);

        assertEquals(Math.PI / 2.0, radians.x, RAD);
        assertEquals(0.0, radians.y, RAD);
        // h is in the CRS's own unit: zero height, so zero either way.
        assertEquals(0.0, radians.z, 1.0e-9);
        assertEquals(90.0, degrees.x, 1.0e-9);
        assertEquals(0.0, degrees.y, 1.0e-9);
    }

    /**
     * {@code fromMetres == 1}, which is every one of the 181 {@code +proj=geocent} definitions in
     * the shipped {@code proj4/nad/epsg} dictionary -- all 181 are {@code +units=m}. This is the
     * reason the defect moved no golden-master row, and it is asserted with a tolerance of
     * <b>exactly zero</b> against the same sphere without the {@code +to_meter}, so that a future
     * change to the scaling cannot perturb the metre case by even one bit.
     */
    @Test
    public void unitsMetreLeavesTheForwardBitIdentical() {
        CRSFactory factory = new CRSFactory();
        Projection bare = factory.createFromParameters("geocent-bare",
                "+proj=geocent +a=1000 +b=1000 +no_defs").getProjection();
        Projection explicitMetres = factory.createFromParameters("geocent-metres",
                "+proj=geocent +a=1000 +b=1000 +units=m +no_defs").getProjection();
        ProjCoordinate fromBare = new ProjCoordinate();
        ProjCoordinate fromExplicit = new ProjCoordinate();

        ProjCoordinate src = new ProjCoordinate(Math.PI / 2.0, 0.0, 0.0);
        bare.projectRadians(src, fromBare);
        explicitMetres.projectRadians(src, fromExplicit);

        // The radius in metres, undivided: the pre-fix answer to the gie row, correct here.
        assertEquals(1000.0, fromBare.y, MM);
        assertEquals(fromBare.x, fromExplicit.x, 0.0);
        assertEquals(fromBare.y, fromExplicit.y, 0.0);
        assertEquals(fromBare.z, fromExplicit.z, 0.0);
    }

    /**
     * A real unit on a real ellipsoid, against PROJ 9.8.1 to twelve places. Note that upstream
     * gives <b>two different answers</b> for what ought to be the same unit, and that this is
     * upstream's doing, not proj4j's:
     *
     * <pre>
     * echo "-134 55 0" | cct -d 12 +proj=geocent +ellps=GRS80 +units=us-ft
     *   -8356380.535945920274  -8653285.358541492373  17064872.441998399794
     * echo "-134 55 0" | cct -d 12 +proj=geocent +ellps=GRS80 +to_meter=0.3048006096012192
     *   -8356380.535945915617  -8653285.358541486785  17064872.441998392344
     * </pre>
     *
     * <p>{@code +units=us-ft} and {@code +to_meter=0.3048006096012192} take two different routes
     * through {@code Proj4Parser} ({@code :344} versus {@code :349}) and <b>do not agree bitwise</b>.
     * They do not agree in PROJ either, and for the same reason: PROJ's {@code pj_units} row for
     * {@code us-ft} carries the factor twice and the two copies differ by three ulps
     * ({@code 9.8.1:src/units.cpp:27}):
     *
     * <pre>
     * {"us-ft", "0.304800609601219", "U.S. Surveyor's Foot", 1200 / 3937.0}
     * </pre>
     *
     * <p><b>{@code +units=} reads the string, not the factor.</b> {@code init.cpp:689} does
     * {@code s = units[i].to_meter} and hands it to {@code pj_strtod}, so {@code +units=us-ft}
     * means exactly {@code strtod("0.304800609601219")} -- which is the literal
     * {@code Units.US_FEET} carries. The {@code 1200 / 3937.0} field is read by
     * {@code +proj=unitconvert} instead ({@code unitconvert.cpp:411,425}), and proj4j tracks that
     * one separately in {@code pipeline/PipelineUnits}. So {@code Units.US_FEET} is <b>right</b>,
     * and it is the {@code +to_meter} spelling that is three ulps off {@code +units=us-ft} --
     * in PROJ and here alike. Anyone tempted to "round-trip fix" {@code Units.US_FEET} to
     * {@code 1200 / 3937.0} should read the block comment above the U.S. units there first: it was
     * measured, and it moves 270 golden rows the wrong way.
     *
     * <p>Each leg is therefore asserted against <b>its own</b> {@code cct} reference, which is the
     * pairing that was wrong here before: the {@code +units=us-ft} reference used to be asserted
     * against the {@code +to_meter} leg, and passed only because 1e-8 is wider than the 4.7e-9 gap
     * between them. The 1e-8 band is ordinary double round-off in the geocentric forward, not a
     * unit discrepancy -- one ulp is 9.3e-10 at 8.4e6 and 3.7e-9 at 1.7e7, and the measured
     * residual against {@code cct} is at most 4 ulps on x and y and exactly zero on z.
     *
     * <p>The 4.7e-9 gap between the two legs is then asserted directly, so the split is pinned as
     * a fact about PROJ rather than left to be rediscovered as a bug. {@code cct} shows the same
     * gap: 4.6566e-9 on x and 7.4506e-9 on z, matching this build bit for bit.
     *
     * <p>The metre control is asserted alongside so that the 3.2808 factor is visible rather than
     * baked into one opaque literal.
     */
    @Test
    public void usSurveyFootMatchesProjCct() {
        CRSFactory factory = new CRSFactory();
        ProjCoordinate viaUnits = new ProjCoordinate();
        ProjCoordinate viaToMeter = new ProjCoordinate();
        ProjCoordinate inMetres = new ProjCoordinate();
        ProjCoordinate src =
                new ProjCoordinate(Math.toRadians(-134.0), Math.toRadians(55.0), 0.0);

        factory.createFromParameters("geocent-usft",
                "+proj=geocent +ellps=GRS80 +units=us-ft +no_defs")
                .getProjection().projectRadians(src, viaUnits);
        factory.createFromParameters("geocent-usft-explicit",
                "+proj=geocent +ellps=GRS80 +to_meter=0.3048006096012192 +no_defs")
                .getProjection().projectRadians(src, viaToMeter);
        factory.createFromParameters("geocent-metres",
                "+proj=geocent +ellps=GRS80 +units=m +no_defs")
                .getProjection().projectRadians(src, inMetres);

        // +units=us-ft against cct's +units=us-ft: PROJ's string column, which is what
        // Units.US_FEET carries. Band is geocent's own round-off, ~4 ulps at these magnitudes.
        assertEquals(-8356380.535945920274, viaUnits.x, 1.0e-8);
        assertEquals(-8653285.358541492373, viaUnits.y, 1.0e-8);
        assertEquals(17064872.441998399794, viaUnits.z, 1.0e-8);

        // +to_meter=0.3048006096012192 against cct's OWN answer for that spelling, which is a
        // different number. Same band, same reason.
        assertEquals(-8356380.535945915617, viaToMeter.x, 1.0e-8);
        assertEquals(-8653285.358541486785, viaToMeter.y, 1.0e-8);
        assertEquals(17064872.441998392344, viaToMeter.z, 1.0e-8);

        // The two legs are deliberately NOT equal, by the 3 ulps between PROJ's two copies of the
        // factor. Pinned so that collapsing them -- in either direction -- fails here.
        assertEquals("x gap matches cct's 4.6566e-9",
                4.6566128730773926E-9, Math.abs(viaUnits.x - viaToMeter.x), 1.0e-9);
        assertEquals("z gap matches cct's 7.4506e-9",
                7.450580596923828E-9, Math.abs(viaUnits.z - viaToMeter.z), 1.0e-9);
        assertTrue("us-ft via +units must be the larger magnitude, dividing by the smaller factor",
                Math.abs(viaUnits.x) > Math.abs(viaToMeter.x));

        assertEquals(-2547029.881416077726, inMetres.x, 1.0e-6);
        assertEquals(-2637526.652336749714, inMetres.y, 1.0e-6);
        assertEquals(5201383.523088155314, inMetres.z, 1.0e-6);
    }

    // -----------------------------------------------------------------------------------------
    // Contract: hasInverse, the name, and the fail-closed guards.
    // -----------------------------------------------------------------------------------------

    /**
     * {@code BasicCoordinateTransform.inverseAvailable} asks {@code hasInverse()} first and then
     * looks for a declared {@code projectInverse(double, double, ProjCoordinate)}. This class has
     * no such method — the two-ordinate signature cannot carry z — so without the declaration the
     * gate rejects every {@code +proj=geocent} CRS as a transformation source.
     */
    @Test
    public void declaresItsInverse() {
        assertTrue(new GeocentProjection().hasInverse());
        assertTrue(wgs84Geocent().hasInverse());
    }

    /** The base {@code toString()} is the literal {@code "None"}, which lands in error messages. */
    @Test
    public void hasAName() {
        assertEquals("Geocentric", new GeocentProjection().toString());
    }

    @Test
    public void forwardRejectsALatitudePastThePole() {
        Projection p = wgs84Geocent();
        try {
            p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(91.0), 0.0),
                    new ProjCoordinate());
            fail("expected a ProjectionException for latitude 91 deg");
        } catch (ProjectionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("latitude"));
        }
    }

    /** {@code fwd_prepare}'s slop band, {@code fwd.cpp:72-77}: clamp, do not reject. */
    @Test
    public void forwardClampsWithinTheSlopBand() {
        Projection p = wgs84Geocent();
        ProjCoordinate atPole = new ProjCoordinate();
        ProjCoordinate justPast = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0, 0.0), atPole);
        p.projectRadians(new ProjCoordinate(0.0, Math.PI / 2.0 + 1.0e-13, 0.0), justPast);

        assertEquals(atPole.x, justPast.x, 0.0);
        assertEquals(atPole.y, justPast.y, 0.0);
        assertEquals(atPole.z, justPast.z, 0.0);
    }

    @Test
    public void forwardRejectsNonFiniteInput() {
        Projection p = wgs84Geocent();
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                p.projectRadians(new ProjCoordinate(0.0, bad, 0.0), new ProjCoordinate());
                fail("expected a ProjectionException for latitude " + bad);
            } catch (ProjectionException expected) {
                // the contract: non-finite in, exception out, never a plausible coordinate
            }
        }
    }

    /** {@code inv_prepare} rejects HUGE_VAL on all three ordinates ({@code inv.cpp:40-45}). */
    @Test
    public void inverseRejectsNonFiniteInput() {
        Projection p = wgs84Geocent();
        double[][] bad = {
                {Double.NaN, 0.0, 0.0},
                {0.0, Double.POSITIVE_INFINITY, 0.0},
                {4517590.0, 0.0, Double.NEGATIVE_INFINITY},
        };
        for (double[] xyz : bad) {
            try {
                p.inverseProjectRadians(new ProjCoordinate(xyz[0], xyz[1], xyz[2]),
                        new ProjCoordinate());
                fail("expected a ProjectionException for (" + xyz[0] + ", " + xyz[1] + ", "
                        + xyz[2] + ")");
            } catch (ProjectionException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("non-finite"));
            }
        }
    }

    /**
     * An absent z is zero, not NaN. {@code ProjCoordinate}'s two-argument constructor leaves z as
     * {@code NaN}, and a geocentric triple with a NaN ordinate is exactly the shape a caller
     * cannot detect.
     */
    @Test
    public void absentHeightIsTreatedAsZero() {
        Projection p = wgs84Geocent();
        ProjCoordinate withoutZ = new ProjCoordinate();
        ProjCoordinate withZeroZ = new ProjCoordinate();

        p.projectRadians(new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5)), withoutZ);
        p.projectRadians(
                new ProjCoordinate(Math.toRadians(9.5), Math.toRadians(55.5), 0.0), withZeroZ);

        assertEquals(withZeroZ.x, withoutZ.x, 0.0);
        assertEquals(withZeroZ.y, withoutZ.y, 0.0);
        assertEquals(withZeroZ.z, withoutZ.z, 0.0);
    }

    /**
     * The ellipsoid may be replaced after construction, so the cached converter has to be keyed on
     * it. Getting this wrong would make the second call answer with the first ellipsoid.
     */
    @Test
    public void aReplacedEllipsoidIsHonoured() {
        GeocentProjection p = new GeocentProjection();
        ProjCoordinate onWgs84 = new ProjCoordinate();
        ProjCoordinate onSphere = new ProjCoordinate();

        p.setEllipsoid(org.locationtech.proj4j.datum.Ellipsoid.WGS84);
        p.initialize();
        p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(45.0), 0.0), onWgs84);

        p.setEllipsoid(new org.locationtech.proj4j.datum.Ellipsoid(
                "test-sphere", 6371000.0, 6371000.0, 0.0, "test sphere"));
        p.initialize();
        p.projectRadians(new ProjCoordinate(0.0, Math.toRadians(45.0), 0.0), onSphere);

        assertEquals(4517590.878848932, onWgs84.x, MM);
        assertEquals(6371000.0 * Math.cos(Math.toRadians(45.0)), onSphere.x, MM);
        assertEquals(6371000.0 * Math.sin(Math.toRadians(45.0)), onSphere.z, MM);
    }
}
