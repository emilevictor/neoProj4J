/*
 * Copyright 2026 the Proj4J contributors.
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
package org.locationtech.proj4j.geoapi;

import org.junit.Test;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.proj.LambertConformalConicProjection;
import org.locationtech.proj4j.proj.MercatorProjection;

import static org.junit.Assert.assertEquals;

/**
 * {@link ParameterAccessor#reset} must leave a projection in the state it would have had if no
 * parameter had ever been set.
 */
public class ParameterAccessorTest {

    /**
     * A reset must forget {@code +lat_ts}, not record it as given with a value of zero.
     *
     * <p>{@code true_scale_latitude}'s setter is {@code Projection.setTrueScaleLatitude}, and that
     * setter records presence, because {@code merc.cpp:47-68} tests presence rather than value and
     * a given {@code +lat_ts=0} therefore discards {@code +k}. Resetting through that setter would
     * hand the projection a parameter it never carried, and the consequence is visible the moment
     * anything calls {@code initialize()}: a {@code merc} whose {@code +k} is silently replaced by
     * 1.
     *
     * <p>Both halves are asserted, because only the pair says the flag is being carried rather than
     * ignored: a merc that really was given {@code +lat_ts=0} must come out on 1, and the same merc
     * after a reset must come out on whatever {@code +k} it is then given.
     */
    @Test
    public void resetForgetsTrueScaleLatitude() {
        MercatorProjection p = new MercatorProjection();
        p.setEllipsoid(Ellipsoid.GRS80);

        p.setTrueScaleLatitudeDegrees(0);
        p.setScaleFactor(0.997);
        p.initialize();
        assertEquals("a merc that was given +lat_ts=0 discards +k", 1.0, p.getScaleFactor(), 0.0);

        ParameterAccessor.reset(p);
        p.setScaleFactor(0.997);
        p.initialize();
        assertEquals("after a reset there is no +lat_ts, so +k stands",
                0.997, p.getScaleFactor(), 0.0);
        assertEquals("and the latitude itself is back to its default",
                0.0, p.getTrueScaleLatitude(), 0.0);
    }

    /**
     * A reset must forget {@code +lat_0} and {@code +lat_2} for the same reason.
     *
     * <p>{@code lcc.cpp:88-95} tests whether the tokens were supplied, so the setters record it. A
     * reset that went through those setters with a default of zero would leave the projection
     * claiming {@code +lat_2=0 +lat_0=0} — a secant cone through the equator with its origin there —
     * where a reset lcc should have no standard parallels at all and should fall its second parallel
     * and its origin back to {@code lat_1}.
     *
     * <p>Both halves are asserted, because only the pair says the flags are carried rather than
     * ignored. The measured PROJ 9.8.1 discriminator, at 10E 40N on GRS80: {@code +lat_1=45} alone
     * puts the origin on the 45th parallel, and {@code +lat_1=45 +lat_2=0 +lat_0=0} puts it on the
     * equator with a different cone constant.
     */
    @Test
    public void resetForgetsLatitudeOfOriginAndSecondParallel() {
        LambertConformalConicProjection p = new LambertConformalConicProjection();
        p.setEllipsoid(Ellipsoid.GRS80);

        p.setProjectionLatitude1(Math.toRadians(45));
        p.setProjectionLatitude2(0);
        p.setProjectionLatitude(0);
        p.initialize();
        assertEquals("a lat_2 given as 0 stays 0", 0.0, p.getProjectionLatitude2(), 0.0);
        assertEquals("and a lat_0 given as 0 stays 0", 0.0, p.getProjectionLatitude(), 0.0);

        ParameterAccessor.reset(p);
        p.setProjectionLatitude1(Math.toRadians(45));
        p.initialize();
        assertEquals("after a reset there is no +lat_2, so it falls back to lat_1",
                Math.toRadians(45), p.getProjectionLatitude2(), 0.0);
        assertEquals("and no +lat_0, so that falls back to lat_1 as well",
                Math.toRadians(45), p.getProjectionLatitude(), 0.0);
    }
}
