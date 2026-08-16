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

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.locationtech.proj4j.gie.GieIoUnits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code +proj=helmert} and {@code +proj=molobadekas} against PROJ 9.8.1.
 *
 * <p>Every expected coordinate below was read from the installed 9.8.1 {@code cct} at
 * twelve decimals, on the definition quoted in the same test. Where this fork
 * deliberately answers something else, both numbers are written down and the reason is
 * on the test.
 */
public class HelmertOperatorTest {

    /** A geocentric point on the WGS84 ellipsoid, reused so the blocks are comparable. */
    private static final double[] GEOCENTRIC = {3771793.968, 140253.342, 5124304.349, 2000};

    /** EPSG 9636-shaped rotations, used for the molobadekas reference-point blocks. */
    private static final String MOLO_SEVEN =
            "+x=61.055 +y=-410.62 +z=-329.381 +rx=-1.2747 +ry=-0.1642 +rz=-0.2907 +s=-2.1";

    private static final double[] MOLO_INPUT =
            {2845455.9734, 2160954.3229, -5265993.2196, 2000};

    private final PipelineFactory factory = new PipelineFactory();

    // ------------------------------------------------------------------ static forms

    /**
     * {@code more_builtins.gie:386-392}, the NAD27→NAD83(91) south-eastern Wisconsin
     * row, which is the one corpus assertion that exercises {@code +theta}. The corpus
     * states the answer to three decimals with a 1 mm tolerance; {@code cct} at twelve
     * decimals is asserted here instead, because a test that only reproduces the
     * corpus's own rounding cannot tell a millimetre bug from a metre bug in the sign of
     * the rotation.
     */
    @Test
    public void reproducesTheCorpusThetaRow() {
        Pipeline p = factory.create("+proj=helmert +x=-9597.3572 +y=.6112 "
                + "+s=0.304794780637 +theta=-1.244048");
        double[] out = p.forward(new double[] {2546506.957, 542256.609, 0, 0});

        assertEquals(766563.675297754235, out[0], 1e-9);
        assertEquals(165282.276663727534, out[1], 1e-9);
        assertEquals(0.0, out[2], 0.0);
    }

    /** The same row backwards; {@code cct --inverse} returns the input to 1e-7 m. */
    @Test
    public void invertsTheCorpusThetaRow() {
        Pipeline p = factory.create("+proj=helmert +x=-9597.3572 +y=.6112 "
                + "+s=0.304794780637 +theta=-1.244048");
        double[] out = p.inverse(new double[] {766563.675297754235, 165282.276663727534, 0, 0});

        assertEquals(2546506.957, out[0], 1e-7);
        assertEquals(542256.609, out[1], 1e-7);
    }

    /**
     * {@code +exact} is not the default. {@code build_rot_matrix} ({@code :229-321}) has
     * two branches, and the one taken when {@code +exact} is absent is the linearised
     * small-angle matrix — the fork had only ever built the trigonometric one, because
     * its only caller was {@code +towgs84}, which {@code create.cpp:131} always spells
     * with {@code exact}.
     *
     * <p>On rotations of 1-3 arcseconds the two differ by about 0.6 mm, which is well
     * inside the corpus's 1 mm tolerances and well outside the 1e-9 asserted here. That
     * gap is the whole point of the block: it is large enough to prove which branch ran.
     */
    @Test
    public void defaultsToTheLinearisedMatrixAndUsesTheExactOneOnRequest() {
        String base = "+proj=helmert +x=10 +y=20 +z=30 +rx=1 +ry=2 +rz=3 +s=4 "
                + "+convention=position_vector";

        double[] linear = factory.create(base).forward(GEOCENTRIC.clone());
        assertEquals(3771866.702121378854, linear[0], 1e-9);
        assertEquals(140303.918324423459, linear[1], 1e-9);
        assertEquals(5124318.953694856726, linear[2], 1e-9);

        double[] exact = factory.create(base + " +exact").forward(GEOCENTRIC.clone());
        assertEquals(3771866.701545126271, exact[0], 1e-9);
        assertEquals(140303.918485247559, exact[1], 1e-9);
        assertEquals(5124318.953679491766, exact[2], 1e-9);

        assertNotEquals("the two matrix branches must not collapse into one",
                linear[0], exact[0], 1e-7);
    }

