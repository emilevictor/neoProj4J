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
package org.locationtech.proj4j.datum;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The generic (N-sample) grid layer: {@link GenericGrid} and {@link GenericGridSet}.
 *
 * <h2>Fixture provenance</h2>
 *
 * <p>{@code subset_of_gr3df97a.tif} is a <strong>byte-for-byte copy of PROJ 9.8.1's own
 * {@code data/tests/subset_of_gr3df97a.tif}</strong> — {@code git -C PROJ show
 * 9.8.1:data/tests/subset_of_gr3df97a.tif}, 3,215 bytes, SHA-256
 * {@code e503665e33f19d2f5394fbaf7670c40971471695db81bba7552a8c43de2e2353}, the same bytes the
 * conformance module already ships under {@code proj-data/tests/}. It is a 10&times;10 Float32
 * three-band NTF&rarr;RGF93 geocentric translation, dataset {@code TYPE=GEOCENTRIC_TRANSLATION},
 * bands described {@code x_translation}, {@code y_translation}, {@code z_translation}, origin
 * (4.45&deg;, 44.55&deg;), pixel size (0.1&deg;, &minus;0.1&deg;), {@code AREA_OR_POINT=Point}.
 * The other two fixtures used here were already present.
 *
 * <h2>Expected-value provenance</h2>
 *
 * <p>Node values were read out of the same bytes by {@code gdallocationinfo -valonly}, which
 * indexes from the <em>north-west</em> corner; every assertion below therefore states which corner
 * it means, because the flip is exactly the sort of thing that passes a test written against the
 * code under test.
 */
public class GenericGridSetTest {

    private static final String GENERIC = "subset_of_gr3df97a.tif";
    private static final String SUBGRIDS = "test_hgrid_with_subgrid.tif";
    private static final String NODATA = "test_vgrid_nodata.tif";

    /** {@code float} literals from a Float32 file: exact, but compared with slack anyway. */
    private static final float EPS = 1e-4f;

    // ------------------------------------------------------------------ the null grid

    /**
     * {@code +grids=null} ({@code grids.cpp:2970-3000}): one grid, no samples, every coordinate
     * covered, every value zero. Note that it is returned <em>without</em> an extent test, which
     * is what makes it a universal no-op.
     */
    @Test
    public void theNullGridSetCoversEveryCoordinateAndShiftsNothing() throws IOException {
        GenericGridSet set = GenericGridSet.open("null");
        assertEquals("null", set.getFormat());
        assertEquals(1, set.grids().size());

        GenericGrid grid = set.gridAt(Math.toRadians(-179.0), Math.toRadians(-89.0));
        assertNotNull(grid);
        assertTrue(grid.isNullGrid());
        assertEquals("the null grid has no samples at all", 0, grid.samplesPerPixel());

        double[] out = new double[3];
        GenericGrid.interpolateThreeSamples(grid, 0.0, 0.0, 0, 1, 2, out);
        assertEquals(0.0, out[0], 0.0);
        assertEquals(0.0, out[1], 0.0);
        assertEquals(0.0, out[2], 0.0);
    }

    /**
     * The sample-count refusal an operator makes is ordered <em>after</em> the null-grid test
     * ({@code xyzgridshift.cpp:71-79}), and this is why: the null grid would fail it.
     */
    @Test
    public void theNullGridWouldFailTheThreeSampleTestItIsNeverAsked() throws IOException {
        GenericGrid grid = GenericGridSet.open("null").grids().get(0);
        assertTrue(grid.isNullGrid());
        assertTrue("so isNullGrid() must be checked first", grid.samplesPerPixel() < 3);
    }

    // -------------------------------------------------------------------- description

