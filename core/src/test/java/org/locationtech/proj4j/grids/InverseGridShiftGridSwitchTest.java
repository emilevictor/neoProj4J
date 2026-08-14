/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.grids;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.datum.GridCache;
import org.locationtech.proj4j.resource.DirectoryResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * When the inverse iterate walks off its grid, it continues in the next grid that contains it.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code Grid.nad_cvt}'s inverse loop interpolates at the current iterate. If that interpolation
 * cannot produce a value, the iterate has stepped outside the grid the <em>input</em> point was found
 * in — which is not the same event as the iteration having failed. PROJ 9.8.1 says so in a comment and
 * then acts on it ({@code src/grids.cpp:3451-3476}, {@code pj_hgrid_apply_internal}):
 *
 * <pre>
 * if (del.lam == HUGE_VAL) {
 *     // We can possibly go outside of the initial guessed grid, so try to
 *     // fetch a new grid into which iterate...
 *     grid = findGrid(gridset, ...);
 *     if (grid &amp;&amp; grid != last_grid) { ...rebase and continue... }
 *     break;
 * }
 * </pre>
 *
 * <p>proj4j had only the {@code break} — it went straight to PROJ's first-approximation escape hatch
 * ("Inverse grid shift iteration failed, presumably at grid edge") and returned the unconverged
 * iterate. That is the right answer when there genuinely is no other grid, and it is still what
 * happens in that case ({@link #withNoNeighbourTheGridEdgeEscapeHatchStillAnswers}). It is the wrong
 * answer when another listed grid does cover the iterate, because the first approximation is off by
 * roughly one whole grid shift and every round trip restarts from the previous one's approximation.
 *
 * <h2>Measured on the real grids, before this test was written</h2>
 *
 * <p>With {@code +nadgrids=&#64;conus,&#64;alaska,&#64;ntv2_0.gsb,&#64;ntv1_can.dat} — the NAD27 list —
 * at {@code -130.516041667, 50.0002461111} ({@code conformance gigs/5207.2.gie.failing:414}),
 * {@code conus} ends at 50&deg;N and the inverse iterate crosses into {@code alaska}. PROJ 9.8.1's
 * {@code PJ_LOG_TRACE} names it: <b>{@code Switching from grid conus to grid alaska}</b>, and
 * {@code proj_roundtrip} closes to <b>0.000000 mm</b> at n = 1, 100 and 1000. Without the rebase
 * proj4j drifted <b>12.94 mm after one round trip and 6,054.00 mm after 1,000</b>; with it, 0.000001 mm.
 * The neighbouring row {@code gigs/5206.gie.failing:490} behaved the same way (12.98 mm &rarr;
 * 648.89 mm at n = 100, now 0.000000 mm).
 *
 * <h2>Why the fixture is synthetic</h2>
 *
 * <p>The real reproduction needs {@code conus} <em>and</em> {@code alaska} in one list.
 * {@code core}'s test resources carry {@code conus}, {@code ntv1_can.dat} and
 * {@code ntv2_0_downsampled.gsb} but not {@code alaska}, and no pair of the three has a boundary an
 * iterate crosses — published grids are smooth, so the iterate moves by far less than a cell and
 * stays put. The real-data case is covered where the data exists, in the conformance corpus.
 *
 * <p>So this fixture is two valid CTABLE V2 grids with <em>constant</em> longitude shifts of different
 * size, arranged so the arithmetic is checkable by hand:
 *
 * <ul>
 *   <li>{@code inner}: 10&deg;W&ndash;0&deg;, 10&deg;S&ndash;10&deg;N, longitude shift 1&deg; at every
 *       node;</li>
 *   <li>{@code outer}: 20&deg;W&ndash;10&deg;E, the same latitudes, longitude shift 2&deg;;</li>
 *   <li>latitude shift identically zero in both, so every number below is a longitude.</li>
 * </ul>
 *
 * <p>At {@link #LON} = 0.5&deg;W, {@code inner} is first in list order and contains the point, so it is
 * selected. The initial guess is {@code lon + 1}&deg; = 0.5&deg;E, which is outside {@code inner} — the
 * escape. {@code outer} contains 0.5&deg;E, and inverting {@code outer}'s 2&deg; shift gives 1.5&deg;E.
 * The forward shift at 1.5&deg;E is also answered by {@code outer} (1.5&deg;E is outside {@code inner}),
 * so the round trip closes exactly. The abandoned first approximation, 0.5&deg;E, does not: it comes
 * back a full degree away.
 *
 * <p>{@code outer} deliberately extends <em>west</em> of the input rather than starting at 0&deg;. PROJ
 * carries the input as an offset from the selected grid's own origin through
 * {@code adjlon(in.lam - extent-&gt;west - M_PI) + M_PI}, which folds into {@code [0, 2}&pi;{@code )};
 * a grid whose origin lies east of the input would therefore give that offset as ~359&deg; rather than
 * ~&minus;0.5&deg;. Real grids that continue past a neighbour's edge are wide ones —
 * {@code us_noaa_alaska.tif} declares its west edge at 194&deg;W — so this shape is the realistic one
 * as well as the tractable one.
 */
public class InverseGridShiftGridSwitchTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final double DEG = Math.PI / 180.0;

    /** Inside {@code inner} and within one {@code inner} shift of its eastern edge. */
    private static final double LON = -0.5;
    private static final double LAT = 0.0;

    /** Inverting {@code outer}'s 2&deg; shift at {@link #LON}: {@code -0.5 + 2.0}. */
    private static final double CONVERGED_LON = 1.5;

    /** What the escape hatch would have returned: the initial guess, {@code -0.5 + 1.0}. */
    private static final double FIRST_APPROXIMATION_LON = 0.5;

    /**
     * The grid values are stored as {@code float}, so a shift of exactly 2&deg; is carried to about
     * 6e-8&deg; (~6 mm). Everything asserted here is a degree or a whole cell in size.
     */
    private static final double TOL_DEG = 1e-6;

    private Path root;

    @After
    public void cleanUp() throws IOException {
        ResourceResolvers.clearResolvers();
        GridCache.instance().clear();
        if (root != null) {
            Files.deleteIfExists(root.resolve("inner"));
            Files.deleteIfExists(root.resolve("outer"));
            Files.deleteIfExists(root);
            root = null;
        }
    }

    /** {@code inner} covers 10&deg;W&ndash;0&deg;; {@code outer} covers 20&deg;W&ndash;10&deg;E. */
    private void writeFixture() throws IOException {
        if (root != null) return;
        root = Files.createTempDirectory("proj4j-gridswitch");
        Files.write(root.resolve("inner"), ctable2("inner", -10.0, 11, 1.0));
        Files.write(root.resolve("outer"), ctable2("outer", -20.0, 31, 2.0));
        ResourceResolvers.addResolver(new DirectoryResourceResolver(root));
        GridCache.instance().clear();
    }

    private List<Grid> grids(String... names) throws IOException {
        writeFixture();
        List<Grid> list = new ArrayList<Grid>();
        for (String name : names) {
            Grid.mergeGridFile(name, list);
        }
        return list;
    }

    /** The premise: the two extents, and that the input point is in {@code inner} only. */
    @Test
    public void theFixtureIsTwoOverlappingGridsAndTheInputIsInTheNarrowOne() throws IOException {
        List<Grid> both = grids("inner", "outer");
        assertEquals(2, both.size());
        assertEquals("ctable2", both.get(0).getFormat());

        double[] inner = both.get(0).extentRadians();
        assertEquals(-10.0, Math.toDegrees(inner[0]), 1e-9);
        assertEquals(0.0, Math.toDegrees(inner[2]), 1e-9);
        double[] outer = both.get(1).extentRadians();
        assertEquals(-20.0, Math.toDegrees(outer[0]), 1e-9);
        assertEquals(10.0, Math.toDegrees(outer[2]), 1e-9);

        assertTrue("the input must be inside inner, which is first in list order and therefore wins",
                Math.toDegrees(inner[0]) < LON && LON < Math.toDegrees(inner[2]));
        assertTrue("and the escaped iterate must be outside inner",
                FIRST_APPROXIMATION_LON > Math.toDegrees(inner[2]));
        assertTrue("but inside outer",
                FIRST_APPROXIMATION_LON < Math.toDegrees(outer[2]));
    }

    /**
     * The fix. The iterate leaves {@code inner}, is picked up by {@code outer}, and converges there
     * — a full degree from the approximation the escape hatch would have handed back.
     */
    @Test
    public void theIterateLeavesTheFirstGridAndConvergesInTheSecond() throws IOException {
        List<Grid> both = grids("inner", "outer");
        double[] got = GridReferenceValues.shiftDegrees(both, true, LON, LAT);

        assertEquals("the inverse must converge against outer, the grid the iterate moved into",
                CONVERGED_LON, got[0], TOL_DEG);
        assertEquals("the latitude shift is zero everywhere in this fixture", LAT, got[1], TOL_DEG);
        assertTrue("this must not be inner's first approximation, which is a whole degree away; got "
                        + got[0],
                Math.abs(got[0] - FIRST_APPROXIMATION_LON) > 0.9);
    }

    /**
     * <b>The reason it matters.</b> The converged answer round-trips; the first approximation is out
     * by one whole grid shift, and each round trip would restart from the previous one's
     * approximation — which is how the real case reached 6 m over 1,000 trips from 13 mm over one.
     */
    @Test
    public void theRoundTripClosesWhereTheFirstApproximationWouldNotHave() throws IOException {
        List<Grid> both = grids("inner", "outer");
        double[] inverted = GridReferenceValues.shiftDegrees(both, true, LON, LAT);
        double[] back = GridReferenceValues.shiftDegrees(both, false, inverted[0], inverted[1]);

        assertEquals("forward-after-inverse must return the input", LON, back[0], TOL_DEG);
        assertEquals(LAT, back[1], TOL_DEG);

        double[] wouldHaveBeen = GridReferenceValues.shiftDegrees(
                both, false, FIRST_APPROXIMATION_LON, LAT);
        assertTrue("the abandoned first approximation does not round-trip -- it comes back at "
                        + wouldHaveBeen[0] + " rather than " + LON + ", which is the error that "
                        + "used to accumulate",
                Math.abs(wouldHaveBeen[0] - LON) > 0.9);
    }

    /**
     * The control that shows the rebase is a <em>correction</em> and not a second algorithm: the
     * answer it reaches is exactly the answer {@code outer} gives when it is the only grid there is,
     * so no information from {@code inner} leaks into the result.
     */
    @Test
    public void theRebasedAnswerEqualsTheAnswerOuterGivesOnItsOwn() throws IOException {
        double[] viaSwitch = GridReferenceValues.shiftDegrees(grids("inner", "outer"), true, LON, LAT);
        double[] outerOnly = GridReferenceValues.shiftDegrees(grids("outer"), true, LON, LAT);

        assertEquals("longitude", outerOnly[0], viaSwitch[0], 1e-12);
        assertEquals("latitude", outerOnly[1], viaSwitch[1], 1e-12);
    }

    /**
     * The other half of PROJ's branch, and the behaviour this change must not have taken away: with
     * nothing to move to, the escape hatch still answers with the first approximation rather than
     * throwing. This is PROJ's "presumably at grid edge. Using first approximation"
     * ({@code grids.cpp:3495-3499}) and it is reachable at every boundary no other listed grid
     * continues past.
     */
    @Test
    public void withNoNeighbourTheGridEdgeEscapeHatchStillAnswers() throws IOException {
        double[] got = GridReferenceValues.shiftDegrees(grids("inner"), true, LON, LAT);
        assertEquals("with only inner listed there is nowhere to rebase to, so the unconverged "
                        + "iterate is the answer -- as it is in PROJ",
                FIRST_APPROXIMATION_LON, got[0], TOL_DEG);
    }

    /**
     * The rebase needs the whole grid list, and {@code Grid.shift} has two overloads that hold it
     * differently ({@code List<Grid>} and {@code Grid[]}) because neither may allocate on this path.
     * They must reach the same grid.
     */
    @Test
    public void bothShiftOverloadsRebaseIdentically() throws IOException {
        List<Grid> list = grids("inner", "outer");
        Grid[] array = list.toArray(new Grid[0]);

        ProjCoordinate viaList = new ProjCoordinate(Math.toRadians(LON), Math.toRadians(LAT));
        ProjCoordinate viaArray = new ProjCoordinate(Math.toRadians(LON), Math.toRadians(LAT));
        Grid.shift(list, true, viaList);
        Grid.shift(array, true, viaArray);

        assertEquals("the array overload must rebase to the same grid, bit for bit",
                Double.doubleToLongBits(viaList.x), Double.doubleToLongBits(viaArray.x));
        assertEquals(Double.doubleToLongBits(viaList.y), Double.doubleToLongBits(viaArray.y));
        assertEquals(CONVERGED_LON, Math.toDegrees(viaArray.x), TOL_DEG);
    }

    /**
     * A CTABLE V2 grid at 1&deg; spacing over {@code westDeg}&hellip;{@code westDeg + cols - 1} in
     * longitude and 10&deg;S&ndash;10&deg;N in latitude, with a constant longitude shift and no
     * latitude shift.
     */
    private static byte[] ctable2(String id, double westDeg, int cols, double shiftDeg) {
        final int rows = 21;
        byte[] b = new byte[160 + cols * rows * 8];
        System.arraycopy("CTABLE V2.0     ".getBytes(ASCII), 0, b, 0, 16);
        byte[] name = (id + " (constant " + shiftDeg + " deg longitude shift)\n").getBytes(ASCII);
        System.arraycopy(name, 0, b, 16, Math.min(name.length, 79));

        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(96, westDeg * DEG);   // ll.lam
        buf.putDouble(104, -10.0 * DEG);    // ll.phi
        buf.putDouble(112, 1.0 * DEG);      // del.lam
        buf.putDouble(120, 1.0 * DEG);      // del.phi
        buf.putInt(128, cols);
        buf.putInt(132, rows);

        for (int i = 0; i < cols * rows; i++) {
            int off = 160 + i * 8;
            buf.putFloat(off, (float) (shiftDeg * DEG));
            buf.putFloat(off + 4, 0.0f);
        }
        return b;
    }
}