    /**
     * The convention is a transpose of the rotation block, so on rotations this size the
     * two answers are about 95 m apart — an error nobody would mistake for a rounding
     * problem, which is why upstream refuses to guess and demands the key.
     */
    @Test
    public void positionVectorAndCoordinateFrameAreTransposesOfEachOther() {
        String base = "+proj=helmert +x=10 +y=20 +z=30 +rx=1 +ry=2 +rz=3 +s=4";

        double[] pv = factory.create(base + " +convention=position_vector")
                .forward(GEOCENTRIC.clone());
        double[] cf = factory.create(base + " +convention=coordinate_frame")
                .forward(GEOCENTRIC.clone());

        assertEquals(3771866.702121378854, pv[0], 1e-9);
        assertEquals(3771771.408230363857, cf[0], 1e-9);
        assertEquals(140243.887702312495, cf[1], 1e-9);
        assertEquals(5124390.738739935681, cf[2], 1e-9);

        double[] cfExact = factory.create(base + " +convention=coordinate_frame +exact")
                .forward(GEOCENTRIC.clone());
        assertEquals(3771771.408022044227, cfExact[0], 1e-9);
        assertEquals(140243.888408497267, cfExact[1], 1e-9);
        assertEquals(5124390.738438823260, cfExact[2], 1e-9);
    }

    /**
     * {@code helmert.cpp:489-490}. Under {@code +theta} both sides become
     * {@code PROJECTED} instead ({@code :567-568}), which is what stops a
     * {@code +theta} step from being spliced between two {@code +proj=cart} steps.
     */
    @Test
    public void declaresCartesianOnBothSidesUnlessThetaIsGiven() {
        Pipeline cartesian = factory.create("+proj=helmert +x=1");
        assertEquals(GieIoUnits.CARTESIAN, cartesian.left());
        assertEquals(GieIoUnits.CARTESIAN, cartesian.right());

        Pipeline projected = factory.create("+proj=helmert +theta=1");
        assertEquals(GieIoUnits.PROJECTED, projected.left());
        assertEquals(GieIoUnits.PROJECTED, projected.right());
    }

    // ----------------------------------------------------------------- molobadekas

    /**
     * {@code helmert.cpp:751-757}: the reference point is subtracted before the rotation
     * and added back after, which is the whole difference between Molodensky-Badekas and
     * a plain seven-parameter helmert. On this point the two answers are about 41 m
     * apart.
     */
    @Test
    public void molobadekasRotatesAboutItsReferencePoint() {
        Pipeline withRefp = factory.create("+proj=molobadekas +convention=coordinate_frame "
                + MOLO_SEVEN + " +px=569150.0 +py=-1885890.0 +pz=-5847080.0");
        double[] out = withRefp.forward(MOLO_INPUT.clone());

        assertEquals(2845507.007317077834, out[0], 1e-8);
        assertEquals(2160534.821577411145, out[1], 1e-8);
        assertEquals(-5266300.623842198402, out[2], 1e-8);

        // The reverse is asserted against cct --inverse rather than against the input,
        // because the round trip does not close: subtracting a reference point of about
        // 6e6 from a coordinate of about 5e6 costs the low bits, and both PROJ and this
        // fork come back 2e-5 m short of where they started. Asserting the input at a
        // loose tolerance would hide that; asserting PROJ's own reverse answer at 1e-8
        // records it and still fails if our matrix transpose is wrong.
        double[] back = withRefp.inverse(out);
        assertEquals(2845455.973380994052, back[0], 1e-8);
        assertEquals(2160954.323050742503, back[1], 1e-8);
        assertEquals(-5265993.219601806253, back[2], 1e-8);
        assertEquals("PROJ does not close this round trip either",
                MOLO_INPUT[0], back[0], 1e-4);
    }

    /**
     * With no {@code +px +py +pz} the reference point is the geocentre, and molobadekas
     * degenerates to exactly the helmert with the same seven values. Both are asserted
     * against the same {@code cct} numbers, which is the control for the block above:
     * it proves the 41 m gap there comes from the reference point and not from the two
     * operators disagreeing about anything else.
     */
    @Test
    public void molobadekasWithoutAReferencePointEqualsPlainHelmert() {
        double[] molo = factory.create(
                "+proj=molobadekas +convention=coordinate_frame " + MOLO_SEVEN)
                .forward(MOLO_INPUT.clone());
        double[] helmert = factory.create(
                "+proj=helmert +convention=coordinate_frame " + MOLO_SEVEN)
                .forward(MOLO_INPUT.clone());

        assertEquals(2845503.815341429785, molo[0], 1e-8);
        assertEquals(2160575.718488908373, molo[1], 1e-8);
        assertEquals(-5266300.452677950263, molo[2], 1e-8);
        assertEquals(molo[0], helmert[0], 0.0);
        assertEquals(molo[1], helmert[1], 0.0);
        assertEquals(molo[2], helmert[2], 0.0);
    }

