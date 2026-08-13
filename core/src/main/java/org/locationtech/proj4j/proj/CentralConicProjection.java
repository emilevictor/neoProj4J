/*******************************************************************************
 * Copyright 2026
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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;

/**
 * Central Conic, {@code +proj=ccon} &mdash; a port of
 * {@code 9.8.1:src/projections/ccon.cpp}. Spherical only; both directions closed form and
 * four lines each.
 *
 * <p>A cone tangent at {@code +lat_1}, on which distances along the <em>central</em> meridian
 * are true and the parallels are circles of radius {@code cot(lat_1) - tan(phi - lat_1)}. The
 * projection has no standard parallel other than {@code lat_1} and no ellipsoidal form.
 *
 * <pre>
 *   r = cot(phi_1) - tan(phi - phi_1)
 *   x = r sin(lam sin phi_1)
 *   y = cot(phi_1) - r cos(lam sin phi_1)
 * </pre>
 *
 * <h2>Two things upstream does that look like mistakes</h2>
 *
 * <p><b>{@code Q-&gt;en} is computed and never read.</b> {@code PJ_PROJECTION(ccon)} calls
 * {@code pj_enfn(P-&gt;n)} and stores the meridian-arc coefficients, and the destructor frees
 * them, but neither the forward nor the inverse touches them &mdash; the projection is
 * spherical. Nothing is ported for it.
 *
 * <p><b>The inverse's radius is {@code hypot(x, y) - cot(phi_1)}, not the other way round,</b>
 * and it is fed to {@code atan} without a sign correction. It is exactly the algebraic inverse
 * of the forward for {@code phi_1 &gt; 0}; for {@code phi_1 &lt; 0} the cone opens the other
 * way and the pair is no longer mutually inverse everywhere. Reproduced verbatim; the corpus's
 * only {@code ccon} block is at {@code +lat_1=52}.
 *
 * <h2>{@code +lat_1} is mandatory</h2>
 *
 * <p>{@code |lat_1| &lt; 1e-10} is rejected with
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} &mdash; {@code cot} would be infinite. Since
 * {@code pj_param}'s absent-key value is 0, that check also makes a bare {@code +proj=ccon} an
 * error, which is why the field's initialiser is 0 rather than any plausible parallel.
 *
 * <p>{@code initialize()} runs twice (constructor and parser). {@code phi1} is only ever
 * written by {@link Projection#setProjectionLatitude1(double)}; everything else here is
 * derived from it on each call.
 *
 * <h2>Latitudes below {@code lat_1 - 90} fold onto the near side</h2>
 *
 * <p>This is a real limitation of the projection and it is <b>not</b> guarded, here or
 * upstream. {@code r = cot(phi_1) - tan(phi - phi_1)} changes sign as {@code phi} crosses
 * {@code phi_1 - 90}, because that is where {@code tan}'s argument passes through
 * {@code -90} degrees. A negative {@code r} plots the point on the far side of the cone's
 * apex. The inverse then recovers the radius with {@code hypot}, which is unsigned and
 * cannot tell the two sides apart, so it hands back a point on the near side instead: the
 * latitude is reflected and the longitude is offset by {@code 180 / sin(phi_1)} degrees.
 * Every latitude the inverse can produce lies in the open interval
 * {@code (phi_1 - 90, phi_1 + 90)}, whatever was fed to the forward.
 *
 * <p>Measured with {@code +proj=ccon +lat_1=45 +ellps=WGS84}, forward then inverse, at
 * longitude 20:
 *
 * <pre>
 *   lat  -45  -&gt;  ( 20.000000000, -45.000000000)   last latitude that survives
 *   lat  -46  -&gt;  (125.441558773, -43.963834817)
 *   lat  -60  -&gt;  (125.441558773, -15.000000000)
 *   lat  -75  -&gt;  (125.441558773,  60.000000000)
 *   lat  -90  -&gt;  (  0.000000000,  90.000000000)   the south pole comes back as the north
 * </pre>
 *
 * <p>The constant longitude offset is {@code 105.4415588 = 360 - 180 / sin(45)}. The last
 * row is not a separate case: {@code r} is zero at {@code phi = 90} <em>and</em> at
 * {@code phi = -90} — those are the two solutions of
 * {@code tan(phi - phi_1) = cot(phi_1)} — so both poles land on the single projected point
 * that is the apex, and the inverse can only answer with one of them.
 *
 * <p><b>PROJ 9.8.1 does exactly the same thing and never refuses.</b> The forward at
 * {@code (20, -75)} is {@code -1140797.744985023281, 10905748.430954094976} from
 * {@code proj} and {@code -1140797.744985023, 1.0905748430954095E7} from this class —
 * equal to the last digit either prints. Under this project's parity doctrine the port is
 * therefore faithful and inventing a domain check here would make this library disagree
 * with the oracle it tracks. {@code RegistryRoundTripAuditTest} pins the resulting
 * 180-degree round-trip error rather than treating it as a defect.
 *
 * <p>There is no domain guard anywhere in the 104 lines of {@code ccon.cpp}. The file's
 * only rejection is the {@code |lat_1| < EPS10} check at {@code ccon.cpp:87-90}, which is
 * about the cone, not about the data. Upstream's own documentation calls the defined area
 * "Global, but best used near the standard parallel"
 * ({@code docs/source/operations/projections/ccon.rst:17}), which is true only for the
 * forward.
 *
 * <p>One inherited claim about this file is <b>wrong</b> and is recorded here so it is not
 * repeated: there is no "90 degrees from the mean parallel" rule in {@code ccon.cpp}.
 * That rule exists, but it belongs to the <em>other</em> conics — {@code sconics.cpp:178-183}
 * rejects {@code |lat_0 - 0.5 * (lat_1 + lat_2)| >= 90} for {@code pconic} — and
 * {@code ccon} has no analogue of it because {@code ccon} never reads {@code +lat_0} at all
 * (see {@code NewOperatorContractTest.cconIgnoresLat0}).
 *
 * <p><b>No upstream fix is in flight.</b> {@code src/projections/ccon.cpp} is byte-identical
 * between tag {@code 9.8.1} and current {@code master}, so upgrading PROJ will not change
 * any of the above.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/ccon.cpp">9.8.1
 *      ccon.cpp</a>
 */
