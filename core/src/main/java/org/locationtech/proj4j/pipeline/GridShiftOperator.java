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

import java.io.IOException;
import java.util.List;

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.GenericGrid;
import org.locationtech.proj4j.datum.GenericGridSet;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +proj=gridshift} — the general grid shift, ported from
 * {@code 9.8.1:src/transformations/gridshift.cpp}.
 *
 * <h2>What it is for</h2>
 *
 * <p>{@code hgridshift} reads two bands of horizontal offset; {@code vgridshift} reads one band of
 * height offset. This operator reads whatever a Geodetic TIFF Grid declares it holds, in any
 * combination, and it is the only one of the three that can read a <b>projected</b> grid or apply
 * NADCON5's <b>biquadratic</b> interpolation. In practice that makes it the operator behind the
 * NADCON5 realisation-to-realisation transformations of the United States, and behind the S-JTSK
 * grid whose two constant offsets are described below.
 *
 * <h2>The dataset TYPE decides everything</h2>
 *
 * <p>Each grid in the file declares a {@code TYPE}, and the set of types present decides which grid
 * is consulted for what:
 *
 * <ul>
 * <li>{@code GEOGRAPHIC_3D_OFFSET} — latitude, longitude and height in one grid. When any grid says
 *     this, it is the main type and no second pass is needed.</li>
 * <li>{@code HORIZONTAL_OFFSET} — latitude and longitude only. It is the main type when there is no
 *     3D grid, and then any one vertical grid present becomes the <em>auxiliary</em> type, applied
 *     in a second pass at the already-shifted horizontal position.</li>
 * <li>{@code ELLIPSOIDAL_HEIGHT_OFFSET}, {@code VERTICAL_OFFSET_VERTICAL_TO_VERTICAL},
 *     {@code VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL} — a height only. At most one of these three
 *     may appear; a file mixing two is refused.</li>
 * </ul>
 *
 * <p>An empty or unrecognised {@code TYPE} is refused at setup, which is also what makes
 * {@code +grids=null} a definition error here rather than the no-op it is for other operators:
 * the null grid declares no type. Verified against 9.8.1's {@code cct}, which answers
 * {@code 1029 gridshift: Missing TYPE metadata item in grid(s).}
 *
 * <h2>The two constant offsets are asymmetric, on purpose</h2>
 *
 * <p>A single {@code HORIZONTAL_OFFSET} grid may carry a {@code constant_offset} on each of its
 * first two bands. It is added <b>after</b> the grid shift in the forward direction and subtracted
 * <b>before</b> it in the reverse — not the mirror image of each other, which would be to subtract
 * it after. That is upstream's order ({@code gridshift.cpp:838-877}, whose comment on the reverse
 * function reads "Must be done before using m_offsetX !") and it is what makes the two directions
 * inverses of one another, because the grid is indexed in the frame that does not include the
 * offset. The S-JTSK/05 grid uses it to carry a 5,000,000 m false origin.
 *
 * <h2>The inverse iterates, except when it does not</h2>
 *
 * <p>A grid gives the shift at a position in the source frame, so undoing it needs the source
 * position, which is what is being solved for. The bilinear inverse therefore iterates: guess,
 * read, correct, at most ten times, to a tolerance of 1e-12 in the grid's own units.
 *
 * <p>The <b>biquadratic inverse does not iterate at all</b>. One subtraction of the shift read at
 * the target position is the answer. That is not an approximation left in by accident: upstream
 * followed NOAA's own NCAT transformer, and the comment at {@code gridshift.cpp:649-659} records
 * that iterating a biquadratic inverse fails to converge for points near a cell or half-cell
 * boundary — with a worked example that is in this corpus as {@code gridshift.gie}'s San Francisco
 * block. A "more correct" iterating inverse fails that block.
 *
 * <p>The iterating branch can also change grids in mid-loop. If a guess wanders out of the grid it
 * started in, the loop looks for another grid of the same type covering the guess and carries on
 * there; failing that it keeps the last good iterate and says nothing.
 *
 * <h2>Where this departs from upstream</h2>
 *
 * <p>Three places, each because upstream's version is mutable state on an object proj4j shares
 * between threads:
 *
 * <ol>
 * <li><b>The units are always declared, never deferred.</b> Upstream's code reads as if it declares
 *     {@code PJ_IO_UNITS_WHATEVER} whenever it has neither seen this {@code +grids=} string before
 *     nor been told {@code +coord_type} ({@code gridshift.cpp:1007-1018}). The operation that
 *     {@code proj_create} actually hands back never does. It builds the object <b>twice</b>: once as
 *     a throwaway validation build inside {@code PROJStringParser::createFromPROJString}
 *     ({@code io.cpp:12717-12743}, with the log level forced to errors-only at {@code :12730},
 *     which is why no amount of {@code PROJ_DEBUG} shows it), and once for real
 *     ({@code c_api.cpp:227}). The first build finds the process-global {@code gKnownGrids} cache
 *     empty, opens the grid, records what it found ({@code gridshift.cpp:951}) — and is discarded.
 *     The second build hits that cache and takes the declared branch. Measured over the whole of
 *     {@code gridshift.gie} with {@code gie -vvvvv}: 26 rows print {@code left: 4 right: 4}
 *     (radians), one prints {@code left: 2 right: 2} (projected), and none prints
 *     {@code WHATEVER}. proj4j has no deferred grid opening and no process-global cache keyed on the
 *     {@code +grids=} text, so it simply declares what the second build would.</li>
 * <li><b>The main type does not stick.</b> Upstream holds the working type in a
 *     {@code std::string&amp;} bound to its own member ({@code gridshift.cpp:759}), so the
 *     3D-to-horizontal fallback at {@code :768} overwrites the member permanently: one coordinate
 *     that falls outside every 3D grid changes the main type for every coordinate afterwards. Here
 *     the working type is a local, so the fallback lasts one coordinate. Reported rather than
 *     silently matched, and unreachable from any corpus row — the fallback needs a definition
 *     mixing a 3D grid with a horizontal one.</li>
 * <li><b>No per-grid cache of the last window read.</b> See {@link GenericShiftKernel}; it cannot
 *     change an answer.</li>
 * </ol>
 *
 * <p>Upstream also reopens a grid whose file has changed underneath it, retrying the transform once.
 * proj4j reads a grid fully into memory when it opens it and has no notion of a file changing, so
 * the retry loop collapses to a single pass.
 *
 * <p>One difference is <b>not</b> this operator's and is recorded here because this is where it shows
 * up. PROJ's framework wraps an angular input longitude into
 * {@code [-pi, pi]} before any operator sees it ({@code adjlon} in {@code 9.8.1:src/fwd.cpp:85} and
 * again at {@code :112}); proj4j's pipeline engine does not. So for {@code gridshift.gie}'s
 * {@code accept 180.1833333}, PROJ prints {@code -179.8166667} and proj4j prints
 * {@code 180.1833333}. They are the same point on the earth, the shift applied is identical, and the
 * corpus row passes because its comparison is a distance and not a string. Anyone diffing printed
 * longitudes against {@code cs2cs} near the antimeridian should expect the spelling to differ by one
 * turn. Note this operator's own {@code normalizeX} is a separate mechanism and is still needed: it
 * moves a longitude by a whole turn to reach a grid, not to reach a canonical range.
 *
 * <p>Immutable after construction; safe to share across threads.
 *
 * @since 2.3.0
 */
