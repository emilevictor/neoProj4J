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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.spi.DbGridAlternative;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;

/**
 * {@link CrsOperationCandidate#compareTo} on its own, against PROJ 9.8.1's ranked answer.
 *
 * <h2>Why the comparator is tested apart from the selector</h2>
 *
 * <p>{@link OperationSelectionTest} exercises the whole path and therefore measures the comparator
 * only through whatever the selector then does with the list. The ordering is a rule in its own right
 * &mdash; it is a port of PROJ's {@code SortFunction::compare}, criterion by criterion &mdash; and the
 * only way to show it is faithful is to feed it the operations PROJ was fed and compare the two
 * orders position by position.
 *
 * <h2>Every figure here came out of PROJ 9.8.1, and here is how to re-read it</h2>
 *
 * <pre>
 * projinfo -s EPSG:4277 -t EPSG:4326 --summary --spatial-test intersects
 *
 * sqlite3 &lt;share/proj&gt;/proj.db \
 *   "select h.code, h.accuracy, e.west_lon, e.south_lat, e.east_lon, e.north_lat
 *      from helmert_transformation h
 *      join usage u on u.object_code = h.code and u.object_table_name = 'helmert_transformation'
 *      join extent e on e.code = u.extent_code
 *     where h.source_crs_code = '4277' and h.target_crs_code = '4326' and h.deprecated = 0;"
 * </pre>
 *
 * <p>The bounding boxes below are that query's output, unrounded.
 */
public class CandidateRankingTest {

    // The seven EPSG operations projinfo lists for OSGB36 -> WGS 84, with the accuracy and the
    // bounding box the authority publishes for each. EPSG:5339 is omitted because it is hidden by a
    // supersession, and ESRI:108089 / ESRI:108336 because projinfo drops them on authority
    // preference -- a gap in the generated index that is Stream D step 4's, not the comparator's.
    private static final CrsOperationCandidate OSGB_7710 =
            candidate("7710", "OSGB36 to WGS 84 (9)", 1.0, -9.01, 49.75, 2.01, 61.01);
    private static final CrsOperationCandidate OSGB_1314 =
            candidate("1314", "OSGB36 to WGS 84 (6)", 2.0, -8.82, 49.79, 1.92, 60.94);
    private static final CrsOperationCandidate OSGB_1195 =
            candidate("1195", "OSGB36 to WGS 84 (1)", 21.0, -8.82, 49.79, 1.92, 60.94);
    private static final CrsOperationCandidate OSGB_1196 =
            candidate("1196", "OSGB36 to WGS 84 (2)", 10.0, -6.5, 49.81, 1.84, 55.85);
    private static final CrsOperationCandidate OSGB_1197 =
            candidate("1197", "OSGB36 to WGS 84 (3)", 21.0, -6.5, 49.81, 1.84, 55.85);
    private static final CrsOperationCandidate OSGB_1198 =
            candidate("1198", "OSGB36 to WGS 84 (4)", 18.0, -8.74, 54.57, -0.65, 60.9);
    private static final CrsOperationCandidate OSGB_1199 =
            candidate("1199", "OSGB36 to WGS 84 (5)", 35.0, -5.34, 51.28, -2.65, 53.48);
    private static final CrsOperationCandidate OSGB_5622 =
            candidate("5622", "OSGB36 to WGS 84 (8)", 3.0, -2.2, 50.53, -1.68, 50.8);

