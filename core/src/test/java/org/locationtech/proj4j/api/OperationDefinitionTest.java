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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
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
import org.locationtech.proj4j.spi.StubProjDatabase;

/**
 * <strong>{@link Proj#createOperationDefinition(String, ProjContext)} against PROJ 9.8.1's own
 * output, digit for digit.</strong>
 *
 * <h2>Why the expected strings are pinned exactly</h2>
 *
 * <p>Every expected definition here was produced by the installed PROJ 9.8.1 binaries, not by this
 * library:
 *
 * <pre>
 * projinfo -o PROJ -q urn:ogc:def:coordinateOperation:EPSG::7941
 * projinfo -o PROJ -q urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_NKG_ETRF00
 * </pre>
 *
 * <p>They are compared as strings rather than as parsed numbers on purpose. A definition that reads
 * {@code +rx=0.00089099999999999} instead of {@code +rx=0.000891} is the same double and would pass
 * any tolerance comparison, and PROJ's own parser accepts it — but it is not what upstream emits,
 * and the whole point of this method is that its output can be diffed against {@code projinfo}. Two
 * of the digits below are there for that reason and no other: {@code +rx=0.000891} needs upstream's
 * substitution of PROJ's arc-second constant for EPSG:9104's stored factor, and
 * {@code +drx=8.1e-05} needs both C's {@code %g} exponent rule and the reprint at 14 significant
 * digits that fires when the 15-digit text contains {@code 9999999999}.
 *
 * <h2>Why EPSG:7941 is the fixture</h2>
 *
 * <p>It exercises more of this path in one row than any other operation in the database: the
 * fifteen-parameter time-dependent form, six different EPSG unit codes — millimetre, milliarc-second,
 * part per billion, millimetre per year, milliarc-second per year, part per billion per year, plus
 * year for the epoch — and both of the printing rules above. Its values are the real ones, read from
 * PROJ 9.8.1's shipped {@code proj.db}:
 *
 * <pre>
 * sqlite3 proj.db "select tx,ty,tz,translation_uom_code,rx,ry,rz,rotation_uom_code,...
 *                  from helmert_transformation where auth_name='EPSG' and code='7941'"
 *   tx,ty,tz            =  54, 51, -48        uom 1025 millimetre
 *   rx,ry,rz            =  0.891, 5.39, -8.712 uom 1031 milliarc-second
 *   scale_difference    =  0                  uom 1028 parts per billion
 *   rate_tx,ty,tz       =  0, 0, 0            uom 1027 millimetres per year
 *   rate_rx,ry,rz       =  0.081, 0.49, -0.792 uom 1032 milliarc-seconds per year
 *   rate_scale          =  0                  uom 1030 parts per billion per year
 *   epoch               =  2000               uom 1029 year
 * </pre>
 *
 * <p>The unit conversion factors in {@link #database()} are likewise the stored ones, printed with
 * {@code format('%!.17g', conv_factor)} because sqlite3's default float output truncates at 15
 * significant digits and hides exactly the last-bit differences this code has to reproduce.
 */
public class OperationDefinitionTest {

    /** {@code projinfo -o PROJ -q urn:ogc:def:coordinateOperation:EPSG::7941}, unwrapped. */
    private static final String EPSG_7941 = "+proj=helmert"
            + " +x=0.054 +y=0.051 +z=-0.048"
            + " +rx=0.000891 +ry=0.00539 +rz=-0.008712 +s=0"
            + " +dx=0 +dy=0 +dz=0"
            + " +drx=8.1e-05 +dry=0.00049 +drz=-0.000792 +ds=0"
            + " +t_epoch=2000 +convention=position_vector";

    private static final String NKG_GRID_STEP =
            "+proj=deformation +t_epoch=2000.0 +grids=eur_nkg_nkgrf03vel_realigned.tif";

    // ------------------------------------------------------------------------ the two real rows

    /**
     * The fifteen-parameter Helmert on its own. This is the assertion that pins the unit arithmetic
     * and both printing rules; if it fails, read the +rx and +drx tokens first.
     */
    @Test
    public void timeDependentHelmertMatchesProjinfo() {
        assertEquals(EPSG_7941, definition("urn:ogc:def:coordinateOperation:EPSG::7941"));
    }

