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

import java.util.List;
import java.util.Locale;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.spi.ProjDatabase;

/**
 * Reads WKT into a {@link CrsDefinition}, and from there into a
 * {@link CoordinateReferenceSystem}.
 * <p>
 * All four dialects of {@link WktDialect} are accepted, auto-detected, and no dependency is
 * involved: this class and its package are plain JDK code, so a consumer needing WKT support does
 * not need a second artifact and does not need Apache SIS or GeoAPI on the classpath.
 * <p>
 * Axis order is governed by {@link AxisOrderPolicy}, which defaults to
 * {@link AxisOrderPolicy#LEGACY} — longitude-first, exactly as proj4j 1.4.3 behaves. The
 * {@code AXIS[]} clauses are parsed faithfully and retained on the returned
 * {@link CrsDefinition} whatever the policy; the policy decides only what the produced
 * {@link CoordinateReferenceSystem} does with them.
 * <p>
 * Instances are immutable and safe to share between threads.
 *
 * <pre>
 * CoordinateReferenceSystem crs = new WktReader().read(wkt);
 * CrsDefinition def = new WktReader().readDefinition(wkt);   // to inspect declared axes, ids
 * </pre>
 */
public final class WktReader {

    private final AxisOrderPolicy axisOrderPolicy;
    private final ProjDatabase database;

    /**
     * A reader with the default {@link AxisOrderPolicy#LEGACY} policy.
     */
    public WktReader() {
        this(AxisOrderPolicy.LEGACY);
    }

    /**
     * A reader with an explicit axis order policy.
     *
     * @param axisOrderPolicy how declared axis order affects the CRSs this reader builds
     */
    public WktReader(AxisOrderPolicy axisOrderPolicy) {
        this(axisOrderPolicy, null);
    }

    /**
     * A reader that resolves what a document referred to by authority code but did not spell out
     * against {@code database}.
     *
     * @param axisOrderPolicy how declared axis order affects the CRSs this reader builds
     * @param database        a database, or {@code null} to read documents as self-contained
     * @since 2.2.0
     */
    public WktReader(AxisOrderPolicy axisOrderPolicy, ProjDatabase database) {
        if (axisOrderPolicy == null) {
            throw new IllegalArgumentException("axisOrderPolicy is null");
        }
        this.axisOrderPolicy = axisOrderPolicy;
        this.database = database;
    }

    public AxisOrderPolicy getAxisOrderPolicy() {
        return axisOrderPolicy;
    }

    /**
     * The database this reader resolves authority references against, or {@code null}.
     *
     * @since 2.2.0
     */
    public ProjDatabase getDatabase() {
        return database;
    }

    /**
     * Parses {@code wkt} into a definition, retaining everything the document said.
     *
     * @throws WktParseException if the text is not well-formed WKT, or describes something this
     *                           library cannot represent
     */
    public CrsDefinition readDefinition(String wkt) {
        WktNode root = WktParser.parse(wkt);
        // Dialect detection runs on the text, not the tree, because PROJ's own rule is textual:
        // it asks whether "AXIS[" and "AUTHORITY[" appear anywhere at all.
        //
        // The guess is recorded on the definition and nothing else reads it. In particular it does
        // not decide how methods and parameters are spelled: that is the job of Impl's esriStyle
        // flag, which is PROJ's esriStyle_ rather than its maybeEsriStyle_. See that field.
        WktDialect dialect = WktDialect.guess(wkt);
        CrsDefinition def = new Impl().crs(root, null, null, 1);
        markDialect(def, dialect);
        return def;
    }

    /**
     * Parses {@code wkt} and builds a proj4j CRS from it, applying this reader's
     * {@link AxisOrderPolicy}.
     * <p>
     * {@link EsriDatumPolicy} is left at its {@link EsriDatumPolicy#REJECT} default, so an ESRI
     * {@code D_} reference frame this library cannot place is refused rather than silently reduced
     * to its ellipsoid. To opt out, call
     * {@link CrsDefinitions#toCrs(CrsDefinition, AxisOrderPolicy, org.locationtech.proj4j.spi.ProjDatabase, EsriDatumPolicy)}
     * on the result of {@link #readDefinition}, or set the policy on a
     * {@link org.locationtech.proj4j.api.ProjContext} and go through
     * {@link org.locationtech.proj4j.api.Proj}.
     *
     * @throws WktParseException                                  if the text cannot be parsed
     * @throws org.locationtech.proj4j.UnsupportedParameterException if it describes a projection
     *                                                            proj4j does not implement
     */
    public CoordinateReferenceSystem read(String wkt) {
        return CrsDefinitions.toCrs(readDefinition(wkt), axisOrderPolicy, database);
    }

    /**
     * Detects which dialect {@code wkt} is written in.
     */
    public static WktDialect dialectOf(String wkt) {
        return WktDialect.guess(wkt);
    }

    private static void markDialect(CrsDefinition def, WktDialect dialect) {
        def.setSourceDialect(dialect);
        if (def.getBaseCrs() != null) {
            markDialect(def.getBaseCrs(), dialect);
        }
        if (def.getHubCrs() != null) {
            markDialect(def.getHubCrs(), dialect);
        }
        for (int i = 0; i < def.getComponents().size(); i++) {
            markDialect(def.getComponents().get(i), dialect);
        }
    }

    /**
     * The traversal itself. One instance per parse, so what the document has said so far can be a
     * field.
     */
    private static final class Impl {

