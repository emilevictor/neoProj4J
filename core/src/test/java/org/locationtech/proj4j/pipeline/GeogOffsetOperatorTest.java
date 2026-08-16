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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=geogoffset} against {@code 9.8.1:src/transformations/affine.cpp:228-250}.
 *
 * <p>Expected figures are the corpus rows at {@code more_builtins.gie:695-720}, confirmed
 * against the installed 9.8.1 with
 * {@code cct -d 15 +proj=geogoffset +dlon=3600 +dlat=-3600 +dh=3}, which prints
 * {@code 10.999999999999998  19.000000000000000  33.000000000000000  40.0000} for
 * {@code 10 20 30 40}. Note the longitude: one degree expressed as 3600 arcseconds does
 * not come back as exactly 11, and the corpus tolerance of 1mm is what absorbs that.
 */
public class GeogOffsetOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static double rad(final double deg) {
        return deg * PipelineUnits.DEG_TO_RAD;
    }

    private static double deg(final double radians) {
        return radians / PipelineUnits.DEG_TO_RAD;
    }

    /** 1e-9 degrees is about 0.1 mm, inside the corpus block's own 1mm tolerance. */
    private static final double DEG_EPS = 1e-9;

    private static final String OFFSET = "+proj=geogoffset +dlon=3600 +dlat=-3600 +dh=3";

    /**
     * The corpus rows {@code more_builtins.gie:710-720}: {@code +dlon} and {@code +dlat}
     * are <b>arcseconds</b>, so 3600 is one degree east and -3600 is one degree south,
     * while {@code +dh=3} is three <b>metres</b>. Getting that asymmetry wrong is a
     * 3600-fold error on the horizontal, which is why it is asserted rather than assumed.
     */
    @Test
    public void arcsecondsHorizontallyAndMetresVertically() {
        Pipeline p = factory.create(OFFSET);
        double[] out = p.forward(new double[] {rad(10), rad(20), 30, 40});
        assertEquals(11.0, deg(out[0]), DEG_EPS);
        assertEquals(19.0, deg(out[1]), DEG_EPS);
        assertEquals(33.0, out[2], 1e-12);
        assertEquals("the time ordinate is carried, not offset", 40.0, out[3], 0.0);
    }

    /**
     * The height offset applies even when the corpus row supplies no height, because gie
     * defaults the third ordinate to 0 rather than omitting it — so {@code 10 20} really
     * is {@code 10 20 0} and comes out at {@code h = 3}. Measured with
     * {@code echo "10 20 0 0" | cct}.
     */
    @Test
    public void aTwoOrdinateInputStillPicksUpTheHeightOffset() {
        double[] out = factory.create(OFFSET).forward(new double[] {rad(10), rad(20), 0, 0});
        assertEquals(11.0, deg(out[0]), DEG_EPS);
        assertEquals(19.0, deg(out[1]), DEG_EPS);
        assertEquals(3.0, out[2], 1e-12);
    }

    /**
     * {@code reverse_4d} subtracts each offset. Because {@code geogoffset} never calls
     * {@code computeReverseParameters}, the reverse matrix stays {@code initQ()}'s
     * identity, so unlike {@code affine} the inverse is a plain subtraction and cannot be
     * lost. {@code roundtrip 1} on all four corpus rows.
     */
    @Test
    public void theInverseSubtractsTheSameOffsets() {
        Pipeline p = factory.create(OFFSET);
        double[] in = {rad(10), rad(20), 30, 40};
        double[] back = p.inverse(p.forward(in.clone()));
        assertEquals(10.0, deg(back[0]), DEG_EPS);
        assertEquals(20.0, deg(back[1]), DEG_EPS);
        assertEquals(30.0, back[2], 1e-9);
        assertEquals(40.0, back[3], 0.0);
    }

    /**
     * The corpus opens the section with a bare {@code +proj=geogoffset}
     * ({@code more_builtins.gie:695-702}) whose only assertion is that it changes nothing.
     * All three keys default to {@code pj_param}'s 0.
     */
    @Test
    public void aBareGeogOffsetIsTheIdentity() {
        double[] in = {rad(10), rad(20), 30, 40};
        double[] out = factory.create("+proj=geogoffset").forward(in.clone());
        for (int i = 0; i < 4; i++) {
            assertEquals(in[i], out[i], 0.0);
        }
    }

    /** Each key is independent; an absent one is 0 and not a copy of its neighbour. */
    @Test
    public void eachOffsetActsOnItsOwnOrdinateOnly() {
        double[] lon = factory.create("+proj=geogoffset +dlon=3600")
                .forward(new double[] {rad(10), rad(20), 30, 0});
        assertEquals(11.0, deg(lon[0]), DEG_EPS);
        assertEquals(20.0, deg(lon[1]), 0.0);
        assertEquals(30.0, lon[2], 0.0);

        double[] h = factory.create("+proj=geogoffset +dh=3")
                .forward(new double[] {rad(10), rad(20), 30, 0});
        assertEquals(10.0, deg(h[0]), 0.0);
        assertEquals(20.0, deg(h[1]), 0.0);
        assertEquals(33.0, h[2], 0.0);
    }

    /** A negative offset is just an offset; there is no range check on any of the three. */
    @Test
    public void offsetsMayBeNegative() {
        double[] out = factory.create("+proj=geogoffset +dlon=-3600 +dh=-10")
                .forward(new double[] {rad(10), rad(20), 30, 0});
        assertEquals(9.0, deg(out[0]), DEG_EPS);
        assertEquals(20.0, out[2], 1e-12);
    }

    /**
     * {@code affine.cpp:241-242} sets {@code RADIANS} on both sides, where plain
     * {@code affine} leaves both {@code WHATEVER} ({@code :192-193}). This is the
     * difference that makes the corpus rows read in degrees, and it is the one property
     * that would be easy to inherit wrongly from {@link AffineOperator}.
     */
    @Test
    public void bothSidesAreRadiansUnlikePlainAffine() {
        Pipeline offset = factory.create("+proj=geogoffset");
        assertEquals(GieIoUnits.RADIANS, offset.left());
        assertEquals(GieIoUnits.RADIANS, offset.right());

        Pipeline affine = factory.create("+proj=affine");
        assertEquals(GieIoUnits.WHATEVER, affine.left());
        assertEquals(GieIoUnits.WHATEVER, affine.right());
    }

    /**
     * And because the sides are fixed rather than {@code WHATEVER}, a {@code geogoffset}
     * does not adopt a neighbour's units — it pins them. A pipeline of
     * {@code geogoffset} then {@code cart} is angular in and cartesian out.
     */
    @Test
    public void itPinsTheUnitsOfANeighbouringWhateverStep() {
        Pipeline p = factory.create("+proj=pipeline"
                + " +step +proj=geogoffset +dlon=3600"
                + " +step +proj=cart +ellps=GRS80");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.CARTESIAN, p.right());
    }

    /** {@code computeReverseParameters} is never reached, so this cannot become one-way. */
    @Test
    public void itIsAlwaysInvertible() {
        assertTrue(factory.create("+proj=pipeline +step " + OFFSET.substring(1))
                .isInvertible());
    }
}
