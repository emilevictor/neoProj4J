/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.bridge;

import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.number;
import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.projDouble;
import static org.locationtech.proj4j.conformance.bridge.ProjDefinitionValidator.radians;

/**
 * Per-operator setup validation, transcribed from PROJ 9.8.1's own
 * {@code PJ_PROJECTION} / {@code PJ_TRANSFORMATION} / {@code PJ_CONVERSION} bodies.
 *
 * <h2>Why this is not circular</h2>
 *
 * <p>{@link ProjDefinitionValidator}'s contract is "return {@code INVALID_DEFINITION}
 * if PROJ 9.8.1 would reject this", and it is the <em>only</em> thing allowed to
 * decide that, precisely so the decision never rests on the {@code expect failure}
 * row being scored. This class extends it the legitimate way: each method is a
 * transcription of one operator's construction-time guards, taken from the source
 * at rev {@code 9.8.1}, with the file and line cited on every check.
 *
 * <p>Two disciplines keep that honest, and both are visible in the code:
 *
 * <ol>
 * <li><b>Whole setup functions are transcribed, not the guards the corpus happens to
 *     hit.</b> {@link #isea} rejects a bad {@code +orient} and {@link #urm5} rejects a
 *     missing {@code +n}, and <em>no corpus row exercises either</em>; {@code omerc}'s
 *     five lat guards are all here though the corpus reaches three. A check that only
 *     exists because a row needs it would stand out.</li>
 * <li><b>Every check was probed against the installed {@code proj 9.8.1} binary in
 *     both directions</b> — a firing case and a near-miss that must still be accepted.
 *     Those probes are recorded in {@link ProjOperatorSetupTest} as executable
 *     assertions, so the model cannot drift from the oracle silently.</li>
 * </ol>
 *
 * <p>Where a guard needs the resolved ellipsoid, {@link #shape} answers only when the
 * definition determines it without a named {@code +datum}, an {@code +init=} or a
 * spherification flag — and, for a named {@code +ellps}, only when an explicit shape
 * parameter is overriding it. It returns {@code null} otherwise and every dependent
 * check is then skipped. That is deliberately fail-open: a skipped check costs an
 * assertion, and a wrong one manufactures a false pass.
 */
final class ProjOperatorSetup {

    /** {@code lcc.cpp:10}, {@code aea.cpp:35}, {@code eqdc.cpp:25}. */
    private static final double EPS10 = 1.e-10;

    /** {@code omerc.cpp:43}. */
    private static final double OMERC_TOL = 1.e-7;

    /** {@code omerc.cpp:44}. */
    private static final double OMERC_EPS = 1.e-10;

    /** {@code aasincos.cpp:8} — {@code aasin} only raises above this. */
    private static final double ONE_TOL = 1.00000000000001;

    /** {@code lagrng.cpp:10}. */
    private static final double LAGRNG_TOL = 1.e-10;

    /** {@code nsper.cpp:159} — {@code pn1 = h / a} must be in {@code ]0, 1e10]}. */
    private static final double NSPER_MAX_PN1 = 1.e10;

    /** {@code geos.cpp:227} — {@code radius_g_1 = h / a} must be in {@code ]0, 1e10]}. */
    private static final double GEOS_MAX_RADIUS_G_1 = 1.e10;

    /** {@code imw_p.cpp:13}. */
    private static final double IMW_P_EPS = 1.e-10;

    /** {@code chamb.cpp:31} — below this a side of the control triangle counts as zero. */
    private static final double CHAMB_TOL = 1.e-9;

    /** {@code krovak.cpp:293} — 49d30'N, used when {@code +lat_0} is absent. */
    private static final double KROVAK_DEFAULT_PHI0 = 0.863937979737193;

    private static final double HALF_PI = Math.PI / 2.0;
    private static final double FORT_PI = Math.PI / 4.0;

    /** {@code ellps.cpp}: {@code {"GRS80", "a=6378137.0", "rf=298.257222101"}}. */
    private static final double GRS80_A = 6378137.0;
    private static final double GRS80_RF = 298.257222101;

    /**
     * {@code ell_set.cpp:352-353} — the spherification flags. Any of them replaces the
     * ellipsoid with a derived sphere, so {@link #shape} declines rather than model it.
     */
    private static final String[] SPHERIFICATION_KEYS = {
            "R_A", "R_V", "R_a", "R_g", "R_h", "R_lat_a", "R_lat_g", "R_C"
    };

    private ProjOperatorSetup() {
    }

    /**
     * @param a a non-pipeline definition whose {@code +proj} names a PROJ 9.8.1
     *     operator and whose global parameters have already been validated
     * @return an {@code INVALID_DEFINITION} failure if this operator's setup function
     *     would reject it, otherwise {@code null}
     */
    static GieFailure validate(GieProjArgs a) {
        String name = a.peek("proj");
        if (name == null) {
            return null;
        }
        if ("lcc".equals(name)) {
            return lcc(a);
        }
        if ("aea".equals(name) || "leac".equals(name)) {
            return aea(a, "leac".equals(name));
        }
        if ("eqdc".equals(name)) {
            return eqdc(a);
        }
        if ("omerc".equals(name)) {
            return omerc(a);
        }
        if ("lagrng".equals(name)) {
            return lagrng(a);
        }
        if ("krovak".equals(name) || "mod_krovak".equals(name)) {
            return krovak(a);
        }
        if ("labrd".equals(name)) {
            return labrd(a);
        }
        if ("nsper".equals(name) || "tpers".equals(name)) {
            return nsper(a);
        }
        if ("geos".equals(name)) {
            return geos(a);
        }
        if ("urm5".equals(name)) {
            return urm5(a);
        }
        if ("urmfps".equals(name)) {
            return urmfps(a);
        }
        if ("gn_sinu".equals(name)) {
            return gnSinu(a);
        }
        if ("oea".equals(name)) {
            return oea(a);
        }
        if ("chamb".equals(name)) {
            return chamb(a);
        }
        if ("imw_p".equals(name)) {
            return imwP(a);
        }
        if ("s2".equals(name)) {
            return s2(a);
        }
        if ("rhealpix".equals(name)) {
            return rhealpix(a);
        }
        if ("isea".equals(name)) {
            return isea(a);
        }
        if ("airocean".equals(name)) {
            return airocean(a);
        }
        if ("ob_tran".equals(name)) {
            return obTran(a);
        }
        if ("topocentric".equals(name)) {
            return topocentric(a);
        }
        if ("helmert".equals(name)) {
            return helmert(a);
        }
        if ("molobadekas".equals(name)) {
            return molobadekas(a);
        }
        if ("molodensky".equals(name)) {
            return molodensky(a);
        }
        if ("defmodel".equals(name)) {
            return defmodel(a);
        }
        if ("gridshift".equals(name)) {
            return gridshift(a);
        }
        if ("xyzgridshift".equals(name)) {
            return xyzgridshift(a);
        }
        if ("ups".equals(name)) {
            return ups(a);
        }
        if ("utm".equals(name)) {
            return utm(a);
        }
        if ("sterea".equals(name)) {
            return sterea(a);
        }
        return null;
    }

    // --------------------------------------------------------------- conics

