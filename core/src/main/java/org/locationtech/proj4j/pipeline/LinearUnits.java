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
 * {@code P->to_meter} for a step ({@code 9.8.1:src/init.cpp:668-700}).
 *
 * <h2>Who needs this, and why it is not each operator's business</h2>
 *
 * <p>Every operator whose declared sides are {@code PJ_IO_UNITS_CARTESIAN} is scaled by
 * the generic code around it: {@code fwd_finalize} multiplies all three ordinates by
 * {@code P->fr_meter} ({@code 9.8.1:src/fwd.cpp:128-137}) and {@code inv_prepare}
 * multiplies them by {@code P->to_meter} ({@code inv.cpp:66-73}). Unlike the
 * {@code PROJECTED} case, no false easting, northing or {@code +z_0} applies and there
 * is no separate vertical unit — so the whole of the generic behaviour for a
 * {@code CARTESIAN} operator is this one factor.
 *
 * <p>{@link CartOperator} and {@link TopocentricOperator} are both such operators, and
 * upstream's {@code helmert} and {@code molobadekas} are two more. Silently dropping the
 * scale is a factor-of-{@code to_meter} error reported as success, so each of them has
 * to apply it; having each of them <em>derive</em> it is how the four copies drift.
 *
 * <h2>The rules, which are init.cpp's and not obvious</h2>
 *
 * <ul>
 * <li>{@code +units} is looked up in the linear table and <b>wins over
 *     {@code +to_meter}</b> when both are given — it is not an error to give both.</li>
 * <li>An <em>angular</em> unit id is not a linear one: {@code +units=rad} resolves, but
 *     {@code p_is_linear} is 0, so it is refused rather than quietly used as 1.</li>
 * <li>{@code +to_meter} accepts a {@code num/den} ratio as well as a plain double.</li>
 * <li>Zero, negative and infinite are all errors.</li>
 * <li><b>{@code +units} resolves to PROJ's {@code to_meter} <em>string</em> column, not
 *     its {@code factor} column.</b> {@code :689} is {@code s = units[i].to_meter}
 *     followed by {@code pj_strtod(s)}, so {@code +units=us-ft} means exactly
 *     {@code strtod("0.304800609601219")} and <em>not</em> {@code 1200 / 3937.0}. The two
 *     disagree by 3 ulps. This reads {@link PipelineUnits.Resolution#toMeter()} for that
 *     reason; {@code factor()} belongs to {@code +proj=unitconvert} and using it here was
 *     a real 3-ulp parity gap on the five U.S. survey rows, silent on the other
 *     sixteen.</li>
 * </ul>
 *
 * <p>This holds inside a {@code +proj=pipeline} too, which is the whole reason the
 * distinction reaches this class: {@code pipeline.cpp:496} builds each step with
 * {@code pj_create_argv_internal}, and {@code create.cpp:304} hands that straight to
 * {@code pj_init_ctx_with_allow_init_epsg}. A step's {@code +units} is {@code init.cpp}'s,
 * measured — {@code cct -d 12 +proj=pipeline +step +proj=cart +ellps=GRS80 +units=us-ft}
 * is bit-identical to the same step unwrapped, and both differ from
 * {@code +to_meter=1200/3937}.
 *
 * <p>Stateless; not instantiable.
 */
final class LinearUnits {

    private LinearUnits() {
        throw new AssertionError("no instances");
    }

    /**
     * {@code init.cpp:668-700}.
     *
     * @param params the step's fully expanded parameter list
     * @return {@code P->to_meter}; {@code 1.0} when neither key is present
     * @throws PipelineDefinitionException on an unknown or non-linear {@code +units}, or
     *                                    a {@code +to_meter} that is not a positive
     *                                    finite number or ratio
     */
    static double toMeter(final ProjParams params) {
        final String units = params.value("units");
        if (units != null && !units.isEmpty()) {
            final PipelineUnits.Resolution u = PipelineUnits.resolve(units);
            if (!u.isKnown() || u.linear() != 1) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "unknown +units=" + units);
            }
            // toMeter(), not factor(): init.cpp:689 reads the string column. See the
            // class comment -- swapping these is wrong by 3 ulps on us-ft, us-yd, us-in,
            // us-ch and us-mi, and bit-identical on every other row.
            return u.toMeter();
        }
        final String raw = params.value("to_meter");
        if (raw == null || raw.isEmpty()) {
            return 1.0;
        }
        final double value = ratio(raw);
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+to_meter=" + raw + " must be a positive finite number");
        }
        return value;
    }

    /** {@code pj_units_ratio}: a plain double, or {@code num/den}. */
    private static double ratio(final String raw) {
        final int slash = raw.indexOf('/');
        try {
            if (slash < 0) {
                return Double.parseDouble(raw.trim());
            }
            final double den = Double.parseDouble(raw.substring(slash + 1).trim());
            if (den == 0.0) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+to_meter=" + raw + " has a zero denominator");
            }
            return Double.parseDouble(raw.substring(0, slash).trim()) / den;
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+to_meter=" + raw + " is not a number or a num/den ratio", e);
        }
    }
}
