/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

/**
 * The 3- or 7-parameter Helmert transformation PROJ builds behind a
 * {@code +towgs84}, i.e. {@code +proj=helmert +exact <params> +convention=position_vector}
 * ({@code 9.8.1:src/create.cpp:145-156}, {@code src/transformations/helmert.cpp}).
 *
 * <h2>Two things here are easy to get wrong</h2>
 *
 * <p><b>{@code +exact}.</b> The cs2cs-emulation helper is built with {@code exact},
 * so the rotation matrix is the full trigonometric one, <em>not</em> the linearised
 * small-angle approximation that {@code datum.Datum.transformFromGeocentricToWgs84}
 * uses. For the rotation magnitudes in the EPSG init file the two differ by well
 * under a millimetre, so this is not why GIGS passes or fails — but it is a real
 * difference, and reproducing upstream costs three cosines.
 *
 * <p><b>{@code position_vector}.</b> PROJ derives the matrix in the
 * <em>coordinate frame</em> convention and then transposes it, and hard-errors if a
 * {@code +towgs84} is combined with anything else
 * ({@code helmert.cpp:540-549}: "towgs84 should only be used with
 * convention=position_vector"). Getting the convention backwards flips the sign of
 * every rotation, which is invisible at the equator and on the prime meridian —
 * precisely the region test fixtures tend to live in.
 *
 * <h2>Parameter representation</h2>
 *
 * <p>The array handed in is PROJ's {@code P->datum_params} <em>after</em>
 * {@code pj_datum_set} has normalised it: translations in metres, rotations in
 * <b>radians</b>, and element 6 holding {@code 1 + s/1e6} rather than {@code s} in
 * ppm. That is also exactly what proj4j's {@code datum.Datum} stores, so a
 * {@code +datum=} lookup and a {@code +towgs84=} parse can share one path.
 *
 * <h2>Two constructors, two callers</h2>
 *
 * <p>The {@code double[]} constructor is the {@code +towgs84} one described above, and
 * is fixed at {@code exact} and {@code position_vector} because that is the string
 * {@code create.cpp} builds. The general constructor is what {@link HelmertOperator}
 * uses for a user-written {@code +proj=helmert} or {@code +proj=molobadekas}, where all
 * four of {@code exact}, the convention, the reference point and {@code no_rotation} are
 * the definition's business. The two share one matrix builder and one pair of
 * transforms, because upstream shares one {@code struct} and one pair of functions.
 *
 * <p>Immutable and thread-safe. A rate-bearing {@code +proj=helmert} builds a fresh one
 * per coordinate rather than mutating a shared one — see {@link HelmertOperator}.
 */
final class HelmertConversion {

    private final double tx;
    private final double ty;
    private final double tz;
    private final double refpX;
    private final double refpY;
    private final double refpZ;
    private final double scale;
    private final boolean pureTranslation;
    private final double r00;
    private final double r01;
    private final double r02;
    private final double r10;
    private final double r11;
    private final double r12;
    private final double r20;
    private final double r21;
    private final double r22;

    /**
     * @param datumParams 3 or 7 elements in {@code pj_datum_set} form: {@code dx, dy, dz}
     *                    in metres, then {@code rx, ry, rz} in radians and
     *                    {@code 1 + s/1e6}
     */
    HelmertConversion(final double[] datumParams) {
        this(datumParams[0], datumParams[1], datumParams[2],
                at(datumParams, 3), at(datumParams, 4), at(datumParams, 5),
                undoAbsoluteScale(at(datumParams, 6)),
                true, true,
                0.0, 0.0, 0.0,
                at(datumParams, 3) == 0.0 && at(datumParams, 4) == 0.0
                        && at(datumParams, 5) == 0.0);
    }

    private static double at(final double[] a, final int i) {
        return a.length > i ? a[i] : 0.0;
    }

    /**
     * {@code helmert.cpp:597-601} undoes {@code pj_datum_set}'s conversion to absolute
     * scale so that {@code helmert_forward_3d:423} can redo it. Reproduced literally so
     * the rounding matches.
     *
     * @param absoluteScale {@code datum_params[6]}, i.e. {@code 1 + s/1e6} or exactly 0
     * @return {@code s} in parts per million
     */
    private static double undoAbsoluteScale(final double absoluteScale) {
        return absoluteScale == 0.0 ? 0.0 : (absoluteScale - 1.0) * 1e6;
    }

