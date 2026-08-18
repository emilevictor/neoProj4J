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
import org.locationtech.proj4j.pipeline.DefmodelMasterFile.TimeFunction;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The six {@code +proj=defmodel} time functions, evaluated directly.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>The gie corpus reaches exactly two of the six: every model under
 * {@code conformance/src/test/resources/proj-data/tests/} uses {@code constant} or
 * {@code step}, and every {@code step} in it has {@code step_epoch} 1900-01-01, so the
 * factor those rows measure is always 1. {@code velocity}, {@code reverse_step},
 * {@code piecewise} and {@code exponential} are ported for faithfulness and would
 * otherwise ship with <b>no</b> coverage — a {@code return 1.0} in any of the four would
 * pass the whole conformance run.
 *
 * <p>Every expected number here is transcribed from PROJ 9.8.1's own unit test,
 * {@code test/unit/test_defmodel.cpp}: {@code evaluate_constant} through
 * {@code evaluate_exponential}, plus that file's eleven {@code piecewise} variations.
 * The model JSON is its {@code getFullValidContent()} fixture reduced to one component,
 * which is all {@code evaluateAt} depends on. Upstream compares most of these for exact
 * equality, so the deltas here are {@code 0.0} wherever upstream's are.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>{@link #theSixFunctionsDisagreeAtOneInstant()} is the positive control. The realistic
 * defect in this area is not bad arithmetic but bad wiring — a branch in
 * {@code parseTimeFunction} that builds the neighbouring class, which for {@code step} and
 * {@code reverse_step} differ only in one return value. That test evaluates all six at one
 * instant and requires four distinct answers, so a dispatch that collapsed any two of them
 * fails here even if each class is correct in isolation.
 */
public class DefmodelTimeFunctionTest {

    /** Upstream's {@code getMinValidContent()} plus one horizontal component. */
    private static String model(final String timeFunction) {
        return "{"
                + "\"file_type\": \"GeoTIFF\","
                + "\"format_version\": \"1.0\","
                + "\"source_crs\": \"EPSG:4959\","
                + "\"target_crs\": \"EPSG:7907\","
                + "\"definition_crs\": \"EPSG:4959\","
                + "\"horizontal_offset_unit\": \"metre\","
                + "\"vertical_offset_unit\": \"metre\","
                + "\"horizontal_offset_method\": \"addition\","
                + "\"extent\": {\"type\": \"bbox\","
                + " \"parameters\": {\"bbox\": [158, -58, 194, -25]}},"
                + "\"time_extent\": {\"first\": \"1900-01-01T00:00:00Z\","
                + " \"last\": \"2050-01-01T00:00:00Z\"},"
                + "\"components\": [{"
                + "  \"description\": \"description\","
                + "  \"displacement_type\": \"horizontal\","
                + "  \"uncertainty_type\": \"none\","
                + "  \"extent\": {\"type\": \"bbox\","
                + "   \"parameters\": {\"bbox\": [158, -58, 194, -25]}},"
                + "  \"spatial_model\": {\"type\": \"GeoTIFF\","
                + "   \"interpolation_method\": \"bilinear\","
                + "   \"filename\": \"nzgd2000-ndm-grid02.tif\"},"
                + "  \"time_function\": " + timeFunction
                + "}]}";
    }

    private static TimeFunction fn(final String timeFunction) {
        return DefmodelMasterFile.parse(model(timeFunction)).components().get(0).timeFunction();
    }

    private static final String CONSTANT = "{\"type\": \"constant\", \"parameters\": {}}";

    private static final String VELOCITY = "{\"type\": \"velocity\", \"parameters\": "
            + "{\"reference_epoch\": \"2000-01-01T00:00:00Z\"}}";

    private static final String STEP = "{\"type\": \"step\", \"parameters\": "
            + "{\"step_epoch\": \"2000-01-01T00:00:00Z\"}}";

    private static final String REVERSE_STEP = "{\"type\": \"reverse_step\", \"parameters\": "
            + "{\"step_epoch\": \"2000-01-01T00:00:00Z\"}}";