    /**
     * The whole point of the 2.2.0 port, stated as one assertion: given the operations PROJ 9.8.1 is
     * given, this comparator produces PROJ 9.8.1's order, at every one of the eight positions.
     *
     * <p>Before the port it agreed at three of eight. The three it got right were the two most
     * accurate operations and one coincidence; everything below that was in a different order,
     * because ranking on accuracy first puts {@code EPSG:5622} third &mdash; 3 m, and valid over
     * 0.52&deg; by 0.27&deg; of Dorset coastline. PROJ puts it last of the eight, which is the
     * correct answer to "what should I be offered for Great Britain".
     */
    @Test
    public void theOsgb36OrderIsProjinfosOrderAtEveryPosition() {
        List<CrsOperationCandidate> shuffled = new ArrayList<CrsOperationCandidate>(Arrays.asList(
                OSGB_1199, OSGB_5622, OSGB_1195, OSGB_7710, OSGB_1197, OSGB_1314, OSGB_1198,
                OSGB_1196));
        Collections.sort(shuffled);

        assertEquals("projinfo -s EPSG:4277 -t EPSG:4326 --spatial-test intersects, in its order",
                Arrays.asList("EPSG:7710", "EPSG:1314", "EPSG:1195", "EPSG:1196", "EPSG:1197",
                        "EPSG:1198", "EPSG:1199", "EPSG:5622"),
                codes(shuffled));
    }

    /**
     * The single most consequential line of the port, isolated. {@code EPSG:1195} is 21 m over all of
     * Great Britain; {@code EPSG:5622} is 3 m over one bay. PROJ offers the 21 m one first, and so
     * does this, because an accuracy figure says nothing about a place the operation does not cover.
     */
    @Test
    public void aWideInaccurateOperationOutranksANarrowPreciseOne() {
        assertTrue("EPSG:1195 (21 m, all of GB) must rank above EPSG:5622 (3 m, one bay)",
                OSGB_1195.compareTo(OSGB_5622) < 0);
    }

    /**
     * The trap the port exists to avoid: PROJ has <em>two</em> accuracy criteria and area sits between
     * them. Whether an accuracy is known is asked <b>above</b> area; how good it is, <b>below</b>.
     * Reasoning about "does area beat accuracy" gets one of these two wrong whichever way you answer.
     */
    @Test
    public void areaStraddlesTheTwoAccuracyCriteriaExactlyAsInProj() {
        CrsOperationCandidate wideUnknown =
                candidate("9001", "wide, accuracy unknown", Double.NaN, -20.0, 20.0, 20.0, 60.0);
        CrsOperationCandidate narrowKnown =
                candidate("9002", "narrow, 35 m", 35.0, -1.0, 40.0, 1.0, 42.0);

        assertTrue("a published accuracy outranks a larger area: this is PROJ's criterion 7, above"
                        + " the area block",
                narrowKnown.compareTo(wideUnknown) < 0);
        assertTrue("a larger area outranks a better accuracy: this is PROJ's criterion 9, above"
                        + " criterion 10",
                OSGB_1195.compareTo(OSGB_5622) < 0);
    }

    /**
     * PROJ measures {@code (east - west) * (sin(north) - sin(south))}, not {@code lonSpan * latSpan}.
     * A flat rectangle would call these two extents identical; they are not, and a comparator that
     * thought they were would rank Norway above Kenya for no reason.
     */
    @Test
    public void areaIsWeightedByLatitudeTheWayProjWeightsIt() {
        CrsOperationCandidate equatorial =
                candidate("9101", "ten by ten at the equator", 1.0, 0.0, 0.0, 10.0, 10.0);
        CrsOperationCandidate arctic =
                candidate("9102", "ten by ten at 70 north", 1.0, 0.0, 70.0, 10.0, 80.0);

        assertTrue("the same ten degrees square covers less ground at 70 N and must rank below",
                equatorial.compareTo(arctic) < 0);
    }

