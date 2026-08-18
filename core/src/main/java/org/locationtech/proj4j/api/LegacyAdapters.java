/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.api;

import org.locationtech.proj4j.BulkCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.ErrorCause;

/**
 * The opt-in bridge between the frozen 1.x API and this package.
 *
 * <h2>Why a bridge and not a re-route</h2>
 *
 * <p>{@link CoordinateTransformFactory} could have been changed to delegate here. It was not, and it
 * will not be. Doing so would make {@code EPSG:4267 -> EPSG:4269} start <b>throwing</b>
 * {@link ErrorCause#BALLPARK_REJECTED} for GeoTools, GeoServer, geomesa and every other caller, on
 * code that has worked for fifteen years. The new default is the <em>right</em> default for new
 * code and an unacceptable surprise for existing code, and there is no version of "we changed it
 * for your own good" that is acceptable in a library reached transitively.
 *
 * <p>So the legacy classes are bit-for-bit unchanged, and this class is how a caller opts in. One
 * line changes:
 *
 * <pre>{@code
 * // before
 * CoordinateTransformFactory factory = new CoordinateTransformFactory();
 *
 * // after: the strict engine, behind the interface the calling code already implements
 * CoordinateTransformFactory factory = LegacyAdapters.transformFactory(ProjContext.DEFAULT);
 * }</pre>
 *
 * <p>Everything downstream of that line keeps compiling and keeps its types. What changes is that
 * {@link CoordinateTransformFactory#createTransform(CoordinateReferenceSystem,
 * CoordinateReferenceSystem)} now applies {@link BallparkPolicy} and the rest of the context &mdash;
 * so it can throw where it used to return an unshifted coordinate. That is the point of asking for
 * it.
 *
 * <p>There is deliberately <b>no global switch and no system property</b>. Opting in is a visible
 * change in the application's own source, at the site that constructs the factory, so that
 * {@code git blame} can answer why the behaviour changed.
 *
 * @see Proj
 * @see CoordinateTransformFactory
 * @since 2.0.0
 */
public final class LegacyAdapters {

    private LegacyAdapters() {
    }

    /**
     * A {@link CoordinateTransformFactory} whose transforms go through this package's checks.
     *
     * <p>Substitutable for {@code new CoordinateTransformFactory()} at any call site: it
     * <em>is</em> a {@code CoordinateTransformFactory}, so it can be assigned to that type, passed
     * to code expecting it, and stored in a field of that type.
     *
     * <p>What differs from the plain factory, and only this:
     * <ul>
     * <li>{@link BallparkPolicy} is applied, so a pair whose datum change would not actually be
     * performed throws {@link CrsCreationException} from {@code createTransform} instead of
     * returning a transform that silently applies no shift;</li>
     * <li>the context's {@link org.locationtech.proj4j.DomainErrorPolicy} is used;</li>
     * <li>the returned transform is the same engine, so its numbers are identical wherever it does
     * not throw.</li>
     * </ul>
     *
     * <p>{@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy} is <b>not</b> applied here, because
     * this method is handed {@link CoordinateReferenceSystem} objects that are already built: axis
     * order is a property of the CRS, and applying a policy to somebody else's CRS behind their back
     * is precisely the silent transposition this design exists to prevent. Build the CRSs with
     * {@link Proj#createCrs(String, ProjContext)} if you want the policy honoured.
     *
     * @param context the policies to apply; null means {@link Proj#defaultContext()}
     * @return a factory; never null
     */
    public static CoordinateTransformFactory transformFactory(ProjContext context) {
        return new StrictFactory(context == null ? Proj.defaultContext() : context);
    }

