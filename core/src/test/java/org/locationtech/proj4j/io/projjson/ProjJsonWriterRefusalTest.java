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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.io.wkt.CrsDefinition;
import org.locationtech.proj4j.io.wkt.WktParseException;
import org.locationtech.proj4j.io.wkt.WktReader;

/**
 * The other half of the malformed-input corpus: the places the PROJJSON <em>writer</em> gives up,
 * and what it says when it does.
 *
 * <p>{@link CrsDefinition} is public and mutable, so a caller can assemble a definition that is
 * missing the parts PROJJSON requires and hand it straight to {@link ProjJsonWriter#write}. Every
 * such gap has a check; before this file only the nesting guard had a test, so the rest could have
 * been replaced by a {@link NullPointerException} without anything noticing. Each test below builds
 * the smallest half-built definition that reaches one check and asserts the message names the
 * missing part.
 *
 * <p>Two throw sites in this package are deliberately left untested, because no input reaches them:
 * <ul>
 *   <li>{@code ProjJsonWriter}'s {@code default:} branch, "cannot write a ... CRS as PROJJSON". The
 *       switch above it has a case for all seven {@link CrsDefinition.Kind} constants, so the
 *       branch can only run if an eighth is added — at which point the guard is exactly what
 *       should fire. A test could only reach it by mocking an enum, which would assert nothing
 *       about any document.</li>
 *   <li>{@code JsonLimits}'s private constructor, {@code throw new AssertionError("no instances")}.
 *       The class is final with only static members; the constructor is reachable only by
 *       reflection, and a test that called it would pin a Java language rule rather than any
 *       behaviour of the reader.</li>
 * </ul>
 *
 * <p>Delete this file and a definition with a missing datum, conversion, coordinate system or
 * transformation can start failing with an unrelated exception type, or with no explanation of
 * which part was missing.
 */
public class ProjJsonWriterRefusalTest {

    private static final String WGS84_WKT =
            "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],"
                    + "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]";

    /** A complete, writable definition; each test below removes exactly one part of it. */
    private static CrsDefinition geographic() {
        return new WktReader().readDefinition(WGS84_WKT);
    }

    /**
     * The control: the definition the tests below mutilate must itself write cleanly, or every one
     * of them would pass for the wrong reason.
     */
    @Test
    public void theBaseDefinitionIsItselfWritable() {
        String json = new ProjJsonWriter().write(geographic());
        assertNotNull("the base definition must write, or every test below proves nothing", json);
        assertTrue(json, json.contains("\"GeographicCRS\""));
    }

    // ------------------------------------------------------------------- ProjJsonWriter

    /** ProjJsonWriter.toMap, "CRS definition is null or has no kind": both halves of the check. */
    @Test
    public void aNullOrKindlessDefinitionIsRefused() {
        assertRefusal("a null definition", "CRS definition is null or has no kind", null);
        assertRefusal("a definition with no kind", "CRS definition is null or has no kind",
                new CrsDefinition());
    }

    /** ProjJsonWriter.datum, "a geodetic CRS needs a datum". */
    @Test
    public void aGeodeticDefinitionWithNoDatumIsRefused() {
        CrsDefinition def = geographic();
        def.setDatum(null);
        assertRefusal("a datum-less geographic CRS", "a geodetic CRS needs a datum", def);
    }

    /** ProjJsonWriter.coordinateSystem, "a CRS needs a coordinate system". */
    @Test
    public void aDefinitionWithNoCoordinateSystemIsRefused() {
        CrsDefinition def = geographic();
        def.setCoordinateSystem(null);
        assertRefusal("a coordinate-system-less CRS", "a CRS needs a coordinate system", def);
    }

    /** ProjJsonWriter.conversion, "a projected CRS needs a conversion". */
    @Test
    public void aProjectedDefinitionWithNoConversionIsRefused() {
        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.PROJECTED);
        def.setName("p");
        def.setBaseCrs(geographic());
        assertRefusal("a conversion-less projected CRS", "a projected CRS needs a conversion", def);
    }

    /** ProjJsonWriter.transformation, "a bound CRS needs transformation parameters". */
    @Test
    public void aBoundDefinitionWithNoTransformationIsRefused() {
        CrsDefinition def = new CrsDefinition();
        def.setKind(CrsDefinition.Kind.BOUND);
        def.setName("b");
        def.setBaseCrs(geographic());
        assertRefusal("a transformation-less bound CRS",
                "a bound CRS needs transformation parameters", def);
    }

    // ------------------------------------------------------------------- Json and JsonNumber

    /**
     * Json.write, "cannot write a ... as JSON". Only reachable from inside the package, because
     * {@link ProjJsonWriter} only ever builds maps of strings, numbers, lists and maps — but the
     * guard is what makes that true by check rather than by inspection, and it has to name the
     * offending class or the report is useless.
     */
    @Test
    public void aValueJsonHasNoSpellingForIsRefused() {
        try {
            Json.write(new Object());
            throw new AssertionError("Json.write accepted a value it has no spelling for, so it "
                    + "would have emitted a Java toString() into a JSON document");
        } catch (WktParseException e) {
            assertTrue("the refusal must name the class that cannot be written: " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains("cannot write a java.lang.Object"));
        }
    }

    /**
     * JsonNumber.format, "cannot write the non-finite value ... as JSON". JSON has no spelling for
     * NaN or an infinity, so emitting one would produce a document no parser could read back;
     * refusing is the only honest option, and the value has to appear in the message to be
     * diagnosable.
     */
    @Test
    public void aNonFiniteNumberIsRefused() {
        assertNonFiniteRefused(Double.NaN, "NaN");
        assertNonFiniteRefused(Double.POSITIVE_INFINITY, "Infinity");
        assertNonFiniteRefused(Double.NEGATIVE_INFINITY, "-Infinity");
    }

    // ------------------------------------------------------------------------------ helpers

    private static void assertNonFiniteRefused(double value, String expected) {
        try {
            JsonNumber.format(value);
            throw new AssertionError("JsonNumber.format emitted " + value + " into JSON, which has "
                    + "no spelling for it; the document would not read back");
        } catch (WktParseException e) {
            assertTrue("the refusal must quote the offending value: " + e.getMessage(),
                    String.valueOf(e.getMessage()).contains(expected));
        }
    }

    /**
     * Writes {@code def}, requires a {@link WktParseException} and requires the message to name the
     * missing part. A different exception type is a defect even though it also fails: callers are
     * documented to catch {@link WktParseException}.
     */
    private static void assertRefusal(String what, String expected, CrsDefinition def) {
        String message;
        try {
            new ProjJsonWriter().write(def);
            throw new AssertionError("a half-built definition was written where " + what
                    + " should have been refused");
        } catch (WktParseException e) {
            message = e.getMessage();
        } catch (RuntimeException e) {
            throw new AssertionError(what + " threw " + e.getClass().getName() + " rather than "
                    + "WktParseException, so it escapes every caller that catches the documented "
                    + "exception: " + e);
        }
        assertNotNull("the refusal for " + what + " carries no message at all", message);
        assertTrue("the refusal for " + what + " does not name the missing part, so a caller "
                        + "cannot tell what to supply; expected it to mention \"" + expected
                        + "\" but it said: " + message,
                message.contains(expected));
    }
}
