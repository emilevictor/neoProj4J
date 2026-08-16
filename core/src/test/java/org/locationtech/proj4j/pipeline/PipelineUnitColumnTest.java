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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;

/**
 * Which of PROJ's two unit columns each key reads.
 *
 * <h2>What this pins, and why it is not obvious</h2>
 *
 * <p>{@code PJ_UNITS} carries the conversion to metres twice — {@code to_meter}, a
 * string, and {@code factor}, a double — and the two disagree on five of the twenty-one
 * linear rows ({@code 9.8.1:src/units.cpp:14-27}, {@code struct} at
 * {@code 9.8.1:src/proj.h:258}). Which field PROJ reads is decided by the <b>key</b>:
 *
 * <ul>
 * <li>{@code +units} ({@code init.cpp:689}) and {@code +vunits} ({@code :726}) read the
 *     <b>string</b>, via {@code s = units[i].to_meter} and then {@code pj_strtod(s)};</li>
 * <li>{@code +xy_in}, {@code +xy_out}, {@code +z_in} and {@code +z_out} read the
 *     <b>double</b>, via {@code get_unit_conversion_factor()}
 *     ({@code 9.8.1:src/conversions/unitconvert.cpp:396-434}).</li>
 * </ul>
 *
 * <p>A pipeline step is no exception. {@code pipeline.cpp:496} builds each step with
 * {@code pj_create_argv_internal}, which {@code create.cpp:304} forwards to
 * {@code pj_init_ctx_with_allow_init_epsg} — so a step's {@code +units} is
 * {@code init.cpp}'s string reader. Measured on the shipped 9.8.1 binary, with
 * {@code +units=m} accepting and {@code +units=rad} correctly erroring as controls:
 *
 * <pre>
 * $ echo "-134 55 0" | cct -d 12 +proj=cart +ellps=GRS80 +units=us-ft
 *   -8356380.535945920274 …
 * $ echo "-134 55 0" | cct -d 12 +proj=pipeline +step +proj=cart +ellps=GRS80 +units=us-ft
 *   -8356380.535945920274 …   # identical, and equal to +to_meter=0.304800609601219
 * $ echo "-134 55 0" | cct -d 12 +proj=pipeline +step +proj=cart +ellps=GRS80 +to_meter=1200/3937
 *   -8356380.535945915617 …   # the factor column, 3 ulps away
 * </pre>
 *
 * <h2>Why the tests below use {@code us-ft} and not {@code ft}</h2>
 *
 * <p><b>Sixteen of the twenty-one rows are bit-identical between the columns, so a test
 * written against {@code m}, {@code ft} or {@code km} passes whichever column the code
 * reads and proves nothing.</b> {@link #theTwoColumnsDisagreeOnExactlyFiveRows} is the
 * non-vacuity control for that: it asserts the split exists, names the five rows, and
 * asserts the other sixteen agree. If PROJ ever reconciles its table, that test fails
 * first and explains why every other test in this class has gone quiet.
 */
public class PipelineUnitColumnTest {

    /** The five ids whose two columns disagree, and by how many ulps ({@code factor - to_meter}). */
    private static final Object[][] SPLIT_ROWS = {
        {"us-in", -1L},
        {"us-ft", +3L},
        {"us-yd", -3L},
        {"us-ch", -1L},
        {"us-mi", +1L},
    };

    private static long ulpsApart(double a, double b) {
        return Double.doubleToRawLongBits(a) - Double.doubleToRawLongBits(b);
    }

    // ------------------------------------------------------- the non-vacuity control

    /**
     * The control that makes every other test in this class meaningful: the two columns
     * really do differ, on exactly these five ids and by exactly these amounts.
     */
    @Test
    public void theTwoColumnsDisagreeOnExactlyFiveRows() {
        int split = 0;
        for (Object[] row : SPLIT_ROWS) {
            String id = (String) row[0];
            long expectedUlps = (Long) row[1];
            PipelineUnits.Resolution u = PipelineUnits.resolve(id);
            assertTrue(id + " must be a known unit", u.isKnown());
            assertEquals(id + ": factor and to_meter must differ by this many ulps",
                    expectedUlps, ulpsApart(u.factor(), u.toMeter()));
            assertNotEquals(id + ": the two columns must not be the same double",
                    u.factor(), u.toMeter(), 0.0);
            split++;
        }
        assertEquals("PROJ has five split rows", 5, split);

        // And the other sixteen agree, which is why they cannot be used as evidence.
        String[] agreeing = {"km", "m", "dm", "cm", "mm", "kmi", "in", "ft", "yd", "mi",
            "fath", "ch", "link", "ind-yd", "ind-ft", "ind-ch"};
        assertEquals("21 linear rows, 5 split, 16 agreeing", 16, agreeing.length);
        for (String id : agreeing) {
            PipelineUnits.Resolution u = PipelineUnits.resolve(id);
            assertTrue(id + " must be a known unit", u.isKnown());
            assertEquals(id + ": these rows are bit-identical in both columns",
                    0L, ulpsApart(u.factor(), u.toMeter()));
        }

        // All three angular rows agree too: DEG_TO_RAD and GRAD_TO_RAD are #defined as
        // the very decimal literals the string column carries.
        for (String id : new String[] {"rad", "deg", "grad"}) {
            PipelineUnits.Resolution u = PipelineUnits.resolve(id);
            assertEquals(id + ": angular columns are bit-identical",
                    0L, ulpsApart(u.factor(), u.toMeter()));
        }
    }

