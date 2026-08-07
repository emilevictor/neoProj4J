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

public class RectangularPolyconicProjection extends Projection {

	private static final long serialVersionUID = -3462641469228621316L;

	/**
	 * {@code Q->phi1} of {@code pj_rpoly_data}. Assigned by {@link #initialize()} and not read
	 * afterwards, exactly as upstream stores it and does not read it again.
	 */
	private double phi1;
	private double fxa;
	private double fxb;
	private boolean mode;

	private final static double EPS = 1e-9;

	/**
	 * {@code rpoly_s_forward} ({@code rpoly.cpp:22-42}).
	 * <p>
	 * The two {@code projectionLatitude} terms were a private {@code phi0} field that this class
	 * declared and never assigned, so both read a constant {@code 0.0} and {@code +lat_0} was
	 * dropped. Upstream's {@code P->phi0} is plain {@code +lat_0} in radians ({@code init.cpp:651},
	 * {@code PIN->phi0 = pj_param(ctx, start, "rlat_0").f}), and the field this library populates
	 * from {@code +lat_0} is the inherited {@link #projectionLatitude} — the same one
	 * {@link PolyconicProjection#project} reads. The projection subtracts it itself; the base class
	 * does not pre-subtract.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double fa;

		if (mode)
			fa = Math.tan(lplam * fxb) * fxa;
		else
			fa = 0.5 * lplam;
		if (Math.abs(lpphi) < EPS) {
			out.x = fa + fa;
			out.y = - projectionLatitude;
		} else {
			out.y = 1. / Math.tan(lpphi);
			out.x = Math.sin(fa = 2. * Math.atan(fa * Math.sin(lpphi))) * out.y;
			out.y = lpphi - projectionLatitude + (1. - Math.cos(fa)) * out.y;
		}
		return out;
	}

	/**
	 * {@code PJ_PROJECTION(rpoly)} ({@code rpoly.cpp:44-59}), whose body is the {@code lat_ts}
	 * block below plus {@code P->es = 0}.
	 * <p>
	 * The block was commented out under a {@code FIXME} because it called {@code pj_param}, which
	 * this library does not have. The parameter it wanted is reachable without it: upstream's
	 * {@code "rlat_ts"} is the {@code +lat_ts} parameter, and the leading {@code r} means
	 * {@code param.cpp} returns it in <em>radians</em> — which is the unit
	 * {@link #trueScaleLatitude} is documented to hold and the unit
	 * {@link #setTrueScaleLatitudeDegrees} converts into. Both default to zero when {@code +lat_ts}
	 * is absent, so {@code mode} stays false and the projection keeps its previous behaviour on
	 * every definition that omits the parameter.
	 * <p>
	 * {@code es = 0} goes before {@code super.initialize()}, which derives {@code spherical},
	 * {@code one_es} and {@code rone_es} from it. Everything assigned here derives from
	 * {@link #trueScaleLatitude}, so the second call the parser makes is a no-op.
	 */
	public void initialize() { // rpoly
		es = 0.;
		e = 0.;
		super.initialize();

		phi1 = Math.abs(trueScaleLatitude);
		mode = phi1 > EPS;
		if (mode) {
			fxb = 0.5 * Math.sin(phi1);
			fxa = 0.5 / fxb;
		}
	}

	public String toString() {
		return "Rectangular Polyconic";
	}

}
