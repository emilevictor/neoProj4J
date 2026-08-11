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
 * {@code S} the seconds, {@code F} the arcseconds remaining inside the degree, {@code R} the raw
 * number, and {@code N} / {@code W} the hemisphere letters. Anything else in the pattern is
 * copied through as a literal, which is where the {@code d}, the apostrophe and the quote in
 * {@code "DdM'S\""} come from.
 *
 * <h2>Three defects are pinned here rather than fixed</h2>
 *
 * <p>Each is marked DEFECT below with the answer the code ought to give. They are deliberately
 * left unfixed in this change; the assertions exist so that whoever fixes one has a test to flip
 * and can see immediately what else moves.
 *
 * <ol>
 * <li>An angle between -1 and 0 degrees comes out positive.</li>
 * <li>Seconds that round up to a whole degree do not carry into the degree.</li>
 * <li>{@code AngleFormat.decimalPattern} does not produce a decimal number.</li>
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
     * DEFECT. The carry stops at the minute: an angle whose seconds round up to a whole degree
     * keeps the old degree count and resets the minutes to zero, so it prints one degree short.
     *
     * <p>The current behaviour is wrong. {@code 45.99999} degrees should print as {@code 46d00}
     * and prints as {@code 45d00} — an error of a whole degree, about 111 km on the ground. The
     * cause is that the degree field is truncated from the raw value ({@code (int) ddmmss}) while
     * the minutes and seconds are taken from the value after rounding to whole arcseconds, so the
     * two fields disagree about which degree they are in. The fix is to derive all three fields
     * from the same rounded arcsecond count.
     */
    @Test
    public void DEFECT_secondsThatRoundUpToAWholeDegreeDoNotCarry() {
        assertEquals("pins today's wrong answer; the right answer is 46d00",
                "45d00", format("DdM", 45.99999));
        assertEquals("pins today's wrong answer; the right answer is 1d00",
                "0d00", format("DdM", 0.9999));
        assertEquals("pins today's wrong answer; the right answer is 180d00'00\"",
                "179d00'00\"", format("DdM'S\"", 179.9999999));
        assertEquals("pins today's wrong answer; the right answer is 46d00'00\"N",
                "45d00'00\"N", format(AngleFormat.ddmmssLatPattern, 45.99999));
    }

    // ------------------------------------------------------------------
    // The sign defect
    // ------------------------------------------------------------------

    /**
     * DEFECT. Any angle strictly between -1 and 0 degrees is printed as if it were positive.
     *
     * <p>The current behaviour is wrong. {@code -0.5} degrees should print as {@code -0d30} and
     * prints as {@code 0d30}. The cause is that the sign only ever reaches the output through the
     * degree field, as the minus sign on {@code (int) ddmmss}; when the degree count is zero there
     * is no minus sign to print, and the minutes and seconds are taken from the absolute value.
     * The fix is to carry the sign separately from the degree count.
     *
     * <p>The boundary is exact: the sign survives for values at or below {@code -1.0}, because
     * {@code (int) -1.0} is {@code -1} and prints its own minus. It is lost for every value in
     * the open interval {@code (-1, 0)}. Patterns that carry a hemisphere letter are unaffected,
     * because there the sign is consumed before the arithmetic and re-emitted as {@code S} or
     * {@code W}.
     */
    @Test
    public void DEFECT_theSignIsLostBelowOneDegree() {
        assertEquals("pins today's wrong answer; the right answer is -0d30",
                "0d30", format("DdM", -0.5));
        assertEquals("pins today's wrong answer; the right answer is -0d30'00\"",
                "0d30'00\"", format("DdM'S\"", -0.5));
        assertEquals("pins today's wrong answer; the right answer is -0d00'58\"",
                "0d00'58\"", format("DdM'S\"", -0.016));
        assertEquals("this is the user-visible form of the same defect; the right answer is "
                + "-0d30 deg", "0d30 deg", Units.DEGREES.format(-0.5));
    }

    /** The boundary of the sign defect, stated as a pair so a fix cannot move it unnoticed. */
    @Test
    public void DEFECT_theSignSurvivesAtExactlyMinusOneDegreeAndIsLostJustInsideIt() {
        assertEquals("at or beyond -1 degree the degree field carries the sign itself",
                "-1d00", format("DdM", -1.0));
        assertEquals("-1d00'00\"", format("DdM'S\"", -1.0000001));

        // Just inside the boundary. Both defects fire at once here: the sign is dropped and the
        // 59.964 arcseconds round up to a degree that is never carried, so an angle of nearly one
        // degree west prints as zero. The right answer is -1d00.
        assertEquals("pins today's wrong answer; the right answer is -1d00",
                "0d00", format("DdM", -0.9999));
        assertEquals("pins today's wrong answer; the right answer is -0d59",
                "0d59", format("DdM", -0.99));
    }

    /**
     * DEFECT, same cause, reached through {@code Projection.getPROJ4Description()}.
     *
     * <p>That method builds an {@code AngleFormat} on {@code ddmmssPattern} in radian mode and
     * writes the result after {@code +lon_0=} and {@code +lat_0=}. A projection centred half a
     * degree south of the equator therefore describes itself as being half a degree north, and
     * the description does not read back as the projection it came from. Asserted here in radian
     * mode rather than through a projection so that the failure points at the formatter.
     */
    @Test
    public void DEFECT_theSignIsAlsoLostInRadianModeWhichIsWhatProj4DescriptionsUse() {
        AngleFormat asProjectionUsesIt = new AngleFormat(AngleFormat.ddmmssPattern, false);
        assertEquals("pins today's wrong answer; the right answer is -0d30",
                "0d30", asProjectionUsesIt.format(ProjectionMath.toRad(-0.5)));
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
     * DEFECT. {@code AngleFormat.decimalPattern} is named for decimal degrees but does not
     * produce them.
     *
     * <p>The current behaviour is wrong. {@code 45.5} degrees under the {@code "D.F"} pattern
     * should print as {@code 45.5} and prints as {@code 45.1800}, because {@code F} emits the
     * count of arcseconds inside the degree (1800 of them in half a degree) with no scaling and
     * no padding, and the {@code .} is only a literal. The number that comes out is not the angle
     * in any notation: it reads as forty-five and eighteen hundred ten-thousandths of a degree.
     * The fix is either to make {@code F} emit a fraction of a degree or to withdraw the
     * constant. Nothing in the repository formats with it today, which is why it has gone
     * unnoticed.
     */
    @Test
    public void DEFECT_theDecimalPatternIsNotDecimal() {
        assertEquals("pins today's wrong answer; the right answer is 45.5",
                "45.1800", format(AngleFormat.decimalPattern, 45.5));
        assertEquals("pins today's wrong answer; the right answer is 0.25",
                "0.900", format(AngleFormat.decimalPattern, 0.25));
        assertEquals("a whole number of degrees is the only case that reads correctly",
                "45.0", format(AngleFormat.decimalPattern, 45.0));
        assertEquals("pins today's wrong answer; the right answer is -122.25",
                "-122.900", format(AngleFormat.decimalPattern, -122.25));
    }
}
