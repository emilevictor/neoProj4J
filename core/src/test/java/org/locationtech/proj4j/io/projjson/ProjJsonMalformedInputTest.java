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
package org.locationtech.proj4j.io.projjson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.io.wkt.AxisOrderPolicy;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.WktParseException;

/**
 * One malformed PROJJSON document per place the reader gives up, asserting both that it gives up
 * and that the message it gives up with names the thing that is wrong.
 *
 * <p>The reading half of {@code io.projjson} — {@link Json}'s parser and
 * {@link ProjJsonReader} — refuses bad input in 49 places.
 * {@link ProjJsonTest#invalidJsonIsRejected} reached nine of them and asserted only that
 * <em>something</em> was thrown, which cannot tell a message that says
 * {@code datum "d" has no "ellipsoid"} from one that says {@code null}. Every document below is the
 * smallest one that reaches exactly one refusal, and every assertion checks the message text, so a
 * future change that keeps refusing but stops explaining is a test failure rather than a silent
 * downgrade of every error a caller will ever see.
 *
 * <p>Delete this file and the reader still refuses bad documents; what is lost is any guarantee
 * that a caller handed a broken definition can find out which part of it is broken.
 *
 * <p>The one input that must <em>not</em> be refused is asserted too, by
 * {@link #theBaseDocumentsAreThemselvesValid}: without it, a template broken by accident would make
 * every test below pass for the wrong reason.
 */
public class ProjJsonMalformedInputTest {

    /**
     * The smallest GeographicCRS this reader accepts. Most documents below are this one with a
     * single member removed or corrupted.
     */
    private static final String MINIMAL_GEOGRAPHIC = ""
            + "{\"type\":\"GeographicCRS\",\"name\":\"g\","
            + "\"datum\":{\"name\":\"d\",\"ellipsoid\":{\"name\":\"e\","
            + "\"semi_major_axis\":6378137,\"inverse_flattening\":298.257223563}},"
            + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":[]}}";

    /** As above, but with the coordinate system left open so an axis can be substituted in. */
    private static String geographicWithAxis(String axis) {
        return "{\"type\":\"GeographicCRS\",\"name\":\"g\","
                + "\"datum\":{\"name\":\"d\",\"ellipsoid\":{\"name\":\"e\","
                + "\"semi_major_axis\":6378137,\"inverse_flattening\":298.257223563}},"
                + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":[" + axis + "]}}";
    }

    /** As above, but with the datum's ellipsoid replaced wholesale. */
    private static String geographicWithEllipsoid(String ellipsoid) {
        return "{\"type\":\"GeographicCRS\",\"name\":\"g\","
                + "\"datum\":{\"name\":\"d\",\"ellipsoid\":" + ellipsoid + "},"
                + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":[]}}";
    }

    /** As above, but with a prime meridian added to the datum. */
    private static String geographicWithPrimeMeridian(String primeMeridian) {
        return "{\"type\":\"GeographicCRS\",\"name\":\"g\","
                + "\"datum\":{\"name\":\"d\",\"ellipsoid\":{\"name\":\"e\","
                + "\"semi_major_axis\":6378137,\"inverse_flattening\":298.257223563},"
                + "\"prime_meridian\":" + primeMeridian + "},"
                + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\",\"axis\":[]}}";
    }

    /** A ProjectedCRS on {@link #MINIMAL_GEOGRAPHIC}, with a caller-supplied conversion. */
    private static String projectedWithConversion(String conversion) {
        return "{\"type\":\"ProjectedCRS\",\"name\":\"p\","
                + "\"base_crs\":" + MINIMAL_GEOGRAPHIC + ","
                + (conversion == null ? "" : "\"conversion\":" + conversion + ",")
                + "\"coordinate_system\":{\"subtype\":\"Cartesian\",\"axis\":[]}}";
    }

    /**
     * The control. If one of the templates above stopped parsing, every malformed variant below
     * would still throw and every test would still pass — but none of them would be measuring the
     * site it claims to measure.
     */
    @Test
    public void theBaseDocumentsAreThemselvesValid() {
        CrsDefinition geographic = new ProjJsonReader().readDefinition(MINIMAL_GEOGRAPHIC);
        assertEquals("the geographic template must parse, or every test below proves nothing",
                CrsDefinition.Kind.GEOGRAPHIC, geographic.getKind());

        CrsDefinition projected = new ProjJsonReader().readDefinition(projectedWithConversion(
                "{\"name\":\"c\",\"method\":{\"name\":\"m\"},"
                        + "\"parameters\":[{\"name\":\"q\",\"value\":1}]}"));
        assertEquals("the projected template must parse, or every test below proves nothing",
                CrsDefinition.Kind.PROJECTED, projected.getKind());
    }

