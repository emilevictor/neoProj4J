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

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=gridshift}, checked against numbers PROJ 9.8.1 produced.
 *
 * <h2>Where the expected values come from</h2>
 *
 * <p>Every coordinate assertion here is a row of upstream's own {@code test/gie/gridshift.gie},
 * which the conformance module also runs whole. Duplicating a handful here is deliberate: these run
 * in the core module's ordinary test cycle, so a change to the kernel is caught by
 * {@code mvn test} rather than only by the conformance profile, and each one is annotated with what
 * it is actually exercising. The tolerances are the ones the corpus states for the block the row
 * came from, converted to a comparison in the unit this test works in.
 *
 * <p>The grid files are vendored under {@code core/src/test/resources/proj4j-data/grids/} and named
 * without a directory, because that is what the classpath resolver expects; the conformance corpus
 * names the same files as {@code tests/<name>}.
 *
 * <h2>What each test is for</h2>
 *
 * <p>Between them these cover every branch of the operator that the arithmetic can reach: a
 * horizontal grid with subgrids, a height-only grid, a Geographic 3D grid read biquadratically, the
 * split horizontal-plus-height pair that needs a second pass, a grid whose second image sits past
 * the antimeridian, a projected grid with a constant offset on each band, both interpolation
 * choices stated explicitly, {@code +no_z_transform}, the non-iterating biquadratic inverse, and
 * every construction-time refusal.
 */
public class GridShiftOperatorTest {

    /** Degrees to radians; the operator's angular side is radians. */
    private static final double D = Math.PI / 180;

    /** One millimetre, as a latitude difference in degrees. Roughly 1/111320000 of a degree. */
    private static final double MM_IN_DEGREES = 1.0 / 111319492.664;

    private final PipelineFactory factory = new PipelineFactory();

    private static final String CONUS =
            "us_noaa_nadcon5_nad83_2007_nad83_2011_conus_extract.tif";
    private static final String ALASKA =
            "us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif";
    private static final String SAN_FRANCISCO =
            "us_noaa_nadcon5_nad83_1986_nad83_harn_conus_extract_sanfrancisco.tif";

    // ============================================================== horizontal offset grids

    /**
     * {@code gridshift.gie:22-24}. A two-band {@code HORIZONTAL_OFFSET} grid with subgrids, so this
     * also checks that the typed lookup descends into the child that actually covers the point:
     * {@code ALbanff} inside {@code CAwest}. The parent's own shift at this point is different, so a
     * lookup that stopped at the root would be visibly wrong rather than marginally so.
     */
    @Test
    public void aHorizontalGridShiftsBothOrdinatesAndDescendsIntoASubgrid() {
        assertForward("+proj=gridshift +grids=test_hgrid_with_subgrid.tif",
                -115.5416667, 51.1666667, 0,
                -115.5427092888, 51.1666899972, 0, 0.1);
    }

    /** {@code gridshift.gie:27-29}, in the other subgrid — {@code ONtronto}, inside {@code CAeast}. */
    @Test
    public void theSecondSubgridIsFoundToo() {
        assertForward("+proj=gridshift +grids=test_hgrid_with_subgrid.tif",
                -80.5041667, 44.5458333, 0,
                -80.50401615833, 44.5458827236, 0, 0.1);
    }

    /**
     * {@code gridshift.gie:18-19}. Outside every grid in the file, which is a coordinate failure and
     * not a definition failure — the row's errno is {@code coord_transfm_outside_grid}.
     */
    @Test
    public void aPointOutsideEveryGridIsACoordinateFailure() {
        assertForwardFails("+proj=gridshift +grids=test_hgrid_with_subgrid.tif", 179.799, 54.5, 0);
    }

    /** A horizontal grid must round-trip, which is the iterating inverse doing its job. */
    @Test
    public void theBilinearInverseIteratesBackToTheStart() {
        assertRoundTrip("+proj=gridshift +grids=test_hgrid_with_subgrid.tif",
                -115.5416667, 51.1666667, 0, 1e-6);
    }

