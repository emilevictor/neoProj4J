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
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link TinShiftOperator}: the half of {@code +proj=tinshift} that is not arithmetic —
 * finding the model file, refusing one it cannot use, and turning "no triangle contains
 * this point" into a failure a caller can act on.
 *
 * <h2>What this pins, and what breaks without it</h2>
 *
 * <ul>
 * <li><b>A model that resolves but does not parse is refused at construction.</b> Delete
 *     this and a truncated or hand-edited model can become an operator that is built
 *     successfully and then transforms nothing — the shape of defect this project treats
 *     as the worst kind, because the caller gets a plausible coordinate and no
 *     signal.</li>
 * <li><b>A point outside every triangle throws rather than passing through.</b> With
 *     {@code fallback_strategy: none} the difference between "outside the model" and
 *     "the model says zero here" is the whole shift, which for a national model is
 *     hundreds of metres.</li>
 * <li><b>The message names the file, the triangle count and the fallback strategy.</b>
 *     Those three are what tell an operator whether they handed over the wrong model,
 *     the wrong point, or a model that needs a fallback strategy set.</li>
 * <li><b>Both sides stay {@link GieIoUnits#WHATEVER} even after a neighbour propagates
 *     its own units.</b> A triangulation's units are a property of its vertices; if
 *     {@code overrideUnits} ever started recording a neighbour's opinion, a degree-based
 *     model between two metre-based steps would be silently rescaled.</li>
 * </ul>
 *
 * <h2>The fixture</h2>
 *
 * <p>{@code proj4j-data/grids/tinshift_two_triangle_test.json} is a unit square split
 * into two triangles that carry <em>different</em> affine maps: triangle A, below the
 * diagonal, doubles both ordinates, while triangle B, above it, sends {@code (0,1)} to
 * {@code (0,3)}. So the expected values below are only reachable by locating the point
 * in the right triangle — a selector that always returned the first triangle would agree
 * on {@code (0.75, 0.25)} and be 0.5 out on {@code (0.25, 0.75)}. Every expected value
 * here is the barycentric interpolation worked out by hand from those four vertices, not
 * a number recorded from a previous run.
 */
public class TinShiftOperatorTest {

    /** The two-triangle unit square; see the class comment. */
    private static final String MODEL = "tinshift_two_triangle_test.json";

    /** Structurally valid apart from a triangle naming vertex 9 of a three-vertex list. */
    private static final String BROKEN_MODEL = "tinshift_bad_vertex_index_test.json";

    private static double[] coord(double x, double y) {
        return new double[] {x, y, 0.0, 0.0};
    }

    // ------------------------------------------------------------------ happy path

    @Test
    public void aResolvableModelIsReadAndItsShapeIsReportedBackToTheCaller() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);

        assertEquals("the +file= value is kept as written, for the error messages",
                MODEL, op.file());
        assertNotNull("a constructed operator has a parsed model", op.triangulation());
        assertEquals("the fixture is two triangles", 2, op.triangulation().triangleCount());
        assertEquals("no fallback_strategy member means FALLBACK_NONE",
                Triangulation.FALLBACK_NONE, op.triangulation().fallbackStrategy());
        assertEquals("tinshift file=" + MODEL, op.description());
        assertTrue("toString must name the file so a pipeline dump is diagnosable: "
                + op.toString(), op.toString().contains(MODEL));
    }

    /**
     * {@code (0.75, 0.25)} lies in triangle A, whose map doubles both ordinates.
     * {@code (0.25, 0.75)} lies in triangle B, which sends it to {@code (0.5, 2.0)} —
     * triangle A's map would say {@code (0.5, 1.5)}, so this row is what proves the point
     * is located rather than assumed.
     */
    @Test
    public void forwardInterpolatesInsideTheTriangleThatContainsThePoint() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);

        double[] inA = coord(0.75, 0.25);
        op.forward(inA);
        assertEquals("triangle A doubles x", 1.5, inA[0], 1e-12);
        assertEquals("triangle A doubles y", 0.5, inA[1], 1e-12);

        double[] inB = coord(0.25, 0.75);
        op.forward(inB);
        assertEquals("triangle B, not A: A would give 0.5 here too, so x cannot separate them",
                0.5, inB[0], 1e-12);
        assertEquals("triangle B stretches y to 2.0; triangle A would say 1.5",
                2.0, inB[1], 1e-12);
    }

    /**
     * The inverse locates the point in the <em>target</em> geometry and interpolates the
     * source columns, so a round trip is a real check on both directions rather than on
     * one of them run backwards.
     */
    @Test
    public void forwardThenInverseReturnsTheOriginalPoint() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);

        double[] c = coord(0.25, 0.75);
        op.forward(c);
        op.inverse(c);
        assertEquals(0.25, c[0], 1e-12);
        assertEquals(0.75, c[1], 1e-12);

        double[] d = coord(0.75, 0.25);
        op.forward(d);
        op.inverse(d);
        assertEquals(0.75, d[0], 1e-12);
        assertEquals(0.25, d[1], 1e-12);
    }

    /** A vertex is its own target: the mesh's corners pin the interpolation's endpoints. */
    @Test
    public void aVertexMapsToItsOwnTargetRow() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);

        double[] c = coord(0.0, 1.0);
        op.forward(c);
        assertEquals(0.0, c[0], 1e-12);
        assertEquals("vertex 3 declares target (0, 3)", 3.0, c[1], 1e-12);
    }

    /** The whole operator, reached the way a caller reaches it. */
    @Test
    public void aBareTinshiftStepBecomesAWorkingOneStepPipeline() {
        Pipeline p = new PipelineFactory().create("+proj=tinshift +file=" + MODEL);
        double[] out = p.forward(new double[] {0.75, 0.25, 0.0, 0.0});
        assertEquals(1.5, out[0], 1e-12);
        assertEquals(0.5, out[1], 1e-12);
    }

    // ------------------------------------------------------------------- failures

    /**
     * The point of the throw: {@code (5, 5)} is outside both triangles in both
     * geometries, and returning it unchanged would be indistinguishable from a model that
     * says the shift is zero there.
     */
    @Test
    public void aPointOutsideEveryTriangleFailsInBothDirections() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);

        assertOutside(op, true);
        assertOutside(op, false);
    }

    private void assertOutside(TinShiftOperator op, boolean forward) {
        String direction = forward ? "forward" : "inverse";
        try {
            double[] c = coord(5.0, 5.0);
            if (forward) {
                op.forward(c);
            } else {
                op.inverse(c);
            }
            fail(direction + " of (5, 5) must fail: it is outside every triangle, and "
                    + "returning it unchanged would report a zero shift");
        } catch (CrsTransformException e) {
            assertEquals("a caller must be able to branch on this without reading the text",
                    ErrorCause.COORDINATE_OUTSIDE_GRID, e.cause());
            String m = e.getMessage();
            assertTrue("the message must name the point: " + m, m.contains("(5.0, 5.0)"));
            assertTrue("the message must name the model file: " + m, m.contains(MODEL));
            assertTrue("the message must say how big the model is: " + m,
                    m.contains("2 triangles"));
            assertTrue("the message must say that no fallback was configured, which is the "
                    + "one setting that would have produced an answer: " + m,
                    m.contains("fallback_strategy=" + Triangulation.FALLBACK_NONE));
        }
    }

    /**
     * A model that resolves and then fails to parse must be refused when the operator is
     * built, not when the first row arrives.
     */
    @Test
    public void aModelThatResolvesButDoesNotParseIsRefusedWithTheReason() {
        try {
            TinShiftOperator.fromFile(BROKEN_MODEL);
            fail("a triangle naming vertex 9 of a three-vertex list is not a usable model");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
            assertTrue("the message must name the problem, not just the file: " + e.getMessage(),
                    e.getMessage().contains("Invalid value for a vertex index"));
        }
    }

    @Test
    public void aMissingFileParameterIsRefusedRatherThanDefaulted() {
        assertRefused(null, "+file= should be specified.", PipelineErrorCode.MISSING_ARG);
        assertRefused("", "+file= should be specified.", PipelineErrorCode.MISSING_ARG);
    }

    /**
     * The message has to say <em>where</em> it looked, because the commonest cause is a
     * model sitting in the working directory — which this resolver chain deliberately
     * never consults.
     */
    @Test
    public void anUnresolvableFileNamesTheChainThatFailedToFindIt() {
        try {
            TinShiftOperator.fromFile("no_such_model_test.json");
            fail("an absent model must be refused at construction");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
            String m = e.getMessage();
            assertTrue(m, m.contains("Cannot open no_such_model_test.json"));
            assertTrue("the message must name the chain: " + m,
                    m.contains("Resolution chain was"));
            assertTrue("and must say the working directory is not searched, which is the "
                    + "first thing a user assumes: " + m,
                    m.contains("working directory is deliberately not searched"));
        }
    }

    /** A traversal attempt resolves to nothing rather than to a file outside the pack. */
    @Test
    public void aTraversingFileNameResolvesToNothing() {
        try {
            TinShiftOperator.fromFile("../../etc/passwd");
            fail("a +file= carrying .. must not be opened");
        } catch (PipelineDefinitionException e) {
            assertEquals(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("Cannot open ../../etc/passwd"));
        }
    }

    // ----------------------------------------------------------------------- units

    @Test
    public void bothSidesAreWhateverAndANeighboursOpinionDoesNotChangeThat() {
        TinShiftOperator op = TinShiftOperator.fromFile(MODEL);
        assertEquals(GieIoUnits.WHATEVER, op.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, op.declaredRight());

        op.overrideUnits(GieIoUnits.RADIANS, GieIoUnits.CARTESIAN);

        assertEquals("a triangulation's units come from its vertices, never from a neighbour",
                GieIoUnits.WHATEVER, op.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, op.declaredRight());
        assertTrue("tinshift is invertible", op.hasInverse());
    }

    private static void assertRefused(String fileName, String fragment,
                                      PipelineErrorCode expected) {
        try {
            TinShiftOperator.fromFile(fileName);
            fail("expected a PipelineDefinitionException for +file=" + fileName);
        } catch (PipelineDefinitionException e) {
            assertEquals(expected, e.code());
            assertTrue("message was: " + e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
