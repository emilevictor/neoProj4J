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
package org.locationtech.proj4j.identity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the {@code equals}/{@code hashCode} contract on {@link Projection}, which is used as a
 * cache key (directly, and transitively through {@code CoordinateReferenceSystem}).
 */
public class ProjectionIdentityTest {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();

    private static Projection proj(String params) {
        return CRS_FACTORY.createFromParameters(null, params).getProjection();
    }

    /**
     * {@code Projection.unit} is null unless {@code +units=} was given or
     * {@code LongLatProjection.initialize()} ran, so {@code unit.equals(p.unit)} threw
     * NullPointerException for every projected CRS defined without {@code +units=}.
     */
    @Test
    public void equalsDoesNotThrowForProjectedCrsWithoutUnits() {
        Projection noUnits = proj("+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs");
        assertNull("fixture must actually leave the unit field unset", unitField(noUnits));

        // Reflexive, and against a separately parsed instance of the same definition.
        assertEquals(noUnits, noUnits);
        Projection sameAgain = proj("+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs");
        assertEquals(noUnits, sameAgain);
        assertEquals(noUnits.hashCode(), sameAgain.hashCode());

        // And against one that does carry +units=, in both directions.
        Projection withMetres =
                proj("+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +units=m +no_defs");
        assertNotNull(unitField(withMetres));
        assertEquals("an absent +units= defaults to metres", noUnits, withMetres);
        assertEquals(withMetres, noUnits);
        assertEquals(noUnits.hashCode(), withMetres.hashCode());

        // A different unit must still be distinguished.
        Projection withFeet =
                proj("+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +units=ft +no_defs");
        assertNotEquals(noUnits, withFeet);
        assertNotEquals(withFeet, noUnits);
    }

    /**
     * A CRS defined without {@code +units=} must survive being used as a hash key, including
     * across a bucket collision. This is the failure mode the NPE actually produced in the wild:
     * a {@code HashMap} keyed on a CRS that blows up on the first collision rather than on insert.
     */
    @Test
    public void projectedCrsWithoutUnitsWorksAsAHashKey() {
        // NOTE: deliberately keyed on Projection, not on CoordinateReferenceSystem. A
        // HashMap<CoordinateReferenceSystem, ?> still misses on lookup, for a separate reason
        // that lives outside this change: CoordinateReferenceSystem.equals compares datums with
        // Datum.isEqual while its hashCode is Objects.hash(datum, proj), and Datum overrides
        // neither equals nor hashCode -- so two separately parsed but equal CRSs hash to two
        // different identity hashes. That is a real equals/hashCode contract violation in
        // CoordinateReferenceSystem.java and is not pinned here.
        Map<Projection, String> map = new HashMap<Projection, String>();

        // Enough distinct definitions that some pair shares a bucket; every put and get walks
        // the bucket chain and calls equals against whatever else landed there.
        for (int zone = 1; zone <= 60; zone++) {
            double lon = (zone - 1) * 6 - 177;
            String noUnits = "+proj=tmerc +lat_0=0 +lon_0=" + lon
                    + " +k=0.9996 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs";
            map.put(proj(noUnits), "zone" + zone);
        }
        assertEquals(60, map.size());

        for (int zone = 1; zone <= 60; zone++) {
            double lon = (zone - 1) * 6 - 177;
            String noUnits = "+proj=tmerc +lat_0=0 +lon_0=" + lon
                    + " +k=0.9996 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs";
            assertEquals("zone" + zone, map.get(proj(noUnits)));
        }

        // Force collisions explicitly by inserting into a set sized to one bucket.
        Set<Projection> set = new HashSet<Projection>(1);
        for (int zone = 1; zone <= 60; zone++) {
            double lon = (zone - 1) * 6 - 177;
            set.add(proj("+proj=tmerc +lat_0=0 +lon_0=" + lon
                    + " +k=0.9996 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs"));
        }
        assertEquals(60, set.size());
    }

