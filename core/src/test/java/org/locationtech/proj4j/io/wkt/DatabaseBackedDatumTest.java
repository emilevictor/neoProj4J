/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.io.wkt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;
import org.locationtech.proj4j.spi.StubProjDatabase;

/**
 * The database seam on {@link CrsDefinitions#toProjParameters(CrsDefinition, AxisOrderPolicy,
 * org.locationtech.proj4j.spi.ProjDatabase)}.
 *
 * <h2>What the seam is for</h2>
 * WKT2 and PROJJSON both allow a reference frame to name itself by authority code and stop there:
 * <pre>DATUM["Trinidad 1903", ID["EPSG",6302]]</pre>
 * is a complete, legal document, and without something to look 6302 up in it is unanswerable.
 * proj4j's answer before this seam existed was to refuse, which is right. The seam lets a caller
 * who has a database get an answer instead, and the rule is that a database can only ever turn a
 * refusal into an answer &mdash; never one answer into a different one. Every test here that
 * passes {@code null} asserts the pre-seam behaviour is untouched.
 *
 * <h2>The unit is the whole risk</h2>
 * EPSG does not publish every ellipsoid in metres. Trinidad 1903 sits on Clarke 1858, whose axes
 * EPSG gives in <em>Clarke's foot</em> &mdash; {@code 20926348} of them. Read as metres that is an
 * Earth three times too big, and it would still produce coordinates. The numbers pinned below come
 * from PROJ 9.8.1 itself, not from this library's own arithmetic:
 * <pre>
 * $ projinfo EPSG:4302 -o PROJ
 * +proj=longlat +a=6378293.64520876 +b=6356617.98767984 +no_defs +type=crs
 * </pre>
 * and the rows they are built from:
 * <pre>
 * $ sqlite3 proj.db "select semi_major_axis, uom_code, semi_minor_axis
 *                    from ellipsoid where code = '7007'"
 * 20926348.0|9005|20855233.0
 * $ sqlite3 proj.db "select name, type, conv_factor from unit_of_measure where code = '9005'"
 * Clarke's foot|length|0.3047972654
 * </pre>
 */
public class DatabaseBackedDatumTest {

    /** The Trinidad 1903 geodetic reference frame, EPSG:6302, as EPSG publishes it. */
    private static final DbObjectRef CLARKE_1858 =
            new DbObjectRef(DbObjectType.ELLIPSOID, "EPSG", "7007");
    private static final DbObjectRef CLARKES_FOOT =
            new DbObjectRef(DbObjectType.UNIT_OF_MEASURE, "EPSG", "9005");

    /** PROJ 9.8.1's own answer for EPSG:4302, quoted above. */
    private static final double A_METRES = 6378293.64520876;
    private static final double B_METRES = 6356617.98767984;

    // ------------------------------------------------------------------ the refusal stands

    @Test
    public void withoutADatabaseAFrameThatOnlyCitesACodeIsStillRefused() {
        try {
            CrsDefinitions.toProjParameters(trinidad(), AxisOrderPolicy.LEGACY, null);
            fail("expected a refusal: nothing in the document says what shape the Earth is");
        } catch (WktParseException expected) {
            assertTrue("the message should say what is missing, not just that something is: "
                    + expected.getMessage(),
                    expected.getMessage().contains("has no ellipsoid"));
        }
    }

