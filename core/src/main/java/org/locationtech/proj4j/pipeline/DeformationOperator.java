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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.GenericGrid;
import org.locationtech.proj4j.datum.GenericGridSet;
import org.locationtech.proj4j.datum.VerticalGrid;
import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=deformation} — kinematic datum shifting through a velocity model,
 * ported from {@code 9.8.1:src/transformations/deformation.cpp}.
 *
 * <h2>The operation</h2>
 *
 * <pre>
 * X_out = X_in + (t_obs - t_epoch) * DX
 * Y_out = Y_in + (t_obs - t_epoch) * DY
 * Z_out = Z_in + (t_obs - t_epoch) * DZ
 * </pre>
 *
 * <p>Input and output are <b>geocentric cartesian metres</b> ({@code P-&gt;left} and
 * {@code P-&gt;right} are both {@code PJ_IO_UNITS_CARTESIAN}), which is why the corpus
 * always writes it between two {@code +proj=cart} steps. The grid, however, is
 * indexed geographically and its three channels are east, north and up — so every
 * evaluation converts cartesian to geodetic to find the cell, reads an ENU velocity
 * triple, and rotates that triple back into the cartesian frame.
 *
 * <h2>The two grid spellings, both implemented</h2>
 *
 * <p>A modern model is one three-channel Geodetic TIFF Grid named by {@code +grids}.
 * The historical spelling is a pair: {@code +xy_grids} in CTable/CTable2 (or NTv1 or
 * NTv2) form for east and north, and {@code +z_grids} in GTX form for up. Both
 * spellings expect <b>millimetres per year</b>, which is why every channel is divided
 * by 1000 on the way out. Which one is in use is decided by
 * {@code deformation.cpp:352-357}: {@code +grids} alone is enough, otherwise
 * <em>both</em> of the pair are required, and the two are never mixed —
 * {@code +grids} wins outright and the pair is not even looked for.
 *
 * <p>{@code +grids} landed in 2.2.0. Before that it was refused, and the stated reason
 * — "proj4j has no GeoTIFF grid reader" — had been false since 2.1.0, when the reader
 * landed; the generic N-sample layer ({@code datum.GenericGrid},
 * {@code datum.GenericGridSet}) followed in 2.2.0. The reason is recorded here rather
 * than deleted because it is the kind of stale note that stops the work instead of
 * costing an experiment: nothing was missing but this operator's use of a layer
 * {@link XyzGridShiftOperator} was already running on.
 *
 * <h2>Which channel is east, north and up</h2>
 *
 * <p>{@code deformation.cpp:99-113} defaults the three channels to bands 0, 1 and 2
 * <em>positionally</em> and then lets any band whose {@code DESCRIPTION} is
 * {@code east_velocity}, {@code north_velocity} or {@code up_velocity} override its
 * slot. That is the vocabulary this operator uses and no other. It matters that it is
 * read from {@code deformation.cpp} and not from a sibling, because PROJ's generic-grid
 * consumers use <b>three mutually incompatible</b> sample-role vocabularies over the
 * same {@code GenericShiftGrid} interface, and their positional fallbacks disagree:
 *
 * <ul>
 * <li>{@code deformation.cpp:104-112} — {@code east_velocity} / {@code north_velocity} /
 *     {@code up_velocity}, fallback 0, 1, 2.</li>
 * <li>{@code xyzgridshift.cpp:85-93} — {@code x_translation} / {@code y_translation} /
 *     {@code z_translation}, fallback 0, 1, 2.</li>
 * <li>{@code gridshift.cpp:257-302} — {@code latitude_offset} / {@code longitude_offset}
 *     when the grid is geographic, {@code easting_offset} / {@code northing_offset} when
 *     it is projected, and the <b>fallback itself changes with that flag</b>:
 *     {@code gridshift.cpp:303-315} takes X from band 0 and Y from band 1 for a projected
 *     grid but X from band <b>1</b> and Y from band <b>0</b> for a geographic one.</li>
 * </ul>
 *
 * <p>Copying the wrong one, or copying {@code gridshift}'s flag-dependent fallback into
 * here, would assign a plausible velocity to the wrong axis — the failure this project
 * exists to eliminate, because the answer stays in the right country.
 *
 * <p><b>One deliberate divergence, and it is fail-closed.</b> Upstream's loop has no
 * "did I find them" test: a three-band grid describing itself as, say,
 * {@code east_velocity} / {@code north_velocity} / {@code some_accuracy} keeps
 * {@code sampleU = 2} and silently reads the accuracy band as a vertical velocity. This
 * operator refuses instead, with {@link ErrorCause#MISSING_GRID} and the offending
 * descriptions in the message, whenever a grid carries <em>any</em> non-empty band
 * description but not all three of the ENU roles. A grid with no descriptions at all
 * still falls back to 0, 1, 2 exactly as upstream does, which is the case the convention
 * exists for. The shape of the test is not invented: it is
 * {@code grids.cpp:2541-2590}'s own {@code foundDescriptionForAtLeastOneSample} guard,
 * which refuses a GeoTIFF whose "IFD 0 has channel descriptions, but no
 * longitude_offset/latitude_offset channel" and, separately, one that names a latitude
 * offset without a longitude offset. PROJ applies that reasoning in its newer consumer
 * and not in this one; proj4j applies it in both. The divergence cannot change the
 * corpus, because a grid that reaches it would have to be out of spec.
 *
 * <p>{@code UNITTYPE} is checked on the <b>east</b> channel only, and only against empty
 * or {@code millimetres per year} ({@code deformation.cpp:114-118}). A file declaring a
 * different unit on north or up alone is accepted and read as mm/yr. Upstream's, and
 * ported as written, because a file like that is out of spec and PROJ's answer for it is
 * the answer we owe.
 *
 * <h2>{@code +dt} versus {@code +t_epoch}, which are mutually exclusive</h2>
 *
 * <p>Exactly one must be given ({@code deformation.cpp:377-392}). With {@code +dt} the
 * interval is fixed and the coordinate's own epoch is never read. With
 * {@code +t_epoch} the interval is {@code t - t_epoch}, and a coordinate whose
 * {@code t} is {@code HUGE_VAL} — gie's spelling for "no epoch" — is
 * {@code PROJ_ERR_COORD_TRANSFM_MISSING_TIME}. Note that gie zero-fills an unwritten
 * fourth ordinate, so "no epoch" has to be written out as the literal
 * {@code HUGE_VAL}; a three-ordinate row arrives with {@code t = 0} and is a perfectly
 * valid observation at year zero.
 *
 * <p>{@code +t_obs} is a <b>hard error</b> with a migration message
 * ({@code deformation.cpp:400}), not a synonym for {@code +dt}. It is undocumented and
 * one of the entries in this project's "implement from the code, not the docs" table.
 *
 * <h2>The inverse is iterative and its z is not</h2>
 *
 * <p>{@code pj_deformation_reverse_shift} runs a loop of at most 10 passes over the
 * horizontal components, carrying z along only because the cartesian-to-geodetic
 * conversion needs it, and then <b>overwrites</b> z with the one-step value
 * {@code input.z - dt * z0} computed from the <em>first</em> evaluation. Ported verbatim,
 * including that overwrite: the expected values in {@code deformation.gie} were generated
 * by it, and "improving" the z iteration would change them.
 *
 * <p><b>The {@code 1e-8} on {@code hypot(dif.x, dif.y)} is where the loop stops early, not
 * a promise about the answer.</b> Each pass <em>adds</em> its residual back
 * ({@code outX += difX}) where a Newton step would subtract it, so the residual roughly
 * doubles every pass instead of shrinking; on anything but a very small displacement the
 * loop therefore runs all 10 passes and the tolerance is never met. There is no check
 * after the loop: whatever the last pass produced is written to {@code coord} and
 * returned, with nothing in the result, the error code or the message to say the tolerance
 * was missed. Callers who need a bound on the closure error have to measure it themselves
 * — {@code DeformationOperatorTest} does, and pins it at about 4.2 mm for a hundred-year
 * inverse and about 42 &micro;m for a ten-year one, growing with the square of the
 * displacement. Adding a convergence check here would be a behaviour change and is
 * deliberately not made.
 *
 * <p>Note also that the loop's {@code dif.z} uses {@code out.z - dt * delta.z} where
 * the other two use {@code + dt * delta}. That asymmetry is upstream's, and it is
 * inside the discarded part of the computation, so it has no effect on the result — it
 * is transcribed rather than tidied so the two files can be diffed.
 *
 * <p>Immutable after construction; safe to share.
 *
 * @since 2.0.0
 */
final class DeformationOperator implements PipelineOperator {

    /** {@code #define TOL 1e-8} on {@code hypot(dif.x, dif.y)}. */
    private static final double TOL = 1e-8;

    /** {@code #define MAX_ITERATIONS 10}. */
    private static final int MAX_ITERATIONS = 10;

    /** {@code desc == "east_velocity"} ({@code deformation.cpp:107}). */
    private static final String EAST = "east_velocity";

    /** {@code desc == "north_velocity"} ({@code deformation.cpp:109}). */
    private static final String NORTH = "north_velocity";

    /** {@code desc == "up_velocity"} ({@code deformation.cpp:111}). */
    private static final String UP = "up_velocity";

    /** The only {@code UNITTYPE} the operation accepts ({@code deformation.cpp:115}). */
    private static final String MM_PER_YEAR = "millimetres per year";

    private final CartConversion cart;

    /** The {@code +grids} spelling: three channels of one generic grid. Null for the pair. */
    private final List<GenericGridSet> grids;

    /** The {@code +xy_grids} half of the pair. Null when {@code +grids} is in use. */
    private final HorizontalGrids hgrids;

    /** The {@code +z_grids} half of the pair. Empty when {@code +grids} is in use. */
    private final List<VerticalGrid> vgrids;
    private final double dt;
    private final double tEpoch;
    private final String description;

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    DeformationOperator(final Registry registry, final ProjParams params) {
        final boolean hasXy = params.has("xy_grids");
        final boolean hasZ = params.has("z_grids");
        final boolean hasGrids = params.has("grids");

        // deformation.cpp:352-357. Note the shape of the test: +grids alone is enough,
        // otherwise BOTH of the pair are required.
        if (!hasGrids && (!hasXy || !hasZ)) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "Either +grids or (+xy_grids and +z_grids) should be specified.");
        }
        // deformation.cpp:359-379: whichever list is in play is opened here, BEFORE +dt and
        // +t_epoch are looked at, so a definition with both a bad grid and a bad interval
        // reports the grid. Kept in upstream's order.
        if (hasGrids) {
            this.grids = openGenericGrids(params.value("grids"));
            this.hgrids = null;
            this.vgrids = Collections.<VerticalGrid>emptyList();
        } else {
            this.grids = null;
            this.hgrids = HorizontalGrids.open(params.value("xy_grids"), "xy_grids");
            this.vgrids = openVerticalGrids(params.value("z_grids"));
        }

        // deformation.cpp:394-411. HUGE_VAL is the "unset" sentinel for both, and
        // exactly one of them must end up set.
        final boolean hasDt = params.has("dt");
        final boolean hasTEpoch = params.has("t_epoch");
        if (params.has("t_obs")) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+t_obs parameter is deprecated. Use +dt instead.");
        }
        if (!hasDt && !hasTEpoch) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "either +dt or +t_epoch needs to be set.");
        }
        if (hasDt && hasTEpoch) {
            throw new PipelineDefinitionException(PipelineErrorCode.MUTUALLY_EXCLUSIVE_ARGS,
                    "+dt or +t_epoch are mutually exclusive.");
        }
        this.dt = hasDt ? params.doubleValue("dt", Double.NaN) : Double.POSITIVE_INFINITY;
        this.tEpoch = hasTEpoch ? params.doubleValue("t_epoch", Double.NaN)
                : Double.POSITIVE_INFINITY;

        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        this.cart = new CartConversion(ellipsoid[0], ellipsoid[1]);

        this.description = "deformation "
                + (hasGrids ? "grids=" + params.value("grids")
                        : "xy_grids=" + params.value("xy_grids")
                                + " z_grids=" + params.value("z_grids"))
                + (hasDt ? " dt=" + dt : " t_epoch=" + tEpoch);
    }

    /**
     * {@code pj_generic_grid_init(P, "grids")} ({@code deformation.cpp:360}) — the same
     * {@code @}-optional and comma-separated rules {@link XyzGridShiftOperator} uses, since it
     * is the same upstream call.
     */
    private static List<GenericGridSet> openGenericGrids(final String spec) {
        try {
            return GenericGridSet.fromGridsSpec(spec);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+grids=" + spec + ": could not find required grid(s): "
                            + e.getMessage(), e);
        }
    }

    /**
     * {@code pj_vgrid_init} ({@code grids.cpp:3753}) — same {@code @}-optional rule as
     * the horizontal list, and the same refusal for a required grid that is absent.
     */
    private static List<VerticalGrid> openVerticalGrids(final String spec) {
        if (spec == null || spec.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+z_grids parameter missing.");
        }
        try {
            return Collections.unmodifiableList(
                    new ArrayList<VerticalGrid>(VerticalGrid.fromGeoidGrids(spec)));
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "+z_grids=" + spec + ": could not find requested z_grid(s): "
                            + e.getMessage(), e);
        }
    }

    /** {@code P-&gt;left = PJ_IO_UNITS_CARTESIAN} ({@code deformation.cpp:420}). */
    @Override
    public GieIoUnits declaredLeft() {
        return GieIoUnits.CARTESIAN;
    }

    /** {@code P-&gt;right = PJ_IO_UNITS_CARTESIAN} ({@code deformation.cpp:421}). */
    @Override
    public GieIoUnits declaredRight() {
        return GieIoUnits.CARTESIAN;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code pj_deformation_forward_4d}. */
    @Override
    public void forward(final double[] coord) {
        final double interval = interval(coord[3]);
        final double[] shift = gridShift(coord[0], coord[1], coord[2]);
        coord[0] += interval * shift[0];
        coord[1] += interval * shift[1];
        coord[2] += interval * shift[2];
    }

    /** {@code pj_deformation_reverse_4d}. */
    @Override
    public void inverse(final double[] coord) {
        final double interval = interval(coord[3]);
        reverseShift(coord, interval);
    }

    /**
     * {@code pj_deformation_forward_4d:262-273}. With {@code +dt} the coordinate's
     * epoch is never read at all; with {@code +t_epoch} a {@code HUGE_VAL} epoch is
     * {@code PROJ_ERR_COORD_TRANSFM_MISSING_TIME}.
     */
    private double interval(final double t) {
        if (!Double.isInfinite(dt)) {
            return dt;
        }
        if (Double.isInfinite(t) || Double.isNaN(t)) {
            throw new CrsTransformException(ErrorCause.MISSING_TIME,
                    "+proj=deformation +t_epoch=" + tEpoch + " needs the coordinate's own epoch, "
                            + "and this coordinate has none");
        }
        return t - tEpoch;
    }

    /**
     * {@code pj_deformation_get_grid_shift} ({@code deformation.cpp:139-196}): the ENU
     * velocity triple at this position, rotated into the cartesian frame, in metres per
     * year.
     *
     * <p>Upstream calls {@code proj_errno_restore} at the end, which <b>discards</b> an
     * {@code OUTSIDE_GRID} raised by the grid read and leaves the caller to notice that
     * the returned components are {@code HUGE_VAL}. Since the ENU-to-cartesian rotation
     * mixes {@code +HUGE_VAL} and {@code -HUGE_VAL}, what actually reaches the caller is
     * a mixture of infinities and {@code NaN}, and upstream's
     * {@code if (shift.x == HUGE_VAL)} test does not reliably catch it — the coordinate
     * comes out {@code NaN}. Both PROJ and proj4j therefore report a failure for such a
     * row (upstream by producing a non-finite coordinate, which is what
     * {@code deformation.gie}'s two {@code expect failure errno
     * coord_transfm_outside_grid} rows assert); the difference is only that proj4j says
     * which grid and where, and that a finite input can never leave here as a plausible
     * coordinate.
     *
     * @return {@code {DX, DY, DZ}} in metres per year
     */
    private double[] gridShift(final double x, final double y, final double z) {
        final double[] geodetic = {x, y, z, 0.0};
        cart.inverse(geodetic);
        final double lam = geodetic[0];
        final double phi = geodetic[1];

        // Both spellings answer in mm/yr; the division to m/yr is upstream's, and upstream does
        // it in two different places (deformation.cpp:126-128 for +grids, :170-172 for the pair)
        // with the same effect.
        final double[] enu = grids != null ? genericVelocity(lam, phi) : pairVelocity(lam, phi);
        final double e = enu[0] / 1000.0;
        final double n = enu[1] / 1000.0;
        final double u = enu[2] / 1000.0;

        final double sp = Math.sin(phi);
        final double cp = Math.cos(phi);
        final double sl = Math.sin(lam);
        final double cl = Math.cos(lam);

        // ENU -> PJ_XYZ, per Noerbech et al. 2003 as cited by deformation.cpp:180-188.
        return new double[] {
            -sp * cl * n - sl * e + cp * cl * u,
            -sp * sl * n + cl * e + cp * sl * u,
            cp * n + sp * u,
        };
    }

    /**
     * The historical pair: east and north from {@code +xy_grids}, up from {@code +z_grids}
     * ({@code deformation.cpp:164-172}).
     *
     * @return {@code {east, north, up}} in millimetres per year
     */
    private double[] pairVelocity(final double lam, final double phi) {
        final double[] hv = hgrids.value(lam, phi);
        return new double[] {hv[0], hv[1], verticalValue(lam, phi)};
    }

    /**
     * {@code pj_deformation_get_grid_values} ({@code deformation.cpp:84-131}) — the
     * {@code +grids} spelling, where all three channels come out of one generic grid.
     *
     * <p>Every refusal below is raised <em>here</em>, at transform time, rather than at
     * construction, because that is where upstream raises it: a list may hold grids this
     * coordinate never selects, and refusing early would reject a definition PROJ accepts.
     *
     * @return {@code {east, north, up}} in millimetres per year
     */
    private double[] genericVelocity(final double lam, final double phi) {
        final GenericGrid grid = GenericGridSet.find(grids, lam, phi);
        if (grid == null) {
            throw new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                    "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
                            + ") is outside every grid of +proj=deformation " + description);
        }
        // deformation.cpp:89-94: +grids=null covers the world and shifts nothing, and its
        // (nonexistent) samples are never asked for, so this precedes the sample count test.
        if (grid.isNullGrid()) {
            return new double[] {0.0, 0.0, 0.0};
        }

        final int samples = grid.samplesPerPixel();
        if (samples < 3) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "deformation: grid has not enough samples: " + grid.getName() + " has "
                            + samples + ", and three are needed for the east, north and up "
                            + "velocities");
        }

        // deformation.cpp:99-113, and NOT gridshift.cpp's flag-dependent fallback: the default
        // here is 0, 1, 2 whatever the grid's CRS is.
        int sampleE = 0;
        int sampleN = 1;
        int sampleU = 2;
        boolean described = false;
        boolean foundE = false;
        boolean foundN = false;
        boolean foundU = false;
        for (int i = 0; i < samples; i++) {
            final String desc = grid.description(i);
            if (!desc.isEmpty()) {
                described = true;
            }
            if (EAST.equals(desc)) {
                sampleE = i;
                foundE = true;
            } else if (NORTH.equals(desc)) {
                sampleN = i;
                foundN = true;
            } else if (UP.equals(desc)) {
                sampleU = i;
                foundU = true;
            }
        }
        // The one deliberate divergence, spelled out in this class's javadoc: upstream would
        // fall back positionally over the top of a vocabulary it does not recognise, which turns
        // an out-of-spec file into a plausible velocity on the wrong axis.
        if (described && !(foundE && foundN && foundU)) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "+proj=deformation cannot tell which channel of " + grid.getName()
                            + " is which: it describes its bands as "
                            + describedBands(grid, samples) + ", and this operation needs "
                            + EAST + ", " + NORTH + " and " + UP + ". Guessing the band order "
                            + "would produce a plausible velocity on the wrong axis. A grid "
                            + "that describes no band at all is read as 0, 1, 2 instead, which "
                            + "is what deformation.cpp:99-101 does.");
        }

        // deformation.cpp:114-118: the east channel alone, and empty is allowed.
        final String unit = grid.unit(sampleE);
        if (!unit.isEmpty() && !MM_PER_YEAR.equals(unit)) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "deformation: Only unit=" + MM_PER_YEAR + " currently handled, and "
                            + grid.getName() + " band " + sampleE + " declares UNITTYPE="
                            + unit);
        }
        if (!grid.isGeographic()) {
            throw new CrsTransformException(ErrorCause.MISSING_GRID,
                    "Can only handle grids referenced in a geographic CRS, and " + grid.getName()
                            + " is not");
        }

        final double[] enu = new double[3];
        GenericGrid.interpolateThreeSamples(grid, lam, phi, sampleE, sampleN, sampleU, enu);
        return enu;
    }

    /** The band descriptions, for the refusal message; an unnamed band shows as {@code ""}. */
    private static String describedBands(final GenericGrid grid, final int samples) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < samples; i++) {
            sb.append(i == 0 ? "[" : ", ").append('"').append(grid.description(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** {@code pj_vgrid_value(P, Q-&gt;vgrids, lp, 1.0)} — multiplier 1, not vgridshift's -1. */
    private double verticalValue(final double lam, final double phi) {
        if (vgrids.isEmpty()) {
            return 0.0;
        }
        if (Double.isNaN(lam) || Double.isNaN(phi)) {
            return Double.NaN;
        }
        for (int i = 0; i < vgrids.size(); i++) {
            final VerticalGrid grid = vgrids.get(i);
            if (!grid.covers(lam, phi)) {
                continue;
            }
            final double value = grid.valueAt(lam, phi, 1.0);
            if (Double.isNaN(value)) {
                throw new CrsTransformException(ErrorCause.GRID_NODATA,
                        "every node surrounding (" + Math.toDegrees(lam) + ", "
                                + Math.toDegrees(phi) + ") in +z_grids grid "
                                + grid.getGridName() + " is nodata");
            }
            return value;
        }
        throw new CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID,
                "(" + Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
                        + ") is outside every grid of +z_grids");
    }

    /**
     * {@code pj_deformation_reverse_shift} ({@code deformation.cpp:199-241}), verbatim.
     *
     * @param coord    {@code {X, Y, Z, t}}, mutated in place
     * @param interval the signed number of years
     */
    private void reverseShift(final double[] coord, final double interval) {
        final double inputX = coord[0];
        final double inputY = coord[1];
        final double inputZ = coord[2];

        double[] delta = gridShift(inputX, inputY, inputZ);
        final double z0 = delta[2];

        double outX = inputX - interval * delta[0];
        double outY = inputY - interval * delta[1];
        double outZ = inputZ + interval * delta[2];

        int i = MAX_ITERATIONS;
        double difX;
        double difY;
        double difZ;
        do {
            delta = gridShift(outX, outY, outZ);

            difX = outX + interval * delta[0] - inputX;
            difY = outY + interval * delta[1] - inputY;
            difZ = outZ - interval * delta[2] - inputZ;
            outX += difX;
            outY += difY;
            outZ += difZ;
        } while (--i > 0 && Math.hypot(difX, difY) > TOL);

        // Upstream's overwrite: the iterated z is discarded and replaced with the
        // one-step value from the FIRST grid evaluation. Deliberate, and load bearing
        // for every expected value in deformation.gie.
        coord[0] = outX;
        coord[1] = outY;
        coord[2] = inputZ - interval * z0;
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
        return "DeformationOperator[" + description + "]";
    }
}
