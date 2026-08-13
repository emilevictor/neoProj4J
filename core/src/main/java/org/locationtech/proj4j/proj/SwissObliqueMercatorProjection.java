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
 *   lon 120  -&gt;  59.396494080345491
 *   lon 179  -&gt;   0.396494080345468
 *   lon  89.5 -&gt; 89.500000000000000    (still inside the turn)
 * </pre>
 *
 * <p>The latitude is <em>not</em> disturbed &mdash; at {@code lat_0=0} the northing comes
 * from {@code phipp}, which does not depend on the quadrant of {@code lamp}, so 45 comes
 * back as 45. That is the one visible difference from {@code gstmerc}, whose northing runs
 * through {@code atan(sinh(Ls) / cos(L))} and therefore changes sign; see
 * {@link GaussSchreiberTransverseMercatorProjection}.
 *
 * <p>This class reproduces PROJ exactly. At {@code (100, 45)} the forward here is
 * {@code 8838377.291795218, 5591295.91855339} against {@code proj}'s
 * {@code 8838377.291795218, 5591295.918553391}, and the inverse here is
 * {@code 79.39649408034548, 44.99999999999999} against {@code proj}'s
 * {@code 79.396494080345477, 44.999999999999993}. Under this project's parity doctrine that
 * makes the port faithful, and a domain guard invented here would make this library
 * disagree with the oracle it tracks. Not patched.
 *
 * <p>{@code Q->c} above is the same expression as {@code Q->n1} in
 * {@code gstmerc.cpp} &mdash; {@code sqrt(1 + es * cos^4(lat_0) / (1 - es))}, at every
 * {@code lat_0}, though the two files compute it by slightly different routes. Evaluated in
 * {@code double} they came out bit-identical at {@code lat_0} = 0, 45 and 46.9524055970347.
 * That is why two unrelated forwards turn at the same longitude and agree on the folded
 * result to thirteen decimal places.
 *
 * <h2>One measured departure from PROJ, exactly on the turning locus</h2>
 *
 * <p>Upstream wraps both of the forward's inverse-sine calls in {@code aasin}, which clamps
 * an argument of magnitude greater than 1 to exactly &plusmn;1. This port calls
 * {@link Math#asin} directly. Everywhere except on the turning locus itself the argument is
 * comfortably inside the range and the two agree; <em>on</em> that locus it sits within one
 * unit in the last place of 1, and the clamp then decides the last bits of an {@code asin}
 * whose derivative is infinite there.
 *
 * <p>Measured, with {@code +proj=somerc +lat_0=46.9524055970347 +lon_0=0 +ellps=bessel} at
 * {@code (-8.1, -43.1)}: {@code proj} gives an easting of {@code -10019820.590799341} and
 * this class gives {@code -1.0019819438357947E7} &mdash; <b>1.15 m apart</b>, with the
 * northing identical. Sweeping a 0.1-degree global grid over five values of {@code lat_0}
 * and two ellipsoids, 6.5 million points each, upstream's arithmetic exceeds 1 at just two
 * of them (both on that locus, at {@code 1 + 1.1e-15}), and this class produced no
 * {@code NaN} at any of the 65 million points tried. The disagreement is real, bounded,
 * and confined to a set of measure zero roughly 10000 km from the projection centre. It is
 * recorded rather than patched: adding a clamp here would be a third behaviour to maintain,
 * and the calls this class actually needs to match are the two on {@code somerc.cpp:32-33}.
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
	  cp = Math.cos(phi0);
	  cp *= cp;
	  c = Math.sqrt(1 + es * cp * cp * rone_es);
	  sp = Math.sin(phi0);
	  cosp0 = Math.cos( phip0 = Math.asin(sinp0 = sp / c) );
	  sp *= e;
	  K = Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * phip0)) - c * (
	      Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * phi0)) - hlf_e *
	      Math.log((1. + sp) / (1. - sp)));
	  kR = scaleFactor * Math.sqrt(one_es) / (1. - sp * sp);
	}

	/**
	 * {@code somerc_e_forward}, {@code somerc.cpp:20-37}.
	 *
	 * <p>The {@code lampp} line is where the quadrant goes: {@code asin} of a sine, so
	 * everything beyond {@code |lplam| = 90 / c} folds back. Deliberately unguarded, because
	 * PROJ 9.8.1 is unguarded here too. See the class comment for the measurements.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
	  double phip, lamp, phipp, lampp, sp, cp;

	  sp = e * Math.sin(lpphi);
	  phip = 2.* Math.atan( Math.exp( c * (
	      Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * lpphi)) - hlf_e * Math.log((1. + sp)/(1. - sp)))
	    + K)) - ProjectionMath.HALFPI;
	  lamp = c * lplam;
	  cp = Math.cos(phip);
	  phipp = Math.asin(cosp0 * Math.sin(phip) - sinp0 * cp * Math.cos(lamp));
	  lampp = Math.asin(cp * Math.sin(lamp) / Math.cos(phipp));
	  xy.x = kR * lampp;
	  xy.y = kR * Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * phipp));
	  return xy;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate lp) {
	  double phip, lamp, phipp, lampp, cp, esp, con, delp;
	  int i;
	  double lplam, lpphi;

	  phipp = 2. * (Math.atan(Math.exp(xyy / kR)) - ProjectionMath.FORTPI);
	  lampp = xyx / kR;
	  cp = Math.cos(phipp);
	  phip = Math.asin(cosp0 * Math.sin(phipp) + sinp0 * cp * Math.cos(lampp));
	  lamp = Math.asin(cp * Math.sin(lampp) / Math.cos(phip));
	  con = (K - Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * phip)))/c;
	  for (i = NITER; i != 0 ; --i) {
	    esp = e * Math.sin(phip);
	    delp = (con + Math.log(Math.tan(ProjectionMath.FORTPI + 0.5 * phip)) - hlf_e *
	        Math.log((1. + esp)/(1. - esp)) ) *
	      (1. - esp * esp) * Math.cos(phip) * rone_es;
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
