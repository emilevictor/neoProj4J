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
package org.locationtech.proj4j.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.UnknownAuthorityCodeException;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;

/**
 * {@link LegacyAdapters#crsFactory(ProjContext)}, new in 2.3.0.
 *
 * <h2>The gap it closes</h2>
 *
 * <p>{@link LegacyAdapters#transformFactory(ProjContext)} has shipped since 2.0.0, so a legacy caller
 * could already get database-backed <em>operation selection</em> while holding 1.x types. What they
 * could not do was <em>resolve</em> a code only the database knows without leaving those types behind
 * — {@code Proj.createCrs} returns a {@code Crs}, not a {@link CoordinateReferenceSystem}, and a
 * fifteen-year-old call site is typed on the latter all the way down.
 *
 * <p>{@code EPSG:9057} is the code these tests turn on, and it is a real one rather than a contrived
 * one: WGS 84 (G1762) is absent from every shipped legacy dictionary and present in the authority
 * database, which is exactly the shape of the gap.
 */
public class LegacyCrsFactoryTest {

    private static ProjContext withDatabase() {
        return ProjContext.builder().database(FakeProjDatabase.wgs84Ensemble()).build();
    }

    /**
     * The headline. Same call, same return type, and the code resolves where the plain factory can
     * only report it unknown.
     */
    @Test
    public void aCodeOnlyTheDatabaseKnowsResolvesAndKeepsTheLegacyType() {
        try {
            new CRSFactory().createFromName("EPSG:9057");
            fail("EPSG:9057 must not be in the shipped dictionary, or this test proves nothing");
        } catch (UnknownAuthorityCodeException expected) {
            // The gap, as a legacy caller sees it today.
        }

        CRSFactory factory = LegacyAdapters.crsFactory(withDatabase());
        CoordinateReferenceSystem crs = factory.createFromName("EPSG:9057");

        assertNotNull(crs);
        assertEquals("WGS 84 (G1762)", crs.getName());
        // The authority's own ellipsoid parameterisation, not a datum alias invented on the way out.
        assertEquals("+proj=longlat +a=6378137.0 +rf=298.257223563 +no_defs",
                crs.getParameterString().trim());
    }

    /**
     * A code the dictionary does have is unaffected — the strict path tries the dictionary first, so
     * attaching a database must not change what {@code EPSG:4326} resolves to.
     */
    @Test
    public void aDictionaryCodeResolvesIdenticallyWithAndWithoutADatabase() {
        String plain = new CRSFactory().createFromName("EPSG:4326").getParameterString();

        assertEquals(plain, LegacyAdapters.crsFactory(ProjContext.DEFAULT)
                .createFromName("EPSG:4326").getParameterString());
        assertEquals(plain, LegacyAdapters.crsFactory(withDatabase())
                .createFromName("EPSG:4326").getParameterString());
    }

    /**
     * The context is genuinely applied, and this is where that becomes visible without a database.
     *
     * <p>{@link LegacyAdapters#transformFactory(ProjContext)} deliberately ignores
     * {@link AxisOrderPolicy}, because it is handed CRSs somebody else built. This method builds
     * them, so the policy applies — and the difference is checkable in one string.
     */
    @Test
    public void theAxisOrderPolicyIsHonouredBecauseThisMethodBuildsTheCrs() {
        ProjContext authority =
                ProjContext.builder().axisOrderPolicy(AxisOrderPolicy.AUTHORITY).build();

        String underAuthority = LegacyAdapters.crsFactory(authority)
                .createFromName("EPSG:4326").getParameterString();
        assertTrue("EPSG:4326 is latitude-first under authority order: " + underAuthority,
                underAuthority.contains("+axis=neu"));

        // And the plain factory is untouched by any of it: still lon-first, as fifteen years of
        // callers require.
        assertTrue(!new CRSFactory().createFromName("EPSG:4326")
                .getParameterString().contains("+axis="));
    }

    /**
     * An unknown code still arrives as the exception legacy callers catch. The strict path adds
     * reasons for codes it can explain; it does not re-type the ordinary miss.
     */
    @Test
    public void anUnknownCodeStillThrowsTheLegacyException() {
        try {
            CoordinateReferenceSystem crs = LegacyAdapters.crsFactory(withDatabase())
                    .createFromName("EPSG:999999");
            fail("expected UnknownAuthorityCodeException, got " + crs);
        } catch (UnknownAuthorityCodeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("999999"));
        }
    }

    /**
     * The factory says which context it carries. A caller debugging why one factory refuses and
     * another does not needs to be able to print them apart.
     */
    @Test
    public void toStringNamesTheFactoryAndItsContext() {
        assertTrue(LegacyAdapters.crsFactory(ProjContext.DEFAULT).toString()
                .startsWith("LegacyAdapters.crsFactory("));
    }
}
