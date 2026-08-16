/*
 * Copyright 2026, PROJ4J contributors
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
package org.locationtech.proj4j.io.wkt;

/**
 * What to do when an ESRI-flavoured WKT document names a reference frame this library cannot
 * place, and the document supplies no {@code TOWGS84[]} of its own.
 *
 * <h2>What "cannot place" means</h2>
 *
 * <p>ESRI WKT1 spells datum names in its own namespace, with a {@code D_} prefix:
 * {@code DATUM["D_European_1950", SPHEROID["International_1924", 6378388.0, 297.0]]}. That name is
 * not an EPSG name and not an OGC name. It is a key into ESRI's own table, and the row it selects
 * says that this frame is EPSG geodetic datum <b>6230</b> &mdash; which is the only reason anyone
 * would know that a shift to WGS&nbsp;84 of order a hundred metres is owed.
 *
 * <p>Read the same document with no such table and every one of those names falls through to the
 * ellipsoid: {@code +proj=longlat +ellps=intl +no_defs}. The result is a coordinate reference
 * system that is right about the shape of the Earth and silent about where it sits on it. Measured
 * against PROJ&nbsp;9.8.1 at 5&deg;E 52&deg;N, the shift that goes missing for
 * {@code D_European_1950} is <b>124.286&nbsp;m</b>; for {@code D_Tokyo} at 139.7&deg;E 35.7&deg;N
 * it is <b>462.853&nbsp;m</b>. Both figures are properties of the probe point, not of the datum,
 * and neither means anything quoted without one.
 *
 * <p>This policy governs <b>only</b> the case where the document has <em>confirmed</em> itself to
 * be ESRI &mdash; by naming a {@code GEOGCS} {@code GCS_something} or a {@code DATUM}
 * {@code D_something}, which is PROJ's {@code esriStyle_} and not its looser
 * {@code maybeEsriStyle_} guess &mdash; <em>and</em> the frame did not resolve to one of the ten
 * built-in {@code +datum=} codes, <em>and</em> the document carried no
 * {@code TOWGS84[]}. A plain PROJ.4 string with a bare {@code +ellps=}, or an OGC WKT1 document
 * that never claimed to be ESRI, is untouched by this and is governed as it always was by
 * {@link org.locationtech.proj4j.api.BallparkPolicy}.
 *
 * <h2>Why the default rejects</h2>
 *
 * <p>Because the alternative is a plausible wrong answer, which is the one outcome this library
 * treats as worse than no answer. A dropped ESRI datum does not throw, does not warn, and does not
 * produce a coordinate that looks wrong: it produces one that is finite, in the right units, and
 * in the right country, and is out by the size of the datum shift. There is no downstream check
 * that catches that. The only place it can be caught is here, while the document is being read.
 *
 * <p><b>This is a deliberate divergence from PROJ 9.8.1, which answers instead of refusing.</b>
 * Given an ESRI document naming a frame its own table does not contain &mdash; the measured case
 * was {@code DATUM["D_Nonsense_Datum", SPHEROID["GRS_1980", 6378137.0, 298.257222101]]} &mdash;
 * PROJ builds a CRS whose datum has no identifier, transforms it to WGS&nbsp;84, and moves the
 * point <b>0.000&nbsp;m</b>. It is silently wrong, and it is wrong in the direction that cannot be
 * detected. proj4j refuses that document by name instead.
 *
 * <p><b>The divergence is wider than just the nonsense names.</b> For a frame PROJ's ESRI table
 * <em>does</em> contain &mdash; and {@link EsriDatumTable} holds all 475 of them, generated from
 * that table &mdash; PROJ resolves the name to an EPSG frame and then still exports
 * a bare ellipsoid, because a PROJ.4 parameter list has nowhere to put a reference frame's
 * identity. proj4j refuses those too. Knowing <em>which</em> frame is not knowing <em>where</em>
 * it is, and proj4j does not yet carry a frame identity into operation selection, so accepting
 * them would produce the same unplaced coordinate with a better-informed shrug behind it. What
 * resolving the name buys is a refusal that names the frame: "PROJ's ESRI table calls this
 * EPSG:6230" is a message a caller can act on.
 *
 * <p>Neither is an {@code ID[]} on the frame an escape hatch, for the same reason: an identity is
 * not a position.
 *
 * <p>There is deliberately <strong>no global property</strong> to switch this off, for the same
 * reason {@link org.locationtech.proj4j.api.BallparkPolicy} has none. Opting in is done in code,
 * per context ({@link org.locationtech.proj4j.api.ProjContext.Builder#esriDatumPolicy}) or per
 * call ({@link CrsDefinitions#toCrs}).
 *
 * <h2>What this policy is not</h2>
 *
 * <p>It is not a way to obtain the shift. Under {@link #ALLOW} the shift is still missing; the
 * caller has simply said they know. proj4j does not invent a {@code +towgs84=} for an ESRI name
 * and could not honestly do so: EPSG publishes <b>36</b> candidate operations from ED50 to
 * WGS&nbsp;84, differing by tens of metres and by area of use, and picking one of them at parse
 * time would re-create exactly this defect one layer further down, with the added disadvantage of
 * looking authoritative.
 *
 * @see org.locationtech.proj4j.api.BallparkPolicy
 * @since 2.2.0
 */
public enum EsriDatumPolicy {

    /**
     * <b>The default.</b> Refuse the document:
     * {@link CrsDefinitions#toCrs} and its siblings throw {@link WktParseException}, and the
     * message names the frame, says which EPSG frame {@link EsriDatumTable} knows it to be if it
     * knows one, and says what the caller can do about it &mdash; supply a {@code TOWGS84[]}, or
     * set this policy to {@link #ALLOW}.
     */
    REJECT,

    /**
     * Emit the ellipsoid and nothing else, which is byte-for-byte what PROJ&nbsp;9.8.1 exports for
     * the same document and what proj4j 2.1.0 did unconditionally.
     *
     * <p>The datum shift is then absent. Whether that absence is itself refused later is
     * {@link org.locationtech.proj4j.api.BallparkPolicy}'s question, not this one, and its
     * default also rejects &mdash; so a caller who sets this and then transforms to another datum
     * will usually still be stopped, one layer down, with a message about the missing shift rather
     * than about the missing name.
     */
    ALLOW
}
