/*******************************************************************************
 * Copyright 2026
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
 *******************************************************************************/

package org.locationtech.proj4j.roundtrip;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.Projection;

/**
 * {@code inverse(forward(p)) == p}, for every projection in the registry that says
 * {@link Projection#hasInverse()}, over a fixed ladder of geographic points.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The identity was asserted <b>nowhere</b> in this project for the registry at large before this
 * class. Two things looked as though they covered it and do not:
 *
 * <ul>
 * <li>{@code org.locationtech.proj4j.errors.RegistryProjectionTest} sweeps the whole registry, but
 *     only asserts that every name <em>resolves</em>, does not write to {@code System.err}, and is
 *     not the abstract base class. It never inverts anything.</li>
 * <li>The {@code golden/} module does probe forward <em>and</em> inverse across its input set
 *     ({@code GoldenFormat.DIMENSIONS = {"fx","fy","fz","ix","iy","iz"}}), but golden is a
 *     <b>change detector</b>: it diffs observed output against a table pinned from released 1.4.3.
 *     A projection whose inverse has been wrong since 1.4.3 sits in that baseline forever and
 *     golden reports it as {@code UNCHANGED}. Golden pins values; it never asserts the round-trip
 *     identity.</li>
 * </ul>
 *
 * <p>So this is the first thing in the tree that can say "this inverse does not undo this forward"
 * without a reference value to compare against.
 *
 * <h2>What it runs</h2>
 *
 * <p>122 of the 151 instantiable registry names report {@code hasInverse() == true}. Each is built
 * with the corpus tests' idiom — {@code new CRSFactory().createFromParameters(name, def)}, as
 * {@code proj.tierA.TierADomainAndParserGapTest} and {@code proj.tierB.TierBCorpus} do — from
 * {@code +proj=<name> +ellps=GRS80} plus, for the eighteen operators that <em>require</em> extra
 * parameters, the minimum that makes {@code initialize()} succeed (see {@link #REQUIRED}).
 *
 * <p>Each is probed at {@link #POINTS}: an 8&nbsp;&times;&nbsp;7 lon/lat grid plus twelve awkward
 * cases (both signed antimeridians, both poles, both near-poles, small offsets from the origin, the
 * two tropics). <b>68 points &times; 122 projections = 8,296 probes</b>; 303 are refused by the
 * forward and skipped, leaving <b>7,993 round-trip assertions</b>. The whole class runs in well
 * under a second.
 *
 * <p>A point the forward <em>refuses</em> — by throwing, or by returning a non-finite ordinate — is
 * skipped, not failed. Refusing an out-of-domain point is the documented {@code DomainErrorPolicy}
 * contract and is tested elsewhere. This class is only about points the projection claims it can
 * handle. A point the forward accepts and the inverse then refuses is a <b>failure</b>, because the
 * pair is inconsistent: one half of the operator says the point is in the map and the other says it
 * is not.
 *
 * <h2>The metric and the one tolerance</h2>
 *
 * <p>The error of a probe is {@code max(|dlat|, |dlon| * cos(lat))} in degrees — the
 * small-displacement approximation to ground distance, in units of 111.32 km per degree. The
 * {@code cos(lat)} factor is what "the pole degeneracy" means quantitatively: a longitude
 * difference at latitude 89.9&deg; is 1/573 of the same difference at the equator. At exactly
 * &plusmn;90&deg; longitude is not recoverable at all and is not compared. Longitude differences
 * are wrapped, so a recovered &minus;180&deg; for an input of +180&deg; is correct.
 *
 * <p><b>{@link #TOLERANCE_DEGREES} = 1e-5 degrees, which is 1.1 m of latitude and at most 1.1 m of
 * longitude.</b> One number for every projection; nothing here is tuned per operator, because a
 * bespoke epsilon hides exactly the finding this class is for.
 *
 * <p>It is not tighter than that because of arithmetic, not code. At a projection's singular points
 * — the poles of the pseudocylindricals, the transverse pole of {@code tcea} — {@code dy/dphi}
 * vanishes, so one ulp of the forward output is amplified to about {@code sqrt(eps)} = 1.5e-8 rad =
 * 8.5e-7&deg; in the recovered latitude. Measured worst of that class here: 1.5e-6&deg;
 * ({@code putp6p} at the pole). A 1e-6&deg; bar would be measuring {@code double}, not proj4j.
 *
 * <p>The choice is also not delicate, which is the real justification. Sorting all 122 measured
 * worst-case errors leaves a clear two-and-a-half-decade gap: the largest error among the
 * projections that pass is 6.6e-6&deg; ({@code eqearth}, Newton inverse at the pole) and the
 * smallest among those that fail is 1.7e-3&deg; ({@code putp4p}/{@code weren}, 190 m). <b>Any
 * tolerance in [1e-5, 1e-4] produces the table below unchanged.</b>
 *
 * <h2>The anti-rot mechanism</h2>
 *
 * <p>30 of the 122 fail. They are pinned in {@link #PINNED} with the error actually measured, and
 * the test asserts the failing set <em>equals</em> that table:
 *
 * <ul>
 * <li>a name that fails and is not pinned fails the test, naming the probe point, the recovered
 *     values and the error magnitude, so it can be acted on without re-running anything;</li>
 * <li><b>a pinned name that now round-trips fails the test as over-pinned</b>, with an instruction
 *     to delete the entry. Without that, the table quietly becomes a permanent excuse list;</li>
 * <li>a pinned name whose error has more than doubled fails as a regression. Entries pinned at
 *     {@code Infinity} (the inverse refuses, or answers {@code NaN}) cannot tighten that way, and
 *     the test does not pretend otherwise;</li>
 * <li>{@link #REQUIRED} is checked for staleness too: every entry there must still be <em>needed</em>,
 *     so nobody can make a projection pass by quietly feeding it kinder parameters.</li>
 * </ul>
 *
 * <p>Most pinned entries are honest domain limits of a regional or interrupted map that the forward
 * accepts anyway — which is itself worth knowing, since a forward that accepts a point its own
 * inverse cannot return is not fail-closed.
 *
 * <h2>Two markers, and why the third one is gone</h2>
 *
 * <p>Entries that looked like defects rather than limits used to be marked <b>[suspect]</b>, meaning
 * "nobody has checked yet." Every one of them has now been checked against PROJ 9.8.1 — both the
 * installed binaries and the source at the {@code 9.8.1} tag — and the marker is retired, because a
 * question that has been answered should not still read as open. Two markers replace it:
 *
 * <ul>
 * <li><b>[upstream]</b> — PROJ 9.8.1 produces the same answer, to the printed digit, and never
 *     refuses. The port is faithful and the limitation is upstream's. Under this project's parity
 *     rule these are <b>deliberately not fixed</b>: a guard invented here alone would make this
 *     library answer differently from the oracle it claims to track, which is the harder thing to
 *     defend. Each carries its upstream file so the claim can be rechecked, and each is being
 *     reported upstream. Upgrading PROJ will not change these until upstream acts.</li>
 * <li><b>[fail-closed]</b> — we deliberately <em>diverge</em> from PROJ by throwing where it
 *     silently returns a wrong answer. Not a defect in either direction; a house rule.
 *     {@code NonConvergenceTest} names these and asserts the throw. Do not "fix" one back to match
 *     PROJ without reading that test first.</li>
 * </ul>
 *
 * <p>What is left unmarked is an ordinary domain limit, and needs no verdict.
 */
