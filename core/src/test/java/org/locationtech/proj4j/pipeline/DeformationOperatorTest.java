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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * The one charset that round-trips every byte 0x00&ndash;0xFF through {@code String}, which is
     * what makes the metadata mutants below safe to build by string surgery on a binary file.
     */
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

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

    /**
     * PROJ 9.8.1's own {@code data/tests/nkgrf03vel_realigned_extract.tif}, vendored byte for byte
     * (SHA-256 {@code 516f686a50b884a45250882eb9ba48e87a717309c16441885a2107f11799cc81}) and
     * resolved off the classpath. It is the grid {@code gie/deformation.gie}'s second block reads,
     * which is what lets the expected coordinate below be PROJ's number rather than ours.
     */
    private static final String GEOTIFF = "nkgrf03vel_realigned_extract.tif";

    /** {@code DESCRIPTION} on bands 0 and 2 exchanged: east is band 2, up is band 0. */
    private static final String PERMUTED = "deformation_permuted_test.tif";

    /** Band 0 described as {@code accuracy_east}, a role this operation cannot map. */
    private static final String FOREIGN = "deformation_foreign_test.tif";

    /** No band described at all, which is the case the positional fallback exists for. */
    private static final String UNDESCRIBED = "deformation_undescribed_test.tif";

    /** {@code UNITTYPE=metre} on the east band. */
    private static final String WRONG_UNIT = "deformation_wrongunit_test.tif";

    /** No {@code UNITTYPE} on the east band, which upstream allows. */
    private static final String NO_UNIT = "deformation_nounit_test.tif";

    /** The point every happy-path row uses: 10&deg;E, 50&deg;N, on the ellipsoid. */
    private static final double LON = 10.0 * DEG;
    private static final double LAT = 50.0 * DEG;

    /** {@code gie/deformation.gie}'s probe, in the Gulf of Bothnia: 21.5&deg;E, 63&deg;N. */
    private static final double NKG_LON = 21.5 * DEG;
    private static final double NKG_LAT = 63.0 * DEG;

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
        writeTheMetadataMutants();
        ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        GridCache.instance().clear();
        GridCache.vertical().clear();
        GridCache.generic().clear();
    }

    @AfterClass
    public static void removeTheGrids() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        GridCache.vertical().clear();
        GridCache.generic().clear();
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

    // -------------------------------------------- the single-file three-channel form

    /**
     * The whole of the {@code +grids} spelling against PROJ's own answer.
     *
     * <p>{@code gie/deformation.gie}'s second block is
     * {@code cart | deformation +grids=nkgrf03vel_realigned_extract.tif +dt=1 | cart -inv},
     * accepting 21.5&deg;E, 63&deg;N, h=0 and expecting
     * {@code 21.5000000049  62.9999999937  0.0083} to 0.05&nbsp;mm. Those are <b>PROJ 9.8.1's
     * published values</b>, generated by upstream and committed to the corpus this project is
     * measured against — not this implementation's output, and not a number produced by running
     * anything locally. The same block also asserts a five-fold round trip, which is checked here.
     *
     * <p>Corroborated independently: the <em>preceding</em> block in the same file runs the
     * historical ct2+gtx pair over extracts of the same model and expects the identical triple.
     * So the two grid spellings have one expected answer between them, and the pair's version of
     * it already passed before this operator could read a GeoTIFF at all.
     *
     * <p>The tolerances are set by the corpus's own precision, not chosen for comfort: the
     * expected longitude and latitude are quoted to 1e-10&nbsp;deg (about 0.01&nbsp;mm here) and
     * the height to 1e-4&nbsp;m. A displacement check guards against the reading that would
     * otherwise pass trivially — 8.3&nbsp;mm of uplift is far more than an identity would show.
     */
    @Test
    public void theSingleFileFormReproducesProj981sOwnExpectedCoordinate() {
        double[] in = cartesian(NKG_LON, NKG_LAT, 0.0);
        double[] out = in.clone();
        DeformationOperator op =
                operator("+proj=deformation +grids=" + GEOTIFF + " +ellps=GRS80 +dt=1");
        op.forward(out);

        assertTrue("a year of this model is 8.3 mm of uplift; an identity or a zero shift must "
                + "not be able to pass this test", distance(in, out) > 0.005);

        double[] g = geodetic(out);
        assertEquals("longitude", 21.5000000049, Math.toDegrees(g[0]), 2e-9);
        assertEquals("latitude", 62.9999999937, Math.toDegrees(g[1]), 2e-9);
        assertEquals("height", 0.0083, g[2], 1e-4);

        // gie's "roundtrip 5" on the same block.
        double[] there = in.clone();
        for (int i = 0; i < 5; i++) {
            op.forward(there);
            op.inverse(there);
        }
        assertEquals("five round trips must return to the input", 0.0, distance(in, there), 1e-6);
    }

    /**
     * The three channels read as three <em>different</em> velocities, in millimetres per year.
     *
     * <p>Held for a thousand years so the displacement in metres is numerically the velocity in
     * mm/yr — the forward is exactly linear in the interval, since the grid is evaluated at the
     * input position and the resulting rate simply scaled. The values are the model's, and the
     * shape of them is the point: a fraction of a millimetre horizontally against 8.3&nbsp;mm/yr
     * of post-glacial uplift. A channel assignment that put the uplift on an horizontal axis would
     * still land in the Gulf of Bothnia, which is why the vertical rate being an order of
     * magnitude larger than the other two is asserted rather than assumed.
     */
    @Test
    public void theSingleFileFormReadsThreeDistinctChannelsInMillimetresPerYear() {
        double[] v = velocityAtTheNkgPoint(GEOTIFF);
        assertEquals("east, mm/yr", 0.25, v[0], 0.05);
        assertEquals("north, mm/yr", -0.70, v[1], 0.05);
        assertEquals("up, mm/yr", 8.30, v[2], 0.05);
        assertTrue("the vertical rate must be the large one, or a swapped channel would be "
                + "indistinguishable from a correct read", v[2] > 10.0 * Math.abs(v[0]));
    }

    /**
     * Which band is east, north and up comes from {@code DESCRIPTION}, not from position.
     *
     * <p>{@code deformation.cpp:99-113} defaults to bands 0, 1, 2 and then lets a band naming
     * itself {@code east_velocity}, {@code north_velocity} or {@code up_velocity} claim that slot.
     * The vendored file's descriptions are already in the positional order, so it cannot tell the
     * two rules apart; the mutant exchanges the {@code sample=} attributes of the east and up
     * descriptions, leaving every pixel of raster data exactly where it was.
     *
     * <p>Correct behaviour is therefore that the reported east velocity becomes the canonical
     * <em>up</em> velocity and vice versa. Positional selection would return the canonical triple
     * unchanged. The two differ by 8&nbsp;mm/yr, so the discrimination is not marginal — and the
     * final assertion is the control that says so.
     *
     * <p>The 1e-4 tolerance belongs to the <em>measurement</em>, not to the operator: the
     * east-north-up decomposition in {@link #displacementEastNorthUp} linearises about the start
     * point, and the two runs end 8&nbsp;m apart in height, which shifts the latitude a given
     * cartesian displacement corresponds to by about a hundredth of a micrometre per year.
     * Against an 8&nbsp;mm/yr signal that is a margin of five orders of magnitude.
     */
    @Test
    public void theChannelsAreChosenByDescriptionAndNotByPosition() {
        double[] canonical = velocityAtTheNkgPoint(GEOTIFF);
        double[] permuted = velocityAtTheNkgPoint(PERMUTED);

        assertEquals("east must now come from the band described as east_velocity, which is "
                + "band 2", canonical[2], permuted[0], 1e-4);
        assertEquals("north is untouched", canonical[1], permuted[1], 1e-4);
        assertEquals("and up from band 0", canonical[0], permuted[2], 1e-4);

        assertTrue("the mutation must be observable at all: if the east and up bands held similar "
                + "values, this test would pass under positional selection too",
                Math.abs(canonical[0] - canonical[2]) > 1.0);
    }

    /**
     * A grid with no band descriptions at all falls back to bands 0, 1, 2 — upstream's default,
     * and the case the convention exists for.
     *
     * <p>The mutant renames all three {@code DESCRIPTION} items to a key nothing reads, so the
     * grid reports an empty description on every band. Since the vendored file's bands are in
     * positional order, the answer must be bit-for-bit the canonical one.
     */
    @Test
    public void aGridWithNoBandDescriptionsFallsBackToBandsZeroOneAndTwo() {
        double[] canonical = velocityAtTheNkgPoint(GEOTIFF);
        double[] undescribed = velocityAtTheNkgPoint(UNDESCRIBED);
        assertEquals(canonical[0], undescribed[0], 0.0);
        assertEquals(canonical[1], undescribed[1], 0.0);
        assertEquals(canonical[2], undescribed[2], 0.0);
    }

    /**
     * A grid that describes its bands in a vocabulary this operation cannot map is <b>refused</b>,
     * and this is the one place proj4j deliberately diverges from {@code deformation.cpp}.
     *
     * <p>Upstream's loop has no "did I find them" test. Given a band described as
     * {@code accuracy_east}, nothing claims slot 0, the initialiser {@code sampleE = 0} survives,
     * and the accuracy band is read as an east velocity — a plausible velocity on the wrong axis,
     * at the right place, which is the failure mode this project exists to eliminate. The shape of
     * the refusal is not invented either: it is {@code grids.cpp:2541-2590}'s own
     * {@code foundDescriptionForAtLeastOneSample} guard, which PROJ applies in its newer
     * generic-grid consumer and not in this one.
     *
     * <p>It cannot change the conformance corpus, because a grid that reaches it is out of spec.
     * The message must name the descriptions it could not map, so the cause is in the failure
     * rather than in a debugger.
     */
    @Test
    public void aGridDescribingBandsThisOperationCannotMapIsRefusedRatherThanGuessed() {
        double[] c = cartesian(NKG_LON, NKG_LAT, 0.0);
        try {
            operator("+proj=deformation +grids=" + FOREIGN + " +ellps=GRS80 +dt=1").forward(c);
            fail("accuracy_east is not a velocity channel, and band 0 must not be read as one");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.MISSING_GRID, e.cause());
            assertTrue("the message must quote what the grid actually said: " + e.getMessage(),
                    e.getMessage().contains("accuracy_east"));
            assertTrue("and what this operation needed: " + e.getMessage(),
                    e.getMessage().contains("east_velocity"));
            assertTrue("and must not be reported as the point being outside the grid: "
                    + e.getMessage(), e.getMessage().contains("which channel"));
        }
    }

    /**
     * {@code deformation.cpp:114-118} accepts an empty {@code UNITTYPE} on the east band and
     * nothing but {@code millimetres per year} otherwise. Both halves, on the same file.
     *
     * <p>The unitless mutant must give the canonical answer exactly — an absent declaration is
     * read as mm/yr, not as a reason to refuse and not as a scale of one.
     */
    @Test
    public void theEastChannelsUnitMustBeMillimetresPerYearOrAbsent() {
        double[] canonical = velocityAtTheNkgPoint(GEOTIFF);
        double[] unitless = velocityAtTheNkgPoint(NO_UNIT);
        assertEquals("an absent UNITTYPE is millimetres per year", canonical[2], unitless[2], 0.0);

        double[] c = cartesian(NKG_LON, NKG_LAT, 0.0);
        try {
            operator("+proj=deformation +grids=" + WRONG_UNIT + " +ellps=GRS80 +dt=1").forward(c);
            fail("UNITTYPE=metre would be a thousand times the velocity the model states");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.MISSING_GRID, e.cause());
            assertTrue("the message must name the unit it will not read: " + e.getMessage(),
                    e.getMessage().contains("metre"));
            assertTrue("and the one it will: " + e.getMessage(),
                    e.getMessage().contains("millimetres per year"));
        }
    }

    /**
     * {@code +grids=null} covers the whole world and shifts nothing
     * ({@code deformation.cpp:89-94}). It has no samples at all, so it must be recognised
     * <em>before</em> the three-channel requirement is applied, and the interval must not turn a
     * zero velocity into a rounding error.
     */
    @Test
    public void theNullGridIsAnExactIdentityForTheSingleFileForm() {
        double[] in = cartesian(NKG_LON, NKG_LAT, 0.0);
        double[] out = in.clone();
        operator("+proj=deformation +grids=null +ellps=GRS80 +dt=1000").forward(out);
        assertEquals(in[0], out[0], 0.0);
        assertEquals(in[1], out[1], 0.0);
        assertEquals(in[2], out[2], 0.0);
    }

    /**
     * A point the single-file grid does not cover is an error, exactly as it is for the pair. The
     * vendored extract is a small window over the Nordic and Baltic countries; the equator on the
     * prime meridian is not in it.
     */
    @Test
    public void aPointOutsideTheSingleFileGridIsRefusedRatherThanShiftedByZero() {
        double[] c = cartesian(0.0, 0.0, 0.0);
        try {
            operator("+proj=deformation +grids=" + GEOTIFF + " +ellps=GRS80 +dt=1").forward(c);
            fail("(0, 0) is nowhere near the NKG extract and must not be shifted by zero");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
            assertTrue("the message must name the grid: " + e.getMessage(),
                    e.getMessage().contains(GEOTIFF));
            assertTrue(e.getMessage(), e.getMessage().contains("outside every grid"));
        }
    }

    /**
     * A grid with fewer than three channels is refused at <b>transform time</b>, where upstream
     * refuses it, and not at construction: a {@code +grids=} list may name a grid that a given
     * coordinate never selects, and PROJ builds that pipeline. {@code test_vgrid_nodata.tif} is a
     * one-band vertical grid over 4&ndash;5&deg;E, 52&ndash;53&deg;N.
     */
    @Test
    public void aSingleFileGridWithFewerThanThreeChannelsIsRefusedAtTransformTime() {
        DeformationOperator op = operator(
                "+proj=deformation +grids=test_vgrid_nodata.tif +ellps=GRS80 +dt=1");
        double[] c = cartesian(4.1 * DEG, 52.2 * DEG, 0.0);
        try {
            op.forward(c);
            fail("one band cannot supply an east, a north and an up velocity");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.MISSING_GRID, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("not enough samples"));
        }
    }

    /** A pipeline dump of the single-file form must name the grid, and not an absent pair. */
    @Test
    public void theDescriptionOfTheSingleFileFormNamesTheGridAndTheInterval() {
        String d = operator("+proj=deformation +grids=" + GEOTIFF + " +ellps=GRS80 +dt=1")
                .description();
        assertTrue(d, d.contains("grids=" + GEOTIFF));
        assertTrue(d, d.contains("dt=1"));
        assertTrue("nothing may claim an +xy_grids that was never given: " + d,
                !d.contains("xy_grids"));
    }

    /**
     * The three channels a {@code +grids} model reports at the gie probe point, in millimetres per
     * year. See {@link #theSingleFileFormReadsThreeDistinctChannelsInMillimetresPerYear()} for why
     * a thousand-year interval makes the displacement in metres numerically the rate in mm/yr.
     *
     * @return {@code {east, north, up}} in millimetres per year
     */
    private static double[] velocityAtTheNkgPoint(String grid) {
        double[] in = cartesian(NKG_LON, NKG_LAT, 0.0);
        double[] out = in.clone();
        operator("+proj=deformation +grids=" + grid + " +ellps=GRS80 +dt=1000").forward(out);
        return displacementEastNorthUp(in, out);
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
     * The single-file spelling reads a real grid now (2.2.0), so a name that resolves to nothing
     * is an ordinary missing-file refusal and not a "not implemented" one. Before 2.2.0 this
     * asserted {@link PipelineErrorCode#NOT_IMPLEMENTED_HERE}; the assertion moved with the code.
     */
    @Test
    public void anUnresolvableSingleFileGridIsRefusedAtSetup() {
        assertRefused("+proj=deformation +grids=no_such_model.tif +ellps=GRS80 +dt=1",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
        assertRefused("+proj=deformation +grids=no_such_model.tif +ellps=GRS80 +dt=1",
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "+grids=no_such_model.tif");
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

    // ------------------------------------------------- the GDAL_METADATA mutants

    /**
     * Five variants of the vendored GeoTIFF, each differing from it only inside the
     * {@code <GDALMetadata>} tag, written to the temp dir at class-setup time.
     *
     * <p>The committed {@code .tif} is <b>not</b> touched: it is a PROJ 9.8.1 artefact and the
     * source of the expected coordinate in
     * {@link #theSingleFileFormReproducesProj981sOwnExpectedCoordinate()}, so editing it would
     * change the question instead of answering it. Its own metadata happens to be in the
     * canonical order — {@code east_velocity}, {@code north_velocity}, {@code up_velocity} on
     * bands 0, 1, 2 — which is exactly why the mutants are needed: against that file alone,
     * description-driven selection and blind positional selection give the same answer, and a
     * test of the former would be vacuous.
     */
    private static void writeTheMetadataMutants() throws IOException {
        byte[] original = shippedGeoTiff();

        // East and up exchanged. Two edits on two distinct anchors, so order does not matter.
        byte[] permuted = retagged(original,
                "\"DESCRIPTION\" sample=\"0\" role=\"description\">east_velocity",
                "\"DESCRIPTION\" sample=\"2\" role=\"description\">east_velocity", 1);
        permuted = retagged(permuted,
                "\"DESCRIPTION\" sample=\"2\" role=\"description\">up_velocity",
                "\"DESCRIPTION\" sample=\"0\" role=\"description\">up_velocity", 1);
        Files.write(root.resolve(PERMUTED), permuted);

        // A 13-for-13 character substitution: the band still has a description, and it is one
        // this operation has no slot for.
        Files.write(root.resolve(FOREIGN), retagged(original,
                "role=\"description\">east_velocity<", "role=\"description\">accuracy_east<", 1));

        // All three DESCRIPTION items renamed to a key nothing reads, so the grid reports no
        // description on any band. Q for R, same length, same offsets.
        Files.write(root.resolve(UNDESCRIBED),
                retagged(original, "name=\"DESCRIPTION\"", "name=\"DESCRIPTIQN\"", 3));

        // The east band's unit only. The shortfall is padded after the </Item>, where the
        // parser is already skipping text.
        Files.write(root.resolve(WRONG_UNIT), retagged(original,
                "sample=\"0\" role=\"unittype\">millimetres per year</Item>",
                "sample=\"0\" role=\"unittype\">metre</Item>", 1));

        // The east band's UNITTYPE item renamed away, leaving band 0 unitless.
        Files.write(root.resolve(NO_UNIT), retagged(original,
                "name=\"UNITTYPE\" sample=\"0\"", "name=\"UNITTYPF\" sample=\"0\"", 1));
    }

    /** The committed grid's bytes, read from the classpath and never written back. */
    private static byte[] shippedGeoTiff() throws IOException {
        InputStream in = DeformationOperatorTest.class
                .getResourceAsStream("/proj4j-data/grids/" + GEOTIFF);
        if (in == null) {
            throw new IOException("the vendored " + GEOTIFF + " is not on the test classpath");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Rewrites {@code occurrences} copies of {@code from} to {@code to} inside the file's
     * {@code <GDALMetadata>} tag <b>without moving a single byte of anything else</b>.
     *
     * <p>That constraint is the whole reason this is done in code rather than with a checked-in
     * pair of files. A TIFF is a graph of absolute file offsets: the IFD entry for tag 42112
     * carries the metadata string's length and the offset of every following tag's data. Growing
     * or shrinking the string by one byte invalidates all of them, and the reader would fail for
     * a reason that has nothing to do with what the test is about. So {@code to} must be no longer
     * than {@code from}, and any shortfall is padded with spaces — legal only where the padding
     * lands <em>between</em> {@code <Item>} elements, which
     * {@link org.locationtech.proj4j.datum.tiff.GdalMetadata} skips, hence the {@code </Item>}
     * requirement below.
     *
     * @throws IllegalStateException if the anchor count is not exactly {@code occurrences}, which
     *                               is what would happen if a future PROJ release reworded the
     *                               metadata — a loud failure rather than a silently unmutated
     *                               copy that makes every mutant test vacuously pass
     */
    private static byte[] retagged(byte[] original, String from, String to, int occurrences) {
        if (to.length() > from.length()) {
            throw new IllegalStateException("a mutant may not grow the metadata: " + to);
        }
        if (to.length() < from.length() && !from.endsWith("</Item>")) {
            throw new IllegalStateException("padding is only legal outside an <Item>: " + from);
        }
        StringBuilder padded = new StringBuilder(to);
        while (padded.length() < from.length()) {
            padded.append(' ');
        }

        String all = new String(original, LATIN1);
        int start = all.indexOf("<GDALMetadata>");
        int end = all.indexOf("</GDALMetadata>");
        if (start < 0 || end < start) {
            throw new IllegalStateException(GEOTIFF + " has no <GDALMetadata> tag");
        }

        int found = 0;
        StringBuilder mutant = new StringBuilder(all);
        for (int at = mutant.indexOf(from, start); at >= 0 && at < end;
                at = mutant.indexOf(from, at + padded.length())) {
            mutant.replace(at, at + from.length(), padded.toString());
            found++;
        }
        if (found != occurrences) {
            throw new IllegalStateException("expected " + occurrences + " copies of \"" + from
                    + "\" in " + GEOTIFF + "'s metadata, found " + found);
        }

        byte[] out = mutant.toString().getBytes(LATIN1);
        if (out.length != original.length) {
            throw new IllegalStateException("the mutant moved " + (out.length - original.length)
                    + " bytes, which invalidates every TIFF offset after the metadata tag");
        }
        return out;
    }
}
