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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.spi.DbConversion;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * The positive control for the field comparisons {@code gen/VerifyIndex} makes: it shows that the
 * fields the verifier compares actually take more than one value in the shipped index, so a comparison
 * that passes has discriminated rather than matched a default against a default.
 * <p>
 * The verifier cannot run in an ordinary build — it needs a {@code sqlite3} dump of {@code proj.db},
 * which only {@code -Pregen-db} produces — so a field it forgets to compare stays forgotten quietly.
 * Eleven such fields were found by reading {@link org.locationtech.proj4j.db.gen.GenerateIndex}'s
 * emitters against the verifier's assertions, and every one of them is covered here by a count read out
 * of the shipped index. If a future change drops one of those assertions, this class still passes — it
 * is not a substitute for the verifier. What it rules out is the other failure: an assertion that is
 * present, green, and comparing nothing.
 * <p>
 * Counts are pinned rather than tested as "greater than zero" because the index is pinned too, by
 * SHA-256 in {@code db/pom.xml}. A PROJ data refresh is expected to move these numbers, and moving them
 * deliberately is the point; a number that moved without anyone deciding to move it is the failure.
 * <p>
 * Everything here goes through {@link ProjDatabase}. Reaching into the file layout would make this a
 * second reader rather than a check on the one that ships.
 */
public class VerifyIndexFieldCoverageTest {

    private static ProjDatabase db;

    /** Every CRS in the index, which is the root every other population below is reached from. */
    private static List<DbObjectRef> crsRefs;

    @BeforeClass
    public static void open() throws IOException {
        db = Proj4jDb.open(VerifyIndexFieldCoverageTest.class.getClassLoader());
        assertNotNull("proj4j-db.pjdx is not on the test classpath", db);
        crsRefs = db.crsCodes(null);
        // A population that came back empty would make every test below pass by selecting nothing.
        assertEquals("CRSs in the shipped index", 13790, crsRefs.size());
    }

    @AfterClass
    public static void close() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    private static List<DbCrs> crssOfType(DbObjectType type) {
        List<DbCrs> out = new ArrayList<DbCrs>();
        for (DbObjectRef r : crsRefs) {
            if (r.type() == type) {
                out.add(db.crs(r.authName(), r.code()));
            }
        }
        return out;
    }

    /**
     * A vertical datum's row omits the ellipsoid and prime meridian that a geodetic one carries, so the
     * reader has to resume at a different field. {@code publication_date} is the first field after that
     * gap and 197 vertical datums have one, which is what makes the verifier's new comparison of it
     * able to fail. {@code frame_reference_epoch} is nearly always NaN — two rows are not, and those two
     * are the whole margin, so this is the assertion to look at if the count ever reaches zero.
     */
    @Test
    public void verticalDatumsCarryPublicationDatesAndTwoFrameEpochs() {
        Set<DbObjectRef> datums = new LinkedHashSet<DbObjectRef>();
        for (DbCrs crs : crssOfType(DbObjectType.VERTICAL_CRS)) {
            if (crs.datum() != null) {
                datums.add(crs.datum());
            }
        }
        assertEquals("vertical datums reachable from a vertical CRS", 553, datums.size());

        int withDate = 0;
        int withEpoch = 0;
        for (DbObjectRef r : datums) {
            DbDatum d = db.datum(DbObjectType.VERTICAL_DATUM, r.authName(), r.code());
            assertNotNull("vertical datum " + r + " is referenced but absent", d);
            if (d.publicationDate() != null) {
                withDate++;
            }
            if (!Double.isNaN(d.frameReferenceEpoch())) {
                withEpoch++;
            }
        }
        assertEquals("vertical datums with a publication date", 197, withDate);
        assertEquals("vertical datums with a frame reference epoch", 2, withEpoch);
    }

    /**
     * The vertical CRS row's coordinate system and deprecated flag. The coordinate system is emitted
     * before the datum and both are EPSG (authority, code) pairs, so a reader that dropped one would
     * produce a reference that looks entirely reasonable; ten distinct coordinate systems across 609
     * rows means a constant would not survive the verifier's comparison either.
     */
    @Test
    public void verticalCrssVaryByCoordinateSystemAndDeprecation() {
        List<DbCrs> vertical = crssOfType(DbObjectType.VERTICAL_CRS);
        assertEquals("vertical CRSs", 609, vertical.size());

        Set<String> coordinateSystems = new TreeSet<String>();
        int deprecated = 0;
        for (DbCrs crs : vertical) {
            assertNotNull(crs + " has no coordinate system", crs.coordinateSystem());
            coordinateSystems.add(crs.coordinateSystem().authorityCode());
            if (crs.deprecated()) {
                deprecated++;
            }
        }
        assertEquals("distinct coordinate systems among vertical CRSs", 10, coordinateSystems.size());
        assertEquals("deprecated vertical CRSs", 11, deprecated);
    }

