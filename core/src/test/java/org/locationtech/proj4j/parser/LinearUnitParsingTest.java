/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * {@code +units} / {@code +to_meter}, against PROJ 9.8.1.
 *
 * <h2>Reference values</h2>
 *
 * <p>Every expected easting/northing below was produced by the installed PROJ 9.8.1
 * ({@code Rel. 9.8.1, April 10th, 2026}):
 *
 * <pre>
 * $ echo "2 1" | proj -f '%.9f' +proj=merc +ellps=GRS80 &lt;more params&gt;
 * </pre>
 *
 * <p>These are <b>projected metres</b> (or the stated linear unit), compared per
 * ordinate. No angular tolerance is involved anywhere in this file.
 *
 * <h2>The rule being pinned</h2>
 *
 * <p>{@code init.cpp:678-714} resolves the linear unit exactly once. If {@code +units}
 * is present it is looked up in {@code pj_list_linear_units()} and <i>that table
 * entry's</i> {@code to_meter} string is what gets parsed — {@code +to_meter} is never
 * read at all. Only in the absence of {@code +units} does {@code +to_meter} apply.
 * Proj4J used to apply {@code +units} and then overwrite it with {@code +to_meter}, so
 * {@code +to_meter} won: exactly inverted, and silent.
 */
public class LinearUnitParsingTest {

    /** PROJ printed 9 decimals; 1e-6 of the stated unit is far inside that. */
    private static final double TOL = 1e-6;

    private static final String MERC = "+proj=merc +ellps=GRS80";

    /** {@code +proj=merc +ellps=GRS80} at (2, 1), in metres. */
    private static final double METRE_X = 222638.981586547;
    private static final double METRE_Y = 110579.965218250;

    /** The same, in U.S. survey feet ({@code +units=us-ft}). */
    private static final double US_FOOT_X = 730441.392088531;
    private static final double US_FOOT_Y = 362794.435886874;

    /** The same, in international feet — {@code +to_meter=0.3048}. */
    private static final double INTL_FOOT_X = 730442.852974236;
    private static final double INTL_FOOT_Y = 362795.161477197;

    private final CRSFactory crsFactory = new CRSFactory();

    private CoordinateReferenceSystem crs(String def) {
        return crsFactory.createFromParameters("test", def);
    }

    private ProjCoordinate project(String def, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate(Double.NaN, Double.NaN);
        crs(def).getProjection().project(new ProjCoordinate(lon, lat), out);
        return out;
    }

    private void expect(String def, double x, double y) {
        ProjCoordinate out = project(def, 2, 1);
        assertEquals(def + " easting", x, out.x, TOL);
        assertEquals(def + " northing", y, out.y, TOL);
    }

    private InvalidValueException rejected(String def) {
        try {
            crs(def);
        } catch (InvalidValueException e) {
            return e;
        } catch (Proj4jException e) {
            fail("expected InvalidValueException for [" + def + "] but got " + e);
        }
        fail("expected [" + def + "] to be rejected");
        return null;
    }

    // ------------------------------------------------------------------
    // +units beats +to_meter, in either order
    // ------------------------------------------------------------------

    /**
     * The discriminating pair: the U.S. survey foot and the international foot differ in
     * the 7th significant figure, so which one won is unambiguous from the number.
     */
    @Test
    public void unitsWinsOverToMeterWhateverTheOrder() {
        expect(MERC + " +units=us-ft +to_meter=0.3048", US_FOOT_X, US_FOOT_Y);
        expect(MERC + " +to_meter=0.3048 +units=us-ft", US_FOOT_X, US_FOOT_Y);

        // ...and the control: without +units, +to_meter is what applies.
        expect(MERC + " +to_meter=0.3048", INTL_FOOT_X, INTL_FOOT_Y);
    }

    /**
     * {@code +units} must win even when the {@code +to_meter} it displaces is absurd.
     * Under the old ordering this returned the metre values scaled by 1e-9.
     */
    @Test
    public void unitsWinsOverAToMeterThatWouldDominateTheResult() {
        expect(MERC + " +units=us-ft +to_meter=1e9", US_FOOT_X, US_FOOT_Y);
    }

    /** {@code getUnits()} must report the unit actually in force. */
    @Test
    public void theReportedUnitIsTheOneThatWon() {
        assertSame(Units.US_FEET, crs(MERC + " +to_meter=0.3048 +units=us-ft")
                .getProjection().getUnits());
        // +to_meter alone names no unit, so the accessor's metres default stands -
        // which is a pre-existing wart, not a claim that the scale is 1.
        assertSame(Units.METRES, crs(MERC + " +to_meter=0.3048").getProjection().getUnits());
    }