    // ================================================================== height-only grids

    /**
     * {@code gridshift.gie:99-100}. A {@code VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL} grid whose band
     * is described as {@code hydroid_height}. Only z moves, and the inverse is a subtraction rather
     * than an iteration, because there is no horizontal grid anywhere in the set.
     */
    @Test
    public void aHeightOnlyGridMovesOnlyZ() {
        assertForward("+proj=gridshift +grids=test_hydroid_height.tif",
                2, 49, 0,
                2, 49, 44.643493652, 0.1);
    }

    /**
     * {@code gridshift.gie:82-84}. The grid's second image begins at longitude 200.625 degrees east,
     * so a point given as {@code -179.8166667} is only inside it after the wrap in
     * {@code normalizeX} has added a full turn. Without that wrap this is a coordinate failure, so
     * the test discriminates: the same file refuses a point 0.0007 of a degree further west.
     */
    @Test
    public void aGridPastTheAntimeridianIsReachedThroughTheLongitudeWrap() {
        String def = "+proj=gridshift +grids=us_noaa_geoid06_ak_subset_at_antimeridian.tif";
        assertForward(def, -179.8166667, 54.5, 0, -179.8166667, 54.5, -3.1933, 1.0);
        assertForward(def, 180.1833333, 54.5, 0, 180.1833333, 54.5, -3.1933, 1.0);
        // ... and just outside, in both spellings of the same place.
        assertForwardFails(def, 180.184, 54.5, 0);
        assertForwardFails(def, -179.816, 54.5, 0);
    }

    /** {@code gridshift.gie:67-69}: either spelling of the antimeridian itself reads the same node. */
    @Test
    public void bothSpellingsOfTheAntimeridianAgree() {
        String def = "+proj=gridshift +grids=us_noaa_geoid06_ak_subset_at_antimeridian.tif";
        assertForward(def, 179.999999, 54.5, 0, 179.999999, 54.5, -2.2872, 1.0);
        assertForward(def, -179.999999, 54.5, 0, -179.999999, 54.5, -2.2872, 1.0);
    }

    // =============================================================== geographic 3D offsets

    /**
     * {@code gridshift.gie:115-117}. A single {@code GEOGRAPHIC_3D_OFFSET} grid, at a point that is
     * exactly on a node, so the interpolation weights are degenerate and any window-indexing error
     * shows up as a clean miss rather than a small one. The file asks for biquadratic itself, in its
     * {@code interpolation_method} metadata, without {@code +interpolation} on the command line.
     */
    @Test
    public void aGeographic3dGridMovesAllThreeOrdinatesAtANode() {
        assertForward("+proj=gridshift +grids=" + CONUS,
                -95.5, 37.0, 10.0,
                -95.4999998219, 37.0000000147, 9.984, 1.0);
    }

    /**
     * {@code gridshift.gie:122-131}. Two points a ten-billionth of a degree apart, deliberately
     * chosen by upstream to sit either side of the half-cell line where the biquadratic window
     * shifts. Both must land within a millimetre of the same answer: if the window shift is
     * mis-signed, one of the two moves and the other does not.
     */
    @Test
    public void theBiquadraticWindowShiftIsContinuousAcrossTheHalfCellLine() {
        String def = "+proj=gridshift +grids=" + CONUS;
        assertForward(def, -95.4916666666, 37.0083333333, 10.0,
                -95.4916664889, 37.0083333484, 9.984, 1.0);
        assertForward(def, -95.4916666667, 37.0083333334, 10.0,
                -95.4916664890, 37.0083333485, 9.984, 1.0);
    }

