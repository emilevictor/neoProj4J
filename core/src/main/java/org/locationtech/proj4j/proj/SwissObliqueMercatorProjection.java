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

/*
 * This file was converted from the PROJ.4 source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Swiss Oblique Mercator, {@code +proj=somerc} &mdash; a port of
 * {@code 9.8.1:src/projections/somerc.cpp}. Ellipsoidal only, and the projection CH1903 and
 * CH1903+ are defined on.
 *
 * <p>A double projection: the ellipsoid is mapped conformally onto a sphere, the sphere is
 * rotated so that {@code lat_0} becomes its equator, and a Mercator is applied to the
 * rotated sphere. The forward is closed form; the inverse is closed form except for the
 * last step, a six-step Newton solve for the geodetic latitude from the isometric one.
 *
 * <h2>Past {@code 90 / c} degrees of longitude the answer folds back</h2>
 *
 * <p>The forward's last angular step is
 * {@code lampp = asin(cp * sin(lamp) / cos(phipp))} ({@code somerc.cpp:33}), which recovers
 * an angle from its sine alone and so cannot distinguish {@code lamp} from
 * {@code 180 - lamp}. <b>There is no {@code atan2} anywhere in {@code somerc.cpp}</b> — nor
 * in {@code gstmerc.cpp}, the other double projection with the same flaw. The quadrant is
 * not lost in transit; it is never computed.
 *
 * <p>Because {@code lamp = c * lam}, the turning point is {@code |lam| = 90 / c}. On WGS84
 * at {@code lat_0=0}, {@code c = 1.0033640898209764} and the turn is at
 * <b>89.698247040172731 degrees</b> from the central meridian. Past it the recovered
 * longitude is reflected about that value, {@code 180 / c - lam}. Measured with
 * {@code +proj=somerc +lat_0=0 +ellps=WGS84}, forward then inverse:
 *
 * <pre>
 *   lon 100  -&gt;  79.396494080345477
 *   lon 120  -&gt;  59.396494080345505
 *   lon 179  -&gt;   0.39649408034546751
 *   lon  89.5 -&gt; 89.499999999999702   (still inside the turn)
 * </pre>
 *
 * <p>Two of those four used to be written here as {@code 59.396494080345491} and
 * {@code 89.500000000000000}. Both were wrong in the last digits; all four above are
 * {@code proj -I}'s own output at {@code %.17g}, and this class returns the same four
 * {@code double}s bit for bit.
 *
 * <p>The latitude is <em>not</em> disturbed &mdash; at {@code lat_0=0} the northing comes
 * from {@code phipp}, which does not depend on the quadrant of {@code lamp}, so 45 comes
 * back as 45. That is the one visible difference from {@code gstmerc}, whose northing runs
 * through {@code atan(sinh(Ls) / cos(L))} and therefore changes sign; see
 * {@link GaussSchreiberTransverseMercatorProjection}.
 *
 * <p>This class reproduces the fold exactly. At {@code (100, 45)} the forward here is
 * {@code 8838377.291795218, 5591295.91855339} against {@code proj}'s
 * {@code 8838377.291795218, 5591295.918553391}, and the inverse here is
 * {@code 79.39649408034548, 44.99999999999999} against {@code proj}'s
 * {@code 79.396494080345477, 44.999999999999993}. Under this project's parity doctrine that
 * makes the port faithful, and a longitude-domain guard invented here would make this library
 * disagree with the oracle it tracks. <b>The fold is not patched.</b> That is a separate
 * question from the clamp on the inverse sines, which is a parity <em>restoration</em> and is
 * described below.
 *
 * <p>{@code Q->c} above is the same expression as {@code Q->n1} in
 * {@code gstmerc.cpp} &mdash; {@code sqrt(1 + es * cos^4(lat_0) / (1 - es))}, at every
 * {@code lat_0}, though the two files compute it by slightly different routes. Evaluated in
 * {@code double} they came out bit-identical at {@code lat_0} = 0, 45 and 46.9524055970347.
 * That is why two unrelated forwards turn at the same longitude and agree on the folded
 * result to thirteen decimal places.
 *
 * <h2>All five inverse sines go through upstream's wrapper; two of them need it</h2>
 *
 * <p>Upstream wraps <em>all five</em> of {@code somerc.cpp}'s inverse sines in {@code aasin}
 * &mdash; lines 32, 33, 48, 49 and 84 &mdash; which clamps an argument that has reached a
 * magnitude of 1 to exactly &plusmn;&pi;/2 and raises past {@code ONE_TOL}.
 * {@link ProjectionMath#asinChecked(double)} <em>is</em> that wrapper, tolerance band and all,
 * and this class now calls it on all five, so the port is at <b>full wrapper parity</b> with
 * upstream, line for line. It used to call {@link Math#asin} at all five.
 *
 * <p>Two of the five are where the argument actually leaves the range: the forward's
 * {@code lampp} ({@code somerc.cpp:33}) and the inverse's {@code lamp} ({@code :49}). Both are
 * {@code asin(cp * sin(lam) / cos(phi))}, whose exact value is at most 1 and equals 1 on the
 * turning locus, so any excess is rounding &mdash; but {@link Math#asin} of {@code 1 + 1 ULP}
 * is {@code NaN}, and rounding is enough to produce one.
 *
 * <p>The other three ({@code :32}, {@code :48} and the {@code phip0} line in
 * {@link #initialize()}, {@code :84}) have arguments bounded by construction &mdash; two dot
 * products of unit vectors, and {@code sin(lat_0)} over a {@code c} that is at least 1 &mdash;
 * so on those the wrapper's clamp cannot <em>change the answer</em>. It can still fire: at
 * {@code lat_0 = 90} the {@code phip0} quotient is exactly 1, where the clamp returns the
 * {@code HALFPI} that {@link Math#asin} returns for 1 anyway. Routing them through the wrapper
 * costs nothing and removes the need to keep three
 * bounding arguments correct as the file changes. The one reachable difference is that a
 * {@code NaN} argument now raises instead of travelling on as data.
 *
 * <h3>The band this removes, measured</h3>
 *
 * <p>With {@code +proj=somerc +lat_0=0 +lon_0=0 +ellps=WGS84}, sweeping longitude across 8001
 * consecutive {@code double}s centred on {@code (pi/2) / c = 1.5655297441182725} rad
 * ({@code 89.69824704017273} degrees) at every whole degree of latitude from &minus;90 to 90,
 * the forward kernel returned a {@code NaN} easting at <b>14</b> of those latitudes, at all
 * 8001 longitudes of each: &minus;88, &minus;86, &minus;80, &minus;68, &minus;43, 38, 43, 56,
 * 72, 75, 80, 82, 84 and 88. At
 * latitude 80 the band was walked out one {@code double} at a time in both directions: every
 * longitude strictly between {@code 89.6982442502741} and {@code 89.69824983007138} degrees
 * produced one, which is <b>392,643,326</b> {@code double}s and
 * {@code 5.579797274890552e-6} degrees wide. Through the public forward each of those was a
 * {@link org.locationtech.proj4j.ProjectionException} with
 * {@link org.locationtech.proj4j.ErrorCause#NUMERICAL_FAILURE}, because the funnel tests the
 * kernel's result before applying the affine.
 *
 * <p><b>A 0.1-degree grid cannot see this.</b> The band is 5.6e-6 degrees wide, so the earlier
 * sweep recorded here &mdash; a 0.1-degree global grid, five values of {@code lat_0}, two
 * ellipsoids, 65 million points, no {@code NaN} anywhere &mdash; was sampling between the
 * teeth. That sentence was true of its own measurement and false about the code.
 *
 * <p>With the clamp, 12 of those 14 latitudes returned a finite coordinate; with the fdlibm
 * routing described next, <b>all 14 do</b>, and <b>12 of the 14 eastings are bit-identical to
 * PROJ 9.8.1's</b> (up from nine), with no refusals left. The two that are not are latitudes
 * &minus;43 and 82, at {@code 0.0947} and {@code 0.1557} m &mdash; see below for why those two
 * cannot be closed by anything this class does.
 *
 * <h2>Every transcendental here goes through fdlibm, because one ulp is worth 0.1 m</h2>
 *
 * <p>This class used to call {@link Math#sin}, {@link Math#cos}, {@link Math#tan},
 * {@link Math#log} and {@link Math#exp} &mdash; 33 sites, the second-heaviest concentration in
 * {@code core}. All five are {@code @IntrinsicCandidate}: HotSpot replaces them with a
 * hand-written per-architecture routine, so their last bit depends on the CPU. Everything here
 * now goes through {@link FastStrictTrig} ({@code sin}, {@code cos}, {@code tan}) or
 * {@link StrictMath} ({@code log}, {@code exp}, {@code atan}), both specified to the bit.
 * {@code Math.sqrt} and {@code Math.abs} are left alone, being exactly specified by IEEE 754.
 *
 * <p><b>Why it matters here specifically.</b> On the turning locus the quotient at
 * {@code somerc.cpp:33} is 1 to within a few ulp, and {@code asin}'s derivative is unbounded
 * there. The easting departs from the clamped value as the <em>square root</em> of the
 * shortfall, {@code a * kR * sqrt(2d)}, so <b>one ulp of quotient is 0.0947 m of easting</b>.
 * Every transcendental feeding that quotient therefore decides a tenth of a metre with its last
 * bit, and a projection whose answer moves with the CPU is not one this library can ship.
 * The symptom was a red CI job: three eastings pinned on aarch64 were different {@code double}s
 * on x86-64, one of them by 0.51 m.
 *
 * <p><b>The two latitudes that used to refuse do not any more, and that was never a deliberate
 * divergence.</b> At &minus;88 and 88 the {@link Math} quotient overshot 1 by {@code 6.4e-14}
 * and {@code 5.8e-14}, past {@link ProjectionMath#ONE_TOL}, so {@code asinChecked} raised
 * {@link org.locationtech.proj4j.ErrorCause#COORDINATE_OUT_OF_DOMAIN} while PROJ answered. That
 * was recorded here, and in the test, as a divergence from the oracle to be kept. It was an
 * artefact of the aarch64 {@code sin}/{@code cos} chain: the fdlibm quotient at those two
 * latitudes is {@code 1 - 2.6e-14} and {@code 1 - 3.2e-14}, the clamp never fires, and both
 * return {@code proj}'s easting to the bit.
 *
 * <h3>PROJ has no single answer on this locus either, so bit parity is not available</h3>
 *
 * <p><b>Measured, not argued.</b> {@code somerc.cpp}'s forward was transcribed to C with PROJ's
 * own {@code DEG_TO_RAD}, compiled from one source with {@code clang -O2 -arch arm64} and
 * {@code -arch x86_64}, and fed identical input bits &mdash; so only {@code libm} differs. The
 * arm64 build reproduces the {@code proj} 9.8.1 binary at all fourteen latitudes, bit for bit,
 * which is what makes it a valid stand-in; the x86-64 build disagrees with it at two of them:
 * latitude 43 by <b>0.0947 m</b> (arm64 takes the {@code asin}, x86-64 clamps) and latitude 56
 * by <b>0.1340 m</b> (the other way round). Twelve of fourteen agree, so the instrument is
 * neither noisy nor vacuous.
 *
 * <p>So &quot;PROJ 9.8.1's answer&quot; is a number per CPU here, and the residual
 * {@code 0.0947} m and {@code 0.1557} m at &minus;43 and 82 are the same phenomenon in the
 * other direction &mdash; fdlibm against Apple's {@code libm}, on a quotient whose exact value
 * is 1. <b>Determinism and bit parity with the oracle conflict on this locus, and this class
 * chooses determinism</b>, which is also what moved it from 9 to 12 bit-exact eastings.
 *
 * <p>At {@code (89.69824704017273, 80)} the forward is now
 * {@code 9985163.185561286, 1.5496570739723718E7}, bit-identical to {@code proj}'s
 * {@code 9985163.1855612863 15496570.739723718}, and the inverse of that pair is
 * {@code 89.69824704017273, 80.00000000000001}, bit-identical to {@code proj -I}'s
 * {@code 89.698247040172731 80.000000000000014}. The inverse had the same band: 801 of 801
 * eastings within &plusmn;400 {@code double}s of that point threw before the change and none
 * do after. <b>Which axis is scanned is part of that claim, not incidental to it</b> &mdash;
 * holding the easting fixed and varying the <em>northing</em> over the same &plusmn;400
 * {@code double}s gives 231 of 801 before and 0 of 801 after. Both scans were run against both
 * class trees; quoting either figure without naming its axis makes the other one look like a
 * refutation.
 *
 * <h3>The 1.15 m disagreement is a different thing, and the clamp does not move it</h3>
 *
 * <p>With {@code +proj=somerc +lat_0=46.9524055970347 +lon_0=0 +ellps=bessel} at
 * {@code (-8.1, -43.1)}, {@code proj} gives an easting of {@code -10019820.590799341} and this
 * class gives {@code -1.0019819438357947E7} &mdash; <b>1.1524413935840130 m apart</b>, the
 * northing agreeing to {@code 1.4e-7} m. Measured before and after the clamp <b>and before and
 * after the fdlibm routing</b>, that easting is the same {@code double} every time: this is not
 * a case where the platform {@code libm} and fdlibm differ, only one where PROJ and Java do.
 * The argument of the {@code asin} there is comfortably
 * inside the domain, so no clamp fires on either side. The gap is Java's transcendental
 * functions against the platform {@code libm}'s, amplified because {@code asin}'s derivative
 * grows without bound as its argument approaches 1. It is real, bounded, and confined to a set
 * of measure zero roughly 10000 km from the projection centre &mdash; and it is <b>not</b>
 * what the clamp was for.
 *
 * <p><b>No upstream fix is in flight.</b> {@code src/projections/somerc.cpp} is
 * byte-identical between tag {@code 9.8.1} and current {@code master}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/somerc.cpp">9.8.1
 *      somerc.cpp</a>
 */
