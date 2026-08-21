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
package org.locationtech.proj4j.omerc;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * The nine {@code omerc} rows that {@code proj4-epsg.csv} recorded as {@code "failing"}, and the five
 * {@code ESRI:102631} rows of {@code PROJ4_SPCS_EPSG_nad83.csv} that used to be commented out.
 *
 * <p>Both sets are held here as live assertions rather than as CSV status flags, because a
 * {@code "failing"} marker in a data file overstates the damage the moment it goes stale and nothing
 * fails when it does.
 *
 * <h2>The nine {@code proj4-epsg.csv} rows all pass now</h2>
 *
 * <p>They are EPSG 3376, 3468, 5247, 6394, 26731, 26931, 29871, 29872 and 29873 — every one an
 * {@code omerc} carrying {@code +gamma}, and seven of the nine also {@code +no_uoff}. Residual against
 * the values the CSV pins is at most <b>5e-7 m</b> where the CSV's own tolerance is 0.1 m (0.03048 m
 * for 26731). They were failing on the {@code cos(gamma)}/{@code cos(alpha)} defect and on
 * {@code Gamma} defaulting to {@code 0.0}. The other four rows that upstream marked
 * {@code "failing"} (3388, 3752, 3994, 5641) are {@code merc +lat_ts} and are not this class's
 * business.
 *
 * <p><b>The {@code "failing"} markers are historical.</b> They are how the file arrived from
 * upstream at {@code 59c2f66}, which had 18 of them; eight of the nine {@code omerc} rows were
 * already reclassified to {@code passing} before the 9.8.1 regeneration, and that regeneration
 * cleared the last four {@code merc} ones. The committed file now carries no {@code "failing"}
 * row at all &mdash; which is exactly why these nine live here as executed assertions instead.
 *
 * <h2>The five {@code ESRI:102631} rows: the dictionary was the defect, and it is fixed</h2>
 *
 * <p>See {@link #esri102631MatchesProj981ThroughTheShippedDictionary()} for the fixed definition
 * measured against PROJ, {@link #esri102631WithoutNoUoffIsWrongByTwiceUZero()} for the mechanism,
 * and {@link #everyEsriOmercEntryDeclaresTheVariantProjGivesIt()} for the other sixteen.
 */
public class ObliqueMercatorEpsgWitnessTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();
    // DomainErrorPolicy.THROW explicitly, not the no-arg default. Since 2.4.0 that default is
    // LEGACY_NO_SHIFT, which passes a datum grid COVERAGE miss through unshifted -- exactly the
    // behaviour every assertion in this file exists to rule out. Naming the strict policy keeps
    // these tests measuring the engine, and turns them into the proof that the 2.4.0 default
    // change did not weaken it.
    private static final CoordinateTransformFactory CT_FACTORY =
            new CoordinateTransformFactory(DomainErrorPolicy.THROW);

    private static ProjCoordinate from(String srcCode, String tgt, double lon, double lat) {
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName(srcCode);
        CoordinateReferenceSystem dst = tgt.startsWith("+")
                ? CRS_FACTORY.createFromParameters("tgt", tgt)
                : CRS_FACTORY.createFromName(tgt);
        ProjCoordinate out = new ProjCoordinate();
        CT_FACTORY.createTransform(src, dst).transform(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private static ProjCoordinate fromWgs84(String tgt, double lon, double lat) {
        return from("EPSG:4326", tgt, lon, lat);
    }

    /** {@code PROJ4_SPCS_EPSG_nad83.csv} rows are sourced from EPSG:4269, not EPSG:4326. */
    private static ProjCoordinate fromNad83(String tgt, double lon, double lat) {
        return from("EPSG:4269", tgt, lon, lat);
    }

    private static void row(String code, double lon, double lat,
                            double x, double y, double tolerance) {
        ProjCoordinate out = fromWgs84(code, lon, lat);
        assertEquals(code + " easting", x, out.x, tolerance);
        assertEquals(code + " northing", y, out.y, tolerance);
    }

    /**
     * All nine, at the CSV's own point (1, -1) and the CSV's own expected values and tolerances.
     * Each expected pair is the row from {@code proj4-epsg.csv}, which was auto-generated from
     * PROJ's EPSG database.
     */
    @Test
    public void theNineOmercRowsRecordedAsFailingNowPass() {
        row("EPSG:3376", 1.0, -1.0, -1.2409058238151E7, -4357833.596094, 0.1);
        row("EPSG:3468", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        row("EPSG:5247", 1.0, -1.0, -1.2409058238151E7, -4357833.596094, 0.1);
        row("EPSG:6394", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        // EPSG:26731 has moved to epsg26731IsNowRefusedByTheDatumStageNotByOmerc(), below.
        row("EPSG:26931", 1.0, -1.0, 2.1299477541839E7, -9945672.888623, 0.1);
        row("EPSG:29871", 1.0, -1.0, -616802.381396, -216616.294447, 1.0);
        row("EPSG:29872", 1.0, -1.0, -4.0708957167923E7, -1.4296675428702E7, 1.0);
        row("EPSG:29873", 1.0, -1.0, -1.2408068634417E7, -4357619.119986, 0.1);
    }

    /**
     * The ninth row, and it is no longer an {@code omerc} question at all.
     *
     * <p>{@code EPSG:26731} is the only one of the nine carrying {@code +datum=NAD27}, and the CSV
     * probes it at <b>(1, -1) — the Gulf of Guinea</b>, some 12,000 km from Alaska and outside every
     * grid {@code +datum=NAD27} names. {@code Grid.shift} used to apply no shift there and report
     * success, so the row reached {@code omerc} with an unshifted coordinate and matched the CSV,
     * whose expected value was generated the same way. It is now
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, matching PROJ 9.8.1:
     *
     * <pre>
     * echo "1 -1" | cs2cs -f "%.10f" +proj=longlat +datum=WGS84 \
     *      +to +proj=longlat +ellps=clrk66 +nadgrids=&#64;conus,&#64;alaska,&#64;ntv2_0.gsb,&#64;ntv1_can.dat
     *   *	* inf
     * </pre>
     *
     * <p>(At the CRS level with {@code proj.db} present, {@code cs2cs +to +proj=longlat +datum=NAD27}
     * instead selects <em>"Ballpark geographic offset"</em> — a <b>declared</b> no-op chosen by the
     * operation factory because the point is outside NADCON's area of use. proj4j's legacy
     * {@code +datum=} path has no such factory; it is the operator path, and the operator path
     * errors. Reproducing the ballpark selection is {@code db/}'s and the strict API's job, not
     * {@code Grid}'s.)
     *
     * <p>So the {@code omerc} claim is asserted here with the datum stage taken out of the way: the
     * identical projection parameters on a bare {@code +ellps=clrk66} still produce the CSV's
     * easting and northing. Nothing about {@code ObliqueMercatorProjection} changed.
     *
     * <p>The row in {@code proj4-epsg.csv} <b>has been reclassified</b>: the 9.8.1 regeneration
     * records {@code EPSG:26731} as {@code refuses:COORDINATE_OUTSIDE_GRID}, which asserts this
     * same refusal by cause rather than merely asserting that the row does not pass.
     */
    @Test
    public void epsg26731IsNowRefusedByTheDatumStageNotByOmerc() {
        try {
            ProjCoordinate out = fromWgs84("EPSG:26731", 1.0, -1.0);
            fail("(1, -1) is outside every +datum=NAD27 grid and PROJ answers '* * inf'; proj4j "
                    + "returned (" + out.x + ", " + out.y + ")");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }

        // The same projection, without the datum stage: the omerc arithmetic is unchanged.
        CoordinateReferenceSystem src = CRS_FACTORY.createFromName("EPSG:4326");
        CoordinateReferenceSystem dst = CRS_FACTORY.createFromParameters("26731-no-datum",
                "+proj=omerc +lat_0=57 +lonc=-133.6666666666667 +alpha=323.1301023611111 "
                        + "+k=0.9999 +x_0=5000000.001016002 +y_0=-5000000.001016002 +no_uoff "
                        + "+gamma=323.1301023611111 +ellps=clrk66 +units=us-ft +no_defs");
        ProjCoordinate out = new ProjCoordinate();
        CT_FACTORY.createTransform(src, dst).transform(new ProjCoordinate(1.0, -1.0), out);
        assertEquals("EPSG:26731 easting, datum stage removed", 6.9883986607415E7, out.x, 0.03048);
        assertEquals("EPSG:26731 northing, datum stage removed", -3.2630755864469E7, out.y, 0.03048);
    }

    /**
     * <b>The dictionary-fidelity assertion: {@code ESRI:102631} resolved by name, against PROJ.</b>
     *
     * <p>Everything else in this class builds its target CRS from a hand-written proj-string, which
     * cannot notice a wrong data file. This one goes through
     * {@code CRSFactory.createFromName("ESRI:102631")}, so it reads
     * {@code epsg/src/main/resources/proj4/nad/esri:5541} as shipped and fails if that line changes.
     *
     * <p>The expected value is <b>PROJ 9.8.1's</b>, to twelve places:
     *
     * <pre>
     * printf "55 -134\n" | cs2cs -f "%.9f" EPSG:4269 ESRI:102631
     *   2616018.151567299	1156379.642216892 0.000000000
     * </pre>
     *
     * <p>Not the CSV's {@code 2616018.154 / 1156379.643}. Those are the same point rounded to three
     * decimals of a US survey foot -- about 0.8 mm -- and they differ from PROJ's answer by
     * <b>2.4 mm easting and 0.8 mm northing</b>, against a CSV tolerance of 0.1 ft. Asserting the
     * CSV's value would cap this test's resolution at that rounding; asserting PROJ's pins it to
     * 1e-6 ft. Both are checked here so the 2.4 mm is visible and cannot later be mistaken for a
     * regression.
     *
     * <p>Axis order is <b>not</b> assumed: {@code EPSG:4269} is authority latitude-first, which is
     * why the {@code cs2cs} input above reads {@code 55 -134}. Feeding it lon-first makes 9.8.1
     * answer {@code * * inf} with {@code omerc: Invalid latitude}, and {@code OGC:CRS84} fed
     * lon-first reproduces the latitude-first {@code EPSG:4269} result exactly. proj4j's
     * {@code ProjCoordinate} is always {@code (x = lon, y = lat)}, hence the argument order here.
     */
    @Test
    public void esri102631MatchesProj981ThroughTheShippedDictionary() {
        // From the dictionary, by code. The whole point of this test.
        ProjCoordinate out = fromNad83("ESRI:102631", -134.0, 55.0);

        // cs2cs 9.8.1. 1e-6 ft is 0.3 um; the agreement measured when this was pinned was 1.2e-7 ft.
        assertEquals("ESRI:102631 easting vs PROJ 9.8.1", 2616018.151567299, out.x, 1.0e-6);
        assertEquals("ESRI:102631 northing vs PROJ 9.8.1", 1156379.642216892, out.y, 1.0e-6);

        // And the CSV's own rounded pins, at the CSV's own tolerance. Residual is 2.4 mm / 0.8 mm.
        assertEquals("ESRI:102631 easting vs PROJ4_SPCS_EPSG_nad83.csv:2", 2616018.154, out.x, 0.1);
        assertEquals("ESRI:102631 northing vs PROJ4_SPCS_EPSG_nad83.csv:2", 1156379.643, out.y, 0.1);
    }

    /**
     * The mechanism behind {@link #esri102631MatchesProj981ThroughTheShippedDictionary()}, kept as
     * an assertion because the size of the error is the evidence that it was {@code u_0} and not
     * something else.
     *
     * <p>The five {@code ESRI:102631} rows of {@code PROJ4_SPCS_EPSG_nad83.csv} were commented out
     * for years, four of them tagged <i>"Bug in Proj4J Obl Merc"</i>. They are live again as of this
     * change, and both the comment characters and that tag are gone. <b>The bug was never in Proj4J's
     * {@code omerc}</b> -- fed the definition as it used to ship, PROJ 9.8.1 returns proj4j's wrong
     * answer bit for bit, which is what proves the kernel right and the data file wrong. What was
     * missing was {@code +no_uoff}, so the projection ran EPSG method 9815 (Hotine variant B, with
     * the {@code u_0} origin shift of {@code omerc.cpp:285-290} and the {@code u -= Q->u_0} of
     * {@code :77}) where PROJ 9.8.1's own {@code ESRI:102631} declares method 9812, variant A.
     *
     * <p>The error is a <b>constant vector</b> on all five rows -- {@code dx = -13,718,224.749904}
     * and {@code dy = +18,290,966.333212} US survey feet, magnitude <b>22,863,707.916512 ft =
     * 6,968,872.110697 m</b> -- because a missing {@code u_0} is a translation and nothing else.
     * That is the signature: a rotation or a scale error would vary from point to point.
     * ({@code ESRI:24571} is the one entry in the same sweep whose vector is <em>not</em> constant,
     * because its {@code +gamma} differs from its {@code +alpha}. See the sweep test.)
     *
     * <p>One pre-fix value is worth keeping: row 2 of the CSV returned exactly
     * {@code 16404166.666666680}, which is {@code x_0 / to_meter} -- <b>the false easting itself</b>,
     * the third of the shapes a failure must never take.
     */
    @Test
    public void esri102631WithoutNoUoffIsWrongByTwiceUZero() {
        // The 102631 line as it shipped before the fix: no +no_uoff, no +gamma.
        String withoutNoUoff =
                "+proj=omerc +lat_0=57 +lonc=-133.6666666666667 +alpha=-36.86989764583333"
                + " +k=0.9999 +x_0=4999999.999999999 +y_0=-4999999.999999999 +ellps=GRS80"
                + " +datum=NAD83 +to_meter=0.3048006096012192 +no_defs";

        // PROJ 9.8.1 given this same string answers 16334242.901471 -17134586.690995, and so do we.
        ProjCoordinate wrong = fromNad83(withoutNoUoff, -134.0, 55.0);
        assertEquals(16334242.901471, wrong.x, 1.0e-5);
        assertEquals(-17134586.690995, wrong.y, 1.0e-5);

        // The constant translation, measured against the fixed dictionary at the same point.
        ProjCoordinate right = fromNad83("ESRI:102631", -134.0, 55.0);
        assertEquals("u_0 easting offset", -13718224.749904, right.x - wrong.x, 1.0e-4);
        assertEquals("u_0 northing offset", 18290966.333212, right.y - wrong.y, 1.0e-4);

        // All five CSV rows, from the dictionary, at the CSV's own tolerance.
        double tol = 0.1;
        assertRow("ESRI:102631", -134.0, 55.0, 2616018.154, 1156379.643, tol);
        assertRow("ESRI:102631", -133.66666666666666, 57.0, 2685941.919, 1886799.668, tol);
        assertRow("ESRI:102631", -131.59595333333334, 54.65073722222222,
                3124531.426, 1035343.511, tol);
        assertRow("ESRI:102631", -129.54166666666666, 54.541666666666664,
                3561448.345, 1015025.876, tol);
        assertRow("ESRI:102631", -141.5, 60.5, 1276328.587, 3248159.207, tol);
    }

    /**
     * All seventeen {@code omerc} entries in the shipped {@code esri} dictionary, each one required
     * to carry {@code +no_uoff} if and only if PROJ 9.8.1 gives that code EPSG method <b>9812</b>,
     * Hotine variant A.
     *
     * <p>This exists because the fix was <em>not</em> one token in one line. Establishing the scope
     * meant running {@code projinfo -o WKT2:2019} on each of the seventeen and reading the
     * {@code METHOD} name, and the split is eight to nine:
     *
     * <table border="1">
     * <caption>The seventeen, by EPSG method</caption>
     * <tr><th>method</th><th>{@code +no_uoff}</th><th>codes</th></tr>
     * <tr><td>9812, variant A</td><td>required</td>
     *     <td>24571, 26731, 26931, 102120, 102121, 102122, 102123, 102631</td></tr>
     * <tr><td>9815, variant B</td><td>forbidden</td>
     *     <td>2056, 2057, 21780, 21781, 23700, 29700, 29871, 29872, 29873</td></tr>
     * </table>
     *
     * <p><b>A wrong {@code +no_uoff} is as bad as a missing one</b>, in the same direction and the
     * same order of magnitude, so this test asserts the token's <em>absence</em> on the nine as
     * firmly as its presence on the eight. A blanket sweep over "every {@code esri} {@code omerc}"
     * would have broken all nine.
     *
     * <p>Corroborated independently: eight of the seventeen codes also exist as {@code omerc} in the
     * {@code epsg} dictionary -- 2057, 24571, 26731, 26931, 29700, 29871, 29872, 29873 -- and after
     * the fix the two dictionaries agree on {@code +no_uoff} for all eight. Before it they disagreed
     * on three (24571, 26731, 26931), which is a second, cheaper way to have found this. That
     * agreement is asserted here too, so neither file can drift from the other.
     */
    @Test
    public void everyEsriOmercEntryDeclaresTheVariantProjGivesIt() {
        Set<String> variantA = new TreeSet<String>(Arrays.asList(
                "24571", "26731", "26931", "102120", "102121", "102122", "102123", "102631"));
        Set<String> variantB = new TreeSet<String>(Arrays.asList(
                "2056", "2057", "21780", "21781", "23700", "29700", "29871", "29872", "29873"));

        Map<String, String> esri = omercEntriesOf("esri");
        Set<String> expected = new TreeSet<String>(variantA);
        expected.addAll(variantB);
        assertEquals("the omerc population of proj4/nad/esri", expected,
                new TreeSet<String>(esri.keySet()));

        for (Map.Entry<String, String> entry : esri.entrySet()) {
            boolean wanted = variantA.contains(entry.getKey());
            assertEquals("+no_uoff on esri:" + entry.getKey() + " -- "
                            + (wanted ? "EPSG method 9812, variant A" : "EPSG method 9815, variant B")
                            + " -- in: " + entry.getValue(),
                    wanted, declaresNoUoff(entry.getValue()));
        }

        // The independent corroboration: the two dictionaries must agree wherever they overlap.
        Map<String, String> epsg = omercEntriesOf("epsg");
        int overlap = 0;
        for (Map.Entry<String, String> entry : esri.entrySet()) {
            String other = epsg.get(entry.getKey());
            if (other == null) {
                continue;
            }
            overlap++;
            assertEquals("+no_uoff disagrees between proj4/nad/esri and proj4/nad/epsg on code "
                            + entry.getKey(),
                    declaresNoUoff(other), declaresNoUoff(entry.getValue()));
        }
        assertEquals("omerc codes defined in both dictionaries", 8, overlap);
    }

    /**
     * Upstream ORs the two spellings ({@code omerc.cpp:139-143}), so a definition that used
     * {@code +no_off} would be variant A just as much as one that used {@code +no_uoff}, and a test
     * that looked only for the longer spelling could be defeated by the shorter one. Word-boundary
     * matched so that {@code +no_uoff} does not also count as {@code +no_off}.
     */
    private static boolean declaresNoUoff(String definition) {
        return NO_UOFF.matcher(definition).find();
    }

    private static final Pattern NO_UOFF = Pattern.compile("\\+no_(u)?off(?![\\w])");

    /**
     * The {@code omerc} entries of a shipped dictionary, read as text from the classpath.
     *
     * <p>Deliberately raw rather than through {@code CRSFactory}: the question is what the data file
     * says, and a parsed {@code Projection} has already collapsed {@code +no_uoff} into a boolean
     * that {@code +gamma} and {@code +alpha} can mask. {@code #} starts a comment for
     * {@code Proj4FileReader}'s {@code StreamTokenizer} ({@code Proj4FileReader:168}), so comments
     * are stripped here for the same reason -- a {@code +no_uoff} written inside one would be
     * invisible to the library and must be invisible here.
     */
    private static Map<String, String> omercEntriesOf(String dictionary) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        InputStream in = ObliqueMercatorEpsgWitnessTest.class.getClassLoader()
                .getResourceAsStream("proj4/nad/" + dictionary);
        assertNotNull("proj4/nad/" + dictionary + " is not on the test classpath", in);
        try {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    int hash = line.indexOf('#');
                    if (hash >= 0) {
                        line = line.substring(0, hash);
                    }
                    Matcher m = ENTRY.matcher(line);
                    if (m.find() && m.group(2).contains("+proj=omerc")) {
                        out.put(m.group(1), m.group(2).trim());
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new AssertionError("reading proj4/nad/" + dictionary + ": " + e);
        }
        assertFalse("no omerc entries found in proj4/nad/" + dictionary
                + " -- the parse, not the file, is the likely fault", out.isEmpty());
        return out;
    }

    /** {@code <code> +proj=... <>}, one entry to a line in all five shipped dictionaries. */
    private static final Pattern ENTRY = Pattern.compile("<(\\d+)>([^<]*)");

    private static void assertRow(String def, double lon, double lat,
                                  double x, double y, double tolerance) {
        ProjCoordinate out = fromNad83(def, lon, lat);
        assertEquals("easting at " + lon + "," + lat, x, out.x, tolerance);
        assertEquals("northing at " + lon + "," + lat, y, out.y, tolerance);
    }
}
