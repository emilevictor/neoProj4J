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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.io.projjson.ProjJsonReader;
import org.locationtech.proj4j.vertical.CompoundCrs;
import org.locationtech.proj4j.vertical.VerticalCrs;

/**
 * A compound CRS read from a document keeps its vertical half.
 *
 * <p>Until 2.2.0 it did not. {@link CrsDefinition} modelled {@code COMPD_CS} and
 * {@code VERT_CS} and the readers filled both in, but {@link CrsDefinitions} took
 * {@code horizontalComponent()} on its first working line and threaded only that through every
 * append step, so the vertical component was still unread when the parameter list was returned.
 * Across the 5,671 rows of {@code proj4/wkt/epsg.properties}, that silently dropped the height
 * definition of 72 compound CRSs — every one of which carries a {@code VERT_CS}; a standalone
 * {@code VERT_CS} — 177 rows — could not be read at all.
 *
 * <p>Every expected string below is PROJ 9.8.1's own, from
 * {@code projinfo EPSG:&lt;code&gt; -o PROJ}, less its trailing {@code +type=crs}. Where this
 * library's answer is deliberately not PROJ's, the test says which and why.
 */
public class CompoundCrsFromDocumentTest {

    /** EPSG:7405, OSGB 1936 / British National Grid + ODN height, as the dictionary spells it. */
    private static final String OSGB_PLUS_ODN =
            "COMPD_CS[\"OSGB 1936 / British National Grid + ODN height\", "
                    + "PROJCS[\"OSGB 1936 / British National Grid\", "
                    + "GEOGCS[\"OSGB 1936\", DATUM[\"OSGB 1936\", "
                    + "SPHEROID[\"Airy 1830\", 6377563.396, 299.3249646], "
                    + "TOWGS84[446.448, -125.157, 542.06, 0.15, 0.247, 0.842, -20.489], "
                    + "AUTHORITY[\"EPSG\",\"6277\"]], PRIMEM[\"Greenwich\", 0.0], "
                    + "UNIT[\"degree\", 0.017453292519943295]], "
                    + "PROJECTION[\"Transverse Mercator\"], "
                    + "PARAMETER[\"central_meridian\", -2.0], "
                    + "PARAMETER[\"latitude_of_origin\", 49.0], "
                    + "PARAMETER[\"scale_factor\", 0.9996012717], "
                    + "PARAMETER[\"false_easting\", 400000.0], "
                    + "PARAMETER[\"false_northing\", -100000.0], "
                    + "UNIT[\"m\", 1.0], AUTHORITY[\"EPSG\",\"27700\"]], "
                    + "VERT_CS[\"ODN height\", VERT_DATUM[\"Ordnance Datum Newlyn\", 2005], "
                    + "UNIT[\"m\", 1.0], AXIS[\"Gravity-related height\", UP], "
                    + "AUTHORITY[\"EPSG\",\"5701\"]], AUTHORITY[\"EPSG\",\"7405\"]]";

    /** EPSG:5754, the one row in the dictionary whose unit has no PROJ identifier. */
    private static final String POOLBEG =
            "VERT_CS[\"Poolbeg height\", VERT_DATUM[\"Poolbeg\", 2005], "
                    + "UNIT[\"m*0.3048007491\", 0.3048007491], "
                    + "AXIS[\"Gravity-related height\", UP], AUTHORITY[\"EPSG\",\"5754\"]]";

    /** WGS 84 in authority order — latitude first — so the third +axis= slot is reachable. */
    private static final String LAT_FIRST_WGS84 =
            "GEOGCS[\"WGS 84\", DATUM[\"World Geodetic System 1984\", "
                    + "SPHEROID[\"WGS 84\", 6378137.0, 298.257223563]], "
                    + "PRIMEM[\"Greenwich\", 0.0], UNIT[\"degree\", 0.017453292519943295], "
                    + "AXIS[\"Geodetic latitude\", NORTH], AXIS[\"Geodetic longitude\", EAST], "
                    + "AUTHORITY[\"EPSG\",\"4326\"]]";

    private static final String MSL_DEPTH =
            "VERT_CS[\"MSL depth\", VERT_DATUM[\"Mean Sea Level\", 2005], UNIT[\"m\", 1.0], "
                    + "AXIS[\"Depth\", DOWN], AUTHORITY[\"EPSG\",\"5715\"]]";

    private static final String MSL_HEIGHT =
            "VERT_CS[\"MSL height\", VERT_DATUM[\"Mean Sea Level\", 2005], UNIT[\"m\", 1.0], "
                    + "AXIS[\"Gravity-related height\", UP], AUTHORITY[\"EPSG\",\"5714\"]]";

