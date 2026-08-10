/*
 * Copyright 2025, PROJ4J contributors
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
package org.locationtech.proj4j.geoapi;

import java.awt.geom.Point2D;
import java.util.Arrays;
import org.junit.Test;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.operation.MathTransform2D;
import org.opengis.referencing.operation.TransformException;

import static org.junit.Assert.*;


/**
 * Tests the four array methods of {@link TransformWrapper2D} and {@link TransformWrapper3D}
 * against the single-point method they inherit from {@link TransformWrapper}.
 *
 * <p>Those four methods are near-copies of each other in each of the two classes, which makes them
 * a standing invitation to factor them together. This test is the safety net for that work: the
 * expected values come from a code path that shares nothing with the array methods, so it fails if
 * a shared helper drops a cast, gets the number of ordinates per tuple wrong, or loses the copy
 * that protects an in-place transform whose target range overlaps its source range.</p>
 */
public final class TransformArrayOverloadsTest {
    /**
     * Longitudes and latitudes of the test points, all well inside the domain of EPSG:27700.
     */
    private static final float[] LONGITUDE_LATITUDE = {
        -2.00f, 52.0f,
        -0.10f, 51.5f,
        -3.50f, 55.0f,
        -1.50f, 53.8f
    };

    /**
     * Ellipsoidal heights to pair with {@link #LONGITUDE_LATITUDE} in the three-dimensional case.
     */
    private static final float[] HEIGHTS = {
        0f, 125.5f, -30.25f, 1500f
    };

    /**
     * Creates a new test case.
     */
    public TransformArrayOverloadsTest() {
    }

    /**
     * {@return a wrapper of the given number of dimensions over a transform from EPSG:4326 to EPSG:27700}.
     * That pair is used rather than a plain projection because OSGB36 carries a seven-parameter datum
     * shift, so the height ordinate reaches the horizontal result. A three-dimensional method that
     * mishandles the height therefore shows up in the easting and northing, not only in the height.
     */
    private static TransformWrapper wrapper(final int dimension) {
        final CRSFactory factory = new CRSFactory();
        return TransformWrapper.wrap(new BasicCoordinateTransform(
                factory.createFromName("EPSG:4326"),
                factory.createFromName("EPSG:27700")), dimension >= 3);
    }

    /**
     * {@return the source coordinate tuples for a wrapper of the given number of dimensions}.
     */
    private static float[] sourceTuples(final int dimension) {
        final int numPts = LONGITUDE_LATITUDE.length / 2;
        final float[] tuples = new float[numPts * dimension];
        for (int i = 0; i < numPts; i++) {
            tuples[i * dimension    ] = LONGITUDE_LATITUDE[i * 2    ];
            tuples[i * dimension + 1] = LONGITUDE_LATITUDE[i * 2 + 1];
            if (dimension >= 3) {
                tuples[i * dimension + 2] = HEIGHTS[i];
            }
        }
        return tuples;
    }

    /**
     * {@return a widening copy of the given tuples}.
     * Widening a {@code float} to a {@code double} is exact, so the two arrays describe the same points.
     */
    private static double[] widen(final float[] tuples) {
        final double[] widened = new double[tuples.length];
        for (int i = 0; i < tuples.length; i++) {
            widened[i] = tuples[i];
        }
        return widened;
    }

    /**
     * {@return a narrowing copy of the given tuples}.
     */
    private static float[] narrow(final double[] tuples) {
        final float[] narrowed = new float[tuples.length];
        for (int i = 0; i < tuples.length; i++) {
            narrowed[i] = (float) tuples[i];
        }
        return narrowed;
    }

    /**
     * Transforms the given tuples one at a time through the {@code DirectPosition} method, which is
     * a different code path than the array methods under test and is therefore usable as a witness.
     *
     * @param  tr         the wrapper to exercise
     * @param  tuples     the source tuples
     * @param  dimension  the number of ordinates per tuple
     * @return the expected results of the array methods, in the same layout as {@code tuples}
     * @throws TransformException if a point cannot be transformed
     */
    private static double[] expected(final TransformWrapper tr, final float[] tuples, final int dimension)
            throws TransformException
    {
        final double[] results = new double[tuples.length];
        for (int i = 0; i < tuples.length; i += dimension) {
            // A two-ordinate ProjCoordinate leaves z at NaN, which is what the 2D wrapper also does.
            final ProjCoordinate src = (dimension >= 3)
                    ? new ProjCoordinate(tuples[i], tuples[i + 1], tuples[i + 2])
                    : new ProjCoordinate(tuples[i], tuples[i + 1]);
            final DirectPosition pos = tr.transform(Wrappers.geoapi(src), null);
            for (int j = 0; j < dimension; j++) {
                results[i + j] = pos.getOrdinate(j);
            }
        }
        return results;
    }

    /**
     * Verifies the four array methods of a wrapper of the given number of dimensions.
     */
    private static void verifyOverloads(final int dimension) throws TransformException {
        final TransformWrapper tr = wrapper(dimension);
        final float[] floatSrc = sourceTuples(dimension);
        final double[] doubleSrc = widen(floatSrc);
        final int length = floatSrc.length;
        final int numPts = length / dimension;
        final double[] expected = expected(tr, floatSrc, dimension);
        final float[] expectedAsFloat = narrow(expected);
        assertTrue("Test points shall project to finite values.", Double.isFinite(expected[0]));

        final double[] doubleDst = new double[length];
        tr.transform(doubleSrc, 0, doubleDst, 0, numPts);
        assertArrayEquals("double source, double target", expected, doubleDst, 0);

        Arrays.fill(doubleDst, Double.NaN);
        tr.transform(floatSrc, 0, doubleDst, 0, numPts);
        assertArrayEquals("float source, double target", expected, doubleDst, 0);

        final float[] floatDst = new float[length];
        tr.transform(doubleSrc, 0, floatDst, 0, numPts);
        assertArrayEquals("double source, float target", expectedAsFloat, floatDst, 0);

        Arrays.fill(floatDst, Float.NaN);
        tr.transform(floatSrc, 0, floatDst, 0, numPts);
        assertArrayEquals("float source, float target", expectedAsFloat, floatDst, 0);

        // Same array, same offset: no copy is needed and none should be made.
        final double[] inPlace = widen(floatSrc);
        tr.transform(inPlace, 0, inPlace, 0, numPts);
        assertArrayEquals("in-place, no offset", expected, inPlace, 0);
    }