    /** Duplicate keys: the first occurrence wins, for these two as for every other. */
    @Test
    public void theFirstOccurrenceOfADuplicatedUnitKeyWins() {
        expect(MERC + " +to_meter=0.3048 +to_meter=1", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +units=us-ft +units=m", US_FOOT_X, US_FOOT_Y);
        expect(MERC + " +units=m +units=us-ft", METRE_X, METRE_Y);
    }

    // ------------------------------------------------------------------
    // +to_meter accepts a num/den ratio
    // ------------------------------------------------------------------

    /**
     * {@code init.cpp:690-710}: {@code pj_strtod} stops at a {@code '/'}, and the
     * remainder is read as a denominator. PROJ's own unit table uses that syntax —
     * {@code dm} is {@code "1/10"} and {@code us-in} is {@code "1/39.37"} — so it is not
     * an exotic corner. Proj4J used bare {@code Double.parseDouble} and threw.
     */
    @Test
    public void toMeterAcceptsARatio() {
        expect(MERC + " +to_meter=3048/10000", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +to_meter=1/3.2808398950131235", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC + " +to_meter=1/1", METRE_X, METRE_Y);
        // 1/10 is exactly how PROJ's table spells the decimetre.
        expect(MERC + " +to_meter=1/10", METRE_X * 10, METRE_Y * 10);
    }

    /** A zero denominator is an error upstream, not a division by zero. */
    @Test
    public void aZeroDenominatorIsRejected() {
        assertTrue(rejected(MERC + " +to_meter=1/0").getMessage().contains("to_meter"));
        assertTrue(rejected(MERC + " +to_meter=1/0.0").getMessage().contains("to_meter"));
    }

    /** {@code to_meter <= 0} is an error ({@code init.cpp:706}). */
    @Test
    public void aNonPositiveToMeterIsRejected() {
        rejected(MERC + " +to_meter=0");
        rejected(MERC + " +to_meter=-0.3048");
        rejected(MERC + " +to_meter=-1/2");
        rejected(MERC + " +to_meter=nonsense");
    }

    // ------------------------------------------------------------------
    // What the linear unit does NOT touch
    // ------------------------------------------------------------------

    /**
     * {@code +x_0} and {@code +y_0} are <b>always metres</b>
     * ({@code init.cpp:660-661}, a plain {@code "d"} lookup with no unit involvement).
     * The output affine is {@code fr_meter * (a*x + x_0)} ({@code fwd.cpp:143-146}), so
     * the false easting is converted <i>with</i> the coordinate on the way out, never
     * interpreted <i>as</i> the declared unit on the way in.
     * <p>
     * If {@code +x_0=500000} were read as 500000 U.S. feet, the easting would come out
     * near 1230441 rather than 2370858 — so the two readings are far apart.
     */
    @Test
    public void unitsDoesNotChangeWhatXZeroAndYZeroMean() {
        expect(MERC + " +units=us-ft +x_0=500000", 2370858.058755198, US_FOOT_Y);
        expect(MERC + " +units=km +x_0=500000 +y_0=200000", 722.638981587, 310.579965218);
        expect(MERC + " +x_0=500000 +y_0=200000", METRE_X + 500000, METRE_Y + 200000);

        // +to_meter must behave identically: km and to_meter=1000 are the same thing.
        expect(MERC + " +to_meter=1000 +x_0=500000 +y_0=200000", 722.638981587, 310.579965218);
    }

    /**
     * {@code +a} and {@code +b} are always metres too. A sphere of 6400000 <i>metres</i>
     * projected into U.S. feet is 732945.2 ft; a sphere of 6400000 <i>feet</i> would be
     * a third of that.
     */
    @Test
    public void unitsDoesNotChangeWhatAAndBMean() {
        expect("+proj=merc +a=6400000", 223402.144255274, 111706.743574944);
        expect("+proj=merc +a=6400000 +units=us-ft", 732945.201610846, 366491.207878797);
        assertEquals("the parsed semi-major axis is unaffected by +units",
                6400000.0,
                crs("+proj=merc +a=6400000 +units=us-ft").getDatum().getEllipsoid()
                        .getEquatorRadius(),
                0.0);
    }

    // ------------------------------------------------------------------
    // The unit table itself
    // ------------------------------------------------------------------

    /**
     * All 21 of PROJ's linear unit ids must now resolve. Four were declared in
     * {@code Units} but missing from the lookup array ({@code fath}, {@code ch},
     * {@code link}, {@code us-ch}) and the three Indian units were absent altogether —
     * and because {@code findUnits} substitutes metres rather than returning null, every
     * one of them silently scaled by 1.
     */
    @Test
    public void everyLinearUnitProjHasResolves() {
        String[] ids = {
                "km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi",
                "fath", "ch", "link",
                "us-in", "us-ft", "us-yd", "us-ch", "us-mi",
                "ind-yd", "ind-ft", "ind-ch",
        };
        List<String> unresolved = new ArrayList<String>();
        for (String id : ids) {
            if (!Units.isKnownUnit(id)) {
                unresolved.add(id);
            }
        }
        assertTrue("PROJ linear units Proj4J still cannot resolve: " + unresolved,
                unresolved.isEmpty());
        assertEquals("Units.LINEAR_UNITS must be PROJ's table, no more and no less",
                ids.length, Units.LINEAR_UNITS.length);
    }

    /**
     * Every one of PROJ's 21 linear factors, <b>bitwise</b>, against the column
     * {@code +units=} actually reads.
     *
     * <h2>Why this exists</h2>
     *
     * <p>PROJ's {@code PJ_UNITS} carries the conversion twice ({@code 9.8.1:src/proj.h:258}): a
     * {@code to_meter} <b>string</b> and a {@code factor} <b>double</b>. For the five U.S. survey
     * units the two do not agree -- 3 ulps for {@code us-ft} and {@code us-yd}, 1 ulp for
     * {@code us-in}, {@code us-ch} and {@code us-mi}. {@code +units=} and {@code +vunits=} read
     * the <b>string</b> ({@code init.cpp:689}, via {@code pj_strtod} plus an optional
     * {@code /denominator}); {@code +proj=unitconvert} reads the <b>factor</b>
     * ({@code unitconvert.cpp:411,425}).
     *
     * <p>So {@code Units.US_FEET}'s {@code 0.304800609601219} is not a rounded transcription of
     * {@code 1200/3937} -- it is the correct value for this table, and
     * {@code PipelineUnits.LINEAR_FACTORS} correctly carries {@code 1200 / 3937.0} for the other
     * path. The two are pinned apart on purpose. It reads like a typo, it has been reported as one
     * more than once, and this test is here so that "fixing" it fails loudly with the reason
     * attached rather than silently moving 270 golden rows away from PROJ.
     *
     * <p>The expected values are spelled the way {@code units.cpp} spells them and parsed the way
     * {@code pj_strtod} parses them, so a wrong constant cannot agree with itself.
     */
    @Test
    public void everyLinearUnitMatchesProjsStringColumnBitwise() {
        // 9.8.1:src/units.cpp:13-33, field 1 and field 2, in that file's order.
        String[][] table = {
                {"km", "1000"}, {"m", "1"}, {"dm", "1/10"}, {"cm", "1/100"}, {"mm", "1/1000"},
                {"kmi", "1852"}, {"in", "0.0254"}, {"ft", "0.3048"}, {"yd", "0.9144"},
                {"mi", "1609.344"}, {"fath", "1.8288"}, {"ch", "20.1168"}, {"link", "0.201168"},
                {"us-in", "1/39.37"}, {"us-ft", "0.304800609601219"},
                {"us-yd", "0.914401828803658"}, {"us-ch", "20.11684023368047"},
                {"us-mi", "1609.347218694437"},
                {"ind-yd", "0.91439523"}, {"ind-ft", "0.30479841"}, {"ind-ch", "20.11669506"},
        };
        assertEquals("the table must cover all of PROJ's linear units",
                Units.LINEAR_UNITS.length, table.length);

        List<String> wrong = new ArrayList<String>();
        for (String[] row : table) {
            double expected = projStrtod(row[1]);
            double actual = Units.findUnits(row[0]).value;
            if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
                wrong.add(row[0] + ": +units= means strtod(\"" + row[1] + "\") = " + expected
                        + " but Units has " + actual + " ("
                        + (Double.doubleToLongBits(actual) - Double.doubleToLongBits(expected))
                        + " ulp)");
            }
        }
        assertTrue("linear units that do not match PROJ's to_meter string column: " + wrong,
                wrong.isEmpty());
    }

    /**
     * The 3-ulp split inside PROJ's own {@code us-ft} row, asserted from both sides so that
     * neither table can be quietly changed to match the other.
     *
     * <p>{@code 1200 / 3937.0} is {@code 0.3048006096012192}; the string column is
     * {@code 0.304800609601219}. Three ulps, 5.46e-16 relative. Both numbers are correct, for
     * different {@code +units=} / {@code +proj=unitconvert} code paths -- see
     * {@link #everyLinearUnitMatchesProjsStringColumnBitwise()}.
     */
    @Test
    public void usSurveyFootIsProjsStringColumnNotItsFactor() {
        double fromString = 0.304800609601219;
        double fromFactor = 1200 / 3937.0;

        assertEquals("1200/3937.0 must be the double that prints as 0.3048006096012192",
                0.3048006096012192, fromFactor, 0.0);
        assertEquals("the two spellings must be exactly 3 ulps apart", 3L,
                Double.doubleToLongBits(fromFactor) - Double.doubleToLongBits(fromString));
        assertEquals("relative gap", 5.463685059936552E-16,
                (fromFactor - fromString) / fromFactor, 1.0e-31);

        // +units=us-ft resolves to the string column, bitwise. This is the assertion that fails
        // if someone "corrects" Units.US_FEET to 1200/3937.0.
        assertEquals("Units.US_FEET must be PROJ's to_meter string, not its factor",
                Double.doubleToLongBits(fromString),
                Double.doubleToLongBits(Units.findUnits("us-ft").value));
        assertFalse("Units.US_FEET must NOT be 1200/3937.0 - that is unitconvert's column",
                Units.findUnits("us-ft").value == fromFactor);
    }

    /**
     * {@code pj_strtod} plus {@code init.cpp:691-700}'s {@code /denominator} step: a plain
     * double, or {@code numerator/denominator} evaluated as two doubles and one divide. The
     * association matters -- {@code 1/39.37} is 1 ulp from {@code 100/3937.0}.
     */
    private static double projStrtod(String s) {
        int slash = s.indexOf('/');
        if (slash < 0) {
            return Double.parseDouble(s);
        }
        return Double.parseDouble(s.substring(0, slash))
                / Double.parseDouble(s.substring(slash + 1));
    }

    /**
     * The scale each newly reachable unit applies, checked against PROJ rather than
     * against the constant in {@code Units} — otherwise a wrong constant would agree
     * with itself.
     */
    @Test
    public void theNewlyReachableUnitsScaleAsProjDoes() {
        expect(MERC + " +units=fath", 121740.475495706, 60465.860246199);
        expect(MERC + " +units=ch", 11067.315954155, 5496.896386018);
        expect(MERC + " +units=link", 1106731.595415509, 549689.638601813);
        expect(MERC + " +units=us-ch", 11067.293819523, 5496.885392225);
        expect(MERC + " +units=ind-yd", 243482.221125046, 120932.351340295);
        expect(MERC + " +units=ind-ft", 730446.663375137, 362797.054020884);
        expect(MERC + " +units=ind-ch", 11067.373687502, 5496.925060922);
    }

    /**
     * {@code ind-ft} and {@code us-ft} differ by about 7 parts per million, and
     * {@code ch}/{@code us-ch}/{@code ind-ch} by similar amounts. Since the old
     * behaviour was to return metres for all of them, this asserts they are now
     * distinct from each other and from metres — the property a per-value check alone
     * would not establish.
     */
    @Test
    public void theNewUnitsAreDistinctFromMetresAndFromEachOther() {
        String[] ids = {"fath", "ch", "link", "us-ch", "ind-yd", "ind-ft", "ind-ch"};
        for (String id : ids) {
            Unit unit = Units.findUnits(id);
            assertFalse(id + " must no longer fall back to metres", unit == Units.METRES);
            assertEquals(id, unit.abbreviation);
        }
        assertFalse("ind-ft and us-ft must not be the same unit",
                Units.findUnits("ind-ft").value == Units.findUnits("us-ft").value);
        assertFalse("ch and us-ch must not be the same unit",
                Units.findUnits("ch").value == Units.findUnits("us-ch").value);
        assertFalse("ch and ind-ch must not be the same unit",
                Units.findUnits("ch").value == Units.findUnits("ind-ch").value);
    }

    /**
     * The angular ids are <b>not</b> {@code +units} names. PROJ resolves {@code +units}
     * against {@code pj_list_linear_units()} only, so {@code +units=rad} is
     * {@code "Invalid value for units"} upstream — verified:
     * <pre>
     * $ echo "2 1" | proj +proj=merc +ellps=GRS80 +units=rad
     * merc: Invalid value for units
     * </pre>
     * They must therefore stay out of the {@code +units} lookup, or Proj4J would accept
     * definitions PROJ rejects.
     *
     * <p><b>{@code deg} is deliberately not checked here, and this test used to look like it
     * covered all three angular ids.</b> It does not: {@code Units.isKnownUnit("deg")} is
     * <b>true</b>, because {@link Units#DEGREES} is in {@link Units#units} for
     * {@code LongLatProjection} and the {@code geoapi} module, and it must stay true for
     * them. The refusal of {@code deg}/{@code degree}/{@code degrees} is therefore asserted
     * at the parse level instead — see
     * {@link #theThreeDegreeSpellingsAreRefusedAtParseLevel()}.
     */
    @Test
    public void angularUnitIdsAreNotLinearUnitNames() {
        assertFalse("+units=rad must not resolve", Units.isKnownUnit("rad"));
        assertFalse("+units=grad must not resolve", Units.isKnownUnit("grad"));
        // deg is the exception, and it is why the parse-level test above exists.
        assertTrue("isKnownUnit(\"deg\") is true by design; see the comment above",
                Units.isKnownUnit("deg"));
        for (Unit unit : Units.LINEAR_UNITS) {
            assertFalse("the linear table must not contain " + unit.abbreviation,
                    unit == Units.RADIANS || unit == Units.GRADS);
        }
        // ...but the constants exist, so a unitconvert-style caller can reach them.
        assertEquals(3, Units.ANGULAR_UNITS.length);
        assertEquals(0.9, Units.GRADS.value, 0.0);
    }

    // ------------------------------------------------------------------
    // +units resolves against the 21 ids and nothing else
    // ------------------------------------------------------------------

    /**
     * Parses a definition given as an argument array, so a {@code +units} value containing
     * a space can be tested. The whitespace-splitting entry point cannot express one.
     */
    private CoordinateReferenceSystem parseArgs(String... args) {
        return new Proj4Parser(new org.locationtech.proj4j.Registry()).parse("t", args);
    }

    private void rejectedArgs(String unitsValue) {
        try {
            parseArgs("+proj=merc", "+ellps=GRS80", "+units=" + unitsValue);
            fail("expected +units=" + unitsValue + " to be rejected");
        } catch (InvalidValueException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("Unknown unit: " + unitsValue));
        }
    }

