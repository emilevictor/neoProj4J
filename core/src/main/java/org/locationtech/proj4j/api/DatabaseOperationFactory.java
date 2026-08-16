/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.spi.DbAxis;
import org.locationtech.proj4j.spi.DbCoordinateSystem;
import org.locationtech.proj4j.spi.DbCrs;
import org.locationtech.proj4j.spi.DbCrsType;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbOperationStep;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Turns a coordinate operation held in an authority database into the PROJ pipeline string that runs
 * it.
 *
 * <p>{@link ProjDatabase#operation(String, String)} already answers "what does the authority say this
 * operation is": a method code, a parameter list in the authority's own units, and for a concatenated
 * operation an ordered list of steps with directions. Nothing turned that into something executable.
 * This class is that step, and it is the Java side of PROJ's {@code _exportToPROJString} for the
 * operation types listed below.
 *
 * <h2>What is supported, and why not more</h2>
 *
 * <table>
 * <caption>Handled operation kinds</caption>
 * <tr><th>kind</th><th>emitted as</th><th>upstream</th></tr>
 * <tr><td>{@code helmert_transformation}, EPSG method 1032, 1033, 1053, 1056</td>
 *     <td>{@code +proj=helmert} with 7 or 15 parameters</td>
 *     <td>{@code singleoperation.cpp:3584}</td></tr>
 * <tr><td>{@code other_transformation} whose method is {@code PROJ:PROJString}</td>
 *     <td>the stored string, verbatim</td>
 *     <td>{@code projbasedoperation.cpp:264}</td></tr>
 * <tr><td>{@code concatenated_operation}</td>
 *     <td>{@code +proj=pipeline} with one {@code +step} per step, {@code +inv} on reverse steps</td>
 *     <td>{@code concatenatedoperation.cpp}</td></tr>
 * </table>
 *
 * <p>Everything else is <b>refused by name</b> rather than approximated. Three refusals are worth
 * stating because they look like omissions and are not:
 *
 * <ul>
 * <li><b>The geographic-domain Helmert methods</b> (9606, 9607, 1035&ndash;1038, 1054, 1055, 1057,
 * 1058) are refused. Upstream wraps those in {@code +proj=cart} on both sides, plus a unit convert
 * and an axis swap, and possibly {@code +proj=push +v_3} &mdash; see
 * {@code setupPROJGeodeticSourceCRS} in {@code singleoperation.cpp}. That wrapper is where a
 * silently-wrong answer would come from, so it is not guessed at.</li>
 * <li><b>Geocentric translation (method 1031) is refused</b> even though it is a Helmert with three
 * parameters, because its <em>inverse</em> is not {@code +inv}: upstream negates the three
 * translations instead ({@code Transformation::inverseAsTransformation}, transformation.cpp). The
 * seven- and fifteen-parameter forms fall through to {@code InverseTransformation} and therefore do
 * take {@code +inv}, which is what the comment at transformation.cpp:1413 means by "for PROJ string,
 * we use the +inv flag so as to get perfect round-tripability".</li>
 * <li><b>{@code grid_transformation} is refused.</b> Mapping an EPSG grid method onto a proj4j grid
 * operator is a separate piece of work with its own failure mode (the wrong grid convention applied
 * silently), and the operations that need it in practice are expressed as {@code PROJ:PROJString}
 * rows anyway &mdash; every NKG grid step is.</li>
 * </ul>
 *
 * <h2>Units are converted, and only in the direction upstream converts them</h2>
 *
 * <p>The database returns each parameter in the authority's own unit: NKG's 2020 Helmerts are in
 * metres, arc-seconds and ppm, but EPSG:7941 (the ITRF2000 pivot) is in <b>millimetres,
 * milliarc-seconds, parts per billion, millimetres per year, milliarc-seconds per year and parts per
 * billion per year</b>. Reading those as though they were the PROJ units would be wrong by a factor
 * of a thousand in every slot. So each value is multiplied by its own
 * {@link DbUnit#conversionFactor()} and divided by the factor of the unit PROJ emits &mdash; the same
 * {@code value * srcFactor / dstFactor} that {@code common::Measure::convertToUnit} computes. A
 * parameter whose unit has no factor, or whose unit row is missing, makes this class refuse rather
 * than assume 1.0.
 *
 * <p>The divisors are PROJ's own compile-time constants ({@code src/iso19111/static.cpp} at 9.8.1),
 * not database rows, because that is what upstream divides by: it passes
 * {@code common::UnitOfMeasure::ARC_SECOND} and friends straight into
 * {@code parameterValueNumeric}. They differ from the database's own rows for the same units in the
 * last bit or two. The multiplier is the database row, with one exception that upstream applies and
 * this class copies &mdash; see {@link #srcFactor}.
 */
final class DatabaseOperationFactory {

    // PROJ's UnitOfMeasure constants, src/iso19111/static.cpp at 9.8.1, transcribed as the same
    // expressions so the doubles are identical rather than merely close.
    private static final double YEAR_SECONDS = 31556925.445;
    private static final double METRE = 1.0;
    private static final double DEGREE = Math.PI / 180.0;
    private static final double ARC_SECOND = Math.PI / 180.0 / 3600.0;
    private static final double PARTS_PER_MILLION = 1e-6;
    private static final double METRE_PER_YEAR = 1.0 / YEAR_SECONDS;
    private static final double ARC_SECOND_PER_YEAR = Math.PI / 180.0 / 3600.0 / YEAR_SECONDS;
    private static final double PPM_PER_YEAR = 1e-6 / YEAR_SECONDS;
    private static final double YEAR = YEAR_SECONDS;

    // EPSG parameter codes, src/proj_constants.h at 9.8.1 lines 509-523.
    private static final String X_TRANSLATION = "8605";
    private static final String Y_TRANSLATION = "8606";
    private static final String Z_TRANSLATION = "8607";
    private static final String X_ROTATION = "8608";
    private static final String Y_ROTATION = "8609";
    private static final String Z_ROTATION = "8610";
    private static final String SCALE_DIFFERENCE = "8611";
    private static final String RATE_X_TRANSLATION = "1040";
    private static final String RATE_Y_TRANSLATION = "1041";
    private static final String RATE_Z_TRANSLATION = "1042";
    private static final String RATE_X_ROTATION = "1043";
    private static final String RATE_Y_ROTATION = "1044";
    private static final String RATE_Z_ROTATION = "1045";
    private static final String RATE_SCALE_DIFFERENCE = "1046";
    private static final String REFERENCE_EPOCH = "1047";

    // EPSG method codes, src/proj_constants.h at 9.8.1 lines 411-483. Geocentric domain only.
    private static final String COORDINATE_FRAME_GEOCENTRIC = "1032";
    private static final String POSITION_VECTOR_GEOCENTRIC = "1033";
    private static final String TIME_DEPENDENT_POSITION_VECTOR_GEOCENTRIC = "1053";
    private static final String TIME_DEPENDENT_COORDINATE_FRAME_GEOCENTRIC = "1056";

    private DatabaseOperationFactory() {
    }

    /**
     * Emits the PROJ definition for one authority coordinate operation.
     *
     * @param db   the database to read; not null
     * @param urn  the identifier, whose {@link AuthorityUrn#authority()} and
     *             {@link AuthorityUrn#code()} are used verbatim
     * @return the PROJ string, or {@code null} if the database has no such operation
     * @throws CrsCreationException if the operation exists but cannot be expressed, with the reason
     *                              naming the kind or method that stopped it
     */
    static String projDefinition(ProjDatabase db, AuthorityUrn urn) {
        DbOperation op = db.operation(urn.authority(), urn.code());
        if (op == null) {
            return null;
        }
        if (op.kind() == DbObjectType.CONCATENATED_OPERATION) {
            return pipeline(db, op);
        }
        return leaf(db, op, false);
    }

    private static String pipeline(ProjDatabase db, DbOperation op) {
        List<DbOperationStep> steps = op.steps();
        if (steps.isEmpty()) {
            throw refuse(op, "it is a concatenated operation with no steps");
        }
        List<String> parts = new ArrayList<>(steps.size());
        for (DbOperationStep step : steps) {
            DbObjectRef ref = step.step();
            DbOperation leafOp = db.operation(ref.authName(), ref.code());
            if (leafOp == null) {
                throw refuse(op, "step " + step.stepNumber() + " names " + ref.authorityCode()
                        + ", which the database does not have");
            }
            if (leafOp.kind() == DbObjectType.CONCATENATED_OPERATION) {
                // Upstream forbids this: factory.cpp:6871 resolves each step with
                // allowConcatenated = false, so a nested concatenation cannot be built at all.
                throw refuse(op, "step " + step.stepNumber() + " (" + ref.authorityCode()
                        + ") is itself a concatenated operation, which the authority model does not"
                        + " allow as a step");
            }
            if (step.direction() == DbOperationStep.Direction.UNSPECIFIED) {
                // With no direction upstream runs ConcatenatedOperation::fixSteps, which infers one
                // by matching each step's CRSs against its neighbours' and may insert operations.
                // Inferring it here would be a guess about which end matches.
                throw refuse(op, "step " + step.stepNumber() + " (" + ref.authorityCode()
                        + ") has no declared direction, and inferring one from the step CRSs is"
                        + " upstream's ConcatenatedOperation::fixSteps, which is not ported");
            }
            parts.add(leaf(db, leafOp, step.direction() == DbOperationStep.Direction.REVERSE));
        }
        StringBuilder sb = new StringBuilder("+proj=pipeline");
        for (String part : parts) {
            sb.append(" +step ").append(part);
        }
        return sb.toString();
    }

    private static String leaf(ProjDatabase db, DbOperation op, boolean inverse) {
        if (op.isProjStringMethod()) {
            return projStringStep(op, inverse);
        }
        if (op.kind() == DbObjectType.HELMERT_TRANSFORMATION) {
            return (inverse ? "+inv " : "") + helmert(db, op);
        }
        throw refuse(op, "its method is " + describeMethod(op) + " and its kind is "
                + op.kind().dbName() + "; only helmert_transformation with an EPSG geocentric-domain"
                + " method (1032, 1033, 1053, 1056) and PROJ:PROJString are emitted");
    }

    /**
     * A {@code PROJ:PROJString} operation carries its own definition in the method name, so the
     * forward direction is a copy. Upstream re-ingests it through the formatter
     * ({@code PROJBasedOperation::_exportToPROJString}), which for a single step and no inversion is
     * the identity.
     */
    private static String projStringStep(DbOperation op, boolean inverse) {
        String def = op.methodName();
        if (def == null || def.trim().isEmpty()) {
            throw refuse(op, "its method is PROJ:PROJString but the method name, which is where the"
                    + " definition lives, is empty");
        }
        def = def.trim();
        if (!inverse) {
            return def;
        }
        if (def.contains("+proj=pipeline") || def.contains("+step")) {
            // Inverting a multi-step string is upstream's ingestPROJString plus its step-merging
            // optimiser, not a token flip. Refused rather than half-ported.
            throw refuse(op, "it is a reverse step whose PROJ:PROJString definition is itself a"
                    + " pipeline (" + def + "), and inverting a pipeline is upstream's"
                    + " ingestPROJString, which is not ported");
        }
        // Single step: the formatter's inversion state toggles the step's +inv flag.
        if (def.startsWith("+inv ")) {
            return def.substring(5);
        }
        if (def.equals("+inv")) {
            throw refuse(op, "its PROJ:PROJString definition is just \"+inv\"");
        }
        return "+inv " + def;
    }

    private static String helmert(ProjDatabase db, DbOperation op) {
        String method = op.methodCode();
        boolean positionVector;
        boolean timeDependent;
        if (!"EPSG".equals(op.methodAuthName())) {
            throw refuse(op, "its method authority is " + op.methodAuthName()
                    + ", and only EPSG Helmert methods are recognised");
        }
        if (POSITION_VECTOR_GEOCENTRIC.equals(method)) {
            positionVector = true;
            timeDependent = false;
        } else if (TIME_DEPENDENT_POSITION_VECTOR_GEOCENTRIC.equals(method)) {
            positionVector = true;
            timeDependent = true;
        } else if (COORDINATE_FRAME_GEOCENTRIC.equals(method)) {
            positionVector = false;
            timeDependent = false;
        } else if (TIME_DEPENDENT_COORDINATE_FRAME_GEOCENTRIC.equals(method)) {
            positionVector = false;
            timeDependent = true;
        } else {
            throw refuse(op, "its method is " + describeMethod(op) + ", which is not one of the four"
                    + " geocentric-domain Helmert methods (EPSG 1032, 1033, 1053, 1056). The"
                    + " geographic-domain forms need the +proj=cart wrapper upstream builds in"
                    + " setupPROJGeodeticSourceCRS, and geocentric translation (1031) inverts by"
                    + " negation rather than +inv, so neither is emitted");
        }
        requireGeocentricMetre(db, op, op.sourceCrs(), "source");
        requireGeocentricMetre(db, op, op.targetCrs(), "target");

        StringBuilder sb = new StringBuilder("+proj=helmert");
        param(sb, "x", value(db, op, X_TRANSLATION, METRE, DbUnit.Type.LENGTH));
        param(sb, "y", value(db, op, Y_TRANSLATION, METRE, DbUnit.Type.LENGTH));
        param(sb, "z", value(db, op, Z_TRANSLATION, METRE, DbUnit.Type.LENGTH));
        param(sb, "rx", value(db, op, X_ROTATION, ARC_SECOND, DbUnit.Type.ANGLE));
        param(sb, "ry", value(db, op, Y_ROTATION, ARC_SECOND, DbUnit.Type.ANGLE));
        param(sb, "rz", value(db, op, Z_ROTATION, ARC_SECOND, DbUnit.Type.ANGLE));
        param(sb, "s", value(db, op, SCALE_DIFFERENCE, PARTS_PER_MILLION, DbUnit.Type.SCALE));
        if (timeDependent) {
            param(sb, "dx", value(db, op, RATE_X_TRANSLATION, METRE_PER_YEAR, DbUnit.Type.LENGTH));
            param(sb, "dy", value(db, op, RATE_Y_TRANSLATION, METRE_PER_YEAR, DbUnit.Type.LENGTH));
            param(sb, "dz", value(db, op, RATE_Z_TRANSLATION, METRE_PER_YEAR, DbUnit.Type.LENGTH));
            param(sb, "drx",
                    value(db, op, RATE_X_ROTATION, ARC_SECOND_PER_YEAR, DbUnit.Type.ANGLE));
            param(sb, "dry",
                    value(db, op, RATE_Y_ROTATION, ARC_SECOND_PER_YEAR, DbUnit.Type.ANGLE));
            param(sb, "drz",
                    value(db, op, RATE_Z_ROTATION, ARC_SECOND_PER_YEAR, DbUnit.Type.ANGLE));
            param(sb, "ds", value(db, op, RATE_SCALE_DIFFERENCE, PPM_PER_YEAR, DbUnit.Type.SCALE));
            param(sb, "t_epoch", value(db, op, REFERENCE_EPOCH, YEAR, DbUnit.Type.TIME));
        }
        sb.append(" +convention=")
                .append(positionVector ? "position_vector" : "coordinate_frame");
        return sb.toString();
    }

    /**
     * Upstream emits nothing around a Helmert whose CRSs are geocentric in metres, because the
     * inverse of a unit conversion by 1.0 is a no-op ({@code setupPROJGeodeticSourceCRS}'s
     * {@code addGeocentricUnitConversionIntoPROJString} branch). Anything else and it emits a
     * conversion this class does not, so this class refuses instead of quietly dropping it.
     */
    private static void requireGeocentricMetre(ProjDatabase db, DbOperation op, DbObjectRef ref,
            String side) {
        if (ref == null) {
            throw refuse(op, "it has no " + side + " CRS");
        }
        DbCrs crs = db.crs(ref.authName(), ref.code());
        if (crs == null) {
            throw refuse(op, "its " + side + " CRS " + ref.authorityCode()
                    + " is not in the database");
        }
        if (crs.type() != DbCrsType.GEOCENTRIC) {
            throw refuse(op, "its " + side + " CRS " + ref.authorityCode() + " is "
                    + crs.type().dbValue() + ", not geocentric, so upstream wraps the Helmert in"
                    + " +proj=cart and a unit convert that this class does not emit");
        }
        DbObjectRef csRef = crs.coordinateSystem();
        DbCoordinateSystem cs = csRef == null ? null
                : db.coordinateSystem(csRef.authName(), csRef.code());
        if (cs == null) {
            throw refuse(op, "the coordinate system of its " + side + " CRS "
                    + ref.authorityCode() + " is not in the database");
        }
        if (cs.dimension() != 3) {
            throw refuse(op, "its " + side + " CRS " + ref.authorityCode() + " has "
                    + cs.dimension() + " axes, and a geocentric Helmert needs three");
        }
        for (DbAxis axis : cs.axes()) {
            DbUnit unit = axis.unit() == null ? null
                    : db.unit(axis.unit().authName(), axis.unit().code());
            if (unit == null || !unit.hasConversionFactor()) {
                throw refuse(op, "the unit of axis " + axis.abbreviation() + " of its " + side
                        + " CRS " + ref.authorityCode() + " has no conversion factor");
            }
            if (unit.conversionFactor() != 1.0) {
                throw refuse(op, "axis " + axis.abbreviation() + " of its " + side + " CRS "
                        + ref.authorityCode() + " is in " + unit.name() + ", not metres, so"
                        + " upstream emits a unit convert that this class does not");
            }
        }
    }

    /**
     * One parameter, converted from the authority's unit into the unit PROJ prints.
     *
     * <p>{@code value * srcFactor / dstFactor}, in that order, which is what
     * {@code Measure::convertToUnit} does ({@code getSIValue()} then divide).
     */
    private static double value(ProjDatabase db, DbOperation op, String epsgParamCode,
            double dstFactor, DbUnit.Type expectedType) {
        DbParam found = null;
        for (DbParam p : op.parameters()) {
            if ("EPSG".equals(p.authName()) && epsgParamCode.equals(p.code())) {
                if (found != null) {
                    throw refuse(op, "it declares EPSG parameter " + epsgParamCode + " twice");
                }
                found = p;
            }
        }
        if (found == null) {
            throw refuse(op, "it is missing EPSG parameter " + epsgParamCode
                    + ", which its method requires");
        }
        DbObjectRef unitRef = found.unit();
        DbUnit unit = unitRef == null ? null : db.unit(unitRef.authName(), unitRef.code());
        if (unit == null) {
            throw refuse(op, "the unit of parameter " + found.name() + " (EPSG:" + epsgParamCode
                    + ") is " + unitRef + ", which is not in the database, and assuming a factor"
                    + " of 1.0 would be a silent thousand-fold error on a millimetre row");
        }
        if (!unit.hasConversionFactor()) {
            throw refuse(op, "the unit " + unit.name() + " of parameter " + found.name()
                    + " (EPSG:" + epsgParamCode + ") has no conversion factor");
        }
        if (unit.type() != expectedType) {
            throw refuse(op, "parameter " + found.name() + " (EPSG:" + epsgParamCode + ") is in "
                    + unit.name() + ", a unit of type " + unit.type().dbValue() + ", where "
                    + expectedType.dbValue() + " was required");
        }
        return found.value() * srcFactor(unit) / dstFactor;
    }

    /**
     * The multiplier upstream uses for a stored unit, which is the stored conversion factor
     * <em>except</em> for two cases that {@code AuthorityFactory::createUnitOfMeasure} overrides
     * (factory.cpp:4638-4652 at 9.8.1).
     *
     * <p>The first is codes 9107 and 9108, the sexagesimal degree spellings, whose stored factor
     * upstream discards in favour of the degree constant. The second matters here: a stored factor
     * within a relative {@code 1e-10} of PROJ's own degree or arc-second constant is <b>replaced by
     * that constant</b>. EPSG:9104 stores {@code 4.8481368110953548e-06} while
     * {@code M_PI/180/3600} is {@code 4.8481368110953598e-06} &mdash; different doubles &mdash; so
     * without this substitution an arc-second value would be multiplied and divided by two slightly
     * different numbers and drift in the last two digits. With it, an arc-second parameter passes
     * through untouched, which is what {@code projinfo} prints.
     *
     * <p>Note the substitution deliberately does <b>not</b> reach the milliarc-second or
     * arc-second-per-year rows: they are nowhere near the two constants it tests, so those really do
     * get the stored factor over the compile-time divisor. That asymmetry is upstream's, and the
     * residual drift it leaves is what upstream's 14-digit print fallback absorbs
     * (see {@link #format}).
     */
    private static double srcFactor(DbUnit unit) {
        if ("EPSG".equals(unit.authName()) && ("9107".equals(unit.code())
                || "9108".equals(unit.code()))) {
            return DEGREE;
        }
        double f = unit.conversionFactor();
        final double eps = 1e-10;
        if (Math.abs(f - DEGREE) < eps * DEGREE) {
            return DEGREE;
        }
        if (Math.abs(f - ARC_SECOND) < eps * ARC_SECOND) {
            return ARC_SECOND;
        }
        return f;
    }

    private static void param(StringBuilder sb, String key, double v) {
        sb.append(" +").append(key).append('=').append(format(v));
    }

    /**
     * Formats a parameter the way {@code PROJStringFormatter::addParam(name, double)} does. Three
     * upstream steps, all of them load-bearing, all in io.cpp / internal.cpp at 9.8.1:
     *
     * <ol>
     * <li><b>Snap to one decimal</b> when the value is within {@code 1e-8} of one
     * ({@code formatToString}, io.cpp:10372). Upstream's own comment says this is what turns 55 grad
     * expressed as 49.500000000000004 degrees back into 49.5.</li>
     * <li><b>Print with C's {@code %.15g}</b> ({@code internal::toString}, internal.cpp:370). That is
     * 15 <em>significant</em> digits, with trailing zeros stripped, and an exponent when the decimal
     * exponent is below -4 or at least 15. So a rate of change prints as {@code 8.1e-05}, not
     * {@code 0.000081}. PROJ's own {@code +proj=helmert} parser reads either, but {@code projinfo}
     * prints the first, and this class is compared against {@code projinfo}.</li>
     * <li><b>Reprint with {@code %.14g}</b> if the 15-digit text contains the substring
     * {@code 9999999999} (internal.cpp:376). This is the step that makes the whole scheme work.
     * EPSG:7941's {@code rate_rx} is 0.081 milliarc-seconds per year; the milliarc-second-per-year
     * row and the arc-second-per-year constant are not exactly 1000 apart as doubles, so the quotient
     * is 8.0999999999999922e-05 and {@code %.15g} gives {@code 8.09999999999999e-05}. The fallback
     * turns that into {@code 8.1e-05}. Without it, roughly a third of the Helmert parameters in the
     * NKG family print a visibly different number from upstream while being the same double.</li>
     * </ol>
     *
     * <p>{@link BigDecimal} does the rounding rather than {@link String#format}, because
     * {@code String.format("%.14e", …)} rounds the shortest decimal that identifies the double,
     * whereas C rounds the double's exact binary value. Those disagree exactly on the ties this
     * fallback exists to catch.
     */
    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new CrsCreationException(ErrorCause.INVALID_PARAM_VALUE,
                    "a Helmert parameter came out as " + v);
        }
        double val = v;
        // std::round upstream; Math.rint differs from it only on an exact .5, where neither can
        // satisfy the 1e-8 test, and where the snap therefore does not fire either way.
        if (Math.abs(val * 10 - Math.rint(val * 10)) < 1e-8) {
            val = Math.rint(val * 10) / 10;
        }
        if (val == 0.0) {
            // Also normalises -0.0, which upstream's negate() avoids producing at all.
            return "0";
        }
        String s = printG(val, 15);
        if (s.contains("9999999999")) {
            return printG(val, 14);
        }
        return s;
    }

    /**
     * C's {@code printf("%.<precision>g", val)} for a finite non-zero value.
     *
     * <p>The rule, from C99 7.19.6.1: round to {@code precision} significant digits, take the
     * resulting decimal exponent X, use scientific notation if {@code X < -4 || X >= precision} and
     * plain notation otherwise, then drop trailing zeros in the fraction and a bare trailing point.
     * The exponent carries at least two digits and always a sign.
     */
    private static String printG(double val, int precision) {
        BigDecimal r = new BigDecimal(val)
                .round(new MathContext(precision, RoundingMode.HALF_EVEN));
        int exponent = r.precision() - r.scale() - 1;
        if (exponent < -4 || exponent >= precision) {
            String mantissa = trim(r.movePointLeft(exponent));
            int abs = Math.abs(exponent);
            return mantissa + "e" + (exponent < 0 ? "-" : "+") + (abs < 10 ? "0" : "") + abs;
        }
        return trim(r);
    }

    private static String trim(BigDecimal b) {
        BigDecimal t = b.stripTrailingZeros();
        if (t.scale() < 0) {
            t = t.setScale(0);
        }
        return t.toPlainString();
    }

    private static String describeMethod(DbOperation op) {
        return op.methodAuthName() + ":" + op.methodCode()
                + (op.methodName() == null ? "" : " (" + op.methodName() + ")");
    }

    private static CrsCreationException refuse(DbOperation op, String why) {
        return new CrsCreationException(ErrorCause.UNSUPPORTED_OPERATION_METHOD,
                "cannot build a PROJ pipeline for " + op.authName() + ":" + op.code() + " ("
                        + op.name() + "): " + why);
    }
}
