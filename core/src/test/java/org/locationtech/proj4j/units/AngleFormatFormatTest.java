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
package org.locationtech.proj4j.units;

import java.text.FieldPosition;

import org.junit.Test;
import org.locationtech.proj4j.util.ProjectionMath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The formatting half of {@link AngleFormat}: every pattern letter the class understands, both
 * unit modes, the hemisphere suffixes, and the rounding boundaries.
 *
 * <h2>What this pins</h2>
 *
 * <p>{@code AngleFormat.format} is the only thing that turns an internal angle back into text.
 * Two public methods depend on it: {@code Units.DEGREES.format(...)}, which is how a degree
 * measurement is shown to a person, and {@code Projection.getPROJ4Description()}, which writes
 * {@code +lon_0=} and {@code +lat_0=} into a PROJ.4 string that is meant to be readable back in.
 * Before this file existed the whole method was measured at 29.2% instruction coverage, so a
 * change to the pattern loop could alter either of those without any test failing.
 *
 * <p>Each pattern letter is asserted separately, so a failure says which letter moved rather than
 * just that some string changed. {@code D} is the degree count, {@code M} the minutes,
 * {@code S} the seconds, {@code F} the fraction of a degree left below the degree count,
 * {@code R} the raw number, and {@code N} / {@code W} the hemisphere letters. Anything else in the pattern is
 * copied through as a literal, which is where the {@code d}, the apostrophe and the quote in
 * {@code "DdM'S\""} come from.
 *
 * <h2>Three defects that used to be pinned here, and are now fixed</h2>
 *
 * <p>Each was marked DEFECT with the answer the code ought to give, and each has since been given
 * it. The assertions below are the flipped versions and are now references rather than markers.
 *
 * <ol>
 * <li>An angle between -1 and 0 degrees came out positive, because the sign reached the output
 *     only as the minus on {@code (int) ddmmss} and that field is zero for every such angle. The
 *     sign is now carried separately —
 *     {@link #theSignSurvivesBelowOneDegree()}.</li>
 * <li>Seconds that round up to a whole degree did not carry into the degree, because the degree
 *     field was truncated from the raw value while the minutes and seconds came from the value
 *     after rounding to whole arcseconds. All three fields now come from one rounded arcsecond
 *     count — {@link #secondsThatRoundUpToAWholeDegreeCarryIntoIt()}.</li>
 * <li>{@code AngleFormat.decimalPattern} did not produce a decimal number, because {@code F}
 *     emitted the arcsecond count unscaled. {@code F} now emits the fraction of a degree —
 *     {@link #theDecimalPatternIsDecimal()}.</li>
 * </ol>
 *
 * <p>If this file is deleted, the pattern loop goes back to being exercised only by
 * {@code DegreeUnitFormatTest}'s handful of {@code 45.5} strings, which use three of the six
 * pattern letters and no negative value below one degree.
 *
 * @see DegreeUnitFormatTest for the separate trap that {@code DegreeUnit.format} shadows
 *      {@code Unit.format} rather than overriding it
 */
public class AngleFormatFormatTest {

    /** Degrees in, degrees out: what {@code DegreeUnit} uses. */
    private static AngleFormat degrees(String pattern) {
        return new AngleFormat(pattern, true);
    }

    private static String format(String pattern, double degrees) {
        return degrees(pattern).format(degrees);
    }

    // ------------------------------------------------------------------
    // The patterns the class ships as constants
    // ------------------------------------------------------------------

    @Test
    public void theShippedPatternsAreTheOnesCallersCompileAgainst() {
        // These are public constants; changing one silently re-formats every caller's output.
        assertEquals("DdM", AngleFormat.ddmmssPattern);
        assertEquals("DdM'S\"", AngleFormat.ddmmssPattern2);
        assertEquals("DdM'S\"W", AngleFormat.ddmmssLongPattern);
        assertEquals("DdM'S\"N", AngleFormat.ddmmssLatPattern);
        assertEquals("DdMmSs", AngleFormat.ddmmssPattern4);
        assertEquals("D.F", AngleFormat.decimalPattern);
    }

    @Test
    public void degreesAndMinutes() {
        assertEquals("zero must still print both fields", "0d00", format("DdM", 0));
        assertEquals("0d30", format("DdM", 0.5));
        assertEquals("45d30", format("DdM", 45.5));
        assertEquals("122d15", format("DdM", 122.25));
        assertEquals("minutes below ten must be zero-padded", "1d05", format("DdM", 1.0 + 5.0 / 60));
    }

    @Test
    public void degreesMinutesAndSeconds() {
        assertEquals("0d00'00\"", format("DdM'S\"", 0));
        assertEquals("45d30'00\"", format("DdM'S\"", 45.5));
        assertEquals("12d34'57\"", format("DdM'S\"", 12.5825));
        assertEquals("seconds below ten must be zero-padded",
                "0d00'05\"", format("DdM'S\"", 5.0 / 3600));
    }

    @Test
    public void theLetterAbbreviationPatternUsesDmsRatherThanPunctuation() {
        assertEquals("12d34m57s", format("DdMmSs", 12.5825));
        assertEquals("0d00m00s", format("DdMmSs", 0));
    }

    @Test
    public void charactersOutsideThePatternAlphabetAreCopiedThrough() {
        assertEquals("[45]", format("[D]", 45.5));
        assertEquals("45 deg 30 min", format("D deg M min", 45.5));
        assertEquals("an empty pattern must produce an empty string, not an exception",
                "", format("", 45.5));
    }

    @Test
    public void theRawValuePatternPrintsTheNumberUntouched() {
        // 'R' appends the double itself. It is the only pattern letter that does not go through
        // the degrees/minutes/seconds arithmetic.
        assertEquals("45.5", format("R", 45.5));
        assertEquals("-45.5", format("R", -45.5));
    }

    // ------------------------------------------------------------------
    // Hemisphere suffixes
    // ------------------------------------------------------------------

    @Test
    public void theLatitudePatternWritesNorthOrSouthInsteadOfASign() {
        assertEquals("45d30'00\"N", format(AngleFormat.ddmmssLatPattern, 45.5));
        assertEquals("45d30'00\"S", format(AngleFormat.ddmmssLatPattern, -45.5));
        assertEquals("zero is north by convention here, not signless",
                "0d00'00\"N", format(AngleFormat.ddmmssLatPattern, 0));
    }

    @Test
    public void theLongitudePatternWritesEastOrWestInsteadOfASign() {
        assertEquals("122d15'00\"E", format(AngleFormat.ddmmssLongPattern, 122.25));
        assertEquals("122d15'00\"W", format(AngleFormat.ddmmssLongPattern, -122.25));
    }

    @Test
    public void aHemispherePatternAbsorbsTheMinusSignRatherThanPrintingBoth() {
        // The negative branch flips the number to positive only when it finds an 'N' or a 'W'
        // anywhere in the pattern, so "45d30'00\"S" must never come out as "-45d30'00\"S".
        assertEquals("45d30'00\"S", format(AngleFormat.ddmmssLatPattern, -45.5));
        assertEquals("the hemisphere letter may sit anywhere in the pattern",
                "S45d30", format("NDdM", -45.5));
        assertEquals("45.5S", format("RN", -45.5));
    }

    @Test
    public void withoutAHemisphereLetterTheMinusSignIsPrinted() {
        assertEquals("-45d30", format("DdM", -45.5));
        assertEquals("-122d15'00\"", format("DdM'S\"", -122.25));
        assertEquals("-1d00", format("DdM", -1.0));
        assertEquals("-1d30m00s", format("DdMmSs", -1.5));
    }

    // ------------------------------------------------------------------
    // Rounding
    // ------------------------------------------------------------------

    @Test
    public void secondsThatRoundUpCarryIntoTheMinute() {
        // 45 degrees 0 minutes 59.6 seconds. The whole angle is rounded to a whole number of
        // arcseconds first, so the carry out of the seconds field is correct.
        double justUnderAMinute = 45.0 + 59.6 / 3600;
        assertEquals("45d01", format("DdM", justUnderAMinute));
        assertEquals("45d01'00\"", format("DdM'S\"", justUnderAMinute));
    }

    @Test
    public void fractionalSecondsAreRoundedNotTruncated() {
        assertEquals("56.9996 arcseconds must round to 57, not truncate to 56",
                "12d34'57\"", format("DdM'S\"", 12.5824999));
        assertEquals("0d00'01\"", format("DdM'S\"", 0.6 / 3600));
        assertEquals("half an arcsecond rounds away from zero",
                "0d00'01\"", format("DdM'S\"", 0.5 / 3600));
    }

    /**
     * The carry reaches the degree. An angle whose seconds round up to a whole degree increments
     * the degree count instead of keeping the old one and resetting the minutes to zero.
     *
     * <p>This used to be wrong by a whole degree, about 111 km on the ground: {@code 45.99999}
     * printed as {@code 45d00}. The degree field was truncated from the raw value
     * ({@code (int) ddmmss}) while the minutes and seconds were taken from the value after
     * rounding to whole arcseconds, so the two disagreed about which degree they were in. All
     * three fields now come from one rounded arcsecond count.
     */
    @Test
    public void secondsThatRoundUpToAWholeDegreeCarryIntoIt() {
        assertEquals("46d00", format("DdM", 45.99999));
        assertEquals("0.9999 degrees is 3599.64 arcseconds, which rounds to a whole degree",
                "1d00", format("DdM", 0.9999));
        assertEquals("180d00'00\"", format("DdM'S\"", 179.9999999));
        assertEquals("the carry must survive the hemisphere patterns too",
                "46d00'00\"N", format(AngleFormat.ddmmssLatPattern, 45.99999));
    }

    // ------------------------------------------------------------------
    // The sign defect
    // ------------------------------------------------------------------

    /**
     * An angle strictly between -1 and 0 degrees keeps its sign.
     *
     * <p>This used to print as if positive — {@code -0.5} came out as {@code 0d30} — because the
     * sign only ever reached the output through the degree field, as the minus on
     * {@code (int) ddmmss}. When the degree count is zero there is no minus to print, and the
     * minutes and seconds were taken from the absolute value. The sign is now carried separately
     * from the degree count.
     *
     * <p>Patterns that carry a hemisphere letter were never affected, because there the sign is
     * consumed before the arithmetic and re-emitted as {@code S} or {@code W}; that contrast is
     * asserted in {@link #aHemispherePatternAbsorbsTheMinusSignRatherThanPrintingBoth()}.
     */
    @Test
    public void theSignSurvivesBelowOneDegree() {
        assertEquals("-0d30", format("DdM", -0.5));
        assertEquals("-0d30'00\"", format("DdM'S\"", -0.5));
        assertEquals("-0d00'58\"", format("DdM'S\"", -0.016));
        assertEquals("this is the user-visible form of the same fix",
                "-0d30 deg", Units.DEGREES.format(-0.5));
    }

    /**
     * The boundary at one degree, stated as a pair so a regression cannot move it unnoticed. It
     * used to be a real discontinuity — the sign survived at {@code -1.0} and vanished just
     * inside it — and is now simply continuous.
     */
    @Test
    public void theSignIsContinuousAcrossTheOneDegreeBoundary() {
        assertEquals("-1d00", format("DdM", -1.0));
        assertEquals("-1d00'00\"", format("DdM'S\"", -1.0000001));

        // Just inside the boundary, where both defects used to fire at once: the sign was dropped
        // and the 59.964 arcseconds rounded up to a degree that was never carried, so an angle of
        // nearly one degree west printed as "0d00".
        assertEquals("-1d00", format("DdM", -0.9999));
        assertEquals("-0d59", format("DdM", -0.99));
    }

    /**
     * An angle too small to round to a single arcsecond is printed without a sign, so a tiny
     * negative is {@code 0d00} and never {@code -0d00}.
     *
     * <p>Pinned because it is the one case the sign fix deliberately does not cover: below half an
     * arcsecond every field is zero and a minus sign in front of them would claim a direction the
     * output has no magnitude to support.
     */
    @Test
    public void anAngleThatRoundsAwayEntirelyIsPrintedWithoutASign() {
        assertEquals("0d00", format("DdM", -0.4 / 3600));
        assertEquals("0d00'00\"", format("DdM'S\"", -0.0));
        assertEquals("0d00'00\"", format("DdM'S\"", 0.0));
    }

    /**
     * The same fix, reached through {@code Projection.getPROJ4Description()}.
     *
     * <p>That method builds an {@code AngleFormat} on {@code ddmmssPattern} in radian mode and
     * writes the result after {@code +lon_0=} and {@code +lat_0=}. A projection centred half a
     * degree south of the equator used to describe itself as being half a degree north, so the
     * description did not read back as the projection it came from. Asserted here in radian mode
     * rather than through a projection so that a failure points at the formatter.
     */
    @Test
    public void theSignSurvivesInRadianModeWhichIsWhatProj4DescriptionsUse() {
        AngleFormat asProjectionUsesIt = new AngleFormat(AngleFormat.ddmmssPattern, false);
        assertEquals("-0d30", asProjectionUsesIt.format(ProjectionMath.toRad(-0.5)));
    }

    // ------------------------------------------------------------------
    // The two unit modes
    // ------------------------------------------------------------------

    @Test
    public void theDefaultConstructorTakesRadiansAndConvertsThemToDegrees() {
        AngleFormat radians = new AngleFormat();
        assertEquals("180d00", radians.format(Math.PI));
        assertEquals("45d00", radians.format(Math.PI / 4));
        assertEquals("-45d00", radians.format(-Math.PI / 4));
        assertEquals("one radian and a bit, in degrees and minutes",
                "28d38", radians.format(0.5));
    }

    @Test
    public void theSinglePatternConstructorAlsoDefaultsToRadians() {
        // new AngleFormat(pattern) means isDegrees == false. Passing degrees to it silently
        // scales them by 180/pi, so this asserts which default a caller is getting.
        assertEquals("45d00", new AngleFormat(AngleFormat.ddmmssPattern).format(Math.PI / 4));
        assertEquals("the same number read as degrees is a different angle",
                "0d47", degrees(AngleFormat.ddmmssPattern).format(Math.PI / 4));
    }

    @Test
    public void theDefaultPatternIsDegreesAndMinutes() {
        assertEquals("45d00", new AngleFormat().format(Math.PI / 4));
    }

    // ------------------------------------------------------------------
    // The NumberFormat contract
    // ------------------------------------------------------------------

    @Test
    public void theLongOverloadGoesThroughTheDoubleOne() {
        assertEquals("45d00", degrees(AngleFormat.ddmmssPattern).format(45L));
        assertEquals("-45d00", degrees(AngleFormat.ddmmssPattern).format(-45L));
    }

    @Test
    public void formattingAppendsToTheCallersBufferAndReturnsIt() {
        // This is the shape Projection.getPROJ4Description() uses: an existing buffer and a null
        // FieldPosition. A NumberFormat is not required to tolerate a null FieldPosition, so the
        // fact that this one does is load-bearing for that caller.
        StringBuffer buffer = new StringBuffer("+lat_0=");
        StringBuffer returned = degrees(AngleFormat.ddmmssPattern).format(45.5, buffer, null);
        assertSame("the caller's buffer must be the one returned", buffer, returned);
        assertEquals("+lat_0=45d30", buffer.toString());

        StringBuffer withPosition = new StringBuffer();
        degrees(AngleFormat.ddmmssPattern).format(45.5, withPosition, new FieldPosition(0));
        assertEquals("a supplied FieldPosition is ignored, not honoured", "45d30",
                withPosition.toString());
    }

    // ------------------------------------------------------------------
    // The decimal pattern
    // ------------------------------------------------------------------

    /**
     * {@code AngleFormat.decimalPattern} produces decimal degrees, which is what its name says.
     *
     * <p>It used not to. {@code 45.5} degrees under {@code "D.F"} printed as {@code 45.1800},
     * because {@code F} emitted the count of arcseconds inside the degree (1800 of them in half a
     * degree) with no scaling, and the {@code .} is only a literal. What came out was not the
     * angle in any notation: it read as forty-five and eighteen hundred ten-thousandths of a
     * degree. {@code F} now emits the fraction of a degree instead.
     */
    @Test
    public void theDecimalPatternIsDecimal() {
        assertEquals("45.5", format(AngleFormat.decimalPattern, 45.5));
        assertEquals("0.25", format(AngleFormat.decimalPattern, 0.25));
        assertEquals("a whole number of degrees keeps a single trailing zero rather than ending "
                + "on the separator", "45.0", format(AngleFormat.decimalPattern, 45.0));
        assertEquals("-122.25", format(AngleFormat.decimalPattern, -122.25));
    }

    /**
     * The fraction is carried to four decimal places, which is the most an arcsecond count can
     * justify: one arcsecond is 0.000277… of a degree, so four places distinguish neighbouring
     * arcseconds and a fifth would be inventing precision the rounded count no longer has.
     */
    @Test
    public void theDecimalFractionIsFourPlacesAndTrailingZerosAreDropped() {
        assertEquals("one arcsecond", "0.0003", format(AngleFormat.decimalPattern, 1.0 / 3600));
        assertEquals("0.5", format(AngleFormat.decimalPattern, 0.5));
        assertEquals("two arcseconds are 0.000555… of a degree and round up, not down",
                "0.0006", format(AngleFormat.decimalPattern, 2.0 / 3600));
        assertEquals("the fraction is padded on the left, not the right",
                "45.0167", format(AngleFormat.decimalPattern, 45.0 + 1.0 / 60));
    }
}
