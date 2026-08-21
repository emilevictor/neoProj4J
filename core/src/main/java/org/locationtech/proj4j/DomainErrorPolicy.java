/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j;

/**
 * What a {@link CoordinateTransform} does with a <em>per-coordinate</em> failure: a coordinate
 * outside the operation's input contract, outside the projection's domain, or one whose
 * computation did not converge or did not come back finite.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Proj4J 1.4.3 answered those cases with a plausible coordinate rather than an error — the
 * input unchanged, a single {@code NaN} ordinate, a pole, or the projection's false
 * easting/northing. A caller's {@code isFinite} guard cannot see three of those four. The
 * library now fails closed, which is a <b>behaviour change</b> for any caller that was
 * (knowingly or not) depending on the old silence.
 *
 * <p>This enum is that caller's documented escape. {@link #RETURN_NAN} is deliberately <em>not</em>
 * a way to get the 1.4.3 behaviour back: it substitutes one honest sentinel for four dishonest
 * ones, so a downstream {@code isFinite} check becomes sufficient where before it was not.
 *
 * <p>{@link #LEGACY_NO_SHIFT} <b>is</b> a way to get 1.4.3's answer back, for exactly one cause,
 * and that asymmetry is deliberate — see its own javadoc for the measurement that forced it. Read
 * the two together: this enum is not a single lenience axis with {@link #THROW} at one end.
 *
 * <h2>Scope: per-coordinate causes only</h2>
 *
 * <p>Only causes for which {@link ErrorCause#isCoordinateError()} is {@code true} are affected.
 * A CRS that cannot be built ({@link ErrorCause#isCrsError()}), an operation that does not
 * exist or has no inverse ({@link ErrorCause#isOperationError()}), and an environment or
 * API-misuse failure ({@link ErrorCause#isEnvironmentError()}) always throw, under every
 * policy: none of them is a property of the coordinate, so returning {@code NaN} once per row
 * would report a planning-time defect four million times.
 *
 * @see CoordinateTransformFactory#CoordinateTransformFactory(DomainErrorPolicy)
 * @see BasicCoordinateTransform#BasicCoordinateTransform(CoordinateReferenceSystem,
 *      CoordinateReferenceSystem, DomainErrorPolicy)
 * @since 2.0.0
 */
public enum DomainErrorPolicy {

    /**
     * Throw {@link CrsTransformException} with the {@link ErrorCause} that explains it. The
     * default, and the only setting under which the fail-closed contract on
     * {@link CrsTransformException} holds.
     */
    THROW,

    /**
     * Write {@code NaN} into every ordinate of the destination coordinate and return normally.
     *
     * <p>Both ordinates, never one: a coordinate with one finite and one {@code NaN} ordinate
     * is the shape that survives a careless range check, and
     * {@link ProjCoordinate#hasValidXandYOrdinates()} is the intended test. {@code z} is
     * cleared too, so a partially-transformed height cannot be mistaken for a result.
     *
     * <p>No exception is thrown, so <b>the reason is lost</b>. Prefer {@link #THROW} and catch,
     * which gives the same skip-this-vertex control flow plus the {@link ErrorCause}.
     */
    RETURN_NAN,

    /**
     * Behave as {@link #THROW} for every cause <em>except</em>
     * {@link ErrorCause#COORDINATE_OUTSIDE_GRID} from a datum grid shift, which passes the
     * coordinate through <b>unshifted</b> and continues the transform. The default for
     * {@link CoordinateTransformFactory} and for
     * {@link BasicCoordinateTransform#BasicCoordinateTransform(CoordinateReferenceSystem,
     * CoordinateReferenceSystem)}, which are 1.4.3-era API and promise 1.4.3 behaviour.
     *
     * <h4>Why this exists, measured rather than argued</h4>
     *
     * <p>{@code proj4j-epsg} ships two of {@code +datum=NAD27}'s four {@code @}-optional grids
     * ({@code conus}, {@code ntv1_can.dat}), so NAD27's grid list resolves <em>non-empty</em> almost
     * everywhere and {@link org.locationtech.proj4j.datum.Grid#shift(java.util.List, boolean,
     * ProjCoordinate)} therefore throws for every point outside CONUS and Canada.
     * Over 20,634 in-domain probes across 6,878 dictionary definitions, that lost <b>267</b>
     * transforms that both {@code cs2cs} 9.8.1 <em>and</em> proj4j 1.4.3 complete. A frozen class
     * that promises "exactly as in 1.4.3" cannot lose 267 transforms to a data-shipping decision.
     *
     * <p>Passing the coordinate through unshifted is also what PROJ arrives at, by a different
     * route: at the CRS layer {@code proj_create_crs_to_crs} selects <i>Ballpark geographic
     * offset</i>, a declared no-op. Measured against {@code cs2cs} on those 267 probes, an unshifted
     * answer agrees to within 1&nbsp;mm on <b>218 of 267 (81.6%)</b>. The other <b>49</b> deviate by
     * up to <b>753&nbsp;m</b>, because PROJ found a real shift there from NADCON grids this library
     * does not ship. So this policy is right four times in five and materially wrong once in five,
     * which is why {@link #THROW} remains the default everywhere it is not a compatibility promise,
     * and why {@link BasicCoordinateTransform#mayReturnUnshiftedCoordinates()} exists.
     *
     * <h4>What it costs, stated plainly</h4>
     *
     * <p>Nothing distinguishes a shifted answer from an unshifted one <em>per coordinate</em> under
     * this policy — including in the bulk status array, which reports {@code OK} rather than
     * {@link org.locationtech.proj4j.bulk.TransformStatus#ERR_OUTSIDE_GRID_EXTENT}, because under
     * this policy it is not an error. That is deliberate: the single-point and bulk paths must not
     * disagree about whether a point succeeded. A caller who needs the per-coordinate verdict wants
     * {@link #THROW}, or the bulk API under {@link #THROW}, or
     * {@link org.locationtech.proj4j.api.Proj} — not this.
     *
     * <p>Only a grid <em>coverage</em> miss is affected. A grid that fails to load, a
     * {@code +towgs84} with a NaN parameter, a non-convergent inverse and an out-of-domain
     * projection all still throw.
     *
     * @since 2.4.0
     */
    LEGACY_NO_SHIFT
}
