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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * <strong>What counts as an authority identifier, and what does not.</strong>
 *
 * <p>Every accept and every reject here is a line in PROJ 9.8.1's {@code createFromUserInput}
 * ({@code src/iso19111/io.cpp}), cited on the case. The rejects are the more valuable half: this
 * parser sits in front of a database lookup, and a form it accepts loosely is a form where proj4j
 * answers a question PROJ does not.
 *
 * <p>The single most load-bearing case is {@link #twoTokenFormIsACrsNotAnOperation()}. Upstream
 * resolves {@code "NKG:ITRF2000_TO_DK"} through {@code createCoordinateReferenceSystem}
 * (io.cpp:7779), so the bare form names a <em>CRS</em>. Reading it as an operation because the code
 * happens to look like one would be proj4j inventing a notation.
 */
public class AuthorityUrnTest {

    // ------------------------------------------------------------------ the four accepted forms

    /** Seven tokens, the canonical spelling. io.cpp:8068. */
    @Test
    public void sevenTokenUrn() {
        AuthorityUrn urn = AuthorityUrn.parse("urn:ogc:def:crs:EPSG::4326");
        assertNotNull(urn);
        assertEquals("crs", urn.type());
        assertEquals("EPSG", urn.authority());
        assertEquals("", urn.version());
        assertEquals("4326", urn.code());
        assertTrue(urn.isCrs());
        assertFalse(urn.isCoordinateOperation());
    }

    /** The version slot is read when it is filled, and is not consulted further here. */
    @Test
    public void sevenTokenUrnWithVersion() {
        AuthorityUrn urn = AuthorityUrn.parse("urn:ogc:def:crs:EPSG:9.2:4326");
        assertNotNull(urn);
        assertEquals("9.2", urn.version());
        assertEquals("EPSG", urn.authority());
    }

    /** A coordinate operation, which is the form this parser exists for. */
    @Test
    public void coordinateOperationUrn() {
        AuthorityUrn urn = AuthorityUrn.parse(
                "urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK");
        assertNotNull(urn);
        assertEquals("coordinateOperation", urn.type());
        assertEquals("NKG", urn.authority());
        assertEquals("ITRF2000_TO_DK", urn.code());
        assertTrue(urn.isCoordinateOperation());
        assertFalse(urn.isCrs());
        assertEquals("NKG:ITRF2000_TO_DK", urn.authorityCode());
    }

    /** Six tokens with no {@code def:}, so the type is at index 2. io.cpp:8085. */
    @Test
    public void legacyOpengisFormWithoutDef() {
        AuthorityUrn urn = AuthorityUrn.parse("urn:opengis:crs:EPSG:0:4326");
        assertNotNull(urn);
        assertEquals("crs", urn.type());
        assertEquals("EPSG", urn.authority());
        assertEquals("0", urn.version());
        assertEquals("4326", urn.code());
    }

    /** Six tokens with {@code def:} and therefore no version slot at all. io.cpp:8095. */
    @Test
    public void legacyXOgcFormWithoutVersion() {
        AuthorityUrn urn = AuthorityUrn.parse("urn:x-ogc:def:crs:EPSG:4326");
        assertNotNull(urn);
        assertEquals("crs", urn.type());
        assertEquals("EPSG", urn.authority());
        assertEquals("", urn.version());
        assertEquals("4326", urn.code());
    }

    /**
     * <strong>The bare two-token form is a CRS.</strong> io.cpp:7779 hands it to
     * {@code createCoordinateReferenceSystem}, with no type token anywhere in the text, so a code
     * that reads like a transformation name is still a CRS lookup as far as PROJ is concerned.
     */
    @Test
    public void twoTokenFormIsACrsNotAnOperation() {
        AuthorityUrn urn = AuthorityUrn.parse("EPSG:4326");
        assertNotNull(urn);
        assertEquals("crs", urn.type());
        assertTrue(urn.isCrs());

        AuthorityUrn looksLikeAnOperation = AuthorityUrn.parse("NKG:ITRF2000_TO_DK");
        assertNotNull(looksLikeAnOperation);
        assertEquals("crs", looksLikeAnOperation.type());
        assertFalse("a bare authority:code must not be read as an operation",
                looksLikeAnOperation.isCoordinateOperation());
    }

    /** The authority and code are handed back exactly as written; the database is case-sensitive. */
    @Test
    public void authorityAndCodeAreNotNormalised() {
        AuthorityUrn urn = AuthorityUrn.parse("urn:ogc:def:coordinateOperation:nkg::itrf2000_to_dk");
        assertNotNull(urn);
        assertEquals("nkg", urn.authority());
        assertEquals("itrf2000_to_dk", urn.code());
    }

    /**
     * Upstream folds the type token for exactly one spelling, the all-caps {@code "CRS"}
     * (io.cpp:8068). Anything else is compared verbatim and simply fails to resolve, so
     * {@code "Crs"} stays {@code "Crs"} here rather than being helpfully lowercased.
     */
    @Test
    public void onlyTheAllCapsCrsTypeTokenIsFolded() {
        assertEquals("crs", AuthorityUrn.parse("urn:ogc:def:CRS:EPSG::4326").type());
        assertEquals("Crs", AuthorityUrn.parse("urn:ogc:def:Crs:EPSG::4326").type());
        assertFalse(AuthorityUrn.parse("urn:ogc:def:Crs:EPSG::4326").isCrs());
    }

    /** The {@code urn} scheme itself is case-insensitive, unlike the parts after it. */
    @Test
    public void urnSchemeIsCaseInsensitive() {
        assertNotNull(AuthorityUrn.parse("URN:ogc:def:crs:EPSG::4326"));
        assertNotNull(AuthorityUrn.parse("Urn:ogc:def:crs:EPSG::4326"));
    }

    /** Leading and trailing whitespace is skipped, matching io.cpp:7666. */
    @Test
    public void surroundingWhitespaceIsTrimmed() {
        AuthorityUrn urn = AuthorityUrn.parse("  urn:ogc:def:crs:EPSG::4326\n");
        assertNotNull(urn);
        assertEquals("4326", urn.code());
    }

    // --------------------------------------------------------------------------- the rejections

    @Test
    public void nullAndEmptyAreRejected() {
        assertNull(AuthorityUrn.parse(null));
        assertNull(AuthorityUrn.parse(""));
        assertNull(AuthorityUrn.parse("   "));
    }

    /**
     * Upstream only tokenises when the text has no interior space (io.cpp:7777). That one test is
     * what keeps a WKT string and a {@code "NAD83 + NAVD88 height"} compound name out of the
     * authority path, so it is reproduced rather than replaced by something tidier.
     */
    @Test
    public void anythingWithASpaceIsRejected() {
        assertNull(AuthorityUrn.parse("NAD83 + NAVD88 height"));
        assertNull(AuthorityUrn.parse("GEOGCRS[\"WGS 84\",DATUM[\"World Geodetic System 1984\"]]"));
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:EPSG:: 4326"));
    }

    /**
     * The comma forms are the compound and combined-reference URNs, which name two objects and are
     * handled upstream before tokenising (io.cpp:7862, :8041). Half-parsing one would silently drop
     * the second component.
     */
    @Test
    public void commaFormsAreRejected() {
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs,crs:EPSG::2393,crs:EPSG::5717"));
        assertNull(AuthorityUrn.parse(
                "urn:ogc:def:coordinateOperation,coordinateOperation:EPSG::3895,EPSG::1618"));
    }

    /**
     * The WMS {@code AUTO} extension is a projection with its parameters inline, not a code
     * (io.cpp:8078). It has more than seven tokens, and the extra ones are the parameters.
     */
    @Test
    public void wmsAutoExtensionIsRejected() {
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:OGC::AUTO42001:-117:33"));
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:OGC:1.3:AUTO42001:-117:33"));
    }

    /** Token counts that match none of the four forms. */
    @Test
    public void unknownShapesAreRejected() {
        assertNull(AuthorityUrn.parse("4326"));
        assertNull(AuthorityUrn.parse("EPSG:crs:4326"));
        assertNull(AuthorityUrn.parse("a:b:c:d"));
        assertNull(AuthorityUrn.parse("a:b:c:d:e"));
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:EPSG::4326:extra"));
    }

    /**
     * An empty type, authority or code is rejected even when the token count is right. An empty
     * authority would otherwise become a database lookup for authority {@code ""}, which finds
     * nothing but reports it as "no such operation" rather than "that is not an identifier".
     */
    @Test
    public void emptyPartsAreRejected() {
        assertNull(AuthorityUrn.parse("urn:ogc:def::EPSG::4326"));
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:::4326"));
        assertNull(AuthorityUrn.parse("urn:ogc:def:crs:EPSG::"));
        assertNull(AuthorityUrn.parse(":4326"));
        assertNull(AuthorityUrn.parse("EPSG:"));
    }

    /** A non-{@code urn} first token with seven parts is not one of the forms. */
    @Test
    public void sevenTokensThatDoNotStartWithUrnAreRejected() {
        assertNull(AuthorityUrn.parse("http:ogc:def:crs:EPSG::4326"));
    }

    // ------------------------------------------------------------------------- value semantics

    /**
     * All four forms of {@code EPSG:4326} that carry no version parse to the same value, so a
     * caller can compare identifiers without knowing which spelling arrived.
     */
    @Test
    public void differentSpellingsOfTheSameObjectAreEqual() {
        AuthorityUrn canonical = AuthorityUrn.parse("urn:ogc:def:crs:EPSG::4326");
        AuthorityUrn legacy = AuthorityUrn.parse("urn:x-ogc:def:crs:EPSG:4326");
        AuthorityUrn bare = AuthorityUrn.parse("EPSG:4326");
        assertEquals(canonical, legacy);
        assertEquals(canonical, bare);
        assertEquals(canonical.hashCode(), bare.hashCode());
    }

    @Test
    public void differingInAnyPartIsNotEqual() {
        AuthorityUrn base = AuthorityUrn.parse("urn:ogc:def:crs:EPSG::4326");
        assertNotEquals(base, AuthorityUrn.parse("urn:ogc:def:datum:EPSG::4326"));
        assertNotEquals(base, AuthorityUrn.parse("urn:ogc:def:crs:ESRI::4326"));
        assertNotEquals(base, AuthorityUrn.parse("urn:ogc:def:crs:EPSG:9.2:4326"));
        assertNotEquals(base, AuthorityUrn.parse("urn:ogc:def:crs:EPSG::4327"));
        assertNotEquals(base, "urn:ogc:def:crs:EPSG::4326");
        assertNotEquals(base, null);
    }

    /** Whatever form came in, one canonical spelling goes out. */
    @Test
    public void toStringIsAlwaysTheSevenTokenForm() {
        assertEquals("urn:ogc:def:crs:EPSG::4326",
                AuthorityUrn.parse("EPSG:4326").toString());
        assertEquals("urn:ogc:def:crs:EPSG::4326",
                AuthorityUrn.parse("urn:opengis:crs:EPSG::4326").toString());
        assertEquals("urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK",
                AuthorityUrn.parse("urn:ogc:def:coordinateOperation:NKG::ITRF2000_TO_DK")
                        .toString());
        assertEquals("urn:ogc:def:crs:EPSG:9.2:4326",
                AuthorityUrn.parse("urn:ogc:def:crs:EPSG:9.2:4326").toString());
    }
}
