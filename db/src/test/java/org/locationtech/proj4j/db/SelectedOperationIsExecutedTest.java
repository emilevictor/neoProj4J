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
package org.locationtech.proj4j.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.api.BestOperationPolicy;
import org.locationtech.proj4j.api.Crs;
import org.locationtech.proj4j.api.CrsOperation;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.api.ProjContext;

/**
 * <strong>The operation the selector chooses is the operation that executes.</strong>
 *
 * <h2>The defect</h2>
 *
 * <p>Until 2.2.0 {@code CrsOperation.fromSelection} built its
 * {@code BasicCoordinateTransform} from {@code source.legacy()} and {@code target.legacy()} exactly
 * as handed in. The chosen {@code CrsOperationCandidate} was stored in a field and surfaced by
 * {@code selectedOperation()}, {@code accuracy()}, {@code areaOfUse()} and {@code describe()} — and
 * was never an argument to anything that computed a coordinate. So the facade named one operation
 * and the engine ran another, with a published accuracy attached to the wrong arithmetic.
 *
 * <h2>The measurement, and where every number comes from</h2>
 *
 * <p>At the Cheshire point this repository already audits
 * ({@code core/src/test/.../LegacyDatumAreaOfUseTest}), lon &minus;2.0301713578021983,
 * lat 53.35168607080468, into {@code +proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000
 * +y_0=-100000 +units=m} on the Airy ellipsoid. Every expected value below is
 * <strong>PROJ 9.8.1's own {@code cs2cs}</strong>, never proj4j's output:
 *
 * <pre>
 * # what selection chooses here: EPSG:1314 "OSGB36 to WGS 84 (6)", method 9606, from proj.db
 * #   sqlite3 proj.db "select tx,ty,tz,rx,ry,rz,scale_difference,translation_uom_code,
 * #                    rotation_uom_code,scale_difference_uom_code,method_code
 * #                    from helmert_transformation where code='1314'"
 * #   -&gt; 446.448|-125.157|542.06|0.15|0.247|0.842|-20.489|9001|9104|9202|9606
 * echo "-2.0301713578021983 53.35168607080468" | cs2cs -d 9 +proj=longlat +datum=WGS84 +to \
 *   +proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000 +ellps=airy \
 *   +units=m +no_defs +towgs84=446.448,-125.157,542.06,0.15,0.247,0.842,-20.489
 *   -&gt; 398089.000827863  383867.000380436
 *
 * # what the engine ran instead: proj4j's own Datum.OSGB36 table, four terms rounded differently
 * ... +towgs84=446.448,-125.157,542.060,0.1502,0.2470,0.8421,-20.4894
 *   -&gt; 398089.003912952  383867.000589373
 *
 * # and for scale, what PROJ picks when the OSTN15 grid IS present (it is not on this classpath)
 * echo "-2.0301713578021983 53.35168607080468" | cs2cs -d 9 EPSG:4326 EPSG:27700   # lat-first in
 *   -&gt; 398088.964408128  383865.216031245
 * </pre>
 *
 * <p>So the two Helmerts differ by <strong>3.085 mm easting</strong> and 0.209 mm northing, and the
 * grid differs from either Helmert by <strong>1.784 m of northing</strong>. Both gaps are the same
 * defect at two scales, and only the smaller one can be demonstrated on a classpath with no grid
 * pack. <strong>The 1.784 m case is not pinned here</strong>, deliberately:
 * {@code uk_os_OSTN15_NTv2_OSGBtoETRS.tif} ships in PROJ-data and not in this repository, so a test
 * asserting it would either fail in CI or quietly assert nothing. {@code RealDatabaseSelectionTest}
 * already pins the <em>refusal</em> that keeps it honest in the meantime.
 *
 * <h2>Why 3 mm is worth a test</h2>
 *
 * <p>Not for its size. For its cause: it is the whole of the difference between "we ran the
 * operation we named" and "we ran something else that happens to be close". A test that only fires
 * on large errors cannot tell those apart, and the same code path carries the 1.784 m case.
 */
