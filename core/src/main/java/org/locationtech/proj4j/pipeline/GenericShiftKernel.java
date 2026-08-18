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

import org.locationtech.proj4j.datum.GenericGrid;

/**
 * The sample-reading half of {@code +proj=gridshift} — {@code gridshiftData::grid_interpolate}
 * ({@code 9.8.1:src/transformations/gridshift.cpp:241-580}), which is the only place in PROJ that
 * reads a generic grid biquadratically.
 *
 * <h2>Two questions, answered in this order</h2>
 *
 * <p><b>Which band is which.</b> A generic grid names its bands in {@code DESCRIPTION} metadata,
 * and the vocabulary depends on whether the grid is referenced in a geographic or a projected CRS:
 * {@code latitude_offset}/{@code longitude_offset} in arc-seconds for the geographic case,
 * {@code easting_offset}/{@code northing_offset} in metres for the projected one. A vertical band is
 * named by any of {@code ellipsoidal_height_offset}, {@code geoid_undulation},
 * {@code hydroid_height} or {@code vertical_offset}, in metres, and its name is read the same way in
 * both cases.
 *
 * <p>When a {@code HORIZONTAL_OFFSET} grid names neither horizontal band, the roles fall back to
 * band positions — and <b>the fallback is not the same in the two cases</b>. A projected grid takes
 * band 0 as easting and band 1 as northing; a geographic grid takes band 0 as
 * <em>latitude</em> and band 1 as <em>longitude</em>, because that is the order NTv2 and its
 * descendants store shifts in. Getting these the wrong way round transposes every shift, which
 * looks like a plausible answer, so the two are written out separately below rather than
 * parameterised.
 *
 * <p><b>Which interpolation.</b> An explicit {@code +interpolation=} wins; failing that the grid's
 * own {@code interpolation_method} metadata; failing that bilinear. Whatever that says, a grid
 * narrower or shorter than three nodes is read bilinearly, because the biquadratic kernel needs a
 * 3&times;3 window.
 *
 * <h2>The biquadratic kernel</h2>
 *
 * <p>This is NADCON5's, transcribed from NOAA's {@code qterp()} Fortran routine by way of
 * {@code gridshift.cpp:492-499}: fit a parabola through three consecutive nodes and evaluate it,
 * {@code f0 + x*df0 + 0.5*x*(x-1)*d2f0}. Applied once along each of the three rows of the window
 * and then once across the three results.
 *
 * <p>The 3&times;3 window is not simply anchored at the cell containing the point. It is shifted one
 * node west, or south, when the point is in the first half of its cell — so the point sits near the
 * middle of the window rather than at its edge — and also when the cell is the last one, so the
 * window stays inside the grid. That shift is why the fraction handed to the parabola is in
 * {@code [0, 2]} rather than {@code [0, 1]}.
 *
 * <h2>What this class does not carry</h2>
 *
 * <p>Upstream caches the resolved band roles per grid in {@code m_cacheGridInfo}, along with the
 * float buffer and the index of the window it last read, so a run of coordinates in one cell reads
 * the grid once. None of that is here, for one reason: a {@link PipelineOperator} is shared across
 * threads, and a cache of the last window read is mutable state on the shared object. Dropping it
 * cannot change an answer — the cache is keyed on the window index and is bypassed whenever a read
 * finds nodata, so a hit returns exactly what a miss would have computed. It costs a re-read of at
 * most nine nodes already decoded in memory, and a handful of string comparisons, per coordinate.
 *
 * <p>Every method here is static and every piece of state is a local or an argument.
 *
 * @since 2.3.0
 */
final class GenericShiftKernel {

    /** {@code REL_TOLERANCE_HGRIDSHIFT} ({@code gridshift.cpp:239}). */
    static final double REL_TOLERANCE_HGRIDSHIFT = 1e-5;

    /** {@code convFactorXY = 1. / 3600 / 180 * M_PI} ({@code gridshift.cpp:570}). */
    static final double ARC_SECOND_TO_RADIAN = 1.0 / 3600 / 180 * Math.PI;