    private static CrsDefinition read(String wkt) {
        return new WktReader().readDefinition(wkt);
    }

    private static String proj(String wkt, AxisOrderPolicy policy) {
        return CrsDefinitions.toProjParameterString(read(wkt), policy);
    }

    // ------------------------------------------------------- the component itself

    @Test
    public void aCompoundCrsHasBothComponents() {
        CrsDefinition def = read(OSGB_PLUS_ODN);
        assertEquals(CrsDefinition.Kind.COMPOUND, def.getKind());
        assertEquals(CrsDefinition.Kind.PROJECTED, def.horizontalComponent().getKind());
        assertEquals(CrsDefinition.Kind.VERTICAL, def.verticalComponent().getKind());
        assertEquals("ODN height", def.verticalComponent().getName());
    }

    @Test
    public void aVerticalCrsIsItsOwnVerticalComponentAndHasNoHorizontalOne() {
        CrsDefinition def = read(MSL_DEPTH);
        assertNull(def.horizontalComponent());
        assertEquals(def, def.verticalComponent());
    }

    @Test
    public void aHorizontalCrsHasNoVerticalComponent() {
        assertNull(read(LAT_FIRST_WGS84).verticalComponent());
    }

    // ------------------------------------------------------- the emitted tokens

    /**
     * {@code projinfo EPSG:7405 -o PROJ} at 9.8.1 answers
     * {@code +proj=tmerc +lat_0=49 +lon_0=-2 +k_0=0.9996012717 +x_0=400000 +y_0=-100000
     * +datum=OSGB36 +units=m +geoidgrids=uk_os_OSGM15_GB.tif +geoid_crs=WGS84 +vunits=m
     * +no_defs +type=crs}. Token for token, including their order.
     */
    @Test
    public void aCompoundCrsCarriesGeoidGridsAndUnits() {
        assertEquals("+proj=tmerc +lat_0=49 +lon_0=-2 +k_0=0.9996012717 +x_0=400000"
                        + " +y_0=-100000 +datum=OSGB36 +units=m"
                        + " +geoidgrids=uk_os_OSGM15_GB.tif +geoid_crs=WGS84 +vunits=m +no_defs",
                proj(OSGB_PLUS_ODN, AxisOrderPolicy.LEGACY));
    }

    /** The vertical tokens come last, before {@code +no_defs}, which is where PROJ puts them. */
    @Test
    public void theVerticalTokensPrecedeNoDefs() {
        String s = proj(OSGB_PLUS_ODN, AxisOrderPolicy.LEGACY);
        assertTrue(s, s.indexOf("+units=m") < s.indexOf("+geoidgrids="));
        assertTrue(s, s.indexOf("+geoidgrids=") < s.indexOf("+geoid_crs="));
        assertTrue(s, s.indexOf("+geoid_crs=") < s.indexOf("+vunits="));
        assertTrue(s, s.indexOf("+vunits=") < s.indexOf("+no_defs"));
    }

    /**
     * A unit PROJ has no identifier for becomes {@code +vto_meter}, never a rounded
     * {@code +vunits=m}. {@code projinfo EPSG:4326+5754 -o PROJ} answers
     * {@code +proj=longlat +datum=WGS84 +vto_meter=0.3048007491 +no_defs +type=crs}, and
     * calling that unit a metre would be a 3.28x height error delivered silently.
     */
    @Test
    public void anUnnamedUnitBecomesVtoMeter() {
        String wkt = "COMPD_CS[\"WGS 84 + Poolbeg height\", " + LAT_FIRST_WGS84 + ", "
                + POOLBEG + "]";
        assertEquals("+proj=longlat +datum=WGS84 +vto_meter=0.3048007491 +no_defs",
                proj(wkt, AxisOrderPolicy.LEGACY));
    }

    /**
     * A vertical CRS the registry does not know contributes its unit and nothing else — which
     * is also what PROJ does for a vertical datum with no grid. {@code projinfo EPSG:4326+5714}
     * answers {@code +proj=longlat +datum=WGS84 +vunits=m +no_defs +type=crs}.
     */
    @Test
    public void anUngriddedVerticalCrsStillCarriesItsUnit() {
        String wkt = "COMPD_CS[\"WGS 84 + MSL height\", " + LAT_FIRST_WGS84 + ", "
                + MSL_HEIGHT + "]";
        assertEquals("+proj=longlat +datum=WGS84 +vunits=m +no_defs",
                proj(wkt, AxisOrderPolicy.LEGACY));
    }

