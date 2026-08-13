/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * A NumberFormat for formatting Angles in various commonly-found mapping styles.
 */
public class AngleFormat extends NumberFormat {

    private static final long serialVersionUID = -1805856094196103692L;

    public static final char CH_MIN_SYMBOL = '\'';
    public static final String STR_SEC_SYMBOL = "\"";
    public static final char CH_DEG_SYMBOL = '\u00b0';
    public static final char CH_DEG_ABBREV = 'd';
    public static final char CH_MIN_ABBREV = 'm';
    public static final String STR_SEC_ABBREV = "s";

    public static final char CH_N = 'N';
    public static final char CH_E = 'E';
    public static final char CH_S = 'S';
    public static final char CH_W = 'W';

    public final static String ddmmssPattern = "DdM";
    public final static String ddmmssPattern2 = "DdM'S\"";
    public final static String ddmmssLongPattern = "DdM'S\"W";
    public final static String ddmmssLatPattern = "DdM'S\"N";
    public final static String ddmmssPattern4 = "DdMmSs";
    public final static String decimalPattern = "D.F";

    private DecimalFormat format;
    private String pattern;
    private boolean isDegrees;

    public AngleFormat() {
        this(ddmmssPattern);
    }

    public AngleFormat(String pattern) {
        this(pattern, false);
    }

    public AngleFormat(String pattern, boolean isDegrees) {
        this.pattern = pattern;
        this.isDegrees = isDegrees;
        format = new DecimalFormat();
        format.setMaximumFractionDigits(0);
        format.setGroupingUsed(false);
    }

    public StringBuffer format(long number, StringBuffer result, FieldPosition fieldPosition) {
        return format((double)number, result, fieldPosition);
    }

    public StringBuffer format(double number, StringBuffer result, FieldPosition fieldPosition) {
        int length = pattern.length();
        int f;
        boolean negative = false;

        if (number < 0) {
            for (int i = length-1; i >= 0; i--) {
                char c = pattern.charAt(i);
                if (c == 'W' || c == 'N') {
                    number = -number;
                    negative = true;
                    break;
                }
            }
        }

        double ddmmss = isDegrees ? number : ProjectionMath.toDeg(number);
        /*
         * All three sexagesimal fields come from ONE rounded arcsecond count, so a carry out
         * of the seconds reaches the minutes and a carry out of the minutes reaches the
         * degrees. Taking the degree field from the raw value instead - (int) ddmmss - let the
         * two disagree about which degree they were in, so 45.99999 printed as "45d00": the
         * minutes had carried and the degrees had not.
         *
         * The magnitude is rounded rather than the signed value, so half an arcsecond rounds
         * away from zero on both sides. Math.round rounds a tie towards positive infinity, so
         * rounding the signed value would have sent -0.5 arcseconds to 0 and +0.5 to 1.
         */
        long arcseconds = Math.round(Math.abs(ddmmss) * 3600.0);
        int degreeField = (int)(arcseconds / 3600);
        int fraction = (int)(arcseconds % 3600);
        /*
         * The sign travels separately from the degree field, which is zero for every angle
         * between -1 and 0 degrees and so has no minus sign of its own to print. That is why
         * -0.5 used to format as "0d30". A value that rounds to no arcseconds at all is not
         * given a sign, so a tiny negative prints as "0d00" and not as "-0d00".
         *
         * Patterns carrying a hemisphere letter never reach this: for those the sign was
         * consumed above and is re-emitted as S or W.
         */
        boolean signed = ddmmss < 0 && arcseconds != 0;

        for (int i = 0; i < length; i++) {
            char c = pattern.charAt(i);
            switch (c) {
            case 'R':
                result.append(number);
                break;
            case 'D':
                if (signed)
                    result.append('-');
                result.append(degreeField);
                break;
            case 'M':
                f = fraction / 60;
                if (f < 10)
                    result.append('0');
                result.append(f);
                break;
            case 'S':
                f = fraction % 60;
                if (f < 10)
                    result.append('0');
                result.append(f);
                break;
            case 'F':
                appendDegreeFraction(result, fraction);
                break;
            case 'W':
                if (negative)
                    result.append(CH_W);
                else
                    result.append(CH_E);
                break;
            case 'N':
                if (negative)
                    result.append(CH_S);
                else
                    result.append(CH_N);
                break;
            default:
                result.append(c);
                break;
            }
        }
        return result;
    }

