/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

/**
 * How the two CRSs' own extents are used to synthesise an area of interest when the caller has not
 * supplied one.
 *
 * <p>PROJ's {@code CoordinateOperationContext::SourceTargetCRSExtentUse},
 * {@code 9.8.1:include/proj/coordinateoperation.hpp:1987-1998}, with upstream's four values and its
 * default.
 *
 * <h2>The filter is on by default and nobody asked for it</h2>
 *
 * <p>This is the part worth stating plainly, because it surprises people: with no
 * {@link ProjContext.Builder#areaOfInterest(AreaOfUse)} call at all, operation selection still
 * applies a spatial filter. The area it filters against is built here, out of metadata the CRSs
 * carry themselves. That is exactly what PROJ does, and the alternative &mdash; filtering only when
 * asked &mdash; would put this library's default candidate list somewhere upstream's never goes.
 *
 * @see ProjContext.Builder#sourceTargetCrsExtentUse(SourceTargetCRSExtentUse)
 * @since 2.2.0
 */
public enum SourceTargetCRSExtentUse {

    /**
     * Ignore both CRSs' extents.
     *
     * <p>With no caller-supplied area of interest either, this switches the spatial filter off
     * altogether: {@code computeAreaOfInterest} leaves the area null and {@code filterOut} skips
     * every extent test. That is the only way to get an unfiltered candidate list, and it is why
     * neither {@link SpatialCriterion} value means "off".
     */
    NONE,

    /**
     * Test the operation's extent against <b>both</b> CRS extents separately, rather than against
     * one synthesised area.
     *
     * <p>Distinct from {@link #INTERSECTION} in a way that matters at the edges: an operation whose
     * extent contains the intersection of the two CRS extents need not contain either of them, so
     * this is the stricter of the two. It is reached only when there is no area of interest, which
     * is why {@code filterOut} carries it as a separate branch rather than as another way of
     * computing one.
     */
    BOTH,

    /** Test against the intersection of the two CRS extents. */
    INTERSECTION,

    /**
     * Test against whichever of the two CRS extents is smaller by
     * {@code Extents.pseudoArea}. <b>PROJ's default, and this library's.</b>
     *
     * <p>Smaller, not narrower: the measure is PROJ's solid-angle proxy, so a wide Nordic extent can
     * be "smaller" than a narrower equatorial one. When only one CRS declares an extent that one is
     * used; when neither does, the area of interest stays null and the spatial filter does not run.
     */
    SMALLEST
}