    @Test
    public void aHorizontalOnlyCrsGainsNoVerticalToken() {
        assertEquals("+proj=longlat +datum=WGS84 +no_defs",
                proj(LAT_FIRST_WGS84, AxisOrderPolicy.LEGACY));
    }

    // ------------------------------------------------------- the axis direction

    /**
     * The proj string cannot say "down" in {@code +vunits}, and PROJ never exports the vertical
     * axis direction at all. {@code +axis=} can say it, though: {@code datum/AxisOrder} maps
     * {@code 'd'} to a z negation. Before 2.2.0 the third slot was padded with {@code 'u'}
     * whatever the document declared, so a depth CRS came out as a height — a sign error
     * expressed as a plausible coordinate.
     */
    @Test
    public void aDepthAxisReachesTheThirdAxisSlot() {
        String wkt = "COMPD_CS[\"WGS 84 + MSL depth\", " + LAT_FIRST_WGS84 + ", "
                + MSL_DEPTH + "]";
        assertEquals("+proj=longlat +datum=WGS84 +axis=ned +vunits=m +no_defs",
                proj(wkt, AxisOrderPolicy.AUTHORITY));
    }

    @Test
    public void anUpAxisStillPadsWithU() {
        String wkt = "COMPD_CS[\"WGS 84 + MSL height\", " + LAT_FIRST_WGS84 + ", "
                + MSL_HEIGHT + "]";
        assertEquals("+proj=longlat +datum=WGS84 +axis=neu +vunits=m +no_defs",
                proj(wkt, AxisOrderPolicy.AUTHORITY));
    }

    /**
     * LEGACY and VISUALISATION emit no {@code +axis=} at all, so under those policies a depth
     * and a height serialise identically — the same answer
     * {@code CompoundCrsTest.aDepthIsFlaggedBecauseTheProjStringCannotCarryIt} pins for the
     * {@code EPSG:4326+5715} syntax. The direction is on the model either way.
     */
    @Test
    public void underLegacyADepthAndAHeightSerialiseAlike() {
        String depth = "COMPD_CS[\"d\", " + LAT_FIRST_WGS84 + ", " + MSL_DEPTH + "]";
        String height = "COMPD_CS[\"h\", " + LAT_FIRST_WGS84 + ", " + MSL_HEIGHT + "]";
        assertEquals(proj(height, AxisOrderPolicy.LEGACY), proj(depth, AxisOrderPolicy.LEGACY));
        assertTrue(CrsDefinitions.toVerticalCrs(read(depth)).isDepth());
        assertFalse(CrsDefinitions.toVerticalCrs(read(height)).isDepth());
    }

    // ------------------------------------------------------- the joined model

    @Test
    public void theDocumentProducesTheLibrarysOwnVerticalCrs() {
        VerticalCrs v = CrsDefinitions.toVerticalCrs(read(OSGB_PLUS_ODN));
        assertEquals("EPSG", v.getAuthority());
        assertEquals("5701", v.getCode());
        assertEquals("ODN height", v.getName());
        assertEquals("uk_os_OSGM15_GB.tif", v.geoidGrids());
        assertEquals("WGS84", v.geoidCrs());
        assertEquals("m", v.verticalUnits());
        assertFalse(v.isDepth());
        assertTrue(v.hasGeoidModel());
    }

    @Test
    public void theDocumentProducesTheLibrarysOwnCompoundCrs() {
        CompoundCrs c = CrsDefinitions.toCompoundCrs(read(OSGB_PLUS_ODN),
                AxisOrderPolicy.LEGACY);
        assertNotNull(c.getHorizontal());
        assertTrue(c.appliesVerticalShift());
        // The horizontal half is built from the horizontal component alone, so composing the
        // two halves cannot append the vertical tokens twice.
        assertEquals(1, countOccurrences(c.toProjString(), "+vunits="));
        assertEquals(1, countOccurrences(c.toProjString(), "+geoidgrids="));
    }

