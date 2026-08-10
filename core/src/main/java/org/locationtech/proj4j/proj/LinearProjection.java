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

import org.locationtech.proj4j.ProjCoordinate;


/**
 * A pass-through projection: forward and inverse both copy the horizontal ordinates unchanged.
 *
 * <p>It has no {@code +proj=} name. {@link org.locationtech.proj4j.Registry} does not list it, so
 * no CRS definition can reach it and no coordinate transform will ever construct one. It is
 * reachable only by a caller that writes {@code new LinearProjection()} — which is how
 * {@code NoInverseGateTest} uses it, as the one in-tree example of a projection whose real inverse
 * genuinely is the base-class identity. {@code Projection}'s Javadoc cites it for the same reason:
 * it is why the no-inverse gate has to consult {@link #hasInverse()} rather than assume that
 * reaching the identity means something has gone wrong.
 *
 * <p><b>Three of the five methods below override nothing.</b> {@code project(ProjCoordinate,
 * ProjCoordinate)}, {@code transform(double[], int, double[], int, int)} and
 * {@code inverseTransform(double[], int, double[], int, int)} have no counterpart in
 * {@link Projection} — they are JHLabs-era signatures that the base class dropped. They are public,
 * so a caller holding a {@code LinearProjection} reference can still call them and they still do
 * what they say; a caller holding a {@code Projection} reference cannot see them at all. Only
 * {@link #inverseProject(ProjCoordinate, ProjCoordinate)}, {@link #hasInverse()},
 * {@link #isRectilinear()} and {@link #toString()} participate in dispatch. The forward direction
 * works regardless, because the base
 * {@link Projection#project(double, double, org.locationtech.proj4j.ProjCoordinate)} is already the
 * identity this class wants.
 *
 * <p>The class is kept because {@code org.locationtech.proj4j.proj} is an exported package and
 * removing a public class is a binary break.
 */
public class LinearProjection extends Projection {

	private static final long serialVersionUID = 132177987862432856L;

	/**
	 * Copies {@code src}'s horizontal ordinates into {@code dst}.
	 *
	 * <p>Overrides nothing — see the class Javadoc. The base class's forward projection is
	 * {@link Projection#project(double, double, org.locationtech.proj4j.ProjCoordinate)}, which is
	 * the same identity and is what a transform actually calls.
	 *
	 * @param src the input coordinate
	 * @param dst the output coordinate
	 * @return {@code dst}
	 */
	public ProjCoordinate project(ProjCoordinate src, ProjCoordinate dst) {
		dst.x = src.x;
		dst.y = src.y;
		return dst;
	}

	/**
	 * Copies {@code numPoints} xy pairs straight across. Overrides nothing — see the class Javadoc.
	 *
	 * @param srcPoints the source array
	 * @param srcOffset the index in {@code srcPoints} of the first ordinate to read
	 * @param dstPoints the destination array
	 * @param dstOffset the index in {@code dstPoints} of the first ordinate to write
	 * @param numPoints the number of xy pairs to copy
	 */
	public void transform(double[] srcPoints, int srcOffset, double[] dstPoints, int dstOffset, int numPoints) {
		for (int i = 0; i < numPoints; i++) {
			dstPoints[dstOffset++] = srcPoints[srcOffset++];
			dstPoints[dstOffset++] = srcPoints[srcOffset++];
		}
	}

	public ProjCoordinate inverseProject(ProjCoordinate src, ProjCoordinate dst) {
		dst.x = src.x;
		dst.y = src.y;
		return dst;
	}

	/**
	 * Copies {@code numPoints} xy pairs straight across. Overrides nothing — see the class Javadoc.
	 *
	 * @param srcPoints the source array
	 * @param srcOffset the index in {@code srcPoints} of the first ordinate to read
	 * @param dstPoints the destination array
	 * @param dstOffset the index in {@code dstPoints} of the first ordinate to write
	 * @param numPoints the number of xy pairs to copy
	 */
	public void inverseTransform(double[] srcPoints, int srcOffset, double[] dstPoints, int dstOffset, int numPoints) {
		for (int i = 0; i < numPoints; i++) {
			dstPoints[dstOffset++] = srcPoints[srcOffset++];
			dstPoints[dstOffset++] = srcPoints[srcOffset++];
		}
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isRectilinear() {
		return true;
	}

	public String toString() {
		return "Linear";
	}

}
