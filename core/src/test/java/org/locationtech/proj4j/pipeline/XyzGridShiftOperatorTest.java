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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=xyzgridshift} against PROJ 9.8.1.
 *
 * <h2>Where the expected numbers came from</h2>
 *
 * <p>The gie corpus exercises this operator with <b>two</b> assertions in total
 * ({@code gie/geotiff_grids.gie}), so the conformance gate is very nearly blind to it and these
 * unit tests are the real evidence. Every expected coordinate below was therefore measured from the
 * <b>installed PROJ 9.8.1 {@code cct}</b>, at nine decimals, reading the same
 * {@code subset_of_gr3df97a.tif} bytes this module ships — for example:
 *
 * <pre>
 * echo '4.9 44.1 0 0' | cct -d 9 +proj=pipeline \
 *     +step +proj=cart +ellps=GRS80 \
 *     +step +proj=xyzgridshift +grids=subset_of_gr3df97a.tif +ellps=GRS80
 * </pre>
 *
 * <p>They are quoted here as measurements, not as this implementation's own output, and the
 * tolerance is 1&nbsp;nm — tight enough that any change to the interpolation's summation order
 * would show.
 *
 * <p>The probe point (4.9&deg;E, 44.1&deg;N, h=0 on GRS80) is well inside the grid and away from
 * every edge, so it exercises the ordinary bilinear cell rather than the clamped one.
 */
public class XyzGridShiftOperatorTest {

    private final PipelineFactory factory = new PipelineFactory();

    private static final String GRID = "subset_of_gr3df97a.tif";
    private static final String ELLPS = "+ellps=GRS80";

    /** {@code cct +proj=cart +ellps=GRS80} of 4.9, 44.1, 0. */
    private static final double[] GEOCENTRIC =
        {4570983.941416112, 391871.558463473, 4416077.688561062, 0.0};

    /** 1 nm. cct printed nine decimals, so its own rounding is half of this. */
    private static final double NM = 1e-9;

    // ---------------------------------------------------------------- the two algorithms

    /** {@code direct_adjustment}: the default, one lookup at the input position. */
    @Test
    public void theDefaultGridRefIsInputCrsAndMatchesProjToTheDigit() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=" + GRID + " " + ELLPS);
        assertEquals(GieIoUnits.CARTESIAN, p.left());
        assertEquals(GieIoUnits.CARTESIAN, p.right());

        double[] out = p.forward(GEOCENTRIC.clone());
        assertEquals(4570816.009409276, out[0], NM);
        assertEquals(391811.858462710, out[1], NM);
        assertEquals(4416397.591576199, out[2], NM);