    private static final String EXPONENTIAL = "{\"type\": \"exponential\", \"parameters\": {"
            + "\"reference_epoch\": \"2000-01-01T00:00:00Z\","
            + "\"end_epoch\": \"2001-01-01T00:00:00Z\","
            + "\"relaxation_constant\": 2.0,"
            + "\"before_scale_factor\": 0.0,"
            + "\"initial_scale_factor\": 1.0,"
            + "\"final_scale_factor\": 3.0}}";

    /** Upstream's four-row model: note rows 2 and 3 share the epoch 2017-01-01. */
    private static String piecewise(final String beforeFirst, final String afterLast) {
        return piecewise(beforeFirst, afterLast,
                "{\"epoch\": \"2016-01-01T00:00:00Z\", \"scale_factor\": 0.5},"
                + "{\"epoch\": \"2017-01-01T00:00:00Z\", \"scale_factor\": 1.0},"
                + "{\"epoch\": \"2017-01-01T00:00:00Z\", \"scale_factor\": 2.0},"
                + "{\"epoch\": \"2018-01-01T00:00:00Z\", \"scale_factor\": 1.0}");
    }

    private static String piecewise(final String beforeFirst, final String afterLast,
                                    final String rows) {
        return "{\"type\": \"piecewise\", \"parameters\": {"
                + "\"before_first\": \"" + beforeFirst + "\","
                + "\"after_last\": \"" + afterLast + "\","
                + "\"model\": [" + rows + "]}}";
    }

    // ------------------------------------------------------------ the two the corpus reaches

    /** {@code evaluate_constant}. No {@code parameters} member is required of this one. */
    @Test
    public void constantIsAlwaysOne() {
        TimeFunction f = fn(CONSTANT);
        assertEquals("constant", f.type());
        assertEquals(1.0, f.evaluateAt(1999.0), 0.0);
        assertEquals(1.0, f.evaluateAt(2000.0), 0.0);
        assertEquals(1.0, f.evaluateAt(2001.0), 0.0);

        // Upstream passes an empty json() rather than looking the member up, so a model
        // that omits it entirely must still parse.
        assertEquals(1.0, fn("{\"type\": \"constant\"}").evaluateAt(2000.0), 0.0);
    }

    /** {@code evaluate_step}. The boundary belongs to the later side. */
    @Test
    public void stepIsZeroBeforeItsEpochAndOneFromIt() {
        TimeFunction f = fn(STEP);
        assertEquals(0.0, f.evaluateAt(1999.99), 0.0);
        assertEquals(1.0, f.evaluateAt(2000.00), 0.0);
        assertEquals(1.0, f.evaluateAt(2000.01), 0.0);
    }

    // ------------------------------------------------------- the four the corpus never reaches

    /** {@code evaluate_velocity}: years since the reference epoch, negative before it. */
    @Test
    public void velocityIsYearsSinceItsReferenceEpoch() {
        TimeFunction f = fn(VELOCITY);
        assertEquals(-1.0, f.evaluateAt(1999.0), 0.0);
        assertEquals(0.0, f.evaluateAt(2000.0), 0.0);
        assertEquals(1.0, f.evaluateAt(2001.0), 0.0);
    }

    /**
     * {@code evaluate_reverse_step}: &minus;1 before, 0 from the epoch on. It is not the
     * negation of {@code step} — both are 0 on one side — so the two cannot be folded.
     */
    @Test
    public void reverseStepIsMinusOneBeforeItsEpochAndZeroFromIt() {
        TimeFunction f = fn(REVERSE_STEP);
        assertEquals(-1.0, f.evaluateAt(1999.99), 0.0);
        assertEquals(0.0, f.evaluateAt(2000.00), 0.0);
        assertEquals(0.0, f.evaluateAt(2000.01), 0.0);
    }

