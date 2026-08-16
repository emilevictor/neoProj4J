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
package org.locationtech.proj4j.io.wkt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.PrimeMeridian;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.spi.DbDatum;
import org.locationtech.proj4j.spi.DbEllipsoid;
import org.locationtech.proj4j.spi.DbObjectRef;
import org.locationtech.proj4j.spi.DbObjectType;
import org.locationtech.proj4j.spi.DbUnit;
import org.locationtech.proj4j.spi.ProjDatabase;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;
import org.locationtech.proj4j.util.ProjectionMath;
import org.locationtech.proj4j.vertical.CompoundCrs;
import org.locationtech.proj4j.vertical.VerticalCrs;
import org.locationtech.proj4j.vertical.VerticalCrsRegistry;

/**
 * The bridge between a {@link CrsDefinition} — what a document said — and proj4j's
 * {@link CoordinateReferenceSystem} — what this library can transform with.
 * <p>
 * Both directions live here. The forward direction builds a PROJ parameter list and hands it to
 * {@link CRSFactory}, which is deliberate: proj4j's CRS model <em>is</em> the PROJ parameter model,
 * so going through it means WKT support inherits every projection, datum and unit the existing
 * engine already handles, and one shared code path stays tested. It also gives a caller the thing
 * they most often actually want, {@link #toProjParameterString}: the PROJ string equivalent to
 * their WKT, which is what a hand-rolled WKT-to-PROJ converter was there to produce.
 * <p>
 * What is lost in the forward direction is stated rather than hidden.
 * {@link CoordinateReferenceSystem} is horizontal-only, so that is what {@link #toCrs} returns
 * for a compound CRS — but since 2.2.0 the parameter list it is built from carries the vertical
 * component's {@code +geoidgrids} / {@code +geoid_crs} / {@code +vunits} tokens, and
 * {@link #toCompoundCrs} returns both halves as a
 * {@link org.locationtech.proj4j.vertical.CompoundCrs}. A vertical-only CRS still has no
 * horizontal half for {@link #toCrs} to return and is still refused there — PROJ's own PROJ.4
 * export of one is a degenerate string with no {@code +proj=} — but {@link #toVerticalCrs} reads
 * it, so it is refused rather than unreadable.
 */
public final class CrsDefinitions {

    private CrsDefinitions() {
    }

    /**
     * Builds a proj4j CRS from a definition, applying {@code policy} to the declared axis order,
     * using only what the document itself said.
     *
     * @throws WktParseException if the definition describes something proj4j cannot represent
     */
    public static CoordinateReferenceSystem toCrs(CrsDefinition def, AxisOrderPolicy policy) {
        return toCrs(def, policy, null);
    }

    /**
     * Builds a proj4j CRS from a definition, applying {@code policy} to the declared axis order
     * and consulting {@code db} for anything the document referred to by authority code but did
     * not spell out.
     * <p>
     * A {@code null} database gives exactly the two-argument behaviour: this overload can only
     * turn a refusal into an answer, never one answer into a different one. Today the single
     * thing it resolves is a reference frame that names an ellipsoid by
     * {@code ID["EPSG",…]} without declaring its axes — legal in WKT2 and in PROJJSON, and
     * refused outright without a database to look it up in.
     *
     * @param db a database to resolve authority references against, or {@code null}
     * @throws WktParseException if the definition describes something proj4j cannot represent
     * @since 2.2.0
     */
    public static CoordinateReferenceSystem toCrs(CrsDefinition def, AxisOrderPolicy policy,
                                                  ProjDatabase db) {
        return toCrs(def, policy, db, EsriDatumPolicy.REJECT);
    }

    /**
     * Builds a proj4j CRS from a definition, applying {@code esriDatumPolicy} to an ESRI reference
     * frame this library cannot place.
     *
     * @param esriDatumPolicy what to do with an unresolved ESRI {@code D_} frame, or {@code null}
     *                        for {@link EsriDatumPolicy#REJECT}
     * @throws WktParseException if the definition describes something proj4j cannot represent
     * @since 2.2.0
     */
    public static CoordinateReferenceSystem toCrs(CrsDefinition def, AxisOrderPolicy policy,
                                                  ProjDatabase db,
                                                  EsriDatumPolicy esriDatumPolicy) {
        String[] params = toProjParameters(def, policy, db, esriDatumPolicy);
        String name = def.getName();
        Identifier id = def.getId();
        if (id != null) {
            name = id.toString();
        }
        return new CRSFactory().createFromParameters(name, params);
    }