    /**
     * <b>The realistic case, and the reason this whole area was wrong.</b> {@code ftUS} is an
     * ordinary misspelling of the U.S. survey foot. Proj4J used to answer in metres, so a
     * caller asking for feet got a number 1.14 million units from what they wanted with
     * nothing to signal it; PROJ refuses. Measured against 9.8.1 on
     * {@code +proj=utm +zone=18 +datum=WGS84} at (-75, 40):
     *
     * <pre>
     * $ echo "-75 40" | cs2cs -f '%.4f' +proj=longlat +datum=WGS84 \
     *       +to +proj=utm +zone=18 +datum=WGS84 +units=ftUS
     * proj_create: Error 1027 (Invalid value for an argument): utm: Invalid value for units
     * </pre>
     *
     * <p>Proj4J's answer there was {@code 500000.0000 4427757.2187} — the correct value in
     * <i>metres</i>, which is exactly what makes it dangerous: it is a plausible coordinate.
     * <p>
     * The message text is asserted verbatim because it is depended on outside this repo.
     */
    @Test
    public void anUnresolvableUnitsNameIsRefusedRatherThanBecomingMetres() {
        for (String bad : new String[]{"ftUS", "usft", "ft-us", "survey-ft", "BOGUS", "parsec"}) {
            InvalidValueException e = rejected(MERC + " +units=" + bad);
            assertEquals("the refusal must name the value, verbatim",
                    "Unknown unit: " + bad, e.getMessage());
        }
        // An empty value is the same error, not a silent default.
        assertEquals("Unknown unit: ", rejected(MERC + " +units=").getMessage());
    }

