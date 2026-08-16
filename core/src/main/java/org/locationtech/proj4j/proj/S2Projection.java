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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * S2, {@code +proj=s2} &mdash; {@code 9.8.1:src/projections/s2.cpp}.
 *
 * <p>Google's S2 cube projection. Like {@link QuadrilateralizedSphericalCubeProjection}
 * it wraps the sphere onto a cube and draws one face, chosen once in {@link #initialize()}
 * from the projection centre alone. Unlike qsc it emits <em>ST</em> coordinates, which are
 * dimensionless and, for the three real {@code +UVtoST} choices, lie in {@code [0, 1]} on
 * the face of definition &mdash; the face centre is {@code (0.5, 0.5)} and its corners are
 * {@code (0,0)} and {@code (1,1)}.
 *
 * <h2>Face selection ({@code s2.cpp:440-451})</h2>
 *
 * <table>
 * <caption>{@code PJ_PROJECTION(s2)}</caption>
 * <tr><th>condition</th><th>face</th></tr>
 * <tr><td>{@code phi0 >= pi/2 - pi/8}</td><td>TOP</td></tr>
 * <tr><td>{@code phi0 <= -(pi/2 - pi/8)}</td><td>BOTTOM</td></tr>
 * <tr><td>{@code |lam0| <= pi/4}</td><td>FRONT</td></tr>
 * <tr><td>{@code |lam0| <= 3pi/4}</td><td>RIGHT if {@code lam0 > 0}, else LEFT</td></tr>
 * <tr><td>otherwise</td><td>BACK</td></tr>
 * </table>
 *
 * <p><b>The face numbering is not qsc's.</b> s2 orders them FRONT, RIGHT, TOP, BACK, LEFT,
 * BOTTOM ({@code s2.cpp:66-73}); qsc orders them FRONT, RIGHT, BACK, LEFT, TOP, BOTTOM
 * ({@code qsc.cpp:50-57}). The two files look alike and the constants are not
 * interchangeable.
 *
 * <h2>Two things this projection does that no other proj4j projection does</h2>
 *
 * <p><b>1. The central meridian selects the face and is then <em>not</em> subtracted.</b>
 * {@code s2.cpp:434} sets {@code P->from_greenwich = -P->lam0}, and {@code fwd.cpp:108}
 * computes {@code (lam - from_greenwich) - lam0}, i.e. {@code (lam + lam0) - lam0}. The two
 * cancel: the kernel is handed the raw longitude, and {@code +lon_0} survives only as the
 * face it picked. The inverse cancels the same way at {@code inv.cpp:113}. A side effect
 * upstream neither documents nor avoids is that {@code +pm} becomes <b>inert</b> &mdash;
 * {@code from_greenwich} is where the prime meridian would otherwise have been stored, and
 * the assignment overwrites it.
 *
 * <p>{@link Projection#projectRadians} subtracts {@code offsetFromGreenwich} and
 * {@code projectionLongitude} before calling {@link #project}, and
 * {@link Projection#inverseProjectRadians} adds them back afterwards, so this class undoes
 * both at the kernel boundary using {@link #longitudeUndo}. Keeping the base fields
 * populated (rather than zeroing them) is deliberate: {@code +lon_0} still has to appear in
 * anything that renders the projection back out as a proj-string, and {@link #initialize()}
 * has to be able to run twice.
 *
 * <p>The one divergence, deliberate: PROJ actually evaluates {@code (lam + lam0) - lam0} in
 * double precision and then {@code adjlon}s it, where this adds {@code lam0} back to a value
 * from which it was just subtracted. Both are the identity in exact arithmetic and differ by
 * at most an ulp of longitude; ST output is order 1, the corpus tolerance is 0.1 mm, and the
 * gap is around 1e-16.
 *
 * <p><b>2. The output is not scaled by the semi-major axis.</b> {@code s2.cpp:432-433} sets
 * {@code P->right = PJ_IO_UNITS_PROJECTED}, so {@code fwd.cpp:147-149} applies
 * {@code fr_meter} and the false easting but <em>not</em> the {@code coo.xy.x *= P->a} that
 * {@code PJ_IO_UNITS_CLASSIC} applies at {@code fwd.cpp:140-142}. proj4j models CLASSIC by
 * folding {@code a} into {@code totalScale} ({@code Projection.initialize}), so the lever
 * here is to set {@code a = 1.0} before {@code super.initialize()} derives
 * {@code totalScale} and its reciprocal from it. The genuine semi-major axis is read from
 * {@link #getEllipsoid()}, which this class never modifies, so the second
 * {@code initialize()} recomputes rather than compounding.
 *
 * <h2>Far-side points are not rejected ({@code s2.cpp:363})</h2>
 *
 * <p>The forward calls {@code ValidFaceXYZtoUV} directly on the chosen face with no check
 * that the point is on that half of the sphere. {@code FaceXYZtoUV} &mdash; the guarded
 * variant that returns false for a point behind the face &mdash; exists in the file and is
 * never called, as are {@code GetFace}, {@code LargestAbsComponent}, {@code XYZtoFaceUV} and
 * {@code FaceUVtoXYZ}. So a point on the far side divides by a coordinate of the wrong sign
 * and lands somewhere plausible-looking and meaningless, and PROJ 9.8.1 reports no error.
 * That is upstream behaviour and it is reproduced here; the dead helpers are not ported.
 *
 * <h2>Parameters</h2>
 *
 * <p>{@code +UVtoST} ({@code pj_param}'s {@code s} sigil, {@code s2.cpp:413}) is the only key
 * beyond the ones the generic parser already supplies. It is one of {@code linear},
 * {@code quadratic}, {@code tangent}, {@code none}, defaulting to {@code quadratic}; anything
 * else is refused with {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}
 * ({@code s2.cpp:417-427}), which is {@link ErrorCause#INVALID_PARAM_VALUE} here.
 *
 * @see <a href="https://github.com/google/s2geometry/blob/0c4c460/src/s2/s2coords.h">s2coords.h</a>
 */
public class S2Projection extends Projection {

    private static final long serialVersionUID = 6413200671245538033L;

    /** {@code s2.cpp:66-73}. Not qsc's numbering; see the class comment. */
    private static final int FACE_FRONT = 0;
    private static final int FACE_RIGHT = 1;
    private static final int FACE_TOP = 2;
    private static final int FACE_BACK = 3;
    private static final int FACE_LEFT = 4;
    private static final int FACE_BOTTOM = 5;

    /** {@code enum S2ProjectionType}, {@code s2.cpp:76}. */
    private static final int LINEAR = 0;
    private static final int QUADRATIC = 1;
    private static final int TANGENT = 2;
    private static final int NO_UV_TO_ST = 3;

    /**
     * {@code M_1_PI} from {@code math.h}, written as the literal rather than as
     * {@code 1.0 / Math.PI} so the value is upstream's regardless of how the quotient
     * happens to round.
     */
    private static final double M_1_PI = 0.31830988618379067154;

    /**
     * {@code 1 / 2^53}, the Tangent nudge at {@code s2.cpp:139-141}. Upstream writes it as
     * {@code 1.0 / (double)((int64_t)1 << 53)}; the shift is exact in {@code long} and the
     * conversion is exact in {@code double}, so the constant is exactly 2^-53.
     */
    private static final double TANGENT_NUDGE = 1.0 / (double) (1L << 53);

    /** The cube face, one of the six {@code FACE_*} constants. {@code Q->face}. */
    private int face = FACE_FRONT;

    /** {@code Q->UVtoST}. Upstream's default is Quadratic ({@code s2.cpp:426}). */
    private int uvToSt = QUADRATIC;

    /**
     * {@code offsetFromGreenwich + projectionLongitude}, the shift
     * {@link Projection#projectRadians} removes and this class must put back, to reproduce
     * {@code P->from_greenwich = -P->lam0}. See the class comment.
     */
    private double longitudeUndo = 0.0;

    /** {@code Q->a_squared}. Zero on a sphere, where it is never read. */
    private double aSquared = 0.0;

    /** {@code P->b}, the genuine semi-minor axis &mdash; not the rescaled {@code a}. */
    private double b = 0.0;

    /** {@code Q->one_minus_f}. Zero on a sphere. */
    private double oneMinusF = 0.0;

    /** {@code Q->one_minus_f_squared}. Zero on a sphere. */
    private double oneMinusFSquared = 0.0;

    /**
     * {@code +UVtoST}, by name, as {@code s2.cpp:413-428} reads it.
     *
     * @param name one of {@code linear}, {@code quadratic}, {@code tangent}, {@code none}
     * @throws InvalidValueException for any other value, which is upstream's
     *     {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. Note the lookup is
     *     {@code std::map::at} on the exact string: it is case-sensitive, and
     *     {@code +UVtoST=Linear} is refused.
     */
    public void setUVtoST(String name) {
        if ("linear".equals(name)) {
            uvToSt = LINEAR;
        } else if ("quadratic".equals(name)) {
            uvToSt = QUADRATIC;
        } else if ("tangent".equals(name)) {
            uvToSt = TANGENT;
        } else if ("none".equals(name)) {
            uvToSt = NO_UV_TO_ST;
        } else {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "+proj=s2: Invalid value for s2 parameter: should be linear, quadratic,"
                            + " tangent, or none. Got +UVtoST=" + name + " (s2.cpp:417-427)");
        }
    }

    /**
     * The {@code +UVtoST} in force, by upstream's name.
     *
     * @return one of {@code linear}, {@code quadratic}, {@code tangent}, {@code none}
     */
    public String getUVtoST() {
        switch (uvToSt) {
            case LINEAR:
                return "linear";
            case TANGENT:
                return "tangent";
            case NO_UV_TO_ST:
                return "none";
            default:
                return "quadratic";
        }
    }

    /**
     * {@code UVtoST}, {@code s2.cpp:148-169}. Cube-face {@code u} to unit-square {@code s}.
     *
     * <p>The {@code volatile} on upstream's Tangent temporary is an x87 excess-precision
     * guard; Java arithmetic is already {@code double}-strict, so the local here has the
     * same effect.
     *
     * @param u the face coordinate
     * @return the square coordinate
     */
    private double uvToSt(double u) {
        switch (uvToSt) {
            case LINEAR:
                return 0.5 * (u + 1);
            case QUADRATIC:
                if (u >= 0) {
                    return 0.5 * StrictMath.sqrt(1 + 3 * u);
                }
                return 1 - 0.5 * StrictMath.sqrt(1 - 3 * u);
            case TANGENT: {
                final double at = StrictMath.atan(u);
                return (2 * M_1_PI) * (at + ProjectionMath.FORTPI);
            }
            default:
                return u;
        }
    }

    /**
     * {@code STtoUV}, {@code s2.cpp:126-146}. The inverse of {@link #uvToSt(double)}.
     *
     * <p>The Tangent branch is not simply {@code tan(pi/2 * s - pi/4)}: upstream adds
     * {@code s * 2^-53} afterwards, because {@code tan(M_PI_4)} is a shade under 1 and the
     * unit square would otherwise fail to reach the face edge. The comment at
     * {@code s2.cpp:120-125} explains why that is a property of pi/4 and not a defect in
     * {@code tan}.
     *
     * @param s the square coordinate
     * @return the face coordinate
     */
    private double stToUv(double s) {
        switch (uvToSt) {
            case LINEAR:
                return 2 * s - 1;
            case QUADRATIC:
                if (s >= 0.5) {
                    return (1 / 3.) * (4 * s * s - 1);
                }
                return (1 / 3.) * (1 - 4 * (1 - s) * (1 - s));
            case TANGENT: {
                final double t = FastStrictTrig.tan(ProjectionMath.HALFPI * s - ProjectionMath.FORTPI);
                return t + TANGENT_NUDGE * t;
            }
            default:
                return s;
        }
    }

    /**
     * {@code s2_forward}, {@code s2.cpp:337-372}.
     *
     * <p>{@code ValidFaceXYZtoUV} ({@code s2.cpp:190-217}) is inlined into the six-way
     * switch below rather than kept as a helper: upstream returns its two results through
     * pointers, and in Java that is either a per-coordinate allocation or a field write on a
     * shared object, both of which the hot path forbids.
     */
    @Override
    protected ProjCoordinate project(double lam, double lpphi, ProjCoordinate dst) {
        // Undo Projection.projectRadians' central-meridian and prime-meridian subtraction:
        // s2.cpp:434 cancels both. See the class comment.
        final double lon = lam + longitudeUndo;

        /* Convert the geodetic latitude to a geocentric latitude -- the [LK12] shift from
         * the ellipsoid to the sphere, s2.cpp:342-347. */
        final double lat;
        if (es != 0.0) {
            lat = StrictMath.atan(oneMinusFSquared * FastStrictTrig.tan(lpphi));
        } else {
            lat = lpphi;
        }

        final double sinlat = FastStrictTrig.sin(lat);
        final double coslat = FastStrictTrig.cos(lat);
        final double sinlon = FastStrictTrig.sin(lon);
        final double coslon = FastStrictTrig.cos(lon);
        final double px = coslat * coslon;
        final double py = coslat * sinlon;
        final double pz = sinlat;

        final double u;
        final double v;
        switch (face) {
            case FACE_FRONT:
                u = py / px;
                v = pz / px;
                break;
            case FACE_RIGHT:
                u = -px / py;
                v = pz / py;
                break;
            case FACE_TOP:
                u = -px / pz;
                v = -py / pz;
                break;
            case FACE_BACK:
                u = pz / px;
                v = py / px;
                break;
            case FACE_LEFT:
                u = pz / py;
                v = -px / py;
                break;
            default:
                u = -py / pz;
                v = -px / pz;
                break;
        }

        dst.x = uvToSt(u);
        dst.y = uvToSt(v);
        return dst;
    }

    /**
     * {@code s2_inverse}, {@code s2.cpp:374-407}.
     *
     * <p>{@code UVtoSphereXYZ} ({@code s2.cpp:281-320}) is inlined for the same reason
     * {@code ValidFaceXYZtoUV} is: it returns three values through a struct pointer.
     * Upstream's return value is {@code true} unconditionally and is discarded at the call
     * site, so there is no rejection to reproduce.
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        final double u = stToUv(x);
        final double v = stToUv(y);

        final double major = 1 / StrictMath.sqrt(1 + u * u + v * v);
        final double minor1 = u * major;
        final double minor2 = v * major;

        final double q;
        final double r;
        final double s;
        switch (face) {
            case FACE_FRONT:
                q = major;
                r = minor1;
                s = minor2;
                break;
            case FACE_RIGHT:
                q = -minor1;
                r = major;
                s = minor2;
                break;
            case FACE_TOP:
                q = -minor1;
                r = -minor2;
                s = major;
                break;
            case FACE_BACK:
                q = -major;
                r = -minor2;
                s = -minor1;
                break;
            case FACE_LEFT:
                q = minor2;
                r = -major;
                s = -minor1;
                break;
            default:
                q = minor2;
                r = minor1;
                s = -major;
                break;
        }

        // s2.cpp:394-395. Plain acos, as qsc.cpp:359 uses: upstream lets a |s| > 1 become
        // NaN, and Projection.inverseProjectRadians' postcondition turns that into a
        // NUMERICAL_FAILURE rather than a plausible coordinate. |s| <= 1 by construction
        // here anyway, since major, minor1 and minor2 are the components of a unit vector.
        double phi = StrictMath.acos(-s) - ProjectionMath.HALFPI;
        final double lam = StrictMath.atan2(r, q);

        /* The [LK12] shift back from the sphere to the ellipsoid, s2.cpp:399-406. */
        if (es != 0.0) {
            final boolean invertSign = phi < 0.0;
            final double tanphi = FastStrictTrig.tan(phi);
            final double xa = b / StrictMath.sqrt(tanphi * tanphi + oneMinusFSquared);
            phi = StrictMath.atan(StrictMath.sqrt(aSquared - xa * xa) / (oneMinusF * xa));
            if (invertSign) {
                phi = -phi;
            }
        }

        dst.y = phi;
        // Pre-cancel the addition Projection.inverseProjectRadians is about to make.
        dst.x = lam - longitudeUndo;
        return dst;
    }

    /**
     * {@code PJ_PROJECTION(s2)}, {@code s2.cpp:409-460}: pick the cube face, then derive the
     * ellipsoid constants.
     *
     * <p>Runs twice, so nothing here may read a field this method writes. The face comes
     * from {@code projectionLatitude} and {@code projectionLongitude}, the longitude undo
     * from those and the prime meridian, and the ellipsoid constants from
     * {@link #getEllipsoid()} and {@code es} &mdash; none of which this class modifies. The
     * one field it does modify, {@code a}, is therefore never used as an input.
     */
    @Override
    public void initialize() {
        // s2.cpp:434. Both terms are what Projection.projectRadians will subtract.
        longitudeUndo = getPrimeMeridian().getOffsetFromGreenwich() + projectionLongitude;

        // s2.cpp:440-451. Note the pole test is pi/2 - pi/8, i.e. 67.5 degrees, not 45.
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

        // s2.cpp:454-458, but read from the ellipsoid rather than from `a`, which the next
        // statement clobbers.
        if (es != 0.0) {
            final double semiMajor = getEllipsoid().equatorRadius;
            aSquared = semiMajor * semiMajor;
            b = semiMajor * StrictMath.sqrt(1.0 - es);
            oneMinusF = 1.0 - (semiMajor - b) / semiMajor;
            oneMinusFSquared = oneMinusF * oneMinusF;
        } else {
            // calloc leaves these zero upstream, and the spherical kernel never reads them.
            aSquared = 0.0;
            b = 0.0;
            oneMinusF = 0.0;
            oneMinusFSquared = 0.0;
        }

        // s2.cpp:433, PJ_IO_UNITS_PROJECTED: the kernel result is NOT multiplied by the
        // semi-major axis. Must precede super.initialize(), which derives totalScale and
        // totalScaleReciprocal from `a`.
        a = 1.0;
        super.initialize();
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "S2";
    }
}
