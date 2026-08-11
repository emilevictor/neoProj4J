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
package org.locationtech.proj4j.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DeformationOperator}: a velocity model applied for a number of years, and every
 * way that can fail.
 *
 * <h2>Why the velocity field is constant, and what that buys</h2>
 *
 * <p>{@code +proj=deformation} reads an east/north pair out of a horizontal grid and an up
 * value out of a vertical one, treats all three as millimetres per year, and rotates the
 * triple from the local east-north-up frame into the geocentric cartesian frame the
 * coordinate lives in. The interesting part is that rotation, and a <em>constant</em>
 * field is what makes it checkable by hand: with 30 mm/yr east, &minus;40 mm/yr north and
 * 20 mm/yr up held over 100 years, the point must end up 3 m further east, 4 m further
 * south and 2 m higher, whatever the grid interpolation did.
 *
 * <p>So the assertions convert the result back to latitude, longitude and height with
 * Bowring's formula — written out here, independent of anything under test — and measure
 * the three displacements against the radii of curvature. A transposed or sign-flipped
 * row of the rotation matrix moves a metre-scale displacement into the wrong ordinate and
 * fails by metres, not by rounding.
 *
 * <h2>What else this pins</h2>
 *
 * <ul>
 * <li><b>{@code +dt} and {@code +t_epoch} are two spellings of the same interval.</b> One
 *     hundred years given as {@code +dt=100} and as {@code +t_epoch=1920} with an
 *     observation at 2020 must produce the identical coordinate, and under {@code +dt} the
 *     coordinate's own epoch must never be read at all.</li>
 * <li><b>A coordinate with no epoch under {@code +t_epoch} is a failure.</b> gie writes
 *     "no epoch" as the literal {@code HUGE_VAL}, and treating it as year zero would apply
 *     a two-thousand-year interval and move the point kilometres.</li>
 * <li><b>Every way of having no value is a throw, not a zero.</b> Outside the horizontal
 *     grid, outside the vertical grid, and every surrounding vertical node nodata are three
 *     distinct causes, and none of them may be delivered as an unshifted coordinate.</li>
 * <li><b>An {@code @}-optional vertical grid that is absent leaves the vertical velocity at
 *     zero</b> — PROJ's wart, reproduced deliberately, and the horizontal shift still
 *     applies.</li>
 * <li><b>The inverse's iteration amplifies its residual rather than converging on it.</b>
 *     Also PROJ's, also deliberate, and worth a test of its own because it is the reason a
 *     hundred-year round trip closes to millimetres instead of exactly; see
 *     {@link #theInverseIterationAmplifiesItsResidualInsteadOfConvergingOnIt()}.</li>
 * </ul>
 *
 * <h2>The fixtures</h2>
 *
 * <p>Three grids, built here rather than vendored, because no published velocity model is
 * small or constant. {@code deformation_xy_test} is a 5&times;5 CTABLE V2 grid over
 * 8&ndash;12&deg;E, 48&ndash;52&deg;N whose two channels are the east and north velocities;
 * {@code deformation_up_test.gtx} is a deliberately <em>smaller</em> 3&times;3 GTX over
 * 9&ndash;11&deg;E, 49&ndash;51&deg;N, so that a point can be inside the horizontal grid and
 * outside the vertical one; {@code deformation_nodata_test.gtx} covers the same area
 * entirely with GTX's {@code -88.8888} nodata sentinel.
 */
public class DeformationOperatorTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final double DEG = Math.PI / 180.0;

    /** The grid channels, in millimetres per year, which is the unit both spellings use. */
    private static final float EAST_MM_PER_YEAR = 30.0f;
    private static final float NORTH_MM_PER_YEAR = -40.0f;
    private static final float UP_MM_PER_YEAR = 20.0f;

    /** {@code GTXVerticalShiftGrid::isNodata}'s official sentinel. */
    private static final float GTX_NODATA = -88.88880f;

    private static final String XY = "deformation_xy_test";
    private static final String Z = "deformation_up_test.gtx";
    private static final String Z_NODATA = "deformation_nodata_test.gtx";

    /** The point every happy-path row uses: 10&deg;E, 50&deg;N, on the ellipsoid. */
    private static final double LON = 10.0 * DEG;
    private static final double LAT = 50.0 * DEG;

    private static Path root;
    private static double a;
    private static double es;

    @BeforeClass
    public static void writeTheGrids() throws IOException {
        Ellipsoid grs80 = new Registry().getEllipsoid("GRS80");
        a = grs80.getEquatorRadius();
        es = grs80.getEccentricitySquared();

        root = Files.createTempDirectory("proj4j-deformation");
        Files.write(root.resolve(XY), ctable2(8.0, 48.0, 5, 5, EAST_MM_PER_YEAR, NORTH_MM_PER_YEAR));
        Files.write(root.resolve(Z), gtx(9.0, 49.0, 3, 3, UP_MM_PER_YEAR));
        Files.write(root.resolve(Z_NODATA), gtx(9.0, 49.0, 3, 3, GTX_NODATA));
        ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        GridCache.instance().clear();
        GridCache.vertical().clear();
    }

    @AfterClass
    public static void removeTheGrids() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        GridCache.vertical().clear();
        if (root != null) {
            DirectoryStream<Path> entries = Files.newDirectoryStream(root);
            try {
                for (Path p : entries) {
                    Files.deleteIfExists(p);
                }
            } finally {
                entries.close();
            }
            Files.deleteIfExists(root);
            root = null;
        }
    }

    private static DeformationOperator operator(String parameters) {
        return new DeformationOperator(new Registry(), ProjParams.parse(parameters));
    }

    /** The two-grid spelling over the fixtures, with a hundred-year interval. */
    private static DeformationOperator hundredYears() {
        return operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z
                + " +ellps=GRS80 +dt=100");
    }

    /** The same two grids over an interval of {@code years}. */
    private static DeformationOperator over(double years) {
        return operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z
                + " +ellps=GRS80 +dt=" + years);
    }

    /** How far a round trip over {@code years} misses the point it started from, in metres. */
    private static double roundTripClosure(double years) {
        double[] in = cartesian(LON, LAT, 0.0);
        double[] there = in.clone();
        DeformationOperator op = over(years);
        op.forward(there);
        op.inverse(there);
        return distance(in, there);
    }

    // -------------------------------------------------------------- the happy path

    /**
     * The whole operation, measured as a geodesist would state it: 100 years of
     * (30, &minus;40, 20) mm/yr is 3 m east, 4 m south and 2 m up.
     */
    @Test
    public void aHundredYearsOfVelocityMovesThePointByTheDistanceTheModelStates() {
        double[] in = cartesian(LON, LAT, 0.0);
        double[] out = in.clone();
        hundredYears().forward(out);

        double[] enu = displacementEastNorthUp(in, out);
        assertEquals("30 mm/yr east for 100 years is 3 m east", 3.0, enu[0], 1e-3);
        assertEquals("-40 mm/yr north for 100 years is 4 m south", -4.0, enu[1], 1e-3);
        assertEquals("20 mm/yr up for 100 years is 2 m up", 2.0, enu[2], 1e-3);

        // Rotation-invariant, so it holds whatever the local frame is: the displacement's
        // length is the velocity's length times the interval.
        double expected = 100.0 * Math.sqrt(0.030 * 0.030 + 0.040 * 0.040 + 0.020 * 0.020);
        assertEquals("the displacement's length cannot depend on the frame it is written in",
                expected, distance(in, out), 1e-6);
    }

    /**
     * {@code +t_epoch} takes the interval from the coordinate's own epoch. 2020 observed
     * against a 1920 epoch is the same hundred years as {@code +dt=100}, so the two must
     * agree to the last bit — the interval is the only thing that differs, and here it does
     * not.
     */
    @Test
    public void tEpochAndDtAreTwoSpellingsOfTheSameInterval() {
        double[] byDt = cartesian(LON, LAT, 0.0);
        hundredYears().forward(byDt);

        double[] byEpoch = cartesian(LON, LAT, 0.0);
        byEpoch[3] = 2020.0;
        operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z
                + " +ellps=GRS80 +t_epoch=1920").forward(byEpoch);

        assertEquals(byDt[0], byEpoch[0], 0.0);
        assertEquals(byDt[1], byEpoch[1], 0.0);
        assertEquals(byDt[2], byEpoch[2], 0.0);
    }

    /**
     * With {@code +dt} the coordinate's epoch is never read, so a coordinate that carries
     * no epoch at all is still transformable. Reading it would turn a fixed interval into a
     * data-dependent one.
     */
    @Test
    public void withDtTheCoordinatesOwnEpochIsNeverConsulted() {
        double[] withEpoch = cartesian(LON, LAT, 0.0);
        withEpoch[3] = 1997.0;
        hundredYears().forward(withEpoch);

        double[] withoutEpoch = cartesian(LON, LAT, 0.0);
        withoutEpoch[3] = Double.POSITIVE_INFINITY;   // gie's HUGE_VAL, "no epoch"
        hundredYears().forward(withoutEpoch);

        assertEquals(withEpoch[0], withoutEpoch[0], 0.0);
        assertEquals(withEpoch[1], withoutEpoch[1], 0.0);
        assertEquals(withEpoch[2], withoutEpoch[2], 0.0);
    }

    /**
     * A round trip over one year — a 54 mm displacement — returns to the input to well
     * under a micrometre. The forward is also checked to have actually moved the point, so
     * that a round trip through two no-ops could not pass this.
     *
     * <p>One year rather than a hundred because of the iteration described in
     * {@link #theInverseIterationAmplifiesItsResidualInsteadOfConvergingOnIt()}: this
     * operator's round trip is only exact for small displacements, and the point of this
     * test is the mirror-image arithmetic, not the iteration's numerics.
     */
    @Test
    public void forwardThenInverseReturnsTheInput() {
        double[] in = cartesian(LON, LAT, 0.0);
        double[] roundTripped = in.clone();

        DeformationOperator op = over(1.0);
        op.forward(roundTripped);
        assertTrue("the forward must move the point before the inverse is worth anything",
                distance(in, roundTripped) > 0.05);

        op.inverse(roundTripped);
        assertEquals("x returns", in[0], roundTripped[0], 1e-6);
        assertEquals("y returns", in[1], roundTripped[1], 1e-6);
        assertEquals("z returns", in[2], roundTripped[2], 1e-6);
    }

    /**
     * The inverse on its own, not as the second half of a round trip: it must subtract the
     * same displacement the forward adds. Upstream computes the vertical component of the
     * inverse in one step from the first grid evaluation rather than from the iteration,
     * and this is the assertion that would notice if that were ever "improved" into
     * something that no longer mirrors the forward.
     */
    @Test
    public void theInverseSubtractsTheDisplacementTheForwardAdds() {
        double[] in = cartesian(LON, LAT, 0.0);
        double[] forward = in.clone();
        double[] inverse = in.clone();

        DeformationOperator op = over(1.0);
        op.forward(forward);
        op.inverse(inverse);

        for (int i = 0; i < 3; i++) {
            assertEquals("ordinate " + i + " must move by the same amount in each direction",
                    forward[i] - in[i], in[i] - inverse[i], 1e-6);
        }
    }

    /**
     * The inverse's loop is <em>not</em> a contraction, and this pins how far off it lands.
     *
     * <p>{@code reverseShift} computes a residual and then <b>adds</b> it back
     * ({@code outX += difX}), which is upstream's {@code deformation.cpp:195-250} ported
     * verbatim. Subtracting it would be the Newton step that converges; adding it doubles
     * the residual on every pass. The residual here is second order — it is the change in
     * the rotated east/north/up shift across the displacement itself, of order
     * <i>d</i>&sup2;/<i>R</i> — so it starts vanishingly small, but ten passes multiply it
     * by about 2<sup>10</sup> and the {@code hypot(dif) > 1e-8} guard never lets the loop
     * out early.
     *
     * <p>Measured on these fixtures: a hundred years is a 5.385 m displacement and closes
     * to about 4.2 mm; ten years is 0.539 m and closes to about 42 &micro;m. The error
     * therefore grows with the <em>square</em> of the interval, which is the signature of
     * the amplified second-order term rather than of ordinary rounding.
     *
     * <p>This is asserted rather than fixed because the port is deliberately faithful:
     * changing the sign would move every expected value in {@code deformation.gie}. If
     * upstream ever corrects it, this test fails and says why — which is the intent.
     */
    @Test
    public void theInverseIterationAmplifiesItsResidualInsteadOfConvergingOnIt() {
        double overACentury = roundTripClosure(100.0);
        double overADecade = roundTripClosure(10.0);

        assertTrue("a hundred-year round trip must not be claimed as exact; upstream's loop "
                + "leaves millimetres behind, and a test that expected zero here would be "
                + "pinning arithmetic this operator does not do. Closure was " + overACentury,
                overACentury > 1e-4);
        assertTrue("but millimetres is the whole of it: anything larger means the iteration "
                + "has started diverging outright. Closure was " + overACentury,
                overACentury < 1e-2);

        double ratio = overACentury / overADecade;
        assertTrue("the closure error must grow with the square of the displacement — a "
                + "second-order residual amplified by a fixed number of passes. Ten times "
                + "the interval gave " + ratio + " times the error, not about 100, so the "
                + "iteration is no longer behaving as described above",
                ratio > 50.0 && ratio < 200.0);
    }

    /** {@code P->left} and {@code P->right} are both cartesian, and the step is invertible. */
    @Test
    public void bothSidesAreCartesianAndNoNeighbourCanChangeThat() {
        DeformationOperator op = hundredYears();
        assertEquals(GieIoUnits.CARTESIAN, op.declaredLeft());
        assertEquals(GieIoUnits.CARTESIAN, op.declaredRight());

        op.overrideUnits(GieIoUnits.RADIANS, GieIoUnits.RADIANS);

        assertEquals("neither side is WHATEVER, so propagation must never reach this operator",
                GieIoUnits.CARTESIAN, op.declaredLeft());
        assertEquals(GieIoUnits.CARTESIAN, op.declaredRight());
        assertTrue(op.hasInverse());
    }

    /** The description is what a pipeline dump shows; it must name both grids and the interval. */
    @Test
    public void theDescriptionNamesBothGridsAndTheInterval() {
        String d = hundredYears().description();
        assertTrue(d, d.contains("xy_grids=" + XY));
        assertTrue(d, d.contains("z_grids=" + Z));
        assertTrue(d, d.contains("dt=100"));
        assertTrue(hundredYears().toString().contains(d));

        String e = operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z
                + " +ellps=GRS80 +t_epoch=1920").description();
        assertTrue("the t_epoch spelling must be reported as itself, not as a dt: " + e,
                e.contains("t_epoch=1920"));
    }

    // -------------------------------------------------- the coordinate-level failures

    /**
     * gie writes "this coordinate has no epoch" as the literal {@code HUGE_VAL}, and
     * {@code +t_epoch} cannot work without one. Treating the sentinel as a year would apply
     * an interval of thousands of years.
     */
    @Test
    public void underTEpochACoordinateWithNoEpochIsMissingTime() {
        assertMissingTime(Double.POSITIVE_INFINITY);
        assertMissingTime(Double.NaN);
    }

    private void assertMissingTime(double t) {
        double[] c = cartesian(LON, LAT, 0.0);
        c[3] = t;
        try {
            operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z
                    + " +ellps=GRS80 +t_epoch=1920").forward(c);
            fail("t=" + t + " is not an epoch, and +t_epoch needs one");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.MISSING_TIME, e.cause());
            assertTrue("the message must name the parameter that needs the epoch: "
                    + e.getMessage(), e.getMessage().contains("t_epoch=1920"));
            assertTrue("and must say what is missing: " + e.getMessage(),
                    e.getMessage().contains("epoch"));
        }
    }

    /** Outside the horizontal grid there is no velocity, and no velocity is not zero velocity. */
    @Test
    public void aPointOutsideTheHorizontalGridIsRefused() {
        double[] c = cartesian(20.0 * DEG, 60.0 * DEG, 0.0);
        try {
            hundredYears().forward(c);
            fail("(20, 60) is outside the 8-12E, 48-52N fixture and must not be shifted by zero");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
            assertTrue("the message must name the grid list: " + e.getMessage(),
                    e.getMessage().contains(XY));
            assertTrue("and must give the position in degrees: " + e.getMessage(),
                    e.getMessage().contains("outside every grid"));
        }
    }

    /**
     * The vertical fixture is deliberately smaller than the horizontal one, so this point
     * has an east/north velocity and no up velocity. Filling the missing channel with zero
     * would be a plausible-looking answer that is wrong by the whole vertical rate.
     */
    @Test
    public void aPointInsideTheHorizontalGridButOutsideTheVerticalOneIsRefused() {
        double[] c = cartesian(11.5 * DEG, 51.5 * DEG, 0.0);
        try {
            hundredYears().forward(c);
            fail("(11.5, 51.5) is inside +xy_grids and outside +z_grids; the vertical velocity "
                    + "is unknown, not zero");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
            assertTrue("the message must say which of the two grid lists failed: "
                    + e.getMessage(), e.getMessage().contains("+z_grids"));
            assertTrue("and must give the position in degrees: " + e.getMessage(),
                    e.getMessage().contains("11.5"));
        }
    }

    /**
     * Nodata is its own cause, distinct from outside-the-grid: the point is inside the
     * model's declared area but the model has nothing to say there, which is a different
     * thing for a caller to act on.
     */
    @Test
    public void aVerticalGridThatIsAllNodataIsReportedAsNodata() {
        double[] c = cartesian(LON, LAT, 0.0);
        try {
            operator("+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z_NODATA
                    + " +ellps=GRS80 +dt=100").forward(c);
            fail("every surrounding node is the GTX nodata sentinel; there is no value here");
        } catch (CrsTransformException e) {
            assertEquals("nodata inside the extent is not the same failure as being outside it",
                    ErrorCause.GRID_NODATA, e.cause());
            assertTrue("the message must name the grid: " + e.getMessage(),
                    e.getMessage().contains(Z_NODATA));
            assertTrue(e.getMessage(), e.getMessage().contains("is nodata"));
        }
    }

    /**
     * PROJ's {@code @} prefix means "use it if it is there". An absent optional vertical
     * grid leaves the list empty, and an empty list is a vertical velocity of zero — the
     * horizontal half of the model still applies. Reproduced deliberately; the point of the
     * test is that the horizontal shift is not lost along with the vertical one.
     */
    @Test
    public void anAbsentOptionalVerticalGridLeavesTheVerticalVelocityAtZero() {
        double[] in = cartesian(LON, LAT, 0.0);
        double[] out = in.clone();
        operator("+proj=deformation +xy_grids=" + XY + " +z_grids=@absent_test.gtx"
                + " +ellps=GRS80 +dt=100").forward(out);

        double[] enu = displacementEastNorthUp(in, out);
        assertEquals("the horizontal half of the model still applies", 3.0, enu[0], 1e-3);
        assertEquals(-4.0, enu[1], 1e-3);
        assertEquals("an empty +z_grids list is a vertical velocity of zero", 0.0, enu[2], 1e-3);
    }

    /**
     * A non-finite input has no grid cell. PROJ lets the non-finiteness travel rather than
     * raising, so that a NaN in is a NaN out instead of an exception from deep inside a
     * per-row loop.
     */
    @Test
    public void aNonFiniteCoordinateTravelsRatherThanThrowing() {
        double[] c = {Double.NaN, 1.0, 2.0, 0.0};
        hundredYears().forward(c);
        assertTrue("x", Double.isNaN(c[0]));
        assertTrue("y", Double.isNaN(c[1]));
        assertTrue("z", Double.isNaN(c[2]));
    }

    // ------------------------------------------------- the construction-time failures

    /** {@code deformation.cpp:352-357}: {@code +grids} alone, or <em>both</em> of the pair. */
    @Test
    public void oneHalfOfTheGridPairIsNotEnough() {
        assertRefused("+proj=deformation +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG, "Either +grids or (+xy_grids and +z_grids)");
        assertRefused("+proj=deformation +xy_grids=" + XY + " +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG, "Either +grids or (+xy_grids and +z_grids)");
        assertRefused("+proj=deformation +z_grids=" + Z + " +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG, "Either +grids or (+xy_grids and +z_grids)");
    }

    /**
     * The single-file spelling is a three-channel Geodetic TIFF Grid, which proj4j cannot
     * read. Refused with the reason and the alternative, rather than ignored — an ignored
     * {@code +grids} would leave the operator looking configured and doing nothing.
     */
    @Test
    public void theSingleFileGeoTiffSpellingIsRefusedWithTheReasonAndTheAlternative() {
        assertRefused("+proj=deformation +grids=model.tif +ellps=GRS80 +dt=1",
                PipelineErrorCode.NOT_IMPLEMENTED_HERE, "no GeoTIFF grid reader");
        assertRefused("+proj=deformation +grids=model.tif +ellps=GRS80 +dt=1",
                PipelineErrorCode.NOT_IMPLEMENTED_HERE, "+xy_grids=");
    }

    /** An empty value is not a grid list; it must not resolve to "no grids, shift nothing". */
    @Test
    public void anEmptyVerticalGridListIsRefused() {
        assertRefused("+proj=deformation +xy_grids=" + XY + " +z_grids= +ellps=GRS80 +dt=1",
                PipelineErrorCode.MISSING_ARG, "+z_grids parameter missing.");
    }

    /** A required (non-{@code @}) vertical grid that is absent is fatal, and says so. */
    @Test
    public void aRequiredVerticalGridThatCannotBeFoundIsFatal() {
        assertRefused("+proj=deformation +xy_grids=" + XY
                        + " +z_grids=absent_test.gtx +ellps=GRS80 +dt=1",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find requested z_grid(s)");
    }

    /** A required horizontal grid that is absent is fatal for the same reason. */
    @Test
    public void aRequiredHorizontalGridThatCannotBeFoundIsFatal() {
        assertRefused("+proj=deformation +xy_grids=absent_test +z_grids=" + Z
                        + " +ellps=GRS80 +dt=1",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
    }

    /**
     * {@code deformation.cpp:394-411}. Exactly one of {@code +dt} and {@code +t_epoch}, and
     * {@code +t_obs} is a hard error with a migration message rather than a synonym for
     * {@code +dt} — one of this project's "implement from the code, not the docs" entries.
     * These are only reachable with grids that resolve, because the grid list is opened
     * first.
     */
    @Test
    public void exactlyOneOfDtAndTEpochIsRequired() {
        String grids = "+proj=deformation +xy_grids=" + XY + " +z_grids=" + Z + " +ellps=GRS80";
        assertRefused(grids, PipelineErrorCode.MISSING_ARG,
                "either +dt or +t_epoch needs to be set.");
        assertRefused(grids + " +dt=1 +t_epoch=2000", PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS,
                "+dt or +t_epoch are mutually exclusive.");
        assertRefused(grids + " +t_obs=2000", PipelineErrorCode.MISSING_ARG,
                "+t_obs parameter is deprecated. Use +dt instead.");
    }

    private static void assertRefused(String definition, PipelineErrorCode expected,
                                      String fragment) {
        try {
            operator(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, expected, e.code());
            assertTrue("message was: " + e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    // ------------------------------------------------------- independent geodesy

    /**
     * Geodetic to geocentric cartesian, the textbook expression. Used to build the inputs,
     * so that no test starts from a number the operator produced.
     */
    private static double[] cartesian(double lam, double phi, double h) {
        double sinPhi = Math.sin(phi);
        double n = a / Math.sqrt(1.0 - es * sinPhi * sinPhi);
        return new double[] {
            (n + h) * Math.cos(phi) * Math.cos(lam),
            (n + h) * Math.cos(phi) * Math.sin(lam),
            (n * (1.0 - es) + h) * sinPhi,
            0.0,
        };
    }

    /**
     * Geocentric cartesian to {@code {lam, phi, h}} by Bowring's 1976 formula, which is
     * accurate to well under a micrometre for a point near the ellipsoid — three orders
     * below the millimetre tolerances above. Written out here so that the check on the
     * east-north-up rotation does not go through the rotation's own inverse.
     */
    private static double[] geodetic(double[] xyz) {
        double b = a * Math.sqrt(1.0 - es);
        double ep2 = es / (1.0 - es);
        double p = Math.hypot(xyz[0], xyz[1]);
        double theta = Math.atan2(xyz[2] * a, p * b);
        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);
        double phi = Math.atan2(xyz[2] + ep2 * b * sinTheta * sinTheta * sinTheta,
                p - es * a * cosTheta * cosTheta * cosTheta);
        double sinPhi = Math.sin(phi);
        double n = a / Math.sqrt(1.0 - es * sinPhi * sinPhi);
        return new double[] {Math.atan2(xyz[1], xyz[0]), phi, p / Math.cos(phi) - n};
    }

    /**
     * The displacement between two cartesian points, expressed in metres east, north and
     * up at the first of them, via the radii of curvature rather than via the operator's
     * own rotation matrix.
     *
     * @return {@code {east, north, up}} in metres
     */
    private static double[] displacementEastNorthUp(double[] from, double[] to) {
        double[] g0 = geodetic(from);
        double[] g1 = geodetic(to);
        double sinPhi = Math.sin(g0[1]);
        double w = 1.0 - es * sinPhi * sinPhi;
        double primeVertical = a / Math.sqrt(w);
        double meridional = a * (1.0 - es) / (w * Math.sqrt(w));
        return new double[] {
            (g1[0] - g0[0]) * primeVertical * Math.cos(g0[1]),
            (g1[1] - g0[1]) * meridional,
            g1[2] - g0[2],
        };
    }

    private static double distance(double[] p, double[] q) {
        double dx = q[0] - p[0];
        double dy = q[1] - p[1];
        double dz = q[2] - p[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- the fixtures

    /**
     * A CTABLE V2 horizontal grid with every node carrying the same pair. The first channel
     * is the one {@code pj_hgrid_value} returns as {@code dlam} and
     * {@code +proj=deformation} reads as the east velocity; the second is the north one.
     *
     * @param west    lower-left longitude, degrees
     * @param south   lower-left latitude, degrees
     * @param columns nodes east-west, at one degree spacing
     * @param rows    nodes south-north, at one degree spacing
     */
    private static byte[] ctable2(double west, double south, int columns, int rows,
                                  float channel1, float channel2) {
        byte[] b = new byte[160 + columns * rows * 8];
        System.arraycopy("CTABLE V2.0     ".getBytes(ASCII), 0, b, 0, 16);
        System.arraycopy("proj4j deformation test velocity grid\n".getBytes(ASCII), 0, b, 16, 38);

        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(96, west * DEG);
        buf.putDouble(104, south * DEG);
        buf.putDouble(112, 1.0 * DEG);
        buf.putDouble(120, 1.0 * DEG);
        buf.putInt(128, columns);
        buf.putInt(132, rows);
        for (int i = 0; i < columns * rows; i++) {
            buf.putFloat(160 + i * 8, channel1);
            buf.putFloat(160 + i * 8 + 4, channel2);
        }
        return b;
    }

    /**
     * A GTX vertical grid with every node carrying the same value: a 40-byte big-endian
     * header of {@code (yorigin, xorigin, ystep, xstep)} in degrees then {@code (rows,
     * columns)}, followed by big-endian floats south to north and west to east.
     */
    private static byte[] gtx(double west, double south, int columns, int rows, float value) {
        byte[] b = new byte[40 + columns * rows * 4];
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        buf.putDouble(0, south);
        buf.putDouble(8, west);
        buf.putDouble(16, 1.0);
        buf.putDouble(24, 1.0);
        buf.putInt(32, rows);
        buf.putInt(36, columns);
        for (int i = 0; i < columns * rows; i++) {
            buf.putFloat(40 + i * 4, value);
        }
        return b;
    }
}
