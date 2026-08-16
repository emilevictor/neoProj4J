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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbOperation;
import org.locationtech.proj4j.spi.DbParam;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;
import org.locationtech.proj4j.spi.StubProjDatabase;

/**
 * <strong>One test per unit kind for the authority-parameters-to-{@code +towgs84} conversion.</strong>
 *
 * <h2>Why this is the risky arithmetic in the whole stream</h2>
 *
 * <p>{@link DbParam#value()} is in the authority's own unit and is deliberately never
 * pre-converted, while {@code +towgs84=} is defined in metres, arc-seconds and parts per million.
 * A rotation of 0.842 arc-seconds read as microradians &mdash; or the reverse &mdash; is out by a
 * factor of 4.848, and the coordinate it produces is finite, in the right units, and in the right
 * country. There is no end-to-end coordinate assertion that reliably distinguishes "the shift is
 * slightly wrong" from "the rotations were read in the wrong unit", so each unit kind is checked
 * on its own here, against a value computed by hand.
 *
 * <h2>What is being compared against</h2>
 *
 * <p>The conversion factors are PROJ 9.8.1's own, read out of the shipped {@code proj.db}:
 * <pre>
 * sqlite3 proj.db "select code,name,type,conv_factor from unit_of_measure
 *                  where code in ('9001','9104','9202','9109','9105','9110')"
 *   9001|metre               |length|1.0
 *   9104|arc-second          |angle |4.84813681109535e-06
 *   9105|grad                |angle |0.0157079632679489
 *   9109|microradian         |angle |1.0e-06
 *   9110|sexagesimal DMS     |angle |            &lt;- null: one of the 11 with no defined ratio
 *   9202|parts per million   |scale |1.0e-06
 * </pre>
 */
public class CandidateParametersUnitsTest {

    // ---------------------------------------------------------------- EPSG parameter codes

    private static final String DX = "8605";
    private static final String DY = "8606";
    private static final String DZ = "8607";
    private static final String RX = "8608";
    private static final String RY = "8609";
    private static final String RZ = "8610";
    private static final String DS = "8611";

