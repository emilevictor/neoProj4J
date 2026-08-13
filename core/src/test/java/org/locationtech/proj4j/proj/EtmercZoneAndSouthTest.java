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
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.datum.Ellipsoid;

/**
 * {@code +zone} and {@code +south} on {@code +proj=etmerc} and {@code +proj=tmerc}, the way the
 * <em>value</em> of {@code +south} is read on {@code +proj=utm}, and the 1..60 range of
 * {@code +zone}.
 *
 * <h2>What upstream does</h2>
 *
 * <p>Neither parameter belongs to {@code etmerc}, and neither belongs to {@code tmerc}. In
 * {@code 9.8.1:src/projections/tmerc.cpp}, {@code PJ_PROJECTION(etmerc)} is four lines: reject a
 * sphere, then {@code setup(P, PODER_ENGSAGER)}. It never calls {@code pj_param} for {@code zone}
 * or for {@code south}. {@code PJ_PROJECTION(tmerc)} reads {@code approx} and {@code algo} and
 * nothing else. The only reader of either parameter is {@code PJ_PROJECTION(utm)}, twenty lines
 * further down, which does
 * {@code P->y0 = pj_param(P->ctx, P->params, "bsouth").i ? 10000000. : 0.} and then
 * {@code pj_param(..., "tzone")} / {@code "izone"}, rejecting anything outside 1..60.
 *
 * <p>So on PROJ 9.8.1, {@code +proj=etmerc +zone=33 +south +ellps=GRS80} is <b>plain
 * {@code etmerc}</b> — both tokens are parsed, neither is read, and {@code proj} projects
 * {@code (12, 56)} to {@code (746631.146104377, 6273771.204197558)}, byte for byte what
 * {@code +proj=etmerc +ellps=GRS80} alone gives. {@code +proj=tmerc +zone=33 +ellps=GRS80} gives
 * that same point, and so does bare {@code +proj=tmerc +ellps=GRS80}. Verified against the
 * installed {@code Rel. 9.8.1, April 10th, 2026}, driven as
 * {@code echo '12 56' | proj -f "%.9f" <definition>}.
 *
 * <h2>What proj4j does</h2>
 *
 * <p>The same thing, as of defect #97. <b>These are reference values now, not markers.</b> The
 * file was written one release earlier, when every {@code +zone}-on-{@code etmerc} expectation in
 * it was a pin on proj4j's own wrong answer with PROJ's number in a comment beside it; those
 * assertions have been flipped to the numbers the comments gave, and the tests renamed to say what
 * they now guarantee rather than what they used to record.
 *
 * <h2>Where this file does not match PROJ 9.8.1</h2>
 *
 * <p>Eight of the definitions used here are refused by proj4j and not refused by PROJ 9.8.1 in the
 * same way. An earlier version of this comment said there were two of them and called both "error
 * behaviour"; that was wrong on both counts. The eight split into two groups, and only the first
 * group is a difference of form.
 *
 * <p><b>Three where PROJ refuses too, just with a different error.</b>
 * {@code +proj=utm +zone=0}, {@code +zone=61} and {@code +zone=99} raise
 * {@link InvalidValueException} here and {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} there —
 * error 1027, {@code "utm: Invalid value for zone"}. Same verdict, different exception type.
 *
 * <p><b>Five where PROJ returns a coordinate and we throw.</b> All of them are a {@code +south}
 * with a value the parser will not guess at:
 *
 * <pre>
 *   +proj=utm    +zone=33 +south=0      312928.560890558   6210141.326748008   northern
 *   +proj=utm    +zone=33 +south=false  312928.560890558   6210141.326748008   northern
 *   +proj=utm    +zone=33 +south=yes    312928.560890558   6210141.326748008   northern
 *   +proj=utm    +zone=33 +south=true   312928.560890558  16210141.326748008   SOUTHERN
 *   +proj=etmerc +zone=33 +south=0      746631.146104377   6273771.204197558   plain etmerc
 * </pre>
 *
 * <p>{@code +south=true} is the row to notice: PROJ does not merely accept it, it reads it as the
 * <em>southern</em> hemisphere. That is a disagreement about the answer, not about the error type,
 * and the old "both are error behaviour" sentence hid it.
 *
 * <p>The cause is that {@code pj_param}'s {@code b} sigil looks at the <b>first character only</b>.
 * {@code true} begins with {@code t}, so it is true; {@code false} begins with {@code f}, so it is
 * false; {@code 0} and {@code yes} match neither arm, and the {@code default:} branch at
 * {@code param.cpp:199-215} sets {@code errno} <em>and</em> sets {@code value.i = 0} — but no
 * caller checks that {@code errno}, so the zero is used and the answer comes back "northern" with
 * no complaint. The same first-letter rule makes
 * {@code +proj=utm +zone=33 +south=tomato +ellps=GRS80} project to
 * {@code (312928.560890558, 16210141.326748008)} — southern, on the {@code t} of "tomato" — which
 * was run against the installed 9.8.1 to confirm it. So any claim that PROJ "accepts an absent
 * value, T/t and F/f, and nothing else" is false: it accepts everything and guesses.
 *
 * <p>proj4j refuses all five instead, and that is a deliberate choice rather than parity: choosing
 * a hemisphere from the first letter of a word is how a 10,000 km error ships without an error
 * message. Do not make these tests pass by teaching the parser to guess.
 *
 * <h2>The five things pinned here</h2>
 *
 * <ol>
 * <li>{@code +zone} is ignored on {@code etmerc} and on {@code tmerc}, as upstream ignores any key
 *     no operator reads ({@link #etmercIgnoresZoneAsProjDoes},
 *     {@link #tmercIgnoresZoneAsProjDoes}).</li>
 * <li>{@code +south} is inert on {@code etmerc} with or without {@code +zone}: the flag reaches
 *     the projection, and nothing derives a false northing from it, because only
 *     {@code setUTMZone} ever does and {@code etmerc} no longer has a {@code +zone} dispatch
 *     ({@link #etmercIgnoresZoneAndSouthAsProjDoes},
 *     {@link #southWithoutZoneIsInertOnEtmerc}).</li>
 * <li>{@code +south=f} is <em>false</em>, and {@code +south=0} is an <em>error</em> here where
 *     PROJ reads it as northern, because the
 *     parser reads the value with {@code pj_param}'s {@code b} sigil instead of testing
 *     {@code params.containsKey(south)}. This one was never confined to {@code etmerc}: it put
 *     {@code +proj=utm +zone=33 +south=f} — a definition where the parameter genuinely applies —
 *     10,000 km out of position ({@link #southEqualsFIsFalse},
 *     {@link #southValuesWeRefuseToGuessAt}).</li>
 * <li>A {@code +zone} outside 1..60 is rejected on {@code utm} and ignored on {@code etmerc} and
 *     {@code tmerc}; none of the three can reach a central meridian of 183&deg; any more
 *     ({@link #zoneOutsideOneToSixtyIsRejectedOnUtm},
 *     {@link #zoneOutsideOneToSixtyIsIgnoredOnEtmerc},
 *     {@link #tmercIgnoresZoneAsProjDoes},
 *     {@link #setUtmZoneRangeChecksOnBothClasses}).</li>
 * <li>{@code +south=} with an explicitly empty value is <em>false</em>, while a bare
 *     {@code +south} is still true. The two are written differently and mean different things
 *     ({@link #southWithAnEmptyValueIsNorthern}).</li>
 * </ol>
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>Five alignments with PROJ 9.8.1 lose their only test, and the absence of a
 * {@code +zone}-on-{@code etmerc} and {@code +zone}-on-{@code tmerc} dispatch loses the thing that
 * would notice either being re-added. {@link #utmKeepsItsZoneAndSouth} is the other half of that:
 * it is here so that "ignore it on {@code etmerc}" cannot quietly become "ignore it everywhere".
 */
