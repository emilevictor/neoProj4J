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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Lambert Conformal Conic, {@code 9.8.1:src/projections/lcc.cpp}.
 *
 * <p>Every latitude-dependent quantity here is the conformal one, so the whole projection rests on
 * {@code pj_tsfn} and {@code pj_phi2}. Both now come from {@link ConformalLat}, the port of
 * 9.8.1's rewritten {@code src/tsfn.cpp} and {@code src/phi2.cpp}, rather than from the deprecated
 * {@code ProjectionMath} equivalents:
 *
 * <ul>
 * <li>the forward and the three {@code initialize()} sites use {@code tsfn}, whose 9.8.1 form
 *     ({@code exp(e*atanh(e*sin phi))} times a cancellation-free ratio) drops a {@code tan} and a
 *     {@code pow} and is exactly {@code 1.0} at the equator where the old one was one ulp short;
 * <li>the inverse uses {@code phi2}, which is Newton on {@code tau} and converges in one or two
 *     trips. On GRS80 the old 15-step {@code pow}-per-trip loop was up to 4,145 nm from the truth;
 *     measured on {@code builtins.gie:3767-3768} ({@code +lat_1=0.5 +lat_2=2}, inverse of
 *     {@code (200, 100)}) the latitude was <b>23.1 um</b> out against that row's 0.1 mm bar, and is
 *     now under 1 nm.
 * </ul>
 *
 * <p>Because {@code n} is derived from {@code log(ml1/ml2)} of two {@code tsfn} values, the cone
 * constant itself moves by about one ulp, and with it every {@code lcc} coordinate. That is
 * expected and is the point.
 *
 * <p><b>Which parallels are in play is decided by PRESENCE, not by value.</b> {@code lcc.cpp:88-95}
 * asks {@code pj_param(..., "tlat_2").i} and {@code pj_param(..., "tlat_0").i}, so an explicit
 * {@code +lat_2=0} is a secant cone through the equator and an explicit {@code +lat_0=0} keeps the
 * latitude of origin there. Both used to be read here as {@code == 0}, which cannot express either.
 * {@link #initialize()} carries the measured figures and the count of shipped definitions affected,
 * which is zero.
 *
 * <p><b>Setup validation.</b> {@code initialize()} now reproduces {@code lcc.cpp:100-110} and
 * {@code :122-138}/{@code :154-161}: a standard parallel at or beyond the pole, and a cone constant
 * that comes out exactly zero, are rejected instead of being carried forward as a division by zero.
 * {@code builtins.gie:3862-3908} asserts eight such setups as {@code expect failure errno
 * invalid_op_illegal_arg_value}, and proj4j accepted all eight, producing coordinates from an
 * infinite or NaN cone constant. This <b>throws where it used to return</b>; no definition in the
 * bundled EPSG, ESRI or NAD tables — 1,885 {@code +proj=lcc} definitions — has {@code |lat_1| >= 90}
 * or {@code |lat_2| >= 90}, and those with no {@code lat_1} at all already threw on the
 * {@code |lat_1 + lat_2| > 0} check that was already here.
 */
public class LambertConformalConicProjection extends ConicProjection {

	private static final long serialVersionUID = 565492101400462955L;

	private double n;
	private double rho0;
	private double c;

	public LambertConformalConicProjection() {
		minLatitude = ProjectionMath.toRad(0);
		maxLatitude = ProjectionMath.toRad(80.0);
		// an incorrect init, LCC is sensitive to input parameters
		// init should happen only after the LCC projection parsing
		// projectionLatitude = ProjectionMath.QUARTERPI;
		projectionLatitude1 = 0;
		projectionLatitude2 = 0;
		// initialize();
	}

	/**
	* Set up a projection suitable for State Place Coordinates.
	*
	* <p>{@code lat_0} and {@code lat_2} go through {@link #setProjectionLatitude(double)} and
	* {@link #setProjectionLatitude2(double)} rather than being written to the fields directly,
	* because a caller of this constructor is supplying both parameters explicitly and
	* {@link #initialize()} now reads whether they were given rather than what they hold. A direct
	* field write would leave both flags false, and a State Plane zone with {@code lat_2} at the
	* equator -- or with a {@code lat_0} of 0 -- would come out tangent at {@code lat_1} instead of
	* secant, which is the very defect this reading was changed to fix. See
	* {@link Projection#projectionLatitude2Specified}.
	*/
	public LambertConformalConicProjection(Ellipsoid ellipsoid, double lon_0, double lat_1, double lat_2, double lat_0, double x_0, double y_0) {
		setEllipsoid(ellipsoid);
		projectionLongitude = lon_0;
		setProjectionLatitude(lat_0);
		scaleFactor = 1.0;
		falseEasting = x_0;
		falseNorthing = y_0;
		projectionLatitude1 = lat_1;
		setProjectionLatitude2(lat_2);
		initialize();
	}

	public ProjCoordinate project(double x, double y, ProjCoordinate out) {
		double rho;
		if (Math.abs(Math.abs(y) - ProjectionMath.HALFPI) < 1e-10) {
			/*
			 * lcc.cpp:27-33, and the inner refusal was missing here. A Lambert conformal conic has
			 * a cone with an apex over one pole, and only that pole projects to a point; the other
			 * one is infinitely far away and has no image on the map. `n` carries the sign of the
			 * apex, so `y * n <= 0` is exactly "the pole on the far side from the apex".
			 *
			 * Without the refusal both poles collapsed to rho = 0 and therefore to the same
			 * easting and northing. With +lat_1=30 +lat_2=60 the forward of (0, -90) was
			 * bit-for-bit the forward of (0, +90), and inverting it returned +90: a 180 degree
			 * error in latitude, accepted silently and with no hint in the output that the answer
			 * was for the opposite hemisphere. The other pole still projects, as it must.
			 */
			if (y * n <= 0.0) throw new ProjectionException(
					"lcc: latitude " + y + " rad is the pole opposite the cone's apex, and no point "
							+ "on the map corresponds to it. The cone's apex is over the "
							+ (n > 0.0 ? "north" : "south") + " pole, because lat_1 and lat_2 give "
							+ "n = " + n + " (lcc.cpp, lcc_e_forward)");
			rho = 0.0;
		} else {
			rho = c * (spherical ?
			    Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * y), -n) :
			      Math.pow(ConformalLat.tsfn(y, Math.sin(y), e), n));
    }
		out.x = scaleFactor * (rho * Math.sin(x *= n));
		out.y = scaleFactor * (rho0 - rho * Math.cos(x));
		return out;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
		// https://github.com/OSGeo/PROJ/blob/9.6/src/projections/lcc.cpp#L49-L53
		x /= scaleFactor;
		y /= scaleFactor;
		y = rho0 - y;
		double rho = ProjectionMath.distance(x, y);
		if (rho != 0) {
			if (n < 0.0) {
				rho = -rho;
				x = -x;
				y = -y;
			}
			if (spherical)
				out.y = 2.0 * Math.atan(Math.pow(c / rho, 1.0 / n)) - ProjectionMath.HALFPI;
			else
				out.y = ConformalLat.phi2(Math.pow(rho / c, 1.0 / n), e);
			out.x = Math.atan2(x, y) / n;
		} else {
			out.x = 0.0;
			out.y = n > 0.0 ? ProjectionMath.HALFPI : -ProjectionMath.HALFPI;
		}
		return out;
	}

	public void initialize() {
		super.initialize();
		double cosphi, sinphi;
		boolean secant;

		// Old code:
		// if ( projectionLatitude1 == 0 )
			// projectionLatitude1 = projectionLatitude2 = projectionLatitude;

		/*
		 * lcc.cpp:88-95. Both of these are PRESENCE tests upstream, and both used to be value
		 * tests here:
		 *
		 *     if (pj_param(P->ctx, P->params, "tlat_2").i)
		 *         Q->phi2 = pj_param(P->ctx, P->params, "rlat_2").f;
		 *     else {
		 *         Q->phi2 = Q->phi1;
		 *         if (!pj_param(P->ctx, P->params, "tlat_0").i)
		 *             P->phi0 = Q->phi1;
		 *     }
		 *
		 * The leading `t` sigil asks whether the key appears in the definition and `.i` is a 0/1
		 * flag, not the value. Zero is a real standard parallel and a real latitude of origin, so
		 * `projectionLatitude2 == 0` could not tell an explicit +lat_2=0 from no +lat_2 at all and
		 * silently made a definition PROJ treats as SECANT come out TANGENT -- with its latitude of
		 * origin moved to lat_1 as well, because the inner test was wrong the same way. Presence
		 * arrives on Projection.projectionLatitude2Specified and
		 * Projection.projectionLatitudeSpecified, which the setters set and which only the parser
		 * can populate.
		 *
		 * Measured against 9.8.1 on GRS80 at 10E 40N, forward:
		 *
		 *     +proj=lcc +lat_1=45 +lat_2=0    825297.566331256530   4211552.547939138487
		 *     +proj=lcc +lat_1=45             854925.007478637854   -503282.608577708714
		 *
		 * 29.6 km apart in easting and 4,715 km apart in northing, for two definitions that
		 * differ by three characters. The second pair shows the inner test in isolation -- both
		 * omit +lat_2, so both are tangent at 45 degrees, and the only difference is whether
		 * +lat_0=0 was typed:
		 *
		 *     +proj=lcc +lat_1=45 +lat_0=0    854925.007478637854   4982777.080788109452
		 *     +proj=lcc +lat_1=45             854925.007478637854   -503282.608577708714
		 *
		 * Identical eastings, because the cone is the same; 5,486 km apart in northing, because
		 * rho0 is taken at phi0 and PROJ leaves phi0 at the equator when the key is present.
		 *
		 * Still idempotent across repeated initialize() calls: on a second pass the flags are
		 * unchanged and projectionLatitude2 already holds projectionLatitude1, so both assignments
		 * are no-ops.
		 *
		 * NO SHIPPED DEFINITION MOVES. Of the 1,885 +proj=lcc definitions in the five bundled
		 * dictionaries (epsg 1,192, esri 521, nad27 75, nad83 68, world 29) not one carries
		 * +lat_2=0, and the 12 that carry +lat_0=0 all carry a non-zero +lat_2 as well, so the
		 * whole block is skipped for them under both the old rule and this one. The five presence
		 * combinations that do occur -- lat_2 present and non-zero (1,547), lat_2 absent with a
		 * non-zero lat_0 (315), lat_2 absent with no lat_0 (23), and the 6 world/*-alger-family
		 * grids that have no lat_1 at all and throw on |lat_1 + lat_2| below either way -- resolve
		 * to the same phi1, phi2, phi0 and secant flag under both rules.
		 */
		if (!projectionLatitude2Specified) {
			projectionLatitude2 = projectionLatitude1;
			if (!projectionLatitudeSpecified)
				projectionLatitude = projectionLatitude1;
		}


		// Left as ProjectionException deliberately. 6 of the 1,885 bundled +proj=lcc definitions
		// omit lat_1 entirely and so already reach this throw; reclassifying it to
		// InvalidValueException -- which is the taxonomically correct answer, since this is a
		// setup error carrying PROJ's invalid_op_illegal_arg_value -- would change the exception
		// type every one of those callers sees, and the two classes are siblings rather than
		// related by inheritance. That reclassification belongs with the error-taxonomy work, not
		// here. None of the eight rejection rows at builtins.gie:3862-3908 reaches this line.
		//
		// The 6 used to be written here as 149, which is wrong by a factor of 25 and was wrong in
		// the direction that makes the reclassification look more expensive than it is. Counted two
		// independent ways over epsg/esri/nad27/nad83/world: joining continuation lines into whole
		// records and testing for an absent lat_1 key gives 6, and grepping the four single-line
		// files plus an awk record-joiner over the three multi-line ones gives 6 as well. They are
		// all in `world` -- n-alger, n-maroc, n-tunis, s-alger, s-maroc and s-tunis, the North
		// African grids, which carry lat_0 and k_0 but no standard parallel at all. A grep for
		// `proj=lcc` that does not join continuation lines misses them entirely, because in `world`
		// the parameters run across three physical lines.
		if (Math.abs(projectionLatitude1 + projectionLatitude2) < 1e-10)
			throw new ProjectionException(
				"Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should be > 0");
		n = sinphi = Math.sin(projectionLatitude1);
		cosphi = Math.cos(projectionLatitude1);
		// lcc.cpp:100-110. Without these the standard parallels may sit at or beyond the pole,
		// where the cone degenerates and every subsequent quantity is a division by zero dressed
		// up as a coordinate. builtins.gie:3876-3908 asserts eight such setups as rejections.
		if (Math.abs(cosphi) < 1e-10 || Math.abs(projectionLatitude1) >= ProjectionMath.HALFPI)
			throw new InvalidValueException("Invalid value for lat_1: |lat_1| should be < 90 degrees");
		if (Math.abs(Math.cos(projectionLatitude2)) < 1e-10
				|| Math.abs(projectionLatitude2) >= ProjectionMath.HALFPI)
			throw new InvalidValueException("Invalid value for lat_2: |lat_2| should be < 90 degrees");
		secant = Math.abs(projectionLatitude1 - projectionLatitude2) >= 1e-10;
		spherical = (es == 0.0);
		if (!spherical) {
			double ml1, m1;

			m1 = ProjectionMath.msfn(sinphi, cosphi, es);
			ml1 = ConformalLat.tsfn(projectionLatitude1, sinphi, e);
			if (secant) {
				n = Math.log(m1 /
				   ProjectionMath.msfn(sinphi = Math.sin(projectionLatitude2), Math.cos(projectionLatitude2), es));
				// lcc.cpp:122-128 and :132-138: es so close to 1 that the two msfn values, or the
				// two tsfn values, are indistinguishable. Dividing by the resulting zero used to
				// yield an infinite cone constant. builtins.gie:3862 and :3869 are these two.
				if (n == 0.0)
					throw new InvalidValueException("Invalid value for eccentricity");
				double denom = Math.log(ml1 / ConformalLat.tsfn(projectionLatitude2, sinphi, e));
				if (denom == 0.0)
					throw new InvalidValueException("Invalid value for eccentricity");
				n /= denom;
			}
			c = (rho0 = m1 * Math.pow(ml1, -n) / n);
			rho0 *= (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < 1e-10) ? 0. :
				Math.pow(ConformalLat.tsfn(projectionLatitude, Math.sin(projectionLatitude), e), n);
		} else {
			if (secant)
				n = Math.log(cosphi / Math.cos(projectionLatitude2)) /
				   Math.log(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude2) /
				   Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude1));
			// lcc.cpp:154-161: reachable with +proj=lcc +a=1 +lat_2=.0000001, upstream's own
			// example, where lat_1 and lat_2 are too close to zero to distinguish.
			if (n == 0.0)
				throw new InvalidValueException(
					"Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should be > 0");
			c = cosphi * Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude1), n) / n;
			rho0 = (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < 1e-10) ? 0. :
				c * Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude), -n);
		}
	}

	/**
	 * Returns true if this projection is conformal
	 */
	public boolean isConformal() {
		return true;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Lambert Conformal Conic";
	}

}