    /**
     * {@code gridshift.gie:136-142}. The extreme corners of a truncated grid, where the window shift
     * has to pull back from the edge — {@code indX + 2 == width} on one side, {@code indX == 0} on
     * the other. A three-by-three read that ran off either end would throw rather than answer.
     */
    @Test
    public void bothCornersOfATruncatedGridAreReadable() {
        String def = "+proj=gridshift +grids=" + CONUS;
        assertForward(def, -95.416667, 37.083333, 0.0,
                -95.4166668251, 37.0833330159, -0.0157, 1.0);
        assertForward(def, -95.58333, 36.91667, 0.0,
                -95.5833298166, 36.9166700108, -0.0157, 1.0);
    }

    /**
     * {@code gridshift.gie:159-166}. The Alaska extract is the split case: a
     * {@code HORIZONTAL_OFFSET} image and an {@code ELLIPSOIDAL_HEIGHT_OFFSET} image in one file. The
     * horizontal pass runs first and the height pass is then evaluated at the <em>shifted</em>
     * position, which is why {@code apply} cannot simply read both at once.
     */
    @Test
    public void aSplitHorizontalAndHeightFileTakesTwoPasses() {
        String def = "+proj=gridshift +grids=" + ALASKA;
        assertForward(def, -158.0, 61.5, 10.0, -157.9999996115, 61.499999564, 9.987, 1.0);
        assertForward(def, -158.1, 61.51, 10.0, -158.0999996011, 61.5099995458, 9.987, 1.0);
    }

    /**
     * {@code gridshift.gie:181-188}. Two files at once, covering different parts of the world. The
     * type-and-extent lookup has to pick the right set per coordinate; a lookup that always used the
     * first would refuse the Alaskan point.
     */
    @Test
    public void twoFilesAreSearchedInOrderForEachCoordinate() {
        String def = "+proj=gridshift +grids=" + CONUS + "," + ALASKA;
        assertForward(def, -95.5, 37.0, 10.0, -95.4999998219, 37.0000000147, 9.984, 1.0);
        assertForward(def, -158.0, 61.5, 10.0, -157.9999996115, 61.499999564, 9.987, 1.0);
    }

    // ================================================================== interpolation choice

    /**
     * {@code gridshift.gie:214-224}. The same point through both kernels. They agree to about a
     * micrometre in z here, which is why the second assertion below matters more than these two: it
     * shows the two are not the same code path.
     */
    @Test
    public void bothInterpolationsAreAccepted() {
        assertForward("+proj=gridshift +grids=" + CONUS + " +interpolation=biquadratic",
                -95.4916666666, 37.0083333333, 10.0,
                -95.49166648893, 37.00833334837, 9.984340, 0.005);
        assertForward("+proj=gridshift +grids=" + CONUS + " +interpolation=bilinear",
                -95.4916666666, 37.0083333333, 10.0,
                -95.49166648893, 37.00833334838, 9.984341, 0.001);
    }

    /**
     * The positive control for the pair above. If {@code +interpolation} were being parsed and then
     * ignored, both blocks would still pass at their stated tolerances, because upstream's own
     * expected values differ only in the last digit. So this asserts they differ at all — measured
     * at about 0.7 micrometres in z — which is the only thing that distinguishes "two kernels" from
     * "one kernel and a parameter that does nothing".
     */
    @Test
    public void theTwoInterpolationsDoNotProduceTheSameNumber() {
        double[] biquadratic = forward("+proj=gridshift +grids=" + CONUS
                + " +interpolation=biquadratic", -95.4916666666, 37.0083333333, 10.0);
        double[] bilinear = forward("+proj=gridshift +grids=" + CONUS
                + " +interpolation=bilinear", -95.4916666666, 37.0083333333, 10.0);
        assertNotEquals("the two kernels must not be the same code path",
                biquadratic[2], bilinear[2], 0.0);
        assertEquals("...but they must agree to a micrometre at this point",
                biquadratic[2], bilinear[2], 1e-5);
    }

