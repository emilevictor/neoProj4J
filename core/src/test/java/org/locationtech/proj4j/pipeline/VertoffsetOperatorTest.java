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

/**
 * {@code +proj=vertoffset} against PROJ 9.8.1.
 *
 * <p>Every expected value here was produced by the installed {@code cct} at 12 decimal
 * places, not copied from the {@code .gie} corpus, which rounds to 1 mm. The corpus
 * assertion is the contract; these are tighter so a transcription error shows up as a
 * failure here rather than as a near miss under a millimetre tolerance.
 */
public class VertoffsetOperatorTest {

    /** {@code more_builtins.gie:781}, EPSG Guidance Note 7-2's test point. */
    private static final String EPSG_GN7 =
            "+proj=vertoffset +lat_0=46.9166666666666666 +lon_0=8.183333333333334 "
                    + "+dh=-0.245 +slope_lat=-0.210 +slope_lon=-0.032 +ellps=GRS80";

    private static final double DEG = Math.PI / 180.0;

    private final PipelineFactory factory = new PipelineFactory();

    private static double[] deg(double lon, double lat, double z) {
        return new double[] {lon * DEG, lat * DEG, z, 0};
    }

    /**
     * {@code more_builtins.gie:786-787}. {@code cct} gives {@code 472.690447883277}; the
     * corpus rounds that to {@code 472.690}.
     */
    @Test
    public void movesOnlyTheHeight() {
        Pipeline p = factory.create(EPSG_GN7);
        double[] out = p.forward(deg(9.666666666666666, 47.333333333333336, 473.000));

        assertEquals("longitude must be untouched",
                9.666666666666666 * DEG, out[0], 0.0);
        assertEquals("latitude must be untouched",
                47.333333333333336 * DEG, out[1], 0.0);
        assertEquals(472.690447883277, out[2], 1e-9);
    }

    /**
     * The inverse subtracts the same offset, so from the same input it lands the same
     * distance the other side: {@code 473.309552116723}. Their mean is the input, exactly.
     */
    @Test
    public void theInverseSubtractsTheSameOffset() {
        Pipeline p = factory.create(EPSG_GN7);
        double[] back = p.inverse(deg(9.666666666666666, 47.333333333333336, 473.000));
        assertEquals(473.309552116723, back[2], 1e-9);
    }

    /** {@code roundtrip 1}: the two directions are exact mirrors, not an approximation. */
    @Test
    public void roundTripsExactly() {
        Pipeline p = factory.create(EPSG_GN7);
        double[] in = deg(9.666666666666666, 47.333333333333336, 473.000);
        double[] out = p.inverse(p.forward(in.clone()));
        assertEquals(in[0], out[0], 0.0);
        assertEquals(in[1], out[1], 0.0);
        assertEquals(in[2], out[2], 1e-12);
    }

    /**
     * The probe that discriminates the {@code lam0} question, and the reason it is here.
     *
     * <p>The slope term must use the longitude <em>relative</em> to {@code +lon_0} while
     * the output keeps the <em>absolute</em> longitude. At {@code lon = 100} against
     * {@code lon_0 = 8.183}, the two readings differ by about 92 degrees of arc in the
     * {@code slope_lon} term — several metres of height — and getting them the wrong way
     * round also shifts the output longitude by {@code lon_0}. Both are silent.
     *
     * <p>PROJ 9.8.1 gives {@code 100.000000000000  47.333333333333  471.631245628492}.
     * The corpus never leaves {@code lam - lam0 = 1.48} degrees, so nothing upstream
     * covers this.
     */
    @Test
    public void slopeUsesRelativeLongitudeAndOutputKeepsAbsolute() {
        Pipeline p = factory.create(EPSG_GN7);
        double[] out = p.forward(deg(100.0, 47.333333333333336, 473.000));

        assertEquals("the output longitude is the input longitude",
                100.0 * DEG, out[0], 0.0);
        assertEquals(471.631245628492, out[2], 1e-9);
    }

    /** All three offsets default to zero, so a bare operator is the identity. */
    @Test
    public void defaultsToTheIdentity() {
        Pipeline p = factory.create("+proj=vertoffset +ellps=GRS80");
        double[] out = p.forward(deg(9.5, 47.5, 473.0));
        assertEquals(473.0, out[2], 0.0);
    }

    /**
     * {@code vertoffset.cpp:89-90}. Both sides {@code RADIANS} is what lets it follow a
     * {@code longlat} step and precede another without a unit break.
     */
    @Test
    public void declaresRadiansOnBothSides() {
        Pipeline p = factory.create(EPSG_GN7);
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.RADIANS, p.right());
    }

    /**
     * {@code +slope_lat}/{@code +slope_lon} are arcseconds per degree of arc
     * ({@code ARCSEC_TO_RAD}, {@code vertoffset.cpp:77}), not radians and not degrees.
     * A slope of 3600 arcseconds is one degree, so at one radian of latitude away from
     * the origin the height moves by {@code rho0} degrees' worth — the assertion below
     * pins the scale factor rather than restating the formula.
     */
    @Test
    public void slopesAreArcsecondsNotRadians() {
        Pipeline whole = factory.create(
                "+proj=vertoffset +lat_0=0 +lon_0=0 +slope_lat=3600 +ellps=GRS80");
        Pipeline third = factory.create(
                "+proj=vertoffset +lat_0=0 +lon_0=0 +slope_lat=1200 +ellps=GRS80");

        double[] a = whole.forward(deg(0, 1, 0));
        double[] b = third.forward(deg(0, 1, 0));
        assertEquals("the slope must be linear in its own units", a[2], 3.0 * b[2], 1e-9);

        // 3600 arcsec = 1 degree = DEG_TO_RAD radians per radian of latitude.
        double rho0 = 6378137.0 * (1 - 0.006694380022903416)
                / Math.pow(1 - 0.0, 1.5);
        assertEquals(PipelineUnits.DEG_TO_RAD * rho0 * (1 * DEG), a[2], 1e-6);
    }
}