    /**
     * {@code molobadekas} never reads {@code theta}, {@code transpose} or any of the
     * rates ({@code :699-760}), so a definition carrying them is a plain 3D cartesian
     * step that silently ignores every one. Confirmed against {@code cct}: with
     * {@code +theta=3600 +x=1} the answer is the input plus one metre of x, not a planar
     * rotation of one degree.
     */
    @Test
    public void molobadekasIgnoresThetaEntirely() {
        double[] out = factory.create(
                "+proj=molobadekas +convention=position_vector +theta=3600 +x=1")
                .forward(new double[] {100, 200, 300, 2000});

        assertEquals(101.0, out[0], 1e-9);
        assertEquals(200.0, out[1], 1e-9);
        assertEquals(300.0, out[2], 1e-9);
    }

    /**
     * {@code no_rotation} is never assigned in molobadekas's setup, so its zero-filled
     * initial value leaves {@code read_convention} unconditionally required — even for a
     * definition with no rotation at all, where helmert would not ask.
     */
    @Test
    public void molobadekasAlwaysNeedsAConventionAndHelmertDoesNot() {
        assertRejected("+proj=molobadekas +x=1", PipelineErrorCode.MISSING_ARG,
                "missing 'convention' argument");
        factory.create("+proj=helmert +x=1");
    }

    // ---------------------------------------------------------------- the theta form

    /**
     * {@code Q->scale} means two different things. In the 2D {@code +theta} form it is a
     * direct multiplier, {@code cr = cos(theta) * Q->scale} ({@code :332}); in 3D it is
     * parts per million, {@code scale = 1 + Q->scale * 1e-6} ({@code :396}). So
     * {@code +s=2} doubles the plane and {@code +s=1000000} doubles the space, and the
     * same key differs by a factor of half a million between the two forms.
     */
    @Test
    public void scaleIsAMultiplierInTwoDimensionsAndPartsPerMillionInThree() {
        double[] planar = factory.create("+proj=helmert +theta=0 +s=2")
                .forward(new double[] {100, 200, 300, 2000});
        assertEquals(200.0, planar[0], 1e-9);
        assertEquals(400.0, planar[1], 1e-9);
        assertEquals("the 2D branch never writes z", 300.0, planar[2], 0.0);

        double[] spatial = factory.create("+proj=helmert +s=1000000")
                .forward(new double[] {100, 200, 300, 2000});
        assertEquals(200.0, spatial[0], 1e-9);
        assertEquals(400.0, spatial[1], 1e-9);
        assertEquals(600.0, spatial[2], 1e-9);
    }

    /**
     * <b>An upstream defect, reproduced on purpose.</b>
     *
     * <p>{@code helmert_forward} adds {@code Q->xyz_0} ({@code :336-337}) — the epoch
     * translation — while the two lines above it read the time-updated {@code Q->theta}
     * and {@code Q->scale}. So on a {@code +theta} step {@code +dtheta} and {@code +ds}
     * apply and {@code +dx} and {@code +dy} are parsed and thrown away, and the same
     * {@code +dx} on a 3D step does apply.
     *
     * <p>Confirmed against 9.8.1 {@code cct}: {@code +theta=0 +s=1 +x=7 +dx=1
     * +t_epoch=2000} at t=2001 gives 107, where the parameter names imply 108. Both
     * numbers are written down here so that the day someone fixes it upstream, this test
     * says which line to change rather than merely going red.
     */
    @Test
    public void translationRatesAreIgnoredUnderThetaAndHonouredWithoutIt() {
        double implied = 108.0;

        double[] planar = factory.create(
                "+proj=helmert +theta=0 +s=1 +x=7 +dx=1 +t_epoch=2000")
                .forward(new double[] {100, 200, 300, 2001});
        assertEquals("PROJ 9.8.1 applies the static +x and not the +dx rate",
                107.0, planar[0], 1e-9);
        assertNotEquals("if this ever equals 108 we have silently diverged from PROJ",
                implied, planar[0], 1e-9);

        double[] spatial = factory.create("+proj=helmert +x=7 +dx=1 +t_epoch=2000")
                .forward(new double[] {100, 200, 300, 2001});
        assertEquals("the 3D path reads the updated translation", implied, spatial[0], 1e-9);
    }

