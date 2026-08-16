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

import org.locationtech.proj4j.units.Angle;

/**
 * {@code pj_param} type {@code 'r'} — an angular parameter, read as degrees and
 * returned in radians.
 *
 * <h2>Why this is not {@link ProjParams#doubleValue}</h2>
 *
 * <p>{@code doubleValue} is {@code pj_param}'s type {@code 'd'}: {@code atof} and
 * nothing else, which is right for {@code +dh}, {@code +slope_lat}, {@code +h_0} and
 * every other plain number. The angular keys are different — {@code pj_init} reads
 * {@code +lon_0} and {@code +lat_0} with {@code pj_param(… "rlon_0")}, and the
 * {@code 'r'} case runs the value through {@code dmstor}
 * ({@code 9.8.1:src/dmstor.cpp}) before scaling to radians. So {@code +lat_0=46d55'N}
 * is a legal angle upstream and {@code Double.parseDouble} would throw on it.
 *
 * <h2>The one part of {@code dmstor} this does not do, and why it refuses instead</h2>
 *
 * <p>{@code dmstor} accepts a trailing {@code r} meaning "this value is already in
 * radians" ({@code +lat_0=0.8r}). {@link Angle#parse} does not, and rather than
 * silently reading {@code 0.8r} as something else it throws
 * {@code NumberFormatException}, which this class turns into an
 * {@link PipelineErrorCode#ILLEGAL_ARG_VALUE} naming the key. That is the fail-closed
 * direction: an angle misread by a factor of {@code 180/pi} is a plausible coordinate
 * reported as success, which is the outcome this project exists to prevent.
 *
 * <p>The gap has a measured corpus population of zero — no {@code r}-suffixed angular
 * value appears in any of the 42 active {@code .gie} files. {@code Angle}'s own Javadoc
 * records the other, opposite divergence: it is <em>more</em> permissive than
 * {@code dmstor} about {@code m} for minutes, also with a measured population of zero.
 *
 * <p>Stateless; not instantiable.
 */
final class StepAngle {

    private StepAngle() {
        throw new AssertionError("no instances");
    }

    /**
     * {@code pj_param(P->ctx, P->params, "r<key>").f}.
     *
     * @param params       the step's fully expanded parameter list
     * @param key          the parameter name, without the leading {@code +}
     * @param defaultValue returned in radians when the key is absent or has no value
     * @return the angle in radians
     * @throws PipelineDefinitionException if the value is present but not an angle
     */
    static double radians(final ProjParams params, final String key,
                          final double defaultValue) {
        final String raw = params.value(key);
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        final double degrees;
        try {
            degrees = Angle.parse(raw.trim());
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+" + key + "=" + raw + " is not an angle. Note that dmstor's trailing "
                            + "'r' for radians is deliberately not accepted here, because "
                            + "misreading it would be a factor-of-180/pi error reported as "
                            + "success", e);
        }
        return degrees * PipelineUnits.DEG_TO_RAD;
    }
}
