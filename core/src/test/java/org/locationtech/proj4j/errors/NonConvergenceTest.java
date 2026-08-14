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
package org.locationtech.proj4j.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.proj.BoggsProjection;
import org.locationtech.proj4j.proj.Eckert4Projection;
import org.locationtech.proj4j.proj.EquidistantAzimuthalProjection;
import org.locationtech.proj4j.proj.EquidistantConicProjection;
import org.locationtech.proj4j.proj.FoucautSinusoidalProjection;
import org.locationtech.proj4j.proj.HatanoProjection;
import org.locationtech.proj4j.proj.KrovakProjection;
import org.locationtech.proj4j.proj.LandsatProjection;
import org.locationtech.proj4j.proj.McBrydeThomasFlatPolarQuarticProjection;
import org.locationtech.proj4j.proj.McBrydeThomasFlatPolarSine2Projection;
import org.locationtech.proj4j.proj.MolleweideProjection;
import org.locationtech.proj4j.proj.NellHProjection;
import org.locationtech.proj4j.proj.NellProjection;
import org.locationtech.proj4j.proj.PutninsP2Projection;
import org.locationtech.proj4j.proj.PutninsP4Projection;
import org.locationtech.proj4j.proj.RobinsonProjection;

/**
 * Every non-convergence site in the owned projections throws rather than returning a coordinate.
 *
 * <p>These tests call the <em>radian hooks</em> ({@code project(double, double, ProjCoordinate)}
 * and {@code projectInverse(double, double, ProjCoordinate)}) directly rather than going through
 * {@code CoordinateTransform}. That is deliberate: an input-domain guard higher up the stack would
 * otherwise reject the trigger before the iteration ever runs, and these assertions are about the
 * iteration.
 *
 * <p>The destination coordinate is always poisoned with {@code 1e300} before the call. Several of
 * these kernels used to accumulate their Newton correction into the destination ordinate — reading
 * back a value the caller supplied — and a poisoned destination is what makes that visible instead
 * of accidentally harmless.
 *
 * <h2>What "returns a coordinate" meant before</h2>
 * <ul>
 * <li>Five clamped to a <b>pole</b>: {@code eck4}, {@code moll} (and through it {@code wag4} and
 *     {@code wag5}), {@code putp2}, {@code fouc_s}, {@code nell_h}. A pole is a specific, in-range,
 *     plausible coordinate — the worst possible way to report a failure.
 *     <p><b>{@code eck4} has since been taken off that list, and its assertion here inverted.</b>
 *     Its clamp is not a failure report: it is the closed-form limit, and
 *     {@code builtins.gie}'s own expected values at {@code (±180, ±90)} are exactly the numbers it
 *     produces. See {@link #eckert4ForwardUsesUpstreamsPoleFallBackRatherThanThrowing}. The other
 *     four are unaffected — for them upstream's clamp really is on a failure path.</p></li>
 * <li>Eight had <b>no convergence test at all</b> and returned the last iterate:
 *     {@code boggs}, {@code hatano}, {@code mbtfpq}, {@code mbt_fps}, {@code nell},
 *     {@code robin}, and {@code eqdc} — where the accumulator started at {@code 0}, so a
 *     zero-iteration exit produced <b>latitude 0</b>.</li>
 * <li>{@code krovak}'s inverse had <b>no iteration cap</b>: not a wrong answer but a hung
 *     thread.</li>
 * </ul>
 */
public class NonConvergenceTest {

    /** The value every destination is poisoned with, to catch a stale read. */
    private static final double POISON = 1e300;

    private static ProjCoordinate poisoned() {
        return new ProjCoordinate(POISON, POISON, POISON);
    }