public class RegistryRoundTripAuditTest {

    /** One tolerance, for every projection and every probe. See the class javadoc. */
    private static final double TOLERANCE_DEGREES = 1e-5;

    /** A pinned entry may not more than double before it counts as a regression. */
    private static final double REGRESSION_FACTOR = 2.0;

    /** The shape every projection is given, unless {@link #REQUIRED} adds to it. */
    private static final String BASE_DEFINITION = "+ellps=GRS80";

    // ----------------------------------------------------------------------------- the ladder

    private static final double[] GRID_LONGITUDES = {-179.9, -135, -90, -45, 0, 45, 90, 135};

    private static final double[] GRID_LATITUDES = {-75, -45, -15, 0, 15, 45, 75};

    /** Deliberately awkward cases, appended to the grid. */
    private static final double[][] AWKWARD = {
            {180.0, 0.0},        // antimeridian, positive spelling
            {-180.0, 0.0},       // antimeridian, negative spelling: must wrap to the same place
            {179.9, 45.0},       // just inside the antimeridian, north
            {-179.9, -45.0},     // just inside the antimeridian, south
            {0.0, 89.9},         // near-pole, where dy/dphi is nearly zero
            {0.0, -89.9},
            {0.0, 90.0},         // the pole itself: latitude only, longitude is not recoverable
            {0.0, -90.0},
            {0.5, 0.5},          // just off the origin, where a series expansion is most accurate
            {-0.5, -0.5},
            {23.5, 66.5},        // tropic and arctic circle, northern
            {-23.5, -66.5},      // and southern
    };