final class GridShiftOperator implements PipelineOperator {

    /** {@code MAX_ITERATIONS} ({@code gridshift.cpp:603}). */
    private static final int MAX_ITERATIONS = 10;

    /** {@code TOL} ({@code gridshift.cpp:604}), compared squared. */
    private static final double TOL = 1e-12;

    private final List<GenericGridSet> grids;
    private final String mainGridType;
    private final String auxGridType;
    private final boolean mainGridTypeIsGeographic3DOffset;
    private final boolean hasHorizontalOffset;
    private final boolean hasGeographic3DOffset;
    private final String interpolation;
    private final boolean skipZTransform;
    private final double offsetX;
    private final double offsetY;
    private final GieIoUnits units;
    private final String description;

    /**
     * @param params the step's fully expanded parameter list
     */
    GridShiftOperator(final ProjParams params) {
        // gridshift.cpp:913-916. pj_param's 't' sigil: presence, not a usable value.
        if (!params.has("grids")) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+proj=gridshift: +grids parameter missing.");
        }
        final String spec = params.value("grids");

        // gridshift.cpp:936-948. Upstream has a deferred-opening path; proj4j has none, so this is
        // always the eager branch, and both of its refusals carry the same error code.
        try {
            this.grids = GenericGridSet.fromGridsSpec(spec);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+proj=gridshift +grids=" + spec
                            + ": could not find required grid(s): " + e.getMessage(), e);
        }

        final GridTypes types = checkGridTypes(this.grids, spec);
        this.hasHorizontalOffset = types.hasHorizontalOffset;
        this.hasGeographic3DOffset = types.hasGeographic3DOffset;
        this.mainGridType = types.mainGridType;
        this.auxGridType = types.auxGridType;
        this.mainGridTypeIsGeographic3DOffset = types.mainGridTypeIsGeographic3DOffset;
        this.offsetX = types.offsetX;
        this.offsetY = types.offsetY;
        boolean projectedCoord = types.projectedCoord;

        // gridshift.cpp:955-966. Note the order: this is checked AFTER the grids are opened, so a
        // definition with both a bad +interpolation and an unreadable grid reports the grid.
        if (params.has("interpolation")) {
            final String value = params.value("interpolation");
            if (!GenericShiftKernel.BILINEAR.equals(value)
                    && !GenericShiftKernel.BIQUADRATIC.equals(value)) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+proj=gridshift: Unsupported value for +interpolation"
                                + (value == null ? "" : ": " + value)
                                + ". Expected bilinear or biquadratic.");
            }
            this.interpolation = value;
        } else {
            this.interpolation = "";
        }

        // gridshift.cpp:968-970.
        this.skipZTransform = params.has("no_z_transform");

        // gridshift.cpp:976-1005. Undocumented upstream and only useful with deferred grid
        // opening, but it is a parameter PROJ reads, so it is one proj4j has to read the same way -
        // including the fact that all three of its refusals report a missing argument rather than
        // an illegal value, which reads like a copy-paste but is what the errno says.
        if (params.has("coord_type")) {
            final String value = params.value("coord_type");
            if ("projected".equals(value)) {
                if (!projectedCoord) {
                    throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                            "+proj=gridshift: +coord_type=projected specified, but the grid is"
                                    + " known to not be projected");
                }
                projectedCoord = true;
            } else if ("geographic".equals(value)) {
                if (projectedCoord) {
                    throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                            "+proj=gridshift: +coord_type=geographic specified, but the grid is"
                                    + " known to be projected");
                }
            } else {
                throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                        "+proj=gridshift: Unsupported value for +coord_type: valid values are"
                                + " 'geographic' or 'projected'");
            }
        }

        // gridshift.cpp:1007-1018, taking the declared branch always. See the class comment.
        this.units = projectedCoord ? GieIoUnits.PROJECTED : GieIoUnits.RADIANS;

        final StringBuilder d = new StringBuilder("gridshift grids=").append(spec);
        if (!interpolation.isEmpty()) {
            d.append(" interpolation=").append(interpolation);
        }
        if (skipZTransform) {
            d.append(" no_z_transform");
        }
        this.description = d.toString();
    }

    // ===================================================================== setup: grid types

    /** What {@code checkGridTypes} works out, as one value so the caller's fields stay final. */
    private static final class GridTypes {
        boolean hasHorizontalOffset;
        boolean hasGeographic3DOffset;
        boolean mainGridTypeIsGeographic3DOffset;
        String mainGridType = "";
        String auxGridType = "";
        boolean projectedCoord;
        double offsetX;
        double offsetY;
    }

    /**
     * {@code gridshiftData::checkGridTypes} ({@code gridshift.cpp:127-220}).
     *
     * <p>Only the root grids of each set are examined, never their subgrids — a subgrid with no
     * {@code TYPE} of its own inherits the first root's, which is how a multi-IFD file with one
     * declaration at the top works at all.
     *
     * <p>{@code projectedCoord} is assigned inside the loop with no accumulation, so it ends up
     * describing the <em>last</em> root grid examined. That looks like a bug for a set holding a
     * mix, and it is reproduced anyway: it is what decides the declared unit domain, and a mixed set
     * has no single right answer.
     */
    private static GridTypes checkGridTypes(final List<GenericGridSet> sets, final String spec) {
        final GridTypes t = new GridTypes();
        boolean hasEllipsoidalHeightOffset = false;
        boolean hasVerticalToVertical = false;
        boolean hasGeographicToVertical = false;
        String offsetX = "";
        String offsetY = "";
        int gridCount = 0;

        for (int s = 0; s < sets.size(); s++) {
            final List<GenericGrid> roots = sets.get(s).grids();
            for (int g = 0; g < roots.size(); g++) {
                final GenericGrid grid = roots.get(g);
                gridCount++;
                final String type = grid.metadataItem("TYPE");
                if (GenericShiftKernel.HORIZONTAL_OFFSET.equals(type)) {
                    t.hasHorizontalOffset = true;
                    if (offsetX.isEmpty()) {
                        offsetX = grid.metadataItem("constant_offset", 0);
                    }
                    if (offsetY.isEmpty()) {
                        offsetY = grid.metadataItem("constant_offset", 1);
                    }
                } else if (GenericShiftKernel.GEOGRAPHIC_3D_OFFSET.equals(type)) {
                    t.hasGeographic3DOffset = true;
                } else if (GenericShiftKernel.ELLIPSOIDAL_HEIGHT_OFFSET.equals(type)) {
                    hasEllipsoidalHeightOffset = true;
                } else if (GenericShiftKernel.VERTICAL_OFFSET_VERTICAL_TO_VERTICAL.equals(type)) {
                    hasVerticalToVertical = true;
                } else if (GenericShiftKernel.VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL.equals(type)) {
                    hasGeographicToVertical = true;
                } else if (type.isEmpty()) {
                    throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                            "+proj=gridshift +grids=" + spec
                                    + ": Missing TYPE metadata item in grid(s).");
                } else {
                    throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                            "+proj=gridshift +grids=" + spec
                                    + ": Unhandled value for TYPE metadata item in grid(s): "
                                    + type);
                }
                t.projectedCoord = !grid.isGeographic();
            }
        }

        if (!offsetX.isEmpty() || !offsetY.isEmpty()) {
            if (gridCount > 1) {
                throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                        "+proj=gridshift +grids=" + spec + ": Shift offset found in one grid."
                                + " Only one grid with shift offset is supported at a time.");
            }
            // c_locale_stod throws for an empty string too, so a file declaring an offset on only
            // one of its two bands is refused rather than given a zero for the other.
            t.offsetX = parseOffset(offsetX, spec);
            t.offsetY = parseOffset(offsetY, spec);
        }

        if ((hasEllipsoidalHeightOffset ? 1 : 0) + (hasVerticalToVertical ? 1 : 0)
                + (hasGeographicToVertical ? 1 : 0) > 1) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+proj=gridshift +grids=" + spec + ": Unsupported mix of grid types.");
        }

        if (t.hasGeographic3DOffset) {
            t.mainGridTypeIsGeographic3DOffset = true;
            t.mainGridType = GenericShiftKernel.GEOGRAPHIC_3D_OFFSET;
        } else if (!t.hasHorizontalOffset) {
            if (hasEllipsoidalHeightOffset) {
                t.mainGridType = GenericShiftKernel.ELLIPSOIDAL_HEIGHT_OFFSET;
            } else if (hasGeographicToVertical) {
                t.mainGridType = GenericShiftKernel.VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL;
            } else {
                // Upstream asserts hasVerticalToVertical here, and the assertion is not quite true:
                // +grids=@i_do_not_exist.tif leaves no grids at all, since a leading @ makes a
                // missing file a silently shorter list. In a release build the assert is a no-op and
                // the type lands here, which is harmless - every later grid lookup misses, so the
                // definition builds and every coordinate is refused. Measured on 9.8.1's cct:
                // "TRANSFORMATION ERROR ... (Coordinate to transform falls outside grid)".
                t.mainGridType = GenericShiftKernel.VERTICAL_OFFSET_VERTICAL_TO_VERTICAL;
            }
        } else {
            t.mainGridType = GenericShiftKernel.HORIZONTAL_OFFSET;
        }

        if (t.hasHorizontalOffset) {
            if (hasEllipsoidalHeightOffset) {
                t.auxGridType = GenericShiftKernel.ELLIPSOIDAL_HEIGHT_OFFSET;
            } else if (hasGeographicToVertical) {
                t.auxGridType = GenericShiftKernel.VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL;
            } else if (hasVerticalToVertical) {
                t.auxGridType = GenericShiftKernel.VERTICAL_OFFSET_VERTICAL_TO_VERTICAL;
            }
        }
        return t;
    }

    private static double parseOffset(final String text, final String spec) {
        try {
            return Double.parseDouble(text);
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+proj=gridshift +grids=" + spec + ": Invalid offset value: '" + text + "'", e);
        }
    }

    // ============================================================================ units

    /** {@code gridshift.cpp:1009}/{@code :1012} — the same value on both sides. */
    @Override
    public GieIoUnits declaredLeft() {
        return units;
    }

    /** {@code gridshift.cpp:1010}/{@code :1013}. */
    @Override
    public GieIoUnits declaredRight() {
        return units;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    // ======================================================================== the transform

    /** {@code pj_gridshift_forward_3d} ({@code gridshift.cpp:838-856}). */
    @Override
    public void forward(final double[] coord) {
        apply(coord, false);
        coord[0] += offsetX;
        coord[1] += offsetY;
    }

    /**
     * {@code pj_gridshift_reverse_3d} ({@code gridshift.cpp:860-877}) — the offsets come off
     * <em>before</em> the grid is read, which is not the mirror of {@link #forward}. See the class
     * comment.
     */
    @Override
    public void inverse(final double[] coord) {
        coord[0] -= offsetX;
        coord[1] -= offsetY;
        apply(coord, true);
    }

    /**
     * {@code gridshiftData::apply} ({@code gridshift.cpp:752-832}): one pass for the main type,
     * then a second for the auxiliary vertical type if there is one and the first pass did not
     * already carry the height.
     */
    private void apply(final double[] coord, final boolean inverse) {
        // Upstream holds this in a reference to its own member, so the fallback below is sticky.
        // Here it is a local. See the class comment.
        String type = mainGridType;
        boolean foundGeog3DOffset = false;

        GenericGrid grid = findGrid(type, coord[0], coord[1]);
        if (grid == null) {
            if (mainGridTypeIsGeographic3DOffset && hasHorizontalOffset) {
                // A definition mixing a 3D grid with a horizontal one plus a height one: outside
                // the 3D grid, fall back to the pair.
                type = GenericShiftKernel.HORIZONTAL_OFFSET;
                grid = findGrid(type, coord[0], coord[1]);
            }
            if (grid == null) {
                throw outsideGrid(coord);
            }
        } else if (mainGridTypeIsGeographic3DOffset) {
            foundGeog3DOffset = true;
        }

        if (!grid.isNullGrid()) {
            // isVerticalOnly: when neither a 3D nor a horizontal grid is in play, the only thing
            // this operator can do is move z, and the inverse of that is a subtraction rather than
            // an iteration.
            applyOneGrid(type, !(hasGeographic3DOffset || hasHorizontalOffset), coord, inverse,
                    grid);
        }

        if (!foundGeog3DOffset && !auxGridType.isEmpty()) {
            final GenericGrid aux = findGrid(auxGridType, coord[0], coord[1]);
            if (aux == null) {
                throw outsideGrid(coord);
            }
            if (!aux.isNullGrid()) {
                applyOneGrid(auxGridType, true, coord, inverse, aux);
            }
        }
    }

    /**
     * {@code gridshiftData::grid_apply_internal} ({@code gridshift.cpp:606-723}), mutating
     * {@code coord} in place.
     *
     * <p>Upstream's {@code HUGE_VAL} returns become throws, with one exception that matters: the
     * "presumably at grid edge" path at {@code :710-715}, where the iteration gave up but the last
     * good iterate is kept and returned. That is a success upstream and a success here.
     */
    private void applyOneGrid(final String type, final boolean verticalOnly, final double[] coord,
                              final boolean inverse, final GenericGrid startGrid) {
        GenericGrid grid = startGrid;
        final double inX = coord[0];
        final double inY = coord[1];
        final double inZ = coord[2];

        final double[] shift = new double[3];
        final boolean[] biquadratic = new boolean[1];
        final float[] scratch = new float[GenericShiftKernel.SCRATCH_FLOATS];

        double normalizedX = normalizeX(grid, inX);
        final double normalizedY = inY;

        if (!GenericShiftKernel.interpolate(grid, type, interpolation, skipZTransform,
                normalizedX, normalizedY, shift, biquadratic, scratch)) {
            throw outsideGrid(coord);
        }

        if (!inverse) {
            coord[0] = inX + shift[0];
            coord[1] = inY + shift[1];
            coord[2] = inZ + shift[2];
            return;
        }

        if (verticalOnly) {
            coord[2] = inZ - shift[2];
            return;
        }

        double guessX = normalizedX - shift[0];
        double guessY = normalizedY - shift[1];

        if (!biquadratic[0]) {
            int i = MAX_ITERATIONS;
            final double toltol = TOL * TOL;
            double diffX = 0.0;
            double diffY = 0.0;
            do {
                if (!GenericShiftKernel.interpolate(grid, type, interpolation, skipZTransform,
                        guessX, guessY, shift, biquadratic, scratch)) {
                    // The guess may have wandered out of this grid; look for another of the same
                    // type that covers it. Failing that, keep the last good iterate - which is
                    // upstream's "using first approximation", and note that a failed read leaves
                    // shift[2] at zero, so z comes through unchanged.
                    final GenericGrid newGrid = findGrid(type, guessX, guessY);
                    if (newGrid == null || newGrid == grid || newGrid.isNullGrid()) {
                        break;
                    }
                    grid = newGrid;
                    normalizedX = normalizeX(grid, inX);
                    diffX = Double.MAX_VALUE;
                    diffY = Double.MAX_VALUE;
                    continue;
                }
                diffX = guessX + shift[0] - normalizedX;
                diffY = guessY + shift[1] - normalizedY;
                guessX -= diffX;
                guessY -= diffY;
            } while (--i != 0 && diffX * diffX + diffY * diffY > toltol);

            if (i == 0) {
                throw new CrsTransformException(ErrorCause.NUMERICAL_FAILURE,
                        "+proj=" + description + ": inverse grid shift iterator failed to converge"
                                + " at (" + inX + ", " + inY + ")");
            }
        }

        coord[0] = grid.isGeographic() ? ProjectionMath.adjlon(guessX) : guessX;
        coord[1] = guessY;
        coord[2] = inZ - shift[2];
    }

    /** {@code gridshiftData::findGrid} ({@code gridshift.cpp:224-235}). */
    private GenericGrid findGrid(final String type, final double x, final double y) {
        for (int i = 0; i < grids.size(); i++) {
            final GenericGrid grid = grids.get(i).gridAt(type, x, y);
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    /**
     * {@code normalizeX} ({@code gridshift.cpp:584-599}): a longitude one whole turn away from the
     * grid's extent is brought back into it. Load bearing for a grid that crosses the
     * antimeridian, such as the Alaska NADCON5 extract, whose second image starts at 200.625
     * degrees east.
     */
    private static double normalizeX(final GenericGrid grid, final double x) {
        if (!grid.isGeographic()) {
            return x;
        }
        final double[] extent = grid.extentRadians();
        final double[] res = grid.resolutionRadians();
        final double epsilon = (res[0] + res[1]) * GenericShiftKernel.REL_TOLERANCE_HGRIDSHIFT;
        if (x < extent[0] - epsilon) {
            return x + 2 * Math.PI;
        }
        if (x > extent[2] + epsilon) {
            return x - 2 * Math.PI;
        }
        return x;
    }

    private CrsTransformException outsideGrid(final double[] coord) {
        return new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                "(" + coord[0] + ", " + coord[1] + ") is outside every usable grid of +proj="
                        + description);
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
        return "GridShiftOperator[" + description + "]";
    }
}
