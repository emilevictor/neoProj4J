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

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=helmert} and {@code +proj=molobadekas}
 * ({@code 9.8.1:src/transformations/helmert.cpp}): the 3-, 4-, 7- and 14-parameter
 * similarity transformations between geocentric cartesian frames, and the
 * Molodensky-Badekas variant that rotates about a named reference point.
 *
 * <p>Both are {@code PJ_TRANSFORMATION} upstream and share one {@code struct}, one
 * matrix builder and one pair of 3D transforms, so they share one class here. What
 * differs is entirely in the setup, and every difference is called out below.
 *
 * <h2>The parameters, and what each one does</h2>
 *
 * <table>
 * <caption>Accepted keys</caption>
 * <tr><th>key</th><th>meaning</th></tr>
 * <tr><td>{@code x y z}</td><td>translation, metres</td></tr>
 * <tr><td>{@code rx ry rz}</td><td>rotation, arcseconds</td></tr>
 * <tr><td>{@code s}</td><td>scale; parts per million in 3D, a <b>direct multiplier</b>
 *     in the 2D {@code +theta} form</td></tr>
 * <tr><td>{@code exact}</td><td>use the full trigonometric matrix</td></tr>
 * <tr><td>{@code convention}</td><td>{@code position_vector} or
 *     {@code coordinate_frame}</td></tr>
 * <tr><td>{@code towgs84}</td><td>the classic seven-value list, in place of
 *     {@code x..rz s} ({@code helmert} only)</td></tr>
 * <tr><td>{@code theta}</td><td>2D rotation, arcseconds; switches the whole step to a
 *     four-parameter planar shift ({@code helmert} only)</td></tr>
 * <tr><td>{@code px py pz}</td><td>rotation reference point, metres
 *     ({@code molobadekas} only)</td></tr>
 * <tr><td>{@code dx dy dz drx dry drz dtheta ds}</td><td>rates of change per year
 *     ({@code helmert} only)</td></tr>
 * <tr><td>{@code t_epoch}</td><td>the epoch the rates are referred to
 *     ({@code helmert} only)</td></tr>
 * <tr><td>{@code transpose}</td><td>refused unconditionally ({@code helmert}
 *     only)</td></tr>
 * </table>
 *
 * <h2>Eight rates, not seven</h2>
 *
 * <p>{@code helmert.cpp:632-661} reads eight: three translation, three rotation,
 * {@code dtheta} and {@code ds}. {@code dtheta} is easy to miss because it is the rate
 * of a parameter that only exists in the 2D form, and it is read whether or not
 * {@code +theta} was given.
 *
 * <h2>The rates are recomputed per coordinate, not cached in the object</h2>
 *
 * <p>Upstream caches: {@code helmert_forward_4d} compares the observation time against
 * {@code Q->t_obs}, and when it differs it rewrites {@code Q->xyz}, {@code Q->opk},
 * {@code Q->scale}, {@code Q->theta} and all nine matrix entries <em>in the shared
 * opaque struct</em> ({@code :437-455}). Reproducing that here would make two threads
 * transforming two epochs through one operator return each other's answers, which
 * {@code CrsOperation}'s contract forbids. So the eight rate fields and the eight
 * {@code _0} fields are final, and a rate-bearing step builds a fresh
 * {@link HelmertConversion} per call. A step with all eight rates zero — every
 * {@code +towgs84}, every EPSG 7-parameter method, and every corpus row but four —
 * builds exactly one at construction time and allocates nothing per coordinate.
 *
 * <h2>{@code +theta} is a different operator wearing the same name</h2>
 *
 * <p>Giving {@code +theta} changes five things at once ({@code :566-571, 611-615}):
 *
 * <ul>
 * <li>Both declared sides become {@code PJ_IO_UNITS_PROJECTED} instead of
 *     {@code CARTESIAN}, so the step's neighbours in a pipeline have to change too.</li>
 * <li>{@code Q->fourparam} is set, and {@code helmert_forward_3d} short-circuits to the
 *     2D function on its first line ({@code :375-379}) — the rotation matrix,
 *     {@code +rx}, {@code +ry}, {@code +rz}, {@code +exact} and the convention are all
 *     built and then never read.</li>
 * <li>{@code Q->scale} stops being parts per million and becomes a direct multiplier:
 *     {@code cr = cos(theta) * Q->scale} ({@code :332}), against
 *     {@code scale = 1 + Q->scale * 1e-6} ({@code :396}) in 3D.</li>
 * <li>{@code scale_0} defaults to {@code 1.0} rather than {@code 0}, and an explicit
 *     {@code +s=0} becomes an error rather than the identity scale.</li>
 * <li>{@code z} is passed through untouched, because the 2D function only ever writes
 *     {@code x} and {@code y}.</li>
 * </ul>
 *
 * <h2>Two upstream defects, reproduced and not reproduced</h2>
 *
 * <p><b>{@code +dx}/{@code +dy} are silently ignored under {@code +theta}. Reproduced.</b>
 * {@code helmert_forward} reads {@code Q->xyz_0} ({@code :336-337}), the epoch
 * translation, where every other time-varying quantity in the same two lines reads the
 * updated {@code Q->theta} and {@code Q->scale}. So on a 2D step {@code +ds} and
 * {@code +dtheta} apply and {@code +dx} and {@code +dy} do not, while on a 3D step
 * {@code +dx} does. Confirmed against the 9.8.1 {@code cct} binary: with
 * {@code +theta=0 +s=1 +x=7 +dx=1 +t_epoch=2000} at t=2001 the output moves by the
 * static 7 and not by the rate. This is reproduced here rather than corrected, because
 * a fork that quietly gives a different answer to the reference implementation is worse
 * than one that gives the same wrong answer loudly; {@code HelmertOperatorTest} pins
 * both the reproduced value and the value the parameter names imply.
 *
 * <p><b>{@code +towgs84} on a user-written {@code +proj=helmert} cancels itself out.
 * Not reproduced.</b> Any {@code PJ} carrying {@code +towgs84} gets a child helmert
 * built for it by {@code create.cpp:127-161}, and {@code fwd_prepare} applies that
 * child's <em>inverse</em> to any {@code CARTESIAN} input before the step runs
 * ({@code fwd.cpp:116-118}). When the step is itself a helmert reading the same
 * {@code +towgs84}, the two cancel: {@code cct} answers {@code 0 0 0} to
 * {@code +proj=helmert +towgs84=1,2,3} on the origin, and {@code -0.0000 -0.0000} to
 * the seven-value form, where the residue is only the difference between the child's
 * {@code exact} matrix and this step's linearised one. That cancellation lives in the
 * generic wrapper, not in {@code helmert.cpp}, and this engine has no such wrapper — so
 * this class implements {@code helmert.cpp} as written and a {@code +towgs84} here
 * applies its translation once. No corpus row is affected: the only corpus row that
 * pairs the two is an expected setup refusal. Both answers are pinned in
 * {@code HelmertOperatorTest}.
 *
 * <h2>{@code +to_meter} applies; the projected-side scaling keys are refused</h2>
 *
 * <p>In the ordinary {@code CARTESIAN} form the generic code contributes exactly one
 * factor, and {@link LinearUnits} reads it, as it does for {@link CartOperator} and
 * {@link TopocentricOperator}. Under {@code +theta} the sides are {@code PROJECTED} and
 * {@code fwd_finalize} instead applies {@code fr_meter * (x + x_0)},
 * {@code fr_meter * (y + y_0)} and {@code vfr_meter * (z + z_0)}, where
 * {@code vto_meter} falls back to {@code to_meter} when no vertical unit is given
 * ({@code init.cpp:747-750}). The vertical half of that lives inside
 * {@link Cs2csOperator} and is not shared; rather than grow a second copy for a
 * combination no corpus row uses, all seven of {@code to_meter}, {@code units},
 * {@code x_0}, {@code y_0}, {@code z_0}, {@code vto_meter} and {@code vunits} are
 * <em>refused</em> when {@code +theta} is present. A loud refusal is recoverable; a
 * silently dropped unit factor is a wrong coordinate reported as a right one.
 *
 * <p>Immutable and thread-safe apart from mutating the array passed in.
 *
 * @since 2.2.0
 */