    /**
     * {@code us-ft}'s two columns, spelled out, so that a swap of either array is a
     * failure that names the number it found.
     */
    @Test
    public void usSurveyFootCarriesBothOfProjsSpellings() {
        PipelineUnits.Resolution usFt = PipelineUnits.resolve("us-ft");
        assertEquals("PJ_UNITS.to_meter, i.e. strtod(\"0.304800609601219\")",
                0.304800609601219, usFt.toMeter(), 0.0);
        assertEquals("PJ_UNITS.factor, i.e. the C expression 1200 / 3937.0",
                1200 / 3937.0, usFt.factor(), 0.0);
    }

    /**
     * {@code us-in}'s string column is the ratio {@code "1/39.37"}, not the tidier
     * {@code 100 / 3937.0} — and the two are one ulp apart, so the spelling is the value.
     */
    @Test
    public void usSurveyInchStringColumnIsTheRatioAsWritten() {
        PipelineUnits.Resolution usIn = PipelineUnits.resolve("us-in");
        assertEquals("units.cpp writes \"1/39.37\"", 1 / 39.37, usIn.toMeter(), 0.0);
        assertEquals("the factor column writes 100 / 3937.0", 100 / 3937.0, usIn.factor(), 0.0);
        assertEquals("and those are not the same double", -1L,
                ulpsApart(usIn.factor(), usIn.toMeter()));
    }

    // ------------------------------------------------ +units reads the string column

