/*******************************************************************************
 * Copyright 2026
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
 *******************************************************************************/

package org.locationtech.proj4j.proj;

/**
 * Modified Krovak ({@code +proj=mod_krovak}), from PROJ 9.8.1's
 * {@code src/projections/krovak.cpp}.
 *
 * <p>Krovak plus a fixed tenth-degree polynomial correction in the southing/westing
 * plane, published by the Czech survey office to bring the 1922 projection onto the
 * ETRF2000 realisation of S-JTSK. EPSG calls it "Krovak Modified"; the coefficients are
 * {@code krovak.cpp:108-120} and the reference is cited there.
 *
 * <p><b>Everything else is shared with {@link KrovakProjection}, deliberately.</b>
 * Upstream is literally one setup function taking a boolean —
 * {@code krovak_setup(P, modified)}, {@code krovak.cpp:280} — and one forward and one
 * inverse that each test it in a single place. Duplicating the 60 lines of series setup
 * would leave two copies to keep in step, so this class is the boolean and nothing
 * more.
 *
 * <p><b>It is not a parameter.</b> There is no {@code +modified} key; the two names are
 * two {@code PROJ_HEAD} entries. So there is nothing to add to
 * {@code Proj4Keyword}'s allow-list for it, and the twelve polynomial constants are
 * {@code constexpr}, not {@code pj_param} reads either.
 *
 * <p><b>{@code +czech} applies here too</b>, and reaches this class through the same
 * parser branch, because it is read once in the shared setup.
 *
 * @see KrovakProjection
 * @since 2.2.0
 */
public class ModifiedKrovakProjection extends KrovakProjection {

    private static final long serialVersionUID = 1L;

    /** {@code PJ_PROJECTION(mod_krovak)} is {@code krovak_setup(P, true)}. */
    @Override
    protected boolean isModified() {
        return true;
    }

    @Override
    public String toString() {
        return "Modified Krovak";
    }
}
