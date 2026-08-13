/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Nell-Hammer, {@code +proj=nell_h} &mdash; a port of
 * {@code 9.8.1:src/projections/nell_h.cpp}. Pseudocylindrical, spherical, forward in closed
 * form, inverse by Newton.
 *
 * <h2>This class deliberately does not match PROJ near the poles. Do not "fix" it.</h2>
 *
 * <p>The iteration is bit-faithful: same {@code NITER 9}, same {@code EPS 1e-7}, same update
 * formula, same seed of zero ({@code nell_h.cpp:29-35}). <b>Only the exhaustion handler
 * differs.</b> When the nine steps run out, upstream ({@code nell_h.cpp:36-39}) sets the
 * latitude to &plusmn;90 and the longitude to {@code 2 * xy.x}. This class raises
 * {@link org.locationtech.proj4j.ConvergenceFailureException} instead.
 *
 * <p>Note that upstream's failure branch writing {@code 2 * xy.x} rather than
 * {@code 2 * xy.x / (1 + cos phi)} is <em>not</em> a second, separate slip, tempting though it
 * is to read it as one. {@code cos(M_HALFPI)} is {@code 6.12e-17}, which is below half an ulp
 * of 1, so {@code 1.0 + cos(M_HALFPI)} is exactly {@code 1.0} and the two lines agree at the
 * value the branch snaps to. Everything wrong with the output is downstream of the latitude.
 *
 * <p>That divergence is intentional and is asserted by two tests, so a change here breaks
 * them on purpose. {@code NonConvergenceTest} lists {@code nell_h} among the five kernels
 * that used to clamp to a pole and records that for the four remaining ones (after
 * {@code eck4} was taken off the list) "upstream's clamp really is on a failure path";
 * {@code RegistryRoundTripAuditTest} pins {@code nell_h} as {@code REFUSED}. A pole is a
 * specific, in-range, entirely plausible coordinate, which makes it the worst available way
 * to report a failure — this library's house rule is to fail closed instead. This is the
 * one documented exception to the parity doctrine for this projection, and it is an
 * exception rather than a porting bug.
 *
 * <p>What upstream's clamp costs, measured with
 * {@code proj -f "%.15f" +proj=nell_h +ellps=GRS80}, forward then inverse:
 *
 * <pre>
 *   (  0, 89.8 )  -&gt;    0.000000000000000, 90.000000000000000   0.2 deg = 22.3 km, silently
 *   ( 45, 89.8 )  -&gt;   45.157079313685067, 90.000000000000000   longitude 0.157 deg out too
 *   (  0, 89.05)  -&gt;    0.000000000000000, 89.049999999982546   correct
 * </pre>
 *
 * <p>The longitude error in the middle row follows from the latitude error: {@code 2 * xy.x} is
 * the right formula <em>for a pole</em>, and the point is not at a pole, so it is a longitude
 * read off the wrong parallel. The size of it is exactly the divisor that should have been
 * applied &mdash; {@code 2x / (1 + cos(89.8 deg))} minus {@code 2x} is
 * {@code 0.157079313685} degrees at longitude 45, matching the measured row to twelve
 * places.
 *
 * <h2>Where the boundary is</h2>
 *
 * <p>Latitude <b>89.05069318341576</b> is the last one that converges within nine steps;
 * the next {@code double} upwards, {@code 89.050693183415774}, exhausts. Those two are
 * adjacent {@code double}s (one ulp at that magnitude is 1.42e-14), so the boundary is
 * pinned exactly. It is also knife-edge: the final correction there is
 * {@code -9.9999993442e-8} against an {@code EPS} of {@code 1e-7}, a margin of six parts in
 * ten million. Anything that perturbs the northing in its last bits &mdash; including the
 * round trip through metres that {@code proj} on the command line performs &mdash; can move
 * an input across the line. Measured: {@code proj} converges at {@code 89.05069318341576}
 * but clamps at {@code 89.0506931834}, which is a <em>smaller</em> latitude. Treat the
 * boundary as a location, not as a threshold to test against.
 *
 * <h2>Raising {@code NITER} fixes everything except latitude 90, and the step test is not an
 * error bound</h2>
 *
 * <p>Worth recording because it changes what an upstream fix would have to look like. The
 * forward is {@code y = 2 (phi - tan(phi/2))}, so
 * {@code dy/dphi = 2 (1 - 0.5 sec^2(phi/2))}, and at {@code phi = pi/2} that is
 * {@code 2 (1 - 0.5 * 2) = 0}. The root is a double root and Newton is only linearly
 * convergent onto it. Measured, solving for latitude 90 with {@code EPS} left at
 * {@code 1e-7}: the step test first passes at <b>iteration 23</b>, with the recovered
 * latitude still {@code 4.4931e-6} degrees (0.50 m on GRS80) away from 90. Left running
 * with the test disabled, the iterate reaches a fixed point at iteration 26, still
 * {@code 9.0961e-7} degrees (0.101 m) out, and never improves again. So at the pole a bigger
 * {@code NITER} buys a converged-looking answer that is still wrong, which is a worse
 * outcome than the throw.
 *
 * <p><b>Short of the pole it is a different story, and the honest answer is not "never raise
 * {@code NITER}".</b> Simulating this exact loop, latitude 89.8 exhausts at nine steps but
 * converges at <b>twelve</b>, to {@code 89.800000000000} with a final correction of
 * {@code -3.06e-12}; 89.9 behaves the same way. So for the whole band this class refuses,
 * upstream's budget of nine genuinely is just too small, and a budget of twelve would fix
 * upstream's silent 22 km without touching anything else. What a bigger budget cannot fix is
 * the pole itself. An upstream fix therefore wants both: more iterations <em>and</em> a snap
 * that is conditioned on the northing rather than on the loop running out.
 *
 * <p>This class does not raise {@code NITER}, because matching upstream's budget bit for bit is
 * what makes the rest of the parity claim checkable. The divergence here is confined to one
 * line, deliberately.
 *
 * <p><b>No upstream fix is in flight.</b> {@code src/projections/nell_h.cpp} is
 * byte-identical between tag {@code 9.8.1} and current {@code master}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/nell_h.cpp">9.8.1
 *      nell_h.cpp</a>
 */