    /**
     * The {@code F} pattern letter: the part of the angle below a whole degree, written as
     * decimal digits.
     *
     * <p>{@code F} is only ever used after a literal {@code '.'} — that is the whole of
     * {@link #decimalPattern} — so what belongs here is the digits of {@code fraction / 3600}
     * and not the arcsecond count. Appending the count unscaled is what made {@code "D.F"}
     * print half a degree as {@code 45.1800}: forty-five degrees and eighteen hundred
     * ten-thousandths, which is not the angle in any notation.
     *
     * <p>Four digits are produced — the width the unscaled count happened to have — then
     * trailing zeros are dropped and at least one digit is kept, so half a degree is
     * {@code 5} and a whole degree is {@code 0}.
     *
     * <p>The digits are assembled by hand rather than through a {@link java.text.NumberFormat}
     * because that would take its zero digit and its grouping from the ambient locale, which
     * {@code NoAmbientLocaleInCoreTest} forbids on a value-bearing path.
     */
    private static void appendDegreeFraction(StringBuffer result, int fraction) {
        // Round fraction/3600 to four decimal places. fraction is at most 3599, so the
        // quotient is at most 9997 and can never carry into the degree field.
        int scaled = (int)((fraction * 10000L + 1800L) / 3600L);
        char[] digits = new char[4];
        for (int k = 3; k >= 0; k--) {
            digits[k] = (char)('0' + scaled % 10);
            scaled /= 10;
        }
        int end = 4;
        while (end > 1 && digits[end - 1] == '0')
            end--;
        result.append(digits, 0, end);
    }

    /**
     * Whether the final character of an angle is a hemisphere letter.
     *
     * <p>{@code N}, {@code E}, {@code S} and {@code W} are recognised in either case, with one
     * exception: a lower-case {@code s} that closes a seconds field is
     * {@link #STR_SEC_ABBREV}, the seconds abbreviation this class itself writes under
     * {@link #ddmmssPattern4}, and not South. Upper-case {@code S} is always South.
     * {@link #closesASecondsField} is the whole of that exception.
     *
     * <p>The exception is as narrow as it is because two ordinary spellings sit on either side
     * of it, and a wider rule loses one or the other:
     *
     * <ul>
     * <li><b>{@code "18d54S"} must stay South.</b> The registry carries twelve tokens of that
     * shape, among them {@code lat_0=18d54S}
     * ({@code epsg/src/main/resources/proj4/nad/world:9}, the Madagascar Laborde grid). A rule
     * of the form "a trailing {@code s} after a degree marker is seconds" reads that as 18
     * degrees 54 minutes north and moves Madagascar into the northern hemisphere. Case alone
     * covers this one.</li>
     *
     * <li><b>{@code "12d34's"}, {@code "12d34'56\"s"} and {@code "12d34'57s"} must also stay
     * South</b>, and case does <em>not</em> cover those. Discriminating on case alone read them
     * as seconds, returning {@code +12.5667} where master and 9.8.1 both give {@code -12.5667},
     * and throwing outright on the second. All three are the symbolic spelling, where {@code s}
     * has no job.</li>
     * </ul>
     *
     * <p>A lower-case {@code s} with no minutes field in front of it, as in {@code "123.12s"} or
     * {@code "18d54s"}, likewise stays South: there is no seconds field for it to close.
     *
     * <h4>How this differs from upstream, which is worth being exact about</h4>
     *
     * <p>{@code dmstor} has no seconds abbreviation at all. Its unit alphabet is {@code d},
     * {@code '} and {@code "} — and, less often quoted, {@code D}, both spellings of the degree
     * sign and a trailing {@code r} for radians — while its {@code SYM} string is
     * {@code "NnEeSsWw"}, so an {@code s} is <em>always</em> a hemisphere letter to it. The
     * reason {@code "12d34m57s"} still comes out positive upstream is nothing to do with the
     * {@code s}: the digit loop stops at the unrecognised {@code m} and the postfix-sign test
     * then looks at that {@code m}, which is not in {@code SYM}. Upstream discriminates by
     * <em>where the parse stopped</em>; we discriminate by what the {@code s} is closing
     * (9.8.1:src/dmstor.cpp, ported in {@code conformance/.../parse/ProjDmsToR.java}).
     *
     * <p>The two rules agree on every angular token in the shipped registries and in the gie
     * corpus — not one of which contains an {@code m} at all — and on both spellings anyone
     * actually writes. Every shape they part company on contains an {@code m}, and none occurs
     * in either body of data: {@code "12d34m57s"} (upstream +12.5667, we read +12.5825),
     * {@code "12d34m57S"} (upstream +12.5667, we read -12.5825) and {@code "123d30mw"}
     * (upstream +123.5, we read -123.5). That is the price of reading back what
     * {@link #ddmmssPattern4} writes, and it is the whole price.
     *
     * <p>Matching upstream exactly would mean suppressing the hemisphere letter whenever an
     * {@code m} ended the angle, which costs the case-insensitivity of every cardinal rather
     * than just the one; and treating {@code m} as ending the angle would stop
     * {@link #ddmmssPattern4} reading back the {@code 12d34m57s} it writes. Neither price buys
     * a single row of measurable parity, so both are declined. These are pinned in
     * {@code AngleFormatParseTest}.
     */
    static boolean isHemisphereLetter(String text) {
        int n = text.length();
        if (n == 0)
            return false;
        char last = text.charAt(n - 1);
        if (last == 's' && closesASecondsField(text))
            return false;
        switch (Character.toUpperCase(last)) {
        case CH_N:
        case CH_E:
        case CH_S:
        case CH_W:
            return true;
        default:
            return false;
        }
    }