    /**
     * {@code +units} on a step, i.e. {@code init.cpp:689}.
     *
     * <p>This is the assertion that fails if someone puts {@code factor()} back into
     * {@link LinearUnits}. It has to be a survey unit to bite at all.
     */
    @Test
    public void unitsReadsProjsStringColumn() {
        assertEquals("+units=us-ft is strtod(\"0.304800609601219\"), not 1200 / 3937.0",
                0.304800609601219,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-ft")), 0.0);
        assertNotEquals("+units must not read PJ_UNITS.factor",
                1200 / 3937.0,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-ft")), 0.0);

        // The same, on each of the other four split rows.
        assertEquals(1 / 39.37,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-in")), 0.0);
        assertEquals(0.914401828803658,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-yd")), 0.0);
        assertEquals(20.11684023368047,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-ch")), 0.0);
        assertEquals(1609.347218694437,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-mi")), 0.0);
    }

    /**
     * {@code +to_meter} is parsed text, so writing the factor column out by hand still
     * gets you the factor column — which is what makes the pair of legs above a real
     * discrimination rather than a tautology. {@code cct} agrees:
     * {@code +to_meter=1200/3937} gives {@code -8356380.535945915617} where
     * {@code +units=us-ft} gives {@code -8356380.535945920274}.
     */
    @Test
    public void toMeterIsWhateverTheUserWroteIncludingTheRatioForm() {
        assertEquals(1200 / 3937.0,
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +to_meter=1200/3937")), 0.0);
        assertEquals(0.304800609601219,
                LinearUnits.toMeter(
                        ProjParams.parse("+proj=cart +to_meter=0.304800609601219")), 0.0);
        assertNotEquals("the two spellings of us-ft are not the same double",
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +to_meter=1200/3937")),
                LinearUnits.toMeter(ProjParams.parse("+proj=cart +units=us-ft")), 0.0);
    }

    /** {@code +units} wins over {@code +to_meter}, and still on the string column. */
    @Test
    public void unitsBeatsToMeterAndStillReadsTheStringColumn() {
        assertEquals(0.304800609601219, LinearUnits.toMeter(
                ProjParams.parse("+proj=cart +to_meter=1200/3937 +units=us-ft")), 0.0);
    }

    // ----------------------------------------------- +vunits reads the string column

    /**
     * {@code +vunits} on a step, i.e. {@code init.cpp:726} — the same string column, and
     * the assertion that fails if {@code factor()} goes back into
     * {@code Cs2csOperator.verticalToMeter}.
     *
     * <p>Reached through {@link Cs2csOperator#verticalToMeter()} rather than the private
     * helper, so the test exercises the wiring and not just the table.
     */
    @Test
    public void vunitsReadsProjsStringColumn() {
        assertEquals("+vunits=us-ft is strtod(\"0.304800609601219\")",
                0.304800609601219, verticalToMeterOf("+proj=merc +ellps=GRS80 +vunits=us-ft"),
                0.0);
        assertNotEquals("+vunits must not read PJ_UNITS.factor",
                1200 / 3937.0, verticalToMeterOf("+proj=merc +ellps=GRS80 +vunits=us-ft"),
                0.0);
        assertEquals(0.914401828803658,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +vunits=us-yd"), 0.0);
    }

    /**
     * With no vertical key at all the vertical unit is the horizontal one
     * ({@code init.cpp:748-750}) — which {@code Cs2csOperator} takes from
     * {@code Projection.getFromMetres()}, i.e. from {@link Units}, i.e. from the string
     * column as well. So both routes to {@code P->vto_meter} land on the same column.
     *
     * <p>The zero deltas here are also a measurement worth keeping. That route is
     * {@code 1.0 / (1.0 / unit.value)} — {@code Proj4Parser:359} stores the reciprocal and
     * {@code Cs2csOperator} inverts it back — and a reciprocal of a reciprocal is not
     * guaranteed to return the double it started from. On all five survey rows, the ones
     * where a lost bit would matter, it does. If a JDK or a refactor ever breaks that,
     * this fails rather than drifting one ulp in silence.
     */
    @Test
    public void absentVerticalKeysFallBackToTheHorizontalStringColumn() {
        assertEquals(0.304800609601219,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +units=us-ft"), 0.0);
        assertEquals(1 / 39.37,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +units=us-in"), 0.0);
        assertEquals(0.914401828803658,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +units=us-yd"), 0.0);
        assertEquals(20.11684023368047,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +units=us-ch"), 0.0);
        assertEquals(1609.347218694437,
                verticalToMeterOf("+proj=merc +ellps=GRS80 +units=us-mi"), 0.0);
    }

    /** {@code +vunits} wins over {@code +vto_meter}, exactly as {@code +units} does. */
    @Test
    public void vunitsBeatsVtoMeter() {
        assertEquals(0.304800609601219, verticalToMeterOf(
                "+proj=merc +ellps=GRS80 +vto_meter=1200/3937 +vunits=us-ft"), 0.0);
    }

    private static double verticalToMeterOf(String definition) {
        Cs2csOperator op = new Cs2csOperator(
                new org.locationtech.proj4j.Registry(), ProjParams.parse(definition));
        return op.verticalToMeter();
    }

    // ------------------------------------- unitconvert must keep reading the factor

    /**
     * The other half of the point: {@code +proj=unitconvert} reads {@code factor} and
     * <b>must keep doing so</b>. This is not the same bug in the other direction — each
     * key reads the column PROJ reads for that key.
     *
     * <p>{@code get_unit_conversion_factor()} returns {@code units[i].factor}, so
     * {@code +xy_in=us-ft +xy_out=m} scales by {@code 1200 / 3937.0} and not by the
     * string. {@code UnitConvertOperatorTest.footAndUsSurveyFootAreNotTheSame} pins the
     * table; this pins the wiring.
     */
    @Test
    public void unitconvertStillReadsProjsFactorColumn() {
        UnitConvertOperator op =
                new UnitConvertOperator(ProjParams.parse("+proj=unitconvert +xy_in=us-ft +xy_out=m"));
        double[] c = {1.0, 1.0, 0.0, 0.0};
        op.forward(c);
        assertEquals("unitconvert scales by PJ_UNITS.factor", 1200 / 3937.0, c[0], 0.0);
        assertNotEquals("and not by PJ_UNITS.to_meter", 0.304800609601219, c[0], 0.0);
    }

    // --------------------------------------------- the two transcriptions must agree

    /**
     * {@code PipelineUnits}' string column and {@link Units}' table are two independent
     * transcriptions of the same twenty-one PROJ literals, written in different packages
     * for different callers. They must agree bitwise — that is what makes either one
     * trustworthy, and it means a typo has to be made twice to survive.
     *
     * <p>{@link Units} is the {@code +units} path through {@code Proj4Parser}; this class
     * is the {@code +units} path through the pipeline engine. They were measured to be
     * the same column; this keeps them that way.
     */
    @Test
    public void unitsTableAndPipelineStringColumnAgreeOnAllTwentyOneRows() {
        assertEquals("PROJ has 21 linear units", 21, Units.LINEAR_UNITS.length);
        int compared = 0;
        for (Unit unit : Units.LINEAR_UNITS) {
            PipelineUnits.Resolution u = PipelineUnits.resolve(unit.abbreviation);
            assertTrue(unit.abbreviation + " must resolve in PipelineUnits", u.isKnown());
            assertEquals(unit.abbreviation
                            + ": units.Units and PipelineUnits' string column must be bit-identical",
                    0L, ulpsApart(unit.value, u.toMeter()));
            compared++;
        }
        assertEquals(21, compared);
    }

    /**
     * And the same comparison against {@code factor()} <b>fails on five rows</b> — the
     * control proving the test above is a real bitwise comparison and not one that would
     * pass against either column.
     */
    @Test
    public void theSameComparisonAgainstTheFactorColumnWouldFailOnFiveRows() {
        int disagreeing = 0;
        for (Unit unit : Units.LINEAR_UNITS) {
            PipelineUnits.Resolution u = PipelineUnits.resolve(unit.abbreviation);
            if (ulpsApart(unit.value, u.factor()) != 0L) {
                disagreeing++;
            }
        }
        assertEquals("units.Units differs from the factor column on exactly the 5 survey rows",
                5, disagreeing);
    }
}