    // ------------------------------------------------------- Json: the JSON grammar itself

    /** Json.parse, "JSON text is null". */
    @Test
    public void aNullDocumentIsRefusedRatherThanThrowingNullPointerException() {
        assertRefusal("a null document", "JSON text is null", null);
    }

    /**
     * Json.parse, "unexpected trailing text": a second value after the first is not one
     * document.
     */
    @Test
    public void textAfterTheRootValueIsRefused() {
        assertRefusal("trailing text", "unexpected trailing text at offset 2", "{}{}");
    }

    /** Json.value, "unexpected end of JSON text". */
    @Test
    public void anEmptyDocumentIsRefused() {
        assertRefusal("an empty document", "unexpected end of JSON text", "");
    }

    /** Json.object, "expected a JSON member name". */
    @Test
    public void anObjectWithoutAMemberNameIsRefused() {
        assertRefusal("a nameless member", "expected a JSON member name at offset 1", "{");
    }

    /** Json.object, "expected ':' after member". */
    @Test
    public void aMemberWithoutItsColonIsRefused() {
        assertRefusal("a missing colon", "expected ':' after member \"a\"", "{\"a\" 1}");
    }

    /** Json.object, "unterminated JSON object". */
    @Test
    public void anUnclosedObjectIsRefused() {
        assertRefusal("an unclosed object", "unterminated JSON object", "{\"a\":1");
    }

    /** Json.object, "expected ',' or '}'". */
    @Test
    public void twoObjectMembersWithNoCommaBetweenThemAreRefused() {
        assertRefusal("a missing comma in an object", "expected ',' or '}' at offset 7",
                "{\"a\":1 2}");
    }

    /** Json.array, "unterminated JSON array". */
    @Test
    public void anUnclosedArrayIsRefused() {
        assertRefusal("an unclosed array", "unterminated JSON array", "[1");
    }

    /** Json.array, "expected ',' or ']'". */
    @Test
    public void twoArrayElementsWithNoCommaBetweenThemAreRefused() {
        assertRefusal("a missing comma in an array", "expected ',' or ']' at offset 3", "[1 2]");
    }

    /** Json.string, "unterminated JSON string". */
    @Test
    public void anUnclosedStringIsRefused() {
        assertRefusal("an unclosed string", "unterminated JSON string", "{\"a\":\"b");
    }

    /** Json.string, "unterminated JSON escape": the document ends on the backslash. */
    @Test
    public void aStringEndingInABackslashIsRefused() {
        assertRefusal("a dangling backslash", "unterminated JSON escape", "{\"a\":\"b\\");
    }

    /** Json.string, "truncated \\u escape": fewer than four characters remain after the u. */
    @Test
    public void aUnicodeEscapeCutShortByTheEndOfTheDocumentIsRefused() {
        assertRefusal("a truncated \\u escape", "truncated \\u escape", "{\"a\":\"\\u12");
    }

    /** Json.string, "bad \\u escape": four characters remain, but they are not hexadecimal. */
    @Test
    public void aUnicodeEscapeWithNonHexDigitsIsRefused() {
        assertRefusal("a non-hex \\u escape", "bad \\u escape", "{\"a\":\"\\uZZZZ\"}");
    }

    /** Json.string, "unknown JSON escape". */
    @Test
    public void anEscapeThatJsonDoesNotDefineIsRefused() {
        assertRefusal("an undefined escape", "unknown JSON escape \\q", "{\"a\":\"\\q\"}");
    }

    /** Json.number, "not a JSON value": characters that look numeric but do not parse. */
    @Test
    public void somethingThatIsNeitherALiteralNorANumberIsRefused() {
        assertRefusal("a malformed number", "not a JSON value: \"1.2.3\" at offset 5",
                "{\"a\":1.2.3}");
        // The same site with nothing at all consumed: the offending text is quoted as empty rather
        // than the parser hanging or silently reading zero.
        assertRefusal("a bare word", "not a JSON value: \"\" at offset 5", "{\"a\":zzz}");
    }

    /** Json.expect, "expected \"true\"": a truncated keyword. */
    @Test
    public void aTruncatedTrueLiteralIsRefused() {
        assertRefusal("a truncated literal", "expected \"true\" at offset 5", "{\"a\":tru}");
    }

    // --------------------------------------------------- ProjJsonReader: document structure