    /**
     * The concatenated operation over it: step 2 is EPSG:7941 forward, step 3 is a
     * {@code PROJ:PROJString} deformation reversed. Two things worth noting, both of which a
     * tidier-looking implementation would get wrong — the step numbers are 2 and 3, not 1 and 2, so
     * nothing may assume they start at one; and the reverse of a single-step PROJ string is
     * {@code +inv} in front of it, not a rewritten step.
     */
    @Test
    public void concatenatedOperationMatchesProjinfo() {
        assertEquals("+proj=pipeline +step " + EPSG_7941 + " +step +inv " + NKG_GRID_STEP,
                definition("urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_NKG_ETRF00"));
    }

    /**
     * <strong>The positive control.</strong> A comparison that cannot fail is not a measurement, so
     * this moves one parameter by one digit — 54 mm to 55 mm — and requires the assertion above to
     * notice. It also fixes which token moved, so a future change that alters two things at once
     * cannot pass by accident.
     */
    @Test
    public void positiveControlOneChangedDigitIsDetected() {
        StubProjDatabase db = database();
        db.withOperation(helmert7941(param("8605", 55.0, "1025")));
        String moved = definition(db, "urn:ogc:def:coordinateOperation:EPSG::7941");
        assertNotEquals(EPSG_7941, moved);
        assertTrue(moved, moved.contains("+x=0.055"));
        assertFalse(moved, moved.contains("+x=0.054"));
        // Nothing else moved: the rest of the string is identical either side of that token.
        assertEquals(EPSG_7941.replace("+x=0.054", "+x=0.055"), moved);
    }

    // ------------------------------------------------------- the PROJ:PROJString step, both ways

    /** Forward, a {@code PROJ:PROJString} operation is its stored definition verbatim. */
    @Test
    public void projStringStepForwardIsVerbatim() {
        assertEquals(NKG_GRID_STEP,
                definition("urn:ogc:def:coordinateOperation:NKG::NKG_ETRF00_TO_ETRF2000"));
    }

    /**
     * A reverse step whose stored definition already begins {@code +inv} loses it rather than
     * gaining a second one. Upstream's formatter toggles the step's inversion state; it does not
     * accumulate flags.
     */
    @Test
    public void reversingAnAlreadyInvertedStepRemovesTheFlag() {
        StubProjDatabase db = database();
        db.withOperation(projString("NKG", "ALREADY_INVERSE", "+inv " + NKG_GRID_STEP));
        db.withOperation(concatenation("NKG", "USES_INVERSE", Arrays.asList(
                new DbOperationStep(1, ref(DbObjectType.OTHER_TRANSFORMATION, "NKG",
                        "ALREADY_INVERSE"), DbOperationStep.Direction.REVERSE))));
        assertEquals("+proj=pipeline +step " + NKG_GRID_STEP,
                definition(db, "urn:ogc:def:coordinateOperation:NKG::USES_INVERSE"));
    }

