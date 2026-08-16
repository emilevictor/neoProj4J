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
 * {@code +proj=topocentric} against PROJ 9.8.1.
 *
 * <p>Expected values are from the installed {@code cct} at 12 decimals; the corpus
 * blocks assert the same points at 1 mm.
 */
public class TopocentricOperatorTest {

    /** {@code builtins.gie:8315}, IOGP 373-7-2's worked example. */
    private static final String GEOCENTRIC_ORIGIN =
            "+proj=topocentric +ellps=WGS84 +X_0=3652755.3058 +Y_0=319574.6799 "
                    + "+Z_0=5201547.3536";

    /** {@code builtins.gie:8326}: the same origin, spelled geographically. */
    private static final String GEOGRAPHIC_ORIGIN_PIPELINE =
            "+proj=pipeline +step +proj=cart +ellps=WGS84 +step +proj=topocentric "
                    + "+ellps=WGS84 +lon_0=5 +lat_0=55 +h_0=200";

    private static final double DEG = Math.PI / 180.0;

    private final PipelineFactory factory = new PipelineFactory();

    /** {@code builtins.gie:8317-8318}. */
    @Test
    public void convertsGeocentricToTopocentric() {
        Pipeline p = factory.create(GEOCENTRIC_ORIGIN);
        double[] out = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 0});

        assertEquals(-189013.869090659922, out[0], 1e-6);
        assertEquals(-128642.040305069168, out[1], 1e-6);
        assertEquals(-4220.170822963638, out[2], 1e-6);
    }

    /**
     * {@code roundtrip 1}: the rotation is orthogonal, so the inverse is exact rather
     * than iterative.
     */
    @Test
    public void roundTripsExactly() {
        Pipeline p = factory.create(GEOCENTRIC_ORIGIN);
        double[] in = {3771793.968, 140253.342, 5124304.349, 0};
        double[] out = p.inverse(p.forward(in.clone()));
        assertEquals(in[0], out[0], 1e-6);
        assertEquals(in[1], out[1], 1e-6);
        assertEquals(in[2], out[2], 1e-6);
    }

    /**
     * {@code builtins.gie:8326-8329}. The same physical origin given as
     * {@code (lon_0, lat_0, h_0)} instead of {@code (X_0, Y_0, Z_0)}, reached through an
     * explicit {@code +proj=cart} because both of topocentric's sides are
     * {@code CARTESIAN}. The two spellings agree to about half a millimetre, which is
     * why the corpus can assert the same three numbers for both at 1 mm.
     */
    @Test
    public void acceptsAGeographicOriginThroughACartStep() {
        Pipeline p = factory.create(GEOGRAPHIC_ORIGIN_PIPELINE);
        double[] out = p.forward(new double[] {2.12955 * DEG, 53.80939444444444 * DEG, 73, 0});

        assertEquals(-189013.869150912709, out[0], 1e-6);
        assertEquals(-128642.039805558321, out[1], 1e-6);
        assertEquals(-4220.170758401737, out[2], 1e-6);
    }

    /**
     * {@code topocentric.cpp:112} — "allow missing h_0". {@code lon_0} needs
     * {@code lat_0} and nothing else; {@code h_0} defaults to zero, which moves the
     * origin 200 m down relative to the block above and therefore the up-ordinate 200 m
     * up. PROJ gives {@code -4020.170835842789}.
     */
    @Test
    public void h0IsOptionalAndDefaultsToZero() {
        Pipeline p = factory.create("+proj=topocentric +ellps=WGS84 +lon_0=5 +lat_0=55");
        double[] out = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 0});

        assertEquals(-189013.869082128454, out[0], 1e-6);
        assertEquals(-128642.040306101044, out[1], 1e-6);
        assertEquals(-4020.170835842789, out[2], 1e-6);
    }

    /**
     * {@code fwd_finalize}'s {@code CARTESIAN} case scales all three ordinates of the
     * output, and {@code inv_prepare} scales the input. Verified against PROJ:
     * {@code +to_meter=1000} turns the first block's answer into
     * {@code -189.013869090660  -128.642040305069  -4.220170822964}.
     */
    @Test
    public void toMeterScalesTheTopocentricSide() {
        Pipeline p = factory.create(GEOCENTRIC_ORIGIN + " +to_meter=1000");
        double[] out = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 0});

        assertEquals(-189.013869090660, out[0], 1e-9);
        assertEquals(-128.642040305069, out[1], 1e-9);
        assertEquals(-4.220170822964, out[2], 1e-9);

        double[] back = p.inverse(out);
        assertEquals(3771793.968, back[0], 1e-6);
        assertEquals(140253.342, back[1], 1e-6);
        assertEquals(5124304.349, back[2], 1e-6);
    }

    /** {@code topocentric.cpp:161-162}. */
    @Test
    public void declaresCartesianOnBothSides() {
        Pipeline p = factory.create(GEOCENTRIC_ORIGIN);
        assertEquals(GieIoUnits.CARTESIAN, p.left());
        assertEquals(GieIoUnits.CARTESIAN, p.right());
    }

    /**
     * The four refusals of {@code topocentric.cpp:98-116}, each one a corpus assertion.
     *
     * <p>The last row is the ordering one: {@code +X_0=0 +lon_0=0} is incomplete
     * <em>and</em> contradictory, and upstream tests exclusivity first, so the answer is
     * {@code MUTUALLY_EXCLUSIVE_ARGS} and not {@code MISSING_ARG} about {@code Y_0}.
     */
    @Test
    public void refusesAnAbsentIncompleteOrContradictoryOrigin() {
        assertRejected("+proj=topocentric +ellps=WGS84",
                PipelineErrorCode.MISSING_ARG, "missing X_0 or lon_0");
        assertRejected("+proj=topocentric +ellps=WGS84 +X_0=0 +Y_0=0",
                PipelineErrorCode.MISSING_ARG, "missing Y_0 and/or Z_0");
        assertRejected("+proj=topocentric +ellps=WGS84 +lon_0=0",
                PipelineErrorCode.MISSING_ARG, "missing lat_0");
        assertRejected("+proj=topocentric +ellps=WGS84 +X_0=0 +lon_0=0",
                PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS, "mutually exclusive");
    }

    /**
     * Exclusivity is over all six keys, not just the two that name a longitude. Each of
     * these pairs one key from each group without repeating the pair above.
     */
    @Test
    public void exclusivityCoversAllSixKeysNotJustX0AndLon0() {
        assertRejected("+proj=topocentric +ellps=WGS84 +X_0=0 +Y_0=0 +Z_0=0 +h_0=1",
                PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS, "mutually exclusive");
        assertRejected("+proj=topocentric +ellps=WGS84 +lon_0=0 +lat_0=0 +Z_0=1",
                PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS, "mutually exclusive");
    }

    private void assertRejected(String definition, PipelineErrorCode expected,
                                String messageFragment) {
        try {
            factory.create(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, expected, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}