public class NellHProjection extends Projection {

	private static final long serialVersionUID = -5588395073299363305L;

	private final static int NITER = 9;
	private final static double EPS = 1e-7;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		out.x = 0.5 * lplam * (1. + Math.cos(lpphi));
		out.y = 2.0 * (lpphi - Math.tan(0.5 *lpphi));
		return out;
	}

	/**
	 * Inverse projection. Port of PROJ 9.8.1 {@code nell_h.cpp}'s {@code nell_h_s_inverse}.
	 * <p>
	 * <b>Fail-closed</b>, and the iteration itself had to be repaired for that to mean
	 * anything. Two defects, both from the 2006 C&rarr;Java conversion:
	 * <ul>
	 * <li>the Newton iteration substituted the <em>constant</em> {@code xyy} for the running
	 *     estimate everywhere upstream writes {@code lp.phi}, so the correction {@code V} was
	 *     the same value on every trip and the loop could never converge unless it happened to
	 *     satisfy the tolerance immediately;</li>
	 * <li>it accumulated into {@code out.y}, which is the caller's destination coordinate and
	 *     holds whatever stale value the caller left there — {@code BasicCoordinateTransform}
	 *     passes the same object as source and destination, so that was the input northing.</li>
	 * </ul>
	 * The combination meant the {@code i == 0} branch was taken for essentially every input, so
	 * {@code nell_h}'s inverse returned a <b>pole</b> almost unconditionally. Clamping to the
	 * pole is now a throw, and the iteration is upstream's, over a local initialised to
	 * {@code 0.0} exactly as upstream's {@code PJ_LP lp = {0.0, 0.0}} does.
	 * <p>
	 * The throw below is the <em>only</em> line in this method that departs from
	 * {@code nell_h.cpp}, and it departs on purpose. Everything above it &mdash; the budget,
	 * the tolerance, the update, the seed &mdash; is upstream's to the bit, and this method
	 * reproduces {@code proj}'s output digit for digit everywhere the iteration converges.
	 * The class comment has the measurements, the exact 9-iteration boundary, and what a larger
	 * {@code NITER} would and would not fix upstream. Two tests assert this throw; see
	 * {@code NonConvergenceTest} and {@code RegistryRoundTripAuditTest}.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double V = Double.NaN, c, p, phi = 0.0;
		int i;

		p = 0.5 * xyy;
		for (i = NITER; i > 0 ; --i) {
			c = Math.cos(0.5 * phi);
			phi -= V = (phi - Math.tan(phi / 2) - p) / (1. - 0.5 / (c * c));
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"inverse latitude iteration did not converge to " + EPS + " within " + NITER
							+ " iterations for northing " + xyy + " (last correction " + V + ")");
		}
		out.y = phi;
		out.x = 2. * xyx / (1. + Math.cos(phi));
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Nell-Hammer";
	}

}
