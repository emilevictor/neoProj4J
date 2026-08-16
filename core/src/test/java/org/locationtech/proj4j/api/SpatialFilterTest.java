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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;

/**
 * {@code OperationSelector.computeAreaOfInterest} and {@code OperationSelector.filterOut}, ported
 * from PROJ 9.8.1's {@code FilterResults}.
 *
 * <h4>The thing to hold on to while reading this file</h4>
 *
 * <p><b>The spatial filter is on by default and nobody has to ask for it.</b> With no
 * {@link ProjContext.Builder#areaOfInterest(AreaOfUse)} call at all, an area of interest is
 * synthesised from the two CRSs' own declared extents and every candidate is tested against it.
 * That is what PROJ does, and it is why {@link SourceTargetCRSExtentUse#NONE} exists as the only way
 * to switch the test off.
 *
 * <p>Two branches here are not reachable through any database this library ships and are driven
 * directly: {@link SourceTargetCRSExtentUse#BOTH}, and the second of the rescue clause's two
 * triggers. A rescue that never fires would be indistinguishable from no rescue at all, so both
 * triggers are fired separately below and each is shown to change the answer.
 *
 * <p>Expected values are derived from
 * {@code 9.8.1:src/iso19111/operation/coordinateoperationfactory.cpp:1239-1408}, read at that tag.
 */
public class SpatialFilterTest {

    private static final DbObjectRef SRC = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4267");
    private static final DbObjectRef TGT = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4269");

    private static AreaOfUse area(double west, double south, double east, double north,
                                  String description) {
        return new AreaOfUse(west, south, east, north, description, true);
    }

    /**
     * A candidate carrying nothing but the two things the filter reads: a name (from which
     * ballpark-ness is inferred) and an extent.
     */
    private static CrsOperationCandidate op(String code, String name, AreaOfUse extent) {
        DbOperation row = new DbOperation(DbObjectType.GRID_TRANSFORMATION, "EPSG", code, name,
                "EPSG", "9615", "NTv2", SRC, TGT, 1.0, null, null, null, null, null, false);
        return new CrsOperationCandidate(row, false, false, new Accuracy(1.0, "EPSG:" + code),
                new ArrayList<GridInfo>(0), extent, CrsOperationCandidate.Rejection.NONE, null,
                null, 0);
    }

    /** The synthesised ballpark, as {@code OperationSelector.ballpark} builds it. */
    private static CrsOperationCandidate ballpark(AreaOfUse extent) {
        DbOperation row = new DbOperation(DbObjectType.OTHER_TRANSFORMATION, "PROJ",
                "BALLPARK_4267_TO_4269", "Ballpark geographic offset from EPSG:6267 to EPSG:6269",
                "PROJ", "PROJString", "+proj=noop", SRC, TGT, Double.NaN, null, null, null, null,
                null, false);
        return new CrsOperationCandidate(row, false, true, null, new ArrayList<GridInfo>(0), extent,
                CrsOperationCandidate.Rejection.BALLPARK, "ballpark", null, 0);
    }

    private static List<String> codesOf(List<CrsOperationCandidate> ops) {
        List<String> out = new ArrayList<String>(ops.size());
        for (CrsOperationCandidate op : ops) {
            out.add(op.authorityCode());
        }
        return out;
    }

    private static ProjContext with(SpatialCriterion criterion, SourceTargetCRSExtentUse use) {
        return ProjContext.builder().spatialCriterion(criterion).sourceTargetCrsExtentUse(use)
                .build();
    }

    // Nebraska-ish, inside CONUS. Deliberately not near (0, 0).
    private static final AreaOfUse CONUS = area(-124.79, 24.41, -66.91, 49.38, "USA - CONUS");
    private static final AreaOfUse CANADA = area(-141.01, 40.04, -47.74, 86.46, "Canada");
    private static final AreaOfUse WORLD = area(-180, -90, 180, 90, "World");
    private static final AreaOfUse ALASKA = area(172.42, 51.3, -129.99, 71.4, "USA - Alaska");

    // ------------------------------------------------------- computeAreaOfInterest

