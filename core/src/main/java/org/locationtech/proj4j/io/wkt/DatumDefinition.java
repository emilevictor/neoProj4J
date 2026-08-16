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

/**
 * A geodetic or vertical reference frame: a name, and for a geodetic frame an ellipsoid and a
 * prime meridian.
 * <p>
 * WKT2 spells the prime meridian as a sibling of {@code DATUM[]} inside the CRS while WKT1 nests
 * it there too, but PROJJSON nests it inside the datum. It is held here, on the datum, in all
 * three cases.
 */
public final class DatumDefinition {

    private String name;
    private EllipsoidDefinition ellipsoid;
    private PrimeMeridianDefinition primeMeridian;
    private Identifier id;
    private String anchor;
    private double frameEpoch = Double.NaN;
    private boolean esriStyle;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EllipsoidDefinition getEllipsoid() {
        return ellipsoid;
    }

    public void setEllipsoid(EllipsoidDefinition ellipsoid) {
        this.ellipsoid = ellipsoid;
    }

    public PrimeMeridianDefinition getPrimeMeridian() {
        return primeMeridian;
    }

    public void setPrimeMeridian(PrimeMeridianDefinition primeMeridian) {
        this.primeMeridian = primeMeridian;
    }

    public Identifier getId() {
        return id;
    }

    public void setId(Identifier id) {
        this.id = id;
    }

    public String getAnchor() {
        return anchor;
    }

    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    /**
     * The reference epoch of a dynamic reference frame, or {@code NaN} for a static one. Retained
     * for round-tripping only; proj4j has no time dimension.
     */
    public double getFrameEpoch() {
        return frameEpoch;
    }

    public void setFrameEpoch(double frameEpoch) {
        this.frameEpoch = frameEpoch;
    }

    public boolean isDynamic() {
        return !Double.isNaN(frameEpoch);
    }

    /**
     * Whether the document this reference frame came from confirmed itself to be ESRI-flavoured
     * WKT1, by naming a {@code GEOGCS} {@code GCS_something} or a {@code DATUM}
     * {@code D_something}.
     * <p>
     * The same question, and the same one-field shape, as
     * {@link ConversionDefinition#isEsriStyle()}
     * — PROJ's {@code esriStyle_} rather than its {@code maybeEsriStyle_}. 2.1.0 carried the flag
     * only on the conversion, which meant a bare {@code GEOGCS} computed it and then threw it away
     * when the parse ended, for exactly the documents where the datum name is the whole content.
     * <p>
     * Why a reference frame needs it: an ESRI {@code D_} name is a name in a <em>different
     * namespace</em>. {@code D_European_1950} is EPSG geodetic datum 6230, and knowing that is the
     * only way to know that a shift to WGS 84 is owed. A frame whose name proj4j cannot place, in a
     * document that has confirmed it is speaking ESRI, is therefore not "a frame with an ellipsoid
     * and no shift" — it is a frame whose shift is unknown. See
     * {@link EsriDatumPolicy}.
     * <p>
     * Package-private, for the same reason the conversion's is: the wider question of
     * dialect-aware reading is still open and this is not yet a public commitment.
     */
    boolean isEsriStyle() {
        return esriStyle;
    }

    void setEsriStyle(boolean esriStyle) {
        this.esriStyle = esriStyle;
    }

    public String toString() {
        return String.valueOf(name);
    }
}
