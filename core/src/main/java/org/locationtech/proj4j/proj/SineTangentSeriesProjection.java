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
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

class SineTangentSeriesProjection extends ConicProjection {

	private static final long serialVersionUID = 8359778409248280371L;

	private double C_x;
	private double C_y;
	private double C_p;
	private boolean tan_mode;

	protected SineTangentSeriesProjection( double p, double q, boolean mode ) {
		es = 0.;
		C_x = q / p;
		C_y = p;
		C_p = 1/ q;
		tan_mode = mode;
		initialize();
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double c;

		xy.x = C_x * lplam * Math.cos(lpphi);
		xy.y = C_y;
		lpphi *= C_p;
		c = Math.cos(lpphi);
		if (tan_mode) {
			xy.x *= c * c;
			xy.y *= Math.tan(lpphi);
		} else {
			xy.x /= c;
			xy.y *= Math.sin(lpphi);
		}
		return xy;
	}

	/**
	 * Inverse projection. Port of PROJ 9.8.1 {@code sts.cpp}'s {@code sts_s_inverse}, lines
	 * 39-52. The base of {@code fouc} ({@code sts.cpp:66}), {@code kav5} ({@code :75}),
	 * {@code qua_aut} ({@code :85}) and {@code mbt_s} ({@code :94}) — one kernel, four
	 * {@code p}/{@code q}/{@code tan_mode} settings.
	 * <p>
	 * The sine branch uses {@link ProjectionMath#asinChecked(double)}, which is upstream's
	 * {@code aasin} — the wrapper {@code sts.cpp:44} uses. It used to be the deprecated
	 * {@link ProjectionMath#asin(double)}, which clamps at any magnitude at all, so any northing
	 * past {@code C_y} came back as a latitude of exactly {@code pi/2 / C_p} instead of being
	 * refused. Past {@link ProjectionMath#ONE_TOL} it now refuses. The tangent branch needs no
	 * such wrapper: {@code Math.atan} is defined on the whole real line, which is why upstream
	 * leaves it bare too.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate lp) {
		double c;

		xyy /= C_y;
		c = Math.cos(lp.y = tan_mode ? Math.atan(xyy) : ProjectionMath.asinChecked(xyy));
		lp.y /= C_p;
    lp.x = xyx / (C_x * Math.cos(lp.y));
		if (tan_mode)
			lp.x /= c * c;
		else
			lp.x *= c;
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof SineTangentSeriesProjection) {
					SineTangentSeriesProjection p = (SineTangentSeriesProjection) that;
					return (
						C_x == p.C_x &&
						C_y == p.C_y &&
						C_p == p.C_p &&
						tan_mode == p.tan_mode &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(C_x, C_y, C_p, tan_mode, super.hashCode());
	}
}
