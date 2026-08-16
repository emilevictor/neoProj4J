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

import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=geoc} ({@code 9.8.1:src/conversions/geoc.cpp:76-85}): geographic latitude
 * to geocentric latitude and back, longitude and height untouched.
 *
 * <h2>Why this is not a {@code proj.Projection}</h2>
 *
 * <p>Upstream declares it {@code PJ_CONVERSION(geoc, 1)} with
 * {@code left = right = PJ_IO_UNITS_RADIANS}. It takes lat/lon and gives lat/lon, whereas
 * a {@code proj.Projection}'s contract is lon/lat to {@code x}/{@code y} in linear units,
 * so there is no shape of {@code Registry} entry that could hold it. It belongs here, and
 * it is named by {@link PipelineFactory#handlesOperator} so that a bare
 * {@code +proj=geoc ellps=GRS80} — which is how {@code more_builtins.gie} writes it —
 * reaches the pipeline engine rather than being reported as an unregistered projection.
 *
 * <h2>Distinct from the {@code +geoc} flag, and sharing its arithmetic</h2>
 *
 * <p>{@code +geoc} the <em>parameter</em> is a modifier on some other operation, folded in
 * by {@code fwd_prepare} and {@code inv_finalize} and implemented in
 * {@link Cs2csOperator}. {@code +proj=geoc} the <em>operator</em> is that conversion and
 * nothing else. Both call {@link GeocConversion}, so the pole guard and the sphere guard
 * exist once.
 *
 * <p>The {@code 1} in {@code PJ_CONVERSION(geoc, 1)} is {@code P->need_ellps}, and it is
 * the only construction-time requirement the operator has: {@code init.cpp:566-577} fails
 * with "Must specify ellipsoid or sphere" when nothing supplies one. Nothing else in the
 * setup function can fail, so there is no guard in the conformance bridge's
 * {@code ProjOperatorSetup} — {@code append_default_ellipsoid_to_paralist} has already
 * supplied {@code +ellps=GRS80} by the time either side looks.
 *
 * <p>{@code geoc.cpp:83} also sets {@code P->is_latlong = 1}. Nothing reads it: at rev
 * {@code 9.8.1} the field is written by {@code geoc.cpp} and {@code latlong.cpp}, zeroed
 * by {@code init.cpp:551}, and read nowhere in {@code src/}. It is a vestige of
 * {@code pj_is_latlong}, which the migration guide records as replaced by
 * {@code proj_angular_output} — that is, by {@code P->right}, which is modelled.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class GeocOperator implements PipelineOperator {

    private final GeocConversion conversion;
    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    GeocOperator(final Registry registry, final ProjParams params) {
        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        this.conversion = new GeocConversion(ellipsoid[1]);
        this.description = "geoc es=" + ellipsoid[1];
    }

    /** {@code P->left = PJ_IO_UNITS_RADIANS} ({@code geoc.cpp:80}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.RADIANS;
    }

    /** {@code P->right = PJ_IO_UNITS_RADIANS} ({@code geoc.cpp:81}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.RADIANS;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code geoc.cpp:66-69}, {@code PJ_FWD}: geographic latitude to geocentric. */
    @Override
    public void forward(final double[] coord) {
        coord[1] = conversion.latitude(coord[1], true);
    }

    /** {@code geoc.cpp:71-74}, {@code PJ_INV}: geocentric latitude to geographic. */
    @Override
    public void inverse(final double[] coord) {
        coord[1] = conversion.latitude(coord[1], false);
    }

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
        return "GeocOperator[" + description + "]";
    }
}
