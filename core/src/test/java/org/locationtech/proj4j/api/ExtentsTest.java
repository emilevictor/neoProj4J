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

import org.junit.Test;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@link Extents} against PROJ 9.8.1's own branch shapes, with the antimeridian probed directly.
 *
 * <p>The reason this file exists rather than leaving the primitives to be exercised by whatever the
 * shipped database happens to contain: <b>a box crossing 180&deg; is written {@code west > east}</b>,
 * every one of the four operations has a separate branch for it, and the naive form of each is wrong
 * by the complement rather than by a rounding error. This library already has one open defect of
 * that exact shape at longitude 180, so each test below states what the naive answer would have been
 * where the two differ.
 *
 * <p>Expected values are derived from {@code 9.8.1:src/iso19111/metadata.cpp:284-491} and
 * {@code 9.8.1:src/iso19111/operation/coordinateoperationfactory.cpp:140-160}, read at that tag; not
 * from this library's output.
 */
public class ExtentsTest {

    private static final double EPS = 1e-12;

    private static AreaOfUse box(double west, double south, double east, double north) {
        return new AreaOfUse(west, south, east, north, null, false);
    }

    private static final AreaOfUse WORLD = box(-180, -90, 180, 90);

    // ---------------------------------------------------------------- pseudoArea

    @Test
    public void pseudoAreaOfNothingIsZero() {
        assertEquals(0.0, Extents.pseudoArea(null), 0.0);
    }

    @Test
    public void pseudoAreaIsLongitudeSpanTimesTheSineDifference() {
        // (10 - 0) * (sin 90 - sin 0) = 10.
        assertEquals(10.0, Extents.pseudoArea(box(0, 0, 10, 90)), EPS);
        // A band with no height has no area whatever its width.
        assertEquals(0.0, Extents.pseudoArea(box(-180, 45, 180, 45)), 0.0);
    }

    /**
     * The wrap. {@code west = 170, east = -170} is a 20&deg;-wide box, not a &minus;340&deg;-wide
     * one, and it is the {@code east += 360} that says so.
     */
    @Test
    public void pseudoAreaOfAWrappingBoxIsPositiveAndTwentyDegreesWide() {
        double wrapping = Extents.pseudoArea(box(170, 0, -170, 10));
        double sameWidthAwayFromTheAntimeridian = Extents.pseudoArea(box(0, 0, 20, 10));
        assertTrue("a 20-degree box that happens to straddle 180 must not measure negative",
                wrapping > 0.0);
        assertEquals(sameWidthAwayFromTheAntimeridian, wrapping, EPS);
        // Without the wrap correction this would have been (-170 - 170) = -340 degrees wide.
        assertEquals(20.0 * StrictMath.sin(ProjectionMath.toRad(10.0)), wrapping, EPS);
    }

    /**
     * A box whose west bound is exactly 180. This is the coordinate the open defect elsewhere in
     * this library gets wrong, so it is stated as its own case: 180 is a legal west bound, it is
     * greater than any legal east bound except itself, and the box therefore wraps.
     */
    @Test
    public void pseudoAreaAtLongitudeOneEightyWraps() {
        AreaOfUse fromTheDateLine = box(180, 60, -170, 70);
        assertTrue(fromTheDateLine.crossesAntimeridian());
        double expected = 10.0 * (StrictMath.sin(ProjectionMath.toRad(70.0))
                - StrictMath.sin(ProjectionMath.toRad(60.0)));
        assertEquals(expected, Extents.pseudoArea(fromTheDateLine), EPS);
        assertTrue(Extents.pseudoArea(fromTheDateLine) > 0.0);
    }

    /**
     * Pseudo-area is a solid angle, not a rectangle, and the whole reason PROJ measures it that way
     * is that the two rank differently. A 40&deg;-wide Nordic box is <em>smaller</em> than a
     * 20&deg;-wide equatorial one.
     */
    @Test
    public void pseudoAreaIsASolidAngleSoAWideNordicBoxBeatsANarrowEquatorialOne() {
        AreaOfUse nordic = box(0, 60, 40, 70);
        AreaOfUse equatorial = box(0, 0, 20, 10);
        assertTrue(Extents.pseudoArea(nordic) < Extents.pseudoArea(equatorial));
        // The flat rectangle a rewrite would reach for ranks them the other way round.
        double nordicFlat = (40 - 0) * (70 - 60);
        double equatorialFlat = (20 - 0) * (10 - 0);
        assertTrue(nordicFlat > equatorialFlat);
    }