public class EtmercZoneAndSouthTest {

    private static final CRSFactory CRS = new CRSFactory();

    /** A test point in UTM zone 33, north of the equator. Copenhagen-ish. */
    private static final double LON = 12;
    private static final double LAT = 56;

    /** A micrometre; proj4j and PROJ agree to about a nanometre where they agree at all. */
    private static final double TIGHT_METRES = 1.0e-6;

    /*
     * PROJ 9.8.1, +ellps=GRS80, at (12, 56), printed with -f "%.9f":
     *
     *   +proj=etmerc                     746631.146104377  6273771.204197558
     *   +proj=etmerc +zone=33            746631.146104377  6273771.204197558   (+zone ignored)
     *   +proj=etmerc +zone=33 +south     746631.146104377  6273771.204197558   (both ignored)
     *   +proj=etmerc +zone=99            746631.146104377  6273771.204197558   (ignored, not checked)
     *   +proj=tmerc                      746631.146104377  6273771.204197558
     *   +proj=tmerc  +zone=33            746631.146104377  6273771.204197558   (+zone ignored)
     *   +proj=tmerc  +zone=61            746631.146104377  6273771.204197558   (ignored, not checked)
     *   +proj=utm    +zone=33            312928.560890558  6210141.326748008
     *   +proj=utm    +zone=33 +south     312928.560890558 16210141.326748008
     *   +proj=utm    +zone=33 +south=f   312928.560890558  6210141.326748008   (+south=f is FALSE)
     *   +proj=utm    +zone=33 +south=    312928.560890558  6210141.326748008   (empty is FALSE)
     *   +proj=utm    +zone=0/61/99       error 1027, "utm: Invalid value for zone"
     *
     * See the class comment for the five +south values PROJ answers and proj4j refuses.
     */

    private static final double PROJ_PLAIN_ETMERC_X = 746631.146104377;
    private static final double PROJ_PLAIN_ETMERC_Y = 6273771.204197558;

    /*
     * Plain +proj=tmerc, which is the same point to every printed digit. PROJ 9.8.1 runs
     * Poder/Engsager for tmerc as well unless +approx or +algo says otherwise, so tmerc and
     * etmerc are the same algorithm at this point. Given its own name rather than reusing the
     * etmerc constants so that a future change to either default cannot silently retarget the
     * other operator's test.
     */
    private static final double PROJ_PLAIN_TMERC_X = 746631.146104377;
    private static final double PROJ_PLAIN_TMERC_Y = 6273771.204197558;

