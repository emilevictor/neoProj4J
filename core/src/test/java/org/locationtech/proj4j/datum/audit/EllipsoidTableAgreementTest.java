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
 */
package org.locationtech.proj4j.datum.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Ellipsoid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The two ellipsoid tables must keep agreeing, and {@link Registry#ellipsoids} must keep holding
 * references rather than re-declarations.
 *
 * <h2>Why two tables exist at all</h2>
 *
 * <p>{@link Registry#ellipsoids} is what {@code +ellps=} resolves against, by exact name.
 * {@code Ellipsoid.ellipsoids} is what the WKT writer scans, <em>numerically</em>, to pick the
 * {@code +ellps=} code for a spheroid given only its axes. They hold the same 49 ellipsoids in
 * different orders, and neither order can be imposed on the other: the WKT table's order is
 * load-bearing (see {@link #theWriterTableBreaksItsFourTiesByPositionSoItsOrderIsLoadBearing()})
 * while the registry's is not, because an exact-name scan does not care where a name sits.
 *
 * <h2>The two failures this guards</h2>
 *
 * <p><b>Membership drift.</b> Adding a constant to {@code Ellipsoid} does not make it reachable
 * through {@code +ellps=}. That is not hypothetical: {@code clrk80ign}, {@code danish},
 * {@code GSK2011} and {@code PZ90} were added as constants, believed done, and still threw
 * "Unknown ellipsoid" until they were also listed in {@link Registry#ellipsoids}.
 *
 * <p><b>Re-declaration drift.</b> Twenty-seven entries of {@link Registry#ellipsoids} were once
 * spelled out again as {@code new Ellipsoid(...)} literals beside the constants of the same name,
 * and two of them were spelled wrongly: {@code NWL9D} and {@code andrae} were written with the
 * inverse flattening in the {@code poleRadius} slot, which selects a different branch of the
 * constructor and takes 298.25 <em>metres</em> literally as the pole radius. That yields
 * {@code e = 0.999999998906693}, so every transform through {@code +ellps=NWL9D} or
 * {@code +ellps=andrae} was computed on a near-flat disc. Both
 * {@link #everyRegistryEntryIsTheSharedConstantAndNotACopyOfIt()} and
 * {@link #noEllipsoidIsANearFlatDisc()} fail if that shape comes back.
 */
public class EllipsoidTableAgreementTest {

    /** The registry table, reached the way {@code +ellps=} reaches it. */
    private static final Ellipsoid[] BY_NAME = Registry.ellipsoids;

    /** The WKT writer's table, which it scans numerically. */
    private static final Ellipsoid[] BY_NUMBER = Ellipsoid.ellipsoids;

    private static Map<String, Ellipsoid> byShortName(Ellipsoid[] table, String label) {
        Map<String, Ellipsoid> m = new LinkedHashMap<String, Ellipsoid>();
        for (Ellipsoid e : table) {
            assertNotNull(label + " has a null entry", e);
            Ellipsoid clash = m.put(e.shortName, e);
            assertEquals(label + " lists " + e.shortName + " twice; getEllipsoid would return the "
                    + "first and the second would be unreachable", null, clash);
        }
        return m;
    }

    /**
     * Same ellipsoids in both, no duplicates in either. This is the guard on the
     * add-a-constant-and-forget-the-registry bug described in the class Javadoc.
     */
    @Test
    public void bothTablesHoldTheSameEllipsoids() {
        Map<String, Ellipsoid> byName = byShortName(BY_NAME, "Registry.ellipsoids");
        Map<String, Ellipsoid> byNumber = byShortName(BY_NUMBER, "Ellipsoid.ellipsoids");

        Set<String> onlyInRegistry = new TreeSet<String>(byName.keySet());
        onlyInRegistry.removeAll(byNumber.keySet());
        assertEquals("these resolve through +ellps= but the WKT writer cannot name them, so a "
                + "round trip through WKT loses the code: add them to Ellipsoid.ellipsoids",
                new TreeSet<String>(), onlyInRegistry);

        Set<String> onlyInWriter = new TreeSet<String>(byNumber.keySet());
        onlyInWriter.removeAll(byName.keySet());
        assertEquals("these are Ellipsoid constants that +ellps= cannot reach — the exact bug that "
                + "left clrk80ign, danish, GSK2011 and PZ90 throwing \"Unknown ellipsoid\" after "
                + "they were added: add them to Registry.ellipsoids",
                new TreeSet<String>(), onlyInWriter);

        assertEquals("the two tables must stay the same size", byNumber.size(), byName.size());
    }

    /**
     * Every registry entry is the {@code Ellipsoid} constant itself, not a copy carrying the same
     * numbers. Reference identity is the assertion on purpose: comparing fields would pass for a
     * freshly written, correct literal and so would not stop the next one being written wrongly.
     */
    @Test
    public void everyRegistryEntryIsTheSharedConstantAndNotACopyOfIt() {
        Map<String, Ellipsoid> byNumber = byShortName(BY_NUMBER, "Ellipsoid.ellipsoids");
        List<String> copies = new ArrayList<String>();
        for (Ellipsoid e : BY_NAME) {
            if (byNumber.get(e.shortName) != e) {
                copies.add(e.shortName);
            }
        }
        assertEquals("Registry.ellipsoids must reference the Ellipsoid constants, never re-declare "
                + "them. These entries are separate objects, so they are new Ellipsoid(...) "
                + "literals whose numbers are now maintained by hand in two places — which is how "
                + "+ellps=NWL9D came to be computed on a near-flat disc: "
                + copies, 0, copies.size());
    }

    /**
     * {@code getEllipsoid} is the entry point the parser actually calls, so the agreement above is
     * also asserted through it rather than only against the array.
     */
    @Test
    public void everyNameInEitherTableResolvesThroughGetEllipsoid() {
        Registry registry = new Registry();
        Set<String> names = new LinkedHashSet<String>();
        for (Ellipsoid e : BY_NAME) {
            names.add(e.shortName);
        }
        for (Ellipsoid e : BY_NUMBER) {
            names.add(e.shortName);
        }
        for (String name : names) {
            Ellipsoid found = registry.getEllipsoid(name);
            assertNotNull("+ellps=" + name + " must resolve", found);
            assertEquals("getEllipsoid returned the wrong ellipsoid for " + name,
                    name, found.shortName);
        }
        assertEquals("both tables hold the same names, so the union is one table's size",
                BY_NUMBER.length, names.size());
    }

    /**
     * No ellipsoid is a near-flat disc. The bug this pins produced {@code e = 0.999999998906693};
     * the most flattened legitimate entry is {@code mprts} (Maupertius 1738, inverse flattening
     * 191) at {@code e = 0.1022}, so the bound is nowhere near either the real values or a
     * borderline judgement.
     */
    @Test
    public void noEllipsoidIsANearFlatDisc() {
        for (Ellipsoid e : BY_NAME) {
            assertTrue(e.shortName + " has eccentricity " + e.eccentricity + ", which is not an "
                    + "ellipsoid of the Earth. The known cause is an inverse flattening written "
                    + "into the poleRadius argument of the four-argument constructor",
                    e.eccentricity >= 0.0 && e.eccentricity < 0.5);
            assertTrue(e.shortName + " has a pole radius of " + e.poleRadius
                    + ", which is not a length in metres", e.poleRadius > 6.0e6);
        }
    }

    /**
     * The WKT writer's table resolves numeric ties by array position, so its order decides which
     * {@code +ellps=} code gets emitted for four pairs of ellipsoids that are numerically
     * indistinguishable.
     *
     * <p>{@code WktNames.projEllipsoidCode} filters on semi-major axis within 1e-4 m, requires
     * sphere-ness to agree, then keeps the smallest inverse-flattening delta using a
     * <em>strict</em> {@code <}. On an exact tie the strict comparison never displaces the
     * incumbent, so the entry appearing first in {@code Ellipsoid.ellipsoids} wins. All four of
     * these ties are exact — delta 0.0, not merely within the 1e-7 tolerance — which is why
     * position is the whole of the decision.
     *
     * <p>Sorting that array, or appending to it in a way that moves these entries relative to one
     * another, silently changes the code the WKT writer emits. Two of the four would flip if the
     * registry's order were adopted instead ({@code WGS66} to {@code NWL9D} and
     * {@code australian} to {@code aust_SA}), which is why the two tables are kept in their own
     * orders rather than aliased.
     */
    @Test
    public void theWriterTableBreaksItsFourTiesByPositionSoItsOrderIsLoadBearing() {
        assertTieBrokenInFavourOf("clrk80", "NAD27");
        assertTieBrokenInFavourOf("WGS66", "NWL9D");
        assertTieBrokenInFavourOf("GRS80", "NAD83");
        assertTieBrokenInFavourOf("australian", "aust_SA");
    }

    /**
     * Asserts that {@code winner} and {@code loser} really are numerically indistinguishable to
     * the writer, and that {@code winner} sits first. The first half matters: without it this
     * would still pass once the pair stopped being tied, and would then be pinning nothing.
     */
    private static void assertTieBrokenInFavourOf(String winner, String loser) {
        int iWinner = -1;
        int iLoser = -1;
        for (int i = 0; i < BY_NUMBER.length; i++) {
            if (BY_NUMBER[i].shortName.equals(winner)) {
                iWinner = i;
            } else if (BY_NUMBER[i].shortName.equals(loser)) {
                iLoser = i;
            }
        }
        assertTrue(winner + " is missing from Ellipsoid.ellipsoids", iWinner >= 0);
        assertTrue(loser + " is missing from Ellipsoid.ellipsoids", iLoser >= 0);

        Ellipsoid a = BY_NUMBER[iWinner];
        Ellipsoid b = BY_NUMBER[iLoser];
        assertEquals(winner + " and " + loser + " must stay tied on the semi-major axis for this "
                + "to be a tie-break at all", a.equatorRadius, b.equatorRadius, 1e-4);
        assertEquals(winner + " and " + loser + " must stay tied on eccentricity for this to be a "
                + "tie-break at all", a.eccentricity2, b.eccentricity2, 0.0);

        assertTrue("Ellipsoid.ellipsoids must keep " + winner + " ahead of " + loser
                + ": they are numerically identical, the WKT writer breaks the tie by array "
                + "position with a strict <, and so it emits +ellps=" + winner
                + ". Reordering this array changes the emitted code. Found " + winner + " at "
                + iWinner + " and " + loser + " at " + iLoser, iWinner < iLoser);
    }

    /**
     * Sharing the constants is only safe because nothing mutates an {@link Ellipsoid} after
     * construction. {@code setShortName}, {@code setEquatorRadius} and
     * {@code setEccentricitySquared} have no callers anywhere in the build, and the four
     * {@code setName} calls in the readers and writers are on {@code EllipsoidDefinition}, a
     * different class. This test states the invariant those facts add up to, so that a future
     * mutator — which would now corrupt a global constant rather than a private copy — is a
     * failure here rather than a surprise in the field.
     */
    @Test
    public void theSharedConstantsStillHoldTheValuesTheyWereDeclaredWith() {
        assertSame("Registry must hand back the constant itself",
                Ellipsoid.GRS80, new Registry().getEllipsoid("GRS80"));
        assertEquals("GRS80", Ellipsoid.GRS80.shortName);
        assertEquals(6378137.0, Ellipsoid.GRS80.equatorRadius, 0.0);
        assertEquals("WGS84", Ellipsoid.WGS84.shortName);
        assertEquals(6378137.0, Ellipsoid.WGS84.equatorRadius, 0.0);
        // The two that were once wrong, checked against ellps.cpp rather than against themselves.
        assertEquals(6378145.0, Ellipsoid.NWL9D.equatorRadius, 0.0);
        assertEquals(298.25, 1.0 / (1.0 - Math.sqrt(1.0 - Ellipsoid.NWL9D.eccentricity2)), 1e-9);
        assertEquals(6377104.43, Ellipsoid.ANDRAE.equatorRadius, 0.0);
        assertEquals(300.0, 1.0 / (1.0 - Math.sqrt(1.0 - Ellipsoid.ANDRAE.eccentricity2)), 1e-9);
    }
}
