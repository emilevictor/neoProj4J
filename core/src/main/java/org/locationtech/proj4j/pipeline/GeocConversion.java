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
 * {@code pj_geocentric_latitude} ({@code 9.8.1:src/conversions/geoc.cpp:37-64}), the
 * conversion between geographic and geocentric latitude.
 *
 * <h2>Why it is a class of its own</h2>
 *
 * <p>Upstream has one function with two callers, and so does this. {@link Cs2csOperator}
 * needs it for the {@code +geoc} <em>flag</em>, which {@code fwd_prepare} applies to the
 * input of any operation ({@code fwd.cpp:80-81}) and {@code inv_finalize} undoes at the
 * very end ({@code inv.cpp:139-140}); {@link GeocOperator} needs it for the {@code +proj=geoc}
 * <em>operator</em>, which is nothing else. Writing the six lines twice would put a numeric
 * rule in two places, and the two escapes below are exactly the kind of detail that gets
 * dropped from a copy.
 *
 * <h2>The two escapes, both upstream's</h2>
 *
 * <ol>
 * <li><b>{@code es == 0}</b>. On a sphere the geographic and geocentric latitudes are
 *     equal everywhere, so the input is returned unchanged. Verified against the installed
 *     9.8.1: {@code echo "12 55 0 0" | cct +proj=geoc +R=6378137} answers
 *     {@code 12 55}, while {@code +ellps=GRS80} answers {@code 12 54.8189733083}.</li>
 * <li><b>Within {@code M_HALFPI - 1e-9} of a pole</b> the input is returned unchanged,
 *     because {@code tan} diverges there while the two latitudes converge — computing
 *     would be both slower and worse. {@code more_builtins.gie} asserts all three sides of
 *     this: {@code 12 90} and {@code 12 -90} come back exactly, and
 *     {@code 12 89.99999999999} — just <em>outside</em> the guard — comes back changed, to
 *     {@code 12 89.999999999989996}.</li>
 * </ol>
 *
 * <p>Note the direction, which is the opposite of what the name suggests when it is used
 * for the flag: {@code PJ_FWD} is geographic to geocentric, and {@code fwd_prepare} calls
 * it {@code PJ_INV} because a {@code +geoc} operation's input already <em>is</em>
 * geocentric. {@code +proj=geoc}'s own forward is the plain {@code PJ_FWD}
 * ({@code geoc.cpp:66-69}).
 *
 * <p>Immutable and thread-safe.
 */
final class GeocConversion {

    /** {@code M_HALFPI - 1e-9} ({@code geoc.cpp:54}). */
    private static final double LIMIT = Math.PI / 2.0 - 1e-9;

    /** {@code P->one_es}, i.e. {@code 1 - es}; the {@code PJ_FWD} factor. */
    private final double oneEs;

    /** {@code P->rone_es}, i.e. {@code 1 / (1 - es)}; the {@code PJ_INV} factor. */
    private final double rOneEs;

    /** {@code P->es == 0}: {@code pj_geocentric_latitude} then returns its input. */
    private final boolean spherical;

    /**
     * @param es the first eccentricity squared, {@code P->es}
     */
    GeocConversion(final double es) {
        this.oneEs = 1.0 - es;
        this.rOneEs = 1.0 / (1.0 - es);
        this.spherical = es == 0.0;
    }

    /**
     * @param phi     latitude in radians
     * @param forward {@code PJ_FWD}, i.e. geographic to geocentric; {@code false} for
     *                {@code PJ_INV}
     * @return the converted latitude in radians
     */
    double latitude(final double phi, final boolean forward) {
        if (spherical || phi > LIMIT || phi < -LIMIT) {
            return phi;
        }
        return Math.atan((forward ? oneEs : rOneEs) * Math.tan(phi));
    }
}