        /**
         * PROJ's {@code esriStyle_} (9.8.1 {@code src/iso19111/io.cpp:1272}), ported.
         * <p>
         * This is not the dialect guess. It is a confirmation found in the document itself, and
         * two things set it, either one alone being enough:
         * <ul>
         * <li>a WKT1 {@code GEOGCS} whose name begins {@code GCS_} ({@code io.cpp:1761-1763});</li>
         * <li>a {@code DATUM} whose name begins {@code D_} ({@code io.cpp:2413}).</li>
         * </ul>
         * Both comparisons are case-<em>sensitive</em>, because PROJ's {@code starts_with} is a
         * {@code memcmp} ({@code include/proj/internal/internal.hpp:107-121}) and the
         * case-insensitive {@code ci_starts_with} sitting next to it is deliberately not used
         * here. Measured against PROJ 9.8.1: {@code DATUM["d_north_american_1983"]} does not flip
         * it, and gives a different projection as a result.
         * <p>
         * Deliberately <em>not</em> PROJ's second flag {@code maybeEsriStyle_}, which is the raw
         * {@link WktDialect#guess} result. That rule calls any WKT1 with no {@code AXIS[} and no
         * {@code AUTHORITY[} ESRI, which is exactly what a shapefile {@code .prj} or a database
         * CRS column written in GDAL vocabulary looks like. Letting the guess govern spelling
         * would turn those into wrong answers; letting the confirmation govern it does not.
         * <p>
         * One {@code Impl} per parse, so this resets with the parse. PROJ's own field is never set
         * back to {@code false}, so a reused {@code WKTParser} stays in ESRI mode for every later
         * document — arguably an upstream bug, and not reproduced here.
         */
        private boolean esriStyle;

        // ------------------------------------------------------------ dispatch

        /**
         * Dispatches on the keyword of a CRS element.
         * <p>
         * {@code depth} counts nested coordinate reference systems, the outermost being 1, and is
         * bounded by {@link WktLimits#MAX_CRS_DEPTH}. {@link WktParser} has already bounded the
         * <em>tree</em>, but the semantic walk is a second recursion over that tree —
         * {@code crs -> compound -> crs} and {@code crs -> bound -> crs} — so it carries its own,
         * lower limit rather than inheriting a bound by accident.
         */
        CrsDefinition crs(WktNode node, UnitDefinition inheritedAngular,
                          UnitDefinition inheritedLinear, int depth) {
            if (depth > WktLimits.MAX_CRS_DEPTH) {
                throw new WktParseException("CRSs nested more than " + WktLimits.MAX_CRS_DEPTH
                        + " deep in WKT at \"" + node.keyword() + "\"; refusing to recurse further");
            }
            // Locale.ROOT: WKT keywords are ASCII and are compared against ASCII literals.
            // Under tr_TR a lower-case "id" would upper-case to "\u0130D" and never match "ID".
            String k = node.keyword().toUpperCase(Locale.ROOT);
            if (k.equals("GEOGCRS") || k.equals("GEOGRAPHICCRS") || k.equals("BASEGEOGCRS")
                    || k.equals("GEODCRS") || k.equals("GEODETICCRS") || k.equals("BASEGEODCRS")) {
                return geodeticWkt2(node, inheritedAngular);
            }
            if (k.equals("PROJCRS") || k.equals("PROJECTEDCRS") || k.equals("BASEPROJCRS")) {
                return projectedWkt2(node);
            }
            if (k.equals("VERTCRS") || k.equals("VERTICALCRS") || k.equals("BASEVERTCRS")) {
                return verticalWkt2(node);
            }
            if (k.equals("COMPOUNDCRS")) {
                return compound(node, false, depth);
            }
            if (k.equals("BOUNDCRS")) {
                return bound(node, depth);
            }
            if (k.equals("ENGCRS") || k.equals("ENGINEERINGCRS")) {
                return engineering(node, true);
            }
            if (k.equals("GEOGCS")) {
                return geographicWkt1(node);
            }
            if (k.equals("PROJCS")) {
                return projectedWkt1(node);
            }
            if (k.equals("GEOCCS")) {
                return geocentricWkt1(node);
            }
            if (k.equals("VERT_CS") || k.equals("VERTCS")) {
                return verticalWkt1(node);
            }
            if (k.equals("COMPD_CS")) {
                return compound(node, true, depth);
            }
            if (k.equals("LOCAL_CS")) {
                return engineering(node, false);
            }
            if (k.equals("TIMECRS") || k.equals("PARAMETRICCRS")) {
                throw new WktParseException(node.keyword()
                        + " is not a coordinate reference system this library supports");
            }
            if (k.equals("FITTED_CS")) {
                throw new WktParseException("FITTED_CS is not supported");
            }
            if (k.equals("DERIVEDPROJCRS")) {
                // Refused as a decision rather than by falling off the end into the catch-all
                // below. A derived projected CRS is a further conversion applied on top of an
                // already projected one, and a proj4j CoordinateReferenceSystem holds exactly one
                // projection. Its BASEPROJCRS is handled above, so only the wrapper is refused.
                throw new WktParseException("DERIVEDPROJCRS is not supported");
            }
            throw new WktParseException("unrecognised CRS element \"" + node.keyword() + "\"");
        }

        // ------------------------------------------------------------- WKT2 CRS