public class SelectedOperationIsExecutedTest {

    /** The Cheshire reference point; see the class javadoc. */
    private static final double LON = -2.0301713578021983;
    private static final double LAT = 53.35168607080468;

    /** cs2cs 9.8.1 with EPSG:1314's own parameters. */
    private static final double CHOSEN_E = 398089.000827863;
    private static final double CHOSEN_N = 383867.000380436;

    /** cs2cs 9.8.1 with proj4j's {@code Datum.OSGB36} table: what the engine used to run. */
    private static final double LEGACY_E = 398089.003912952;
    private static final double LEGACY_N = 383867.000589373;

    /**
     * 1e-6 m. Well inside the 3.085 mm gap being measured and well outside the ~1e-9 m at which
     * cs2cs and proj4j agree on identical parameters, so this discriminates rather than accepts
     * both answers.
     */
    private static final double TOL = 1.0e-6;

    private static PjdxDatabase db;
    private static ProjContext ctx;

    @BeforeClass
    public static void openTheRealIndex() throws IOException {
        db = Proj4jDb.open();
        ctx = ProjContext.builder().database(db)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .build();
    }

    @AfterClass
    public static void closeIt() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    /**
     * <strong>WGS 84 to an OSGB36 transverse Mercator runs EPSG:1314's published parameters, not
     * proj4j's built-in OSGB36 table.</strong>
     *
     * <p>The target CRS carries the datum here, and the published operation is
     * {@code EPSG:4277 -> EPSG:4326} used in reverse, so the rewrite lands on the target side. That
     * is the second of the two orientations {@code CandidateParameters} accepts and the one an
     * on-source-only implementation would silently miss.
     */
    @Test
    public void theTargetSideHelmertIsTheOneTheAuthorityPublished() {
        Crs wgs84 = Proj.createCrs("+proj=longlat +datum=WGS84", ctx);
        Crs osgbTm = Proj.createCrs("+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 "
                + "+y_0=-100000 +datum=OSGB36 +units=m +no_defs", ctx);
        CrsOperation op = Proj.createCrsToCrs(wgs84, osgbTm, ctx);

        assertEquals("EPSG:1314", op.selectedOperation().get().authorityCode());
        assertTrue("the selected operation must be the one that executes, and must say so: "
                + op.describe(), op.executionNote().isPresent());
        String note = op.executionNote().get();
        assertTrue("must name the operation it is executing: " + note, note.contains("EPSG:1314"));
        assertTrue("must name the side it rewrote: " + note, note.contains("target"));
        assertTrue("must be the authority's own parameters, in +towgs84's units: " + note,
                note.contains("+towgs84=446.448,-125.157,542.06,0.15,0.247,0.842,-20.489"));

        ProjCoordinate out = new ProjCoordinate();
        op.transform(new ProjCoordinate(LON, LAT), out);

        assertEquals("easting must be cs2cs 9.8.1 on EPSG:1314's parameters", CHOSEN_E, out.x, TOL);
        assertEquals("northing must be cs2cs 9.8.1 on EPSG:1314's parameters", CHOSEN_N, out.y, TOL);

        // The positive control: the old answer is genuinely different, so this test can fail.
        assertFalse("the legacy Datum.OSGB36 answer must be distinguishable at this tolerance, or "
                + "the assertion above proves nothing",
                Math.abs(LEGACY_E - CHOSEN_E) < TOL && Math.abs(LEGACY_N - CHOSEN_N) < TOL);
        assertTrue("this must no longer be the legacy table's answer",
                Math.abs(out.x - LEGACY_E) > TOL);
    }

