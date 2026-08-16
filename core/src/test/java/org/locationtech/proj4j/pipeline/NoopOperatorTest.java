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
 * {@code +proj=noop} against {@code 9.8.1:src/conversions/noop.cpp}, all fifteen lines of
 * it.
 *
 * <p>The arithmetic cannot be wrong, so what is worth asserting is everything
 * <em>around</em> it: that the step exists rather than being optimised away, that both
 * declared sides are {@code WHATEVER} and therefore adopt a neighbour's, and that the
 * inverse is present. Those are the three ways an operator that does nothing can still be
 * wrong.
 */
public class NoopOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    /**
     * The corpus rows, {@code more_builtins.gie:807-812}: three coordinates of two, three
     * and four ordinates, each unchanged. Probed against the installed 9.8.1 with
     * {@code cct +proj=noop}, which echoes its input to the last digit.
     */
    @Test
    public void everyOrdinateSurvivesUntouched() {
        Pipeline p = factory.create("+proj=noop");
        double[] in = {25, 25, 25, 25};
        double[] out = p.forward(in.clone());
        for (int i = 0; i < 4; i++) {
            assertEquals(in[i], out[i], 0.0);
        }
        double[] back = p.inverse(in.clone());
        for (int i = 0; i < 4; i++) {
            assertEquals(in[i], back[i], 0.0);
        }
    }

    /**
     * {@code noop.cpp:12-13}: {@code PJ_IO_UNITS_WHATEVER} on both sides. This is the one
     * property of {@code noop} that has an observable consequence — declaring
     * {@code RADIANS} instead would make gie convert degrees on the way in and back out,
     * which is lossless for {@code 25 25} and is not lossless next to a
     * {@code +proj=unitconvert}.
     */
    @Test
    public void bothSidesAreWhatever() {
        Pipeline p = factory.create("+proj=noop");
        assertEquals(GieIoUnits.WHATEVER, p.left());
        assertEquals(GieIoUnits.WHATEVER, p.right());
    }

    /**
     * And because they are {@code WHATEVER}, a neighbour's units propagate through
     * ({@code pipeline.cpp:583-618}). A {@code noop} in front of a {@code cart} must not
     * leave the pipeline reporting {@code WHATEVER} on its left, or the comparator picks a
     * Euclidean metric for what is really an angular input.
     */
    @Test
    public void aNeighbourSUnitsPropagateThroughIt() {
        Pipeline p = factory.create(
                "+proj=pipeline +step +proj=noop +step +proj=cart +ellps=GRS80");
        assertEquals(GieIoUnits.RADIANS, p.left());
        assertEquals(GieIoUnits.CARTESIAN, p.right());
    }

    /**
     * Doing nothing is not the same as being absent: a {@code noop} step is a real step
     * and is counted as one. If the factory ever "optimised" it away, a pipeline's step
     * count would stop matching its definition and {@code +omit_fwd} on a {@code noop}
     * would have nowhere to attach.
     */
    @Test
    public void aNoopStepIsStillAStep() {
        assertEquals(1, factory.create("+proj=noop").steps().size());
        assertEquals(2, factory.create(
                "+proj=pipeline +step +proj=noop +step +proj=noop").steps().size());
    }

    /** {@code inv4d} is the same empty function, so the pipeline stays invertible. */
    @Test
    public void itIsInvertible() {
        assertTrue(factory.create("+proj=pipeline +step +proj=noop").isInvertible());
    }

    /** {@code +inv} on something that does nothing still does nothing. */
    @Test
    public void invertingItChangesNothing() {
        double[] out = factory.create("+proj=pipeline +step +inv +proj=noop")
                .forward(new double[] {1, 2, 3, 4});
        assertEquals(1.0, out[0], 0.0);
        assertEquals(4.0, out[3], 0.0);
    }
}