    /**
     * {@code gridshift.gie:237-238}, and the one row in the file that runs in reverse. The
     * biquadratic inverse deliberately does not iterate: this point is close enough to a half-cell
     * line that an iterating inverse fails to converge, which is upstream's own recorded reason
     * ({@code gridshift.cpp:649-659}). The tolerance is the corpus's 0.005 mm.
     */
    @Test
    public void theBiquadraticInverseAnswersInOnePassNearAWindowBoundary() {
        assertInverse("+proj=gridshift +grids=" + SAN_FRANCISCO + " +interpolation=biquadratic",
                -122.4250009683, 37.8286740788, 0,
                -122.4249999391, 37.8286728006, 0.005);
    }

    // ==================================================================== +no_z_transform

    /** {@code gridshift.gie:201-202}: x and y move as before, z is left exactly alone. */
    @Test
    public void noZTransformLeavesTheHeightUntouched() {
        double[] out = forward("+proj=gridshift +grids=" + CONUS + " +no_z_transform",
                -95.5, 37.0, 10.0);
        assertEquals(-95.4999998219, out[0] / D, MM_IN_DEGREES);
        assertEquals(37.0000000147, out[1] / D, MM_IN_DEGREES);
        assertEquals("z must be bit-identical, not merely close", 10.0, out[2], 0.0);
    }

    // ==================================================================== projected grids

    /**
     * {@code gridshift.gie:250-251}. A grid referenced in a projected CRS, whose two bands each carry
     * a {@code constant_offset} of -5,000,000 metres. The offset is added <em>after</em> the grid
     * shift going forward and subtracted <em>before</em> it coming back; getting that order wrong
     * misses by five million metres, so this is not a subtle test.
     */
    @Test
    public void aProjectedGridAppliesItsConstantOffsetAfterTheShift() {
        double[] out = forward("+proj=gridshift +grids=test_gridshift_projected.tif",
                -598000.0, -1160020.0, 0.0);
        assertEquals(-5597999.885, out[0], 5e-4);
        assertEquals(-6160019.978, out[1], 5e-4);
        assertEquals(0.0, out[2], 5e-4);
    }

    /** The reverse direction of the same, which is where the asymmetric order earns its keep. */
    @Test
    public void theProjectedGridRoundTripsThroughItsConstantOffset() {
        Pipeline p = factory.create("+proj=gridshift +grids=test_gridshift_projected.tif");
        double[] there = p.forward(new double[] {-598000.0, -1160020.0, 0.0, 0.0});
        double[] back = p.inverse(there);
        assertEquals(-598000.0, back[0], 5e-4);
        assertEquals(-1160020.0, back[1], 5e-4);
    }

    /**
     * A projected grid makes the operation's declared unit domain
     * {@link GieIoUnits#PROJECTED} rather than {@link GieIoUnits#RADIANS}, on both sides. That is not
     * cosmetic: a conformance comparator picks its distance metric from it, and a step's neighbours
     * in a pipeline are checked against it.
     */
    @Test
    public void theDeclaredUnitsFollowTheGrid() {
        Pipeline projected = factory.create("+proj=gridshift +grids=test_gridshift_projected.tif");
        assertEquals(GieIoUnits.PROJECTED, projected.left());
        assertEquals(GieIoUnits.PROJECTED, projected.right());

        Pipeline geographic = factory.create("+proj=gridshift +grids=" + CONUS);
        assertEquals(GieIoUnits.RADIANS, geographic.left());
        assertEquals(GieIoUnits.RADIANS, geographic.right());
    }