final class HelmertOperator implements PipelineOperator {

    /** Keys the generic {@code PROJECTED} scaling would consume, which are refused instead. */
    private static final String[] PROJECTED_SCALING_KEYS = {
        "to_meter", "units", "x_0", "y_0", "z_0", "vto_meter", "vunits",
    };

    /** {@code Q->xyz_0}: the epoch translation, metres. */
    private final double x0;
    private final double y0;
    private final double z0;

    /** {@code Q->opk_0}: the epoch rotation, radians. */
    private final double rx0;
    private final double ry0;
    private final double rz0;

    /** {@code Q->scale_0}: ppm in 3D, a direct multiplier under {@code +theta}. */
    private final double scale0;

    /** {@code Q->theta_0}, radians. */
    private final double theta0;

    /** {@code Q->dxyz}, metres per year. */
    private final double dx;
    private final double dy;
    private final double dz;

    /** {@code Q->dopk}, radians per year. */
    private final double drx;
    private final double dry;
    private final double drz;

    /** {@code Q->dtheta}, radians per year. */
    private final double dtheta;

    /** {@code Q->dscale}, ppm per year. */
    private final double ds;

    /** {@code Q->t_epoch}, decimal years. */
    private final double tEpoch;

    /** {@code Q->refp}, metres. Always zero for {@code helmert}. */
    private final double refpX;
    private final double refpY;
    private final double refpZ;

