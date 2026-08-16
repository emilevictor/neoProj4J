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
package org.locationtech.proj4j.vertical;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A vertical coordinate reference system — the right-hand half of a compound CRS such as
 * {@code EPSG:4326+5773}.
 *
 * <h2>What a vertical CRS reduces to, in proj-string terms</h2>
 *
 * <p>PROJ 9.8.1 exports a compound CRS as the horizontal CRS's proj-string plus at most
 * three tokens. Verbatim, {@code projinfo EPSG:4326+5773 -o PROJ}:
 *
 * <pre>
 * +proj=longlat +datum=WGS84 +geoidgrids=us_nga_egm96_15.tif +geoid_crs=WGS84 +vunits=m
 *   +no_defs +type=crs</pre>
 *
 * <p>So a vertical CRS contributes exactly {@code +geoidgrids}, {@code +geoid_crs} and
 * {@code +vunits}, and this class carries no more than that plus its identity. In
 * particular it is <em>not</em> a {@code CoordinateReferenceSystem}: it has no projection
 * and no horizontal datum, and pretending otherwise would let it reach code that assumes
 * both.
 *
 * <h2>Two grid names, deliberately</h2>
 *
 * <p>{@link #geoidGrids()} is the name PROJ emits — a GeoTIFF. {@link #legacyGeoidGrids()}
 * is the GTX name {@code proj.db}'s {@code grid_alternatives} table records as its
 * predecessor ({@code egm96_15.gtx} for {@code us_nga_egm96_15.tif}).
 *
 * <p><b>This used to say the GTX name was the only one we could open. That is no longer
 * true and has not been since the GeoTIFF reader landed:</b>
 * {@link org.locationtech.proj4j.datum.VerticalGrid} dispatches on the {@code .gtx} suffix
 * first and otherwise sniffs the header with {@code GeoTiffDataset.isTiff}, and reads both.
 * Measured 2026-08-14 with {@code ./docker/run.sh ci}: 2,784 lines across
 * {@code org.locationtech.proj4j.datum.tiff} and {@code datum/GeoTiffGrid.java}, and 68
 * green tests — 62 in {@code datum.geotiff} plus {@code GeoTiffSelfCycleTest}'s 6.
 *
 * <p>The two fields stay separate for a reason that never depended on reader support:
 * {@link #geoidGrids()} has to reproduce PROJ's export token-for-token, while
 * {@link #readableGeoidGrids()} answers the different question of which file to open, and
 * a user's PROJ data directory holds one name or the other, not reliably both. Collapsing
 * them would mean emitting a proj-string PROJ does not emit. Which of the two
 * {@link #readableGeoidGrids()} prefers is now a choice rather than a necessity; it prefers
 * GTX, and {@code CompoundCrsTest} pins that, so changing it is a deliberate act.
 *
 * <h2>Down-positive axes are recorded but not expressible</h2>
 *
 * <p>{@code EPSG:5715} (MSL depth) and {@code EPSG:6357} (NAVD88 depth) use coordinate
 * system {@code EPSG:6498}, whose single axis points <em>down</em>. PROJ's own PROJ.4
 * export drops that — {@code projinfo EPSG:4326+5715 -o PROJ} is indistinguishable from
 * {@code EPSG:4326+5714} — because a legacy proj-string can express it only as
 * {@code +axis=end}, which also reorders. {@link #isDepth()} therefore records the fact so
 * a caller can refuse rather than silently return a height where a depth was asked for.
 *
 * <p>Immutable and thread-safe.
 *
 * @since 2.0.0
 */
public final class VerticalCrs implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String authority;
    private final String code;
    private final String name;
    private final String geoidGrids;
    private final String legacyGeoidGrids;
    private final String geoidCrs;
    private final String verticalUnits;
    private final double verticalToMetre;
    private final boolean depth;

    /**
     * A vertical CRS whose unit has a PROJ id, so that {@code +vunits} can name it.
     *
     * @param authority        the authority, conventionally {@code "EPSG"}; may be {@code null}
     * @param code             the authority code, e.g. {@code "5773"}; may be {@code null}
     * @param name             the human-readable name, e.g. {@code "EGM96 height"}
     * @param geoidGrids       the {@code +geoidgrids} list PROJ emits, or {@code null} when the
     *                         vertical CRS has no geoid model expressible as a proj-string
     * @param legacyGeoidGrids the GTX equivalent from {@code grid_alternatives}, or {@code null}
     * @param geoidCrs         the {@code +geoid_crs} value, or {@code null}
     * @param verticalUnits    the {@code +vunits} id; {@code null} is treated as {@code "m"}
     * @param depth            whether the single axis is down-positive
     */
    public VerticalCrs(final String authority, final String code, final String name,
                       final String geoidGrids, final String legacyGeoidGrids,
                       final String geoidCrs, final String verticalUnits, final boolean depth) {
        this(authority, code, name, geoidGrids, legacyGeoidGrids, geoidCrs,
                verticalUnits == null || verticalUnits.isEmpty() ? "m" : verticalUnits,
                Double.NaN, depth);
    }

    /**
     * A vertical CRS whose unit may have no PROJ id, in which case the factor is carried
     * instead and {@link #projTokens(boolean)} emits {@code +vto_meter}.
     *
     * <p>This mirrors {@code 9.8.1:src/iso19111/crs.cpp}'s
     * {@code VerticalCRS::_exportToPROJString} exactly, which asks the axis unit for its PROJ
     * id and, <em>only</em> when that comes back empty, falls back to
     * {@code +vto_meter=<conversionToSI>}. {@code EPSG:5754} ("Poolbeg height", 0.3048007491 m)
     * is the case in the shipped EPSG WKT dictionary that needs it:
     * {@code projinfo EPSG:4326+5754 -o PROJ} is
     * {@code +proj=longlat +datum=WGS84 +vto_meter=0.3048007491 +no_defs +type=crs}.
     *
     * @param authority        the authority, conventionally {@code "EPSG"}; may be {@code null}
     * @param code             the authority code; may be {@code null}
     * @param name             the human-readable name
     * @param geoidGrids       the {@code +geoidgrids} list PROJ emits, or {@code null}
     * @param legacyGeoidGrids the GTX equivalent from {@code grid_alternatives}, or {@code null}
     * @param geoidCrs         the {@code +geoid_crs} value, or {@code null}
     * @param verticalUnits    the {@code +vunits} id, or {@code null} when the unit has none
     * @param verticalToMetre  the metres-per-unit factor, used only when {@code verticalUnits}
     *                         is {@code null}; {@code NaN} or a non-positive value with no unit
     *                         id falls back to metres
     * @param depth            whether the single axis is down-positive
     * @since 2.2.0
     */
    public VerticalCrs(final String authority, final String code, final String name,
                       final String geoidGrids, final String legacyGeoidGrids,
                       final String geoidCrs, final String verticalUnits,
                       final double verticalToMetre, final boolean depth) {
        this.authority = authority;
        this.code = code;
        this.name = name;
        this.geoidGrids = emptyToNull(geoidGrids);
        this.legacyGeoidGrids = emptyToNull(legacyGeoidGrids);
        this.geoidCrs = emptyToNull(geoidCrs);
        final String id = emptyToNull(verticalUnits);
        final boolean usableFactor = verticalToMetre > 0.0 && !Double.isInfinite(verticalToMetre);
        // Exactly one of the two describes the unit, and "neither" means metres. A vertical CRS
        // that carried both would let a later reader pick the one that happens to be wrong.
        if (id != null) {
            this.verticalUnits = id;
            this.verticalToMetre = Double.NaN;
        } else if (usableFactor) {
            this.verticalUnits = null;
            this.verticalToMetre = verticalToMetre;
        } else {
            this.verticalUnits = "m";
            this.verticalToMetre = Double.NaN;
        }
        this.depth = depth;
    }

    private static String emptyToNull(final String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** @return the authority, or {@code null} for an anonymous vertical CRS. */
    public String getAuthority() {
        return authority;
    }

    /** @return the authority code, or {@code null}. */
    public String getCode() {
        return code;
    }

    /** @return {@code authority:code}, or the name when there is no code. */
    public String getIdentifier() {
        if (authority == null || code == null) {
            return name;
        }
        return authority + ":" + code;
    }

    /** @return the human-readable name. */
    public String getName() {
        return name;
    }

    /**
     * @return the {@code +geoidgrids} list as PROJ 9.8.1 emits it (GeoTIFF names), or
     *         {@code null} when this vertical CRS carries no geoid model in a proj-string
     */
    public String geoidGrids() {
        return geoidGrids;
    }

    /**
     * @return the GTX name {@code proj.db}'s {@code grid_alternatives} gives for
     *         {@link #geoidGrids()}, or {@code null}. Both forms are readable — see the
     *         class javadoc — so this is the alternative name, not the only usable one.
     */
    public String legacyGeoidGrids() {
        return legacyGeoidGrids;
    }

    /**
     * @return the grid name to hand to {@link VGridShiftOperator}: the GTX alternative when
     *         there is one, else the name PROJ emits, else {@code null}
     */
    public String readableGeoidGrids() {
        return legacyGeoidGrids != null ? legacyGeoidGrids : geoidGrids;
    }

    /**
     * {@code +geoid_crs}, which {@code pj_init} does not read at all — only the CRS parser
     * honours it, and only when {@code +geoidgrids} is also present
     * ({@code io.cpp}'s hand-coded exception). It is carried so a round-tripped proj-string
     * matches PROJ's, not because it changes any arithmetic here.
     *
     * @return the value, or {@code null}
     */
    public String geoidCrs() {
        return geoidCrs;
    }

    /**
     * @return the {@code +vunits} id, or {@code null} when the unit has no PROJ id and
     *         {@link #verticalToMetre()} describes it instead. Never {@code null} for a
     *         {@code VerticalCrs} built through the eight-argument constructor, which
     *         defaults to {@code "m"}.
     */
    public String verticalUnits() {
        return verticalUnits;
    }

    /**
     * The metres-per-unit factor for a vertical unit PROJ's {@code pj_list_linear_units()}
     * cannot name, which is exported as {@code +vto_meter}.
     *
     * @return the factor, or {@code NaN} when {@link #verticalUnits()} names the unit instead
     * @since 2.2.0
     */
    public double verticalToMetre() {
        return verticalToMetre;
    }

    /** @return whether the axis is down-positive, i.e. a depth rather than a height. */
    public boolean isDepth() {
        return depth;
    }

    /** @return whether a geoid model is available in any form. */
    public boolean hasGeoidModel() {
        return geoidGrids != null || legacyGeoidGrids != null;
    }

    /**
     * The tokens this vertical CRS contributes to a proj-string, in PROJ's own order.
     *
     * <p>The order is {@code VerticalCRS::_exportToPROJString}'s
     * ({@code 9.8.1:src/iso19111/crs.cpp}): {@code +geoidgrids}, then {@code +geoid_crs}, then
     * exactly one of {@code +vunits} and {@code +vto_meter}. The axis direction is not among
     * them, upstream or here — see the class javadoc.
     *
     * @param useReadableGridName {@code true} to name the GTX file this library can read,
     *                            {@code false} to reproduce PROJ's GeoTIFF spelling exactly
     * @return a possibly empty token string with no leading or trailing space
     */
    public String projTokens(final boolean useReadableGridName) {
        final StringBuilder sb = new StringBuilder();
        final String grid = useReadableGridName ? readableGeoidGrids() : geoidGrids;
        if (grid != null) {
            sb.append("+geoidgrids=").append(grid);
            if (geoidCrs != null) {
                sb.append(" +geoid_crs=").append(geoidCrs);
            }
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        if (verticalUnits != null) {
            sb.append("+vunits=").append(verticalUnits);
        } else {
            sb.append("+vto_meter=").append(number(verticalToMetre));
        }
        return sb.toString();
    }

    /**
     * Fifteen significant digits, no exponent, no trailing zeros — the same rule
     * {@code io/wkt/WktFormat.number} applies, restated here because that method is
     * package-private to a package this one must not depend on. PROJ emits
     * {@code +vto_meter=0.3048007491} for {@code EPSG:5754} and this reproduces it.
     */
    private static String number(final double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new BigDecimal(v, SIGNIFICANT_15).stripTrailingZeros().toPlainString();
    }

    private static final MathContext SIGNIFICANT_15 = new MathContext(15, RoundingMode.HALF_UP);

    @Override
    public String toString() {
        return "VerticalCrs[" + getIdentifier() + " " + name
                + (geoidGrids == null ? "" : ", geoidgrids=" + geoidGrids)
                + (verticalUnits != null ? ", vunits=" + verticalUnits
                        : ", vto_meter=" + number(verticalToMetre))
                + (depth ? ", down-positive" : "") + "]";
    }
}
