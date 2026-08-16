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
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.vertical.VGridShiftOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PROJ's built-in {@code null} vertical pseudo-grid — {@code +grids=null} and
 * {@code +geoidgrids=null}.
 *
 * <p>Upstream is {@code NullVerticalShiftGrid} ({@code 9.8.1:src/grids.cpp:148-168}), dispatched by
 * {@code VerticalShiftGridSet::open}'s {@code if (filename == "null")} at {@code :1615} before the
 * file manager is ever consulted. It is a 3&times;3 grid over {@code globalExtent()}
 * ({@code :131-141}) whose {@code valueAt} writes {@code 0.0f}.
 *
 * <p>Two of the fork's three parallel grid hierarchies already had this — {@code Grid.nullGrid()}
 * for {@code +nadgrids=} and {@code GenericGrid.nullGrid()} for the N-sample layer — mirroring
 * upstream's own three. {@link VerticalGrid} was the only one missing it, so
 * {@code +grids=<real>,null} failed at construction on a token that names no file.
 *
 * <p>The motivating corpus row is {@code 4D-API_cs2cs-style.gie:455},
 * {@code operation proj=vgridshift grids=tests/test_nodata.gtx,null ellps=GRS80}, whose second
 * assertion the corpus itself comments <em>"Outside validity area of test_nodata.gtx. Fallback on
 * null"</em>. That fixture lives in the conformance module, so the fall-through here is exercised
 * against {@code egm96_15_downsampled.gtx} instead — a grid whose latitude extent stops short of
 * the poles, which gives the same two-grid ordering with a fixture core actually ships.
 */
public class NullVerticalGridTest {

    /** The name that must never reach the resolver chain. */
    private static final String NULL = "null";

    private static VerticalGrid nullGrid() throws IOException {
        return VerticalGrid.fromName(NULL);
    }

    @Test
    public void theNameIsRecognisedWithoutTouchingTheResolverChain() throws IOException {
        VerticalGrid g = nullGrid();
        assertTrue("isNullGrid must identify it", g.isNullGrid());
        assertEquals("upstream's own m_format string", "null", g.getFormat());
        assertEquals("null", g.getGridName());
        assertEquals("built-in", g.getResolverName());
        assertEquals("nine nodes, no file read", 36L, g.sizeBytes());
    }

    /**
     * {@code globalExtent()} verbatim: west {@code -}&pi;, south {@code -}&pi;/2, {@code resX} &pi;,
     * {@code resY} &pi;/2 over a 3&times;3 grid, which puts east at {@code +}&pi; and north at
     * {@code +}&pi;/2.
     */
    @Test
    public void theExtentIsProjsGlobalExtent() throws IOException {
        VerticalGrid g = nullGrid();
        assertEquals(3, g.getWidth());
        assertEquals(3, g.getHeight());

        double[] extent = g.extentRadians();
        assertEquals("west", -Math.PI, extent[0], 0.0);
        assertEquals("south", -Math.PI / 2.0, extent[1], 0.0);
        assertEquals("east", Math.PI, extent[2], 1e-15);
        assertEquals("north", Math.PI / 2.0, extent[3], 1e-15);

        double[] res = g.resolutionRadians();
        assertEquals("resX", Math.PI, res[0], 0.0);
        assertEquals("resY", Math.PI / 2.0, res[1], 0.0);
    }

    /**
     * Coverage everywhere. This is the property the fall-through depends on, so it is swept over the
     * whole sphere rather than probed at one point — and the sweep asserts how many points it
     * visited, so a sweep that ranges over nothing cannot report a clean pass.
     */
    @Test
    public void itCoversEveryPointOnTheSphere() throws IOException {
        VerticalGrid g = nullGrid();
        int visited = 0;
        for (int lat = -90; lat <= 90; lat += 5) {
            for (int lon = -180; lon <= 180; lon += 5) {
                double lam = Math.toRadians(lon);
                double phi = Math.toRadians(lat);
                assertTrue("null grid must cover (" + lon + ", " + lat + ")", g.covers(lam, phi));
                visited++;
            }
        }
        assertEquals("the sweep must actually range over the sphere", 37 * 73, visited);
    }

    /** And the value there is exactly zero, at every one of those points, for any multiplier. */
    @Test
    public void itsValueIsExactlyZeroEverywhere() throws IOException {
        VerticalGrid g = nullGrid();
        int visited = 0;
        for (int lat = -90; lat <= 90; lat += 5) {
            for (int lon = -180; lon <= 180; lon += 5) {
                double lam = Math.toRadians(lon);
                double phi = Math.toRadians(lat);
                assertEquals("shift at (" + lon + ", " + lat + ")",
                        0.0, g.valueAt(lam, phi, 1.0), 0.0);
                assertEquals("the multiplier cannot make zero non-zero",
                        0.0, g.valueAt(lam, phi, -1.0), 0.0);
                visited++;
            }
        }
        assertEquals(37 * 73, visited);
    }

