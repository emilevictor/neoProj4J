/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;

/**
 * The re-keying control for {@code Proj4jGieOperationFactory.mapConstructionThrowable}'s
 * {@code MISSING_GRID} arm, and the guard against that arm widening.
 *
 * <h2>What changed and why</h2>
 *
 * <p>The arm used to be a literal message-prefix match on {@code "Unknown nadgrid"}. That is the
 * message the <em>horizontal</em> path writes ({@code Proj4Parser.parseDatum:1348}); the vertical path writes
 * {@code "Unknown vertical grid"}, so {@code +proj=vgridshift +grids=<absent>} was classified
 * {@code NOT_IMPLEMENTED} — which {@code ExpectedFailureVerdict} never scores as genuine — even
 * though core had already declared the right thing, {@link ErrorCause#MISSING_GRID} at
 * {@code VGridShiftOperator:132}.
 *
 * <p>The sibling classifier in the same file, {@code pipelineKind}, had already been re-keyed onto
 * the cause (see {@link PipelineKindTest}). Two classifiers keyed on two different facts about the
 * same failure is how the gap survived, so this one now asks the cause too.
 *
 * <h2>The prefix test is kept, and this file pins why</h2>
 *
 * <p>It looks redundant once the cause is consulted. It is not:
 * {@code Proj4Parser.parseDatum:1348} wraps a failed {@code +nadgrids=} in an
 * {@link InvalidValueException},
 * whose {@code cause()} is {@link ErrorCause#INVALID_PARAM_VALUE}, <b>not</b>
 * {@link ErrorCause#MISSING_GRID}. Dropping the prefix would silently reclassify every horizontal
 * missing-grid row. {@link #theHorizontalPathIsCarriedByThePrefixAndNotByTheCause()} asserts both
 * halves of that, so the claim cannot rot into an assumption.
 *
 * <h2>Non-vacuity</h2>
 *
 * <p>Per the skill's non-negotiable 5c, {@link #thePrefixOnlyClassifierGetsTheVerticalCaseWrong()}
 * re-runs the identical question against the implementation as it stood before the change and
 * requires it to <em>disagree</em>. Without that leg, every assertion here would pass just as
 * cleanly against a classifier that returned {@code MISSING_GRID} unconditionally.
 */
class ConstructionThrowableKindTest {

    /** The arm as it stood before the re-keying, so the two can be compared rather than asserted. */
    private static boolean legacyPrefixOnlyIsMissingGrid(Throwable e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.startsWith("Unknown nadgrid");
    }

    private static boolean isMissingGrid(Throwable e) {
        GieFailure f = Proj4jGieOperationFactory.mapConstructionThrowable(e);
        assertNotNull(f, "mapConstructionThrowable refused to classify " + e);
        return f.kind() == GieFailureKind.MISSING_GRID;
    }

    private static void assertKind(GieFailureKind expected, String args) {
        GieOperation o = new Proj4jGieOperationFactory().create(args);
        assertFalse(o.isUsable(), "expected unusable, got a usable operation for " + args);
        assertNotNull(o.failure());
        assertEquals(expected, o.failure().kind(),
                args + ": wrong classification; message was: " + o.failure().message());
    }

    // ============================================ the vertical path, end to end

    /**
     * {@code more_builtins.gie:273}, which the manifest keys as {@code more_builtins.gie#22:0} and
     * which carries {@code expect failure errno invalid_op_file_not_found_or_invalid}. The grid is
     * genuinely absent from the resolver chain, so {@code MISSING_GRID} is the honest kind and the
     * row becomes a real pass rather than a vacuous one.
     */
    @Test
    @DisplayName("an absent vertical grid is MISSING_GRID, keyed on the cause not the message")
    void anAbsentVerticalGridIsMissingGrid() {
        assertKind(GieFailureKind.MISSING_GRID, "proj=vgridshift grids=nonexistinggrid.gtx");
    }

    /**
     * {@code geotiff_grids.gie:159} and {@code :165} — the manifest's {@code geotiff_grids.gie#20:0}
     * and {@code #21:0}. Neither {@code .tif} is vendored under
     * {@code conformance/src/test/resources/proj-data/tests/}, so proj4j cannot tell "invalid
     * channel type" from "absent" and does not have to: the errno these rows name is
     * {@code invalid_op_file_not_found_or_invalid}, whose own name covers both.
     */
    @Test
    @DisplayName("geotiff_grids' two unreadable vertical .tif operations are MISSING_GRID too")
    void theUnreadableVerticalGeotiffsAreMissingGrid() {
        assertKind(GieFailureKind.MISSING_GRID,
                "proj=vgridshift grids=tests/test_vgrid_invalid_channel_type.tif multiplier=1");
        assertKind(GieFailureKind.MISSING_GRID,
                "proj=vgridshift grids=tests/test_vgrid_unsupported_byte.tif multiplier=1");
    }

