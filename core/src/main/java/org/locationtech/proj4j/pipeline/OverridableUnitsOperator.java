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

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code P->left} and {@code P->right} for the operators that let a neighbouring step
 * overwrite them.
 *
 * <p>The operators here split two ways. {@code cart}, {@code hgridshift},
 * {@code deformation} and {@code tinshift} declare fixed sides and ignore
 * {@code pipeline.cpp:583-618}'s override, so they answer from constants and are not
 * subclasses of this. The rest hold the pair in two mutable fields and let the override
 * land; those are the ones that extend this class, and before it existed each of the six
 * carried its own copy of the same two fields and same three accessors.
 *
 * <p>The default is {@link GieIoUnits#WHATEVER} on both sides, which is what a subclass
 * that never calls {@link #declareUnits} wants. A subclass whose sides depend on its
 * parameters calls {@code declareUnits} rather than passing them to a constructor,
 * because the values are only known after parameter validation has had its chance to
 * throw and a {@code super(...)} argument would have to be evaluated before that.
 *
 * <p>{@link #hasInverse()} is deliberately left to subclasses even though four of the six
 * answer a constant {@code true}: an operator that quietly inherits "yes, I invert"
 * because nobody remembered to say otherwise is the wrong failure direction.
 */
abstract class OverridableUnitsOperator implements PipelineOperator {

    private GieIoUnits left = GieIoUnits.WHATEVER;
    private GieIoUnits right = GieIoUnits.WHATEVER;

    @Override
    public final GieIoUnits declaredLeft() {
        return left;
    }

    @Override
    public final GieIoUnits declaredRight() {
        return right;
    }

    @Override
    public final void overrideUnits(final GieIoUnits newLeft, final GieIoUnits newRight) {
        this.left = newLeft;
        this.right = newRight;
    }

    /**
     * The sides as the subclass's own setup function computes them, replacing the
     * {@code WHATEVER} default.
     *
     * @param newLeft  {@code P->left}
     * @param newRight {@code P->right}
     */
    final void declareUnits(final GieIoUnits newLeft, final GieIoUnits newRight) {
        this.left = newLeft;
        this.right = newRight;
    }
}
