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
package org.locationtech.proj4j.failopen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.DomainErrorPolicy;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * <b>{@link DomainErrorPolicy#LEGACY_NO_SHIFT}: a datum grid <em>coverage</em> miss passes the
 * coordinate through unshifted instead of refusing.</b>
 *
 * <h2>The defect this fixes</h2>
 *
 * <p>{@code proj4j-epsg} ships two of {@code +datum=NAD27}'s four {@code @}-optional grids —
 * {@code conus} and {@code ntv1_can.dat} — so NAD27's grid list resolves <em>non-empty</em> almost
 * everywhere. {@code Grid.shift} throws {@link ErrorCause#COORDINATE_OUTSIDE_GRID} when a non-empty
 * list cannot cover the point, which is correct for the operator layer and was wrong for
 * {@link CoordinateTransformFactory}, a CRS-level API whose javadoc promises 1.4.3 behaviour.
 *
 * <p>Measured over 20,634 in-domain probes across 6,878 dictionary definitions, that lost <b>267</b>
 * transforms which both {@code cs2cs} 9.8.1 <em>and</em> proj4j 1.4.3 complete. After the fix the
 * same frozen probe list diverges from {@code cs2cs} on <b>zero</b> of them, and the 20,055 rows
 * that already answered are <b>bit-identical</b> — the change adds answers and moves nothing.
 *
 * <h2>Why this is not simply "fail open again"</h2>
 *
 * <p>The distinction the engine draws is untouched, and it is the whole design:
 *
 * <ul>
 *   <li>an <b>empty</b> grid list — every {@code @}-optional token absent — has always been a silent
 *       no-op, matching every upstream operator's {@code if (!grids.empty())} guard;</li>
 *   <li>a point <b>outside a grid that loaded</b> is what changed, and only under this policy;</li>
 *   <li>every other per-coordinate cause still throws under it, which
 *       {@link #everyOtherCauseStillThrowsUnderTheLenientPolicy()} pins.</li>
 * </ul>
 *
 * <p>PROJ reaches the same answer by a different route: at the CRS layer
 * {@code proj_create_crs_to_crs} selects <i>Ballpark geographic offset</i>, a declared no-op. On the
 * 267 probes an unshifted answer agrees with {@code cs2cs} to within 1&nbsp;mm on 218 and is up to
 * 753&nbsp;m out on the other 49, where PROJ found a real shift from NADCON grids this library does
 * not ship. {@link BasicCoordinateTransform#mayReturnUnshiftedCoordinates()} is how a caller learns
 * that the 49 are possible.
 */
public class Nad27CoverageMissPassesThroughTest {

    private final CRSFactory crsFactory = new CRSFactory();

    /**
     * (1&deg;E, 1&deg;S) in the Gulf of Guinea — the probe {@code proj4-epsg.csv} uses. Outside
     * {@code conus} (131&deg;W–63&deg;W, 20&deg;N–50&deg;N) and outside {@code ntv1_can.dat}
     * (40&deg;N–84&deg;N) by thousands of kilometres, so no shipped NAD27 grid can supply a value.
     */
    private static final double LON = 1.0;
    private static final double LAT = -1.0;

    private CoordinateReferenceSystem wgs84() {
        return crsFactory.createFromName("EPSG:4326");
    }

    /** {@code EPSG:4267}, NAD27 geographic: the shortest path to the grid stage. */
    private CoordinateReferenceSystem nad27() {
        return crsFactory.createFromName("EPSG:4267");
    }

    /**
     * @param policy the policy, or null for the <em>no-argument</em> constructor
     *
     *               <p>Null means "call the no-arg constructor", NOT "pass null to the one-arg
     *               constructor" — those differ, deliberately and by design: the no-arg default is
     *               {@link DomainErrorPolicy#LEGACY_NO_SHIFT} and an explicit null normalises to
     *               {@link DomainErrorPolicy#THROW}. Conflating them here made two tests in this
     *               file fail against a working fix, which is a fair warning about the asymmetry.
     */
    private ProjCoordinate run(DomainErrorPolicy policy, CoordinateReferenceSystem src,
                               CoordinateReferenceSystem tgt, double x, double y) {
        CoordinateTransformFactory f = policy == null
                ? new CoordinateTransformFactory()
                : new CoordinateTransformFactory(policy);
        CoordinateTransform t = f.createTransform(src, tgt);
        return t.transform(new ProjCoordinate(x, y), new ProjCoordinate());
    }

    // ------------------------------------------------------------------ the fix

    /**
     * The default factory answers, and the answer is the input unshifted.
     *
     * <p>Asserted as an exact {@code ==}, not a tolerance: the point is that the datum stage
     * contributed <em>nothing</em>, and a tolerance would also accept a small shift that happened to
     * be applied. For a geographic-to-geographic pair on NAD27 the only stage between the two CRSs
     * <em>is</em> the datum shift, so bit-equality is the honest assertion.
     */
    @Test
    public void theDefaultFactoryPassesACoverageMissThroughUnshifted() {
        ProjCoordinate out = run(null, wgs84(), nad27(), LON, LAT);
        assertEquals("longitude must come through untouched", LON, out.x, 0.0);
        assertEquals("latitude must come through untouched", LAT, out.y, 0.0);
    }

    /** The no-argument constructors, which are the ones fifteen years of callers actually use. */
    @Test
    public void theNoArgConstructorsBothPassItThrough() {
        CoordinateTransform viaFactory =
                new CoordinateTransformFactory().createTransform(wgs84(), nad27());
        ProjCoordinate a = viaFactory.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertEquals(LON, a.x, 0.0);

        CoordinateTransform direct = new BasicCoordinateTransform(wgs84(), nad27());
        ProjCoordinate b = direct.transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        assertEquals(LON, b.x, 0.0);
    }

    /**
     * A projected NAD27 target too, so this is not a property of the geographic-to-geographic path.
     *
     * <p>{@code EPSG:26716} is UTM zone 16N on NAD27, central meridian 87&deg;W. The probe is
     * <b>not</b> the Gulf of Guinea point the rest of this file uses: at (1&deg;E, 1&deg;S) that CRS
     * is 88&deg; from its central meridian and the <em>projection</em> refuses first, with
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} — a different cause, and one this policy correctly
     * does not touch. Getting a grid-coverage miss on a projected target needs a point that is
     * in-domain for the projection and outside every grid, so: on the central meridian, at
     * 20&deg;S. {@code conus} covers 20&deg;N–50&deg;N and {@code ntv1_can.dat} 40&deg;N–84&deg;N,
     * so the southern hemisphere is outside both.
     *
     * <p>That distinction is the test. It would have been easy to write this against the shared probe,
     * watch it throw, and conclude the fix does not cover projected targets.
     */
    @Test
    public void aProjectedNad27TargetAlsoCompletes() {
        CoordinateReferenceSystem utm16 = crsFactory.createFromName("EPSG:26716");
        ProjCoordinate out = run(null, wgs84(), utm16, -87.0, -20.0);
        assertTrue("easting must be finite: " + out.x, out.x - out.x == 0.0);
        assertTrue("northing must be finite: " + out.y, out.y - out.y == 0.0);

        // And the strict policy refuses the same point for the grid reason, not the domain reason --
        // which is what makes this a coverage-miss test rather than a second domain test.
        try {
            run(DomainErrorPolicy.THROW, wgs84(), utm16, -87.0, -20.0);
            fail("the strict policy must refuse this coverage miss");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    // ------------------------------------------------------------------ the scope

    /**
     * <b>The scope proof.</b> The same transform under {@link DomainErrorPolicy#THROW} still refuses,
     * with the same cause it always did.
     *
     * <p>Without this, the fix would be indistinguishable from having deleted the throw.
     */
    @Test
    public void theStrictPolicyStillRefusesTheSameCoordinate() {
        try {
            run(DomainErrorPolicy.THROW, wgs84(), nad27(), LON, LAT);
            fail("DomainErrorPolicy.THROW must still refuse a NAD27 point outside every shipped "
                    + "grid; if this passes, the engine's fail-closed behaviour is gone, not scoped");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, expected.cause());
        }
    }

    /**
     * The lenient policy is lenient about <em>one</em> cause. A projection-domain failure still
     * throws under it, so this is a carve-out and not a mode.
     *
     * <p>{@code EPSG:2020} is a {@code tmerc} whose central meridian is 83.5&deg; from the probe, far
     * enough out of domain to refuse regardless of any datum.
     */
    @Test
    public void everyOtherCauseStillThrowsUnderTheLenientPolicy() {
        try {
            run(DomainErrorPolicy.LEGACY_NO_SHIFT, wgs84(),
                    crsFactory.createFromName("EPSG:2020"), LON, LAT);
            fail("LEGACY_NO_SHIFT must not suppress COORDINATE_OUT_OF_DOMAIN");
        } catch (CrsTransformException expected) {
            assertEquals(ErrorCause.COORDINATE_OUT_OF_DOMAIN, expected.cause());
        }
    }

    /**
     * A point <em>inside</em> {@code conus} is still shifted, under both policies, identically.
     *
     * <p>This is the non-vacuity leg that matters most. Every other test here would pass if the datum
     * stage had been disabled outright; this one fails if it had. Kansas (−98&deg;, 39&deg;) is well
     * inside {@code conus}, and NAD27&rarr;WGS84 there is on the order of tens of metres — so a zero
     * shift is not a rounding question.
     */
    @Test
    public void aPointInsideTheGridIsStillShiftedAndBothPoliciesAgree() {
        double lon = -98.0;
        double lat = 39.0;
        ProjCoordinate lenient = run(DomainErrorPolicy.LEGACY_NO_SHIFT, nad27(), wgs84(), lon, lat);
        ProjCoordinate strict = run(DomainErrorPolicy.THROW, nad27(), wgs84(), lon, lat);

        assertEquals("the two policies must agree wherever the grid covers the point",
                strict.x, lenient.x, 0.0);
        assertEquals(strict.y, lenient.y, 0.0);

        assertFalse("a point inside conus must actually be shifted, or this whole file is vacuous: "
                        + "got (" + lenient.x + ", " + lenient.y + ")",
                lenient.x == lon && lenient.y == lat);
        // Tens of metres, not degrees: a shift this size in longitude is ~1e-4 degrees.
        assertTrue("the shift must be datum-sized, not garbage: dx=" + Math.abs(lenient.x - lon),
                Math.abs(lenient.x - lon) > 1e-6 && Math.abs(lenient.x - lon) < 1e-2);
    }

    // ------------------------------------------------------------------ the label

    /**
     * {@link BasicCoordinateTransform#mayReturnUnshiftedCoordinates()} is true exactly when both
     * halves hold, and false when either does not.
     *
     * <p>Four cells, because a predicate that returned {@code true} whenever the policy was lenient
     * would be useless on the overwhelming majority of transforms, which have no grid in the path at
     * all.
     */
    @Test
    public void theLabelIsTrueOnlyWhenAGridShiftMeetsTheLenientPolicy() {
        CoordinateReferenceSystem wgs = wgs84();
        CoordinateReferenceSystem nad = nad27();
        CoordinateReferenceSystem merc = crsFactory.createFromParameters("merc",
                "+proj=merc +ellps=WGS84 +units=m +no_defs");

        assertTrue("grid shift + lenient policy",
                new BasicCoordinateTransform(wgs, nad, DomainErrorPolicy.LEGACY_NO_SHIFT)
                        .mayReturnUnshiftedCoordinates());
        assertFalse("grid shift + strict policy",
                new BasicCoordinateTransform(wgs, nad, DomainErrorPolicy.THROW)
                        .mayReturnUnshiftedCoordinates());
        assertFalse("no grid shift + lenient policy",
                new BasicCoordinateTransform(wgs, merc, DomainErrorPolicy.LEGACY_NO_SHIFT)
                        .mayReturnUnshiftedCoordinates());
        assertFalse("no grid shift + strict policy",
                new BasicCoordinateTransform(wgs, merc, DomainErrorPolicy.THROW)
                        .mayReturnUnshiftedCoordinates());

        assertTrue("and the default constructor is the lenient corner",
                new BasicCoordinateTransform(wgs, nad).mayReturnUnshiftedCoordinates());
    }

    /**
     * The bulk path agrees with the single-point path about whether a point succeeded.
     *
     * <p>Stated as a test because it is the one thing about this policy that is easy to get wrong and
     * impossible to notice: if bulk reported
     * {@link org.locationtech.proj4j.bulk.TransformStatus#ERR_OUTSIDE_GRID_EXTENT} while the
     * single-point path returned a coordinate, the two APIs would disagree about the same input.
     */
    @Test
    public void theBulkPathAgreesWithTheSinglePointPath() {
        BasicCoordinateTransform t =
                new BasicCoordinateTransform(wgs84(), nad27(), DomainErrorPolicy.LEGACY_NO_SHIFT);

        double[] xy = {LON, LAT};
        byte[] status = new byte[1];
        int failures = t.transform2D(xy, 0, 1, 2, status);

        assertEquals("bulk must not count a suppressed coverage miss as a failure", 0, failures);
        assertEquals("and must report OK, matching the coordinate the single-point path returns",
                org.locationtech.proj4j.bulk.TransformStatus.OK, status[0]);
        assertEquals(LON, xy[0], 0.0);
        assertEquals(LAT, xy[1], 0.0);
    }

    // ------------------------------------------------------------------ the narrowness, proven

    /**
     * A grid that is declared without PROJ's {@code @} optional marker and cannot be found is still
     * an error under the lenient policy.
     *
     * <p>The complement of the fix: "the grid file is not there" and "the point is outside a grid
     * that loaded" are different questions, and this policy answers only the second one. A
     * mandatory missing grid must not become silent.
     */
    @Test
    public void aMandatoryMissingGridIsStillAnErrorUnderTheLenientPolicy() {
        try {
            CoordinateReferenceSystem bad = crsFactory.createFromParameters("mandatory-missing",
                    "+proj=longlat +ellps=clrk66 +nadgrids=no_such_grid_at_all.gsb +no_defs");
            run(DomainErrorPolicy.LEGACY_NO_SHIFT, wgs84(), bad, LON, LAT);
            fail("a non-@ grid that cannot be resolved must not be silently skipped");
        } catch (RuntimeException expected) {
            assertNotNull("the failure must carry a message naming the grid", expected.getMessage());
            assertTrue("message should name the grid: " + expected.getMessage(),
                    expected.getMessage().contains("no_such_grid_at_all"));
        }
    }

    // ------------------------------------------------------------------ the positive control

    /**
     * <b>The control that proves this file can fail.</b>
     *
     * <p>Reverting the fix means making the default policy strict again. This asserts the two halves
     * that a revert would break, in the two directions, on one input — so it fails whether the
     * revert makes the default throw or makes the strict policy lenient.
     *
     * <p>It is deliberately not a mock: both legs run a real {@code EPSG:4326 -> EPSG:4267} transform
     * and the assertion names the coordinate, which is what makes a failure diagnosable without a
     * debugger.
     */
    @Test
    public void theControlDiscriminatesInBothDirections() {
        CrsTransformException strictRefusal = null;
        try {
            run(DomainErrorPolicy.THROW, wgs84(), nad27(), LON, LAT);
        } catch (CrsTransformException e) {
            strictRefusal = e;
        }
        assertNotNull("leg 1: THROW must refuse (" + LON + ", " + LAT + ")", strictRefusal);
        assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, strictRefusal.cause());

        CrsTransformException lenientRefusal = null;
        ProjCoordinate out = null;
        try {
            out = run(DomainErrorPolicy.LEGACY_NO_SHIFT, wgs84(), nad27(), LON, LAT);
        } catch (CrsTransformException e) {
            lenientRefusal = e;
        }
        assertNull("leg 2: LEGACY_NO_SHIFT must NOT refuse (" + LON + ", " + LAT + ")",
                lenientRefusal);
        assertNotNull(out);
        assertEquals("leg 2: and must return the input unshifted", LON, out.x, 0.0);

        // The two legs used the same CRSs and the same coordinate, so the only variable was the
        // policy. That is what makes this a discriminator rather than two independent assertions.
    }
}
