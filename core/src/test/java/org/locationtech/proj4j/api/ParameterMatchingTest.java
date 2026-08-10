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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

/**
 * How a PROJ parameter is looked up in an already-parsed list, which is one rule shared by
 * {@link Proj}, {@link Crs} and {@link LegacyAdapters} rather than a copy each.
 *
 * <p>The three of them have to agree. If {@link Proj#createCrs(String)} recognised a parameter that
 * {@link LegacyAdapters#fromLegacy} did not, the same CRS would report one axis order when built
 * from text and another when adapted from an object, and only one of the two would be right.
 */
public class ParameterMatchingTest {

    /** Both entry points read the same explicit {@code +axis=} out of the same parameters. */
    @Test
    public void projAndLegacyAdapterAgreeOnAnExplicitAxis() {
        String definition = "+proj=longlat +datum=WGS84 +axis=neu +no_defs";
        Crs fromText = Proj.createCrs(definition);
        Crs adapted = LegacyAdapters.fromLegacy(
                new CRSFactory().createFromParameters("test", definition), null);

        assertEquals("neu", fromText.axisOrder());
        assertEquals("neu", adapted.axisOrder());
        assertTrue(fromText.isAxisOrderAuthoritative());
        assertTrue("an explicit +axis= is declared, not inferred, however the CRS was built",
                adapted.isAxisOrderAuthoritative());
    }

    /**
     * The spelling without a leading {@code +}, which is how the shipped dictionaries write every
     * parameter. Matching only on {@code "+axis="} finds nothing here and silently reports
     * east-north-up.
     */
    @Test
    public void aParameterWithoutItsPlusIsStillFound() {
        CoordinateReferenceSystem legacy = new CRSFactory().createFromParameters("test",
                new String[] {"proj=longlat", "datum=WGS84", "axis=neu", "no_defs"});
        Crs adapted = LegacyAdapters.fromLegacy(legacy, null);

        assertEquals("neu", adapted.axisOrder());
        assertTrue(adapted.isLatitudeFirst());
        assertTrue(adapted.isAxisOrderAuthoritative());
    }

    /**
     * A different parameter that merely starts with the same letters is not it. {@code +axis=} has
     * to be matched up to and including its {@code =}, or a CRS carrying some other {@code axis*}
     * key would be read as latitude-first and every coordinate through it would be transposed.
     */
    @Test
    public void aLongerKeyIsNotMistakenForTheOneBeingLookedFor() {
        String definition = "+proj=longlat +datum=WGS84 +axistype=whatever +no_defs";
        Crs fromText = Proj.createCrs(definition);
        Crs adapted = LegacyAdapters.fromLegacy(
                new CRSFactory().createFromParameters("test", definition), null);

        assertEquals("enu", fromText.axisOrder());
        assertEquals("enu", adapted.axisOrder());
        assertFalse(fromText.isAxisOrderAuthoritative());
        assertFalse(adapted.isAxisOrderAuthoritative());
    }
}