    /**
     * The other two rates that reach the 2D form do work, which is what makes the block
     * above a defect rather than "rates are not supported in 2D".
     * {@code +dtheta=3600} is one degree per year.
     */
    @Test
    public void rotationAndScaleRatesDoReachTheTwoDimensionalForm() {
        double[] scaled = factory.create("+proj=helmert +theta=0 +s=1 +ds=1 +t_epoch=2000")
                .forward(new double[] {100, 200, 300, 2001});
        assertEquals(200.0, scaled[0], 1e-9);
        assertEquals(400.0, scaled[1], 1e-9);

        double[] turned = factory.create(
                "+proj=helmert +theta=0 +s=1 +dtheta=3600 +t_epoch=2000")
                .forward(new double[] {100, 200, 300, 2001});
        assertEquals(103.475250803096, turned[0], 1e-9);
        assertEquals(198.224298387550, turned[1], 1e-9);
    }

    // ------------------------------------------------------------------- time rates

    /**
     * The 14-parameter form, evaluated at three epochs through <em>one</em> operator.
     * Upstream would have rewritten nine matrix entries in its opaque struct between
     * these three calls; here each call builds its own conversion from the immutable
     * epoch values and rates.
     */
    @Test
    public void evaluatesTheFourteenParameterFormAtTheObservationEpoch() {
        Pipeline p = factory.create("+proj=helmert +x=1 +y=2 +z=3 +rx=1 +ry=2 +rz=3 +s=4 "
                + "+dx=0.1 +dy=0.2 +dz=0.3 +drx=0.01 +dry=0.02 +drz=0.03 +ds=0.4 "
                + "+t_epoch=2000 +convention=position_vector");

        double[] at2010 = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 2010});
        assertEquals(3771878.554201447871, at2010[0], 1e-8);
        assertEquals(140291.481000963919, at2010[1], 1e-8);
        assertEquals(5124311.861502072774, at2010[2], 1e-8);

        double[] at2000 = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 2000});
        assertEquals(3771857.702121378854, at2000[0], 1e-8);
        assertEquals(140285.918324423459, at2000[1], 1e-8);
        assertEquals(5124291.953694856726, at2000[2], 1e-8);

        double[] at1990 = p.forward(new double[] {3771793.968, 140253.342, 5124304.349, 1990});
        assertEquals(3771836.850079428405, at1990[0], 1e-8);
        assertEquals(140280.355671895260, at1990[1], 1e-8);
        assertEquals(5124272.045858927071, at1990[2], 1e-8);
    }

    /**
     * {@code helmert_forward_4d:439} treats {@code HUGE_VAL} as "no observation time",
     * and then the epoch stands in for it so the rates contribute nothing.
     */
    @Test
    public void anInfiniteTimeOrdinateMeansTheEpochItself() {
        Pipeline p = factory.create("+proj=helmert +x=1 +dx=1 +t_epoch=2000");

        double[] atEpoch = p.forward(new double[] {0, 0, 0, 2000});
        double[] atInfinity = p.forward(new double[] {0, 0, 0, Double.POSITIVE_INFINITY});

        assertEquals(1.0, atEpoch[0], 0.0);
        assertEquals(atEpoch[0], atInfinity[0], 0.0);
    }

    /**
     * The reason the rates are recomputed per call instead of cached the way upstream
     * caches them in {@code P->opaque}: two threads at two epochs through one operator
     * must not see each other's matrix.
     *
     * <p>The control is that a single thread produces two visibly different answers for
     * these two epochs — 2 m apart — so a swap would be detected rather than lost in
     * rounding.
     */
    @Test
    public void oneRateBearingOperatorIsSafeToShareBetweenThreads()
            throws InterruptedException {
        final Pipeline p = factory.create("+proj=helmert +x=1 +dx=0.1 +t_epoch=2000");

        final double at2000 = p.forward(new double[] {0, 0, 0, 2000})[0];
        final double at2020 = p.forward(new double[] {0, 0, 0, 2020})[0];
        assertEquals(1.0, at2000, 1e-12);
        assertEquals(3.0, at2020, 1e-12);

        final int rounds = 20000;
        final CyclicBarrier start = new CyclicBarrier(2);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        Thread a = new Thread(new Runner(p, start, failure, 2000, at2000, rounds));
        Thread b = new Thread(new Runner(p, start, failure, 2020, at2020, rounds));
        a.start();
        b.start();
        a.join();
        b.join();

        assertNull(String.valueOf(failure.get()), failure.get());
    }

    private static final class Runner implements Runnable {
        private final Pipeline pipeline;
        private final CyclicBarrier start;
        private final AtomicReference<Throwable> failure;
        private final double epoch;
        private final double expected;
        private final int rounds;

        Runner(Pipeline pipeline, CyclicBarrier start, AtomicReference<Throwable> failure,
               double epoch, double expected, int rounds) {
            this.pipeline = pipeline;
            this.start = start;
            this.failure = failure;
            this.epoch = epoch;
            this.expected = expected;
            this.rounds = rounds;
        }

        @Override
        public void run() {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    double got = pipeline.forward(new double[] {0, 0, 0, epoch})[0];
                    if (Math.abs(got - expected) > 1e-12) {
                        throw new AssertionError("at t=" + epoch + " round " + i
                                + " expected " + expected + " but got " + got);
                    }
                }
            } catch (BrokenBarrierException e) {
                failure.compareAndSet(null, e);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }
    }

    // --------------------------------------------------------------------- towgs84

    /**
     * <b>A deliberate divergence from the 9.8.1 binary, pinned in both directions.</b>
     *
     * <p>{@code create.cpp:127-161} builds a hidden child helmert for <em>any</em> PJ
     * carrying {@code +towgs84}, and {@code fwd_prepare} applies that child's inverse to
     * a {@code CARTESIAN} input before the step runs ({@code fwd.cpp:116-118}). When the
     * step is itself a helmert reading the same {@code +towgs84}, the two cancel, and
     * 9.8.1 answers {@code 0 0 0} to {@code +proj=helmert +towgs84=1,2,3} on the origin.
     *
     * <p>That cancellation is in the generic wrapper, not in {@code helmert.cpp}, and
     * this engine has no such wrapper — so a {@code +towgs84} here applies its
     * translation once, exactly as the file it ports says. No corpus assertion is
     * affected: the only corpus row that pairs the two is an expected setup refusal.
     */
    @Test
    public void towgs84AppliesOnceHereWhereTheBinaryCancelsItOut() {
        double projAnswer = 0.0;

        double[] out = factory.create("+proj=helmert +towgs84=1,2,3")
                .forward(new double[] {0, 0, 0, 2000});
        assertEquals(1.0, out[0], 1e-12);
        assertEquals(2.0, out[1], 1e-12);
        assertEquals(3.0, out[2], 1e-12);
        assertNotEquals("9.8.1 answers 0 here, for a reason outside helmert.cpp",
                projAnswer, out[0], 1e-12);

        // Written directly rather than through +towgs84, the binary agrees with us.
        double[] direct = factory.create("+proj=helmert +x=1 +y=2 +z=3")
                .forward(new double[] {0, 0, 0, 2000});
        assertEquals(1.0, direct[0], 1e-12);
        assertEquals(2.0, direct[1], 1e-12);
        assertEquals(3.0, direct[2], 1e-12);
    }

    /**
     * {@code :583-600}: the seven {@code +towgs84} slots overwrite whatever {@code +x}
     * and friends had already set, including when the overwriting value is zero. So the
     * translation below is 1 and not 9, and reordering the two keys changes nothing
     * because the overwrite is positional in the setup rather than in the parameter list.
     */
    @Test
    public void towgs84OverwritesTheIndividualKeysRatherThanMergingWithThem() {
        double[] out = factory.create("+proj=helmert +x=9 +towgs84=1,0,0")
                .forward(new double[] {0, 0, 0, 2000});
        assertEquals(1.0, out[0], 1e-12);

        double[] reversed = factory.create("+proj=helmert +towgs84=1,0,0 +x=9")
                .forward(new double[] {0, 0, 0, 2000});
        assertEquals(1.0, reversed[0], 1e-12);
    }

    // ---------------------------------------------------------------------- scaling

    /**
     * {@code fwd_finalize}'s {@code CARTESIAN} case scales the output by
     * {@code fr_meter} and {@code inv_prepare} scales the inverse's input by
     * {@code to_meter}; neither touches the forward input. So with
     * {@code +to_meter=1000} the translation still happens in metres and only the answer
     * is expressed in kilometres — {@code cct} gives {@code 0.11 0.22 0.33} for
     * {@code 100 200 300} through {@code +x=10 +y=20 +z=30}, not {@code 100.01 …}.
     */
    @Test
    public void toMeterScalesTheOutputOfTheCartesianForm() {
        Pipeline p = factory.create("+proj=helmert +x=10 +y=20 +z=30 +to_meter=1000");
        double[] out = p.forward(new double[] {100, 200, 300, 2000});

        assertEquals(0.11, out[0], 1e-12);
        assertEquals(0.22, out[1], 1e-12);
        assertEquals(0.33, out[2], 1e-12);

        double[] back = p.inverse(out);
        assertEquals(100.0, back[0], 1e-9);
        assertEquals(200.0, back[1], 1e-9);
        assertEquals(300.0, back[2], 1e-9);
    }

    /**
     * Under {@code +theta} the declared sides are {@code PROJECTED}, and the generic
     * scaling there is {@code fr_meter * (x + x_0)} with a separate vertical factor that
     * falls back to {@code to_meter}. That machinery is private inside
     * {@link Cs2csOperator}; rather than grow a second copy for a combination no corpus
     * row uses, all seven keys are refused. A refusal is recoverable, a dropped unit
     * factor is a wrong coordinate reported as a right one.
     */
    @Test
    public void refusesTheProjectedScalingKeysUnderTheta() {
        String[] keys = {"to_meter=2", "units=us-ft", "x_0=1", "y_0=1", "z_0=1",
            "vto_meter=2", "vunits=us-ft"};
        for (int i = 0; i < keys.length; i++) {
            assertRejected("+proj=helmert +theta=1 +" + keys[i],
                    PipelineErrorCode.NOT_IMPLEMENTED_HERE, "is not implemented");
        }
        // The control: without +theta the same key is accepted and applied.
        factory.create("+proj=helmert +x=1 +to_meter=2");
    }

    // --------------------------------------------------------------------- refusals

    /**
     * The five setup refusals of {@code helmert.cpp}, each also a row in the conformance
     * oracle. {@code +transpose} is tested for <em>presence</em> ({@code :573}), so
     * {@code +transpose=F} is refused too, and {@code +s=-1e6} is refused where
     * {@code +s=-999999} is not.
     */
    @Test
    public void reproducesEverySetupRefusal() {
        assertRejected("+proj=helmert +transpose", PipelineErrorCode.ILLEGAL_ARG_VALUE,
                "no longer valid");
        assertRejected("+proj=helmert +transpose=F", PipelineErrorCode.ILLEGAL_ARG_VALUE,
                "no longer valid");
        assertRejected("+proj=helmert +rx=1", PipelineErrorCode.MISSING_ARG,
                "missing 'convention' argument");
        assertRejected("+proj=helmert +drx=1", PipelineErrorCode.MISSING_ARG,
                "missing 'convention' argument");
        assertRejected("+proj=helmert +rx=1 +convention=foo",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "invalid value for 'convention'");
        assertRejected("+proj=helmert +towgs84=1,2,3,4,5,6,7 +convention=coordinate_frame",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "position_vector");
        assertRejected("+proj=helmert +s=-1000000", PipelineErrorCode.ILLEGAL_ARG_VALUE,
                "invalid value for s");
        assertRejected("+proj=helmert +theta=1 +s=0", PipelineErrorCode.ILLEGAL_ARG_VALUE,
                "invalid value for s");

        // Controls: each refusal's nearest legal neighbour must still build.
        factory.create("+proj=helmert +rx=0");
        factory.create("+proj=helmert +rx=1 +convention=coordinate_frame");
        factory.create("+proj=helmert +s=-999999");
        factory.create("+proj=helmert +theta=1");
        factory.create("+proj=helmert");
    }

    /**
     * molobadekas shares the six-parameter reader and therefore the convention rules,
     * but not the {@code +transpose} check and not the {@code +s} validation
     * ({@code :699-760}). Both of these are accepted by the 9.8.1 binary and would be
     * easy to "tidy up" into refusals by sharing more code than upstream shares.
     */
    @Test
    public void molobadekasSkipsTheTransposeAndScaleValidation() {
        factory.create("+proj=molobadekas +convention=position_vector +transpose");
        factory.create("+proj=molobadekas +convention=position_vector +s=-2000000");

        assertRejected("+proj=molobadekas +convention=foo",
                PipelineErrorCode.ILLEGAL_ARG_VALUE, "invalid value for 'convention'");
    }

    private void assertRejected(String definition, PipelineErrorCode expected,
                                String messageFragment) {
        try {
            factory.create(definition);
            fail("expected a PipelineDefinitionException for: " + definition);
        } catch (PipelineDefinitionException e) {
            assertEquals(definition, expected, e.code());
            assertTrue("message was: " + e.getMessage(),
                    e.getMessage().contains(messageFragment));
        }
    }
}