    /**
     * The ranking must not move with the JVM, so the transcription uses {@link StrictMath} and
     * {@link ProjectionMath#toRad}. Asserted bit-for-bit, not to a tolerance -- a tolerance here
     * would pass under exactly the substitution it exists to forbid.
     */
    @Test
    public void pseudoAreaIsBitForBitStrictMath() {
        AreaOfUse a = box(-100.5, 17.25, -80.125, 49.75);
        double expected = (-80.125 - -100.5)
                * (StrictMath.sin(ProjectionMath.toRad(49.75))
                        - StrictMath.sin(ProjectionMath.toRad(17.25)));
        assertEquals(Double.doubleToLongBits(expected),
                Double.doubleToLongBits(Extents.pseudoArea(a)));
    }

    // ---------------------------------------------------------------- contains

    @Test
    public void containsIsFalseWhenEitherBoxIsAbsent() {
        assertFalse(Extents.contains(null, WORLD));
        assertFalse(Extents.contains(WORLD, null));
        assertFalse(Extents.contains(null, null));
    }

    @Test
    public void containsChecksLatitudeFirst() {
        assertFalse(Extents.contains(box(-10, 0, 10, 10), box(-5, -1, 5, 5)));
        assertFalse(Extents.contains(box(-10, 0, 10, 10), box(-5, 5, 5, 11)));
        assertTrue(Extents.contains(box(-10, 0, 10, 10), box(-5, 0, 5, 10)));
    }

    /**
     * The full-width outer rule, {@code return oW != oE}. Two consequences worth pinning: the world
     * contains the world, and the world does <b>not</b> contain a box of zero longitude width.
     */
    @Test
    public void aFullWidthOuterBoxContainsAnythingWithNonZeroWidthIncludingItself() {
        assertTrue(Extents.contains(WORLD, box(-10, -10, 10, 10)));
        assertTrue(Extents.contains(WORLD, box(170, -10, -170, 10)));
        assertTrue("the first rule fires before the full-width-inner rule",
                Extents.contains(WORLD, WORLD));
        assertFalse("a meridian has no width, and upstream says that is not contained",
                Extents.contains(WORLD, box(10, -10, 10, 10)));
    }

    /**
     * The full-width inner rule. A box one thousandth of a degree short of the world contains
     * nothing full width, however well the latitudes fit -- upstream returns false flatly rather
     * than comparing bounds.
     */
    @Test
    public void aFullWidthInnerBoxIsContainedOnlyByAFullWidthOuterBox() {
        assertFalse(Extents.contains(box(-179.999, -90, 180, 90), WORLD));
        assertFalse(Extents.contains(box(-180, -90, 179.999, 90), WORLD));
        assertTrue(Extents.contains(WORLD, WORLD));
    }

    @Test
    public void aNormalOuterBoxCannotContainAWrappingOne() {
        // The inner box is 10 degrees wide and sits inside the outer box's longitude range in every
        // arithmetic sense a naive comparison would use; it still is not contained, because it is
        // the far 350 degrees that the wrap denotes.
        assertFalse(Extents.contains(box(-180, -20, 180 - 1e-9, 20), box(175, -10, -175, 10)));
        assertFalse(Extents.contains(box(-100, -20, 100, 20), box(50, -10, -50, 10)));
    }

    /**
     * A wrapping outer box contains a normal inner box that sits wholly in either lobe, which is the
     * case a bound-for-bound comparison gets wrong. {@code (179.5, 180)} is inside
     * {@code (170 .. -170)}; {@code e >= oe} reads {@code -170 >= 180} and says otherwise.
     */
    @Test
    public void aWrappingOuterBoxContainsBoxesInEitherLobeIncludingOnesTouchingOneEighty() {
        AreaOfUse wrapping = box(170, 60, -170, 80);

        AreaOfUse touchingTheDateLineFromTheWest = box(179.5, 65, 180.0, 75);
        assertTrue(Extents.contains(wrapping, touchingTheDateLineFromTheWest));
        assertFalse("the naive bound-for-bound form is what gets this wrong",
                naiveContains(wrapping, touchingTheDateLineFromTheWest));

        assertTrue(Extents.contains(wrapping, box(175, 65, 179, 75)));
        assertTrue(Extents.contains(wrapping, box(-179, 65, -175, 75)));
        assertFalse(Extents.contains(wrapping, box(-100, 65, -90, 75)));
        assertFalse(Extents.contains(wrapping, box(0, 65, 10, 75)));
    }

    @Test
    public void twoWrappingBoxesAreComparedBoundForBound() {
        AreaOfUse outer = box(170, -20, -170, 20);
        assertTrue(Extents.contains(outer, box(175, -10, -175, 10)));
        assertFalse(Extents.contains(outer, box(165, -10, -175, 10)));
        assertFalse(Extents.contains(outer, box(175, -10, -165, 10)));
    }

