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
package org.locationtech.proj4j.api;

/**
 * One object named by authority: what kind of thing it is, which authority names it, and the code.
 *
 * <p>This is the parse half of PROJ's {@code proj_create()} on a short text. PROJ does it in
 * {@code createFromUserInput} ({@code src/iso19111/io.cpp} at 9.8.1), which splits the text on
 * {@code ':'} and then matches on token count. The five forms it accepts are reproduced here, in
 * the same order, with the same rules:
 *
 * <table>
 * <caption>Accepted forms</caption>
 * <tr><th>form</th><th>example</th><th>upstream site</th></tr>
 * <tr><td>seven tokens, {@code urn} first</td>
 *     <td>{@code urn:ogc:def:crs:EPSG::4326}</td><td>io.cpp:8068</td></tr>
 * <tr><td>six tokens, {@code urn} first, third token not {@code def}</td>
 *     <td>{@code urn:opengis:crs:EPSG:0:4326}</td><td>io.cpp:8085</td></tr>
 * <tr><td>six tokens, {@code urn} first (no version)</td>
 *     <td>{@code urn:x-ogc:def:crs:EPSG:4326}</td><td>io.cpp:8095</td></tr>
 * <tr><td>two tokens</td><td>{@code EPSG:4326}</td><td>io.cpp:7779</td></tr>
 * </table>
 *
 * <p>The two-token form has <b>no type token</b>, and upstream resolves it as a
 * <b>coordinate reference system</b> ({@code createCoordinateReferenceSystem}), so {@link #type()}
 * reports {@code "crs"} for it. That matters: {@code "NKG:ITRF2000_TO_DK"} does <em>not</em> name an
 * operation to PROJ, only {@code "urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK"} does.
 *
 * <p>{@link #parse(String)} returns {@code null} rather than throwing for anything it does not
 * recognise, because "this text is not an authority identifier" is a routing fact and the caller has
 * other notations to try. In particular it returns {@code null}, not a half-parse, for the four
 * upstream forms that are <em>not</em> a single authority lookup and are handled elsewhere in
 * {@code createFromUserInput}:
 *
 * <ul>
 * <li>the comma-separated compound and combined-reference URNs, e.g.
 *     {@code urn:ogc:def:crs,crs:EPSG::2393,crs:EPSG::5717} (io.cpp:7862, :8041);</li>
 * <li>the WMS {@code AUTO} extension {@code urn:ogc:def:crs:OGC::AUTO42001:-117:33}
 *     (io.cpp:8078), which is a projection with inline parameters, not a code;</li>
 * <li>anything containing a space &mdash; upstream only tokenises when
 *     {@code text.find(' ') == npos} (io.cpp:7777), which is what stops a WKT string or a
 *     {@code "NAD83 + NAVD88 height"} compound name being read as a code;</li>
 * <li>{@code http://} CRS URLs and {@code AUTO:} strings, which upstream screens before
 *     tokenising.</li>
 * </ul>
 *
 * <p>What this class deliberately does <b>not</b> do is look anything up. It reports the four parts
 * and stops. The authority may be versioned ({@code "EPSG"} plus version {@code "9.2"} resolving to
 * an authority literally named {@code "EPSG_9_2"}); upstream retries through
 * {@code getVersionedAuthority} only after an unversioned lookup fails, so that retry belongs to
 * whoever holds the database, not here.
 *
 * <p>Instances are immutable and safe to share.
 *
 * @see Proj#createOperationDefinition(String)
 * @since 2.2.0
 */
public final class AuthorityUrn {

    private final String type;
    private final String authority;
    private final String version;
    private final String code;

    private AuthorityUrn(String type, String authority, String version, String code) {
        this.type = type;
        this.authority = authority;
        this.version = version;
        this.code = code;
    }

    /**
     * Reads one of the four authority-identifier forms.
     *
     * <p>Leading and trailing whitespace is trimmed first, matching upstream's
     * {@code find_first_not_of(" \t\r\n")} skip at io.cpp:7666. No other normalisation happens: the
     * authority and the code are returned exactly as written, because
     * {@link org.locationtech.proj4j.spi.ProjDatabase} compares both case-sensitively.
     *
     * @param text the candidate identifier; may be null
     * @return the parsed identifier, or {@code null} if {@code text} is not one of the recognised
     *         forms. Never throws.
     */
    public static AuthorityUrn parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        // io.cpp:7777 -- upstream only tokenises when there is no space in the text, which is what
        // keeps WKT and "A + B" compound names out of this path.
        if (s.indexOf(' ') >= 0) {
            return null;
        }
        // A comma means one of the compound / combined-reference URNs, handled upstream at
        // io.cpp:7862 and :8041 before tokenising, and not a single authority lookup.
        if (s.indexOf(',') >= 0) {
            return null;
        }
        String[] t = s.split(":", -1);