    static final String HORIZONTAL_OFFSET = "HORIZONTAL_OFFSET";
    static final String GEOGRAPHIC_3D_OFFSET = "GEOGRAPHIC_3D_OFFSET";
    static final String ELLIPSOIDAL_HEIGHT_OFFSET = "ELLIPSOIDAL_HEIGHT_OFFSET";
    static final String VERTICAL_OFFSET_VERTICAL_TO_VERTICAL =
            "VERTICAL_OFFSET_VERTICAL_TO_VERTICAL";
    static final String VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL =
            "VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL";

    static final String BILINEAR = "bilinear";
    static final String BIQUADRATIC = "biquadratic";

    /** The largest window this kernel reads is 3&times;3 nodes of 3 bands. */
    static final int SCRATCH_FLOATS = 3 * 3 * 3;

    private GenericShiftKernel() {
    }

    /**
     * {@code gridshiftData::grid_interpolate} ({@code gridshift.cpp:241-580}).
     *
     * <p>Upstream signals every kind of refusal by returning {@code {HUGE_VAL, HUGE_VAL, 0}}, and
     * its caller acts on that in two different ways depending on the direction, so the sentinel is
     * reproduced rather than replaced by an exception: {@code shift} is <b>always</b> filled, and
     * the boolean is a convenience for the caller that wants to test it without a comparison. In
     * particular {@code shift[2]} is left at {@code 0} on failure, which is what makes upstream's
     * "use the first approximation" path in the inverse leave {@code z} alone.
     *
     * @param grid          the grid to read; must cover {@code (x, y)} — the caller has established
     *                      that through {@code GenericGridSet.gridAt}
     * @param type          the dataset {@code TYPE} the caller looked the grid up under, which
     *                      decides which bands are <em>required</em>
     * @param interpolation the value of {@code +interpolation=}, or the empty string
     * @param skipZ         {@code +no_z_transform}
     * @param x             longitude in radians, or easting, already longitude-normalised
     * @param y             latitude in radians, or northing
     * @param shift         out: {@code {dx, dy, dz}} — radians and metres, or the failure sentinel
     * @param biquadratic   out: length 1, set to whether the biquadratic kernel was used. The
     *                      caller needs this even on failure, because it decides whether the
     *                      inverse iterates.
     * @return whether a shift was produced
     */
    static boolean interpolate(final GenericGrid grid, final String type,
                               final String interpolation, final boolean skipZ,
                               final double x, final double y,
                               final double[] shift, final boolean[] biquadratic,
                               final float[] scratch) {
        shift[0] = Double.POSITIVE_INFINITY;
        shift[1] = Double.POSITIVE_INFINITY;
        shift[2] = 0.0;
        biquadratic[0] = false;

        final boolean isProjectedCoord = !grid.isGeographic();

        // --- band roles (gridshift.cpp:252-336) ------------------------------------------------
        boolean eastingNorthingOffset = false;
        int idxSampleX = -1;
        int idxSampleY = -1;
        int idxSampleZ = -1;
        final int samplesPerPixel = grid.samplesPerPixel();
        for (int i = 0; i < samplesPerPixel; i++) {
            final String desc = grid.description(i);
            if (!isProjectedCoord && "latitude_offset".equals(desc)) {
                idxSampleY = i;
                if (!isUnit(grid, i, "arc-second")) {
                    return false;
                }
            } else if (!isProjectedCoord && "longitude_offset".equals(desc)) {
                idxSampleX = i;
                if (!isUnit(grid, i, "arc-second")) {
                    return false;
                }
            } else if (isProjectedCoord && "easting_offset".equals(desc)) {
                eastingNorthingOffset = true;
                idxSampleX = i;
                if (!isUnit(grid, i, "metre")) {
                    return false;
                }
            } else if (isProjectedCoord && "northing_offset".equals(desc)) {
                eastingNorthingOffset = true;
                idxSampleY = i;
                if (!isUnit(grid, i, "metre")) {
                    return false;
                }
            } else if ("ellipsoidal_height_offset".equals(desc)
                    || "geoid_undulation".equals(desc)
                    || "hydroid_height".equals(desc)
                    || "vertical_offset".equals(desc)) {
                idxSampleZ = i;
                if (!isUnit(grid, i, "metre")) {
                    return false;
                }
            }
        }

        // gridshift.cpp:305-319. Only HORIZONTAL_OFFSET gets a positional fallback, and note the
        // asymmetry: projected is (easting, northing), geographic is (latitude, longitude).
        if (samplesPerPixel >= 2 && idxSampleY < 0 && idxSampleX < 0
                && HORIZONTAL_OFFSET.equals(type)) {
            if (isProjectedCoord) {
                eastingNorthingOffset = true;
                idxSampleX = 0;
                idxSampleY = 1;
            } else {
                idxSampleX = 1;
                idxSampleY = 0;
            }
        }

        if (HORIZONTAL_OFFSET.equals(type) || GEOGRAPHIC_3D_OFFSET.equals(type)) {
            if (idxSampleY < 0 || idxSampleX < 0) {
                return false;
            }
        }
        if (ELLIPSOIDAL_HEIGHT_OFFSET.equals(type)
                || VERTICAL_OFFSET_GEOGRAPHIC_TO_VERTICAL.equals(type)
                || VERTICAL_OFFSET_VERTICAL_TO_VERTICAL.equals(type)
                || GEOGRAPHIC_3D_OFFSET.equals(type)) {
            if (idxSampleZ < 0) {
                return false;
            }
        }

        // --- which interpolation (gridshift.cpp:338-357) ---------------------------------------
        String chosen = interpolation;
        if (chosen.isEmpty()) {
            chosen = grid.metadataItem("interpolation_method");
        }
        if (chosen.isEmpty()) {
            chosen = BILINEAR;
        }
        if (!BILINEAR.equals(chosen) && !BIQUADRATIC.equals(chosen)) {
            // An unusable interpolation_method inside the file. +interpolation= itself was
            // validated at setup, so only the file can reach this.
            return false;
        }

        final int effectiveZ = skipZ ? -1 : idxSampleZ;
        final boolean bilinear = BILINEAR.equals(chosen) || grid.width() < 3 || grid.height() < 3;
        biquadratic[0] = !bilinear;

        // gridshift.cpp:359-373. When the grid stores (latitude, longitude) in that order the
        // bands are requested as 0,1 - consecutive, which is the order the reader can memcpy -
        // and the two results are swapped back afterwards.
        final boolean swapXYInRes = idxSampleX == 1 && idxSampleY == 0;
        final int[] idxSampleXYZ = new int[effectiveZ >= 0 ? 3 : 2];
        if (swapXYInRes) {
            idxSampleXYZ[0] = 0;
            idxSampleXYZ[1] = 1;
        } else {
            idxSampleXYZ[0] = idxSampleX;
            idxSampleXYZ[1] = idxSampleY;
        }
        if (idxSampleXYZ.length == 3) {
            idxSampleXYZ[2] = idxSampleZ;
        }

        // --- cell and fraction (gridshift.cpp:384-421) ----------------------------------------
        final double[] extent = grid.extentRadians();
        final double[] res = grid.resolutionRadians();
        final int width = grid.width();
        final int height = grid.height();

        final double gx = (x - extent[0]) / res[0];
        final double gy = (y - extent[1]) / res[1];
        // Upstream is (int32_t)lround(floor(...)) with a NaN guard. Held as a long here so the
        // edge tests below cannot be reached through an integer overflow: a 32-bit wrap on a wild
        // input would turn "far past the east edge" into a valid column.
        long indX = Double.isNaN(gx) ? 0L : Math.round(Math.floor(gx));
        long indY = Double.isNaN(gy) ? 0L : Math.round(Math.floor(gy));
        double frctX = gx - indX;
        double frctY = gy - indY;

        // The point may sit one part in 1e5 of a cell outside the grid, in which case it is
        // snapped onto the edge node rather than refused. Note the tolerance is 10x
        // REL_TOLERANCE_HGRIDSHIFT, and that it is asymmetric: the fraction has to be almost 1 to
        // snap up from the west edge, and almost 0 to snap down from the east.
        if (indX < 0) {
            if (indX == -1 && frctX > 1 - 10 * REL_TOLERANCE_HGRIDSHIFT) {
                indX++;
                frctX = 0.0;
            } else {
                return false;
            }
        } else if (indX + 1 >= width) {
            if (indX + 1 == width && frctX < 10 * REL_TOLERANCE_HGRIDSHIFT) {
                indX--;
                frctX = 1.0;
            } else {
                return false;
            }
        }
        if (indY < 0) {
            if (indY == -1 && frctY > 1 - 10 * REL_TOLERANCE_HGRIDSHIFT) {
                indY++;
                frctY = 0.0;
            } else {
                return false;
            }
        } else if (indY + 1 >= height) {
            if (indY + 1 == height && frctY < 10 * REL_TOLERANCE_HGRIDSHIFT) {
                indY--;
                frctY = 1.0;
            } else {
                return false;
            }
        }

        if (bilinear) {
            if (!bilinearWindow(grid, (int) indX, (int) indY, frctX, frctY,
                    idxSampleX, idxSampleY, effectiveZ, idxSampleXYZ, shift, scratch)) {
                return false;
            }
        } else {
            if (!biquadraticWindow(grid, indX, indY, frctX, frctY, width, height,
                    idxSampleX, idxSampleY, effectiveZ, idxSampleXYZ, shift, scratch)) {
                return false;
            }
        }

        // gridshift.cpp:569-577. Arc-seconds to radians, but only for a geographic grid: an
        // easting/northing offset is already in the grid's own linear unit.
        if (idxSampleX >= 0 && idxSampleY >= 0 && !eastingNorthingOffset) {
            shift[0] *= ARC_SECOND_TO_RADIAN;
            shift[1] *= ARC_SECOND_TO_RADIAN;
        }
        if (swapXYInRes) {
            final double t = shift[0];
            shift[0] = shift[1];
            shift[1] = t;
        }
        return true;
    }