    /** The compound CRS deprecated flag, which is the last field of that row. */
    @Test
    public void someCompoundCrssAreDeprecated() {
        List<DbCrs> compound = crssOfType(DbObjectType.COMPOUND_CRS);
        assertEquals("compound CRSs", 702, compound.size());
        int deprecated = 0;
        for (DbCrs crs : compound) {
            if (crs.deprecated()) {
                deprecated++;
            }
        }
        assertEquals("deprecated compound CRSs", 11, deprecated);
    }

    /**
     * The one newly compared field with no variation to find: an engineering CRS row is a name and a
     * deprecated flag, and all 15 flags are false. The verifier's comparison of it therefore cannot
     * fail on a dropped flag — only on one read from the wrong offset — and saying so here is more
     * honest than leaving someone to assume the field is covered the way the others are.
     */
    @Test
    public void engineeringCrssAreAllCurrentSoThatFlagHasNothingToDiscriminate() {
        List<DbCrs> engineering = crssOfType(DbObjectType.ENGINEERING_CRS);
        assertEquals("engineering CRSs", 15, engineering.size());
        for (DbCrs crs : engineering) {
            assertFalse(crs + " is deprecated; this test's premise no longer holds and the"
                    + " engineering_crs.deprecated comparison in VerifyIndex is now discriminating",
                    crs.deprecated());
        }
    }

    /**
     * The conversion deprecated flag, emitted after the parameter list — so it is the field a
     * miscounted parameter block corrupts first. The table has 4,312 rows and 911 deprecated ones;
     * only conversions a projected CRS refers to are reachable through the SPI, which is 4,106 of
     * them, so the counts below are of that subset rather than of the whole table.
     */
    @Test
    public void someConversionsAreDeprecated() {
        Set<DbObjectRef> conversions = new LinkedHashSet<DbObjectRef>();
        for (DbCrs crs : crssOfType(DbObjectType.PROJECTED_CRS)) {
            if (crs.conversion() != null) {
                conversions.add(crs.conversion());
            }
        }
        assertEquals("conversions reachable from a projected CRS", 4106, conversions.size());
        int deprecated = 0;
        for (DbObjectRef r : conversions) {
            DbConversion cv = db.conversion(r.authName(), r.code());
            assertNotNull("conversion " + r + " is referenced but absent", cv);
            if (cv.deprecated()) {
                deprecated++;
            }
        }
        assertEquals("deprecated conversions", 784, deprecated);
    }

    /**
     * The Helmert method authority, method code and operation version. The verifier resolves the method
     * <em>name</em> through the same (authority, code) pair it is checking, so the name would still
     * match if the index had stored the pair swapped or dropped it; these are the fields that say the
     * pair itself survived. Twenty distinct method codes over 2,734 rows.
     */
    @Test
    public void helmertRowsCarryAMethodPairAndUsuallyAnOperationVersion() {
        List<DbOperation> helmert = operationsOfType(DbObjectType.HELMERT_TRANSFORMATION);
        assertEquals("Helmert transformations", 2734, helmert.size());

        Set<String> methodCodes = new TreeSet<String>();
        int withVersion = 0;
        for (DbOperation op : helmert) {
            assertEquals(op + " method authority", "EPSG", op.methodAuthName());
            assertNotNull(op + " has no method code", op.methodCode());
            methodCodes.add(op.methodCode());
            if (op.operationVersion() != null) {
                withVersion++;
            }
        }
        assertEquals("distinct Helmert method codes", 20, methodCodes.size());
        assertEquals("Helmert rows with an operation version", 2362, withVersion);
    }

    /**
     * The concatenated operation deprecated flag. A deprecated operation is still returned by
     * {@code operationsBetween}, so a caller that filters on this flag is reading a value that nothing
     * else in the verifier compared.
     */
    @Test
    public void someConcatenatedOperationsAreDeprecated() {
        List<DbOperation> concatenated = operationsOfType(DbObjectType.CONCATENATED_OPERATION);
        assertEquals("concatenated operations", 374, concatenated.size());
        int deprecated = 0;
        for (DbOperation op : concatenated) {
            if (op.deprecated()) {
                deprecated++;
            }
        }
        assertEquals("deprecated concatenated operations", 63, deprecated);
    }

    /**
     * Every operation of one kind, reached the only way the SPI allows: through the source-CRS index,
     * from every CRS. That happens to reach all 4,751 operations, which the pinned counts above assert
     * rather than assume.
     */
    private static List<DbOperation> operationsOfType(DbObjectType type) {
        Set<DbObjectRef> refs = new LinkedHashSet<DbObjectRef>();
        for (DbObjectRef crs : crsRefs) {
            for (DbObjectRef op : db.operationsWithSourceCrs(crs.authName(), crs.code())) {
                if (op.type() == type) {
                    refs.add(op);
                }
            }
        }
        List<DbOperation> out = new ArrayList<DbOperation>(refs.size());
        for (DbObjectRef r : refs) {
            DbOperation op = db.operation(r.authName(), r.code());
            assertNotNull("operation " + r + " is indexed but absent", op);
            assertTrue(r + " decoded as " + op.kind(), op.kind() == type);
            out.add(op);
        }
        return out;
    }
}