    /**
     * {@code evaluate_piecewise}, first block. The interesting row is 2017.0: two rows share
     * that epoch, and the search takes the <em>second</em> of them as the left end of the next
     * segment, so the factor jumps from 1.0 to 2.0 there rather than averaging.
     */
    @Test
    public void piecewiseInterpolatesBetweenItsRows() {
        TimeFunction f = fn(piecewise("zero", "constant"));
        assertEquals(0.0, f.evaluateAt(2015.99), 0.0);
        assertEquals(0.5, f.evaluateAt(2016.00), 0.0);
        assertEquals(0.75, f.evaluateAt(2016.5), 0.0);
        assertEquals(1.0, f.evaluateAt(2017 - 1e-9), 1e-9);
        assertEquals(2.0, f.evaluateAt(2017.0), 0.0);
        assertEquals(1.5, f.evaluateAt(2017.5), 0.0);
        assertEquals(1.0, f.evaluateAt(2018.0), 0.0);
        assertEquals(1.0, f.evaluateAt(2019.0), 0.0);
    }

    /** The three {@code before_first} settings, evaluated at 2015.5. */
    @Test
    public void piecewiseBeforeFirstHasThreeSettings() {
        assertEquals(0.0, fn(piecewise("zero", "constant")).evaluateAt(2015.5), 0.0);
        assertEquals(0.5, fn(piecewise("constant", "constant")).evaluateAt(2015.5), 0.0);
        // Extrapolated backwards along the line through the first two rows.
        assertEquals(0.25, fn(piecewise("linear", "constant")).evaluateAt(2015.5), 0.0);
    }

    /** The three {@code after_last} settings, evaluated at 2018.5. */
    @Test
    public void piecewiseAfterLastHasThreeSettings() {
        assertEquals(0.0, fn(piecewise("zero", "zero")).evaluateAt(2018.5), 0.0);
        assertEquals(1.0, fn(piecewise("zero", "constant")).evaluateAt(2018.5), 0.0);
        // Forwards along the line through the last two rows, which is falling.
        assertEquals(0.5, fn(piecewise("zero", "linear")).evaluateAt(2018.5), 0.0);
    }

    /**
     * The three degenerate models, each of which upstream answers rather than rejects: no
     * rows at all is 0; one row is that row's factor on both sides, whatever
     * {@code before_first}/{@code after_last} say, because a line needs two points; and two
     * rows sharing an epoch return a factor instead of dividing by zero.
     */
    @Test
    public void piecewiseHandlesTheDegenerateModels() {
        assertEquals(0.0, fn(piecewise("linear", "linear", "")).evaluateAt(2015.5), 0.0);

        TimeFunction one = fn(piecewise("linear", "linear",
                "{\"epoch\": \"2016-01-01T00:00:00Z\", \"scale_factor\": 0.5}"));
        assertEquals(0.5, one.evaluateAt(2015.5), 0.0);
        assertEquals(0.5, one.evaluateAt(2016.5), 0.0);

        TimeFunction tied = fn(piecewise("linear", "linear",
                "{\"epoch\": \"2016-01-01T00:00:00Z\", \"scale_factor\": 0.5},"
                + "{\"epoch\": \"2016-01-01T00:00:00Z\", \"scale_factor\": 1.0}"));
        assertEquals(0.5, tied.evaluateAt(2015.5), 0.0);
        assertEquals(1.0, tied.evaluateAt(2016.5), 0.0);
    }

