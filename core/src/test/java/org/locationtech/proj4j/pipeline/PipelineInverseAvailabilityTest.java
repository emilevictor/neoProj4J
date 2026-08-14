/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.proj.Projection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The two engines must agree about whether a projection can be inverted.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code Cs2csOperator} asked {@code Projection.hasInverse()}; {@code BasicCoordinateTransform}
 * asked "is there a {@code projectInverse} implementation". Those are different questions, and
 * {@code KrovakProjection} and {@code NewZealandMapGridProjection} are exactly where they diverge:
 * both implement {@code projectInverse}, neither declares {@code hasInverse()}, and upstream
 * assigns each a {@code P->inv} unconditionally ({@code 9.8.1:src/projections/krovak.cpp:329},
 * {@code nzmg.cpp:123}).
 *
 * <p>Nothing selects between the engines except the parameter list, so the disagreement was
 * invisible until a definition carried a key that forces the pipeline route. {@code +pm=} is such a
 * key — the prime-meridian shift is one of the hidden cs2cs-emulation steps, which live in the
 * pipeline engine — so:
 *
 * <pre>
 *   +proj=krovak +lat_0=49.5 +lon_0=42.5 +k=0.9999 +x_0=0 +y_0=0 +ellps=bessel +pm=ferro
 * </pre>
 *
 * <p>reported {@code PipelineDefinitionException: pipeline is not invertible} while the same
 * definition <em>without</em> {@code +pm} round-tripped through the other engine without complaint.
 * That was {@code gie/builtins.gie:137:1}, and the only regression against proj4j 1.4.3 in the
 * whole 7,923-assertion corpus. {@code +pm} was never the cause: it neither reaches nor needs the
 * projection's inverse, it only decides which engine runs.
 *
 * <h2>Controls</h2>
 *
 * <p>{@link #theTwoPredicatesStillDisagree()} is the non-vacuity guard: it asserts that
 * {@code hasInverse()} is still {@code false} for both classes while
 * {@code hasInverseImplementation()} is {@code true}. Should someone declare
 * {@code hasInverse()} on them, the two questions would coincide, every other test here would pass
 * for a reason unrelated to the fix, and this one fails and says so.
 *
 * <p>{@link #aGenuinelyForwardOnlyProjectionIsStillRefused()} is the negative control: the fix must
 * not have made everything invertible. {@code +proj=august} has no inverse in proj4j and
 * {@code august.cpp:30} sets {@code P->inv = nullptr}, so refusing it is parity, and it must still
 * be refused through the same route and with the same key.
 *
 * <h2>The wrapper the first fix broke</h2>
 *
 * <p>Moving both engines onto {@code hasInverseImplementation()} was right for the 151 registered
 * projections and wrong for one: {@code +proj=ob_tran}. It holds a child chosen at run time by
 * {@code +o_proj=}, and it declares a {@code projectInverse} of its own, so the hierarchy walk
 * found that method and answered "invertible" whatever the child was. Measured, on
 * {@code +proj=ob_tran +o_proj=august +o_lat_p=45 +o_lon_p=0 +lon_0=0 +ellps=GRS80 +pm=ferro}:
 *
 * <table>
 * <caption>before and after the wrapper fix</caption>
 * <tr><th></th><th>{@code isInvertible()}</th><th>{@code inverse()} raises</th></tr>
 * <tr><td>before this branch</td><td>{@code false}</td>
 *     <td>{@code PipelineDefinitionException}, {@code NO_INVERSE_OP}</td></tr>
 * <tr><td>after the two engines were unified, before the wrapper fix</td><td><b>{@code true}</b>
 *     — the lie</td><td>{@code ProjectionException}, {@code NO_INVERSE_AVAILABLE}</td></tr>
 * <tr><td>now</td><td>{@code false}</td>
 *     <td>{@code PipelineDefinitionException}, {@code NO_INVERSE_OP}</td></tr>
 * </table>
 *
 * <p>No wrong coordinate was ever produced; the refusal simply moved one layer inwards and changed
 * shape. The fix is that {@code ObliqueTransformationProjection} overrides both predicates and
 * forwards each to the child's {@code hasInverseImplementation()}. It is the only class in
 * {@code core} that needs this — every other projection holding a child
 * ({@code GoodeProjection}, {@code InterruptedProjection}, {@code SpilhausProjection},
 * {@code TransverseMercatorProjection}) fixes that child's type at compile time and declares
 * {@code hasInverse()} {@code true} over it, so the two predicates cannot disagree.
 */
public class PipelineInverseAvailabilityTest {

    /** {@code gie/builtins.gie:137}, the EPSG Guidance Note 7-2 point. */
    private static final String KROVAK_FERRO =
            "+proj=krovak +lat_0=49.5 +lon_0=42.5 +k=0.9999 +x_0=0 +y_0=0 +ellps=bessel +pm=ferro";

    /** 16&deg;50'59.179"E, 50&deg;12'32.442"N. */
    private static final double LON = 16.849771944444445;
    private static final double LAT = 50.20901166666667;

    /**
     * {@code proj 9.8.1} and {@code cct 9.8.1} both answer
     * {@code -568990.995437313337 -1050538.630846057087} for this point. The corpus row's own
     * tolerance is 1.1 cm; this is tighter because the forward direction never regressed and a
     * loose bound here would stop describing the arithmetic.
     */
    private static final double[] PROJ_981_FORWARD = {-568990.995437313337, -1050538.630846057087};

    private final Registry registry = new Registry();

    // ------------------------------------------------------------------ the failing row

    /**
     * The regression witness. Before the fix this threw
     * {@code PipelineDefinitionException: pipeline is not invertible}, which the harness scored as
     * a roundtrip deviation of {@code Infinity} mm against an expected 11 mm.
     */
    @Test
    public void krovakWithAPrimeMeridianRoundTripsThroughThePipelineEngine() {
        Pipeline pipeline = new PipelineFactory(registry).create(KROVAK_FERRO);
        assertTrue("krovak has an inverse upstream (krovak.cpp:329) and in proj4j, so a "
                + "single-step pipeline around it must report itself invertible",
                pipeline.isInvertible());

        double[] forward = pipeline.forward(
                new double[] {Math.toRadians(LON), Math.toRadians(LAT), 0, 0});
        assertEquals("easting must match proj 9.8.1", PROJ_981_FORWARD[0], forward[0], 1e-6);
        assertEquals("northing must match proj 9.8.1", PROJ_981_FORWARD[1], forward[1], 1e-6);

        double[] back = pipeline.inverse(new double[] {forward[0], forward[1], 0, 0});
        // Measured 1.07e-14 deg in longitude and exactly 0 in latitude, i.e. under a micron.
        // 1e-11 deg is about a micron at this latitude, so this bound is not merely the gie
        // row's 1.1 cm restated - it would catch an inverse that computed but computed badly.
        assertEquals("longitude must round-trip", LON, Math.toDegrees(back[0]), 1e-11);
        assertEquals("latitude must round-trip", LAT, Math.toDegrees(back[1]), 1e-11);
    }

    /**
     * The same projection reached through the other engine, which never lost the inverse. Both
     * engines must now say the same thing about the same class; that they did not is the whole
     * defect, so asserting the agreement is the point rather than a duplicate of the row above.
     */
    @Test
    public void bothEnginesAgreeThatKrovakIsInvertible() {
        Pipeline withPm = new PipelineFactory(registry).create(KROVAK_FERRO);
        assertTrue("pipeline engine", withPm.isInvertible());

        CRSFactory crsFactory = new CRSFactory();
        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        CoordinateReferenceSystem wgs84 = crsFactory.createFromName("EPSG:4326");
        // Without +pm this definition does not need the emulation steps, so it goes to
        // BasicCoordinateTransform - the route that was passing all along.
        CoordinateReferenceSystem krovak = crsFactory.createFromParameters("krovak",
                "+proj=krovak +lat_0=49.5 +lon_0=42.5 +k=0.9999 +x_0=0 +y_0=0 +ellps=bessel");
        ProjCoordinate projected = ctFactory.createTransform(wgs84, krovak)
                .transform(new ProjCoordinate(LON, LAT), new ProjCoordinate());
        ProjCoordinate back = ctFactory.createTransform(krovak, wgs84)
                .transform(projected, new ProjCoordinate());
        assertEquals("BasicCoordinateTransform longitude", LON, back.x, 1e-9);
        assertEquals("BasicCoordinateTransform latitude", LAT, back.y, 1e-9);
    }

    /** {@code nzmg} is the second class where the two predicates diverge, so it gets the same row. */
    @Test
    public void nzmgWithAPrimeMeridianIsInvertibleToo() {
        Pipeline pipeline = new PipelineFactory(registry)
                .create("+proj=nzmg +ellps=GRS80 +pm=ferro");
        assertTrue("nzmg has an inverse upstream (nzmg.cpp:123)", pipeline.isInvertible());

        // Inside its area of use: New Zealand. A projection probed 12,000 km outside its domain
        // can legitimately fail to invert, which would make this assert the wrong thing.
        double[] forward = pipeline.forward(
                new double[] {Math.toRadians(174.0), Math.toRadians(-41.0), 0, 0});
        double[] back = pipeline.inverse(new double[] {forward[0], forward[1], 0, 0});
        assertEquals(174.0, Math.toDegrees(back[0]), 1e-9);
        assertEquals(-41.0, Math.toDegrees(back[1]), 1e-9);
    }

    // ------------------------------------------------------------------------ controls

    /**
     * Non-vacuity. Every test above would pass for the wrong reason if {@code hasInverse()} were
     * simply declared on these two classes, because the old predicate would then be right by
     * accident. The fix asserted here is that the engines ask the <em>sound</em> question, so the
     * unsound one must still be observably wrong.
     */
    @Test
    public void theTwoPredicatesStillDisagree() {
        for (String name : new String[] {"krovak", "nzmg"}) {
            Projection p = registry.getProjection(name);
            assertFalse(name + " must still not declare hasInverse(), or these tests are vacuous "
                    + "- they exist because the declaration and the implementation disagree",
                    p.hasInverse());
            assertTrue(name + " implements projectInverse, so hasInverseImplementation() must "
                    + "see it", p.hasInverseImplementation());
        }
    }

    /**
     * Negative control: the fix must not have made every projection invertible. Nothing here has
     * an inverse to find, and upstream agrees - {@code august.cpp:30} is a literal
     * {@code P->inv = nullptr}.
     */
    @Test
    public void aGenuinelyForwardOnlyProjectionIsStillRefused() {
        Projection august = registry.getProjection("august");
        assertFalse("august declares no inverse", august.hasInverse());
        assertFalse("and implements none either", august.hasInverseImplementation());

        Pipeline pipeline = new PipelineFactory(registry)
                .create("+proj=august +ellps=GRS80 +pm=ferro");
        assertFalse("a forward-only projection must still make the pipeline one-way",
                pipeline.isInvertible());
        try {
            pipeline.inverse(new double[] {1.0, 1.0, 0, 0});
            fail("inverting a forward-only pipeline must raise, not answer");
        } catch (PipelineDefinitionException expected) {
            assertEquals(PipelineErrorCode.NO_INVERSE_OP, expected.code());
        }
    }

    // ------------------------------------------------------------------ the runtime-chosen child

    /** {@code +proj=ob_tran} around {@code child}, on the {@code +pm} route into this engine. */
    private static String obTran(String child) {
        return "+proj=ob_tran +o_proj=" + child
                + " +o_lat_p=45 +o_lon_p=0 +lon_0=0 +ellps=GRS80 +pm=ferro";
    }

    /**
     * {@code +proj=ob_tran} over a child with no inverse must report itself one-way, and must
     * refuse by the same route and with the same key as any other one-way pipeline.
     *
     * <p>Three children, because one would not distinguish "the wrapper asks the child" from "the
     * wrapper happens to be refused". {@code august.cpp:30}, {@code guyou.cpp:57} and
     * {@code vandg2.cpp:70} all leave {@code P->inv} null, so {@code ob_tran.cpp:292} leaves the
     * wrapper's null too.
     *
     * <p>The exception type and code are the pinned part. Between the two-engine unification and
     * this fix they were {@code ProjectionException} / {@code NO_INVERSE_AVAILABLE}, thrown from
     * inside the projection after the engine had already waved the call through; before and after,
     * they are {@code PipelineDefinitionException} / {@code NO_INVERSE_OP}, thrown by the engine.
     */
    @Test
    public void obTranOverAForwardOnlyChildIsNotInvertible() {
        for (String child : new String[] {"august", "guyou", "vandg2"}) {
            Pipeline pipeline = new PipelineFactory(registry).create(obTran(child));
            assertFalse("+o_proj=" + child + " has no inverse, so neither has the ob_tran "
                    + "around it (ob_tran.cpp:292)", pipeline.isInvertible());

            // The forward direction is unaffected and must keep working - a wrapper that refused
            // both directions would also pass the assert above.
            double[] forward = pipeline.forward(
                    new double[] {Math.toRadians(10), Math.toRadians(20), 0, 0});
            assertTrue("+o_proj=" + child + " must still project forward",
                    isFinite(forward[0]) && isFinite(forward[1]));

            try {
                pipeline.inverse(new double[] {forward[0], forward[1], 0, 0});
                fail("+o_proj=" + child + ": inverting must raise, not answer");
            } catch (PipelineDefinitionException expected) {
                assertEquals("+o_proj=" + child + " must be refused by the engine, with the same "
                        + "code as any other one-way pipeline",
                        PipelineErrorCode.NO_INVERSE_OP, expected.code());
            }
        }
    }

    /**
     * The other half of the fix: it must not be "refuse every {@code ob_tran}". {@code merc} and
     * {@code moll} both have inverses, so the wrapper must have one and it must be right.
     */
    @Test
    public void obTranOverAnInvertibleChildStillRoundTrips() {
        for (String child : new String[] {"merc", "moll", "latlon"}) {
            Pipeline pipeline = new PipelineFactory(registry).create(obTran(child));
            assertTrue("+o_proj=" + child + " has an inverse, so the ob_tran around it must too",
                    pipeline.isInvertible());

            double[] forward = pipeline.forward(
                    new double[] {Math.toRadians(10), Math.toRadians(20), 0, 0});
            double[] back = pipeline.inverse(new double[] {forward[0], forward[1], 0, 0});
            // Measured under 1e-13 deg for all three. 1e-9 deg is about 0.1 mm, tight enough to
            // catch an inverse that runs but computes the wrong rotation.
            assertEquals("+o_proj=" + child + " longitude", 10.0, Math.toDegrees(back[0]), 1e-9);
            assertEquals("+o_proj=" + child + " latitude", 20.0, Math.toDegrees(back[1]), 1e-9);
        }
    }

    /**
     * Non-vacuity for the wrapper rows. The hierarchy walk in
     * {@code Projection.hasInverseImplementation()} answers "yes" the moment it finds a
     * {@code projectInverse} declared below {@code Projection} — so if
     * {@code ObliqueTransformationProjection} ever stopped declaring one, the walk would give the
     * right answer by accident, and
     * {@link #obTranOverAForwardOnlyChildIsNotInvertible()} would pass for a reason unrelated to
     * the override this file exists to protect.
     */
    @Test
    public void theWrapperStillHidesItsChildFromTheHierarchyWalk() throws Exception {
        // The exact lookup hasInverseImplementation() performs.
        Class<?> obTran = Class.forName(
                "org.locationtech.proj4j.proj.ObliqueTransformationProjection");
        obTran.getDeclaredMethod("projectInverse",
                double.class, double.class, ProjCoordinate.class);

        Projection forwardOnly = new CRSFactory()
                .createFromParameters("obtran-august", obTran("august")).getProjection();
        assertFalse("the wrapper must answer for its child, not for its own declared method",
                forwardOnly.hasInverseImplementation());
        assertFalse("and both predicates must agree, or the two engines diverge again",
                forwardOnly.hasInverse());

        Projection invertible = new CRSFactory()
                .createFromParameters("obtran-merc", obTran("merc")).getProjection();
        assertTrue(invertible.hasInverseImplementation());
        assertTrue(invertible.hasInverse());
    }

    /**
     * The wrapper asks the child {@code hasInverseImplementation()}, not {@code hasInverse()}, so
     * it inherits the child's real capability rather than the child's declaration of it.
     * {@code krovak} is the class where those differ, and it is invertible upstream
     * ({@code krovak.cpp:329}) — so {@code +o_proj=krovak} has an inverse, and asking the child's
     * declaration instead would refuse one it has.
     */
    @Test
    public void obTranAsksTheChildTheSoundQuestion() {
        Projection krovak = registry.getProjection("krovak");
        assertFalse("krovak is still the class whose declaration is wrong", krovak.hasInverse());

        Pipeline pipeline = new PipelineFactory(registry).create(obTran("krovak"));
        assertTrue("+o_proj=krovak must be invertible, because krovak is", pipeline.isInvertible());

        double[] forward = pipeline.forward(
                new double[] {Math.toRadians(10), Math.toRadians(20), 0, 0});
        double[] back = pipeline.inverse(new double[] {forward[0], forward[1], 0, 0});
        assertEquals("+o_proj=krovak longitude", 10.0, Math.toDegrees(back[0]), 1e-9);
        assertEquals("+o_proj=krovak latitude", 20.0, Math.toDegrees(back[1]), 1e-9);
    }

    /** {@code Double.isFinite} is Java 8; core targets an older source level in places. */
    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }
}