    /** ProjJsonReader's constructor rejects a null policy loudly rather than at first use. */
    @Test
    public void aNullAxisOrderPolicyIsRefusedAtConstruction() {
        try {
            new ProjJsonReader((AxisOrderPolicy) null);
            fail("a null axis order policy must be refused at construction, not stored and "
                    + "dereferenced later inside read()");
        } catch (IllegalArgumentException e) {
            assertTrue("the refusal must name the argument at fault, not just fail: "
                    + e.getMessage(), String.valueOf(e.getMessage()).contains("axisOrderPolicy"));
        }
    }

    /** ProjJsonReader.readDefinition, "PROJJSON must be a JSON object". */
    @Test
    public void aRootThatIsNotAnObjectIsRefused() {
        assertRefusal("an array at the root", "PROJJSON must be a JSON object", "[]");
    }

    /** ProjJsonReader.crs, "PROJJSON object has no \"type\" member". */
    @Test
    public void anObjectWithoutATypeIsRefused() {
        assertRefusal("a typeless object", "PROJJSON object has no \"type\" member", "{}");
    }

    /** ProjJsonReader.crs, the unsupported-type branch. */
    @Test
    public void aTypeThatIsNotACoordinateReferenceSystemIsRefused() {
        assertRefusal("a non-CRS type",
                "PROJJSON type \"Ellipsoid\" is not a coordinate reference system",
                "{\"type\":\"Ellipsoid\"}");
    }

    // ------------------------------------------------------- ProjJsonReader: the CRS kinds

    /** ProjJsonReader.projected, "a ProjectedCRS needs a \"base_crs\"". */
    @Test
    public void aProjectedCrsWithoutABaseCrsIsRefused() {
        assertRefusal("a base-less ProjectedCRS", "a ProjectedCRS needs a \"base_crs\"",
                "{\"type\":\"ProjectedCRS\",\"name\":\"p\"}");
    }

    /** ProjJsonReader.conversion, "a ProjectedCRS needs a \"conversion\"". */
    @Test
    public void aProjectedCrsWithoutAConversionIsRefused() {
        assertRefusal("a conversion-less ProjectedCRS", "a ProjectedCRS needs a \"conversion\"",
                projectedWithConversion(null));
    }

    /** ProjJsonReader.conversion, "conversion ... has no \"method\"". */
    @Test
    public void aConversionWithoutAMethodIsRefused() {
        assertRefusal("a method-less conversion", "conversion \"c\" has no \"method\"",
                projectedWithConversion("{\"name\":\"c\"}"));
    }

    /** ProjJsonReader.conversion, "parameter ... has no value". */
    @Test
    public void aConversionParameterWithoutAValueIsRefused() {
        assertRefusal("a valueless parameter", "parameter \"q\" has no value",
                projectedWithConversion("{\"name\":\"c\",\"method\":{\"name\":\"m\"},"
                        + "\"parameters\":[{\"name\":\"q\"}]}"));
    }

    /** ProjJsonReader.compound, "a CompoundCRS needs \"components\"" — absent, and empty. */
    @Test
    public void aCompoundCrsWithoutComponentsIsRefused() {
        assertRefusal("a component-less CompoundCRS", "a CompoundCRS needs \"components\"",
                "{\"type\":\"CompoundCRS\",\"name\":\"c\"}");
        assertRefusal("an empty components array", "a CompoundCRS needs \"components\"",
                "{\"type\":\"CompoundCRS\",\"name\":\"c\",\"components\":[]}");
    }

    /** ProjJsonReader.bound, "a BoundCRS needs a \"source_crs\"". */
    @Test
    public void aBoundCrsWithoutASourceCrsIsRefused() {
        assertRefusal("a source-less BoundCRS", "a BoundCRS needs a \"source_crs\"",
                "{\"type\":\"BoundCRS\",\"name\":\"b\"}");
    }

    /** ProjJsonReader.helmert, "a BoundCRS transformation needs \"parameters\"". */
    @Test
    public void aBoundCrsTransformationWithoutParametersIsRefused() {
        assertRefusal("a parameter-less transformation",
                "a BoundCRS transformation needs \"parameters\"",
                "{\"type\":\"BoundCRS\",\"name\":\"b\",\"source_crs\":" + MINIMAL_GEOGRAPHIC
                        + ",\"transformation\":{\"name\":\"t\"}}");
    }

    // ------------------------------------------------ ProjJsonReader: datum and ellipsoid

