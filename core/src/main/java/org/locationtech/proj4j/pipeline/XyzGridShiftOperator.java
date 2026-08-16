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
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.GenericGrid;
import org.locationtech.proj4j.datum.GenericGridSet;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=xyzgridshift} — a geocentric translation read out of a grid, ported from
 * {@code 9.8.1:src/transformations/xyzgridshift.cpp}.
 *
 * <h2>The operation</h2>
 *
 * <p>Input and output are both <b>geocentric cartesian metres</b> ({@code P-&gt;left} and
 * {@code P-&gt;right} are {@code PJ_IO_UNITS_CARTESIAN}), which is why the corpus always writes
 * this between {@code +proj=cart} steps. The grid is indexed geographically and carries three
 * channels of &Delta;X, &Delta;Y, &Delta;Z in metres, so each evaluation converts the cartesian
 * position to geodetic purely to find the cell, then adds the interpolated triple back in the
 * cartesian frame. This is the classic NTF&rarr;RGF93 {@code gr3df97a} arrangement.
 *
 * <h2>{@code +grid_ref} decides which of the two algorithms runs</h2>
 *
 * <p>A grid of &Delta;X,&Delta;Y,&Delta;Z is indexed by a position, and the two datums disagree
 * about that position, so the question "which datum is the index in?" has to be answered
 * explicitly. {@code +grid_ref=input_crs} (the default) says the index is in the source frame, so
 * one lookup at the input position is exact — {@code direct_adjustment}.
 * {@code +grid_ref=output_crs} says the index is in the target frame, which is not known until the
 * shift is known, so the shift is found by fixed-point iteration —
 * {@code iterative_adjustment}, at most ten passes.
 *
 * <p>The inverse swaps them: undoing a direct shift needs iteration, undoing an iterated shift is
 * direct. Only the sign of the applied shift changes ({@code factor = -1}); the grid is always
 * read at the current iterate's own geodetic position.
 *
 * <p>Two details of the loop are transcribed rather than tidied, because they decide the last
 * digits of every expected value:
 * <ul>
 * <li>{@code err} is formed from the <em>previous</em> iterate — the displacement already applied
 *     minus the displacement just read — and is tested <em>after</em> the new iterate has been
 *     stored. So the loop always applies the shift it just computed, even on the pass that
 *     breaks.</li>
 * <li>There is no convergence check after the loop. If ten passes do not reach {@code 1e-10} m²
 *     (10 µm of total displacement error) the tenth pass's answer is returned with nothing said.
 *     Upstream does the same; {@code XyzGridShiftOperatorTest} measures the closure rather than
 *     trusting it.</li>
 * </ul>
 *
 * <h2>Which channel is which</h2>
 *
 * <p>Channels default to 0, 1, 2 <em>positionally</em> and are then overridden by any band whose
 * {@code DESCRIPTION} is {@code x_translation}, {@code y_translation} or {@code z_translation}
 * ({@code xyzgridshift.cpp:80-91}). Note the shape of that loop: it is not "use the names if all
 * three are present" — a file naming only {@code z_translation}, on band 0, gets
 * {@code sampleX = 0} and {@code sampleZ = 0} and is read twice from the same band, with no
 * complaint. Ported as written.
 *
 * <p>{@code UNITTYPE} is checked on the X channel <b>only</b>, and only against empty or
 * {@code metre}; a file declaring {@code UNITTYPE=millimetre} on Y alone is accepted and its Y
 * read as metres. Also upstream's, also ported as written, because a file like that is
 * out of spec and PROJ's answer for it is the answer we owe.
 *
 * <h2>What is refused, and when</h2>
 *
 * <p>At <em>setup</em>, in upstream's own order: an unrecognised {@code +grid_ref} first
 * (before {@code +grids} is even looked for), then a missing {@code +grids}, then a grid file that
 * cannot be found or read. At <em>transform</em> time: a position no grid covers, a grid with
 * fewer than three channels, a non-metre X channel, and a grid referenced in a projected CRS. The
 * split is upstream's and is kept exactly, because moving any of the second group into the first
 * would turn a definition PROJ accepts into one proj4j rejects — the grid that fails may be one
 * this coordinate never reaches.
 *
 * <p>Immutable after construction; safe to share across threads.
 *
 * @since 2.2.0
 */
