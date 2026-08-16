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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Icosahedral Snyder Equal Area. A port of {@code 9.8.1:src/projections/isea.cpp}.
 *
 * <p>Snyder's 1992 equal-area polyhedral projection: the sphere is divided among the twenty
 * faces of an icosahedron, each face is projected equal-area onto its plane, and the
 * icosahedron is unfolded. Area is preserved exactly; angles are distorted by up to 17.27
 * degrees and scale varies by about 16 per cent.
 *
 * <h2>The two halves have different provenance, and it shows</h2>
 *
 * <p>The forward is Nathan Wagner's public-domain code, a direct transcription of Snyder's
 * equations 5-14. The inverse arrived much later, adapted from Franz-Benjamin Mocnik's Java
 * implementation, and it is <b>not</b> a general inverse of the forward: it is installed only
 * when the operator is in one of exactly two configurations. See {@link #initialize()}. Where
 * it is not installed, upstream returns {@code {inf, inf}} for every point, so we refuse.
 *
 * <h2>{@code +orient}</h2>
 *
 * <p>{@code +orient=isea} (the default) puts the poles at edge midpoints, so the equator is
 * mapped symmetrically. {@code +orient=pole} puts a vertex at each pole. Any other value is
 * refused at setup. The value set is disjoint from airocean's {@code vertical|horizontal};
 * they share only the keyword.
 *
 * <p>{@code +lat_0}, {@code +lon_0} and {@code +azi} override the orientation directly
 * ({@code isea.cpp:1024-1033}) rather than acting as the usual projection centre. Note that
 * {@code +lon_0} does <em>double</em> duty: upstream's generic setup also copies it into
 * {@code P->lam0}, so it both shifts the incoming longitude and moves the icosahedron. That
 * is reproduced here, because {@link Projection#projectRadians} subtracts
 * {@link Projection#projectionLongitude} in the same place {@code fwd_prepare} does.
 *
 * <h2>{@code +mode}, {@code +resolution}, {@code +aperture}</h2>
 *
 * <p>{@code +mode=plane} (the default) emits plane coordinates. {@code di}, {@code dd} and
 * {@code hex} emit discrete-global-grid cell addresses instead - integers dressed up as
 * coordinates, which the affine in {@code fwd_finalize} then multiplies by {@code a} anyway.
 * They are ported because the corpus exercises one of them, and because refusing a value
 * upstream accepts would be a divergence; nothing in the shipped dictionaries uses them.
 *
 * <h2>Upstream defects that are deliberately NOT reproduced</h2>
 *
 * <ol>
 * <li>{@code isea_snyder_forward} ends with {@code fprintf(stderr, ...); exit(EXIT_FAILURE);}
 *     when no face claims the point, and {@code isea_triangle_xy} does the same in a
 *     {@code default:} label it calls "should be impossible". <b>A library must not call
 *     {@code exit}</b>, and there is no way to be bug-compatible with process termination.
 *     Both raise {@link ProjectionException} here. The {@code isea_triangle_xy} branch is in
 *     fact unreachable - {@code triangle %= 20} then {@code triangle / 5} is 0..3 - and the
 *     {@code isea_snyder_forward} one has not been observed to fire.</li>
 * <li>Upstream caches {@code triangle} and {@code quad} on the shared operator struct on
 *     every forward call. Nothing reads {@code triangle}, and {@code quad} is read only from
 *     an {@code #ifdef FIXME} block that is not compiled. They are simply not stored here,
 *     which keeps the forward free of hot-path field writes.</li>
 * </ol>
 *
 * <h2>One real inconsistency, faithfully preserved</h2>
 *
 * <p>The forward carries a {@code TODO} saying it should convert geodetic latitude to
 * authalic and does not, while the inverse's setup <em>does</em> compute an authalic radius
 * from the ellipsoid. On an ellipsoid the two halves therefore disagree and the round trip
 * does not close. Every corpus row uses a sphere, where the question does not arise. This is
 * upstream's behaviour to the digit and is not corrected here.
 */
public class IcosahedralSnyderEqualAreaProjection extends Projection {

    private static final long serialVersionUID = 1L;

    private static final int NUM_ICOSAHEDRON_FACES = 20;

    /* ---------------------------------------------------------------------------------
     * Constants, transcribed from isea.cpp:73-134. Written as the same expressions
     * upstream writes, not simplified: SIN_G_COS_SDC2VOS really is the product of two
     * literals, and TABLE_G really is TANG * (SQRT3 / 2.0), so the rounding matches.
     * --------------------------------------------------------------------------------- */

    private static final double DEG120 = 2.09439510239319549229;
    private static final double DEG180 = Math.PI;

    /** {@code sqrt(5)/M_PI}; used only by the non-plane output modes. */
    private static final double ISEA_SCALE = 0.8301572857837594396028083;

    /** Latitude of the centre of the top icosahedron faces, {@code atan((3+sqrt(5))/4)}. */
    private static final double E_RAD = 0.91843818701052843323;

    /** Latitude of the centre of the mirroring faces, {@code atan((3-sqrt(5))/4)}. */
    private static final double F_RAD = 0.18871053078356206978;

    /** g: spherical distance from a face centre to any of its vertices. */
    private static final double SDC2VOS = 0.6523581397843681859886783;

    private static final double TANG = 0.76393202250021030358019673567;

    private static final double TAN30 = 0.57735026918962576450914878;
    private static final double COT_THETA = 1.0 / TAN30;

    /** {@code cos(36 deg)}. */
    private static final double COS_G = 0.80901699437494742410229341718281905886;
    /** {@code sin(36 deg)}. */
    private static final double SIN_G = 0.587785252292473129168705954639072768597652;
    /** {@code cos(g)}. */
    private static final double COS_SDC2VOS = 0.7946544722917661229596057297879189448539;
    private static final double SIN_G_COS_SDC2VOS = SIN_G * COS_SDC2VOS;

    private static final double SQRT3 = 1.73205080756887729352744634150587236694280525381038;
    private static final double SIN60 = SQRT3 / 2.0;
    private static final double COS30 = SQRT3 / 2.0;

    private static final double TABLE_G = TANG * SIN60;
    private static final double TABLE_H = 0.25 * TANG;

    /** R' / R. */
    private static final double RPRIME_OVER_R = 0.9103832815095032;

    private static final double ISEA_STD_LAT = 1.01722196792335072101;
    private static final double ISEA_STD_LONG = .19634954084936207740;

    /** Newton stopping threshold in the inverse ({@code isea.cpp:1197}). */
    private static final double PRECISION = ProjectionMath.DTR * 1e-11;
    /** Pole cut-off in {@code revertOrientation} ({@code isea.cpp:1198}). */
    private static final double PRECISION_PER_DEFINITION = ProjectionMath.DTR * 1e-5;

    private static final double AZ_MAX = ProjectionMath.DTR * 120;
    private static final double WEST_VERTEX_LON = ProjectionMath.DTR * -144;

    /** {@code G} in Snyder's equation 7. */
    private static final double G_36 = ProjectionMath.DTR * 36;

    /** {@code faceOrientation}'s non-zero return ({@code isea.cpp:1288}). */
    private static final double DTR_180 = ProjectionMath.DTR * 180;

    private static final double SAFE_ARC_EPSILON = 1E-15;

    /** {@code +mode} values, in upstream's {@code isea_address_form} order. */
    private static final int ISEA_PLANE = 0;
    private static final int ISEA_Q2DI = 1;
    private static final int ISEA_Q2DD = 2;
    private static final int ISEA_HEX = 3;

    /**
     * Latitudes of the dodecahedron vertices that sit at the icosahedron face centres
     * ({@code isea.cpp:246-259}).
     */
    private static final double[] FACE_CENTER_LAT = {
        E_RAD, E_RAD, E_RAD, E_RAD, E_RAD,
        F_RAD, F_RAD, F_RAD, F_RAD, F_RAD,
        -F_RAD, -F_RAD, -F_RAD, -F_RAD, -F_RAD,
        -E_RAD, -E_RAD, -E_RAD, -E_RAD, -E_RAD
    };

    /** Longitudes of the same twenty face centres. */
    private static final double[] FACE_CENTER_LON = {
        ProjectionMath.toRad(-144), ProjectionMath.toRad(-72), ProjectionMath.toRad(0),
        ProjectionMath.toRad(72), ProjectionMath.toRad(144),
        ProjectionMath.toRad(-144), ProjectionMath.toRad(-72), ProjectionMath.toRad(0),
        ProjectionMath.toRad(72), ProjectionMath.toRad(144),
        ProjectionMath.toRad(-108), ProjectionMath.toRad(-36), ProjectionMath.toRad(36),
        ProjectionMath.toRad(108), ProjectionMath.toRad(180),
        ProjectionMath.toRad(-108), ProjectionMath.toRad(-36), ProjectionMath.toRad(36),
        ProjectionMath.toRad(108), ProjectionMath.toRad(180)
    };

    /**
     * {@code sin} and {@code cos} of {@link #FACE_CENTER_LAT}, which upstream recomputes per
     * operator in {@code isea_grid_init}. There are only four distinct latitudes and they are
     * compile-time constants, so this is a class-level table; it is derived with
     * {@link FastStrictTrig}, which {@code DeterminismTest} pins to {@code StrictMath}, so it
     * is the same table on every JVM.
     */
    private static final double[] FACE_CENTER_SIN_LAT = new double[NUM_ICOSAHEDRON_FACES];
    private static final double[] FACE_CENTER_COS_LAT = new double[NUM_ICOSAHEDRON_FACES];

    static {
        for (int i = 0; i < NUM_ICOSAHEDRON_FACES; i++) {
            FACE_CENTER_SIN_LAT[i] = FastStrictTrig.sin(FACE_CENTER_LAT[i]);
            FACE_CENTER_COS_LAT[i] = FastStrictTrig.cos(FACE_CENTER_LAT[i]);
        }
    }

    /* ---------------------------------------------------------------------------------
     * Parameters. Defaults are upstream's post-setup values, NOT isea_grid_init's:
     * isea_grid_init seeds aperture 4 / resolution 6 and PJ_PROJECTION(isea) then
     * unconditionally overwrites both with 3 and 4 (isea.cpp:1058-1068). Seeding 4 and 6
     * here would silently change which grid every +mode=di query lands in.
     * --------------------------------------------------------------------------------- */

    private double oLat = ISEA_STD_LAT;
    private double oLon = ISEA_STD_LONG;
    private double oAz = 0.0;
    private int aperture = 3;
    private int resolution = 4;
    private int output = ISEA_PLANE;

    /* Derived in initialize(). */
    private boolean planar;
    private double orientationLat;
    private double orientationLon;
    private double cosOrientationLat;
    private double sinOrientationLat;
    private double r2;
    private double rprime;
    private double rprime2X;
    private double rprimeTang;
    private double rprime2Tan2g;
    private double centerToBase;
    private double triWidth;
    private final double[] yOffsets = new double[4];
    private double xo;
    private double yo;
    private double sx;
    private double sy;

    /* =================================================================================
     * Parameter setters. Each polices its own value list, exactly where upstream's setup
     * does, so a bad value is refused before initialize() rather than mis-projected.
     * ================================================================================= */

    /**
     * {@code +orient}, an 's' sigil compared with {@code strcmp} ({@code isea.cpp:1008-1021}).
     * A bare {@code +orient} yields the empty string upstream, which matches neither name and
     * fails setup; passing {@code ""} here reproduces that.
     */
    public void setOrient(String orient) {
        if ("isea".equals(orient)) {
            oLat = ISEA_STD_LAT;
            oLon = ISEA_STD_LONG;
            oAz = 0.0;
        } else if ("pole".equals(orient)) {
            oLat = Math.PI / 2.0;
            oLon = 0.0;
            oAz = 0;
        } else {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "Invalid value for orient: only isea or pole are supported");
        }
    }

    /** {@code +mode} ({@code isea.cpp:1035-1051}). */
    public void setMode(String mode) {
        if ("plane".equals(mode)) {
            output = ISEA_PLANE;
        } else if ("di".equals(mode)) {
            output = ISEA_Q2DI;
        } else if ("dd".equals(mode)) {
            output = ISEA_Q2DD;
        } else if ("hex".equals(mode)) {
            output = ISEA_HEX;
        } else {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "Invalid value for mode: only plane, di, dd or hex are supported");
        }
    }

    /** {@code +resolution}, an 'i' sigil; upstream imposes no range check. */
    public void setResolution(int resolution) {
        this.resolution = resolution;
    }

    /** {@code +aperture}, an 'i' sigil; upstream imposes no range check. */
    public void setAperture(int aperture) {
        this.aperture = aperture;
    }

    /** {@code +azi}, an 'r' sigil; overrides the orientation azimuth, not a map rotation. */
    public void setAziRadians(double azi) {
        this.oAz = azi;
    }

    /** {@code +lon_0} as read by isea's own setup, in addition to the generic {@code lam0}. */
    public void setOrientationLongitudeRadians(double lon) {
        this.oLon = lon;
    }

    /** {@code +lat_0} as read by isea's own setup. */
    public void setOrientationLatitudeRadians(double lat) {
        this.oLat = lat;
    }

    /* =================================================================================
     * Setup.
     * ================================================================================= */

    /**
     * Transcribes {@code pj_isea_data::initialize} ({@code isea.cpp:1310-1355}).
     *
     * <p>The inverse exists only for two exact orientations and only in the default plane
     * mode at aperture 3 / resolution 4. The comparisons are upstream's literal {@code ==}
     * on doubles, which is why {@code +lat_0} spelled to the last digit of
     * {@link #ISEA_STD_LAT} still selects the standard net: reproducing that is the point.
     *
     * <p>{@code P->b} has no counterpart field here, so the authalic surface integral uses
     * {@code a * sqrt(1 - es)}. For GRS80 that is bit-for-bit the same double as upstream's
     * {@code (1 - f) * a}, checked by hex comparison.
     */
    @Override
    public void initialize() {
        super.initialize();

        planar = false;
        if (output == ISEA_PLANE && oAz == 0.0 && aperture == 3 && resolution == 4) {
            if (oLat == ISEA_STD_LAT && oLon == ISEA_STD_LONG) {
                planar = true;
                orientationLat = (E_RAD + F_RAD) / 2;
                orientationLon = ProjectionMath.toRad(-11.25);
            } else if (oLat == Math.PI / 2.0 && oLon == 0) {
                planar = true;
                orientationLat = 0;
                orientationLon = 0;
            }
        }

        if (!planar) {
            return;
        }

        cosOrientationLat = FastStrictTrig.cos(orientationLat);
        sinOrientationLat = FastStrictTrig.sin(orientationLat);

        if (e > 0) {
            double b = a * StrictMath.sqrt(one_es);
            double a2 = a * a;
            double c2 = b * b;
            double log1pe1me = StrictMath.log((1 + e) / (1 - e));
            double s = Math.PI * (2 * a2 + c2 / e * log1pe1me);
            r2 = s / (4 * Math.PI);
            rprime = RPRIME_OVER_R * StrictMath.sqrt(r2);
        } else {
            r2 = a * a;
            rprime = RPRIME_OVER_R * a;
        }

        rprime2X = 2 * rprime;
        rprimeTang = rprime * TANG;
        centerToBase = rprimeTang / 2;
        triWidth = rprimeTang * SQRT3;
        rprime2Tan2g = rprimeTang * rprimeTang;

        yOffsets[0] = -2 * centerToBase;
        yOffsets[1] = -4 * centerToBase;
        yOffsets[2] = -5 * centerToBase;
        yOffsets[3] = -7 * centerToBase;

        xo = 2.5 * triWidth;
        yo = -1.5 * centerToBase;
        sx = 1.0 / triWidth;
        sy = 1.0 / (3 * centerToBase);
    }

    /* =================================================================================
     * Small helpers.
     * ================================================================================= */

    /** {@code isea.cpp:334-340}: {@code asin} with the three exact endpoints snapped. */
    private static double safeArcSin(double t) {
        if (Math.abs(t) < SAFE_ARC_EPSILON) {
            return 0;
        }
        if (Math.abs(t - 1.0) < SAFE_ARC_EPSILON) {
            return Math.PI / 2;
        }
        if (Math.abs(t + 1.0) < SAFE_ARC_EPSILON) {
            return -Math.PI / 2;
        }
        return StrictMath.asin(t);
    }

    /** {@code isea.cpp:342-348}. */
    private static double safeArcCos(double t) {
        if (Math.abs(t) < SAFE_ARC_EPSILON) {
            return Math.PI / 2;
        }
        if (Math.abs(t + 1) < SAFE_ARC_EPSILON) {
            return Math.PI;
        }
        if (Math.abs(t - 1) < SAFE_ARC_EPSILON) {
            return 0;
        }
        return StrictMath.acos(t);
    }

    /**
     * {@code az_adjustment} ({@code isea.cpp:263-268}). Note the deliberate {@code -M_PI} for
     * the bottom row: the comment says the forward "sometimes is returning a negative M_PI",
     * so this is <em>not</em> interchangeable with {@code faceOrientation}'s {@code +180}.
     */
    private static double azAdjustment(int triangle) {
        if ((triangle >= 5 && triangle <= 9) || triangle == 15 || triangle == 16) {
            return Math.PI;
        } else if (triangle >= 17) {
            return -Math.PI;
        }
        return 0;
    }

    /* =================================================================================
     * Forward.
     * ================================================================================= */

    /**
     * {@code isea_snyder_forward} ({@code isea.cpp:351-505}). Returns the face index and
     * writes the in-face plane coordinates into {@code out}.
     */
    private int sneiderForward(double lat, double lon, double[] out) {
        double sinLat = FastStrictTrig.sin(lat);
        double cosLat = FastStrictTrig.cos(lat);

        for (int i = 0; i < NUM_ICOSAHEDRON_FACES; i++) {
            double centerSin = FACE_CENTER_SIN_LAT[i];
            double centerCos = FACE_CENTER_COS_LAT[i];
            double dLon = lon - FACE_CENTER_LON[i];
            double cosLatCosLon = cosLat * FastStrictTrig.cos(dLon);
            double cosZ = centerSin * sinLat + centerCos * cosLatCosLon;

            /* step 1 */
            double z = safeArcCos(cosZ);

            /* not on this triangle */
            if (z > SDC2VOS + 0.000005) {
                continue;
            }

            /* snyder eq 14 */
            double az = StrictMath.atan2(cosLat * FastStrictTrig.sin(dLon),
                    centerCos * sinLat - centerSin * cosLatCosLon);

            /* step 2 */
            az -= azAdjustment(i);
            if (az < 0.0) {
                az += 2.0 * Math.PI;
            }

            int azAdjustMultiples = 0;
            while (az < 0.0) {
                az += DEG120;
                azAdjustMultiples--;
            }
            while (az > DEG120 + Math.ulp(1.0)) {
                az -= DEG120;
                azAdjustMultiples++;
            }

            /* step 3, eq 9 */
            double cosAz = FastStrictTrig.cos(az);
            double sinAz = FastStrictTrig.sin(az);
            double q = StrictMath.atan2(TANG, cosAz + sinAz * COT_THETA);

            /* not in this triangle */
            if (z > q + 0.000005) {
                continue;
            }

            /* step 4: equations 6-8 and 10-12 in order */
            double h = StrictMath.acos(sinAz * SIN_G_COS_SDC2VOS - cosAz * COS_G);
            double ag = az + G_36 + h - DEG180;
            double azPrime = StrictMath.atan2(2.0 * ag,
                    RPRIME_OVER_R * RPRIME_OVER_R * TANG * TANG - 2.0 * ag * COT_THETA);
            double dPrime = RPRIME_OVER_R * TANG
                    / (FastStrictTrig.cos(azPrime) + FastStrictTrig.sin(azPrime) * COT_THETA);
            double f = dPrime / (2.0 * RPRIME_OVER_R * FastStrictTrig.sin(q / 2.0));
            double rho = 2.0 * RPRIME_OVER_R * f * FastStrictTrig.sin(z / 2.0);

            azPrime += DEG120 * azAdjustMultiples;

            out[0] = rho * FastStrictTrig.sin(azPrime);
            out[1] = rho * FastStrictTrig.cos(azPrime);
            return i;
        }

        /*
         * Upstream prints to stderr and calls exit(EXIT_FAILURE) here. See the class javadoc:
         * a library cannot terminate the process, so this is the one place the port cannot be
         * bug-compatible.
         */
        throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                "isea: (" + ProjectionMath.toDeg(lon) + ", " + ProjectionMath.toDeg(lat)
                        + ") is not on any icosahedron triangle");
    }

    /**
     * {@code snyder_ctran} ({@code isea.cpp:519-551}), Snyder's "Map Projections: A Working
     * Manual" p31 equations 5-7 and 5-8b. Writes {lat, lon} into {@code result}.
     */
    private static void snyderCtran(double npLat, double npLon, double ptLat, double ptLon,
            double[] result) {
        double dlambda = ptLon - npLon;
        double cosP = FastStrictTrig.cos(ptLat);
        double sinP = FastStrictTrig.sin(ptLat);
        double cosA = FastStrictTrig.cos(npLat);
        double sinA = FastStrictTrig.sin(npLat);
        double cosDlambda = FastStrictTrig.cos(dlambda);
        double sinDlambda = FastStrictTrig.sin(dlambda);

        double sinPhip = sinA * sinP - cosA * cosP * cosDlambda;
        double lpB = StrictMath.atan2(cosP * sinDlambda, sinA * cosP * cosDlambda + cosA * sinP);
        double lambdap = lpB + npLon;

        /* C fmod truncates toward zero, and so does Java's % on doubles. */
        lambdap = lambdap % (2 * Math.PI);
        while (lambdap > Math.PI) {
            lambdap -= 2 * Math.PI;
        }
        while (lambdap < -Math.PI) {
            lambdap += 2 * Math.PI;
        }

        result[0] = safeArcSin(sinPhip);
        result[1] = lambdap;
    }

    /** {@code isea_ctran} ({@code isea.cpp:553-572}). */
    private static void iseaCtran(double npLat, double npLon, double ptLat, double ptLon,
            double lon0, double[] result) {
        snyderCtran(npLat, npLon + Math.PI, ptLat, ptLon, result);

        double lon = result[1] - (-lon0 + npLon);
        lon = lon % (2 * Math.PI);
        while (lon > Math.PI) {
            lon -= 2 * Math.PI;
        }
        while (lon < -Math.PI) {
            lon += 2 * Math.PI;
        }
        result[1] = lon;
    }

    /** {@code isea_triangle_xy} ({@code isea.cpp:270-303}). */
    private double[] triangleXy(int triangle) {
        triangle %= NUM_ICOSAHEDRON_FACES;

        double x = TABLE_G * ((triangle % 5) - 2) * 2.0;
        if (triangle > 9) {
            x += TABLE_G;
        }

        double y;
        switch (triangle / 5) {
            case 0:
                y = 5.0 * TABLE_H;
                break;
            case 1:
                y = TABLE_H;
                break;
            case 2:
                y = -TABLE_H;
                break;
            case 3:
                y = -5.0 * TABLE_H;
                break;
            default:
                /* Upstream: exit(EXIT_FAILURE). Unreachable - triangle/5 is 0..3 after %20. */
                throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                        "isea: impossible triangle row " + (triangle / 5));
        }
        return new double[] {x * RPRIME_OVER_R, y * RPRIME_OVER_R};
    }

    /** {@code isea_rotate} ({@code isea.cpp:614-630}). */
    private static void rotate(double[] pt, double degrees) {
        double rad = -degrees * Math.PI / 180.0;
        while (rad >= 2.0 * Math.PI) {
            rad -= 2.0 * Math.PI;
        }
        while (rad <= -2.0 * Math.PI) {
            rad += 2.0 * Math.PI;
        }
        double cos = FastStrictTrig.cos(rad);
        double sin = FastStrictTrig.sin(rad);
        double x = pt[0] * cos + pt[1] * sin;
        double y = -pt[0] * sin + pt[1] * cos;
        pt[0] = x;
        pt[1] = y;
    }

    /** {@code isea_tri_plane} ({@code isea.cpp:632-643}). */
    private void triPlane(int tri, double[] pt) {
        if ((tri / 5) % 2 == 1) {
            pt[0] *= -1;
            pt[1] *= -1;
        }
        double[] tc = triangleXy(tri);
        pt[0] += tc[0];
        pt[1] += tc[1];
    }

    /** {@code isea_ptdd} ({@code isea.cpp:646-659}); returns the quad number. */
    private static int ptdd(int tri, double[] pt) {
        boolean downtri = ((tri / 5) % 2 == 1);
        int quadz = (tri % 5) + (tri / 10) * 5 + 1;

        rotate(pt, downtri ? 240.0 : 60.0);
        if (downtri) {
            pt[0] += 0.5;
            pt[1] += COS30;
        }
        return quadz;
    }

    /**
     * {@code hex_xy} ({@code isea.cpp:148-158}) applied to an iso triple. The comment about
     * rounding toward -inf is upstream's; C integer division truncates toward zero and Java's
     * does too, so the {@code x >= 0} split carries over unchanged.
     */
    private static void hexXy(long[] h) {
        if (h[0] >= 0) {
            h[1] = -h[1] - (h[0] + 1) / 2;
        } else {
            h[1] = -h[1] - h[0] / 2;
        }
    }

    /** {@code hex_iso} ({@code isea.cpp:160-173}); fills {@code h[2]}. */
    private static void hexIso(long[] h) {
        if (h[0] >= 0) {
            h[1] = (-h[1] - (h[0] + 1) / 2);
        } else {
            h[1] = (-h[1] - (h[0]) / 2);
        }
        h[2] = -h[0] - h[1];
    }

    /**
     * {@code hexbin2} ({@code isea.cpp:175-225}). The two {@code throw}s upstream are
     * {@code throw "Integer overflow"} / {@code "Division by zero"} on a string literal,
     * caught by {@code isea_s_forward} and turned into a domain error; that is what the
     * {@code +mode=hex +resolution=31} corpus row exercises.
     */
    private void hexbin2(double width, double x, double y, long[] out) {
        x = x / FastStrictTrig.cos(30 * Math.PI / 180.0);
        y = y - x / 2.0;

        if (width == 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea: division by zero (hex width)");
        }
        x /= width;
        y /= width;

        double z = -x - y;

        double rx = Math.floor(x + 0.5);
        long ix = (long) rx;
        double ry = Math.floor(y + 0.5);
        long iy = (long) ry;
        double rz = Math.floor(z + 0.5);
        long iz = (long) rz;

        if (Math.abs((double) ix + iy) > Integer.MAX_VALUE
                || Math.abs((double) ix + iy + iz) > Integer.MAX_VALUE) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea: integer overflow in hexbin2");
        }

        long s = ix + iy + iz;
        if (s != 0) {
            double absDx = Math.abs(rx - x);
            double absDy = Math.abs(ry - y);
            double absDz = Math.abs(rz - z);

            if (absDx >= absDy && absDx >= absDz) {
                ix -= s;
            } else if (absDy >= absDx && absDy >= absDz) {
                iy -= s;
            } else {
                iz -= s;
            }
        }

        long[] h = new long[] {ix, iy, iz};
        hexXy(h);
        out[0] = h[0];
        out[1] = h[1];
    }

    /** {@code isea_dddi_ap3odd} ({@code isea.cpp:678-758}); returns the quad. */
    private int dddiAp3odd(int quadz, double[] pt, double[] di) {
        double sidelength = (StrictMath.pow(2.0, resolution) + 1.0) / 2.0;
        double hexwidth = FastStrictTrig.cos(Math.PI / 6.0) / sidelength;
        long maxcoord = Math.round(sidelength * 2.0);

        long[] xy = new long[2];
        hexbin2(hexwidth, pt[0], pt[1], xy);
        long[] h = new long[] {xy[0], xy[1], 0};
        hexIso(h);

        long d = h[0] - h[2];
        long i = h[0] + h[1] + h[1];

        if (quadz <= 5) {
            if (d == 0 && i == maxcoord) {
                quadz = 0;
                d = 0;
                i = 0;
            } else if (i == maxcoord) {
                quadz += 1;
                if (quadz == 6) {
                    quadz = 1;
                }
                i = maxcoord - d;
                d = 0;
            } else if (d == maxcoord) {
                quadz += 5;
                d = 0;
            }
        } else {
            if (i == 0 && d == maxcoord) {
                quadz = 11;
                d = 0;
                i = 0;
            } else if (d == maxcoord) {
                quadz += 1;
                if (quadz == 11) {
                    quadz = 6;
                }
                d = maxcoord - i;
                i = 0;
            } else if (i == maxcoord) {
                quadz = (quadz - 4) % 5;
                i = 0;
            }
        }

        di[0] = d;
        di[1] = i;
        return quadz;
    }

    /** {@code isea_dddi} ({@code isea.cpp:760-832}); returns the quad. */
    private int dddi(int quadz, double[] pt, double[] di) {
        if (aperture == 3 && resolution % 2 != 0) {
            return dddiAp3odd(quadz, pt, di);
        }

        long sidelength;
        if (aperture > 0) {
            double sidelengthDouble = StrictMath.pow(aperture, resolution / 2.0);
            if (Math.abs(sidelengthDouble) > Integer.MAX_VALUE) {
                throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                        "isea: integer overflow computing side length");
            }
            sidelength = Math.round(sidelengthDouble);
        } else {
            sidelength = resolution;
        }

        if (sidelength == 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea: division by zero (side length)");
        }
        double hexwidth = 1.0 / sidelength;

        double[] v = new double[] {pt[0], pt[1]};
        rotate(v, -30.0);
        long[] xy = new long[2];
        hexbin2(hexwidth, v[0], v[1], xy);
        long[] h = new long[] {xy[0], xy[1], 0};
        hexIso(h);

        if (quadz <= 5) {
            if (h[0] == 0 && h[2] == -sidelength) {
                quadz = 0;
                h[2] = 0;
                h[1] = 0;
                h[0] = 0;
            } else if (h[2] == -sidelength) {
                quadz = quadz + 1;
                if (quadz == 6) {
                    quadz = 1;
                }
                h[1] = sidelength - h[0];
                h[2] = h[0] - sidelength;
                h[0] = 0;
            } else if (h[0] == sidelength) {
                quadz += 5;
                h[1] = -h[2];
                h[0] = 0;
            }
        } else {
            if (h[2] == 0 && h[0] == sidelength) {
                quadz = 11;
                h[0] = 0;
                h[1] = 0;
                h[2] = 0;
            } else if (h[0] == sidelength) {
                quadz = quadz + 1;
                if (quadz == 11) {
                    quadz = 6;
                }
                h[0] = h[1] + sidelength;
                h[1] = 0;
                h[2] = -h[0];
            } else if (h[1] == -sidelength) {
                quadz -= 4;
                h[1] = 0;
                h[2] = -h[0];
            }
        }

        di[0] = h[0];
        di[1] = -h[2];
        return quadz;
    }

    /** {@code isea_ptdi} ({@code isea.cpp:834-843}). */
    private int ptdi(int tri, double[] pt, double[] di) {
        double[] v = new double[] {pt[0], pt[1]};
        int quadz = ptdd(tri, v);
        return dddi(quadz, v, di);
    }

    /**
     * {@code isea_hex} ({@code isea.cpp:850-873}), minus the {@code #ifdef FIXME} tail that
     * upstream does not compile.
     */
    private void hex(int tri, double[] pt, double[] out) {
        double[] v = new double[2];
        int quadz = ptdi(tri, pt, v);

        if (v[0] < (Integer.MIN_VALUE >> 4) || v[0] > (Integer.MAX_VALUE >> 4)) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea: invalid shift building a hex address");
        }
        out[0] = ((int) v[0] * 16) + quadz;
        out[1] = v[1];
    }

    /** {@code isea_forward} ({@code isea.cpp:934-968}). */
    private void iseaForward(double lat, double lon, double[] out) {
        double[] i = new double[2];
        iseaCtran(oLat, oLon, lat, lon, oAz, i);

        int tri = sneiderForward(i[0], i[1], out);

        if (output == ISEA_PLANE) {
            triPlane(tri, out);
            return;
        }

        /* convert to isea standard triangle size */
        out[0] *= ISEA_SCALE;
        out[1] *= ISEA_SCALE;
        out[0] += 0.5;
        out[1] += 2.0 * .14433756729740644112;

        double[] coord = new double[2];
        switch (output) {
            case ISEA_Q2DD:
                ptdd(tri, out);
                break;
            case ISEA_Q2DI:
                ptdi(tri, out, coord);
                out[0] = coord[0];
                out[1] = coord[1];
                break;
            case ISEA_HEX:
                hex(tri, out, coord);
                out[0] = coord[0];
                out[1] = coord[1];
                break;
            default:
                break;
        }
    }

    /**
     * {@code isea_s_forward} ({@code isea.cpp:974-993}). Returns the dimensionless plane
     * coordinate; {@link Projection#projectRadians} then applies {@code a} and the false
     * origin, as {@code fwd_finalize} does.
     */
    @Override
    public ProjCoordinate project(double lam, double phi, ProjCoordinate dst) {
        double[] out = new double[2];
        iseaForward(phi, lam, out);
        dst.x = out[0];
        dst.y = out[1];
        return dst;
    }

    /* =================================================================================
     * Inverse.
     * ================================================================================= */

    /** {@code ISEAPlanarProjection::faceOrientation} ({@code isea.cpp:1286-1289}). */
    private static double faceOrientation(int face) {
        return (face <= 4 || (10 <= face && face <= 14)) ? 0 : DTR_180;
    }

    /** {@code ISEAPlanarProjection::revertOrientation} ({@code isea.cpp:1270-1284}). */
    private void revertOrientation(double cLat, double cLon, double[] r) {
        double lon = (cLat < ProjectionMath.toRad(-90) + PRECISION_PER_DEFINITION
                || cLat > ProjectionMath.toRad(90) - PRECISION_PER_DEFINITION) ? 0 : cLon;
        if (orientationLat != 0.0 || orientationLon != 0.0) {
            double sinLat = FastStrictTrig.sin(cLat);
            double cosLat = FastStrictTrig.cos(cLat);
            double sinLon = FastStrictTrig.sin(lon);
            double cosLon = FastStrictTrig.cos(lon);
            double cosLonCosLat = cosLon * cosLat;
            r[0] = StrictMath.asin(sinLat * cosOrientationLat - cosLonCosLat * sinOrientationLat);
            r[1] = StrictMath.atan2(sinLon * cosLat,
                    cosLonCosLat * cosOrientationLat + sinLat * sinOrientationLat)
                    - orientationLon;
        } else {
            r[0] = cLat;
            r[1] = lon;
        }
    }

    /**
     * {@code ISEAPlanarProjection::icosahedronToSphere} ({@code isea.cpp:1199-1268}).
     *
     * <p>The Newton iteration solves Snyder's equation 7 for the spherical azimuth. Upstream
     * has no iteration cap; the loop is left uncapped here too, because adding one would
     * change behaviour on any input where upstream diverges rather than merely being slow.
     */
    private boolean icosahedronToSphere(int face, double cx, double cy, double[] r) {
        if (face < 0 || face >= NUM_ICOSAHEDRON_FACES) {
            return false;
        }

        double az = StrictMath.atan2(cx, cy);
        double rho = StrictMath.sqrt(cx * cx + cy * cy);
        double azAdjustment = faceOrientation(face);

        az += azAdjustment;
        while (az < 0) {
            azAdjustment += AZ_MAX;
            az += AZ_MAX;
        }
        while (az > AZ_MAX) {
            azAdjustment -= AZ_MAX;
            az -= AZ_MAX;
        }

        double sinAz = FastStrictTrig.sin(az);
        double cosAz = FastStrictTrig.cos(az);
        double cotAz = cosAz / sinAz;
        double area = rprime2Tan2g / (2 * (cotAz + COT_THETA));
        double deltaAz = 10 * PRECISION;
        double degAreaOverR2Plus180Minus36 = area / r2 - WEST_VERTEX_LON;
        double azEarth = az;

        while (Math.abs(deltaAz) > PRECISION) {
            double sinAzEarth = FastStrictTrig.sin(azEarth);
            double cosAzEarth = FastStrictTrig.cos(azEarth);
            double h = StrictMath.acos(sinAzEarth * SIN_G_COS_SDC2VOS - cosAzEarth * COS_G);
            double fAzEarth = degAreaOverR2Plus180Minus36 - h - azEarth;
            double f2AzEarth =
                    (cosAzEarth * SIN_G_COS_SDC2VOS + sinAzEarth * COS_G) / FastStrictTrig.sin(h)
                            - 1;
            deltaAz = -fAzEarth / f2AzEarth;
            azEarth += deltaAz;
        }

        double sinAzEarth = FastStrictTrig.sin(azEarth);
        double cosAzEarth = FastStrictTrig.cos(azEarth);
        double q = StrictMath.atan2(TANG, (cosAzEarth + sinAzEarth * COT_THETA));
        double d = rprimeTang / (cosAz + sinAz * COT_THETA);
        double f = d / (rprime2X * FastStrictTrig.sin(q / 2));
        double z = 2 * StrictMath.asin(rho / (rprime2X * f));

        azEarth -= azAdjustment;

        double sinLat0 = FACE_CENTER_SIN_LAT[face];
        double cosLat0 = FACE_CENTER_COS_LAT[face];
        double sinZ = FastStrictTrig.sin(z);
        double cosZ = FastStrictTrig.cos(z);
        double cosLat0SinZ = cosLat0 * sinZ;
        double latSin = sinLat0 * cosZ + cosLat0SinZ * FastStrictTrig.cos(azEarth);
        double lat = safeArcSin(latSin);
        double lon = FACE_CENTER_LON[face]
                + StrictMath.atan2(FastStrictTrig.sin(azEarth) * cosLat0SinZ,
                        cosZ - sinLat0 * FastStrictTrig.sin(lat));

        revertOrientation(lat, lon, r);
        return true;
    }

    /** {@code ISEAPlanarProjection::cartesianToGeo} ({@code isea.cpp:1105-1196}). */
    private boolean cartesianToGeo(double px, double py, double[] result) {
        final double epsilon = 1E-11;
        final double sr = -SIN60;
        final double cr = 0.5;
        final double shearX = 1.0 / SQRT3;

        int face = 0;

        if (px < 0 || (px < triWidth / 2 && py < 0 && py * cr < px * sr)) {
            px += 5 * triWidth;
        }

        double yp = -(px * sr + py * cr);
        double x = (px * cr - py * sr + yp * shearX) * sx;
        double y = yp * sy;

        if (x < 0 || (y > x && x < 5 - epsilon)) {
            x += epsilon;
        } else if (x > 5 || (y < x && x > 0 + epsilon)) {
            x -= epsilon;
        }

        if (y < 0 || (x > y && y < 6 - epsilon)) {
            y += epsilon;
        } else if (y > 6 || (x < y && y > 0 + epsilon)) {
            y -= epsilon;
        }

        if (x >= 0 && x <= 5 && y >= 0 && y <= 6) {
            int ix = Math.max(0, Math.min(4, (int) x));
            int iy = Math.max(0, Math.min(5, (int) y));

            if (iy == ix || iy == ix + 1) {
                int rhombus = ix + iy;
                boolean top = x - ix > y - iy;
                face = -1;

                switch (rhombus) {
                    case 0: face = top ? 0 : 5; break;
                    case 2: face = top ? 1 : 6; break;
                    case 4: face = top ? 2 : 7; break;
                    case 6: face = top ? 3 : 8; break;
                    case 8: face = top ? 4 : 9; break;
                    case 1: face = top ? 10 : 15; break;
                    case 3: face = top ? 11 : 16; break;
                    case 5: face = top ? 12 : 17; break;
                    case 7: face = top ? 13 : 18; break;
                    case 9: face = top ? 14 : 19; break;
                    default: break;
                }
                face++;
            }
        }

        if (face == 0) {
            return false;
        }

        int fy = (face - 1) / 5;
        int fx = (face - 1) - 5 * fy;
        double rx = px - (2 * fx + fy / 2 + 1) * triWidth / 2;
        double ry = py - (yOffsets[fy] + 3 * centerToBase);

        double[] dst = new double[2];
        if (!icosahedronToSphere(face - 1, rx, ry, dst)) {
            return false;
        }

        double lat = dst[0];
        double lon = dst[1];
        if (lon < -Math.PI - epsilon) {
            lon += 2 * Math.PI;
        } else if (lon > Math.PI + epsilon) {
            lon -= 2 * Math.PI;
        }
        result[0] = lat;
        result[1] = lon;
        return true;
    }

    /**
     * {@code isea_s_inverse} ({@code isea.cpp:1357-1377}).
     *
     * <p>{@code x} and {@code y} arrive divided by {@code a} - {@code inv_prepare} multiplies
     * by {@code P->ra} and Proj4J multiplies by {@code totalScaleReciprocal} in the same
     * place - and upstream immediately multiplies them back by {@code P->a}. That looks like
     * a wasted round trip and is kept because it is exactly what upstream computes: the same
     * two operations in the same order give the same double.
     *
     * <p>{@code xo}/{@code yo} shift the origin because {@code +proj=isea}'s natural origin
     * differs from OGC:1534's.
     */
    @Override
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        if (!planar) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea: no inverse for this configuration - upstream installs one only for "
                            + "+mode=plane +aperture=3 +resolution=4 +azi=0 with +orient=isea or "
                            + "+orient=pole");
        }

        double[] result = new double[2];
        if (!cartesianToGeo(x * a + xo, y * a + yo, result)) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "isea inverse: (" + x * a + ", " + y * a + ") lies outside the icosahedron net");
        }
        dst.x = result[1];
        dst.y = result[0];
        return dst;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    /**
     * Orientation, grid and output mode all change the numbers, so all belong in equality;
     * the base class already covers the ellipsoid and the affine.
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (!(that instanceof IcosahedralSnyderEqualAreaProjection) || !super.equals(that)) {
            return false;
        }
        IcosahedralSnyderEqualAreaProjection other = (IcosahedralSnyderEqualAreaProjection) that;
        return Double.compare(oLat, other.oLat) == 0
                && Double.compare(oLon, other.oLon) == 0
                && Double.compare(oAz, other.oAz) == 0
                && aperture == other.aperture
                && resolution == other.resolution
                && output == other.output;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Double.valueOf(oLat).hashCode();
        result = 31 * result + Double.valueOf(oLon).hashCode();
        result = 31 * result + Double.valueOf(oAz).hashCode();
        result = 31 * result + aperture;
        result = 31 * result + resolution;
        result = 31 * result + output;
        return result;
    }

    @Override
    public String toString() {
        return "Icosahedral Snyder Equal Area";
    }
}