    /** The three-line version this port exists to avoid, kept only as a foil for the assertions. */
    private static boolean naiveContains(AreaOfUse outer, AreaOfUse inner) {
        return outer.southLatitude() <= inner.southLatitude()
                && outer.northLatitude() >= inner.northLatitude()
                && outer.westLongitude() <= inner.westLongitude()
                && outer.eastLongitude() >= inner.eastLongitude();
    }

    // ---------------------------------------------------------------- intersects

    @Test
    public void intersectsIsFalseWhenEitherBoxIsAbsent() {
        assertFalse(Extents.intersects(null, WORLD));
        assertFalse(Extents.intersects(WORLD, null));
    }

    @Test
    public void intersectsChecksLatitudeFirst() {
        assertFalse(Extents.intersects(box(-10, 0, 10, 10), box(-5, 20, 5, 30)));
        assertFalse(Extents.intersects(box(-10, 20, 10, 30), box(-5, 0, 5, 10)));
    }

    /**
     * Upstream's comparison is {@code max(W, oW) < min(E, oE)}, strictly. Two boxes sharing only an
     * edge do not intersect, and this library keeps that rather than admitting an operation whose
     * extent touches the area of interest at a line.
     */
    @Test
    public void sharingOnlyAnEdgeIsNotIntersecting() {
        assertFalse(Extents.intersects(box(0, 0, 10, 10), box(10, 0, 20, 10)));
        assertTrue(Extents.intersects(box(0, 0, 10, 10), box(9.999999, 0, 20, 10)));
        // Latitude, by contrast, is compared non-strictly: N < oS is the rejection.
        assertTrue(Extents.intersects(box(0, 0, 10, 10), box(0, 10, 10, 20)));
    }

    @Test
    public void worldCoverageAlwaysMeetsAWrappingBox() {
        assertTrue(Extents.intersects(WORLD, box(175, -10, -175, 10)));
        assertTrue(Extents.intersects(box(175, -10, -175, 10), WORLD));
        // And it is genuinely the special case, not the general path: latitude still governs.
        assertFalse(Extents.intersects(box(-180, 50, 180, 60), box(175, -10, -175, 10)));
    }

    /**
     * The split-and-recurse case, and the one the naive form gets flatly wrong. The normal box lives
     * just east of the antimeridian; the wrapping box reaches across it. They overlap in the eastern
     * lobe, and {@code max(-179, 170) < min(-175, -176)} says they do not.
     */
    @Test
    public void aNormalBoxAndAWrappingBoxMeetByBeingSplitAtTheAntimeridian() {
        AreaOfUse normal = box(-179, -10, -175, 10);
        AreaOfUse wrapping = box(170, -10, -176, 10);
        assertTrue(Extents.intersects(normal, wrapping));
        assertTrue("the predicate must be symmetric", Extents.intersects(wrapping, normal));
        assertFalse("the naive form: max(-179, 170) < min(-175, -176)",
                StrictMath.max(-179.0, 170.0) < StrictMath.min(-175.0, -176.0));

        // The other lobe, reached by the first half of the recursion rather than the second.
        assertTrue(Extents.intersects(box(172, -10, 178, 10), wrapping));

        // And a box in neither lobe still does not intersect, so the recursion is not vacuous.
        assertFalse(Extents.intersects(box(-100, -10, -90, 10), wrapping));
        assertFalse(Extents.intersects(box(0, -10, 100, 10), wrapping));
    }

    @Test
    public void twoWrappingBoxesAlwaysIntersect() {
        assertTrue(Extents.intersects(box(179, -10, -179, 10), box(170, -10, -170, 10)));
        // Even these two, which share only the 1-degree sliver either side of 180.
        assertTrue(Extents.intersects(box(179.9, -10, -179.9, 10), box(179.5, -10, -179.5, 10)));
    }

    @Test
    public void intersectsIsSymmetricOverAMixedPopulation() {
        AreaOfUse[] population = {
            WORLD,
            box(-180, -90, 180, 0),
            box(-179, -10, -175, 10),
            box(170, -10, -176, 10),
            box(179, 60, -179, 80),
            box(0, 0, 10, 10),
            box(10, 0, 20, 10),
            box(180, 60, -170, 70),
            box(-100.5, 17.25, -80.125, 49.75),
        };
        for (AreaOfUse a : population) {
            for (AreaOfUse b : population) {
                assertEquals(a + " vs " + b, Extents.intersects(a, b), Extents.intersects(b, a));
            }
        }
    }