final class XyzGridShiftOperator implements PipelineOperator {

    /** {@code for (int i = 0; i < 10; i++)} ({@code xyzgridshift.cpp:120}). */
    private static final int MAX_ITERATIONS = 10;

    /** {@code if (err < 1e-10) break;} — a squared distance, so 10 µm of displacement. */
    private static final double CONVERGENCE = 1e-10;

    private final CartConversion cart;
    private final boolean gridRefIsInput;
    private final List<GenericGridSet> grids;
    private final double multiplier;
    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    XyzGridShiftOperator(final Registry registry, final ProjParams params) {
        // xyzgridshift.cpp:236-243: the cart child is created and given P's ellipsoid before any
        // parameter is validated, so the ellipsoid is resolved first here too. StepEllipsoid is
        // pj_inherit_ellipsoid_def's equivalent.
        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        this.cart = new CartConversion(ellipsoid[0], ellipsoid[1]);

        // xyzgridshift.cpp:245-259. Note that this is checked BEFORE +grids: a definition with a
        // bad +grid_ref and no +grids at all reports the grid_ref error, not the missing grid.
        // Verified against 9.8.1's cct, which answers 1027 (illegal value), not 1026 (missing arg).
        // A bare +grid_ref with no value reaches strcmp with an empty string and is refused the
        // same way; also verified.
        boolean refIsInput = true;
        if (params.has("grid_ref")) {
            final String ref = params.value("grid_ref");
            if ("input_crs".equals(ref)) {
                refIsInput = true;
            } else if ("output_crs".equals(ref)) {
                refIsInput = false;
            } else {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+proj=xyzgridshift: unusupported value for grid_ref"
                                + (ref == null ? "" : ": " + ref)
                                + ". Expected input_crs or output_crs.");
            }
        }
        this.gridRefIsInput = refIsInput;