    /**
     * Whether the trailing lower-case {@code s} is abbreviating a seconds field rather than
     * naming the southern hemisphere: a lettered minutes marker opened a field, and the
     * character in front of the {@code s} is a digit sitting in it.
     *
     * <p>Both halves are as narrow as they are because a shape broke without them.
     *
     * <p><b>The marker must be {@link #CH_MIN_ABBREV}, not {@link #CH_MIN_SYMBOL}.</b> There are
     * two spellings of an angle here and this class never mixes them: {@link #ddmmssPattern4}
     * writes the lettered {@code 12d34m57s}, everything else writes the symbolic
     * {@code 12d34'57"}. So the {@code s} abbreviation belongs to the lettered spelling alone.
     * Accepting {@code '} as well made {@code "12d34'57s"} read {@code +12.5825} where master
     * and 9.8.1 agree on {@code -12.5825} — a divergence from both, bought for a spelling
     * nothing writes. Requiring the {@code m} leaves the round trip intact and the parity too.
     *
     * <p><b>A digit must precede the {@code s}.</b> Otherwise it sits after a marker that has
     * already closed its own field, which makes it a suffix rather than an abbreviation:
     * {@code "12d34m56\"s"} is South, not 56 seconds twice over.
     *
     * <p>Neither half applies to {@code "18d54s"}, which has no minutes marker at all and so
     * stays South, agreeing with master and with {@code dmstor}.
     */
    private static boolean closesASecondsField(String text) {
        int n = text.length();
        if (n < 2 || !Character.isDigit(text.charAt(n - 2)))
            return false;
        return text.lastIndexOf(CH_MIN_ABBREV, n - 3) >= 0;
    }

    /**
     * The index of the degree marker, {@code d} or {@code °}, or {@code -1}.
     *
     * <p><b>Lowercase {@code d} only.</b> {@code dmstor} takes either case - its unit table is
     * {@code "Dd°'\\\"rR"} - so {@code "18D54S"} is an angle upstream and is not one here:
     * with no marker found the text falls through to {@code Double.parseDouble}, which throws.
     * That gap predates this branch and is left alone rather than widened into a second parity
     * argument. It is unreachable from anything shipped - of the 323 distinct DMS values in the
     * registries, <b>none</b> uses an uppercase {@code D}, and the gie corpus has none either
     * (counted over text files only; {@code nad/conus} and {@code nad/ntv1_can.dat} are binary
     * grids and a naive grep over them reports thousands of false hits).
     */
    static int indexOfDegreeMarker(String text) {
        int i = text.indexOf(CH_DEG_ABBREV);
        if (i == -1)
            i = text.indexOf(CH_DEG_SYMBOL);
        return i;
    }

