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


/**
 * Werenskiold I, {@code +proj=weren} &mdash; the {@code putp4p} kernel with different scale
 * constants, which is exactly how {@code 9.8.1:src/projections/putp4p.cpp} arranges it: one
 * {@code putp4p_s_forward} and one {@code putp4p_s_inverse} are wired to two
 * {@code PJ_PROJECTION} blocks, and {@code PJ_PROJECTION(weren)}
 * ({@code putp4p.cpp:61-76}) differs from {@code PJ_PROJECTION(putp4p)} only in setting
 * {@code C_x = 1} and {@code C_y = 4.442882938}. Subclassing
 * {@link PutninsP4Projection} and overwriting the two fields is the same arrangement.
 *
 * <p>Because the kernel is shared, the near-pole inverse error described in
 * {@link PutninsP4Projection}'s class comment is <b>identical</b> here rather than merely
 * similar: it lives in the latitude folding, which is upstream of {@code C_x} and
 * {@code C_y} entirely. Measured, {@code +proj=weren +ellps=WGS84}, forward then inverse at
 * {@code (0, 90)}: {@code proj} returns latitude {@code 89.998267949128135} and this class
 * returns {@code 89.99826794912813} &mdash; the same pair of numbers {@code putp4p} gives,
 * about 193 m from the pole. The forward metres differ, as they must:
 * {@code 10018754.161909394} here against {@code putp4p}'s {@code 8756779.308926504}, from
 * both implementations.
 *
 * <p>Faithful, so not patched, and no upstream fix is in flight &mdash;
 * {@code src/projections/putp4p.cpp} is byte-identical between tag {@code 9.8.1} and current
 * {@code master}.
 *
 * @see PutninsP4Projection
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/putp4p.cpp">9.8.1
 *      putp4p.cpp</a>
 */
public class WerenskioldProjection extends PutninsP4Projection {

	private static final long serialVersionUID = -198960339224160339L;

	public WerenskioldProjection() {
		C_x = 1;
		C_y = 4.442882938;
	}

	public String toString() {
		return "Werenskiold I";
	}

}