    /**
     * {@code lcc.cpp:88-141}. {@code lat_2} defaults to {@code lat_1} rather than to
     * zero, which is why {@code +proj=lcc +ellps=GRS80} with no parallels at all is
     * rejected: both are 0 and the sum guard fires.
     *
     * <p>The two secant-cone eccentricity guards at {@code :126-131} and
     * {@code :134-139} are ported. Both are pure closed form — {@code pj_msfn} is one
     * expression and {@code pj_tsfn} is two — so unlike {@code eqdc}'s they can be
     * evaluated exactly rather than through a series; see {@link #msfn} and
     * {@link #tsfn}. They fire only when {@code es} is so close to 1 that the two
     * {@code msfn}, or the two {@code tsfn}, values are indistinguishable, which is
     * what {@code builtins.gie} block 166 ({@code +a=9999999 +b=.9}) asserts.
     */
    private static GieFailure lcc(GieProjArgs a) {
        double phi1 = radians(a, "lat_1", 0.0);
        double phi2 = a.contains("lat_2") ? radians(a, "lat_2", 0.0) : phi1;

        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid("lcc", "|lat_1 + lat_2| should be > 0 (lcc.cpp:97-100)");
        }
        if (Math.abs(Math.cos(phi1)) < EPS10 || Math.abs(phi1) >= HALF_PI) {
            return invalid("lcc", "|lat_1| should be < 90 degrees (lcc.cpp:105-109)");
        }
        if (Math.abs(Math.cos(phi2)) < EPS10 || Math.abs(phi2) >= HALF_PI) {
            return invalid("lcc", "|lat_2| should be < 90 degrees (lcc.cpp:110-114)");
        }

