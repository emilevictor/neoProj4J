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

import java.text.ParseException;
import java.text.ParsePosition;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The reading half of {@link AngleFormat}, and its near-duplicate {@link Angle#parse(String)}.
 *
 * <h2>What this pins</h2>
 *
 * <p>{@code Angle.parse} is how every angular PROJ.4 parameter gets its value:
 * {@code Proj4Parser} routes {@code +lat_0}, {@code +lon_0}, {@code +lat_ts} and the rest through
 * it, so a change in what these two methods accept changes which CRS definitions the library can
 * read. {@code AngleFormat.parse} is the same algorithm copied into a {@link java.text.NumberFormat},
 * reached through {@code Units.DEGREES.parse(String)}.
 *
 * <p>The two bodies are line-for-line alike apart from one guard, which is exactly the sort of
 * near-duplication that invites someone to delete one and forward it to the other. This file
 * measures how far apart they actually are, so that such a merge is a visible change rather than
 * a silent one: {@link #theTwoParsersAgreeOnEverythingExceptADegreesOnlyString()} holds both
 * against the same table of inputs, and the tests marked DEFECT record where they part company.
 *
 * <h2>Defects pinned rather than fixed</h2>
 *
 * <p>Each is marked DEFECT with the answer the code ought to give. They stay unfixed in this
 * change; the assertions exist so a fix has a test to flip.
 *
 * <ol>
 * <li>{@code Angle.parse("123d")} returns 123 while {@code AngleFormat.parse("123d")} throws.</li>
 * <li>A trailing {@code s} — the class's own seconds abbreviation — is read as the southern
 *     hemisphere, so anything written with {@code ddmmssPattern4} reads back negated.</li>
 * <li>{@code "-0d30"} parses as positive: below one degree the minus sign is lost, the mirror of
 *     the formatting defect pinned in {@code AngleFormatFormatTest}.</li>
 * <li>Fractional minutes between 59 and 60 are rejected, although the matching seconds check
 *     allows them.</li>
 * <li>{@link ParsePosition} is left one character short when a hemisphere letter was consumed.</li>
 * </ol>
 *
 * <p>If this file is deleted, {@code AngleFormat.parse} returns to having no direct test at all
 * and {@code Angle.parse} to being covered only incidentally, through the coordinates in the
 * transform test corpus.
 */
public class AngleFormatParseTest {

    /** Degrees in, degrees out: the configuration {@code DegreeUnit} uses. */
    private static AngleFormat degreeParser() {
        return new AngleFormat(AngleFormat.ddmmssPattern, true);
    }

    /** The default configuration: text in degrees, value out in radians. */
    private static AngleFormat radianParser() {
        return new AngleFormat();
    }

    private static double parseAsDegrees(String text) throws ParseException {
        return degreeParser().parse(text).doubleValue();
    }

    /** Slack for the divisions by 60 and 3600; far tighter than an arcsecond. */
    private static final double TOLERANCE = 1e-12;

    // ------------------------------------------------------------------
    // The forms both parsers accept
    // ------------------------------------------------------------------

    @Test
    public void plainDecimalDegrees() throws ParseException {
        assertEquals(123.0, Angle.parse("123"), 0.0);
        assertEquals(123.12, Angle.parse("123.12"), 0.0);
        assertEquals(-123.12, Angle.parse("-123.12"), 0.0);
        assertEquals("a leading plus is accepted", 123.12, Angle.parse("+123.12"), 0.0);
        assertEquals("exponent notation reaches Double.parseDouble untouched",
                100.0, Angle.parse("1e2"), 0.0);
        assertEquals("surrounding whitespace is tolerated", 123.0, Angle.parse("  123 "), 0.0);

        assertEquals(123.12, parseAsDegrees("123.12"), 0.0);
        assertEquals(-123.12, parseAsDegrees("-123.12"), 0.0);
    }

    @Test
    public void degreesAndMinutes() throws ParseException {
        assertEquals(123.5, Angle.parse("123d30m"), TOLERANCE);
        assertEquals("the apostrophe is accepted for minutes as well as 'm'",
                123.5, Angle.parse("123d30'"), TOLERANCE);
        assertEquals("the degree sign is accepted as well as 'd'",
                123.5, Angle.parse("123°30m"), TOLERANCE);
        assertEquals("a bare number after the degree letter is read as minutes",
                123.5, Angle.parse("123d30"), TOLERANCE);

        assertEquals(123.5, parseAsDegrees("123d30m"), TOLERANCE);
        assertEquals(123.5, parseAsDegrees("123°30m"), TOLERANCE);
    }

    @Test
    public void degreesMinutesAndSeconds() throws ParseException {
        assertEquals(123.50416666666666, Angle.parse("123d30'15\""), TOLERANCE);
        assertEquals("seconds may be fractional",
                123.74570972222223, Angle.parse("123d44'44.555\""), TOLERANCE);
        assertEquals("the closing quote is optional",
                123.50416666666666, Angle.parse("123d30'15"), TOLERANCE);

        assertEquals(123.50416666666666, parseAsDegrees("123d30'15\""), TOLERANCE);
    }

    @Test
    public void anEmptyMinutesFieldIsReadAsZero() throws ParseException {
        // The minute letter immediately after the degree letter: the "i == 0" branch, which is
        // the one path through the sexagesimal block that neither parser gets wrong.
        assertEquals(123.0, Angle.parse("123dm"), 0.0);
        assertEquals(123.0, Angle.parse("123d'"), 0.0);
        assertEquals(123.0, parseAsDegrees("123dm"), 0.0);
        assertEquals(123.0, parseAsDegrees("123d'"), 0.0);
    }

    @Test
    public void fractionalDegreesAndMinutesAreSimplyAddedTogether() throws ParseException {
        // Not a form anyone should write, but it is accepted, and accepting it is what makes the
        // "Minutes must be between 0 and 59" check the only guard on the value.
        assertEquals(124.0, Angle.parse("123.5d30m"), TOLERANCE);
        assertEquals(124.0, parseAsDegrees("123.5d30m"), TOLERANCE);
    }

    // ------------------------------------------------------------------
    // Hemisphere suffixes
    // ------------------------------------------------------------------

    @Test
    public void theHemisphereSuffixesSetTheSign() throws ParseException {
        assertEquals("N leaves the value positive", 45.5, Angle.parse("45d30'00\"N"), TOLERANCE);
        assertEquals("S negates", -45.5, Angle.parse("45d30'00\"S"), TOLERANCE);
        assertEquals("E leaves the value positive", 123.12, Angle.parse("123.12E"), 0.0);
        assertEquals("W negates", -123.12, Angle.parse("123.12W"), 0.0);

        assertEquals(-45.5, parseAsDegrees("45d30'00\"S"), TOLERANCE);
        assertEquals(-123.12, parseAsDegrees("123.12W"), 0.0);
    }

    @Test
    public void hemisphereSuffixesAreCaseInsensitive() throws ParseException {
        assertEquals(123.12, Angle.parse("123.12n"), 0.0);
        assertEquals(123.12, Angle.parse("123.12e"), 0.0);
        assertEquals(-123.5, Angle.parse("123d30mw"), TOLERANCE);
        assertEquals(-123.5, parseAsDegrees("123d30mw"), TOLERANCE);
    }

    @Test
    public void aHemisphereSuffixOnAnAlreadyNegativeValueNegatesItAgain() throws ParseException {
        // Nobody should write "-45.5S", but pinning it says the suffix is applied last and
        // unconditionally rather than being reconciled with an explicit sign.
        assertEquals(45.5, Angle.parse("-45.5S"), 0.0);
        assertEquals(45.5, parseAsDegrees("-45.5S"), 0.0);
    }

    // ------------------------------------------------------------------
    // Radian mode
    // ------------------------------------------------------------------

    @Test
    public void theDefaultAngleFormatReadsDegreesAndReturnsRadians() throws ParseException {
        assertEquals(Math.toRadians(123.5), radianParser().parse("123d30m").doubleValue(), TOLERANCE);
        assertEquals(Math.toRadians(123.12), radianParser().parse("123.12").doubleValue(), TOLERANCE);
        assertEquals("the hemisphere suffix is applied after the conversion",
                -Math.toRadians(123.12), radianParser().parse("123.12W").doubleValue(), TOLERANCE);
    }

    @Test
    public void unitModeChangesTheValueButNotWhatIsAccepted() throws ParseException {
        assertEquals(123.5, parseAsDegrees("123d30m"), TOLERANCE);
        assertNotEquals("the two modes must not be returning the same number",
                123.5, radianParser().parse("123d30m").doubleValue(), 1e-6);
    }

    // ------------------------------------------------------------------
    // DEFECT 1: the two parsers disagree
    // ------------------------------------------------------------------

    /**
     * DEFECT. {@code "123d"} — a whole number of degrees with nothing after the degree letter —
     * is read by {@link Angle} and rejected by {@link AngleFormat}.
     *
     * <p>The current behaviour is wrong on the {@code AngleFormat} side. {@code "123d"} is a
     * well-formed angle of 123 degrees and both should return 123. {@code Angle.parse} does,
     * because it guards the minutes field with {@code if (mmss.length() == 0) m = 0;}
     * (Angle.java:88-90). {@code AngleFormat.parse} has the identical code with that guard
     * missing (AngleFormat.java:193) and so hands an empty string to {@code Double.valueOf},
     * which throws {@code NumberFormatException: empty String}. The fix is to copy the guard
     * across — or better, to delete the duplicated body and have one call the other.
     *
     * <p>It is not only the {@code d} form: every degrees-only string is affected, including the
     * degree-sign spelling and the form with a hemisphere letter.
     */
    @Test
    public void DEFECT_aDegreesOnlyStringIsReadByAngleAndRejectedByAngleFormat() {
        String[] degreesOnly = {"123d", "123°", "0d", "-1d", "123dN", "123°W"};
        double[] whatAngleReturns = {123.0, 123.0, 0.0, -1.0, 123.0, -123.0};

        for (int i = 0; i < degreesOnly.length; i++) {
            String text = degreesOnly[i];
            assertEquals("Angle.parse must keep accepting a degrees-only string: " + text,
                    whatAngleReturns[i], Angle.parse(text), 0.0);

            NumberFormatException thrown = assertThrows(
                    "pins today's wrong answer for AngleFormat.parse(\"" + text + "\"); the right "
                            + "answer is " + whatAngleReturns[i] + ", the same as Angle.parse",
                    NumberFormatException.class,
                    new ThrowingRunnable() {
                        public void run() throws Exception {
                            degreeParser().parse(text);
                        }
                    });
            assertEquals("the failure comes out of Double.valueOf(\"\"), which is why the message "
                    + "does not name the input the caller supplied",
                    "empty String", thrown.getMessage());
        }
    }

    /**
     * The same defect where a caller meets it: {@code Units.DEGREES.parse} goes through
     * {@code AngleFormat}, so it rejects a string that {@code Proj4Parser} — which goes through
     * {@code Angle} — accepts. One library, two answers for {@code "123d"}.
     */
    @Test
    public void DEFECT_unitsDegreesInheritsTheRejection() {
        assertEquals(45.5, Units.DEGREES.parse("45d30"), TOLERANCE);
        assertEquals(45.5, Units.DEGREES.parse("45.5"), 0.0);

        NumberFormatException thrown = assertThrows(
                "pins today's wrong answer; the right answer is 123.0, which is what "
                        + "Angle.parse(\"123d\") returns for the same text",
                NumberFormatException.class,
                new ThrowingRunnable() {
                    public void run() {
                        Units.DEGREES.parse("123d");
                    }
                });
        assertEquals("empty String", thrown.getMessage());
    }

    /**
     * Everything else agrees. Holding the two implementations against one table is what makes the
     * test above a statement about one missing guard rather than about one arbitrary string, and
     * it is the net that catches a merge of the two bodies that changes any other case.
     */
    @Test
    public void theTwoParsersAgreeOnEverythingExceptADegreesOnlyString() {
        String[] inputs = {
                "123", "123.12", "-123.12", "+123.12", "1e2", "  123 ",
                "123d30m", "123d30", "123d30'", "123°30m", "123dm", "123d'",
                "123d30'15\"", "123d44'44.555\"", "123.5d30m",
                "123.12W", "123.12E", "123.12n", "45d30'00\"N", "45d30'00\"S", "123d30mw",
                "-0d30", "-1d30m", "12d34m57s",
                "123d60m", "123d-5m", "123d59.5m", "123d0'60\"", "123d30m-5s",
                "", "d30m", "abc", "12dxxm", "123D30M", "12d ",
        };
        for (String input : inputs) {
            assertEquals("Angle.parse and AngleFormat.parse must not drift apart on \"" + input
                            + "\"; only a degrees-only string is allowed to differ, and that "
                            + "difference is pinned separately as a defect",
                    angleOutcome(input), angleFormatOutcome(input));
        }
    }

    // ------------------------------------------------------------------
    // DEFECT 2: the seconds abbreviation collides with South
    // ------------------------------------------------------------------

    /**
     * DEFECT. A trailing {@code s} is taken for the southern hemisphere before anything else is
     * looked at, so an angle written with the class's own seconds abbreviation comes back
     * negated.
     *
     * <p>The current behaviour is wrong. {@code "12d34m57s"} is twelve and a half degrees north
     * or east and should parse as {@code +12.5825}; it parses as {@code -12.5825}. The suffix
     * test runs first and matches {@code 's'} case-insensitively, so the {@code s} that
     * {@link AngleFormat#STR_SEC_ABBREV} writes is consumed as {@link AngleFormat#CH_S}. The
     * later {@code endsWith(STR_SEC_ABBREV)} check, which exists precisely to strip that letter,
     * never sees it.
     *
     * <p>This is not a hypothetical spelling. {@code AngleFormat.ddmmssPattern4} produces exactly
     * this text, and {@code Angle.parse}'s own Javadoc offers {@code 123d44m44.555s} as a
     * supported example. The fix is to treat a trailing {@code s} as the hemisphere only when the
     * string holds no minute or degree marker before it, or to drop {@code s} as a hemisphere
     * spelling and require {@code S}.
     */
    @Test
    public void DEFECT_aTrailingSecondsAbbreviationIsReadAsSouth() throws ParseException {
        assertEquals("pins today's wrong answer; the right answer is 12.5825",
                -12.5825, Angle.parse("12d34m57s"), TOLERANCE);
        assertEquals("pins today's wrong answer; the right answer is 12.5825",
                -12.5825, parseAsDegrees("12d34m57s"), TOLERANCE);
        assertEquals("pins today's wrong answer; the right answer is 123.74570972222223",
                -123.74570972222223, Angle.parse("123d44m44.555s"), TOLERANCE);
        assertEquals("even a plain decimal is negated by a trailing s; "
                        + "the right answer is 123.12", -123.12, Angle.parse("123.12s"), 0.0);

        assertEquals("the quote spelling of the same angle is read correctly, which is what "
                        + "shows the 's' is the problem and not the arithmetic",
                12.5825, Angle.parse("12d34'57\""), TOLERANCE);
    }

    /**
     * DEFECT, stated as a round trip because that is how it will be met in practice: text written
     * by this class cannot be read back by this class.
     */
    @Test
    public void DEFECT_theLetterAbbreviationPatternDoesNotSurviveARoundTrip() {
        AngleFormat letters = new AngleFormat(AngleFormat.ddmmssPattern4, true);
        String written = letters.format(12.5825);
        assertEquals("12d34m57s", written);
        assertEquals("pins today's wrong answer; reading back what this class just wrote must "
                        + "give 12.5825 and instead flips the sign",
                -12.5825, Angle.parse(written), TOLERANCE);

        AngleFormat punctuation = new AngleFormat(AngleFormat.ddmmssPattern2, true);
        String alsoWritten = punctuation.format(12.5825);
        assertEquals("12d34'57\"", alsoWritten);
        assertEquals("the punctuation pattern does round-trip, which is the comparison that says "
                        + "the defect is in the letter 's' and nowhere else",
                12.5825, Angle.parse(alsoWritten), TOLERANCE);
    }

    // ------------------------------------------------------------------
    // DEFECT 3: minus zero degrees
    // ------------------------------------------------------------------

    /**
     * DEFECT. An angle between -1 and 0 degrees written sexagesimally parses as positive.
     *
     * <p>The current behaviour is wrong. {@code "-0d30"} is half a degree south and should parse
     * as {@code -0.5}; it parses as {@code +0.5}. The degrees field is read with
     * {@code Double.valueOf("-0")}, which is negative zero, and
     * {@code ProjectionMath.dmsToDeg} decides where to put the sign with {@code if (d >= 0)} —
     * true for negative zero, so the minutes are added rather than subtracted. The fix is to
     * carry the sign of the text separately from the value of the degrees field.
     *
     * <p>This is the reading-side twin of the formatting defect: {@code format(-0.5)} writes
     * {@code "0d30"} and {@code parse("-0d30")} reads {@code +0.5}, so a value below one degree
     * loses its hemisphere whichever direction it is travelling. Writing the sign as a hemisphere
     * letter instead does work, and that contrast is asserted here so a fix keeps it working.
     */
    @Test
    public void DEFECT_negativeZeroDegreesLosesTheSign() throws ParseException {
        assertEquals("pins today's wrong answer; the right answer is -0.5",
                0.5, Angle.parse("-0d30"), TOLERANCE);
        assertEquals("pins today's wrong answer; the right answer is -0.5",
                0.5, Angle.parse("-0d30m"), TOLERANCE);
        assertEquals("pins today's wrong answer; the right answer is -0.5",
                0.5, parseAsDegrees("-0d30'00\""), TOLERANCE);
        assertEquals("pins today's wrong answer; the right answer is -0.0002777...",
                2.777777777777778E-4, Angle.parse("-0d0'1\""), TOLERANCE);

        assertEquals("at one whole degree the sign survives, which is where the defect stops",
                -1.5, Angle.parse("-1d30m"), TOLERANCE);
        assertEquals("the hemisphere spelling of the same angle is read correctly",
                -0.5, Angle.parse("0d30'00\"S"), TOLERANCE);
        assertEquals("and the decimal spelling is read correctly",
                -0.5, Angle.parse("-0.5"), 0.0);
    }

    // ------------------------------------------------------------------
    // Range checks
    // ------------------------------------------------------------------

    @Test
    public void minutesOutsideTheRangeAreRejected() {
        assertEquals("Minutes must be between 0 and 59",
                angleFailureMessage("123d60m"));
        assertEquals("Minutes must be between 0 and 59",
                angleFailureMessage("123d-5m"));
        assertEquals("Minutes must be between 0 and 59",
                angleFormatFailureMessage("123d60m"));
        assertEquals("59 minutes exactly must still be accepted",
                123.98333333333333, Angle.parse("123d59m"), TOLERANCE);
    }

    @Test
    public void secondsOutsideTheRangeAreRejected() {
        assertEquals("Seconds must be between 0 and 59",
                angleFailureMessage("123d0'60\""));
        assertEquals("Seconds must be between 0 and 59",
                angleFailureMessage("123d30m-5s"));
        assertEquals("Seconds must be between 0 and 59",
                angleFormatFailureMessage("123d0'60\""));
    }

    /**
     * DEFECT. The two range checks do not agree with each other, and neither agrees with its own
     * message.
     *
     * <p>Seconds are checked with {@code s >= 60}, so 59.5 seconds is accepted — correctly, since
     * it is a real angle. Minutes are checked with {@code m > 59}, so 59.5 minutes is rejected,
     * although it is equally real: {@code "123d59.5m"} is 123 degrees 59 minutes 30 seconds. The
     * current behaviour is wrong; {@code "123d59.5m"} should parse as {@code 123.99166...}. The
     * fix is to check {@code m >= 60}.
     *
     * <p>Both messages say "between 0 and 59" while the seconds check in fact permits anything
     * below 60, so the message is misleading on the side that behaves correctly and accurate only
     * on the side that does not.
     */
    @Test
    public void DEFECT_fractionalMinutesJustBelowSixtyAreRejectedThoughSecondsAreNot() {
        assertEquals("59.5 seconds is accepted, and should be",
                123.01652777777778, Angle.parse("123d0m59.5\""), TOLERANCE);
        assertEquals("pins today's wrong answer; 123d59.5m should parse as 123.99166...",
                "Minutes must be between 0 and 59", angleFailureMessage("123d59.5m"));
        assertEquals("pins today's wrong answer; the two parsers are wrong in the same way",
                "Minutes must be between 0 and 59", angleFormatFailureMessage("123d59.5m"));
    }

    // ------------------------------------------------------------------
    // Malformed input
    // ------------------------------------------------------------------

    @Test
    public void nonNumericTextIsRejectedAndTheMessageNamesIt() {
        assertTrue("the message must name the text the caller supplied, so a bad CRS parameter "
                        + "can be found: " + angleFailureMessage("abc"),
                angleFailureMessage("abc").contains("abc"));
        assertTrue("a bad minutes field must be named: " + angleFailureMessage("12dxxm"),
                angleFailureMessage("12dxxm").contains("xx"));
        assertTrue(angleFormatFailureMessage("abc").contains("abc"));
        assertTrue(angleFormatFailureMessage("12dxxm").contains("xx"));
    }

    @Test
    public void theMarkersMustBeLowerCase() {
        // 'D' and 'M' are the pattern letters for writing, not for reading. An angle written in
        // upper case falls through to Double.parseDouble and is rejected whole.
        assertTrue("the whole string is named because no lower-case 'd' was found: "
                        + angleFailureMessage("123D30M"),
                angleFailureMessage("123D30M").contains("123D30M"));
        assertTrue(angleFormatFailureMessage("123D30M").contains("123D30M"));
    }

    /**
     * The failures that come out of an empty field name nothing at all. Recorded as it stands
     * rather than asserted as correct: a caller who gets {@code "empty String"} back from a CRS
     * definition has no way to tell which parameter was at fault. Whoever fixes the degrees-only
     * defect above should give these messages the offending text as well.
     */
    @Test
    public void emptyFieldsAreRejectedWithAMessageThatNamesNothing() {
        assertEquals("empty String", angleFailureMessage(""));
        assertEquals("a missing degrees field", "empty String", angleFailureMessage("d30m"));
        assertEquals("whitespace after the degree letter is not the same as nothing after it",
                "empty String", angleFailureMessage("12d "));
        assertEquals("empty String", angleFormatFailureMessage(""));
        assertEquals("empty String", angleFormatFailureMessage("d30m"));
    }

    @Test
    public void nullIsRejectedWithoutBeingReportedAsANumberProblem() {
        assertThrows(NullPointerException.class, new ThrowingRunnable() {
            public void run() {
                Angle.parse(null);
            }
        });
        assertThrows(NullPointerException.class, new ThrowingRunnable() {
            public void run() throws Exception {
                degreeParser().parse(null);
            }
        });
    }

    // ------------------------------------------------------------------
    // The NumberFormat contract
    // ------------------------------------------------------------------

    @Test
    public void parsingReportsTheWholeStringAsConsumed() {
        ParsePosition position = new ParsePosition(0);
        Number value = degreeParser().parse("123d30m", position);
        assertEquals(123.5, value.doubleValue(), TOLERANCE);
        assertEquals("the caller must be told the text was fully consumed",
                "123d30m".length(), position.getIndex());
    }

    /**
     * DEFECT. When a hemisphere letter is consumed, the reported position stops one character
     * short of it.
     *
     * <p>The current behaviour is wrong. After reading all eight characters of {@code "123d30mN"}
     * the index should be 8; it is 7, because the index is set from the length of the string
     * <em>after</em> the suffix has been chopped off. A caller reading several angles out of one
     * string with a shared {@link ParsePosition} would read the {@code N} again as the start of
     * the next one. The fix is to record the original length before the suffix is removed.
     *
     * <p>Nothing in the repository parses with an explicit position today, which is why this has
     * not bitten: {@code Units.DEGREES.parse} goes through {@code NumberFormat.parse(String)},
     * which only checks the index is not zero.
     */
    @Test
    public void DEFECT_theReportedPositionExcludesTheHemisphereLetter() {
        ParsePosition position = new ParsePosition(0);
        degreeParser().parse("123d30mN", position);
        assertEquals("pins today's wrong answer; the right answer is 8",
                7, position.getIndex());
    }

    @Test
    public void aNullParsePositionIsAccepted() {
        // NumberFormat never passes null, but the method guards for it, and DegreeUnit's
        // behaviour would change if that guard turned into an unconditional call.
        assertEquals(123.5, degreeParser().parse("123d30m", null).doubleValue(), TOLERANCE);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String angleFailureMessage(String text) {
        try {
            Angle.parse(text);
            throw new AssertionError("expected \"" + text + "\" to be rejected");
        } catch (NumberFormatException expected) {
            return expected.getMessage();
        }
    }

    private static String angleFormatFailureMessage(String text) {
        try {
            degreeParser().parse(text);
            throw new AssertionError("expected \"" + text + "\" to be rejected");
        } catch (NumberFormatException expected) {
            return expected.getMessage();
        } catch (ParseException unexpected) {
            throw new AssertionError("AngleFormat reports malformed input as NumberFormatException, "
                    + "never as ParseException: " + unexpected);
        }
    }

    /** The value, or the failure, as one comparable string. */
    private static String angleOutcome(String text) {
        try {
            return String.valueOf(Angle.parse(text));
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String angleFormatOutcome(String text) {
        try {
            return String.valueOf(degreeParser().parse(text).doubleValue());
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        } catch (ParseException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }
}
