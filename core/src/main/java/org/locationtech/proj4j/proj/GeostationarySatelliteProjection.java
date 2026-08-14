/*
 * Copyright 2022 The Proj4J Contributors.
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

import java.util.Objects;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Geostationary satellite view, {@code +proj=geos}, a port of
 * {@code 9.8.1:src/projections/geos.cpp}. Registered as {@code "geos"} in
 * {@code org.locationtech.proj4j.Registry}.
 *
 * <p>This is not a map projection in the usual sense: it is what a camera on a satellite in a
 * circular equatorial orbit actually sees. The forward direction casts a ray from the satellite,
 * which sits {@code +h} metres above the point where {@code +lon_0} crosses the equator, to the
 * point on the figure of the Earth, and reports the two scan angles of that ray, scaled by the
 * orbit height so the result is in metres. The inverse reverses it: the plane coordinates become
 * scan angles, and the ray they define is intersected with the figure of the Earth by solving a
 * quadratic. That quadratic is where both inverses can fail, when the discriminant is negative
 * and the ray misses the Earth entirely.
 *
 * <p>Because it is a view rather than a mapping, most of the plane has no pre-image. Only the
 * visible disc does, so both directions refuse points outside it rather than returning a
 * plausible-looking coordinate; see {@link #project_s} for the one place where that refusal is a
 * deliberate divergence from upstream. Instruments whose imagery is georeferenced this way include
 * Meteosat, GOES and Himawari.
 *
 * <p>Two parameters matter beyond the usual {@code +lon_0} and ellipsoid:
 * <ul>
 * <li><b>{@code +h}</b>, the height of the orbit in metres, reaching {@link #setHeightOfOrbit}.
 *     There is no default, because upstream has none. This class used to hold 35785831 m, the
 *     nominal geostationary height, and since {@code Proj4Parser} assigns the keyword only when it
 *     is present, that number was the effective default for a bare {@code +proj=geos}: we answered
 *     where PROJ refuses, and answered as though the caller had asked for a satellite they never
 *     mentioned. It is now 0, which {@link #initialize()} rejects, matching
 *     {@code geos.cpp:206,226-230} — upstream reads {@code "dh"} with no presence test, so an
 *     omitted {@code +h} and an explicit {@code +h=0} are the same input there and are refused by
 *     the same value test. That is why this is a default and a range check rather than a presence
 *     test in the parser.</li>
 * <li><b>{@code +sweep}</b>, which chooses whether the scan angles are taken about the x or the y
 *     axis. It is <em>not</em> implemented here. Upstream defaults it to {@code y} and flips the
 *     two angles when it is {@code x}; this class always behaves as {@code +sweep=y}, which is the
 *     Meteosat and Himawari convention. GOES-R sweeps about x and so cannot be expressed here.</li>
 * </ul>
 *
 * @author yaqiang
 */
public class GeostationarySatelliteProjection extends Projection {

    private static final long serialVersionUID = 7598288678901692538L;

    /**
     * Height of orbit, in metres, above the point where {@code +lon_0} crosses the equator. No
     * default: 0 is upstream's value for an absent {@code +h} and {@link #initialize()} refuses it.
     */
    protected double heightOfOrbit = 0.0;

    private double _radiusP;
    private double _radiusP2;
    private double _radiusPInv2;
    private double _radiusG;
    private double _radiusG1;
    private double _c;

    /**
     * Constructor. Deliberately does not call {@link #initialize()}, as
     * {@link LambertConformalConicProjection} also does not: with no default orbit height,
     * initialising here would throw before {@code Proj4Parser} had a chance to assign {@code +h}.
     * {@code Proj4Parser} calls {@code initialize()} itself once every parameter is in place.
     */
    public GeostationarySatelliteProjection() {
        name = "Geostationary";
    }

