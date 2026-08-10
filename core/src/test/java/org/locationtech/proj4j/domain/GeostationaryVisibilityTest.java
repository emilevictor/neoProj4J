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
package org.locationtech.proj4j.domain;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.GeostationarySatelliteProjection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code GeostationarySatelliteProjection}'s two {@code out.x = out.y = NaN; return;} sentinel
 * returns, converted to throws. Sibling of {@link RobinsonSentinelTest}, and the same argument
 * applies: {@code Projection.projectRadians}' finiteness postcondition <em>would</em> turn each of
 * these into a {@link ErrorCause#NUMERICAL_FAILURE} one frame up, but that is the wrong
 * attribution. Nothing failed numerically — the point is behind the globe, so it is
 * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, which is also what upstream reports
 * ({@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}, {@code geos.cpp:99}).
 *
 * <h2>The spherical arm is a deliberate divergence from PROJ 9.8.1</h2>
 *
 * <p>Upstream's {@code geos_s_forward} has a "check visibility" comment with no code under it
 * ({@code geos.cpp:65}). The check was removed by commit {@code dbba67bd} ("Converted geos.
 * Expanded tabs.", 2016-04-18) — a mechanical tab-expansion pass that left the ellipsoidal arm's
 * equivalent check intact in the same diff — and has not been restored in the ten years since.
 * Behind the globe {@code radius_g - Vx} stays positive, so upstream's two {@code atan} calls both
 * return finite numbers and {@code +proj=geos +R=...} answers an invisible point with a plausible,
 * wrong coordinate. This library keeps the check, because a failure must not be dressed up as a
 * coordinate that looks usable. {@link #sphericalAndEllipsoidalAgreeThatTheAntipodeIsInvisible()}
 * is the pin on that decision: if someone "restores PROJ parity" by deleting the spherical check,
 * it fails.
 *
 * <p>No gie assertion can cover the divergence in either direction — it would have to assert
 * something PROJ does not do — which is why the coverage is here instead.
 */
public class GeostationaryVisibilityTest {

    /** Real GOES geometry: a 6 378 137 m sphere and a 35 785 831 m orbit, so radius_g is ~6.61. */
    private static final String SPHERICAL = "+proj=geos +R=6378137 +h=35785831 +lon_0=0";

    private static final String ELLIPSOIDAL = "+proj=geos +ellps=GRS80 +h=35785831 +lon_0=0";

    /** 45 degrees north in radians, as the caller supplies it. */
    private static final double PHI_45 = Math.PI / 4;

    private static GeostationarySatelliteProjection geos(String definition) {
        // Typed as the concrete class on purpose: Projection.project(double, double, ...) is
        // protected, and geos widens it. These tests want the raw arm, not projectRadians, whose
        // finiteness postcondition is the very thing being made redundant.
        return (GeostationarySatelliteProjection)
                new CRSFactory().createFromParameters("geos", definition).getProjection();
    }

    /** The sub-satellite point is visible in both arms, so neither check fires on valid input. */
    @Test
    public void theSubSatellitePointProjects() {
        for (String definition : new String[] {SPHERICAL, ELLIPSOIDAL}) {
            ProjCoordinate out =
                    geos(definition).project(0.0, 0.0, new ProjCoordinate(1e300, 1e300));
            assertTrue(definition + ": " + out, out.hasValidXandYOrdinates());
            assertEquals(definition + ": the sub-satellite point is the origin", 0.0, out.x, 0.0);
            assertEquals(definition + ": the sub-satellite point is the origin", 0.0, out.y, 0.0);
        }
    }

    /**
     * The antipode of the sub-satellite point is behind the globe in both arms. The spherical half
     * of this assertion is the divergence from upstream; the ellipsoidal half is plain parity.
     */
    @Test
    public void sphericalAndEllipsoidalAgreeThatTheAntipodeIsInvisible() {
        for (String definition : new String[] {SPHERICAL, ELLIPSOIDAL}) {
            GeostationarySatelliteProjection p = geos(definition);
            for (double phi : new double[] {0.0, PHI_45, -PHI_45}) {
                try {
                    ProjCoordinate out =
                            p.project(Math.PI, phi, new ProjCoordinate(1e300, 1e300));
                    fail(definition + ": (180, " + phi + " rad) is behind the globe, got " + out);
                } catch (ProjectionException e) {
                    assertEquals(definition + " at phi=" + phi,
                            ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
                    assertTrue(definition + ": " + e.getMessage(),
                            e.getMessage().contains("behind the globe"));
                    // The orbit goes into the message as a double, so it reads 3.5785831E7 rather
                    // than 35785831. Build the expected token the same way instead of hardcoding
                    // one spelling of it.
                    assertTrue(definition + ": the message must name the orbit it refused for; "
                            + e.getMessage(),
                            e.getMessage().contains(Double.toString(35785831.0)));
                }
            }
        }
    }

    /**
     * The ellipsoidal refusal must report the latitude the <em>caller</em> passed.
     *
     * <p>{@code project_e} overwrites its {@code lpphi} parameter with the geocentric latitude
     * before the visibility check runs, so interpolating the parameter into the message would name
     * a latitude nobody supplied — off by about 0.19 degrees at mid-latitudes, which is exactly
     * the sort of number that sends a reader looking in the wrong place. The fix is a saved local,
     * and this is its pin.
     */
    @Test
    public void theEllipsoidalMessageReportsTheGeodeticLatitudeNotTheGeocentricOne() {
        GeostationarySatelliteProjection p = geos(ELLIPSOIDAL);
        try {
            p.project(Math.PI, PHI_45, new ProjCoordinate(1e300, 1e300));
            fail("(180, 45) must be refused");
        } catch (ProjectionException e) {
            String m = e.getMessage();
            assertTrue("the message must carry the caller's latitude, " + PHI_45 + "; was: " + m,
                    m.contains(Double.toString(PHI_45)));
            // The geocentric latitude for GRS80 at 45 degrees. Distinct from PHI_45 in the third
            // decimal place, so this is not a tolerance question.
            double geocentric = Math.atan((1 - 0.0066943800229) * Math.tan(PHI_45));
            assertTrue("geocentric and geodetic must actually differ for this test to mean "
                    + "anything", Math.abs(geocentric - PHI_45) > 1e-3);
            assertFalse("the message must not report the geocentric latitude, " + geocentric
                    + "; was: " + m, m.contains(Double.toString(geocentric)));
        }
    }

    /**
     * Every sentinel is gone from the forward direction: no input may make it <em>return</em> a
     * {@code NaN}. As with Robinson, the point of the exercise is that a caller's finiteness guard
     * is no longer the only thing between it and a wrong answer, so there must be nothing left for
     * that guard to catch.
     */
    @Test
    public void noInputMakesTheForwardReturnNaN() {
        for (String definition : new String[] {SPHERICAL, ELLIPSOIDAL}) {
            GeostationarySatelliteProjection p = geos(definition);
            int raised = 0;
            int returned = 0;
            for (double lam = -Math.PI; lam <= Math.PI; lam += Math.PI / 24) {
                for (double phi = -1.5; phi <= 1.5; phi += 0.1) {
                    ProjCoordinate out = new ProjCoordinate(1e300, 1e300);
                    try {
                        p.project(lam, phi, out);
                        returned++;
                        assertTrue(definition + ": returned a NaN sentinel at (" + lam + ", "
                                + phi + "): " + out, out.hasValidXandYOrdinates());
                    } catch (ProjectionException e) {
                        raised++;
                        assertEquals(definition + " at (" + lam + ", " + phi + ")",
                                ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
                    }
                }
            }
            assertTrue(definition + ": the sweep must exercise both outcomes; raised=" + raised
                    + " returned=" + returned, raised > 0 && returned > 0);
        }
    }
}