    private final boolean exact;
    private final boolean positionVector;
    private final boolean noRotation;

    /** {@code Q->fourparam}: the 2D planar form selected by {@code +theta}. */
    private final boolean fourparam;

    /** Whether any of the eight rates is non-zero. */
    private final boolean timeDependent;

    /** The single precomputed conversion; {@code null} when {@link #timeDependent}. */
    private final HelmertConversion staticConversion;

    private final GieIoUnits units;
    private final double toMeter;
    private final double frMeter;
    private final String description;

    /**
     * {@code PJ_TRANSFORMATION(helmert, 0)} ({@code helmert.cpp:556-694}).
     *
     * @param params the step's fully expanded parameter list
     * @return the operator
     * @throws PipelineDefinitionException on any of upstream's five setup refusals
     */
    static HelmertOperator helmert(final ProjParams params) {
        return new HelmertOperator(params, false);
    }

    /**
     * {@code PJ_TRANSFORMATION(molobadekas, 0)} ({@code helmert.cpp:699-760}).
     *
     * @param params the step's fully expanded parameter list
     * @return the operator
     * @throws PipelineDefinitionException when {@code +convention} is absent or invalid
     */
    static HelmertOperator molobadekas(final ProjParams params) {
        return new HelmertOperator(params, true);
    }