    /**
     * The refusal fires in the <b>default</b> parse mode, which is the whole point: it is
     * PROJ's own behaviour, not an opt-in stricter-than-PROJ check. {@code CRSFactory} only
     * ever selects {@code PROJ_COMPATIBLE}, so this goes through it.
     */
    @Test
    public void theRefusalFiresInTheDefaultParseModeNotOnlyInStrict() {
        assertEquals(Proj4Parser.ParseMode.PROJ_COMPATIBLE,
                new Proj4Parser(new org.locationtech.proj4j.Registry()).getParseMode());
        assertEquals("Unknown unit: ftUS", rejected(MERC + " +units=ftUS").getMessage());
        // ...and an id still works through the same default-mode entry point.
        assertSame(Units.US_FEET, crs(MERC + " +units=us-ft").getProjection().getUnits());
    }

    /**
     * <b>{@code deg}, {@code degree} and {@code degrees} produced a plausible wrong
     * coordinate, and {@code STRICT} did not catch it.</b>
     *
     * <p>{@link Units#DEGREES} is in {@link Units#units} because {@code LongLatProjection}
     * and the {@code geoapi} module look a unit up by the {@code deg}/{@code degrees}
     * symbol, and it carries name {@code degree}, plural {@code degrees} and abbreviation
     * {@code deg}. {@code Units.findUnits} matches all three, so {@code +units=deg} on a
     * projected operator used to resolve — measured at
     * {@code +proj=utm +zone=18 +datum=WGS84} (-75, 40) as {@code (0.0000, 39.7752)}, where
     * the answer in metres is {@code (500000.0000, 4427757.2187)}. That reads like a
     * longitude/latitude pair and is wrong in both ordinates: longitude 0 for a point at
     * -75. PROJ refuses all three.
     *
     * <p>{@code ParseMode.STRICT} did not help, because it asked
     * {@code Units.isKnownUnit("deg")}, which is <b>true</b> — the null-returning wrapper
     * never returned null, so the throw was never reached. That is why this is asserted at
     * the <b>parse</b> level: {@code isKnownUnit("deg")} is still true by design, and must
     * stay true for the WKT and geoapi callers.
     */
    @Test
    public void theThreeDegreeSpellingsAreRefusedAtParseLevel() {
        for (String spelling : new String[]{"deg", "degree", "degrees"}) {
            assertEquals("Unknown unit: " + spelling,
                    rejected(MERC + " +units=" + spelling).getMessage());
            // ...in STRICT too, which is where it used to slip through.
            try {
                new Proj4Parser(new org.locationtech.proj4j.Registry(),
                        Proj4Parser.ParseMode.STRICT)
                        .parse("t", (MERC + " +units=" + spelling).split(" "));
                fail("STRICT must reject +units=" + spelling);
            } catch (InvalidValueException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(spelling));
            }
        }
        // The reason STRICT could not catch it, pinned so the design stays deliberate:
        // these stay resolvable by NAME, for WktNames and geoapi/Units.
        assertTrue("isKnownUnit(\"deg\") must stay true - WktNames and geoapi need it",
                Units.isKnownUnit("deg"));
        assertTrue(Units.isKnownUnit("degree"));
        assertTrue(Units.isKnownUnit("degrees"));
        assertSame(Units.DEGREES, Units.findUnits("deg"));
        // ...but "deg" is not an accepted +units id.
        assertFalse(Units.linearUnitIds().contains("deg"));
    }

    /**
     * <b>A unit's name and plural are not ids.</b> PROJ resolves {@code +units} against the
     * {@code id} column of {@code pj_list_linear_units()} only, so {@code +units=feet} is an
     * error upstream while {@code +units=ft} is fine — verified, because the intuition runs
     * the other way and an earlier report had {@code feet} on its "correct, do not fix" list:
     *
     * <pre>
     * $ echo "-75 40" | cs2cs -f '%.4f' +proj=longlat +datum=WGS84 \
     *       +to +proj=utm +zone=18 +datum=WGS84 +units=feet
     * proj_create: Error 1027 (Invalid value for an argument): utm: Invalid value for units
     * $ ... +units=ft
     * 1640419.9475    14526762.5287 0.0000
     * </pre>
     *
     * <p>This part was <b>permissiveness rather than a wrong answer</b> — {@code +units=feet}
     * did get you feet — but it is still not parity, and every one of these used to resolve.
     *
     * <p>Enumerated from {@link Units#units} rather than hard-coded, so a unit added later
     * cannot quietly reintroduce a name spelling. The 18 spellings containing a space go
     * through the argument-array entry point, since a proj string cannot express one.
     */
    @Test
    public void unitNamesAndPluralsAreRefusedWhileTheIdIsAccepted() {
        // The single most likely one to be got wrong, called out by name.
        assertEquals("Unknown unit: feet", rejected(MERC + " +units=feet").getMessage());
        assertSame(Units.FEET, crs(MERC + " +units=ft").getProjection().getUnits());

        List<String> wronglyAccepted = new ArrayList<String>();
        List<String> wronglyRefused = new ArrayList<String>();
        int spaced = 0;
        int singleToken = 0;
        for (Unit unit : Units.units) {
            for (String spelling : new String[]{unit.name, unit.plural, unit.abbreviation}) {
                boolean shouldResolve = Units.linearUnitIds().contains(spelling);
                boolean resolved;
                try {
                    // Always the array entry point, so a spaced name is expressible.
                    parseArgs("+proj=merc", "+ellps=GRS80", "+units=" + spelling);
                    resolved = true;
                } catch (InvalidValueException e) {
                    resolved = false;
                }
                if (resolved && !shouldResolve) {
                    wronglyAccepted.add(spelling);
                } else if (!resolved && shouldResolve) {
                    wronglyRefused.add(spelling);
                }
                if (!shouldResolve) {
                    if (spelling.indexOf(' ') >= 0) {
                        spaced++;
                    } else {
                        singleToken++;
                    }
                }
            }
        }
        assertTrue("spellings PROJ refuses that we still accept: " + wronglyAccepted,
                wronglyAccepted.isEmpty());
        assertTrue("ids we must accept but refused: " + wronglyRefused,
                wronglyRefused.isEmpty());
        // Non-vacuity: the sweep must actually have had non-id spellings to refuse, in both
        // shapes. Counts are not pinned exactly, because adding a unit legitimately moves
        // them; a floor is enough to prove the loop is not walking an empty set.
        assertTrue("the sweep must cover single-token non-id spellings, got " + singleToken,
                singleToken >= 23);
        assertTrue("the sweep must cover spaced non-id spellings, got " + spaced, spaced >= 18);
    }

    /**
     * <b>The lookup is case-sensitive, and must stay that way.</b> PROJ is case-sensitive on
     * this key: {@code us-ft} resolves and {@code US-FT} does not. Proj4J's comparison missed
     * and fell through to metres, so {@code US-FT} — a genuine id in the wrong case — was a
     * <b>wrong answer</b>, not mere permissiveness.
     *
     * <p><b>Do not "fix" this by lower-casing the key.</b> That would resolve {@code US-FT}
     * but would also start accepting {@code M} and {@code Ft}, which PROJ refuses. All three
     * were measured against 9.8.1 and all three are
     * {@code Error 1027 ... utm: Invalid value for units}. Matching PROJ means refusing all
     * three, which is what a case-sensitive comparison does.
     */
    @Test
    public void theUnitsLookupIsCaseSensitiveExactlyAsProjIs() {
        for (String wrongCase : new String[]{"US-FT", "M", "Ft", "KM", "Us-Ft", "IND-YD"}) {
            assertEquals("Unknown unit: " + wrongCase,
                    rejected(MERC + " +units=" + wrongCase).getMessage());
        }
        // ...and the correctly-spelled ids are unaffected.
        assertSame(Units.US_FEET, crs(MERC + " +units=us-ft").getProjection().getUnits());
        assertSame(Units.METRES, crs(MERC + " +units=m").getProjection().getUnits());
        assertSame(Units.FEET, crs(MERC + " +units=ft").getProjection().getUnits());
        assertSame(Units.KILOMETRES, crs(MERC + " +units=km").getProjection().getUnits());
    }

    /**
     * A {@code +units} key with no value at all. {@code createParameterMap} stores
     * {@code null} for a token with no {@code '='}, which is <b>not</b> the same as the empty
     * string, so a bare {@code +units} used to skip the units block entirely and come out as
     * metres. PROJ refuses it:
     *
     * <pre>
     * $ echo "-75 40" | cs2cs -f '%.4f' +proj=longlat +datum=WGS84 \
     *       +to +proj=utm +zone=18 +datum=WGS84 +units
     * proj_create: Error 1027 (Invalid value for an argument): utm: Invalid value for units
     * </pre>
     *
     * <p>A present-but-valueless {@code +units} is distinct from an <i>absent</i> one, and
     * the difference is load-bearing: an absent {@code +units} is what lets {@code +to_meter}
     * apply, so this must not break that.
     */
    @Test
    public void aValuelessUnitsKeyIsRefusedButAnAbsentOneStillLetsToMeterApply() {
        rejected(MERC + " +units");
        rejected(MERC + " +units=");
        // The regression this guards: no +units at all must still hand over to +to_meter.
        expect(MERC + " +to_meter=0.3048", INTL_FOOT_X, INTL_FOOT_Y);
        expect(MERC, METRE_X, METRE_Y);
        // And a valueless +units must not be rescued by a +to_meter alongside it.
        rejected(MERC + " +units +to_meter=0.3048");
    }

    /**
     * {@link Units#linearUnitIds()} is the supported way to discover what {@code +units}
     * accepts, and it must not be able to drift from what the parser does — so every id it
     * reports is parsed here, through {@code +units=}, rather than merely compared against a
     * second hard-coded list.
     *
     * <p>The accessor exists because once the parser refuses everything outside these 21
     * ids, a caller has no other supported way to ask. {@code Units.LINEAR_UNITS} is not that
     * way: it is a fork-only public field absent from upstream 1.4.3, and a consumer who read
     * it from a static initialiser got {@code NoSuchFieldError} out of {@code <clinit>} on a
     * 1.4.3 classpath — an {@code Error}, so it escaped every {@code catch (Exception)} they
     * had.
     */
    @Test
    public void linearUnitIdsIsTheAcceptedSetAndCannotDriftFromTheParser() {
        assertEquals("PROJ's pj_units has 21 linear ids", 21, Units.linearUnitIds().size());

        // Every advertised id must actually parse, and yield the unit whose id it is.
        for (String id : Units.linearUnitIds()) {
            Unit resolved = crs(MERC + " +units=" + id).getProjection().getUnits();
            assertEquals("+units=" + id + " must resolve to the unit whose id it is",
                    id, resolved.abbreviation);
        }

        // Unmodifiable, so a caller cannot widen what the parser accepts by mutating it.
        try {
            Units.linearUnitIds().add("bananas");
            fail("linearUnitIds() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // as documented
        }
        assertEquals(21, Units.linearUnitIds().size());

        // It is the ids, not the names: the discriminating pair from PROJ's own table.
        assertTrue(Units.linearUnitIds().contains("ft"));
        assertFalse(Units.linearUnitIds().contains("feet"));
        assertFalse("deg is an angular id and is not a +units name",
                Units.linearUnitIds().contains("deg"));
    }

    /**
     * The refusal is not about {@code STRICT} any more — it fires in both modes — so this
     * now says what it checks: STRICT does not <i>additionally</i> refuse any id, and does
     * not stop refusing anything either. It was
     * {@code strictModeStillRejectsWhatProjRejects}, from when STRICT was the only mode that
     * refused an unresolvable unit.
     */
    @Test
    public void strictModeAcceptsAndRefusesExactlyWhatTheDefaultModeDoes() {
        org.locationtech.proj4j.Registry registry = new org.locationtech.proj4j.Registry();
        Proj4Parser strict = new Proj4Parser(registry, Proj4Parser.ParseMode.STRICT);
        Proj4Parser lax = new Proj4Parser(registry, Proj4Parser.ParseMode.PROJ_COMPATIBLE);

        // An A/B against one frozen input list: both modes must refuse the same values with
        // the same message, so nothing here is gated on the mode any more.
        for (String bad : new String[]{"rad", "grad", "furlong", "point", "min", "deg", "feet"}) {
            String definition = MERC + " +units=" + bad;
            String strictMessage = null;
            String laxMessage = null;
            try {
                strict.parse("t", definition.split(" "));
                fail("STRICT must reject +units=" + bad);
            } catch (InvalidValueException expected) {
                strictMessage = expected.getMessage();
            }
            try {
                lax.parse("t", definition.split(" "));
                fail("PROJ_COMPATIBLE must reject +units=" + bad + " too - PROJ does");
            } catch (InvalidValueException expected) {
                laxMessage = expected.getMessage();
            }
            assertTrue(strictMessage, strictMessage.contains(bad));
            assertEquals("both modes must give the same refusal", strictMessage, laxMessage);
        }

        for (String good : new String[]{"fath", "ch", "link", "us-ch", "ind-yd", "ind-ft", "ind-ch"}) {
            String definition = MERC + " +units=" + good;
            assertSame(good, Units.findUnits(good),
                    strict.parse("t", definition.split(" ")).getProjection().getUnits());
            assertSame(good, Units.findUnits(good),
                    lax.parse("t", definition.split(" ")).getProjection().getUnits());
        }
    }
}