    /** The cause is what carries it: the message deliberately does not start with the old prefix. */
    @Test
    @DisplayName("the vertical throwable declares MISSING_GRID and says 'Unknown vertical grid'")
    void theVerticalThrowableCarriesTheCauseAndNotThePrefix() {
        CrsTransformException e = new CrsTransformException(ErrorCause.MISSING_GRID,
                "+grids=nonexistinggrid.gtx: Unknown vertical grid: nonexistinggrid.gtx");
        assertTrue(isMissingGrid(e), "the cause must classify it");
        assertFalse(legacyPrefixOnlyIsMissingGrid(e),
                "if the old prefix matched this, the change under test would be a no-op");
    }

    // ============================================ the horizontal path still works

    /**
     * The prefix arm is load-bearing. {@code Proj4Parser.parseDatum:1348} throws an
     * {@link InvalidValueException}, so its {@code cause()} is {@link ErrorCause#INVALID_PARAM_VALUE}
     * and the cause test alone would demote it to {@code NOT_IMPLEMENTED}. Re-causing that throw is
     * a core change with golden exposure; until then, both tests are needed and this pins it.
     */
    @Test
    @DisplayName("the horizontal path is carried by the prefix, because its cause is not MISSING_GRID")
    void theHorizontalPathIsCarriedByThePrefixAndNotByTheCause() {
        InvalidValueException e = new InvalidValueException("Unknown nadgrid: nope.gsb");
        assertEquals(ErrorCause.INVALID_PARAM_VALUE, e.cause(),
                "if this ever becomes MISSING_GRID, delete the prefix test and this assertion");
        assertTrue(isMissingGrid(e), "the prefix must still classify the horizontal path");
    }

    // ============================================ the guard against widening

    /**
     * The four rows that are correctly vacuous and must stay so. They are the <em>other</em> four
     * {@code errno invalid_op_file_not_found_or_invalid} rows in the corpus — the same errno as the
     * three that flip — and all four fail for an unrelated reason: {@code +proj=defmodel} and
     * {@code +proj=gridshift} are not in proj4j's {@code Registry}, so the refusal is a statement
     * about proj4j and not about a grid. If any of them moves, the arm has widened.
     *
     * <ul>
     *   <li>{@code defmodel.gie#1:0} / {@code #2:0} — {@code defmodel.gie:14} and {@code :18}</li>
     *   <li>{@code gridshift.gie#15:0} / {@code #16:0} — {@code gridshift.gie:284} and {@code :290}</li>
     * </ul>
     *
     * <p>Note the last three name a file. That is the point: the operator lookup fails before
     * anything looks at the file, so a grid-shaped definition is still not a grid failure.
     */
    @Test
    @DisplayName("the four unregistered-operator rows stay NOT_IMPLEMENTED, not MISSING_GRID")
    void unregisteredOperatorsAreNotMissingGrids() {
        assertKind(GieFailureKind.NOT_IMPLEMENTED, "proj=defmodel model=i_do_not_exist");
        assertKind(GieFailureKind.NOT_IMPLEMENTED, "proj=defmodel model=proj.ini");
        assertKind(GieFailureKind.NOT_IMPLEMENTED,
                "proj=gridshift grids=tests/test_vgrid_unsupported_byte.tif");
        assertKind(GieFailureKind.NOT_IMPLEMENTED,
                "proj=gridshift grids=tests/i_do_not_exist.tif");
    }

    /** Any other cause on a {@code Proj4jException} is still a proj4j gap, not a grid failure. */
    @Test
    @DisplayName("a non-grid cause on a Proj4jException is still NOT_IMPLEMENTED")
    void anUnrelatedCauseIsStillNotImplemented() {
        Proj4jException e = new InvalidValueException("Unknown projection: no_such_proj");
        assertFalse(isMissingGrid(e));
        assertEquals(GieFailureKind.NOT_IMPLEMENTED,
                Proj4jGieOperationFactory.mapConstructionThrowable(e).kind());
    }

    // ============================================ non-vacuity

    /**
     * The positive control. If this test ever passes trivially — i.e. if the legacy classifier and
     * the current one agree on the vertical case — then nothing above is measuring the change.
     */
    @Test
    @DisplayName("the prefix-only classifier really does get the vertical case wrong")
    void thePrefixOnlyClassifierGetsTheVerticalCaseWrong() {
        CrsTransformException vertical = new CrsTransformException(ErrorCause.MISSING_GRID,
                "+grids=nonexistinggrid.gtx: Unknown vertical grid: nonexistinggrid.gtx");
        assertNotEquals(legacyPrefixOnlyIsMissingGrid(vertical), isMissingGrid(vertical),
                "the two classifiers must disagree here, or the re-keying changed nothing");

        // ... and must agree on the horizontal case, so the change is a widening of one path
        // rather than a swap of one path for another.
        InvalidValueException horizontal = new InvalidValueException("Unknown nadgrid: nope.gsb");
        assertEquals(legacyPrefixOnlyIsMissingGrid(horizontal), isMissingGrid(horizontal),
                "the horizontal path must be classified identically before and after");
    }
}
