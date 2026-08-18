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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * {@link LegacyAdapters#transformFactory(ProjContext)} with an authority database attached, which
 * until 2.3.0 nothing tested.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>A consumer reported that "the legacy API cannot reach the database at all". That is false, and
 * the code says so plainly: {@code StrictFactory.createTransform} calls
 * {@code CrsOperation.create(fromLegacy(src, ctx), fromLegacy(tgt, ctx), ctx)},
 * {@code CrsOperation.create} tests {@code context.hasDatabase()}, and {@code OperationSelector} has
 * a branch written for exactly this input &mdash; {@code referenceFor} special-cases
 * {@link Crs.Source#LEGACY_OBJECT} and maps the legacy CRS's {@code +datum=} through PROJ's own
 * ten-name table.
 *
 * <p><strong>But "the code says so" is not a measurement, and before this file there was not one.</strong>
 * Two test files mentioned {@code LegacyAdapters} and neither built a context with a database; all
 * fifteen {@code .database(} sites in tests went through the {@code Proj}/{@code CrsOperation} API.
 * The path was coded, documented and reachable, and completely unproven end to end &mdash; which is
 * the state a consumer's incorrect claim about it can survive in indefinitely.
 *
 * <p>The pair is {@code EPSG:4267 -> EPSG:4269}, because it is the one the bridge exists for: it is
 * the first assertion in {@link LegacyApiUnchangedTest} and the pair {@code LegacyAdapters}' own
 * javadoc names.
 *
 * <h2>What proves the database was consulted, and what does not</h2>
 *
 * <p><strong>Not the coordinate.</strong> Measured, the bridge with a database and the plain 1.x
 * factory return bit-identical numbers here, and that is the correct result rather than a hole in the
 * test: the selected {@code EPSG:1241} names the NADCON {@code conus} grid, and the dictionary's
 * {@code +datum=NAD27} alias expands to {@code +nadgrids=@conus,...}, so both paths run the same grid
 * over the same point. An "it must differ" assertion would be asserting that we broke something.
 *
 * <p>What proves it is the pair of assertions the coordinate cannot make: the selected operation is
 * reported as {@code EPSG:1241} at 0.15 m &mdash; a fact only the database has &mdash; and the same
 * bridge with the database taken away <em>refuses the pair</em>. The plain 1.x factory does neither.
 *
 * @see LegacyApiUnchangedTest for the other half: that the plain 1.x factory is untouched by all of
 *     this
 * @see OperationSelectionTest for the same selection asserted through the new API
 */
public class LegacyAdaptersWithDatabaseTest {

    private static final double LON = -122.4;
    private static final double LAT = 37.8;

    private static CoordinateReferenceSystem crs(String code) {
        return new CRSFactory().createFromName(code);
    }

    private static ProjContext withDatabase() {
        return ProjContext.builder().database(FakeProjDatabase.nad27ToNad83()).build();
    }

    /**
     * The headline: the bridge, handed a database, transforms the pair behind the frozen
     * {@link CoordinateTransformFactory} interface &mdash; and returns the answer legacy callers
     * already got.
     *
     * <p>The equality is the assertion, not a weakness of it. Attaching a database to the bridge must
     * not move a coordinate this pair already produced correctly, and here it does not: both paths
     * end up running NADCON {@code conus}. That the database was nonetheless consulted is proved by
     * the two tests below.
     */
    @Test
    public void theBridgeReachesTheDatabaseFromLegacyTypes() {
        ProjContext ctx = withDatabase();
        CoordinateTransformFactory factory = LegacyAdapters.transformFactory(ctx);

        // Same call a fifteen-year-old GeoTools call site makes, same argument types.
        CoordinateTransform t = factory.createTransform(crs("EPSG:4267"), crs("EPSG:4269"));
        ProjCoordinate out = t.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertTrue("the selected operation must produce a finite coordinate",
                out.hasValidXandYOrdinates());

        // The shift really happened -- this is not the input handed back.
        assertNotEquals("EPSG:1241 must actually shift the point",
                Double.doubleToLongBits(LON), Double.doubleToLongBits(out.x));

        ProjCoordinate plain = new CoordinateTransformFactory()
                .createTransform(crs("EPSG:4267"), crs("EPSG:4269"))
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertEquals("attaching a database must not move an answer legacy callers already had",
                Double.doubleToLongBits(plain.x), Double.doubleToLongBits(out.x));
        assertEquals(Double.doubleToLongBits(plain.y), Double.doubleToLongBits(out.y));
    }

    /**
     * The numbers are the new API's numbers, bit for bit. Anything less would mean the bridge is a
     * second engine rather than the same one behind an old interface.
     */
    @Test
    public void theBridgeAndTheFacadeAgreeToTheBit() {
        ProjContext ctx = withDatabase();

        ProjCoordinate viaBridge = LegacyAdapters.transformFactory(ctx)
                .createTransform(crs("EPSG:4267"), crs("EPSG:4269"))
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());

        CrsOperation op = CrsOperation.create(
                LegacyAdapters.fromLegacy(crs("EPSG:4267"), ctx),
                LegacyAdapters.fromLegacy(crs("EPSG:4269"), ctx), ctx);
        ProjCoordinate viaFacade = op.transform(new ProjCoordinate(LON, LAT));

        assertEquals(Double.doubleToLongBits(viaFacade.x), Double.doubleToLongBits(viaBridge.x));
        assertEquals(Double.doubleToLongBits(viaFacade.y), Double.doubleToLongBits(viaBridge.y));

        // And the operation really was chosen from the authority, not synthesised from the datum.
        assertTrue("an authority operation must have been selected: " + op.describe(),
                op.selectedOperation().isPresent());
        assertEquals("EPSG:1241", op.selectedOperation().get().authorityCode());
        assertEquals(0.15, op.accuracy().get().metres(), 0.0);
        assertFalse("nine published operations exist for this pair and none is ballpark",
                op.isBallparkTransformation());
    }

    /**
     * The control that makes the two tests above mean something: the <em>same</em> bridge, with the
     * <em>same</em> policies and only the database taken away, refuses the pair.
     *
     * <p>Without this, "the bridge returned a coordinate" would be consistent with the bridge quietly
     * behaving like the plain 1.x factory.
     */
    @Test
    public void theSameBridgeWithoutADatabaseStillRefusesThePair() {
        assertFalse("this test is only meaningful with no database configured",
                ProjContext.DEFAULT.hasDatabase());
        try {
            CoordinateTransform t = LegacyAdapters.transformFactory(ProjContext.DEFAULT)
                    .createTransform(crs("EPSG:4267"), crs("EPSG:4269"));
            fail("expected BALLPARK_REJECTED with no database, got " + t);
        } catch (CrsCreationException expected) {
            assertEquals(ErrorCause.BALLPARK_REJECTED, expected.cause());
        }
    }

    /**
     * The bulk path goes through the same selection. It is a separate override on
     * {@code StrictFactory}, so it is a separate assertion: a change that re-routed one and not the
     * other would leave the test above green.
     */
    @Test
    public void theBulkPathReachesTheDatabaseToo() {
        double[] xy = {LON, LAT};
        int failed = LegacyAdapters.transformFactory(withDatabase())
                .createBulkTransform(crs("EPSG:4267"), crs("EPSG:4269"))
                .transform2D(xy, 0, 1, 2, new byte[1]);

        assertEquals("the point must transform cleanly", 0, failed);

        // Same numbers as the single-point path through the same bridge, to the bit.
        ProjCoordinate single = LegacyAdapters.transformFactory(withDatabase())
                .createTransform(crs("EPSG:4267"), crs("EPSG:4269"))
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertEquals(Double.doubleToLongBits(single.x), Double.doubleToLongBits(xy[0]));
        assertEquals(Double.doubleToLongBits(single.y), Double.doubleToLongBits(xy[1]));
        assertNotEquals("the shift must have been applied in bulk as well",
                Double.doubleToLongBits(LON), Double.doubleToLongBits(xy[0]));
    }
}
