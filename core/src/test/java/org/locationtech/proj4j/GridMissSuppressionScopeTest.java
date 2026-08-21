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
package org.locationtech.proj4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <b>{@link DomainErrorPolicy#LEGACY_NO_SHIFT} suppresses exactly one {@link ErrorCause}, proven
 * exhaustively — because behaviour cannot prove it.</b>
 *
 * <h2>Why this file exists in this package</h2>
 *
 * <p>{@code BasicCoordinateTransform.isSuppressibleGridMiss} is package-private, and making it public
 * to test it would be adding API for a test. So the test lives beside it. The behavioural half of the
 * story is in {@code failopen/Nad27CoverageMissPassesThroughTest}; this file is the half that
 * behaviour cannot reach.
 *
 * <h2>Why behaviour cannot reach it, measured</h2>
 *
 * <p>Widening the predicate to accept every cause — replacing the guard with {@code if (false)} at
 * both call sites — leaves <b>all 2,630 tests in {@code core} green</b>. That was run, not assumed.
 * The reason is structural: the datum grid-shift path raises exactly one cause. {@code Grid.shift}'s
 * only {@code throw} is {@link ErrorCause#COORDINATE_OUTSIDE_GRID}, and it uses that same cause for
 * both of the shapes PROJ distinguishes — no grid contains the point
 * ({@code grids.cpp:3517-3520}), and a grid contains it but interpolation yields no value
 * ({@code :3534-3536}, which {@code applyOne} signals with a {@code NO_VALUE} return rather than an
 * exception). No input can therefore reach the re-throw branch.
 *
 * <p>The guard is kept anyway, and this is the test that keeps it honest. Deleting it because
 * "nothing reaches it" would make the next cause added to the grid subsystem silently suppressible,
 * which is precisely the class of defect this library exists to eliminate. A guard that cannot fail
 * today but can be proven narrow is worth more than no guard.
 */
public class GridMissSuppressionScopeTest {

    /**
     * Every {@link ErrorCause}, one at a time, against the predicate.
     *
     * <p>The loop is the assertion; the count afterwards is what stops the loop from passing
     * vacuously if {@link ErrorCause#values()} were ever empty or the constant renamed.
     */
    @Test
    public void exactlyOneCauseIsSuppressible() {
        int suppressible = 0;
        for (ErrorCause cause : ErrorCause.values()) {
            boolean actual = BasicCoordinateTransform.isSuppressibleGridMiss(cause);
            if (cause == ErrorCause.COORDINATE_OUTSIDE_GRID) {
                assertTrue("COORDINATE_OUTSIDE_GRID must be suppressible", actual);
                suppressible++;
            } else {
                assertFalse(cause + " must NOT be suppressible by LEGACY_NO_SHIFT", actual);
            }
        }
        assertEquals("exactly one of the " + ErrorCause.values().length
                + " causes may be suppressed", 1, suppressible);
        assertTrue("the enum must be non-empty, or the loop above asserts nothing",
                ErrorCause.values().length > 5);
    }

    /**
     * The confusable neighbours, named rather than left to the loop.
     *
     * <p>These are the three causes the grid subsystem could plausibly grow, so a reader can see they
     * are excluded without reasoning about {@link ErrorCause#values()}. {@link ErrorCause#GRID_NODATA}
     * is the sharpest: it is the *other* half of the same physical situation — a grid that covers the
     * point but has no value for it — and it is deliberately not suppressed, because "the authority
     * published a hole here" is a different statement from "no grid reaches this point".
     */
    @Test
    public void theConfusableNeighboursAreExcludedByName() {
        assertFalse("a nodata cell is not a coverage miss",
                BasicCoordinateTransform.isSuppressibleGridMiss(ErrorCause.GRID_NODATA));
        assertFalse("a grid that would not load is not a coverage miss",
                BasicCoordinateTransform.isSuppressibleGridMiss(ErrorCause.MISSING_GRID));
        assertFalse("a projection-domain refusal is not a coverage miss",
                BasicCoordinateTransform.isSuppressibleGridMiss(
                        ErrorCause.COORDINATE_OUT_OF_DOMAIN));
        assertFalse("and a numerical failure certainly is not",
                BasicCoordinateTransform.isSuppressibleGridMiss(ErrorCause.NUMERICAL_FAILURE));
    }

    /**
     * The suppressed cause is a <em>coordinate</em> error, which is what makes suppressing it
     * coherent with {@link DomainErrorPolicy}'s documented scope.
     *
     * <p>{@link DomainErrorPolicy} says only causes for which
     * {@link ErrorCause#isCoordinateError()} holds are eligible for any policy at all, because a CRS
     * or operation failure is a property of the transform rather than of the point. If
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID} were ever reclassified, this policy would be
     * suppressing a planning-time defect once per row, and this assertion is what would catch it.
     */
    @Test
    public void theSuppressedCauseIsAPerCoordinateOne() {
        assertTrue("COORDINATE_OUTSIDE_GRID must remain a per-coordinate cause for "
                        + "LEGACY_NO_SHIFT to be within DomainErrorPolicy's documented scope",
                ErrorCause.COORDINATE_OUTSIDE_GRID.isCoordinateError());
    }
}
