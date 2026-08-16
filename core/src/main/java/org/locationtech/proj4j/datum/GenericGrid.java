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
 *******************************************************************************/
package org.locationtech.proj4j.datum;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.datum.tiff.GeoTiffImage;

/**
 * A grid with an arbitrary number of samples per node — PROJ's {@code GenericShiftGrid}
 * ({@code 9.8.1:src/grids.hpp:236-268}, {@code src/grids.cpp:3058-3201}).
 *
 * <h2>Why this exists when {@link Grid} and {@link VerticalGrid} already do</h2>
 *
 * <p>The two node representations proj4j had were both fixed-purpose:
 * {@code util.FloatPolarCoordinate} holds exactly two floats and a bare {@code float[]} holds
 * exactly one. Every consumer pinned one or two samples per node. PROJ has a third shape for the
 * operators whose grids carry three or more channels with <em>named roles</em> —
 * {@code xyzgridshift}, {@code gridshift} and {@code defmodel} — and the roles are resolved from
 * per-band metadata rather than from position, so the grid object has to expose the metadata as
 * well as the numbers.
 *
 * <p>The <em>reader</em> was never the missing piece:
 * {@link GeoTiffImage#readSamples(int[])} has read an arbitrary set of samples since 2.1.0. What
 * was missing was this object and {@link GenericGridSet}, sitting between the reader and the
 * operators.
 *
 * <h2>Six upstream behaviours, and which of them are here</h2>
 *
 * <ol>
 * <li><b>Sample roles are the operator's business, not the grid's.</b> Upstream has three
 *     mutually incompatible vocabularies — {@code latitude_offset}/{@code longitude_offset},
 *     {@code easting_offset}/{@code northing_offset} and
 *     {@code x_translation}/{@code y_translation}/{@code z_translation} — with different unit
 *     requirements and a positional fallback that differs by {@link #isGeographic()}. None of that
 *     is here. This class answers {@link #description(int)}, {@link #unit(int)}, {@link #type()}
 *     and {@link #samplesPerPixel()}; each operator maps those to its own roles.</li>
 * <li><b>{@code TYPE} is a lookup-time selector, not only a bucketing key.</b>
 *     {@link GenericGridSet#gridAt(String, double, double)} skips grids whose {@link #type()}
 *     differs, which is a different question from the one
 *     {@code GeoTiffGrid.insertIntoHierarchy} asks when it builds the tree.</li>
 * <li><b>Metadata is inherited across IFDs.</b> See {@link #metadataItem(String, int)}.</li>
 * <li><b>A windowed read reports nodata as an outcome distinct from a value.</b> See
 *     {@link #valuesAt}.</li>
 * <li><b>Windowed output is sample-innermost.</b> See {@link #valuesAt}.</li>
 * <li><b>Biquadratic (NADCON5) interpolation is NOT implemented.</b> Only
 *     {@link #interpolateThreeSamples} is, which is bilinear and is all
 *     {@code xyzgridshift} and {@code deformation} use. {@code +proj=gridshift
 *     +interpolation=biquadratic} — {@code gridshift.cpp:474-560} — has no port here, and
 *     {@code gridshift} is refused outright, so the gap is not reachable. Whoever implements
 *     {@code gridshift} has to write it; {@link #valuesAt} with a 3&times;3 window is the input it
 *     needs.</li>
 * </ol>
 *
 * <h2>Immutability</h2>
 *
 * <p>Every field is {@code final} and nothing is computed lazily. In particular {@link #type()} is
 * resolved in the constructor rather than through upstream's {@code mutable m_type} /
 * {@code m_bTypeSet} pair: a grid is shared between threads through {@link GridCache}, and a
 * mutable cache on a shared instance is exactly the pattern this port does not copy.
 *
 * @since 2.2.0
 */
public final class GenericGrid {

    /** {@code sizeof(float)}, for {@link #sizeBytes()}. */
    private static final long BYTES_PER_NODE = 4L;

    /**
     * A rough per-string charge for the retained metadata snapshot. The maps hold a handful of
     * short strings per IFD; charging 64 bytes an entry over-states them, which is the safe
     * direction for a cache bound.
     */
    private static final long BYTES_PER_METADATA_ENTRY = 64L;

    private final String name;
    private final int width;
    private final int height;
    private final boolean geographic;
    private final double west;
    private final double south;
    private final double east;
    private final double north;
    private final double resX;
    private final double resY;
    private final int samplesPerPixel;