        // Naming the default explicitly must be the same operation, not a near miss.
        Pipeline explicit =
            factory.create("+proj=xyzgridshift +grids=" + GRID + " +grid_ref=input_crs " + ELLPS);
        double[] same = explicit.forward(GEOCENTRIC.clone());
        assertEquals(out[0], same[0], 0.0);
        assertEquals(out[1], same[1], 0.0);
        assertEquals(out[2], same[2], 0.0);
    }

    /**
     * {@code iterative_adjustment}: {@code +grid_ref=output_crs} says the grid is indexed in the
     * target frame, so the shift is found by fixed-point iteration.
     *
     * <p>The two algorithms differ here in the sixth decimal of a metre — about 1.5&nbsp;µm — which
     * is small enough that a test comparing them at any everyday tolerance would pass whichever one
     * ran. That is the reason both expected triples are pinned to the nanometre, and the reason the
     * last assertion checks they are <em>not</em> equal.
     */
    @Test
    public void gridRefOutputCrsIteratesAndMatchesProjToTheDigit() {
        Pipeline p = factory.create(
                "+proj=xyzgridshift +grids=" + GRID + " +grid_ref=output_crs " + ELLPS);
        double[] out = p.forward(GEOCENTRIC.clone());
        assertEquals(4570816.010911774, out[0], NM);
        assertEquals(391811.857298859, out[1], NM);
        assertEquals(4416397.590181743, out[2], NM);

        Pipeline direct = factory.create("+proj=xyzgridshift +grids=" + GRID + " " + ELLPS);
        double[] other = direct.forward(GEOCENTRIC.clone());
        assertTrue("the two algorithms must not have collapsed into one",
                Math.abs(out[0] - other[0]) > 1e-4);
    }

    // ----------------------------------------------------------------------- the inverse

    /**
     * The inverse swaps the algorithms: undoing a direct shift needs the iteration. So this is the
     * only test that exercises the loop's <em>convergence</em>, and it measures the closure rather
     * than assuming it — upstream's loop gives up silently after ten passes.
     */
    @Test
    public void theInverseOfADirectShiftIteratesBackToTheInput() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=" + GRID + " " + ELLPS);
        double[] shifted = {4570816.009409276, 391811.858462710, 4416397.591576199, 0.0};
        double[] back = p.inverse(shifted);

        // cct -I printed the input back at all nine decimals.
        assertEquals(GEOCENTRIC[0], back[0], NM);
        assertEquals(GEOCENTRIC[1], back[1], NM);
        assertEquals(GEOCENTRIC[2], back[2], NM);

        // And the round trip through this object closes to well under a nanometre.
        double[] there = p.forward(GEOCENTRIC.clone());
        double[] andBack = p.inverse(there);
        double dx = andBack[0] - GEOCENTRIC[0];
        double dy = andBack[1] - GEOCENTRIC[1];
        double dz = andBack[2] - GEOCENTRIC[2];
        double closure = Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertTrue("geocentric closure was " + closure + " m", closure < 1e-9);
    }

    /** ... and with {@code +grid_ref=output_crs} the inverse is the direct branch, sign flipped. */
    @Test
    public void theInverseOfAnIteratedShiftIsASingleLookup() {
        Pipeline p = factory.create(
                "+proj=xyzgridshift +grids=" + GRID + " +grid_ref=output_crs " + ELLPS);
        double[] out = p.inverse(GEOCENTRIC.clone());
        assertEquals(4571151.873422948, out[0], NM);
        assertEquals(391931.258464236, out[1], NM);
        assertEquals(4415757.785545926, out[2], NM);
    }

    // -------------------------------------------------------------------------- multiplier

    /**
     * {@code +multiplier} scales the interpolated triple, not the position. The default is 1 and
     * {@code deformation}-style uses set it to a per-year or unit-conversion factor.
     */
    @Test
    public void multiplierScalesTheShiftAndNotThePosition() {
        Pipeline p = factory.create(
                "+proj=xyzgridshift +grids=" + GRID + " +multiplier=2 " + ELLPS);
        double[] out = p.forward(GEOCENTRIC.clone());
        assertEquals(4570648.077402440, out[0], NM);
        assertEquals(391752.158461947, out[1], NM);
        assertEquals(4416717.494591336, out[2], NM);

        // The measured shift is exactly twice the unmultiplied one: -167.932006836 -> -335.864.
        Pipeline plain = factory.create("+proj=xyzgridshift +grids=" + GRID + " " + ELLPS);
        double[] once = plain.forward(GEOCENTRIC.clone());
        assertEquals(2 * (once[0] - GEOCENTRIC[0]), out[0] - GEOCENTRIC[0], NM);
        assertEquals(2 * (once[2] - GEOCENTRIC[2]), out[2] - GEOCENTRIC[2], NM);
    }

    // ------------------------------------------------------------------- the null grid

    /** {@code +grids=null} is a valid definition and an exact identity, not an error. */
    @Test
    public void theNullGridIsAnExactIdentityBothWays() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=null " + ELLPS);
        double[] out = p.forward(GEOCENTRIC.clone());
        assertEquals(GEOCENTRIC[0], out[0], 0.0);
        assertEquals(GEOCENTRIC[1], out[1], 0.0);
        assertEquals(GEOCENTRIC[2], out[2], 0.0);

        double[] back = p.inverse(GEOCENTRIC.clone());
        assertEquals(GEOCENTRIC[0], back[0], 0.0);
        assertEquals(GEOCENTRIC[1], back[1], 0.0);
        assertEquals(GEOCENTRIC[2], back[2], 0.0);
    }

    /** It also swallows a point no real grid covers, which is what makes it useful as a fallback. */
    @Test
    public void theNullGridAlsoCoversWhatTheRealGridDoesNot() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=" + GRID + ",null " + ELLPS);
        double[] far = {6378137.0, 0.0, 0.0, 0.0};
        double[] out = p.forward(far.clone());
        assertEquals(far[0], out[0], 0.0);
    }

    // ------------------------------------------------------- transform-time refusals

    /**
     * A point outside every grid is an <b>error</b>, never a zero shift. {@code cct} answers
     * {@code TRANSFORMATION ERROR} for the same input; the equator on the prime meridian is nowhere
     * near a 10&times;10 grid over the Rh&ocirc;ne valley.
     */
    @Test
    public void aPointOutsideEveryGridFailsRatherThanShiftingByZero() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=" + GRID + " " + ELLPS);
        double[] far = {6378137.0, 0.0, 0.0, 0.0};
        try {
            p.forward(far);
            fail("expected a CrsTransformException");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
        }
    }

    /**
     * A grid with fewer than three channels is refused <b>at transform time</b>, exactly where
     * upstream refuses it. {@code test_vgrid_nodata.tif} is a one-band vertical grid, and 9.8.1's
     * {@code cct} answers {@code "xyzgridshift: grid has not enough samples"} for a point inside
     * it rather than failing to build the pipeline.
     *
     * <p>Moving this to construction would be tidier and would be a parity bug: a {@code +grids=}
     * list may name a grid that a given coordinate never selects, and PROJ builds that pipeline.
     */
    @Test
    public void aGridWithTooFewChannelsIsRefusedAtTransformTimeNotAtSetup() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=test_vgrid_nodata.tif " + ELLPS);

        // 4.1E, 52.2N on GRS80, inside the 4x4 vertical grid.
        Pipeline cart = factory.create("+proj=cart " + ELLPS);
        double[] xyz = cart.forward(new double[] {
            Math.toRadians(4.1), Math.toRadians(52.2), 0.0, 0.0});
        try {
            p.forward(xyz);
            fail("expected a CrsTransformException");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.MISSING_GRID, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("not enough samples"));
        }
    }

    // ------------------------------------------------------------- setup-time refusals

    /**
     * {@code +grid_ref} is validated <b>before</b> {@code +grids} is looked for
     * ({@code xyzgridshift.cpp:245-264}). Verified against 9.8.1's {@code cct}, which answers 1027
     * (illegal value) and not 1026 (missing arg) for a definition missing both.
     */
    @Test
    public void anUnknownGridRefIsReportedBeforeAMissingGrids() {
        assertRejected("+proj=xyzgridshift +grid_ref=bogus " + ELLPS,
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "unusupported value for grid_ref");
        // A bare +grid_ref reaches the same strcmp with nothing to compare, and is refused too.
        assertRejected("+proj=xyzgridshift +grid_ref " + ELLPS,
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "unusupported value for grid_ref");
        assertRejected("+proj=xyzgridshift +grid_ref= " + ELLPS,
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "unusupported value for grid_ref");
    }

    /**
     * The misspelling in {@code "unusupported"} is upstream's, at {@code xyzgridshift.cpp:257}, and
     * is reproduced rather than corrected: the message is what a user greps for and what a
     * downstream test may match on.
     */
    @Test
    public void theUpstreamMisspellingIsPreserved() {
        try {
            factory.create("+proj=xyzgridshift +grid_ref=bogus " + ELLPS);
            fail("expected a PipelineDefinitionException");
        } catch (PipelineDefinitionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unusupported"));
        }
    }

    @Test
    public void aMissingOrUnresolvableGridIsRefusedAtSetup() {
        assertRejected("+proj=xyzgridshift " + ELLPS,
                PipelineErrorCode.MISSING_ARG, "+grids parameter missing.");
        assertRejected("+proj=xyzgridshift +grids=no_such_grid.tif " + ELLPS,
                PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, "could not find required grid(s)");
    }

    /** A leading {@code @} makes a grid optional, and an all-optional list is legal and empty. */
    @Test
    public void anOptionalGridThatIsMissingLeavesTheStepWithNothingToFind() {
        Pipeline p = factory.create("+proj=xyzgridshift +grids=@no_such_grid.tif " + ELLPS);
        try {
            p.forward(GEOCENTRIC.clone());
            fail("expected a CrsTransformException: the list is empty, so nothing covers the point");
        } catch (CrsTransformException e) {
            assertEquals(ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
        }
    }

    // -------------------------------------------------------------------- thread safety

    /**
     * {@code CrsOperation} promises its objects are immutable and shareable across any number of
     * threads. Upstream keeps its iteration state on {@code P-&gt;opaque}; this port keeps none, and
     * this test is what says so. Interleaving two different inputs on one shared pipeline would
     * expose a shared scratch buffer immediately.
     */
    @Test
    public void oneOperatorInstanceIsSafeToShareAcrossThreads() throws Exception {
        final Pipeline p = factory.create(
                "+proj=xyzgridshift +grids=" + GRID + " +grid_ref=output_crs " + ELLPS);
        final double[] expected = p.forward(GEOCENTRIC.clone());
        final double[] other = {4570983.9, 391871.5, 4416077.6, 0.0};
        final double[] expectedOther = p.forward(other.clone());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<Future<String>>();
            for (int t = 0; t < 4; t++) {
                final boolean useFirst = (t % 2) == 0;
                futures.add(pool.submit(new Callable<String>() {
                    @Override
                    public String call() {
                        double[] in = useFirst ? GEOCENTRIC : other;
                        double[] want = useFirst ? expected : expectedOther;
                        for (int i = 0; i < 500; i++) {
                            double[] got = p.forward(in.clone());
                            for (int k = 0; k < 3; k++) {
                                if (got[k] != want[k]) {
                                    return "pass " + i + " ordinate " + k + ": " + got[k]
                                            + " != " + want[k];
                                }
                            }
                        }
                        return null;
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                String failure = futures.get(i).get();
                if (failure != null) {
                    fail(failure);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------- helpers

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