    /**
     * The PROJ parameter list equivalent to a definition, each element in {@code +key=value} form,
     * using only what the document itself said.
     *
     * @throws WktParseException if the definition describes something proj4j cannot represent
     */
    public static String[] toProjParameters(CrsDefinition def, AxisOrderPolicy policy) {
        return toProjParameters(def, policy, null);
    }

    /**
     * The PROJ parameter list equivalent to a definition, consulting {@code db} for anything the
     * document referred to by authority code but did not spell out.
     *
     * @param db a database to resolve authority references against, or {@code null}
     * @throws WktParseException if the definition describes something proj4j cannot represent
     * @since 2.2.0
     */
    public static String[] toProjParameters(CrsDefinition def, AxisOrderPolicy policy,
                                            ProjDatabase db) {
        return toProjParameters(def, policy, db, EsriDatumPolicy.REJECT);
    }

    /**
     * The PROJ parameter list equivalent to a definition, applying {@code esriDatumPolicy} to an
     * ESRI reference frame this library cannot place.
     *
     * @param esriDatumPolicy what to do with an unresolved ESRI {@code D_} frame, or {@code null}
     *                        for {@link EsriDatumPolicy#REJECT}
     * @throws WktParseException if the definition describes something proj4j cannot represent
     * @since 2.2.0
     */
    public static String[] toProjParameters(CrsDefinition def, AxisOrderPolicy policy,
                                            ProjDatabase db, EsriDatumPolicy esriDatumPolicy) {
        if (esriDatumPolicy == null) {
            esriDatumPolicy = EsriDatumPolicy.REJECT;
        }
        if (def == null) {
            throw new WktParseException("CRS definition is null");
        }
        if (policy == null) {
            policy = AxisOrderPolicy.LEGACY;
        }
        CrsDefinition horizontal = def.horizontalComponent();
        if (horizontal == null) {
            throw new WktParseException("a " + def.getKind() + " CRS has no horizontal component; "
                    + "proj4j cannot represent \"" + def.getName() + "\" as a proj string. PROJ's "
                    + "own PROJ.4 export of a vertical CRS has no +proj=, so there is nothing to "
                    + "build a projection from; read it with toVerticalCrs(def) instead.");
        }

        List<String> params = new ArrayList<String>();
        boolean sphere = false;

        // 1. the projection itself
        if (horizontal.getKind() == CrsDefinition.Kind.PROJECTED) {
            ConversionDefinition conv = horizontal.getConversion();
            if (conv == null) {
                throw new WktParseException("projected CRS \"" + horizontal.getName()
                        + "\" has no conversion");
            }
            int flags = WktMethods.appendProjection(conv, horizontal, params);
            sphere = (flags & WktMethods.FLAG_SPHERE_FROM_A) != 0;
        } else if (horizontal.getKind() == CrsDefinition.Kind.GEOCENTRIC) {
            params.add("+proj=geocent");
        } else if (horizontal.getKind() == CrsDefinition.Kind.GEOGRAPHIC) {
            params.add("+proj=longlat");
        } else {
            throw new WktParseException("proj4j cannot represent a "
                    + horizontal.getKind() + " CRS (\"" + horizontal.getName() + "\")");
        }

        // 2. the datum, or failing that the ellipsoid, plus any Helmert parameters
        appendDatum(def, horizontal, params, sphere, db, esriDatumPolicy);

        // 3. the prime meridian
        DatumDefinition datum = horizontal.resolveDatum();
        if (datum != null && datum.getPrimeMeridian() != null
                && !datum.getPrimeMeridian().isGreenwich()) {
            params.add("+pm=" + primeMeridianValue(datum.getPrimeMeridian()));
        }

        // 4. the unit of the coordinate system
        appendUnits(horizontal, params);

        // 5. the axis order, if the policy says so
        appendAxisOrder(def, horizontal, policy, params);

        // 6. the vertical component of a compound CRS
        appendVertical(def, params);

        params.add("+no_defs");
        return params.toArray(new String[params.size()]);
    }