    /**
     * {@code setRadius} (i.e. {@code +R=}) mutates the equator radius without touching the
     * ellipsoid, so {@code +R} must be part of equality in its own right.
     */
    @Test
    public void projectionsDifferingOnlyByRadiusAreNotEqual() {
        String base = "+proj=merc +lon_0=0 +x_0=0 +y_0=0 +units=m +no_defs";
        Projection earth = proj(base + " +R=6371000");
        Projection slightlyBigger = proj(base + " +R=6378137");

        assertEquals("+R must actually reach the projection", 6371000.0, earth.getEquatorRadius(), 0.0);
        assertEquals(6378137.0, slightlyBigger.getEquatorRadius(), 0.0);

        assertNotEquals(earth, slightlyBigger);
        assertNotEquals(slightlyBigger, earth);
        assertNotEquals(earth.hashCode(), slightlyBigger.hashCode());

        // ... and two identical +R definitions must still agree.
        Projection earthAgain = proj(base + " +R=6371000");
        assertEquals(earth, earthAgain);
        assertEquals(earth.hashCode(), earthAgain.hashCode());
    }

    /**
     * {@code setRadius} must make a projection unequal to the one it was cloned from — the
     * conclusion this test has always been about, since {@code Projection} is a cache key and a
     * resized projection projects differently.
     *
     * <h2>The premise changed under it, and the premise was never the point</h2>
     *
     * <p>This test's scaffolding used to assert {@code setRadius must not touch the ellipsoid},
     * because at the time {@code setRadius} assigned the semi-major axis <em>alone</em>. That was
     * the {@code +R} defect: {@code e}, {@code es} and {@code ellipsoid} stayed stale, so
     * {@code spherical} stayed {@code false} and the ellipsoidal formula ran on a declared sphere —
     * northing wrong by ~1,495 m at 2 degrees and ~35,000 m at 55 degrees. The scaffolding existed
     * only to establish that {@code a} was then the <em>sole</em> difference, which is what made
     * "{@code equals} saw only the ellipsoid" the interesting failure.
     *
     * <p>{@code setRadius} now declares a genuine sphere ({@code 9.8.1:src/ell_set.cpp:92-100}), so
     * the ellipsoid <em>is</em> replaced. The assertion is inverted rather than deleted: kept as
     * {@code assertEquals} it pins a defect that has been fixed, and deleted it would leave the
     * three assertions below with no stated premise at all. Inverted, it says the true and
     * load-bearing thing — {@code +R} reaches the ellipsoid — which is exactly the property whose
     * absence caused the km-scale error.
     */
    @Test
    public void setRadiusAloneMakesProjectionsUnequal() {
        String def = "+proj=merc +lon_0=0 +ellps=GRS80 +units=m +no_defs";
        Projection untouched = proj(def);
        Projection resized = proj(def);
        resized.setRadius(6371000.0);

        assertNotEquals("setRadius must replace the ellipsoid with a sphere, not just move a",
                untouched.getEllipsoid(), resized.getEllipsoid());
        assertEquals("and that sphere must have zero eccentricity",
                0.0, resized.getEllipsoid().getEccentricitySquared(), 0.0);
        assertNotEquals(untouched.getEquatorRadius(), resized.getEquatorRadius(), 0.0);

        assertNotEquals(untouched, resized);
        assertNotEquals(resized, untouched);
        assertNotEquals(untouched.hashCode(), resized.hashCode());
    }