        private CrsDefinition geodeticWkt2(WktNode node, UnitDefinition inheritedAngular) {
            CrsDefinition def = new CrsDefinition();
            def.setName(name(node));
            DatumDefinition datum = datumWkt2(node);
            def.setDatum(datum);

            UnitDefinition angular = firstUnit(node, UnitDefinition.ANGULAR);
            if (angular == null) {
                angular = inheritedAngular;
            }
            WktNode csNode = node.find("CS");
            UnitDefinition csUnit = angular != null ? angular : UnitDefinition.DEGREE;
            CoordinateSystemDefinition cs = coordinateSystem(node, csNode, csUnit);
            if (cs.getAxes().isEmpty()) {
                // A WKT2 BASEGEOGCRS carries no CS at all — the standard says the base CRS's
                // coordinate system is implied. PROJ implies EPSG:6422, latitude then longitude,
                // and so do we: the axes are then present to be retained, and the policy at the
                // boundary decides what they mean.
                cs.setType(CoordinateSystemDefinition.ELLIPSOIDAL);
                cs.addAxis(new AxisDefinition("Geodetic latitude", "Lat", AxisDefinition.NORTH,
                        csUnit));
                cs.addAxis(new AxisDefinition("Geodetic longitude", "Lon", AxisDefinition.EAST,
                        csUnit));
            }
            def.setCoordinateSystem(cs);

            WktNode pm = node.find("PRIMEM", "PRIMEMERIDIAN");
            if (pm != null) {
                datum.setPrimeMeridian(primeMeridian(pm,
                        angular != null ? angular : UnitDefinition.DEGREE));
            } else if (datum.getPrimeMeridian() == null) {
                datum.setPrimeMeridian(PrimeMeridianDefinition.greenwich());
            }

            boolean geocentric = CoordinateSystemDefinition.CARTESIAN.equalsIgnoreCase(cs.getType())
                    || (cs.getAxes().size() == 3 && isGeocentricAxes(cs));
            def.setKind(geocentric ? CrsDefinition.Kind.GEOCENTRIC
                    : CrsDefinition.Kind.GEOGRAPHIC);
            metadata(node, def);
            return def;
        }

        private boolean isGeocentricAxes(CoordinateSystemDefinition cs) {
            List<AxisDefinition> axes = cs.getAxes();
            for (int i = 0; i < axes.size(); i++) {
                String d = axes.get(i).getDirection();
                if (AxisDefinition.GEOCENTRIC_X.equals(d) || AxisDefinition.GEOCENTRIC_Y.equals(d)
                        || AxisDefinition.GEOCENTRIC_Z.equals(d)) {
                    return true;
                }
            }
            return false;
        }

        private CrsDefinition projectedWkt2(WktNode node) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.PROJECTED);
            def.setName(name(node));

            WktNode base = node.find("BASEGEOGCRS", "BASEGEODCRS", "BASEGEOGRAPHICCRS");
            if (base == null) {
                throw new WktParseException("PROJCRS[] has no BASEGEOGCRS[] or BASEGEODCRS[]");
            }
            CrsDefinition baseCrs = geodeticWkt2(base, UnitDefinition.DEGREE);
            def.setBaseCrs(baseCrs);

            WktNode conv = node.find("CONVERSION", "DERIVINGCONVERSION");
            if (conv == null) {
                throw new WktParseException("PROJCRS[] has no CONVERSION[]");
            }
            UnitDefinition linear = firstUnit(node, UnitDefinition.LINEAR);
            UnitDefinition baseAngular = baseCrs.getCoordinateSystem() == null ? UnitDefinition.DEGREE
                    : defaultAngularUnit(baseCrs.getCoordinateSystem());
            ConversionDefinition conversion = conversionWkt2(conv, linear, baseAngular);
            // As in projectedWkt1: the base CRS, hence its DATUM, was read above.
            conversion.setEsriStyle(esriStyle);
            def.setConversion(conversion);