    /**
     * Whether a degrees field is negative zero, which is how {@code "-0d30"} arrives:
     * {@code Double.valueOf("-0")} is {@code -0.0} and carries the only copy of the minus sign
     * the caller wrote.
     *
     * <p>{@link ProjectionMath#dmsToDeg} chooses where to put the sign with {@code if (d >= 0)},
     * and negative zero satisfies that, so the minutes were added to it rather than subtracted
     * and half a degree south came back as half a degree north. It is fixed here, at the call
     * site, rather than in {@code ProjectionMath}: 187 registry tokens are of the {@code -#d#}
     * form (99 in {@code nad27}, 88 in {@code nad83}, 77 distinct) and take that method's
     * {@code d - m/60 - s/3600} branch, and leaving the method untouched is the only way to be
     * sure none of them shifts by an ulp.
     *
     * <p>Negating afterwards is exact and not merely close. IEEE-754 addition and multiplication
     * are symmetric about zero, so {@code -(0.0 + m/60 + s/3600)} has the same bits as
     * {@code (-0.0 - m/60 - s/3600)} for every input.
     *
     * <h4>A trailing cardinal overrules a leading minus</h4>
     *
     * <p>The two signs do not compound. When a hemisphere letter is present the letter sets the
     * sign outright and any leading minus is discarded, so {@code "-0d34S"} is {@code -0.5667}
     * and {@code "-0d34N"} is {@code +0.5667}. That is {@code dmstor}'s rule — its postfix-sign
     * test assigns rather than multiplies ({@code sign = idx >= 4 ? -1 : 1} in 9.8.1's
     * {@code dmstor.cpp}, ported at {@code conformance/.../parse/ProjDmsToR.java:181}) — and
     * 9.8.1 was measured on each of these.
     *
     * <p>The alternative, letting both negations apply, was tried and reverted. It fixed
     * {@code "-0d30"} but broke five shapes this class already got right, turning
     * {@code "-0d30W"} from {@code -0.5} into {@code +0.5} and {@code "-0d0'30\"W"} from
     * {@code -0.008333} into {@code +0.008333}. Assigning fixes all of them at once, and also
     * corrects two long-standing readings that predate this change: {@code "-1d30E"} was
     * {@code -1.5} against upstream's {@code +1.5}, and {@code "-12d34S"} was {@code +12.5667}
     * against {@code -12.5667}.
     *
     * <p>Nothing shipped depends on either reading. Of the 735 DMS tokens in the registries, 187
     * open with a minus and 66 close with a cardinal, and <b>none does both</b>; the gie corpus
     * has none either. Pinned in {@code AngleFormatParseTest}.
     */
    static boolean isNegativeZero(double d) {
        return d == 0.0 && Double.doubleToRawLongBits(d) != 0L;
    }

    /**
     *
     * @param text
     * @return
     * @deprecated
     * @see Angle#parse(String)
     */
    public Number parse(String text, ParsePosition parsePosition) {
        double d = 0, m = 0, s = 0;
        double result;
        boolean hemisphere = false;
        boolean negate = false;
        // Recorded before the hemisphere letter is chopped off. Reporting the length of the
        // truncated string left the index one short of the letter, so a caller reading several
        // angles from one string with a shared ParsePosition read the N again as the start of
        // the next angle.
        final int consumed = text.length();
        int i = AngleFormat.indexOfDegreeMarker(text);
        if (isHemisphereLetter(text)) {
            char c = Character.toUpperCase(text.charAt(consumed-1));
            hemisphere = true;
            if (c == CH_W || c == CH_S)
                negate = true;
            // The degree marker sits in front of the suffix, so i is still valid afterwards.
            text = text.substring(0, consumed-1);
        }
        if (i != -1) {
            String dd = text.substring(0, i);
            String mmss = text.substring(i+1);
            d = Double.valueOf(dd).doubleValue();
            // The letter and the apostrophe are interchangeable for minutes, on the reading
            // side as well as the writing side -- see Angle.parse.
            i = mmss.indexOf(CH_MIN_ABBREV);
            if (i == -1)
                i = mmss.indexOf(CH_MIN_SYMBOL);
            if (i != -1) {
                if (i != 0) {
                    String mm = mmss.substring(0, i);
                    m = Double.valueOf(mm).doubleValue();
                }
                if (mmss.endsWith(STR_SEC_ABBREV) || mmss.endsWith(STR_SEC_SYMBOL))
                    mmss = mmss.substring(0, mmss.length()-1);
                if (i != mmss.length()-1) {
                    String ss = mmss.substring(i+1);
                    s = Double.valueOf(ss).doubleValue();
                }
                if (m < 0 || m >= 60)
                    throw new NumberFormatException("Minutes must be at least 0 and less than 60");
                if (s < 0 || s >= 60)
                    throw new NumberFormatException("Seconds must be at least 0 and less than 60");
            } else if (mmss.length() != 0)
                m = Double.valueOf(mmss).doubleValue();
            if (isDegrees)
                result = ProjectionMath.dmsToDeg(d, m, s);
            else
                result = ProjectionMath.dmsToRad(d, m, s);
            if (isNegativeZero(d))
                result = -result;
        } else {
            result = Double.parseDouble(text);
            if (!isDegrees)
                result = ProjectionMath.toRad(result);
        }
        if (parsePosition != null)
            parsePosition.setIndex(consumed);
        // The letter assigns the sign rather than flipping it, so a leading minus in front of a
        // cardinal is discarded. See isNegativeZero for why, and for what it costs.
        if (hemisphere)
            result = negate ? -Math.abs(result) : Math.abs(result);
        return new Double(result);
    }
}
