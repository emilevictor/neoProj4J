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
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.GeocentricConverter;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +proj=geocent}: geodetic {@code (lambda, phi, h)} to geocentric cartesian
 * {@code (X, Y, Z)} in metres, and back.
 *
 * <p>Not a projection. Its right-hand side is three-dimensional and cartesian, which is why it
 * overrides {@link #projectRadians(ProjCoordinate, ProjCoordinate)} and
 * {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)} wholesale rather than supplying a
 * {@code project(double, double, ProjCoordinate)} kernel: the two-ordinate kernel signature cannot
 * carry {@code z}, and the affine post-multiply the base funnel applies to a projected result is
 * meaningless on a geocentric triple. Upstream draws the same line —
 * {@code 9.8.1:src/conversions/geocent.cpp} declares {@code P->right = PJ_IO_UNITS_CARTESIAN} and
 * its own {@code fwd}/{@code inv} are the identity, with the real work done by
 * {@code fwd_finalize}/{@code inv_prepare} calling {@code +proj=cart}.
 *
 * <h2>What was wrong before 1.5.0</h2>
 *
 * <p>Both directions <b>read {@code dst} instead of {@code src}</b>:
 *
 * <pre>{@code
 * public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
 *     new GeocentricConverter(this.ellipsoid).convertGeodeticToGeocentric(dst);   // dst!
 *     return dst;
 * }
 * }</pre>
 *
 * <p>So the input was ignored and the *output* buffer was converted in place. It survived because
 * the only caller in {@code core} aliases the two arguments:
 * {@code BasicCoordinateTransform.transformClosed} does {@code tgt.setValue(src)} and then calls
 * {@code projectRadians(tgt, tgt)} and {@code inverseProjectRadians(tgt, tgt)}. With
 * {@code src == dst} reading {@code dst} happens to read the input, so every CRS transform through
 * {@code +proj=geocent} was accidentally correct and no test — there were none for this class —
 * could see the defect. It bites the moment the two arguments differ, which is the documented
 * contract of the public {@link #project(ProjCoordinate, ProjCoordinate)},
 * {@link #projectRadians(ProjCoordinate, ProjCoordinate)},
 * {@link #inverseProject(ProjCoordinate, ProjCoordinate)} and
 * {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}, and is what
 * {@code pipeline.Cs2csOperator.projectForward} does (fresh {@code src} and {@code dst}) for every
 * other projection — it special-cases {@code geocent} onto its own {@code CartConversion} and so
 * never exercised this code either.
 *
 * <p>{@link #project(ProjCoordinate, ProjCoordinate)} was worse than wrong: the degrees-in entry
 * point is not virtual through {@code projectRadians(src, dst)} — the base class routes it into the
 * *private* two-ordinate funnel — so it never reached this class at all and returned the base
 * identity plus the affine. It is overridden here for that reason.
 *
 * <h2>Why {@code hasInverse()} has to be declared</h2>
 *
 * <p>{@code BasicCoordinateTransform.inverseAvailable} answers "is there an implementation" by
 * {@code hasInverse() || isGeographic()}, then by looking for a declared
 * {@code projectInverse(double, double, ProjCoordinate)} up the class hierarchy. This class has no
 * such method — it cannot, see above — so before {@code hasInverse()} was declared here the gate
 * classified every {@code +proj=geocent} CRS as non-invertible and refused it as a transformation
 * <em>source</em>. That is 330 CRS in the registry dictionaries (181 {@code epsg} defs plus the
 * pairs the MetaCRS CSVs reference), all of which round-tripped in 1.4.3.
 *
 * <h2>Iterative or closed form?</h2>
 *
 * <p>PROJ 9.8.1 has two geocentric-to-geodetic implementations and {@code +proj=geocent} uses the
 * <b>closed form</b>: {@code geocent.cpp} builds {@code P->cart} ({@code create.cpp:167-175}) and
 * {@code inv_prepare} ({@code inv.cpp:65-70}) runs it, and {@code cart.cpp:156-230} is Bowring's
 * closed form. proj4j's {@link GeocentricConverter} is the other one — the 1996
 * Toms/Hannover iteration ported from PROJ.4's {@code geocent.c}, converging on {@code sin(phi)}
 * to {@code 1e-12} (about 6 um of latitude).
 *
 * <p>This class delegates to {@link GeocentricConverter} anyway, deliberately:
 *
 * <ul>
 * <li>The <b>forward</b> is not a choice: {@code GeocentricConverter.convertGeodeticToGeocentric}
 *     and {@code cart.cpp}'s {@code cartesian()} are the same expression in the same order —
 *     {@code (N + h) * cos(phi) * cos(lam)}, {@code (N + h) * cos(phi) * sin(lam)},
 *     {@code (N * (1 - es) + h) * sin(phi)} with {@code N = a / sqrt(1 - es * sin^2(phi))} — so
 *     they agree bit for bit on the same {@code a} and {@code es}.</li>
 * <li>The <b>inverse</b> differs, and the legacy engine this class serves already uses
 *     {@code GeocentricConverter} for its datum stage
 *     ({@code BasicCoordinateTransform.datumTransform}, a port of PROJ 5.2.0's
 *     {@code pj_transform.c}). Using Bowring here and the iteration one stage later would make a
 *     {@code geocent}-to-{@code geocent} transform disagree with itself by which stage ran. One
 *     kernel per engine is the property worth having: the legacy engine is iterative throughout,
 *     the pipeline engine is Bowring throughout ({@code pipeline.CartConversion}, which is
 *     package-private and so not reachable from here in any case).</li>
 * </ul>
 *
 * <p>The residual ~6 um divergence from 9.8.1 is therefore a property of the <em>engine</em>, not
 * of this class, and it is the pipeline engine's job to retire it by taking over
 * {@code +proj=geocent} — which it already does.
 *
 * <h2>How the linear-unit and offset parameters are handled</h2>
 *
 * <ul>
 * <li><b>{@code +to_meter} / {@code +units} are honoured, on all three ordinates.</b>
 *     {@code fwd_finalize}'s {@code PJ_IO_UNITS_CARTESIAN} branch
 *     ({@code 9.8.1:src/fwd.cpp:133-136}) multiplies {@code x}, {@code y} <em>and</em> {@code z} by
 *     {@code P->fr_meter} — Proj4J's {@link #fromMetres} — and {@code inv_prepare}
 *     ({@code inv.cpp:67-69}) does the mirror with {@code P->to_meter}. Neither is conditional
 *     upstream, and neither is optional here.
 *     <p>This used to be recorded as a deliberate divergence, on the argument that all 181
 *     {@code +proj=geocent} definitions across the five registry dictionaries are {@code +units=m}
 *     so honouring it "would move no observable row". The premise is true and the conclusion was
 *     false: {@code 4D-API_cs2cs-style.gie:488} is
 *     {@code +proj=geocent +a=1000 +b=1000 +to_meter=1000}, whose expected answer for
 *     {@code (90, 0, 0)} is {@code (0, 1, 0)} and which this class answered {@code (0, 1000, 0)} —
 *     999 m out, and out by a factor rather than an offset. Worse, the {@code roundtrip 1} on the
 *     same block <em>passed</em>, because both directions dropped the scale symmetrically and so
 *     closed on a wrong intermediate. A shipped dictionary being unaffected is an argument about
 *     the corpus of definitions, never about the corpus of inputs.
 *     <p>{@code pipeline.CartOperator} and {@code pipeline.Cs2csOperator}'s {@code GEOCENT} kernel
 *     already did this, which is why the sibling assertion on {@code +proj=cart} at
 *     {@code 4D-API_cs2cs-style.gie:493} passed throughout: {@code cart} is not in
 *     {@code Registry}, so it routes to the pipeline engine and never reached this class.</li>
 * <li><b>{@code +x_0} / {@code +y_0} are ignored, and that is correct.</b>
 *     {@code geocent.cpp:53-54} forces {@code P->x0 = P->y0 = 0}, and {@code fwd_finalize}'s
 *     {@code PJ_IO_UNITS_CARTESIAN} branch never adds them.</li>
 * <li><b>{@code +lon_0} <em>is</em> honoured</b>, because upstream honours it:
 *     {@code fwd_prepare} does {@code lam = (lam - from_greenwich) - lam0} then {@code adjlon}
 *     ({@code fwd.cpp:105-112}) and {@code inv_finalize} adds it back
 *     ({@code inv.cpp:110-118}), for cartesian right-hand sides as much as for projected ones.
 *     {@code pipeline.Cs2csOperator} ports exactly that for its {@code GEOCENT} kernel. The
 *     override used to skip it.</li>
 * </ul>
 *
 * @see GeocentricConverter
 */
public class GeocentProjection extends Projection {

    private static final long serialVersionUID = 6460444409174128890L;

    /**
     * The converter and the ellipsoid it was derived from, as one object so that the pair is
     * published atomically. 1.4.3 allocated a {@link GeocentricConverter} on every single
     * coordinate; the ellipsoid can still be replaced after construction
     * ({@code Projection.setEllipsoid}), so the cache is keyed on it rather than built once.
     *
     * <p>The race is benign: two threads may each build an equivalent converter and one write
     * wins. Neither can observe a partially built one, because the field is {@code volatile}.
     */
    private static final class Cached {
        private final Ellipsoid ellipsoid;
        private final GeocentricConverter converter;

        Cached(Ellipsoid ellipsoid) {
            this.ellipsoid = ellipsoid;
            this.converter = new GeocentricConverter(ellipsoid);
        }
    }

    private volatile Cached cached;

    private GeocentricConverter converter() {
        Ellipsoid e = getEllipsoid();
        Cached c = cached;
        if (c == null || c.ellipsoid != e) {
            c = new Cached(e);
            cached = c;
        }
        return c.converter;
    }

    /**
     * The inverse is real and it is {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}.
     * Declared because there is no {@code projectInverse(double, double, ProjCoordinate)} for
     * {@code BasicCoordinateTransform.inverseAvailable} to find; see the class javadoc.
     */
    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Geocentric";
    }

    /**
     * Geodetic degrees to geocentric metres.
     *
     * <p>Overridden because the base class routes this entry point into its private two-ordinate
     * funnel and so would never reach
     * {@link #projectRadians(ProjCoordinate, ProjCoordinate)}: the degrees-in caller would get the
     * base identity, in degrees, with the false easting added.
     *
     * @param src geodetic {@code (lambda, phi)} in degrees, {@code z} in metres
     * @param dst geocentric {@code (X, Y, Z)} in metres; may be the same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate project(ProjCoordinate src, ProjCoordinate dst) {
        double h = src.hasValidZOrdinate() ? src.z : 0.0;
        return forward(src.x * ProjectionMath.DTR, src.y * ProjectionMath.DTR, h, dst);
    }

    /**
     * Geodetic radians to geocentric metres.
     *
     * @param src geodetic {@code (lambda, phi)} in radians, {@code z} in metres
     * @param dst geocentric {@code (X, Y, Z)} in metres; may be the same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate projectRadians(ProjCoordinate src, ProjCoordinate dst) {
        double h = src.hasValidZOrdinate() ? src.z : 0.0;
        return forward(src.x, src.y, h, dst);
    }

    /**
     * Geocentric metres to geodetic radians.
     *
     * @param src geocentric {@code (X, Y, Z)} in metres
     * @param dst geodetic {@code (lambda, phi)} in radians and {@code z} in metres; may be the
     *            same object as {@code src}
     * @return {@code dst}
     */
    @Override
    public ProjCoordinate inverseProjectRadians(ProjCoordinate src, ProjCoordinate dst) {
        double x = src.x;
        double y = src.y;
        double z = src.hasValidZOrdinate() ? src.z : 0.0;
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            // inv_prepare (9.8.1:src/inv.cpp:40-45) rejects HUGE_VAL on all three ordinates,
            // cartesian input included. NaN is rejected too, for the reason
            // Projection.checkForwardDomain gives: a NaN that was asked for is indistinguishable
            // downstream from one the kernel invented.
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "non-finite geocentric input (" + x + ", " + y + ", " + z + ") m");
        }

        // Read src, write dst -- and write all three before converting, so that the conversion
        // reads the input whether or not the caller aliased the two arguments.
        //
        // inv_prepare's PJ_IO_UNITS_CARTESIAN branch (9.8.1:src/inv.cpp:67-69) de-scales all three
        // ordinates by P->to_meter BEFORE the cartesian inverse runs, and adds no false easting.
        // Proj4J stores only the reciprocal (fromMetres == P->fr_meter == 1/to_meter, set by
        // Proj4Parser:344,349), so to_meter is recovered as 1.0/fromMetres. That is a reciprocal of
        // a reciprocal and so is not guaranteed to reproduce the parsed +to_meter to the last bit;
        // recording the parsed value instead would mean a new field on Projection, and the
        // multiply-by-reciprocal is what this class's base already does for totalScaleReciprocal.
        //
        // Guarded on != 1.0 so that the 181 shipped +units=m definitions run exactly the
        // instructions they ran before -- not because 1.0 would change the value (multiplying by
        // exactly 1.0 is bit-identity, unlike the `x + 0.0` guarded further down, which is not the
        // identity on -0.0), but because it keeps the division off the common path and because
        // pipeline.CartOperator and pipeline.Cs2csOperator guard the same way. All three now agree.
        if (fromMetres != 1.0) {
            final double toMeter = 1.0 / fromMetres;
            dst.x = x * toMeter;
            dst.y = y * toMeter;
            dst.z = z * toMeter;
        } else {
            dst.x = x;
            dst.y = y;
            dst.z = z;
        }
        converter().convertGeocentricToGeodetic(dst);
        checkFinite(dst, "inverse", x, y, z);

        // inv_finalize's PJ_IO_UNITS_RADIANS branch (inv.cpp:113-117), which adds BOTH
        // from_greenwich and lam0 and only then wraps. from_greenwich is here rather than in
        // BasicCoordinateTransform for the reason Projection.inverseProjectRadians gives; this class
        // bypasses that funnel, so without these two lines a `+proj=geocent +pm=` lost its prime
        // meridian silently once the composition layer stopped applying it. No shipped definition
        // combines the two -- 181 `+proj=geocent` rows across the five dictionaries, 0 with `+pm=`
        // -- so this is reachable only from a hand-written proj-string, which is exactly the case
        // that would have gone wrong with no test to catch it.
        //
        // Still guarded on "either is non-zero", so that the overwhelmingly common definition with
        // neither is bit-for-bit unchanged: `x + 0.0` is not the identity on -0.0.
        final double fromGreenwich = getPrimeMeridian().getOffsetFromGreenwich();
        if (projectionLongitude != 0 || fromGreenwich != 0) {
            dst.x = ProjectionMath.normalizeLongitude(dst.x + fromGreenwich + projectionLongitude);
        }
        return dst;
    }

    /**
     * The single forward body. {@code lam}/{@code phi} in radians, {@code h} in metres.
     */
    private ProjCoordinate forward(double lam, double phi, double h, ProjCoordinate dst) {
        // fwd_prepare's angular input contract (9.8.1:src/fwd.cpp:54-77) applies to geocent as
        // much as to any projection: geocent.cpp:56 declares P->left = PJ_IO_UNITS_RADIANS.
        phi = checkForwardDomain(lam, phi);
        if (!isFinite(h)) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "non-finite geodetic height " + h + " m");
        }
        // fwd_prepare's subtraction, fwd.cpp:108: from_greenwich AND lam0, associated left to right,
        // then the wrap. Guarded on "either is non-zero" for the -0.0 reason given in
        // inverseProjectRadians, which also explains why the prime meridian is here at all.
        final double fromGreenwich = getPrimeMeridian().getOffsetFromGreenwich();
        if (projectionLongitude != 0 || fromGreenwich != 0) {
            lam = ProjectionMath.normalizeLongitude((lam - fromGreenwich) - projectionLongitude);
        }

        dst.x = lam;
        dst.y = phi;
        dst.z = h;
        converter().convertGeodeticToGeocentric(dst);
        // fwd_finalize's PJ_IO_UNITS_CARTESIAN branch (9.8.1:src/fwd.cpp:133-136): all three
        // ordinates, no false easting, no separate vertical unit. See inverseProjectRadians for why
        // the guard is on != 1.0 and why this is the mirror of that method's 1.0/fromMetres.
        if (fromMetres != 1.0) {
            dst.x *= fromMetres;
            dst.y *= fromMetres;
            dst.z *= fromMetres;
        }
        // After the scale, so the postcondition covers the value actually returned.
        checkFinite(dst, "forward", lam, phi, h);
        return dst;
    }

    /**
     * The output postcondition. This class bypasses the base funnel, so it owns the funnel's
     * promise that returning normally implies a finite result — and it owns it for {@code z} too,
     * which the two-ordinate funnel never checked.
     */
    private void checkFinite(ProjCoordinate p, String direction, double a, double b, double c) {
        if (!isFinite(p.x) || !isFinite(p.y) || !isFinite(p.z)) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "geocentric " + direction + " conversion of (" + a + ", " + b + ", " + c
                            + ") returned a non-finite coordinate (" + p.x + ", " + p.y + ", "
                            + p.z + ")");
        }
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