        // xyzgridshift.cpp:261-264, pj_param's 't' sigil: presence, not a usable value.
        if (!params.has("grids")) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+proj=xyzgridshift: +grids parameter missing.");
        }
        final String spec = params.value("grids");

        this.multiplier = params.doubleValue("multiplier", 1.0);

        try {
            this.grids = GenericGridSet.fromGridsSpec(spec);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+proj=xyzgridshift +grids=" + spec
                            + ": could not find required grid(s): " + e.getMessage(), e);
        }

        this.description = "xyzgridshift grids=" + spec
                + " grid_ref=" + (gridRefIsInput ? "input_crs" : "output_crs")
                + (multiplier == 1.0 ? "" : " multiplier=" + multiplier);
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_CARTESIAN} ({@code xyzgridshift.cpp:235}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.CARTESIAN;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_CARTESIAN} ({@code xyzgridshift.cpp:236}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.CARTESIAN;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code pj_xyzgridshift_forward_3d} ({@code xyzgridshift.cpp:168-179}). */
    @Override
    public void forward(final double[] coord) {
        if (gridRefIsInput) {
            direct(coord, 1.0);
        } else {
            iterative(coord, 1.0);
        }
    }

    /** {@code pj_xyzgridshift_reverse_3d} ({@code xyzgridshift.cpp:181-192}). */
    @Override
    public void inverse(final double[] coord) {
        if (gridRefIsInput) {
            iterative(coord, -1.0);
        } else {
            direct(coord, -1.0);
        }
    }

    /**
     * {@code direct_adjustment} ({@code xyzgridshift.cpp:150-165}): one geodetic conversion, one
     * grid read, one addition.
     */
    private void direct(final double[] coord, final double factor) {
        final double[] scratch = {coord[0], coord[1], coord[2], 0.0};
        gridValues(scratch);
        coord[0] += factor * scratch[0];
        coord[1] += factor * scratch[1];
        coord[2] += factor * scratch[2];
    }

    /**
     * {@code iterative_adjustment} ({@code xyzgridshift.cpp:119-147}), including the placement of
     * the convergence test after the assignment.
     */
    private void iterative(final double[] coord, final double factor) {
        final double initX = coord[0];
        final double initY = coord[1];
        final double initZ = coord[2];
        double x = initX;
        double y = initY;
        double z = initZ;

        final double[] scratch = new double[4];
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            scratch[0] = x;
            scratch[1] = y;
            scratch[2] = z;
            scratch[3] = 0.0;
            gridValues(scratch);

            final double dx = factor * scratch[0];
            final double dy = factor * scratch[1];
            final double dz = factor * scratch[2];

            final double ex = (x - initX) - dx;
            final double ey = (y - initY) - dy;
            final double ez = (z - initZ) - dz;
            final double err = ex * ex + ey * ey + ez * ez;

            x = initX + dx;
            y = initY + dy;
            z = initZ + dz;
            if (err < CONVERGENCE) {
                break;
            }
        }
        coord[0] = x;
        coord[1] = y;
        coord[2] = z;
    }

    /**
     * {@code get_grid_values} ({@code xyzgridshift.cpp:56-113}).
     *
     * <p>Takes {@code {X, Y, Z, t}} and <b>overwrites</b> {@code [0..2]} with the interpolated
     * &Delta;X, &Delta;Y, &Delta;Z in metres. One array in, one array out, so a transform allocates
     * one temporary per grid evaluation and this class keeps no mutable state of its own — the
     * point on which {@code CrsOperation}'s thread-safety promise rests.
     *
     * @param xyzt in: the cartesian position and its epoch; out: the shift triple in {@code [0..2]}
     */
    private void gridValues(final double[] xyzt) {
        cart.inverse(xyzt);
        final double lam = xyzt[0];
        final double phi = xyzt[1];

        final GenericGrid grid = GenericGridSet.find(grids, lam, phi);
        if (grid == null) {
            throw new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                    "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
                            + ") is outside every grid of +proj=xyzgridshift " + description);
        }
        if (grid.isNullGrid()) {
            xyzt[0] = 0.0;
            xyzt[1] = 0.0;
            xyzt[2] = 0.0;
            return;
        }

        final int samples = grid.samplesPerPixel();
        if (samples < 3) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "xyzgridshift: grid has not enough samples: " + grid.getName() + " has "
                            + samples + ", and three are needed for dX, dY and dZ");
        }

        int sampleX = 0;
        int sampleY = 1;
        int sampleZ = 2;
        for (int i = 0; i < samples; i++) {
            final String desc = grid.description(i);
            if ("x_translation".equals(desc)) {
                sampleX = i;
            } else if ("y_translation".equals(desc)) {
                sampleY = i;
            } else if ("z_translation".equals(desc)) {
                sampleZ = i;
            }
        }

        final String unit = grid.unit(sampleX);
        if (!unit.isEmpty() && !"metre".equals(unit)) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "xyzgridshift: Only unit=metre currently handled, and " + grid.getName()
                            + " band " + sampleX + " declares UNITTYPE=" + unit);
        }
        if (!grid.isGeographic()) {
            // The refusal upstream raises inside pj_bilinear_interpolation_three_samples, with
            // PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID. Raised here, at the same moment in the
            // sequence, rather than at construction: a set may hold grids this coordinate never
            // selects, and refusing early would reject a definition PROJ accepts.
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "Can only handle grids referenced in a geographic CRS, and " + grid.getName()
                            + " is not");
        }

        GenericGrid.interpolateThreeSamples(grid, lam, phi, sampleX, sampleY, sampleZ, xyzt);

        xyzt[0] *= multiplier;
        xyzt[1] *= multiplier;
        xyzt[2] *= multiplier;
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
        return "XyzGridShiftOperator[" + description + "]";
    }
}
