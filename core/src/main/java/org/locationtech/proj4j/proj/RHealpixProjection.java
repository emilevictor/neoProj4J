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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * rHEALPix, {@code +proj=rhealpix} &mdash; {@code PJ_PROJECTION(rhealpix)} of
 * {@code 9.8.1:src/projections/healpix.cpp:636-694}.
 *
 * <p>The same equal-area sphere-to-plane map as {@link HealpixProjection}, but with the
 * eight triangular polar lobes rotated about their tips and reassembled into two whole
 * squares &mdash; one north, one south &mdash; so the image is a plain rectangle with a
 * square stuck on the top and another on the bottom. {@code +north_square} and
 * {@code +south_square} say which of the four positions each square sits in.
 *
 * <h2>Why this is a sibling of {@code HealpixProjection} and not a subclass</h2>
 *
 * <p>{@code rhealpix} does not read {@code +rot_xy}. Upstream shares one file and one
 * {@code pj_healpix_data} struct between the two, but only the {@code healpix} setup
 * function ever writes {@code rot_xy}, so for {@code rhealpix} it stays at the zero
 * {@code calloc} left there. If this class extended {@code HealpixProjection},
 * {@code Proj4Parser}'s {@code instanceof} dispatch would hand it a rotation upstream
 * never applies, and {@code +proj=rhealpix +rot_xy=45} would quietly produce a rotated
 * map instead of ignoring the key. The shared kernel is reached through
 * {@code HealpixProjection}'s package-private statics instead.
 *
 * <h2>{@code +north_square} and {@code +south_square}</h2>
 *
 * <p>Read through {@code pj_param}'s <b>{@code i}</b> sigil, so a strict decimal integer,
 * and each rejected outside {@code [0, 3]} with
 * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} at {@code healpix.cpp:670-683}. That
 * refusal is mirrored by {@code ProjOperatorSetup} so a caller sees it before any
 * coordinate is produced, and it is also enforced here so the class cannot be driven into
 * an undefined state through its setters.
 *
 * <h2>Inverse domain</h2>
 *
 * <p>The rHEALPix image is a twelve-vertex polygon whose shape depends on both square
 * positions, so unlike HEALPix's the vertex table cannot be a constant; it is built once
 * in {@link #initialize()} and only read afterwards.
 * {@code s_rhealpix_inverse}/{@code e_rhealpix_inverse} reject points outside it, and
 * this class throws where upstream returns {@code HUGE_VAL}.
 *
 * @see HealpixProjection
 */
public class RHealpixProjection extends Projection {

    private static final long serialVersionUID = 3130914079452195513L;

    /** {@code get_cap}'s {@code CapMap::north}. */
    private static final int REGION_NORTH = 0;

    /** {@code get_cap}'s {@code CapMap::south}. */
    private static final int REGION_SOUTH = 1;

    /** {@code get_cap}'s {@code CapMap::equatorial}. */
    private static final int REGION_EQUATORIAL = 2;

    /**
     * {@code ROT}, {@code healpix.cpp:42-64}: identity, then counter-clockwise quarter,
     * half and three-quarter turns, then those three again in the inverse order &mdash;
     * R3 is R1's inverse and R1 is R3's, so the last three entries are the same three
     * matrices read backwards, which is why the table has seven rows and not eight.
     */
    private static final double[][][] ROT = {
            {{1, 0}, {0, 1}},    /* IDENT */
            {{0, -1}, {1, 0}},   /* R1, +pi/2 */
            {{-1, 0}, {0, -1}},  /* R2, +pi */
            {{0, 1}, {-1, 0}},   /* R3, +3pi/2 */
            {{0, 1}, {-1, 0}},   /* R1 inverse */
            {{-1, 0}, {0, -1}},  /* R2 inverse */
            {{0, -1}, {1, 0}}    /* R3 inverse */
    };

    /** Null on a sphere, where no authalic conversion is performed at all. */
    private AuthalicLat authalic;

    private int northSquare = 0;

    private int southSquare = 0;

    /**
     * The twelve-vertex rHEALPix image polygon for the square positions in force. Written
     * only by {@link #initialize()}.
     */
    private double[][] imageVerts;

    /**
     * {@code +north_square}.
     *
     * @param northSquare the position of the north polar square, 0 to 3
     * @throws ProjectionException if outside {@code [0, 3]}, as
     *         {@code healpix.cpp:670-676}
     */
    public void setNorthSquare(int northSquare) {
        if (northSquare < 0 || northSquare > 3) {
            throw new ProjectionException(this,
                    "Invalid value for north_square: it should be in [0,3] range. "
                            + "(healpix.cpp:670)");
        }
        this.northSquare = northSquare;
    }

    /**
     * {@code +south_square}.
     *
     * @param southSquare the position of the south polar square, 0 to 3
     * @throws ProjectionException if outside {@code [0, 3]}, as
     *         {@code healpix.cpp:677-683}
     */
    public void setSouthSquare(int southSquare) {
        if (southSquare < 0 || southSquare > 3) {
            throw new ProjectionException(this,
                    "Invalid value for south_square: it should be in [0,3] range. "
                            + "(healpix.cpp:677)");
        }
        this.southSquare = southSquare;
    }

    /**
     * The north polar square's position.
     *
     * @return 0 to 3
     */
    public int getNorthSquare() {
        return northSquare;
    }

    /**
     * The south polar square's position.
     *
     * @return 0 to 3
     */
    public int getSouthSquare() {
        return southSquare;
    }

    /**
     * {@code get_rotate_index}, {@code healpix.cpp:100-121}. Anything outside
     * {@code [-3, 3]} falls through to the identity, which upstream relies on and which is
     * unreachable anyway because the argument is a difference of two values in
     * {@code [0, 3]}.
     *
     * @param index the cap-number difference, &minus;3 to 3
     * @return the row of {@link #ROT} to use
     */
    private static int getRotateIndex(int index) {
        switch (index) {
            case 0:
                return 0;
            case 1:
                return 1;
            case 2:
                return 2;
            case 3:
                return 3;
            case -1:
                return 4;
            case -2:
                return 5;
            case -3:
                return 6;
            default:
                return 0;
        }
    }

    /**
     * {@code in_image(..., proj=1, ...)}, {@code healpix.cpp:200-224}: the rHEALPix image
     * polygon for the given square positions.
     *
     * @param northSquare the north polar square's position
     * @param southSquare the south polar square's position
     * @return the twelve vertices
     */
    private static double[][] imageVerts(int northSquare, int southSquare) {
        final double eps = HealpixProjection.HEALPIX_EPS;
        final double pi = ProjectionMath.PI;
        final double half = ProjectionMath.HALFPI;
        final double fort = ProjectionMath.FORTPI;
        return new double[][]{
                {-pi - eps, fort + eps},
                {-pi + northSquare * half - eps, fort + eps},
                {-pi + northSquare * half - eps, 3 * fort + eps},
                {-pi + (northSquare + 1.0) * half + eps, 3 * fort + eps},
                {-pi + (northSquare + 1.0) * half + eps, fort + eps},
                {pi + eps, fort + eps},
                {pi + eps, -fort - eps},
                {-pi + (southSquare + 1.0) * half + eps, -fort - eps},
                {-pi + (southSquare + 1.0) * half + eps, -3 * fort - eps},
                {-pi + southSquare * half - eps, -3 * fort - eps},
                {-pi + southSquare * half - eps, -fort - eps},
                {-pi - eps, -fort - eps}
        };
    }

    /**
     * {@code combine_caps} with {@code get_cap} inlined,
     * {@code healpix.cpp:337-497}. Forward it folds the four HEALPix polar lobes into one
     * square; inverse it unfolds them.
     *
     * <p>{@code get_cap} is inlined because it is called from exactly one place and
     * returns a four-field {@code CapMap} by value. A Java translation that kept it
     * separate would have to allocate that holder on every coordinate, which the hot-path
     * rule forbids; a field would be a per-coordinate write to a shared projection.
     *
     * <p>The dot products keep upstream's leading {@code 0 +}. It is not decoration: the
     * rotation matrices are all zeros and units, so a term can be {@code -0.0}, and
     * {@code 0.0 + -0.0} is {@code +0.0} while {@code -0.0 + -0.0} is {@code -0.0}. The
     * sign of a zero survives into the output coordinate.
     *
     * @param x           the abscissa on the unit sphere's image
     * @param y           the ordinate
     * @param northSquare the north polar square's position
     * @param southSquare the south polar square's position
     * @param inverse     true to unfold, false to fold
     * @param dst         receives the result
     * @return {@code dst}
     */
    static ProjCoordinate combineCaps(double x, double y, int northSquare,
                                      int southSquare, boolean inverse,
                                      ProjCoordinate dst) {
        final double eps = HealpixProjection.HEALPIX_EPS;
        final double half = ProjectionMath.HALFPI;
        final double fort = ProjectionMath.FORTPI;

        final int region;
        int cn = 0;
        double capX;
        double capY;

        if (!inverse) {
            final double c;
            if (y > fort) {
                region = REGION_NORTH;
                c = half;
            } else if (y < -fort) {
                region = REGION_SOUTH;
                c = -half;
            } else {
                dst.x = x;
                dst.y = y;
                return dst;
            }
            if (x < -half) {
                cn = 0;
                capX = -3 * fort;
            } else if (x < 0) {
                cn = 1;
                capX = -fort;
            } else if (x < half) {
                cn = 2;
                capX = fort;
            } else {
                cn = 3;
                capX = 3 * fort;
            }
            capY = c;
        } else {
            // get_cap takes x by value and shifts its own copy before choosing the cap
            // number; combine_caps' vector still uses the unshifted x.
            final double xs;
            if (y > fort) {
                region = REGION_NORTH;
                capX = -3 * fort + northSquare * half;
                capY = half;
                xs = x - northSquare * half;
            } else if (y < -fort) {
                region = REGION_SOUTH;
                capX = -3 * fort + southSquare * half;
                capY = -half;
                xs = x - southSquare * half;
            } else {
                dst.x = x;
                dst.y = y;
                return dst;
            }
            if (region == REGION_NORTH) {
                if (y >= -xs - fort - eps && y < xs + 5 * fort - eps) {
                    cn = (northSquare + 1) % 4;
                } else if (y > -xs - fort + eps && y >= xs + 5 * fort - eps) {
                    cn = (northSquare + 2) % 4;
                } else if (y <= -xs - fort + eps && y > xs + 5 * fort + eps) {
                    cn = (northSquare + 3) % 4;
                } else {
                    cn = northSquare;
                }
            } else {
                if (y <= xs + fort + eps && y > -xs - 5 * fort + eps) {
                    cn = (southSquare + 1) % 4;
                } else if (y < xs + fort - eps && y <= -xs - 5 * fort + eps) {
                    cn = (southSquare + 2) % 4;
                } else if (y >= xs + fort - eps && y < -xs - 5 * fort - eps) {
                    cn = (southSquare + 3) % 4;
                } else {
                    cn = southSquare;
                }
            }
        }

        final int pole;
        final int rotIndex;
        if (!inverse) {
            if (region == REGION_NORTH) {
                pole = northSquare;
                rotIndex = getRotateIndex(cn - pole);
            } else {
                pole = southSquare;
                rotIndex = getRotateIndex(-1 * (cn - pole));
            }
        } else {
            if (region == REGION_NORTH) {
                pole = northSquare;
                rotIndex = getRotateIndex(-1 * (cn - pole));
            } else {
                pole = southSquare;
                rotIndex = getRotateIndex(cn - pole);
            }
        }

        final double[][] m = ROT[rotIndex];
        final double vx = x - capX;
        final double vy = y - capY;
        final double dx = 0.0 + m[0][0] * vx + m[0][1] * vy;
        final double dy = 0.0 + m[1][0] * vx + m[1][1] * vy;

        dst.x = dx + (-3 * fort + (inverse ? cn : pole) * half);
        dst.y = dy + ((region == REGION_NORTH) ? 1 : -1) * half;
        return dst;
    }

    /**
     * {@code e_rhealpix_forward}/{@code s_rhealpix_forward},
     * {@code healpix.cpp:541-558}.
     */
    @Override
    protected ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        final double lat = authalic == null ? phi : authalic.forward(phi);
        HealpixProjection.healpixSphere(lam, lat, dst);
        return combineCaps(dst.x, dst.y, northSquare, southSquare, false, dst);
    }

    /**
     * {@code e_rhealpix_inverse}/{@code s_rhealpix_inverse},
     * {@code healpix.cpp:560-594}.
     *
     * @throws ProjectionException when the point is outside the rHEALPix image, where
     *         upstream sets {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        if (!HealpixProjection.pnpoly(imageVerts, x, y)) {
            throw new ProjectionException(this,
                    "(" + x + ", " + y + ") is outside the rHEALPix image "
                            + "(healpix.cpp:565, :583)");
        }
        combineCaps(x, y, northSquare, southSquare, true, dst);
        HealpixProjection.healpixSphereInverse(dst.x, dst.y, dst);
        if (authalic != null) {
            dst.y = authalic.inverse(dst.y);
        }
        return dst;
    }

    /**
     * {@code PJ_PROJECTION(rhealpix)}, {@code healpix.cpp:636-694}. The authalic radius
     * is read back off the ellipsoid rather than compounded onto {@code a}, so that the
     * second {@code initialize()} the parser triggers lands on the same number as the
     * first.
     */
    @Override
    public void initialize() {
        imageVerts = imageVerts(northSquare, southSquare);
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

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "rHEALPix";
    }
}