    /**
     * The headline: no caller input, and there is still an area of interest. If this ever starts
     * returning null for the default context the filter silently stops running and every candidate
     * list quietly widens.
     */
    @Test
    public void withNoCallerInputTheAreaOfInterestIsStillSynthesised() {
        AreaOfUse got = OperationSelector.computeAreaOfInterest(ProjContext.DEFAULT, CONUS, CANADA);
        assertNotNull("the default context must still produce an area of interest", got);
        assertFalse(ProjContext.DEFAULT.areaOfInterest().isPresent());
        assertEquals(SourceTargetCRSExtentUse.SMALLEST, ProjContext.DEFAULT.sourceTargetCrsExtentUse());
    }

    @Test
    public void aCallerSuppliedAreaOfInterestWinsOverEveryExtentUse() {
        AreaOfUse mine = area(-100, 40, -99, 41, "my field site");
        for (SourceTargetCRSExtentUse use : SourceTargetCRSExtentUse.values()) {
            ProjContext context = ProjContext.builder().areaOfInterest(mine)
                    .sourceTargetCrsExtentUse(use).build();
            assertSame(use.toString(), mine,
                    OperationSelector.computeAreaOfInterest(context, CONUS, CANADA));
        }
    }

    @Test
    public void smallestPicksTheSmallerBySolidAngle() {
        ProjContext context = with(SpatialCriterion.PARTIAL_INTERSECTION,
                SourceTargetCRSExtentUse.SMALLEST);
        assertSame(CONUS, OperationSelector.computeAreaOfInterest(context, CONUS, CANADA));
        assertSame(CONUS, OperationSelector.computeAreaOfInterest(context, CANADA, CONUS));
        // With one extent missing it uses the one it has, rather than giving up.
        assertSame(CANADA, OperationSelector.computeAreaOfInterest(context, null, CANADA));
        assertSame(CANADA, OperationSelector.computeAreaOfInterest(context, CANADA, null));
        assertNull(OperationSelector.computeAreaOfInterest(context, null, null));
    }

    /**
     * {@code INTERSECTION} is computed only when both extents exist. Upstream deliberately leaves
     * the area null with one, rather than falling back to the one it has -- which is the opposite of
     * what {@code SMALLEST} does with the same input, and is the difference this pins.
     */
    @Test
    public void intersectionNeedsBothExtentsAndOtherwiseLeavesTheAreaNull() {
        ProjContext context = with(SpatialCriterion.PARTIAL_INTERSECTION,
                SourceTargetCRSExtentUse.INTERSECTION);
        AreaOfUse both = OperationSelector.computeAreaOfInterest(context, CONUS, CANADA);
        assertNotNull(both);
        assertEquals(-124.79, both.westLongitude(), 0.0);
        assertEquals(-66.91, both.eastLongitude(), 0.0);
        assertEquals(40.04, both.southLatitude(), 0.0);
        assertEquals(49.38, both.northLatitude(), 0.0);
        assertNull("an intersection this library computed is not an authority's statement",
                both.description());
        assertFalse(both.isDatabaseDerived());

        assertNull(OperationSelector.computeAreaOfInterest(context, null, CANADA));
        assertNull(OperationSelector.computeAreaOfInterest(context, CONUS, null));
    }

    @Test
    public void noneAndBothLeaveTheAreaNull() {
        assertNull(OperationSelector.computeAreaOfInterest(
                with(SpatialCriterion.PARTIAL_INTERSECTION, SourceTargetCRSExtentUse.NONE),
                CONUS, CANADA));
        assertNull(OperationSelector.computeAreaOfInterest(
                with(SpatialCriterion.PARTIAL_INTERSECTION, SourceTargetCRSExtentUse.BOTH),
                CONUS, CANADA));
    }

    /**
     * The synthesised area can itself cross the antimeridian, so this is not a synthetic worry.
     * Alaska's extent runs {@code 172.42} east to {@code -129.99}, and measuring it the naive way
     * would make it the largest extent in the database instead of one of the smaller ones.
     */
    @Test
    public void aSynthesisedAreaOfInterestCanCrossTheAntimeridian() {
        ProjContext context = with(SpatialCriterion.PARTIAL_INTERSECTION,
                SourceTargetCRSExtentUse.SMALLEST);
        assertTrue(ALASKA.crossesAntimeridian());
        assertSame("Alaska is smaller than the world however its longitudes are written",
                ALASKA, OperationSelector.computeAreaOfInterest(context, ALASKA, WORLD));
    }

