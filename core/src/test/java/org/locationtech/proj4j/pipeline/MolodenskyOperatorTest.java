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

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=molodensky} against PROJ 9.8.1.
 *
 * <p>Expected values are from the installed {@code cct} at 12 decimals. The corpus
 * blocks ({@code more_builtins.gie:37-72}) assert the same point at a 2 m tolerance,
 * which is loose enough to hide a swapped abridged/standard branch — the two answers
 * differ by 0.44 mm in height and 1e-11 degrees in position. These assertions separate
 * them.
 */
public class MolodenskyOperatorTest {

    private static final String DELTAS =
            "+a=6378160 +rf=298.25 +da=-23 +df=-8.120449e-8 +dx=-134 +dy=-48 +dz=149";

    /** {@code more_builtins.gie:37}. */
    private static final String ABRIDGED = "+proj=molodensky " + DELTAS + " +abridged";

    /** {@code more_builtins.gie:50}: the same deltas, unabridged. */
    private static final String STANDARD = "+proj=molodensky " + DELTAS;

    private static final double DEG = Math.PI / 180.0;

    private final PipelineFactory factory = new PipelineFactory();

    private static double[] deg(double lon, double lat, double z) {
        return new double[] {lon * DEG, lat * DEG, z, 0};
    }

    private static void assertDegrees(double expectedLon, double expectedLat,
                                      double expectedZ, double[] actual) {
        assertEquals(expectedLon, actual[0] / DEG, 1e-10);
        assertEquals(expectedLat, actual[1] / DEG, 1e-10);
        assertEquals(expectedZ, actual[2], 1e-8);
    }

    /** {@code more_builtins.gie:45-46}, abridged. */
    @Test
    public void abridgedMatchesUpstream() {
        Pipeline p = factory.create(ABRIDGED);
        assertDegrees(144.968019692095, -37.798480353194, 46.378115057743,
                p.forward(deg(144.9667, -37.8, 50)));
    }

    /** {@code more_builtins.gie:58-59}, standard. Differs from abridged in the last places. */
    @Test
    public void standardMatchesUpstream() {
        Pipeline p = factory.create(STANDARD);
        assertDegrees(144.968019681762, -37.798480369217, 46.378553482498,
                p.forward(deg(144.9667, -37.8, 50)));
    }

    /**
     * The two branches must not be interchangeable. This is the assertion the corpus
     * cannot make: at a 2 m tolerance the abridged and standard answers are the same
     * point, so a build that ran the wrong formula would pass all four corpus rows.
     */
    @Test
    public void theTwoBranchesGiveDifferentAnswers() {
        double[] a = factory.create(ABRIDGED).forward(deg(144.9667, -37.8, 50));
        double[] s = factory.create(STANDARD).forward(deg(144.9667, -37.8, 50));
        assertTrue("abridged and standard must differ in height",
                Math.abs(a[2] - s[2]) > 1e-5);
        assertTrue("abridged and standard must differ in latitude",
                Math.abs(a[1] - s[1]) > 0.0);
    }

    /**
     * {@code molodensky.cpp:349} reads {@code +abridged} with {@code pj_param}'s
     * {@code 't'} sigil — a presence test. So {@code +abridged=no} selects the
     * <b>abridged</b> branch, which is what PROJ 9.8.1 does: it returns the abridged
     * numbers to the last printed digit. Reading the key as a boolean would silently run
     * the standard formula instead.
     */
    @Test
    public void abridgedIsAPresenceTestSoAbridgedNoIsStillAbridged() {
        Pipeline p = factory.create("+proj=molodensky " + DELTAS + " +abridged=no");
        assertDegrees(144.968019692095, -37.798480353194, 46.378115057743,
                p.forward(deg(144.9667, -37.8, 50)));
    }

    /**
     * The inverse evaluates the offset at the target rather than solving for the source,
     * so from the same input it does not simply mirror the forward. PROJ's answers,
     * abridged and standard.
     */
    @Test
    public void theInverseIsEvaluatedAtTheTargetCoordinate() {
        assertDegrees(144.965380307905, -37.801519646806, 53.621884942257,
                factory.create(ABRIDGED).inverse(deg(144.9667, -37.8, 50)));
        assertDegrees(144.965380318238, -37.801519630783, 53.621446517502,
                factory.create(STANDARD).inverse(deg(144.9667, -37.8, 50)));
    }

    /**
     * {@code more_builtins.gie:71}: {@code roundtrip 100    1 m}. Because the inverse is
     * an approximation, a hundred round trips are allowed to drift up to a metre — and
     * do drift. Asserting a tight bound here would be asserting something upstream does
     * not do; asserting no bound would miss a sign error. One metre after 100 cycles is
     * the corpus's own contract, restated.
     */
    @Test
    public void oneHundredRoundTripsStayWithinOneMetre() {
        Pipeline p = factory.create(STANDARD);
        double[] in = deg(144.9667, -37.8, 50);
        double[] c = in.clone();
        for (int i = 0; i < 100; i++) {
            c = p.inverse(p.forward(c));
        }
        // Degrees of arc are worth about 111 km, so scale before comparing to a metre.
        double dLon = (c[0] - in[0]) * 6378160.0 * Math.cos(in[1]);
        double dLat = (c[1] - in[1]) * 6378160.0;
        double dz = c[2] - in[2];
        assertTrue("drift was " + Math.sqrt(dLon * dLon + dLat * dLat + dz * dz) + " m",
                Math.sqrt(dLon * dLon + dLat * dLat + dz * dz) < 1.0);
    }

    /**
     * {@code more_builtins.gie:63-72}: all five deltas zero is legal — they are present,
     * which is what the {@code 't'} test asks — and the offset is identically zero, so
     * this block gets {@code roundtrip 1} at 1 mm rather than the 1 m the others get.
     */
    @Test
    public void allZeroDeltasAreTheIdentity() {
        Pipeline p = factory.create(
                "+proj=molodensky +a=6378160 +rf=298.25 +da=0 +df=0 +dx=0 +dy=0 +dz=0");
        double[] out = p.forward(deg(144.9667, -37.8, 50));
        assertDegrees(144.9667, -37.8, 50, out);

        double[] back = p.inverse(out);
        assertDegrees(144.9667, -37.8, 50, back);
    }

    /**
     * {@code molodensky.cpp:320-348}: five required keys, tested in order, each naming
     * itself. Two of these rows are corpus assertions; the other three exist because the
     * corpus stops after {@code dx} and a loop that fell through after the second key
     * would still pass it.
     */
    @Test
    public void refusesEachMissingDeltaByName() {
        String base = "+proj=molodensky +a=6378160 +rf=298.25";
        assertRejected(base, "missing dx");
        assertRejected(base + " +dx=0", "missing dy");
        assertRejected(base + " +dx=0 +dy=0", "missing dz");
        assertRejected(base + " +dx=0 +dy=0 +dz=0", "missing da");
        assertRejected(base + " +dx=0 +dy=0 +dz=0 +da=0", "missing df");
    }

    /** {@code molodensky.cpp:317-318}. */
    @Test
    public void declaresRadiansOnBothSides() {
        Pipeline p = factory.create(STANDARD);
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());
    }

    private void assertRejected(String definition, String messageFragment) {
        try {
            factory.create(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, PipelineErrorCode.MISSING_ARG, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}