    /**
     * A standalone vertical CRS is still refused by {@link CrsDefinitions#toCrs} — PROJ's own
     * PROJ.4 export of one has no {@code +proj=}, so there is nothing to build a projection
     * from. What changed is that the refusal names the door that does work.
     */
    @Test
    public void aStandaloneVerticalCrsIsRefusedButReadable() {
        try {
            proj(POOLBEG, AxisOrderPolicy.LEGACY);
            fail("expected a refusal");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("toVerticalCrs"));
        }
        VerticalCrs v = CrsDefinitions.toVerticalCrs(read(POOLBEG));
        assertEquals("5754", v.getCode());
        assertNull("an unnamed unit is carried as a factor, not renamed", v.verticalUnits());
        assertEquals(0.3048007491, v.verticalToMetre(), 0.0);
        assertEquals("+vto_meter=0.3048007491", v.projTokens(false));
    }

    @Test
    public void aStandaloneVerticalCrsIsNotACompoundCrs() {
        try {
            CrsDefinitions.toCompoundCrs(read(POOLBEG), AxisOrderPolicy.LEGACY);
            fail("expected a refusal");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("no horizontal component"));
        }
    }

    @Test
    public void aHorizontalOnlyCrsIsNotACompoundCrs() {
        assertNull(CrsDefinitions.toVerticalCrs(read(LAT_FIRST_WGS84)));
        try {
            CrsDefinitions.toCompoundCrs(read(LAT_FIRST_WGS84), AxisOrderPolicy.LEGACY);
            fail("expected a refusal");
        } catch (WktParseException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("no vertical component"));
        }
    }

    // ------------------------------------------------------- WKT2 and PROJJSON

    @Test
    public void wkt2CompoundCrsBehavesTheSame() {
        String wkt2 = "COMPOUNDCRS[\"WGS 84 + EGM96 height\","
                + "GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\","
                + "ELLIPSOID[\"WGS 84\",6378137,298.257223563,LENGTHUNIT[\"metre\",1]]],"
                + "PRIMEM[\"Greenwich\",0,ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "CS[ellipsoidal,2],"
                + "AXIS[\"geodetic latitude (Lat)\",north,ORDER[1],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],"
                + "AXIS[\"geodetic longitude (Lon)\",east,ORDER[2],"
                + "ANGLEUNIT[\"degree\",0.0174532925199433]],ID[\"EPSG\",4326]],"
                + "VERTCRS[\"EGM96 height\",VDATUM[\"EGM96 geoid\"],CS[vertical,1],"
                + "AXIS[\"gravity-related height (H)\",up,LENGTHUNIT[\"metre\",1]],"
                + "ID[\"EPSG\",5773]]]";
        assertEquals("+proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif"
                        + " +geoid_crs=WGS84 +vunits=m +no_defs",
                proj(wkt2, AxisOrderPolicy.LEGACY));
    }

    /**
     * PROJJSON reaches the same drop point: {@code ProjJsonReader} builds the same
     * {@link CrsDefinition} and hands it to the same {@link CrsDefinitions#toCrs}.
     */
    @Test
    public void projJsonCompoundCrsBehavesTheSame() {
        String json = "{\"type\":\"CompoundCRS\",\"name\":\"WGS 84 + EGM96 height\","
                + "\"components\":["
                + "{\"type\":\"GeographicCRS\",\"name\":\"WGS 84\","
                + "\"datum\":{\"type\":\"GeodeticReferenceFrame\","
                + "\"name\":\"World Geodetic System 1984\","
                + "\"ellipsoid\":{\"name\":\"WGS 84\",\"semi_major_axis\":6378137,"
                + "\"inverse_flattening\":298.257223563}},"
                + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":["
                + "{\"name\":\"Geodetic latitude\",\"abbreviation\":\"Lat\","
                + "\"direction\":\"north\",\"unit\":\"degree\"},"
                + "{\"name\":\"Geodetic longitude\",\"abbreviation\":\"Lon\","
                + "\"direction\":\"east\",\"unit\":\"degree\"}]},"
                + "\"id\":{\"authority\":\"EPSG\",\"code\":4326}},"
                + "{\"type\":\"VerticalCRS\",\"name\":\"EGM96 height\","
                + "\"datum\":{\"type\":\"VerticalReferenceFrame\",\"name\":\"EGM96 geoid\"},"
                + "\"coordinate_system\":{\"subtype\":\"vertical\",\"axis\":["
                + "{\"name\":\"Gravity-related height\",\"abbreviation\":\"H\","
                + "\"direction\":\"up\",\"unit\":\"metre\"}]},"
                + "\"id\":{\"authority\":\"EPSG\",\"code\":5773}}]}";
        CrsDefinition def = new ProjJsonReader().readDefinition(json);
        assertEquals(CrsDefinition.Kind.COMPOUND, def.getKind());
        assertEquals("+proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif"
                        + " +geoid_crs=WGS84 +vunits=m +no_defs",
                CrsDefinitions.toProjParameterString(def, AxisOrderPolicy.LEGACY));
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
