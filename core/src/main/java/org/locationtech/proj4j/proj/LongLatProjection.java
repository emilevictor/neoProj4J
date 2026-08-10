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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.units.Units;

/**
 * A "projection" for geodetic coordinates in Decimal Degrees.
 *
 * <p>It declares no {@code project}/{@code projectInverse} of its own, and does not need to. Both
 * are already the identity in the base class: {@link Projection#project(double, double,
 * org.locationtech.proj4j.ProjCoordinate)} copies its arguments through, and
 * {@link Projection#projectInverse(double, double, org.locationtech.proj4j.ProjCoordinate)} does
 * the same behind a gate written for exactly this class — {@code !hasInverse() && !isGeographic()},
 * so a geographic CRS reaches the identity although {@code hasInverse()} is the inherited
 * {@code false}. The degree/radian handling is likewise in the base, keyed off the
 * {@link Units#DEGREES} that {@link #initialize()} assigns.
 *
 * <p>A stale {@code TODO} asking for those methods to be written, and a commented-out
 * {@code transformRadians(Point2D.Double, Point2D.Double)} beneath it, stood here until 2.0.1. The
 * commented method overrode nothing — {@code java.awt.geom.Point2D} is not imported by this class
 * and has not been part of this signature for years — which is the same trap recorded against
 * {@code LandsatProjection} in {@link Projection}'s Javadoc.
 */
public class LongLatProjection extends Projection
{

    private static final long serialVersionUID = 4367262992647079905L;

    public String toString() {
        return "LongLat";
    }

    public void initialize()
    {
        // units are always in Decimal Degrees
        unit = Units.DEGREES;
        totalScale = 1.0;
    }

    @Override public Boolean isGeographic() {
        return true;
    }
}
