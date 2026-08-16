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

/**
 * {@code +proj=noop} ({@code 9.8.1:src/conversions/noop.cpp}), the whole of which is
 * fifteen lines: an empty {@code fwd4d}, the same empty {@code inv4d}, and
 * {@code PJ_IO_UNITS_WHATEVER} on both sides.
 *
 * <h2>Doing nothing is not the same as being absent</h2>
 *
 * <p>{@code +proj=noop} is what {@code proj_create_crs_to_crs} returns when the two CRSs
 * are the same, and what 9.8.1 answers for a deprecated transformation it refuses to
 * apply. An operation that <em>declines</em> to exist and an operation that runs and
 * changes nothing are different answers to a caller, and only the second one is this. So
 * this class exists rather than the factory special-casing the name and skipping the
 * step: a {@code noop} step is a real step, it appears in {@link Pipeline#steps()}, and
 * {@code +proj=pipeline +step +proj=noop +step +proj=noop} is a two-step pipeline.
 *
 * <h2>{@code WHATEVER} on both sides, and why the override matters</h2>
 *
 * <p>{@code noop} is the only operator in the corpus whose sides are both
 * {@code WHATEVER} and which carries no parameters at all, so it is the pure case of
 * {@code pipeline.cpp:583-618}: a neighbouring step's units are adopted wholesale, and
 * with no neighbour they stay {@code WHATEVER} and nothing is scaled. That is what makes
 * {@code more_builtins.gie}'s bare {@code +proj=noop} pass {@code 25 25} through
 * unchanged — a {@code RADIANS} declaration would have had gie convert degrees on the way
 * in and back out, which happens to be lossless here but would not be for a
 * {@code +proj=pipeline +step +proj=unitconvert +step +proj=noop}.
 *
 * <p>Hence {@link OverridableUnitsOperator} rather than fixed constants: the override
 * must land, even though this operator's arithmetic could not care less what units it is
 * handed.
 *
 * <p>Stateless, immutable and thread-safe apart from the mutable unit pair it inherits.
 *
 * @since 2.2.0
 */
final class NoopOperator extends OverridableUnitsOperator {

    /** {@code noop.cpp:7,10}: {@code static void noop(PJ_COORD &, PJ *) {}}. */
    @Override
    public void forward(final double[] coord) {
        // no operation, deliberately
    }

    /** {@code noop.cpp:7,11}: the same empty function is installed as {@code inv4d}. */
    @Override
    public void inverse(final double[] coord) {
        // no operation, deliberately
    }

    /** {@code inv4d} is non-null, so {@code pj_has_inverse} is true. */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return "noop";
    }

    @Override
    public String toString() {
        return "NoopOperator[]";
    }
}
