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
 * <p>The two bodies are line-for-line alike, which is exactly the sort of near-duplication that
 * invites someone to delete one and forward it to the other. This file measures how far apart
 * they actually are, so that such a merge is a visible change rather than a silent one:
 * {@link #theTwoParsersAgreeOnEveryFormAndEveryFailure()} holds both against the same table of
 * inputs. They now agree on all of it; until this change they did not.
 *
 * <h2>Five defects that used to be pinned here, and are now fixed</h2>
 *
 * <p>Each was marked DEFECT with the answer the code ought to give, and each has since been given
 * it. The assertions below are the flipped versions and are now references rather than markers.
 *
 * <ol>
 * <li>{@code Angle.parse("123d")} returned 123 while {@code AngleFormat.parse("123d")} threw
 *     {@code NumberFormatException: empty String}. {@code AngleFormat} was missing the
 *     empty-minutes guard its twin had — {@link #aDegreesOnlyStringIsReadByBothParsers()}.</li>
 * <li>A trailing {@code s} — the class's own seconds abbreviation — was read as the southern
 *     hemisphere, so anything written with {@code ddmmssPattern4} read back negated. A lower-case
 *     {@code s} is now the seconds abbreviation only where it closes a lettered seconds field:
 *     a digit in front of it and an {@code m} minutes marker earlier in the string —
 *     {@link #aTrailingSecondsAbbreviationIsNotReadAsSouth()}. Everywhere else it is still
 *     South — {@link #aLowerCaseSThatClosesNoSecondsFieldIsStillSouth()}.</li>
 * <li>{@code "-0d30"} parsed as positive, because the sign lived only in a negative-zero degrees
 *     field and {@code dmsToDeg} tests {@code d >= 0}. It is now detected at the call site —
 *     {@link #negativeZeroDegreesKeepsTheSign()}.</li>
 * <li>Fractional minutes between 59 and 60 were rejected although the matching seconds check
 *     allowed them, so the real angle {@code 123d59.5m} would not parse —
 *     {@link #fractionalMinutesJustBelowSixtyAreAcceptedLikeSeconds()}.</li>
 * <li>{@link ParsePosition} was left one character short when a hemisphere letter was consumed —
 *     {@link #theReportedPositionIncludesTheHemisphereLetter()}.</li>
 * </ol>
 *
 * <h2>Where this parser deliberately does not match upstream</h2>
 *
 * <p>{@code dmstor} recognises {@code d}, {@code '} and {@code "} and nothing else, so an
 * {@code m} ends the angle for it and the seconds after one are dropped. Here the two minute
 * spellings are one grammar and the seconds are read either way, because
 * {@link AngleFormat#ddmmssPattern4} is {@code DdMmSs} and this class would otherwise be unable
 * to read back its own output. Pinned in {@link #theTwoMinuteSpellingsAreOneGrammar()} and
 * {@link #bothMinuteSpellingsRoundTrip()}, with the full list of shapes where the two disagree
 * in {@link #theThreeShapesWhereThisParserDivergesFromDmstor()}. No angular token in the shipped
 * registries or the gie corpus contains an {@code m} at all, so nothing measurable turns on it.
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
        // "Minutes must be at least 0 and less than 60" check the only guard on the value.
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

    /**
     * Re-pinned. This used to assert {@code +45.5}, on the reasoning that the suffix "is applied
     * last and unconditionally rather than being reconciled with an explicit sign". The suffix is
     * now reconciled with it: it assigns the sign, as {@code dmstor} does, so the minus is
     * discarded and the answer is {@code -45.5}, which is 9.8.1's. The full case is in
     * {@link #aTrailingCardinalOverrulesALeadingMinus}.
     */
    @Test
    public void aHemisphereSuffixOverrulesAnAlreadyNegativeValue() throws ParseException {
        assertEquals(-45.5, Angle.parse("-45.5S"), 0.0);
        assertEquals(-45.5, parseAsDegrees("-45.5S"), 0.0);
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
    // The two parsers, held against each other
    // ------------------------------------------------------------------

    /**
     * {@code "123d"} — a whole number of degrees with nothing after the degree letter — is read by
     * both parsers.
     *
     * <p>{@code AngleFormat} used to reject it with {@code NumberFormatException: empty String}.
     * {@code Angle.parse} guarded the minutes field with a length check and {@code AngleFormat}
     * had the identical code with that guard missing, so it handed an empty string to
     * {@code Double.valueOf}. One library, two answers for one well-formed angle.
     *
     * <p>It was never only the {@code d} form: every degrees-only string was affected, including
     * the degree-sign spelling and the forms with a hemisphere letter, which is why all six are
     * asserted here.
     */
    @Test
    public void aDegreesOnlyStringIsReadByBothParsers() throws ParseException {
        String[] degreesOnly = {"123d", "123°", "0d", "-1d", "123dN", "123°W"};
        double[] expected = {123.0, 123.0, 0.0, -1.0, 123.0, -123.0};

        for (int i = 0; i < degreesOnly.length; i++) {
            String text = degreesOnly[i];
            assertEquals("Angle.parse: " + text, expected[i], Angle.parse(text), 0.0);
            assertEquals("AngleFormat.parse must give the same answer: " + text,
                    expected[i], parseAsDegrees(text), 0.0);
        }
    }

    /**
     * The same fix where a caller meets it. {@code Units.DEGREES.parse} goes through
     * {@code AngleFormat} while {@code Proj4Parser} goes through {@code Angle}, so a degrees-only
     * string used to be readable as a CRS parameter and unreadable as a user-supplied measurement.
     */
    @Test
    public void unitsDegreesAcceptsADegreesOnlyString() {
        assertEquals(45.5, Units.DEGREES.parse("45d30"), TOLERANCE);
        assertEquals(45.5, Units.DEGREES.parse("45.5"), 0.0);
        assertEquals(123.0, Units.DEGREES.parse("123d"), 0.0);
    }

    /**
     * Everything agrees, including every failure. Holding the two implementations against one
     * table is the net that catches a merge of the two bodies — or an edit to one of them — that
     * changes any case at all.
     *
     * <p>Before this change the table had to carve out the degrees-only strings; it no longer
     * does, so any difference whatsoever is now a failure.
     */
    @Test
    public void theTwoParsersAgreeOnEveryFormAndEveryFailure() {
        String[] inputs = {
                "123", "123.12", "-123.12", "+123.12", "1e2", "  123 ",
                "123d30m", "123d30", "123d30'", "123°30m", "123dm", "123d'",
                "123d", "123°", "0d", "-1d", "123dN", "123°W",
                "123d30'15\"", "123d44'44.555\"", "123.5d30m",
                "123.12W", "123.12E", "123.12n", "123.12s", "45d30'00\"N", "45d30'00\"S",
                "123d30mw", "18d54S", "18d54s",
                "12d34's", "12d34'56\"s", "12d34'57s", "12d34m56\"s", "12d34m57S",
                "-0d30", "-0d34S", "-12d34S", "-1d30m", "12d34m57s", "123d44m44.555s",
                "123d0m59.5\"",
                "123d60m", "123d-5m", "123d59.5m", "123d0'60\"", "123d30m-5s", "123d0'-5\"",
                "", "d30m", "abc", "12dxxm", "123D30M", "12d ",
        };
        for (String input : inputs) {
            assertEquals("Angle.parse and AngleFormat.parse must not drift apart on \"" + input
                            + "\"", angleOutcome(input), angleFormatOutcome(input));
        }
    }

    // ------------------------------------------------------------------
    // The seconds abbreviation and the southern hemisphere
    // ------------------------------------------------------------------

    /**
     * A trailing lower-case {@code s} that closes a lettered seconds field is the seconds
     * abbreviation, not the southern hemisphere, so an angle written with this class's own
     * {@code ddmmssPattern4} no longer reads back negated.
     *
     * <p>The hemisphere test used to run first and match {@code 's'} case-insensitively, so the
     * {@code s} that {@link AngleFormat#STR_SEC_ABBREV} writes was consumed as
     * {@link AngleFormat#CH_S} and {@code "12d34m57s"} came back as {@code -12.57}. The later
     * {@code endsWith(STR_SEC_ABBREV)} check, which exists precisely to strip that letter, never
     * saw it.
     *
     * <p>The rule is not case alone. Two things have to hold before the {@code s} is read as
     * seconds: an {@code m} minutes marker appears earlier in the string, and the character
     * immediately in front of the {@code s} is a digit. Case alone was tried and cost three
     * ordinary spellings — see {@link #aLowerCaseSThatClosesNoSecondsFieldIsStillSouth()} for
     * what each of them returned. Upper-case {@code S} is always South, and the reason is in
     * {@link #upperCaseSouthAfterADegreeMarkerIsStillTheHemisphere()}: the registry writes
     * {@code 18d54S} and means South.
     */
    @Test
    public void aTrailingSecondsAbbreviationIsNotReadAsSouth() throws ParseException {
        assertEquals(12.5825, Angle.parse("12d34m57s"), TOLERANCE);
        assertEquals(12.5825, parseAsDegrees("12d34m57s"), TOLERANCE);
        assertEquals(123.74570972222223, Angle.parse("123d44m44.555s"), 0.0);

        assertEquals("with no degree marker in front of it there is no seconds field for an 's' "
                        + "to be abbreviating, so it stays South -- and dmstor reads it the same "
                        + "way", -123.12, Angle.parse("123.12s"), 0.0);

        assertEquals("the quote spelling of the same angle agrees to the last bit, which is the "
                        + "point: the two spellings are one grammar",
                Angle.parse("12d34'57\""), Angle.parse("12d34m57s"), 0.0);
    }

    /**
     * The shapes a case-only rule got wrong. Each ends in a lower-case {@code s} that closes no
     * seconds field, so each is South, and the narrowed rule reads them that way again.
     *
     * <p>Reading the {@code s} as seconds cost three ordinary spellings. Measured against master
     * and against an installed PROJ 9.8.1, piping each token through
     * {@code cs2cs -f '%.6f' +proj=latlong +datum=WGS84 +to +proj=latlong +datum=WGS84}:
     *
     * <ul>
     * <li>{@code "12d34's"} read {@code +12.5667} where master and 9.8.1 both give
     *     {@code -12.5667}.</li>
     * <li>{@code "12d34'56\"s"} threw outright, where master and 9.8.1 both read
     *     {@code -12.5822}.</li>
     * <li>{@code "12d34'57s"} read {@code +12.5825} where master and 9.8.1 both give
     *     {@code -12.5825}.</li>
     * </ul>
     *
     * <p>All three are the symbolic spelling, in which {@code s} has no job: this class writes the
     * lettered {@code 12d34m57s} and the symbolic {@code 12d34'57"}, never a mixture. So the
     * abbreviation is recognised only after an {@code m}. These three are agreements with
     * upstream, not divergences from it, which is why they are here and not in
     * {@link #theThreeShapesWhereThisParserDivergesFromDmstor()}.
     *
     * <p>The two conditions the rule tests are pinned separately below: {@code "18d54s"} has no
     * minutes marker at all, and {@code "12d34m56\"s"} has one but no digit in front of the
     * {@code s}.
     */
    @Test
    public void aLowerCaseSThatClosesNoSecondsFieldIsStillSouth() throws ParseException {
        assertEquals(-12.566666666666666, Angle.parse("12d34's"), 0.0);
        assertEquals(-12.582222222222223, Angle.parse("12d34'56\"s"), 0.0);
        assertEquals(-12.5825, Angle.parse("12d34'57s"), 0.0);

        assertEquals(-12.566666666666666, parseAsDegrees("12d34's"), 0.0);
        assertEquals(-12.582222222222223, parseAsDegrees("12d34'56\"s"), 0.0);
        assertEquals(-12.5825, parseAsDegrees("12d34'57s"), 0.0);

        assertEquals("upper case is South by the same reading, and always was",
                -12.582222222222223, Angle.parse("12d34'56\"S"), 0.0);

        assertEquals("no minutes marker at all, so there is no seconds field open for the 's' to "
                        + "close: master and dmstor both read this as South too",
                -18.9, Angle.parse("18d54s"), TOLERANCE);

        // The digit rule. The quote has already closed the seconds field, so the 's' after it is
        // a suffix rather than an abbreviation. Pinned against the symbolic spelling rather than
        // against a literal, because the two must be the same angle: that value is -12.5822,
        // asserted above.
        assertEquals("a marker that already closed its own field leaves the 's' as a suffix",
                Angle.parse("12d34'56\"s"), Angle.parse("12d34m56\"s"), 0.0);
    }

    /**
     * An upper-case {@code S} after a degree marker is South, and this is the assertion that
     * stops a fix for the test above from moving Madagascar into the northern hemisphere.
     *
     * <p>{@code epsg/src/main/resources/proj4/nad/world:9} — the Madagascar Laborde grid — writes
     * {@code +lat_0=18d54S}, and eleven more registry tokens are shaped the same way. Any rule of
     * the form "a trailing s after a degree marker is seconds" reads that as 18 degrees 54 minutes
     * north, and moves Madagascar into the northern hemisphere.
     */
    @Test
    public void upperCaseSouthAfterADegreeMarkerIsStillTheHemisphere() throws ParseException {
        assertEquals("the Madagascar Laborde grid's +lat_0", -18.9, Angle.parse("18d54S"), TOLERANCE);
        assertEquals(-18.9, parseAsDegrees("18d54S"), TOLERANCE);
        assertEquals("and the same with seconds spelled out",
                -18.9, Angle.parse("18d54'00\"S"), TOLERANCE);
        assertEquals("the other three cardinals are unaffected in either case",
                18.9, Angle.parse("18d54N"), TOLERANCE);
        assertEquals(18.9, Angle.parse("18d54n"), TOLERANCE);
        assertEquals(-18.9, Angle.parse("18d54w"), TOLERANCE);
    }

    /**
     * The three shapes where this parser and {@code dmstor} disagree, pinned so that the
     * disagreement is a recorded decision rather than a surprise.
     *
     * <p>Every survivor contains an {@code m}, and one choice produces all three: {@code m} and
     * {@code '} are one grammar here, both carrying the angle on into a seconds field. Upstream
     * recognises only {@code d}, {@code '} and {@code "}, so its digit loop stops at the
     * unrecognised {@code m}, and its postfix-sign test then looks at that {@code m} rather than
     * at the end of the string. Upstream discriminates by where the parse stopped; we read the
     * whole string.
     *
     * <p>The set used to have five members. It shrank when the seconds rule was narrowed: the
     * discriminator is no longer case, it is whether the {@code s} closes a lettered seconds
     * field — a digit in front of it and an {@code m} earlier in the string. Under that rule
     * {@code "18d54s"} and {@code "12d34'57s"} are both South, which is what master and 9.8.1
     * say too, so they are agreements now rather than divergences and have moved to
     * {@link #aLowerCaseSThatClosesNoSecondsFieldIsStillSouth()}.
     *
     * <p>Matching upstream on what is left would mean treating {@code m} as ending the angle,
     * which would stop {@link AngleFormat#ddmmssPattern4} reading back what it writes — see
     * {@link #bothMinuteSpellingsRoundTrip()} — and would suppress every cardinal after an
     * {@code m} rather than just the {@code s}.
     *
     * <p>None of these three occurs in the shipped registries or the gie corpus. What was
     * measured is that the registries hold 1,792 distinct angular values, not one of which
     * contains an {@code m}, and that the gie corpus holds none either. So the choice moves no
     * data either way. If that ever stops being true, this test is the place to change the rule.
     */
    @Test
    public void theThreeShapesWhereThisParserDivergesFromDmstor() {
        assertEquals("upstream reads +12.5667, having stopped at the 'm'; we read the seconds",
                12.5825, Angle.parse("12d34m57s"), TOLERANCE);
        assertEquals("upstream reads +12.5667, because it stopped at the 'm' and never looked at "
                        + "the 'S'", -12.5825, Angle.parse("12d34m57S"), TOLERANCE);
        assertEquals("upstream reads +123.5, for the same reason",
                -123.5, Angle.parse("123d30mw"), TOLERANCE);
    }

    /**
     * {@code m} and {@code '} are two spellings of the same minutes field, and either one lets
     * the angle continue into seconds.
     *
     * <p>This is where the library deliberately parts company with {@code dmstor}, whose unit
     * alphabet is {@code d}, {@code '} and {@code "} — see
     * {@code conformance/.../parse/ProjDmsToR.java}, a port of {@code 9.8.1:src/dmstor.cpp} — and
     * whose digit loop therefore reads the minutes and stops at the unrecognised {@code m}. The
     * reason for parting company is {@link #bothMinuteSpellingsRoundTrip()}: this class writes
     * the {@code m} spelling itself.
     *
     * <p>Reading the whole string also means the seconds after an {@code m} are range-checked
     * like any others, rather than passing unlooked-at.
     */
    @Test
    public void theTwoMinuteSpellingsAreOneGrammar() throws ParseException {
        assertEquals("the same angle either way", Angle.parse("12d34'57\""),
                Angle.parse("12d34m57s"), 0.0);
        // Delta 0.0, and the literal is the double the code actually returns. This assertion
        // used to end ...22222 while degreesMinutesAndSeconds pinned ...22223 for the same
        // angle spelled 123d44'44.555". Those are one ULP apart, so TOLERANCE hid it -- 1e-12
        // is about 70 ULPs at this magnitude -- and the file was asserting that the two
        // spellings are the same double and then giving them different values.
        assertEquals(123.74570972222223, Angle.parse("123d44m44.555s"), 0.0);
        assertEquals("and the quote spelling is the same double, not merely close",
                Angle.parse("123d44'44.555\""), Angle.parse("123d44m44.555s"), 0.0);
        assertEquals("seconds after an 'm' are validated, not skipped",
                "Seconds must be at least 0 and less than 60",
                angleFailureMessage("123d30m-5s"));
        assertEquals("and the two markers can be mixed within one angle",
                123.01652777777778, Angle.parse("123d0m59.5\""), TOLERANCE);
    }

    /**
     * {@link AngleFormat#ddmmssPattern4} survives a round trip, which it did not before.
     *
     * <p>It used to come back negated — twelve and a half degrees south instead of north —
     * because the trailing {@code s} this class writes was read as South. The pattern is
     * {@code DdMmSs}, so the {@code m} and the {@code s} are both this class's own output, and a
     * writing format that the matching reader cannot read back is a defect rather than a
     * grammar. Both patterns now return the value they were given.
     */
    @Test
    public void bothMinuteSpellingsRoundTrip() {
        AngleFormat letters = new AngleFormat(AngleFormat.ddmmssPattern4, true);
        String written = letters.format(12.5825);
        assertEquals("12d34m57s", written);
        assertEquals(12.5825, Angle.parse(written), TOLERANCE);

        AngleFormat punctuation = new AngleFormat(AngleFormat.ddmmssPattern2, true);
        String alsoWritten = punctuation.format(12.5825);
        assertEquals("12d34'57\"", alsoWritten);
        assertEquals(12.5825, Angle.parse(alsoWritten), TOLERANCE);

        assertEquals("the two spellings of the same angle agree to the last bit",
                Angle.parse(written), Angle.parse(alsoWritten), 0.0);
    }

    // ------------------------------------------------------------------
    // Minus zero degrees
    // ------------------------------------------------------------------

    /**
     * An angle between -1 and 0 degrees written sexagesimally keeps its sign.
     *
     * <p>{@code "-0d30"} is half a degree south and used to parse as {@code +0.5}. The degrees
     * field is read with {@code Double.valueOf("-0")}, which is negative zero, and
     * {@code ProjectionMath.dmsToDeg} decides where to put the sign with {@code if (d >= 0)} —
     * true for negative zero, so the minutes were added rather than subtracted. The sign is now
     * recovered at the call site, by inspecting the sign bit of the degrees field, which leaves
     * {@code dmsToDeg}'s arithmetic untouched for the 187 registry tokens of the {@code -#d#}
     * form (99 in {@code nad27}, 88 in {@code nad83}, 77 distinct) that take its
     * genuinely-negative branch.
     *
     * <p>This is the reading-side twin of the formatting defect: {@code format(-0.5)} wrote
     * {@code "0d30"} and {@code parse("-0d30")} read {@code +0.5}, so a value below one degree
     * lost its hemisphere whichever direction it was travelling.
     */
    @Test
    public void negativeZeroDegreesKeepsTheSign() throws ParseException {
        assertEquals(-0.5, Angle.parse("-0d30"), TOLERANCE);
        assertEquals(-0.5, Angle.parse("-0d30m"), TOLERANCE);
        assertEquals(-0.5, parseAsDegrees("-0d30'00\""), TOLERANCE);
        assertEquals(-2.777777777777778E-4, Angle.parse("-0d0'1\""), TOLERANCE);

        assertEquals("at one whole degree the sign always survived; it must still",
                -1.5, Angle.parse("-1d30m"), TOLERANCE);
        assertEquals("a positive zero degrees field is not negated",
                0.5, Angle.parse("0d30"), TOLERANCE);
        assertEquals("the hemisphere spelling of the same angle",
                -0.5, Angle.parse("0d30'00\"S"), TOLERANCE);
        assertEquals("and the decimal spelling", -0.5, Angle.parse("-0.5"), 0.0);
    }

    /**
     * A trailing cardinal overrules a leading minus rather than compounding with it. The letter
     * assigns the sign and the minus is discarded, so {@code "-0d30S"} is south and
     * {@code "-0d30N"} is north.
     *
     * <p>That is what {@code dmstor} does — {@code sign = idx >= 4 ? -1 : 1}, an assignment, at
     * {@code conformance/.../parse/ProjDmsToR.java:181} — and every value below was read out of
     * PROJ 9.8.1 rather than reasoned about. It corrects two readings this library had wrong
     * before this branch, {@code "-1d30E"} (was {@code -1.5}) and {@code "-12d34S"} (was
     * {@code +12.5667}), and it is also the only rule under which recovering the lost sign of
     * {@code "-0d30"} does not break the four sub-degree shapes that were already right.
     *
     * <p>Nothing shipped writes both. Of 735 DMS tokens in the registries, 187 open with a minus
     * and 66 close with a cardinal, none does both, and the gie corpus has none either.
     */
    @Test
    public void aTrailingCardinalOverrulesALeadingMinus() throws ParseException {
        // Sub-degree: the minus survives only when no letter contradicts it. All six agree with
        // 9.8.1, and the last five agreed with master too -- the multiply rule would have
        // flipped every one of them.
        assertEquals("no letter, so the recovered sign bit stands",
                -0.5, Angle.parse("-0d30"), 0.0);
        assertEquals(-0.5, Angle.parse("-0d30W"), 0.0);
        assertEquals(0.5, Angle.parse("-0d30E"), 0.0);
        assertEquals(0.5, Angle.parse("-0d30N"), 0.0);
        assertEquals(-0.5, Angle.parse("-0d30S"), 0.0);
        assertEquals(-0.008333333333333333, Angle.parse("-0d0'30\"W"), 0.0);

        // Whole degrees. Both of these read with the wrong sign before this branch.
        assertEquals("was -1.5 here, +1.5 upstream", 1.5, Angle.parse("-1d30E"), 0.0);
        assertEquals("was +12.5667 here, -12.5667 upstream",
                -12.566666666666666, Angle.parse("-12d34S"), 0.0);
        assertEquals(12.566666666666666, Angle.parse("-12d34N"), 0.0);

        // The decimal spelling takes the same rule, and always did.
        assertEquals(-45.5, Angle.parse("-45.5S"), 0.0);
        assertEquals(45.5, Angle.parse("-45.5N"), 0.0);

        // The other parser, on the same inputs.
        assertEquals(-0.5, parseAsDegrees("-0d30W"), 0.0);
        assertEquals(0.5, parseAsDegrees("-0d30E"), 0.0);
        assertEquals(-12.566666666666666, parseAsDegrees("-12d34S"), 0.0);
        assertEquals(12.566666666666666, parseAsDegrees("-12d34N"), 0.0);
        assertEquals(1.5, parseAsDegrees("-1d30E"), 0.0);
    }

    // ------------------------------------------------------------------
    // Range checks
    // ------------------------------------------------------------------

    @Test
    public void minutesOutsideTheRangeAreRejected() {
        assertEquals("Minutes must be at least 0 and less than 60",
                angleFailureMessage("123d60m"));
        assertEquals("Minutes must be at least 0 and less than 60",
                angleFailureMessage("123d-5m"));
        assertEquals("Minutes must be at least 0 and less than 60",
                angleFormatFailureMessage("123d60m"));
        assertEquals("59 minutes exactly must still be accepted",
                123.98333333333333, Angle.parse("123d59m"), TOLERANCE);
    }

    @Test
    public void secondsOutsideTheRangeAreRejected() {
        assertEquals("Seconds must be at least 0 and less than 60",
                angleFailureMessage("123d0'60\""));
        // Both spellings of a negative seconds field. The lettered one, "123d30m-5s", is the
        // input this test carried originally; the symbolic one was substituted for it at some
        // point without a note. They are the same case and both are cheap, so both are here.
        assertEquals("Seconds must be at least 0 and less than 60",
                angleFailureMessage("123d0'-5\""));
        assertEquals("Seconds must be at least 0 and less than 60",
                angleFailureMessage("123d30m-5s"));
        assertEquals("Seconds must be at least 0 and less than 60",
                angleFormatFailureMessage("123d0'60\""));
        assertEquals("59 seconds exactly must still be accepted",
                123.01638888888889, Angle.parse("123d0'59\""), TOLERANCE);
    }

    /**
     * The two range checks now agree with each other, and both messages describe what they do.
     *
     * <p>Seconds were checked with {@code s >= 60}, so 59.5 seconds was accepted — correctly,
     * since it is a real angle. Minutes were checked with {@code m > 59}, so 59.5 minutes was
     * rejected, although {@code "123d59.5m"} is the equally real 123 degrees 59 minutes 30
     * seconds. Both are now "at least 0 and less than 60", which is also what both messages say;
     * the old wording, "between 0 and 59", was accurate only on the side that behaved wrongly.
     */
    @Test
    public void fractionalMinutesJustBelowSixtyAreAcceptedLikeSeconds() {
        assertEquals(123.99166666666666, Angle.parse("123d59.5m"), TOLERANCE);
        assertEquals("both parsers, since the check was duplicated in both",
                123.99166666666666, Units.DEGREES.parse("123d59.5m"), TOLERANCE);
        assertEquals("the apostrophe spelling, which was rejected too",
                123.99166666666666, Angle.parse("123d59.5'"), TOLERANCE);
        assertEquals("and the fractional seconds that were always allowed",
                123.01652777777778, Angle.parse("123d0'59.5\""), TOLERANCE);
        assertEquals("60 exactly is still out of range on both sides",
                "Minutes must be at least 0 and less than 60", angleFailureMessage("123d60m"));
        assertEquals("Seconds must be at least 0 and less than 60",
                angleFailureMessage("123d0'60\""));
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
     * definition has no way to tell which parameter was at fault. These messages come out of
     * {@code Double.valueOf} rather than out of this library, so improving them means catching
     * and rethrowing at each of the four fields, which is a change to {@code Angle} and
     * {@code AngleFormat} and not to this test.
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
     * When a hemisphere letter is consumed, the reported position includes it.
     *
     * <p>It used to stop one character short: after reading all eight characters of
     * {@code "123d30mN"} the index came back as 7, because it was taken from the length of the
     * string <em>after</em> the suffix had been chopped off. A caller reading several angles out
     * of one string with a shared {@link ParsePosition} would have read the {@code N} again as the
     * start of the next one. The length is now recorded before the suffix is removed.
     *
     * <p>Nothing in the repository parses with an explicit position today, which is why this never
     * bit: {@code Units.DEGREES.parse} goes through {@code NumberFormat.parse(String)}, which only
     * checks the index is not zero.
     */
    @Test
    public void theReportedPositionIncludesTheHemisphereLetter() {
        ParsePosition withSuffix = new ParsePosition(0);
        degreeParser().parse("123d30mN", withSuffix);
        assertEquals(8, withSuffix.getIndex());

        ParsePosition decimalWithSuffix = new ParsePosition(0);
        degreeParser().parse("123.12W", decimalWithSuffix);
        assertEquals("the decimal path reports the whole string too",
                7, decimalWithSuffix.getIndex());
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