    private static final DbObjectRef OSGB36 = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG",
            "4277");
    private static final DbObjectRef WGS84 = new DbObjectRef(DbObjectType.GEODETIC_CRS, "EPSG",
            "4326");

    // ---------------------------------------------------------------- 1. the native units

    /**
     * <strong>Metres, arc-seconds and parts per million pass through untouched.</strong>
     *
     * <p>These are EPSG:1314's own values and they must come out reading exactly as the authority
     * published them. This is not cosmetic: a round trip out to the SI base unit and back would
     * return {@code 0.14999999999999983} for a published {@code 0.15}, because {@code proj.db}
     * stores arc-second as {@code 4.84813681109535e-06} and that is a truncation of
     * {@code pi/648000 = 4.8481368110953599e-06}. The bias is about 2e-15 relative and physically
     * nothing; the point is that a {@code +towgs84} string which no longer reads like the
     * authority's numbers cannot be checked by eye against {@code projinfo}, and that is how a real
     * unit error gets waved through.
     */
    @Test
    public void metresArcSecondsAndPartsPerMillionAreAlreadyTowgs84sUnits() {
        DbOperation op = positionVector(
                metres(DX, 446.448), metres(DY, -125.157), metres(DZ, 542.06),
                arcSeconds(RX, 0.15), arcSeconds(RY, 0.247), arcSeconds(RZ, 0.842),
                ppm(DS, -20.489));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        assertEquals("446.448,-125.157,542.06,0.15,0.247,0.842,-20.489", h.token);
    }

    // ---------------------------------------------------------------- 2. angle: microradian

    /**
     * <strong>Microradians (EPSG:9109) become arc-seconds, and the factor is 206264.806.</strong>
     *
     * <p>One microradian is {@code 1e-6 rad}, and {@code 1 rad = 648000/pi = 206264.80624709636}
     * arc-seconds, so a rotation published as {@code 4.0} microradians is {@code 0.825059224988385}
     * arc-seconds. Passing the {@code 4.0} straight through would understate the rotation by a
     * factor of 4.848 and every coordinate would still land in the right country.
     */
    @Test
    public void microradiansBecomeArcSeconds() {
        DbOperation op = positionVector(
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                microradians(RX, 4.0), microradians(RY, -5.0), microradians(RZ, 6.0),
                ppm(DS, 7.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        String[] terms = h.token.split(",");
        double expectedRx = 4.0 * 1.0e-6 * (648000.0 / Math.PI);
        assertEquals(0.825059224988385, expectedRx, 1.0e-15);
        assertEquals(expectedRx, Double.parseDouble(terms[3]), 1.0e-15);
        assertEquals(-expectedRx * 5.0 / 4.0, Double.parseDouble(terms[4]), 1.0e-15);
        assertEquals(expectedRx * 6.0 / 4.0, Double.parseDouble(terms[5]), 1.0e-15);

        // The positive control for this whole test: the raw value and the converted value are
        // genuinely far apart, so an implementation that skipped the conversion would fail here.
        assertTrue("4.0 microradians must not read as 4.0 arc-seconds",
                Math.abs(Double.parseDouble(terms[3]) - 4.0) > 3.0);
    }

    // ---------------------------------------------------------------- 3. angle: grad

    /**
     * <strong>Grads (EPSG:9105) become arc-seconds.</strong>
     *
     * <p>A grad is 1/400 of a turn, so 3240 arc-seconds. Included because it exercises the
     * two-hop path with a factor larger than one, where a sign or reciprocal error in the
     * conversion would show up as an enormous rotation rather than a tiny one.
     */
    @Test
    public void gradsBecomeArcSeconds() {
        DbOperation op = positionVector(
                metres(DX, 0.0), metres(DY, 0.0), metres(DZ, 0.0),
                grads(RX, 1.0), grads(RY, 0.0), grads(RZ, 0.0),
                ppm(DS, 0.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        double rx = Double.parseDouble(h.token.split(",")[3]);
        // 3240 exactly, up to proj.db's truncated grad factor 0.0157079632679489.
        assertEquals(3240.0, rx, 1.0e-6);
    }

    // ---------------------------------------------------------------- 4. length: US survey foot

    /**
     * <strong>A translation in US survey feet (EPSG:9003) becomes metres.</strong>
     *
     * <p>{@code 1 US survey foot = 1200/3937 m = 0.30480060960121924 m}. A translation read as
     * metres when it was recorded in feet is out by a factor of 3.28 &mdash; for a 500 m shift,
     * roughly 1.1 km, which is large but is still a finite coordinate in the right hemisphere.
     */
    @Test
    public void usSurveyFeetBecomeMetres() {
        DbOperation op = geocentricTranslations(
                usSurveyFeet(DX, 100.0), usSurveyFeet(DY, -200.0), usSurveyFeet(DZ, 300.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        String[] terms = h.token.split(",");
        assertEquals("a three-parameter method emits exactly three terms", 3, terms.length);
        assertEquals(100.0 * 1200.0 / 3937.0, Double.parseDouble(terms[0]), 1.0e-12);
        assertEquals(-200.0 * 1200.0 / 3937.0, Double.parseDouble(terms[1]), 1.0e-12);
        assertEquals(300.0 * 1200.0 / 3937.0, Double.parseDouble(terms[2]), 1.0e-12);
    }

    // ---------------------------------------------------------------- 5. scale: unity

    /**
     * <strong>A scale difference in unity (EPSG:9201) becomes parts per million.</strong>
     *
     * <p>{@code +towgs84}'s seventh term is ppm, so a published {@code 1.2e-5} unity is
     * {@code 12.0} ppm. Passing the {@code 1.2e-5} through would leave the scale term six orders
     * of magnitude too small, which is a shift of millimetres where metres were meant &mdash; and
     * therefore the failure mode most likely to survive review.
     */
    @Test
    public void unityScaleBecomesPartsPerMillion() {
        DbOperation op = positionVector(
                metres(DX, 0.0), metres(DY, 0.0), metres(DZ, 0.0),
                arcSeconds(RX, 0.0), arcSeconds(RY, 0.0), arcSeconds(RZ, 0.0),
                unity(DS, 1.2e-5));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        assertEquals(12.0, Double.parseDouble(h.token.split(",")[6]), 1.0e-12);
    }

    // ---------------------------------------------------------------- 6. the refusals

    /**
     * <strong>A unit with no conversion factor refuses; it does not default to one.</strong>
     *
     * <p>Upstream leaves 11 units' {@code conv_factor} null, EPSG:9110 sexagesimal DMS among them,
     * and {@link DbUnit#conversionFactor()} reports that as {@code NaN} rather than 1.0 for exactly
     * this reason: a defaulted factor of one is indistinguishable from a real one.
     */
    @Test
    public void aUnitWithNoConversionFactorRefusesRatherThanDefaultingToOne() {
        DbOperation op = positionVector(
                metres(DX, 1.0), metres(DY, 1.0), metres(DZ, 1.0),
                param(RX, "X-axis rotation", 1.0, "9110"), arcSeconds(RY, 0.0),
                arcSeconds(RZ, 0.0), ppm(DS, 0.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull("must not produce a token", h.token);
        assertNotNull(h.refusal);
        assertTrue("must name the parameter: " + h.refusal, h.refusal.contains("8608"));
        assertTrue("must name the unit: " + h.refusal, h.refusal.contains("sexagesimal DMS"));
        assertTrue("must say why a default is not acceptable: " + h.refusal,
                h.refusal.contains("1.0"));
    }

    /**
     * <strong>A rotation declared in a length unit refuses.</strong>
     *
     * <p>The unit's {@link DbUnit#type()} is checked against the slot the parameter fills, so a
     * mis-recorded unit reference cannot be silently multiplied by a length factor and written into
     * a rotation slot.
     */
    @Test
    public void aRotationDeclaredInALengthUnitRefuses() {
        DbOperation op = positionVector(
                metres(DX, 1.0), metres(DY, 1.0), metres(DZ, 1.0),
                metres(RX, 1.0), arcSeconds(RY, 0.0), arcSeconds(RZ, 0.0), ppm(DS, 0.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.token);
        assertNotNull(h.refusal);
        assertTrue("must say what it should have been: " + h.refusal, h.refusal.contains("angle"));
        assertTrue("must name the unit it found: " + h.refusal, h.refusal.contains("metre"));
    }

    /**
     * <strong>Coordinate Frame rotation (EPSG:9607) has its three rotations negated, and says so.</strong>
     *
     * <p>{@code +towgs84} is Position Vector convention and has no {@code coordinate_frame}
     * spelling. The two conventions describe the same transformation with opposite rotation signs,
     * so a Coordinate Frame operation installed verbatim applies double the rotation in the wrong
     * direction &mdash; for a one arc-second rotation at the earth's surface, about 60 m.
     */
    @Test
    public void coordinateFrameRotationsAreNegatedIntoPositionVectorConvention() {
        DbOperation op = operation("9607", "Coordinate Frame rotation (geog2D domain)",
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                arcSeconds(RX, 0.15), arcSeconds(RY, -0.247), arcSeconds(RZ, 0.842),
                ppm(DS, -20.489));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        assertTrue("the negation must be on the record, not inferred from a sign that looks wrong",
                h.negatedRotations);
        assertEquals("1,2,3,-0.15,0.247,-0.842,-20.489", h.token);

        // The control: Position Vector with the same numbers must NOT be negated.
        CandidateParameters.Helmert pv = CandidateParameters.helmert(positionVector(
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                arcSeconds(RX, 0.15), arcSeconds(RY, -0.247), arcSeconds(RZ, 0.842),
                ppm(DS, -20.489)), units());
        assertEquals("1,2,3,0.15,-0.247,0.842,-20.489", pv.token);
    }

    /**
     * <strong>Molodensky-Badekas refuses by name; its rotation pivot has nowhere to go.</strong>
     *
     * <p>EPSG:9636 carries the seven Helmert terms plus three ordinates of the evaluation point.
     * Dropping those three and installing the rest would produce a {@code +towgs84} that parses,
     * runs, and applies a rotation about the wrong origin.
     */
    @Test
    public void molodenskyBadekasRefusesByName() {
        DbOperation op = operation("9636", "Molodensky-Badekas (CF geog2D domain)",
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                arcSeconds(RX, 0.1), arcSeconds(RY, 0.2), arcSeconds(RZ, 0.3),
                ppm(DS, 1.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.token);
        assertNotNull(h.refusal);
        assertTrue("must name the method code: " + h.refusal, h.refusal.contains("9636"));
        assertTrue("must say what the obstacle is: " + h.refusal,
                h.refusal.contains("rotation pivot"));
    }

    /**
     * <strong>A method that claims seven parameters and carries six refuses.</strong>
     *
     * <p>The alternative is to default the missing slot to zero, which produces a shift wrong by
     * exactly the missing term and right in every other respect.
     */
    @Test
    public void aMissingParameterRefusesRatherThanDefaultingToZero() {
        DbOperation op = operation("9606", "Position Vector transformation (geog2D domain)",
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                arcSeconds(RX, 0.1), arcSeconds(RY, 0.2), arcSeconds(RZ, 0.3));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.token);
        assertNotNull(h.refusal);
        assertTrue("must say how many it expected and how many it found: " + h.refusal,
                h.refusal.contains("7") && h.refusal.contains("6"));
    }

    /**
     * <strong>EPSG:1037 is Position Vector, whatever {@code proj.db} calls it.</strong>
     *
     * <p>PROJ 9.8.1's {@code helmert_transformation.method_name} labels EPSG:1037
     * "Geocentric translations (geog3D domain)", which is EPSG:1035's name. Matching on that name
     * would classify a seven-parameter operation as a three-parameter one, drop the rotations, and
     * leave a translation-only shift that looks entirely reasonable. This is why the method table
     * in {@code CandidateParameters} is keyed on the code.
     */
    @Test
    public void methodsAreIdentifiedByCodeAndNotByProjDbsDisplayName() {
        DbOperation op = operation("1037", "Geocentric translations (geog3D domain)",
                metres(DX, 1.0), metres(DY, 2.0), metres(DZ, 3.0),
                arcSeconds(RX, 0.1), arcSeconds(RY, 0.2), arcSeconds(RZ, 0.3),
                ppm(DS, 1.0));

        CandidateParameters.Helmert h = CandidateParameters.helmert(op, units());

        assertNull(h.refusal, h.refusal);
        assertEquals("all seven terms must survive", "1,2,3,0.1,0.2,0.3,1", h.token);
    }

    // ---------------------------------------------------------------- fixtures

    private static DbParam param(String code, String name, double value, String unitCode) {
        return new DbParam("EPSG", code, name, value,
                new DbObjectRef(DbObjectType.UNIT_OF_MEASURE, "EPSG", unitCode));
    }

    private static DbParam metres(String code, double v) {
        return param(code, "translation", v, "9001");
    }

    private static DbParam usSurveyFeet(String code, double v) {
        return param(code, "translation", v, "9003");
    }

    private static DbParam arcSeconds(String code, double v) {
        return param(code, "rotation", v, "9104");
    }

    private static DbParam microradians(String code, double v) {
        return param(code, "rotation", v, "9109");
    }

    private static DbParam grads(String code, double v) {
        return param(code, "rotation", v, "9105");
    }

    private static DbParam ppm(String code, double v) {
        return param(code, "Scale difference", v, "9202");
    }

    private static DbParam unity(String code, double v) {
        return param(code, "Scale difference", v, "9201");
    }

    private static DbOperation positionVector(DbParam... params) {
        return operation("9606", "Position Vector transformation (geog2D domain)", params);
    }

    private static DbOperation geocentricTranslations(DbParam... params) {
        return operation("9603", "Geocentric translations (geog2D domain)", params);
    }

    private static DbOperation operation(String methodCode, String methodName, DbParam... params) {
        return new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1314",
                "OSGB36 to WGS 84 (6)", "EPSG", methodCode, methodName, OSGB36, WGS84, 2.0,
                new ArrayList<DbParam>(Arrays.asList(params)), null, null, null, null, false);
    }

    /** The unit table, with PROJ 9.8.1's own {@code conv_factor} values. */
    private static ProjDatabase units() {
        return new StubProjDatabase("units-only")
                .withUnit(new DbUnit("EPSG", "9001", "metre", DbUnit.Type.LENGTH, 1.0, "m", false))
                .withUnit(new DbUnit("EPSG", "9003", "US survey foot", DbUnit.Type.LENGTH,
                        1200.0 / 3937.0, "us-ft", false))
                .withUnit(new DbUnit("EPSG", "9104", "arc-second", DbUnit.Type.ANGLE,
                        4.84813681109535e-06, null, false))
                .withUnit(new DbUnit("EPSG", "9105", "grad", DbUnit.Type.ANGLE,
                        0.0157079632679489, null, false))
                .withUnit(new DbUnit("EPSG", "9109", "microradian", DbUnit.Type.ANGLE, 1.0e-06,
                        null, false))
                .withUnit(new DbUnit("EPSG", "9110", "sexagesimal DMS", DbUnit.Type.ANGLE,
                        Double.NaN, null, false))
                .withUnit(new DbUnit("EPSG", "9201", "unity", DbUnit.Type.SCALE, 1.0, null, false))
                .withUnit(new DbUnit("EPSG", "9202", "parts per million", DbUnit.Type.SCALE,
                        1.0e-06, null, false));
    }
}