public class CentralConicProjection extends ConicProjection {

    private static final long serialVersionUID = -8732698340965281734L;

    private static final double EPS10 = 1e-10;

    private double sinphi1;
    private double ctgphi1;

    /**
     * Port of {@code PJ_PROJECTION(ccon)} ({@code ccon.cpp:76-101}).
     *
     * @throws InvalidValueException if {@code |lat_1| < 1e-10}, including the absent case
     */
    @Override
    public void initialize() {
        super.initialize();
        if (Math.abs(projectionLatitude1) < EPS10) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=ccon requires +lat_1 and |lat_1| must be > 0; got "
                            + projectionLatitude1 + " rad. The cone's cot(lat_1) is infinite at "
                            + "the equator (ccon.cpp:86-89). Note pj_param answers 0 for an "
                            + "absent +lat_1, so a bare +proj=ccon is this same error.");
        }
        sinphi1 = FastStrictTrig.sin(projectionLatitude1);
        ctgphi1 = FastStrictTrig.cos(projectionLatitude1) / sinphi1;
    }

    /** {@code ccon_forward}, {@code ccon.cpp:42-52}. */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
        final double r = ctgphi1 - FastStrictTrig.tan(phi - projectionLatitude1);
        xy.x = r * FastStrictTrig.sin(lam * sinphi1);
        xy.y = ctgphi1 - r * FastStrictTrig.cos(lam * sinphi1);
        return xy;
    }

    /**
     * {@code ccon_inverse}, {@code ccon.cpp:54-63}.
     *
     * <p>{@code hypot} is unsigned, so this cannot recover a negative forward radius; that is
     * the whole of the fold described in the class comment. Deliberately unguarded, because
     * PROJ 9.8.1 is unguarded here too.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
        final double yy = ctgphi1 - y;
        lp.y = projectionLatitude1 - StrictMath.atan(StrictMath.hypot(x, yy) - ctgphi1);
        lp.x = StrictMath.atan2(x, yy) / sinphi1;
        return lp;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Central Conic";
    }
}