    /**
     * The PROJ string equivalent to a definition, as a single space-separated string. Handy for
     * logging, for handing to {@link CRSFactory#createFromParameters(String, String)}, and for
     * comparing against {@code projinfo} output.
     */
    public static String toProjParameterString(CrsDefinition def, AxisOrderPolicy policy) {
        return toProjParameterString(def, policy, null);
    }

    /**
     * The PROJ string equivalent to a definition, consulting {@code db} for anything the document
     * referred to by authority code but did not spell out.
     *
     * @param db a database to resolve authority references against, or {@code null}
     * @since 2.2.0
     */
    public static String toProjParameterString(CrsDefinition def, AxisOrderPolicy policy,
                                               ProjDatabase db) {
        return toProjParameterString(def, policy, db, EsriDatumPolicy.REJECT);
    }

    /**
     * The PROJ string equivalent to a definition, applying {@code esriDatumPolicy} to an ESRI
     * reference frame this library cannot place.
     *
     * @param esriDatumPolicy what to do with an unresolved ESRI {@code D_} frame, or {@code null}
     *                        for {@link EsriDatumPolicy#REJECT}
     * @since 2.2.0
     */
    public static String toProjParameterString(CrsDefinition def, AxisOrderPolicy policy,
                                               ProjDatabase db,
                                               EsriDatumPolicy esriDatumPolicy) {
        String[] params = toProjParameters(def, policy, db, esriDatumPolicy);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(params[i]);
        }
        return sb.toString();
    }

    private static void appendDatum(CrsDefinition def, CrsDefinition horizontal,
                                    List<String> params, boolean sphere, ProjDatabase db,
                                    EsriDatumPolicy esriDatumPolicy) {
        DatumDefinition datum = horizontal.resolveDatum();
        if (datum == null) {
            throw new WktParseException("CRS \"" + horizontal.getName() + "\" has no datum");
        }
        EllipsoidDefinition ellipsoid = datum.getEllipsoid();
        if (ellipsoid == null) {
            ellipsoid = ellipsoidFromDatabase(datum, db);
        }
        double[] toWgs84 = def.resolveToWgs84();

        if (sphere) {
            // A method which projects onto a sphere of the ellipsoid's semi-major axis: EPSG:3857
            // and its ESRI spelling. Emitted as an explicit equal-axis ellipsoid, which is what
            // GDAL's own PROJ.4 export of EPSG:3857 does, and never as +datum= — that would
            // restore the flattening the method exists to discard.
            if (ellipsoid == null) {
                throw new WktParseException("a spherical-development method needs an ellipsoid to "
                        + "take its radius from");
            }
            String a = WktFormat.number(ellipsoid.getSemiMajorAxisMetres());
            params.add("+a=" + a);
            params.add("+b=" + a);
            return;
        }

        String datumCode = WktNames.projDatumCode(datum.getName());
        if (datumCode != null && ellipsoidMatchesDatum(datumCode, ellipsoid)) {
            // A built-in datum carries its own shift to WGS 84, so +towgs84 is not emitted with
            // it: proj4j's DatumParameters lets whichever of the two is set last win, and a
            // silently-preferred parameter is how a datum shift goes missing.
            params.add("+datum=" + datumCode);
            return;
        }

        if (ellipsoid == null) {
            throw new WktParseException("datum \"" + datum.getName()
                    + "\" has no ellipsoid and is not a datum proj4j knows by name");
        }

        List<String> shape = ellipsoidParams(ellipsoid);

        // An ESRI name proj4j cannot place is not "an ellipsoid with no shift", it is a shift of
        // unknown size. Refusing here is the whole point of EsriDatumPolicy; see that enum. An
        // ID[] on the frame is deliberately not an escape hatch: it says which frame this is, not
        // where the frame is, and proj4j has nowhere to carry the former into operation selection.
        if (esriDatumPolicy == EsriDatumPolicy.REJECT && datum.isEsriStyle() && toWgs84 == null) {
            throw new WktParseException("reference frame \"" + datum.getName()
                    + "\" is an ESRI datum name proj4j cannot place"
                    + EsriDatumTable.describe(datum.getName())
                    + ", and the document supplies no TOWGS84[]; reading it would give "
                    + join(shape) + ", which is the right ellipsoid in the wrong place, because"
                    + " the shift to WGS 84 would be silently absent. Supply a TOWGS84[], or set"
                    + " EsriDatumPolicy.ALLOW to accept the ellipsoid alone");
        }

        params.addAll(shape);
        if (toWgs84 != null) {
            StringBuilder sb = new StringBuilder("+towgs84=");
            for (int i = 0; i < toWgs84.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(WktFormat.number(toWgs84[i]));
            }
            params.add(sb.toString());
        }
    }

    /**
     * The PROJ parameters that state an ellipsoid's shape: {@code +ellps=} when proj4j knows it by
     * name, and otherwise its two axes.
     */
    private static List<String> ellipsoidParams(EllipsoidDefinition ellipsoid) {
        List<String> out = new ArrayList<String>(2);
        String ellipsoidCode = WktNames.projEllipsoidCode(ellipsoid);
        if (ellipsoidCode != null) {
            out.add("+ellps=" + ellipsoidCode);
        } else {
            // Deliberately +a= and +b=, never +rf= or +f=: the semi-minor axis is exact, needs no
            // reciprocal, and is unaffected by how a parser interprets a flattening.
            double a = ellipsoid.getSemiMajorAxisMetres();
            double rf = WktNames.inverseFlatteningOf(ellipsoid);
            double b = rf == 0.0 ? a : a * (1.0 - 1.0 / rf);
            out.add("+a=" + WktFormat.number(a));
            out.add("+b=" + WktFormat.number(b));
        }
        return out;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /**
     * Whether the ellipsoid a document declared is the one proj4j's built-in datum of that name
     * uses. A document naming "WGS_1984" but describing a Bessel ellipsoid is not WGS 84, and
     * emitting {@code +datum=WGS84} for it would replace its ellipsoid silently.
     */
    private static boolean ellipsoidMatchesDatum(String datumCode, EllipsoidDefinition ellipsoid) {
        if (ellipsoid == null) {
            return true;
        }
        Datum datum = findDatum(datumCode);
        if (datum == null) {
            return false;
        }
        Ellipsoid e = datum.getEllipsoid();
        if (Math.abs(e.equatorRadius - ellipsoid.getSemiMajorAxisMetres()) > 1e-3) {
            return false;
        }
        double rf = WktNames.inverseFlatteningOf(ellipsoid);
        double erf = e.eccentricity2 == 0.0 ? 0.0 : 1.0 / (1.0 - Math.sqrt(1.0 - e.eccentricity2));
        return Math.abs(erf - rf) <= 1e-6;
    }

    /**
     * The ellipsoid of a reference frame that named itself by authority code but did not declare
     * its axes, read from {@code db}.
     * <p>
     * WKT2 and PROJJSON both allow {@code DATUM["OSGB36",ID["EPSG",6277]]} with no
     * {@code ELLIPSOID[]} inside it, and without a database that is unanswerable: the caller is
     * told so rather than being given some default. With a database it is a lookup.
     * <p>
     * Returns {@code null} — meaning "still unanswerable, refuse as before" — for every case
     * where the answer would be a guess: no database, no identifier, an authority the database
     * does not carry, a frame it does not know, a frame with no ellipsoid, or an ellipsoid whose
     * axes are in a unit the database cannot convert to metres. That last one matters: EPSG
     * publishes Clarke 1858 (EPSG:7007) in Clarke's feet, so taking {@code semiMajorAxis()} as
     * metres would shrink the Earth by a factor of three and still produce coordinates.
     */
    private static EllipsoidDefinition ellipsoidFromDatabase(DatumDefinition datum,
                                                             ProjDatabase db) {
        if (db == null || datum == null) {
            return null;
        }
        Identifier id = datum.getId();
        if (id == null || id.getAuthority() == null || id.getCode() == null) {
            return null;
        }
        DbDatum found;
        try {
            found = db.datum(DbObjectType.GEODETIC_DATUM, id.getAuthority(), id.getCode());
        } catch (RuntimeException e) {
            return null;
        }
        if (found == null || found.ellipsoid() == null) {
            return null;
        }
        DbObjectRef ref = found.ellipsoid();
        DbEllipsoid e;
        try {
            e = db.ellipsoid(ref.authName(), ref.code());
        } catch (RuntimeException ex) {
            return null;
        }
        if (e == null || Double.isNaN(e.semiMajorAxis())) {
            return null;
        }
        double toMetres = metresPerUnit(e.unit(), db);
        if (Double.isNaN(toMetres)) {
            return null;
        }

        EllipsoidDefinition out = new EllipsoidDefinition();
        out.setName(e.name());
        out.setId(new Identifier(e.authName(), e.code()));
        // Built in metres, with the metre unit set to say so, rather than carrying the
        // authority's unit alongside an unconverted number.
        out.setUnit(UnitDefinition.METRE);
        out.setSemiMajorAxis(e.semiMajorAxis() * toMetres);
        if (!Double.isNaN(e.inverseFlattening())) {
            // Dimensionless, so it survives the unit change untouched.
            out.setInverseFlattening(e.inverseFlattening());
        } else if (!Double.isNaN(e.semiMinorAxis())) {
            out.setSemiMinorAxis(e.semiMinorAxis() * toMetres);
        } else {
            return null;
        }
        return out;
    }

    /**
     * How many metres one of {@code ref}'s units is, or {@code NaN} if that cannot be established.
     * A unit of the wrong kind, or one of the eleven proj.db rows with a null conversion factor,
     * gives {@code NaN} rather than 1.0 — the caller then refuses, which is the whole point.
     */
    private static double metresPerUnit(DbObjectRef ref, ProjDatabase db) {
        if (ref == null) {
            return Double.NaN;
        }
        DbUnit unit;
        try {
            unit = db.unit(ref.authName(), ref.code());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
        if (unit == null || unit.type() != DbUnit.Type.LENGTH || !unit.hasConversionFactor()) {
            return Double.NaN;
        }
        return unit.conversionFactor();
    }

    private static Datum findDatum(String code) {
        Datum[] datums = org.locationtech.proj4j.Registry.datums;
        for (int i = 0; i < datums.length; i++) {
            if (datums[i].getCode().equals(code)) {
                return datums[i];
            }
        }
        return null;
    }

    /**
     * A {@code +pm=} value: a name proj4j knows, or else the offset in degrees. Never a name
     * proj4j does not know, because {@link PrimeMeridian#forName} silently falls back to Greenwich
     * for those, which would drop the offset entirely.
     */
    private static String primeMeridianValue(PrimeMeridianDefinition pm) {
        String name = pm.getName();
        if (name != null) {
            // Locale.ROOT: a PrimeMeridian lookup key. Under tr_TR "Lisbon" would lowercase
            // with a dotless i, miss forName(), and silently drop the meridian offset.
            String lower = name.toLowerCase(Locale.ROOT);
            PrimeMeridian known = PrimeMeridian.forName(lower);
            if (known != null && lower.equals(known.getName())) {
                return lower;
            }
        }
        return WktFormat.number(pm.getLongitudeDegrees());
    }

    private static void appendUnits(CrsDefinition horizontal, List<String> params) {
        if (horizontal.getKind() != CrsDefinition.Kind.PROJECTED
                && horizontal.getKind() != CrsDefinition.Kind.GEOCENTRIC) {
            // A geographic CRS is always degrees in proj4j; LongLatProjection.initialize() sets
            // that unconditionally, so saying so again would be noise.
            return;
        }
        CoordinateSystemDefinition cs = horizontal.getCoordinateSystem();
        UnitDefinition unit = cs == null ? null : cs.unitOf(0);
        if (unit == null || unit.getType() != UnitDefinition.LINEAR) {
            return;
        }
        String code = WktNames.projUnitsCode(unit);
        if (code != null) {
            params.add("+units=" + code);
        } else {
            params.add("+to_meter=" + WktFormat.number(unit.getConversionFactor()));
        }
    }

    private static void appendAxisOrder(CrsDefinition def, CrsDefinition horizontal,
                                        AxisOrderPolicy policy, List<String> params) {
        if (policy != AxisOrderPolicy.AUTHORITY) {
            // LEGACY ignores the declared order; VISUALISATION forces east/north/up, which is
            // proj4j's default and therefore also nothing to emit.
            return;
        }
        CoordinateSystemDefinition cs = horizontal.getCoordinateSystem();
        if (cs == null || cs.getAxes().size() < 2 || cs.isXBeforeY()) {
            return;
        }
        List<AxisDefinition> axes = cs.getAxes();
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < axes.size() && i < 3; i++) {
            sb.append(projAxisChar(axes.get(i)));
        }
        // The third slot is the vertical one, and for a compound CRS the vertical component is
        // where its direction is declared. Reading it here is the only place a legacy
        // proj-string can express a down-positive height at all: AxisOrder.Down negates z, and
        // filling the slot with 'u' regardless — which is what this did before the compound's
        // vertical component was read — is a sign error, not a rounding one.
        if (sb.length() < 3) {
            CrsDefinition vertical = def.verticalComponent();
            AxisDefinition verticalAxis = vertical == null ? null : soleAxisOf(vertical);
            sb.append(verticalAxis == null ? 'u' : projAxisChar(verticalAxis));
        }
        while (sb.length() < 3) {
            sb.append('u');
        }
        params.add("+axis=" + sb);
    }

    private static char projAxisChar(AxisDefinition axis) {
        char c = axis.toProjAxisChar();
        if (c == 0) {
            throw new WktParseException("axis \"" + axis.getName() + "\" has direction "
                    + axis.getDirection() + ", which cannot be expressed as +axis=; "
                    + "AxisOrderPolicy.AUTHORITY cannot honour this CRS");
        }
        return c;
    }

    private static AxisDefinition soleAxisOf(CrsDefinition vertical) {
        CoordinateSystemDefinition cs = vertical.getCoordinateSystem();
        if (cs == null || cs.getAxes().size() != 1) {
            return null;
        }
        return cs.getAxes().get(0);
    }

    // ------------------------------------------------------------------ vertical

    /**
     * Appends the tokens a compound CRS's vertical component contributes, which are exactly the
     * ones {@code VerticalCRS::_exportToPROJString} emits
     * ({@code 9.8.1:src/iso19111/crs.cpp}): {@code +geoidgrids}, {@code +geoid_crs} and one of
     * {@code +vunits} / {@code +vto_meter}. They are produced by
     * {@link org.locationtech.proj4j.vertical.VerticalCrs#projTokens(boolean)} — the same method
     * {@link CRSFactory#createCompound(String)}'s {@code EPSG:4326+5773} syntax already uses, so
     * the two ways of naming a compound CRS cannot drift apart.
     * <p>
     * Nothing is appended for a CRS with no vertical component, which is every non-compound CRS
     * and a {@code COMPD_CS} that declares two horizontal parts.
     */
    private static void appendVertical(CrsDefinition def, List<String> params) {
        VerticalCrs vertical = toVerticalCrs(def);
        if (vertical == null) {
            return;
        }
        String tokens = vertical.projTokens(false);
        int start = 0;
        while (start < tokens.length()) {
            int space = tokens.indexOf(' ', start);
            if (space < 0) {
                space = tokens.length();
            }
            if (space > start) {
                params.add(tokens.substring(start, space));
            }
            start = space + 1;
        }
    }

    /**
     * The vertical half of a definition as the library's own vertical CRS type, or {@code null}
     * when the definition declares no vertical component.
     * <p>
     * <b>This is the join between the two ways a compound CRS can arrive.</b> Before 2.2.0 the
     * WKT and PROJJSON readers produced a {@link CrsDefinition} whose vertical component nothing
     * ever read, and {@link CRSFactory#createCompound(String)}'s {@code EPSG:4326+5773} syntax
     * produced a {@link org.locationtech.proj4j.vertical.CompoundCrs} that no document could
     * reach. There is deliberately no second vertical model: a document is translated into the
     * existing one here, and everything downstream — the proj tokens, the grid names, the
     * down-positive flag — is whatever {@link VerticalCrs} already says it is.
     *
     * <h4>Where each field comes from</h4>
     * <ul>
     * <li><b>unit and direction</b> from the document, always. They are what the document is
     *     authoritative about, and {@code VerticalCRS::_exportToPROJString} reads them off the
     *     coordinate system for the same reason.</li>
     * <li><b>geoid grids</b> from {@link VerticalCrsRegistry} keyed on the vertical CRS's own
     *     identifier, because no WKT dialect carries a grid <em>filename</em>. WKT2's
     *     {@code GEOIDMODEL[]} names a model ("EGM2008"), not a file, so resolving it needs
     *     authority data either way. A code the registry does not know contributes no
     *     {@code +geoidgrids}, which is the same answer PROJ gives for a vertical CRS whose
     *     datum has no grid: the height passes through unshifted and
     *     {@link org.locationtech.proj4j.vertical.CompoundCrs#appliesVerticalShift()} reports
     *     it.</li>
     * </ul>
     *
     * @param def any definition; may be {@code null}
     * @return the vertical CRS, or {@code null}
     * @since 2.2.0
     */
    public static VerticalCrs toVerticalCrs(CrsDefinition def) {
        if (def == null) {
            return null;
        }
        CrsDefinition vertical = def.verticalComponent();
        if (vertical == null) {
            return null;
        }
        Identifier id = vertical.getId();
        VerticalCrs known = id == null ? null
                : VerticalCrsRegistry.find(id.getAuthority(), id.getCode());

        AxisDefinition axis = soleAxisOf(vertical);
        boolean depth = axis != null && AxisDefinition.DOWN.equals(axis.getDirection());

        CoordinateSystemDefinition cs = vertical.getCoordinateSystem();
        UnitDefinition unit = cs == null ? null : cs.unitOf(0);
        String unitCode = null;
        double toMetre = Double.NaN;
        if (unit != null && unit.getType() == UnitDefinition.LINEAR) {
            unitCode = WktNames.projUnitsCode(unit);
            if (unitCode == null) {
                toMetre = unit.getConversionFactor();
            }
        } else {
            // No LENGTHUNIT at all. PROJ's UnitOfMeasure default for a vertical axis is metre,
            // and so is this: never a silent guess at something else.
            unitCode = "m";
        }

        return new VerticalCrs(
                id == null ? null : id.getAuthority(),
                id == null ? null : id.getCode(),
                vertical.getName(),
                known == null ? null : known.geoidGrids(),
                known == null ? null : known.legacyGeoidGrids(),
                known == null ? null : known.geoidCrs(),
                unitCode, toMetre, depth);
    }

    /**
     * Builds a compound CRS from a definition: the horizontal half as a proj4j
     * {@link CoordinateReferenceSystem}, the vertical half as a {@link VerticalCrs}.
     * <p>
     * The horizontal CRS is built from the horizontal component alone, so its parameter list
     * carries no vertical token and
     * {@link org.locationtech.proj4j.vertical.CompoundCrs#toProjString()} composes the two
     * halves exactly once. That string is the same set of tokens
     * {@link #toProjParameterString(CrsDefinition, AxisOrderPolicy)} produces for the whole
     * definition, in a different order — {@code CompoundCrs} appends after {@code +no_defs}
     * where PROJ puts it last.
     *
     * @param def    a compound definition, or any definition with a vertical component
     * @param policy the axis-order policy for the horizontal half
     * @return the compound CRS
     * @throws WktParseException if the definition has no vertical component, or no horizontal
     *                           one
     * @since 2.2.0
     */
    public static CompoundCrs toCompoundCrs(CrsDefinition def, AxisOrderPolicy policy) {
        if (def == null) {
            throw new WktParseException("CRS definition is null");
        }
        VerticalCrs vertical = toVerticalCrs(def);
        if (vertical == null) {
            throw new WktParseException("a " + def.getKind() + " CRS has no vertical component; \""
                    + def.getName() + "\" is not a compound CRS");
        }
        CrsDefinition horizontal = def.horizontalComponent();
        if (horizontal == null) {
            throw new WktParseException("a " + def.getKind() + " CRS has no horizontal component; "
                    + "proj4j cannot represent \"" + def.getName() + "\" as a compound CRS. Its "
                    + "vertical half alone is available from toVerticalCrs(def).");
        }
        return new CompoundCrs(def.getName(), toCrs(horizontal, policy), vertical);
    }

    // ------------------------------------------------------------------- reverse

    /**
     * Describes an existing proj4j CRS as a definition, so that it can be written as WKT or
     * PROJJSON.
     * <p>
     * What a proj4j CRS does not carry, this cannot invent: there are no authority identifiers, no
     * area of use and no axis names, so the result is the minimum faithful description of the
     * projection, datum, ellipsoid and units. Round-tripping a definition that came from
     * {@link WktReader} keeps everything, because the definition itself is retained; this method
     * is for CRSs built from a PROJ string or from {@code CRSFactory.createFromName}.
     *
     * @throws WktParseException if the CRS uses a projection with no known WKT method
     */
    public static CrsDefinition fromCrs(CoordinateReferenceSystem crs) {
        if (crs == null) {
            throw new WktParseException("CRS is null");
        }
        Projection proj = crs.getProjection();
        if (proj == null) {
            throw new WktParseException("CRS \"" + crs.getName() + "\" has no projection");
        }
        Datum datum = crs.getDatum();

        DatumDefinition datumDef = new DatumDefinition();
        datumDef.setName(WktNames.wktDatumName(datum));
        datumDef.setEllipsoid(WktNames.definitionOf(proj.getEllipsoid()));
        PrimeMeridianDefinition pm = new PrimeMeridianDefinition();
        PrimeMeridian projPm = proj.getPrimeMeridian();
        if (projPm == null || "greenwich".equals(projPm.getName())) {
            pm = PrimeMeridianDefinition.greenwich();
        } else {
            pm.setName(capitalise(projPm.getName()));
            pm.setUnit(UnitDefinition.DEGREE);
            pm.setLongitude(ProjectionMath.toDeg(offsetFromGreenwichRadians(projPm)));
        }
        datumDef.setPrimeMeridian(pm);

        boolean geographic = Boolean.TRUE.equals(proj.isGeographic());

        CrsDefinition base = new CrsDefinition();
        base.setKind(CrsDefinition.Kind.GEOGRAPHIC);
        base.setName(geographicNameFor(datumDef.getName()));
        base.setDatum(datumDef);
        CoordinateSystemDefinition baseCs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.ELLIPSOIDAL);
        baseCs.setUnit(UnitDefinition.DEGREE);
        baseCs.addAxis(new AxisDefinition("geodetic longitude", "Lon", AxisDefinition.EAST,
                UnitDefinition.DEGREE));
        baseCs.addAxis(new AxisDefinition("geodetic latitude", "Lat", AxisDefinition.NORTH,
                UnitDefinition.DEGREE));
        base.setCoordinateSystem(baseCs);

        if (datum != null && datum.getTransformType() == Datum.TYPE_3PARAM
                || datum != null && datum.getTransformType() == Datum.TYPE_7PARAM) {
            base.setToWgs84(datum.getTransformToWGS84());
        }

        if (geographic) {
            return base;
        }

        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.PROJECTED);
        def.setName(crs.getName() != null ? crs.getName() : proj.getName());
        def.setBaseCrs(base);
        def.setConversion(WktMethods.conversionOf(proj));

        UnitDefinition linear = linearUnitOf(proj);
        CoordinateSystemDefinition cs =
                new CoordinateSystemDefinition(CoordinateSystemDefinition.CARTESIAN);
        cs.setUnit(linear);
        cs.addAxis(new AxisDefinition("easting", "E", AxisDefinition.EAST, linear));
        cs.addAxis(new AxisDefinition("northing", "N", AxisDefinition.NORTH, linear));
        def.setCoordinateSystem(cs);
        return def;
    }

    /**
     * The offset of a prime meridian from Greenwich, in radians.
     * <p>
     * {@link PrimeMeridian} has no accessor for it, so it is read the only way the class allows:
     * by shifting a zero coordinate. The call mutates the scratch coordinate and nothing else.
     */
    private static double offsetFromGreenwichRadians(PrimeMeridian pm) {
        org.locationtech.proj4j.ProjCoordinate probe = new org.locationtech.proj4j.ProjCoordinate(
                0.0, 0.0);
        pm.toGreenwich(probe);
        return probe.x;
    }

    private static UnitDefinition linearUnitOf(Projection proj) {
        Unit unit = proj.getUnits();
        if (unit == null || unit == Units.DEGREES) {
            return UnitDefinition.METRE;
        }
        UnitDefinition u = WktNames.unitFromProjCode(unit.abbreviation);
        return u != null ? u : new UnitDefinition(unit.name, unit.value, UnitDefinition.LINEAR);
    }

    private static String geographicNameFor(String datumName) {
        if (datumName == null) {
            return "unknown";
        }
        if (datumName.startsWith("World Geodetic System 1984")) {
            return "WGS 84";
        }
        if (datumName.startsWith("North American Datum 1983")) {
            return "NAD83";
        }
        if (datumName.startsWith("North American Datum 1927")) {
            return "NAD27";
        }
        return datumName;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