    /**
     * <strong>The mirror orientation: OSGB36 as the source, WGS 84 as the target, rewrite on the
     * source side.</strong>
     *
     * <p>Same operation, same parameters, published forwards this time. The round trip back through
     * the target-side form must land on the input, which is the cheapest available check that the
     * two orientations install the same transformation and not two that merely look alike.
     */
    @Test
    public void theSourceSideOrientationInstallsTheSameTransformation() {
        Crs osgb = Proj.createCrs("+proj=longlat +datum=OSGB36", ctx);
        Crs wgs84 = Proj.createCrs("+proj=longlat +datum=WGS84", ctx);
        CrsOperation forward = Proj.createCrsToCrs(osgb, wgs84, ctx);

        assertEquals("EPSG:1314", forward.selectedOperation().get().authorityCode());
        assertTrue(forward.describe(), forward.executionNote().isPresent());
        assertTrue("must have rewritten the source side: " + forward.executionNote().get(),
                forward.executionNote().get().contains("source"));

        CrsOperation backward = Proj.createCrsToCrs(wgs84, osgb, ctx);
        assertTrue(backward.describe(), backward.executionNote().isPresent());
        assertTrue("must have rewritten the target side: " + backward.executionNote().get(),
                backward.executionNote().get().contains("target"));

        ProjCoordinate inWgs = new ProjCoordinate(-2.0, 53.0);
        ProjCoordinate inOsgb = new ProjCoordinate();
        ProjCoordinate roundTrip = new ProjCoordinate();
        backward.transform(inWgs, inOsgb);
        forward.transform(inOsgb, roundTrip);

        // 2e-8 degrees is about 2.2 mm, and the measured closure is 1.23e-8 deg of longitude.
        // It is NOT zero, and the reason is worth stating rather than absorbing into a loose
        // tolerance: the legacy datum model inverts a seven-parameter Helmert by negating its
        // parameters, which is PROJ.4's own approximation and is second-order accurate, not exact.
        // That is precisely why CandidateParameters refuses to install a hub-to-CRS publication by
        // negation -- the approximation is acceptable as the engine's round trip and is not
        // acceptable as "we ran the operation the authority published".
        assertEquals(inWgs.x, roundTrip.x, 2.0e-8);
        assertEquals(inWgs.y, roundTrip.y, 2.0e-8);
    }

    /**
     * <strong>A candidate the datum model cannot express is refused by name and left on the legacy
     * path, rather than being approximated.</strong>
     *
     * <p>{@code EPSG:4267 -> EPSG:4269} is NAD27 to NAD83: nine published grid operations, no WGS 84
     * end at either side, and no grid file on this classpath. There is nothing here a
     * {@code +towgs84=} could honestly carry, so {@code executionNote()} must be <em>empty</em> and
     * the warnings must say what could not be expressed. An implementation that quietly produced a
     * plan for this pair would be inventing a transformation.
     */
    @Test
    public void anInexpressibleCandidateSaysSoInsteadOfApproximating() {
        Crs nad27 = Proj.createCrs("EPSG:4267", ctx);
        Crs nad83 = Proj.createCrs("EPSG:4269", ctx);
        CrsOperation op = Proj.createCrsToCrs(nad27, nad83, ProjContext.builder()
                .database(db)
                .bestOperationPolicy(BestOperationPolicy.ALLOW_DEGRADED)
                .gridPolicy(org.locationtech.proj4j.api.GridPolicy.WARN)
                .build());

        assertFalse("neither end of a NAD27->NAD83 operation is the WGS 84 hub, so the legacy datum "
                + "model's \"shift to WGS 84\" cannot carry it: " + op.describe(),
                op.executionNote().isPresent());
        assertTrue("the warnings must state that the selected operation is not what runs: "
                + op.warnings(), warningsContain(op, "not executed directly"));
        assertTrue("and must name the operation and its published direction: " + op.warnings(),
                warningsContain(op, "WGS 84 hub"));
    }

    private static boolean warningsContain(CrsOperation op, String needle) {
        for (int i = 0; i < op.warnings().size(); i++) {
            if (op.warnings().get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