    /**
     * Every box that {@code contains} another must also {@code intersect} it. The one documented
     * exception is a zero-width box, which no box contains and none intersects either, so the
     * implication holds throughout.
     */
    @Test
    public void containmentImpliesIntersectionOverAMixedPopulation() {
        AreaOfUse[] population = {
            WORLD,
            box(-179.999, -90, 180, 90),
            box(-179, -10, -175, 10),
            box(170, -20, -170, 20),
            box(175, -10, -175, 10),
            box(179.5, 65, 180.0, 75),
            box(170, 60, -170, 80),
            box(0, 0, 10, 10),
            box(-100.5, 17.25, -80.125, 49.75),
        };
        for (AreaOfUse outer : population) {
            for (AreaOfUse inner : population) {
                if (Extents.contains(outer, inner)) {
                    assertTrue(outer + " contains but does not intersect " + inner,
                            Extents.intersects(outer, inner));
                }
            }
        }
    }

    // ---------------------------------------------------------------- intersection

    @Test
    public void intersectionIsNullWhenEitherBoxIsAbsent() {
        assertNull(Extents.intersection(null, WORLD));
        assertNull(Extents.intersection(WORLD, null));
    }

    @Test
    public void intersectionOfTwoNormalBoxesIsTheOverlap() {
        AreaOfUse got = Extents.intersection(box(-10, -10, 10, 10), box(0, -20, 20, 5));
        assertNotNull(got);
        assertEquals(0.0, got.westLongitude(), 0.0);
        assertEquals(10.0, got.eastLongitude(), 0.0);
        assertEquals(-10.0, got.southLatitude(), 0.0);
        assertEquals(5.0, got.northLatitude(), 0.0);
    }

    /**
     * The result is this library's arithmetic, not an authority's statement, and
     * {@code OperationSelector.filterOut} keys its shortcut off the description being present. Both
     * of those depend on what is asserted here.
     */
    @Test
    public void anIntersectionIsNotDatabaseDerivedAndCarriesNoDescription() {
        AreaOfUse a = new AreaOfUse(-10, -10, 10, 10, "United States (USA)", true);
        AreaOfUse b = new AreaOfUse(0, -20, 20, 5, "Canada", true);
        AreaOfUse got = Extents.intersection(a, b);
        assertNotNull(got);
        assertFalse(got.isDatabaseDerived());
        assertNull(got.description());
    }

    @Test
    public void intersectionIsNullWhenTheBoxesOnlyShareAnEdgeOrMissEntirely() {
        assertNull(Extents.intersection(box(0, 0, 10, 10), box(10, 0, 20, 10)));
        assertNull(Extents.intersection(box(0, 0, 10, 10), box(20, 0, 30, 10)));
        assertNull(Extents.intersection(box(0, 0, 10, 10), box(0, 20, 10, 30)));
    }

    @Test
    public void intersectionWithWorldCoverageReturnsTheWrappingBoxClampedInLatitude() {
        AreaOfUse got = Extents.intersection(box(-180, 0, 180, 90), box(175, -10, -175, 45));
        assertNotNull(got);
        assertTrue("the answer is still a wrapping box", got.crossesAntimeridian());
        assertEquals(175.0, got.westLongitude(), 0.0);
        assertEquals(-175.0, got.eastLongitude(), 0.0);
        assertEquals(0.0, got.southLatitude(), 0.0);
        assertEquals(45.0, got.northLatitude(), 0.0);

        AreaOfUse reversed = Extents.intersection(box(175, -10, -175, 45), box(-180, 0, 180, 90));
        assertNotNull(reversed);
        assertEquals(175.0, reversed.westLongitude(), 0.0);
        assertEquals(-175.0, reversed.eastLongitude(), 0.0);
    }

    /**
     * The lossy case, ported deliberately. A normal box crossed by a wrapping one overlaps in two
     * disjoint pieces and upstream returns <b>the wider one</b> rather than their union, so the
     * answer depends on which piece is wider and both directions are pinned here.
     */
    @Test
    public void aTwoPieceIntersectionReturnsTheWiderPieceAndDiscardsTheOther() {
        AreaOfUse wide = box(-179, -10, 179, 10);

        AreaOfUse easternLobeWider = Extents.intersection(wide, box(170, -10, -175, 10));
        assertNotNull(easternLobeWider);
        assertEquals("the (170, 179) piece spans 9 degrees against the other's 4",
                170.0, easternLobeWider.westLongitude(), 0.0);
        assertEquals(179.0, easternLobeWider.eastLongitude(), 0.0);

        AreaOfUse westernLobeWider = Extents.intersection(wide, box(176, -10, -160, 10));
        assertNotNull(westernLobeWider);
        assertEquals("the (-179, -160) piece spans 19 degrees against the other's 3",
                -179.0, westernLobeWider.westLongitude(), 0.0);
        assertEquals(-160.0, westernLobeWider.eastLongitude(), 0.0);
    }