    /**
     * {@code +coord_type} is checked against the grid rather than believed. Both spellings are
     * accepted when they agree with the file and refused when they do not, and — this is upstream's
     * choice, not a transcription slip — the refusal is a <em>missing argument</em> error rather than
     * an illegal value ({@code gridshift.cpp:976-1005}).
     */
    @Test
    public void coordTypeMustAgreeWithTheGrid() {
        factory.create("+proj=gridshift +grids=" + CONUS + " +coord_type=geographic");
        factory.create("+proj=gridshift +grids=test_gridshift_projected.tif"
                + " +coord_type=projected");
        assertRejected("+proj=gridshift +grids=" + CONUS + " +coord_type=projected",
                PipelineErrorCode.MISSING_ARG, "known to not be projected");
        assertRejected("+proj=gridshift +grids=test_gridshift_projected.tif"
                        + " +coord_type=geographic",
                PipelineErrorCode.MISSING_ARG, "known to be projected");
        assertRejected("+proj=gridshift +grids=" + CONUS + " +coord_type=nonsense",
                PipelineErrorCode.MISSING_ARG, "Unsupported value for +coord_type");
    }

    // ================================================================ construction refusals

    /** {@code gridshift.gie:278-280}. No {@code +grids} at all. */
    @Test
    public void gridsIsRequired() {
        assertRejected("+proj=gridshift", PipelineErrorCode.MISSING_ARG,
                "+grids parameter missing");
    }

    /** {@code gridshift.gie:272-274}. */
    @Test
    public void anUnknownInterpolationIsRefused() {
        assertRejected("+proj=gridshift +grids=" + CONUS + " +interpolation=invalid",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "Unsupported value for +interpolation");
    }

