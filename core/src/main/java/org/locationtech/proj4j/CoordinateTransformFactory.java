/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
 * Creates {@link CoordinateTransform}s
 * from source and target {@link CoordinateReferenceSystem}s.
 * <p>
 * This is also where the {@link DomainErrorPolicy} hangs. Every transform this factory creates
 * inherits the factory's policy, so an application that has one factory has one switch:
 *
 * <pre>{@code
 * // the default: fails closed on every per-coordinate cause EXCEPT a datum grid coverage
 * // miss, which passes the coordinate through unshifted, as 1.4.3 did
 * CoordinateTransformFactory compatible = new CoordinateTransformFactory();
 *
 * // fully fail-closed; the only mode under which the CrsTransformException contract holds
 * // for every cause
 * CoordinateTransformFactory strict =
 *         new CoordinateTransformFactory(DomainErrorPolicy.THROW);
 *
 * // documented escape for a caller that was relying on 1.4.3's silence everywhere
 * CoordinateTransformFactory lenient =
 *         new CoordinateTransformFactory(DomainErrorPolicy.RETURN_NAN);
 * }</pre>
 *
 * <h2>This class is frozen. It is not, and will not be, re-routed through the new facade.</h2>
 *
 * <p>{@link org.locationtech.proj4j.api.Proj} is the 1.5.0 entry point and it makes different
 * choices &mdash; most consequentially, it <em>refuses</em> to build an operation whose datum change
 * would not actually be performed, throwing
 * {@link ErrorCause#BALLPARK_REJECTED} at creation time. That is the right default for new code.
 *
 * <p>It would be the wrong behaviour to impose here. Re-routing this class would make
 * {@code EPSG:4267 -> EPSG:4269} start <b>throwing</b> for GeoTools, GeoServer, geomesa and every
 * other caller, on code that has worked for fifteen years, in a library most of them reach
 * transitively and did not choose. So:
 *
 * <ul>
 * <li>This class's selection behaviour is <b>unchanged</b>. The transform it returns is the same
 * {@link BasicCoordinateTransform} it has always returned, an unreachable {@code @}-optional grid is
 * still skipped silently, and &mdash; since 2.4.0 &mdash; a point outside every grid that
 * <em>did</em> load is passed through unshifted, both exactly as in 1.4.3.
 *
 * <p><b>That second clause was a promise this class broke between 2.0.0 and 2.3.0, and the fix is
 * why the default policy is now {@link DomainErrorPolicy#LEGACY_NO_SHIFT}.</b> The two cases read
 * alike and are not: "the grid file is absent" resolves to an empty list and has always been a
 * silent no-op, while "the point is outside a grid that loaded" began throwing
 * {@link ErrorCause#COORDINATE_OUTSIDE_GRID}. Shipping {@code conus} in {@code proj4j-epsg} turned
 * the rare case into the common one, because {@code +datum=NAD27}'s list stopped resolving empty.
 * Measured over 20,634 in-domain probes, this class lost <b>267</b> transforms that both
 * {@code cs2cs} 9.8.1 and 1.4.3 complete. A frozen class cannot lose 267 transforms to a
 * data-shipping decision, so it does not.</li>
 * <li>It is <b>not deprecated</b>, in 1.5.0 or later. Java 8 has no
 * {@code @Deprecated(forRemoval=)}, so the promise is stated in prose instead: <b>this class will
 * not be removed</b>. Nobody should plan a migration they do not need.</li>
 * <li>A caller who <em>wants</em> the strict engine behind this interface changes one line:
 * {@code LegacyAdapters.transformFactory(context)} returns a {@code CoordinateTransformFactory}
 * that applies the new policies. Opting in is visible in the application's own source, which is
 * where a change of behaviour belongs.</li>
 * </ul>
 *
 * <p>What <em>did</em> change in 1.5.0, and applies here too, is per-coordinate honesty: a
 * computation failure is reported as an exception rather than as a plausible coordinate. That is a
 * bug fix, it is governed by {@link DomainErrorPolicy}, and the escape hatch is
 * {@link DomainErrorPolicy#RETURN_NAN}. The one cause carved back out of it in 2.4.0 is a datum grid
 * <em>coverage</em> miss, for the reason above; see
 * {@link BasicCoordinateTransform#mayReturnUnshiftedCoordinates()} for how to tell whether a given
 * transform can produce one.
 *
 * @author mbdavis
 * @see DomainErrorPolicy
 * @see org.locationtech.proj4j.api.LegacyAdapters#transformFactory(org.locationtech.proj4j.api.ProjContext)
 * @see org.locationtech.proj4j.api.Proj#createCrsToCrs(String, String)
 */
public class CoordinateTransformFactory {

    private final DomainErrorPolicy domainErrorPolicy;

    /**
     * A factory whose transforms fail closed on a per-coordinate error, throwing
     * {@link CrsTransformException} &mdash; <em>except</em> a datum grid coverage miss, which passes
     * the coordinate through unshifted as 1.4.3 did.
     *
     * <p>The policy is {@link DomainErrorPolicy#LEGACY_NO_SHIFT}, changed from
     * {@link DomainErrorPolicy#THROW} in 2.4.0. Pass {@code THROW} explicitly for a factory that
     * refuses a coverage miss too.
     */
    public CoordinateTransformFactory() {
        this(DomainErrorPolicy.LEGACY_NO_SHIFT);
    }

    /**
     * A factory whose transforms all apply the given policy to a per-coordinate error.
     *
     * @param domainErrorPolicy the policy; null is treated as {@link DomainErrorPolicy#THROW}
     * @since 2.0.0
     */
    public CoordinateTransformFactory(DomainErrorPolicy domainErrorPolicy) {
        this.domainErrorPolicy =
                domainErrorPolicy == null ? DomainErrorPolicy.THROW : domainErrorPolicy;
    }

    /**
     * The policy every transform from this factory applies to a per-coordinate error.
     *
     * @return the policy; never null
     * @since 2.0.0
     */
    public DomainErrorPolicy getDomainErrorPolicy() {
        return domainErrorPolicy;
    }

    /**
     * Creates a transformation from a source CRS to a target CRS,
     * following the logic in PROJ.4.
     * The transformation may include any or all of inverse projection, datum transformation,
     * and reprojection, depending on the nature of the coordinate reference systems
     * provided.
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a tranformation from the source CRS to the target CRS
     */
    public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {
        return new BasicCoordinateTransform(sourceCRS, targetCRS, domainErrorPolicy);
    }

    /**
     * Creates a transformation typed as the allocation-free bulk API.
     *
     * <p>The same object {@link #createTransform(CoordinateReferenceSystem,
     * CoordinateReferenceSystem)} returns — {@link BasicCoordinateTransform} implements both
     * interfaces — so a caller who already holds a {@link CoordinateTransform} can simply cast, and
     * one who wants many points per call can ask for the bulk type directly and never see the
     * single-point signature:
     *
     * <pre>{@code
     * BulkCoordinateTransform op = factory.createBulkTransform(wgs84, utm33n);
     * byte[] status = new byte[maxVertices];          // caller-owned, reused per geometry
     * if (op.transform2D(xy, 0, numVertices, 2, status) != 0) {
     *     return emptyGeometry();
     * }
     * }</pre>
     *
     * <p>The declared return type is the point: it is a compile-time statement that the caller has
     * opted into the batch shape, and it means a future engine can return a different
     * implementation for the bulk path without changing this signature.
     *
     * <p>Results are bit-for-bit identical to the same points through
     * {@link CoordinateTransform#transform(ProjCoordinate, ProjCoordinate)}, and the factory's
     * {@link DomainErrorPolicy} applies to both — see {@link BulkCoordinateTransform} for how the
     * policy composes with the status array.
     *
     * @param sourceCRS the source CoordinateReferenceSystem
     * @param targetCRS the target CoordinateReferenceSystem
     * @return a bulk transformation from the source CRS to the target CRS
     * @since 2.0.0
     */
    public BulkCoordinateTransform createBulkTransform(CoordinateReferenceSystem sourceCRS,
                                                       CoordinateReferenceSystem targetCRS) {
        return new BasicCoordinateTransform(sourceCRS, targetCRS, domainErrorPolicy);
    }
}