    /** ProjJsonReader.datum, "a geodetic CRS needs a \"datum\" or a \"datum_ensemble\"". */
    @Test
    public void aGeodeticCrsWithoutADatumIsRefused() {
        assertRefusal("a datum-less GeographicCRS",
                "a geodetic CRS needs a \"datum\" or a \"datum_ensemble\"",
                "{\"type\":\"GeographicCRS\",\"name\":\"g\"}");
    }

    /** ProjJsonReader.datum, "datum ... has no \"ellipsoid\"" — and it names the datum. */
    @Test
    public void aDatumWithoutAnEllipsoidIsRefused() {
        assertRefusal("an ellipsoid-less datum", "datum \"d\" has no \"ellipsoid\"",
                "{\"type\":\"GeographicCRS\",\"name\":\"g\",\"datum\":{\"name\":\"d\"}}");
    }

    /** ProjJsonReader.ellipsoid, "has no \"semi_major_axis\" or \"radius\"". */
    @Test
    public void anEllipsoidWithNeitherASemiMajorAxisNorARadiusIsRefused() {
        assertRefusal("a sizeless ellipsoid",
                "ellipsoid \"e\" has no \"semi_major_axis\" or \"radius\"",
                geographicWithEllipsoid("{\"name\":\"e\"}"));
    }

    /** ProjJsonReader.ellipsoid, "has neither \"inverse_flattening\" nor \"semi_minor_axis\"". */
    @Test
    public void anEllipsoidWithNoSecondDefiningParameterIsRefused() {
        assertRefusal("a half-defined ellipsoid",
                "ellipsoid \"e\" has neither \"inverse_flattening\" nor \"semi_minor_axis\"",
                geographicWithEllipsoid("{\"name\":\"e\",\"semi_major_axis\":6378137}"));
    }

    /** ProjJsonReader.ellipsoid, "\"semi_minor_axis\" has no value". */
    @Test
    public void aSemiMinorAxisMeasureWithoutAValueIsRefused() {
        assertRefusal("a valueless semi_minor_axis", "\"semi_minor_axis\" has no value",
                geographicWithEllipsoid("{\"name\":\"e\",\"semi_major_axis\":6378137,"
                        + "\"semi_minor_axis\":{\"unit\":\"metre\"}}"));
    }

    /** ProjJsonReader.primeMeridian, "prime meridian ... has no \"longitude\"". */
    @Test
    public void aPrimeMeridianWithoutALongitudeIsRefused() {
        assertRefusal("a longitude-less prime meridian",
                "prime meridian \"p\" has no \"longitude\"",
                geographicWithPrimeMeridian("{\"name\":\"p\"}"));
    }

    /** ProjJsonReader.primeMeridian, "prime meridian longitude has no value". */
    @Test
    public void aPrimeMeridianLongitudeMeasureWithoutAValueIsRefused() {
        assertRefusal("a valueless prime meridian longitude",
                "prime meridian longitude has no value",
                geographicWithPrimeMeridian(
                        "{\"name\":\"p\",\"longitude\":{\"unit\":\"degree\"}}"));
    }

    // ------------------------------------------------ ProjJsonReader: coordinate system

    /** ProjJsonReader.coordinateSystem, "a CRS needs a \"coordinate_system\"". */
    @Test
    public void aCrsWithoutACoordinateSystemIsRefused() {
        assertRefusal("a coordinate-system-less CRS", "a CRS needs a \"coordinate_system\"",
                "{\"type\":\"EngineeringCRS\",\"name\":\"x\"}");
    }

    /** ProjJsonReader.coordinateSystem, "a coordinate system needs an \"axis\" array". */
    @Test
    public void aCoordinateSystemWithoutAnAxisArrayIsRefused() {
        assertRefusal("an axis-less coordinate system",
                "a coordinate system needs an \"axis\" array",
                "{\"type\":\"GeographicCRS\",\"name\":\"g\",\"datum\":{\"name\":\"d\","
                        + "\"ellipsoid\":{\"name\":\"e\",\"semi_major_axis\":6378137,"
                        + "\"inverse_flattening\":298.257223563}},"
                        + "\"coordinate_system\":{\"subtype\":\"ellipsoidal\"}}");
    }

    // ------------------------------------------------------- ProjJsonReader: units and ids

    /**
     * ProjJsonReader.unit, the short-name branch. PROJJSON allows exactly three bare names;
     * anything else has to be an object with a conversion factor, and the message has to say so,
     * because a reader that silently guessed a factor would corrupt every coordinate it later
     * produced.
     */
    @Test
    public void aUnitShortNameOutsideProjJsonsThreeIsRefused() {
        assertRefusal("an unknown unit short name",
                "unit \"foot\" is not one of PROJJSON's three short names",
                geographicWithAxis("{\"name\":\"a\",\"direction\":\"east\",\"unit\":\"foot\"}"));
    }

