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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbSupersession;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Covers {@link ProjDatabase#supersededBy} and {@link ProjDatabase#replacementsFor}, the two
 * object-keyed lookups that nothing else in the suite exercises against the shipped index.
 * <p>
 * Both resolve their key the same way — table tag, then authority, then code — and the three ways
 * that can come up empty are easy to conflate: the object may be null, one of its strings may be
 * absent from the file's string pool, or both strings may be present and simply key no row. All
 * three must give back an empty list rather than a null or an exception, and all three are asserted
 * here.
 * <p>
 * Every expected value here was read out of {@code proj.db} with {@code sqlite3}, not from memory.
 */
public class PjdxSupersessionTest {

    private static ProjDatabase db;

    @BeforeClass
    public static void open() throws IOException {
        db = Proj4jDb.open(PjdxSupersessionTest.class.getClassLoader());
        assertNotNull("proj4j-db.pjdx is not on the test classpath", db);
    }

    @AfterClass
    public static void close() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    /**
     * EPSG:1066 is superseded by EPSG:15740 and by nothing else. Its {@code same_source_target_crs}
     * is set, which is what tells a caller the replacement is a like-for-like swap rather than a
     * transformation between some other pair of CRSs.
     */
    @Test
    public void supersededByReportsTheReplacementAndItsFlags() {
        DbObjectRef op = new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1066");
        List<DbSupersession> s = db.supersededBy(op);
        assertEquals(1, s.size());
        assertEquals(op, s.get(0).superseded());
        assertEquals(new DbObjectRef(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "15740"),
                s.get(0).replacement());
        assertEquals("EPSG", s.get(0).source());
        assertTrue(s.get(0).sameSourceTargetCrs());
    }

    /** EPSG:4035, the deprecated authalic sphere, was replaced by EPSG:4047 and by nothing else. */
    @Test
    public void replacementsForReportsTheSingleReplacement() {
        List<DbObjectRef> r = db.replacementsFor(
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4035"));
        assertEquals(1, r.size());
        assertEquals(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4047"), r.get(0));
    }

    /**
     * A deprecated CRS can have more than one replacement — EPSG:4172 has two — so the scan has to
     * carry on past the first matching row instead of stopping there.
     */
    @Test
    public void replacementsForReportsEveryReplacement() {
        List<DbObjectRef> r = db.replacementsFor(
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4172"));
        assertEquals(2, r.size());
        assertTrue(r.contains(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4190")));
        assertTrue(r.contains(new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4694")));
    }

    /** A null object is a miss, not a crash: callers pass on references that may not be there. */
    @Test
    public void nullObjectIsEmpty() {
        assertTrue(db.supersededBy(null).isEmpty());
        assertTrue(db.replacementsFor(null).isEmpty());
    }

    /**
     * An authority that appears nowhere in the file has no string id at all, which is a different
     * kind of miss from a known authority that happens to key no row.
     */
    @Test
    public void stringsMissingFromThePoolAreEmpty() {
        DbObjectRef unknownAuthority =
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "NO-SUCH-AUTHORITY", "4326");
        assertTrue(db.supersededBy(unknownAuthority).isEmpty());
        assertTrue(db.replacementsFor(unknownAuthority).isEmpty());
    }

    /** EPSG:4326 is neither deprecated nor superseded, and both of its strings are in the pool. */
    @Test
    public void pooledStringsThatKeyNoRowAreEmpty() {
        DbObjectRef live = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326");
        assertTrue(db.supersededBy(live).isEmpty());
        assertTrue(db.replacementsFor(live).isEmpty());
    }
}