    /**
     * {@code planes[sample][y * width + x]}, with {@code y == 0} the <b>southernmost</b> row.
     * {@link GeoTiffImage#readSamples} has already applied the bottom-up flip, so the orientation
     * here matches {@code GTiffGrid::valueAt}'s {@code yFromBottom} convention with no further
     * arithmetic. {@code null} only for the null grid.
     */
    private final float[][] planes;

    private final Map<String, String> metadata;

    /**
     * The metadata of IFD 0, consulted when this grid's own lookup comes back empty, or
     * {@code null} when no fallback applies. See {@link #metadataItem(String, int)}.
     */
    private final Map<String, String> inherited;

    private final String type;
    private final boolean hasNodata;
    private final float nodataValue;
    private final boolean nullGrid;
    private final List<GenericGrid> children;

    GenericGrid(String name, int width, int height, boolean geographic, double west, double south,
                double resX, double resY, int samplesPerPixel, float[][] planes,
                Map<String, String> metadata, Map<String, String> inherited, boolean hasNodata,
                float nodataValue, boolean nullGrid, List<GenericGrid> children) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.geographic = geographic;
        this.west = west;
        this.south = south;
        this.resX = resX;
        this.resY = resY;
        // Node-centred, exactly as GeoTiffImage computes it and as ExtentAndRes carries it: the
        // declared extent runs corner node to corner node, so it spans (width - 1) cells.
        this.east = west + resX * (width - 1);
        this.north = south + resY * (height - 1);
        this.samplesPerPixel = samplesPerPixel;
        this.planes = planes;
        this.metadata = metadata;
        this.inherited = inherited;
        this.hasNodata = hasNodata;
        this.nodataValue = nodataValue;
        this.nullGrid = nullGrid;
        this.children = children;
        // Eager, not lazy: see the class javadoc on immutability.
        this.type = metadataItem("TYPE", GeoTiffImage.GRID_LEVEL);
    }

    /**
     * {@code NullGenericShiftGrid} ({@code grids.cpp:2970-3000}): {@code +grids=null}. A 3&times;3
     * grid over the whole world with <b>zero</b> samples per pixel, whose every value is {@code 0}
     * and which every operator short-circuits on {@link #isNullGrid()} before asking how many
     * samples it has. That ordering is load bearing: {@code xyzgridshift} refuses a grid with fewer
     * than three samples, and the null grid has none.
     */
    static GenericGrid nullGrid() {
        return new GenericGrid("null", 3, 3, true, -Math.PI, -Math.PI / 2.0,
                Math.PI, Math.PI / 2.0, 0, null,
                Collections.<String, String>emptyMap(), null, false, 0f, true,
                Collections.<GenericGrid>emptyList());
    }

    // ------------------------------------------------------------------ description

    /** The grid's own name — the {@code grid_name} metadata item, or a synthesised IFD label. */
    public String getName() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** How many channels each node carries. <b>Zero</b> for the null grid. */
    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    /** {@code +grids=null}: covers everything, shifts nothing. */
    public boolean isNullGrid() {
        return nullGrid;
    }

    /**
     * Whether the grid is referenced in a geographic CRS. {@code gridshift} handles both;
     * {@code xyzgridshift} and {@code deformation} handle only the geographic case, because
     * {@link #interpolateThreeSamples} does.
     */
    public boolean isGeographic() {
        return geographic;
    }

    /** {@code [west, south, east, north]} in radians (or in the projected CRS's own linear unit). */
    public double[] extentRadians() {
        return new double[] {west, south, east, north};
    }

    /** {@code [resX, resY]}, in the same unit as {@link #extentRadians()}. */
    public double[] resolutionRadians() {
        return new double[] {resX, resY};
    }

    /** Nested subgrids, outermost first; unmodifiable, possibly empty. */
    public List<GenericGrid> children() {
        return children;
    }

    /** Total number of grids in this subtree, counting subgrids at any depth. */
    public int countGrids() {
        int n = 1;
        for (int i = 0; i < children.size(); i++) {
            n += children.get(i).countGrids();
        }
        return n;
    }

    // --------------------------------------------------------------------- metadata

    /**
     * {@code GTiffGenericGrid::metadataItem} ({@code grids.cpp:2870-2876}) — <b>with</b> upstream's
     * cross-IFD fallback.
     *
     * <p>{@code GTiffGenericGridShiftSet::open} ({@code grids.cpp:3033-3038}) wires a grid to IFD 0
     * when three things hold at once: it is not the first grid in the set, its own {@code TYPE} is
     * empty, and IFD 0's {@code TYPE} is not. The <em>trigger</em> is that {@code TYPE} test, but
     * once wired, the fallback applies to <b>every</b> key and every sample — {@code DESCRIPTION}
     * and {@code UNITTYPE} included, which is how a subgrid with no per-band metadata of its own
     * inherits the parent's band roles.
     *
     * <p>proj4j's {@code GdalMetadata} is strictly per-IFD and has no notion of a neighbour, which
     * is why the fallback lives here rather than in the TIFF reader: it is a property of the
     * <em>set</em>, decided when the set is assembled.
     *
     * @param key    the {@code name} attribute of the {@code GDAL_METADATA} item
     * @param sample the band index, or {@link GeoTiffImage#GRID_LEVEL}
     * @return the value, or the empty string; never {@code null}
     */
    public String metadataItem(String key, int sample) {
        String own = metadata.get(GeoTiffImage.metadataKey(key, sample));
        if (own != null && !own.isEmpty()) {
            return own;
        }
        if (inherited != null) {
            String up = inherited.get(GeoTiffImage.metadataKey(key, sample));
            if (up != null) {
                return up;
            }
        }
        return own == null ? "" : own;
    }

    /** A grid-level metadata item. */
    public String metadataItem(String key) {
        return metadataItem(key, GeoTiffImage.GRID_LEVEL);
    }

    /**
     * The dataset-level {@code TYPE}, e.g. {@code GEOCENTRIC_TRANSLATION} or
     * {@code HORIZONTAL_OFFSET}. The empty string when the file declares none.
     */
    public String type() {
        return type;
    }

    /** {@code UNITTYPE} for one band, or the empty string. */
    public String unit(int sample) {
        return metadataItem("UNITTYPE", sample);
    }

    /** {@code DESCRIPTION} for one band — the band's role name — or the empty string. */
    public String description(int sample) {
        return metadataItem("DESCRIPTION", sample);
    }

    // ------------------------------------------------------------------- node access

    /**
     * One node's value for one channel.
     *
     * <p>Upstream's {@code valueAt} returns {@code bool} because it may have to read from disk and
     * the read may fail. Here the planes are already decoded in memory, so the only way to fail is
     * to ask for a node or a channel that does not exist — a programming error, and thrown as one
     * rather than answered with a sentinel.
     *
     * @param x      column, 0 at the west edge
     * @param y      row, <b>0 at the south edge</b>
     * @param sample channel index
     * @return the value, after any {@code role="scale"} / {@code role="offset"} the file declared
     */
    public float valueAt(int x, int y, int sample) {
        if (nullGrid) {
            // NullGenericShiftGrid::valueAt: "out = 0.0f; return true;" -- for any index at all.
            return 0.0f;
        }
        if (sample < 0 || sample >= samplesPerPixel) {
            throw new IndexOutOfBoundsException("sample " + sample + " of grid " + name
                    + ", which has " + samplesPerPixel);
        }
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("node (" + x + ", " + y + ") of grid " + name
                    + ", which is " + width + "x" + height);
        }
        return planes[sample][y * width + x];
    }

    /** {@code GTiffGrid::isNodata} ({@code grids.cpp:935-937}): the declared sentinel, or any NaN. */
    public boolean isNodata(float value) {
        return (hasNodata && value == nodataValue) || Float.isNaN(value);
    }

    /**
     * A rectangular window of nodes for a chosen set of channels —
     * {@code GenericShiftGrid::valuesAt} ({@code grids.cpp:3068-3083}) over
     * {@code GTiffGrid::valuesAt} ({@code grids.cpp:775-931}).
     *
     * <p><b>The output order is sample-innermost:</b> row, then column, then channel. So a
     * 2&times;2 window of three channels fills {@code out} as
     * {@code [ (y0,x0,s0) (y0,x0,s1) (y0,x0,s2) (y0,x1,s0) ... ]}. Upstream's fast path in
     * {@code GTiffGrid::valuesAt} is a {@code memcpy} that only fires when the requested sample
     * indices are <em>consecutive</em>, precisely because that is the order it has to produce;
     * {@code gridshift.cpp} permutes its channel indices to hit it. Producing any other order here
     * would leave a future {@code gridshift} port reading the wrong channel out of the right
     * numbers, which is silent.
     *
     * <p><b>Nodata is a separate outcome, not a value.</b> Upstream reports it through a
     * {@code bool&amp;} out-parameter and every caller tests it <em>in addition to</em> the return
     * code: {@code if (!grid->valuesAt(...) || nodataFound) return false;}. The samples are still
     * written — a caller that ignores the flag gets the raw sentinel, e.g. {@code -32768} — so the
     * flag is the only thing standing between a nodata cell and a plausible-looking shift.
     *
     * @param xStart    westmost column of the window
     * @param yStart    southmost row of the window
     * @param xCount    columns
     * @param yCount    rows
     * @param sampleIdx the channels to read, in the order they should appear in {@code out}
     * @param out       receives {@code xCount * yCount * sampleIdx.length} values
     * @return whether any value read was nodata, i.e. upstream's {@code nodataFound}
     */
    public boolean valuesAt(int xStart, int yStart, int xCount, int yCount, int[] sampleIdx,
                            float[] out) {
        int needed = xCount * yCount * sampleIdx.length;
        if (out.length < needed) {
            throw new IllegalArgumentException("out is " + out.length + " floats, and a " + xCount
                    + "x" + yCount + " window of " + sampleIdx.length + " samples needs " + needed);
        }
        boolean nodataFound = false;
        int at = 0;
        for (int y = yStart; y < yStart + yCount; y++) {
            for (int x = xStart; x < xStart + xCount; x++) {
                for (int i = 0; i < sampleIdx.length; i++) {
                    float v = valueAt(x, y, sampleIdx[i]);
                    if (isNodata(v)) {
                        nodataFound = true;
                    }
                    out[at++] = v;
                }
            }
        }
        return nodataFound;
    }

    // ----------------------------------------------------------------------- lookup

    /**
     * {@code isPointInExtent} ({@code grids.cpp:1689-1704}) with upstream's default
     * {@code eps = 0}.
     *
     * <p><b>The generic path has no tolerance slack, and that is deliberate.</b> The horizontal
     * grid path calls the same function with {@code (resX + resY) * REL_TOLERANCE_HGRIDSHIFT};
     * {@code GenericShiftGridSet::gridAt} does not pass an {@code eps} at all. Adding the slack
     * here would accept points upstream refuses, which is the wrong direction to differ in.
     *
     * @param lam longitude in radians, or easting for a projected grid
     * @param phi latitude in radians, or northing
     */
    public boolean covers(double lam, double phi) {
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            return false;
        }
        if (!(phi >= south && phi <= north)) {
            return false;
        }
        if (isFullWorldLongitude()) {
            return true;
        }
        double x = lam;
        if (geographic) {
            if (x < west) {
                x += 2 * Math.PI;
            } else if (x > east) {
                x -= 2 * Math.PI;
            }
        }
        return x >= west && x <= east;
    }

    /**
     * {@code GenericShiftGrid::gridAt} ({@code grids.cpp:3159-3167}): the first child whose extent
     * contains the point, recursively, and otherwise this grid.
     *
     * <p>Note what it is <em>not</em>: it does not check that {@code this} contains the point. The
     * set has already established that, and a grid reached through {@link GenericGridSet#gridAt}
     * therefore always covers its argument.
     */
    public GenericGrid gridAt(double lam, double phi) {
        for (int i = 0; i < children.size(); i++) {
            GenericGrid child = children.get(i);
            if (child.covers(lam, phi)) {
                return child.gridAt(lam, phi);
            }
        }
        return this;
    }

    // ---------------------------------------------------------------- interpolation

    /**
     * {@code pj_bilinear_interpolation_three_samples} ({@code grids.cpp:3844-3922}) — the whole of
     * what {@code +proj=xyzgridshift} and {@code +proj=deformation}'s GeoTIFF form read out of a
     * generic grid.
     *
     * <p>Transcribed rather than rewritten, weight by weight, because the summation order decides
     * the last bits and the corpus's expected values were generated by it. Three details are worth
     * naming:
     *
     * <ul>
     * <li>The null grid answers three zeroes and does not touch its (nonexistent) samples.</li>
     * <li>The &plusmn;2&pi; longitude wrap fires only <em>outside</em> the declared extent, and the
     *     grid is then indexed as if the point had been given on the other side of the
     *     antimeridian.</li>
     * <li>{@code ix2 = min(ix + 1, width - 1)} clamps rather than wrapping, so the east edge
     *     degenerates to a linear interpolation in latitude alone. Combined with node-centred
     *     extents ({@code east = west + resX * (width - 1)}) this cannot read out of bounds for a
     *     point the grid covers.</li>
     * </ul>
     *
     * <p>Upstream additionally re-reads the grid when {@code grid->hasChanged()} — a network-grid
     * concern. proj4j's grids are parsed once from a byte array that is never revalidated, so
     * there is no {@code must_retry} loop and nothing to port.
     *
     * @param grid the grid to read, normally the result of {@link GenericGridSet#gridAt}
     * @param lam  longitude, radians
     * @param phi  latitude, radians
     * @param s1   channel for {@code out[0]}
     * @param s2   channel for {@code out[1]}
     * @param s3   channel for {@code out[2]}
     * @param out  receives the three interpolated values, in the file's own units
     * @throws IllegalStateException if the grid is not geographic. Upstream refuses this here, at
     *                               transform time, with
     *                               {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID} and the
     *                               message <i>"Can only handle grids referenced in a geographic
     *                               CRS"</i>. proj4j's operators raise that same refusal one call
     *                               earlier, as a {@code CrsTransformException} naming the grid —
     *                               at the same moment in the sequence, so a definition PROJ
     *                               accepts is still accepted. Reaching this throw therefore means
     *                               an operator forgot to check.
     */
    public static void interpolateThreeSamples(GenericGrid grid, double lam, double phi,
                                               int s1, int s2, int s3, double[] out) {
        if (grid.nullGrid) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        if (!grid.geographic) {
            throw new IllegalStateException("Can only handle grids referenced in a geographic CRS: "
                    + grid.name + " is not. This should have been refused when the operator was "
                    + "built.");
        }

        final double invResX = 1.0 / grid.resX;
        final double invResY = 1.0 / grid.resY;

        double gridX = (lam - grid.west) * invResX;
        if (lam < grid.west) {
            gridX = (lam + 2 * Math.PI - grid.west) * invResX;
        } else if (lam > grid.east) {
            gridX = (lam - 2 * Math.PI - grid.west) * invResX;
        }
        final double gridY = (phi - grid.south) * invResY;

        final int ix = (int) gridX;
        final int iy = (int) gridY;
        final int ix2 = Math.min(ix + 1, grid.width - 1);
        final int iy2 = Math.min(iy + 1, grid.height - 1);

        final float dx1 = grid.valueAt(ix, iy, s1);
        final float dx2 = grid.valueAt(ix2, iy, s1);
        final float dx3 = grid.valueAt(ix, iy2, s1);
        final float dx4 = grid.valueAt(ix2, iy2, s1);
        final float dy1 = grid.valueAt(ix, iy, s2);
        final float dy2 = grid.valueAt(ix2, iy, s2);
        final float dy3 = grid.valueAt(ix, iy2, s2);
        final float dy4 = grid.valueAt(ix2, iy2, s2);
        final float dz1 = grid.valueAt(ix, iy, s3);
        final float dz2 = grid.valueAt(ix2, iy, s3);
        final float dz3 = grid.valueAt(ix, iy2, s3);
        final float dz4 = grid.valueAt(ix2, iy2, s3);

        // Upstream's own variable names and order. frct_phi is reused, which is why m11 and m01
        // are multiplied before it is replaced by its complement.
        final double frctLam = gridX - ix;
        double frctPhi = gridY - iy;
        double m10 = frctLam;
        double m11 = m10;
        double m01 = 1.0 - frctLam;
        double m00 = m01;
        m11 *= frctPhi;
        m01 *= frctPhi;
        frctPhi = 1.0 - frctPhi;
        m00 *= frctPhi;
        m10 *= frctPhi;

        out[0] = m00 * dx1 + m10 * dx2 + m01 * dx3 + m11 * dx4;
        out[1] = m00 * dy1 + m10 * dy2 + m01 * dy3 + m11 * dy4;
        out[2] = m00 * dz1 + m10 * dz2 + m01 * dz3 + m11 * dz4;
    }

    // ------------------------------------------------------------------ housekeeping

    private boolean isFullWorldLongitude() {
        return geographic && east - west + resX >= 2 * Math.PI - 1e-10;
    }

    /** Accounted heap cost of this subtree, for {@link GridCache}. */
    long sizeBytes() {
        long total = 0L;
        if (planes != null) {
            for (int i = 0; i < planes.length; i++) {
                total += (long) planes[i].length * BYTES_PER_NODE;
            }
        }
        total += (long) metadata.size() * BYTES_PER_METADATA_ENTRY;
        for (int i = 0; i < children.size(); i++) {
            total += children.get(i).sizeBytes();
        }
        return total;
    }

    @Override
    public String toString() {
        return "GenericGrid[" + name + "; " + width + "x" + height + "x" + samplesPerPixel
                + (type.isEmpty() ? "" : "; " + type) + "]";
    }
}
