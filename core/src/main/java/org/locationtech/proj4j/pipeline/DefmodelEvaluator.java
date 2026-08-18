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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.datum.GenericGrid;
import org.locationtech.proj4j.datum.GenericGridSet;
import org.locationtech.proj4j.pipeline.DefmodelMasterFile.Component;
import org.locationtech.proj4j.pipeline.DefmodelMasterFile.DisplacementType;
import org.locationtech.proj4j.pipeline.DefmodelMasterFile.SpatialExtent;

/**
 * The arithmetic of {@code +proj=defmodel} — a port of {@code Evaluator},
 * {@code GridEx} and {@code ComponentEx} from
 * {@code 9.8.1:src/transformations/defmodel_impl.hpp}, together with the {@code Grid} and
 * {@code GridSet} adapters and the {@code EvaluatorIface} from {@code defmodel.cpp}.
 * {@link DefmodelMasterFile} is the file format; {@link DefmodelOperator} is the pipeline
 * step.
 *
 * <h2>Why the methods return {@code boolean} rather than throwing</h2>
 *
 * <p>Upstream's {@link #inverse} calls {@link #forward} up to ten times and treats a
 * {@code false} as a hard stop, so the shape of the control flow is part of the result,
 * not an implementation detail. Keeping {@code boolean} here means the iteration is a
 * transcription rather than a reconstruction. The single throw happens one level up, in
 * {@link DefmodelOperator}, using {@link #reason()} — so a caller still gets an
 * exception with a stated cause rather than a sentinel coordinate.
 *
 * <h2>Two upstream classes are folded into one here</h2>
 *
 * <p>Upstream keeps three caches: a {@code GridSet} holding one {@code Grid} adapter per
 * underlying grid, a {@code ComponentEx} holding one {@code GridEx} per {@code Grid}, and
 * the {@code GridEx} itself holding the per-cell geocentric offsets. The first two are
 * both keyed on the same grid and both live exactly as long as the component, so
 * {@link GridAdapter} below is all three: the band-role lookup, the trigonometry cache and
 * the per-cell cache in one object, created on demand by
 * {@link ComponentEx#gridAt(double, double)}. The numbers are unchanged; there is one
 * fewer hash lookup per point.
 *
 * <p>The four {@code sin}/{@code cos} of the resolution that upstream computes in
 * {@code GridEx}'s constructor are computed in {@link GridAdapter}'s, so they are also
 * computed for a component that interpolates plainly and never needs them. Four
 * trigonometric calls once per grid per component is not worth a second lazy field.
 *
 * <h2>What is deliberately not ported</h2>
 *
 * <p>{@code clearGridCache} and {@code reassign_context} exist so a {@code PJ} can be
 * moved between {@code PJ_CONTEXT}s, usually between threads. proj4j has no context
 * object, and {@code pipeline/package-info.java} already states that a pipeline and its
 * operators are not safe to share across threads, so there is nothing for them to do.
 *
 * <p>The {@code DEBUG_DEFMODEL} logging is not ported. Its {@code shortName} helper is,
 * as {@link Component#shortName()}, because the messages this class produces on failure
 * name the component and that is the name upstream uses for it.
 *
 * <h2>Mutable, and not safe to share</h2>
 *
 * <p>Grids are opened on first use and every cache above is written during
 * {@link #forward}, as upstream's are.
 *
 * @since 2.3.0
 */
final class DefmodelEvaluator {

    /** {@code EPS_HORIZ} ({@code defmodel_impl.hpp:1234}). Radians, or metres. */
    private static final double EPS_HORIZ = 1e-12;

    /** {@code EPS_VERT} ({@code defmodel_impl.hpp:1235}). Metres. */
    private static final double EPS_VERT = 1e-3;

    /** {@code for (int i = 0; i < 10; i++)} ({@code defmodel_impl.hpp:1237}). */
    private static final int MAX_ITERATIONS = 10;

    private final DefmodelMasterFile model;
    private final double a;
    private final double b;
    private final double es;
    private final boolean isHorizontalUnitDegree;
    private final boolean isAddition;
    private final boolean isGeographicCRS;

    /** The {@code +proj=cart +a=<inherited>} child PJ ({@code defmodel.cpp:387-392}). */
    private final CartConversion cart;

    private final List<ComponentEx> components;

    /** Scratch for {@link CartConversion}, which transforms a coordinate in place. */
    private final double[] geocentric = new double[3];

    /** Why the last {@link #forward} or {@link #inverse} returned {@code false}. */
    private String reason;

    /**
     * How to classify that failure. Upstream has nothing to port here: {@code forward_4d}
     * returns an all-{@code HUGE_VAL} coordinate and sets <em>no</em> error code at all
     * for any of these, so a caller can only see that something went wrong and not what.
     * The classification is ours, and no row in the corpus asserts on it — the only
     * {@code expect failure errno} rows for this operator are the setup-time ones and the
     * two missing-epoch ones, which are raised in {@link DefmodelOperator} and do have
     * upstream codes.
     */
    private ErrorCause cause;