    /** {@code gridshift.gie:290-292}: a name nothing resolves. */
    @Test
    public void anAbsentGridIsAFileError() {
        assertRejected("+proj=gridshift +grids=i_do_not_exist.tif",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
    }

    /** {@code gridshift.gie:284-286}: a file that resolves but cannot be read. */
    @Test
    public void anUnreadableGridIsTheSameKindOfFileError() {
        assertRejected("+proj=gridshift +grids=test_vgrid_unsupported_byte.tif",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
    }

    /**
     * {@code +grids=null} is a working no-op for {@code hgridshift} and {@code vgridshift} and a
     * <em>definition error</em> here, because the null grid declares no {@code TYPE} and
     * {@code checkGridTypes} refuses a set it cannot classify. Confirmed against 9.8.1's own
     * {@code cct}, which answers {@code 1029 gridshift: Missing TYPE metadata item in grid(s).}
     */
    @Test
    public void theNullGridIsRefusedByThisOperatorAloneAmongTheGridShifts() {
        assertRejected("+proj=gridshift +grids=null",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "Missing TYPE metadata item");
    }

    /**
     * A grid list of nothing but missing optional files builds — the {@code @} prefix turns a missing
     * file into a shorter list — and then refuses every coordinate. Upstream asserts this cannot
     * happen and, in a release build, does exactly this; measured on 9.8.1's {@code cct}, which
     * answers {@code TRANSFORMATION ERROR ... (Coordinate to transform falls outside grid)}.
     */
    @Test
    public void anAllOptionalMissingListBuildsAndThenRefusesEveryCoordinate() {
        Pipeline p = factory.create("+proj=gridshift +grids=@i_do_not_exist.tif");
        assertEquals(GieIoUnits.RADIANS, p.left());
        try {
            p.forward(new double[] {2 * D, 49 * D, 0, 0});
            fail("expected a coordinate failure");
        } catch (CrsTransformException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("outside"));
        }
    }

    // ================================================================== the kernel's parabola

    /**
     * {@code quadraticInterpol} is the whole of the biquadratic kernel's arithmetic, so it is worth
     * pinning against parabolas whose values can be worked out by hand rather than only through a
     * grid file. The three samples are {@code f(0)}, {@code f(1)} and {@code f(2)}, and the answer
     * has to be exact at those three points and on the parabola in between.
     */
    @Test
    public void theParabolaPassesThroughAllThreeSamples() {
        // f(x) = x*x + 3, sampled at 0, 1, 2 -> 3, 4, 7.
        assertEquals(3.0, GenericShiftKernel.quadraticInterpol(0.0, 3, 4, 7), 0.0);
        assertEquals(4.0, GenericShiftKernel.quadraticInterpol(1.0, 3, 4, 7), 0.0);
        assertEquals(7.0, GenericShiftKernel.quadraticInterpol(2.0, 3, 4, 7), 0.0);
        // and it really is that parabola in between, not a pair of straight lines
        assertEquals(3.25, GenericShiftKernel.quadraticInterpol(0.5, 3, 4, 7), 1e-15);
        assertEquals(5.25, GenericShiftKernel.quadraticInterpol(1.5, 3, 4, 7), 1e-15);
    }

    /**
     * Three collinear samples must give back the straight line, at every point and outside the
     * window as well. This is what makes the biquadratic kernel agree with the bilinear one on a
     * grid whose shifts happen to vary linearly.
     */
    @Test
    public void collinearSamplesGiveBackTheStraightLine() {
        for (double x = -1.0; x <= 3.0; x += 0.25) {
            assertEquals("at " + x, 10 + 2 * x,
                    GenericShiftKernel.quadraticInterpol(x, 10, 12, 14), 1e-13);
        }
    }

    // ============================================================================ helpers

    private double[] forward(String def, double lonDeg, double latDeg, double z) {
        Pipeline p = factory.create(def);
        boolean angular = p.left() != GieIoUnits.PROJECTED;
        return p.forward(new double[] {
                angular ? lonDeg * D : lonDeg, angular ? latDeg * D : latDeg, z, 0});
    }

    private void assertForward(String def, double lonDeg, double latDeg, double z,
                               double wantLonDeg, double wantLatDeg, double wantZ,
                               double toleranceMm) {
        double[] out = forward(def, lonDeg, latDeg, z);
        double tolDeg = toleranceMm * MM_IN_DEGREES;
        assertEquals(def + " longitude", wantLonDeg, out[0] / D, tolDeg / Math.cos(latDeg * D));
        assertEquals(def + " latitude", wantLatDeg, out[1] / D, tolDeg);
        assertEquals(def + " height", wantZ, out[2], toleranceMm / 1000);
    }

    private void assertInverse(String def, double lonDeg, double latDeg, double z,
                               double wantLonDeg, double wantLatDeg, double toleranceMm) {
        Pipeline p = factory.create(def);
        double[] out = p.inverse(new double[] {lonDeg * D, latDeg * D, z, 0});
        double tolDeg = toleranceMm * MM_IN_DEGREES;
        assertEquals(def + " longitude", wantLonDeg, out[0] / D, tolDeg / Math.cos(latDeg * D));
        assertEquals(def + " latitude", wantLatDeg, out[1] / D, tolDeg);
    }

    private void assertRoundTrip(String def, double lonDeg, double latDeg, double z,
                                 double toleranceMm) {
        Pipeline p = factory.create(def);
        double[] out = p.inverse(p.forward(new double[] {lonDeg * D, latDeg * D, z, 0}));
        double tolDeg = toleranceMm * MM_IN_DEGREES;
        assertEquals(def + " longitude", lonDeg, out[0] / D, tolDeg / Math.cos(latDeg * D));
        assertEquals(def + " latitude", latDeg, out[1] / D, tolDeg);
        assertEquals(def + " height", z, out[2], toleranceMm / 1000);
    }

    private void assertForwardFails(String def, double lonDeg, double latDeg, double z) {
        try {
            double[] out = forward(def, lonDeg, latDeg, z);
            fail("expected a coordinate failure for " + def + " at " + lonDeg + ", " + latDeg
                    + " but got " + out[0] / D + ", " + out[1] / D + ", " + out[2]);
        } catch (CrsTransformException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("outside"));
        }
    }

    private void assertRejected(String def, PipelineErrorCode expected, String messageFragment) {
        try {
            factory.create(def);
            fail("expected a PipelineDefinitionException for: " + def);
        } catch (PipelineDefinitionException e) {
            assertEquals(def, expected, e.code());
            assertTrue(def + ": message was " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}