    /**
     * {@code evaluate_exponential}. The last row is the one worth having: past
     * {@code end_epoch} the factor freezes at its value <em>at</em> that epoch, so 2002 and
     * 2001 agree. Without the clamp the 2002 answer would be 1.63 instead of 1.79.
     */
    @Test
    public void exponentialRelaxesAndThenFreezesAtItsEndEpoch() {
        TimeFunction f = fn(EXPONENTIAL);
        assertEquals(0.0, f.evaluateAt(1999.99), 0.0);
        assertEquals(1.0, f.evaluateAt(2000.00), 0.0);
        assertEquals(1.0 + (3.0 - 1.0) * (1.0 - Math.exp(-(2000.50 - 2000.00) / 2.0)),
                f.evaluateAt(2000.50), 0.0);
        assertEquals(1.0 + (3.0 - 1.0) * (1.0 - Math.exp(-(2001.00 - 2000.00) / 2.0)),
                f.evaluateAt(2001.00), 0.0);
        assertEquals(1.0 + (3.0 - 1.0) * (1.0 - Math.exp(-(2001.00 - 2000.00) / 2.0)),
                f.evaluateAt(2002.00), 0.0);

        // And the clamp is not a no-op: the unclamped value at 2002 is a different number.
        assertTrue(f.evaluateAt(2002.00)
                != 1.0 + (3.0 - 1.0) * (1.0 - Math.exp(-(2002.00 - 2000.00) / 2.0)));

        // Without +end_epoch it keeps relaxing.
        TimeFunction open = fn("{\"type\": \"exponential\", \"parameters\": {"
                + "\"reference_epoch\": \"2000-01-01T00:00:00Z\","
                + "\"relaxation_constant\": 2.0,"
                + "\"before_scale_factor\": 0.0,"
                + "\"initial_scale_factor\": 1.0,"
                + "\"final_scale_factor\": 3.0}}");
        assertEquals(1.0 + (3.0 - 1.0) * (1.0 - Math.exp(-(2002.00 - 2000.00) / 2.0)),
                open.evaluateAt(2002.00), 0.0);
    }

    // -------------------------------------------------------------------- the refusals

    /** Each is upstream's own message, so a user grepping PROJ's source finds the same text. */
    @Test
    public void thePerFunctionRefusalsAreUpstreams() {
        assertRejected("{\"type\": \"bogus\", \"parameters\": {}}",
                "Unsupported type of time function: bogus");
        assertRejected(piecewise("bogus", "constant"), "Unsupported value for before_first");
        // Upstream's message for this one names the C++ field, not the JSON key.
        assertRejected(piecewise("zero", "bogus"), "Unsupported value for afterLast");
        assertRejected("{\"type\": \"exponential\", \"parameters\": {"
                + "\"reference_epoch\": \"2000-01-01T00:00:00Z\","
                + "\"relaxation_constant\": 0.0,"
                + "\"before_scale_factor\": 0.0,"
                + "\"initial_scale_factor\": 1.0,"
                + "\"final_scale_factor\": 3.0}}",
                "Invalid value for relaxation_constant");
        assertRejected("{\"type\": \"velocity\", \"parameters\": {}}", "reference_epoch");
        assertRejected("{\"type\": \"step\", \"parameters\": {\"step_epoch\": \"2000\"}}",
                "Wrong formatting / invalid date-time for 2000");
    }

    // -------------------------------------------------------------------- non-vacuity

    /**
     * The control. All six are evaluated at one instant just before 2000-01-01 and must give
     * four distinct answers — {@code constant} 1, {@code velocity} about &minus;0.01,
     * {@code step} 0, {@code reverse_step} &minus;1, {@code piecewise} 0 (it agrees with
     * {@code step} here, which is why the requirement is four and not six) and
     * {@code exponential} 0. If a dispatch branch ever built the wrong class, this fails even
     * though every assertion above would still pass on the class it did build.
     */
    @Test
    public void theSixFunctionsDisagreeAtOneInstant() {
        String[] definitions = {
            CONSTANT, VELOCITY, STEP, REVERSE_STEP, piecewise("zero", "constant"), EXPONENTIAL,
        };
        String[] expectedTypes = {
            "constant", "velocity", "step", "reverse_step", "piecewise", "exponential",
        };
        Set<Double> values = new HashSet<Double>();
        for (int i = 0; i < definitions.length; i++) {
            TimeFunction f = fn(definitions[i]);
            assertEquals("wrong class built for " + expectedTypes[i],
                    expectedTypes[i], f.type());
            values.add(Double.valueOf(f.evaluateAt(1999.99)));
        }
        assertEquals("the six must not collapse onto fewer answers than this", 4, values.size());
    }

    private void assertRejected(final String timeFunction, final String messageFragment) {
        try {
            fn(timeFunction);
            fail("expected a PipelineDefinitionException for: " + timeFunction);
        } catch (PipelineDefinitionException e) {
            assertEquals(timeFunction, PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}
