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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The parsed {@code +model=} file of {@code +proj=defmodel} — a port of
 * {@code MasterFile}, {@code SpatialExtent}, {@code Epoch}, {@code Component} and the six
 * time functions from {@code 9.8.1:src/transformations/defmodel.hpp} and
 * {@code defmodel_impl.hpp}. The arithmetic that consumes it is in
 * {@link DefmodelEvaluator}; this class is only the file format.
 *
 * <h2>Every key is validated, even the ones nothing reads</h2>
 *
 * <p>Upstream reads {@code license}, {@code publication_date}, the {@code authority} block,
 * the {@code links} array and the four uncertainty keys, and uses none of them. But it
 * reads them through the same accessors as everything else, so a model whose
 * {@code license} is a number is <b>rejected</b>. That rejection is observable behaviour,
 * so the checks are kept here even where the value is thrown away — see
 * {@link #checkOptionalString}. What is <em>not</em> kept is a field to hold a value no
 * caller can ask for.
 *
 * <h2>Degrees and radians are both stored</h2>
 *
 * <p>An extent is compared against a coordinate whose units depend on whether
 * {@code definition_crs} is geographic, and that is not known while parsing. So
 * {@code SpatialExtent} keeps the bbox twice, in the file's degrees and in radians, and the
 * evaluator picks — {@code minxNormalized(bool)} ({@code defmodel.hpp:79-90}). For a
 * projected model the "degrees" pair is really metres and the radian pair is nonsense that
 * is never consulted.
 *
 * <h2>{@code PI} is written out, not taken from {@link Math#PI}</h2>
 *
 * <p>{@code defmodel_impl.hpp:53-55} defines its own {@code DEFMODEL_PI} and
 * {@code DegToRad}. The literal is the same value as {@link Math#PI}, but the conversion is
 * a single multiply by a compile-time constant, which is neither
 * {@code Math.toRadians} nor a divide by 180 — and this repository forbids the first for
 * exactly the reason that the three do not agree in the last bits.
 *
 * <p>Immutable after construction.
 *
 * @since 2.3.0
 */
final class DefmodelMasterFile {

    // defmodel_impl.hpp:39-51.
    static final String DEGREE = "degree";
    static final String METRE = "metre";
    static final String ADDITION = "addition";
    static final String GEOCENTRIC = "geocentric";
    static final String BILINEAR = "bilinear";
    static final String GEOCENTRIC_BILINEAR = "geocentric_bilinear";
    static final String NONE = "none";
    static final String HORIZONTAL = "horizontal";
    static final String VERTICAL = "vertical";
    static final String THREE_D = "3d";

    /** {@code DEG_TO_RAD_CONSTANT} ({@code defmodel_impl.hpp:54}). */
    private static final double DEG_TO_RAD_CONSTANT = 3.14159265358979323846 / 180.;

    /** {@code DEFMODEL_PI} ({@code defmodel_impl.hpp:53}). */
    static final double DEFMODEL_PI = 3.14159265358979323846;

    /** {@code DegToRad} ({@code defmodel_impl.hpp:55}). */
    static double degToRad(final double d) {
        return d * DEG_TO_RAD_CONSTANT;
    }

    // ------------------------------------------------------------ nested types

    /** How a component displaces a coordinate — {@code DisplacementType}. */
    enum DisplacementType {
        /** Contributes nothing; {@code forward} skips the component outright. */
        NONE,
        HORIZONTAL,
        VERTICAL,
        THREE_D;

        /**
         * {@code ComponentEx::getDisplacementType} ({@code defmodel_impl.hpp:191-199}).
         * Note that anything unrecognised maps to {@link #NONE} here, but
         * {@code Component::parse} has already refused it, so the fall-through is only
         * reachable for the literal string {@code "none"}.
         */
        static DisplacementType of(final String s) {
            if (DefmodelMasterFile.HORIZONTAL.equals(s)) {
                return HORIZONTAL;
            }
            if (DefmodelMasterFile.VERTICAL.equals(s)) {
                return VERTICAL;
            }
            if (DefmodelMasterFile.THREE_D.equals(s)) {
                return THREE_D;
            }
            return NONE;
        }
    }

    /** {@code SpatialExtent} — a bbox, held in both the file's units and radians. */
    static final class SpatialExtent {

        private final double minx;
        private final double miny;
        private final double maxx;
        private final double maxy;
        private final double minxRad;
        private final double minyRad;
        private final double maxxRad;
        private final double maxyRad;

        private SpatialExtent(final double minx, final double miny,
                              final double maxx, final double maxy) {
            this.minx = minx;
            this.miny = miny;
            this.maxx = maxx;
            this.maxy = maxy;
            this.minxRad = degToRad(minx);
            this.minyRad = degToRad(miny);
            this.maxxRad = degToRad(maxx);
            this.maxyRad = degToRad(maxy);
        }

        double minx(final boolean isGeographic) {
            return isGeographic ? minxRad : minx;
        }

        double miny(final boolean isGeographic) {
            return isGeographic ? minyRad : miny;
        }

        double maxx(final boolean isGeographic) {
            return isGeographic ? maxxRad : maxx;
        }

        double maxy(final boolean isGeographic) {
            return isGeographic ? maxyRad : maxy;
        }

        /** {@code SpatialExtent::parse} ({@code defmodel_impl.hpp:480-510}). */
        static SpatialExtent parse(final Map<String, Object> j) {
            final String type = PipelineJson.requiredString(j, "type");
            if (!"bbox".equals(type)) {
                throw PipelineJson.invalid("unsupported type of extent");
            }
            final Map<String, Object> parameters = PipelineJson.requiredObject(j, "parameters");
            final List<Object> bbox = PipelineJson.requiredArray(parameters, "bbox");
            if (bbox.size() != 4) {
                throw PipelineJson.invalid("bbox is not an array of 4 numeric elements");
            }
            final double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                if (!(bbox.get(i) instanceof Double)) {
                    throw PipelineJson.invalid("bbox is not an array of 4 numeric elements");
                }
                v[i] = ((Double) bbox.get(i)).doubleValue();
            }
            return new SpatialExtent(v[0], v[1], v[2], v[3]);
        }

        @Override
        public String toString() {
            return "[" + minx + ", " + miny + ", " + maxx + ", " + maxy + "]";
        }
    }

    /**
     * {@code Epoch} — an ISO 8601 instant and its decimal year.
     *
     * <p>The empty string is a legal epoch with a decimal year of zero: that is how
     * {@code exponential}'s optional {@code end_epoch} says "no end", and
     * {@link #isEmpty} is the test upstream writes as {@code toString().empty()}.
     */
    static final class Epoch {

        private final String text;
        private final double decimalYear;

        Epoch(final String dt) {
            this.text = dt;
            this.decimalYear = dt.isEmpty() ? 0.0 : iso8601ToDecimalYear(dt);
        }

        double toDecimalYear() {
            return decimalYear;
        }

        boolean isEmpty() {
            return text.isEmpty();
        }

        @Override
        public String toString() {
            return text;
        }

        private static final int[][] MONTH_TABLE = {
            {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31},
            {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31},
        };

        /**
         * {@code ISO8601ToDecimalYear} ({@code defmodel_impl.hpp:228-254}). Leap years
         * count; leap seconds do not, which is why a year is exactly 365 or 366 days long
         * and the fraction is seconds-since-1-January over that.
         *
         * <p>Upstream reads the string with
         * {@code sscanf("%04d-%02d-%02dT%02d:%02d:%02dZ")} and requires all six fields to
         * convert. {@code sscanf} is looser than the format string looks: {@code %04d}
         * accepts up to four digits, not exactly four, and a trailing {@code Z} that is
         * absent or is something else does not fail the conversion because the six numbers
         * have already been read. This port reproduces that looseness rather than the
         * stricter reading of the format, because a model file that PROJ accepts must not
         * be refused here.
         */
        private static double iso8601ToDecimalYear(final String dt) {
            final int[] f = new int[6];
            final char[] separators = {'-', '-', 'T', ':', ':'};
            int pos = 0;
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    // sscanf's literal characters must match, or the conversion count falls
                    // short of 6 and upstream throws.
                    if (pos >= dt.length() || dt.charAt(pos) != separators[i - 1]) {
                        throw wrongFormat(dt);
                    }
                    pos++;
                }
                final int limit = i == 0 ? 4 : 2;
                int digits = 0;
                int value = 0;
                boolean negative = false;
                if (pos < dt.length() && (dt.charAt(pos) == '-' || dt.charAt(pos) == '+')) {
                    // %d accepts a sign. A negative year fails the >= 1582 check below.
                    negative = dt.charAt(pos) == '-';
                    pos++;
                }
                while (digits < limit && pos < dt.length()
                        && dt.charAt(pos) >= '0' && dt.charAt(pos) <= '9') {
                    value = value * 10 + (dt.charAt(pos) - '0');
                    pos++;
                    digits++;
                }
                if (digits == 0) {
                    throw wrongFormat(dt);
                }
                f[i] = negative ? -value : value;
            }
            final int year = f[0];
            final int month = f[1];
            final int day = f[2];
            final int hour = f[3];
            final int min = f[4];
            final int sec = f[5];
            if (year < 1582 // Start of the Gregorian calendar.
                    || month < 1 || month > 12 || day < 1 || day > 31
                    || hour < 0 || hour >= 24 || min < 0 || min >= 60
                    || sec < 0 || sec >= 61) {
                throw wrongFormat(dt);
            }
            final boolean isLeapYear = ((year % 4) == 0 && (year % 100) != 0) || (year % 400) == 0;
            final int[] months = MONTH_TABLE[isLeapYear ? 1 : 0];
            int dayInYear = day - 1;
            for (int m = 1; m < month; m++) {
                dayInYear += months[m - 1];
            }
            if (day > months[month - 1]) {
                throw wrongFormat(dt);
            }
            return year + (dayInYear * 86400 + hour * 3600 + min * 60 + sec)
                    / (isLeapYear ? 86400. * 366 : 86400. * 365);
        }

        private static PipelineDefinitionException wrongFormat(final String dt) {
            return PipelineJson.invalid("Wrong formatting / invalid date-time for " + dt);
        }
    }

    /**
     * A component's scale factor as a function of the decimal year —
     * {@code Component::TimeFunction} and its six subclasses
     * ({@code defmodel_impl.hpp:624-707}).
     */
    abstract static class TimeFunction {

        private final String type;

        TimeFunction(final String type) {
            this.type = type;
        }

        /** @return the scale factor to multiply every offset of this component by */
        abstract double evaluateAt(double dt);

        final String type() {
            return type;
        }

        @Override
        public String toString() {
            return type;
        }
    }

    /** {@code constant}: always 1. The only time function with no {@code parameters}. */
    static final class ConstantTimeFunction extends TimeFunction {
        ConstantTimeFunction() {
            super("constant");
        }

        @Override
        double evaluateAt(final double dt) {
            return 1.0;
        }
    }

    /** {@code velocity}: years since {@code reference_epoch}, so negative before it. */
    static final class VelocityTimeFunction extends TimeFunction {
        private final Epoch referenceEpoch;

        VelocityTimeFunction(final Epoch referenceEpoch) {
            super("velocity");
            this.referenceEpoch = referenceEpoch;
        }

        @Override
        double evaluateAt(final double dt) {
            return dt - referenceEpoch.toDecimalYear();
        }
    }

    /** {@code step}: 0 strictly before {@code step_epoch}, 1 from it on. */
    static final class StepTimeFunction extends TimeFunction {
        private final Epoch stepEpoch;

        StepTimeFunction(final Epoch stepEpoch) {
            super("step");
            this.stepEpoch = stepEpoch;
        }

        @Override
        double evaluateAt(final double dt) {
            if (dt < stepEpoch.toDecimalYear()) {
                return 0.0;
            }
            return 1.0;
        }
    }

    /**
     * {@code reverse_step}: <b>&minus;1</b> strictly before {@code step_epoch}, 0 from it
     * on. Not the negation of {@code step} — both are zero on one side, and the sign is the
     * whole point: it expresses the same displacement measured from the other end.
     */
    static final class ReverseStepTimeFunction extends TimeFunction {
        private final Epoch stepEpoch;

        ReverseStepTimeFunction(final Epoch stepEpoch) {
            super("reverse_step");
            this.stepEpoch = stepEpoch;
        }

        @Override
        double evaluateAt(final double dt) {
            if (dt < stepEpoch.toDecimalYear()) {
                return -1.0;
            }
            return 0.0;
        }
    }

    /** One {@code (epoch, scale_factor)} row of a {@code piecewise} model. */
    static final class EpochScaleFactor {
        private final Epoch epoch;
        private final double scaleFactor;

        EpochScaleFactor(final Epoch epoch, final double scaleFactor) {
            this.epoch = epoch;
            this.scaleFactor = scaleFactor;
        }
    }

    /**
     * {@code piecewise}: linear interpolation between listed epochs, with
     * {@code before_first} and {@code after_last} choosing what happens outside.
     *
     * <p>Three details are easy to get wrong and are upstream's, not inventions here: an
     * empty model is 0 rather than an error; a single-row model behaves as
     * {@code constant} on both ends whatever {@code before_first}/{@code after_last} say,
     * because there is no second row to draw a line through; and two rows sharing an epoch
     * fall back to a scale factor rather than dividing by zero.
     */
    static final class PiecewiseTimeFunction extends TimeFunction {
        private final String beforeFirst;
        private final String afterLast;
        private final List<EpochScaleFactor> model;

        PiecewiseTimeFunction(final String beforeFirst, final String afterLast,
                              final List<EpochScaleFactor> model) {
            super("piecewise");
            this.beforeFirst = beforeFirst;
            this.afterLast = afterLast;
            this.model = model;
        }

        @Override
        double evaluateAt(final double dt) {
            if (model.isEmpty()) {
                return 0.0;
            }
            final double dt1 = model.get(0).epoch.toDecimalYear();
            if (dt < dt1) {
                if ("zero".equals(beforeFirst)) {
                    return 0.0;
                }
                if ("constant".equals(beforeFirst) || model.size() == 1) {
                    return model.get(0).scaleFactor;
                }
                final double f1 = model.get(0).scaleFactor;
                final double dt2 = model.get(1).epoch.toDecimalYear();
                final double f2 = model.get(1).scaleFactor;
                if (dt1 == dt2) {
                    return f1;
                }
                return (f1 * (dt2 - dt) + f2 * (dt - dt1)) / (dt2 - dt1);
            }
            for (int i = 1; i < model.size(); i++) {
                final double dtip1 = model.get(i).epoch.toDecimalYear();
                if (dt < dtip1) {
                    final double dti = model.get(i - 1).epoch.toDecimalYear();
                    final double fip1 = model.get(i).scaleFactor;
                    final double fi = model.get(i - 1).scaleFactor;
                    return (fi * (dtip1 - dt) + fip1 * (dt - dti)) / (dtip1 - dti);
                }
            }
            if ("zero".equals(afterLast)) {
                return 0.0;
            }
            final EpochScaleFactor last = model.get(model.size() - 1);
            if ("constant".equals(afterLast) || model.size() == 1) {
                return last.scaleFactor;
            }
            final EpochScaleFactor prev = model.get(model.size() - 2);
            final double dtnm1 = prev.epoch.toDecimalYear();
            final double fnm1 = prev.scaleFactor;
            final double dtn = last.epoch.toDecimalYear();
            final double fn = last.scaleFactor;
            if (dtnm1 == dtn) {
                return fn;
            }
            return (fnm1 * (dtn - dt) + fn * (dt - dtnm1)) / (dtn - dtnm1);
        }
    }

    /**
     * {@code exponential}: post-seismic relaxation. Before {@code reference_epoch} the
     * factor is {@code before_scale_factor} flat; after it the factor relaxes from
     * {@code initial_scale_factor} towards {@code final_scale_factor} with the given
     * constant, and freezes at {@code end_epoch} if one is given.
     */
    static final class ExponentialTimeFunction extends TimeFunction {
        private final Epoch referenceEpoch;
        private final Epoch endEpoch;
        private final double relaxationConstant;
        private final double beforeScaleFactor;
        private final double initialScaleFactor;
        private final double finalScaleFactor;

        ExponentialTimeFunction(final Epoch referenceEpoch, final Epoch endEpoch,
                                final double relaxationConstant, final double beforeScaleFactor,
                                final double initialScaleFactor, final double finalScaleFactor) {
            super("exponential");
            this.referenceEpoch = referenceEpoch;
            this.endEpoch = endEpoch;
            this.relaxationConstant = relaxationConstant;
            this.beforeScaleFactor = beforeScaleFactor;
            this.initialScaleFactor = initialScaleFactor;
            this.finalScaleFactor = finalScaleFactor;
        }

        @Override
        double evaluateAt(final double dtIn) {
            final double t0 = referenceEpoch.toDecimalYear();
            if (dtIn < t0) {
                return beforeScaleFactor;
            }
            double dt = dtIn;
            if (!endEpoch.isEmpty()) {
                dt = Math.min(dt, endEpoch.toDecimalYear());
            }
            return initialScaleFactor + (finalScaleFactor - initialScaleFactor)
                    * (1.0 - Math.exp(-(dt - t0) / relaxationConstant));
        }
    }

    /** {@code Component::SpatialModel} — which grid file, read how. */
    static final class SpatialModel {
        private final String interpolationMethod;
        private final String filename;

        SpatialModel(final String interpolationMethod, final String filename) {
            this.interpolationMethod = interpolationMethod;
            this.filename = filename;
        }

        String interpolationMethod() {
            return interpolationMethod;
        }

        String filename() {
            return filename;
        }
    }

    /** {@code Component} — one grid, one extent, one time function. */
    static final class Component {

        private final String description;
        private final SpatialExtent extent;
        private final String displacementType;
        private final SpatialModel spatialModel;
        private final TimeFunction timeFunction;

        private Component(final String description, final SpatialExtent extent,
                          final String displacementType, final SpatialModel spatialModel,
                          final TimeFunction timeFunction) {
            this.description = description;
            this.extent = extent;
            this.displacementType = displacementType;
            this.spatialModel = spatialModel;
            this.timeFunction = timeFunction;
        }

        SpatialExtent extent() {
            return extent;
        }

        String displacementType() {
            return displacementType;
        }

        SpatialModel spatialModel() {
            return spatialModel;
        }

        TimeFunction timeFunction() {
            return timeFunction;
        }

        /** The first line of {@code description}, plus the grid name — {@code shortName}. */
        String shortName() {
            final int nl = description.indexOf('\n');
            return (nl < 0 ? description : description.substring(0, nl))
                    + " (" + spatialModel.filename + ")";
        }

        /** {@code Component::parse} ({@code defmodel_impl.hpp:514-620}). */
        static Component parse(final Object node) {
            final Map<String, Object> j = PipelineJson.asObject(node, "component");
            final String description = PipelineJson.optionalString(j, "description");
            final SpatialExtent extent =
                    SpatialExtent.parse(PipelineJson.requiredObject(j, "extent"));
            final String displacementType = PipelineJson.requiredString(j, "displacement_type");
            if (!NONE.equals(displacementType) && !HORIZONTAL.equals(displacementType)
                    && !VERTICAL.equals(displacementType) && !THREE_D.equals(displacementType)) {
                throw PipelineJson.invalid("Unsupported value for displacement_type");
            }
            // Required, and required to be a string, but never read afterwards. Every model
            // in the corpus says "none"; upstream has no code that acts on any other value.
            PipelineJson.requiredString(j, "uncertainty_type");
            // Optional numbers, validated and discarded for the same reason.
            PipelineJson.optionalDouble(j, "horizontal_uncertainty");
            PipelineJson.optionalDouble(j, "vertical_uncertainty");

            final Map<String, Object> jSpatialModel =
                    PipelineJson.requiredObject(j, "spatial_model");
            PipelineJson.requiredString(jSpatialModel, "type");
            final String interpolationMethod =
                    PipelineJson.requiredString(jSpatialModel, "interpolation_method");
            if (!BILINEAR.equals(interpolationMethod)
                    && !GEOCENTRIC_BILINEAR.equals(interpolationMethod)) {
                throw PipelineJson.invalid("Unsupported value for interpolation_method");
            }
            final String filename = PipelineJson.requiredString(jSpatialModel, "filename");
            checkOptionalString(jSpatialModel, "md5_checksum");

            return new Component(description, extent, displacementType,
                    new SpatialModel(interpolationMethod, filename),
                    parseTimeFunction(PipelineJson.requiredObject(j, "time_function")));
        }

        private static TimeFunction parseTimeFunction(final Map<String, Object> j) {
            final String type = PipelineJson.requiredString(j, "type");
            if ("constant".equals(type)) {
                // Deliberately does NOT require a "parameters" object; upstream passes an
                // empty json() in this one case.
                return new ConstantTimeFunction();
            }
            final Map<String, Object> p = PipelineJson.requiredObject(j, "parameters");
            if ("velocity".equals(type)) {
                return new VelocityTimeFunction(
                        new Epoch(PipelineJson.requiredString(p, "reference_epoch")));
            }
            if ("step".equals(type)) {
                return new StepTimeFunction(
                        new Epoch(PipelineJson.requiredString(p, "step_epoch")));
            }
            if ("reverse_step".equals(type)) {
                return new ReverseStepTimeFunction(
                        new Epoch(PipelineJson.requiredString(p, "step_epoch")));
            }
            if ("piecewise".equals(type)) {
                final String beforeFirst = PipelineJson.requiredString(p, "before_first");
                if (!"zero".equals(beforeFirst) && !"constant".equals(beforeFirst)
                        && !"linear".equals(beforeFirst)) {
                    throw PipelineJson.invalid("Unsupported value for before_first");
                }
                final String afterLast = PipelineJson.requiredString(p, "after_last");
                if (!"zero".equals(afterLast) && !"constant".equals(afterLast)
                        && !"linear".equals(afterLast)) {
                    // Upstream's message names the C++ field, not the JSON key. Kept as
                    // written so a user grepping PROJ's source finds the same text.
                    throw PipelineJson.invalid("Unsupported value for afterLast");
                }
                final List<Object> jModel = PipelineJson.requiredArray(p, "model");
                final List<EpochScaleFactor> model =
                        new ArrayList<EpochScaleFactor>(jModel.size());
                for (int i = 0; i < jModel.size(); i++) {
                    final Map<String, Object> row =
                            PipelineJson.asObject(jModel.get(i), "model[] element");
                    model.add(new EpochScaleFactor(
                            new Epoch(PipelineJson.requiredString(row, "epoch")),
                            PipelineJson.requiredDouble(row, "scale_factor")));
                }
                return new PiecewiseTimeFunction(beforeFirst, afterLast,
                        Collections.unmodifiableList(model));
            }
            if ("exponential".equals(type)) {
                final Epoch referenceEpoch =
                        new Epoch(PipelineJson.requiredString(p, "reference_epoch"));
                final Epoch endEpoch = new Epoch(PipelineJson.optionalString(p, "end_epoch"));
                final double relaxationConstant =
                        PipelineJson.requiredDouble(p, "relaxation_constant");
                if (relaxationConstant <= 0.0) {
                    throw PipelineJson.invalid("Invalid value for relaxation_constant");
                }
                return new ExponentialTimeFunction(referenceEpoch, endEpoch, relaxationConstant,
                        PipelineJson.requiredDouble(p, "before_scale_factor"),
                        PipelineJson.requiredDouble(p, "initial_scale_factor"),
                        PipelineJson.requiredDouble(p, "final_scale_factor"));
            }
            throw PipelineJson.invalid("Unsupported type of time function: " + type);
        }

    }

    // ----------------------------------------------------------------- master

    private final String definitionCRS;
    private final String horizontalOffsetUnit;
    private final String horizontalOffsetMethod;
    private final SpatialExtent extent;
    private final Epoch timeExtentFirst;
    private final Epoch timeExtentLast;
    private final List<Component> components;

    private DefmodelMasterFile(final String definitionCRS, final String horizontalOffsetUnit,
                               final String horizontalOffsetMethod, final SpatialExtent extent,
                               final Epoch timeExtentFirst, final Epoch timeExtentLast,
                               final List<Component> components) {
        this.definitionCRS = definitionCRS;
        this.horizontalOffsetUnit = horizontalOffsetUnit;
        this.horizontalOffsetMethod = horizontalOffsetMethod;
        this.extent = extent;
        this.timeExtentFirst = timeExtentFirst;
        this.timeExtentLast = timeExtentLast;
        this.components = components;
    }

    String definitionCRS() {
        return definitionCRS;
    }

    String horizontalOffsetUnit() {
        return horizontalOffsetUnit;
    }

    String horizontalOffsetMethod() {
        return horizontalOffsetMethod;
    }

    SpatialExtent extent() {
        return extent;
    }

    Epoch timeExtentFirst() {
        return timeExtentFirst;
    }

    Epoch timeExtentLast() {
        return timeExtentLast;
    }

    List<Component> components() {
        return components;
    }

    /**
     * {@code MasterFile::parse} ({@code defmodel_impl.hpp:347-476}).
     *
     * @param text the model file, decoded as UTF-8
     * @return the parsed model
     * @throws PipelineDefinitionException {@code FILE_NOT_FOUND_OR_INVALID}, carrying
     *                                     {@code "invalid model: "} and upstream's own
     *                                     message, for every rejection
     */
    static DefmodelMasterFile parse(final String text) {
        final Object root = PipelineJson.parse(text);
        if (!(root instanceof Map)) {
            throw PipelineJson.invalid("Not an object");
        }
        final Map<String, Object> j = PipelineJson.asObject(root, "model");

        PipelineJson.requiredString(j, "file_type");
        PipelineJson.requiredString(j, "format_version");
        checkOptionalString(j, "name");
        checkOptionalString(j, "version");
        checkOptionalString(j, "license");
        checkOptionalString(j, "description");
        checkOptionalString(j, "publication_date");

        if (j.containsKey("authority")) {
            if (!(j.get("authority") instanceof Map)) {
                throw PipelineJson.invalid("authority is not a object");
            }
            final Map<String, Object> authority = PipelineJson.asObject(j.get("authority"),
                    "authority");
            checkOptionalString(authority, "name");
            checkOptionalString(authority, "url");
            checkOptionalString(authority, "address");
            checkOptionalString(authority, "email");
        }

        if (j.containsKey("links")) {
            if (!(j.get("links") instanceof List)) {
                throw PipelineJson.invalid("links is not an array");
            }
            final List<Object> links = PipelineJson.asArray(j.get("links"), "links");
            for (int i = 0; i < links.size(); i++) {
                if (!(links.get(i) instanceof Map)) {
                    throw PipelineJson.invalid("links[] item is not an object");
                }
                final Map<String, Object> link = PipelineJson.asObject(links.get(i), "links[]");
                checkOptionalString(link, "href");
                checkOptionalString(link, "rel");
                checkOptionalString(link, "type");
                checkOptionalString(link, "title");
            }
        }

        final String sourceCRS = PipelineJson.requiredString(j, "source_crs");
        PipelineJson.requiredString(j, "target_crs");
        final String definitionCRS = PipelineJson.requiredString(j, "definition_crs");
        if (!sourceCRS.equals(definitionCRS)) {
            // Upstream's limitation, not this port's: it would have to compose the
            // source-to-definition transformation itself, and it does not.
            throw PipelineJson.invalid("source_crs != definition_crs not currently supported");
        }
        checkOptionalString(j, "reference_epoch");
        checkOptionalString(j, "uncertainty_reference_epoch");

        final String horizontalOffsetUnit =
                PipelineJson.optionalString(j, "horizontal_offset_unit");
        if (!horizontalOffsetUnit.isEmpty() && !METRE.equals(horizontalOffsetUnit)
                && !DEGREE.equals(horizontalOffsetUnit)) {
            throw PipelineJson.invalid("Unsupported value for horizontal_offset_unit");
        }
        final String verticalOffsetUnit = PipelineJson.optionalString(j, "vertical_offset_unit");
        if (!verticalOffsetUnit.isEmpty() && !METRE.equals(verticalOffsetUnit)) {
            throw PipelineJson.invalid("Unsupported value for vertical_offset_unit");
        }
        checkOptionalString(j, "horizontal_uncertainty_type");
        checkOptionalString(j, "horizontal_uncertainty_unit");
        checkOptionalString(j, "vertical_uncertainty_type");
        checkOptionalString(j, "vertical_uncertainty_unit");
        final String horizontalOffsetMethod =
                PipelineJson.optionalString(j, "horizontal_offset_method");
        if (!horizontalOffsetMethod.isEmpty() && !ADDITION.equals(horizontalOffsetMethod)
                && !GEOCENTRIC.equals(horizontalOffsetMethod)) {
            throw PipelineJson.invalid("Unsupported value for horizontal_offset_method");
        }

        final SpatialExtent extent =
                SpatialExtent.parse(PipelineJson.requiredObject(j, "extent"));

        final Map<String, Object> jTimeExtent = PipelineJson.requiredObject(j, "time_extent");
        final Epoch first = new Epoch(PipelineJson.requiredString(jTimeExtent, "first"));
        final Epoch last = new Epoch(PipelineJson.requiredString(jTimeExtent, "last"));

        final List<Object> jComponents = PipelineJson.requiredArray(j, "components");
        final List<Component> components = new ArrayList<Component>(jComponents.size());
        for (int i = 0; i < jComponents.size(); i++) {
            final Component comp = Component.parse(jComponents.get(i));
            components.add(comp);
            if (HORIZONTAL.equals(comp.displacementType())
                    || THREE_D.equals(comp.displacementType())) {
                if (horizontalOffsetUnit.isEmpty()) {
                    throw PipelineJson.invalid("horizontal_offset_unit should be defined as "
                            + "there is a component with displacement_type = horizontal/3d");
                }
                if (horizontalOffsetMethod.isEmpty()) {
                    throw PipelineJson.invalid("horizontal_offset_method should be defined as "
                            + "there is a component with displacement_type = horizontal/3d");
                }
            }
            if (VERTICAL.equals(comp.displacementType())
                    || THREE_D.equals(comp.displacementType())) {
                if (verticalOffsetUnit.isEmpty()) {
                    throw PipelineJson.invalid("vertical_offset_unit should be defined as there "
                            + "is a component with displacement_type = vertical/3d");
                }
            }
            if (DEGREE.equals(horizontalOffsetUnit)
                    && !BILINEAR.equals(comp.spatialModel().interpolationMethod())) {
                throw PipelineJson.invalid("horizontal_offset_unit = degree can only be used "
                        + "with interpolation_method = bilinear");
            }
        }

        if (DEGREE.equals(horizontalOffsetUnit) && !ADDITION.equals(horizontalOffsetMethod)) {
            throw PipelineJson.invalid("horizontal_offset_unit = degree can only be used with "
                    + "horizontal_offset_method = addition");
        }

        return new DefmodelMasterFile(definitionCRS, horizontalOffsetUnit, horizontalOffsetMethod,
                extent, first, last, Collections.unmodifiableList(components));
    }

    /**
     * Reads an optional string purely to reject a non-string, which is what upstream's
     * {@code getOptString} does for the keys nothing goes on to use.
     *
     * @param object the containing object
     * @param key    the member name
     */
    private static void checkOptionalString(final Map<String, Object> object, final String key) {
        PipelineJson.optionalString(object, key);
    }

    @Override
    public String toString() {
        return "DefmodelMasterFile[definition_crs=" + definitionCRS
                + ", horizontal_offset_unit=" + horizontalOffsetUnit
                + ", horizontal_offset_method=" + horizontalOffsetMethod
                + ", extent=" + extent + ", components=" + components.size() + "]";
    }
}
