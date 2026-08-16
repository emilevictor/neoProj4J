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

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=geogoffset} ({@code 9.8.1:src/transformations/affine.cpp:228-250}): the
 * EPSG "Geographic Offsets" method — add a longitude offset, a latitude offset and a
 * height offset, and nothing else.
 *
 * <pre>
 * lam' = lam + dlon&middot;arcsec
 * phi' = phi + dlat&middot;arcsec
 * h'   = h   + dh
 * </pre>
 *
 * <h2>It has no source file of its own</h2>
 *
 * <p>{@code geogoffset} lives in {@code affine.cpp} and shares {@code affine}'s
 * {@code pj_opaque_affine}, {@code forward_4d}, {@code reverse_4d} and {@code initQ()}.
 * Three consequences follow from the sharing, and all three are why this is a separate
 * class rather than an {@link AffineOperator} with different keys:
 *
 * <ol>
 * <li><b>The matrix is {@code initQ()}'s identity and is never touched.</b>
 *     {@code geogoffset}'s setup function reads only {@code +dlon}, {@code +dlat} and
 *     {@code +dh}; it never assigns {@code s11}…{@code s33} or {@code tscale}. So the
 *     matrix product collapses to a translation, and {@code Q->toff} stays 0 with
 *     {@code tscale} at 1 — the time ordinate is carried through untouched.</li>
 * <li><b>{@code computeReverseParameters} is never called</b>, so {@code Q->reverse} keeps
 *     {@code initQ()}'s identity too. The inverse is therefore always available — unlike
 *     {@code affine}, which loses it on a singular matrix — and is
 *     {@code reverse_4d}'s "subtract the offset, then apply the identity".</li>
 * <li><b>The sides are {@code RADIANS}, not {@code affine}'s {@code WHATEVER}</b>
 *     ({@code affine.cpp:241-242} against {@code :192-193}). That is what makes
 *     {@code more_builtins.gie}'s {@code accept 10 20 / expect 11 19} come out in degrees:
 *     the harness converts on the way in and on the way out, and the operator itself only
 *     ever sees radians.</li>
 * </ol>
 *
 * <p>Writing the collapsed translation rather than {@code 1&middot;x + 0&middot;y + 0&middot;z}
 * is observationally identical. The two forms could only differ on a non-finite
 * {@code y} or {@code z}, where {@code 0&middot;Inf} is {@code NaN} — and 9.8.1 refuses a
 * non-finite input coordinate before the operator runs. Probed against the installed
 * binary: {@code echo "10 20 inf 0" | cct +proj=geogoffset +dlon=3600} answers
 * {@code TRANSFORMATION ERROR}, and a {@code NaN} ordinate makes the whole coordinate
 * {@code NaN} rather than only the columns the matrix would have contaminated.
 *
 * <h2>{@code +dlon} and {@code +dlat} are arcseconds; {@code +dh} is metres</h2>
 *
 * <p>{@code ARCSEC_TO_RAD} is {@code DEG_TO_RAD / 3600.0} ({@code affine.cpp:226}), so
 * {@code +dlon=3600} is one degree. The asymmetry is EPSG's, not a slip: the two angular
 * offsets are tabulated in arcseconds and the vertical one in linear units.
 *
 * <p>All three default to {@code pj_param}'s 0, so a bare {@code +proj=geogoffset} is the
 * identity — which {@code more_builtins.gie} asserts, with {@code accept 10 20} and
 * {@code expect 10 20}.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class GeogOffsetOperator implements PipelineOperator {

    /** {@code ARCSEC_TO_RAD} ({@code affine.cpp:226}): {@code DEG_TO_RAD / 3600.0}. */
    private static final double ARCSEC_TO_RAD = PipelineUnits.DEG_TO_RAD / 3600.0;

    /** {@code Q->xoff}: {@code +dlon} in radians. */
    private final double xoff;

    /** {@code Q->yoff}: {@code +dlat} in radians. */
    private final double yoff;

    /** {@code Q->zoff}: {@code +dh} in metres. */
    private final double zoff;

    private final String description;

    /**
     * @param params the step's fully expanded parameter list
     */
    GeogOffsetOperator(final ProjParams params) {
        this.xoff = params.doubleValue("dlon", 0.0) * ARCSEC_TO_RAD;
        this.yoff = params.doubleValue("dlat", 0.0) * ARCSEC_TO_RAD;
        this.zoff = params.doubleValue("dh", 0.0);
        this.description = "geogoffset dlon=" + params.doubleValue("dlon", 0.0)
                + " dlat=" + params.doubleValue("dlat", 0.0)
                + " dh=" + zoff;
    }

    /** {@code P->left = PJ_IO_UNITS_RADIANS} ({@code affine.cpp:241}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P->right = PJ_IO_UNITS_RADIANS} ({@code affine.cpp:242}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code forward_4d} ({@code affine.cpp:59-70}) with the identity matrix. */
    @Override
    public void forward(final double[] coord) {
        coord[0] += xoff;
        coord[1] += yoff;
        coord[2] += zoff;
    }

    /** {@code reverse_4d} ({@code affine.cpp:86-97}) with the identity matrix. */
    @Override
    public void inverse(final double[] coord) {
        coord[0] -= xoff;
        coord[1] -= yoff;
        coord[2] -= zoff;
    }

    /** {@code computeReverseParameters} is never called, so {@code inv4d} survives. */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "GeogOffsetOperator[" + description + "]";
    }
}