    /**
     * The asymmetry in PROJ's area block, which is easy to lose when the branches are "simplified".
     * A zero area never beats a positive one, but two zero areas are a <em>tie</em> and not a verdict,
     * so the criteria below get to decide. An operation with no bounding box has zero area, never the
     * whole world &mdash; 18 extents in the shipped database publish none, and reading those as
     * global would make them win every comparison they took part in.
     */
    @Test
    public void anAbsentBoundingBoxIsZeroAreaAndNotTheWholeWorld() {
        CrsOperationCandidate noBox = candidate("9201", "no extent published", 0.5, null);
        CrsOperationCandidate otherNoBox = candidate("9202", "also no extent published", 35.0, null);

        assertTrue("a real extent outranks an absent one", OSGB_1199.compareTo(noBox) < 0);
        assertTrue("and the absent one does not win by being read as global",
                noBox.compareTo(OSGB_1199) > 0);
        assertTrue("two absent extents tie on area, so accuracy decides below it",
                noBox.compareTo(otherNoBox) < 0);
    }

    /**
     * PROJ's criterion 17, the one hardcoded name preference upstream carries. The reference order
     * would give the same answer for the only two rows that exist, but it would be giving it by
     * accident: {@code EPSG:1764}'s own remarks record that OGP prefers the IGN Paris value, which is
     * an authority preference and not a tiebreak.
     */
    @Test
    public void theNtfParisPreferenceIsAppliedRatherThanArrivedAtByAccident() {
        // Codes reversed against the names, so the reference tiebreak would give the wrong answer if
        // the NTF rule were not there to fire first.
        CrsOperationCandidate one = candidate("1999", "NTF (Paris) to NTF (1)", 0.0, 0.0, 41.0, 9.0,
                52.0);
        CrsOperationCandidate two = candidate("1001", "NTF (Paris) to NTF (2)", 0.0, 0.0, 41.0, 9.0,
                52.0);

        assertTrue("(1) is preferred over (2) whatever the codes say", one.compareTo(two) < 0);
        assertTrue("and the rule is antisymmetric", two.compareTo(one) > 0);
    }

    /**
     * PROJ's criterion 16. Kept even though upstream's own comment on it ends in a question mark,
     * because dropping it would reorder pairs upstream does not leave to the tiebreak.
     */
    @Test
    public void theShorterNameWinsBeforeTheReferenceDoes() {
        CrsOperationCandidate longName =
                candidate("1000", "a considerably longer operation name", 1.0, 0.0, 0.0, 1.0, 1.0);
        CrsOperationCandidate shortName =
                candidate("9999", "short name", 1.0, 0.0, 0.0, 1.0, 1.0);

        assertTrue("the shorter name outranks the lower code", shortName.compareTo(longName) < 0);
    }

    /**
     * The documented divergence from PROJ, asserted so it cannot be lost by accident. Upstream ends
     * on {@code return a_name > b_name}, deliberately inverted so that {@code "(4)"} precedes
     * {@code "(3)"}. Two operations can share a name, so that is not a total order, and determinism
     * is a gate in this repository rather than a preference. This ends on the authority reference,
     * which cannot tie.
     */
    @Test
    public void theFinalTiebreakIsTheReferenceAndNotProjsNameComparison() {
        CrsOperationCandidate three =
                candidate("3000", "Amersfoort to WGS 84 (3)", 1.0, 3.0, 50.0, 7.0, 54.0);
        CrsOperationCandidate four =
                candidate("4000", "Amersfoort to WGS 84 (4)", 1.0, 3.0, 50.0, 7.0, 54.0);

        assertTrue("PROJ would put (4) first; this puts the lower code first, on purpose",
                three.compareTo(four) < 0);
    }

