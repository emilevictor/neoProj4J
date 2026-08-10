/*******************************************************************************
 * Copyright 2026 Proj4J contributors
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
package org.locationtech.proj4j.units;

import java.lang.reflect.Field;
import java.text.NumberFormat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link DegreeUnit} renders degrees sexagesimally, and must keep doing so.
 *
 * <h2>The trap this pins</h2>
 *
 * <p>The three {@code format} overloads in {@link DegreeUnit} are character-for-character
 * identical to the ones in {@link Unit}, which is why a copy-paste detector flags them and why
 * they look like deletable overrides. They are not. Both classes declare a static field named
 * {@code format} — {@code Unit.format} is a decimal {@link NumberFormat} ({@code Unit.java:41}),
 * {@code DegreeUnit.format} is an {@link AngleFormat} on the {@code "DdM"} pattern
 * ({@code DegreeUnit.java:24}) — and Java resolves a field access against the <em>static</em> type
 * of the enclosing class at compile time. The same source text therefore reads a different object
 * in each class, which {@code javap} shows as {@code getstatic Field format:Ljava/text/NumberFormat;}
 * against {@code getstatic Field format:Lorg/locationtech/proj4j/units/AngleFormat;}.
 *
 * <p>This is shadowing, not overriding. No helper method is dispatched dynamically here, so the
 * usual intuition that identical bodies in a subclass are redundant does not apply.
 *
 * <p>Delete the overrides and {@code Units.DEGREES.format(45.5)} silently changes from
 * {@code 45d30 deg} to {@code 45.5 deg}. Before this file existed, nothing in the build noticed:
 * the only in-repo caller is {@code NoAmbientLocaleInCoreTest}'s
 * {@code assertNotNull(Units.DEGREES.format(1.5))}, which passes either way.
 */
public class DegreeUnitFormatTest {

    /** Deleting {@code DegreeUnit.format(double)} turns this into {@code "45.5 deg"}. */
    @Test
    public void degreesFormatAsSexagesimalNotDecimal() {
        assertEquals("Units.DEGREES.format(double) must stay sexagesimal. If this reads "
                + "\"45.5 deg\" then DegreeUnit's format overrides have been deleted as duplicates "
                + "and the shadowed AngleFormat is no longer reached",
                "45d30 deg", Units.DEGREES.format(45.5));
    }

    /** Deleting {@code DegreeUnit.format(double, boolean)} turns these decimal. */
    @Test
    public void theAbbreviationFlagOverloadIsAlsoSexagesimal() {
        assertEquals("45d30 deg", Units.DEGREES.format(45.5, true));
        assertEquals("45d30", Units.DEGREES.format(45.5, false));
    }

    /** Deleting {@code DegreeUnit.format(double, double, boolean)} turns these decimal. */
    @Test
    public void theCoordinatePairOverloadsAreAlsoSexagesimal() {
        assertEquals("45d30/-122d15 deg", Units.DEGREES.format(45.5, -122.25, true));
        assertEquals("45d30/-122d15", Units.DEGREES.format(45.5, -122.25, false));

        // format(double, double) is deliberately NOT overridden in DegreeUnit. It is inherited
        // from Unit and still produces sexagesimal output only because its delegation to
        // format(x, y, true) is a virtual call. That asymmetry with the other three overloads is
        // correct, and this assertion is what says so.
        assertEquals("45d30/-122d15 deg", Units.DEGREES.format(45.5, -122.25));
    }

    /**
     * The base class renders the same value as decimal. Holding both sides at once is what makes
     * the assertions above a statement about the shadowing rather than about one arbitrary string.
     */
    @Test
    public void theBaseClassRendersTheSameValueAsDecimal() {
        Unit plainDegree = new Unit("degree", "degrees", "deg", 1);
        assertEquals("45.5 deg", plainDegree.format(45.5));
    }

    /**
     * The mechanism itself, so that whoever next reads those identical bodies finds the
     * explanation attached to a failure rather than having to disassemble the class to find it.
     */
    @Test
    public void theFormatFieldIsShadowedNotInherited() throws NoSuchFieldException {
        assertSame("Unit.format must stay a decimal NumberFormat",
                NumberFormat.class, Unit.class.getDeclaredField("format").getType());

        Field derived = DegreeUnit.class.getDeclaredField("format");
        assertSame("DegreeUnit.format must stay an AngleFormat. This field is the only reason the "
                + "identical format() bodies mean different things; remove it and degrees collapse "
                + "to decimal", AngleFormat.class, derived.getType());

        assertEquals("DegreeUnit's AngleFormat must stay on the sexagesimal pattern",
                "DdM", AngleFormat.ddmmssPattern);
    }
}
