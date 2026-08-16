/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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

import static java.lang.Math.*;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Krovak ({@code +proj=krovak}), ported from {@code 9.8.1:src/projections/krovak.cpp}.
 *
 * <p>Krovak's azimuth and pseudo-standard-parallel latitude are hard-coded upstream too and cannot
 * be given from outside; that part of the old comment was correct. Four other things were not.
 *
 * <h2>{@code +ellps} was ignored — but only half of it</h2>
 *
 * <p>Upstream forces Bessel 1841 outright: {@code P->a = 6377397.155; P->es = 0.006674372230614;}.
 * The old code carried the same {@code es} literal but set <b>{@code a = 1}</b> with a comment
 * claiming the ellipsoid is applied by the caller. It is not: {@code Projection.initialize()}
 * computes {@code totalScale = a * fromMetres} from whatever {@code +ellps} supplied, so
 * {@code +proj=krovak +ellps=GRS80} projected onto a <b>GRS80</b> semi-major axis while using
 * Bessel's eccentricity. Both literals are now assigned, before {@code super.initialize()} so that
 * {@code totalScale}, {@code spherical}, {@code one_es} and {@code rone_es} all derive from them.
 *
 * <h2>Three parameter defaults that only PROJ had</h2>
 *
 * <p>{@code krovak_setup} supplies {@code lat_0 = 0.863937979737193} (49&deg;30&prime;N),
 * {@code lon_0 = 0.7417649320975901 - 0.308341501185665} (42&deg;30&prime;E of Ferro less
 * 17&deg;40&prime;, i.e. 24&deg;50&prime;E of Greenwich) and {@code k_0 = 0.9999}, each only when
 * the key is absent. {@code Proj4Parser} assigns those three only when the key is <em>present</em>,
 * so this class's field initialisers were the effective defaults — and they were 0, 0 and 1. The
 * corpus measures it directly: {@code +proj=krovak +ellps=GRS80} at
 * {@code (24.833333333333, 59.757598563058)} must give {@code (0, 0)}, and gave
 * {@code (1370527.32, -301670.39)} — <b>1403 km</b>.
 *
 * <p>Distinguishing "absent" from "explicitly 0" needs a flag per key, because 0 is a legal value
 * for all three; hence the three {@code *Explicit} booleans and the setter overrides. Nothing
 * derived is written into a field that is read again, so the second {@code initialize()} the parser
 * makes sees the same inputs as the first — non-negotiable 4.
 *
 * <h2>Two guards</h2>
 *
 * <ul>
 * <li><b>{@code lat_0} such that {@code tan(lat_0/2 + pi/4) == 0}</b> — that is
 *     {@code lat_0 = -90} — is rejected by upstream at setup with
 *     {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. Without it {@code k} was
 *     {@code something/0 = Infinity} and the forward returned a plausible finite northing.
 *     {@code builtins.gie} asserts the rejection.</li>
 * <li><b>{@code cos(s) &lt; 1e-12} in the forward returns {@code (0, 0)}</b> before the
 *     {@code asin(cos(u)*sin(deltav)/cos(s))} that would otherwise divide by ~0.</li>
 * </ul>
 *
 * <p>The inverse's fixed-point loop keeps its cap. It was written {@code do { … } while (ok == 0);}
 * with <b>no cap at all</b>, which for an input that never reaches the fixed point is a hung thread
 * rather than a catchable error. The budget is now upstream's own {@code MAX_ITER = 100} rather
 * than the 30 borrowed from {@code moll.cpp}, so the set of inputs that resolve is upstream's set.
 *
 * <h2>The inverse deliberately disagrees with PROJ, because PROJ's inverse is wrong</h2>
 *
 * <p>This is the one place in this class where matching upstream was considered and rejected. Read
 * this section before "fixing" anything here to make the corpus pass.
 *
 * <p><b>PROJ's krovak inverse is not the inverse of PROJ's own krovak forward.</b> Feed PROJ
 * {@code +proj=krovak +ellps=GRS80}, project a point, and project it straight back: you land
 * <b>138.6 m</b> away. (Measured at the Guidance Note 7-2 test point,
 * 16&deg;50&prime;59.18&Prime;E 50&deg;12&prime;32.44&Prime;N, with the 9.8.1 binaries. With
 * {@code +ellps=intl} the miss is 185.6 m. The magnitude depends on the {@code +ellps} you name,
 * which is itself the tell, since krovak is documented to ignore it.)
 *
 * <p>The cause is two lines that were never written. {@code krovak_setup} assigns {@code P->a},
 * {@code P->es} and {@code P->e} ({@code 9.8.1:src/projections/krovak.cpp}, lines 287-289) and
 * never assigns {@code P->ra}, nor calls {@code pj_calc_ellipsoid_params} to re-derive it — that
 * function is called zero times in the file. Krovak declares {@code PJ_IO_UNITS_CLASSIC}, so the
 * generic wrappers do the unit scaling: the forward multiplies by {@code P->a}
 * ({@code 9.8.1:src/fwd.cpp}, lines 142-143) and the inverse multiplies by {@code P->ra}
 * ({@code 9.8.1:src/inv.cpp}, lines 92-93). After setup, {@code P->a} is Bessel's 6377397.155 while
 * {@code P->ra} is still the reciprocal of whatever {@code +ellps} supplied — 1/6378137 for GRS80.
 * The two directions are scaled by different ellipsoids, 1.16e-4 apart in relative terms, and at
 * roughly 1.2e6 m from the projection origin that is the 138 m observed. The {@code +ellps} krovak
 * claims to discard leaks back in through the inverse.
 *
 * <p>Upstream knows this hazard in general: every other projection that overwrites the axis at setup
 * repairs {@code ra} as well — {@code calcofi.cpp} lines 139-140, {@code healpix.cpp} line 623 (via
 * {@code pj_calc_ellipsoid_params}) and lines 667-668, {@code nzmg.cpp} line 117 — and
 * {@code inv.cpp} lines 84-89 carries a comment about exactly this class of problem. Krovak is one
 * of the two files that forgot. {@code mod_ster.cpp} is the other — it overwrites {@code P->a} at
 * five sites and never touches {@code ra} — and it shows the same symptom: with the 9.8.1 binaries,
 * {@code +proj=alsk} and {@code +proj=gs50} give bit-identical forward output under
 * {@code +ellps=GRS80}, {@code clrk66} and {@code intl}, correctly ignoring the ellipsoid, yet only
 * {@code clrk66} — the one they force internally — round-trips exactly; GRS80 misses by about 5 m
 * for {@code alsk} and 50 m for {@code gs50}, and {@code intl} misses the other way. That is
 * recorded as a lead for whoever ports those, not chased here.
 *
 * <p><b>What this class does instead.</b> {@code initialize()} assigns the Bessel {@code a} and
 * {@code es} before {@code super.initialize()}, so {@code totalScale} derives from Bessel's axis and
 * is used by <em>both</em> {@code project} and {@code projectInverse}. This class round-trips
 * exactly, and its answer is what krovak's own definition implies.
 *
 * <p>Both answers, pinned, so nobody has to re-derive them. Inverse of
 * {@code +proj=krovak +ellps=GRS80} at (200, 100), (200, &minus;100), (&minus;200, 100),
 * (&minus;200, &minus;100) metres:
 *
 * <table>
 * <caption>Krovak inverse, this class versus PROJ 9.8.1</caption>
 * <tr><th>input</th><th>this class</th><th>PROJ 9.8.1</th></tr>
 * <tr><td>200, 100</td><td>24.836219260 59.758404029</td><td>24.836218919 59.758403933</td></tr>
 * <tr><td>200, &minus;100</td><td>24.836315838 59.756888342</td><td>24.836315485 59.756888426</td></tr>
 * <tr><td>&minus;200, 100</td><td>24.830447406 59.758404029</td><td>24.830447748 59.758403933</td></tr>
 * <tr><td>&minus;200, &minus;100</td><td>24.830350829 59.756888342</td><td>24.830351182 59.756888426</td></tr>
 * </table>
 *
 * <p>The gap is about 22 mm per row, against {@code builtins.gie}'s 0.1 mm tolerance. Asking PROJ
 * for the same inverse with the ellipsoid spelled out — {@code +a=6377397.155 +es=0.006674372230614},
 * or equivalently {@code +ellps=bessel} — reproduces this class's four values exactly, which is the
 * cleanest demonstration that the disagreement is the stale {@code ra} and nothing else.
 *
 * <p><b>The decision, which is settled: keep this behaviour, change no code here, and record PROJ's
 * as an upstream defect.</b> Adopting PROJ's numbers would mean reintroducing the stale reciprocal
 * on purpose — writing a known-wrong scale factor into the inverse so that a corpus generated by the
 * defect agrees with us. That is the wrong trade at 22 mm.
 *
 * <p>The consequence is four rows of {@code builtins.gie}'s
 * {@code +proj=krovak +ellps=GRS80} block — the inverse rows, keys
 * {@code gie/builtins.gie#135:5} through {@code #135:8} — carried as expected failures in
 * {@code conformance/src/test/resources/gie-expected-failures.tsv}. Three details of how they fail
 * are worth keeping, because together they rule out every alternative explanation:
 *
 * <ul>
 * <li>All five <em>forward</em> rows of that block pass. The projection kernel agrees with upstream;
 *     only the outer scaling differs, and only in one direction.</li>
 * <li>The fifth inverse row, {@code accept 0 0}, also passes — at the projection origin the two
 *     scale factors both multiply zero, so the defect cannot show. The error grows with distance
 *     from the origin, exactly as a scale-factor error must.</li>
 * <li>The four failing deviations are 21.942, 21.932, 21.959 and 21.948 mm. The inputs are 224 m
 *     from the origin, where a 1.16e-4 relative scale error predicts about 26 mm — the right size,
 *     and not expected to match to the millimetre, since the deviation is measured on the
 *     geographic side after a non-linear inverse rather than in projected metres.</li>
 * </ul>
 *
 * @see <a href="http://www.ihsenergy.com/epsg/guid7.html#1.4.3"> Guidance Note 7 </a>
 */
public class KrovakProjection extends Projection {

    private static final long serialVersionUID = 6345336388507557850L;

    /** {@code krovak.cpp}: Bessel 1841's semi-major axis, forced regardless of {@code +ellps}. */
    private static final double BESSEL_A = 6377397.155;

    /** {@code krovak.cpp}: Bessel 1841's squared eccentricity, as the literal upstream carries. */
    private static final double BESSEL_ES = 0.006674372230614;

    /** {@code krovak_setup}: 49&deg;30&prime;N, used when {@code +lat_0} is absent. */
    private static final double DEFAULT_LAT_0 = 0.863937979737193;

    /**
     * {@code krovak_setup}: 42&deg;30&prime;E of Ferro less 17&deg;40&prime;, written as the same
     * subtraction upstream writes so the rounding is identical.
     */
    private static final double DEFAULT_LON_0 = 0.7417649320975901 - 0.308341501185665;

    /** {@code krovak_setup}: the scale on the pseudo-standard parallel when {@code +k} is absent. */
    private static final double DEFAULT_K_0 = 0.9999;

    /** {@code UQ}: {@code DU(2, 59, 42, 42.69689)}. */
    private static final double UQ = 1.04216856380474;

    /** {@code S0}: the latitude of the pseudo standard parallel, 78&deg;30&prime;00&quot;N. */
    private static final double S0 = 1.37008346281555;

    /** Whether the {@code +czech} westing/southing axis convention was asked for. */
    private boolean czech = false;

    /** Whether a caller supplied {@code +lat_0} / {@code +lon_0} / {@code +k}, as opposed to 0/0/1. */
    private boolean lat0Explicit, lon0Explicit, kExplicit;

    private double s45, alfa, k, ro0, ad, s0, n;

    /**
     * Iteration cap for the inverse latitude fixed-point loop.
     * <p>
     * The loop was written as {@code do { … } while (ok == 0);} with <b>no cap at all</b>. Its
     * exit test is an absolute difference below 1e-15 radians — about 6 nanometres on the
     * ground — which is at or below the representable spacing of the iterate for latitudes of
     * any magnitude, so an input that does not happen to reach a fixed point makes it spin
     * forever. That is not an error a caller can catch: it is a hung thread, and in a Spark
     * executor a task that never returns and a stage that never completes. A NaN input reaches
     * it directly, since every comparison against NaN is false.
     * <p>
     * 100 is {@code krovak.cpp}'s own {@code MAX_ITER}, so the inputs that resolve here are exactly
     * the inputs that resolve upstream. (It was 30, borrowed from {@code moll.cpp}, before
     * {@code krovak.cpp} was read directly.) The loop settles in a handful of trips for any real
     * coordinate.
     */
    private static final int MAX_ITER = 100;

    /** {@code krovak.cpp}'s {@code EPS}, in radians. Roughly 6 nm on the ground. */
    private static final double ITERATION_TOLERANCE = 1e-15;

    /**
     * Whether the Modified Krovak polynomial correction applies, i.e. whether this is
     * {@code +proj=mod_krovak} rather than {@code +proj=krovak}.
     * <p>
     * Upstream is one function, {@code krovak_setup(P, modified)}, called with
     * {@code false} from {@code PJ_PROJECTION(krovak)} and {@code true} from
     * {@code PJ_PROJECTION(mod_krovak)} ({@code krovak.cpp:336-341}). It is <b>not</b> a
     * parameter — there is no {@code +modified} key — so it is a property of the class
     * and an overridden method rather than a field: a field would have to be assigned
     * before this class's constructor calls {@link #initialize()}, which a subclass
     * cannot do.
     *
     * @return {@code false} here, {@code true} in {@link ModifiedKrovakProjection}
     */
    protected boolean isModified() {
        return false;
    }

    public KrovakProjection() {
        minLatitude = ProjectionMath.toRad(-60);
        maxLatitude = ProjectionMath.toRad(60);
        minLongitude = ProjectionMath.toRad(-90);
        maxLongitude = ProjectionMath.toRad(90);
        initialize();
    }

    /**
     * {@code krovak_setup} ({@code krovak.cpp:280-341}).
     *
     * @throws InvalidValueException where upstream returns
     *         {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}: a {@code +lat_0} for which
     *         {@code tan(lat_0/2 + pi/4)} is 0
     */
    @Override
    public void initialize() {
        // Bessel 1841 is forced, and BEFORE super.initialize(), which derives totalScale from `a`
        // and spherical/one_es/rone_es from `es`. See the class javadoc.
        a = BESSEL_A;
        es = BESSEL_ES;
        e = sqrt(es);

        // pj_param defaults, applied only where the key was absent.
        if (!lat0Explicit) {
            projectionLatitude = DEFAULT_LAT_0;
        }
        if (!lon0Explicit) {
            projectionLongitude = DEFAULT_LON_0;
        }
        if (!kExplicit) {
            scaleFactor = DEFAULT_K_0;
        }

        super.initialize();

        double s90, fi0, e2, u0, g, k1, n0;

        // M_PI_4, not the truncated 0.785398163397448 this file used to carry: upstream writes
        // M_PI_4 and the four-digit truncation moves tan(phi/2 + pi/4) by ~6e-16 relative. Well
        // inside the corpus bar, but rule 2 of this project is that a constant matches upstream
        // digit-for-digit, because five separate defects have been rounded literals.
        s45 = ProjectionMath.QUARTERPI;   /* 45deg */
        s90 = 2 * s45;                    /* M_PI_2; the doubling is exact */
        fi0 = projectionLatitude;    /* Latitude of projection centre 49deg 30' */
        e2 = es;

        alfa = sqrt(1. + (e2 * pow(cos(fi0), 4)) / (1. - e2));

        u0 = asin(sin(fi0) / alfa);
        g = pow(   (1. + e * sin(fi0)) / (1. - e * sin(fi0)) , alfa * e / 2.  );

        double tanHalfFi0PlusS45 = tan(fi0 / 2. + s45);
        if (tanHalfFi0PlusS45 == 0.0) {
            throw new InvalidValueException(
                    "Invalid value for +lat_0: lat_0 + PI/4 should be different from 0, but lat_0 = "
                            + Math.toDegrees(fi0) + " degrees makes tan(lat_0/2 + PI/4) exactly 0, "
                            + "so the Krovak cone constant is undefined");
        }
        k = tan( u0 / 2. + s45) / pow  (tanHalfFi0PlusS45 , alfa) * g;

        k1 = scaleFactor;
        // NOT a * sqrt(...): upstream's n0 is dimensionless and the semi-major axis is applied once,
        // by fwd_finalize -- here by Projection's totalScale. With `a` correctly 6377397.155 rather
        // than 1, keeping the factor would scale the answer by the semi-major axis twice.
        n0 = sqrt(1. - e2) / (1. - e2 * pow(sin(fi0), 2));
        s0 = S0;                     /* Latitude of pseudo standard parallel 78deg 30'00" N */
        n = sin(s0);
        ro0 = k1 * n0 / tan(s0);
        ad = s90 - UQ;
    }

    // ------------------------------------------------------------------------------------
    // Modified Krovak, namespace pj_modified_krovak (krovak.cpp:108-142).
    //
    // These twelve numbers are `constexpr double`, not parameters. There is no +X0, no +C1
    // and nothing for Proj4Keyword to allow: mod_krovak's whole difference from krovak is
    // a fixed polynomial published by the Czech survey office (CUZK), whose reference
    // krovak.cpp:125-127 cites. Reproduced digit-for-digit including the exponent forms.
    // ------------------------------------------------------------------------------------

    /** {@code X0}: the southing, in metres, that the correction polynomial is centred on. */
    private static final double MOD_X0 = 1089000.0;

    /** {@code Y0}: the westing, in metres, that the correction polynomial is centred on. */
    private static final double MOD_Y0 = 654000.0;

    private static final double MOD_C1 = 2.946529277E-02;
    private static final double MOD_C2 = 2.515965696E-02;
    private static final double MOD_C3 = 1.193845912E-07;
    private static final double MOD_C4 = -4.668270147E-07;
    private static final double MOD_C5 = 9.233980362E-12;
    private static final double MOD_C6 = 1.523735715E-12;
    private static final double MOD_C7 = 1.696780024E-18;
    private static final double MOD_C8 = 4.408314235E-18;
    private static final double MOD_C9 = -8.331083518E-24;
    private static final double MOD_C10 = -3.689471323E-24;

    /**
     * The southing half of {@code mod_krovak_compute_dx_dy} ({@code krovak.cpp:127-134}).
     * <p>
     * <b>Why two methods rather than one.</b> Upstream writes both corrections in one
     * function with {@code double&amp;} out-parameters, which Java has no equivalent for.
     * The alternatives were a {@code double[2]} allocated on every projected vertex, or a
     * pair of fields — and a field written from {@code project()} is the shared-mutable-state
     * defect this codebase has spent real effort removing. The two halves share only
     * {@code Xr2}/{@code Yr2}/{@code Xr4}/{@code Yr4}, which are four multiplies; splitting
     * them is bit-identical because every term is an independent product.
     *
     * @param Xr the southing relative to {@link #MOD_X0}, metres
     * @param Yr the westing relative to {@link #MOD_Y0}, metres
     * @return {@code dX}, the southing correction, metres
     */
    private static double modKrovakDX(double Xr, double Yr) {
        final double Xr2 = Xr * Xr;
        final double Yr2 = Yr * Yr;
        final double Xr4 = Xr2 * Xr2;
        final double Yr4 = Yr2 * Yr2;
        return MOD_C1 + MOD_C3 * Xr - MOD_C4 * Yr - 2 * MOD_C6 * Xr * Yr
                + MOD_C5 * (Xr2 - Yr2)
                + MOD_C7 * Xr * (Xr2 - 3 * Yr2) - MOD_C8 * Yr * (3 * Xr2 - Yr2)
                + 4 * MOD_C9 * Xr * Yr * (Xr2 - Yr2)
                + MOD_C10 * (Xr4 + Yr4 - 6 * Xr2 * Yr2);
    }

    /**
     * The westing half of {@code mod_krovak_compute_dx_dy} ({@code krovak.cpp:135-140}).
     * See {@link #modKrovakDX(double, double)} for why this is a second method.
     *
     * @param Xr the southing relative to {@link #MOD_X0}, metres
     * @param Yr the westing relative to {@link #MOD_Y0}, metres
     * @return {@code dY}, the westing correction, metres
     */
    private static double modKrovakDY(double Xr, double Yr) {
        final double Xr2 = Xr * Xr;
        final double Yr2 = Yr * Yr;
        final double Xr4 = Xr2 * Xr2;
        final double Yr4 = Yr2 * Yr2;
        return MOD_C2 + MOD_C3 * Yr + MOD_C4 * Xr + 2 * MOD_C5 * Xr * Yr
                + MOD_C6 * (Xr2 - Yr2)
                + MOD_C8 * Xr * (Xr2 - 3 * Yr2) + MOD_C7 * Yr * (3 * Xr2 - Yr2)
                - 4 * MOD_C10 * Xr * Yr * (Xr2 - Yr2)
                + MOD_C9 * (Xr4 + Yr4 - 6 * Xr2 * Yr2);
    }

    /** {@code +czech}: report westing and southing rather than easting and northing. */
    public void setCzech(boolean czech) {
        this.czech = czech;
    }

    public boolean isCzech() {
        return czech;
    }

    @Override public void setProjectionLatitude(double projectionLatitude) {
        super.setProjectionLatitude(projectionLatitude);
        this.lat0Explicit = true;
    }

    @Override public void setProjectionLatitudeDegrees(double projectionLatitude) {
        super.setProjectionLatitudeDegrees(projectionLatitude);
        this.lat0Explicit = true;
    }

    @Override public void setProjectionLongitude(double projectionLongitude) {
        super.setProjectionLongitude(projectionLongitude);
        this.lon0Explicit = true;
    }

    @Override public void setProjectionLongitudeDegrees(double projectionLongitude) {
        super.setProjectionLongitudeDegrees(projectionLongitude);
        this.lon0Explicit = true;
    }

    @Override public void setScaleFactor(double scaleFactor) {
        super.setScaleFactor(scaleFactor);
        this.kExplicit = true;
    }

    /** {@code krovak_e_forward} ({@code krovak.cpp:146-211}). */
    @Override
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        double gfi, u, deltav, s, d, eps, ro;
        /* Transformation */

        gfi =pow ( ((1. + e * sin(lpphi)) /
                    (1. - e * sin(lpphi))) , (alfa * e / 2.));

        u= 2. * (atan(k * pow( tan(lpphi / 2. + s45), alfa) / gfi)-s45);

        deltav = - lplam * alfa;

        s = ProjectionMath.asinChecked(cos(ad) * sin(u) + sin(ad) * cos(u) * cos(deltav));
        final double cosS = cos(s);
        if (cosS < 1e-12) {
            // krovak.cpp:166-170: the antipode of the cone's apex. Upstream returns the origin
            // rather than dividing by ~0, and its own comment marks it as such.
            out.x = 0;
            out.y = 0;
            return out;
        }
        d = ProjectionMath.asinChecked(cos(u) * sin(deltav) / cosS);
        eps = n * d;
        ro = ro0 * pow(tan(s0 / 2. + s45) , n) / pow(tan(s / 2. + s45) , n)   ;

        // Upstream produces a southing and a westing and then swaps them, so the westing becomes
        // the first ordinate. That is the "x and y are reverted!" of the old comment.
        double southing = ro * cos(eps);
        double westing = ro * sin(eps);

        if (isModified()) {
            // krovak.cpp:174-190. The correction is defined on the SOUTHING/WESTING pair, in
            // metres, and therefore has to be applied here -- before the swap and before the
            // sign flip. Applying it after either would rotate or reflect a polynomial that
            // was fitted in one particular frame.
            final double Xr = southing * a - MOD_X0;
            final double Yr = westing * a - MOD_Y0;
            southing -= modKrovakDX(Xr, Yr) / a;
            westing -= modKrovakDY(Xr, Yr) / a;
        }

        out.x = westing;            /* westing  -> first  */
        out.y = southing;           /* southing -> second */

        if(!czech) {
            // krovak.cpp:203-208, AFTER the swap. The default (non-Czech) convention is
            // easting/northing, so the signs flip - and the false easting/northing has to be taken
            // out twice, because Projection adds it back once after this returns. With
            // +x_0=+y_0=0, as in every corpus row, the two extra terms vanish.
            out.x = -out.x - 2. * falseEasting / a;
            out.y = -out.y - 2. * falseNorthing / a;
        }

        return out;
    }

    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        /* calculate lat/lon from xy */

        /* Constants, identisch wie in der Umkehrfunktion */
        double u, deltav, s, d, eps, ro, fi1;
        int ok;

        /* Transformation */
        // krovak.cpp:215-224: undo the easting/northing convention FIRST (note that x0 is taken off
        // the second ordinate and y0 off the first, because the swap has not happened yet), then
        // swap. The working values are locals: the old translation staged them in `dst` and then
        // overwrote `dst.x`/`dst.y` with the answer, which is only safe because nothing read them
        // back - but it is one line away from the in-place-reassignment defect that cost bipc and
        // airy their rows, so keep the staging visible.
        double wx = x;
        double wy = y;
        if(!czech) {
          double negY = -wy - 2. * falseEasting / a;
          double negX = -wx - 2. * falseNorthing / a;
          wy = negY;
          wx = negX;
        }
        /* revert y, x */
        double southing = wy;
        double westing = wx;

        if (isModified()) {
            // krovak.cpp:220-232, after the swap and before rho/eps -- the mirror of the
            // forward, with the sign of the correction reversed and no re-solve. Upstream's
            // own comment records that this is EPSG guidance note 7-2's Xr'/Yr'/dX'/dY', i.e.
            // the correction is deliberately evaluated at the CORRECTED point rather than
            // iterated back to the uncorrected one. It is not an exact inverse of the forward
            // and it is not meant to be; the residual is sub-millimetre over Czechia.
            final double Xr = southing * a - MOD_X0;
            final double Yr = westing * a - MOD_Y0;
            southing += modKrovakDX(Xr, Yr) / a;
            westing += modKrovakDY(Xr, Yr) / a;
        }

        ro = sqrt(southing * southing + westing * westing);
        eps = atan2(westing, southing);
        d = eps / sin(s0);
        if (ro == 0.0) {
            s = ProjectionMath.HALFPI;
        } else {
            s = 2. * (atan(  pow(ro0 / ro, 1. / n) * tan(s0 / 2. + s45)) - s45);
        }

        u = ProjectionMath.asinChecked(cos(ad) * sin(s) - sin(ad) * cos(s) * cos(d));
        deltav = ProjectionMath.asinChecked(cos(s) * sin(d) / cos(u));

        dst.x = projectionLongitude - deltav / alfa;

        /* ITERATION FOR lp.phi */
        fi1 = u;

        ok = 0;
        int iter = 0;
        double delta;
        do
        {
            dst.y = 2. * ( atan( pow( k, -1. / alfa)  *
                        pow( tan(u / 2. + s45) , 1. / alfa)  *
                        pow( (1. + e * sin(fi1)) / (1. - e * sin(fi1)) , e / 2.)
                        )  - s45);

            delta = abs(fi1 - dst.y);
            if (delta < ITERATION_TOLERANCE) ok=1;
            fi1 = dst.y;

            if (ok == 0 && ++iter >= MAX_ITER) {
                throw new ConvergenceFailureException(this,
                        "inverse latitude iteration did not converge to " + ITERATION_TOLERANCE
                                + " within " + MAX_ITER + " iterations for (x=" + x + ", y=" + y
                                + "); last two iterates differ by " + delta);
            }
        }
        while (ok==0);

        dst.x -= projectionLongitude;

        return dst;
    }

    public String toString() {
        return "Krovak";
    }
}