    /**
     * PROJ criterion 6, both halves. A grid the database can name a public source for outranks one
     * it knows nothing about &mdash; and <b>a second slot the first slot's file already carries is
     * not a second thing to know about</b>, so it must not cost the operation this tier.
     *
     * <p>The last leg is the one that makes the middle leg mean something. {@code EPSG:1241} declares
     * {@code conus.las} and {@code conus.los}; upstream's {@code gridsNeeded()} collapses the pair to
     * one file before {@code gridsKnown_} is computed, and only 1 of the 85 distinct
     * {@code grid2_name}s in the index has a {@code grid_alternatives} row of its own. Weighing the
     * carried slot separately demoted every NADCON operation below far smaller single-grid ones.
     * Weighing it as always-fine would be the opposite error, which is what {@code 8013} rules out.
     */
    @Test
    public void aCarriedGridSlotDoesNotCostTheTierButAnIndependentUnknownOneDoes() {
        AreaOfUse area = new AreaOfUse(-20.0, 30.0, -10.0, 40.0, "grid tier extent", true);
        GridInfo firstOfPair = knownGrid("pair.las", 1);

        CrsOperationCandidate known = withGrids("8010", "one slot, known", 3.0, area,
                Arrays.asList(knownGrid("one_known.gsb", 1)));
        CrsOperationCandidate unknown = withGrids("8011", "one slot, unknown", 3.0, area,
                Arrays.asList(unknownGrid("one_unknown.gsb", 1)));
        CrsOperationCandidate carried = withGrids("8012", "two slots, second carried", 3.0, area,
                Arrays.asList(firstOfPair, GridInfo.sharedWithEarlierSlot(firstOfPair, "pair.los", 2,
                        null, "EPSG:8012 grid2_name", "one file carries both shifts")));
        CrsOperationCandidate splitUnknown = withGrids("8013", "two slots, second unknown", 3.0,
                area, Arrays.asList(knownGrid("split_a.gsb", 1), unknownGrid("split_b.gsb", 2)));

        assertTrue("a named source outranks nothing at all", known.compareTo(unknown) < 0);
        assertTrue("the carried slot must not demote its operation", carried.compareTo(unknown) < 0);
        assertTrue("a genuinely separate unknown second slot still demotes",
                known.compareTo(splitUnknown) < 0);
        assertTrue("and it demotes the two-slot shape too, so the skip is about being carried and "
                + "not about having two slots", carried.compareTo(splitUnknown) < 0);
    }

    /**
     * The proof obligation that comes with owning a {@link Comparable}: a comparator that is not a
     * strict weak ordering can make {@link Collections#sort} produce a different answer for a
     * different input order, or throw, and determinism is a gate here.
     *
     * <p>Checked exhaustively over a population chosen to make every criterion fire &mdash; equal
     * areas, equal accuracies, absent accuracies, absent extents, equal name lengths, the NTF pair,
     * both directions of the same operation, and all four grid shapes. Antisymmetry over all ordered
     * pairs, transitivity over all ordered triples, and no two distinct candidates comparing equal,
     * which is what the reference tiebreak exists to guarantee.
     *
     * <p>The grid shapes were added in 2.2.0 and they closed a real hole: every candidate here used
     * to carry an empty grid list, so PROJ criterion 6 &mdash; {@code gridsKnown} &mdash; had no pair
     * that reached it, and this proof said nothing about the branch that ranks {@code EPSG:1241}.
     */
    @Test
    public void theComparatorIsAStrictWeakOrdering() {
        List<CrsOperationCandidate> population = population();

        for (int i = 0; i < population.size(); i++) {
            CrsOperationCandidate a = population.get(i);
            assertEquals(a + " must be equal to itself", 0, a.compareTo(a));
            for (int j = 0; j < population.size(); j++) {
                CrsOperationCandidate b = population.get(j);
                int ab = signum(a.compareTo(b));
                int ba = signum(b.compareTo(a));
                assertEquals("antisymmetry: " + a + " vs " + b, -ab, ba);
                if (i != j) {
                    assertTrue("no two distinct candidates may tie, or the sort stops being"
                            + " reproducible: " + a + " vs " + b, ab != 0);
                }
                for (int k = 0; k < population.size(); k++) {
                    CrsOperationCandidate c = population.get(k);
                    if (ab < 0 && signum(b.compareTo(c)) < 0) {
                        assertTrue("transitivity: " + a + " < " + b + " < " + c,
                                a.compareTo(c) < 0);
                    }
                }
            }
        }
    }