        // urn:ogc:def:crs:EPSG::4326                                             (io.cpp:8068)
        if (t.length == 7 && "urn".equals(lower(t[0]))) {
            // Upstream folds only the exact uppercase spelling "CRS"; every other type token is
            // taken verbatim, so "Crs" stays "Crs" and will not resolve. Reproduced as-is.
            String type = "CRS".equals(t[3]) ? "crs" : t[3];
            return make(type, t[4], t[5], t[6]);
        }
        // urn:ogc:def:crs:OGC::AUTO42001:-117:33 -- a WMS AUTO projection, not a code (io.cpp:8078)
        if (t.length > 7 && "urn".equals(t[0]) && "OGC".equals(t[4])
                && lower(t[6]).startsWith("auto")) {
            return null;
        }
        // Legacy urn:opengis:crs:EPSG:0:4326 -- note the missing "def:"             (io.cpp:8085)
        if (t.length == 6 && "urn".equals(t[0]) && !"def".equals(t[2])) {
            return make(t[2], t[3], t[4], t[5]);
        }
        // Legacy urn:x-ogc:def:crs:EPSG:4326 -- note the missing version            (io.cpp:8095)
        if (t.length == 6 && "urn".equals(t[0])) {
            return make(t[3], t[4], "", t[5]);
        }
        // EPSG:4326 -- resolved upstream as a CRS, not as an operation              (io.cpp:7779)
        if (t.length == 2) {
            return make("crs", t[0], "", t[1]);
        }
        return null;
    }

    private static AuthorityUrn make(String type, String authority, String version, String code) {
        if (type.isEmpty() || authority.isEmpty() || code.isEmpty()) {
            return null;
        }
        return new AuthorityUrn(type, authority, version, code);
    }

    private static String lower(String s) {
        // ROOT so that a Turkish default locale cannot turn "URN" into "urn" with a dotless i.
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The object kind, as the URN spells it: {@code "crs"}, {@code "coordinateOperation"},
     * {@code "datum"}, {@code "ensemble"}, {@code "ellipsoid"}, {@code "meridian"} or
     * {@code "coordinateMetadata"} are the seven upstream dispatches on ({@code createFromURNPart},
     * io.cpp:7606). Any other token parses fine and simply will not resolve, which is upstream's
     * behaviour too ({@code "unhandled object type"}).
     *
     * @return the type token; never null, never empty
     */
    public String type() {
        return type;
    }

    /**
     * The authority name, e.g. {@code "EPSG"} or {@code "NKG"}, exactly as written.
     *
     * @return the authority; never null, never empty
     */
    public String authority() {
        return authority;
    }

    /**
     * The authority version, e.g. {@code "9.2"} in {@code urn:ogc:def:crs:EPSG:9.2:4326}.
     *
     * <p>Almost always empty: the usual spelling puts nothing between the two colons. Upstream only
     * consults it after an unversioned lookup has already failed.
     *
     * @return the version, or {@code ""} when the form carries none; never null
     */
    public String version() {
        return version;
    }

    /**
     * The object code, e.g. {@code "4326"} or {@code "ITRF2000_TO_DK"}.
     *
     * @return the code; never null, never empty
     */
    public String code() {
        return code;
    }

    /**
     * @return {@code true} if this names a coordinate reference system
     */
    public boolean isCrs() {
        return "crs".equals(type);
    }

    /**
     * @return {@code true} if this names a coordinate operation
     */
    public boolean isCoordinateOperation() {
        return "coordinateOperation".equals(type);
    }

    /**
     * The two parts a {@link org.locationtech.proj4j.spi.ProjDatabase} lookup takes, joined.
     *
     * @return e.g. {@code "NKG:ITRF2000_TO_DK"}
     */
    public String authorityCode() {
        return authority + ':' + code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuthorityUrn)) {
            return false;
        }
        AuthorityUrn other = (AuthorityUrn) o;
        return type.equals(other.type) && authority.equals(other.authority)
                && version.equals(other.version) && code.equals(other.code);
    }

    @Override
    public int hashCode() {
        int h = type.hashCode();
        h = 31 * h + authority.hashCode();
        h = 31 * h + version.hashCode();
        h = 31 * h + code.hashCode();
        return h;
    }

    /**
     * @return the canonical seven-token URN spelling, e.g.
     *         {@code urn:ogc:def:crs:EPSG::4326}, whatever form was parsed
     */
    @Override
    public String toString() {
        return "urn:ogc:def:" + type + ':' + authority + ':' + version + ':' + code;
    }
}
