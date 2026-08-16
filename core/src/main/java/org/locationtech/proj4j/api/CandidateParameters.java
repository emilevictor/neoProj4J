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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Turns the coordinate operation the selector <em>chose</em> into the PROJ parameters the engine
 * will actually <em>run</em>.
 *
 * <h2>The defect this exists to close</h2>
 *
 * <p>{@link CrsOperation} used to select a published operation and then build its
 * {@link org.locationtech.proj4j.BasicCoordinateTransform} from the two CRSs exactly as they were
 * handed in. The chosen candidate was never an argument to anything that computed a coordinate: it
 * reached {@code selectedOperation()}, {@code accuracy()}, {@code areaOfUse()} and {@code describe()}
 * and stopped there. So the facade reported one operation and the engine ran whatever
 * {@code +datum=} happened to imply.
 *
 * <p>Measured, at Cheshire (lon &minus;2.0301713578021983, lat 53.35168607080468) into
 * {@code +proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000 +ellps=airy}:
 * <table>
 * <caption>OSGB36, what was reported against what ran</caption>
 * <tr><th>parameters</th><th>easting</th><th>northing</th></tr>
 * <tr><td>EPSG:1314 "OSGB36 to WGS 84 (6)", what selection <em>chose</em>
 *     ({@code 446.448,-125.157,542.06,0.15,0.247,0.842,-20.489})</td>
 *     <td>398089.000827863</td><td>383867.000380436</td></tr>
 * <tr><td>proj4j's {@code Datum.OSGB36} table, what the engine <em>ran</em>
 *     ({@code ...,0.1502,0.2470,0.8421,-20.4894})</td>
 *     <td>398089.003912952</td><td>383867.000589373</td></tr>
 * <tr><td>EPSG:7710 OSTN15, the operation PROJ 9.8.1 picks when the grid is present</td>
 *     <td>398088.964408128</td><td>383865.216031245</td></tr>
 * </table>
 * The first two differ by 3.1&nbsp;mm easting; the grid differs from either Helmert by
 * <b>1.784&nbsp;m</b> of northing. Both gaps are the same defect at two scales.
 *
 * <h2>What it will and will not express</h2>
 *
 * <p>The engine's datum model is PROJ.4's: every CRS carries a shift <em>to WGS 84</em>, and a pair
 * is transformed by composing one side's shift with the inverse of the other's. A published
 * operation therefore fits only when it runs between a CRS and the WGS 84 hub <em>in that
 * direction</em>. Everything else &mdash; a hub-to-CRS publication that would need the Helmert
 * inverted, a CRS-to-CRS operation with no hub at either end, Molodensky-Badekas, the
 * time-dependent methods, a NADCON pair the legacy grid reader cannot open &mdash; is
 * <b>refused by name</b> and left on the legacy path, where {@code engineDisagreement()} still
 * guards it. A silently approximated inverse is exactly the failure this library exists to
 * eliminate, so it is not offered.
 *
 * <h2>Units are the arithmetic risk</h2>
 *
 * <p>{@link DbParam#value()} is in the authority's own units and is never pre-converted;
 * {@code +towgs84=} wants metres, arc-seconds and parts per million. A rotation read as arc-seconds
 * when it was recorded in microradians is out by a factor of 4.85 and produces a plausible
 * coordinate in the right country. So every value goes through its declared
 * {@link DbUnit#conversionFactor()} to the SI base unit and then out to the {@code +towgs84}
 * unit, the unit's {@link DbUnit#type()} is checked against the parameter's role, and a unit with
 * no conversion factor (upstream leaves 11 of them null) refuses rather than defaulting to one.
 *
 * <p>Package-private, stateless, and every method is a pure function of its arguments.
 */
final class CandidateParameters {

    // ------------------------------------------------------------------ EPSG parameter codes

    /** X-axis translation, metres in {@code +towgs84}. */
    private static final String DX = "8605";
    private static final String DY = "8606";
    private static final String DZ = "8607";
    /** X-axis rotation, arc-seconds in {@code +towgs84}. */
    private static final String RX = "8608";
    private static final String RY = "8609";
    private static final String RZ = "8610";
    /** Scale difference, parts per million in {@code +towgs84}. */
    private static final String DS = "8611";

    private static final String[] THREE = {DX, DY, DZ};
    private static final String[] SEVEN = {DX, DY, DZ, RX, RY, RZ, DS};

    // ------------------------------------------------------------------ EPSG method codes
    //
    // Keyed on the code and never on the method name, for the reason DbParam's javadoc gives: the
    // name is display text. proj.db 9.8.1 in fact labels EPSG:1037 "Geocentric translations (geog3D
    // domain)", which is EPSG:1035's name -- 1037 is Position Vector. Matching on that name would
    // drop three rotations and leave a translation-only shift that looks entirely reasonable.

    /** Geocentric translations: geocentric, geog2D, geog3D. Three parameters, no rotations. */
    private static final String[] TRANSLATION_METHODS = {"1031", "9603", "1035"};

    /**
     * Position Vector: geocentric, geog2D, geog3D. This is {@code +towgs84}'s own convention, so the
     * rotations pass through with their published sign.
     */
    private static final String[] POSITION_VECTOR_METHODS = {"1033", "9606", "1037"};

    /**
     * Coordinate Frame rotation: geocentric, geog2D, geog3D. The same transformation as Position
     * Vector with the three rotations negated. PROJ's {@code +towgs84} has no
     * {@code convention=coordinate_frame} spelling, so the negation happens here and is stated in
     * the note rather than left for a reader to infer from a sign that looks wrong.
     */
    private static final String[] COORDINATE_FRAME_METHODS = {"1032", "9607", "1038"};

    /** {@code radians -> arc-seconds}. 648000/pi, written out so it is checkable by eye. */
    private static final double ARC_SECONDS_PER_RADIAN = 648000.0 / Math.PI;

    /** The EPSG codes of the WGS 84 hub: geographic 2D, geographic 3D, geocentric. */
    private static final String[] WGS84_HUB_CODES = {"4326", "4979", "4978"};

    private CandidateParameters() {
    }

    // ------------------------------------------------------------------ the result

    /**
     * The CRS pair the engine should actually be built from, together with one sentence saying why.
     *
     * <p>Exactly one of {@link #note} and {@link #refusal} is non-null. When it is {@link #refusal},
     * {@link #source} and {@link #target} are the originals, unchanged, and the caller stays on the
     * legacy path.
     */
    static final class Plan {

        private final CoordinateReferenceSystem source;
        private final CoordinateReferenceSystem target;
        private final String note;
        private final String refusal;

        private Plan(CoordinateReferenceSystem source, CoordinateReferenceSystem target,
                     String note, String refusal) {
            this.source = source;
            this.target = target;
            this.note = note;
            this.refusal = refusal;
        }

        CoordinateReferenceSystem source() {
            return source;
        }

        CoordinateReferenceSystem target() {
            return target;
        }

        /** Non-null iff the operation was expressed; says which side carries it and in what form. */
        String note() {
            return note;
        }

        /** Non-null iff it was not; names the specific thing that could not be expressed. */
        String refusal() {
            return refusal;
        }

        boolean rewritten() {
            return note != null;
        }
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Rewrites whichever side of the pair carries the datum shift so that {@code chosen} is the
     * operation the engine runs, or explains why it cannot be.
     *
     * @param source the source CRS as handed in
     * @param target the target CRS as handed in
     * @param chosen the operation selection settled on; may be null, which refuses
     * @param db     the authority database, for unit lookups; may be null, which refuses
     * @return the plan; never null
     */
    static Plan plan(Crs source, Crs target, CrsOperationCandidate chosen, ProjDatabase db) {
        CoordinateReferenceSystem s = source.legacy();
        CoordinateReferenceSystem t = target.legacy();
        if (chosen == null || db == null) {
            return new Plan(s, t, null, "no candidate or no database");
        }
        DbOperation op = chosen.operation();
        if (op == null) {
            return new Plan(s, t, null, "the candidate carries no authority operation record");
        }

        // +towgs84= on a CRS means, exactly, "this CRS to WGS 84". So a published operation fits
        // iff its TARGET is the hub, whichever way selection is using it; the engine composes one
        // side's shift with the inverse of the other's by itself. A publication that runs
        // hub-to-CRS does not fit, because installing it would need the Helmert inverted, and
        // inverting a seven-parameter Helmert is not the same transformation as negating its
        // parameters.
        //
        // isInverted() then says only which of OUR two CRSs the non-hub end is: forwards it is our
        // source, backwards it is our target.
        DbObjectRef publishedFrom = op.sourceCrs();
        DbObjectRef publishedTo = op.targetCrs();
        if (!isWgs84Hub(publishedTo) || isWgs84Hub(publishedFrom)) {
            return new Plan(s, t, null, cannotOrient(chosen, publishedFrom, publishedTo));
        }
        boolean onSource = !chosen.isInverted();

        Crs carrier = onSource ? source : target;
        CoordinateReferenceSystem carrierLegacy = onSource ? s : t;

        // Prefer the grid: it is what the authority ranked first and what PROJ would run.
        String[] rewritten;
        String how;
        String nadgrids = nadgridsToken(chosen);
        if (nadgrids != null) {
            rewritten = withShift(carrierLegacy, "nadgrids", nadgrids);
            how = "+nadgrids=" + nadgrids;
        } else {
            Helmert helmert = helmert(op, db);
            if (helmert.token == null) {
                return new Plan(s, t, null, helmert.refusal);
            }
            rewritten = withShift(carrierLegacy, "towgs84", helmert.token);
            how = "+towgs84=" + helmert.token
                    + (helmert.negatedRotations ? " (Coordinate Frame rotations negated into "
                            + "Position Vector convention)" : "");
        }
        if (rewritten == null) {
            return new Plan(s, t, null, "the " + (onSource ? "source" : "target")
                    + " CRS has no parameter list to rewrite, so " + chosen.authorityCode()
                    + " cannot be applied to it");
        }

        CoordinateReferenceSystem rebuilt;
        try {
            rebuilt = new CRSFactory().createFromParameters(carrierLegacy.getName(), rewritten);
        } catch (RuntimeException e) {
            return new Plan(s, t, null, "rewriting the " + (onSource ? "source" : "target")
                    + " CRS for " + chosen.authorityCode() + " produced a parameter list this "
                    + "library cannot parse (" + e.getMessage() + "): " + join(rewritten));
        }
        String shapeChange = ellipsoidMoved(carrierLegacy, rebuilt);
        if (shapeChange != null) {
            // The rewrite drops +datum= and has to restate its ellipsoid; if that restatement did
            // not land on the same figure, the shift would be applied on the wrong shape and the
            // error would be metre-scale and entirely plausible. Refuse instead.
            return new Plan(s, t, null, "rewriting the " + (onSource ? "source" : "target")
                    + " CRS for " + chosen.authorityCode() + " changed its ellipsoid: "
                    + shapeChange);
        }

        String note = "executing " + chosen.authorityCode() + " (" + chosen.name() + ") as "
                + how + " on the " + (onSource ? "source" : "target") + " CRS";
        return new Plan(onSource ? rebuilt : s, onSource ? t : rebuilt, note, null);
    }

    private static String cannotOrient(CrsOperationCandidate chosen, DbObjectRef from,
                                       DbObjectRef to) {
        return "selection chose " + chosen.authorityCode() + " (" + chosen.name() + "), published "
                + ref(from) + " -> " + ref(to)
                + (chosen.isInverted() ? " and used in reverse" : "")
                + ". The transformation engine's datum model expresses a shift only as "
                + "\"this CRS to WGS 84\", so an operation is executable only when it is published "
                + "with the WGS 84 hub (EPSG:4326, EPSG:4979 or EPSG:4978) as its target. Inverting "
                + "a seven-parameter Helmert is not the same transformation as negating its "
                + "parameters, so a hub-to-CRS publication is refused rather than approximated.";
    }

    private static String ref(DbObjectRef r) {
        return r == null ? "an unnamed CRS" : r.authorityCode();
    }

    private static boolean isWgs84Hub(DbObjectRef ref) {
        if (ref == null || !"EPSG".equals(ref.authName())) {
            return false;
        }
        for (int i = 0; i < WGS84_HUB_CODES.length; i++) {
            if (WGS84_HUB_CODES[i].equals(ref.code())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the grid form

    /**
     * The {@code +nadgrids=} token for a grid operation the legacy grid reader can actually open, or
     * null.
     *
     * <p>Every one of the four refusals here is a way to apply a shift that looks right and is not:
     * an operator the engine does not implement, half of a NADCON pair, a grid whose PROJ form runs
     * the opposite way, or a GeoTIFF this reader cannot parse.
     */
    private static String nadgridsToken(CrsOperationCandidate chosen) {
        List<GridInfo> grids = chosen.grids();
        if (grids.isEmpty()) {
            return null;
        }
        String file = null;
        for (int i = 0; i < grids.size(); i++) {
            GridInfo g = grids.get(i);
            if (!g.isAvailable()) {
                return null;
            }
            if (g.isInverseDirection()) {
                return null;
            }
            String method = g.projMethod().orElse(null);
            if (method != null && !"hgridshift".equals(method)) {
                return null;
            }
            String resolved = resolvedFileName(g);
            if (resolved == null || !legacyReaderCanOpen(resolved)) {
                return null;
            }
            if (file == null) {
                file = resolved;
            } else if (!file.equals(resolved)) {
                // Two slots satisfied by two different files: a NADCON .las/.los pair that did not
                // collapse onto one GeoTIFF. +nadgrids= is a fallback chain, not a pair, so writing
                // both would apply the latitude shift twice and the longitude shift never.
                return null;
            }
        }
        return file;
    }

    /**
     * The file a slot actually resolved to. {@link GridInfo#satisfiedBy()} carries a trailing
     * {@code " -- reason"} for a slot covered by an earlier one, which is prose for a human and not
     * part of the name.
     */
    private static String resolvedFileName(GridInfo g) {
        String satisfied = g.satisfiedBy().orElse(null);
        if (satisfied == null) {
            return g.name();
        }
        int sep = satisfied.indexOf(" -- ");
        return sep < 0 ? satisfied : satisfied.substring(0, sep);
    }

    /**
     * Whether {@link org.locationtech.proj4j.datum.Grid} can read this file: CTABLE V2, NTv1 and
     * NTv2 only. A {@code .tif} would resolve, be handed to {@code +nadgrids=}, fail to parse, and
     * &mdash; because {@code +nadgrids=} treats an unreadable grid as a grid to skip &mdash; leave
     * the coordinate unshifted with an operation name attached to it.
     */
    private static boolean legacyReaderCanOpen(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return !lower.endsWith(".tif") && !lower.endsWith(".tiff") && !lower.endsWith(".json");
    }

    // ------------------------------------------------------------------ the Helmert form

    /**
     * The outcome of {@link #helmert}. Package-private rather than private so that the unit
     * conversion can be tested one unit kind at a time: a rotation read in the wrong unit produces
     * a plausible coordinate in the right country, and that is not something an end-to-end
     * coordinate assertion reliably catches.
     */
    static final class Helmert {
        /** The {@code +towgs84=} value, or null iff {@link #refusal} is set. */
        String token;
        /** Why no token could be produced, or null. */
        String refusal;
        /** Whether Coordinate Frame rotations were negated into Position Vector convention. */
        boolean negatedRotations;
    }

    /**
     * The {@code +towgs84=} value for a parameterised operation, in metres, arc-seconds and parts
     * per million, or a named refusal.
     */
    static Helmert helmert(DbOperation op, ProjDatabase db) {
        Helmert out = new Helmert();
        String methodAuth = op.methodAuthName();
        String methodCode = op.methodCode();
        if (!"EPSG".equals(methodAuth) || methodCode == null) {
            out.refusal = "operation " + op.ref().authorityCode() + " uses method "
                    + methodAuth + ":" + methodCode + ", which is not an EPSG method code, so its "
                    + "rotation sign convention cannot be established";
            return out;
        }

        boolean translationsOnly = contains(TRANSLATION_METHODS, methodCode);
        boolean positionVector = contains(POSITION_VECTOR_METHODS, methodCode);
        boolean coordinateFrame = contains(COORDINATE_FRAME_METHODS, methodCode);
        if (!translationsOnly && !positionVector && !coordinateFrame) {
            out.refusal = "operation " + op.ref().authorityCode() + " uses EPSG method "
                    + methodCode + " (" + op.methodName() + "), which +towgs84= cannot express. "
                    + "+towgs84= is exactly the three geocentric translations (EPSG 9603) and the "
                    + "seven-parameter Position Vector (9606) and Coordinate Frame (9607) forms "
                    + "with their geocentric and geog3D variants; Molodensky-Badekas carries a "
                    + "rotation pivot, and the time-dependent methods carry an epoch, neither of "
                    + "which has anywhere to go in a +towgs84 list";
            return out;
        }

        String[] wanted = translationsOnly ? THREE : SEVEN;
        Map<String, DbParam> byCode = new HashMap<String, DbParam>();
        List<DbParam> params = op.parameters();
        for (int i = 0; i < params.size(); i++) {
            DbParam p = params.get(i);
            if (!"EPSG".equals(p.authName())) {
                out.refusal = "operation " + op.ref().authorityCode() + " carries parameter "
                        + p.authName() + ":" + p.code() + ", not an EPSG parameter code, so it "
                        + "cannot be bound to a +towgs84 slot by identity";
                return out;
            }
            if (byCode.put(p.code(), p) != null) {
                out.refusal = "operation " + op.ref().authorityCode() + " carries EPSG parameter "
                        + p.code() + " twice";
                return out;
            }
        }
        // Exactly the expected set, no more: an extra parameter means this is not the method the
        // code claims, and dropping it silently is how a Molodensky-Badekas pivot goes missing.
        if (byCode.size() != wanted.length) {
            out.refusal = "operation " + op.ref().authorityCode() + " declares EPSG method "
                    + methodCode + ", which takes " + wanted.length + " parameters, but carries "
                    + byCode.size() + " (" + byCode.keySet() + ")";
            return out;
        }

        double[] values = new double[wanted.length];
        for (int i = 0; i < wanted.length; i++) {
            DbParam p = byCode.get(wanted[i]);
            if (p == null) {
                out.refusal = "operation " + op.ref().authorityCode() + " is missing EPSG "
                        + "parameter " + wanted[i] + ", so a +towgs84 slot would be defaulted to "
                        + "zero; the result would be a shift that is wrong by exactly the missing "
                        + "term and otherwise plausible";
                return out;
            }
            DbUnit.Type role = i < 3 ? DbUnit.Type.LENGTH
                    : i < 6 ? DbUnit.Type.ANGLE : DbUnit.Type.SCALE;
            double converted = toTowgs84Unit(p, role, db, out);
            if (out.refusal != null) {
                return out;
            }
            values[i] = converted;
        }

        if (coordinateFrame) {
            values[3] = -values[3];
            values[4] = -values[4];
            values[5] = -values[5];
            out.negatedRotations = true;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(format(values[i]));
        }
        out.token = sb.toString();
        return out;
    }

    /**
     * One parameter, from the authority's unit to {@code +towgs84}'s: metres for a translation,
     * arc-seconds for a rotation, parts per million for the scale difference.
     *
     * <p>Two hops, deliberately, rather than a direct table of unit-to-unit factors: the authority's
     * factor to the SI base unit is the only number the database actually holds, and the base unit
     * to {@code +towgs84}'s unit is a constant this file can state and a reader can check. A single
     * fused factor would hide which half was wrong.
     */
    private static double toTowgs84Unit(DbParam p, DbUnit.Type role, ProjDatabase db,
                                        Helmert out) {
        DbObjectRef unitRef = p.unit();
        if (unitRef == null) {
            out.refusal = "EPSG parameter " + p.code() + " (" + p.name() + ") has no unit, so its "
                    + "value " + p.value() + " cannot be converted; a value assumed to already be "
                    + "in " + towgs84UnitName(role) + " is a guess with no way to detect it";
            return 0.0;
        }
        DbUnit unit = db.unit(unitRef.authName(), unitRef.code());
        if (unit == null) {
            out.refusal = "EPSG parameter " + p.code() + " (" + p.name() + ") is in unit "
                    + unitRef.authorityCode() + ", which this database does not define";
            return 0.0;
        }
        if (unit.type() != role) {
            out.refusal = "EPSG parameter " + p.code() + " (" + p.name() + ") should be a "
                    + role.dbValue() + " but is declared in " + unit.name() + ", a "
                    + (unit.type() == null ? "unclassified" : unit.type().dbValue()) + " unit";
            return 0.0;
        }
        if (!unit.hasConversionFactor()) {
            out.refusal = "EPSG parameter " + p.code() + " (" + p.name() + ") is in unit "
                    + unit.name() + " (" + unitRef.authorityCode() + "), which has no conversion "
                    + "factor upstream. Defaulting it to 1.0 would be indistinguishable from a real "
                    + "factor of one";
            return 0.0;
        }
        // The unit is already +towgs84's own, so there is nothing to convert and converting anyway
        // would be worse than doing nothing. proj.db stores EPSG:9104 arc-second as
        // 4.84813681109535e-06, a truncation of pi/648000 = 4.8481368110953599e-06, so a round trip
        // out to radians and back would return 0.14999999999999983 for a published 0.15 -- a
        // systematic bias of about 2e-15 relative, and a +towgs84 string that no longer reads like
        // the authority's own numbers. Identified by unit CODE, never by name.
        if (isNativeTowgs84Unit(unitRef.code(), role)) {
            return p.value();
        }

        double si = p.value() * unit.conversionFactor();
        switch (role) {
            case LENGTH:
                // SI base is the metre and +towgs84 wants metres, so any length unit is one hop:
                // EPSG:9002 foot, 9003 US survey foot, 9036 kilometre.
                return si;
            case ANGLE:
                // SI base is the radian; +towgs84 wants arc-seconds. EPSG:9109 microradian is the
                // one that turns up in practice, and reading it as arc-seconds is a factor of
                // 4.848 -- a plausible coordinate in the right country.
                return si * ARC_SECONDS_PER_RADIAN;
            case SCALE:
                // SI base is unity -- a bare ratio -- and +towgs84 wants parts per million.
                // EPSG:9201 unity and 9203 coefficient land here.
                return si * 1.0e6;
            default:
                out.refusal = "EPSG parameter " + p.code() + " has unit type " + unit.type();
                return 0.0;
        }
    }

    /**
     * Whether this EPSG unit code <em>is</em> the unit {@code +towgs84} expects for this role, so
     * the value passes through untouched.
     *
     * @param unitCode the unit's EPSG code
     * @param role     which of the three {@code +towgs84} slots the parameter fills
     * @return true iff no conversion is needed
     */
    private static boolean isNativeTowgs84Unit(String unitCode, DbUnit.Type role) {
        switch (role) {
            case LENGTH:
                return "9001".equals(unitCode);   // metre
            case ANGLE:
                return "9104".equals(unitCode);   // arc-second
            case SCALE:
                return "9202".equals(unitCode);   // parts per million
            default:
                return false;
        }
    }

    private static String towgs84UnitName(DbUnit.Type role) {
        switch (role) {
            case LENGTH:
                return "metres";
            case ANGLE:
                return "arc-seconds";
            default:
                return "parts per million";
        }
    }

    // ------------------------------------------------------------------ parameter list surgery

    /**
     * The CRS's own parameter list with every existing datum-shift declaration removed and one new
     * one appended.
     *
     * <p>{@code +datum=} is dropped rather than left in place with the new token after it. proj4j's
     * {@code DatumParameters} lets whichever of {@code +datum} and {@code +towgs84} is applied last
     * win, so relying on order would make the fix depend on an implementation detail of the parser;
     * and {@code +datum=} also carries a {@code +nadgrids=} list of its own for NAD27 and potsdam,
     * which would then compete with the one being installed. Dropping it means the ellipsoid has to
     * be restated, and the caller verifies that it landed on the same figure.
     *
     * @return the new list, or null if there was nothing to rewrite
     */
    private static String[] withShift(CoordinateReferenceSystem crs, String key, String value) {
        String[] params = crs.getParameters();
        if (params == null) {
            return null;
        }
        List<String> out = new ArrayList<String>(params.length + 2);
        for (int i = 0; i < params.length; i++) {
            String p = params[i];
            if (Crs.declares(p, "datum") || Crs.declares(p, "towgs84")
                    || Crs.declares(p, "nadgrids")) {
                continue;
            }
            out.add(p);
        }
        if (!declaresAnyEllipsoid(out)) {
            String ellipsoid = ellipsoidParameters(crs);
            if (ellipsoid == null) {
                return null;
            }
            out.add(ellipsoid);
        }
        out.add("+" + key + "=" + value);
        return out.toArray(new String[out.size()]);
    }

    private static boolean declaresAnyEllipsoid(List<String> params) {
        for (int i = 0; i < params.size(); i++) {
            String p = params.get(i);
            if (Crs.declares(p, "ellps") || Crs.declares(p, "a") || Crs.declares(p, "R")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The dropped {@code +datum=}'s ellipsoid, restated. Its short name where the registry knows one,
     * because that is exact and readable; otherwise {@code +a=} and {@code +b=}, never {@code +rf=},
     * for the reason {@code CrsDefinitions} gives: the semi-minor axis needs no reciprocal.
     */
    private static String ellipsoidParameters(CoordinateReferenceSystem crs) {
        Datum datum = crs.getDatum();
        Ellipsoid e = datum == null ? null : datum.getEllipsoid();
        if (e == null) {
            return null;
        }
        String shortName = e.getShortName();
        if (shortName != null && !shortName.isEmpty() && !"unknown".equals(shortName)
                && !"user".equals(shortName)) {
            return "+ellps=" + shortName;
        }
        double a = e.getA();
        double b = e.getB();
        if (a <= 0.0 || b <= 0.0) {
            return null;
        }
        return "+a=" + a + " +b=" + b;
    }

    /**
     * Whether the rewrite changed the figure of the earth, described if so.
     *
     * <p>This is the self-check on {@link #ellipsoidParameters}: a short name the registry resolves
     * to a different ellipsoid than the datum held would move the coordinate by metres, and the
     * shift would still be applied and still be reported as the chosen operation.
     *
     * @return null when the figure is unchanged
     */
    private static String ellipsoidMoved(CoordinateReferenceSystem before,
                                         CoordinateReferenceSystem after) {
        Ellipsoid a = before.getDatum() == null ? null : before.getDatum().getEllipsoid();
        Ellipsoid b = after.getDatum() == null ? null : after.getDatum().getEllipsoid();
        if (a == null || b == null) {
            return a == b ? null : "one side has no ellipsoid at all";
        }
        if (Math.abs(a.getEquatorRadius() - b.getEquatorRadius()) > 1.0e-6) {
            return "semi-major axis " + a.getEquatorRadius() + " became " + b.getEquatorRadius();
        }
        if (Math.abs(a.getEccentricitySquared() - b.getEccentricitySquared()) > 1.0e-14) {
            return "eccentricity squared " + a.getEccentricitySquared() + " became "
                    + b.getEccentricitySquared();
        }
        return null;
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean contains(String[] haystack, String needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i].equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@code +towgs84} term. {@link Double#toString} is used deliberately: it is the shortest
     * decimal that round-trips to the same double, so the parser reads back the number the database
     * held, and {@code Locale} cannot turn the decimal point into a comma inside a comma-separated
     * list.
     */
    private static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0e15) {
            // 542.0 rather than 542.06 is a real difference; 542.0 rather than 542 is not, and the
            // shorter form is what the authority prints.
            long asLong = (long) v;
            return Long.toString(asLong);
        }
        return Double.toString(v);
    }

    private static String join(String[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }
}