    /**
     * The consequence of the ordering being total: the sorted result cannot depend on the order the
     * database returned rows in. Asserted over the same population, sorted from its own order and
     * from its reverse.
     */
    @Test
    public void theSortedOrderDoesNotDependOnTheInputOrder() {
        List<CrsOperationCandidate> forward = new ArrayList<CrsOperationCandidate>(population());
        List<CrsOperationCandidate> backward = new ArrayList<CrsOperationCandidate>(population());
        Collections.reverse(backward);
        Collections.sort(forward);
        Collections.sort(backward);

        assertEquals(codes(forward), codes(backward));
    }

    /** Chosen so that every criterion in the chain has at least one pair that reaches it. */
    private static List<CrsOperationCandidate> population() {
        List<CrsOperationCandidate> all = new ArrayList<CrsOperationCandidate>(Arrays.asList(
                OSGB_7710, OSGB_1314, OSGB_1195, OSGB_1196, OSGB_1197, OSGB_1198, OSGB_1199,
                OSGB_5622));
        // Same area and same accuracy as EPSG:1195, so the chain runs past both.
        all.add(candidate("8001", "OSGB36 to WGS 84 (X)", 21.0, -8.82, 49.79, 1.92, 60.94));
        // No accuracy at all, with and without an extent.
        all.add(candidate("8002", "accuracy unknown, with extent", Double.NaN, -8.82, 49.79, 1.92,
                60.94));
        all.add(candidate("8003", "accuracy unknown, no extent", Double.NaN, null));
        all.add(candidate("8004", "known accuracy, no extent", 4.0, null));
        // Identical name length and identical area, so only the reference can separate them.
        all.add(candidate("8005", "twin operation a", 7.0, 0.0, 0.0, 1.0, 1.0));
        all.add(candidate("8006", "twin operation b", 7.0, 0.0, 0.0, 1.0, 1.0));
        // The NTF pair, whose names are the same length.
        all.add(candidate("8007", "NTF (Paris) to NTF (1)", 0.0, 0.0, 41.0, 9.0, 52.0));
        all.add(candidate("8008", "NTF (Paris) to NTF (2)", 0.0, 0.0, 41.0, 9.0, 52.0));
        // An antimeridian-crossing extent, which is where a naive east minus west goes negative.
        all.add(candidate("8009", "crosses the antimeridian", 5.0, 170.0, -20.0, -170.0, 20.0));
        // Grids, which nothing above this line carries, so PROJ criterion 6 -- gridsKnown -- had no
        // pair reaching it at all until 2.2.0. All four shapes, sharing one area and one accuracy so
        // that the chain is forced down to the grid criterion rather than settling earlier.
        AreaOfUse gridArea = new AreaOfUse(-20.0, 30.0, -10.0, 40.0, "grid tier extent", true);
        all.add(withGrids("8010", "one slot, the database names a source", 3.0, gridArea,
                Arrays.asList(knownGrid("one_known.gsb", 1))));
        all.add(withGrids("8011", "one slot, nothing known about it", 3.0, gridArea,
                Arrays.asList(unknownGrid("one_unknown.gsb", 1))));
        // The shape gridsKnown() has to skip: slot 2 has no grid_alternatives row of its own, and it
        // does not need one, because slot 1's file carries both shifts.
        GridInfo firstOfPair = knownGrid("pair.las", 1);
        all.add(withGrids("8012", "two slots, the second carried by the first", 3.0, gridArea,
                Arrays.asList(firstOfPair, GridInfo.sharedWithEarlierSlot(firstOfPair, "pair.los", 2,
                        null, "EPSG:8012 grid2_name", "one file carries both shifts"))));
        // And the same two-slot shape where the second is genuinely a separate unknown file, so the
        // skip cannot be "always true in disguise".
        all.add(withGrids("8013", "two slots, the second independently unknown", 3.0, gridArea,
                Arrays.asList(knownGrid("split_a.gsb", 1), unknownGrid("split_b.gsb", 2))));
        // A candidate in each rejection tier, so the usability tier is exercised too.
        Rejections rejections = new Rejections();
        all.addAll(rejections.oneOfEach());
        // The same operation both ways round, which only the direction tiebreak can separate.
        all.add(inverted(OSGB_7710));
        return all;
    }

