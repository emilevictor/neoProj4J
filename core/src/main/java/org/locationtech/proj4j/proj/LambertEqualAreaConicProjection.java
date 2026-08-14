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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Lambert Equal Area Conic, {@code +proj=leac}.
 *
 * <p>Upstream this is {@code PJ_PROJECTION(leac)} in the <em>same file</em> as {@code aea}
 * ({@code 9.8.1:src/projections/aea.cpp:214-227}) and differs from it only in how the two standard
 * parallels are obtained:
 *
 * <pre>
 * Q-&gt;phi2 = pj_param(P-&gt;ctx, P-&gt;params, "rlat_1").f;
 * Q-&gt;phi1 = pj_param(P-&gt;ctx, P-&gt;params, "bsouth").i ? -M_HALFPI : M_HALFPI;
 * </pre>
 *
 * <p>So {@code leac} is a one-parallel operator: {@code +lat_1} becomes the <b>second</b> parallel,
 * the first is a pole chosen by {@code +south}, and <b>{@code +lat_2} is not read at all</b>.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The old class set {@code projectionLatitude1} to &plusmn;45&deg; and
 * {@code projectionLatitude2} to &plusmn;90&deg; in its constructor and then inherited
 * {@code AlbersProjection}'s reading of those two fields. Since {@code Proj4Parser} assigns
 * {@code +lat_1} to {@code projectionLatitude1} and {@code +lat_2} to {@code projectionLatitude2}
 * whenever those keywords are present, {@code +proj=leac +lat_1=0 +lat_2=2} ran <em>as
 * {@code aea}</em> with parallels (0&deg;, 2&deg;) where upstream uses (90&deg;, 0&deg;) — two
 * different cones. All 16 of the projection's {@code builtins.gie} assertions failed, the forward
 * ones by about 3 km and the inverse ones by 2.4 mm against a 0.1 mm bar.
 *
 * <p>The <em>mapping</em> from keyword to parallel is the two {@code protected} seams
 * {@link AlbersProjection#firstStandardParallel()} and
 * {@link AlbersProjection#secondStandardParallel()} rather than field mutation, because
 * {@code initialize()} runs twice — once from the constructor and once from the parser — and a
 * mapping that <em>rewrote</em> the shared fields on every call would not be idempotent across
 * those two calls.
 *
 * <p>That is about writes made <em>by</em> {@code initialize()}. It is not an argument against the
 * one write below, in the constructor: the constructor runs once, before the parser has assigned
 * anything, and {@code Proj4Parser} writes {@code projectionLatitude1} only when {@code +lat_1} is
 * actually present, so an explicit parallel still wins and a second {@code initialize()} sees the
 * same two fields the first one did.
 *
 * <h2>The second defect, and why the constructor is where it is fixed</h2>
 *
 * <p>{@code leac} sets no parallel of its own, so before this change both came from the implicit
 * {@code super()} call — {@code AlbersProjection}'s 45.5&deg; and 29.5&deg;, the conterminous-US
 * Albers pair. A bare {@code +proj=leac} therefore ran the cone (+90&deg;, 45.5&deg;) where
 * upstream runs (+90&deg;, 0&deg;). Measured at (12, 56) on {@code +ellps=WGS84}, forward, with
 * {@code proj} 9.8.1: PROJ's bare answer is {@code (553194.816514914, 7476320.691195731)} and its
 * {@code +lat_1=45.5} answer is {@code (720787.420562720, 5760346.894499558)}, 1,724 km apart. Our
 * bare answer was the second of those and is now the first, and our {@code +lat_1=0} agrees with
 * our bare answer bit for bit.
 *
 * <p>This is a <b>default</b> defect and not a presence one, so it does not belong in
 * {@code Proj4Parser} beside the {@code sconics} check. Upstream reads {@code rlat_1} with no
 * {@code 't'} presence test, so an absent {@code +lat_1} and an explicit {@code +lat_1=0} are the
 * same input upstream and must stay the same input here. Zeroing the two fields in the constructor
 * is exactly that equivalence, and for <em>that</em> input it cannot degenerate the cone: the first
 * parallel is a pole and the second is 0, so {@code |phi1 + phi2| = pi/2} and
 * {@code AlbersProjection}'s {@code |phi1 + phi2| < 1e-10} rejection cannot fire.
 *
 * <p>That is a statement about the bare form only, not about the operator. An explicit parallel at
 * the <em>opposite</em> pole does reach the rejection: {@code +proj=leac +lat_1=-90 +ellps=WGS84}
 * throws it, and so does {@code +proj=leac +south +lat_1=90}. Both are correct — {@code proj} 9.8.1
 * refuses each with {@code Error 1027 ... Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should
 * be > 0} — so the rejection being reachable there is parity, not a defect.
 */
public class LambertEqualAreaConicProjection extends AlbersProjection {

    private static final long serialVersionUID = -4045880544360988887L;

    /** Upstream's {@code +south} flag: which pole is the first standard parallel. */
    private boolean south;

    public LambertEqualAreaConicProjection() {
        this( false );
    }

    public LambertEqualAreaConicProjection( boolean south ) {
        this.south = south;
        minLatitude = ProjectionMath.toRad(0);
        maxLatitude = ProjectionMath.toRad(90);
        // Undo AlbersProjection's conterminous-US pair. leac reads one parallel and reads it into
        // the SECOND slot, so the absent value upstream is 0 for both fields; see the class
        // comment. projectionLatitude2 is zeroed as well although secondStandardParallel() does
        // not read it, so that getProjectionLatitude2() cannot report a parallel this operator
        // never uses.
        projectionLatitude1 = 0.0;
        projectionLatitude2 = 0.0;
        initialize();
    }

    /**
     * @return {@code -pi/2} under {@code +south}, otherwise {@code +pi/2}. Upstream reads
     *         {@code bsouth}, a boolean parameter, so it is the pole and not {@code +lat_1}.
     */
    @Override
    protected double firstStandardParallel() {
        return south ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
    }

    /**
     * @return {@code +lat_1}. Deliberately not {@code +lat_2}: {@code leac} never reads
     *         {@code +lat_2}, so a definition supplying it is silently ignored upstream and is
     *         silently ignored here.
     */
    @Override
    protected double secondStandardParallel() {
        return projectionLatitude1;
    }

    /**
     * @param south whether the first standard parallel is the south pole
     * @since 2.0.0
     */
    public void setSouth(boolean south) {
        this.south = south;
    }

    /**
     * @return whether the first standard parallel is the south pole
     * @since 2.0.0
     */
    public boolean isSouth() {
        return south;
    }

    /**
     * {@code +south}, under the name {@code Proj4Parser} actually calls. Without this override the
     * parameter reached {@link Projection#setSouthernHemisphere(boolean)}, which refuses on the
     * base class, so {@code +proj=leac +south} threw
     * {@link org.locationtech.proj4j.UnsupportedParameterException} rather
     * than selecting the south pole. That was an over-refusal: upstream genuinely reads the key
     * here, as {@code pj_param(P->ctx, P->params, "bsouth")} at
     * {@code 9.8.1:src/projections/aea.cpp:223} — {@code leac} is a second {@code PJ_PROJECTION}
     * in {@code aea.cpp}, not a file of its own. The name mismatch was the whole defect:
     * {@link #setSouth(boolean)} existed and worked, and nothing in the library ever called it.
     *
     * @param isSouth whether the first standard parallel is the south pole
     */
    @Override
    public void setSouthernHemisphere(boolean isSouth) {
        setSouth(isSouth);
    }

    /**
     * @return whether the first standard parallel is the south pole
     */
    @Override
    public boolean getSouthernHemisphere() {
        return isSouth();
    }

    public String toString() {
        return "Lambert Equal Area Conic";
    }

}
