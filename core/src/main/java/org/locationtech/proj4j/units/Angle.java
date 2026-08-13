/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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

import org.locationtech.proj4j.util.ProjectionMath;

public class Angle
{

    /**
     * Parses a text representation of a degree angle in various formats.
     *
     * Formats include DMS and DD in the forms
     * supported by {@link AngleFormat},
     * as in the following examples:
     * <pre>
     * 123.12
     * -123.12
     * 123.12W
     *
     * 123d44'44.555"
     * 123d44mN
     * </pre>
     *
     * <p><b>{@code m} and {@code '} both mean minutes, and either one lets the angle continue
     * into a seconds field.</b> {@code "123d44m44.555s"} and {@code "123d44'44.555\""} are the
     * same angle here. Upstream is stricter and this method deliberately does not follow it:
     * {@code dmstor} recognises {@code d}, {@code D}, both spellings of the degree sign,
     * {@code '} and {@code "} as unit markers - and a trailing {@code r} for radians - but not
     * {@code m}, so its digit loop takes the 44 as minutes, stops at the unrecognised {@code m}
     * and returns 123.7333 with the seconds dropped (9.8.1:src/dmstor.cpp, ported in
     * {@code conformance/.../parse/ProjDmsToR.java}).
     *
     * <p>Two things decided that. {@link AngleFormat#ddmmssPattern4} is {@code DdMmSs}, so this
     * class writes {@code 12d34m57s} itself, and following upstream would leave it unable to
     * read back its own output. And no angular token in the shipped registries or in the gie
     * corpus contains an {@code m} at all — zero of the 1,792 distinct angular values in the
     * registries and zero in the corpus, measured — so the strictness would buy no measurable
     * parity in exchange for that round trip.
     *
     * @param text
     * @return the value of the angle, in degrees
     */
    public static double parse(String text)
        throws NumberFormatException
    {
        double d = 0, m = 0, s = 0;
        double result;
        boolean hemisphere = false;
        boolean negate = false;
        int length = text.length();
        int i = AngleFormat.indexOfDegreeMarker(text);
        if (AngleFormat.isHemisphereLetter(text)) {
            char c = Character.toUpperCase(text.charAt(length-1));
            hemisphere = true;
            if (c == AngleFormat.CH_W || c == AngleFormat.CH_S)
                negate = true;
            // The degree marker sits in front of the suffix, so i is still valid afterwards.
            text = text.substring(0, length-1);
        }
        if (i != -1) {
            String dd = text.substring(0, i);
            String mmss = text.substring(i+1);
            d = Double.valueOf(dd).doubleValue();
            /*
             * The letter and the apostrophe are interchangeable for minutes on the reading side
             * as well as the writing side, so 123d44m44.555s and 123d44'44.555" are the same
             * angle. dmstor drops the seconds on the first of those; see the class Javadoc for
             * why we do not follow it there.
             */
            i = mmss.indexOf(AngleFormat.CH_MIN_ABBREV);
            if (i == -1)
                i = mmss.indexOf(AngleFormat.CH_MIN_SYMBOL);
            if (i != -1) {
                if (i != 0) {
                    String mm = mmss.substring(0, i);
                    m = Double.valueOf(mm).doubleValue();
                }
                if (mmss.endsWith(AngleFormat.STR_SEC_ABBREV) || mmss.endsWith(AngleFormat.STR_SEC_SYMBOL))
                    mmss = mmss.substring(0, mmss.length()-1);
                if (i != mmss.length()-1) {
                    String ss = mmss.substring(i+1);
                    s = Double.valueOf(ss).doubleValue();
                }
                // Both bounds are "at least 0 and less than 60". Minutes used to be checked
                // with m > 59, which rejected the fractional 59.5 that the identically-worded
                // seconds check accepts, and 123d59.5m is a real angle.
                if (m < 0 || m >= 60)
                    throw new NumberFormatException("Minutes must be at least 0 and less than 60");
                if (s < 0 || s >= 60)
                    throw new NumberFormatException("Seconds must be at least 0 and less than 60");
            } else if (mmss.length() != 0)
                m = Double.valueOf(mmss).doubleValue();
            result = ProjectionMath.dmsToDeg(d, m, s);
            if (AngleFormat.isNegativeZero(d))
                result = -result;
        } else {
            result = Double.parseDouble(text);
        }
        // The letter assigns the sign rather than flipping it, so a leading minus in front of a
        // cardinal is discarded. See AngleFormat.isNegativeZero for why, and for what it costs.
        if (hemisphere)
            result = negate ? -Math.abs(result) : Math.abs(result);
        return result;
    }

}