public class SwissObliqueMercatorProjection extends Projection {

  private static final long serialVersionUID = 5252033951754363918L;

  private static final int NITER = 6;

	private double  K, c, hlf_e, kR, cosp0, sinp0;
  private double phi0;
	
	public SwissObliqueMercatorProjection() {
		//initialize();
	}
	
	public void initialize() {
		super.initialize();
	  double cp, phip0, sp;

    phi0 = projectionLatitude;
    
	  hlf_e = 0.5 * e;
	  cp = FastStrictTrig.cos(phi0);
	  cp *= cp;
	  c = Math.sqrt(1 + es * cp * cp * rone_es);
	  sp = FastStrictTrig.sin(phi0);
	  // asinChecked, which is upstream's aasin (somerc.cpp:84). It cannot change the answer here:
	  // sp is sin(lat_0) and c is sqrt(1 + es*cos^4(lat_0)/(1-es)), which is at least 1, so
	  // |sp / c| <= 1. At lat_0 = 90 exactly the quotient is 1.0, where asinChecked clamps to
	  // HALFPI and Math.asin(1.0) returns HALFPI too. Measured at lat_0 = -90, -46.95, 0, 46.95
	  // and 90: identical bits from both, and identical K, kR, cosp0 and sinp0.
	  cosp0 = FastStrictTrig.cos( phip0 = ProjectionMath.asinChecked(sinp0 = sp / c) );
	  sp *= e;
	  K = StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * phip0)) - c * (
	      StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * phi0)) - hlf_e *
	      StrictMath.log((1. + sp) / (1. - sp)));
	  kR = scaleFactor * Math.sqrt(one_es) / (1. - sp * sp);
	}

	/**
	 * {@code somerc_e_forward}, {@code somerc.cpp:20-37}.
	 *
	 * <p>The {@code lampp} line is where the quadrant goes: {@code asin} of a sine, so
	 * everything beyond {@code |lplam| = 90 / c} folds back. The <b>fold</b> is deliberately
	 * unguarded, because PROJ 9.8.1 does not guard it either -- there is no {@code atan2}
	 * anywhere in {@code somerc.cpp} to recover the quadrant from.
	 *
	 * <p>The {@code asin} on that same line is a different matter, and this used to say it was
	 * unguarded upstream as well. It is not: {@code somerc.cpp:33} wraps it in {@code aasin}.
	 * It is now {@link ProjectionMath#asinChecked(double)}, which is that wrapper. See the class
	 * comment for the band of NaN eastings this removed and for the 1.15 m drift it does not
	 * touch.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
	  double phip, lamp, phipp, lampp, sp, cp;

	  sp = e * FastStrictTrig.sin(lpphi);
	  phip = 2.* StrictMath.atan( StrictMath.exp( c * (
	      StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * lpphi)) - hlf_e * StrictMath.log((1. + sp)/(1. - sp)))
	    + K)) - ProjectionMath.HALFPI;
	  lamp = c * lplam;
	  cp = FastStrictTrig.cos(phip);
	  // asinChecked is upstream's aasin (9.8.1:src/aasincos.cpp:11-21): return Math.asin(v)
	  // for |v| < 1, clamp to +/-HALFPI for 1 <= |v| <= ONE_TOL, raise beyond that. somerc.cpp
	  // calls it on both of these lines, :32 and :33.
	  phipp = ProjectionMath.asinChecked(cosp0 * FastStrictTrig.sin(phip) - sinp0 * cp * FastStrictTrig.cos(lamp));
	  // This is the argument that overshoots. cos(phipp) is a rounded sqrt(1 - a*a) for the a
	  // computed on the line above, so the quotient reaches 1 + 1 ULP on the turning locus, where
	  // a bare Math.asin answers NaN. See the class comment for the band of NaN eastings that
	  // produced.
	  lampp = ProjectionMath.asinChecked(cp * FastStrictTrig.sin(lamp) / FastStrictTrig.cos(phipp));
	  xy.x = kR * lampp;
	  xy.y = kR * StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * phipp));
	  return xy;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate lp) {
	  double phip, lamp, phipp, lampp, cp, esp, con, delp;
	  int i;
	  double lplam, lpphi;

	  phipp = 2. * (StrictMath.atan(StrictMath.exp(xyy / kR)) - ProjectionMath.FORTPI);
	  lampp = xyx / kR;
	  cp = FastStrictTrig.cos(phipp);
	  // The forward's two lines seen from the other side, and upstream calls aasin on both of
	  // these too, :48 and :49. The second is the one that overshoots.
	  phip = ProjectionMath.asinChecked(cosp0 * FastStrictTrig.sin(phipp) + sinp0 * cp * FastStrictTrig.cos(lampp));
	  lamp = ProjectionMath.asinChecked(cp * FastStrictTrig.sin(lampp) / FastStrictTrig.cos(phip));
	  con = (K - StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * phip)))/c;
	  for (i = NITER; i != 0 ; --i) {
	    esp = e * FastStrictTrig.sin(phip);
	    delp = (con + StrictMath.log(FastStrictTrig.tan(ProjectionMath.FORTPI + 0.5 * phip)) - hlf_e *
	        StrictMath.log((1. + esp)/(1. - esp)) ) *
	      (1. - esp * esp) * FastStrictTrig.cos(phip) * rone_es;
	    phip -= delp;
	    if (Math.abs(delp) < ProjectionMath.EPS10)
	      break;
	  }
	  if (i != 0) {
	    lpphi = phip;
	    lplam = lamp / c;
	  } else {
	    // The message used to be "I_ERROR", upstream's pj_errno mnemonic, which tells a Java
	    // caller nothing. Same throw, same type, same cause -- only the text changed.
	    throw new ProjectionException(
	        "somerc: the inverse did not converge. After " + NITER
	            + " steps solving for the geodetic latitude from the isometric one, the "
	            + "correction was still bigger than " + ProjectionMath.EPS10
	            + " (somerc.cpp, somerc_e_inverse)");
	  }
    lp.x = lplam;
    lp.y = lpphi;
	  
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Swiss Oblique Mercator";
	}

}