    /**
     * Reversing a stored definition that is itself a pipeline is refused. Upstream does it by
     * re-ingesting the string and running its step-merging optimiser; flipping tokens here would be
     * a plausible-looking wrong answer.
     */
    @Test
    public void reversingAPipelineValuedStepIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(projString("NKG", "IS_A_PIPELINE",
                "+proj=pipeline +step +proj=noop +step " + NKG_GRID_STEP));
        db.withOperation(concatenation("NKG", "USES_PIPELINE", Arrays.asList(
                new DbOperationStep(1, ref(DbObjectType.OTHER_TRANSFORMATION, "NKG",
                        "IS_A_PIPELINE"), DbOperationStep.Direction.REVERSE))));
        refused(db, "urn:ogc:def:coordinateOperation:NKG::USES_PIPELINE", "is itself a pipeline");
    }

    // ------------------------------------------------------------------- the refusals, by reason

    /**
     * <strong>Geocentric translation, EPSG method 1031, is refused although it is a Helmert with
     * three parameters.</strong> Its inverse is not {@code +inv}: upstream negates the three
     * translations instead, so emitting a {@code +proj=helmert} for it would round-trip wrongly in
     * the reverse direction while looking perfectly reasonable forward.
     */
    @Test
    public void geocentricTranslationIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1149",
                "a geocentric translation", "EPSG", "1031", "Geocentric translations (geocentric)",
                ITRF2000, ETRF2000, 1.0,
                Arrays.asList(param("8605", 0.0, "9001"), param("8606", 0.0, "9001"),
                        param("8607", 0.0, "9001")),
                null, null, null, null, false));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::1149", "1031");
    }

    /**
     * The geographic-domain Helmerts are refused for the same class of reason: upstream wraps them
     * in {@code +proj=cart} on both sides plus a unit convert and an axis swap, and that wrapper is
     * where a silently wrong answer would come from.
     */
    @Test
    public void geographicDomainHelmertIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "1188",
                "a geographic-domain Helmert", "EPSG", "9606", "Position Vector transformation"
                        + " (geog2D domain)", ITRF2000, ETRF2000, 1.0,
                sevenParams(), null, null, null, null, false));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::1188", "geocentric-domain Helmert");
    }

    /** A grid transformation is refused by name rather than mapped onto a guessed grid operator. */
    @Test
    public void gridTransformationIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(new DbOperation(DbObjectType.GRID_TRANSFORMATION, "EPSG", "9999",
                "a grid transformation", "EPSG", "9615", "NTv2", ITRF2000, ETRF2000, 1.0,
                null, Arrays.asList("some.gsb"), null, null, null, false));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::9999", "grid_transformation");
    }

    /**
     * A source or target CRS that is not geocentric is refused, because upstream emits a
     * {@code +proj=cart} wrapper and a unit convert around the Helmert in that case and this class
     * emits neither.
     */
    @Test
    public void nonGeocentricCrsIsRefused() {
        StubProjDatabase db = database();
        db.withCrs(new DbCrs(DbCrsType.GEOGRAPHIC_2D, "EPSG", "4919", "ITRF2000 as geographic 2D",
                false, CS_6500, null, null, null, null, null, null));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::7941", "not geocentric");
    }

    /**
     * A CRS whose axes are in something other than metres is refused for the same reason: the unit
     * convert upstream emits is not emitted here, so quietly ignoring the axis unit would be wrong
     * by that factor.
     */
    @Test
    public void nonMetreAxisUnitIsRefused() {
        StubProjDatabase db = database();
        db.withUnit(new DbUnit("EPSG", "9036", "kilometre", DbUnit.Type.LENGTH, 1000.0, "km",
                false));
        db.withCoordinateSystem(new DbCoordinateSystem("EPSG", "6500", "Cartesian", 3,
                Arrays.asList(axis("Geocentric X", "X", 1, "9036"),
                        axis("Geocentric Y", "Y", 2, "9036"),
                        axis("Geocentric Z", "Z", 3, "9036"))));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::7941", "not metres");
    }

    /**
     * <strong>A missing unit row is refused rather than defaulted to 1.0.</strong> EPSG:7941 is
     * stored in millimetres, so a silent default here is not a small error, it is a factor of a
     * thousand on every translation — 54 metres instead of 54 millimetres, which is a plausible
     * coordinate in the right country.
     */
    @Test
    public void missingUnitRowIsRefused() {
        StubProjDatabase db = database();
        // Every unit except the millimetre the translations are in.
        StubProjDatabase without = new StubProjDatabase("no millimetre");
        for (String code : new String[] {"9001", "1027", "1028", "1029", "1030", "1031", "1032"}) {
            without.withUnit(unit(code));
        }
        without.withCoordinateSystem(cs6500());
        without.withCrs(geocentric("4919", "ITRF2000"));
        without.withCrs(geocentric("7930", "ETRF2000"));
        without.withOperation(helmert7941());
        refused(without, "urn:ogc:def:coordinateOperation:EPSG::7941", "not in the database");
        // The control: with the row present the same fixture succeeds.
        assertEquals(EPSG_7941, definition(db, "urn:ogc:def:coordinateOperation:EPSG::7941"));
    }

    /**
     * A parameter in a unit of the wrong kind is refused. A rotation stored in metres is not a
     * rotation, and multiplying it by a length factor would produce a number rather than an error.
     */
    @Test
    public void parameterInTheWrongKindOfUnitIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(helmert7941(param("8608", 0.891, "1025")));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::7941", "a unit of type length");
    }

    /** A method that requires fifteen parameters and has seven is refused, naming the missing one. */
    @Test
    public void missingParameterIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "7941",
                "ITRF2000 to ETRF2000 (2)", "EPSG", "1053",
                "Time-dependent Position Vector tfm (geocentric)", ITRF2000, ETRF2000, 0.0,
                sevenParams(), null, null, null, "EPSG", false));
        refused(db, "urn:ogc:def:coordinateOperation:EPSG::7941", "missing EPSG parameter 1040");
    }

    /**
     * A step with no declared direction is refused. Upstream infers one by matching each step's
     * CRSs against its neighbours' and may insert operations to make the ends meet
     * ({@code ConcatenatedOperation::fixSteps}); guessing here would be a guess about which end
     * matches.
     */
    @Test
    public void stepWithNoDirectionIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(concatenation("NKG", "NO_DIRECTION", Arrays.asList(
                new DbOperationStep(1, ref(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "7941"),
                        DbOperationStep.Direction.UNSPECIFIED))));
        refused(db, "urn:ogc:def:coordinateOperation:NKG::NO_DIRECTION", "no declared direction");
    }

    /**
     * A concatenated operation as a step of another is refused, which is not a limitation of this
     * class but of the authority model: upstream resolves each step with
     * {@code allowConcatenated = false}, so such a thing cannot be built at all.
     */
    @Test
    public void nestedConcatenationIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(concatenation("NKG", "OUTER", Arrays.asList(
                new DbOperationStep(1, ref(DbObjectType.CONCATENATED_OPERATION, "NKG",
                        "ITRF2000_TO_NKG_ETRF00"), DbOperationStep.Direction.FORWARD))));
        refused(db, "urn:ogc:def:coordinateOperation:NKG::OUTER",
                "is itself a concatenated operation");
    }

    /** A step naming a code the database does not hold is refused, naming the code. */
    @Test
    public void missingStepIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(concatenation("NKG", "DANGLING", Arrays.asList(
                new DbOperationStep(7, ref(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "404"),
                        DbOperationStep.Direction.FORWARD))));
        refused(db, "urn:ogc:def:coordinateOperation:NKG::DANGLING", "EPSG:404");
    }

    /** A concatenated operation with no steps is refused rather than emitting an empty pipeline. */
    @Test
    public void concatenationWithNoStepsIsRefused() {
        StubProjDatabase db = database();
        db.withOperation(concatenation("NKG", "EMPTY", null));
        refused(db, "urn:ogc:def:coordinateOperation:NKG::EMPTY", "no steps");
    }

    // ---------------------------------------------------------------------- the API's own errors

    @Test
    public void aCrsUrnIsNotAnOperation() {
        misused("urn:ogc:def:crs:EPSG::4326", "not a coordinateOperation");
    }

    /**
     * The bare two-token form is refused even when the code names something the database holds as
     * an operation, because upstream resolves that form as a CRS. Answering it here would make
     * proj4j accept a notation PROJ does not.
     */
    @Test
    public void bareAuthorityCodeIsRefused() {
        misused("NKG:ITRF2000_TO_NKG_ETRF00", "not a coordinateOperation");
    }

    @Test
    public void nonUrnTextIsRefused() {
        misused("+proj=helmert +x=1", "is not an OGC URN");
        misused("not an identifier at all", "is not an OGC URN");
    }

    @Test
    public void nullIdentifierIsRefused() {
        misused(null, "null");
    }

    /** With no database attached the error says so, and says which artifact supplies one. */
    @Test
    public void noDatabaseIsReported() {
        try {
            Proj.createOperationDefinition("urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK",
                    ProjContext.builder().build());
            fail("expected a refusal");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.DATABASE_UNAVAILABLE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("proj4j-db"));
        }
    }

    /** A well-formed URN for a code the database does not hold is a different error again. */
    @Test
    public void unknownCodeIsReported() {
        try {
            definition("urn:ogc:def:coordinateOperation:NKG::NOT_A_REAL_OPERATION");
            fail("expected a refusal");
        } catch (CrsCreationException e) {
            assertEquals(ErrorCause.NO_OPERATION_AVAILABLE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains("NKG:NOT_A_REAL_OPERATION"));
        }
    }

    // ------------------------------------------------------------------------------- the fixture

    private static final DbObjectRef ITRF2000 = ref(DbObjectType.GEODETIC_CRS, "EPSG", "4919");
    private static final DbObjectRef ETRF2000 = ref(DbObjectType.GEODETIC_CRS, "EPSG", "7930");
    private static final DbObjectRef CS_6500 =
            ref(DbObjectType.COORDINATE_SYSTEM, "EPSG", "6500");

    private static String definition(String urn) {
        return definition(database(), urn);
    }

    private static String definition(StubProjDatabase db, String urn) {
        return Proj.createOperationDefinition(urn, ProjContext.builder().database(db).build());
    }

    private static void refused(StubProjDatabase db, String urn, String expectedInMessage) {
        try {
            String got = definition(db, urn);
            fail("expected a refusal mentioning \"" + expectedInMessage + "\", got: " + got);
        } catch (CrsCreationException e) {
            assertEquals(e.getMessage(), ErrorCause.UNSUPPORTED_OPERATION_METHOD, e.cause());
            assertTrue("the refusal should say why; expected \"" + expectedInMessage + "\" in: "
                    + e.getMessage(), e.getMessage().contains(expectedInMessage));
        }
    }

    private static void misused(String urn, String expectedInMessage) {
        try {
            String got = definition(urn);
            fail("expected a refusal mentioning \"" + expectedInMessage + "\", got: " + got);
        } catch (CrsCreationException e) {
            assertEquals(e.getMessage(), ErrorCause.API_MISUSE, e.cause());
            assertTrue(e.getMessage(), e.getMessage().contains(expectedInMessage));
        }
    }

    /**
     * The real EPSG:7941 row, the two geocentric CRSs it joins, their shared coordinate system and
     * the seven unit rows it touches — nothing else, so a lookup this code should not be making
     * fails the test by name rather than quietly returning null.
     */
    private static StubProjDatabase database() {
        StubProjDatabase db = new StubProjDatabase("EPSG 7941 and NKG ITRF2000_TO_NKG_ETRF00");
        for (String code : new String[] {"9001", "1025", "1027", "1028", "1029", "1030", "1031",
                "1032"}) {
            db.withUnit(unit(code));
        }
        db.withCoordinateSystem(cs6500());
        db.withCrs(geocentric("4919", "ITRF2000"));
        db.withCrs(geocentric("7930", "ETRF2000"));
        db.withOperation(helmert7941());
        db.withOperation(projString("NKG", "NKG_ETRF00_TO_ETRF2000", NKG_GRID_STEP));
        db.withOperation(concatenation("NKG", "ITRF2000_TO_NKG_ETRF00", Arrays.asList(
                new DbOperationStep(2, ref(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "7941"),
                        DbOperationStep.Direction.FORWARD),
                new DbOperationStep(3, ref(DbObjectType.OTHER_TRANSFORMATION, "NKG",
                        "NKG_ETRF00_TO_ETRF2000"), DbOperationStep.Direction.REVERSE))));
        return db;
    }

    /**
     * EPSG:7941 with zero or more parameters replaced. The replacement form is how the positive
     * control and the wrong-unit case are built, so that they differ from the passing case in
     * exactly one parameter and nothing else.
     */
    private static DbOperation helmert7941(DbParam... overrides) {
        List<DbParam> params = new ArrayList<DbParam>(fifteenParams());
        for (DbParam override : overrides) {
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i).code().equals(override.code())) {
                    params.set(i, override);
                }
            }
        }
        return new DbOperation(DbObjectType.HELMERT_TRANSFORMATION, "EPSG", "7941",
                "ITRF2000 to ETRF2000 (2)", "EPSG", "1053",
                "Time-dependent Position Vector tfm (geocentric)", ITRF2000, ETRF2000, 0.0,
                params, null, null, null, "EPSG", false);
    }

    private static List<DbParam> fifteenParams() {
        return Arrays.asList(
                param("8605", 54.0, "1025"),
                param("8606", 51.0, "1025"),
                param("8607", -48.0, "1025"),
                param("8608", 0.891, "1031"),
                param("8609", 5.39, "1031"),
                param("8610", -8.712, "1031"),
                param("8611", 0.0, "1028"),
                param("1040", 0.0, "1027"),
                param("1041", 0.0, "1027"),
                param("1042", 0.0, "1027"),
                param("1043", 0.081, "1032"),
                param("1044", 0.49, "1032"),
                param("1045", -0.792, "1032"),
                param("1046", 0.0, "1030"),
                param("1047", 2000.0, "1029"));
    }

    private static List<DbParam> sevenParams() {
        return fifteenParams().subList(0, 7);
    }

    private static DbOperation projString(String authName, String code, String definition) {
        return new DbOperation(DbObjectType.OTHER_TRANSFORMATION, authName, code,
                authName + " " + code, "PROJ", "PROJString", definition,
                ref(DbObjectType.GEODETIC_CRS, "NKG", "ETRF00"), ETRF2000, 0.01,
                null, null, null, null, null, false);
    }

    private static DbOperation concatenation(String authName, String code,
            List<DbOperationStep> steps) {
        return new DbOperation(DbObjectType.CONCATENATED_OPERATION, authName, code,
                authName + " " + code, null, null, null, ITRF2000,
                ref(DbObjectType.GEODETIC_CRS, "NKG", "ETRF00"), 0.01,
                null, null, null, steps, "NKG 2008", false);
    }

    private static DbParam param(String epsgCode, double value, String uomCode) {
        return new DbParam("EPSG", epsgCode, "EPSG parameter " + epsgCode, value,
                ref(DbObjectType.UNIT_OF_MEASURE, "EPSG", uomCode));
    }

    private static DbCrs geocentric(String code, String name) {
        return new DbCrs(DbCrsType.GEOCENTRIC, "EPSG", code, name, false, CS_6500, null, null,
                null, null, null, null);
    }

    private static DbCoordinateSystem cs6500() {
        return new DbCoordinateSystem("EPSG", "6500", "Cartesian", 3,
                Arrays.asList(axis("Geocentric X", "X", 1, "9001"),
                        axis("Geocentric Y", "Y", 2, "9001"),
                        axis("Geocentric Z", "Z", 3, "9001")));
    }

    private static DbAxis axis(String name, String abbreviation, int order, String uomCode) {
        return new DbAxis(name, abbreviation, "geocentric" + abbreviation, order,
                ref(DbObjectType.UNIT_OF_MEASURE, "EPSG", uomCode));
    }

    /**
     * The unit rows PROJ 9.8.1 ships, with the factors as they are actually stored. Printed with
     * {@code format('%!.17g', conv_factor)}: sqlite3's default output truncates at 15 significant
     * digits, which hides the difference between EPSG:9104's stored arc-second and PROJ's own
     * {@code M_PI/180/3600} — and that difference is the whole reason this class has a substitution
     * rule and a 14-digit print fallback.
     */
    private static DbUnit unit(String code) {
        if ("9001".equals(code)) {
            return new DbUnit("EPSG", "9001", "metre", DbUnit.Type.LENGTH, 1.0, "m", false);
        }
        if ("1025".equals(code)) {
            return new DbUnit("EPSG", "1025", "millimetre", DbUnit.Type.LENGTH, 0.001, "mm", false);
        }
        if ("1027".equals(code)) {
            return new DbUnit("EPSG", "1027", "millimetres per year", DbUnit.Type.LENGTH,
                    3.1688765172731488e-11, null, false);
        }
        if ("1028".equals(code)) {
            return new DbUnit("EPSG", "1028", "parts per billion", DbUnit.Type.SCALE,
                    1.0000000000000001e-09, null, false);
        }
        if ("1029".equals(code)) {
            return new DbUnit("EPSG", "1029", "year", DbUnit.Type.TIME, 31556925.445, null, false);
        }
        if ("1030".equals(code)) {
            return new DbUnit("EPSG", "1030", "parts per billion per year", DbUnit.Type.SCALE,
                    3.1688765172731483e-17, null, false);
        }
        if ("1031".equals(code)) {
            return new DbUnit("EPSG", "1031", "milliarc-second", DbUnit.Type.ANGLE,
                    4.8481368110953553e-09, null, false);
        }
        if ("1032".equals(code)) {
            return new DbUnit("EPSG", "1032", "milliarc-seconds per year", DbUnit.Type.ANGLE,
                    1.5363146893207598e-16, null, false);
        }
        throw new IllegalArgumentException("no fixture for unit " + code);
    }

    private static DbObjectRef ref(DbObjectType type, String authName, String code) {
        return new DbObjectRef(type, authName, code);
    }
}