    /**
     * A {@link CRSFactory} whose {@link CRSFactory#createFromName(String) createFromName} resolves
     * through this package &mdash; so an {@code authority:code} only the configured authority
     * database knows resolves, while the caller keeps the legacy type.
     *
     * <p>This is the other half of {@link #transformFactory(ProjContext)}, and it closes a real gap:
     * before 2.3.0 a legacy caller could get database-backed <em>operation selection</em> and still
     * had no way to <em>resolve</em> a code the legacy dictionary lacks without leaving the 1.x types
     * behind. Substitutable for {@code new CRSFactory()} the same way, at the same kind of call site:
     *
     * <pre>{@code
     * CRSFactory factory = LegacyAdapters.crsFactory(ctx);
     * CoordinateReferenceSystem crs = factory.createFromName("EPSG:9057");
     * }</pre>
     *
     * <p>What changes, and only this:
     * <ul>
     * <li>a code the legacy dictionary lacks resolves from the context's authority database, if one
     * is attached and the type is geodetic;</li>
     * <li>the context's {@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy} is honoured, because
     * unlike {@link #transformFactory(ProjContext)} this method <em>builds</em> the CRS rather than
     * being handed one somebody else built &mdash; so under
     * {@link org.locationtech.proj4j.io.wkt.AxisOrderPolicy#AUTHORITY} {@code EPSG:4326} comes back
     * with {@code +axis=neu}, where the plain factory is always lon-first;</li>
     * <li>WKT and PROJJSON are accepted too, since {@link Proj#createCrs(String, ProjContext)}
     * detects the notation. That is a superset and breaks nothing: the plain
     * {@code createFromName} treats a WKT document as a name and throws.</li>
     * </ul>
     *
     * <h4>It does not fall back to the plain factory, deliberately</h4>
     *
     * <p>Falling back on failure would be the obvious shape and it is the wrong one. The strict path
     * already tries the legacy dictionary first, so a fallback could only ever fire on a definition
     * the strict path <em>refused</em> &mdash; and there the two outcomes are a policy refusal turned
     * into the CRS the caller's own policy declined to build, or, where the plain factory would fail
     * too, a considered message replaced by a bare {@code UnknownAuthorityCodeException}. Neither is
     * an improvement. A caller who wants the policies not to apply already has
     * {@code new CRSFactory()}.
     *
     * <p><b>Only {@code createFromName} is overridden.</b> The parameter-string methods are the plain
     * ones, because {@code createFromParameters} takes a display {@code name} alongside the
     * parameters and {@link Proj#createCrs(String, ProjContext)} has nowhere to put it &mdash; routing
     * them would quietly rename callers' CRSs. {@link CRSFactory#createCompound(String)} is likewise
     * unchanged; {@code Crs} is two-dimensional and has nothing to add there.
     *
     * <p>Two further limits, stated rather than discovered later. The reader and {@link
     * org.locationtech.proj4j.Registry} inside {@code CRSFactory} are {@code private static} and
     * shared by every instance, so this factory cannot be given a different dictionary &mdash; only
     * different behaviour in the method it overrides. And an unknown code still arrives as
     * {@link org.locationtech.proj4j.UnknownAuthorityCodeException}, unchanged, because
     * {@link Proj#createCrs(String, ProjContext)} rethrows that one verbatim; other refusals arrive
     * as {@link CrsCreationException} carrying the reason.
     *
     * @param context the policies to build under, including the authority database if one is
     *                attached; null means {@link Proj#defaultContext()}
     * @return a factory; never null
     * @since 2.3.0
     */
    public static CRSFactory crsFactory(ProjContext context) {
        return new StrictCrsFactory(context == null ? Proj.defaultContext() : context);
    }

    /**
     * Wraps an already-built legacy CRS so that this package's introspection can be used on it.
     *
     * <p>The CRS is wrapped, not re-parsed, so nothing about it changes &mdash; including its axis
     * order, whatever {@code context} says. {@link Crs#definitionText()} is its parameter string
     * where it has one.
     *
     * @param crs     the legacy CRS
     * @param context the context to record against it; null means {@link Proj#defaultContext()}
     * @return the wrapper; never null
     * @throws CrsCreationException with {@link ErrorCause#API_MISUSE} if {@code crs} is null
     */
    public static Crs fromLegacy(CoordinateReferenceSystem crs, ProjContext context) {
        if (crs == null) {
            throw new CrsCreationException(ErrorCause.API_MISUSE,
                    "cannot adapt a null CoordinateReferenceSystem");
        }
        ProjContext ctx = context == null ? Proj.defaultContext() : context;
        String[] params = crs.getParameters();
        String definition = params == null ? crs.getName() : crs.getParameterString().trim();
        return new Crs(definition, Crs.Source.LEGACY_OBJECT, ctx, crs, null,
                params != null && Crs.hasParam(params, "axis"),
                "adapted from an existing CoordinateReferenceSystem: its axis order is whatever it "
                        + "was built with and was deliberately not re-derived, because silently "
                        + "transposing a CRS somebody else built is the failure this API exists to "
                        + "prevent.");
    }

    /**
     * The factory {@link #transformFactory(ProjContext)} returns. Package-private and final: it is
     * an implementation detail, and a caller should hold the {@link CoordinateTransformFactory}
     * type.
     */
    private static final class StrictFactory extends CoordinateTransformFactory {

        private final ProjContext context;

        StrictFactory(ProjContext context) {
            super(context.domainErrorPolicy());
            this.context = context;
        }

        @Override
        public CoordinateTransform createTransform(CoordinateReferenceSystem sourceCRS,
                                                   CoordinateReferenceSystem targetCRS) {
            return operation(sourceCRS, targetCRS).asLegacy();
        }

        @Override
        public BulkCoordinateTransform createBulkTransform(CoordinateReferenceSystem sourceCRS,
                                                           CoordinateReferenceSystem targetCRS) {
            return operation(sourceCRS, targetCRS).bulk();
        }

        private CrsOperation operation(CoordinateReferenceSystem sourceCRS,
                                       CoordinateReferenceSystem targetCRS) {
            return CrsOperation.create(fromLegacy(sourceCRS, context),
                    fromLegacy(targetCRS, context), context);
        }

        @Override
        public String toString() {
            return "LegacyAdapters.transformFactory(" + context + ")";
        }
    }

    /**
     * The factory {@link #crsFactory(ProjContext)} returns. Private and final for the same reason
     * {@link StrictFactory} is: a caller should hold the {@link CRSFactory} type.
     */
    private static final class StrictCrsFactory extends CRSFactory {

        private final ProjContext context;

        StrictCrsFactory(ProjContext context) {
            this.context = context;
        }

        @Override
        public CoordinateReferenceSystem createFromName(String name) {
            return Proj.createCrs(name, context).asLegacy();
        }

        @Override
        public String toString() {
            return "LegacyAdapters.crsFactory(" + context + ")";
        }
    }
}