    // ------------------------------------------------------- filterOut, area of interest

    @Test
    public void strictContainmentKeepsOnlyOperationsThatSwallowTheWholeArea() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", CONUS),
                op("1243", "NAD27 to NAD83 (3)", CANADA),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals(Arrays.asList("EPSG:1241", "PROJ:BALLPARK_4267_TO_4269"), codesOf(kept));
    }

    @Test
    public void partialIntersectionKeepsAnythingThatOverlapsAtAll() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", CONUS),
                op("1243", "NAD27 to NAD83 (3)", CANADA),
                op("9999", "Somewhere else entirely", area(100, -40, 150, -10, "Australia")),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.PARTIAL_INTERSECTION, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals(Arrays.asList("EPSG:1241", "EPSG:1243", "PROJ:BALLPARK_4267_TO_4269"), codesOf(kept));
    }

    /**
     * The two criteria must actually disagree on the same input, or one of them is not being
     * applied. This is the positive control for the pair of tests above.
     */
    @Test
    public void theTwoCriteriaDisagreeOnTheSameInput() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", CONUS),
                op("1243", "NAD27 to NAD83 (3)", CANADA),
                ballpark(WORLD));
        List<String> strict = codesOf(OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA));
        List<String> partial = codesOf(OperationSelector.filterOut(all,
                with(SpatialCriterion.PARTIAL_INTERSECTION, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA));
        assertFalse(strict + " should differ from " + partial, strict.equals(partial));
    }

    /**
     * A null area of interest under anything but {@code BOTH} means no spatial test at all, which is
     * how {@link SourceTargetCRSExtentUse#NONE} switches the filter off.
     */
    @Test
    public void withNoAreaOfInterestNothingIsFilteredOut() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", CONUS),
                op("9999", "Somewhere else entirely", area(100, -40, 150, -10, "Australia")),
                op("8888", "No extent at all", null),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.NONE),
                null, CONUS, CANADA);
        assertEquals(Arrays.asList("EPSG:1241", "EPSG:9999", "EPSG:8888", "PROJ:BALLPARK_4267_TO_4269"), codesOf(kept));
    }

    /**
     * Upstream's "same description" shortcut, which fires only for a caller-supplied area of
     * interest: if any operation declares an extent with exactly that name, only operations with
     * that name survive -- regardless of geometry.
     */
    @Test
    public void anAreaOfInterestNamedLikeAnExtentSelectsByNameNotByGeometry() {
        AreaOfUse namedLikeConus = new AreaOfUse(-124.79, 24.41, -66.91, 49.38, "USA - CONUS", false);
        ProjContext context = ProjContext.builder().areaOfInterest(namedLikeConus).build();
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", CONUS),
                op("1313", "NAD27 to NAD83 (13)", CANADA),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all, context,
                namedLikeConus, CONUS, CANADA);
        assertEquals("the world-extent ballpark is dropped despite containing the area",
                Arrays.asList("EPSG:1241"), codesOf(kept));

        // Control: rename the area, the shortcut does not fire, and geometry alone decides -- so
        // both the Canadian operation and the world-extent ballpark come back. Same bounds, same
        // candidates, different answer, from the description and nothing else.
        AreaOfUse unnamed = new AreaOfUse(-124.79, 24.41, -66.91, 49.38, "my field site", false);
        ProjContext other = ProjContext.builder().areaOfInterest(unnamed).build();
        assertEquals(Arrays.asList("EPSG:1241", "EPSG:1313", "PROJ:BALLPARK_4267_TO_4269"),
                codesOf(OperationSelector.filterOut(all, other, unnamed, CONUS, CANADA)));
    }

    // ------------------------------------------------------- filterOut, BOTH

    /**
     * {@code BOTH} tests against the two CRS extents separately, and is strictly harder to satisfy
     * than {@code INTERSECTION}: an operation containing the overlap of the two need not contain
     * either of them.
     */
    @Test
    public void bothTestsAgainstEachCrsExtentSeparately() {
        AreaOfUse overlapOnly = area(-124.79, 40.04, -66.91, 49.38, "the overlap");
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", overlapOnly),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.BOTH),
                null, CONUS, CANADA);
        assertEquals("an operation covering only the overlap contains neither CRS extent",
                Arrays.asList("PROJ:BALLPARK_4267_TO_4269"), codesOf(kept));

        // Control: the same operation passes under INTERSECTION, whose area it does contain.
        AreaOfUse intersection = OperationSelector.computeAreaOfInterest(
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.INTERSECTION),
                CONUS, CANADA);
        assertEquals(Arrays.asList("EPSG:1241", "PROJ:BALLPARK_4267_TO_4269"),
                codesOf(OperationSelector.filterOut(all,
                        with(SpatialCriterion.STRICT_CONTAINMENT,
                                SourceTargetCRSExtentUse.INTERSECTION),
                        intersection, CONUS, CANADA)));
    }

    /**
     * Upstream's asymmetry under {@code BOTH} + {@code PARTIAL_INTERSECTION}: a missing
     * {@code extent1} counts as intersecting, a missing {@code extent2} counts as <b>not</b>
     * intersecting.
     *
     * <pre>
     * bool extentIntersectsExtent1 = !extent1 || extent-&gt;intersects(NN_NO_CHECK(extent1));
     * bool extentIntersectsExtent2 =  extent2 &amp;&amp; extent-&gt;intersects(NN_NO_CHECK(extent2));
     * </pre>
     *
     * <p>{@code coordinateoperationfactory.cpp:1384-1387} at tag 9.8.1. It reads like a typo, and it
     * has teeth: with a target CRS that declares no extent, <b>every operation is filtered out and
     * neither rescue trigger fires</b>, because setting {@code hasNonBallparkOpWithExtent} happens
     * before the criterion test and {@code hasNonBallparkWithoutExtent} stays false. The answer is
     * an empty candidate list, which {@code select} then reports as a refusal. Pinned here so that
     * nobody tidies the asymmetry away without noticing they have changed the answer, and so that
     * the empty list is recorded as understood rather than discovered later as a mystery.
     */
    @Test
    public void bothWithPartialIntersectionIsAsymmetricInTheMissingExtent() {
        List<CrsOperationCandidate> all = Arrays.asList(op("1241", "NAD27 to NAD83 (1)", CONUS));
        ProjContext context = with(SpatialCriterion.PARTIAL_INTERSECTION,
                SourceTargetCRSExtentUse.BOTH);

        // Source extent missing: the operation survives.
        assertEquals(Arrays.asList("EPSG:1241"),
                codesOf(OperationSelector.filterOut(all, context, null, null, CANADA)));

        // Both extents present: it survives too, so the case above is not passing by accident.
        assertEquals(Arrays.asList("EPSG:1241"),
                codesOf(OperationSelector.filterOut(all, context, null, CONUS, CANADA)));

        // Target extent missing: dropped, and not rescued.
        assertTrue("a null extent2 alone empties the candidate list",
                OperationSelector.filterOut(all, context, null, CONUS, null).isEmpty());

        // The same input under STRICT_CONTAINMENT, whose two legs are symmetric, keeps it -- which
        // is what makes the line above an asymmetry rather than just a strict filter.
        assertEquals(Arrays.asList("EPSG:1241"),
                codesOf(OperationSelector.filterOut(all,
                        with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.BOTH),
                        null, CONUS, null)));
    }

    // ------------------------------------------------------- filterOut, the rescue

    /**
     * Rescue trigger 1: {@code res.empty() && !hasNonBallparkOpWithExtent}. Nothing survived, and
     * nothing that could have survived declared an extent, so the spatial test is dropped and the
     * whole list comes back.
     */
    @Test
    public void rescueTriggerOneFiresWhenNothingSurvivedAndNothingDeclaredAnExtent() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", null),
                op("1313", "NAD27 to NAD83 (13)", null));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals(Arrays.asList("EPSG:1241", "EPSG:1313"), codesOf(kept));
    }

    /**
     * Rescue trigger 2: {@code hasOnlyBallpark && hasNonBallparkWithoutExtent}. <b>{@code res} is
     * not empty here</b> -- the ballpark survived -- which is why reading the rescue as a single
     * "if nothing survived" condition loses this case entirely. A real operation was thrown out for
     * declaring no extent while only a ballpark got through, so the list is re-run without the
     * spatial test.
     */
    @Test
    public void rescueTriggerTwoFiresEvenThoughSomethingSurvived() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", null),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals("the extent-less real operation must be rescued alongside the ballpark",
                Arrays.asList("PROJ:BALLPARK_4267_TO_4269", "EPSG:1241"), codesOf(kept));
    }

    /**
     * And the rescue must <b>not</b> fire when a real operation with an extent was considered and
     * merely lost on geometry. Without this the filter would be a no-op dressed up as a filter.
     */
    @Test
    public void neitherRescueTriggerFiresWhenARealOperationWithAnExtentWasWeighed() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("9999", "Somewhere else entirely", area(100, -40, 150, -10, "Australia")));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertTrue("an operation on the wrong continent is not rescued", kept.isEmpty());
    }

    /**
     * The rescue appends rather than clears, so trigger 2 can meet an operation that is already in
     * the list. Upstream lets the duplicate through and removes it later in {@code andSort}; this
     * dedupes as it appends, and the assertion is that no candidate appears twice.
     */
    @Test
    public void theRescueDoesNotDuplicateAnOperationItAlreadyKept() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", null),
                ballpark(WORLD));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals(2, kept.size());
        assertEquals(2, new java.util.HashSet<CrsOperationCandidate>(kept).size());
    }

    /**
     * An operation whose own name marks it ballpark counts as ballpark for the rescue triggers, not
     * only the synthesised candidate. Upstream asks {@code op->hasBallparkTransformation()}, which
     * is true of both.
     */
    @Test
    public void anAuthorityRowNamedBallparkCountsAsBallparkForTheRescue() {
        CrsOperationCandidate namedBallpark =
                op("7777", "Ballpark geographic offset from NAD27 to NAD83", WORLD);
        List<CrsOperationCandidate> all = Arrays.asList(
                op("1241", "NAD27 to NAD83 (1)", null),
                namedBallpark);
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertEquals("trigger 2 needs hasOnlyBallpark, which this row must satisfy",
                Arrays.asList("EPSG:7777", "EPSG:1241"), codesOf(kept));
    }

    // ------------------------------------------------------- the ballpark's extent

    /**
     * The synthesised ballpark declares the world, and the world contains any area of interest of
     * non-zero width -- so the ballpark survives even {@link SpatialCriterion#STRICT_CONTAINMENT}.
     * That is why {@code projinfo -s EPSG:4267 -t EPSG:4269 --summary} reports one candidate rather
     * than none, and if the ballpark ever loses its extent the count goes to zero.
     */
    @Test
    public void aWorldExtentBallparkSurvivesEvenStrictContainment() {
        List<CrsOperationCandidate> all = Arrays.asList(ballpark(WORLD));
        for (AreaOfUse aoi : new AreaOfUse[] {CONUS, CANADA, ALASKA}) {
            assertEquals(aoi.toString(), 1, OperationSelector.filterOut(all,
                    with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                    aoi, CONUS, CANADA).size());
        }
    }

    /**
     * The control for the test above, and the reason {@code ballparkExtent} exists: an extent-less
     * ballpark is dropped by the very first branch of the loop. It is only the rescue that puts it
     * back, and the rescue is not always available.
     */
    @Test
    public void anExtentLessBallparkIsDroppedByTheFilter() {
        List<CrsOperationCandidate> all = Arrays.asList(
                op("9999", "Somewhere else entirely", area(100, -40, 150, -10, "Australia")),
                ballpark(null));
        List<CrsOperationCandidate> kept = OperationSelector.filterOut(all,
                with(SpatialCriterion.STRICT_CONTAINMENT, SourceTargetCRSExtentUse.SMALLEST),
                CONUS, CONUS, CANADA);
        assertTrue("an operation with no extent cannot pass a spatial test", kept.isEmpty());
    }
}