    /** Alpha and lonc are NaN by default, and must not make a projection unequal to itself. */
    @Test
    public void alphaAndLoncParticipateInEqualityWithoutBreakingTheNaNDefault() {
        Projection a = proj("+proj=omerc +lat_0=4 +lonc=115 +alpha=53 +k=0.99984 "
                + "+x_0=590476.87 +y_0=442857.65 +ellps=GRS80 +units=m +no_defs");
        Projection b = proj("+proj=omerc +lat_0=4 +lonc=115 +alpha=53 +k=0.99984 "
                + "+x_0=590476.87 +y_0=442857.65 +ellps=GRS80 +units=m +no_defs");
        Projection differentAlpha = proj("+proj=omerc +lat_0=4 +lonc=115 +alpha=54 +k=0.99984 "
                + "+x_0=590476.87 +y_0=442857.65 +ellps=GRS80 +units=m +no_defs");
        Projection differentLonc = proj("+proj=omerc +lat_0=4 +lonc=116 +alpha=53 +k=0.99984 "
                + "+x_0=590476.87 +y_0=442857.65 +ellps=GRS80 +units=m +no_defs");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentAlpha);
        assertNotEquals(a, differentLonc);

        // Both alpha and lonc left at their NaN default.
        Projection noAlpha = proj("+proj=merc +lon_0=0 +ellps=GRS80 +units=m +no_defs");
        Projection noAlphaAgain = proj("+proj=merc +lon_0=0 +ellps=GRS80 +units=m +no_defs");
        assertTrue("fixture must leave alpha at NaN", Double.isNaN(noAlpha.getAlpha()));
        assertTrue("fixture must leave lonc at NaN", Double.isNaN(noAlpha.getLonC()));
        assertEquals(noAlpha, noAlphaAgain);
        assertEquals(noAlpha.hashCode(), noAlphaAgain.hashCode());
    }

    /**
     * equals and hashCode must stay consistent across a spread of real definitions: any two that
     * compare equal must hash the same. Notably, {@code equals} compares ellipsoids with
     * {@code Ellipsoid.isEqual} (radius and eccentricity only, ignoring the name), so hashing the
     * {@code Ellipsoid} object -- whose own hashCode includes its name -- was a live violation.
     */
    @Test
    public void equalsAndHashCodeAreConsistent() {
        String[] definitions = {
                "+proj=merc +lon_0=0 +ellps=GRS80 +units=m +no_defs",
                "+proj=merc +lon_0=0 +ellps=WGS84 +units=m +no_defs",
                "+proj=merc +lon_0=0 +a=6378137 +rf=298.257222101 +units=m +no_defs",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +no_defs",
                "+proj=tmerc +lat_0=0 +lon_0=9 +k=1 +x_0=500000 +y_0=0 +ellps=GRS80 +units=m +no_defs",
                "+proj=cass +lat_0=2.1216797444 +lon_0=103.4279362361 +x_0=-14810.562 +y_0=8758.32 "
                        + "+ellps=GRS80 +units=m +no_defs",
                "+proj=cass +lat_0=2.1216797444 +lon_0=103.4279362361 +x_0=-14810.562 +y_0=8758.32 "
                        + "+ellps=GRS80 +no_defs",
                "+proj=lcc +lat_1=45 +lat_2=50 +lat_0=47 +lon_0=10 +ellps=GRS80 +units=m +no_defs",
                "+proj=lcc +lat_1=45 +lat_2=50 +lat_0=47 +lon_0=11 +ellps=GRS80 +units=m +no_defs",
                "+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs",
                "+proj=utm +zone=33 +south +datum=WGS84 +units=m +no_defs",
                "+proj=stere +lat_0=90 +lat_ts=70 +lon_0=0 +ellps=WGS84 +units=m +no_defs",
                "+proj=longlat +datum=WGS84 +no_defs",
                "+proj=merc +lon_0=0 +R=6371000 +units=m +no_defs",
                "+proj=merc +lon_0=0 +R=6378137 +units=m +no_defs",
        };

        Projection[] ps = new Projection[definitions.length];
        for (int i = 0; i < definitions.length; i++) {
            ps[i] = proj(definitions[i]);
        }

        for (int i = 0; i < ps.length; i++) {
            // Reflexive, and stable across two parses of the same string.
            Projection reparsed = proj(definitions[i]);
            assertEquals(definitions[i], ps[i], reparsed);
            assertEquals(definitions[i], ps[i].hashCode(), reparsed.hashCode());

            for (int j = 0; j < ps.length; j++) {
                boolean eq = ps[i].equals(ps[j]);
                assertEquals("equals must be symmetric: [" + definitions[i] + "] vs ["
                        + definitions[j] + "]", eq, ps[j].equals(ps[i]));
                if (eq) {
                    assertEquals("equal projections must share a hashCode: [" + definitions[i]
                            + "] vs [" + definitions[j] + "]", ps[i].hashCode(), ps[j].hashCode());
                }
            }
        }
    }

    /**
     * Whether {@code +lat_ts} was given is part of equality, deliberately, and this is a visible
     * change to the {@code equals}/{@code hashCode} contract.
     *
     * <p>The reason is that presence decides what {@code MercatorProjection.initialize()}
     * computes: {@code +lat_ts=0 +k=0.997} ends on a scale factor of 1 and {@code +k=0.997} alone
     * on 0.997. Those two already differ in {@code scaleFactor}, so they were already unequal —
     * what the flag adds is the pair below, {@code +lat_ts=0} against no {@code +lat_ts} with no
     * {@code +k} at all. Those two project identically today, and they now compare unequal. That
     * costs a cache miss and never a wrong answer, which is the direction to err in for a value
     * used as a cache key: a coarser equals would hand back a transform built for the other
     * definition.
     */
    @Test
    public void whetherLatTsWasGivenIsPartOfEquality() {
        // The pair that behaves differently, and that differs in scaleFactor as well.
        Projection latTsZero = proj("+proj=merc +lat_ts=0 +k=0.997 +ellps=GRS80 +units=m +no_defs");
        Projection kOnly = proj("+proj=merc +k=0.997 +ellps=GRS80 +units=m +no_defs");
        assertEquals("fixture must reset the scale factor", 1.0, latTsZero.getScaleFactor(), 0.0);
        assertEquals("fixture must keep +k", 0.997, kOnly.getScaleFactor(), 0.0);
        assertNotEquals(latTsZero, kOnly);
        assertNotEquals(kOnly, latTsZero);

        // The pair that behaves the same and is now distinguished anyway.
        Projection plainWithLatTs = proj("+proj=merc +lat_ts=0 +ellps=GRS80 +units=m +no_defs");
        Projection plain = proj("+proj=merc +ellps=GRS80 +units=m +no_defs");
        assertEquals("both fixtures must end on a scale factor of 1",
                plain.getScaleFactor(), plainWithLatTs.getScaleFactor(), 0.0);
        assertNotEquals(plainWithLatTs, plain);
        assertNotEquals(plain, plainWithLatTs);

        // Still reflexive and still stable across two parses of the same string, both ways.
        assertEquals(plainWithLatTs,
                proj("+proj=merc +lat_ts=0 +ellps=GRS80 +units=m +no_defs"));
        assertEquals(plainWithLatTs.hashCode(),
                proj("+proj=merc +lat_ts=0 +ellps=GRS80 +units=m +no_defs").hashCode());
        assertEquals(plain, proj("+proj=merc +ellps=GRS80 +units=m +no_defs"));
        assertEquals(plain.hashCode(), proj("+proj=merc +ellps=GRS80 +units=m +no_defs").hashCode());

        // A projection that seeds trueScaleLatitude itself rather than from a parameter must not
        // be caught by the flag: stere's constructor assigns 90 degrees directly.
        Projection stereNoLatTs = proj("+proj=stere +lat_0=90 +lon_0=0 +ellps=WGS84 +units=m +no_defs");
        assertEquals(stereNoLatTs, proj("+proj=stere +lat_0=90 +lon_0=0 +ellps=WGS84 +units=m +no_defs"));
        assertNotEquals("an explicit +lat_ts is still a different stere", stereNoLatTs,
                proj("+proj=stere +lat_0=90 +lat_ts=70 +lon_0=0 +ellps=WGS84 +units=m +no_defs"));
    }

    /**
     * The same reasoning as {@link #whetherLatTsWasGivenIsPartOfEquality()}, for {@code +lat_2} and
     * {@code +lat_0} on {@code lcc}.
     *
     * <p>{@code lcc.cpp:88-95} tests whether the tokens were supplied, so
     * {@code +lat_1=45 +lat_2=0} and {@code +lat_1=45} are different projections: PROJ 9.8.1 at
     * 10E 40N on GRS80 answers {@code 825297.566331256530 4211552.547939138487} for the first and
     * {@code 854925.007478637854 -503282.608577708714} for the second. Those two already differ in
     * every derived field, so they were already unequal; what the flags add is that the difference
     * is now visible <em>before</em> {@code initialize()} runs, and that it survives the case where
     * the derived fields agree.
     *
     * <p>{@code Projection} is used as part of a transform-cache key, so the flags belong in
     * {@code equals} and {@code hashCode}: a coarser {@code equals} would hand back a transform
     * built for the other definition.
     */
    @Test
    public void whetherLatTwoAndLatZeroWereGivenIsPartOfEquality() {
        Projection latTwoZero = proj("+proj=lcc +lat_1=45 +lat_2=0 +ellps=GRS80 +units=m +no_defs");
        Projection noLatTwo = proj("+proj=lcc +lat_1=45 +ellps=GRS80 +units=m +no_defs");
        assertNotEquals(latTwoZero, noLatTwo);
        assertNotEquals(noLatTwo, latTwoZero);

        // The lat_0 half on its own: these two are the same cone with the same origin, reached one
        // way by naming lat_2 and the other by naming lat_0=0, and they compare unequal because the
        // flags differ even though every derived field agrees.
        Projection latTwoGiven = proj("+proj=lcc +lat_1=45 +lat_2=45 +ellps=GRS80 +units=m +no_defs");
        Projection latZeroGiven = proj("+proj=lcc +lat_1=45 +lat_0=0 +ellps=GRS80 +units=m +no_defs");
        assertEquals("fixtures must agree on the latitude of origin",
                latTwoGiven.getProjectionLatitude(), latZeroGiven.getProjectionLatitude(), 0.0);
        assertEquals("and on the second parallel",
                latTwoGiven.getProjectionLatitude2(), latZeroGiven.getProjectionLatitude2(), 0.0);
        assertNotEquals(latTwoGiven, latZeroGiven);
        assertNotEquals(latZeroGiven, latTwoGiven);

        // Still reflexive and still stable across two parses of the same string.
        for (String d : new String[] {
                "+proj=lcc +lat_1=45 +lat_2=0 +ellps=GRS80 +units=m +no_defs",
                "+proj=lcc +lat_1=45 +ellps=GRS80 +units=m +no_defs",
                "+proj=lcc +lat_1=45 +lat_0=0 +ellps=GRS80 +units=m +no_defs"}) {
            assertEquals(d, proj(d), proj(d));
            assertEquals(d, proj(d).hashCode(), proj(d).hashCode());
        }

        // A projection that seeds projectionLatitude itself rather than from a parameter must not be
        // caught by the flag: tmerc's initialize path writes the field directly.
        Projection tmerc = proj("+proj=tmerc +lon_0=9 +ellps=GRS80 +units=m +no_defs");
        assertEquals(tmerc, proj("+proj=tmerc +lon_0=9 +ellps=GRS80 +units=m +no_defs"));
        assertNotEquals("an explicit +lat_0 is still a different tmerc", tmerc,
                proj("+proj=tmerc +lat_0=0 +lon_0=9 +ellps=GRS80 +units=m +no_defs"));
    }

    /** Reads the private {@code unit} field, to prove a fixture really leaves it null. */
    private static Object unitField(Projection p) {
        try {
            java.lang.reflect.Field f = Projection.class.getDeclaredField("unit");
            f.setAccessible(true);
            return f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Projection.unit is expected to exist", e);
        }
    }
}
