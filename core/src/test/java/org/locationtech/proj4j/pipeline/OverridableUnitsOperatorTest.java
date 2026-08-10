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

import org.junit.Test;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code P->left} and {@code P->right} for the six operators that share
 * {@link OverridableUnitsOperator}.
 *
 * <h2>What this is guarding</h2>
 *
 * <p>The shared base defaults both sides to {@link GieIoUnits#WHATEVER}, which is right
 * for {@code affine}, {@code set}, {@code push} and {@code pop} and wrong for the other
 * three, each of which computes its sides from its own parameters. Nothing about the
 * default is visible at the call site of an operator that forgot to say otherwise: it
 * would simply declare {@code WHATEVER} and let the pipeline assembler hand it a
 * neighbour's units. The consequence is not an exception but a different unit metric in
 * the gie comparator and, for {@code cs2cs}, a skipped {@code fwd_prepare} — so the
 * per-operator values are pinned here rather than left to the corpus to notice.
 */
public class OverridableUnitsOperatorTest {

    private static final Registry REGISTRY = new Registry();

    // ------------------------------------------------ the WHATEVER-on-both-sides four

    @Test
    public void affineDeclaresWhateverOnBothSides() {
        AffineOperator op = new AffineOperator(ProjParams.parse("+proj=affine +xoff=1"));
        assertEquals(GieIoUnits.WHATEVER, op.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, op.declaredRight());
    }

    @Test
    public void setDeclaresWhateverOnBothSides() {
        SetOperator op = new SetOperator(ProjParams.parse("+proj=set +v_1=10"));
        assertEquals(GieIoUnits.WHATEVER, op.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, op.declaredRight());
    }

    @Test
    public void pushAndPopDeclareWhateverOnBothSides() {
        ProjParams params = ProjParams.parse("+proj=push +v_3");
        PushPopOperator push = new PushPopOperator(true, params, null);
        assertEquals(GieIoUnits.WHATEVER, push.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, push.declaredRight());

        PushPopOperator pop = new PushPopOperator(false, params, null);
        assertEquals(GieIoUnits.WHATEVER, pop.declaredLeft());
        assertEquals(GieIoUnits.WHATEVER, pop.declaredRight());
    }

    // ------------------------------------------------- the three that compute their own

    @Test
    public void axisswapRaisesBothSidesForAngularunits() {
        AxisSwapOperator op =
                new AxisSwapOperator(ProjParams.parse("+proj=axisswap +order=2,1 +angularunits"));
        assertEquals(GieIoUnits.RADIANS, op.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, op.declaredRight());
    }

    @Test
    public void unitconvertRaisesEachSideIndependently() {
        UnitConvertOperator op = new UnitConvertOperator(
                ProjParams.parse("+proj=unitconvert +xy_in=deg +xy_out=rad"));
        assertEquals(GieIoUnits.DEGREES, op.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, op.declaredRight());
    }

    /**
     * The three kernels declare three different pairs, and the right-hand one is what
     * separates them. {@code CLASSIC} rather than {@code PROJECTED} is deliberate: the
     * fold is {@code pj_right}'s job, not the operator's.
     */
    @Test
    public void cs2csDeclaresAPairPerKernel() {
        Cs2csOperator longlat = new Cs2csOperator(REGISTRY, ProjParams.parse("+proj=longlat"));
        assertEquals(GieIoUnits.RADIANS, longlat.declaredLeft());
        assertEquals(GieIoUnits.RADIANS, longlat.declaredRight());

        Cs2csOperator geocent = new Cs2csOperator(REGISTRY, ProjParams.parse("+proj=geocent"));
        assertEquals(GieIoUnits.RADIANS, geocent.declaredLeft());
        assertEquals(GieIoUnits.CARTESIAN, geocent.declaredRight());

        Cs2csOperator merc = new Cs2csOperator(REGISTRY, ProjParams.parse("+proj=merc"));
        assertEquals(GieIoUnits.RADIANS, merc.declaredLeft());
        assertEquals(GieIoUnits.CLASSIC, merc.declaredRight());
    }

    // --------------------------------------------------------------------- the override

    /**
     * {@code pipeline.cpp:583-618} only ever overrides both sides at once, and the
     * operators here accept it wholesale rather than merging it with what they declared.
     */
    @Test
    public void overrideReplacesBothSides() {
        SetOperator op = new SetOperator(ProjParams.parse("+proj=set +v_1=10"));
        op.overrideUnits(GieIoUnits.DEGREES, GieIoUnits.DEGREES);
        assertEquals(GieIoUnits.DEGREES, op.declaredLeft());
        assertEquals(GieIoUnits.DEGREES, op.declaredRight());
    }

    /**
     * An operator that computed its sides is not immune to the override — it simply is
     * never offered one, because the assembler asks only operators declaring
     * {@code WHATEVER} on both sides. Pinned so that the base class's single
     * implementation is known to reach the same fields the constructor wrote.
     */
    @Test
    public void overrideAlsoReplacesComputedSides() {
        UnitConvertOperator op = new UnitConvertOperator(
                ProjParams.parse("+proj=unitconvert +xy_in=deg +xy_out=rad"));
        op.overrideUnits(GieIoUnits.CARTESIAN, GieIoUnits.CARTESIAN);
        assertEquals(GieIoUnits.CARTESIAN, op.declaredLeft());
        assertEquals(GieIoUnits.CARTESIAN, op.declaredRight());
    }
}