    /** The 68-point ladder: {@code 8 x 7} grid plus {@link #AWKWARD}. */
    private static final double[][] POINTS = ladder();

    // ------------------------------------------------------------------- required parameters

    /**
     * The eighteen operators that cannot be built from {@code +proj=<name> +ellps=GRS80} alone,
     * with the minimum that makes {@code initialize()} succeed. Every one of these is asserted to
     * be <em>necessary</em> by {@link #requiredParametersAreStillRequired()}, so this cannot become
     * a place to hide a fix.
     *
     * <p>Values are chosen to be unremarkable — a mid-latitude cone, a Madagascar-shaped
     * {@code lat_0} for {@code labrd}, a low orbit for the perspectives — not to be flattering.
     */
    private static final String[][] REQUIRED = {
            {"bonne", "+lat_1=45"},
            {"ccon", "+lat_1=45"},
            {"euler", "+lat_1=30 +lat_2=60"},
            {"gn_sinu", "+m=1 +n=2"},
            {"imw_p", "+lat_1=30 +lat_2=60"},
            {"labrd", "+lat_0=-19"},
            {"lcc", "+lat_1=30 +lat_2=60"},
            {"lcca", "+lat_0=45"},
            {"misrsom", "+path=1"},
            {"murd1", "+lat_1=30 +lat_2=60"},
            {"murd2", "+lat_1=30 +lat_2=60"},
            {"murd3", "+lat_1=30 +lat_2=60"},
            {"nsper", "+h=500000"},
            {"pconic", "+lat_1=30 +lat_2=60"},
            {"tissot", "+lat_1=30 +lat_2=60"},
            {"tpeqd", "+lat_1=30 +lon_1=-10 +lat_2=40 +lon_2=10"},
            {"tpers", "+h=500000"},
            {"vitk1", "+lat_1=30 +lat_2=60"},
    };

    // ------------------------------------------------------------------------- the pinned set

    /** A known-failing projection: the error measured on this ladder, and why. */
    private static final class Pin {
        final String name;
        final double worstDegrees;
        final String reason;

        Pin(String name, double worstDegrees, String reason) {
            this.name = name;
            this.worstDegrees = worstDegrees;
            this.reason = reason;
        }
    }

    private static final double REFUSED = Double.POSITIVE_INFINITY;

