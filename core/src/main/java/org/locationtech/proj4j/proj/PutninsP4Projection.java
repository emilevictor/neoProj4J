/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Putnins P4', {@code +proj=putp4p} &mdash; a port of
 * {@code 9.8.1:src/projections/putp4p.cpp}. Pseudocylindrical, spherical, equal area, both
 * directions closed form.
 *
 * <p>{@link WerenskioldProjection} ({@code +proj=weren}) is the same kernel with different
 * {@code C_x} and {@code C_y}, exactly as upstream has one {@code putp4p_s_forward} and one
 * {@code putp4p_s_inverse} serving two {@code PJ_PROJECTION} blocks
 * ({@code putp4p.cpp:44-76}). Everything below therefore applies to both, and the two agree
 * with each other to the last digit.
 *
 * <h2>Latitude 90 inverts to 89.99826794912813, and PROJ returns the same value</h2>
 *
 * <p>That is 1.7320508718654537e-3 degrees, or <b>192.81 m</b> on a WGS84 radius, and it is
 * not a numerical accident of this port. Measured with
 * {@code proj -f "%.15f" +proj=putp4p +ellps=WGS84}, forward then inverse, against this
 * class:
 *
 * <pre>
 *   lat 90    proj 89.998267949128135    here 89.99826794912813     err 1.7320509e-3 deg
 *   lat 89.9  proj 89.899985001137765    here 89.89998500113776     err 1.4998862e-5 deg
 *   lat 89    proj 88.999998500153481                               err 1.4998e-6 deg
 *   lat 80    proj 79.999999851526141                               err 1.485e-7 deg
 * </pre>
 *
 * <p>The forward metres match too: {@code 8756779.308926504} for {@code putp4p} at
 * {@code (0, 90)} and {@code 10018754.161909394} for {@code weren}, from both. Under this
 * project's parity doctrine the port is faithful and is not patched.
 *
 * <h2>The cause is two constants that are not quite reciprocals</h2>
 *
 * <p><b>It is not a clamp.</b> The forward's {@code asin} argument at latitude 90 is
 * {@code 0.883883476} and the inverse's is {@code 0.9999999995430745}; neither reaches 1,
 * so neither {@code aasin} upstream nor {@link org.locationtech.proj4j.util.ProjectionMath#asin}
 * here ever engages its clamp.
 *
 * <p>What actually happens is that the forward folds the latitude with
 * {@code 0.883883476} ({@code putp4p.cpp:22}) and the inverse unfolds it with
 * {@code 1.13137085} ({@code putp4p.cpp:39}), and those two literals are each rounded
 * versions of an exact pair:
 *
 * <pre>
 *   forward  0.883883476    exact 0.625 * sqrt(2) = 0.8838834764831844   (-4.83e-10)
 *   inverse  1.13137085     exact reciprocal      = 1.1313708498984762   (+1.02e-10)
 *   product  0.9999999995430745  =  1 - 4.56925e-10
 * </pre>
 *
 * <p>So the round trip computes {@code asin((1 - delta) sin phi)} with
 * {@code delta = 4.56925e-10} rather than returning {@code phi}. Writing {@code d} for the
 * distance from the pole in radians, the recovered latitude is
 * {@code 90 - sqrt(d^2 + 2 delta)}, so the error is {@code sqrt(d^2 + 2 delta) - d}.
 *
 * <p>That formula is worth reading carefully, because the obvious summary of it is wrong.
 * The error does <b>not</b> grow as the square root of the distance from the pole. Away from
 * the pole, where {@code d} is much larger than {@code sqrt(2 delta) = 3.02e-5} rad, the
 * error is simply {@code delta / d}: it grows as you <em>approach</em> the pole, ten times
 * larger for every factor of ten closer, which is exactly the 1.5e-7, 1.5e-6, 1.5e-5
 * progression measured at latitudes 80, 89 and 89.9. The square root only appears at the
 * pole itself, where {@code d} is zero and the error stops growing, saturating at
 * {@code sqrt(2 delta) = 3.023e-5} rad {@code = 1.7320496e-3} degrees. Round-off in the
 * intervening steps moves the last digit or two; the measured value at latitude 90,
 * 1.7320509e-3 degrees, is that saturation point.
 *
 * <p>Upstream's {@code 0.333333333333333} in the forward against the plain {@code 3.} in the
 * inverse is a second non-reciprocal pair, but at 1e-15 relative it is swamped by the one
 * above and does not show up in any of the figures here.
 *
 * <p><b>No upstream fix is in flight.</b> {@code src/projections/putp4p.cpp}, which carries
 * both {@code putp4p} and {@code weren}, is byte-identical between tag {@code 9.8.1} and
 * current {@code master}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/putp4p.cpp">9.8.1
 *      putp4p.cpp</a>
 */
public class PutninsP4Projection extends Projection {

	private static final long serialVersionUID = -8179250064112033852L;

	protected double C_x;
	protected double C_y;

	public PutninsP4Projection() {
		C_x = 0.874038744;
		C_y = 3.883251825;
	}

	/**
	 * {@code putp4p_s_forward}, {@code putp4p.cpp:18-29}. The literals are upstream's exactly
	 * as written; {@code 0.883883476} is <em>not</em> {@code 0.625 * sqrt(2)} and correcting it
	 * here would break parity. See the class comment.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		lpphi = ProjectionMath.asin(0.883883476 * Math.sin(lpphi));
		xy.x = C_x * lplam * Math.cos(lpphi);
		xy.x /= Math.cos(lpphi *= 0.333333333333333);
		xy.y = C_y * Math.sin(lpphi);
		return xy;
	}

	/**
	 * {@code putp4p_s_inverse}, {@code putp4p.cpp:31-42}. {@code 1.13137085} is the constant
	 * that does not quite undo the forward's {@code 0.883883476}: their product is
	 * {@code 1 - 4.56925e-10}, which is why latitude 90 comes back as 89.99826794912813 here
	 * and in PROJ 9.8.1 alike. See the class comment.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate lp) {
		lp.y = ProjectionMath.asin(xyy / C_y);
		lp.x = xyx * Math.cos(lp.y) / C_x;
		lp.y *= 3.;
		lp.x /= Math.cos(lp.y);
		lp.y = ProjectionMath.asin(1.13137085 * Math.sin(lp.y));
		return lp;
	}

	/**
	 * Returns true if this projection is equal area
	 */
	public boolean isEqualArea() {
		return true;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Putnins P4";
	}

}