    @Test
    public void aGenericGridReportsItsExtentSamplesAndDatasetType() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));

        assertEquals(10, grid.width());
        assertEquals(10, grid.height());
        assertEquals(3, grid.samplesPerPixel());
        assertTrue(grid.isGeographic());
        assertEquals("GEOCENTRIC_TRANSLATION", grid.type());

        // The extent is the NODE extent, and it is NOT gdalinfo's "Origin".
        //
        // The file's ModelTiepoint is (4.5, 44.5) and GTRasterTypeGeoKey is RasterPixelIsPoint.
        // PROJ takes the tiepoint as the centre of node (0, 0) and applies its half-pixel shift
        // only for RasterPixelIsArea (grids.cpp:1289-1292), so west is 4.5. gdalinfo reports
        // Origin = 4.45 because it converts a point-sampled raster to a corner-based geotransform
        // on the way out. Reading a number off gdalinfo and calling it the grid's west edge is
        // half a cell wrong -- 5.5 km here -- and every value it produces still looks plausible.
        double[] extent = grid.extentRadians();
        assertEquals(4.5, Math.toDegrees(extent[0]), 1e-9);
        assertEquals(43.6, Math.toDegrees(extent[1]), 1e-9);   // 44.5 - 9 * 0.1
        assertEquals(5.4, Math.toDegrees(extent[2]), 1e-9);    // 4.5 + 9 * 0.1
        assertEquals(44.5, Math.toDegrees(extent[3]), 1e-9);
    }

    /** The three role vocabularies live in the operators; the grid only exposes the strings. */
    @Test
    public void bandDescriptionsAndUnitsAreExposedVerbatim() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));
        assertEquals("x_translation", grid.description(0));
        assertEquals("y_translation", grid.description(1));
        assertEquals("z_translation", grid.description(2));
        // No UNITTYPE anywhere in this file. Empty, not "metre": xyzgridshift accepts either, and
        // conflating them would hide a file that really does declare a unit.
        assertEquals("", grid.unit(0));
        assertEquals("", grid.unit(1));
        assertEquals("", grid.unit(2));
    }

    // --------------------------------------------------------------------- node access

    /**
     * Row 0 is the <b>southernmost</b> row. {@code gdallocationinfo} indexes from the north-west,
     * so its (0, 9) is this reader's (0, 0).
     */
    @Test
    public void rowZeroIsTheSouthernmostRow() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));
        assertEquals(-167.884995f, grid.valueAt(0, 0, 0), EPS);   // gdal (0, 9)
        assertEquals(-167.595001f, grid.valueAt(0, 9, 0), EPS);   // gdal (0, 0)
        assertEquals(-167.615997f, grid.valueAt(1, 9, 0), EPS);   // gdal (1, 0)
        assertEquals(-59.798999f, grid.valueAt(0, 9, 1), EPS);
        assertEquals(319.572998f, grid.valueAt(0, 9, 2), EPS);
    }

    /** A sample index or a node outside the grid is a programming error, not a sentinel value. */
    @Test
    public void anOutOfRangeNodeOrSampleThrows() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));
        assertThrowsIndex(grid, 0, 0, 3);
        assertThrowsIndex(grid, 0, 0, -1);
        assertThrowsIndex(grid, 10, 0, 0);
        assertThrowsIndex(grid, 0, 10, 0);
    }

    /**
     * {@code GenericShiftGrid::valuesAt} ({@code grids.cpp:3068-3083}) writes
     * <b>sample-innermost</b>: for each row, for each column, for each requested sample. Getting
     * this wrong transposes a window silently.
     */
    @Test
    public void valuesAtIsSampleInnermost() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));
        int[] samples = {2, 0};
        float[] out = new float[2 * 2 * 2];
        boolean nodata = grid.valuesAt(3, 4, 2, 2, samples, out);
        assertFalse("this file declares no nodata value", nodata);

        int k = 0;
        for (int y = 4; y < 6; y++) {
            for (int x = 3; x < 5; x++) {
                for (int s = 0; s < samples.length; s++) {
                    assertEquals("out[" + k + "] is (x=" + x + ", y=" + y + ", sample="
                            + samples[s] + ")", grid.valueAt(x, y, samples[s]), out[k], 0.0f);
                    k++;
                }
            }
        }
        assertEquals(out.length, k);
    }

    /**
     * A windowed read reports nodata as a <b>distinct outcome</b> rather than as a value: the
     * caller gets the numbers <em>and</em> the fact that some of them are the nodata marker. The
     * border of {@code test_vgrid_nodata.tif} is {@code -88.8888} and its interior is 10.
     */
    @Test
    public void aWindowedReadReportsNodataAsItsOwnOutcome() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(NODATA));
        assertEquals(4, grid.width());
        assertEquals(4, grid.height());

        float[] interior = new float[4];
        assertFalse("the 2x2 interior is all 10",
                grid.valuesAt(1, 1, 2, 2, new int[] {0}, interior));
        for (int i = 0; i < interior.length; i++) {
            assertEquals(10.0f, interior[i], EPS);
        }

        float[] corner = new float[4];
        assertTrue("the south-west 2x2 touches the nodata border",
                grid.valuesAt(0, 0, 2, 2, new int[] {0}, corner));
        // ... and the values are still returned, unaltered. Nodata is reported, not substituted.
        assertEquals(-88.8888f, corner[0], EPS);
        assertTrue(grid.isNodata(corner[0]));
        assertFalse(grid.isNodata(10.0f));
    }

    @Test
    public void aTooShortOutputArrayIsRefusedRatherThanOverrun() throws IOException {
        GenericGrid grid = only(GenericGridSet.open(GENERIC));
        try {
            grid.valuesAt(0, 0, 2, 2, new int[] {0, 1, 2}, new float[11]);
            fail("expected an IllegalArgumentException: 2 * 2 * 3 needs 12 floats");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("12"));
        }
    }

    // ------------------------------------------------------------------------- lookup

    @Test
    public void aPointInsideTheExtentSelectsTheGridAndOneOutsideSelectsNothing()
            throws IOException {
        GenericGridSet set = GenericGridSet.open(GENERIC);
        assertNotNull(set.gridAt(Math.toRadians(4.9), Math.toRadians(44.1)));
        assertNull("east of the extent", set.gridAt(Math.toRadians(6.0), Math.toRadians(44.1)));
        assertNull("north of the extent", set.gridAt(Math.toRadians(4.9), Math.toRadians(45.0)));
        // Inclusive on all four edges, with no tolerance slack: isPointInExtent's eps is 0.
        // The edges are taken from the grid rather than recomputed, because with eps = 0 a
        // one-ulp difference between `5.4 * (PI/180)` and `Math.toRadians(5.4)` is the whole
        // answer -- as this assertion found when it was written the other way.
        double[] extent = only(set).extentRadians();
        assertNotNull(set.gridAt(extent[0], extent[1]));
        assertNotNull(set.gridAt(extent[2], extent[3]));
        // ... and a whisker outside one is outside, which is what "no slack" buys.
        assertNull(set.gridAt(Math.nextDown(extent[0]), Math.toRadians(44.0)));
        assertNull(set.gridAt(Math.toRadians(4.9), Math.nextUp(extent[3])));
    }

    /**
     * The dataset-level {@code TYPE} is a <b>lookup-time selector</b>, not just a build-time
     * bucket: one file can hold a horizontal and a vertical grid over the same area, and the
     * caller says which it wants ({@code grids.cpp:3185-3201}).
     */
    @Test
    public void theTypeSelectorFiltersTheLookup() throws IOException {
        GenericGridSet set = GenericGridSet.open(GENERIC);
        double lam = Math.toRadians(4.9);
        double phi = Math.toRadians(44.1);
        assertNotNull(set.gridAt("GEOCENTRIC_TRANSLATION", lam, phi));
        assertNull("a type this file does not declare", set.gridAt("VERTICAL_OFFSET", lam, phi));
        assertNull("and the empty type matches nothing here either", set.gridAt("", lam, phi));
    }

    /** A typed lookup must not pick up {@code +grids=null}, whose type is empty. */
    @Test
    public void theNullGridIsNotReturnedByATypedLookup() throws IOException {
        GenericGridSet set = GenericGridSet.open("null");
        assertNotNull(set.gridAt(0.0, 0.0));
        assertNull(set.gridAt("HORIZONTAL_OFFSET", 0.0, 0.0));
    }

    /** {@code pj_find_generic_grid}: first set that covers the point wins, and the search stops. */
    @Test
    public void findTakesTheFirstSetThatCoversThePoint() throws IOException {
        List<GenericGridSet> sets = GenericGridSet.fromGridsSpec(GENERIC + ",null");
        assertEquals(2, sets.size());
        GenericGrid inside = GenericGridSet.find(sets, Math.toRadians(4.9), Math.toRadians(44.1));
        assertFalse("the real grid comes first and covers the point", inside.isNullGrid());
        GenericGrid outside = GenericGridSet.find(sets, Math.toRadians(0.0), Math.toRadians(0.0));
        assertTrue("and the null grid catches everything else", outside.isNullGrid());
    }

    // ---------------------------------------------------------------------- hierarchy

    /**
     * {@code test_hgrid_with_subgrid.tif} is four IFDs: two named roots (CAwest, CAeast) and two
     * IFDs that name a parent. A point inside a nested subgrid must reach the subgrid, not the
     * root that contains it.
     */
    @Test
    public void aNamedSubgridIsNestedUnderItsParentAndWinsAtLookup() throws IOException {
        GenericGridSet set = GenericGridSet.open(SUBGRIDS);

        // ALbanff is 11x21 at 0.008333 deg on nodes (-115.583333 .. -115.5, 51.083333 .. 51.25),
        // strictly inside CAwest (-115.75 .. -115.0, 50.916667 .. 51.666667).
        GenericGrid deep = set.gridAt(Math.toRadians(-115.55), Math.toRadians(51.2));
        assertNotNull(deep);
        assertTrue("expected the subgrid, got " + deep.getName(),
                deep.getName().endsWith("ALbanff"));
        assertEquals("and it is reached through CAwest, not alongside it",
                2, set.grids().get(0).countGrids());

        // A point in CAwest but outside the subgrid stays on the parent.
        GenericGrid shallow = set.gridAt(Math.toRadians(-115.7), Math.toRadians(51.6));
        assertTrue("expected the root, got " + shallow.getName(),
                shallow.getName().endsWith("CAwest"));
    }

    /**
     * <b>A declared {@code parent_grid_name} is advisory; the extents are authoritative</b>
     * ({@code insertIntoHierarchy}, {@code grids.cpp:1397-1414}). If the named parent does not
     * contain the child, upstream logs at DEBUG and falls through to the bounding-box search — and
     * if that finds nobody either, the child becomes another <em>root</em>.
     *
     * <p>This is not hypothetical, and it is the reason this test exists: ONtronto declares
     * {@code parent_grid_name=CAeast} and is not inside CAeast. Its west node is &minus;80.541667
     * and CAeast's is &minus;80.5, so it hangs 0.041667&deg; off the western edge. So the file has
     * <b>three</b> roots, not two, and the lookup consequences below are visible to users.
     */
    @Test
    public void aSubgridThatEscapesItsDeclaredParentBecomesARootInstead() throws IOException {
        GenericGridSet set = GenericGridSet.open(SUBGRIDS);
        assertEquals("three roots, because ONtronto did not fit in CAeast",
                3, set.grids().size());
        assertEquals(2, set.grids().get(0).countGrids());   // CAwest + ALbanff
        assertEquals(1, set.grids().get(1).countGrids());   // CAeast, childless
        assertEquals(1, set.grids().get(2).countGrids());   // ONtronto, promoted
        assertTrue(set.grids().get(2).getName().endsWith("ONtronto"));

        // Where the two overlap, CAeast is found first and ONtronto is unreachable: the set scans
        // its roots in file order and the first cover wins.
        GenericGrid overlap = set.gridAt(Math.toRadians(-80.48), Math.toRadians(44.55));
        assertTrue("expected CAeast to shadow it, got " + overlap.getName(),
                overlap.getName().endsWith("CAeast"));

        // The sliver west of CAeast is the only place ONtronto answers.
        GenericGrid sliver = set.gridAt(Math.toRadians(-80.52), Math.toRadians(44.55));
        assertTrue("expected ONtronto, got " + sliver.getName(),
                sliver.getName().endsWith("ONtronto"));
    }

    /**
     * Cross-IFD metadata inheritance ({@code grids.cpp:3033-3038}), and the surprising half of it:
     * the donor is the <b>first root</b>, not the grid's own parent.
     *
     * <p>ONtronto declares no {@code TYPE} and names CAeast as its parent, and CAeast declares
     * both a {@code TYPE} and two band {@code DESCRIPTION}s. ONtronto still inherits from CAwest,
     * the first root, which declares a {@code TYPE} and no descriptions. So ONtronto's type is
     * filled in and its band description is <em>not</em> — which is only possible if the donor is
     * CAwest. Inheriting from the named parent instead would be an easy and completely silent
     * "fix", and the second assertion here is the only thing that would catch it.
     *
     * <p>Note also that the inheritance is decided from the IFD's <em>own</em> {@code TYPE} before
     * it is placed, so it does not care that ONtronto ends up a root rather than a child.
     */
    @Test
    public void aTypelessIfdInheritsFromTheFirstRootAndNotFromItsParent() throws IOException {
        GenericGridSet set = GenericGridSet.open(SUBGRIDS);
        GenericGrid caEast = set.grids().get(1);
        assertTrue(caEast.getName().endsWith("CAeast"));
        assertEquals("latitude_offset", caEast.description(0));

        GenericGrid onTronto = set.grids().get(2);
        assertTrue(onTronto.getName().endsWith("ONtronto"));
        assertEquals("TYPE is inherited", "HORIZONTAL_OFFSET", onTronto.type());
        assertEquals("but not the named parent's band description", "", onTronto.description(0));
    }

    /** The first root declares its own {@code TYPE}, so it inherits nothing and needs nobody. */
    @Test
    public void theFirstRootIsItsOwnAuthority() throws IOException {
        GenericGridSet set = GenericGridSet.open(SUBGRIDS);
        assertEquals("HORIZONTAL_OFFSET", set.grids().get(0).type());
    }

    // ----------------------------------------------------------- loading and refusals

    @Test
    public void anOptionalGridThatIsMissingIsSkippedSilently() throws IOException {
        List<GenericGridSet> sets =
                GenericGridSet.fromGridsSpec("@definitely_not_a_grid," + GENERIC);
        assertEquals(1, sets.size());
        assertEquals(GENERIC, sets.get(0).getName());
    }

    @Test
    public void aRequiredGridThatIsMissingIsAnError() {
        try {
            GenericGridSet.fromGridsSpec("definitely_not_a_grid");
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Unknown generic grid"));
        }
    }

    @Test
    public void aFileThatIsNotATiffIsRefusedByFormatRatherThanMisread() {
        try {
            GenericGridSet.open("conus");
            fail("expected an IOException: conus is CTable2, not GeoTIFF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Unrecognized generic grid format"));
        }
    }

    /** The same name-safety rule the other two grid readers apply, applied before any I/O. */
    @Test
    public void aTraversingNameIsRefusedBeforeTheResolverChainIsConsulted() {
        try {
            GenericGridSet.open("../../etc/passwd");
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Refusing"));
        }
    }

    // --------------------------------------------------------------------- the cache

    /**
     * One parse per name, and the parsed set is shared. This is also the reason a grid holds a
     * metadata <em>snapshot</em> rather than the {@code GeoTiffImage} it came from: the image
     * retains the whole file's bytes, and a cached set that retained them would be charged for
     * node data it had already decoded.
     */
    @Test
    public void aSetIsParsedOnceAndSharedThereafter() throws IOException {
        GenericGridSet first = GenericGridSet.open(GENERIC);
        GenericGridSet second = GenericGridSet.open(GENERIC);
        assertSame(first, second);

        // 10 * 10 nodes * 3 planes * 4 bytes = 1200, plus the metadata allowance.
        assertTrue("accounted size was " + first.sizeBytes(), first.sizeBytes() >= 1200L);
        assertTrue("the generic cache draws on the shared budget",
                GridCache.generic().maxBytes() == GridCache.instance().maxBytes());
    }

    // ------------------------------------------------------------------------ helpers

    private static GenericGrid only(GenericGridSet set) {
        assertEquals("expected a single top-level grid", 1, set.grids().size());
        return set.grids().get(0);
    }

    private static void assertThrowsIndex(GenericGrid grid, int x, int y, int sample) {
        try {
            grid.valueAt(x, y, sample);
            fail("expected an IndexOutOfBoundsException for (" + x + ", " + y + ", " + sample
                    + ")");
        } catch (IndexOutOfBoundsException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