    private HelmertOperator(final ProjParams params, final boolean molobadekas) {
        // ---- init_helmert_six_parameters (:479-517). Both entry points, unchanged.
        double tx = params.doubleValue("x", 0.0);
        double ty = params.doubleValue("y", 0.0);
        double tz = params.doubleValue("z", 0.0);
        double omega = params.doubleValue("rx", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
        double phi = params.doubleValue("ry", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
        double kappa = params.doubleValue("rz", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
        this.exact = params.booleanValue("exact");

        // molobadekas never looks at theta, so +proj=molobadekas +theta=5 stays a
        // 3D cartesian step and silently ignores the key - checked against cct.
        final boolean hasTheta = !molobadekas && params.has("theta");
        this.fourparam = hasTheta;
        this.units = hasTheta ? GieIoUnits.PROJECTED : GieIoUnits.CARTESIAN;

        // ---- :573-579. Presence, not truth: +transpose=F is refused too.
        if (!molobadekas && params.has("transpose")) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "helmert: 'transpose' argument is no longer valid. "
                            + "Use convention=position_vector/coordinate_frame");
        }

        // ---- :583-600. towgs84 overwrites the six parameters read above, including
        // with zeros, so +x=9 +towgs84=1,2,3 has a translation of 1 and not 9.
        double scalePpm = 0.0;
        final boolean hasTowgs84 = !molobadekas && params.has("towgs84");
        if (hasTowgs84) {
            final String raw = params.value("towgs84");
            final double[] dp = Cs2csOperator.parseTowgs84(raw == null ? "" : raw);
            tx = dp[0];
            ty = dp[1];
            tz = dp[2];
            omega = dp[3];
            phi = dp[4];
            kappa = dp[5];
            scalePpm = dp[6] == 0.0 ? 0.0 : (dp[6] - 1.0) * 1e6;
        }

        // ---- :602-606.
        if (hasTheta) {
            this.theta0 = params.doubleValue("theta", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
            scalePpm = 1.0;
        } else {
            this.theta0 = 0.0;
        }

        // ---- :608-621. molobadekas reads +s and validates nothing (:709-712).
        if (params.has("s")) {
            scalePpm = params.doubleValue("s", 0.0);
            if (!molobadekas) {
                if (scalePpm <= -1.0e6) {
                    throw new PipelineDefinitionException(
                            PipelineErrorCode.ILLEGAL_ARG_VALUE,
                            "helmert: invalid value for s.");
                }
                if (hasTheta && scalePpm == 0.0) {
                    throw new PipelineDefinitionException(
                            PipelineErrorCode.ILLEGAL_ARG_VALUE,
                            "helmert: invalid value for s.");
                }
            }
        }

        // ---- :623-661, the eight rates, and :663-665, the epoch.
        if (molobadekas) {
            this.dx = 0.0;
            this.dy = 0.0;
            this.dz = 0.0;
            this.drx = 0.0;
            this.dry = 0.0;
            this.drz = 0.0;
            this.dtheta = 0.0;
            this.ds = 0.0;
            this.tEpoch = 0.0;
        } else {
            this.dx = params.doubleValue("dx", 0.0);
            this.dy = params.doubleValue("dy", 0.0);
            this.dz = params.doubleValue("dz", 0.0);
            this.drx = params.doubleValue("drx", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
            this.dry = params.doubleValue("dry", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
            this.drz = params.doubleValue("drz", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
            this.dtheta = params.doubleValue("dtheta", 0.0) * PipelineUnits.ARCSEC_TO_RAD;
            this.ds = params.doubleValue("ds", 0.0);
            this.tEpoch = params.doubleValue("t_epoch", 0.0);
        }

        this.rx0 = omega;
        this.ry0 = phi;
        this.rz0 = kappa;
        this.scale0 = scalePpm;

        // ---- :672-676. Six values, not three. molobadekas never assigns the field, so
        // its calloc'd zero leaves both the fast path off and the convention required.
        this.noRotation = !molobadekas
                && omega == 0.0 && phi == 0.0 && kappa == 0.0
                && drx == 0.0 && dry == 0.0 && drz == 0.0;

        // ---- read_convention (:519-554).
        if (noRotation) {
            this.positionVector = false;
        } else {
            final String convention = params.value("convention");
            if (convention == null) {
                throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                        "helmert: missing 'convention' argument");
            }
            if ("position_vector".equals(convention)) {
                this.positionVector = true;
            } else if ("coordinate_frame".equals(convention)) {
                this.positionVector = false;
            } else {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "helmert: invalid value for 'convention' argument");
            }
            // :546-553. The check is on the raw key, so it also fires for molobadekas,
            // which never reads towgs84 for anything else.
            if (params.has("towgs84") && !positionVector) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "helmert: towgs84 should only be used with "
                                + "convention=position_vector");
            }
        }

        // ---- :729-737 and :751-757. The reference point is folded into the
        // translation, which is why helmert_forward_3d's comment says Q->xyz already
        // incorporates it.
        if (molobadekas) {
            this.refpX = params.doubleValue("px", 0.0);
            this.refpY = params.doubleValue("py", 0.0);
            this.refpZ = params.doubleValue("pz", 0.0);
            tx += refpX;
            ty += refpY;
            tz += refpZ;
        } else {
            this.refpX = 0.0;
            this.refpY = 0.0;
            this.refpZ = 0.0;
        }

        this.x0 = tx;
        this.y0 = ty;
        this.z0 = tz;

        this.timeDependent = dx != 0.0 || dy != 0.0 || dz != 0.0
                || drx != 0.0 || dry != 0.0 || drz != 0.0
                || dtheta != 0.0 || ds != 0.0;
        this.staticConversion = timeDependent ? null : conversionAt(0.0);

        if (hasTheta) {
            for (int i = 0; i < PROJECTED_SCALING_KEYS.length; i++) {
                if (params.has(PROJECTED_SCALING_KEYS[i])) {
                    throw new PipelineDefinitionException(
                            PipelineErrorCode.NOT_IMPLEMENTED_HERE,
                            "+" + PROJECTED_SCALING_KEYS[i]
                                    + " on a +theta helmert is not implemented");
                }
            }
            this.toMeter = 1.0;
            this.frMeter = 1.0;
        } else {
            this.toMeter = LinearUnits.toMeter(params);
            this.frMeter = 1.0 / toMeter;
        }

        this.description = describe(molobadekas);
    }

    /**
     * {@code update_parameters} ({@code :191-224}) followed by {@code build_rot_matrix}
     * ({@code :226-321}), as a value rather than as a write into shared state.
     *
     * @param dt {@code t_obs - t_epoch}, years
     * @return the conversion in force at that offset from the epoch
     */
    private HelmertConversion conversionAt(final double dt) {
        return new HelmertConversion(
                x0 + dx * dt, y0 + dy * dt, z0 + dz * dt,
                rx0 + drx * dt, ry0 + dry * dt, rz0 + drz * dt,
                scale0 + ds * dt, exact, positionVector,
                refpX, refpY, refpZ, noRotation);
    }

    /**
     * {@code helmert_forward_4d} ({@code :437-455}): {@code HUGE_VAL} means "no
     * observation time given", and then the epoch stands in for it so that
     * {@code dt} is zero.
     *
     * <p>{@code GieCoordParser} defaults a missing ordinate to {@code 0} rather than to
     * {@code HUGE_VAL}, so a corpus row that omits {@code t} on a rate-bearing step
     * would be read here as an observation at year zero. Every rate-bearing row in the
     * corpus states {@code t} explicitly, so no assertion depends on the difference;
     * the infinity is honoured anyway for callers that supply it.
     *
     * @param t the {@code t} ordinate as handed to the step
     * @return {@code t_obs - t_epoch}, years
     */
    private double dt(final double t) {
        final double tObs = Double.isInfinite(t) ? tEpoch : t;
        return tObs - tEpoch;
    }

    private HelmertConversion conversionFor(final double[] coord) {
        return timeDependent ? conversionAt(dt(coord[3])) : staticConversion;
    }

    /** {@code P->left}: {@code CARTESIAN} ({@code :489}), or {@code PROJECTED} under {@code +theta} ({@code :567}). */
    @Override
    public GieIoUnits declaredLeft() {
        return units;
    }

    /** {@code P->right}: {@code CARTESIAN} ({@code :490}), or {@code PROJECTED} under {@code +theta} ({@code :568}). */
    @Override
    public GieIoUnits declaredRight() {
        return units;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    @Override
    public void forward(final double[] coord) {
        if (fourparam) {
            forward2d(coord);
            return;
        }
        conversionFor(coord).forward(coord);
        if (frMeter != 1.0) {
            coord[0] *= frMeter;
            coord[1] *= frMeter;
            coord[2] *= frMeter;
        }
    }

    @Override
    public void inverse(final double[] coord) {
        if (fourparam) {
            inverse2d(coord);
            return;
        }
        if (toMeter != 1.0) {
            coord[0] *= toMeter;
            coord[1] *= toMeter;
            coord[2] *= toMeter;
        }
        conversionFor(coord).inverse(coord);
    }

    /**
     * {@code helmert_forward} ({@code :324-341}).
     *
     * <p>The translation is {@code Q->xyz_0} and not {@code Q->xyz}: see the class
     * comment. {@code z} is untouched.
     *
     * @param coord {@code {x, y, z, t}}, mutated in place
     */
    private void forward2d(final double[] coord) {
        final double dt = dt(coord[3]);
        final double theta = theta0 + dtheta * dt;
        final double scale = scale0 + ds * dt;
        final double cr = Math.cos(theta) * scale;
        final double sr = Math.sin(theta) * scale;
        final double x = coord[0];
        final double y = coord[1];
        coord[0] = cr * x + sr * y + x0;
        coord[1] = -sr * x + cr * y + y0;
    }

    /** {@code helmert_reverse} ({@code :344-360}). */
    private void inverse2d(final double[] coord) {
        final double dt = dt(coord[3]);
        final double theta = theta0 + dtheta * dt;
        final double scale = scale0 + ds * dt;
        final double cr = Math.cos(theta) / scale;
        final double sr = Math.sin(theta) / scale;
        final double x = coord[0] - x0;
        final double y = coord[1] - y0;
        coord[0] = x * cr - y * sr;
        coord[1] = x * sr + y * cr;
    }

    /** Both {@code P->inv3d} and {@code P->inv4d} are always installed. */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return description;
    }

    private String describe(final boolean molobadekas) {
        final StringBuilder sb = new StringBuilder(molobadekas ? "molobadekas" : "helmert");
        sb.append(" x=").append(x0).append(" y=").append(y0).append(" z=").append(z0);
        if (!noRotation) {
            sb.append(" rx=").append(rx0).append(" ry=").append(ry0).append(" rz=").append(rz0);
            sb.append(positionVector ? " position_vector" : " coordinate_frame");
        }
        if (scale0 != 0.0) {
            sb.append(" s=").append(scale0);
        }
        if (exact) {
            sb.append(" exact");
        }
        if (fourparam) {
            sb.append(" theta=").append(theta0);
        }
        if (molobadekas) {
            sb.append(" px=").append(refpX).append(" py=").append(refpY)
                    .append(" pz=").append(refpZ);
        }
        if (timeDependent) {
            sb.append(" t_epoch=").append(tEpoch);
        }
        if (toMeter != 1.0) {
            sb.append(" to_meter=").append(toMeter);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "HelmertOperator[" + description + "]";
    }
}