    /**
     * Verifies an in-place transform whose target range overlaps its source range. Without the
     * defensive copy made by the wrapper, the result of one tuple overwrites the input of the next.
     */
    private static void verifyOverlappingInPlace(final int dimension) throws TransformException {
        final TransformWrapper tr = wrapper(dimension);
        final float[] floatSrc = sourceTuples(dimension);
        final int length = floatSrc.length;
        final int numPts = length / dimension;
        final double[] expected = expected(tr, floatSrc, dimension);
        final float[] expectedAsFloat = narrow(expected);

        final double[] doubles = Arrays.copyOf(widen(floatSrc), length + dimension);
        tr.transform(doubles, 0, doubles, dimension, numPts);
        assertArrayEquals("overlapping doubles", expected,
                Arrays.copyOfRange(doubles, dimension, doubles.length), 0);

        final float[] floats = Arrays.copyOf(floatSrc, length + dimension);
        tr.transform(floats, 0, floats, dimension, numPts);
        assertArrayEquals("overlapping floats", expectedAsFloat,
                Arrays.copyOfRange(floats, dimension, floats.length), 0);
    }

    /**
     * Tests the array methods of the two-dimensional wrapper.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testOverloads2D() throws TransformException {
        verifyOverloads(2);
    }

    /**
     * Tests the array methods of the three-dimensional wrapper.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testOverloads3D() throws TransformException {
        verifyOverloads(3);
    }

    /**
     * Tests an overlapping in-place transform with the two-dimensional wrapper.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testOverlappingInPlace2D() throws TransformException {
        verifyOverlappingInPlace(2);
    }

    /**
     * Tests an overlapping in-place transform with the three-dimensional wrapper.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testOverlappingInPlace3D() throws TransformException {
        verifyOverlappingInPlace(3);
    }

    /**
     * Tests the {@link Point2D} method of the two-dimensional wrapper against the array method.
     * The GeoAPI conformance suite does not exercise that method, so this is its only coverage.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testPoint2D() throws TransformException {
        final MathTransform2D tr = (MathTransform2D) wrapper(2);
        final double[] source = {-2.0, 52.0};
        final double[] expected = new double[source.length];
        tr.transform(source, 0, expected, 0, 1);
        assertTrue("Test point shall project to a finite value.", Double.isFinite(expected[0]));

        final Point2D created = tr.transform(new Point2D.Double(source[0], source[1]), null);
        assertEquals("created target, x", expected[0], created.getX(), 0);
        assertEquals("created target, y", expected[1], created.getY(), 0);

        final Point2D supplied = new Point2D.Double();
        assertSame(supplied, tr.transform(new Point2D.Double(source[0], source[1]), supplied));
        assertEquals("supplied target, x", expected[0], supplied.getX(), 0);
        assertEquals("supplied target, y", expected[1], supplied.getY(), 0);

        // The caller is allowed to pass the same point as both the source and the target.
        final Point2D inPlace = new Point2D.Double(source[0], source[1]);
        assertSame(inPlace, tr.transform(inPlace, inPlace));
        assertEquals("in-place, x", expected[0], inPlace.getX(), 0);
        assertEquals("in-place, y", expected[1], inPlace.getY(), 0);
    }

    /**
     * Tests the two wrappers against each other. The two-dimensional one never writes the height
     * ordinate, so PROJ4J sees the NaN that {@link ProjCoordinate} leaves there and reads it as
     * "no height supplied"; the three-dimensional one must produce the same easting and northing,
     * bit for bit, when the caller supplies that same NaN. A supplied height of 1000 metres must
     * on the other hand move the result, which is what stops the two loop bodies from being folded
     * into one that ignores the third ordinate.
     *
     * @throws TransformException if a point cannot be transformed
     */
    @Test
    public void testMissingHeightMatchesTwoDimensional() throws TransformException {
        final double[] src2D = {-2.0, 52.0, -0.1, 51.5};
        final double[] src3D = {-2.0, 52.0, Double.NaN, -0.1, 51.5, Double.NaN};
        final double[] srcAt1000 = {-2.0, 52.0, 1000.0, -0.1, 51.5, 1000.0};
        final double[] res2D = new double[src2D.length];
        final double[] res3D = new double[src3D.length];
        final double[] resAt1000 = new double[srcAt1000.length];
        wrapper(2).transform(src2D, 0, res2D, 0, 2);
        wrapper(3).transform(src3D, 0, res3D, 0, 2);
        wrapper(3).transform(srcAt1000, 0, resAt1000, 0, 2);
        assertTrue("Test points shall project to finite values.", Double.isFinite(res2D[0]));
        assertEquals(res2D[0], res3D[0], 0);
        assertEquals(res2D[1], res3D[1], 0);
        assertEquals(res2D[2], res3D[3], 0);
        assertEquals(res2D[3], res3D[4], 0);
        assertNotEquals("The height shall reach the datum shift.", res3D[0], resAt1000[0], 0);
        assertNotEquals("The height shall reach the datum shift.", res3D[1], resAt1000[1], 0);
    }
}
