/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 *
 * @author yaqiang
 */
public class GeostationarySatelliteProjection extends Projection {

    private static final long serialVersionUID = 7598288678901692538L;

    /**
     * Height of orbit - Geostationary satellite projection
     */
    protected double heightOfOrbit = 35785831.0;

    private double _radiusP;
    private double _radiusP2;
    private double _radiusPInv2;
    private double _radiusG;
    private double _radiusG1;
    private double _c;

    /**
     * Constructor
     */
    public GeostationarySatelliteProjection() {
        name = "Geostationary";
        initialize();
    }

    @Override
    public void initialize() {
        super.initialize();
        _radiusG = 1 + (_radiusG1 = heightOfOrbit / a);
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
            throw new ProjectionException();
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
            throw new ProjectionException();
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
