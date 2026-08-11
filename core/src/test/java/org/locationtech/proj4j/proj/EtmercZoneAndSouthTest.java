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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * {@code +zone} and {@code +south} on {@code +proj=etmerc}, and the {@code +south=f} reading that
 * they share with {@code +proj=utm}.
 *
 * <h2>What upstream does</h2>
 *
 * <p>Neither parameter belongs to {@code etmerc}. In {@code 9.8.1:src/projections/tmerc.cpp},
 * {@code PJ_PROJECTION(etmerc)} is four lines: reject a sphere, then {@code setup(P,
 * PODER_ENGSAGER)}. It never calls {@code pj_param} for {@code zone} or for {@code south}. The
 * only reader of either is {@code PJ_PROJECTION(utm)}, twenty lines further down, which does
 * {@code P->y0 = pj_param(P->ctx, P->params, "bsouth").i ? 10000000. : 0.} and then
 * {@code pj_param(..., "tzone")} / {@code "izone"}, rejecting anything outside 1..60.
 *
 * <p>So on PROJ 9.8.1, {@code +proj=etmerc +zone=33 +south +ellps=GRS80} is <b>plain
 * {@code etmerc}</b> — both tokens are parsed, neither is read, and {@code proj} projects
 * {@code (12, 56)} to {@code (746631.146104377, 6273771.204197558)}, byte for byte what
 * {@code +proj=etmerc +ellps=GRS80} alone gives. Verified against the installed
 * {@code Rel. 9.8.1, April 10th, 2026}.
 *
 * <h2>What proj4j does, and why these assertions are markers rather than references</h2>
 *
 * <p>{@code Proj4Parser} dispatches {@code +zone} on {@code projection instanceof
 * ExtendedTransverseMercatorProjection}, which {@code +proj=etmerc} is, so {@code etmerc} picks up
 * the whole UTM frame: {@code lon_0} from the zone, {@code k = 0.9996}, {@code x_0 = 500000} and,
 * with {@code +south}, {@code y_0 = 10000000}. The answer moves by 434 km east and 9,936 km north.
 *
 * <p><b>Every expected value in this file that involves {@code +zone} on {@code etmerc} is what
 * the code does today. It is not a reference value and PROJ does not agree with it.</b> Each
 * assertion carries the upstream number in a comment. They are here so the arm is executed at all
 * — before this file nothing in the suite combined {@code +zone} with {@code +south} on
 * {@code etmerc}, and nothing exercised {@code +south=f} anywhere — and so that whoever aligns the
 * parser with upstream has a list of failing assertions to flip rather than a silent behaviour
 * change.
 *
 * <h2>The three defects pinned here</h2>
 *
 * <ol>
 * <li>{@code +zone} is honoured on {@code etmerc}; upstream ignores it
 *     ({@link #etmercAdoptsTheUtmFrameFromZoneWhichProjDoesNot}).</li>
 * <li>{@code +south} adds the 10,000 km false northing on {@code etmerc}; upstream ignores it
 *     ({@link #etmercWithZoneAndSouthAddsTheSouthernFalseNorthing}).</li>
 * <li>{@code +south=f} and {@code +south=0} are read as <em>true</em>, because the parser tests
 *     {@code params.containsKey(south)} rather than parsing the value with {@code pj_param}'s
 *     {@code b} sigil. This one is not confined to {@code etmerc}: it puts
 *     {@code +proj=utm +zone=33 +south=f} — a definition where the parameter genuinely applies —
 *     10,000 km out of position ({@link #southEqualsFalseIsReadAsTrue}).</li>
 * </ol>
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>The three divergences go back to being invisible, and the {@code +zone}-on-{@code etmerc}
 * dispatch loses its only test.
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
     *   +proj=utm    +zone=33            312928.560890558  6210141.326748008
     *   +proj=utm    +zone=33 +south     312928.560890558 16210141.326748008
     *   +proj=utm    +zone=33 +south=f   312928.560890558  6210141.326748008   (+south=f is FALSE)
     */

    private static final double PROJ_PLAIN_ETMERC_X = 746631.146104377;
    private static final double PROJ_PLAIN_ETMERC_Y = 6273771.204197558;
    private static final double UTM33_X = 312928.560890558;
    private static final double UTM33_NORTH_Y = 6210141.326748008;
    private static final double UTM33_SOUTH_Y = 16210141.326748008;

    /**
     * The unparameterised baseline, which <em>is</em> a reference value: proj4j and PROJ 9.8.1
     * agree on {@code +proj=etmerc +ellps=GRS80}.
     *
     * <p>Everything below is a departure from this line, so if this one drifts the rest of the
     * file is measuring the wrong thing.
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
     * Defect 1. {@code +zone} on {@code etmerc} installs the UTM frame.
     *
     * <p>PROJ 9.8.1 returns {@code (746631.146104377, 6273771.204197558)} here, because
     * {@code PJ_PROJECTION(etmerc)} never reads {@code zone}. The values asserted are proj4j's
     * present behaviour, which is instead identical to {@code +proj=utm +zone=33}.
     */
    @Test
    public void etmercAdoptsTheUtmFrameFromZoneWhichProjDoesNot() {
        Projection p = projection("+proj=etmerc +zone=33 +ellps=GRS80");

        assertEquals("+zone on etmerc no longer sets the zone's central meridian; if that is "
                        + "because the parser now ignores +zone for etmerc, as PROJ does, this "
                        + "whole test should be replaced by an equality with plain etmerc",
                15.0, p.getProjectionLongitudeDegrees(), 1.0e-13);
        assertEquals("+zone on etmerc no longer sets the UTM scale factor", 0.9996,
                p.getScaleFactor(), 0.0);
        assertEquals("+zone on etmerc no longer sets the UTM false easting", 500000.0,
                p.getFalseEasting(), 0.0);
        assertEquals("+zone alone must not touch the false northing", 0.0,
                p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +zone=33 +ellps=GRS80");
        assertEquals("today's (wrong, but pinned) easting for +proj=etmerc +zone=33; PROJ 9.8.1 "
                        + "gives " + PROJ_PLAIN_ETMERC_X, UTM33_X, got.x, TIGHT_METRES);
        assertEquals("today's (wrong, but pinned) northing for +proj=etmerc +zone=33; PROJ 9.8.1 "
                        + "gives " + PROJ_PLAIN_ETMERC_Y, UTM33_NORTH_Y, got.y, TIGHT_METRES);
    }

    /**
     * Defect 2, the combination the assignment names: {@code +proj=etmerc +zone=N +south}.
     *
     * <p>PROJ 9.8.1 returns {@code (746631.146104377, 6273771.204197558)} — both tokens ignored.
     * proj4j returns the southern-hemisphere UTM answer, 10,000 km further north.
     *
     * <p>Also asserted: the two tokens commute. The parser sets the southern flag at one place and
     * reads {@code +zone} at another, later, place, so the order they appear in the proj-string
     * must not matter. If it ever does, {@code +south +zone=33} would silently lose the false
     * northing, which is the same 10,000 km error in the other direction.
     */
    @Test
    public void etmercWithZoneAndSouthAddsTheSouthernFalseNorthing() {
        Projection p = projection("+proj=etmerc +zone=33 +south +ellps=GRS80");
        assertTrue("+south did not reach the projection at all",
                p.getSouthernHemisphere());
        assertEquals("+south on a zoned etmerc no longer installs the 10,000 km false northing",
                10000000.0, p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +zone=33 +south +ellps=GRS80");
        assertEquals("today's (wrong, but pinned) easting for +proj=etmerc +zone=33 +south; "
                        + "PROJ 9.8.1 gives " + PROJ_PLAIN_ETMERC_X, UTM33_X, got.x,
                TIGHT_METRES);
        assertEquals("today's (wrong, but pinned) northing for +proj=etmerc +zone=33 +south; "
                        + "PROJ 9.8.1 gives " + PROJ_PLAIN_ETMERC_Y, UTM33_SOUTH_Y, got.y,
                TIGHT_METRES);

        ProjCoordinate reordered = forward("+proj=etmerc +south +zone=33 +ellps=GRS80");
        assertEquals("+south before +zone gives a different easting from +zone before +south, so "
                        + "the parser is order-dependent", got.x, reordered.x, 0.0);
        assertEquals("+south before +zone gives a different northing from +zone before +south: "
                        + "the false northing is being derived before the flag is set",
                got.y, reordered.y, 0.0);
    }

    /**
     * Defect 3, and the only one of the three that also strikes a definition PROJ considers valid.
     *
     * <p>{@code Proj4Parser} does {@code if (params.containsKey(Proj4Keyword.south))
     * projection.setSouthernHemisphere(true)}. Upstream reads the same token with {@code pj_param}'s
     * {@code b} sigil, for which a bare {@code +south} is true but {@code +south=f} and
     * {@code +south=0} are <b>false</b>. So {@code +proj=utm +zone=33 +south=f} means the northern
     * hemisphere to PROJ — {@code (312928.560890558, 6210141.326748008)} — and the southern
     * hemisphere to proj4j.
     *
     * <p>Pinned rather than fixed: the fix belongs in the parser, not in a test. When it lands,
     * these two assertions fail and the expected values become the PROJ ones in the comments.
     */
    @Test
    public void southEqualsFalseIsReadAsTrue() {
        for (String falsey : new String[] {"+south=f", "+south=0"}) {
            ProjCoordinate utm = forward("+proj=utm +zone=33 " + falsey + " +ellps=GRS80");
            assertEquals("+proj=utm +zone=33 " + falsey + ": easting", UTM33_X, utm.x,
                    TIGHT_METRES);
            assertEquals("+proj=utm +zone=33 " + falsey + " is read as southern hemisphere, so "
                            + "the northing is 10,000 km out; PROJ 9.8.1 gives "
                            + UTM33_NORTH_Y + " because pj_param's 'b' sigil reads f/0 as false",
                    UTM33_SOUTH_Y, utm.y, TIGHT_METRES);

            ProjCoordinate etmerc = forward("+proj=etmerc +zone=33 " + falsey + " +ellps=GRS80");
            assertEquals("+proj=etmerc +zone=33 " + falsey + " carries the same containsKey "
                            + "misreading; PROJ 9.8.1 ignores both tokens and gives "
                            + PROJ_PLAIN_ETMERC_Y,
                    UTM33_SOUTH_Y, etmerc.y, TIGHT_METRES);
        }
    }

    /**
     * {@code +south} without {@code +zone} is inert on {@code etmerc}: the flag is set, and nothing
     * reads it.
     *
     * <p>The false northing is only ever derived inside {@code setUTMZone}, so with no zone the
     * output is plain {@code etmerc}. That happens to be PROJ's answer as well — upstream ignores
     * {@code +south} here for a different reason, namely that {@code etmerc} never asks for it —
     * so this row agrees with 9.8.1 by coincidence rather than by construction. Worth recording,
     * because "+south is honoured" and "+south is ignored" produce the same number here and only
     * the presence of {@code +zone} tells them apart.
     */
    @Test
    public void southWithoutZoneIsInertOnEtmerc() {
        Projection p = projection("+proj=etmerc +south +ellps=GRS80");
        assertTrue("+south no longer reaches the projection", p.getSouthernHemisphere());
        assertEquals("the flag is set but nothing derives a false northing from it without a zone; "
                        + "if that changed, this definition now diverges from PROJ 9.8.1, which "
                        + "ignores +south on etmerc entirely",
                0.0, p.getFalseNorthing(), 0.0);

        ProjCoordinate got = forward("+proj=etmerc +south +ellps=GRS80");
        assertEquals("+proj=etmerc +south must still be plain etmerc: easting",
                PROJ_PLAIN_ETMERC_X, got.x, TIGHT_METRES);
        assertEquals("+proj=etmerc +south must still be plain etmerc: northing",
                PROJ_PLAIN_ETMERC_Y, got.y, TIGHT_METRES);
    }

    /**
     * A fourth divergence, in the same dispatch: proj4j accepts zone numbers outside 1..60.
     *
     * <p>{@code PJ_PROJECTION(utm)} rejects them with {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}
     * ("utm: Invalid value for zone"), and {@code etmerc} ignores {@code +zone} altogether, so
     * neither operator can reach a central meridian of 183&deg; upstream. proj4j computes
     * {@code (zone - 1 + 0.5) * pi/30 - pi} unguarded and produces one.
     *
     * <p>Pinned as today's behaviour. The right answers are: reject, for {@code utm}; ignore, for
     * {@code etmerc}.
     */
    @Test
    public void zoneOutsideOneToSixtyIsAcceptedAndShouldNotBe() {
        Projection past = projection("+proj=etmerc +zone=61 +ellps=GRS80");
        assertEquals("+zone=61 no longer yields a central meridian of 183 degrees; if it is now "
                        + "rejected, or ignored as PROJ does, this test has done its job and "
                        + "should be rewritten to assert that",
                183.0, past.getProjectionLongitudeDegrees(), 1.0e-12);

        Projection below = projection("+proj=etmerc +zone=0 +ellps=GRS80");
        assertEquals("+zone=0 no longer yields a central meridian of -183 degrees; same note as "
                        + "for +zone=61", -183.0, below.getProjectionLongitudeDegrees(), 1.0e-12);
    }

    /**
     * Whatever frame {@code +zone} and {@code +south} end up installing, the forward and the
     * inverse must agree about it.
     *
     * <p>This is an identity rather than a reference value, so it stays true after the parser is
     * corrected — which is the point: it is the assertion that survives the fix.
     */
    @Test
    public void theZonedSouthernFrameIsSelfConsistent() {
        String[] definitions = {
            "+proj=etmerc +zone=33 +south +ellps=GRS80",
            "+proj=etmerc +zone=33 +ellps=GRS80",
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
     * A southern-hemisphere point under the zoned {@code +south} frame, so the 10,000 km offset is
     * exercised where it is meant to be used rather than only at a northern point.
     *
     * <p>{@code (12, -56)} under {@code +proj=utm +zone=33 +south} is
     * {@code (312928.560890558, 3789858.673251992)} in PROJ 9.8.1, and proj4j's {@code etmerc}
     * with {@code +zone=33 +south} reproduces it — which is exactly the problem: it is answering
     * as {@code utm} when it was asked for {@code etmerc}, where PROJ would have given
     * {@code (746631.146104377, -6273771.204197558)}.
     */
    @Test
    public void theSouthernFalseNorthingIsExercisedInTheSouth() {
        ProjCoordinate got = forward("+proj=etmerc +zone=33 +south +ellps=GRS80", 12, -56);
        assertEquals("today's (wrong, but pinned) easting: this is +proj=utm +zone=33 +south's "
                        + "answer, not +proj=etmerc's", 312928.560890558, got.x, TIGHT_METRES);
        assertEquals("today's (wrong, but pinned) northing: 10,000,000 m minus the meridional arc, "
                        + "which is utm +south's convention and not something PROJ applies to "
                        + "etmerc", 3789858.673251992, got.y, TIGHT_METRES);
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
