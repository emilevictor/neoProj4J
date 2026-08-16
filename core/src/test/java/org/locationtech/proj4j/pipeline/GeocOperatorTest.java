/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=geoc} against {@code 9.8.1:src/conversions/geoc.cpp}.
 *
 * <p>Every expected figure below is either a corpus row from
 * {@code more_builtins.gie:486-498} — which is vendored from the 9.8.1 tree and is
 * therefore the oracle, not a restatement of this code — or was measured against the
 * installed 9.8.1 binaries with {@code cct -d 15}, and is quoted in the test that uses
 * it.
 *
 * <p>The interesting content is not {@code atan(one_es * tan(phi))}. It is the two
 * escapes that return the input untouched, because each of them is a place where a
 * plausible implementation silently gives a different answer from PROJ: at the poles
 * ({@code geoc.cpp:54-56}) and on a sphere ({@code P->es == 0}, same line).
 */
public class GeocOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static double rad(final double deg) {
        return deg * PipelineUnits.DEG_TO_RAD;
    }

    private static double deg(final double radians) {
        return radians / PipelineUnits.DEG_TO_RAD;
    }

    /** ~1e-13 degrees is about 11 nanometres, i.e. the double round-trip and no more. */
    private static final double DEG_EPS = 1e-12;

    /**
     * The headline corpus row, {@code more_builtins.gie:486-489}: on GRS80 a geographic
     * latitude of 55 is a geocentric latitude of 54.818973308324573. Confirmed
     * independently with {@code echo "12 55 0 0" | cct -d 15 +proj=geoc +ellps=GRS80},
     * which prints {@code 54.818973308324580} — the two agree to the digits {@code cct}
     * chooses to show.
     */
    @Test
    public void theGeographicToGeocentricLatitudeOfTheCorpusRow() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        double[] out = p.forward(new double[] {rad(12), rad(55), 0, 0});
        assertEquals("longitude is untouched", 12.0, deg(out[0]), DEG_EPS);
        assertEquals(54.818973308324573, deg(out[1]), DEG_EPS);
        assertEquals("height is untouched", 0.0, out[2], 0.0);
    }

    /** And back again, which is what {@code roundtrip 1000} on that row asserts. */
    @Test
    public void theInverseIsTheGeocentricToGeographicDirection() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        double[] back = p.inverse(new double[] {rad(12), rad(54.818973308324573), 0, 0});
        assertEquals(55.0, deg(back[1]), DEG_EPS);
    }

    /** A thousand there-and-back cycles must not drift, per {@code roundtrip 1000}. */
    @Test
    public void aThousandRoundTripsDoNotDrift() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        double[] c = {rad(12), rad(55), 0, 0};
        for (int i = 0; i < 1000; i++) {
            c = p.inverse(p.forward(c));
        }
        assertEquals(12.0, deg(c[0]), DEG_EPS);
        assertEquals(55.0, deg(c[1]), 1e-9);
    }

    /**
     * A bare {@code +proj=geoc} is GRS80, not WGS84.
     * {@code append_default_ellipsoid_to_paralist} ({@code init.cpp:319-363}) appends
     * {@code ellps=GRS80} to any non-pipeline operation carrying no shape parameter, and
     * it runs long before {@code need_ellps} is consulted. Measured: {@code cct +proj=geoc}
     * gives {@code 54.818973308324580} and {@code cct +proj=geoc +ellps=WGS84} gives
     * {@code 54.818973309214435}, so the default is observable at the 9th decimal and
     * choosing the wrong one is a ~1 mm error, not a rounding difference.
     */
    @Test
    public void aBareGeocDefaultsToGrs80AndThatIsDistinguishableFromWgs84() {
        double bare = deg(factory.create("+proj=geoc")
                .forward(new double[] {rad(12), rad(55), 0, 0})[1]);
        double grs80 = deg(factory.create("+proj=geoc +ellps=GRS80")
                .forward(new double[] {rad(12), rad(55), 0, 0})[1]);
        double wgs84 = deg(factory.create("+proj=geoc +ellps=WGS84")
                .forward(new double[] {rad(12), rad(55), 0, 0})[1]);

        assertEquals(grs80, bare, 0.0);
        assertEquals(54.818973309214435, wgs84, DEG_EPS);
        assertNotEquals("the two defaults must not be confusable", grs80, wgs84, 1e-12);
    }

    /**
     * The pole guard, {@code geoc.cpp:54-56}: {@code limit = M_HALFPI - 1e-9} radians, and
     * a latitude at or beyond it comes back untouched. Corpus rows
     * {@code more_builtins.gie:491-498} cover exactly 90, exactly -90, and
     * 89.99999999999 — the last of which is 1.7e-13 radians short of the pole and so is
     * still inside the guard. That is why the corpus expects 89.999999999989996 back
     * rather than a converted value: the difference from the input is the degree/radian
     * round-trip, not the conversion.
     */
    @Test
    public void theGuardReturnsPolarLatitudesUntouched() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        double[] cases = {90, -90, 89.99999999999, -89.99999999999};
        for (double lat : cases) {
            double in = rad(lat);
            double out = p.forward(new double[] {rad(12), in, 0, 0})[1];
            assertEquals("lat " + lat + " must be bit-identical", in, out, 0.0);
        }
        assertEquals(89.999999999989996,
                deg(p.forward(new double[] {rad(12), rad(89.99999999999), 0, 0})[1]), 1e-15);
    }

    /**
     * The other side of that boundary, so the guard cannot be a blanket "never convert".
     * {@code limit} is {@code M_HALFPI - 1e-9} radians, so a latitude two nanoradians
     * short of the pole is converted and one nanoradian short is not.
     */
    @Test
    public void justInsideTheLimitTheConversionStillHappens() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        double converted = Math.PI / 2.0 - 2e-9;
        double guarded = Math.PI / 2.0 - 5e-10;
        assertNotEquals("2e-9 short of the pole is inside the limit and must convert",
                converted, p.forward(new double[] {0, converted, 0, 0})[1], 0.0);
        assertEquals("5e-10 short of the pole is outside it and must not",
                guarded, p.forward(new double[] {0, guarded, 0, 0})[1], 0.0);
    }

    /**
     * The sphere guard, the {@code P->es == 0} arm of the same line. On a sphere the
     * geographic and geocentric latitudes coincide, so the operator is the identity —
     * it is <em>not</em> an error, and it is not {@code atan(tan(phi))} either, which
     * would lose the sign of a latitude past the branch cut. Measured:
     * {@code cct +proj=geoc +R=6378137} echoes {@code 55} back exactly.
     */
    @Test
    public void onASphereItIsExactlyTheIdentity() {
        Pipeline p = factory.create("+proj=geoc +R=6378137");
        double in = rad(55);
        assertEquals(in, p.forward(new double[] {rad(12), in, 0, 0})[1], 0.0);
        assertEquals(in, p.inverse(new double[] {rad(12), in, 0, 0})[1], 0.0);
    }

    /** {@code geoc.cpp:80-81}: {@code RADIANS} in and {@code RADIANS} out. */
    @Test
    public void bothSidesAreRadians() {
        Pipeline p = factory.create("+proj=geoc +ellps=GRS80");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());
    }

    /** {@code inv4d} is set at {@code geoc.cpp:78}, so the step is two-way. */
    @Test
    public void itIsInvertible() {
        assertTrue(factory.create("+proj=pipeline +step +proj=geoc +ellps=GRS80")
                .isInvertible());
    }

    /**
     * The operator and the {@code +geoc} <em>flag</em> must agree, because they are the
     * same conversion reached two ways and now share {@link GeocConversion}. The corpus
     * asserts it too: {@code more_builtins.gie:486} and {@code :504} are the same
     * coordinate and the same expected answer, written once as {@code proj=geoc} and once
     * as {@code proj=longlat ellps=GRS80 geoc inv}. If the shared class ever drifts, this
     * fails here rather than as a conformance row.
     */
    @Test
    public void theOperatorAndTheOldFlagGiveTheSameAnswer() {
        double viaOperator = factory.create("+proj=geoc +ellps=GRS80")
                .forward(new double[] {rad(12), rad(55), 0, 0})[1];
        double viaFlag = factory.create(
                "+proj=pipeline +step +proj=longlat +ellps=GRS80 +geoc +inv")
                .forward(new double[] {rad(12), rad(55), 0, 0})[1];
        assertEquals(deg(viaOperator), deg(viaFlag), DEG_EPS);
        assertEquals(54.818973308324573, deg(viaFlag), DEG_EPS);
    }
}
