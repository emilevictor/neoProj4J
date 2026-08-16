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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Quadrilateralized Spherical Cube, {@code +proj=qsc} &mdash;
 * {@code 9.8.1:src/projections/qsc.cpp}.
 *
 * <p>The sphere is wrapped onto a cube and one face of that cube is drawn. Which face is
 * a function of the projection centre alone, decided once in {@link #initialize()} by
 * {@code PJ_PROJECTION(qsc)} ({@code qsc.cpp:387-399}), and each face is then divided into
 * four <em>areas</em> counted counterclockwise from the one containing the centre.
 *
 * <h2>Face selection</h2>
 *
 * <table>
 * <caption>{@code qsc.cpp:388-399}</caption>
 * <tr><th>condition</th><th>face</th></tr>
 * <tr><td>{@code phi0 >= pi/2 - pi/8}</td><td>TOP</td></tr>
 * <tr><td>{@code phi0 <= -(pi/2 - pi/8)}</td><td>BOTTOM</td></tr>
 * <tr><td>{@code |lam0| <= pi/4}</td><td>FRONT</td></tr>
 * <tr><td>{@code |lam0| <= 3pi/4}</td><td>RIGHT if {@code lam0 > 0}, else LEFT</td></tr>
 * <tr><td>otherwise</td><td>BACK</td></tr>
 * </table>
 *
 * <p>Note the second column of the test is {@code M_FORTPI / 2.0}, i.e. 22.5&deg;, not
 * 45&deg;: any {@code +lat_0} at or beyond 67.5&deg; selects a polar face regardless of
 * {@code +lon_0}. The four equatorial faces are reached by shifting the longitude origin
 * ({@code qsc_shift_longitude_origin}) and then rotating the unit-sphere Cartesian triple.
 *
 * <h2>Parameters</h2>
 *
 * <p>{@code PJ_PROJECTION(qsc)} reads nothing beyond {@code P->phi0}, {@code P->lam0},
 * {@code P->a} and {@code P->es}, all of which the generic parser already supplies, so
 * this projection adds no key to {@code Proj4Keyword}. It also contains no rejection
 * guard of any kind &mdash; the only early return is the {@code calloc} failure &mdash;
 * so there is nothing for {@code ProjDefinitionValidator} to refuse either. Every
 * {@code +proj=qsc} definition PROJ 9.8.1 builds, this builds.
 *
 * <h2>Ellipsoid</h2>
 *
 * <p>On an ellipsoid the forward first shifts the geodetic latitude to a geocentric one,
 * {@code atan(one_minus_f_squared * tan(phi))}, and the inverse shifts back through the
 * auxiliary radius {@code xa}; this is the [LK12] sphere substitution and is the only
 * place {@code +ellps} enters the kernel. On a sphere ({@code es == 0}) both shifts are
 * skipped and the four derived constants are never read.
 *
 * <h2>Idempotence</h2>
 *
 * <p>{@link #initialize()} runs twice. Every field it writes is derived from
 * {@code projectionLatitude}, {@code projectionLongitude}, {@code a} and {@code es}, none
 * of which this class modifies, so the second pass recomputes the same values. The
 * spherical branch clears the four ellipsoidal constants rather than leaving them stale,
 * matching the {@code calloc} that zeroes them upstream.
 */
public class QuadrilateralizedSphericalCubeProjection extends Projection {

    private static final long serialVersionUID = 5361419948241905241L;

    /** {@code qsc.cpp:50-57}. */
    private static final int FACE_FRONT = 0;
    private static final int FACE_RIGHT = 1;
    private static final int FACE_BACK = 2;
    private static final int FACE_LEFT = 3;
    private static final int FACE_TOP = 4;
    private static final int FACE_BOTTOM = 5;

    /** {@code qsc.cpp:76}. AREA_0 holds the centre; the rest count counterclockwise. */
    private static final int AREA_0 = 0;
    private static final int AREA_1 = 1;
    private static final int AREA_2 = 2;
    private static final int AREA_3 = 3;

    /** {@code qsc.cpp:71}. */
    private static final double QSC_EPS10 = 1.e-10;

    /** {@code M_PI_HALFPI}, {@code proj_internal.h:162}: 1.5 pi, written as upstream writes it. */
    private static final double PI_HALFPI = 4.71238898038468985769;

    /** {@code 1 / sqrt(2)}, the inverse's {@code tantheta} denominator offset. */
    private static final double INV_SQRT2 = 1.0 / StrictMath.sqrt(2.0);

    /** The cube face, one of the six {@code FACE_*} constants. */
    private int face = FACE_FRONT;

    /** {@code Q->b}, the semi-minor axis. Zero on a sphere, where it is never read. */
    private double b = 0.0;

    /** {@code Q->one_minus_f}. Zero on a sphere. */
    private double oneMinusF = 0.0;

    /** {@code Q->one_minus_f_squared}. Zero on a sphere. */
    private double oneMinusFSquared = 0.0;

    /**
     * {@code qsc_shift_longitude_origin}, {@code qsc.cpp:107-115}. A single wrap, not a
     * loop: upstream assumes the sum is already within one turn.
     *
     * @param longitude the longitude, radians
     * @param offset    the shift, radians
     * @return the shifted longitude, wrapped once into {@code [-pi, pi]}
     */
    private static double shiftLongitudeOrigin(double longitude, double offset) {
        double slon = longitude + offset;
        if (slon < -ProjectionMath.PI) {
            slon += ProjectionMath.TWOPI;
        } else if (slon > +ProjectionMath.PI) {
            slon -= ProjectionMath.TWOPI;
        }
        return slon;
    }

    /**
     * {@code qsc_e_forward}, {@code qsc.cpp:117-235}. One function serves sphere and
     * ellipsoid; the {@code es != 0} branches are the [LK12] latitude shift.
     *
     * <p>{@code qsc_fwd_equat_face_theta} ({@code qsc.cpp:81-104}) is inlined into the
     * four-face branch rather than kept as a helper, because upstream returns
     * {@code theta} and writes the area through an {@code enum Area *} out-parameter, and
     * there is no allocation-free way to return two values from a Java method. A field
     * would be a hot-path write on a shared projection object, and a wrapper object would
     * allocate once per coordinate.
     */
    @Override
    protected ProjCoordinate project(double lam, double lpphi, ProjCoordinate dst) {
        final double lat;
        if (es != 0.0) {
            lat = StrictMath.atan(oneMinusFSquared * FastStrictTrig.tan(lpphi));
        } else {
            lat = lpphi;
        }

        int area;
        double theta;
        double phi;
        double longitude = lam;

        if (face == FACE_TOP) {
            phi = ProjectionMath.HALFPI - lat;
            if (longitude >= ProjectionMath.FORTPI
                    && longitude <= ProjectionMath.HALFPI + ProjectionMath.FORTPI) {
                area = AREA_0;
                theta = longitude - ProjectionMath.HALFPI;
            } else if (longitude > ProjectionMath.HALFPI + ProjectionMath.FORTPI
                    || longitude <= -(ProjectionMath.HALFPI + ProjectionMath.FORTPI)) {
                area = AREA_1;
                theta = (longitude > 0.0 ? longitude - ProjectionMath.PI
                        : longitude + ProjectionMath.PI);
            } else if (longitude > -(ProjectionMath.HALFPI + ProjectionMath.FORTPI)
                    && longitude <= -ProjectionMath.FORTPI) {
                area = AREA_2;
                theta = longitude + ProjectionMath.HALFPI;
            } else {
                area = AREA_3;
                theta = longitude;
            }
        } else if (face == FACE_BOTTOM) {
            phi = ProjectionMath.HALFPI + lat;
            if (longitude >= ProjectionMath.FORTPI
                    && longitude <= ProjectionMath.HALFPI + ProjectionMath.FORTPI) {
                area = AREA_0;
                theta = -longitude + ProjectionMath.HALFPI;
            } else if (longitude < ProjectionMath.FORTPI && longitude >= -ProjectionMath.FORTPI) {
                area = AREA_1;
                theta = -longitude;
            } else if (longitude < -ProjectionMath.FORTPI
                    && longitude >= -(ProjectionMath.HALFPI + ProjectionMath.FORTPI)) {
                area = AREA_2;
                theta = -longitude - ProjectionMath.HALFPI;
            } else {
                area = AREA_3;
                theta = (longitude > 0.0 ? -longitude + ProjectionMath.PI
                        : -longitude - ProjectionMath.PI);
            }
        } else {
            if (face == FACE_RIGHT) {
                longitude = shiftLongitudeOrigin(longitude, +ProjectionMath.HALFPI);
            } else if (face == FACE_BACK) {
                longitude = shiftLongitudeOrigin(longitude, +ProjectionMath.PI);
            } else if (face == FACE_LEFT) {
                longitude = shiftLongitudeOrigin(longitude, -ProjectionMath.HALFPI);
            }
            final double sinlat = FastStrictTrig.sin(lat);
            final double coslat = FastStrictTrig.cos(lat);
            final double sinlon = FastStrictTrig.sin(longitude);
            final double coslon = FastStrictTrig.cos(longitude);
            final double q = coslat * coslon;
            final double r = coslat * sinlon;
            final double s = sinlat;

            final double thetaY;
            final double thetaX;
            if (face == FACE_RIGHT) {
                phi = StrictMath.acos(r);
                thetaY = s;
                thetaX = -q;
            } else if (face == FACE_BACK) {
                phi = StrictMath.acos(-q);
                thetaY = s;
                thetaX = -r;
            } else if (face == FACE_LEFT) {
                phi = StrictMath.acos(-r);
                thetaY = s;
                thetaX = q;
            } else {
                // FACE_FRONT. Upstream also writes an unreachable else that zeroes
                // phi/theta/area (qsc.cpp:205-209); with TOP and BOTTOM already handled the
                // five remaining faces are exhaustive, so FRONT is the only one left.
                phi = StrictMath.acos(q);
                thetaY = s;
                thetaX = r;
            }

            // qsc_fwd_equat_face_theta, qsc.cpp:81-104.
            if (phi < QSC_EPS10) {
                area = AREA_0;
                theta = 0.0;
            } else {
                theta = StrictMath.atan2(thetaY, thetaX);
                if (Math.abs(theta) <= ProjectionMath.FORTPI) {
                    area = AREA_0;
                } else if (theta > ProjectionMath.FORTPI
                        && theta <= ProjectionMath.HALFPI + ProjectionMath.FORTPI) {
                    area = AREA_1;
                    theta -= ProjectionMath.HALFPI;
                } else if (theta > ProjectionMath.HALFPI + ProjectionMath.FORTPI
                        || theta <= -(ProjectionMath.HALFPI + ProjectionMath.FORTPI)) {
                    area = AREA_2;
                    theta = (theta >= 0.0 ? theta - ProjectionMath.PI
                            : theta + ProjectionMath.PI);
                } else {
                    area = AREA_3;
                    theta += ProjectionMath.HALFPI;
                }
            }
        }

        // qsc.cpp:215-218. Eq. (3-21) of [OL76] for mu -- note the typos there, compare
        // Eq. (3-14) -- and Eq. (3-38) for nu, of which only tan(nu) = t is needed.
        double mu = StrictMath.atan((12.0 / ProjectionMath.PI)
                * (theta + StrictMath.acos(FastStrictTrig.sin(theta)
                        * FastStrictTrig.cos(ProjectionMath.FORTPI))
                        - ProjectionMath.HALFPI));
        final double cosmu = FastStrictTrig.cos(mu);
        final double t = StrictMath.sqrt((1.0 - FastStrictTrig.cos(phi)) / (cosmu * cosmu)
                / (1.0 - FastStrictTrig.cos(StrictMath.atan(1.0 / FastStrictTrig.cos(theta)))));

        if (area == AREA_1) {
            mu += ProjectionMath.HALFPI;
        } else if (area == AREA_2) {
            mu += ProjectionMath.PI;
        } else if (area == AREA_3) {
            mu += PI_HALFPI;
        }

        dst.x = t * FastStrictTrig.cos(mu);
        dst.y = t * FastStrictTrig.sin(mu);
        return dst;
    }

    /**
     * {@code qsc_e_inverse}, {@code qsc.cpp:237-376}.
     *
     * <p>The inverse is not in the original paper; upstream cites a 1993 FITS mailing-list
     * message for the derivation. {@code cosphi} is clamped into {@code [-1, 1]} before
     * the {@code acos}, which is upstream's own guard and not an addition here.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double nu = StrictMath.atan(StrictMath.sqrt(x * x + y * y));
        double mu = StrictMath.atan2(y, x);
        final int area;
        if (x >= 0.0 && x >= Math.abs(y)) {
            area = AREA_0;
        } else if (y >= 0.0 && y >= Math.abs(x)) {
            area = AREA_1;
            mu -= ProjectionMath.HALFPI;
        } else if (x < 0.0 && -x >= Math.abs(y)) {
            area = AREA_2;
            mu = (mu < 0.0 ? mu + ProjectionMath.PI : mu - ProjectionMath.PI);
        } else {
            area = AREA_3;
            mu += ProjectionMath.HALFPI;
        }

        double t = (ProjectionMath.PI / 12.0) * FastStrictTrig.tan(mu);
        final double tantheta = FastStrictTrig.sin(t) / (FastStrictTrig.cos(t) - INV_SQRT2);
        final double theta = StrictMath.atan(tantheta);
        final double cosmu = FastStrictTrig.cos(mu);
        final double tannu = FastStrictTrig.tan(nu);
        double cosphi = 1.0 - cosmu * cosmu * tannu * tannu
                * (1.0 - FastStrictTrig.cos(StrictMath.atan(1.0 / FastStrictTrig.cos(theta))));
        if (cosphi < -1.0) {
            cosphi = -1.0;
        } else if (cosphi > +1.0) {
            cosphi = +1.0;
        }

        if (face == FACE_TOP) {
            final double phi = StrictMath.acos(cosphi);
            dst.y = ProjectionMath.HALFPI - phi;
            if (area == AREA_0) {
                dst.x = theta + ProjectionMath.HALFPI;
            } else if (area == AREA_1) {
                dst.x = (theta < 0.0 ? theta + ProjectionMath.PI : theta - ProjectionMath.PI);
            } else if (area == AREA_2) {
                dst.x = theta - ProjectionMath.HALFPI;
            } else {
                dst.x = theta;
            }
        } else if (face == FACE_BOTTOM) {
            final double phi = StrictMath.acos(cosphi);
            dst.y = phi - ProjectionMath.HALFPI;
            if (area == AREA_0) {
                dst.x = -theta + ProjectionMath.HALFPI;
            } else if (area == AREA_1) {
                dst.x = -theta;
            } else if (area == AREA_2) {
                dst.x = -theta - ProjectionMath.HALFPI;
            } else {
                dst.x = (theta < 0.0 ? -theta - ProjectionMath.PI : -theta + ProjectionMath.PI);
            }
        } else {
            double q = cosphi;
            double r;
            double s;
            t = q * q;
            if (t >= 1.0) {
                s = 0.0;
            } else {
                s = StrictMath.sqrt(1.0 - t) * FastStrictTrig.sin(theta);
            }
            t += s * s;
            if (t >= 1.0) {
                r = 0.0;
            } else {
                r = StrictMath.sqrt(1.0 - t);
            }
            // Rotate q,r,s into the correct area.
            if (area == AREA_1) {
                t = r;
                r = -s;
                s = t;
            } else if (area == AREA_2) {
                r = -r;
                s = -s;
            } else if (area == AREA_3) {
                t = r;
                r = s;
                s = -t;
            }
            // Rotate q,r,s into the correct cube face.
            if (face == FACE_RIGHT) {
                t = q;
                q = -r;
                r = t;
            } else if (face == FACE_BACK) {
                q = -q;
                r = -r;
            } else if (face == FACE_LEFT) {
                t = q;
                q = r;
                r = -t;
            }
            dst.y = StrictMath.acos(-s) - ProjectionMath.HALFPI;
            dst.x = StrictMath.atan2(r, q);
            if (face == FACE_RIGHT) {
                dst.x = shiftLongitudeOrigin(dst.x, -ProjectionMath.HALFPI);
            } else if (face == FACE_BACK) {
                dst.x = shiftLongitudeOrigin(dst.x, -ProjectionMath.PI);
            } else if (face == FACE_LEFT) {
                dst.x = shiftLongitudeOrigin(dst.x, +ProjectionMath.HALFPI);
            }
        }

        // qsc.cpp:362-374, the [LK12] shift back from the sphere to the ellipsoid.
        if (es != 0.0) {
            final boolean invertSign = dst.y < 0.0;
            final double tanphi = FastStrictTrig.tan(dst.y);
            final double xa = b / StrictMath.sqrt(tanphi * tanphi + oneMinusFSquared);
            dst.y = StrictMath.atan(StrictMath.sqrt(a * a - xa * xa) / (oneMinusF * xa));
            if (invertSign) {
                dst.y = -dst.y;
            }
        }
        return dst;
    }

    /**
     * {@code PJ_PROJECTION(qsc)}, {@code qsc.cpp:378-410}: pick the cube face, then derive
     * the ellipsoid constants.
     */
    @Override
    public void initialize() {
        super.initialize();
        if (projectionLatitude >= ProjectionMath.HALFPI - ProjectionMath.FORTPI / 2.0) {
            face = FACE_TOP;
        } else if (projectionLatitude <= -(ProjectionMath.HALFPI - ProjectionMath.FORTPI / 2.0)) {
            face = FACE_BOTTOM;
        } else if (Math.abs(projectionLongitude) <= ProjectionMath.FORTPI) {
            face = FACE_FRONT;
        } else if (Math.abs(projectionLongitude) <= ProjectionMath.HALFPI + ProjectionMath.FORTPI) {
            face = (projectionLongitude > 0.0 ? FACE_RIGHT : FACE_LEFT);
        } else {
            face = FACE_BACK;
        }
        if (es != 0.0) {
            b = a * StrictMath.sqrt(1.0 - es);
            oneMinusF = 1.0 - (a - b) / a;
            oneMinusFSquared = oneMinusF * oneMinusF;
        } else {
            // calloc leaves these zero upstream, and the spherical kernel never reads them.
            b = 0.0;
            oneMinusF = 0.0;
            oneMinusFSquared = 0.0;
        }
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Quadrilateralized Spherical Cube";
    }
}