            CoordinateSystemDefinition cs = coordinateSystem(node, node.find("CS"),
                    linear != null ? linear : UnitDefinition.METRE);
            def.setCoordinateSystem(cs);
            metadata(node, def);
            return def;
        }

        private CrsDefinition verticalWkt2(WktNode node) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.VERTICAL);
            def.setName(name(node));
            WktNode vd = node.find("VDATUM", "VERTICALDATUM", "VRF", "VERTICALREFERENCEFRAME",
                    "ENSEMBLE");
            if (vd != null) {
                DatumDefinition datum = new DatumDefinition();
                datum.setName(name(vd));
                datum.setId(id(vd));
                def.setDatum(datum);
            }
            UnitDefinition linear = firstUnit(node, UnitDefinition.LINEAR);
            def.setCoordinateSystem(coordinateSystem(node, node.find("CS"),
                    linear != null ? linear : UnitDefinition.METRE));
            metadata(node, def);
            return def;
        }

        private CrsDefinition compound(WktNode node, boolean wkt1, int depth) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.COMPOUND);
            def.setName(name(node));
            List<WktNode> children = node.children();
            for (int i = 0; i < children.size(); i++) {
                WktNode c = children.get(i);
                if (c.isLeaf() || isMetadata(c)) {
                    continue;
                }
                def.addComponent(crs(c, null, null, depth + 1));
            }
            if (def.getComponents().isEmpty()) {
                throw new WktParseException((wkt1 ? "COMPD_CS" : "COMPOUNDCRS")
                        + "[] has no component CRSs");
            }
            metadata(node, def);
            return def;
        }

        private CrsDefinition bound(WktNode node, int depth) {
            WktNode source = node.find("SOURCECRS");
            WktNode target = node.find("TARGETCRS");
            WktNode transformation = node.find("ABRIDGEDTRANSFORMATION");
            if (source == null || source.childCount() == 0) {
                throw new WktParseException("BOUNDCRS[] has no SOURCECRS[]");
            }
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.BOUND);
            CrsDefinition sourceCrs = crs(source.child(0), null, null, depth + 1);
            def.setBaseCrs(sourceCrs);
            def.setName(sourceCrs.getName());
            if (target != null && target.childCount() > 0) {
                def.setHubCrs(crs(target.child(0), null, null, depth + 1));
            }
            if (transformation != null) {
                def.setToWgs84(helmert(transformation));
            }
            metadata(node, def);
            return def;
        }

        private CrsDefinition engineering(WktNode node, boolean wkt2) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.ENGINEERING);
            def.setName(name(node));
            UnitDefinition linear = firstUnit(node, UnitDefinition.LINEAR);
            def.setCoordinateSystem(coordinateSystem(node, node.find("CS"),
                    linear != null ? linear : UnitDefinition.METRE));
            metadata(node, def);
            return def;
        }

        // ------------------------------------------------------------- WKT1 CRS

        private CrsDefinition geographicWkt1(WktNode node) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.GEOGRAPHIC);
            def.setName(name(node));
            // Only for GEOGCS, not for the GEOCCS that also arrives here via geocentricWkt1:
            // PROJ tests the node keyword before looking at the name (io.cpp:1761).
            if ("GEOGCS".equalsIgnoreCase(node.keyword())) {
                noteEsriGeogcsName(def.getName());
            }
            UnitDefinition angular = unit(node.find("UNIT"), UnitDefinition.ANGULAR);
            if (angular == null) {
                angular = UnitDefinition.DEGREE;
            }
            DatumDefinition datum = datumWkt1(node, def);
            def.setDatum(datum);
            WktNode pm = node.find("PRIMEM", "PRIMEMERIDIAN");
            // OGC 01-009: the WKT1 PRIMEM longitude is in degrees, whatever the GEOGCS UNIT says.
            datum.setPrimeMeridian(pm == null ? PrimeMeridianDefinition.greenwich()
                    : primeMeridian(pm, UnitDefinition.DEGREE));
            CoordinateSystemDefinition cs = coordinateSystem(node, null, angular);
            if (cs.getAxes().isEmpty()) {
                // OGC 01-009 and GDAL's practice: a GEOGCS with no AXIS clauses is
                // longitude-first. Stating it explicitly means no caller has to infer it.
                cs.addAxis(new AxisDefinition("Longitude", "lon", AxisDefinition.EAST, angular));
                cs.addAxis(new AxisDefinition("Latitude", "lat", AxisDefinition.NORTH, angular));
            }
            def.setCoordinateSystem(cs);
            metadata(node, def);
            return def;
        }

        private CrsDefinition geocentricWkt1(WktNode node) {
            CrsDefinition def = geographicWkt1(node);
            def.setKind(CrsDefinition.Kind.GEOCENTRIC);
            UnitDefinition linear = unit(node.find("UNIT"), UnitDefinition.LINEAR);
            if (linear != null) {
                def.getCoordinateSystem().setUnit(linear);
                def.getCoordinateSystem().setType(CoordinateSystemDefinition.CARTESIAN);
            }
            return def;
        }

        private CrsDefinition projectedWkt1(WktNode node) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.PROJECTED);
            def.setName(name(node));

            WktNode geogcs = node.find("GEOGCS");
            if (geogcs == null) {
                throw new WktParseException("PROJCS[] has no GEOGCS[]");
            }
            CrsDefinition baseCrs = geographicWkt1(geogcs);
            def.setBaseCrs(baseCrs);

            UnitDefinition linear = unit(node.find("UNIT"), UnitDefinition.LINEAR);
            if (linear == null) {
                linear = UnitDefinition.METRE;
            }
            UnitDefinition baseAngular = defaultAngularUnit(baseCrs.getCoordinateSystem());

            WktNode projection = node.find("PROJECTION");
            if (projection == null) {
                throw new WktParseException("PROJCS[\"" + def.getName()
                        + "\"] has no PROJECTION[]");
            }
            ConversionDefinition conv = new ConversionDefinition();
            conv.setName("unnamed");
            conv.setMethodName(name(projection));
            conv.setMethodId(id(projection));
            List<WktNode> params = node.findAll("PARAMETER");
            for (int i = 0; i < params.size(); i++) {
                WktNode p = params.get(i);
                ParameterDefinition param = new ParameterDefinition();
                param.setName(name(p));
                param.setValue(p.doubleAt(1));
                param.setUnit(WktMethods.isAngularParameter(param.getName()) ? baseAngular
                        : WktMethods.isScaleParameter(param.getName()) ? UnitDefinition.UNITY
                        : linear);
                param.setId(id(p));
                conv.addParameter(param);
            }
            // The GEOGCS and its DATUM were read above, so anything in the document that confirms
            // ESRI style has already been seen by the time the conversion is built. That ordering
            // is control flow, not luck.
            conv.setEsriStyle(esriStyle);
            def.setConversion(conv);

            CoordinateSystemDefinition cs = coordinateSystem(node, null, linear);
            if (cs.getAxes().isEmpty()) {
                // A PROJCS with no AXIS clauses is easting then northing, as GDAL assumes.
                cs.addAxis(new AxisDefinition("Easting", "E", AxisDefinition.EAST, linear));
                cs.addAxis(new AxisDefinition("Northing", "N", AxisDefinition.NORTH, linear));
            }
            def.setCoordinateSystem(cs);
            WktNode extension = node.find("EXTENSION");
            if (extension != null && extension.childCount() >= 2
                    && "PROJ4".equalsIgnoreCase(extension.textAt(0))) {
                def.setProj4Extension(extension.textAt(1));
            }
            metadata(node, def);
            return def;
        }

        private CrsDefinition verticalWkt1(WktNode node) {
            CrsDefinition def = new CrsDefinition();
            def.setKind(CrsDefinition.Kind.VERTICAL);
            def.setName(name(node));
            WktNode vd = node.find("VERT_DATUM", "VDATUM");
            if (vd != null) {
                DatumDefinition datum = new DatumDefinition();
                datum.setName(name(vd));
                datum.setId(id(vd));
                def.setDatum(datum);
            }
            UnitDefinition linear = unit(node.find("UNIT"), UnitDefinition.LINEAR);
            def.setCoordinateSystem(coordinateSystem(node, null,
                    linear != null ? linear : UnitDefinition.METRE));
            metadata(node, def);
            return def;
        }

        // -------------------------------------------------------------- pieces

        private DatumDefinition datumWkt2(WktNode node) {
            WktNode dn = node.find("DATUM", "GEODETICDATUM", "TRF", "GEODETICREFERENCEFRAME");
            boolean ensemble = false;
            if (dn == null) {
                dn = node.find("ENSEMBLE");
                ensemble = dn != null;
            }
            if (dn == null) {
                throw new WktParseException(node.keyword()
                        + "[] has no DATUM[], GEODETICREFERENCEFRAME[] or ENSEMBLE[]");
            }
            DatumDefinition datum = new DatumDefinition();
            datum.setName(name(dn));
            noteEsriDatumName(datum.getName());
            datum.setEsriStyle(esriStyle);
            datum.setId(id(dn));
            WktNode anchor = dn.find("ANCHOR", "ANCHOREPOCH");
            if (anchor != null && anchor.childCount() > 0) {
                datum.setAnchor(anchor.textAt(0));
            }
            WktNode ell = dn.find("ELLIPSOID", "SPHEROID");
            if (ell == null && ensemble) {
                // A datum ensemble carries the ellipsoid of its members; PROJ writes it inside
                // the ENSEMBLE, and so does every EPSG-derived WKT2:2019 document.
                ell = dn.find("ELLIPSOID");
            }
            if (ell != null) {
                datum.setEllipsoid(ellipsoid(ell));
            } else if (!ensemble) {
                throw new WktParseException("DATUM[\"" + datum.getName()
                        + "\"] has no ELLIPSOID[]");
            }
            WktNode dynamic = node.find("DYNAMIC");
            if (dynamic != null) {
                WktNode epoch = dynamic.find("FRAMEEPOCH");
                if (epoch != null && epoch.childCount() > 0) {
                    datum.setFrameEpoch(epoch.doubleAt(0));
                }
            }
            return datum;
        }

        private DatumDefinition datumWkt1(WktNode node, CrsDefinition def) {
            WktNode dn = node.find("DATUM");
            if (dn == null) {
                throw new WktParseException(node.keyword() + "[] has no DATUM[]");
            }
            DatumDefinition datum = new DatumDefinition();
            datum.setName(name(dn));
            noteEsriDatumName(datum.getName());
            // Stamped here, not at the end of the parse, and after noteEsriDatumName so that this
            // frame's own D_ name counts. The GCS_ confirmation has already run for every shape
            // that carries one: geographicWkt1 calls noteEsriGeogcsName before datumWkt1, and a
            // PROJCS reaches its GEOGCS through that same method.
            datum.setEsriStyle(esriStyle);
            datum.setId(id(dn));
            WktNode ell = dn.find("SPHEROID", "ELLIPSOID");
            if (ell == null) {
                throw new WktParseException("DATUM[\"" + datum.getName()
                        + "\"] has no SPHEROID[]");
            }
            datum.setEllipsoid(ellipsoid(ell));
            WktNode towgs84 = dn.find("TOWGS84");
            if (towgs84 != null) {
                def.setToWgs84(toWgs84(towgs84));
            }
            return datum;
        }

        /**
         * Records that a {@code GEOGCS} named {@code GCS_something} has been seen, which is one of
         * the two things that confirm ESRI style. See {@link #esriStyle}.
         * <p>
         * Case-sensitive, as PROJ's is ({@code io.cpp:1761-1763}). A lower-case {@code gcs_} does
         * not count, and PROJ measurably reads such a document differently.
         */
        private void noteEsriGeogcsName(String name) {
            if (name != null && name.startsWith("GCS_")) {
                esriStyle = true;
            }
        }

        /**
         * Records that a {@code DATUM} named {@code D_something} has been seen, the other of the
         * two confirmations. See {@link #esriStyle}.
         * <p>
         * Case-sensitive for the same reason as {@link #noteEsriGeogcsName} ({@code io.cpp:2413}).
         * PROJ reaches this line only when it has not already resolved the datum name from its
         * database, but that guard only fires for {@code WGS_1984} and for names ending
         * {@code " ensemble"}, neither of which can begin {@code D_}, so it is not reproduced.
         */
        private void noteEsriDatumName(String name) {
            if (name != null && name.startsWith("D_")) {
                esriStyle = true;
            }
        }

        private EllipsoidDefinition ellipsoid(WktNode node) {
            EllipsoidDefinition e = new EllipsoidDefinition();
            e.setName(name(node));
            e.setSemiMajorAxis(node.doubleAt(1));
            double second = node.childCount() > 2 && isNumeric(node.child(2))
                    ? node.doubleAt(2) : Double.NaN;
            e.setInverseFlattening(second);
            UnitDefinition u = unit(node.find("LENGTHUNIT", "UNIT"), UnitDefinition.LINEAR);
            e.setUnit(u != null ? u : UnitDefinition.METRE);
            e.setId(id(node));
            if (Double.isNaN(second)) {
                throw new WktParseException("ELLIPSOID[\"" + e.getName()
                        + "\"] has no inverse flattening");
            }
            return e;
        }

        private PrimeMeridianDefinition primeMeridian(WktNode node, UnitDefinition defaultUnit) {
            PrimeMeridianDefinition pm = new PrimeMeridianDefinition();
            pm.setName(name(node));
            pm.setLongitude(node.childCount() > 1 ? node.doubleAt(1) : 0.0);
            UnitDefinition u = unit(node.find("ANGLEUNIT", "UNIT"), UnitDefinition.ANGULAR);
            pm.setUnit(u != null ? u : defaultUnit);
            pm.setId(id(node));
            return pm;
        }

        private ConversionDefinition conversionWkt2(WktNode node, UnitDefinition defaultLinear,
                                                    UnitDefinition defaultAngular) {
            ConversionDefinition conv = new ConversionDefinition();
            conv.setName(name(node));
            conv.setId(id(node));
            WktNode method = node.find("METHOD", "PROJECTION");
            if (method == null) {
                throw new WktParseException("CONVERSION[] has no METHOD[]");
            }
            conv.setMethodName(name(method));
            conv.setMethodId(id(method));
            List<WktNode> params = node.findAll("PARAMETER");
            for (int i = 0; i < params.size(); i++) {
                conv.addParameter(parameter(params.get(i), defaultLinear, defaultAngular));
            }
            return conv;
        }

        private ParameterDefinition parameter(WktNode node, UnitDefinition defaultLinear,
                                              UnitDefinition defaultAngular) {
            ParameterDefinition p = new ParameterDefinition();
            p.setName(name(node));
            p.setValue(node.doubleAt(1));
            UnitDefinition u = unit(node.find("ANGLEUNIT"), UnitDefinition.ANGULAR);
            if (u == null) {
                u = unit(node.find("LENGTHUNIT"), UnitDefinition.LINEAR);
            }
            if (u == null) {
                u = unit(node.find("SCALEUNIT"), UnitDefinition.SCALE);
            }
            if (u == null) {
                WktNode generic = node.find("UNIT");
                if (generic != null) {
                    int type = WktMethods.isAngularParameter(p.getName()) ? UnitDefinition.ANGULAR
                            : WktMethods.isScaleParameter(p.getName()) ? UnitDefinition.SCALE
                            : UnitDefinition.LINEAR;
                    u = unit(generic, type);
                }
            }
            if (u == null) {
                u = WktMethods.isAngularParameter(p.getName()) ? defaultAngular
                        : WktMethods.isScaleParameter(p.getName()) ? UnitDefinition.UNITY
                        : defaultLinear;
            }
            p.setUnit(u);
            p.setId(id(node));
            return p;
        }

        /**
         * The WKT1 {@code TOWGS84[dx,dy,dz,rx,ry,rz,s]} clause, in PROJ's {@code +towgs84} order
         * and units already.
         */
        private double[] toWgs84(WktNode node) {
            int n = 0;
            double[] all = new double[7];
            for (int i = 0; i < node.childCount() && n < 7; i++) {
                WktNode c = node.child(i);
                if (c.isLeaf() && isNumeric(c)) {
                    all[n++] = c.asDouble();
                }
            }
            if (n != 3 && n != 7) {
                throw new WktParseException("TOWGS84[] needs 3 or 7 values, found " + n);
            }
            if (n == 3) {
                return new double[]{all[0], all[1], all[2]};
            }
            return all;
        }

        /**
         * The Helmert parameters of a {@code BOUNDCRS}' {@code ABRIDGEDTRANSFORMATION}, converted
         * to PROJ's {@code +towgs84} order, units and rotation convention.
         * <p>
         * Two conversions are needed and both are easy to get wrong. The abridged form expresses
         * the scale as a multiplier {@code 1 + s * 1e-6} rather than in parts per million, and a
         * {@code Coordinate Frame rotation} method uses the opposite rotation sign convention
         * from {@code Position Vector transformation}, which is the one {@code +towgs84} assumes.
         */
        private double[] helmert(WktNode node) {
            WktNode method = node.find("METHOD", "PROJECTION");
            String methodName = method == null ? "" : name(method);
            boolean coordinateFrame = WktNames.normalize(methodName).contains("coordinateframe");

            double[] v = new double[7];
            boolean[] present = new boolean[7];
            List<WktNode> params = node.findAll("PARAMETER");
            for (int i = 0; i < params.size(); i++) {
                WktNode p = params.get(i);
                String pn = WktNames.normalize(name(p));
                double value = p.doubleAt(1);
                int index = helmertIndex(pn);
                if (index < 0) {
                    continue;
                }
                if (index >= 3 && index <= 5) {
                    // Rotations: WKT carries them in the declared angular unit, +towgs84 wants
                    // arc-seconds.
                    UnitDefinition u = unit(p.find("ANGLEUNIT", "UNIT"), UnitDefinition.ANGULAR);
                    if (u != null) {
                        value = u.toBase(value) / UnitDefinition.ARC_SECOND.getConversionFactor();
                    }
                    if (coordinateFrame) {
                        value = -value;
                    }
                } else if (index == 6) {
                    UnitDefinition u = unit(p.find("SCALEUNIT", "UNIT"), UnitDefinition.SCALE);
                    if (u != null && u.getConversionFactor() != 1.0) {
                        // An explicit non-unity scale unit, in practice parts per million.
                        value = u.toBase(value) * 1e6;
                    } else if (Math.abs(value - 1.0) < 0.1) {
                        // The abridged convention, which is the default in this element and is
                        // usually written with no unit at all: the value is 1 + s * 1e-6. Reading
                        // it as parts per million would inflate the scale by a factor of 800,000.
                        value = (value - 1.0) * 1e6;
                    }
                } else {
                    UnitDefinition u = unit(p.find("LENGTHUNIT", "UNIT"), UnitDefinition.LINEAR);
                    if (u != null) {
                        value = u.toBase(value);
                    }
                }
                v[index] = value;
                present[index] = true;
            }
            if (!present[0] && !present[1] && !present[2]) {
                throw new WktParseException("ABRIDGEDTRANSFORMATION[] has no translation "
                        + "parameters");
            }
            if (!present[3] && !present[4] && !present[5] && !present[6]) {
                return new double[]{v[0], v[1], v[2]};
            }
            return v;
        }

        private int helmertIndex(String normalizedName) {
            if (normalizedName.equals("xaxistranslation") || normalizedName.equals("dx")) {
                return 0;
            }
            if (normalizedName.equals("yaxistranslation") || normalizedName.equals("dy")) {
                return 1;
            }
            if (normalizedName.equals("zaxistranslation") || normalizedName.equals("dz")) {
                return 2;
            }
            if (normalizedName.equals("xaxisrotation") || normalizedName.equals("rx")) {
                return 3;
            }
            if (normalizedName.equals("yaxisrotation") || normalizedName.equals("ry")) {
                return 4;
            }
            if (normalizedName.equals("zaxisrotation") || normalizedName.equals("rz")) {
                return 5;
            }
            if (normalizedName.equals("scaledifference") || normalizedName.equals("ds")) {
                return 6;
            }
            return -1;
        }

        /**
         * Builds the coordinate system from an optional WKT2 {@code CS[]} plus the sibling
         * {@code AXIS[]} clauses. WKT1 has no {@code CS[]}, so it arrives here with
         * {@code csNode == null} and the axes alone.
         */
        private CoordinateSystemDefinition coordinateSystem(WktNode crsNode, WktNode csNode,
                                                            UnitDefinition defaultUnit) {
            CoordinateSystemDefinition cs = new CoordinateSystemDefinition();
            cs.setUnit(defaultUnit);
            if (csNode != null) {
                if (csNode.childCount() > 0) {
                    cs.setType(csNode.child(0).value());
                }
                if (csNode.childCount() > 1 && isNumeric(csNode.child(1))) {
                    cs.setDeclaredDimension((int) csNode.doubleAt(1));
                }
            }
            List<WktNode> axes = crsNode.findAll("AXIS");
            for (int i = 0; i < axes.size(); i++) {
                cs.addAxis(axis(axes.get(i), defaultUnit));
            }
            if (cs.getType() == null) {
                cs.setType(inferCsType(cs, defaultUnit));
            }
            return cs;
        }

        private String inferCsType(CoordinateSystemDefinition cs, UnitDefinition defaultUnit) {
            if (defaultUnit != null && defaultUnit.getType() == UnitDefinition.ANGULAR) {
                return CoordinateSystemDefinition.ELLIPSOIDAL;
            }
            List<AxisDefinition> axes = cs.getAxes();
            if (axes.size() == 1 && axes.get(0).isVertical()) {
                return CoordinateSystemDefinition.VERTICAL;
            }
            return CoordinateSystemDefinition.CARTESIAN;
        }

        private AxisDefinition axis(WktNode node, UnitDefinition defaultUnit) {
            AxisDefinition axis = new AxisDefinition();
            String label = node.childCount() > 0 ? node.textAt(0) : null;
            // WKT2 embeds the abbreviation in the name: "geodetic latitude (Lat)".
            if (label != null && label.endsWith(")")) {
                int open = label.lastIndexOf(" (");
                if (open > 0) {
                    axis.setName(label.substring(0, open));
                    axis.setAbbreviation(label.substring(open + 2, label.length() - 1));
                } else {
                    axis.setName(label);
                }
            } else {
                axis.setName(label);
            }
            if (node.childCount() > 1) {
                axis.setDirection(normalizeDirection(node.textAt(1)));
            } else {
                axis.setDirection(AxisDefinition.UNSPECIFIED);
            }
            UnitDefinition u = unit(node.find("ANGLEUNIT"), UnitDefinition.ANGULAR);
            if (u == null) {
                u = unit(node.find("LENGTHUNIT"), UnitDefinition.LINEAR);
            }
            if (u == null && node.find("UNIT") != null) {
                u = unit(node.find("UNIT"), defaultUnit == null ? UnitDefinition.LINEAR
                        : defaultUnit.getType());
            }
            axis.setUnit(u != null ? u : defaultUnit);
            return axis;
        }

        /**
         * Normalises an axis direction to WKT2's lower-case spelling. WKT1 writes {@code EAST},
         * WKT2 writes {@code east}, ESRI writes both, and {@code OTHER} appears in the wild.
         */
        private String normalizeDirection(String token) {
            String t = token.trim();
            if (t.equalsIgnoreCase("east")) {
                return AxisDefinition.EAST;
            }
            if (t.equalsIgnoreCase("west")) {
                return AxisDefinition.WEST;
            }
            if (t.equalsIgnoreCase("north")) {
                return AxisDefinition.NORTH;
            }
            if (t.equalsIgnoreCase("south")) {
                return AxisDefinition.SOUTH;
            }
            if (t.equalsIgnoreCase("up")) {
                return AxisDefinition.UP;
            }
            if (t.equalsIgnoreCase("down")) {
                return AxisDefinition.DOWN;
            }
            if (t.equalsIgnoreCase("geocentricX")) {
                return AxisDefinition.GEOCENTRIC_X;
            }
            if (t.equalsIgnoreCase("geocentricY")) {
                return AxisDefinition.GEOCENTRIC_Y;
            }
            if (t.equalsIgnoreCase("geocentricZ")) {
                return AxisDefinition.GEOCENTRIC_Z;
            }
            return t;
        }

        private void metadata(WktNode node, CrsDefinition def) {
            for (int i = 0; i < node.childCount(); i++) {
                WktNode c = node.child(i);
                if (c.isLeaf()) {
                    continue;
                }
                String k = c.keyword().toUpperCase(Locale.ROOT);
                if (k.equals("ID") || k.equals("AUTHORITY")) {
                    Identifier identifier = identifier(c);
                    if (identifier != null) {
                        def.addId(identifier);
                    }
                } else if (k.equals("REMARK")) {
                    def.setRemark(c.childCount() > 0 ? c.textAt(0) : null);
                } else if (k.equals("SCOPE")) {
                    def.setScope(c.childCount() > 0 ? c.textAt(0) : null);
                } else if (k.equals("AREA")) {
                    def.setAreaDescription(c.childCount() > 0 ? c.textAt(0) : null);
                } else if (k.equals("BBOX")) {
                    def.setBoundingBox(new double[]{c.doubleAt(0), c.doubleAt(1), c.doubleAt(2),
                            c.doubleAt(3)});
                } else if (k.equals("USAGE")) {
                    metadata(c, def);
                }
            }
        }

        private boolean isMetadata(WktNode node) {
            String k = node.keyword().toUpperCase(Locale.ROOT);
            return k.equals("ID") || k.equals("AUTHORITY") || k.equals("REMARK")
                    || k.equals("SCOPE") || k.equals("AREA") || k.equals("BBOX")
                    || k.equals("USAGE") || k.equals("UNIT") || k.equals("LENGTHUNIT")
                    || k.equals("ANGLEUNIT") || k.equals("AXIS") || k.equals("VERTICALEXTENT")
                    || k.equals("TIMEEXTENT") || k.equals("EXTENSION");
        }

        // -------------------------------------------------------------- helpers

        private String name(WktNode node) {
            WktNode first = node.child(0);
            if (first == null || !first.isLeaf()) {
                throw new WktParseException(node.keyword() + "[] has no name");
            }
            return first.value();
        }

        private Identifier id(WktNode node) {
            WktNode idNode = node.find("ID", "AUTHORITY");
            return idNode == null ? null : identifier(idNode);
        }

        private Identifier identifier(WktNode node) {
            if (node.childCount() < 2) {
                return null;
            }
            return new Identifier(node.textAt(0), node.textAt(1));
        }

        private UnitDefinition firstUnit(WktNode node, int type) {
            UnitDefinition u = unit(node.find(type == UnitDefinition.ANGULAR ? "ANGLEUNIT"
                    : "LENGTHUNIT"), type);
            if (u != null) {
                return u;
            }
            return unit(node.find("UNIT"), type);
        }

        private UnitDefinition unit(WktNode node, int type) {
            if (node == null) {
                return null;
            }
            String unitName = node.childCount() > 0 ? node.textAt(0) : null;
            double factor = node.childCount() > 1 && isNumeric(node.child(1))
                    ? node.doubleAt(1) : Double.NaN;
            WktNode idNode = node.find("ID", "AUTHORITY");
            return WktNames.unit(unitName, factor, type,
                    idNode == null ? null : identifier(idNode));
        }

        private UnitDefinition defaultAngularUnit(CoordinateSystemDefinition cs) {
            if (cs != null && cs.getUnit() != null
                    && cs.getUnit().getType() == UnitDefinition.ANGULAR) {
                return cs.getUnit();
            }
            return UnitDefinition.DEGREE;
        }

        private boolean isNumeric(WktNode node) {
            if (node == null || !node.isLeaf() || node.isQuoted()) {
                return false;
            }
            try {
                Double.parseDouble(node.value().trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