    /**
     * Zero is not nodata under either of {@code VerticalGrid.isNodata}'s two rules, which is what
     * lets nine real zeros stand in for upstream's overridden node reader. If it were nodata,
     * {@code VGridShiftOperator} would raise {@code GRID_NODATA} instead of shifting by nothing —
     * so this is a distinct claim from "the value is zero" and is asserted separately.
     */
    @Test
    public void aZeroNodeIsNotTreatedAsNodata() throws IOException {
        VerticalGrid g = nullGrid();
        assertEquals(0.0f, g.nodeAt(0, 0), 0.0f);
        double[] coord = {Math.toRadians(4.05), Math.toRadians(-52.1), 123.5, 0};
        VGridShiftOperator.fromGrids(NULL, 1.0).forward(coord);
        assertEquals("a nodata verdict would have thrown, not returned", 123.5, coord[2], 0.0);
    }

    // ------------------------------------------------------------------ ordering

    /**
     * The fall-through ordering. {@code VGridShiftOperator.valueAt} takes the first grid whose
     * extent contains the point, so {@code null} placed last means "the real grid where it reaches,
     * nothing elsewhere" — and placed <em>first</em> it would shadow the real grid entirely. Both
     * directions are asserted, because only the pair shows that the order is what decides.
     */
    @Test
    public void nullLastFallsThroughAndNullFirstShadows() {
        double insideLat = 52.1;      // egm96_15_downsampled.gtx covers this
        double outsideLat = 89.9;     // north of its last row centre, ~89.624 deg
        double lon = 4.05;

        double real = shift("egm96_15_downsampled.gtx", lon, insideLat);
        assertTrue("the real grid must return a non-zero shift here, or this test proves nothing",
                Math.abs(real) > 1.0);

        assertEquals("null last: the real grid still wins where it reaches",
                real, shift("egm96_15_downsampled.gtx,null", lon, insideLat), 0.0);
        assertEquals("null last: outside the real grid, shift by nothing instead of failing",
                0.0, shift("egm96_15_downsampled.gtx,null", lon, outsideLat), 0.0);
        assertEquals("null first: it covers the world, so it shadows the real grid",
                0.0, shift("null,egm96_15_downsampled.gtx", lon, insideLat), 0.0);
    }

    /**
     * The negative control for the ordering test: without the {@code null} token the same point is a
     * refusal, and it is a refusal at <em>transform</em> time rather than a silent zero. That is what
     * the null grid is standing in for, so if this ever stops throwing the test above is vacuous.
     */
    @Test
    public void withoutTheNullTokenTheSamePointIsRefused() {
        try {
            shift("egm96_15_downsampled.gtx", 4.05, 89.9);
            fail("outside every grid must be an error, not a zero shift");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("outside every grid"));
        }
    }

    /** {@code null} is one grid in the list, not a marker that shortens it. */
    @Test
    public void theNullTokenIsAGridInTheListLikeAnyOther() throws IOException {
        List<VerticalGrid> grids = VerticalGrid.fromGeoidGrids("egm96_15_downsampled.gtx,null");
        assertEquals(2, grids.size());
        assertFalse(grids.get(0).isNullGrid());
        assertTrue(grids.get(1).isNullGrid());
        assertEquals("only the built-in name is special",
                1, VerticalGrid.fromGeoidGrids(NULL).size());
    }

    /**
     * The special case must not have loosened the refusal for anything else: a name that is not
     * exactly {@code "null"} still walks the chain and still fails when nothing resolves it.
     */
    @Test
    public void onlyTheExactNameIsSpecial() {
        String[] nearMisses = {"NULL", "null.gtx", "nullx", "tests/null"};
        for (int i = 0; i < nearMisses.length; i++) {
            try {
                VerticalGrid.fromName(nearMisses[i]);
                fail("\"" + nearMisses[i] + "\" is not PROJ's null grid and resolves to no file");
            } catch (IOException expected) {
                assertTrue(nearMisses[i] + ": " + expected.getMessage(),
                        expected.getMessage().contains("Unknown vertical grid")
                                || expected.getMessage().contains("Unrecognised vertical grid")
                                || expected.getMessage().contains("Refusing vertical grid name"));
            }
        }
    }

    private static double shift(String spec, double lonDeg, double latDeg) {
        double[] coord = {Math.toRadians(lonDeg), Math.toRadians(latDeg), 0.0, 0.0};
        // DEFAULT_MULTIPLIER is -1; pass 1 so the sign of the grid value is the sign of the shift.
        VGridShiftOperator.fromGrids(spec, 1.0).forward(coord);
        return coord[2];
    }
}
