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
 * How an operation's declared extent must relate to the area of interest for the operation to be a
 * candidate at all.
 *
 * <p>PROJ's {@code CoordinateOperationContext::SpatialCriterion},
 * {@code 9.8.1:include/proj/coordinateoperation.hpp}. <b>Two values, and there is no "off"</b>:
 * upstream has no {@code NONE}, and neither does this, because the way to switch the spatial test
 * off is to have no area of interest for it to test against &mdash; see
 * {@link SourceTargetCRSExtentUse#NONE}.
 *
 * <h2>"PROJ's default" is two different values and you have to say which one you mean</h2>
 *
 * <p>{@code CoordinateOperationContext}'s own field initialiser is {@link #STRICT_CONTAINMENT}, and
 * that is the value quoted whenever someone reads the header. It is <b>not</b> the value PROJ
 * transforms coordinates with. {@code proj_create_crs_to_crs_from_pj},
 * {@code 9.8.1:src/crs_to_crs.cpp:568-570}, sets {@link #PARTIAL_INTERSECTION} unconditionally
 * before it builds anything, with no flag and no way to ask for the other &mdash; and every
 * {@code cs2cs} invocation, every {@code proj_create_crs_to_crs}, and the two internal
 * geographic-CRS helpers at {@code :229} and {@code :296} go through that. The header default
 * survives in exactly one visible place: {@code projinfo} with no {@code --spatial-test}.
 *
 * <p>So {@code projinfo -s EPSG:4267 -t EPSG:4269 --summary} reports <b>one</b> candidate, and
 * {@code cs2cs EPSG:4267 EPSG:4269} on the same pair applies a real shift, and both are PROJ, and
 * they disagree because they are asking under different criteria. {@code projinfo} knows it:
 * having found one candidate it re-runs under {@link #PARTIAL_INTERSECTION} purely to print
 * <em>"Note: using '--spatial-test intersects' would bring more results (10)"</em>
 * ({@code 9.8.1:src/apps/projinfo_lib.cpp:1117-1132}).
 *
 * <p>{@link Proj#createCrsToCrs} is the analogue of {@code proj_create_crs_to_crs}, not of
 * {@code projinfo}, so {@link #PARTIAL_INTERSECTION} is this library's default. Defaulting to the
 * header value instead would have reduced {@code EPSG:4267} to {@code EPSG:4269} to a lone
 * ballpark candidate, which under {@link BallparkPolicy#REJECT} is a refusal &mdash; a refusal on
 * the pair this library exists to have fixed, and one PROJ does not make.
 *
 * @see ProjContext.Builder#spatialCriterion(SpatialCriterion)
 * @since 2.2.0
 */
public enum SpatialCriterion {

    /**
     * The operation's extent must wholly contain the area of interest. <b>{@code projinfo}'s
     * default, and {@code CoordinateOperationContext}'s; not this library's</b> &mdash; see the
     * class javadoc for why those are different questions.
     *
     * <p>This is stricter than it sounds. For {@code EPSG:4267} to {@code EPSG:4269} the area of
     * interest is the smaller of NAD27's and NAD83's own extents &mdash; measured on the 9.8.1
     * database, NAD83's {@code EPSG:1350}, which <em>crosses the antimeridian</em> at
     * {@code west = 167.65, east = -40.73} &mdash; and <em>no</em> published operation contains the
     * whole of it: the Canadian ones stop at the border, the CONUS ones stop at the other side of
     * it, and the two that span both are still narrower in latitude. Only the synthesised ballpark
     * survives, because it declares the whole world.
     *
     * <p>Set this when the question is "which operations are valid everywhere both CRSs are", which
     * is a reasonable thing to ask and is what {@code projinfo} answers. Do not set it expecting
     * {@code cs2cs}'s answer.
     */
    STRICT_CONTAINMENT,

    /**
     * The operation's extent need only intersect the area of interest. <b>This library's default,
     * and what every PROJ transformation path uses.</b>
     *
     * <p>{@code projinfo --spatial-test intersects}, and what
     * {@code proj_create_crs_to_crs_from_pj} sets. Ten candidates for the NAD27 pair against
     * {@link #STRICT_CONTAINMENT}'s one.
     *
     * <p>This is a pair-level filter and it does not, by itself, choose between the ten. That is
     * done per coordinate, in {@code pj_get_suggested_operation}, where the test is against the
     * point rather than against an area &mdash; see
     * {@link CrsOperation#transform(org.locationtech.proj4j.ProjCoordinate,
     * org.locationtech.proj4j.ProjCoordinate)}. The two steps are why PROJ can offer ten operations
     * for North America and still apply the CONUS one in Nebraska.
     *
     * <p>Note the intersection test is strict at the edges: two boxes sharing only a boundary do not
     * intersect. That is upstream's {@code max(W, oW) < min(E, oE)}, kept.
     */
    PARTIAL_INTERSECTION
}