    /**
     * @throws org.locationtech.proj4j.InvalidValueException if {@code +h} is absent, zero, negative
     *         or more than 1e10 times the semi-major axis
     */
    @Override
    public void initialize() {
        super.initialize();
        _radiusG = 1 + (_radiusG1 = heightOfOrbit / a);
        /*
         * geos.cpp:226-230, on the same quantity: radius_g_1 is h/a, so the test is scale-free and
         * catches an absent +h, a zero one and a negative one together. Upstream's errno is
         * PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE, not MISSING_ARG, even for the absent case, because
         * upstream cannot tell the two apart either -- it reads "dh" with no presence test.
         */
        if (_radiusG1 <= 0 || _radiusG1 > 1e10) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "Invalid value for h: +proj=geos needs an orbit height in metres, greater"
                            + " than 0 and at most 1e10 times the semi-major axis; got "
                            + heightOfOrbit + " m over a = " + a + " m. An omitted +h is 0 here,"
                            + " as it is upstream (geos.cpp:206,226-230)");
        }
        _c = _radiusG * _radiusG - 1.0;
        if (!this.spherical) {
            _radiusP = Math.sqrt(one_es);
            _radiusP2 = one_es;
            _radiusPInv2 = rone_es;
        } else {
            _radiusP = _radiusP2 = _radiusPInv2 = 1.0;
        }
    }


    @Override
    public double getHeightOfOrbit(){
        return this.heightOfOrbit;
    }

    @Override
    public void setHeightOfOrbit(double h){
        this.heightOfOrbit = h;
    }

    @Override
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
        if (spherical) {
            project_s(lplam, lpphi, out);
        } else {
            project_e(lplam, lpphi, out);
        }
        return out;
    }

    /**
     * Spherical forward.
     *
     * <p><b>This arm diverges from PROJ 9.8.1 on purpose.</b> Upstream's {@code geos_s_forward}
     * ({@code src/projections/geos.cpp:65}) has a "check visibility" comment with no code under it.
     * The check was deleted by upstream commit {@code dbba67bd} ("Converted geos. Expanded tabs.",
     * 2016-04-18) — a mechanical tab-expansion pass whose message claims nothing about removing a
     * check, and which left the ellipsoidal arm's equivalent check intact in the same diff. Ten
     * years on it has not been restored.
     *
     * <p>The consequence upstream is not a visible failure. Behind the globe
     * {@code radius_g - Vx} stays positive, so both {@code atan} calls return finite numbers and
     * {@code +proj=geos +R=...} answers an invisible point with a plausible, wrong coordinate. This
     * library keeps the check instead, because a failure must not be expressed as a coordinate that
     * looks usable. The refusal reports {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, which is the
     * cause upstream's own ellipsoidal arm reports for exactly this condition.
     *
     * <p>Nothing in the gie corpus measures the divergence in either direction: the one spherical
     * geos block ({@code builtins.gie}, {@code +R=6400000}) probes within 2 degrees of the
     * sub-satellite point, and the corpus's only invisible-point assertion is ellipsoidal. A gie
     * assertion could not cover it, since it would have to assert something PROJ does not do.
     *
     * @param lplam longitude relative to the central meridian, in radians
     * @param lpphi latitude, in radians
     * @param out   receives the projected coordinate
     * @throws ProjectionException if the point is not visible from the satellite
     */
    public void project_s(double lplam, double lpphi, ProjCoordinate out) {
        /* Calculation of the three components of the vector from satellite to
         ** position on earth surface (lon,lat).*/
        double tmp = Math.cos(lpphi);
        double vx = Math.cos(lplam) * tmp;
        double vy = Math.sin(lplam) * tmp;
        double vz = Math.sin(lpphi);

        /* Check visibility.*/
        if (((_radiusG - vx) * vx - vy * vy - vz * vz) < 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "fwd: (" + lplam + ", " + lpphi + ") rad is not visible from an orbit of "
                            + heightOfOrbit + " m above the central meridian; the point lies "
                            + "behind the globe");
        }

        /* Calculation based on view angles from satellite.*/
        tmp = _radiusG - vx;
        out.x = _radiusG1 * Math.atan(vy / tmp);
        out.y = _radiusG1 * Math.atan(vz / ProjectionMath.hypot(vy, tmp));
    }

    /**
     * Ellipsoidal forward.
     *
     * <p>The visibility check matches PROJ 9.8.1's {@code geos_e_forward}
     * ({@code src/projections/geos.cpp:96-100}) predicate for predicate, and upstream reports the
     * same condition as {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}, which is
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} here. Unlike {@link #project_s}, this arm is not
     * a divergence.
     *
     * @param lplam longitude relative to the central meridian, in radians
     * @param lpphi latitude, in radians
     * @param out   receives the projected coordinate
     * @throws ProjectionException if the point is not visible from the satellite
     */
    public void project_e(double lplam, double lpphi, ProjCoordinate out) {
        // Kept for the refusal message below: lpphi is about to be overwritten with the geocentric
        // latitude, which is up to ~0.19 degrees away from what the caller passed at mid-latitudes.
        // Reporting the overwritten value would name a latitude nobody asked for.
        double geodeticPhi = lpphi;

        /* Calculation of geocentric latitude. */
        lpphi = Math.atan(_radiusP2 * Math.tan(lpphi));

        /* Calculation of the three components of the vector from satellite to
         ** position on earth surface (lon,lat).*/
        double r = (_radiusP) / ProjectionMath.hypot(_radiusP * Math.cos(lpphi), Math.sin(lpphi));
        double vx = r * Math.cos(lplam) * Math.cos(lpphi);
        double vy = r * Math.sin(lplam) * Math.cos(lpphi);
        double vz = r * Math.sin(lpphi);

        /* Check visibility. */
        if (((_radiusG - vx) * vx - vy * vy - vz * vz * _radiusPInv2) < 0) {
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "fwd: (" + lplam + ", " + geodeticPhi + ") rad is not visible from an orbit of "
                            + heightOfOrbit + " m above the central meridian; the point lies "
                            + "behind the globe");
        }

        /* Calculation based on view angles from satellite. */
        double tmp = _radiusG - vx;
        out.x = _radiusG1 * Math.atan(vy / tmp);
        out.y = _radiusG1 * Math.atan(vz / ProjectionMath.hypot(vy, tmp));
    }

    @Override
    public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
        if (spherical) {
            projectInverse_s(xyx, xyy, out);
        } else {
            projectInverse_e(xyx, xyy, out);
        }
        return out;
    }

    public void projectInverse_s(double xyx, double xyy, ProjCoordinate out) {
        double det;

        /* Setting three components of vector from satellite to position.*/
        double vx = -1.0;
        double vy = Math.tan(xyx / (_radiusG - 1.0));
        double vz = Math.tan(xyy / (_radiusG - 1.0)) * Math.sqrt(1.0 + vy * vy);

        /* Calculation of terms in cubic equation and determinant.*/
        double a = vy * vy + vz * vz + vx * vx;
        double b = 2 * _radiusG * vx;
        if ((det = (b * b) - 4 * a * _c) < 0) {
            throw new ProjectionException(
                    "geos: the line of sight for (" + xyx + ", " + xyy + ") misses the globe. "
                            + "Intersecting that ray with the sphere has no real solution -- the "
                            + "quadratic's discriminant is " + det + " -- so the point is off the "
                            + "visible disc (geos.cpp, geos_s_inverse)");
        }

        /* Calculation of three components of vector from satellite to position.*/
        double k = (-b - Math.sqrt(det)) / (2 * a);
        vx = _radiusG + k * vx;
        vy *= k;
        vz *= k;

        /* Calculation of longitude and latitude.*/
        double lplam = Math.atan2(vy, vx);
        double lpphi = Math.atan(vz * Math.cos(lplam) / vx);

        out.x = lplam;
        out.y = lpphi;
    }

    public void projectInverse_e(double xyx, double xyy, ProjCoordinate out) {
        double det;

        /* Setting three components of vector from satellite to position.*/
        double vx = -1.0;
        double vy = Math.tan(xyx / _radiusG1);
        double vz = Math.tan(xyy / _radiusG1) * ProjectionMath.hypot(1.0, vy);

        /* Calculation of terms in cubic equation and determinant.*/
        double a = vz / _radiusP;
        a = vy * vy + a * a + vx * vx;
        double b = 2 * _radiusG * vx;
        if ((det = (b * b) - 4 * a * _c) < 0) {
            throw new ProjectionException(
                    "geos: the line of sight for (" + xyx + ", " + xyy + ") misses the globe. "
                            + "Intersecting that ray with the ellipsoid has no real solution -- "
                            + "the quadratic's discriminant is " + det + " -- so the point is off "
                            + "the visible disc (geos.cpp, geos_e_inverse)");
        }

        /* Calculation of three components of vector from satellite to position.*/
        double k = (-b - Math.sqrt(det)) / (2 * a);
        vx = _radiusG + k * vx;
        vy *= k;
        vz *= k;

        /* Calculation of longitude and latitude.*/
        double lplam = Math.atan2(vy, vx);
        double lpphi = Math.atan(vz * Math.cos(lplam) / vx);
        lpphi = Math.atan(_radiusPInv2 * Math.tan(lpphi));

        out.x = lplam;
        out.y = lpphi;
    }

    /**
     * Returns true if this projection is equal area
     */
    @Override
    public boolean isEqualArea() {
        return false;
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String toString() {
        return "Geostationary Satellite";
    }

    @Override
	public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that instanceof GeostationarySatelliteProjection) {
            GeostationarySatelliteProjection p = (GeostationarySatelliteProjection) that;
            return (this.heightOfOrbit == p.heightOfOrbit) && super.equals(that);
        }
        return false;
    }

    @Override
	public int hashCode() {
			return Objects.hash(heightOfOrbit, super.hashCode());
	}
}