    /**
     * {@code gridshift.cpp:424-473}. The weights are formed exactly as upstream forms them,
     * including its reuse of {@code frct.y} as {@code 1 - frct.y} half way through, because the
     * order of the multiplications decides the last bit of every product.
     */
    private static boolean bilinearWindow(final GenericGrid grid, final int indX, final int indY,
                                          final double frctX, final double frctY,
                                          final int idxSampleX, final int idxSampleY,
                                          final int effectiveZ, final int[] idxSampleXYZ,
                                          final double[] shift, final float[] scratch) {
        double m10 = frctX;
        double m11 = m10;
        double m01 = 1.0 - frctX;
        double m00 = m01;
        double fy = frctY;
        m11 *= fy;
        m01 *= fy;
        fy = 1.0 - fy;
        m00 *= fy;
        m10 *= fy;

        if (idxSampleX >= 0 && idxSampleY >= 0) {
            // GenericGrid.valuesAt returns nodataFound, the opposite polarity of upstream's
            // success flag, and throws where upstream would have returned false. The window is
            // inside the grid by construction, so the only outcome to test for is nodata.
            if (grid.valuesAt(indX, indY, 2, 2, idxSampleXYZ, scratch)) {
                return false;
            }
            final int stride = idxSampleXYZ.length;
            shift[0] = m00 * scratch[0] + m10 * scratch[stride]
                    + m01 * scratch[2 * stride] + m11 * scratch[3 * stride];
            shift[1] = m00 * scratch[1] + m10 * scratch[stride + 1]
                    + m01 * scratch[2 * stride + 1] + m11 * scratch[3 * stride + 1];
            if (effectiveZ >= 0) {
                shift[2] = m00 * scratch[2] + m10 * scratch[stride + 2]
                        + m01 * scratch[2 * stride + 2] + m11 * scratch[3 * stride + 2];
            }
        } else {
            shift[0] = 0.0;
            shift[1] = 0.0;
            if (effectiveZ >= 0) {
                if (grid.valuesAt(indX, indY, 2, 2, new int[] {effectiveZ}, scratch)) {
                    return false;
                }
                shift[2] = m00 * scratch[0] + m10 * scratch[1]
                        + m01 * scratch[2] + m11 * scratch[3];
            }
        }
        return true;
    }