    /**
     * The 30 projections whose inverse does not undo their forward on this ladder, each with the
     * worst error measured, in degrees. {@link #REFUSED} means the inverse threw or answered
     * {@code NaN} on a point the forward had accepted.
     *
     * <p>Delete an entry the moment it starts passing; the test will tell you to.
     */
    private static final Pin[] PINNED = {
            new Pin("adams_ws2", REFUSED,
                    "(180,0) and (0,90) land on the square's boundary and the inverse throws"),
            new Pin("alsk", REFUSED,
                    "Alaska-only modified stereographic: 48/68 probes wrong outside it, 35 refused"),
            new Pin("bipc", 49.136,
                    "bipolar conic for the Americas: 7 probes outside the two lobes, up to 49 deg off"),
            // The second half of this reason used to read "and (-179.9,-45) inverts to longitude
            // 684". That was wrong, and wrong from the commit that wrote it: at that probe the
            // inverse throws ("generic 2D inverse did not converge in 15 iterations"), and no probe
            // on the ladder recovers 684. Corrected to what is actually measured.
            new Pin("cass", REFUSED,
                    "Cassini past ~90 deg from the central meridian: 24 wrong, 20 of them refused "
                            + "by the inverse rather than answered, including (-179.9,-45), where "
                            + "the generic 2D inverse gives up after 15 iterations"),
            // ccon, murd2 and pconic share a symptom, not a file: ccon is ccon.cpp and the other two
            // are sconics.cpp, and within sconics.cpp only murd2 and pconic fold - murd3, tissot,
            // vitk1 and euler do not, so this is not a whole-family defect. What the three have in
            // common is that each inverse recovers latitude from a radius that is even about the
            // fold, so the two hemispheres are indistinguishable to it.
            //
            // Checked against the oracle at 9.8.1: forward of (-135,-75) then inverse gives PROJ the
            // same +60 we give. murd2's fold is worth knowing precisely because it is in the NORTHERN
            // hemisphere, where nobody thinks to look - bisected on the binaries, the last input that
            // round-trips is 89.503439020862118 and the first that folds is 89.503439020862103, so
            // the apex is 89.50343902086212. Past it the recovered longitude is destroyed too: at
            // lon 0 it comes back -100.990547487986 rather than 0.
            // `git diff 9.8.1 master -- src/projections/ccon.cpp src/projections/sconics.cpp` is
            // empty, so no upstream fix is in flight.
            new Pin("ccon", 180.0,
                    "[upstream] central conic (lat_1=45): the southern hemisphere is reflected onto "
                            + "the northern one, not collapsed - lat -75 comes back as +60 and lat "
                            + "-85 as +83.9 - and the south pole inverts to +90 (ccon.cpp, "
                            + "ccon_s_inverse; PROJ 9.8.1 answers identically)"),
            new Pin("gs48", REFUSED,
                    "modified stereographic for the 48 states: 32/68 probes wrong outside it"),
            new Pin("gs50", REFUSED,
                    "modified stereographic for the 50 states: 50/68 probes wrong outside it"),
            // Shares its cause with somerc below: both lose the quadrant when the point is more than
            // 90 deg from the projection centre, because the inverse reconstructs longitude through
            // an atan of a ratio whose two arguments have both changed sign. Same answer from PROJ
            // 9.8.1 to the printed digit; `git diff 9.8.1 master -- src/projections/gstmerc.cpp
            // src/projections/somerc.cpp` is empty.
            new Pin("gstmerc", 179.6,
                    "[upstream] Gauss-Schreiber TM folds a point 180 deg from the central meridian "
                            + "onto the near side: (-179.9,0) inverts to lon 0.5036, no refusal "
                            + "(gstmerc.cpp, gstmerc_s_inverse; PROJ 9.8.1 answers identically)"),
            new Pin("hammer", REFUSED,
                    "the antimeridian and both poles land exactly on the bounding ellipse, whose "
                            + "boundary the inverse refuses"),
            new Pin("igh", REFUSED,
                    "interrupted Goode homolosine: the south pole at lon 0 is on a lobe boundary"),
            new Pin("igh_o", REFUSED, "as igh, with the ocean-centred interruptions"),
            new Pin("imoll", REFUSED,
                    "interrupted Mollweide: the south pole at lon 0 is on a lobe boundary"),
            new Pin("imoll_o", REFUSED, "as imoll, with the ocean-centred interruptions"),
            new Pin("imw_p", REFUSED,
                    "IMW polyconic (lat_1=30, lat_2=60): 25 probes wrong, 24 refused, inverse "
                            + "latitude NaN"),
            new Pin("labrd", REFUSED,
                    "Laborde is Madagascar-only (lat_0=-19): 56/68 wrong, recovering latitudes in "
                            + "the tens of thousands of degrees"),
            new Pin("lagrng", REFUSED,
                    "Lagrange: the antimeridian lands on the bounding circle, which is refused"),
            // lcc was pinned here at 180.0 with the note "the forward should refuse it". It now
            // does (LambertConformalConicProjection.project, lcc.cpp:27-33), so the projection
            // round-trips on all 67 probes it accepts, worst error 3.0e-14 degrees, and no pin of
            // any kind is needed: the audit skips probes the forward refuses. This test told me to
            // delete the entry rather than the other way round - it reports OVER-PINNED when a
            // pinned projection starts passing.
            new Pin("lee_os", 88.194,
                    "Lee oblique stereographic: the far hemisphere is not injective, 7 probes wrong"),
            new Pin("mil_os", 46.019,
                    "Miller oblique stereographic: the far hemisphere is not injective, 7 wrong"),
            new Pin("murd2", 179.0,
                    "[upstream] Murdoch II (lat_1=30, lat_2=60): the southern hemisphere is "
                            + "reflected onto the northern one - lat -75 comes back as +58.1 and lat "
                            + "-85 as +82.7 (sconics.cpp, murd2_s_inverse; PROJ 9.8.1 answers "
                            + "identically, apex of the fold at 89.5028 deg in both)"),
            // "all four |lat| >= 89.9 probes" understated the reach: 89.9 is simply the closest
            // point to the pole on the ladder. Measured directly, (0, 89.8) throws as well (last
            // correction -4.7656e-4) and (0, 89.0) succeeds to 4.4e-12 deg.
            //
            // Adjudicated. The iteration itself is bit-faithful: nell_h.cpp has NITER 9 and EPS 1e-7,
            // the same update and the same phi = 0 seed, and NellHProjection matches all of it. The
            // only difference is what happens when the 9 run out - PROJ snaps to a pole, we throw -
            // so this is a house rule and not a porting error in either direction. Measured on the
            // oracle, forward then inverse: 89.8 comes back as 90.000000000000000, a silent 22.26 km,
            // and at lon 45 the longitude comes back 45.157079313685; 89.05 round-trips correctly to
            // 89.049999999982546. Our boundary is latitude 89.05069318341576.
            //
            // "Too few iterations" was closer to right than a previous note here claimed, and the
            // distinction matters to anyone reading this to decide what upstream should do:
            //
            //  - Short of the pole, NITER really is the whole problem. Simulating the loop exactly,
            //    89.8 exhausts at 9 but converges at 12 steps to 89.800000000000, and 89.9 likewise.
            //    So for the band this pin is actually about, a bigger loop would fix upstream.
            //  - AT the pole it would not, because dy/dphi = 2(1 - sec^2(phi/2)/2) is zero at pi/2,
            //    making it a double root with only linear convergence. The loop stops at 23 steps
            //    with the latitude still 4.5e-6 deg out - about 50 cm - and 200 iterations give the
            //    same answer in the same 23 steps. |V| < EPS is not an error bound there, so a snap
            //    is still needed; it wants conditioning on p rather than on exhaustion.
            //  - The exhaustion branch's lam = 2*x is NOT a second, independent slip. 1.0 +
            //    cos(M_HALFPI) is exactly 1.0 in doubles (cos is 6.12e-17, below half an ulp), so
            //    that line agrees with the normal one at the value it snaps to. The 0.157 deg of
            //    longitude is entirely downstream of the latitude being wrong.
            new Pin("nell_h", REFUSED,
                    "[fail-closed] Nell-Hammer's Newton inverse throws ConvergenceFailureException "
                            + "at every ladder probe with |lat| >= 89.9, and at 89.8 too, where the "
                            + "forward is happy. PROJ does not refuse - it returns latitude 90 for "
                            + "an input of 89.8, a silent 22.26 km. NonConvergenceTest names nell_h "
                            + "as one of five kernels where upstream's pole-clamp really is on a "
                            + "failure path, and asserts this throw (nell_h.cpp, nell_h_s_inverse)"),
            new Pin("omerc", 1.2070,
                    "0.1 deg from the antipode of the oblique centre the recovered longitude is "
                            + "1.207 deg out; 11 probes wrong"),
            // ccon and pconic have different forwards but recover the same lat/lon to about 1e-13,
            // which is worth knowing: whatever is reflecting the southern hemisphere is shared
            // between them rather than being two separate arithmetic slips.
            new Pin("pconic", 180.0,
                    "[upstream] perspective conic (lat_1=30, lat_2=60): as ccon, and to within 1e-13 "
                            + "of ccon's recovered coordinates - lat -75 comes back as +60 "
                            + "(sconics.cpp, pconic_s_inverse; PROJ 9.8.1 answers identically)"),
            new Pin("peirce_q", REFUSED,
                    "Peirce quincuncial: 11 probes on the square's edges and corners are refused"),
            new Pin("poly", REFUSED,
                    "polyconic far from the central meridian: 32 wrong, 22 refused, the rest "
                            + "inverting to latitudes beyond the poles (-105.7 deg)"),
            // Four failing probes, not two: as well as +/-90 it misses at +/-89.9, by 1.4999e-5 deg,
            // which clears the 1e-5 bar by half a part in three. So the error does not switch on at
            // the pole itself - it grows towards it, and the ladder happens to catch it just as it
            // crosses the tolerance.
            // Adjudicated, and the cause given here previously - "the asin argument saturates at the
            // pole" - was wrong. Nothing saturates and nothing is clamped. putp4p.cpp squashes the
            // latitude with asin(0.883883476 * sin phi) on the way out and unsquashes it with
            // asin(1.13137085 * sin phi) on the way back, and those two constants are not exact
            // reciprocals: 1/0.883883476 is 1.1313708505169522, so the second is 5.2e-10 short.
            // At the pole the product is 0.9999999995430745, which is BELOW one, so asin returns
            // 89.998267950 deg rather than 90 - a shortfall of 0.00173205 deg, which is the pinned
            // tolerance to five figures. Substituting the exact reciprocal recovers 90 exactly.
            // The fix upstream is one more digit in one constant; we are faithful to the digit.
            new Pin("putp4p", 0.0017321,
                    "[upstream] Putnins P4' recovers 89.99826795 for an input of 90 - 193 m - "
                            + "because the inverse's unsquashing constant 1.13137085 is 5.2e-10 short "
                            + "of the reciprocal of the forward's 0.883883476 (putp4p.cpp, "
                            + "putp4p_s_inverse; PROJ 9.8.1 answers identically)"),
            new Pin("som", REFUSED,
                    "Space Oblique Mercator refuses both poles on the inverse after accepting them "
                            + "on the forward"),
            new Pin("somerc", 179.6,
                    "[upstream] Swiss oblique Mercator folds the far side onto the near side: "
                            + "(-179.9,0) inverts to lon 0.5036 with no refusal; 39 probes wrong. "
                            + "Not a longitude-wrap artefact - forcing the wrap path with "
                            + "+lon_0=0.0000001 moves the answer by 2e-7 deg and nothing more "
                            + "(somerc.cpp, somerc_e_inverse; PROJ 9.8.1 answers identically)"),
            new Pin("sterea", 1.2070,
                    "oblique stereographic 0.1 deg from the antipode, where the scale factor is "
                            + "~1e9: recovered longitude 1.207 deg out"),
            // "rescaled" is measured, not assumed, and now also explained: putp4p.cpp registers both
            // projections against the same forward and the same inverse, differing only in C_x and
            // C_y (0.874038744/3.883251825 against 1.0/4.442882938). 3.883251825/4.442882938 is
            // 0.87403874..., which is exactly the C_x ratio, so the two forwards differ by the
            // constant 1.14406 that was measured, and the inverses - which divide the same two
            // constants back out before touching either latitude constant - agree to the last digit
            // at 89.99826794912813. The 5.2e-10 constant shortfall described under putp4p is shared
            // verbatim, because it is the same function.
            new Pin("weren", 0.0017321,
                    "[upstream] Werenskiold I is Putnins P4' rescaled - literally the same forward "
                            + "and inverse in PROJ, with different C_x and C_y - so it misses the "
                            + "pole by the same 0.00173205 deg, to the last printed digit, and misses "
                            + "at 89.9 for the same reason (putp4p.cpp, putp4p_s_inverse; PROJ 9.8.1 "
                            + "answers identically)"),
    };