    @Test
    public void aTwoPieceCandidateWithOnlyOneRealPieceReturnsThatPiece() {
        // The normal box lies wholly west of the antimeridian, so only the (oW, 180) half survives.
        AreaOfUse got = Extents.intersection(box(160, -10, 178, 10), box(170, -10, -175, 10));
        assertNotNull(got);
        assertEquals(170.0, got.westLongitude(), 0.0);
        assertEquals(178.0, got.eastLongitude(), 0.0);

        // And wholly east of it, so only the (-180, oE) half does.
        AreaOfUse other = Extents.intersection(box(-178, -10, -100, 10), box(170, -10, -175, 10));
        assertNotNull(other);
        assertEquals(-178.0, other.westLongitude(), 0.0);
        assertEquals(-175.0, other.eastLongitude(), 0.0);
    }

    @Test
    public void intersectingTwoWrappingBoxesGivesAWrappingBox() {
        AreaOfUse got = Extents.intersection(box(170, -20, -170, 20), box(175, -10, -175, 10));
        assertNotNull(got);
        assertTrue(got.crossesAntimeridian());
        assertEquals(175.0, got.westLongitude(), 0.0);
        assertEquals(-175.0, got.eastLongitude(), 0.0);
        assertEquals(-10.0, got.southLatitude(), 0.0);
        assertEquals(10.0, got.northLatitude(), 0.0);
    }

    /**
     * An intersection exists exactly when {@link Extents#intersects} says so. Checked over the same
     * mixed population, because the two functions have the same branch structure and a divergence
     * between them would mean one of the transcriptions slipped.
     */
    @Test
    public void intersectionAgreesWithIntersectsOverAMixedPopulation() {
        AreaOfUse[] population = {
            WORLD,
            box(-180, -90, 180, 0),
            box(-179, -10, -175, 10),
            box(170, -10, -176, 10),
            box(179, 60, -179, 80),
            box(0, 0, 10, 10),
            box(10, 0, 20, 10),
            box(180, 60, -170, 70),
            box(-100.5, 17.25, -80.125, 49.75),
        };
        for (AreaOfUse a : population) {
            for (AreaOfUse b : population) {
                assertEquals(a + " vs " + b,
                        Extents.intersects(a, b), Extents.intersection(a, b) != null);
            }
        }
    }

    // ---------------------------------------------------------------- smaller

    @Test
    public void smallerFallsBackToWhicheverBoxExists() {
        AreaOfUse only = box(0, 0, 10, 10);
        assertSame(only, Extents.smaller(null, only));
        assertSame(only, Extents.smaller(only, null));
        assertNull(Extents.smaller(null, null));
    }

    @Test
    public void smallerRanksBySolidAngleNotByWidth() {
        AreaOfUse nordic = box(0, 60, 40, 70);
        AreaOfUse equatorial = box(0, 0, 20, 10);
        assertSame(nordic, Extents.smaller(nordic, equatorial));
        assertSame(nordic, Extents.smaller(equatorial, nordic));
    }

    /**
     * Upstream's tie-break is implicit in the shape of its {@code if}: strictly-less picks the first
     * argument and everything else, equality included, picks the second. Observable, because the
     * two extents can carry different descriptions.
     */
    @Test
    public void equalAreasPickTheSecondArgument() {
        AreaOfUse first = new AreaOfUse(0, 0, 10, 10, "first", false);
        AreaOfUse second = new AreaOfUse(50, 0, 60, 10, "second", false);
        assertEquals(Extents.pseudoArea(first), Extents.pseudoArea(second), 0.0);
        assertSame(second, Extents.smaller(first, second));
        assertSame(first, Extents.smaller(second, first));
    }

    /** A wrapping box must not win "smallest" by measuring its complement. */
    @Test
    public void smallerIsNotFooledByAWrappingBox() {
        AreaOfUse wrappingTwentyWide = box(170, 0, -170, 10);
        AreaOfUse normalTenWide = box(0, 0, 10, 10);
        assertSame(normalTenWide, Extents.smaller(wrappingTwentyWide, normalTenWide));
        assertSame(normalTenWide, Extents.smaller(normalTenWide, wrappingTwentyWide));
    }
}
