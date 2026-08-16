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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * HEALPix, {@code +proj=healpix} &mdash; {@code PJ_PROJECTION(healpix)} of
 * {@code 9.8.1:src/projections/healpix.cpp:607-634}.
 *
 * <p>Hierarchical Equal Area isoLatitude Pixelisation. The sphere is drawn as an
 * equatorial Lambert cylindrical equal-area band joined to eight triangular polar lobes,
 * four north and four south. Equal-area everywhere, which is what makes the ellipsoidal
 * form go through the <b>authalic</b> latitude rather than the geocentric one.
 *
 * <h2>The equal-area substitution</h2>
 *
 * <p>On an ellipsoid ({@code es != 0}) upstream does two things at setup
 * ({@code healpix.cpp:618-627}): it builds the authalic-latitude coefficients, and it
 * <em>replaces the semi-major axis</em> with the authalic radius
 * {@code a * sqrt(0.5 * qp)} before {@code pj_calc_ellipsoid_params} re-derives the rest.
 * The forward then maps the authalic latitude with the spherical kernel and the inverse
 * maps back. Both halves are needed: the latitude substitution alone would not preserve
 * area, and the radius substitution alone would not put the lobe boundaries in the right
 * place.
 *
 * <p>Proj4J's equivalent of {@code P->a} at projection time is {@code totalScale}, which
 * {@link Projection#initialize()} derives as {@code a * fromMetres}. This class therefore
 * assigns the authalic radius to {@code a} <em>before</em> calling {@code super}, exactly
 * as {@code CalCOFIProjection} stomps on {@code a} for the same reason. It reads the base
 * radius back from {@link #getEllipsoid()} rather than from the field, so the second
 * {@code initialize()} the parser triggers recomputes the same product instead of
 * shrinking the map by {@code sqrt(0.5 * qp)} a second time.
 *
 * <h2>{@code +rot_xy}</h2>
 *
 * <p>{@code healpix.cpp:615-616} reads it through {@code pj_param}'s <b>{@code d}</b>
 * sigil &mdash; a plain double, <em>not</em> the DMS-capable {@code r} &mdash; and then
 * applies {@code PJ_TORAD}. So {@code +rot_xy=42} is 42 degrees and {@code +rot_xy=42r}
 * is not radians, it is a parse of "42". The forward rotates the projected point by
 * {@code -rot_xy} and the inverse by {@code +rot_xy}; both sines and cosines are taken of
 * the signed angle at setup, not derived from one another by symmetry, so that the two
 * directions are bit-for-bit what upstream computes.
 *
 * <p>{@code rhealpix} does <b>not</b> read {@code +rot_xy}, which is why
 * {@link RHealpixProjection} is a sibling of this class and not a subclass: an
 * {@code instanceof} dispatch in {@code Proj4Parser} would otherwise hand it a rotation
 * upstream never applies.
 *
 * <h2>Inverse domain</h2>
 *
 * <p>{@code s_healpix_inverse}/{@code e_healpix_inverse} test the rotated point against
 * the HEALPix image polygon and set
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} when it falls outside. Proj4J
 * throws instead of returning {@code HUGE_VAL}, so the refusal cannot be mistaken for a
 * coordinate.
 *
 * @see RHealpixProjection
 */
public class HealpixProjection extends Projection {

    private static final long serialVersionUID = 8747549392318216461L;

    /** {@code healpix.cpp:66}. Fuzz for the polygon tests, not for the arithmetic. */
    static final double HEALPIX_EPS = 1e-15;

    /** {@code asin(2/3)}, the latitude where the equatorial band meets the polar lobes. */
    private static final double PHI0 = StrictMath.asin(2.0 / 3.0);

    /**
     * The HEALPix image polygon, {@code healpix.cpp:177-195}. Nineteen vertices, the last
     * repeating the first's abscissa; the {@code EPS} jitter is upstream's and widens the
     * polygon so that a point exactly on the boundary is inside it.
     */
    private static final double[][] HEALPIX_VERTS = {
            {-ProjectionMath.PI - HEALPIX_EPS, ProjectionMath.FORTPI},
            {-3 * ProjectionMath.FORTPI, ProjectionMath.HALFPI + HEALPIX_EPS},
            {-ProjectionMath.HALFPI, ProjectionMath.FORTPI + HEALPIX_EPS},
            {-ProjectionMath.FORTPI, ProjectionMath.HALFPI + HEALPIX_EPS},
            {0.0, ProjectionMath.FORTPI + HEALPIX_EPS},
            {ProjectionMath.FORTPI, ProjectionMath.HALFPI + HEALPIX_EPS},
            {ProjectionMath.HALFPI, ProjectionMath.FORTPI + HEALPIX_EPS},
            {3 * ProjectionMath.FORTPI, ProjectionMath.HALFPI + HEALPIX_EPS},
            {ProjectionMath.PI + HEALPIX_EPS, ProjectionMath.FORTPI},
            {ProjectionMath.PI + HEALPIX_EPS, -ProjectionMath.FORTPI},
            {3 * ProjectionMath.FORTPI, -ProjectionMath.HALFPI - HEALPIX_EPS},
            {ProjectionMath.HALFPI, -ProjectionMath.FORTPI - HEALPIX_EPS},
            {ProjectionMath.FORTPI, -ProjectionMath.HALFPI - HEALPIX_EPS},
            {0.0, -ProjectionMath.FORTPI - HEALPIX_EPS},
            {-ProjectionMath.FORTPI, -ProjectionMath.HALFPI - HEALPIX_EPS},
            {-ProjectionMath.HALFPI, -ProjectionMath.FORTPI - HEALPIX_EPS},
            {-3 * ProjectionMath.FORTPI, -ProjectionMath.HALFPI - HEALPIX_EPS},
            {-ProjectionMath.PI - HEALPIX_EPS, -ProjectionMath.FORTPI},
            {-ProjectionMath.PI - HEALPIX_EPS, ProjectionMath.FORTPI}
    };

    /** Null on a sphere, where no authalic conversion is performed at all. */
    private AuthalicLat authalic;

    /** {@code Q->rot_xy}, in radians. */
    private double rotXy = 0.0;

    /** {@code cos(-rot_xy)}, the forward's rotation. */
    private double cosForwardRot = 1.0;

    /** {@code sin(-rot_xy)}, the forward's rotation. */
    private double sinForwardRot = 0.0;

    /** {@code cos(+rot_xy)}, the inverse's rotation. */
    private double cosInverseRot = 1.0;

    /** {@code sin(+rot_xy)}, the inverse's rotation. */
    private double sinInverseRot = 0.0;

    /**
     * {@code +rot_xy} in degrees, which is the form {@code pj_param}'s {@code d} sigil
     * produces before {@code PJ_TORAD}.
     *
     * @param rotXyDegrees the rotation, in degrees
     */
    public void setRotXyDegrees(double rotXyDegrees) {
        this.rotXy = rotXyDegrees * ProjectionMath.DTR;
    }

    /**
     * {@code +rot_xy} in radians.
     *
     * @param rotXyRadians the rotation, in radians
     */
    public void setRotXyRadians(double rotXyRadians) {
        this.rotXy = rotXyRadians;
    }

    /**
     * The {@code +rot_xy} in force.
     *
     * @return the rotation, in radians
     */
    public double getRotXyRadians() {
        return rotXy;
    }

    /**
     * {@code sign}, {@code healpix.cpp:92}: 1, &minus;1 or <b>0</b>. The zero case is
     * load-bearing &mdash; it is what puts {@code y = 0} on the equator of the polar
     * branch rather than pushing it to a lobe.
     *
     * @param v the value
     * @return its sign
     */
    static double sign(double v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    /**
     * {@code pnpoly}, {@code healpix.cpp:132-165}: crossing-number point-in-polygon, with
     * an explicit vertex-coincidence pre-pass so that a point exactly on a vertex counts
     * as inside.
     *
     * @param vert  the polygon vertices
     * @param testx the abscissa
     * @param testy the ordinate
     * @return true if the point is inside or on the polygon
     */
    static boolean pnpoly(double[][] vert, double testx, double testy) {
        final int nvert = vert.length;
        for (int i = 0; i < nvert; i++) {
            if (testx == vert[i][0] && testy == vert[i][1]) {
                return true;
            }
        }
        int counter = 0;
        double p1x = vert[0][0];
        double p1y = vert[0][1];
        for (int i = 1; i < nvert; i++) {
            final double p2x = vert[i % nvert][0];
            final double p2y = vert[i % nvert][1];
            if (testy > Math.min(p1y, p2y) && testy <= Math.max(p1y, p2y)
                    && testx <= Math.max(p1x, p2x) && p1y != p2y) {
                final double xinters = (testy - p1y) * (p2x - p1x) / (p2y - p1y) + p1x;
                if (p1x == p2x || testx <= xinters) {
                    counter++;
                }
            }
            p1x = p2x;
            p1y = p2y;
        }
        return counter % 2 != 0;
    }

    /**
     * {@code healpix_sphere}, {@code healpix.cpp:246-268}. The whole projection on the
     * unit sphere; everything else in this class is a change of latitude, a rotation or a
     * rearrangement of the polar lobes.
     *
     * @param lam the longitude, radians
     * @param phi the (authalic, on an ellipsoid) latitude, radians
     * @param dst receives the result
     * @return {@code dst}
     */
    static ProjCoordinate healpixSphere(double lam, double phi, ProjCoordinate dst) {
        if (Math.abs(phi) <= PHI0) {
            dst.x = lam;
            dst.y = 3 * ProjectionMath.PI / 8 * FastStrictTrig.sin(phi);
        } else {
            final double sigma =
                    StrictMath.sqrt(3 * (1 - Math.abs(FastStrictTrig.sin(phi))));
            double cn = Math.floor(2 * lam / ProjectionMath.PI + 2);
            if (cn >= 4) {
                cn = 3;
            }
            final double lamc = -3 * ProjectionMath.FORTPI + ProjectionMath.HALFPI * cn;
            dst.x = lamc + (lam - lamc) * sigma;
            dst.y = sign(phi) * ProjectionMath.FORTPI * (2 - sigma);
        }
        return dst;
    }

    /**
     * {@code healpix_spherhealpix_e_inverse}, {@code healpix.cpp:273-298} &mdash; the
     * name is upstream's, a search-and-replace accident, and is kept only in this
     * citation.
     *
     * <p>{@code pow(tau, 2)} is written {@code tau * tau} here. fdlibm's {@code pow}
     * special-cases an exponent of exactly 2 and returns {@code x * x}, so the two are
     * the same double and not merely close.
     *
     * @param x   the abscissa on the unit sphere's image
     * @param y   the ordinate
     * @param dst receives {@code (lam, phi)}
     * @return {@code dst}
     */
    static ProjCoordinate healpixSphereInverse(double x, double y, ProjCoordinate dst) {
        final double y0 = ProjectionMath.FORTPI;
        if (Math.abs(y) <= y0) {
            dst.x = x;
            dst.y = StrictMath.asin(8 * y / (3 * ProjectionMath.PI));
        } else if (Math.abs(y) < ProjectionMath.HALFPI) {
            double cn = Math.floor(2 * x / ProjectionMath.PI + 2);
            if (cn >= 4) {
                cn = 3;
            }
            final double xc = -3 * ProjectionMath.FORTPI + ProjectionMath.HALFPI * cn;
            final double tau = 2.0 - 4 * Math.abs(y) / ProjectionMath.PI;
            dst.x = xc + (x - xc) / tau;
            dst.y = sign(y) * StrictMath.asin(1.0 - tau * tau / 3.0);
        } else {
            dst.x = -ProjectionMath.PI;
            dst.y = sign(y) * ProjectionMath.HALFPI;
        }
        return dst;
    }

    /**
     * The authalic machinery, or {@code null} on a sphere. Package-private so that
     * {@link RHealpixProjection} can share the setup without inheriting
     * {@code +rot_xy}.
     *
     * @return the authalic-latitude machinery, possibly {@code null}
     */
    final AuthalicLat authalic() {
        return authalic;
    }

    /**
     * Rebuilds {@link #authalic} and assigns the authalic radius to {@code a}, then runs
     * {@code super.initialize()} so that {@code totalScale} picks the new radius up.
     * {@code healpix.cpp:618-625} and {@code :662-668}, which differ only in that
     * {@code healpix} re-derives the whole ellipsoid and {@code rhealpix} sets
     * {@code P->ra} alone &mdash; the same thing here, because {@code b} is not read by
     * either kernel.
     */
    final void initializeAuthalicRadius() {
        final double baseRadius = getEllipsoid().equatorRadius;
        if (es != 0.0) {
            authalic = new AuthalicLat(es);
            a = baseRadius * StrictMath.sqrt(0.5 * authalic.qp());
        } else {
            authalic = null;
            a = baseRadius;
        }
        super.initialize();
    }

    /**
     * {@code e_healpix_forward}/{@code s_healpix_forward}, {@code healpix.cpp:493-505}.
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double lat = authalic == null ? phi : authalic.forward(phi);
        healpixSphere(lam, lat, dst);
        final double rx = dst.x * cosForwardRot - dst.y * sinForwardRot;
        final double ry = dst.y * cosForwardRot + dst.x * sinForwardRot;
        dst.x = rx;
        dst.y = ry;
        return dst;
    }

    /**
     * {@code e_healpix_inverse}/{@code s_healpix_inverse}, {@code healpix.cpp:507-541}.
     *
     * @throws ProjectionException when the rotated point is outside the HEALPix image,
     *         where upstream sets
     *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double rx = x * cosInverseRot - y * sinInverseRot;
        final double ry = y * cosInverseRot + x * sinInverseRot;
        if (!pnpoly(HEALPIX_VERTS, rx, ry)) {
            throw new ProjectionException(this,
                    "(" + rx + ", " + ry + ") is outside the HEALPix image "
                            + "(healpix.cpp:513, :531)");
        }
        healpixSphereInverse(rx, ry, dst);
        if (authalic != null) {
            dst.y = authalic.inverse(dst.y);
        }
        return dst;
    }

    /**
     * {@code PJ_PROJECTION(healpix)}, {@code healpix.cpp:607-634}.
     */
    @Override
    public void initialize() {
        // Both signed angles are taken directly, rather than deriving one pair from the
        // other by the evenness of cos and the oddness of sin, so that neither direction
        // can differ from upstream by an ulp on a platform where they are not exact.
        cosForwardRot = FastStrictTrig.cos(-rotXy);
        sinForwardRot = FastStrictTrig.sin(-rotXy);
        cosInverseRot = FastStrictTrig.cos(rotXy);
        sinInverseRot = FastStrictTrig.sin(rotXy);
        initializeAuthalicRadius();
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "HEALPix";
    }
}