    /**
     * Asserts the call threw {@link ConvergenceFailureException} with
     * {@link ErrorCause#NUMERICAL_FAILURE}, and that nothing was written into the destination that
     * a caller could mistake for a result.
     */
    private static void assertThrowsConvergence(String what, Runnable call) {
        try {
            call.run();
            fail(what + " must throw on non-convergence, not return a coordinate");
        } catch (ConvergenceFailureException e) {
            assertEquals(what + ": wrong ErrorCause", ErrorCause.NUMERICAL_FAILURE, e.cause());
            assertTrue(what + ": the message must name the projection and the iteration count, "
                            + "so an operator can act on it. Was: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains("iterations"));
        }
    }

    // -------------------------------------------------------- the five that clamped to a pole

    /**
     * <b>This assertion has been inverted, and the previous version of it was wrong.</b>
     *
     * <p>{@code eck4}'s Newton iteration genuinely does not converge at a pole — the derivative
     * {@code 1 + c(c+2) - s^2} tends to 0 there — and upstream's fall-back is not a failure
     * report. It is the closed-form limit, returned as a <em>success</em>:
     * {@code xy.x = C_x * lam}, {@code xy.y = theta < 0 ? -C_y : C_y}.
     *
     * <p>The corpus settles it. {@code builtins.gie}'s {@code +proj=eck4 +a=6400000} block has
     * {@code accept -180 90} / {@code expect -8489602.7403 8489602.7403}, and those two numbers are
     * exactly {@code C_x * pi * a} and {@code C_y * a} — the fall-back's output, to ten significant
     * figures, at a {@code tolerance 0.1 mm}. Throwing here cost <b>four</b> assertions; so would a
     * "better" iteration. Non-negotiable 7 of this port: where upstream's expected values were
     * generated by upstream's approximation, port the approximation.
     *
     * <p>A {@code NaN} latitude therefore comes back as the pole, in both libraries — {@code sin(NaN)}
     * is {@code NaN}, every {@code fabs(V) < EPS} test is false, the loop exhausts, and
     * {@code NaN < 0.} is false so the sign is positive. That is not a sentinel this projection
     * minted; it is what PROJ answers, and {@code Projection.projectRadians} deliberately propagates
     * caller-supplied {@code NaN} rather than rejecting it.
     */
    @Test
    public void eckert4ForwardUsesUpstreamsPoleFallBackRatherThanThrowing() {
        final Eckert4Projection p = new Eckert4Projection();
        final ProjCoordinate out = poisoned();
        p.project(0.1, Double.NaN, out);
        // C_x = 2/sqrt(4pi + pi^2), C_y = 2*sqrt(pi/(4+pi)), on the unit sphere.
        assertEquals("Eckert IV easting is C_x * lam", .42223820031577120149 * 0.1, out.x, 1e-15);
        assertEquals("Eckert IV northing is +C_y", 1.32650042817700232218, out.y, 1e-15);
    }

    @Test
    public void mollweideForwardThrowsInsteadOfClampingToAPole() {
        final MolleweideProjection p = new MolleweideProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Mollweide forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
        assertPoleNotReturned("Mollweide", out);
    }

    /** The same kernel serves {@code wag4} and {@code wag5}, so it is closed for them too. */
    @Test
    public void wagnerIvAndVForwardThrowInsteadOfClampingToAPole() {
        for (final int type : new int[] { MolleweideProjection.WAGNER4,
                MolleweideProjection.WAGNER5 }) {
            final MolleweideProjection p = new MolleweideProjection(type);
            final ProjCoordinate out = poisoned();
            assertThrowsConvergence("Mollweide kernel type " + type, new Runnable() {
                public void run() {
                    p.project(0.1, Double.NaN, out);
                }
            });
        }
    }

    @Test
    public void putninsP2ForwardThrowsInsteadOfClampingToPiOverThree() {
        final PutninsP2Projection p = new PutninsP2Projection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Putnins P2 forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
        assertPoleNotReturned("Putnins P2", out);
    }

    @Test
    public void foucautSinusoidalInverseThrowsInsteadOfClampingToAPole() throws Exception {
        final FoucautSinusoidalProjection p = new FoucautSinusoidalProjection();
        // +n is not parsed by Proj4Parser, so the iterating branch is only reachable with n != 0.
        // Reflection is the only way to exercise a fail-open that is otherwise unreachable, and
        // an unreachable fail-open is still a defect: the moment +n is wired up it is live.
        Field n = FoucautSinusoidalProjection.class.getDeclaredField("n");
        n.setAccessible(true);
        n.setDouble(p, 0.5);
        Field n1 = FoucautSinusoidalProjection.class.getDeclaredField("n1");
        n1.setAccessible(true);
        n1.setDouble(p, 0.5);

        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Foucaut Sinusoidal inverse", new Runnable() {
            public void run() {
                p.projectInverse(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void nellHammerInverseThrowsInsteadOfClampingToAPole() {
        final NellHProjection p = new NellHProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Nell-Hammer inverse", new Runnable() {
            public void run() {
                p.projectInverse(0.1, Double.NaN, out);
            }
        });
        assertPoleNotReturned("Nell-Hammer", out);
    }

    // ------------------------------------------- the ones with no convergence test at all

    @Test
    public void boggsForwardThrowsInsteadOfReturningTheLastIterate() {
        final BoggsProjection p = new BoggsProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Boggs forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void hatanoForwardThrowsInsteadOfReturningTheLastIterate() {
        final HatanoProjection p = new HatanoProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Hatano forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void mcBrydeThomasFlatPolarQuarticForwardThrows() {
        final McBrydeThomasFlatPolarQuarticProjection p =
                new McBrydeThomasFlatPolarQuarticProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("McBryde-Thomas Flat-Polar Quartic forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void mcBrydeThomasFlatPolarSine2ForwardThrows() {
        final McBrydeThomasFlatPolarSine2Projection p =
                new McBrydeThomasFlatPolarSine2Projection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("McBryde-Thomas Flat-Pole Sine (No. 2) forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void nellForwardThrows() {
        final NellProjection p = new NellProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Nell forward", new Runnable() {
            public void run() {
                p.project(0.1, Double.NaN, out);
            }
        });
    }

    @Test
    public void robinsonInverseThrows() {
        final RobinsonProjection p = new RobinsonProjection();
        final ProjCoordinate out = poisoned();
        assertThrowsConvergence("Robinson inverse", new Runnable() {
            public void run() {
                p.projectInverse(0.1, Double.NaN, out);
            }
        });
    }

    // ------------------------------------------------------------------------- krovak liveness

    /**
     * {@code KrovakProjection.projectInverse} was {@code do { … } while (ok == 0);} with no cap and
     * an exit tolerance of 1e-15 radians. <b>Without the cap this test does not fail — it
     * hangs</b>, which is exactly the production symptom: not a rejected row, a task that never
     * returns. The timeout is the assertion.
     */
    @Test(timeout = 10000)
    public void krovakInverseIsCappedRatherThanSpinningForever() {
        final KrovakProbe p = new KrovakProbe();
        final ProjCoordinate out = poisoned();
        // The NaN no longer reaches the loop at all: krovak's inverse now routes its two arcsines
        // through ProjectionMath.asinChecked, whose one deliberate divergence from upstream's aasin
        // is that a NaN argument raises instead of being returned. So the rejection is strictly
        // earlier than the cap, and still NUMERICAL_FAILURE. The cap remains the guarantee for a
        // finite input that does not settle; the timeout is still the assertion that neither path
        // hangs, which is the production symptom this test exists for.
        try {
            p.invert(Double.NaN, Double.NaN, out);
            fail("Krovak inverse of (NaN, NaN) must raise rather than return a coordinate");
        } catch (ProjectionException e) {
            assertEquals("Krovak inverse: wrong ErrorCause",
                    ErrorCause.NUMERICAL_FAILURE, e.cause());
        }
        assertEquals("nothing may have been written into the destination", POISON, out.x, 0.0);
        assertEquals("nothing may have been written into the destination", POISON, out.y, 0.0);
    }

    /** Exposes Krovak's protected inverse hook, which it does not widen to public. */
    private static final class KrovakProbe extends KrovakProjection {
        ProjCoordinate invert(double x, double y, ProjCoordinate dst) {
            return projectInverse(x, y, dst);
        }
    }

    // ------------------------------------------- what a NaN latitude does at an arcsine now

    /**
     * Three comments used to say a {@code NaN} could not reach their arcsine "because no caller can
     * deliver one". This class is that caller: the radian hooks are public on the concrete
     * projections, this file calls them directly, and it passes {@code Double.NaN} to fifteen of
     * them already. The comments have been corrected, and these three tests pin what each site
     * actually does with a {@code NaN} so the corrected wording stays true.
     *
     * <p>The precedent is {@link #krovakInverseIsCappedRatherThanSpinningForever()}:
     * {@link org.locationtech.proj4j.util.ProjectionMath#asinChecked(double)} is upstream's
     * {@code aasin} except on a {@code NaN}, where upstream returns the {@code NaN} and we raise
     * {@link ErrorCause#NUMERICAL_FAILURE}. That is the port's one deliberate divergence at these
     * sites, and it is the "a silently wrong coordinate is the failure mode" rule applied to the one
     * input for which upstream has no answer either.
     *
     * <p>{@code putp4p}'s forward is the first of the three. Its argument is
     * {@code 0.883883476 * sin(lpphi)}, which cannot reach 1, so the clamp is unreachable and the
     * {@code NaN} is the only input on which this site's behaviour moved at all. Measured before the
     * change: {@code project(0.1, NaN, out)} wrote {@code (NaN, NaN)} into the destination and
     * returned normally.
     */
    @Test
    public void putninsP4ForwardRaisesOnANaNLatitudeInsteadOfWritingNaNAndReturning() {
        PutninsP4Projection p = new PutninsP4Projection();
        ProjCoordinate out = poisoned();
        try {
            p.project(0.1, Double.NaN, out);
            fail("putp4p forward of a NaN latitude must raise, not return (" + out.x + ", "
                    + out.y + ")");
        } catch (ProjectionException e) {
            assertEquals("putp4p forward: wrong ErrorCause", ErrorCause.NUMERICAL_FAILURE,
                    e.cause());
            assertTrue("the message must say a NaN arrived, not that a coordinate left the map; "
                    + "was: " + e.getMessage(), e.getMessage().contains("asin(NaN)"));
        }
        assertEquals("nothing may have been written into the destination", POISON, out.x, 0.0);
        assertEquals("nothing may have been written into the destination", POISON, out.y, 0.0);
    }

    /**
     * The second: {@code aeqd}'s spherical inverse, {@code aeqd.cpp:252} and {@code :256}. The
     * {@code OBLIQUE} arm is the one reached here, {@code lat_0} being 45. Measured before the
     * change: {@code projectInverse(0.1, NaN, out)} wrote {@code (NaN, NaN)} into the destination
     * and returned normally, at {@code lat_0 = 45} and at {@code lat_0 = 0} both. The deprecated
     * {@link org.locationtech.proj4j.util.ProjectionMath#asin(double)} guards only
     * {@code Math.abs(v) > 1.}, which is false for a {@code NaN}, so the {@code NaN} fell through to
     * {@link Math#asin} and out to the caller as a coordinate — the failure mode this project
     * exists to eliminate.
     *
     * <p>The second leg is the control that the arcsine is what changed and not the radius guard
     * above it: a radius of 4 is past {@code pi} and is refused with
     * {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} by the guard at {@code aeqd.cpp}'s
     * {@code c_rh > pi} test, which is a different cause from a different line and was already there.
     */
    @Test
    public void equidistantAzimuthalInverseRaisesOnANaNNorthingInsteadOfWritingNaNAndReturning() {
        EquidistantAzimuthalProjection p = (EquidistantAzimuthalProjection) new CRSFactory()
                .createFromParameters("t", "+proj=aeqd +lat_0=45 +lon_0=0 +R=1").getProjection();

        ProjCoordinate out = poisoned();
        try {
            p.projectInverse(0.1, Double.NaN, out);
            fail("aeqd inverse of a NaN northing must raise, not return (" + out.x + ", " + out.y
                    + ")");
        } catch (ProjectionException e) {
            assertEquals("aeqd inverse: wrong ErrorCause", ErrorCause.NUMERICAL_FAILURE, e.cause());
            assertTrue("the message must say a NaN arrived; was: " + e.getMessage(),
                    e.getMessage().contains("asin(NaN)"));
        }
        assertPoleNotReturned("aeqd inverse", out);

        // Control: a finite radius past pi is a different failure, from a different line, with a
        // different cause. If this leg ever reports NUMERICAL_FAILURE the guard has been lost and
        // the test above would be passing for the wrong reason.
        ProjCoordinate far = poisoned();
        try {
            p.projectInverse(4.0, 0.0, far);
            fail("aeqd inverse must refuse a radius of 4, which is past pi");
        } catch (ProjectionException e) {
            assertEquals("a radius past pi is the caller's point being off the map, not our "
                            + "arithmetic failing", ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            assertTrue("the radius guard's message must name the radius; was: " + e.getMessage(),
                    e.getMessage().contains("this point is at 4.0"));
        }
    }

    /**
     * The third, and the one where nothing moved: {@code lsat}'s forward returns
     * {@code (Infinity, Infinity)} for a {@code NaN} latitude, both before and after the change.
     *
     * <p>The reason is upstream's, not ours. With a {@code NaN} latitude every convergence test in
     * {@code som.cpp}'s inner loop is false, so the iteration counter runs down to zero and the
     * {@code if (l != 0)} guard skips the block the arcsine lives in; the {@code else} arm writes
     * {@code HUGE_VAL} into both ordinates, which is {@code som.cpp:151}. So this really is
     * unreachable by a {@code NaN} — but by the loop above it, not by "no caller can deliver one",
     * which is what the comment used to claim and is false for the same reason it is false in the
     * two tests above.
     *
     * <p>Infinity is not a plausible coordinate, so it is not the failure mode this project exists
     * to eliminate, and PROJ 9.8.1 returns it too. Pinned here so that if the guard is ever
     * restructured the change shows up as a test failure rather than as a pole.
     */
    @Test
    public void landsatForwardStillReturnsInfinityForANaNLatitudeBecauseTheArcsineIsSkipped() {
        LandsatProjection p = new LandsatProjection();
        ProjCoordinate out = poisoned();
        p.project(0.1, Double.NaN, out);
        assertEquals("lsat easting must be upstream's HUGE_VAL", Double.POSITIVE_INFINITY, out.x,
                0.0);
        assertEquals("lsat northing must be upstream's HUGE_VAL", Double.POSITIVE_INFINITY, out.y,
                0.0);
        assertPoleNotReturned("lsat forward", out);
    }

    // ------------------------------------------------------- eqdc: the zero-initialised phi

    /**
     * {@code eqdc}'s inverse used a Newton loop over a {@code phi} initialised to {@code 0}, with
     * no convergence test, so a zero-iteration exit returned latitude exactly 0. The site is now
     * gone rather than guarded: upstream's inverse is {@code pj_inv_mlfn}, and Proj4J's
     * {@link org.locationtech.proj4j.util.MeridianArc#invMlfn} is closed-form, so there is no
     * iteration left to fail. This asserts the property that mattered — the inverse of a real
     * coordinate is not latitude 0.
     */
    @Test
    public void equidistantConicInverseNeverSilentlyReturnsLatitudeZero() {
        EqdcProbe p = new EqdcProbe();
        ProjCoordinate fwd = poisoned();
        p.forward(Math.toRadians(0.5), Math.toRadians(40.5), fwd);
        ProjCoordinate back = poisoned();
        p.invert(fwd.x, fwd.y, back);
        assertEquals("eqdc inverse must recover the latitude, not fall out of an unconverged "
                        + "loop at zero", Math.toRadians(40.5), back.y, 1e-12);
        assertTrue("eqdc inverse must not return the equator", Math.abs(back.y) > 0.1);
    }

    /** Exposes eqdc's protected radian hooks. */
    private static final class EqdcProbe extends EquidistantConicProjection {
        ProjCoordinate forward(double lam, double phi, ProjCoordinate dst) {
            return project(lam, phi, dst);
        }

        ProjCoordinate invert(double x, double y, ProjCoordinate dst) {
            return projectInverse(x, y, dst);
        }
    }

    // ------------------------------------------------------------------------------ regression

    /**
     * The converged path must still be right. Four of these kernels accumulated their Newton
     * correction into the destination ordinate instead of into the running latitude, so the
     * correction was identical on every trip and the loop could never converge at all — the
     * output was computed from the raw geographic latitude where the solved parametric latitude
     * belongs. Values are PROJ 9.8.1's, from {@code proj +a=6400000} at (2, 40) degrees.
     */
    @Test
    public void repairedForwardsMatchProj981() {
        final PutninsP2Projection putp2 = new PutninsP2Projection();
        assertForward("putp2", 172470.547625, 4621770.512966, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                putp2.project(lam, phi, out);
            }
        });
        final McBrydeThomasFlatPolarQuarticProjection mbtfpq =
                new McBrydeThomasFlatPolarQuarticProjection();
        assertForward("mbtfpq", 176107.408014, 4627302.715424, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                mbtfpq.project(lam, phi, out);
            }
        });
        final McBrydeThomasFlatPolarSine2Projection mbtfps =
                new McBrydeThomasFlatPolarSine2Projection();
        assertForward("mbt_fps", 178195.220393, 4775502.443544, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                mbtfps.project(lam, phi, out);
            }
        });
        final NellProjection nell = new NellProjection();
        assertForward("nell", 199464.679483, 4268597.459204, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                nell.project(lam, phi, out);
            }
        });
        final NellHProjection nellH = new NellHProjection();
        assertForward("nell_h", 197269.057721, 4277266.771604, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                nellH.project(lam, phi, out);
            }
        });

        // ...and four whose iteration was already correct, as a control on the harness itself.
        final Eckert4Projection eck4 = new Eckert4Projection();
        assertForward("eck4", 170340.467656, 5027279.116777, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                eck4.project(lam, phi, out);
            }
        });
        final MolleweideProjection moll = new MolleweideProjection();
        assertForward("moll", 170437.534251, 4805816.268817, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                moll.project(lam, phi, out);
            }
        });
        final BoggsProjection boggs = new BoggsProjection();
        assertForward("boggs", 171021.780049, 4630530.614145, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                boggs.project(lam, phi, out);
            }
        });
        final HatanoProjection hatano = new HatanoProjection();
        assertForward("hatano", 170035.571625, 5010653.698434, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                hatano.project(lam, phi, out);
            }
        });
        final RobinsonProjection robin = new RobinsonProjection();
        assertForward("robin", 174736.647081, 4291010.076542, new Forward() {
            public void project(double lam, double phi, ProjCoordinate out) {
                robin.project(lam, phi, out);
            }
        });
    }

    /**
     * The radian forward hook. Needed because {@code Projection}'s declaration is protected and
     * only the concrete subclasses widen it to public, so a {@code Projection}-typed variable
     * cannot be used from this package.
     */
    private interface Forward {
        void project(double lam, double phi, ProjCoordinate out);
    }

    /**
     * {@code nell_h}'s inverse iterated over a constant, so it exhausted its budget and clamped to
     * a pole for essentially every input. With the iteration repaired it reproduces PROJ.
     */
    @Test
    public void nellHammerInverseMatchesProj981() {
        NellHProjection p = new NellHProjection();
        ProjCoordinate out = poisoned();
        // proj -I +proj=nell_h +a=6400000 <<< "222390.0 4400000.0"
        p.projectInverse(222390.0 / 6400000.0, 4400000.0 / 6400000.0, out);
        assertEquals(2.273318099, Math.toDegrees(out.x), 1e-9);
        assertEquals(41.273369782, Math.toDegrees(out.y), 1e-9);
    }

    private static void assertForward(String name, double expectedX, double expectedY,
            Forward forward) {
        final double radius = 6400000.0;
        ProjCoordinate out = poisoned();
        // The radian hooks work on the unit sphere; the affine post-multiply by the radius is
        // Projection.projectRadians' job, and is applied here so the numbers are PROJ's.
        forward.project(Math.toRadians(2), Math.toRadians(40), out);
        assertEquals(name + " easting", expectedX, out.x * radius, 1e-6);
        assertEquals(name + " northing", expectedY, out.y * radius, 1e-6);
    }

    /**
     * A pole, on the unit sphere the radian hooks work in, is {@code ±HALFPI} in the latitude
     * ordinate. This asserts the destination was not quietly filled with one on the way out.
     */
    private static void assertPoleNotReturned(String what, ProjCoordinate out) {
        assertTrue(what + " must leave the destination untouched when it throws, not fill it "
                        + "with a pole; found y=" + out.y,
                out.y == POISON || Double.isNaN(out.y)
                        || Math.abs(Math.abs(out.y) - Math.PI / 2) > 1e-9);
    }
}