    /**
     * The general form: {@code build_rot_matrix} ({@code helmert.cpp:229-321}) plus the
     * translation, scale and reference point {@code helmert_forward_3d} applies.
     *
     * <h4>Why {@code noRotation} is a parameter and not derived</h4>
     *
     * <p>Upstream sets {@code Q->no_rotation} from six values, not three: the three
     * rotations <em>and</em> their three rates ({@code helmert.cpp:664-667}). A helmert
     * with {@code +rx=0 +drx=1} is therefore not "no rotation" even at the epoch, and
     * its fast path must stay switched off. {@code molobadekas} never assigns the field
     * at all, so its calloc'd zero leaves the fast path off permanently and the
     * convention argument permanently required. Neither fact is visible from the three
     * angles this constructor is handed.
     *
     * @param tx             {@code Q->xyz.x}, metres; for {@code molobadekas} this already
     *                       has {@code +px} folded in ({@code helmert.cpp:753-755})
     * @param ty             {@code Q->xyz.y}, metres
     * @param tz             {@code Q->xyz.z}, metres
     * @param rx             {@code Q->opk.o} (omega), radians
     * @param ry             {@code Q->opk.p} (phi), radians
     * @param rz             {@code Q->opk.k} (kappa), radians
     * @param scalePpm       {@code Q->scale}, parts per million
     * @param exact          {@code +exact}: the full trigonometric matrix rather than the
     *                       linearised small-angle one, which is upstream's default
     * @param positionVector {@code convention=position_vector}, i.e. transpose the
     *                       coordinate-frame matrix the formulas are written in
     * @param refpX          {@code Q->refp.x}, metres; zero for {@code helmert}
     * @param refpY          {@code Q->refp.y}, metres
     * @param refpZ          {@code Q->refp.z}, metres
     * @param noRotation     {@code Q->no_rotation}
     */
    HelmertConversion(final double tx, final double ty, final double tz,
            final double rx, final double ry, final double rz,
            final double scalePpm, final boolean exact, final boolean positionVector,
            final double refpX, final double refpY, final double refpZ,
            final boolean noRotation) {
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.refpX = refpX;
        this.refpY = refpY;
        this.refpZ = refpZ;

        // helmert_forward_3d:388 - "no_rotation && scale == 0" takes the fast path,
        // where scale is the ppm value, not the multiplier.
        this.pureTranslation = noRotation && scalePpm == 0.0;
        this.scale = 1.0 + scalePpm * 1e-6;

        // build_rot_matrix, in the coordinate-frame convention. Upstream renames
        // (omega, phi, kappa) to (f, t, p) first, and the linearised branch is written
        // in those names, so the renaming is kept.
        final double f = rx;
        final double t = ry;
        final double p = rz;

        final double m00;
        final double m01;
        final double m02;
        final double m10;
        final double m11;
        final double m12;
        final double m20;
        final double m21;
        final double m22;

        if (exact) {
            final double cf = Math.cos(f);
            final double sf = Math.sin(f);
            final double ct = Math.cos(t);
            final double st = Math.sin(t);
            final double cp = Math.cos(p);
            final double sp = Math.sin(p);

            m00 = ct * cp;
            m01 = cf * sp + sf * st * cp;
            m02 = sf * sp - cf * st * cp;
            m10 = -ct * sp;
            m11 = cf * cp - sf * st * sp;
            m12 = sf * cp + cf * st * sp;
            m20 = st;
            m21 = -sf * ct;
            m22 = cf * ct;
        } else {
            m00 = 1;
            m01 = p;
            m02 = -t;
            m10 = -p;
            m11 = 1;
            m12 = f;
            m20 = t;
            m21 = -f;
            m22 = 1;
        }

        if (positionVector) {
            // ...then transposed, which is what convention=position_vector means.
            this.r00 = m00;
            this.r01 = m10;
            this.r02 = m20;
            this.r10 = m01;
            this.r11 = m11;
            this.r12 = m21;
            this.r20 = m02;
            this.r21 = m12;
            this.r22 = m22;
        } else {
            this.r00 = m00;
            this.r01 = m01;
            this.r02 = m02;
            this.r10 = m10;
            this.r11 = m11;
            this.r12 = m12;
            this.r20 = m20;
            this.r21 = m21;
            this.r22 = m22;
        }
    }

    /**
     * {@code helmert_forward_3d} ({@code helmert.cpp:370-416}): local frame to WGS84.
     *
     * @param coord {@code {X, Y, Z, t}} in metres, mutated in place
     */
    void forward(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        if (pureTranslation) {
            coord[0] = x + tx;
            coord[1] = y + ty;
            coord[2] = z + tz;
            return;
        }
        final double dx = x - refpX;
        final double dy = y - refpY;
        final double dz = z - refpZ;
        coord[0] = scale * (r00 * dx + r01 * dy + r02 * dz) + tx;
        coord[1] = scale * (r10 * dx + r11 * dy + r12 * dz) + ty;
        coord[2] = scale * (r20 * dx + r21 * dy + r22 * dz) + tz;
    }

    /**
     * {@code helmert_reverse_3d} ({@code helmert.cpp:419-436}): WGS84 to local frame.
     *
     * <p>Note this is <em>not</em> the algebraic inverse of {@link #forward} in
     * general — upstream unscales and de-offsets, then rotates by the transpose,
     * which for a non-orthogonal linearised matrix would differ. With
     * {@code +exact} the matrix is orthogonal and the two agree; the ordering is
     * kept as upstream writes it regardless.
     *
     * @param coord {@code {X, Y, Z, t}} in metres, mutated in place
     */
    void inverse(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        if (pureTranslation) {
            coord[0] = x - tx;
            coord[1] = y - ty;
            coord[2] = z - tz;
            return;
        }
        final double dx = (x - tx) / scale;
        final double dy = (y - ty) / scale;
        final double dz = (z - tz) / scale;
        coord[0] = (r00 * dx + r10 * dy + r20 * dz) + refpX;
        coord[1] = (r01 * dx + r11 * dy + r21 * dz) + refpY;
        coord[2] = (r02 * dx + r12 * dy + r22 * dz) + refpZ;
    }
}
