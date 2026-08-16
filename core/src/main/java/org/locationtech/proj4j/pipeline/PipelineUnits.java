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
 * PROJ's linear and angular unit tables ({@code 9.8.1:src/units.cpp}), <b>both
 * columns</b> of them.
 *
 * <h2>Two columns, and which key reads which</h2>
 *
 * <p>{@code PJ_UNITS} carries every conversion to metres twice, in two fields that do
 * not agree ({@code struct} at {@code 9.8.1:src/proj.h:258}, table at
 * {@code 9.8.1:src/units.cpp:14-27}):
 *
 * <pre>
 * {"us-ft", "0.304800609601219", "U.S. Surveyor's Foot", 1200 / 3937.0}
 *           ^^^^^^^^^^^^^^^^^^^ to_meter, a string       ^^^^^^^^^^^^ factor, a double
 * </pre>
 *
 * <p><b>Which one PROJ reads depends on the key, not on the unit.</b> So this class
 * carries both, and every caller has to pick deliberately:
 *
 * <table>
 * <caption>Which accessor a key must use</caption>
 * <tr><th>key</th><th>PROJ reads</th><th>accessor</th><th>PROJ source</th></tr>
 * <tr><td>{@code +units}</td><td>{@code to_meter}, the string</td>
 *     <td>{@link Resolution#toMeter()}</td><td>{@code 9.8.1:src/init.cpp:689}</td></tr>
 * <tr><td>{@code +vunits}</td><td>{@code to_meter}, the string</td>
 *     <td>{@link Resolution#toMeter()}</td><td>{@code 9.8.1:src/init.cpp:726}</td></tr>
 * <tr><td>{@code +xy_in}, {@code +xy_out}, {@code +z_in}, {@code +z_out}</td>
 *     <td>{@code factor}, the double</td><td>{@link Resolution#factor()}</td>
 *     <td>{@code 9.8.1:src/conversions/unitconvert.cpp:396-434}</td></tr>
 * </table>
 *
 * <p>{@code init.cpp:689} does {@code s = units[i].to_meter} and then {@code pj_strtod(s)}
 * with an optional {@code /denominator}, which is why the string column is transcribed
 * below <em>with its ratio spellings intact</em>: {@code dm} is {@code "1/10"} and
 * {@code us-in} is {@code "1/39.37"}, and {@code 1 / 39.37} is not the same double as
 * {@code 100 / 3937.0}. {@code get_unit_conversion_factor()} instead does
 * {@code return units[i].factor}.
 *
 * <p><b>Getting this backwards is a silent 3-ulp error, and it is invisible on sixteen
 * of the twenty-one rows.</b> The two columns differ on exactly five, all of them U.S.
 * survey units: {@code us-in} by 1 ulp, {@code us-ft} by 3, {@code us-yd} by 3,
 * {@code us-ch} by 1, {@code us-mi} by 1. Every other linear row and all three angular
 * rows are bit-identical, so a test written against {@code ft} or {@code m} passes
 * whichever column it reads. {@code PipelineUnitColumnTest} pins each key to its column.
 *
 * <h2>Why not {@code org.locationtech.proj4j.units.Units}</h2>
 *
 * <p>Because the <b>normalised name</b> is load bearing and proj4j's table does
 * not carry PROJ's. {@code unitconvert} sets {@code P->left}/{@code P->right} to
 * {@code RADIANS} or {@code DEGREES} only when the normalised name is exactly
 * {@code "Radian"} or {@code "Degree"} ({@code unitconvert.cpp:487-493, 510-516}),
 * and otherwise leaves the side {@code WHATEVER}. {@code grad} normalises to
 * {@code "Grad"}, which is neither — so
 * {@code +step +proj=unitconvert +xy_in=rad +xy_out=grad} leaves the pipeline's
 * right-hand side {@code WHATEVER}, and the gie comparator therefore measures the
 * <b>Euclidean</b> distance between two coordinates expressed in grads against a
 * tolerance written in metres. That is exactly what {@code gigs/5102.2.gie} does,
 * and it is deliberate upstream behaviour: reproducing it faithfully is required,
 * "fixing" it silently changes 38 expected values.
 *
 * <p>{@code org.locationtech.proj4j.units.Units} carries the <b>string</b> column only,
 * because the only key that reaches it is {@code +units} through {@code Proj4Parser}.
 * That makes it an independent second transcription of this class's
 * {@code LINEAR_TO_METER}, and {@code PipelineUnitColumnTest} asserts the two agree
 * bitwise on all 21 ids — so a typo in either has to be made twice to survive.
 *
 * <p>The difference between the columns is PROJ's own, and it is measurable in the
 * shipped 9.8.1 binary. Both legs below were run, and {@code +units=m} accepts while
 * {@code +units=rad} correctly errors, so the instrument discriminates:
 *
 * <pre>
 * $ echo "-134 55 0" | cct -d 12 +proj=geocent +ellps=GRS80 +units=us-ft
 *   -8356380.535945920274  ...            # the "0.304800609601219" string
 * $ echo "-134 55 0" | cct -d 12 +proj=geocent +ellps=GRS80 +to_meter=1200/3937
 *   -8356380.535945915617  ...            # the 1200/3937.0 factor
 * </pre>
 *
 * <p>Wrapping the same step in {@code +proj=pipeline +step …} changes neither number, so
 * a pipeline step's {@code +units} is {@code init.cpp}'s string reader and nothing else:
 * {@code pipeline.cpp:496} builds each step with {@code pj_create_argv_internal}, which
 * {@code create.cpp:304} forwards straight to {@code pj_init_ctx_with_allow_init_epsg}.
 * {@code +proj=unitconvert} is a different key on a different column, not a different
 * opinion about the same one.
 *
 * <p>{@code gigs/5103.2} versus {@code 5103.3} exist precisely to separate {@code ft}
 * from {@code us-ft}, and {@code UnitConvertOperatorTest.footAndUsSurveyFootAreNotTheSame}
 * pins {@code LINEAR_FACTORS}' {@code us-ft} at exactly {@code 1200 / 3937.0} with a
 * zero tolerance.
 *
 * <p>Stateless; not instantiable.
 */
final class PipelineUnits {

    /** {@code M_PI / 200}, spelled as {@code units.cpp:41} spells it. */
    static final double GRAD_TO_RAD = 0.015707963267948967;

    /** {@code DEG_TO_RAD} from {@code proj_internal.h}. */
    static final double DEG_TO_RAD = 0.017453292519943296;

    /**
     * {@code #define ARCSEC_TO_RAD (DEG_TO_RAD / 3600.0)}.
     *
     * <p>Defined identically, character for character, in
     * {@code 9.8.1:src/transformations/vertoffset.cpp:77} and
     * {@code 9.8.1:src/transformations/helmert.cpp:477} — the two places upstream needs
     * it. It lives here rather than privately in each operator because it is one shared
     * constant with one spelling, not an arithmetic form whose rounding is worth keeping
     * separate. Used by {@code +slope_lat}/{@code +slope_lon}, and by helmert's
     * {@code +rx}/{@code +ry}/{@code +rz}/{@code +theta} and their rate-of-change twins.
     */
    static final double ARCSEC_TO_RAD = DEG_TO_RAD / 3600.0;

    /** PROJ's normalised name for a unit whose id is unknown to both tables. */
    static final String UNKNOWN = null;

    private static final String[] LINEAR_IDS = {
        "km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi", "fath", "ch", "link",
        "us-in", "us-ft", "us-yd", "us-ch", "us-mi", "ind-yd", "ind-ft", "ind-ch",
    };

    /**
     * {@code PJ_UNITS.factor}, the {@code double} column, verbatim as C spells it —
     * what {@code +proj=unitconvert} reads. <b>Not</b> what {@code +units} reads.
     */
    private static final double[] LINEAR_FACTORS = {
        1000.0, 1.0, 0.1, 0.01, 0.001, 1852.0, 0.0254, 0.3048, 0.9144, 1609.344, 1.8288,
        20.1168, 0.201168, 100 / 3937.0, 1200 / 3937.0, 3600 / 3937.0, 79200 / 3937.0,
        6336000 / 3937.0, 0.91439523, 0.30479841, 20.11669506,
    };

    /**
     * {@code PJ_UNITS.to_meter}, the <b>string</b> column, as {@code pj_strtod} plus an
     * optional {@code /denominator} parses it — what {@code +units} and {@code +vunits}
     * read.
     *
     * <p>Each entry is written the way {@code units.cpp} writes the string, so the four
     * ratio spellings stay ratios: {@code "1/10"}, {@code "1/100"}, {@code "1/1000"} and
     * {@code us-in}'s {@code "1/39.37"}. That last one matters — {@code 1 / 39.37} is one
     * ulp below {@code 100 / 3937.0}, so rewriting it in the tidier form would silently
     * move it onto the other column.
     *
     * <p>Sixteen of these are bit-identical to {@code LINEAR_FACTORS}. The five that are
     * not are the U.S. survey rows at indices 13-17.
     */
    private static final double[] LINEAR_TO_METER = {
        1000, 1, 1 / 10.0, 1 / 100.0, 1 / 1000.0, 1852, 0.0254, 0.3048, 0.9144, 1609.344,
        1.8288, 20.1168, 0.201168, 1 / 39.37, 0.304800609601219, 0.914401828803658,
        20.11684023368047, 1609.347218694437, 0.91439523, 0.30479841, 20.11669506,
    };

    private static final String[] LINEAR_NAMES = {
        "Kilometer", "Meter", "Decimeter", "Centimeter", "Millimeter",
        "International Nautical Mile", "International Inch", "International Foot",
        "International Yard", "International Statute Mile", "International Fathom",
        "International Chain", "International Link", "U.S. Surveyor's Inch",
        "U.S. Surveyor's Foot", "U.S. Surveyor's Yard", "U.S. Surveyor's Chain",
        "U.S. Surveyor's Statute Mile", "Indian Yard", "Indian Foot", "Indian Chain",
    };

    private static final String[] ANGULAR_IDS = {"rad", "deg", "grad"};

    private static final double[] ANGULAR_FACTORS = {1.0, DEG_TO_RAD, GRAD_TO_RAD};

    /**
     * {@code pj_angular_units}' string column ({@code "1.0"},
     * {@code "0.017453292519943296"}, {@code "0.015707963267948967"}).
     *
     * <p>All three parse to the same doubles as {@link #ANGULAR_FACTORS}, because
     * {@code DEG_TO_RAD} and {@code GRAD_TO_RAD} are {@code #define}d as those exact
     * decimal literals ({@code proj_internal.h:1034}, {@code units.cpp:41}). The column
     * is transcribed anyway rather than aliased, so that {@link Resolution#toMeter()} is
     * total over the table and an angular row cannot become the one place the two
     * accessors silently share storage.
     *
     * <p>No key reads it: {@code +units} and {@code +vunits} search
     * {@code pj_list_linear_units()} only, and reject an angular id.
     */
    private static final double[] ANGULAR_TO_METER = {
        1.0, 0.017453292519943296, 0.015707963267948967,
    };

    private static final String[] ANGULAR_NAMES = {"Radian", "Degree", "Grad"};

    private PipelineUnits() {
        throw new AssertionError("no instances");
    }

    /**
     * One row of PROJ's unit table, or the "not a unit id" answer.
     *
     * <p>PROJ returns three things from one call through out-parameters: the
     * factor, whether the unit is linear, and the normalised name. All three are
     * needed at every call site, so they travel together — and so does the row's
     * <em>other</em> conversion field, because which of the two a caller wants depends on
     * the key it is resolving. See {@link #factor()} and {@link #toMeter()}.
     */
    static final class Resolution {

        /** {@code get_unit_conversion_factor} returning 0.0: not a known unit id. */
        static final Resolution NOT_A_UNIT = new Resolution(0.0, 0.0, -1, null);

        private final double factor;
        private final double toMeter;
        private final int linear;
        private final String normalisedName;

        private Resolution(final double factor, final double toMeter, final int linear,
                final String normalisedName) {
            this.factor = factor;
            this.toMeter = toMeter;
            this.linear = linear;
            this.normalisedName = normalisedName;
        }

        /**
         * PROJ's numeric fallback: {@code +xy_in=0.5} rather than a unit id. It
         * carries <b>no normalised name and no linearity</b>, because upstream
         * leaves {@code normalized_name} null and {@code p_is_linear} at its
         * initial {@code -1} on that path — so a raw factor can never raise a
         * step's unit domain above {@link GieIoUnits#WHATEVER}, and never triggers
         * the linear/angular consistency check.
         *
         * @param factor the multiplier, already validated as finite and non-zero
         * @return a nameless resolution
         */
        static Resolution rawFactor(final double factor) {
            // Both columns are the same number here: the user wrote the number, so there
            // is no table row and no string to reparse. Only +xy_in and friends accept a
            // raw factor at all, so toMeter() is unreachable on this path -- but leaving
            // it 0.0 would make it look like NOT_A_UNIT to isKnown()'s sibling checks.
            return new Resolution(factor, factor, -1, null);
        }

        /**
         * {@code PJ_UNITS.factor}, the {@code double} column.
         *
         * <p><b>Only {@code +proj=unitconvert}'s keys may read this</b> —
         * {@code +xy_in}, {@code +xy_out}, {@code +z_in}, {@code +z_out} — because
         * {@code get_unit_conversion_factor()} returns {@code units[i].factor}
         * ({@code 9.8.1:src/conversions/unitconvert.cpp:396-434}). For {@code +units} or
         * {@code +vunits} use {@link #toMeter()} instead; the two differ by up to 3 ulps
         * on the five U.S. survey rows and are identical on the other sixteen, so a
         * mix-up here is wrong and almost unobservable.
         *
         * @return the multiplier to the pivot unit (metres, or radians)
         */
        double factor() {
            return factor;
        }

        /**
         * {@code PJ_UNITS.to_meter}, the string column, already parsed.
         *
         * <p><b>This is the column {@code +units} and {@code +vunits} read</b>
         * ({@code 9.8.1:src/init.cpp:689} and {@code :726}), including on a
         * {@code +proj=pipeline} step, whose parameters go through the same
         * {@code pj_init_ctx} ({@code pipeline.cpp:496} to {@code create.cpp:304}). See
         * {@link #factor()} for the other column and why the choice matters.
         *
         * @return the multiplier to metres as {@code pj_strtod} yields it
         */
        double toMeter() {
            return toMeter;
        }

        /** @return {@code 1} linear, {@code 0} angular, {@code -1} unknown — PROJ's {@code p_is_linear}. */
        int linear() {
            return linear;
        }

        /** @return PROJ's normalised name, or {@code null} when the id is unknown. */
        String normalisedName() {
            return normalisedName;
        }

        /** @return whether this is a recognised unit id. */
        boolean isKnown() {
            return factor != 0.0;
        }
    }

    /**
     * {@code get_unit_conversion_factor()} ({@code unitconvert.cpp:396-434}):
     * linear table first, then angular. Ids are matched with {@code strcmp}, so the
     * comparison is case sensitive and exact.
     *
     * @param id a unit id, may be {@code null}
     * @return the resolution; {@link Resolution#NOT_A_UNIT} when unknown
     */
    static Resolution resolve(final String id) {
        if (id == null) {
            return Resolution.NOT_A_UNIT;
        }
        for (int i = 0; i < LINEAR_IDS.length; i++) {
            if (LINEAR_IDS[i].equals(id)) {
                return new Resolution(LINEAR_FACTORS[i], LINEAR_TO_METER[i], 1, LINEAR_NAMES[i]);
            }
        }
        for (int i = 0; i < ANGULAR_IDS.length; i++) {
            if (ANGULAR_IDS[i].equals(id)) {
                return new Resolution(ANGULAR_FACTORS[i], ANGULAR_TO_METER[i], 0, ANGULAR_NAMES[i]);
            }
        }
        return Resolution.NOT_A_UNIT;
    }
}