    // -------------------------------------------------------------------------------- the test

    /**
     * The audit. Every failure — unpinned, over-pinned or regressed — is collected and reported in
     * one message, so a single run tells you everything that needs doing.
     */
    @Test
    public void everyInvertibleProjectionRoundTripsExceptThePinnedOnes() {
        Map<String, Projection> invertible = invertibleProjections();
        if (invertible.size() < 100) {
            fail("only " + invertible.size() + " invertible projections were found; the registry "
                    + "sweep is broken, and an empty sweep must not masquerade as a pass");
        }

        Map<String, Result> results = new LinkedHashMap<String, Result>();
        int probes = 0;
        int skipped = 0;
        for (Map.Entry<String, Projection> entry : invertible.entrySet()) {
            Result r = audit(entry.getKey(), build(entry.getKey()));
            results.put(entry.getKey(), r);
            probes += POINTS.length;
            skipped += r.skipped;
        }

        List<String> problems = new ArrayList<String>();

        // 1. Anything failing that is not pinned, with everything needed to act on it.
        for (Map.Entry<String, Result> entry : results.entrySet()) {
            Result r = entry.getValue();
            if (r.failures > 0 && pinFor(entry.getKey()) == null) {
                problems.add("NOT PINNED  " + entry.getKey() + ": " + r.failures + " of "
                        + r.asserted + " probes do not round-trip. " + r.worstDescription
                        + ". Fix the projection, or add it to PINNED with this measured error and "
                        + "a one-line reason.");
            }
        }

        // 2. Anything pinned that now passes. This is what stops PINNED becoming an excuse list.
        for (int i = 0; i < PINNED.length; i++) {
            Pin pin = PINNED[i];
            Result r = results.get(pin.name);
            if (r == null) {
                problems.add("OVER-PINNED " + pin.name + ": pinned, but no longer in the registry "
                        + "with hasInverse() == true. DELETE the PINNED entry.");
            } else if (r.failures == 0) {
                problems.add("OVER-PINNED " + pin.name + ": round-trips on all " + r.asserted
                        + " probes it accepts (worst error " + r.worst + " deg, within the "
                        + TOLERANCE_DEGREES + " deg tolerance), but is still pinned as failing "
                        + "with " + pin.worstDegrees + " deg. DELETE the PINNED entry for "
                        + pin.name + " - the defect it records is fixed.");
            } else if (r.worst > pin.worstDegrees * REGRESSION_FACTOR) {
                problems.add("REGRESSED   " + pin.name + ": pinned at " + pin.worstDegrees
                        + " deg, now " + r.worst + " deg. " + r.worstDescription
                        + ". Re-pin only with an explanation of why it got worse.");
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("inverse(forward(p)) != p, over ").append(probes)
                    .append(" probes (").append(skipped)
                    .append(" skipped as refused by the forward), tolerance ")
                    .append(TOLERANCE_DEGREES).append(" deg:");
            for (int i = 0; i < problems.size(); i++) {
                sb.append(System.lineSeparator()).append("  ").append(problems.get(i));
            }
            fail(sb.toString());
        }
    }

    /**
     * Every {@link #REQUIRED} entry must still be required. If a projection gains a sane default,
     * or its validation is relaxed, the entry has to go — otherwise this table silently becomes a
     * way to make a projection pass by handing it kinder parameters than a user would.
     */
    @Test
    public void requiredParametersAreStillRequired() {
        List<String> stale = new ArrayList<String>();
        for (int i = 0; i < REQUIRED.length; i++) {
            String name = REQUIRED[i][0];
            try {
                new CRSFactory().createFromParameters(name,
                        "+proj=" + name + " " + BASE_DEFINITION);
                stale.add(name + " (REQUIRED says it needs \"" + REQUIRED[i][1]
                        + "\", but it now builds without it)");
            } catch (RuntimeException expected) {
                // Still required, which is the point.
            }
        }
        if (!stale.isEmpty()) {
            fail("these REQUIRED entries are stale and must be deleted, so that the audit probes "
                    + "the same definition a user would write: " + stale);
        }
    }

    // ---------------------------------------------------------------------------- the machinery

    /** What one projection did across the whole ladder. */
    private static final class Result {
        int asserted;
        int skipped;
        int failures;
        double worst;
        String worstDescription = "no probe was accepted by the forward";
    }

    private Result audit(String name, Projection projection) {
        Result result = new Result();
        for (int i = 0; i < POINTS.length; i++) {
            double lon = POINTS[i][0];
            double lat = POINTS[i][1];

            ProjCoordinate forward = new ProjCoordinate();
            try {
                projection.project(new ProjCoordinate(lon, lat), forward);
            } catch (RuntimeException outOfDomain) {
                // Documented DomainErrorPolicy behaviour, tested elsewhere. Not this test's business.
                result.skipped++;
                continue;
            }
            if (notFinite(forward.x) || notFinite(forward.y)) {
                result.skipped++;
                continue;
            }
            result.asserted++;

            ProjCoordinate back = new ProjCoordinate();
            double error;
            String how;
            try {
                projection.inverseProject(forward, back);
                if (notFinite(back.x) || notFinite(back.y)) {
                    error = REFUSED;
                    how = "the inverse answered (" + back.x + ", " + back.y + ")";
                } else {
                    error = error(lon, lat, back);
                    how = "the inverse answered (" + back.x + ", " + back.y + ")";
                }
            } catch (RuntimeException refused) {
                error = REFUSED;
                how = "the inverse threw " + refused.getClass().getSimpleName() + ": "
                        + refused.getMessage();
            }

            if (error > TOLERANCE_DEGREES) {
                result.failures++;
            }
            if (result.asserted == 1 || error > result.worst) {
                result.worst = error;
                result.worstDescription = "worst at (" + lon + ", " + lat + "): the forward gave ("
                        + forward.x + ", " + forward.y + ") and " + how + ", " + error
                        + " deg from the input";
            }
        }
        return result;
    }

    /**
     * Ground displacement between the input and what came back, in degrees: {@code |dlat|} against
     * {@code |dlon| * cos(lat)}, whichever is larger. Longitude is wrapped, so +180 and -180 are
     * the same place, and at the poles it is not compared at all because it is not recoverable
     * there.
     */
    private static double error(double lon, double lat, ProjCoordinate back) {
        double dlat = Math.abs(back.y - lat);
        if (Math.abs(lat) == 90.0) {
            return dlat;
        }
        double dlon = Math.abs(wrapDegrees(back.x - lon)) * Math.cos(Math.toRadians(lat));
        return Math.max(dlat, dlon);
    }

    /** A longitude difference reduced to (-180, 180]. */
    private static double wrapDegrees(double degrees) {
        return (degrees + 540.0) % 360.0 - 180.0;
    }

    private static boolean notFinite(double d) {
        return Double.isNaN(d) || Double.isInfinite(d);
    }

    /** Every registry projection that claims an inverse, keyed and ordered by name. */
    private static Map<String, Projection> invertibleProjections() {
        Map<String, Projection> byName = new TreeMap<String, Projection>();
        List<Projection> all = new Registry().getProjections();
        for (int i = 0; i < all.size(); i++) {
            Projection p = all.get(i);
            if (p.hasInverse()) {
                byName.put(p.getName(), p);
            }
        }
        return byName;
    }

    /**
     * The corpus tests' idiom: hand the real {@link CRSFactory} a {@code +proj=} definition and use
     * what comes back. A projection that cannot be built this way is a failure of {@link #REQUIRED},
     * not something to skip quietly.
     */
    private static Projection build(String name) {
        String definition = "+proj=" + name + " " + BASE_DEFINITION;
        String extra = requiredFor(name);
        if (extra != null) {
            definition = definition + " " + extra;
        }
        try {
            return new CRSFactory().createFromParameters(name, definition).getProjection();
        } catch (RuntimeException e) {
            throw new AssertionError("cannot build \"" + definition + "\": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " -- add the parameters " + name + " requires to REQUIRED, do not skip it");
        }
    }

    private static String requiredFor(String name) {
        for (int i = 0; i < REQUIRED.length; i++) {
            if (REQUIRED[i][0].equals(name)) {
                return REQUIRED[i][1];
            }
        }
        return null;
    }

    private static Pin pinFor(String name) {
        for (int i = 0; i < PINNED.length; i++) {
            if (PINNED[i].name.equals(name)) {
                return PINNED[i];
            }
        }
        return null;
    }

    private static double[][] ladder() {
        List<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < GRID_LONGITUDES.length; i++) {
            for (int j = 0; j < GRID_LATITUDES.length; j++) {
                points.add(new double[] {GRID_LONGITUDES[i], GRID_LATITUDES[j]});
            }
        }
        for (int i = 0; i < AWKWARD.length; i++) {
            points.add(AWKWARD[i]);
        }
        return points.toArray(new double[points.size()][]);
    }

    /** Guards the table against duplicate or misspelled names, which would silently never match. */
    @Test
    public void thePinnedTableIsWellFormed() {
        TreeSet<String> seen = new TreeSet<String>();
        for (int i = 0; i < PINNED.length; i++) {
            if (!seen.add(PINNED[i].name)) {
                fail("PINNED lists " + PINNED[i].name + " twice");
            }
            if (PINNED[i].worstDegrees <= TOLERANCE_DEGREES) {
                fail("PINNED entry " + PINNED[i].name + " records " + PINNED[i].worstDegrees
                        + " deg, which is within tolerance; a pinned entry must be a real failure");
            }
        }
        TreeSet<String> required = new TreeSet<String>();
        for (int i = 0; i < REQUIRED.length; i++) {
            if (!required.add(REQUIRED[i][0])) {
                fail("REQUIRED lists " + REQUIRED[i][0] + " twice");
            }
        }
    }
}
