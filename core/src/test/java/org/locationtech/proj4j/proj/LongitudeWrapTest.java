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
 *******************************************************************************/
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +lon_wrap}: re-centre the <b>forward</b> output's longitude range.
 *
 * <h2>The gap</h2>
 *
 * <p>{@code Proj4Keyword} carried no {@code lon_wrap} constant at all — only a javadoc mention
 * on {@code +over} — and {@code isSupported()} is a <em>closed allow-list</em> that
 * {@code GieProjArgs.toProj4Args()} filters against <em>before</em> {@code setParameters} runs.
 * So the parameter was dropped on the floor and {@code +proj=longlat +lon_wrap=180} answered
 * with the unwrapped longitude. That is the shape of failure this project treats as worse than a
 * refusal: a plausible number, silently.
 *
 * <p>It is not a corpus-only path. Measured on an installed 9.8.1:
 *
 * <pre>
 * $ projinfo -s "+proj=longlat +datum=WGS84 +type=crs" \
 *            -t "+proj=longlat +datum=WGS84 +lon_wrap=180 +type=crs" -o PROJ
 * +proj=pipeline
 *   +step +proj=unitconvert +xy_in=deg +xy_out=rad
 *   +step +proj=longlat +datum=WGS84 +lon_wrap=180
 *   +step +proj=unitconvert +xy_in=rad +xy_out=deg
 * </pre>
 *
 * <p>and running {@code -1 10} through it gives {@code 359 10}.
 *
 * <h2>The two things that were mismeasured on the first pass</h2>
 *
 * <p><b>The {@code r} sigil.</b> {@code init.cpp:613} reads {@code "rlon_wrap"}, so the value is
 * an <em>angle</em> and {@code +lon_wrap=180} stores &pi;. That is what makes upstream's guard,
 * {@code !(fabs(center) < 10 * M_TWOPI)}, a bound of 10&nbsp;&times;&nbsp;2&pi;
 * <em>radians</em> &asymp; 3600&nbsp;<em>degrees</em> — not 62.8 degrees, which is the reading
 * that falls out of forgetting the sigil. Both boundaries are pinned below against measured
 * 9.8.1 behaviour.
 *
 * <p><b>Forward only.</b> {@code fwd_finalize} applies it in {@code case PJ_IO_UNITS_RADIANS}
 * and in no other case ({@code fwd.cpp:162-167}); {@code inv_finalize}
 * ({@code inv.cpp:102-130}) has no {@code lon_wrap} line at all. So an inverse-longlat and an
 * inverse-merc leave the longitude unwrapped. That asymmetry is upstream's, and reproducing it
 * is the parity — {@link #theInverseDoesNotWrapBecauseUpstreamsInverseDoesNot()} pins it so it
 * cannot be "fixed" into a divergence.
 */
public class LongitudeWrapTest {

    private final CRSFactory crsFactory = new CRSFactory();

    private Projection projection(String definition) {
        return crsFactory.createFromParameters("test", definition).getProjection();
    }

    /** Forward-projects a longitude in degrees and answers the output longitude in degrees. */
    private double forwardLongitude(String definition, double lonDegrees, double latDegrees) {
        ProjCoordinate out = new ProjCoordinate();
        projection(definition).project(new ProjCoordinate(lonDegrees, latDegrees), out);
        return out.x;
    }

    // ------------------------------------------------------------------ the parser

    @Test
    public void theParserStoresTheCentreInRadiansBecauseTheSigilIsR() {
        // +lon_wrap=180 is pi, not 180. Reading it with parseDouble would have stored 180
        // radians, i.e. 28.6 turns, which the guard below would then have rejected outright.
        Projection p = projection("+proj=longlat +ellps=WGS84 +lon_wrap=180");
        assertTrue(p.isLongitudeWrapSet());
        assertEquals(Math.PI, p.getLongitudeWrapCenter(), 1e-15);

        // 0 is a real request, not "absent": it re-centres on Greenwich. Hence the separate
        // presence flag rather than a sentinel value.
        Projection zero = projection("+proj=longlat +ellps=WGS84 +lon_wrap=0");
        assertTrue(zero.isLongitudeWrapSet());
        assertEquals(0.0, zero.getLongitudeWrapCenter(), 0.0);

        // Absent means absent, and the centre reads 0 without the flag being set.
        Projection none = projection("+proj=longlat +ellps=WGS84");
        assertFalse(none.isLongitudeWrapSet());
        assertEquals(0.0, none.getLongitudeWrapCenter(), 0.0);

        // The 'r' suffix means the value is ALREADY radians, so this is pi twice over and the
        // two spellings must land on the same stored number.
        assertEquals(
                projection("+proj=longlat +ellps=WGS84 +lon_wrap=180").getLongitudeWrapCenter(),
                projection("+proj=longlat +ellps=WGS84 +lon_wrap=3.141592653589793r")
                        .getLongitudeWrapCenter(),
                1e-15);

        // And DMS, since the sigil routes through dmstor's grammar: 179d60 is 180 degrees.
        assertEquals(Math.PI,
                projection("+proj=longlat +ellps=WGS84 +lon_wrap=179d60").getLongitudeWrapCenter(),
                1e-15);
    }

    @Test
    public void theGuardBoundaryIsTenTimesTwoPiRADIANSWhichIsAboutThirtySixHundredDegrees() {
        // MEASURED on 9.8.1, and this is the whole point of the r sigil:
        //   +lon_wrap=3599 -> accepted
        //   +lon_wrap=3600 -> Error 1027, "longlat: Invalid value for lon_wrap"
        //   +lon_wrap=3601 -> Error 1027
        // 3600 degrees is exactly 10 * 2*pi radians, and the comparison is strict, so the
        // boundary value itself is refused.
        assertEquals(3599.0 * ProjectionMath.DTR,
                projection("+proj=longlat +ellps=WGS84 +lon_wrap=3599").getLongitudeWrapCenter(),
                1e-12);
        assertRefused("+proj=longlat +ellps=WGS84 +lon_wrap=3600");
        assertRefused("+proj=longlat +ellps=WGS84 +lon_wrap=3601");

        // The 62.8-DEGREE reading of the same guard would have refused these two. 9.8.1
        // accepts both, measured.
        assertTrue(projection("+proj=longlat +ellps=WGS84 +lon_wrap=62").isLongitudeWrapSet());
        assertTrue(projection("+proj=longlat +ellps=WGS84 +lon_wrap=63").isLongitudeWrapSet());

        // The sign is not what is bounded; the magnitude is.
        assertRefused("+proj=longlat +ellps=WGS84 +lon_wrap=-3600");
        assertTrue(projection("+proj=longlat +ellps=WGS84 +lon_wrap=-3599").isLongitudeWrapSet());

        // NaN. Upstream's own comment says the test is written as !(fabs(x) < limit) rather
        // than as >= precisely so that a NaN centre is an error; !(NaN < limit) is true, so
        // the same inversion here refuses it with no separate isNaN branch. A guard written
        // the other way round would have stored NaN and produced NaN longitudes.
        assertRefused("+proj=longlat +ellps=WGS84 +lon_wrap=NaN");
    }

    private void assertRefused(String definition) {
        try {
            projection(definition);
            fail("expected " + definition + " to be refused");
        } catch (InvalidValueException expected) {
            assertTrue("the message must name the parameter: " + expected.getMessage(),
                    expected.getMessage().contains("lon_wrap"));
        }
    }

    // -------------------------------------------------------------- the arithmetic

    @Test
    public void theForwardWrapsToTheCentredRangeExactlyAsUpstreamDoes() {
        String def = "+proj=longlat +ellps=WGS84 +lon_wrap=180";

        // EVERY value below is measured on an installed 9.8.1, latitude 10, through
        //   cs2cs +proj=longlat +datum=WGS84 +type=crs +to <def> +type=crs
        // and not computed from the formula this test is checking.
        assertEquals(359.0, forwardLongitude(def, -1.0, 10.0), 1e-9);   // the corpus row
        assertEquals(179.0, forwardLongitude(def, -181.0, 10.0), 1e-9);
        assertEquals(181.0, forwardLongitude(def, 181.0, 10.0), 1e-9);  // already in range
        assertEquals(0.0, forwardLongitude(def, 0.0, 10.0), 1e-9);
        assertEquals(359.0, forwardLongitude(def, 359.0, 10.0), 1e-9);
        assertEquals(1.0, forwardLongitude(def, 361.0, 10.0), 1e-9);

        // BOTH antimeridian spellings answer +180, which is the tell that this is adjlon's
        // 1e-12 overshoot window and not a naive modulo: -180 is left alone by the input-side
        // adjlon and then centred onto +180.
        assertEquals(180.0, forwardLongitude(def, 180.0, 10.0), 1e-9);
        assertEquals(180.0, forwardLongitude(def, -180.0, 10.0), 1e-9);

        // Latitude is untouched. The wrap is on lam alone.
        ProjCoordinate out = new ProjCoordinate();
        projection(def).project(new ProjCoordinate(-1.0, 10.0), out);
        assertEquals(10.0, out.y, 1e-9);
    }

    @Test
    public void aCentreOfZeroIsTheDefaultRangeAndAnOffCentreOneShiftsIt() {
        // Centre 0 gives (-180, 180], so nothing inside it moves and 200 comes back as -160.
        String greenwich = "+proj=longlat +ellps=WGS84 +lon_wrap=0";
        assertEquals(-160.0, forwardLongitude(greenwich, 200.0, 0.0), 1e-9);
        assertEquals(-1.0, forwardLongitude(greenwich, -1.0, 0.0), 1e-9);

        // A centre nobody would choose, to show the centre really is the centre rather than a
        // hard-coded 180: range (-10, 350].
        String ninety = "+proj=longlat +ellps=WGS84 +lon_wrap=170";
        assertEquals(349.0, forwardLongitude(ninety, -11.0, 0.0), 1e-9);
        assertEquals(-9.0, forwardLongitude(ninety, -9.0, 0.0), 1e-9);
    }

    @Test
    public void overDoesNotDisableTheWrapAndTheTwoComposeInEitherOrder() {
        // +over suppresses fwd_prepare's INPUT-side adjlon and inv_finalize's; fwd_finalize's
        // wrap sits outside both guards, so +over +lon_wrap=180 still wraps. Getting this
        // wrong by guarding the wrap on !over would have left -1 at -1.
        assertEquals(359.0,
                forwardLongitude("+proj=longlat +ellps=WGS84 +over +lon_wrap=180", -1.0, 10.0),
                1e-9);

        // And it composes with an out-of-range input the same way with and without +over,
        // which is the measurable form of "adjlon is idempotent, so the order of the input
        // reduction against the wrap does not matter". PROJ's fwd_prepare adjlons the input
        // whenever !over; projectRadians does so only when it is also a pipeline step. The two
        // orders agree because the wrap composition is mod 2*pi.
        //
        // 400 rather than 721 because LongLatProjection rejects any longitude beyond +/-10
        // RADIANS - "LongLat: invalid longitude 12.58 rad (721.0 deg)" - which is an input
        // guard of its own and nothing to do with lon_wrap. 400 degrees is 6.98 rad, inside it.
        assertEquals(
                forwardLongitude("+proj=longlat +ellps=WGS84 +lon_wrap=180", 400.0, 10.0),
                forwardLongitude("+proj=longlat +ellps=WGS84 +over +lon_wrap=180", 400.0, 10.0),
                1e-9);
        assertEquals("and both read 40, since 400 - 360 is already inside (0, 360]",
                40.0, forwardLongitude("+proj=longlat +ellps=WGS84 +lon_wrap=180", 400.0, 10.0),
                1e-9);
    }

    @Test
    public void theInverseDoesNotWrapBecauseUpstreamsInverseDoesNot() {
        // NOT a gap. inv_finalize (inv.cpp:102-130) contains no lon_wrap, so an inverse
        // returns the plain reduced longitude and the centre is ignored. This is pinned so
        // that "the inverse doesn't wrap" is not later read as a defect and fixed into a
        // divergence from 9.8.1.
        Projection p = projection("+proj=longlat +ellps=WGS84 +lon_wrap=180");
        ProjCoordinate out = new ProjCoordinate();
        p.inverseProject(new ProjCoordinate(-1.0, 10.0), out);
        assertEquals(-1.0, out.x, 1e-9);

        // The flag is still set; it is simply not consulted on this path.
        assertTrue(p.isLongitudeWrapSet());
    }

    // ------------------------------------------------------------------ non-vacuity

    @Test
    public void withoutTheParameterNothingWrapsAtAll() {
        // The control. If the definition without +lon_wrap also answered 359, every assertion
        // above would be measuring the input rather than the wrap.
        assertEquals(-1.0, forwardLongitude("+proj=longlat +ellps=WGS84", -1.0, 10.0), 1e-9);
        assertEquals(0.0, forwardLongitude("+proj=longlat +ellps=WGS84", 0.0, 10.0), 1e-9);
    }

    @Test
    public void theKeywordIsRegisteredSoTheStrictParserDoesNotRefuseIt() {
        // The other half of the gap, and the half that is invisible from a coordinate: the
        // keyword has to be in Proj4Keyword's closed allow-list or callers that filter
        // against it - GieProjArgs.toProj4Args among them - drop the token before the parser
        // ever sees it, and the parser read added above would parse nothing.
        assertTrue(org.locationtech.proj4j.parser.Proj4Keyword.isSupported("lon_wrap"));
        // Non-vacuity for that assertion: the allow-list really does reject things.
        assertFalse(org.locationtech.proj4j.parser.Proj4Keyword.isSupported("lon_wrapp"));
    }
}