    @Test
    public void aFrameWithNoIdentifierIsRefusedEvenWithADatabase() {
        CrsDefinition def = trinidad();
        def.getDatum().setId(null);
        try {
            CrsDefinitions.toProjParameters(def, AxisOrderPolicy.LEGACY, epsg());
            fail("expected a refusal: a database cannot look up a code the document never gave");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage().contains("has no ellipsoid"));
        }
    }

    @Test
    public void aFrameTheDatabaseDoesNotCarryIsRefused() {
        CrsDefinition def = trinidad();
        def.getDatum().setId(new Identifier("EPSG", "6999"));
        try {
            CrsDefinitions.toProjParameters(def, AxisOrderPolicy.LEGACY, epsg());
            fail("expected a refusal: EPSG:6999 is not a reference frame this database has");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage().contains("has no ellipsoid"));
        }
    }

    // ------------------------------------------------------------------ the answer

    @Test
    public void aDatabaseTurnsTheRefusalIntoTheAuthoritysOwnEllipsoid() {
        String proj = CrsDefinitions.toProjParameterString(trinidad(), AxisOrderPolicy.LEGACY,
                epsg());

        assertEquals("+proj=longlat +a=" + WktFormat.number(A_METRES)
                + " +b=" + WktFormat.number(B_METRES) + " +no_defs", proj);
    }

    /**
     * The positive control for the test above: it would also pass if the axes were emitted in
     * Clarke's feet and this library happened to agree with itself, so this asserts the raw
     * authority number is <em>not</em> what comes out, and by how much.
     */
    @Test
    public void theAxesAreConvertedToMetresRatherThanPassedThroughInFeet() {
        String proj = CrsDefinitions.toProjParameterString(trinidad(), AxisOrderPolicy.LEGACY,
                epsg());

        assertTrue("the raw authority value 20926348 must not reach the parameter list: "
                + proj, proj.indexOf("20926348") < 0);
        assertTrue("the raw authority value 20855233 must not reach the parameter list: "
                + proj, proj.indexOf("20855233") < 0);
        // Clarke's foot is 0.3047972654 m, so reading feet as metres inflates the semi-major axis
        // by 14548054.35 m -- an error of over three Earth radii, expressed as a coordinate.
        assertEquals(14548054.354791241, 20926348.0 - A_METRES, 1.0e-6);
    }

    @Test
    public void theResolvedEllipsoidReachesTheBuiltCrs() {
        org.locationtech.proj4j.CoordinateReferenceSystem crs =
                CrsDefinitions.toCrs(trinidad(), AxisOrderPolicy.LEGACY, epsg());

        assertNotNull(crs.getProjection());
        assertEquals(A_METRES, crs.getProjection().getEllipsoid().getEquatorRadius(), 1.0e-6);
    }

    // ------------------------------------------------------------------ units it must refuse

    @Test
    public void anAxisInAUnitWithNoConversionFactorIsRefused() {
        // proj.db has eleven unit_of_measure rows with a null conv_factor. A unit that cannot be
        // converted is not a unit that converts to 1.0.
        ProjDatabase db = base().withUnit(new DbUnit("EPSG", "9005", "Clarke's foot",
                DbUnit.Type.LENGTH, Double.NaN, null, false));
        try {
            CrsDefinitions.toProjParameters(trinidad(), AxisOrderPolicy.LEGACY, db);
            fail("expected a refusal: the database cannot say how long Clarke's foot is");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage().contains("has no ellipsoid"));
        }
    }

    @Test
    public void anAxisInAnAngularUnitIsRefused() {
        // A length in arc-seconds is a corrupt row, not a length. Multiplying by 4.848e-06 would
        // give a plausible-looking small number and no complaint.
        ProjDatabase db = base().withUnit(new DbUnit("EPSG", "9005", "arc-second",
                DbUnit.Type.ANGLE, 4.84813681109535e-06, null, false));
        try {
            CrsDefinitions.toProjParameters(trinidad(), AxisOrderPolicy.LEGACY, db);
            fail("expected a refusal: an angular unit cannot measure a semi-major axis");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage().contains("has no ellipsoid"));
        }
    }

    @Test
    public void aUnitTheDatabaseDoesNotCarryIsRefused() {
        try {
            CrsDefinitions.toProjParameters(trinidad(), AxisOrderPolicy.LEGACY, base());
            fail("expected a refusal: EPSG:9005 is not in this database");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage().contains("has no ellipsoid"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * {@code GEOGCRS["Trinidad 1903", DATUM["Trinidad 1903", ID["EPSG",6302]], ...]} — a frame that
     * cites its code and declares no ellipsoid.
     */
    private static CrsDefinition trinidad() {
        DatumDefinition datum = new DatumDefinition();
        datum.setName("Trinidad 1903");
        datum.setId(new Identifier("EPSG", "6302"));
        datum.setPrimeMeridian(PrimeMeridianDefinition.greenwich());

        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.GEOGRAPHIC);
        def.setName("Trinidad 1903");
        def.setDatum(datum);
        return def;
    }

    /** The frame and its ellipsoid, but no unit — so each unit test can supply its own. */
    private static StubProjDatabase base() {
        return new StubProjDatabase("EPSG 6302")
                .withDatum(new DbDatum(DbObjectType.GEODETIC_DATUM, "EPSG", "6302",
                        "Trinidad 1903", CLARKE_1858, null, "1903", Double.NaN, Double.NaN,
                        null, false))
                .withEllipsoid(new DbEllipsoid("EPSG", "7007", "Clarke 1858", null, 20926348.0,
                        CLARKES_FOOT, Double.NaN, 20855233.0, false));
    }

    /** The same, with Clarke's foot as EPSG publishes it. */
    private static StubProjDatabase epsg() {
        return base().withUnit(new DbUnit("EPSG", "9005", "Clarke's foot", DbUnit.Type.LENGTH,
                0.3047972654, null, false));
    }
}
