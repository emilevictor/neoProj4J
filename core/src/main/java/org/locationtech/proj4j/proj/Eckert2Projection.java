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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class Eckert2Projection extends Projection {

	private static final long serialVersionUID = 6871103723037419539L;

	private final static double FXC = 0.46065886596178063902;
	private final static double FYC = 1.44720250911653531871;
	private final static double C13 = 0.33333333333333333333;
	private final static double ONEEPS = 1.0000001;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		out.x = FXC * lplam * (out.y = Math.sqrt(4. - 3. * Math.sin(Math.abs(lpphi))));
		out.y = FYC * (2. - out.y);
		if ( lpphi < 0.) out.y = -out.y;
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		out.x = xyx / (FXC * ( out.y = 2. - Math.abs(xyy) / FYC) );
		out.y = (4. - out.y * out.y) * C13;
		if (Math.abs(out.y) >= 1.) {
			// The message used to be "I", upstream's pj_errno mnemonic, which tells a Java
			// caller nothing. Same throw, same type, same cause -- only the text changed.
			if (Math.abs(out.y) > ONEEPS)	throw new ProjectionException(
					"eck2: northing " + xyy + " is off the map. Working back from it gives "
							+ "sin(latitude) = " + out.y + ", and anything bigger than " + ONEEPS
							+ " is too far outside -1..1 to be rounded to a pole "
							+ "(eck2.cpp, eck2_s_inverse)");
			else
				out.y = out.y < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		} else
			out.y = Math.asin(out.y);
		if (xyy < 0)
			out.y = -out.y;
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Eckert II";
	}

}