    /** ProjJsonReader.unit, "unit ... has no \"conversion_factor\"". */
    @Test
    public void aUnitObjectWithoutAConversionFactorIsRefused() {
        assertRefusal("a factorless unit object", "unit \"foot\" has no \"conversion_factor\"",
                geographicWithAxis("{\"name\":\"a\",\"direction\":\"east\","
                        + "\"unit\":{\"type\":\"LinearUnit\",\"name\":\"foot\"}}"));
    }

    /** ProjJsonReader.identifier, "an \"id\" needs an \"authority\" and a \"code\"". */
    @Test
    public void anIdentifierMissingItsCodeIsRefused() {
        assertRefusal("a code-less id", "an \"id\" needs an \"authority\" and a \"code\"",
                withExtraMember("\"id\":{\"authority\":\"EPSG\"}"));
    }

    /** ProjJsonReader.required, "missing numeric member" — and it names which one. */
    @Test
    public void aBoundingBoxMissingACornerIsRefused() {
        assertRefusal("an incomplete bbox", "missing numeric member \"south_latitude\"",
                withExtraMember("\"bbox\":{}"));
    }

    // ---------------------------------------- ProjJsonReader: the four type-checking helpers

    /** ProjJsonReader.string, "member ... must be a string". */
    @Test
    public void aMemberThatMustBeAStringButIsNotIsRefused() {
        assertRefusal("a numeric name", "member \"name\" must be a string",
                "{\"type\":\"GeographicCRS\",\"name\":42}");
    }

    /** ProjJsonReader.number, "member ... must be a number". */
    @Test
    public void aMemberThatMustBeANumberButIsNotIsRefused() {
        assertRefusal("a quoted semi-major axis", "member \"semi_major_axis\" must be a number",
                geographicWithEllipsoid("{\"name\":\"e\",\"semi_major_axis\":\"6378137\"}"));
    }

    /** ProjJsonReader.object, "member ... must be an object". */
    @Test
    public void aMemberThatMustBeAnObjectButIsNotIsRefused() {
        assertRefusal("a numeric datum", "member \"datum\" must be an object",
                "{\"type\":\"GeographicCRS\",\"name\":\"g\",\"datum\":42}");
    }

    /** ProjJsonReader.array, "member ... must be an array". */
    @Test
    public void aMemberThatMustBeAnArrayButIsNotIsRefused() {
        assertRefusal("an object where components should be", "member \"components\" must be an "
                        + "array",
                "{\"type\":\"CompoundCRS\",\"name\":\"c\",\"components\":{}}");
    }

    /** ProjJsonReader.asObject, "... must be a JSON object": an array element of the wrong kind. */
    @Test
    public void anArrayElementThatMustBeAnObjectButIsNotIsRefused() {
        assertRefusal("a number among the components", "component must be a JSON object",
                "{\"type\":\"CompoundCRS\",\"name\":\"c\",\"components\":[1]}");
    }

    // ------------------------------------------------------------------------------ helpers

    /** {@link #MINIMAL_GEOGRAPHIC} with one more member, for the metadata sites. */
    private static String withExtraMember(String member) {
        return MINIMAL_GEOGRAPHIC.substring(0, MINIMAL_GEOGRAPHIC.length() - 1)
                + "," + member + "}";
    }

    /**
     * Reads {@code json}, requires a {@link WktParseException}, and requires its message to contain
     * {@code expected}.
     *
     * <p>Both halves matter. The type is what callers catch — anything else, notably a
     * {@link NullPointerException} or a {@link ClassCastException} from a half-finished check, is a
     * defect however loudly it fails. The message is the only thing that tells whoever wrote the
     * document what to change.
     */
    private static void assertRefusal(String what, String expected, String json) {
        String message;
        try {
            new ProjJsonReader().readDefinition(json);
            throw new AssertionError("a malformed document was accepted where " + what
                    + " should have been refused: " + json);
        } catch (WktParseException e) {
            message = e.getMessage();
        } catch (RuntimeException e) {
            throw new AssertionError(what + " threw " + e.getClass().getName() + " rather than "
                    + "WktParseException, so it escapes every caller that catches the documented "
                    + "exception: " + e);
        }
        assertNotNull("the refusal for " + what + " carries no message at all", message);
        assertTrue("the refusal for " + what + " does not name the problem, so a caller cannot "
                        + "tell what to fix; expected it to mention \"" + expected
                        + "\" but it said: " + message,
                message.contains(expected));
    }
}