    /**
     * A grid slot the database can name a public source for: unavailable on disk, but carrying a
     * {@code grid_alternatives} row, which is what {@code gridsKnown()} asks about.
     */
    private static GridInfo knownGrid(String name, int slot) {
        return GridInfo.forDbGrid(name, slot,
                new DbGridAlternative(name, "modern_" + name + ".tif", null, "GTiff", "hgridshift",
                        false, "https://cdn.proj.org/modern_" + name + ".tif", Boolean.TRUE,
                        Boolean.TRUE, null),
                "test grid" + slot + "_name");
    }

    /** A grid slot with no {@code grid_alternatives} row at all, so nothing accounts for it. */
    private static GridInfo unknownGrid(String name, int slot) {
        return GridInfo.forDbGrid(name, slot, null, "test grid" + slot + "_name");
    }

    private static CrsOperationCandidate withGrids(String code, String name, double accuracy,
                                                   AreaOfUse area, List<GridInfo> grids) {
        return new CrsOperationCandidate(operation(code, name), false, false,
                new Accuracy(accuracy, "EPSG:" + code), grids, area,
                CrsOperationCandidate.Rejection.NONE, null, null, 0);
    }

    private static int signum(int value) {
        return value < 0 ? -1 : (value > 0 ? 1 : 0);
    }

    private static List<String> codes(List<CrsOperationCandidate> candidates) {
        List<String> out = new ArrayList<String>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            out.add(candidates.get(i).authorityCode()
                    + (candidates.get(i).isInverted() ? " inverted" : ""));
        }
        return out;
    }

    /** One candidate per rejection category, all otherwise identical, to exercise the top tier. */
    private static final class Rejections {
        List<CrsOperationCandidate> oneOfEach() {
            List<CrsOperationCandidate> out = new ArrayList<CrsOperationCandidate>();
            CrsOperationCandidate.Rejection[] values = CrsOperationCandidate.Rejection.values();
            for (int i = 0; i < values.length; i++) {
                out.add(new CrsOperationCandidate(
                        operation("70" + i, "rejected as " + values[i]),
                        false, false, new Accuracy(9.0, "test"),
                        Collections.<GridInfo>emptyList(),
                        new AreaOfUse(-8.82, 49.79, 1.92, 60.94, "Great Britain", true),
                        values[i], values[i] == CrsOperationCandidate.Rejection.NONE ? null
                                : "because the test says so",
                        null, 0));
            }
            return out;
        }
    }

    private static CrsOperationCandidate inverted(CrsOperationCandidate forward) {
        return new CrsOperationCandidate(forward.operation(), true, false,
                forward.accuracy().orElse(null), Collections.<GridInfo>emptyList(),
                forward.areaOfUse().orElse(null), forward.rejection(), null, null, 0);
    }

    private static CrsOperationCandidate candidate(String code, String name, double accuracy,
                                                   double west, double south, double east,
                                                   double north) {
        return candidate(code, name, accuracy,
                new AreaOfUse(west, south, east, north, name + " extent", true));
    }

    private static CrsOperationCandidate candidate(String code, String name, double accuracy,
                                                   AreaOfUse area) {
        return new CrsOperationCandidate(operation(code, name), false, false,
                Double.isNaN(accuracy) ? null : new Accuracy(accuracy, "EPSG:" + code),
                Collections.<GridInfo>emptyList(), area, CrsOperationCandidate.Rejection.NONE, null,
                null, 0);
    }

    private static DbOperation operation(String code, String name) {
        return new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", code, name, "EPSG",
                "9606", "Position Vector transformation (geog2D domain)",
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4277"),
                new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG", "4326"),
                Double.NaN, null, null, null, null, null, false);
    }
}