    /** {@code gridshift.cpp:474-567}. */
    private static boolean biquadraticWindow(final GenericGrid grid, final long indXIn,
                                             final long indYIn, final double frctXIn,
                                             final double frctYIn, final int width,
                                             final int height, final int idxSampleX,
                                             final int idxSampleY, final int effectiveZ,
                                             final int[] idxSampleXYZ, final double[] shift,
                                             final float[] scratch) {
        long indX = indXIn;
        long indY = indYIn;
        double frctX = frctXIn;
        double frctY = frctYIn;

        // Shift the 3x3 window so the point is near its middle, and so the window stays inside
        // the grid at the last cell. After this indX is in [0, width-3]: the only way to reach
        // width-2 is the second test, which decrements, and the indX > 0 conjunct in the first
        // stops it going negative.
        if ((frctX <= 0.5 && indX > 0) || indX + 2 == width) {
            indX -= 1;
            frctX += 1.0;
        }
        if ((frctY <= 0.5 && indY > 0) || indY + 2 == height) {
            indY -= 1;
            frctY += 1.0;
        }

        final int x0 = (int) indX;
        final int y0 = (int) indY;

        if (idxSampleX >= 0 && idxSampleY >= 0) {
            if (grid.valuesAt(x0, y0, 3, 3, idxSampleXYZ, scratch)) {
                return false;
            }
            final int stride = idxSampleXYZ.length;
            final int rowStride = 3 * stride;
            // Along each of the three rows first, then across the three results.
            final double[] alongX = new double[3];
            for (int c = 0; c < stride; c++) {
                for (int j = 0; j < 3; j++) {
                    final int base = j * rowStride + c;
                    alongX[j] = quadraticInterpol(frctX, scratch[base],
                            scratch[base + stride], scratch[base + 2 * stride]);
                }
                final double v = quadraticInterpol(frctY, alongX[0], alongX[1], alongX[2]);
                if (c == 0) {
                    shift[0] = v;
                } else if (c == 1) {
                    shift[1] = v;
                } else {
                    shift[2] = v;
                }
            }
        } else {
            shift[0] = 0.0;
            shift[1] = 0.0;
            if (effectiveZ >= 0) {
                if (grid.valuesAt(x0, y0, 3, 3, new int[] {effectiveZ}, scratch)) {
                    return false;
                }
                final double[] alongX = new double[3];
                for (int j = 0; j < 3; j++) {
                    alongX[j] = quadraticInterpol(frctX, scratch[3 * j],
                            scratch[3 * j + 1], scratch[3 * j + 2]);
                }
                shift[2] = quadraticInterpol(frctY, alongX[0], alongX[1], alongX[2]);
            }
        }
        return true;
    }

    /**
     * NOAA's {@code qterp()}, by way of {@code gridshift.cpp:492-499}: the parabola through
     * {@code f(0)=f0}, {@code f(1)=f1}, {@code f(2)=f2}, evaluated at {@code xToInterp}, which the
     * window shift keeps in {@code [0, 2]}.
     */
    static double quadraticInterpol(final double xToInterp, final double f0, final double f1,
                                    final double f2) {
        final double df0 = f1 - f0;
        final double df1 = f2 - f1;
        final double d2f0 = df1 - df0;
        return f0 + xToInterp * df0 + 0.5 * xToInterp * (xToInterp - 1.0) * d2f0;
    }

    /**
     * An empty {@code UNITTYPE} is accepted as the expected one — upstream's
     * {@code if (!unit.empty() && unit != expected)}, so a file that declares nothing is trusted
     * rather than refused.
     */
    private static boolean isUnit(final GenericGrid grid, final int sample, final String expected) {
        final String unit = grid.unit(sample);
        return unit.isEmpty() || expected.equals(unit);
    }
}