        // lcc.cpp:117-141. Only the secant branch has the guards, and only on an
        // ellipsoid; shape() declining costs the check rather than guessing at one.
        boolean secant = Math.abs(phi1 - phi2) >= EPS10;
        double[] ell = shape(a);
        if (!secant || ell == null || ell[1] == 0.0) {
            return null;
        }
        double es = ell[1];
        double e = Math.sqrt(es);
        double sinphi1 = Math.sin(phi1);
        double sinphi2 = Math.sin(phi2);
        double m1 = msfn(sinphi1, Math.cos(phi1), es);
        if (Math.log(m1 / msfn(sinphi2, Math.cos(phi2), es)) == 0.0) {
            return invalid("lcc", "eccentricity is indistinguishable from 1, so the two "
                    + "msfn values are equal and the cone constant is 0 (lcc.cpp:125-131)");
        }
        if (Math.log(tsfn(phi1, sinphi1, e) / tsfn(phi2, sinphi2, e)) == 0.0) {
            return invalid("lcc", "eccentricity is indistinguishable from 1, so the two "
                    + "tsfn values are equal and the cone constant divides by 0 "
                    + "(lcc.cpp:132-139)");
        }
        return null;
    }

    /**
     * {@code aea.cpp:130-147}, shared by {@code aea} and {@code leac} through
     * {@code setup()}. {@code leac} substitutes its own parallels at
     * {@code aea.cpp:222-223}: {@code phi2} comes from {@code +lat_1} and {@code phi1}
     * is a pole chosen by {@code +south}, so {@code +lat_2} is ignored entirely.
     *
     * <p>Note the bound is {@code > 90} here, not {@code >= 90} as in {@code lcc} —
     * {@code leac} depends on {@code |phi1| == 90} being legal.
     */
    private static GieFailure aea(GieProjArgs a, boolean leac) {
        double phi1;
        double phi2;
        if (leac) {
            phi2 = radians(a, "lat_1", 0.0);
            boolean south = a.contains("south")
                    && ProjDefinitionValidator.projBooleanValue(a.peek("south"));
            phi1 = south ? -HALF_PI : HALF_PI;
        } else {
            phi1 = radians(a, "lat_1", 0.0);
            phi2 = radians(a, "lat_2", 0.0);
        }
        String op = leac ? "leac" : "aea";
        if (Math.abs(phi1) > HALF_PI) {
            return invalid(op, "|lat_1| should be <= 90 degrees (aea.cpp:127-131)");
        }
        if (Math.abs(phi2) > HALF_PI) {
            return invalid(op, "|lat_2| should be <= 90 degrees (aea.cpp:132-136)");
        }
        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid(op, "|lat_1 + lat_2| should be > 0 (aea.cpp:137-141)");
        }
        return null;
    }

    /**
     * {@code eqdc.cpp:84-102} (the three latitude guards) and {@code :127-132} (the
     * ellipsoidal secant cone constant).
     *
     * <h2>The cone constant guard, and the argument that used to decline it</h2>
     *
     * <p>Upstream computes {@code Q->n = (m1 - pj_msfn(sinphi2, cosphi2, es)) / (ml2 -
     * ml1)} and rejects {@code Q->n == 0}. The numerator is closed form —
     * {@code pj_msfn}, already here as {@link #msfn} — but the denominator is a
     * difference of {@code pj_mlfn} values, and {@code pj_mlfn} is a 6th-order
     * expansion in the third flattening documented as accurate only for
     * {@code |f| <= 1/150} ({@code mlfn.cpp:5-7}), which the rows that reach this guard
     * are far outside.
     *
     * <p>This method used to decline the guard on that basis, arguing that if
     * {@code pj_mlfn} returned a non-finite value then {@code Q->n} would be
     * {@code NaN}, {@code NaN == 0} would be false, and PROJ would <em>build</em> the
     * operation — so an exactly-zero numerator would not imply a rejection.
     * <b>That argument does not hold.</b> Enumerate what a zero numerator can divide
     * by: a finite non-zero denominator gives {@code ±0.0}, which is
     * {@code == 0}; {@code ±inf} also gives {@code ±0.0}; and a zero denominator is
     * {@code ml1 == ml2}, which the guard immediately above at {@code :121-125} has
     * already refused with the same {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}. The
     * only escape really is a {@code NaN} denominator, and {@code pj_mlfn} cannot
     * produce one here: it is {@code pj_rectifying_radius(n) * pj_auxlat_convert(...)}
     * ({@code mlfn.cpp:9-26}), a ratio of polynomials in {@code n} over {@code 1 + n}
     * ({@code latitudes.cpp:415-420}) composed with {@code sin}/{@code cos}, and
     * {@code n = f / (2 - f)} is in {@code [0, 1]} for every {@code f} that
     * {@code pj_calc_ellipsoid_params} lets through. Inaccurate is not the same as
     * non-finite, and only non-finite would have saved the old argument.
     *
     * <p>So the guard is decided from the numerator alone. Probed against the installed
     * 9.8.1 on fourteen definitions, agreeing on all fourteen, including the three that
     * separate this from "es is close to 1": {@code +a=9999999 +b=.9} is refused at
     * {@code lat_1=1} and at {@code 0.5/2} but <em>accepted</em> at {@code 30/45}, where
     * the two {@code msfn} values still differ by 2.8e-15, and accepted at
     * {@code lat_1=lat_2=1}, where the cone is tangent so upstream never enters the
     * branch. Both directions are in {@link ProjOperatorSetupTest}.
     *
     * <p>The spherical {@code n == 0} guard at {@code :137-143} is <b>not</b> ported,
     * and needs nothing: {@code n} there is {@code sin(phi1)} when tangent and
     * {@code (cos phi1 - cos phi2) / (phi2 - phi1)} when secant, and both are zero only
     * when {@code phi2 == -phi1}, which the {@code |lat_1 + lat_2|} guard above has
     * already refused. It is unreachable, not skipped.
     */
    private static GieFailure eqdc(GieProjArgs a) {
        double phi1 = radians(a, "lat_1", 0.0);
        double phi2 = radians(a, "lat_2", 0.0);
        if (Math.abs(phi1) > HALF_PI) {
            return invalid("eqdc", "|lat_1| should be <= 90 degrees (eqdc.cpp:85-89)");
        }
        if (Math.abs(phi2) > HALF_PI) {
            return invalid("eqdc", "|lat_2| should be <= 90 degrees (eqdc.cpp:91-95)");
        }
        if (Math.abs(phi1 + phi2) < EPS10) {
            return invalid("eqdc", "|lat_1 + lat_2| should be > 0 (eqdc.cpp:96-100)");
        }

        // eqdc.cpp:117 -- only the secant cone reaches the ellipsoidal n == 0 guard.
        boolean secant = Math.abs(phi1 - phi2) >= EPS10;
        double[] ell = shape(a);
        if (!secant || ell == null || ell[1] == 0.0) {
            return null;
        }
        double es = ell[1];
        double m1 = msfn(Math.sin(phi1), Math.cos(phi1), es);
        double m2 = msfn(Math.sin(phi2), Math.cos(phi2), es);
        if (m1 - m2 == 0.0) {
            return invalid("eqdc", "eccentricity is indistinguishable from 1, so the two "
                    + "msfn values are equal and the cone constant is 0 (eqdc.cpp:127-132)");
        }
        return null;
    }

    /**
     * {@code omerc.cpp:132-195} and {@code :224-248}. Two disjoint parameterisations:
     * {@code +alpha}/{@code +gamma} with {@code +lonc}, or the two-point
     * {@code +lat_1}/{@code +lon_1}/{@code +lat_2}/{@code +lon_2} form. The
     * {@code |lat_0| < 90} guard belongs to both.
     *
     * <p>The {@code +gamma}-without-{@code +alpha} limit is the interesting one. PROJ
     * computes {@code alpha_c = aasin(D * sin(gamma))} and then <em>tests
     * {@code proj_errno}</em> ({@code :232-239}), which {@code aasin} sets only when
     * the argument exceeds {@code ONE_TOL} ({@code aasincos.cpp:14-19}). So the limit
     * is {@code |D sin(gamma)| > 1.00000000000001}, and the slack matters: on a sphere
     * with {@code +lat_0=10} the limit is exactly {@code gamma = 80}, where
     * {@code D sin(gamma)} rounds to within an ulp of 1 and must <em>not</em> raise.
     * The {@code +alpha} branch at {@code :227-230} calls {@code aasin} too but never
     * checks the errno, so there is no guard there.
     *
     * <p>Confirmed against the oracle, including the case the corpus gets wrong:
     * {@code builtins.gie:5335} labels {@code +R=6400000 +rf=300 +gamma=80.01} "# OK",
     * but {@code +R} overrules every shape parameter ({@code ell_set.cpp:90-98}) so it
     * is a sphere, and {@code proj 9.8.1} rejects it with "|gamma| should be <=
     * 80.000000". That block asserts nothing, so the comment was never checked.
     */
    private static GieFailure omerc(GieProjArgs a) {
        boolean alp = a.contains("alpha");
        boolean gam = a.contains("gamma");
        double phi0 = radians(a, "lat_0", 0.0);

        if (!alp && !gam) {
            double phi1 = radians(a, "lat_1", 0.0);
            double phi2 = radians(a, "lat_2", 0.0);
            if (Math.abs(phi1) > HALF_PI - OMERC_TOL) {
                return invalid("omerc", "|lat_1| should be < 90 degrees (omerc.cpp:157-162)");
            }
            if (Math.abs(phi2) > HALF_PI - OMERC_TOL) {
                return invalid("omerc", "|lat_2| should be < 90 degrees (omerc.cpp:164-169)");
            }
            if (Math.abs(phi1 - phi2) <= OMERC_TOL) {
                return invalid("omerc",
                        "lat_1 should be different from lat_2 (omerc.cpp:171-177)");
            }
            if (Math.abs(phi1) <= OMERC_TOL) {
                return invalid("omerc",
                        "lat_1 should be different from 0 (omerc.cpp:179-185)");
            }
        }
        if (Math.abs(Math.abs(phi0) - HALF_PI) <= OMERC_TOL) {
            return invalid("omerc", "|lat_0| should be < 90 degrees "
                    + "(omerc.cpp:187-192 in the two-point branch, :243-248 otherwise)");
        }
        if (gam && !alp) {
            double[] ell = shape(a);
            if (ell != null) {
                double es = ell[1];
                double gamma = radians(a, "gamma", 0.0);
                double d = omercD(es, phi0);
                if (Math.abs(d * Math.sin(gamma)) > ONE_TOL) {
                    return invalid("omerc", "given lat_0, |gamma| should be <= "
                            + Math.toDegrees(Math.asin(1.0 / d))
                            + " degrees (omerc.cpp:231-239 via aasin)");
                }
            }
        }

        // omerc.cpp:252-275, the two-point branch only - the +alpha/+gamma branch at
        // :222-250 has no such guard. Both are pure closed form (pj_tsfn plus pow), so
        // unlike eqdc's they can be evaluated exactly. They fire when es is
        // indistinguishable from 1, which is what builtins.gie block 241
        // (+lat_1=0.8 +a=6400000 +b=.4) asserts.
        if (!alp && !gam) {
            double[] ell = shape(a);
            if (ell != null) {
                double es = ell[1];
                double e = Math.sqrt(es);
                double[] be = omercConstants(es, phi0);
                double bl = be[0];
                double el = be[1];
                double phi1 = radians(a, "lat_1", 0.0);
                double phi2 = radians(a, "lat_2", 0.0);
                double h = Math.pow(tsfn(phi1, Math.sin(phi1), e), bl);
                double l = Math.pow(tsfn(phi2, Math.sin(phi2), e), bl);
                if ((l - h) / (l + h) == 0.0) {
                    return invalid("omerc", "eccentricity is indistinguishable from 1, so "
                            + "the two-point centre line is undefined (omerc.cpp:255-261)");
                }
                double f = el / h;
                if (f - 1.0 / f == 0.0) {
                    return invalid("omerc", "eccentricity is indistinguishable from 1, so "
                            + "the two-point centre line azimuth is undefined "
                            + "(omerc.cpp:270-275)");
                }
            }
        }
        return null;
    }

    /**
     * {@code omerc.cpp:199-221} — {@code Q->B}, {@code Q->E} and {@code D}, in that
     * order. {@code Q->A} is omitted: it is the only one that reads {@code +k_0}, and no
     * guard depends on it.
     *
     * @param es the resolved squared eccentricity
     * @param phi0 {@code +lat_0} in radians
     * @return {@code {B, E, D}}
     */
    private static double[] omercConstants(double es, double phi0) {
        double oneEs = 1.0 - es;
        double com = Math.sqrt(oneEs);
        if (Math.abs(phi0) <= OMERC_EPS) {
            // :217-221 - B is 1/com and E, D and F are all exactly 1.
            return new double[] {1.0 / com, 1.0, 1.0};
        }
        double sinph0 = Math.sin(phi0);
        double cosph0 = Math.cos(phi0);
        double con = 1.0 - es * sinph0 * sinph0;
        double b = cosph0 * cosph0;
        b = Math.sqrt(1.0 + es * b * b / oneEs);
        double d = b * com / (cosph0 * Math.sqrt(con));
        double f = d * d - 1.0;
        if (f <= 0.0) {
            f = 0.0;
        } else {
            f = Math.sqrt(f);
            if (phi0 < 0.0) {
                f = -f;
            }
        }
        f += d;
        double el = f * Math.pow(tsfn(phi0, sinph0, Math.sqrt(es)), b);
        return new double[] {b, el, d};
    }

    /** {@code omerc.cpp:199-221} — {@code D}, which is what bounds {@code +gamma}. */
    private static double omercD(double es, double phi0) {
        return omercConstants(es, phi0)[2];
    }

    // ------------------------------------------------ PROJ's closed-form helpers

    /**
     * {@code msfn.cpp:5-7} — {@code pj_msfn}, verbatim.
     *
     * @param sinphi {@code sin(phi)}
     * @param cosphi {@code cos(phi)}
     * @param es squared eccentricity
     * @return {@code m(phi)}
     */
    private static double msfn(double sinphi, double cosphi, double es) {
        return cosphi / Math.sqrt(1.0 - es * sinphi * sinphi);
    }

    /**
     * {@code tsfn.cpp:6-35} — {@code pj_tsfn}, verbatim, including the branch on the
     * sign of {@code sinphi} that keeps the {@code cos/(1+sin)} form well conditioned.
     *
     * @param phi latitude in radians
     * @param sinphi {@code sin(phi)}
     * @param e eccentricity
     * @return {@code exp(-psi)}, {@code psi} being the isometric latitude
     */
    private static double tsfn(double phi, double sinphi, double e) {
        double cosphi = Math.cos(phi);
        return Math.exp(e * atanh(e * sinphi))
                * (sinphi > 0 ? cosphi / (1 + sinphi) : (1 - sinphi) / cosphi);
    }

    /**
     * {@code atanh}, which {@code java.lang.Math} has no form of.
     *
     * <p>Written as {@code 0.5 * log1p(2x / (1 - x))} rather than the algebraically
     * equal {@code 0.5 * log((1 + x) / (1 - x))}: the latter loses the whole result to
     * cancellation as {@code x} approaches 0, and {@code +lat_2} defaulting to 0 makes
     * {@code x == 0} the single most common argument here.
     *
     * @param x the argument
     * @return {@code atanh(x)}
     */
    private static double atanh(double x) {
        return 0.5 * Math.log1p(2.0 * x / (1.0 - x));
    }

    // ------------------------------------------------- single-guard operators

    /** {@code lagrng.cpp:79-95}. {@code +W} defaults to 2, so it is not required. */
    private static GieFailure lagrng(GieProjArgs a) {
        double w = number(a, "W", 2.0);
        if (w <= 0) {
            return invalid("lagrng", "W should be > 0 (lagrng.cpp:83-86)");
        }
        double sinPhi1 = Math.sin(radians(a, "lat_1", 0.0));
        if (Math.abs(Math.abs(sinPhi1) - 1.0) < LAGRNG_TOL) {
            return invalid("lagrng", "|lat_1| should be < 90 degrees (lagrng.cpp:90-95)");
        }
        return null;
    }

    /**
     * {@code krovak.cpp:317-322}, reached from both {@code krovak} and
     * {@code mod_krovak} through {@code krovak_setup}. The test is an exact
     * {@code == 0.0} on {@code tan(lat_0/2 + pi/4)}, and {@code +lat_0} defaults to
     * 49d30'N ({@code :292-293}) rather than to 0 — so a bare {@code +proj=krovak} is
     * fine and only {@code +lat_0=-90} degenerates.
     */
    private static GieFailure krovak(GieProjArgs a) {
        double phi0 = radians(a, "lat_0", KROVAK_DEFAULT_PHI0);
        if (Math.tan(phi0 / 2.0 + FORT_PI) == 0.0) {
            return invalid("krovak",
                    "lat_0 + PI/4 should be different from 0 (krovak.cpp:317-322)");
        }
        return null;
    }

    /** {@code labrd.cpp:111-115}. {@code +lat_0} defaults to 0, which is the reject. */
    private static GieFailure labrd(GieProjArgs a) {
        if (radians(a, "lat_0", 0.0) == 0.0) {
            return invalid("labrd",
                    "lat_0 should be different from 0 (labrd.cpp:111-115)");
        }
        return null;
    }

    /**
     * {@code nsper.cpp:147,158-162}, shared with {@code tpers}. {@code +h} defaults to
     * 0, so it is effectively required. {@code pn1 = h / a}: the lower bound needs no
     * ellipsoid because {@code a > 0} is already established, the upper bound does.
     */
    private static GieFailure nsper(GieProjArgs a) {
        double h = number(a, "h", 0.0);
        if (h <= 0) {
            return invalid("nsper", "h / a must be > 0 (nsper.cpp:158-162)");
        }
        double[] ell = shape(a);
        if (ell != null && h / ell[0] > NSPER_MAX_PN1) {
            return invalid("nsper", "h / a must be <= 1e10 (nsper.cpp:158-162)");
        }
        return null;
    }

    /**
     * {@code geos.cpp:198-230}. The same {@code h / a} model as {@link #nsper}, on the
     * other operator that takes an orbit height, plus a {@code +sweep} value set.
     *
     * <p>{@code +h} is read with the {@code 'd'} sigil and no presence test
     * ({@code :206}), so an absent {@code +h} is 0 and is refused by the same value test
     * as an explicit {@code +h=0} — which is exactly what {@code builtins.gie:2183}
     * asserts. As in {@code nsper}, the lower bound needs no ellipsoid because
     * {@code a > 0} is already established and the upper bound does.
     *
     * <p>The {@code +sweep} guard ({@code :212-218}) is transcribed although no corpus
     * row carries the keyword and although proj4j ignores {@code +sweep} altogether:
     * this class says what PROJ 9.8.1 does with a definition, not what proj4j does with
     * it. Upstream compares {@code sweep_axis[0]} against {@code 'x'}/{@code 'y'} and
     * then requires {@code sweep_axis[1] == '\0'}, so the legal set is the two
     * one-character strings and nothing else, including the empty one.
     */
    private static GieFailure geos(GieProjArgs a) {
        GieFailure f = oneOf(a, "geos", "sweep", new String[] {"x", "y"},
                "geos.cpp:212-218");
        if (f != null) {
            return f;
        }
        double h = number(a, "h", 0.0);
        if (h <= 0) {
            return invalid("geos", "h / a must be > 0 (geos.cpp:206,226-230)");
        }
        double[] ell = shape(a);
        if (ell != null && h / ell[0] > GEOS_MAX_RADIUS_G_1) {
            return invalid("geos", "h / a must be <= 1e10 (geos.cpp:226-230)");
        }
        return null;
    }

    /** {@code urm5.cpp:37-57}. */
    private static GieFailure urm5(GieProjArgs a) {
        if (!a.contains("n")) {
            return invalid("urm5", "missing parameter n (urm5.cpp:37-40)");
        }
        double n = number(a, "n", 0.0);
        if (n <= 0.0 || n > 1.0) {
            return invalid("urm5", "n should be in ]0,1] (urm5.cpp:42-47)");
        }
        double t = n * Math.sin(radians(a, "alpha", 0.0));
        if (Math.sqrt(1.0 - t * t) == 0.0) {
            return invalid("urm5",
                    "n * sin(|alpha|) should be < 1 (urm5.cpp:52-58)");
        }
        return null;
    }

    /**
     * {@code urmfps.cpp:48-69}: a presence test on {@code +n} ({@code :56-59}) and then a
     * range test on the value ({@code :62-66}).
     *
     * <p>{@code wag1} shares {@code urmfps_setup} but is a separate {@code PROJ_HEAD}
     * ({@code :71-81}) that assigns {@code n = 0.8660254037844386467637231707} itself and
     * calls no {@code pj_param}, so it is deliberately not routed here. A bare
     * {@code +proj=wag1} is legal and so is {@code +proj=wag1 +n=-1}; both are in the
     * oracle transcript as ACCEPT rows.
     */
    private static GieFailure urmfps(GieProjArgs a) {
        if (!a.contains("n")) {
            return invalid("urmfps", "missing parameter n (urmfps.cpp:56-59)");
        }
        double n = number(a, "n", 0.0);
        if (n <= 0.0 || n > 1.0) {
            return invalid("urmfps", "n should be in ]0,1] (urmfps.cpp:62-66)");
        }
        return null;
    }

    /**
     * {@code gn_sinu.cpp:172-202}: presence of {@code +n} and then of {@code +m}
     * ({@code :180-187}), and only after both the two value tests ({@code :191-198}).
     *
     * <p>The order is kept because it is observable: a bare {@code +proj=gn_sinu} is
     * refused for the missing {@code n}, not the missing {@code m}. The {@code m}
     * presence test cannot be folded into the {@code m < 0} one either, since
     * {@code +m=0} is legal.
     */
    private static GieFailure gnSinu(GieProjArgs a) {
        if (!a.contains("n")) {
            return invalid("gn_sinu", "missing parameter n (gn_sinu.cpp:180-183)");
        }
        if (!a.contains("m")) {
            return invalid("gn_sinu", "missing parameter m (gn_sinu.cpp:184-187)");
        }
        if (number(a, "n", 0.0) <= 0.0) {
            return invalid("gn_sinu", "n should be > 0 (gn_sinu.cpp:191-194)");
        }
        if (number(a, "m", 0.0) < 0.0) {
            return invalid("gn_sinu", "m should be >= 0 (gn_sinu.cpp:195-198)");
        }
        return null;
    }

    /**
     * {@code oea.cpp:57-88}: {@code +n} then {@code +m}, each rejected at {@code <= 0}
     * ({@code :64-72}).
     *
     * <p>Unlike {@code urmfps} and {@code gn_sinu} there is no separate presence test,
     * and none is needed: both are read with {@code pj_param}'s {@code d} sigil, which
     * answers 0 for an absent key, so "not given" and "given as 0" reach the same
     * comparison and produce the same message. A bare {@code +proj=oea} is therefore
     * refused for {@code n}, not for {@code m} — the order is observable and is kept.
     *
     * <p>{@code +theta} has no test at all ({@code :74} reads it and stores it), so no
     * value of it can make a definition invalid.
     */
    private static GieFailure oea(GieProjArgs a) {
        if (number(a, "n", 0.0) <= 0.0) {
            return invalid("oea", "n should be > 0 (oea.cpp:64-67)");
        }
        if (number(a, "m", 0.0) <= 0.0) {
            return invalid("oea", "m should be > 0 (oea.cpp:69-72)");
        }
        return null;
    }

    /**
     * {@code chamb.cpp:103-151}: the three control points must be pairwise distinct
     * ({@code :121-134}).
     *
     * <p>The test upstream is {@code Q->c[i].v.r == 0.0} on each of the three sides,
     * where {@code v.r} has already been floored to exactly zero by {@code vect}'s
     * {@code fabs(v.r) > TOL} guard ({@code chamb.cpp:47-50}). So the real predicate is
     * "shorter than {@link #CHAMB_TOL} radians", not "bit-identical coordinates", and
     * two control points a nanoradian apart are refused. That is why this reproduces
     * {@code vect}'s distance branch rather than comparing the parameters.
     *
     * <p><b>Collinear is not rejected.</b> Upstream's own comment at {@code :133} is
     * "co-linearity problem ignored for now", and {@code builtins.gie}'s only
     * {@code chamb} block is exactly that case. Adding a collinearity test here would
     * refuse a definition PROJ answers.
     *
     * <p>A bare {@code +proj=chamb} has all six ordinates defaulting to 0, so all three
     * points coincide and it is refused — by this test, not by a missing-parameter one.
     */
    private static GieFailure chamb(GieProjArgs a) {
        double lam0 = radians(a, "lon_0", 0.0);
        double[] phi = new double[3];
        double[] lam = new double[3];
        for (int i = 0; i < 3; i++) {
            phi[i] = radians(a, "lat_" + (i + 1), 0.0);
            lam[i] = adjlon(radians(a, "lon_" + (i + 1), 0.0) - lam0);
        }
        for (int i = 0; i < 3; i++) {
            int j = i == 2 ? 0 : i + 1;
            if (chambArc(phi[i], lam[i], phi[j], lam[j]) == 0.0) {
                return invalid("chamb",
                        "control points should be distinct (chamb.cpp:126-132)");
            }
        }
        return null;
    }

    /**
     * The distance half of {@code vect} ({@code chamb.cpp:34-51}), including the
     * {@code TOL} floor that turns a short side into an exact zero. The azimuth half is
     * not transcribed because nothing in the setup function can reject on it.
     */
    private static double chambArc(double phi1, double lam1, double phi2, double lam2) {
        double dphi = phi2 - phi1;
        double dlam = lam2 - lam1;
        double c1 = Math.cos(phi1);
        double s1 = Math.sin(phi1);
        double c2 = Math.cos(phi2);
        double s2 = Math.sin(phi2);
        double r;
        if (Math.abs(dphi) > 1.0 || Math.abs(dlam) > 1.0) {
            double v = s1 * s2 + c1 * c2 * Math.cos(dlam);
            r = Math.acos(v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v));
        } else {
            double dp = Math.sin(0.5 * dphi);
            double dl = Math.sin(0.5 * dlam);
            double v = Math.sqrt(dp * dp + c1 * c2 * dl * dl);
            r = 2.0 * Math.asin(v > 1.0 ? 1.0 : v);
        }
        return Math.abs(r) > CHAMB_TOL ? r : 0.0;
    }

    /** {@code adjlon.cpp:6-20}, needed only by {@link #chamb}. */
    private static double adjlon(double longitude) {
        if (Math.abs(longitude) < Math.PI + 1e-12) {
            return longitude;
        }
        double v = longitude + Math.PI;
        v -= 2.0 * Math.PI * Math.floor(v / (2.0 * Math.PI));
        return v - Math.PI;
    }

    /**
     * {@code imw_p.cpp:32-57}, {@code phi12}, which is the whole of what
     * {@code PJ_PROJECTION(imw_p)} can reject — the rest of its setup returns only
     * {@code PROJ_ERR_OTHER} on a failed allocation ({@code :177-185}).
     *
     * <p>Presence of {@code +lat_1} and then of {@code +lat_2}, then the half difference
     * and the half sum. The two value tests do not subsume the two presence tests: they
     * catch an absent <em>pair</em>, because both halves are then 0, but not exactly one
     * absent parallel. Both presence failures report
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} ({@code :38}, {@code :41}) rather
     * than {@code MISSING_ARG}, unlike {@code urmfps} and {@code gn_sinu}; that changes
     * the errno a corpus row would have to name, not the verdict here.
     */
    private static GieFailure imwP(GieProjArgs a) {
        if (!a.contains("lat_1")) {
            return invalid("imw_p", "lat_1 should be specified (imw_p.cpp:36-38)");
        }
        if (!a.contains("lat_2")) {
            return invalid("imw_p", "lat_2 should be specified (imw_p.cpp:39-41)");
        }
        double phi1 = radians(a, "lat_1", 0.0);
        double phi2 = radians(a, "lat_2", 0.0);
        if (Math.abs(0.5 * (phi2 - phi1)) < IMW_P_EPS
                || Math.abs(0.5 * (phi2 + phi1)) < IMW_P_EPS) {
            return invalid("imw_p", "|lat_1 - lat_2| and |lat_1 + lat_2| should be > 0 "
                    + "(imw_p.cpp:45-54)");
        }
        return null;
    }

    /** {@code s2.cpp:77-81, 417-427}. */
    private static GieFailure s2(GieProjArgs a) {
        return oneOf(a, "s2", "UVtoST",
                new String[] {"linear", "quadratic", "tangent", "none"}, "s2.cpp:417-427");
    }

    /**
     * {@code airocean.cpp:829-841}. Not reached by any corpus row: both {@code airocean}
     * blocks use a legal {@code +orient}, and their {@code expect failure} rows fail at
     * transform time, on a point outside the unfolded net, which is not this class's
     * business. It is here because the setup function is what is being transcribed.
     *
     * <p>Deliberately a separate method from {@link #isea} even though both police a key
     * called {@code +orient}: the value sets are disjoint, so a shared helper would accept
     * {@code +proj=airocean +orient=pole}, which upstream refuses.
     */
    private static GieFailure airocean(GieProjArgs a) {
        return oneOf(a, "airocean", "orient", new String[] {"vertical", "horizontal"},
                "airocean.cpp:829-841");
    }

    /**
     * {@code healpix.cpp:664-683}, {@code rhealpix}'s two square positions.
     *
     * <p>There are <b>two</b> refusal mechanisms here and they report the same errno from
     * different places, which is why neither can be dropped.
     *
     * <p>The first is {@code pj_param}'s {@code i} sigil itself
     * ({@code param.cpp:172-180}): it runs {@code atoi} and then walks the text, and on
     * any character outside {@code 0-9} it sets
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} on the context <em>and returns
     * zero</em>. Zero is inside {@code [0, 3]}, so the range guard below is happy and the
     * definition is refused anyway when the context errno is inspected after setup.
     * Measured on 9.8.1: {@code +proj=rhealpix +R=1 +north_square=-1} and
     * {@code +north_square=1.5} both exit 3 with "Invalid value for an argument", and only
     * the second of the two prints the {@code [0,3]} text. A leading minus is a
     * <em>parse</em> failure, not a range failure.
     *
     * <p>The second is the explicit range guard at {@code :670-683}, which is what
     * {@code +north_square=4} trips.
     *
     * <p>An empty value is accepted. Whether the key is written {@code +north_square} or
     * {@code +north_square=}, {@code opt} ends up pointing at a terminating NUL,
     * {@code atoi("")} is 0 and the digit loop has nothing to walk, so no errno is set.
     * <p><b>Two of the three 9.8.1 tools disagree about this, so it was settled with the
     * third.</b> {@code cct} accepts both forms and {@code proj} refuses both with a
     * message-less 1027; the {@code proj} CLI's extra guard is its own, since it accepts
     * the identical empty value on a {@code d}-sigil key ({@code +proj=merc +lat_ts=}) and
     * on an {@code i}-sigil key the operator never reads ({@code +proj=healpix
     * +north_square=}). The tie-breaker is <b>{@code gie} itself</b>, the tool this
     * harness mirrors: a two-block file asserting {@code +proj=rhealpix +ellps=WGS84
     * +north_square=} and the bare {@code +north_square} both map {@code 0 0} to
     * {@code 0 0} reports "2 tests succeeded, 0 failed" on 9.8.1. {@code +north_square=x}
     * and {@code +north_square=7} stay refused. So the library behaviour is modelled here,
     * and it is the behaviour the corpus is run under.
     */
    private static GieFailure rhealpix(GieProjArgs a) {
        GieFailure f = squarePosition(a, "north_square", "healpix.cpp:665, :670-676");
        if (f != null) {
            return f;
        }
        return squarePosition(a, "south_square", "healpix.cpp:666, :677-683");
    }

    /**
     * One of {@code rhealpix}'s two {@code i}-sigil square positions.
     *
     * @param a     the definition
     * @param key   {@code north_square} or {@code south_square}
     * @param where the upstream citation
     * @return the failure, or null
     */
    private static GieFailure squarePosition(GieProjArgs a, String key, String where) {
        GieToken t = a.find(key);
        if (t == null) {
            return null;
        }
        String v = t.value();
        if (v == null || v.isEmpty()) {
            // atoi("") is 0 and the digit loop never runs: upstream accepts this.
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return invalid("rhealpix", "+" + key + "=" + v + " is not a decimal "
                        + "integer; pj_param's 'i' sigil sets "
                        + "PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE on any character outside "
                        + "0-9 (param.cpp:172-180)");
            }
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            // A digit string too long for a long; atoi's overflow is undefined but the
            // value cannot be in [0,3] on any sane libc.
            return invalid("rhealpix",
                    "+" + key + "=" + v + " should be in [0,3] range (" + where + ")");
        }
        if (n > 3) {
            return invalid("rhealpix",
                    "+" + key + "=" + v + " should be in [0,3] range (" + where + ")");
        }
        return null;
    }

    /**
     * {@code isea.cpp:1008-1020} and {@code :1039-1050}. Neither guard is reached by
     * any corpus row — {@code builtins.gie:3152} uses the legal {@code +mode=hex} and
     * fails at transform time, which is not this class's business. They are here
     * because the setup function is what is being transcribed.
     *
     * <p><b>This branch stays now that {@code isea} is registered.</b> It looks redundant —
     * {@code IcosahedralSnyderEqualAreaProjection.setOrient} refuses the same values — but
     * the two answer different questions: this one says "PROJ would refuse", which is what
     * scores an {@code expect failure} row, and deleting it would turn every assertion that
     * depends on it from PASS to vacuous. Removing one such branch elsewhere cost seven
     * assertions and failed the gate as {@code REGRESSED}.
     */
    private static GieFailure isea(GieProjArgs a) {
        GieFailure f = oneOf(a, "isea", "orient", new String[] {"isea", "pole"},
                "isea.cpp:1008-1020");
        if (f != null) {
            return f;
        }
        return oneOf(a, "isea", "mode", new String[] {"plane", "di", "dd", "hex"},
                "isea.cpp:1039-1050");
    }

    /**
     * {@code ob_tran.cpp:189-206}, with {@code ob_tran_target_params} at {@code :138-168}.
     *
     * <p>Three distinct rejections, in PROJ's order. Note that {@code +o_proj} written
     * with no {@code '='} still satisfies the first: {@code pj_param("so_proj").s}
     * points at the terminating NUL, which is non-null. It is the <em>rewrite</em> in
     * {@code ob_tran_target_params} that then finds no {@code proj=} to hand to
     * {@code pj_create_argv_internal}, so it fails as "unknown" rather than "missing".
     *
     * <p>Only the first {@code o_proj} token is modelled, which is what
     * {@code pj_param} reads. A definition repeating the key — {@code +o_proj=moll
     * +o_proj=ob_tran} — is rejected upstream for reasons the rewrite loop makes
     * genuinely hard to predict, and is left alone; see
     * {@code ProjOperatorSetupTest.UNMODELLED}. Under-counting there is free, and no
     * corpus row does it.
     */
    private static GieFailure obTran(GieProjArgs a) {
        GieToken t = a.find("o_proj");
        if (t == null) {
            return invalid("ob_tran", "missing parameter o_proj (ob_tran.cpp:189-192)");
        }
        String target = t.value();
        // ob_tran_target_params rewrites `o_proj=xxx` to `proj=xxx` and bails out
        // entirely when that yields `proj=ob_tran` - the recursion guard.
        if ("ob_tran".equals(target)) {
            return invalid("ob_tran",
                    "o_proj=ob_tran would recurse (ob_tran.cpp:164-168, :195-200)");
        }
        // Anything pj_create_argv_internal cannot build is "unknown": no name at all,
        // a name PROJ does not have, or `pipeline`, which has no steps to run here.
        if (target == null || target.isEmpty() || "pipeline".equals(target)
                || !ProjTables.isProjOperator(target)) {
            return invalid("ob_tran", "+o_proj=" + (target == null ? "" : target)
                    + " does not name a projection to rotate (ob_tran.cpp:204-207)");
        }
        return null;
    }

    /** {@code topocentric.cpp:92-116}. Purely a presence algebra over six keys. */
    private static GieFailure topocentric(GieProjArgs a) {
        boolean hasX0 = a.contains("X_0");
        boolean hasY0 = a.contains("Y_0");
        boolean hasZ0 = a.contains("Z_0");
        boolean hasLon0 = a.contains("lon_0");
        boolean hasLat0 = a.contains("lat_0");
        boolean hasH0 = a.contains("h_0");
        if (!hasX0 && !hasLon0) {
            return invalid("topocentric", "missing X_0 or lon_0 (topocentric.cpp:98-101)");
        }
        if ((hasX0 || hasY0 || hasZ0) && (hasLon0 || hasLat0 || hasH0)) {
            return invalid("topocentric",
                    "(X_0,Y_0,Z_0) and (lon_0,lat_0,h_0) are mutually exclusive "
                            + "(topocentric.cpp:102-107)");
        }
        if (hasX0 && (!hasY0 || !hasZ0)) {
            return invalid("topocentric", "missing Y_0 and/or Z_0 (topocentric.cpp:108-111)");
        }
        if (hasLon0 && !hasLat0) {
            return invalid("topocentric", "missing lat_0 (topocentric.cpp:112-116)");
        }
        return null;
    }

    /**
     * {@code helmert.cpp:581-585} plus {@code read_convention} at {@code :517-551}.
     *
     * <p>{@code +convention} is required exactly when a rotation is present, and
     * "present" means <em>non-zero</em> after {@code :663-666} compares all six of
     * {@code rx ry rz drx dry drz} — verified: {@code +rx=0} is accepted and
     * {@code +drx=1} is not. {@code +towgs84} feeds the same three rotation slots
     * through {@code pj_datum_set} ({@code :590-604}), and then may only be combined
     * with {@code convention=position_vector} ({@code :542-548}).
     *
     * <p>{@code +s} has two refusals at {@code :615-621}, both guarded on the key being
     * <em>present</em>. A scale of {@code -1e6} ppm or less would make the factor
     * {@code 1 + s*1e-6} zero or negative, and under {@code +theta} the same key is a
     * direct multiplier rather than ppm, so {@code +s=0} collapses the plane to a point.
     * Both are checked before {@code read_convention} runs, though every path here ends
     * in a rejection either way so the order only affects which message comes out.
     */
    private static GieFailure helmert(GieProjArgs a) {
        if (a.contains("transpose")) {
            return invalid("helmert",
                    "the 'transpose' argument is no longer valid (helmert.cpp:581-585)");
        }
        if (a.contains("s")) {
            double s = ProjDefinitionValidator.number(a, "s", 0.0);
            if (s <= -1.0e6) {
                return invalid("helmert", "invalid value for s (helmert.cpp:615-618)");
            }
            if (a.contains("theta") && s == 0.0) {
                return invalid("helmert",
                        "invalid value for s under theta (helmert.cpp:619-621)");
            }
        }
        return convention(a, "helmert", hasRotation(a));
    }

    /**
     * {@code helmert.cpp:699-723}. {@code molobadekas} never assigns
     * {@code Q->no_rotation}, so the calloc'd zero leaves {@code read_convention}'s
     * {@code !Q->no_rotation} permanently true: {@code +convention} is
     * <em>unconditionally</em> required, even for a bare {@code +proj=molobadekas}.
     * Confirmed against the oracle in both directions.
     */
    private static GieFailure molobadekas(GieProjArgs a) {
        return convention(a, "molobadekas", true);
    }

    /** {@code helmert.cpp:517-551}, shared by {@code helmert} and {@code molobadekas}. */
    private static GieFailure convention(GieProjArgs a, String op, boolean required) {
        if (!required) {
            return null;
        }
        GieToken t = a.find("convention");
        if (t == null) {
            return invalid(op, "missing 'convention' argument (helmert.cpp:523-528)");
        }
        String v = t.value();
        boolean positionVector = "position_vector".equals(v);
        if (!positionVector && !"coordinate_frame".equals(v)) {
            return invalid(op, "invalid value for 'convention' (helmert.cpp:529-538)");
        }
        if (a.contains("towgs84") && !positionVector) {
            return invalid(op, "towgs84 should only be used with "
                    + "convention=position_vector (helmert.cpp:542-548)");
        }
        return null;
    }

    /** {@code helmert.cpp:663-666}, including the {@code towgs84} feed at {@code :590-604}. */
    private static boolean hasRotation(GieProjArgs a) {
        String[] keys = {"rx", "ry", "rz", "drx", "dry", "drz"};
        for (int i = 0; i < keys.length; i++) {
            if (number(a, keys[i], 0.0) != 0.0) {
                return true;
            }
        }
        String towgs84 = a.peek("towgs84");
        if (towgs84 != null) {
            String[] parts = towgs84.split(",", -1);
            for (int i = 3; i < parts.length && i < 6; i++) {
                Double d = projDouble(parts[i]);
                if (d != null && d.doubleValue() != 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code molodensky.cpp:321-349}: five required translation/ellipsoid deltas. */
    private static GieFailure molodensky(GieProjArgs a) {
        String[] required = {"dx", "dy", "dz", "da", "df"};
        for (int i = 0; i < required.length; i++) {
            if (!a.contains(required[i])) {
                return invalid("molodensky",
                        "missing " + required[i] + " (molodensky.cpp:321-349)");
            }
        }
        return null;
    }

    /** {@code defmodel.cpp:402-406}. */
    private static GieFailure defmodel(GieProjArgs a) {
        if (!a.contains("model")) {
            return invalid("defmodel", "+model= should be specified (defmodel.cpp:402-406)");
        }
        return null;
    }

    /**
     * {@code gridshift.cpp:913-916} and {@code :955-965}.
     *
     * <p>The {@code +interpolation} guard sits after the grid is opened, so the local
     * oracle cannot reach it — every probe stops at "could not find required grid(s)"
     * because PROJ's data path here has no {@code tests/} tree. The guard itself is
     * unambiguous in the source, and it is transcribed alongside the {@code +grids}
     * one, which the oracle does confirm.
     */
    private static GieFailure gridshift(GieProjArgs a) {
        if (!a.contains("grids")) {
            return invalid("gridshift", "+grids parameter missing (gridshift.cpp:913-916)");
        }
        return oneOf(a, "gridshift", "interpolation",
                new String[] {"bilinear", "biquadratic"}, "gridshift.cpp:955-965");
    }

    /**
     * {@code xyzgridshift.cpp:246-265}.
     *
     * <p><b>The order is load-bearing and is not the order the parameters are documented
     * in.</b> {@code +grid_ref} is validated before {@code +grids} is even looked for, so a
     * definition missing both reports 1027 (illegal value) and not 1026 (missing arg).
     * Probed in the installed 9.8.1 {@code proj} in both directions and recorded in
     * {@link ProjOperatorSetupTest}.
     *
     * <p>{@code +multiplier} is read with {@code pj_param}'s {@code d} sigil and has no
     * guard at all — not even against zero — so there is nothing here to model for it.
     */
    private static GieFailure xyzgridshift(GieProjArgs a) {
        GieFailure ref = oneOf(a, "xyzgridshift", "grid_ref",
                new String[] {"input_crs", "output_crs"}, "xyzgridshift.cpp:246-260");
        if (ref != null) {
            return ref;
        }
        if (!a.contains("grids")) {
            return invalid("xyzgridshift",
                    "+grids parameter missing (xyzgridshift.cpp:262-265)");
        }
        return null;
    }

    // -------------------------------------------------- ellipsoid-dependent

    /** {@code stere.cpp:318-323}: {@code ups} has no spherical formulation. */
    private static GieFailure ups(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell != null && ell[1] == 0.0) {
            return invalid("ups",
                    "only the ellipsoidal formulation is supported (stere.cpp:318-323)");
        }
        return null;
    }

    /** {@code tmerc.cpp:632-653}. */
    private static GieFailure utm(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell != null && ell[1] == 0.0) {
            return invalid("utm",
                    "eccentricity should not be zero (tmerc.cpp:632-636)");
        }
        if (a.contains("zone")) {
            Double z = projDouble(a.peek("zone"));
            if (z != null && !(z.doubleValue() > 0 && z.doubleValue() <= 60)) {
                return invalid("utm", "zone must be in [1,60] (tmerc.cpp:644-653)");
            }
        }
        return null;
    }

    /**
     * {@code sterea.cpp:104-106} through {@code pj_gauss_ini} at
     * {@code gauss.cpp:49-78}, which is {@code sterea}'s only caller in the whole of
     * 9.8.1. Two {@code nullptr} returns, both surfacing as {@code PROJ_ERR_OTHER}:
     * {@code C == 0}, and — the one the corpus reaches — {@code srat} underflowing to
     * zero, because {@code ratexp} grows without bound as {@code es} approaches 1.
     * With {@code +a=9999 +b=.9 +lat_0=73} the exponent is about 475 and the base
     * about 0.0223, so {@code pow} returns 0.
     */
    private static GieFailure sterea(GieProjArgs a) {
        double[] ell = shape(a);
        if (ell == null) {
            return null;
        }
        double es = ell[1];
        double e = Math.sqrt(es);
        double phi0 = radians(a, "lat_0", 0.0);
        double sphi = Math.sin(phi0);
        double cphi = Math.cos(phi0);
        cphi *= cphi;
        double c = Math.sqrt(1.0 + es * cphi * cphi / (1.0 - es));
        if (c == 0.0) {
            return invalid("sterea", "pj_gauss_ini: C == 0 (gauss.cpp:61-65)");
        }
        double ratexp = 0.5 * c * e;
        double esinp = e * sphi;
        double srat = Math.pow((1.0 - esinp) / (1.0 + esinp), ratexp);
        if (srat == 0.0) {
            return invalid("sterea",
                    "pj_gauss_ini: srat underflows to 0 with ratexp = " + ratexp
                            + " (gauss.cpp:66-71)");
        }
        return null;
    }

    // ------------------------------------------------------------- helpers

    /**
     * {@code pj_param} type {@code 's'} against a fixed value set.
     *
     * @return a failure when the key is present with a value outside {@code allowed};
     *     {@code null} when absent, since every one of these has a default
     */
    private static GieFailure oneOf(GieProjArgs a, String op, String key, String[] allowed,
            String where) {
        GieToken t = a.find(key);
        if (t == null) {
            return null;
        }
        String v = t.value();
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i].equals(v)) {
                return null;
            }
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            list.append(i == 0 ? "" : ", ").append(allowed[i]);
        }
        return invalid(op, "+" + key + "=" + v + " is not one of " + list + " (" + where + ")");
    }

    /**
     * The {@code a} and {@code es} that {@code ell_set.cpp} would resolve, <b>or
     * {@code null} when this class declines to say.</b>
     *
     * <p>It declines whenever a named {@code +datum}, an {@code +init=}, or any
     * spherification flag is in play, because reproducing those faithfully means
     * porting all 657 lines of {@code ell_set.cpp} plus the 46-entry
     * {@code ellps.cpp} table — a second, competing model of parameter resolution
     * living in test scope. Every caller treats {@code null} as "skip this check",
     * which costs assertions and cannot manufacture a pass.
     *
     * <p>What it does model, verbatim: {@code +R} short-circuiting everything
     * ({@code :90-98}), {@code ellps_size} ({@code :199-238}), {@code ellps_shape}'s
     * first-match-wins over {@code rf f es e b} ({@code :242-345}), and the implicit
     * {@code +ellps=GRS80} that {@code init.cpp:362} appends when the definition names
     * no size or shape at all.
     *
     * <h2>The one narrow thing it models about a named {@code +ellps}</h2>
     *
     * <p>A bare {@code +ellps=<name>} still declines. But {@code +ellps=<name>} together
     * with an explicit {@code rf}/{@code f}/{@code es}/{@code e}/{@code b} resolves,
     * because {@code pj_ellipsoid} runs {@code ellps_ellps}, then {@code ellps_size},
     * then {@code ellps_shape} in that order ({@code ell_set.cpp:103-116}) — so the name
     * supplies the size and the explicit key <em>replaces</em> the shape. That is
     * upstream's documented intent, not an accident: its own comment cites
     * {@code +ellps=xxx +a=1} as the point of it.
     *
     * <p>Looks inconsistent, and the asymmetry is deliberate. Resolving a bare
     * {@code +ellps} would need the whole {@code ellps.cpp} table and would newly feed
     * an {@code es} to the {@code lcc}, {@code omerc}, {@code sterea}, {@code ups},
     * {@code utm}, {@code nsper} and {@code geos} guards on hundreds of corpus rows that
     * skip them today. Resolving only the override case reaches <b>exactly one</b>
     * operation in the whole vendored corpus — {@code builtins.gie:1865},
     * {@code +proj=eqdc +lat_1=1 +ellps=GRS80 +b=.1}. Measured over all 53
     * {@code .gie} files rather than only the 42 active ones: 146
     * {@code operation}/{@code crs_src}/{@code crs_dst} lines name an {@code +ellps=},
     * and that is the only one of them also carrying an
     * {@code rf}/{@code f}/{@code es}/{@code e}/{@code b}. So every other verdict is
     * unchanged by construction. Dropping the {@code hasShapeKey} condition to "tidy up"
     * the asymmetry is therefore a much larger change than it looks, and needs its own
     * before/after measurement.
     *
     * @return {@code {a, es}}, or {@code null}
     */
    static double[] shape(GieProjArgs a) {
        if (a.contains("datum") || a.contains("init")) {
            return null;
        }
        for (int i = 0; i < SPHERIFICATION_KEYS.length; i++) {
            if (a.contains(SPHERIFICATION_KEYS[i])) {
                return null;
            }
        }
        if (a.contains("R")) {
            Double r = projDouble(a.peek("R"));
            return r == null || !(r.doubleValue() > 0)
                    ? null : new double[] {r.doubleValue(), 0.0};
        }

        boolean hasShapeKey = a.contains("rf") || a.contains("f") || a.contains("es")
                || a.contains("e") || a.contains("b");
        Double named = null;
        if (a.contains("ellps")) {
            if (!hasShapeKey) {
                return null;
            }
            named = namedEllipsoidMajorAxis(a.peek("ellps"));
            if (named == null) {
                return null;
            }
        }
        Double major = a.contains("a") ? projDouble(a.peek("a")) : named;
        if (major == null) {
            if (hasShapeKey || a.contains("no_defs")) {
                // ellps_size: "Major axis not given". Left to the existing
                // hasEllipsoidSize()/impliesGrs80() checks rather than duplicated here.
                return null;
            }
            double f = 1.0 / GRS80_RF;
            return new double[] {GRS80_A, 2 * f - f * f};
        }
        if (!(major.doubleValue() > 0)) {
            return null;
        }
        double size = major.doubleValue();

        double es;
        if (a.contains("rf")) {
            Double v = projDouble(a.peek("rf"));
            if (v == null || !(v.doubleValue() > 0)) {
                return null;
            }
            double f = 1.0 / v.doubleValue();
            es = 2 * f - f * f;
        } else if (a.contains("f")) {
            Double v = projDouble(a.peek("f"));
            if (v == null || v.doubleValue() < 0) {
                return null;
            }
            double f = v.doubleValue();
            es = 2 * f - f * f;
        } else if (a.contains("es")) {
            Double v = projDouble(a.peek("es"));
            if (v == null) {
                return null;
            }
            es = v.doubleValue();
        } else if (a.contains("e")) {
            Double v = projDouble(a.peek("e"));
            if (v == null) {
                return null;
            }
            es = v.doubleValue() * v.doubleValue();
        } else if (a.contains("b")) {
            Double v = projDouble(a.peek("b"));
            if (v == null || !(v.doubleValue() > 0)) {
                return null;
            }
            if (v.doubleValue() == size) {
                es = 0.0;
            } else {
                double f = (size - v.doubleValue()) / size;
                es = 2 * f - f * f;
            }
        } else {
            es = 0.0;
        }
        if (!(es >= 0.0) || es >= 1.0) {
            // Already an INVALID_DEFINITION by the global ellipsoid checks; declining
            // here keeps this method's contract to "a shape PROJ would have built".
            return null;
        }
        return new double[] {size, es};
    }

    /**
     * The semi-major axis of a built-in ellipsoid, or {@code null} if this class does
     * not carry it.
     *
     * <p>Deliberately not all 46 entries of {@code ellps.cpp}. It is consulted from one
     * place — {@link #shape}, and only when an explicit shape key is overriding the
     * named ellipsoid — and there the size is needed for the {@code +b} branch alone,
     * because {@code f = (a - b) / a}. A name absent from here makes {@link #shape}
     * decline, which costs a check and cannot manufacture a pass, so growing this on
     * demand is safe. <b>Both entries are probed against the installed 9.8.1 in
     * {@link ProjOperatorSetupTest}</b>; add a probe with any name you add, or the entry
     * is an untested guess about a table nobody read.
     */
    private static Double namedEllipsoidMajorAxis(String name) {
        if ("GRS80".equals(name)) {
            return Double.valueOf(GRS80_A);
        }
        if ("WGS84".equals(name)) {
            // ellps.cpp: {"WGS84", "a=6378137.0", "rf=298.257223563"}.
            return Double.valueOf(6378137.0);
        }
        return null;
    }

    private static GieFailure invalid(String op, String why) {
        return GieFailures.invalidDefinition("+proj=" + op + ": " + why
                + " - PROJ 9.8.1's own setup function rejects this definition");
    }
}
