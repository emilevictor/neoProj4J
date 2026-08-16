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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The oracle transcript for {@link ProjOperatorSetup}.
 *
 * <p>Every row below was run through the installed {@code proj 9.8.1} binary
 * (Rel. 9.8.1, April 10th 2026 — the same rev the corpus is vendored from) as
 * {@code echo "0 0" | proj <definition>}, and the {@code REJECT}/{@code ACCEPT}
 * column is what it printed. The test asserts that
 * {@link ProjDefinitionValidator#validate} agrees.
 *
 * <p>What an ACCEPT looks like depends on the operator. For a projection it is a
 * coordinate. For a transformation — {@code helmert}, {@code molobadekas} — it is
 * the line <i>"can't initialize operations that take non-angular input coordinates.
 * Try cct."</i>, because {@code proj} only drives projections. That message means
 * setup <b>succeeded</b>; the REJECT rows print {@code proj_create: Error ...} with
 * the reason instead, before the banner. Reproduce those rows with {@code cct}
 * if you want a number as well as a verdict, and beware that zsh does not split
 * unquoted parameter expansions, so {@code proj $def} hands the whole definition
 * over as one argument and answers {@code Unknown projection} to every row alike.
 *
 * <p><b>The ACCEPT rows are the point.</b> A validator that returned
 * {@code INVALID_DEFINITION} for everything would satisfy every REJECT row and be
 * worthless — worse than worthless, since {@code INVALID_DEFINITION} is the only
 * verdict that can manufacture a false pass. So each guard is paired with a
 * near-miss that must still be accepted: {@code +rf=1} rejects but
 * {@code +rf=1.0000001} accepts; {@code lagrng +W=0} rejects but a bare
 * {@code lagrng} accepts because {@code +W} defaults to 2; {@code helmert +drx=1}
 * rejects but {@code +rx=0} accepts.
 *
 * <p>{@link #theOracleTranscriptExercisesBothVerdicts()} enforces that balance so
 * the transcript cannot decay into a one-sided list.
 */
class ProjOperatorSetupTest {

    /** One oracle observation: a definition and whether {@code proj 9.8.1} refused it. */
    private static final class Row {
        final String def;
        final boolean rejected;
        final String note;

        Row(String def, boolean rejected, String note) {
            this.def = def;
            this.rejected = rejected;
            this.note = note;
        }
    }

    private static Row reject(String def, String note) {
        return new Row(def, true, note);
    }

    private static Row accept(String def, String note) {
        return new Row(def, false, note);
    }

    /**
     * Definitions whose verdict this validator is expected to reproduce.
     *
     * <p>Deliberately excluded: definitions PROJ rejects for a reason
     * {@link ProjOperatorSetup} does not model. Those are listed in {@link #UNMODELLED}
     * instead, so the gap is on the record rather than silently absent. The {@code lcc},
     * {@code omerc} and {@code eqdc} eccentricity guards all used to sit there, on the
     * grounds that they need {@code pj_mlfn}; all three are decidable from closed form
     * and are now here, probed in both directions.
     */
    private static final Row[] ORACLE = {
            // ---- ell_set.cpp / pj_calc_ellipsoid_params: f must be in [0,1)
            reject("proj=merc a=1 f=1", "f=1 is not < 1"),
            reject("proj=merc a=1 f=2", "f=2 is not < 1"),
            reject("proj=utm zone=32 ellps=GRS80 f=1", "the +f modifier still bounds f"),
            reject("proj=merc a=1 rf=1", "rf=1 gives f=1"),
            reject("proj=merc a=1 rf=0.5", "rf<1 gives f>1"),
            reject("proj=merc a=1 b=2", "b>a gives a negative flattening"),
            accept("proj=merc a=1 f=0.5", "f in range"),
            accept("proj=merc a=1 rf=1.0000001", "just inside: f<1"),
            accept("proj=merc a=1 rf=300", "ordinary"),
            accept("proj=merc a=1 b=1", "b==a is a sphere"),
            accept("proj=merc a=1 b=0.5", "b<a"),
            accept("proj=merc R=1 f=2", "+R short-circuits every shape parameter"),

            // ---- lcc
            reject("proj=lcc ellps=GRS80 lat_1=0 lat_2=90", "|lat_2| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=90 lat_2=0", "|lat_1| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=90 lat_2=90", "lat_1 guard wins"),
            reject("proj=lcc ellps=sphere lat_1=91", "lat_2 defaults to lat_1"),
            reject("proj=lcc ellps=sphere lat_2=91", "|lat_2| >= 90"),
            reject("proj=lcc ellps=GRS80 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=lcc ellps=GRS80", "both parallels default to 0"),
            accept("proj=lcc ellps=GRS80 lat_1=30 lat_2=45", "ordinary secant"),
            accept("proj=lcc ellps=GRS80 lat_1=0.5 lat_2=2", "corpus row builtins.gie:3833"),

            // ---- lcc: the secant-cone eccentricity guard, lcc.cpp:125-131. Note the
            //      guard is about the PAIR of parallels, not about es alone: the same
            //      degenerate ellipsoid is accepted at 30/45 and rejected at 0/1.
            reject("proj=lcc a=9999999 b=.9 lat_2=1",
                    "corpus block 166: the two msfn values are equal, so the cone constant is 0"),
            reject("proj=lcc a=9999999 b=.9 lat_1=0.5 lat_2=2", "same guard, non-zero lat_1"),
            accept("proj=lcc a=9999999 b=.9 lat_1=30 lat_2=45",
                    "es is just as degenerate, but the msfn ratio still differs from 1"),
            accept("proj=lcc a=9999999 b=9999 lat_2=1", "near miss: es not close enough to 1"),
            accept("proj=lcc a=9999999 b=.9 lat_1=1",
                    "lat_2 defaults to lat_1, so the cone is tangent and neither guard applies"),

            // ---- aea / leac / eqdc
            reject("proj=aea R=6400000 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=aea ellps=GRS80 lat_1=900", "|lat_1| > 90"),
            reject("proj=aea ellps=GRS80 lat_2=900", "|lat_2| > 90"),
            reject("proj=aea", "both parallels default to 0"),
            accept("proj=aea ellps=GRS80 lat_1=0 lat_2=2", "sum is 2 degrees"),
            reject("proj=eqdc R=6400000 lat_1=1 lat_2=-1", "|lat_1+lat_2| == 0"),
            reject("proj=eqdc R=6400000 lat_1=91", "|lat_1| > 90"),
            reject("proj=eqdc R=6400000 lat_2=91", "|lat_2| > 90"),
            reject("proj=eqdc R=1 lat_1=1e-9", "1e-9 degrees is 1.7e-11 rad, under EPS10"),
            accept("proj=eqdc ellps=GRS80 lat_1=0.5 lat_2=2", "ordinary"),

            // ---- eqdc: the secant-cone eccentricity guard, eqdc.cpp:127-132. Decided
            //      from the numerator alone; see ProjOperatorSetup.eqdc for why the
            //      pj_mlfn denominator cannot rescue a zero numerator. Note eqdc
            //      defaults lat_2 to 0 where lcc defaults it to lat_1, so `lat_1=1`
            //      alone is already a secant cone here.
            reject("proj=eqdc lat_1=1 ellps=GRS80 b=.1",
                    "corpus block 84, builtins.gie:1865 - the +b modifier drives es to "
                            + "1-2.2e-16 and both msfn values come out exactly 1.0"),
            reject("proj=eqdc lat_1=1 ellps=WGS84 b=.1",
                    "the other named ellipsoid this class carries a size for"),
            reject("proj=eqdc a=9999999 b=.9 lat_2=1", "corpus block 79, builtins.gie:1850"),
            reject("proj=eqdc a=9999999 b=.9 lat_1=1", "lat_2 defaults to 0, so still secant"),
            reject("proj=eqdc a=9999999 b=.9 lat_1=0.5 lat_2=2", "same guard, non-zero lat_1"),
            reject("proj=eqdc lat_1=1 ellps=GRS80 rf=1.0000001", "es = 1-1e-14 via +rf"),
            reject("proj=eqdc lat_1=1 ellps=GRS80 es=0.999999999999999", "es given directly"),
            accept("proj=eqdc a=9999999 b=.9 lat_1=30 lat_2=45",
                    "the guard is about the PAIR, not es alone: the same degenerate "
                            + "ellipsoid still separates these two msfn values by 2.8e-15"),
            accept("proj=eqdc a=9999999 b=.9 lat_1=1 lat_2=1",
                    "tangent cone - upstream never enters the branch"),
            accept("proj=eqdc a=9999999 b=9999 lat_2=1", "near miss: es not close enough to 1"),
            accept("proj=eqdc lat_1=1 ellps=GRS80 f=0.9", "es = 0.99 is not close enough"),
            accept("proj=eqdc lat_1=1 ellps=GRS80 b=.1 a=1",
                    "+a overrides the named size BEFORE +b sets the shape, so es = 0.99"),
            accept("proj=eqdc lat_1=1 ellps=GRS80", "a bare named ellipsoid is ordinary"),
            accept("proj=leac ellps=GRS80 lat_1=0 lat_2=2", "leac takes phi1 from the pole"),
            accept("proj=leac R=6400000 lat_1=0 lat_2=2", "and ignores lat_2"),

            // ---- omerc
            reject("proj=omerc R=1 alpha=0 lat_0=90", "|lat_0| >= 90 in the alpha branch"),
            reject("proj=omerc lat_1=91", "|lat_1| > 90 - TOL"),
            reject("proj=omerc lat_2=91", "|lat_2| > 90 - TOL"),
            reject("proj=omerc", "lat_1 == lat_2 == 0"),
            reject("proj=omerc ellps=GRS80 lat_1=0 lat_2=2", "lat_1 must differ from 0"),
            reject("proj=omerc ellps=GRS80 lat_1=1 lat_2=1", "lat_1 must differ from lat_2"),
            reject("proj=omerc ellps=GRS80 lat_1=30 lat_2=40 lat_0=90", "|lat_0| >= 90"),
            accept("proj=omerc R=1 alpha=0 lat_0=45", "alpha branch, lat_0 in range"),
            accept("proj=omerc R=1 lat_0=1 lat_1=2 no_rot", "corpus row builtins.gie:5269"),
            accept("proj=omerc a=6400000 lat_0=45 lat_1=45 lat_2=45.00001 lon_1=0 lon_2=1e-5",
                    "|lat_1-lat_2| is 1.7e-7 rad, just OVER TOL=1e-7"),
            accept("proj=omerc ellps=GRS80 lat_1=0.5 lat_2=2", "corpus row builtins.gie:5223"),

            // ---- omerc: the two-point eccentricity guards, omerc.cpp:255-261 and
            //      :270-275. The lat_2=30 row is the only probe that reaches the SECOND
            //      one - p is non-zero there and F - 1/F is what collapses.
            reject("proj=omerc lat_1=0.8 a=6400000 b=.4",
                    "corpus block 241: H == L, so p == 0 and the centre line is undefined"),
            reject("proj=omerc lat_1=0.8 lat_2=30 a=6400000 b=.4",
                    "p != 0 but F - 1/F == 0, the second guard"),
            reject("proj=omerc lat_1=0.8 a=6400000 b=.4 lat_0=20",
                    "same, through the |lat_0| > EPS branch that computes B and E"),
            accept("proj=omerc lat_1=0.8 a=6400000 b=4000", "near miss: H differs from L"),
            accept("proj=omerc lat_1=0.8 lat_2=30 a=6400000 b=4000",
                    "near miss on both guards"),

            // ---- omerc: the +gamma limit, which is where D matters
            accept("proj=omerc lat_0=10 R=6400000 gamma=80",
                    "exactly at the spherical limit; aasin's ONE_TOL slack must absorb it"),
            reject("proj=omerc lat_0=10 R=6400000 gamma=80.0000001", "1e-7 degrees over"),
            reject("proj=omerc lat_0=10 R=6400000 rf=300 gamma=80.01",
                    "+R makes it spherical, so the limit is still 80 - the corpus's "
                            + "'# OK' comment on builtins.gie:5335 is wrong and unasserted"),
            reject("proj=omerc lat_0=10 a=6400000 rf=300 gamma=80.1",
                    "ellipsoidal limit is 80.031684"),
            accept("proj=omerc lat_0=10 a=6400000 rf=300 gamma=80.01",
                    "under the ellipsoidal limit, unlike the +R form above"),

            // ---- lagrng / krovak / labrd
            reject("proj=lagrng R=1 W=-1", "W must be > 0"),
            reject("proj=lagrng R=1 W=0", "W must be > 0"),
            reject("proj=lagrng R=1 lat_1=90.00001", "|sin(lat_1)| within TOL of 1"),
            accept("proj=lagrng R=1 W=0.5", "W in range"),
            accept("proj=lagrng R=1", "+W defaults to 2, so it is not required"),
            accept("proj=lagrng R=1 lat_1=89", "well inside"),
            reject("proj=krovak lat_0=-90", "tan(lat_0/2 + pi/4) == 0"),
            reject("proj=mod_krovak lat_0=-90", "shares krovak_setup"),
            accept("proj=krovak", "+lat_0 defaults to 49d30'N, not to 0"),
            accept("proj=krovak lat_0=49.5", "the default, written out"),
            reject("proj=labrd ellps=GRS80 lat_0=0", "lat_0 must differ from 0"),
            reject("proj=labrd ellps=GRS80", "+lat_0 defaults to 0"),
            accept("proj=labrd ellps=GRS80 lat_0=-18.9", "Madagascar"),

            // ---- nsper / tpers
            reject("proj=nsper R=1 h=0", "pn1 = h/a must be > 0"),
            reject("proj=nsper R=1 h=-5", "negative height"),
            reject("proj=nsper R=1", "+h defaults to 0"),
            reject("proj=nsper R=1 h=1e11", "pn1 > 1e10"),
            accept("proj=nsper R=1 h=1e10", "exactly at the upper bound"),
            accept("proj=nsper R=1 h=10", "ordinary"),
            reject("proj=tpers R=1 h=0", "tpers shares nsper_setup"),
            accept("proj=tpers R=1 h=10", "ordinary"),

            // ---- geos: the same h/a model, plus a +sweep value set
            reject("proj=geos R=1 h=0", "corpus row builtins.gie:2183"),
            reject("proj=geos R=1", "+h defaults to 0, with no presence test"),
            reject("proj=geos R=1 h=-5", "negative height"),
            reject("proj=geos R=1 h=1e11", "corpus row builtins.gie:2188 - h/a > 1e10"),
            accept("proj=geos R=1 h=1e10", "exactly at the upper bound"),
            accept("proj=geos R=1 h=10", "ordinary"),
            accept("proj=geos ellps=GRS80 h=35785831",
                    "corpus row builtins.gie:2137 - the nominal geostationary height"),
            reject("proj=geos R=1 h=10 sweep=z", "sweep must be x or y"),
            reject("proj=geos R=1 h=10 sweep=xy", "one character only"),
            reject("proj=geos R=1 h=10 sweep=", "an empty value is not 'x' or 'y' either"),
            accept("proj=geos R=1 h=10 sweep=x", "legal, though proj4j ignores it"),
            accept("proj=geos R=1 h=10 sweep=y", "the upstream default, written out"),

            // ---- urmfps / wag1: the same kernel, only one of which reads +n
            reject("proj=urmfps a=6400000", "+n is required"),
            reject("proj=urmfps a=6400000 n=0", "n must be in ]0,1]"),
            reject("proj=urmfps a=6400000 n=-0.5", "nor negative"),
            reject("proj=urmfps a=6400000 n=1.5", "nor above 1"),
            accept("proj=urmfps a=6400000 n=0.5", "corpus row builtins.gie:7712"),
            accept("proj=urmfps a=6400000 n=1", "exactly at the upper bound"),
            accept("proj=wag1 a=6400000", "corpus row builtins.gie:7977 - n is hard-coded"),
            accept("proj=wag1 a=6400000 n=0", "wag1 never reads the key, so no range applies"),
            accept("proj=wag1 a=6400000 n=-1", "same"),

            // ---- gn_sinu: two presence tests before either value test
            reject("proj=gn_sinu a=6400000", "+n named first"),
            reject("proj=gn_sinu a=6400000 m=1", "still the missing n"),
            reject("proj=gn_sinu a=6400000 n=1", "then the missing m"),
            reject("proj=gn_sinu a=6400000 n=0 m=1", "n must be > 0"),
            reject("proj=gn_sinu a=6400000 n=-1 m=1", "same"),
            reject("proj=gn_sinu a=6400000 n=1 m=-1", "m must be >= 0"),
            accept("proj=gn_sinu a=6400000 n=1 m=0", "m=0 is legal, which is why the "
                    + "presence test cannot be a value test"),
            accept("proj=gn_sinu a=6400000 m=1 n=2", "corpus row builtins.gie:2220"),

            // ---- oea: the same two keys as gn_sinu, with NO presence test and a
            // ---- strict bound on both, so a bare +proj=oea is refused for n
            reject("proj=oea a=6400000", "+n defaults to 0 and 0 is not > 0"),
            reject("proj=oea a=6400000 m=2", "n is named first, even though m is given"),
            reject("proj=oea a=6400000 n=1", "then m"),
            reject("proj=oea a=6400000 n=-1 m=2", "n must be > 0"),
            reject("proj=oea a=6400000 n=1 m=0", "m must be > 0 - unlike gn_sinu, "
                    + "where m=0 is legal"),
            accept("proj=oea a=6400000 n=1 m=2 theta=3", "corpus row builtins.gie:5192"),
            accept("proj=oea a=6400000 n=1 m=2", "+theta defaults to 0 and is not bounded"),
            accept("proj=oea a=6400000 n=1 m=2 theta=-1000", "nor bounded from below"),

            // ---- chamb: the three control points must be pairwise distinct, by
            // ---- great-circle distance rather than by parameter equality
            reject("proj=chamb R=6400000", "all six ordinates default to 0"),
            reject("proj=chamb R=6400000 lat_1=1 lat_2=1", "points 1 and 2 coincide"),
            reject("proj=chamb R=6400000 lat_1=1 lon_1=0 lat_2=1 lon_2=360",
                    "distinct parameters, one point: +lon_2 wraps onto +lon_1"),
            reject("proj=chamb R=6400000 lat_1=1 lat_2=1.00000005",
                    "8.7e-10 rad apart is under chamb.cpp's 1e-9 TOL"),
            accept("proj=chamb R=6400000 lat_1=1 lat_2=1.0000001",
                    "1.7e-9 rad apart is over it - the pair brackets the tolerance and "
                            + "shows the test is a distance, not an equality"),
            accept("proj=chamb R=6400000 lat_1=0.5 lat_2=2", "corpus row builtins.gie:1069"),
            accept("proj=chamb R=6400000 lat_1=10 lat_2=20 lat_3=30",
                    "collinear is accepted - chamb.cpp:133 says so in as many words"),

            // ---- rouss: PJ_PROJECTION(rouss) has no guard of any kind, so every
            // ---- definition of it is accepted, including ones that answer nonsense
            accept("proj=rouss ellps=GRS80", "ordinary"),
            accept("proj=rouss R=6400000", "a sphere: es=0 collapses the meridian series"),
            accept("proj=rouss ellps=GRS80 lat_0=90",
                    "accepted at setup and answers -2.5e+68 at (1,1); refusing here would "
                            + "diverge from PROJ, so the nonsense is reproduced instead"),

            // ---- imw_p: two presence tests the two value tests do not subsume
            reject("proj=imw_p ellps=GRS80", "lat_1 named first"),
            reject("proj=imw_p ellps=GRS80 lat_2=30", "still the missing lat_1"),
            reject("proj=imw_p ellps=GRS80 lat_1=30", "then the missing lat_2 - the case "
                    + "the |lat_1 - lat_2| test cannot see"),
            reject("proj=imw_p ellps=GRS80 lat_1=30 lat_2=30", "|lat_1 - lat_2| == 0"),
            reject("proj=imw_p ellps=GRS80 lat_1=30 lat_2=-30", "|lat_1 + lat_2| == 0"),
            reject("proj=imw_p ellps=GRS80 lat_1=0 lat_2=0", "both, written out"),
            reject("proj=imw_p ellps=GRS80 lat_1=0.5 lat_2=0.5000000001",
                    "1e-10 degrees apart is under EPS on the half difference"),
            accept("proj=imw_p ellps=GRS80 lat_1=0 lat_2=10",
                    "corpus row builtins.gie:3108 - an explicit 0 selects PHI_1_IS_ZERO"),
            accept("proj=imw_p ellps=GRS80 lat_1=10 lat_2=0", "and PHI_2_IS_ZERO"),
            accept("proj=imw_p ellps=GRS80 lat_1=0.5 lat_2=2", "corpus row builtins.gie:3085"),

            // ---- urm5 / s2 / isea
            reject("proj=urm5 a=6400000", "+n is required"),
            reject("proj=urm5 a=6400000 n=0", "n must be in ]0,1]"),
            reject("proj=urm5 a=6400000 n=1.5", "n must be in ]0,1]"),
            reject("proj=urm5 a=6400000 n=1 alpha=90", "n*sin(alpha) == 1"),
            accept("proj=urm5 a=6400000 n=0.5", "ordinary"),
            reject("proj=s2 ellps=WGS84 lat_0=0 lon_0=0 UVtoST=invalid", "not in the map"),
            reject("proj=s2 ellps=WGS84 UVtoST=Linear", "the std::map lookup is case-sensitive"),
            reject("proj=s2 ellps=WGS84 UVtoST=",
                    "present with an empty value: pj_param's 's' hands back a pointer to the "
                            + "NUL, which is not a key of the map either"),
            reject("proj=s2 ellps=WGS84 UVtoST",
                    "and the bare form is the same thing - PRESENCE is what makes the lookup "
                            + "happen, so this is a reject where +north_square is an accept"),
            accept("proj=s2 ellps=WGS84 lat_0=0 lon_0=0 UVtoST=linear", "in the map"),
            accept("proj=s2 ellps=WGS84 lat_0=0 lon_0=0", "defaults to quadratic"),
            accept("proj=s2 ellps=WGS84 lat_0=90 UVtoST=tangent", "corpus row builtins.gie:6504"),
            accept("proj=s2 ellps=WGS84 lat_0=0 lon_0=180 UVtoST=none",
                    "corpus row builtins.gie:6523 - 'none' is a value, not an absence"),

            // ---- rhealpix: the two i-sigil square positions, healpix.cpp:664-683. Two
            //      refusal mechanisms with one errno, so both directions of both are here.
            reject("proj=rhealpix ellps=WGS84 north_square=4", "the [0,3] guard, :670-676"),
            reject("proj=rhealpix ellps=WGS84 south_square=4", "the same guard at :677-683"),
            reject("proj=rhealpix ellps=WGS84 north_square=-1",
                    "the OTHER mechanism: pj_param's digit-only grammar (param.cpp:172-180). "
                            + "proj prints no [0,3] text for this one, because atoi returned 0 "
                            + "and the range guard was satisfied"),
            reject("proj=rhealpix ellps=WGS84 north_square=x", "same grammar, no digits at all"),
            reject("proj=rhealpix ellps=WGS84 north_square=1.5", "a '.' is outside 0-9"),
            accept("proj=rhealpix ellps=WGS84 south_square=2 north_square=3",
                    "corpus row builtins.gie:2758"),
            accept("proj=rhealpix ellps=WGS84 north_square=0",
                    "0 is in range and is also the default, so this must not be read as absent"),
            accept("proj=rhealpix ellps=WGS84 north_square=3", "the top of the range"),
            accept("proj=rhealpix ellps=WGS84", "both default to 0"),
            accept("proj=healpix ellps=WGS84 rot_xy=42",
                    "corpus row builtins.gie:2682 - +rot_xy has no guard of any kind"),
            accept("proj=healpix ellps=WGS84 north_square=9",
                    "healpix never READS the squares, so a value rhealpix would refuse is inert "
                            + "here - the near-miss that proves the branch is keyed on +proj="),
            reject("proj=isea mode=nope", "no corpus row reaches this guard"),
            reject("proj=isea orient=nope", "nor this one"),
            accept("proj=isea mode=hex", "corpus row builtins.gie:3152 - legal at setup"),
            accept("proj=isea orient=pole", "legal"),

            // ---- airocean / isea: the two operators share the keyword +orient and share
            //      none of its values. The crossed rows are what stop a shared allow-list
            //      from creeping in; each was run through proj 9.8.1 in both directions and
            //      each crossed value produces error 1027 naming the OTHER operator's pair.
            reject("proj=airocean orient=nope", "airocean.cpp:829-841"),
            reject("proj=airocean orient", "bare +orient is the empty string, not a no-op"),
            reject("proj=airocean orient=isea", "isea's value, refused by airocean"),
            reject("proj=airocean orient=pole", "likewise"),
            accept("proj=airocean", "+orient defaults to vertical"),
            accept("proj=airocean orient=vertical", "corpus block builtins.gie:1195"),
            accept("proj=airocean orient=horizontal", "corpus block builtins.gie:1297"),
            reject("proj=isea orient=vertical", "airocean's value, refused by isea"),
            reject("proj=isea orient=horizontal", "likewise"),
            reject("proj=isea orient", "bare +orient again"),
            accept("proj=isea orient=isea", "the default, spelled out"),
            accept("proj=isea aperture=0", "+aperture has no setup-time range check"),
            accept("proj=isea resolution=31",
                    "legal at setup; corpus row builtins.gie:3152 fails at transform time"),
            accept("proj=isea mode=dd", "legal"),
            accept("proj=isea mode=di", "legal"),
            accept("proj=isea azi=10", "+azi moves the orientation, it does not fail setup"),
            accept("proj=isea lat_0=10", "+lat_0 likewise; it just costs the inverse"),

            // ---- ob_tran
            reject("proj=ob_tran R=6400000", "+o_proj missing"),
            reject("proj=ob_tran R=6400000 o_proj=ob_tran", "recursion guard"),
            reject("proj=ob_tran R=6400000 o_proj",
                    "bare +o_proj passes the null check, then names nothing to build"),
            reject("proj=ob_tran R=6400000 o_proj=", "same, written with an empty value"),
            reject("proj=ob_tran R=6400000 o_proj=nosuchthing", "unknown target operator"),
            reject("proj=ob_tran R=6400000 o_proj=pipeline", "a pipeline with no steps"),
            accept("proj=ob_tran R=6400000 o_proj=moll o_lat_p=45 o_lon_p=0", "ordinary"),

            // ---- topocentric
            reject("proj=topocentric ellps=WGS84", "neither X_0 nor lon_0"),
            reject("proj=topocentric ellps=WGS84 X_0=0 Y_0=0", "Z_0 missing"),
            reject("proj=topocentric ellps=WGS84 lon_0=0", "lat_0 missing"),
            reject("proj=topocentric ellps=WGS84 X_0=0 lon_0=0", "mutually exclusive"),
            accept("proj=topocentric ellps=WGS84 X_0=0 Y_0=0 Z_0=0", "geocentric origin"),
            accept("proj=topocentric ellps=WGS84 lon_0=0 lat_0=0", "geographic origin"),

            // ---- helmert / molobadekas / molodensky
            reject("proj=helmert rx=1", "rotation without a convention"),
            reject("proj=helmert drx=1", "a rate of rotation counts too"),
            reject("proj=helmert rx=1 convention=foo", "not a known convention"),
            reject("proj=helmert rx=1 convention=1", "nor this"),
            reject("proj=helmert towgs84=1,2,3,4,5,6,7 convention=coordinate_frame",
                    "towgs84 is position_vector by history"),
            reject("proj=helmert transpose", "the obsolete flag is a hard error"),
            accept("proj=helmert towgs84=1,2,3,4,5,6,7 convention=position_vector", "legal"),
            accept("proj=helmert rx=1 convention=position_vector", "legal"),
            accept("proj=helmert rx=0", "a zero rotation is no rotation"),
            accept("proj=helmert x=1", "translation only"),
            accept("proj=helmert", "the identity is legal"),
            reject("proj=helmert transpose=F",
                    "presence, not truth: pj_param reads 'ttranspose', the 't' sigil"),
            reject("proj=helmert s=-1000000", "s <= -1e6 makes the scale factor zero or negative"),
            accept("proj=helmert s=-999999", "one part per million short of the refusal"),
            reject("proj=helmert theta=1 s=0",
                    "under +theta the scale is a direct multiplier, so zero collapses the plane"),
            accept("proj=helmert theta=1", "no +s at all defaults the multiplier to 1"),
            accept("proj=helmert theta=1 s=1", "an explicit multiplier of 1"),
            reject("proj=molobadekas", "convention is unconditionally required here"),
            accept("proj=molobadekas convention=position_vector", "legal"),
            accept("proj=molobadekas transpose convention=position_vector",
                    "molobadekas has no transpose check - only helmert does"),
            accept("proj=molobadekas s=-2000000 convention=position_vector",
                    "nor does it validate +s, so a negative scale factor is accepted here"),
            reject("proj=molodensky a=6378160 rf=298.25", "dx missing"),
            reject("proj=molodensky a=6378160 rf=298.25 dx=0", "dy missing"),
            accept("proj=molodensky a=6378160 rf=298.25 dx=0 dy=0 dz=0 da=0 df=0", "complete"),
            accept("proj=molodensky a=6378160 rf=298.25 dx=0 dy=0 dz=0 da=0 df=0 abridged",
                    "+abridged never affects acceptance; it selects a formula"),

            // ---- vertoffset. It has no branch in ProjOperatorSetup and needs none:
            // vertoffset.cpp:93-100 reads five optional numbers and computes two radii,
            // with no guard and no path that returns a destructor. These rows pin that
            // "no branch" is a measured conclusion rather than an operator nobody got to
            // - if someone adds a guard, the accepts here have to be revisited to say why.
            accept("proj=vertoffset ellps=GRS80", "every parameter defaults; this is the identity"),
            accept("proj=vertoffset ellps=GRS80 lat_0=46.9166666666666666 "
                    + "lon_0=8.183333333333334 dh=-0.245 slope_lat=-0.210 slope_lon=-0.032",
                    "the corpus definition, more_builtins.gie:781"),

            // ---- defmodel / gridshift
            reject("proj=defmodel", "+model= required"),
            reject("proj=gridshift", "+grids required"),

            // ---- xyzgridshift. +grid_ref is checked BEFORE +grids, so the first row
            //      below is 1027 and not 1026 -- probed both ways round.
            reject("proj=xyzgridshift", "+grids required"),
            reject("proj=xyzgridshift grid_ref=bogus", "grid_ref wins over the missing +grids"),
            reject("proj=xyzgridshift grids=null grid_ref=bogus", "unknown +grid_ref"),
            accept("proj=xyzgridshift grids=null", "+grids=null is a real, buildable grid set"),
            accept("proj=xyzgridshift grids=null grid_ref=input_crs", "the default, named"),
            accept("proj=xyzgridshift grids=null grid_ref=output_crs", "the iterative branch"),
            accept("proj=xyzgridshift grids=null multiplier=0", "+multiplier has no guard at all"),

            // ---- ups / utm / sterea
            reject("proj=ups a=6400000", "no spherical formulation"),
            accept("proj=ups ellps=GRS80", "ellipsoidal"),
            reject("proj=utm a=6400000 zone=30", "eccentricity must not be zero"),
            reject("proj=utm R=6400000 zone=30", "same, via +R"),
            accept("proj=utm ellps=GRS80 zone=30", "ordinary"),
            reject("proj=sterea a=9999 b=.9 lat_0=73", "pj_gauss_ini srat underflows"),
            accept("proj=sterea a=9999 b=.9 lat_0=0", "sin(lat_0)=0 makes srat 1"),
            accept("proj=sterea ellps=GRS80 lat_0=52", "ordinary"),

            // ---- noop / geoc / geogoffset (2.2.0). None of the three has a setup
            //      guard of its own: noop.cpp reads no parameters at all, and
            //      geogoffset (affine.cpp:228-250) reads +dlon/+dlat/+dh with pj_param
            //      defaults of 0, so every value is legal and the bare form is the
            //      identity. These ACCEPT rows record that the absence was checked
            //      rather than assumed - a guard added upstream would show up here as
            //      an oracle disagreement rather than as a silent parity gap.
            accept("proj=noop", "noop.cpp: no parameters, nothing to refuse"),
            accept("proj=geogoffset", "all three offsets default to 0; the identity"),
            accept("proj=geogoffset dlon=3600 dlat=-3600 dh=3", "corpus more_builtins.gie:705"),
            accept("proj=geogoffset dlon=-1e9 dh=-1e9", "no range check on any offset"),
            accept("proj=geoc ellps=GRS80", "corpus more_builtins.gie:486"),
            accept("proj=geoc", "append_default_ellipsoid_to_paralist supplies GRS80"),
            accept("proj=geoc R=6378137", "a sphere is legal; es==0 makes the conversion "
                    + "the identity at run time, which is not a setup failure"),

            // geoc's ONLY construction requirement is PJ_CONVERSION(geoc, 1)'s
            // need_ellps, and the only way to reach it is to inhibit the GRS80 default.
            // Probed: `proj +proj=geoc +no_defs` fails 1026 "Must specify ellipsoid or
            // sphere" (init.cpp:570-572) while `+proj=noop +no_defs` and
            // `+proj=geogoffset +dlon=3600 +no_defs` both SUCCEED, because those two are
            // declared with need_ellps=0. The validator reaches the right verdict here
            // for a broader reason than PROJ's - see validateEllipsoid - so this row is
            // pinned on the verdict only; the divergence on the other two is recorded in
            // NEED_ELLPS_NOT_MODELLED below.
            reject("proj=geoc no_defs", "need_ellps=1 with the GRS80 default inhibited"),
    };

    /**
     * Definitions {@code proj 9.8.1} <em>accepts</em> and this validator rejects.
     *
     * <p>{@link ProjDefinitionValidator#validateEllipsoid} refuses any {@code +no_defs}
     * carrying no ellipsoid size, on the strength of {@code proj +proj=merc +no_defs}
     * failing. That is right for the ~170 operators declared {@code NEED_ELLPS = 1}, and
     * wrong for the handful declared {@code 0}: {@code init.cpp:569-580} hands those a
     * free WGS84 instead of failing. Probed against the installed 9.8.1, both of the
     * definitions below print a coordinate.
     *
     * <p>It is left unfixed deliberately, and the reason is measurable: <b>{@code no_defs}
     * appears in none of the 42 active corpus files</b>, so the rule has a corpus
     * population of zero and modelling {@code need_ellps} per operator would be 186 table
     * rows bought with no assertion. This array is the honest record of the gap, and it
     * fails the moment the gap closes by accident.
     */
    private static final String[] NEED_ELLPS_NOT_MODELLED = {
            "proj=noop no_defs",
            "proj=geogoffset dlon=3600 no_defs",
    };

    /**
     * Definitions {@code proj 9.8.1} rejects that {@link ProjOperatorSetup}
     * deliberately does <em>not</em>. Asserting they come back valid pins the boundary:
     * if someone ports those guards, this list must shrink in the same commit, and if
     * the validator starts rejecting them by accident, this catches it.
     *
     * <p>The {@code lcc}, {@code omerc} and {@code eqdc} entries have left this list —
     * all three guards are decidable from closed form ({@code pj_msfn},
     * {@code pj_tsfn}), and their probes moved into {@link #ORACLE} in both directions.
     * {@code eqdc} was the last to go, and it left for a different reason from the other
     * two: its expression really does divide by a difference of {@code pj_mlfn} values,
     * but no value that denominator can take turns a zero numerator into an accepted
     * definition. {@link ProjOperatorSetup#eqdc} enumerates the cases.
     */
    private static final String[] UNMODELLED = {
            // A repeated +o_proj: ob_tran_target_params rewrites every occurrence and
            // the resulting failure is not the one the rewrite loop reads as though it
            // should be. proj 9.8.1 rejects this with omerc's lat_1/lat_2 message,
            // which is not predictable from ob_tran.cpp alone.
            "proj=ob_tran R=6400000 o_proj=moll o_proj=ob_tran",
    };

    @Test
    @DisplayName("the validator reproduces proj 9.8.1's verdict on every probed definition")
    void validatorAgreesWithTheOracle() {
        List<String> wrong = new ArrayList<String>();
        for (Row r : ORACLE) {
            GieFailure f = ProjDefinitionValidator.validate(GieProjArgs.parse(r.def));
            boolean saysInvalid = f != null;
            if (saysInvalid != r.rejected) {
                wrong.add(String.format("%-70s oracle=%s validator=%s   (%s)%s",
                        r.def, r.rejected ? "REJECT" : "ACCEPT",
                        saysInvalid ? "REJECT" : "ACCEPT", r.note,
                        f == null ? "" : "\n        " + f.message()));
            }
        }
        assertTrue(wrong.isEmpty(),
                "the validator disagrees with proj 9.8.1 on " + wrong.size() + " of "
                        + ORACLE.length + " probed definitions:\n  "
                        + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("the transcript asserts both verdicts, so it cannot be satisfied by a stub")
    void theOracleTranscriptExercisesBothVerdicts() {
        int rejects = 0;
        int accepts = 0;
        for (Row r : ORACLE) {
            if (r.rejected) {
                rejects++;
            } else {
                accepts++;
            }
        }
        assertTrue(accepts >= 30, "only " + accepts + " ACCEPT rows; a validator that "
                + "refused everything would pass a REJECT-only transcript");
        assertTrue(rejects >= 30, "only " + rejects + " REJECT rows");
        assertEquals(ORACLE.length, rejects + accepts);
    }

    @Test
    @DisplayName("the +no_defs rule over-rejects the need_ellps=0 operators, on the record")
    void needEllpsIsNotModelledPerOperator() {
        for (String def : NEED_ELLPS_NOT_MODELLED) {
            GieFailure f = ProjDefinitionValidator.validate(GieProjArgs.parse(def));
            assertTrue(f != null, def + " is now accepted, which matches proj 9.8.1. If "
                    + "need_ellps was modelled per operator, delete this entry - the gap "
                    + "it records has closed. If it was not, something else changed the "
                    + "+no_defs rule and the ~170 need_ellps=1 operators need re-checking.");
        }
    }

    @Test
    @DisplayName("guards this class declines to model are honestly reported as not modelled")
    void unmodelledGuardsStayValid() {
        for (String def : UNMODELLED) {
            GieFailure f = ProjDefinitionValidator.validate(GieProjArgs.parse(def));
            assertEquals(null, f, def + " is now classified INVALID_DEFINITION. proj 9.8.1 "
                    + "does reject it, but on a guard this class does not model - so "
                    + "either the guard was ported (update this list) or the rejection is "
                    + "coming from somewhere it should not.");
        }
    }
}