    /**
     * @param model           the parsed {@code +model=} file
     * @param a               the step's semi-major axis, metres
     * @param b               the step's semi-minor axis, metres
     * @param isGeographicCRS whether {@code definition_crs} names a geographic CRS
     * @throws PipelineDefinitionException for the three combinations upstream refuses
     */
    DefmodelEvaluator(final DefmodelMasterFile model, final double a, final double b,
                      final boolean isGeographicCRS) {
        this.model = model;
        this.a = a;
        this.b = b;
        // defmodel_impl.hpp:731. Note it is recomputed from a and b rather than taken from
        // the step, so that this class has one definition of flattening and not two.
        this.es = 1 - (b * b) / (a * a);
        this.isHorizontalUnitDegree =
                DefmodelMasterFile.DEGREE.equals(model.horizontalOffsetUnit());
        this.isAddition = DefmodelMasterFile.ADDITION.equals(model.horizontalOffsetMethod());
        this.isGeographicCRS = isGeographicCRS;
        this.cart = new CartConversion(a, es);

        if (!isGeographicCRS && isHorizontalUnitDegree) {
            throw PipelineJson.invalid("definition_crs = projected CRS and "
                    + "horizontal_offset_unit = degree are incompatible");
        }
        if (!isGeographicCRS && !isAddition) {
            throw PipelineJson.invalid("definition_crs = projected CRS and "
                    + "horizontal_offset_method = geocentric are incompatible");
        }
        final List<Component> parsed = model.components();
        this.components = new ArrayList<ComponentEx>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            final ComponentEx ex = new ComponentEx(parsed.get(i));
            this.components.add(ex);
            if (!isGeographicCRS && !ex.isBilinearInterpolation) {
                throw PipelineJson.invalid("definition_crs = projected CRS and "
                        + "interpolation_method = geocentric_bilinear are incompatible");
            }
        }
    }

    /** @return whether {@code definition_crs} is geographic, which decides the I/O units */
    boolean isGeographicCRS() {
        return isGeographicCRS;
    }

    /** @return why the last call returned {@code false}; never {@code null} after one did */
    String reason() {
        return reason;
    }

    /** @return how to classify that failure; never {@code null} after a call returned false */
    ErrorCause cause() {
        return cause;
    }

    /** Record why, and answer {@code false}, so the call sites stay one line each. */
    private boolean fail(final ErrorCause why, final String what) {
        this.cause = why;
        this.reason = what;
        return false;
    }

    // ------------------------------------------------------------------- forward

    /**
     * {@code Evaluator::forward} ({@code defmodel_impl.hpp:825-1221}).
     *
     * @param x                     longitude in radians, or easting in metres
     * @param y                     latitude in radians, or northing in metres
     * @param z                     ellipsoidal height, metres
     * @param t                     the observation epoch, decimal years
     * @param forInverseComputation relax the extent tests and clamp to the edges, which
     *                              {@link #inverse} needs because its intermediate points
     *                              wander slightly outside the model
     * @param out                   receives {@code {x, y, z}}; untouched on {@code false}
     *                              only insofar as upstream leaves it untouched, which is
     *                              not at all — treat it as undefined
     * @return whether the point could be transformed
     */
    boolean forward(final double xIn, final double y, final double z, final double t,
                    final boolean forInverseComputation, final double[] out) {
        double x = xIn;
        double xOut = x;
        double yOut = y;
        double zOut = z;

        final double eps = isGeographicCRS ? 1e-10 : 1e-5;

        // Against the model's own extent, wrapping longitude to reach it. Note that the
        // wrap moves x, which the component loop below then uses, but NOT xOut, which was
        // taken before the wrap: a point given at 362 degrees comes back at 362 degrees.
        // That is upstream's behaviour and the corpus depends on the comparator measuring
        // a geodesic distance, for which 362 and 2 are the same meridian.
        final SpatialExtent extent = model.extent();
        final double minx = extent.minx(isGeographicCRS);
        final double maxx = extent.maxx(isGeographicCRS);
        if (isGeographicCRS) {
            while (x < minx - eps) {
                x += 2.0 * DefmodelMasterFile.DEFMODEL_PI;
            }
            while (x > maxx + eps) {
                x -= 2.0 * DefmodelMasterFile.DEFMODEL_PI;
            }
        }
        final double miny = extent.miny(isGeographicCRS);
        final double maxy = extent.maxy(isGeographicCRS);
        // A tenth of a degree, or ten kilometres. Only consulted for the inverse.
        final double extraMarginForInverse =
                isGeographicCRS ? DefmodelMasterFile.degToRad(0.1) : 10000;
        final double[] xy = {x, y};
        if (!bboxCheck(xy, forInverseComputation, minx, miny, maxx, maxy, eps,
                extraMarginForInverse)) {
            return fail(ErrorCause.COORDINATE_OUTSIDE_GRID, "calculation point (" + xy[0]
                    + ", " + xy[1] + ") is outside the extent of the deformation model "
                    + extent);
        }
        // bboxCheck clamps, and the clamped values are what the components see.
        x = xy[0];
        final double yClamped = xy[1];

        final DefmodelMasterFile.Epoch first = model.timeExtentFirst();
        final DefmodelMasterFile.Epoch last = model.timeExtentLast();
        if (t < first.toDecimalYear() || t > last.toDecimalYear()) {
            return fail(ErrorCause.COORDINATE_OUTSIDE_AREA_OF_USE, "calculation epoch " + t
                    + " is not valid for the deformation model, whose time extent is "
                    + first + " to " + last);
        }

        // For isHorizontalUnitDegree.
        double dlam = 0;
        double dphi = 0;
        // For !isHorizontalUnitDegree.
        double de = 0;
        double dn = 0;
        double dz = 0;

        boolean sincosphiInitialized = false;
        double sinphi = 0;
        double cosphi = 0;

        for (int c = 0; c < components.size(); c++) {
            final ComponentEx compEx = components.get(c);
            final Component comp = compEx.component;
            if (compEx.displacementType == DisplacementType.NONE) {
                continue;
            }
            final SpatialExtent cext = comp.extent();
            final double cminx = cext.minx(isGeographicCRS);
            final double cmaxx = cext.maxx(isGeographicCRS);
            final double cminy = cext.miny(isGeographicCRS);
            final double cmaxy = cext.maxy(isGeographicCRS);
            // Zero, not extraMarginForInverse: the slack is granted once against the whole
            // model, and not again against each component.
            final double[] forGrid = {x, yClamped};
            if (!bboxCheck(forGrid, forInverseComputation, cminx, cminy, cmaxx, cmaxy, eps,
                    0)) {
                // Outside this component is not a failure of the point; other components
                // may still contribute.
                continue;
            }
            double xForGrid = Math.max(forGrid[0], cminx);
            double yForGrid = Math.max(forGrid[1], cminy);
            xForGrid = Math.min(xForGrid, cmaxx);
            yForGrid = Math.min(yForGrid, cmaxy);

            final double tfactor = compEx.evaluateAt(t);
            if (tfactor == 0.0) {
                continue;
            }

            if (!compEx.openGridSet()) {
                return false;
            }
            final GridAdapter grid = compEx.gridAt(xForGrid, yForGrid);
            if (grid == null) {
                continue;
            }
            if (grid.width < 2 || grid.height < 2) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.grid.getName()
                        + " of component " + comp.shortName() + " is " + grid.width + "x"
                        + grid.height + "; bilinear interpolation needs at least 2x2");
            }
            final double ixd = (xForGrid - grid.minx) / grid.resx;
            final double iyd = (yForGrid - grid.miny) / grid.resy;
            if (ixd < -eps || iyd < -eps || ixd + 1 >= grid.width + eps
                    || iyd + 1 >= grid.height + eps) {
                // Inside the declared extent of the component but outside the actual
                // extent of the grid it named. Upstream skips, and so must this.
                continue;
            }
            final int ix0 = Math.min((int) ixd, grid.width - 2);
            final int iy0 = Math.min((int) iyd, grid.height - 2);
            final int ix1 = ix0 + 1;
            final int iy1 = iy0 + 1;
            final double frctX = ixd - ix0;
            final double frctY = iyd - iy0;
            final double oneMinusFrctX = 1. - frctX;
            final double oneMinusFrctY = 1. - frctY;
            final double m00 = oneMinusFrctX * oneMinusFrctY;
            final double m10 = frctX * oneMinusFrctY;
            final double m01 = oneMinusFrctX * frctY;
            final double m11 = frctX * frctY;

            if (compEx.displacementType == DisplacementType.VERTICAL) {
                if (!grid.getZOffset(ix0, iy0)) {
                    return false;
                }
                final double dz00 = grid.offZ;
                if (!grid.getZOffset(ix1, iy0)) {
                    return false;
                }
                final double dz10 = grid.offZ;
                if (!grid.getZOffset(ix0, iy1)) {
                    return false;
                }
                final double dz01 = grid.offZ;
                if (!grid.getZOffset(ix1, iy1)) {
                    return false;
                }
                final double dz11 = grid.offZ;
                dz += tfactor * (dz00 * m00 + dz01 * m01 + dz10 * m10 + dz11 * m11);
            } else if (isHorizontalUnitDegree) {
                final double dx00;
                final double dy00;
                final double dx01;
                final double dy01;
                final double dx10;
                final double dy10;
                final double dx11;
                final double dy11;
                if (compEx.displacementType == DisplacementType.HORIZONTAL) {
                    if (!grid.getLongLatOffset(ix0, iy0)) {
                        return false;
                    }
                    dx00 = grid.offX;
                    dy00 = grid.offY;
                    if (!grid.getLongLatOffset(ix1, iy0)) {
                        return false;
                    }
                    dx10 = grid.offX;
                    dy10 = grid.offY;
                    if (!grid.getLongLatOffset(ix0, iy1)) {
                        return false;
                    }
                    dx01 = grid.offX;
                    dy01 = grid.offY;
                    if (!grid.getLongLatOffset(ix1, iy1)) {
                        return false;
                    }
                    dx11 = grid.offX;
                    dy11 = grid.offY;
                } else {
                    if (!grid.getLongLatZOffset(ix0, iy0)) {
                        return false;
                    }
                    dx00 = grid.offX;
                    dy00 = grid.offY;
                    final double dz00 = grid.offZ;
                    if (!grid.getLongLatZOffset(ix1, iy0)) {
                        return false;
                    }
                    dx10 = grid.offX;
                    dy10 = grid.offY;
                    final double dz10 = grid.offZ;
                    if (!grid.getLongLatZOffset(ix0, iy1)) {
                        return false;
                    }
                    dx01 = grid.offX;
                    dy01 = grid.offY;
                    final double dz01 = grid.offZ;
                    if (!grid.getLongLatZOffset(ix1, iy1)) {
                        return false;
                    }
                    dx11 = grid.offX;
                    dy11 = grid.offY;
                    final double dz11 = grid.offZ;
                    dz += tfactor * (dz00 * m00 + dz01 * m01 + dz10 * m10 + dz11 * m11);
                }
                dlam += tfactor * (dx00 * m00 + dx01 * m01 + dx10 * m10 + dx11 * m11);
                dphi += tfactor * (dy00 * m00 + dy01 * m01 + dy10 * m10 + dy11 * m11);
            } else {
                final double de00;
                final double dn00;
                final double de01;
                final double dn01;
                final double de10;
                final double dn10;
                final double de11;
                final double dn11;
                if (compEx.displacementType == DisplacementType.HORIZONTAL) {
                    if (!grid.getEastingNorthingOffset(ix0, iy0)) {
                        return false;
                    }
                    de00 = grid.offX;
                    dn00 = grid.offY;
                    if (!grid.getEastingNorthingOffset(ix1, iy0)) {
                        return false;
                    }
                    de10 = grid.offX;
                    dn10 = grid.offY;
                    if (!grid.getEastingNorthingOffset(ix0, iy1)) {
                        return false;
                    }
                    de01 = grid.offX;
                    dn01 = grid.offY;
                    if (!grid.getEastingNorthingOffset(ix1, iy1)) {
                        return false;
                    }
                    de11 = grid.offX;
                    dn11 = grid.offY;
                } else {
                    if (!grid.getEastingNorthingZOffset(ix0, iy0)) {
                        return false;
                    }
                    de00 = grid.offX;
                    dn00 = grid.offY;
                    final double dz00 = grid.offZ;
                    if (!grid.getEastingNorthingZOffset(ix1, iy0)) {
                        return false;
                    }
                    de10 = grid.offX;
                    dn10 = grid.offY;
                    final double dz10 = grid.offZ;
                    if (!grid.getEastingNorthingZOffset(ix0, iy1)) {
                        return false;
                    }
                    de01 = grid.offX;
                    dn01 = grid.offY;
                    final double dz01 = grid.offZ;
                    if (!grid.getEastingNorthingZOffset(ix1, iy1)) {
                        return false;
                    }
                    de11 = grid.offX;
                    dn11 = grid.offY;
                    final double dz11 = grid.offZ;
                    dz += tfactor * (dz00 * m00 + dz01 * m01 + dz10 * m10 + dz11 * m11);
                }
                if (compEx.isBilinearInterpolation) {
                    de += tfactor * (de00 * m00 + de01 * m01 + de10 * m10 + de11 * m11);
                    dn += tfactor * (dn00 * m00 + dn01 * m01 + dn10 * m10 + dn11 * m11);
                } else {
                    grid.bilinearGeocentric(ix0, iy0, de00, dn00, de01, dn01, de10, dn10,
                            de11, dn11, m00, m01, m10, m11);
                    final double dX = grid.gcX;
                    final double dY = grid.gcY;
                    final double dZ = grid.gcZ;
                    if (!sincosphiInitialized) {
                        sincosphiInitialized = true;
                        sinphi = Math.sin(y);
                        cosphi = Math.cos(y);
                    }
                    final double lamRelToCellCenter = (frctX - 0.5) * grid.resx;
                    // Small-angle sin and cos, gated on a cell narrower than one degree.
                    // Upstream measures the worst error at 3.9e-9 on cos and 1.3e-11 on
                    // sin, and the expected values in the corpus were produced by these
                    // approximations, not by the library functions.
                    final double sinlam = grid.smallResx
                            ? lamRelToCellCenter * (1 - (1. / 6)
                                    * (lamRelToCellCenter * lamRelToCellCenter))
                            : Math.sin(lamRelToCellCenter);
                    final double coslam = grid.smallResx
                            ? (1 - 0.5 * (lamRelToCellCenter * lamRelToCellCenter))
                            : Math.cos(lamRelToCellCenter);
                    de += tfactor * (-dX * sinlam + dY * coslam);
                    dn += tfactor * ((-dX * coslam - dY * sinlam) * sinphi + dZ * cosphi);
                }
            }
        }

        if (isHorizontalUnitDegree) {
            xOut += dlam;
            yOut += dphi;
        } else if (isAddition && !isGeographicCRS) {
            xOut += de;
            yOut += dn;
        } else if (isAddition) {
            if (!sincosphiInitialized) {
                cosphi = Math.cos(y);
            }
            // deltaEastingNorthingToLongLat, inlined so the two temporaries stay local.
            final double oneMinuX = es * (1 - cosphi * cosphi);
            final double xTerm = 1 - oneMinuX;
            final double sqrtX = Math.sqrt(xTerm);
            xOut += de * sqrtX / (a * cosphi);
            yOut += dn * a * sqrtX * xTerm / (b * b);
        } else {
            if (!sincosphiInitialized) {
                sinphi = Math.sin(y);
                cosphi = Math.cos(y);
            }
            final double sinlam = Math.sin(x);
            final double coslam = Math.cos(x);
            final double dnsinphi = dn * sinphi;
            final double dX = -de * sinlam - dnsinphi * coslam;
            final double dY = de * coslam - dnsinphi * sinlam;
            final double dZ = dn * cosphi;
            // Note x, not xOut: the geocentric branch recomputes the horizontal position
            // from the wrapped longitude, so unlike every other branch it does NOT carry
            // the caller's 362 degrees through.
            geocentric[0] = x;
            geocentric[1] = y;
            geocentric[2] = 0;
            cart.forward(geocentric);
            geocentric[0] += dX;
            geocentric[1] += dY;
            geocentric[2] += dZ;
            cart.inverse(geocentric);
            xOut = geocentric[0];
            yOut = geocentric[1];
            // geocentric[2] is a height computed from a zero input height and is discarded,
            // exactly as upstream's h_out_ignored is. z is carried by dz below.
        }
        zOut += dz;

        out[0] = xOut;
        out[1] = yOut;
        out[2] = zOut;
        return true;
    }

    /**
     * {@code Evaluator::inverse} ({@code defmodel_impl.hpp:1225-1265}) — fixed-point
     * iteration on the forward, at most ten passes.
     *
     * <p>The stopping test is {@code max(|dx|, |dy|) < 1e-12} together with
     * {@code |dz| < 1e-3}, and there is no fallback: a point that has not converged in ten
     * passes is a failure, not a best effort. For a model whose working unit is metres
     * that horizontal threshold is one picometre, which converges only because the
     * displacement field is smooth and the iteration reaches a fixed point exactly rather
     * than approaching one.
     *
     * @param out receives {@code {x, y, z}}
     * @return whether the iteration converged
     */
    boolean inverse(final double x, final double y, final double z, final double t,
                    final double[] out) {
        double xOut = x;
        double yOut = y;
        double zOut = z;
        final double[] scratch = new double[3];
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (!forward(xOut, yOut, zOut, t, true, scratch)) {
                return false;
            }
            final double dx = scratch[0] - x;
            final double dy = scratch[1] - y;
            final double dz = scratch[2] - z;
            xOut -= dx;
            yOut -= dy;
            zOut -= dz;
            if (Math.max(Math.abs(dx), Math.abs(dy)) < EPS_HORIZ && Math.abs(dz) < EPS_VERT) {
                out[0] = xOut;
                out[1] = yOut;
                out[2] = zOut;
                return true;
            }
        }
        return fail(ErrorCause.NUMERICAL_FAILURE,
                "the inverse of the deformation model did not converge in " + MAX_ITERATIONS
                        + " iterations at (" + x + ", " + y + ", " + z + ") epoch " + t);
    }

    /**
     * {@code bboxCheck} ({@code defmodel_impl.hpp:786-821}). For the forward this is a
     * plain containment test; for the inverse a point slightly outside is pulled onto the
     * nearest edge, because the iteration's intermediate points step outside the model
     * near its boundary and refusing them would make the boundary uninvertible.
     *
     * @param xy   {@code {x, y}}, clamped in place
     * @return whether the point is usable
     */
    private static boolean bboxCheck(final double[] xy, final boolean forInverseComputation,
                                     final double minx, final double miny, final double maxx,
                                     final double maxy, final double eps,
                                     final double extraMarginForInverse) {
        final double x = xy[0];
        final double y = xy[1];
        if (x < minx - eps || x > maxx + eps || y < miny - eps || y > maxy + eps) {
            if (!forInverseComputation) {
                return false;
            }
            boolean xOk = false;
            if (x >= minx - eps && x <= maxx + eps) {
                xOk = true;
            } else if (x > minx - extraMarginForInverse && x < minx) {
                xy[0] = minx;
                xOk = true;
            } else if (x < maxx + extraMarginForInverse && x > maxx) {
                xy[0] = maxx;
                xOk = true;
            }
            boolean yOk = false;
            if (y >= miny - eps && y <= maxy + eps) {
                yOk = true;
            } else if (y > miny - extraMarginForInverse && y < miny) {
                xy[1] = miny;
                yOk = true;
            } else if (y < maxy + extraMarginForInverse && y > maxy) {
                xy[1] = maxy;
                yOk = true;
            }
            return xOk && yOk;
        }
        return true;
    }

    @Override
    public String toString() {
        return "DefmodelEvaluator[" + model + ", a=" + a + ", b=" + b
                + ", geographic=" + isGeographicCRS + "]";
    }

    // ================================================================== component

    /** {@code ComponentEx} ({@code defmodel_impl.hpp:176-220}) plus {@code GridSet}. */
    private final class ComponentEx {

        private final Component component;
        private final boolean isBilinearInterpolation;
        private final DisplacementType displacementType;

        private GenericGridSet gridSet;
        private final Map<GenericGrid, GridAdapter> adapters =
                new HashMap<GenericGrid, GridAdapter>();

        /** A one-entry memo, because every point of a bulk transform shares its epoch. */
        private double cachedDt;
        private double cachedValue;

        ComponentEx(final Component component) {
            this.component = component;
            this.isBilinearInterpolation = DefmodelMasterFile.BILINEAR.equals(
                    component.spatialModel().interpolationMethod());
            this.displacementType = DisplacementType.of(component.displacementType());
        }

        double evaluateAt(final double dt) {
            if (dt == cachedDt) {
                return cachedValue;
            }
            cachedDt = dt;
            cachedValue = component.timeFunction().evaluateAt(dt);
            return cachedValue;
        }

        /**
         * {@code iface.open} ({@code defmodel.cpp:250-261}), on first use. A grid file that
         * cannot be opened is a transform-time failure and not a setup-time one, because
         * upstream only opens a grid when a point actually lands in its component.
         *
         * @return whether the set is available
         */
        boolean openGridSet() {
            if (gridSet != null) {
                return true;
            }
            final String filename = component.spatialModel().filename();
            try {
                gridSet = GenericGridSet.open(filename);
            } catch (final IOException e) {
                return fail(ErrorCause.MISSING_GRID, "cannot open " + filename
                        + ", the grid of component " + component.shortName() + ": "
                        + e.getMessage());
            }
            if (gridSet == null) {
                return fail(ErrorCause.MISSING_GRID, "cannot open " + filename
                        + ", the grid of component " + component.shortName());
            }
            return true;
        }

        /**
         * {@code GridSet::gridAt} ({@code defmodel.cpp:220-235}) — the <b>untyped</b>
         * two-argument lookup, so a deformation model takes whichever root covers the
         * point regardless of its dataset {@code TYPE}.
         *
         * @return the adapter, or {@code null} when no grid in the set covers the point
         */
        GridAdapter gridAt(final double x, final double y) {
            final GenericGrid real = gridSet.gridAt(x, y);
            if (real == null) {
                return null;
            }
            GridAdapter adapter = adapters.get(real);
            if (adapter == null) {
                adapter = new GridAdapter(real);
                adapters.put(real, adapter);
            }
            return adapter;
        }
    }

    // ======================================================================= grid

    /**
     * {@code Grid} ({@code defmodel.cpp:47-200}) and {@code GridEx}
     * ({@code defmodel_impl.hpp:64-171}) over one {@link GenericGrid}.
     *
     * <h2>The band roles, and where they come from</h2>
     *
     * <p>{@code east_offset} / {@code north_offset} / {@code vertical_offset}, defaulting
     * positionally to bands 0, 1 and 2. This vocabulary belongs to {@code defmodel.cpp}
     * and to nothing else: {@code deformation.cpp} says {@code east_velocity}, and
     * {@code gridshift.cpp} says {@code easting_offset} and swaps its positional fallback
     * depending on whether the grid is geographic. Reading a neighbour's vocabulary here
     * would put a plausible number on the wrong axis.
     *
     * <p><b>A one-band grid is legal for a vertical component</b> and its single band is
     * the vertical offset ({@code defmodile.cpp:124-126}). Three of the corpus's seven
     * grids are that shape, so the branch is load-bearing rather than defensive.
     *
     * <p>The checks are done once and remembered — but only once they <em>succeed</em>.
     * A failed check is not remembered, so a second point in the same grid repeats it and
     * produces the message again. That is upstream's control flow, and since the check is
     * a pure function of the file it cannot come out differently.
     *
     * <p>Unlike {@code deformation.cpp}, this one does refuse a grid that has band
     * descriptions but not the expected ones — upstream refuses it here too, so there is
     * no divergence to declare.
     */
    private final class GridAdapter {

        private final GenericGrid grid;
        private final double minx;
        private final double miny;
        private final double resx;
        private final double resy;
        private final int width;
        private final int height;

        private boolean checkedHorizontal;
        private boolean checkedVertical;
        private int sampleX;
        private int sampleY = 1;
        private int sampleZ = 2;

        /** The most recent offsets read, in place of upstream's reference parameters. */
        private double offX;
        private double offY;
        private double offZ;

        // GridEx: the geocentric_bilinear cache.
        private final boolean smallResx;
        private final double sinhalfresx;
        private final double coshalfresx;
        private final double sinresy;
        private final double cosresy;
        private int lastIx0 = -1;
        private int lastIy0 = -1;
        private double dX00;
        private double dY00;
        private double dZ00;
        private double dX01;
        private double dY01;
        private double dZ01;
        private double dX10;
        private double dY10;
        private double dZ10;
        private double dX11;
        private double dY11;
        private double dZ11;
        private double sinphi0;
        private double cosphi0;
        private double sinphi1;
        private double cosphi1;
        private double gcX;
        private double gcY;
        private double gcZ;

        GridAdapter(final GenericGrid grid) {
            this.grid = grid;
            final double[] extent = grid.extentRadians();
            final double[] res = grid.resolutionRadians();
            this.minx = extent[0];
            this.miny = extent[1];
            this.resx = res[0];
            this.resy = res[1];
            this.width = grid.width();
            this.height = grid.height();
            this.smallResx = resx < DefmodelMasterFile.degToRad(1);
            this.sinhalfresx = Math.sin(resx / 2);
            this.coshalfresx = Math.cos(resx / 2);
            this.sinresy = Math.sin(resy);
            this.cosresy = Math.cos(resy);
        }

        /** {@code Grid::checkHorizontal} ({@code defmodel.cpp:67-107}). */
        private boolean checkHorizontal(final String expectedUnit) {
            if (checkedHorizontal) {
                return true;
            }
            final int samplesPerPixel = grid.samplesPerPixel();
            if (samplesPerPixel < 2) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " has not enough samples (" + samplesPerPixel
                        + "); a horizontal offset needs 2");
            }
            boolean foundDescX = false;
            boolean foundDescY = false;
            boolean foundDesc = false;
            for (int i = 0; i < samplesPerPixel; i++) {
                final String desc = grid.description(i);
                if ("east_offset".equals(desc)) {
                    sampleX = i;
                    foundDescX = true;
                } else if ("north_offset".equals(desc)) {
                    sampleY = i;
                    foundDescY = true;
                }
                if (desc != null && !desc.isEmpty()) {
                    foundDesc = true;
                }
            }
            if (foundDesc && (!foundDescX || !foundDescY)) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " has band descriptions, but not east_offset and north_offset");
            }
            final String unit = grid.unit(sampleX);
            if (unit != null && !unit.isEmpty() && !unit.equals(expectedUnit)) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " declares unit=" + unit + " on its east band; only unit="
                        + expectedUnit + " is handled for this mode");
            }
            checkedHorizontal = true;
            return true;
        }

        /** The first half of {@code Grid::getZOffset} ({@code defmodel.cpp:121-161}). */
        private boolean checkVertical() {
            if (checkedVertical) {
                return true;
            }
            final int samplesPerPixel = grid.samplesPerPixel();
            if (samplesPerPixel == 1) {
                sampleZ = 0;
            } else if (samplesPerPixel < 3) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " has not enough samples (" + samplesPerPixel
                        + "); a vertical offset needs 1 or 3");
            }
            boolean foundDesc = false;
            boolean foundDescZ = false;
            for (int i = 0; i < samplesPerPixel; i++) {
                final String desc = grid.description(i);
                if ("vertical_offset".equals(desc)) {
                    sampleZ = i;
                    foundDescZ = true;
                }
                if (desc != null && !desc.isEmpty()) {
                    foundDesc = true;
                }
            }
            if (foundDesc && !foundDescZ) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " has band descriptions, but not vertical_offset");
            }
            final String unit = grid.unit(sampleZ);
            if (unit != null && !unit.isEmpty() && !DefmodelMasterFile.METRE.equals(unit)) {
                return fail(ErrorCause.MISSING_GRID, "grid " + grid.getName()
                        + " declares unit=" + unit + " on its vertical band; only "
                        + "unit=metre is handled for this mode");
            }
            checkedVertical = true;
            return true;
        }

        /**
         * Degrees in the file, radians out — and the multiply is by the same
         * {@code double} PROJ's {@code DEG_TO_RAD} is, which was checked rather than
         * assumed: the literal in {@code proj_internal.h}, {@code M_PI / 180} and
         * {@code 3.14159265358979323846 / 180} are all the identical bit pattern
         * {@code 0x3f91df46a2529d39}.
         */
        boolean getLongLatOffset(final int ix, final int iy) {
            if (!checkHorizontal(DefmodelMasterFile.DEGREE)) {
                return false;
            }
            // Read as float and widened, as upstream does, so the rounding matches.
            final float longOffsetDeg = grid.valueAt(ix, iy, sampleX);
            final float latOffsetDeg = grid.valueAt(ix, iy, sampleY);
            offX = DefmodelMasterFile.degToRad(longOffsetDeg);
            offY = DefmodelMasterFile.degToRad(latOffsetDeg);
            return true;
        }

        boolean getZOffset(final int ix, final int iy) {
            if (!checkVertical()) {
                return false;
            }
            offZ = grid.valueAt(ix, iy, sampleZ);
            return true;
        }

        boolean getEastingNorthingOffset(final int ix, final int iy) {
            if (!checkHorizontal(DefmodelMasterFile.METRE)) {
                return false;
            }
            offX = grid.valueAt(ix, iy, sampleX);
            offY = grid.valueAt(ix, iy, sampleY);
            return true;
        }

        boolean getLongLatZOffset(final int ix, final int iy) {
            // Note the order and the short circuit: upstream's &&, so a horizontal failure
            // is reported and the vertical check is not reached.
            return getLongLatOffset(ix, iy) && getZOffset(ix, iy);
        }

        boolean getEastingNorthingZOffset(final int ix, final int iy) {
            return getEastingNorthingOffset(ix, iy) && getZOffset(ix, iy);
        }

        /**
         * {@code GridEx::getBilinearGeocentric} ({@code defmodel_impl.hpp:98-168}) —
         * interpolate in the geocentric frame rather than in easting and northing, which
         * is what makes a grid spanning a pole usable.
         *
         * <p>The four corner offsets are converted to geocentric deltas relative to a
         * point at the cell's own centre meridian, so the two longitudes involved are
         * exactly &plusmn;{@code resx/2} and their sines and cosines are cached per grid
         * rather than computed per point. The latitudes are two rows, and the second is
         * reached from the first by the angle-sum identity rather than a fresh
         * {@code sin}/{@code cos} — so the numbers depend on that identity and not on the
         * library, and reproducing them means reproducing the identity.
         *
         * <p>Results land in {@link #gcX}, {@link #gcY} and {@link #gcZ}.
         */
        void bilinearGeocentric(final int ix0, final int iy0,
                                final double de00, final double dn00,
                                final double de01, final double dn01,
                                final double de10, final double dn10,
                                final double de11, final double dn11,
                                final double m00, final double m01,
                                final double m10, final double m11) {
            if (ix0 != lastIx0 || iy0 != lastIy0) {
                lastIx0 = ix0;
                if (iy0 != lastIy0) {
                    final double y0 = miny + iy0 * resy;
                    sinphi0 = Math.sin(y0);
                    cosphi0 = Math.cos(y0);
                    // sin(y0 + resy) and cos(y0 + resy), by the angle-sum identity.
                    sinphi1 = sinphi0 * cosresy + cosphi0 * sinresy;
                    cosphi1 = cosphi0 * cosresy - sinphi0 * sinresy;
                    lastIy0 = iy0;
                }

                final double sinlam00 = -sinhalfresx;
                final double coslam00 = coshalfresx;
                final double dn00sinphi00 = dn00 * sinphi0;
                dX00 = -de00 * sinlam00 - dn00sinphi00 * coslam00;
                dY00 = de00 * coslam00 - dn00sinphi00 * sinlam00;
                dZ00 = dn00 * cosphi0;

                final double sinlam01 = -sinhalfresx;
                final double coslam01 = coshalfresx;
                final double dn01sinphi01 = dn01 * sinphi1;
                dX01 = -de01 * sinlam01 - dn01sinphi01 * coslam01;
                dY01 = de01 * coslam01 - dn01sinphi01 * sinlam01;
                dZ01 = dn01 * cosphi1;

                final double sinlam10 = sinhalfresx;
                final double coslam10 = coshalfresx;
                final double dn10sinphi10 = dn10 * sinphi0;
                dX10 = -de10 * sinlam10 - dn10sinphi10 * coslam10;
                dY10 = de10 * coslam10 - dn10sinphi10 * sinlam10;
                dZ10 = dn10 * cosphi0;

                final double sinlam11 = sinhalfresx;
                final double coslam11 = coshalfresx;
                final double dn11sinphi11 = dn11 * sinphi1;
                dX11 = -de11 * sinlam11 - dn11sinphi11 * coslam11;
                dY11 = de11 * coslam11 - dn11sinphi11 * sinlam11;
                dZ11 = dn11 * cosphi1;
            }

            gcX = m00 * dX00 + m01 * dX01 + m10 * dX10 + m11 * dX11;
            gcY = m00 * dY00 + m01 * dY01 + m10 * dY10 + m11 * dY11;
            gcZ = m00 * dZ00 + m01 * dZ01 + m10 * dZ10 + m11 * dZ11;
        }
    }
}