    private static final double UTM33_X = 312928.560890558;
    private static final double UTM33_NORTH_Y = 6210141.326748008;
    private static final double UTM33_SOUTH_Y = 16210141.326748008;

    /**
     * The unparameterised baseline: proj4j and PROJ 9.8.1 agree on
     * {@code +proj=etmerc +ellps=GRS80}.
     *
     * <p>Most of the file now asserts that some combination of {@code +zone} and {@code +south}
     * lands back on this line, so if this one drifts the rest is measuring the wrong thing.
     */
    @Test
    public void plainEtmercAgreesWithProj() {
        ProjCoordinate got = forward("+proj=etmerc +ellps=GRS80");
        assertEquals("plain +proj=etmerc no longer matches PROJ 9.8.1: easting",
                PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
        assertEquals("plain +proj=etmerc no longer matches PROJ 9.8.1: northing",
                PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);
    }

    /**
     * 1. {@code +zone} on {@code etmerc} changes nothing.
     *
     * <p>{@code PJ_PROJECTION(etmerc)} never reads {@code zone}, so upstream retains the token and
     * ignores it — the same thing it does with any key no operator asks for. proj4j used to
     * dispatch {@code +zone} on {@code projection instanceof ExtendedTransverseMercatorProjection}
     * and install the entire UTM frame from it: {@code lon_0 = 15}, {@code k = 0.9996},
     * {@code x_0 = 500000}, putting the answer 434 km east of PROJ's.
     *
     * <p>The frame is asserted as well as the coordinate because they fail differently: a frame
     * assertion says the parser re-grew the dispatch, a coordinate assertion could also mean the
     * series drifted.
     */
    @Test
    public void etmercIgnoresZoneAsProjDoes() {
        Projection p = projection("+proj=etmerc +zone=33 +ellps=GRS80");

        assertEquals("+zone=33 set a central meridian on etmerc, so the parser has re-grown the "
                        + "+zone dispatch PROJ does not have; upstream leaves lon_0 at 0",
                0.0, p.getProjectionLongitudeDegrees(), 1.0e-13);
        assertEquals("+zone set the UTM scale factor on etmerc; upstream leaves k at 1", 1.0,
                p.getScaleFactor(), 0.0);
        assertEquals("+zone set the UTM false easting on etmerc; upstream leaves x_0 at 0", 0.0,
                p.getFalseEasting(), 0.0);
        assertEquals("+zone alone must not touch the false northing", 0.0,
                p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +zone=33 +ellps=GRS80");
        assertEquals("+proj=etmerc +zone=33 must be plain etmerc: easting",
                PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
        assertEquals("+proj=etmerc +zone=33 must be plain etmerc: northing",
                PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);

        // Stated the second way as well: whatever plain etmerc answers, the zoned one answers
        // bit for bit. This one survives a change of ellipsoid or of the series.
        ProjCoordinate plain = forward("+proj=etmerc +ellps=GRS80");
        assertEquals("+zone=33 moved the easting away from plain etmerc", plain.x, got.x, 0.0);
        assertEquals("+zone=33 moved the northing away from plain etmerc", plain.y, got.y, 0.0);
    }

    /**
     * 1b. {@code +zone} on {@code tmerc} changes nothing either — the same story as
     * {@link #etmercIgnoresZoneAsProjDoes}, on the operator where it survived longest.
     *
     * <p>{@link org.locationtech.proj4j.Registry} binds <b>both</b> {@code tmerc} and {@code utm}
     * to {@link TransverseMercatorProjection}, so a parser guard written as
     * {@code projection instanceof TransverseMercatorProjection} catches {@code +proj=tmerc} as
     * well and installs the whole UTM frame on it: {@code lon_0} from the zone, {@code k = 0.9996},
     * {@code x_0 = 500000}. The {@code etmerc} half of that defect was removed first and this half
     * was left live and untested, on the one operator of the three that the golden corpus actually
     * exercises ({@code mod/tmerc/zone}). The guard is now keyed on
     * {@code "utm".equals(+proj)} instead of on the class.
     *
     * <p>Measured on the installed {@code Rel. 9.8.1, April 10th, 2026}:
     * {@code echo '12 56' | proj -f "%.9f" +proj=tmerc +zone=33 +ellps=GRS80} and the same command
     * without {@code +zone} both print {@code 746631.146104377  6273771.204197558}. proj4j used to
     * answer {@code 312928.56} for the first of the two, 434 km east.
     *
     * <p>{@code +zone=61} is asserted alongside {@code +zone=33} because it is out of range and
     * must still be <em>accepted</em>: on this path nothing reads the zone, so nothing
     * range-checks it, and PROJ likewise accepts and ignores it. Rejecting it here would be a
     * divergence in the opposite direction — see {@link #zoneOutsideOneToSixtyIsIgnoredOnEtmerc},
     * which makes the same point for {@code etmerc}.
     */
    @Test
    public void tmercIgnoresZoneAsProjDoes() {
        ProjCoordinate plain = forward("+proj=tmerc +ellps=GRS80");
        assertEquals("plain +proj=tmerc no longer matches PROJ 9.8.1: easting",
                PROJ_PLAIN_TMERC_X, plain.x, TIGHT_METRES);
        assertEquals("plain +proj=tmerc no longer matches PROJ 9.8.1: northing",
                PROJ_PLAIN_TMERC_Y, plain.y, TIGHT_METRES);

        for (String zone : new String[] {"33", "61"}) {
            String definition = "+proj=tmerc +zone=" + zone + " +ellps=GRS80";
            Projection p = projection(definition);

            assertEquals("+zone=" + zone + " set a central meridian on tmerc; the +zone dispatch "
                            + "is keyed on +proj=utm and tmerc must not reach it",
                    0.0, p.getProjectionLongitudeDegrees(), 1.0e-13);
            assertEquals("+zone=" + zone + " set the UTM scale factor on tmerc; upstream leaves "
                    + "k at 1", 1.0, p.getScaleFactor(), 0.0);
            assertEquals("+zone=" + zone + " set the UTM false easting on tmerc; upstream leaves "
                    + "x_0 at 0", 0.0, p.getFalseEasting(), 0.0);
            assertEquals("+zone=" + zone + " must not touch the false northing on tmerc", 0.0,
                    p.getFalseNorthing(), 0.0);

            ProjCoordinate got = forward(definition);
            assertEquals(definition + " must be plain tmerc: easting", PROJ_PLAIN_TMERC_X, got.x,
                    TIGHT_METRES);
            assertEquals(definition + " must be plain tmerc: northing", PROJ_PLAIN_TMERC_Y, got.y,
                    TIGHT_METRES);

            // And stated against this build's own plain tmerc, so it survives a change of
            // ellipsoid or of the series.
            assertEquals("+zone=" + zone + " moved the easting away from plain tmerc", plain.x,
                    got.x, 0.0);
            assertEquals("+zone=" + zone + " moved the northing away from plain tmerc", plain.y,
                    got.y, 0.0);
        }
    }

    /**
     * 2. {@code +proj=etmerc +zone=N +south}, the combination defect #97 names. Both tokens are
     * ignored, so this is plain {@code etmerc} too.
     *
     * <p>{@code +south} is <em>not</em> narrowed to {@code utm} in the parser: it is still
     * dispatched through {@code Projection.setSouthernHemisphere} for everything that implements
     * it, so the flag genuinely reaches this projection. It is inert because the 10,000 km false
     * northing is only ever derived inside {@code setUTMZone}, and {@code etmerc} no longer has a
     * {@code +zone} dispatch to call it. Upstream reaches the same coordinate by the shorter route
     * of never reading either token — same answer, different reason, which is why the flag itself
     * is asserted rather than only the output.
     *
     * <p>Also asserted: the two tokens commute. The parser sets the southern flag at one place and
     * would read {@code +zone} at another, later, place, so the order they appear in must not
     * matter.
     */
    @Test
    public void etmercIgnoresZoneAndSouthAsProjDoes() {
        Projection p = projection("+proj=etmerc +zone=33 +south +ellps=GRS80");
        assertTrue("+south no longer reaches the projection; it is still dispatched generally, "
                        + "and narrowing it to utm alone is a wider change than #97",
                p.getSouthernHemisphere());
        assertEquals("+south installed a false northing on etmerc: only setUTMZone derives one, "
                        + "so the +zone-on-etmerc dispatch must be back",
                0.0, p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +zone=33 +south +ellps=GRS80");
        assertEquals("+proj=etmerc +zone=33 +south must be plain etmerc: easting",
                PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
        assertEquals("+proj=etmerc +zone=33 +south must be plain etmerc: northing",
                PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);

        ProjCoordinate reordered = forward("+proj=etmerc +south +zone=33 +ellps=GRS80");
        assertEquals("+south before +zone gives a different easting from +zone before +south, so "
                        + "the parser is order-dependent", got.x, reordered.x, 0.0);
        assertEquals("+south before +zone gives a different northing from +zone before +south",
                got.y, reordered.y, 0.0);
    }

    /**
     * 3a. {@code +south=f} means the northern hemisphere.
     *
     * <p>{@code Proj4Parser} used to do {@code if (params.containsKey(south))
     * setSouthernHemisphere(true)}. Upstream reads the same token with {@code pj_param}'s
     * {@code b} sigil, for which a bare {@code +south} is true and {@code +south=f} is false, so
     * {@code +proj=utm +zone=33 +south=f} is northern — {@code (312928.560890558,
     * 6210141.326748008)} — and proj4j answered 10,000 km away from that.
     *
     * <p>{@code F} is tested alongside {@code f} because the sigil accepts both cases, and
     * {@code etmerc} alongside {@code utm} because the flag reaches the projection in both and
     * only the {@code utm} frame does anything with it.
     */
    @Test
    public void southEqualsFIsFalse() {
        for (String falsey : new String[] {"+south=f", "+south=F"}) {
            Projection utmProj = projection("+proj=utm +zone=33 " + falsey + " +ellps=GRS80");
            assertFalse(falsey + " is still read as the southern hemisphere; pj_param's 'b' sigil "
                            + "reads f/F as false", utmProj.getSouthernHemisphere());
            assertEquals(falsey + " left the 10,000 km false northing in place", 0.0,
                    utmProj.getFalseNorthing(), 0.0);

            ProjCoordinate utm = forward("+proj=utm +zone=33 " + falsey + " +ellps=GRS80");
            assertEquals("+proj=utm +zone=33 " + falsey + ": easting", UTM33_X, utm.x,
                    TIGHT_METRES);
            assertEquals("+proj=utm +zone=33 " + falsey + " must be the northern answer",
                    UTM33_NORTH_Y, utm.y, TIGHT_METRES);

            ProjCoordinate etmerc = forward("+proj=etmerc +zone=33 " + falsey + " +ellps=GRS80");
            assertEquals("+proj=etmerc +zone=33 " + falsey + " must be plain etmerc: easting",
                    PROJ_PLAIN_ETMERC_X, etmerc.x, TIGHT_METRES);
            assertEquals("+proj=etmerc +zone=33 " + falsey + " must be plain etmerc: northing",
                    PROJ_PLAIN_ETMERC_Y, etmerc.y, TIGHT_METRES);
        }
    }

    /**
     * 3b. A {@code +south} value that is neither empty nor {@code T}/{@code t}/{@code F}/{@code f}
     * is refused. <b>These five are the file's real divergence from PROJ 9.8.1</b>, and PROJ
     * answers all five rather than failing any of them.
     *
     * <p>An earlier version of this comment said {@code pj_param}'s {@code b} sigil "accepts an
     * absent value, T/t and F/f, and nothing else" and that PROJ therefore fails these
     * definitions. That was simply untrue, and it is worth writing down why, because the mistake
     * points the wrong way — it makes a coordinate disagreement look like a difference of
     * exception type.
     *
     * <p>The sigil reads the <b>first character only</b>. Its {@code default:} branch
     * ({@code param.cpp:199-215}) sets {@code errno} and also sets {@code value.i = 0}, and
     * nothing checks that {@code errno}, so the zero is used. Measured on the installed
     * {@code Rel. 9.8.1, April 10th, 2026} at {@code (12, 56)} with {@code +ellps=GRS80}:
     *
     * <pre>
     *   +proj=utm    +zone=33 +south=0      312928.560890558   6210141.326748008   northern
     *   +proj=utm    +zone=33 +south=false  312928.560890558   6210141.326748008   northern
     *   +proj=utm    +zone=33 +south=yes    312928.560890558   6210141.326748008   northern
     *   +proj=utm    +zone=33 +south=true   312928.560890558  16210141.326748008   SOUTHERN
     *   +proj=etmerc +zone=33 +south=0      746631.146104377   6273771.204197558   plain etmerc
     * </pre>
     *
     * <p>{@code +south=true} is southern upstream, on the strength of its leading {@code t}, so
     * refusing it is a disagreement about the answer and not about the error type.
     * {@code +south=false} is northern on its leading {@code f}. {@code 0} and {@code yes} hit the
     * {@code default:} branch and come back northern with no complaint.
     *
     * <p>proj4j raises {@link InvalidValueException} for all five, on purpose. Reading a
     * hemisphere off the first letter of a word is the silent-wrong-answer shape this file exists
     * to remove, and no shipped registry entry writes {@code +south} with a value at all.
     *
     * <p>Split out of {@link #southEqualsFIsFalse} rather than looped with it: {@code f} returns a
     * coordinate and these never get that far, so no single assertion covers both. Before defect
     * #97 they were the same test, because {@code containsKey} made them the same bug.
     */
    @Test
    public void southValuesWeRefuseToGuessAt() {
        for (String definition : new String[] {
            "+proj=utm +zone=33 +south=0 +ellps=GRS80",
            "+proj=etmerc +zone=33 +south=0 +ellps=GRS80",
            "+proj=utm +zone=33 +south=yes +ellps=GRS80",
            "+proj=utm +zone=33 +south=true +ellps=GRS80",
            "+proj=utm +zone=33 +south=false +ellps=GRS80",
        }) {
            InvalidValueException e = assertThrows(
                    definition + " was accepted; proj4j takes an empty value, T/t and F/f and "
                            + "refuses the rest rather than reading a hemisphere off the first "
                            + "character the way PROJ does",
                    InvalidValueException.class,
                    new ThrowingParse(definition));
            assertTrue("the message should name +south and the offending value, not just fail: "
                            + e.getMessage(), e.getMessage().contains("south"));
        }
    }

    /**
     * 5. {@code +south=} written with an {@code =} and nothing after it is the <b>northern</b>
     * hemisphere, and a bare {@code +south} is still the southern one.
     *
     * <p>Both are in one test because the point is that they differ. {@code createParameterMap}
     * stores {@code null} for a bare flag and the empty string for a flag written with an empty
     * value, so {@code parseBoolean} can tell them apart: {@code null} is true, {@code ""} is
     * false.
     *
     * <p>{@code ""} used to be read as true, and that failure was <b>silent</b>. No exception, no
     * warning, no rejected definition — {@code +proj=utm +zone=33 +south=} simply answered
     * {@code 16210141.33} where PROJ 9.8.1 answers {@code 6210141.33}. The wrong hemisphere,
     * 10,000 km out, handed back as an ordinary result. That is the whole reason this row is
     * worth a test: nothing downstream could have noticed.
     *
     * <p>PROJ's side was measured on the installed {@code Rel. 9.8.1, April 10th, 2026}:
     * {@code echo '12 56' | proj -f "%.9f" +proj=utm +zone=33 +south= +ellps=GRS80} prints
     * {@code 312928.560890558  6210141.326748008}. Upstream reaches that by a different route —
     * its tokenizer drops a key with an empty value before the sigil ever sees it — but the answer
     * is the one asserted here.
     */
    @Test
    public void southWithAnEmptyValueIsNorthern() {
        Projection empty = projection("+proj=utm +zone=33 +south= +ellps=GRS80");
        assertFalse("+south= (an explicitly empty value) is being read as southern; it is false, "
                        + "and reading it as true is a 10,000 km error with no exception",
                empty.getSouthernHemisphere());
        assertEquals("+south= left the 10,000 km false northing in place", 0.0,
                empty.getFalseNorthing(), 0.0);

        ProjCoordinate emptyXy = forward("+proj=utm +zone=33 +south= +ellps=GRS80");
        assertEquals("+proj=utm +zone=33 +south=: easting", UTM33_X, emptyXy.x, TIGHT_METRES);
        assertEquals("+proj=utm +zone=33 +south= must be the northern answer", UTM33_NORTH_Y,
                emptyXy.y, TIGHT_METRES);

        // The other half: a bare +south, written with no '=' at all, is unchanged.
        Projection bare = projection("+proj=utm +zone=33 +south +ellps=GRS80");
        assertTrue("a bare +south stopped meaning the southern hemisphere; only +south= is false",
                bare.getSouthernHemisphere());

        ProjCoordinate bareXy = forward("+proj=utm +zone=33 +south +ellps=GRS80");
        assertEquals("+proj=utm +zone=33 +south: easting", UTM33_X, bareXy.x, TIGHT_METRES);
        assertEquals("+proj=utm +zone=33 +south must be the southern answer", UTM33_SOUTH_Y,
                bareXy.y, TIGHT_METRES);

        assertEquals("+south= and a bare +south gave the same northing, so the parser can no "
                        + "longer tell an empty value from an absent one",
                10000000.0, bareXy.y - emptyXy.y, TIGHT_METRES);
    }

    /**
     * A bare {@code +south} still does what it always did, on the operator that reads it.
     *
     * <p>The point of the file is that {@code +zone} and {@code +south} stop having an effect on
     * {@code etmerc}; this is the guard that they did not stop having one on {@code utm}, which is
     * the over-correction the {@code etmerc} fix invites. {@code UTM33_SOUTH_Y} is a PROJ 9.8.1
     * reference and is asserted here and nowhere else.
     */
    @Test
    public void utmKeepsItsZoneAndSouth() {
        Projection p = projection("+proj=utm +zone=33 +south +ellps=GRS80");
        assertTrue("+south stopped reaching +proj=utm", p.getSouthernHemisphere());
        assertEquals("+proj=utm lost the zone's central meridian", 15.0,
                p.getProjectionLongitudeDegrees(), 1.0e-13);
        assertEquals("+proj=utm lost the UTM scale factor", 0.9996, p.getScaleFactor(), 0.0);
        assertEquals("+proj=utm lost the UTM false easting", 500000.0, p.getFalseEasting(), 0.0);
        assertEquals("+proj=utm +south lost the 10,000 km false northing", 10000000.0,
                p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=utm +zone=33 +south +ellps=GRS80");
        assertEquals("+proj=utm +zone=33 +south: easting", UTM33_X, got.x, TIGHT_METRES);
        assertEquals("+proj=utm +zone=33 +south: northing", UTM33_SOUTH_Y, got.y, TIGHT_METRES);

        ProjCoordinate north = forward("+proj=utm +zone=33 +ellps=GRS80");
        assertEquals("+proj=utm +zone=33 without +south: easting", UTM33_X, north.x,
                TIGHT_METRES);
        assertEquals("+proj=utm +zone=33 without +south: northing", UTM33_NORTH_Y, north.y,
                TIGHT_METRES);
    }

    /**
     * {@code +south} without {@code +zone} is inert on {@code etmerc}: the flag is set, and
     * nothing reads it.
     *
     * <p>This test predates defect #97 and is unchanged by it, which is the interesting part. It
     * used to be the one row in the file that agreed with PROJ by coincidence — the false northing
     * is only ever derived inside {@code setUTMZone}, so with no zone there was nothing to derive
     * it from. Now that {@code etmerc} has no {@code +zone} dispatch at all, the same reasoning
     * covers {@link #etmercIgnoresZoneAndSouthAsProjDoes} as well, and the coincidence has become
     * the rule.
     */
    @Test
    public void southWithoutZoneIsInertOnEtmerc() {
        Projection p = projection("+proj=etmerc +south +ellps=GRS80");
        assertTrue("+south no longer reaches the projection", p.getSouthernHemisphere());
        assertEquals("the flag is set but nothing derives a false northing from it without a zone",
                0.0, p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +south +ellps=GRS80");
        assertEquals("+proj=etmerc +south must still be plain etmerc: easting",
                PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
        assertEquals("+proj=etmerc +south must still be plain etmerc: northing",
                PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);
    }

    /**
     * 4a. A zone outside 1..60 is refused on {@code utm}.
     *
     * <p>{@code PJ_PROJECTION(utm)} does {@code if (zone > 0 && zone <= 60) --zone; else "Invalid
     * value for zone"} and fails with {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. proj4j used
     * to compute {@code (zone - 1 + 0.5) * pi/30 - pi} unguarded and hand back a projection whose
     * central meridian was 183&deg; or -183&deg; — a meridian that does not exist, on a CRS that
     * then transformed without complaint.
     *
     * <p>{@code +zone=1} and {@code +zone=60} are asserted alongside so that the guard cannot be
     * fixed by rejecting the whole parameter.
     */
    @Test
    public void zoneOutsideOneToSixtyIsRejectedOnUtm() {
        for (String zone : new String[] {"0", "61", "99"}) {
            String definition = "+proj=utm +zone=" + zone + " +ellps=GRS80";
            InvalidValueException e = assertThrows(
                    definition + " was accepted; a UTM zone is 1..60 and PROJ rejects the rest at "
                            + "parse time", InvalidValueException.class,
                    new ThrowingParse(definition));
            // Not contains(zone): the message already says "1 to 60 inclusive", so for zone "0"
            // that passed no matter what the message said about the value the caller gave. The
            // key and the value together can only appear if the message really names them.
            assertTrue("the message should name +zone and the offending value together: "
                    + e.getMessage(), e.getMessage().contains("+zone: " + zone));
        }

        assertEquals("+zone=1 must still be accepted, at -177 degrees", -177.0,
                projection("+proj=utm +zone=1 +ellps=GRS80").getProjectionLongitudeDegrees(),
                1.0e-12);
        assertEquals("+zone=60 must still be accepted, at 177 degrees", 177.0,
                projection("+proj=utm +zone=60 +ellps=GRS80").getProjectionLongitudeDegrees(),
                1.0e-12);
    }

    /**
     * 4b. The same zones are <em>ignored</em>, not rejected, on {@code etmerc} — because
     * {@code etmerc} never reads {@code +zone} at all, so there is nothing to range-check.
     *
     * <p>This is the pair to {@link #zoneOutsideOneToSixtyIsRejectedOnUtm} and the two must not be
     * collapsed: "reject" and "ignore" are different upstream behaviours, and getting {@code
     * etmerc} to reject would be a divergence in the opposite direction from the one #97 fixed.
     */
    @Test
    public void zoneOutsideOneToSixtyIsIgnoredOnEtmerc() {
        for (String zone : new String[] {"0", "61", "99"}) {
            Projection p = projection("+proj=etmerc +zone=" + zone + " +ellps=GRS80");
            assertEquals("+proj=etmerc +zone=" + zone + " produced a central meridian; etmerc "
                            + "does not read +zone, so it cannot reach one",
                    0.0, p.getProjectionLongitudeDegrees(), 1.0e-13);

            ProjCoordinate got = forward("+proj=etmerc +zone=" + zone + " +ellps=GRS80");
            assertEquals("+proj=etmerc +zone=" + zone + " must be plain etmerc: easting",
                    PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
            assertEquals("+proj=etmerc +zone=" + zone + " must be plain etmerc: northing",
                    PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);
        }
    }

    /**
     * 4c. The range check is on the setter, on <b>both</b> classes, not on the parser.
     *
     * <p>{@code ExtendedTransverseMercatorProjection.setUTMZone} is no longer reachable from a
     * proj-string — that is what {@link #zoneOutsideOneToSixtyIsIgnoredOnEtmerc} asserts — but it
     * is public API and a caller can still install a UTM frame on the exact algorithm directly.
     * Leaving it unguarded would have made the guard depend on which door you came in by.
     */
    @Test
    public void setUtmZoneRangeChecksOnBothClasses() {
        for (final int zone : new int[] {0, -1, 61, 99}) {
            final TransverseMercatorProjection tmerc = new TransverseMercatorProjection();
            tmerc.setEllipsoid(Ellipsoid.GRS80);
            assertThrows("TransverseMercatorProjection.setUTMZone(" + zone + ") was accepted",
                    InvalidValueException.class, new ThrowingRunnable() {
                        public void run() {
                            tmerc.setUTMZone(zone);
                        }
                    });

            final ExtendedTransverseMercatorProjection etmerc =
                    new ExtendedTransverseMercatorProjection();
            etmerc.setEllipsoid(Ellipsoid.GRS80);
            assertThrows("ExtendedTransverseMercatorProjection.setUTMZone(" + zone
                            + ") was accepted", InvalidValueException.class,
                    new ThrowingRunnable() {
                        public void run() {
                            etmerc.setUTMZone(zone);
                        }
                    });
        }

        for (int zone : new int[] {1, 33, 60}) {
            double expectedLon0 = -183.0 + 6.0 * zone;

            TransverseMercatorProjection tmerc = new TransverseMercatorProjection();
            tmerc.setEllipsoid(Ellipsoid.GRS80);
            tmerc.setUTMZone(zone);
            assertEquals("TransverseMercatorProjection.setUTMZone(" + zone + ") central meridian",
                    expectedLon0, tmerc.getProjectionLongitudeDegrees(), 1.0e-12);

            ExtendedTransverseMercatorProjection etmerc =
                    new ExtendedTransverseMercatorProjection();
            etmerc.setEllipsoid(Ellipsoid.GRS80);
            etmerc.setUTMZone(zone);
            assertEquals("ExtendedTransverseMercatorProjection.setUTMZone(" + zone
                            + ") central meridian",
                    expectedLon0, etmerc.getProjectionLongitudeDegrees(), 1.0e-12);
        }
    }

    /**
     * Whatever frame {@code +zone} and {@code +south} end up installing, the forward and the
     * inverse must agree about it.
     *
     * <p>This is an identity rather than a reference value, so it was true before defect #97 and
     * is true after — which is the point: it is the assertion that survived the fix, and it is
     * unchanged from the version of this file that pinned the wrong answers.
     */
    @Test
    public void theZonedSouthernFrameIsSelfConsistent() {
        String[] definitions = {
            "+proj=etmerc +zone=33 +south +ellps=GRS80",
            "+proj=etmerc +zone=33 +ellps=GRS80",
            "+proj=tmerc +zone=33 +south +ellps=GRS80",
            "+proj=utm +zone=33 +south +ellps=GRS80",
            "+proj=etmerc +ellps=GRS80",
        };
        double[][] probes = {{LON, LAT}, {12, -56}, {17, 0.5}, {13.5, -34}};
        for (String definition : definitions) {
            Projection p = projection(definition);
            for (double[] probe : probes) {
                ProjCoordinate xy = new ProjCoordinate();
                p.project(new ProjCoordinate(probe[0], probe[1]), xy);
                ProjCoordinate lp = new ProjCoordinate();
                p.inverseProject(xy, lp);
                assertEquals(definition + " does not round trip at (" + probe[0] + ", " + probe[1]
                                + "): longitude", probe[0], lp.x, 1.0e-9);
                assertEquals(definition + " does not round trip at (" + probe[0] + ", " + probe[1]
                                + "): latitude", probe[1], lp.y, 1.0e-9);
            }
        }
    }

    /**
     * A southern-hemisphere point, so the 10,000 km offset is exercised where it is meant to be
     * used — and, on {@code etmerc}, where it is meant not to be.
     *
     * <p>{@code (12, -56)} under {@code +proj=utm +zone=33 +south} is
     * {@code (312928.560890558, 3789858.673251992)} in PROJ 9.8.1: 10,000,000 m minus the
     * meridional arc. Under {@code +proj=etmerc +zone=33 +south} it is
     * {@code (746631.146104377, -6273771.204197558)} — plain {@code etmerc}, and the mirror of
     * {@link #plainEtmercAgreesWithProj} because {@code etmerc} at {@code lat_0 = 0} is odd in
     * latitude. proj4j used to answer the first of those to a caller who asked for the second.
     */
    @Test
    public void theSouthernFalseNorthingAppliesToUtmAndNotToEtmerc() {
        ProjCoordinate utm = forward("+proj=utm +zone=33 +south +ellps=GRS80", 12, -56);
        assertEquals("+proj=utm +zone=33 +south at (12, -56): easting", 312928.560890558, utm.x,
                TIGHT_METRES);
        assertEquals("+proj=utm +zone=33 +south at (12, -56): northing, i.e. 10,000,000 m minus "
                        + "the meridional arc", 3789858.673251992, utm.y, TIGHT_METRES);

        ProjCoordinate etmerc = forward("+proj=etmerc +zone=33 +south +ellps=GRS80", 12, -56);
        assertEquals("+proj=etmerc +zone=33 +south at (12, -56) is answering as utm: easting",
                PROJ_PLAIN_ETMERC_X, etmerc.x, TIGHT_METRES);
        assertEquals("+proj=etmerc +zone=33 +south at (12, -56) is answering as utm: northing",
                -PROJ_PLAIN_ETMERC_Y, etmerc.y, TIGHT_METRES);
    }

    /** A parse deferred so {@code assertThrows} can run it. */
    private static final class ThrowingParse implements ThrowingRunnable {
        private final String definition;

        ThrowingParse(String definition) {
            this.definition = definition;
        }

        public void run() {
            projection(definition);
        }
    }

    private static Projection projection(String definition) {
        return CRS.createFromParameters("etmerc-test", definition + " +no_defs").getProjection();
    }

    private static ProjCoordinate forward(String definition) {
        return forward(definition, LON, LAT);
    }

    private static ProjCoordinate forward(String definition, double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).project(new ProjCoordinate(lon, lat), out);
        return out;
    }
}
