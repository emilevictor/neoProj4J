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
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The nine pseudocylindrical inverse sines that used to invent a latitude for a northing off the
 * map, one test each. All nine now call {@link ProjectionMath#asinChecked(double)}, which is
 * upstream's {@code aasin}; they used to call the deprecated {@link ProjectionMath#asin(double)},
 * which clamps at <em>any</em> magnitude, so {@code asin(1e9)} came back as exactly {@code pi/2}
 * and the caller got a pole.
 *
 * <h2>The nine sites</h2>
 *
 * <table border="1">
 * <caption>upstream line, expression, and the test that covers it</caption>
 * <tr><th>upstream</th><th>argument</th><th>test</th></tr>
 * <tr><td>{@code fouc_s.cpp:48}</td><td>{@code xy.y}</td>
 *     <td>{@link #foucautSinusoidalRefusesPastOneTol()}</td></tr>
 * <tr><td>{@code mbt_fps.cpp:41}</td><td>{@code xy.y / C_y}</td>
 *     <td>{@link #mcBrydeThomasFlatPolarSine2RefusesPastOneTol()}</td></tr>
 * <tr><td>{@code mbt_fps.cpp:44}</td><td>{@code (C1 sin(t) + sin(phi)) / C3}</td>
 *     <td>{@link #mcBrydeThomasFlatPolarSine2SecondSiteRefusesInsideTheNorthingRange()}</td></tr>
 * <tr><td>{@code nell.cpp:36}</td><td>{@code 0.5 (xy.y + sin(xy.y))}</td>
 *     <td>{@link #nellRefusesPastOneTol()}</td></tr>
 * <tr><td>{@code putp2.cpp:45}</td><td>{@code xy.y / C_y}</td>
 *     <td>{@link #putninsP2RefusesPastOneTol()}</td></tr>
 * <tr><td>{@code putp2.cpp:48}</td><td>{@code (phi + sin(phi)(c - 1)) / C_p}</td>
 *     <td>{@link #putninsP2SecondSiteReachesExactlyOneAndClamps()}</td></tr>
 * <tr><td>{@code putp4p.cpp:35}</td><td>{@code xy.y / C_y}</td>
 *     <td>{@link #putninsP4RefusesPastOneTol()}</td></tr>
 * <tr><td>{@code putp4p.cpp:39}</td><td>{@code 1.13137085 sin(phi)}</td>
 *     <td>{@link #putninsP4SecondSiteBindsBeforeTheFirst()}</td></tr>
 * <tr><td>{@code sts.cpp:44}</td><td>{@code xy.y}, in the non-{@code tan_mode} arm</td>
 *     <td>{@link #sineTangentSeriesRefusesPastOneTolInEveryNonTanVariant()}</td></tr>
 * </table>
 *
 * <h2>Every refusal here is one PROJ 9.8.1 makes too</h2>
 *
 * <p>Each test names the {@code proj -I} command it was checked against. PROJ prints
 * {@code *\t*} - its way of saying the point has no inverse - at every northing this class refuses,
 * and prints a coordinate at every northing this class answers, including the boundary
 * {@code double} immediately below each refusal. The parity is exact on both sides of the edge,
 * which is the property that matters: a fail-closed change that refused one {@code double} early
 * would be a new disagreement with the oracle, not a fix.
 *
 * <p>Two of the answers PROJ gives are latitudes <b>outside</b> {@code [-90, 90]}:
 * {@code kav5} at {@code y = 1.50488} answers 121.89510000000001 degrees and {@code qua_aut} at
 * {@code y = 2} answers 180. Those are upstream's numbers and we return them; under this project's
 * parity doctrine an out-of-range latitude PROJ computes is not ours to reject.
 *
 * <h2>Where the edge is, and why it is measured per projection rather than derived</h2>
 *
 * <p>{@code aasin} refuses at {@code |v| > ONE_TOL}, {@code ONE_TOL = 1.00000000000001}
 * ({@code 9.8.1:src/aasincos.cpp:8}). For the six first sites of the form {@code xy.y / C_y} that
 * would put the edge at {@code C_y * ONE_TOL}, and mostly it does - but the quotient is computed by
 * division, and the division rounds, so the last answering northing is up to a couple of
 * {@code double}s away from that product. {@code putp2}'s is one ulp above it. Every edge below is
 * therefore the {@code double} that was measured, not the one an algebraic argument predicts, and
 * both sides of it are asserted so a shift in either direction fails a test.
 *
 * <h2>What breaks if this file is deleted</h2>
 *
 * <p>Nothing else pins any of the nine. The neighbouring
 * {@link org.locationtech.proj4j.errors.NonConvergenceTest} covers the <em>iteration</em> failures
 * in {@code fouc_s}, {@code mbt_fps}, {@code nell} and {@code putp2} - a different failure in the
 * same four files - and says nothing about their arcsines. Without this file all nine sites could
 * revert to the deprecated {@link ProjectionMath#asin(double)} and every test in the repository
 * would still pass, while a northing a hundred sphere radii off the map came back as a pole.
 */
public class InverseSineDomainRefusalTest {

    private static final CRSFactory FACTORY = new CRSFactory();

    /**
     * A longitude that is not zero, so a wrong answer in the latitude cannot be masked by an
     * easting that happens to be zero too. Every call below uses it.
     */
    private static final double EASTING = 0.1;

    /** Degrees out of a unit-sphere inverse. PROJ prints these at {@code %.17g}. */
    private static final double DEGREE_TOL = 1e-12;

    // ---------------------------------------------------------------------- fouc_s, one site

    /**
     * {@code fouc_s.cpp:48}, the {@code n == 0} arm's {@code aasin(xy.y)}. {@code C_y} is 1 here,
     * so the edge is {@code ONE_TOL} itself.
     * <p>
     * Reference: {@code echo "0.1 1.00000000000001" | proj -I -f "%.17g" +proj=fouc_s +R=1} prints
     * {@code 5.729577951308233 90}, and one {@code double} higher prints {@code * *} (PROJ 9.8.1).
     */
    @Test
    public void foucautSinusoidalRefusesPastOneTol() {
        assertEdge("+proj=fouc_s +R=1", 1.00000000000001, 1.0000000000000102,
                5.729577951308233, 90.0, "1.0000000000000102");
        // The edge is ONE_TOL exactly, because this site's argument is the northing itself with no
        // division in front of it - the one site of the nine where the algebra and the measurement
        // cannot come apart.
        assertEquals("fouc_s's last answering northing is ONE_TOL itself",
                ProjectionMath.ONE_TOL, 1.00000000000001, 0.0);
    }

    // -------------------------------------------------------------------- mbt_fps, two sites

    /**
     * {@code mbt_fps.cpp:41}, {@code aasin(xy.y / C_y)} with {@code C_y = 1.44492}.
     * <p>
     * Reference: {@code echo "0.1 1.4449200000000144" | proj -I -f "%.17g" +proj=mbt_fps +R=1}
     * prints {@code -9.6882033980897001e-16 66.195792274947863}, and one {@code double} higher
     * prints {@code * *} (PROJ 9.8.1).
     */
    @Test
    public void mcBrydeThomasFlatPolarSine2RefusesPastOneTol() {
        assertEdge("+proj=mbt_fps +R=1", 1.4449200000000144, 1.4449200000000146,
                -9.6882033980897001e-16, 66.195792274947863, "1.0000000000000102");
    }

    /**
     * {@code mbt_fps.cpp:44}, the second site - and it is reachable well <em>inside</em> the range
     * of northings the first site accepts, which is what makes it worth a test of its own. At
     * {@code y = 1.319356452}, which is 0.913 of {@code C_y}, the first arcsine is happy and the
     * second is handed {@code 1.0000196943558102}. That is 2e-5 past 1, and {@code ONE_TOL}'s own
     * excess over 1 is {@code 9.992007221626409e-15}, so the overshoot is 1.97e9 times it - nine
     * orders of magnitude - and it is not a rounding artefact: the two constants {@code C1} and
     * {@code C3} simply do not bound this expression by 1.
     * <p>
     * Measured over 10 001 northings evenly spaced across {@code [0, C_y]}, <b>540 refuse</b> here.
     * The old clamp turned every one of those into a pole.
     * <p>
     * Reference: {@code echo "0 1.319356452" | proj -I -f "%.17g" +proj=mbt_fps +R=1} prints
     * {@code * *}, while {@code echo "0.1 1.44492" | …} - a <em>larger</em> northing - prints
     * {@code -9.6882033980897001e-16 66.195792274947863} (PROJ 9.8.1). The refusal is genuinely not
     * monotone in the northing, on either side.
     */
    @Test
    public void mcBrydeThomasFlatPolarSine2SecondSiteRefusesInsideTheNorthingRange() {
        ProjectionException e = assertRefuses("+proj=mbt_fps +R=1", 0.0, 1.319356452);
        assertTrue("the second site's overshoot is 2e-5, not a rounding artefact; got: "
                + e.getMessage(), e.getMessage().contains("1.0000196943558102"));

        // Non-monotone: C_y itself, which is larger, answers. If a future change made this site
        // refuse everything past the first refusal, this assertion would fail.
        ProjCoordinate at = invert("+proj=mbt_fps +R=1", EASTING, 1.44492);
        assertEquals("C_y is a larger northing than the refusal above and must still answer",
                66.195792274947863, Math.toDegrees(at.y), DEGREE_TOL);

        // How much of the range the second site rejects, as a count rather than an adjective.
        assertEquals("540 of 10 001 northings evenly spaced across [0, C_y] must refuse",
                540, refusalsAcross("+proj=mbt_fps +R=1", 1.44492));
    }

    // ------------------------------------------------------------------------- nell, one site

    /**
     * {@code nell.cpp:36}, {@code aasin(0.5 * (xy.y + sin(xy.y)))}. The argument is not a plain
     * quotient, so the edge is not {@code C_y * ONE_TOL} at all: it is the northing at which
     * {@code (y + sin y) / 2} crosses {@code ONE_TOL}, measured at
     * {@code 1.1060601577062856}.
     * <p>
     * Reference: {@code echo "0.1 1.1060601577062856" | proj -I -f "%.17g" +proj=nell +R=1} prints
     * {@code 7.9127599924348884 90}, and one {@code double} higher prints {@code * *}
     * (PROJ 9.8.1).
     */
    @Test
    public void nellRefusesPastOneTol() {
        assertEdge("+proj=nell +R=1", 1.1060601577062856, 1.1060601577062859,
                7.9127599924348884, 90.0, "1.0000000000000102");
        // Well past the edge, where the argument is 1.57 rather than 1 plus a rounding error, so
        // the test does not depend only on the last bit.
        ProjectionException e = assertRefuses("+proj=nell +R=1", EASTING, 3.0);
        assertTrue("a northing of 3 gives an argument of 1.5705600040299337, which is 57% past "
                + "the domain and used to come back as a pole; got: " + e.getMessage(),
                e.getMessage().contains("1.5705600040299337"));
    }

    // -------------------------------------------------------------------- putp2, two sites

    /**
     * {@code putp2.cpp:45}, {@code aasin(xy.y / C_y)} with {@code C_y = 1.71848}. The edge here is
     * <b>one ulp above</b> {@code C_y * ONE_TOL}, which is the clearest case for measuring each
     * edge rather than deriving it: the product is {@code 1.718480000000017} and the last northing
     * that answers is {@code 1.7184800000000173}, its {@link Math#nextUp(double)}, because
     * {@code y / C_y} rounds down.
     * <p>
     * Reference: {@code echo "0.1 1.7184800000000173" | proj -I -f "%.17g" +proj=putp2 +R=1} prints
     * {@code -6.0473670919924363 68.334636563488132}, and one {@code double} higher prints
     * {@code * *} (PROJ 9.8.1).
     */
    @Test
    public void putninsP2RefusesPastOneTol() {
        assertEdge("+proj=putp2 +R=1", 1.7184800000000173, 1.7184800000000175,
                -6.0473670919924363, 68.334636563488132, "1.0000000000000102");
        assertTrue("the measured edge must be above C_y * ONE_TOL, which is what makes deriving "
                        + "it wrong", 1.7184800000000173 > 1.71848 * ProjectionMath.ONE_TOL);
    }

    /**
     * {@code putp2.cpp:48}, the second site. <b>Its clamp is reachable at the boundary and nowhere
     * past it</b>, and saying so is the honest form of this test: the wrapper here changes no
     * answer for any finite northing the first site lets through.
     *
     * <p>The argument is {@code (phi + sin(phi)(cos(phi) - 1)) / C_p} with
     * {@code C_p = 0.6141848493043784}, and its numerator is maximised at {@code phi = pi/3} - the
     * {@code PI_DIV_3} that {@link PutninsP2Projection} already carries as a constant - where it
     * equals {@code C_p} <b>exactly</b>: {@code phi + sin(phi) * (cos(phi) - 1.0)} evaluates to the
     * same {@code double} {@code 0.6141848493043784}, in Java and in C against the same
     * {@code libm} the local {@code proj} binary uses. So the argument tops out at
     * exactly {@code 1.0}. Scanned over 4 000 001 consecutive {@code double}s of northing centred
     * on {@code C_y sin(pi/3) = 1.488247335895482}, the maximum reached is {@code 1.0} and
     * <b>none</b> exceeds it.
     *
     * <p>At exactly 1 {@code asinChecked} takes its clamp branch and returns {@code HALFPI}, which
     * is what {@link Math#asin} would have returned anyway - so this site is covered here for the
     * reason it was changed, not because the change moved anything: keeping all of a file's
     * arcsines on one wrapper removes the need to keep a bounding argument correct for each of them
     * separately.
     *
     * <p>Reference: {@code echo "0.1 1.488247335895482" | proj -I -f "%.17g" +proj=putp2 +R=1}
     * prints {@code -70.406325773431661 90} (PROJ 9.8.1). <b>Only the latitude is asserted.</b> The
     * longitude is {@code x / (C_x (cos(phi) - 0.5))} and {@code phi} is {@code pi/3} there, so the
     * denominator is a rounding error away from zero - {@code cos(pi/3) - 0.5} is
     * {@code 1.1102230246251565e-16} in Java and the same {@code double} in C - and both kernels
     * compute the same longitude, {@code 475339028694970.31} rad, i.e. 2.7e16 degrees. The
     * difference is PROJ's {@code adjlon} wrap in its inverse funnel: applied to that radian value
     * it gives exactly the {@code -70.406325773431661} degrees printed above, while this test calls
     * the kernel directly and so sees the value unwrapped. Neither is meaningful, both are what the
     * shared formula produces, and there is no defect on either side to pin.
     *
     * <p><b>Transcribing {@code adjlon} into Java does not reproduce that number, and the reason is
     * not a defect either.</b> {@code adjlon.cpp:17} is
     * {@code longitude -= M_TWOPI * floor(longitude / M_TWOPI)}, which the C compiler contracts into
     * a single {@code fma}; at this magnitude the subtraction cancels fifteen digits, so the one
     * rounding the {@code fma} avoids is worth 1.4 degrees in the result. Measured: compiled at
     * {@code -O2} that line yields {@code -70.406325773431661}, compiled at
     * {@code -ffp-contract=off} it yields {@code -68.989427193403}, and the straightforward Java
     * transcription yields {@code -68.989427193403} because the JLS forbids the contraction.
     * {@link Math#fma} recovers PROJ's value exactly ({@code -1.2288221989781256} rad).
     */
    @Test
    public void putninsP2SecondSiteReachesExactlyOneAndClamps() {
        double critical = 1.71848 * Math.sin(Math.PI / 3.0);
        assertEquals("the critical northing is C_y sin(pi/3)", 1.488247335895482, critical, 0.0);

        ProjCoordinate lp = invert("+proj=putp2 +R=1", EASTING, critical);
        assertEquals("at the maximum of the second site's argument the latitude must be PROJ's 90",
                90.0, Math.toDegrees(lp.y), DEGREE_TOL);
        assertEquals("and it must be exactly HALFPI, which is the clamp branch's return value and "
                        + "also what Math.asin(1.0) gives",
                Double.doubleToRawLongBits(ProjectionMath.HALFPI),
                Double.doubleToRawLongBits(lp.y));

        // The argument reaches 1 and never passes it, over four million doubles of northing.
        long base = Double.doubleToRawLongBits(critical);
        double max = 0;
        int over = 0;
        for (long k = -2000000; k <= 2000000; k++) {
            double v = secondSiteArgument(Double.longBitsToDouble(base + k));
            if (v > max) {
                max = v;
            }
            if (v > 1.0) {
                over++;
            }
        }
        assertEquals("the second site's argument reaches exactly 1", 1.0, max, 0.0);
        assertEquals("and no northing in four million doubles about the critical point pushes it "
                + "past 1, so only a NaN can make this site refuse", 0, over);
    }

    // ------------------------------------------------------------------- putp4p, two sites

    /**
     * {@code putp4p.cpp:35}, {@code aasin(xy.y / C_y)} with {@code C_y = 3.883251825}. Reached with
     * a northing well clear of the edge, because for {@code putp4p} the second site refuses first
     * and takes the edge with it - see
     * {@link #putninsP4SecondSiteBindsBeforeTheFirst()}. At {@code y = 5} the first site's own
     * argument is {@code 1.2875806734475688}.
     * <p>
     * Reference: {@code echo "0.1 5.0" | proj -I -f "%.17g" +proj=putp4p +R=1} prints {@code * *},
     * while {@code echo "0.1 3.0" | …} prints {@code -4.7251326256881976 32.377991653609051}
     * (PROJ 9.8.1).
     */
    @Test
    public void putninsP4RefusesPastOneTol() {
        ProjectionException e = assertRefuses("+proj=putp4p +R=1", EASTING, 5.0);
        assertTrue("the first site's argument at y=5 is 1.2875806734475688; got: "
                + e.getMessage(), e.getMessage().contains("1.2875806734475688"));

        // And a northing inside the map still answers, with PROJ's coordinate.
        ProjCoordinate lp = invert("+proj=putp4p +R=1", EASTING, 3.0);
        assertEquals("putp4p must still answer inside the map, with PROJ's longitude",
                -4.7251326256881976, Math.toDegrees(lp.x), DEGREE_TOL);
        assertEquals("and PROJ's latitude", 32.377991653609051, Math.toDegrees(lp.y), DEGREE_TOL);
    }

    /**
     * {@code putp4p.cpp:39}, {@code aasin(1.13137085 * sin(phi))}. The factor in front is bigger
     * than 1, so this site refuses <b>before</b> the first one does as the northing grows: the last
     * answering northing is {@code 3.832261942648194}, which is short of {@code C_y} by 0.051, and
     * the message names {@code -1.0000000000000104} - the second site's argument, not the first's
     * {@code y / C_y}.
     *
     * <p>At {@code y = C_y} exactly the argument is {@code -1.13137085}, a full 13% past the
     * domain, so this is not a boundary artefact. Measured over 10 001 northings evenly spaced
     * across {@code [0, C_y]}, <b>2 930 refuse</b> here - well over a quarter of the in-range
     * interval, every one of which used to come back as a pole.
     *
     * <p>Reference: {@code echo "0.1 3.832261942648194" | proj -I -f "%.17g" +proj=putp4p +R=1}
     * prints {@code -2.2638455417555932 -90}, one {@code double} higher prints {@code * *}, and
     * {@code echo "0.1 3.883251825" | …} prints {@code * *} (PROJ 9.8.1).
     */
    @Test
    public void putninsP4SecondSiteBindsBeforeTheFirst() {
        assertEdge("+proj=putp4p +R=1", 3.832261942648194, 3.8322619426481945,
                -2.2638455417555932, -90.0, "-1.0000000000000104");
        assertTrue("the second site's edge must be below C_y, which is what 'binds first' means",
                3.832261942648194 < 3.883251825);

        ProjectionException atCy = assertRefuses("+proj=putp4p +R=1", EASTING, 3.883251825);
        assertTrue("at C_y the second site's argument is -1.13137085, 13% past the domain; got: "
                + atCy.getMessage(), atCy.getMessage().contains("-1.13137085"));

        assertEquals("2 930 of 10 001 northings evenly spaced across [0, C_y] must refuse",
                2930, refusalsAcross("+proj=putp4p +R=1", 3.883251825));
    }

    // --------------------------------------------------------------------- sts.cpp, one site

    /**
     * {@code sts.cpp:44}, {@code Q->tan_mode ? atan(xy.y) : aasin(xy.y)} - one line serving four
     * projections, of which the three with {@code tan_mode == 0} reach the arcsine. Two are
     * exercised here through the shared {@link SineTangentSeriesProjection} kernel:
     * {@code +proj=kav5} ({@code C_y = 1.50488}) and {@code +proj=qua_aut} ({@code C_y = 2}).
     *
     * <p>The fourth, {@code +proj=fouc}, has {@code tan_mode == 1} and so never reaches the
     * arcsine at all. It is the control: at a northing of 3, where both of the others refuse,
     * {@code fouc} answers - and it answers with PROJ's number. Without that leg this test could
     * pass on a change that made the whole {@code sts} family refuse.
     *
     * <p>Note both of the answers below are latitudes outside {@code [-90, 90]}:
     * {@code 121.89510000000001} for {@code kav5} at its edge and {@code 180} for {@code qua_aut}
     * at its. Those are upstream's, checked against {@code proj -I}, and we return them.
     *
     * <p>Reference: {@code proj -I -f "%.17g" +proj=kav5 +R=1} and the same for {@code qua_aut} and
     * {@code fouc} (PROJ 9.8.1); each pair of numbers below is one line of its output.
     */
    @Test
    public void sineTangentSeriesRefusesPastOneTolInEveryNonTanVariant() {
        assertEdge("+proj=kav5 +R=1", 1.504880000000015, 1.5048800000000153,
                -7.3778017460166143e-16, 121.89510000000001, "1.0000000000000102");
        assertEdge("+proj=qua_aut +R=1", 2.00000000000002, 2.0000000000000204,
                -3.5083546492674384e-16, 180.0, "1.0000000000000102");

        // Well past the edge in both, so neither leg rests on a single bit.
        assertRefuses("+proj=kav5 +R=1", EASTING, 4.0);
        assertRefuses("+proj=qua_aut +R=1", EASTING, 3.0);

        // The tan_mode control: same kernel, same line, atan instead of aasin, and it answers.
        ProjCoordinate lp = invert("+proj=fouc +R=1", EASTING, 3.0);
        assertEquals("fouc takes the atan arm of sts.cpp:44 and must still answer at a northing "
                        + "both of the others refuse; PROJ's longitude",
                -48.414933688554576, Math.toDegrees(lp.x), DEGREE_TOL);
        assertEquals("and PROJ's latitude, which is itself out of range and is upstream's",
                112.61986494804043, Math.toDegrees(lp.y), DEGREE_TOL);
    }

    // ---------------------------------------------------------------- the cause, all nine at once

    /**
     * Every one of the nine refuses with {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} and not with
     * {@link ErrorCause#NUMERICAL_FAILURE}. The distinction is the whole point of having two
     * causes: the point is off the map, which is the caller's doing, rather than our arithmetic
     * having gone wrong. {@code asinChecked} reserves {@code NUMERICAL_FAILURE} for a {@code NaN}
     * argument, which is a different situation and is pinned in
     * {@link org.locationtech.proj4j.errors.NonConvergenceTest}.
     *
     * <p>{@code putp2}'s second site is absent from this list because it cannot be made to refuse
     * by any finite northing - see {@link #putninsP2SecondSiteReachesExactlyOneAndClamps()}.
     */
    @Test
    public void allTheseRefusalsSayTheCoordinateIsOffTheMapNotThatArithmeticFailed() {
        Object[][] sites = {
            {"+proj=fouc_s +R=1", 2.0},              // fouc_s.cpp:48
            {"+proj=mbt_fps +R=1", 3.0},             // mbt_fps.cpp:41
            {"+proj=mbt_fps +R=1", 1.319356452},     // mbt_fps.cpp:44
            {"+proj=nell +R=1", 3.0},                // nell.cpp:36
            {"+proj=putp2 +R=1", 2.0},               // putp2.cpp:45
            {"+proj=putp4p +R=1", 5.0},              // putp4p.cpp:35
            {"+proj=putp4p +R=1", 3.883251825},      // putp4p.cpp:39
            {"+proj=kav5 +R=1", 4.0},                // sts.cpp:44, kav5
            {"+proj=qua_aut +R=1", 3.0},             // sts.cpp:44, qua_aut
        };
        for (Object[] site : sites) {
            String definition = (String) site[0];
            double northing = (Double) site[1];
            ProjectionException e = assertRefuses(definition, EASTING, northing);
            assertEquals(definition + " at y=" + northing + ": wrong ErrorCause",
                    ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
            assertTrue(definition + " at y=" + northing + ": the message must name the argument "
                            + "that left the domain, so a caller can see how far out it was; got: "
                            + e.getMessage(),
                    e.getMessage().startsWith("asin(")
                            && e.getMessage().contains("ONE_TOL"));
        }
    }

    // ------------------------------------------------------------------------------- plumbing

    private static Projection projection(String definition) {
        return FACTORY.createFromParameters("t", definition).getProjection();
    }

    /**
     * Inverts through the radian hook rather than through {@code CoordinateTransform}. That is
     * deliberate, and it is the same choice {@code NonConvergenceTest} documents: the funnel's own
     * domain checks would otherwise decide some of these cases before the kernel ran, and these
     * assertions are about the kernel.
     */
    private static ProjCoordinate invert(String definition, double x, double y) {
        ProjCoordinate out = new ProjCoordinate(POISON, POISON);
        projection(definition).projectInverse(x, y, out);
        return out;
    }

    /** As in {@code NonConvergenceTest}: a destination value no kernel could compute. */
    private static final double POISON = 1e300;

    private static ProjectionException assertRefuses(String definition, double x, double y) {
        ProjCoordinate out = new ProjCoordinate(POISON, POISON);
        try {
            projection(definition).projectInverse(x, y, out);
            fail(definition + " must refuse the northing " + y + ", as PROJ 9.8.1 does; it "
                    + "answered lon=" + Math.toDegrees(out.x) + " lat=" + Math.toDegrees(out.y));
            return null;
        } catch (ProjectionException expected) {
            assertTrue(definition + " must leave the destination untouched when it refuses, not "
                            + "fill it with a pole; found y=" + out.y,
                    out.y == POISON || Math.abs(Math.abs(out.y) - ProjectionMath.HALFPI) > 1e-9);
            return expected;
        }
    }

    /**
     * Asserts both sides of a refusal edge at once: the last northing that answers, with PROJ's
     * coordinate for it, and the next {@code double} up, which must refuse and must name the
     * argument that left the domain.
     */
    private static void assertEdge(String definition, double lastAnswering, double firstRefusing,
            double projLonDeg, double projLatDeg, String argumentInMessage) {
        assertEquals(definition + ": the two probes must be consecutive doubles, or the edge is "
                        + "not being straddled",
                Double.doubleToRawLongBits(lastAnswering) + 1,
                Double.doubleToRawLongBits(firstRefusing));

        ProjCoordinate lp = invert(definition, EASTING, lastAnswering);
        assertEquals(definition + " at y=" + lastAnswering + " must answer with PROJ's longitude",
                projLonDeg, Math.toDegrees(lp.x), Math.max(DEGREE_TOL, Math.abs(projLonDeg) * 1e-14));
        assertEquals(definition + " at y=" + lastAnswering + " must answer with PROJ's latitude",
                projLatDeg, Math.toDegrees(lp.y), DEGREE_TOL);

        ProjectionException e = assertRefuses(definition, EASTING, firstRefusing);
        assertEquals(definition + " at y=" + firstRefusing + ": wrong ErrorCause",
                ErrorCause.COORDINATE_OUT_OF_DOMAIN, e.cause());
        assertTrue(definition + " at y=" + firstRefusing + ": the message must name the argument "
                        + argumentInMessage + "; got: " + e.getMessage(),
                e.getMessage().contains(argumentInMessage));
    }

    /** {@code putp2.cpp:48}'s argument, written out so the test can scan it directly. */
    private static double secondSiteArgument(double northing) {
        double phi = Math.asin(northing / 1.71848);
        return (phi + Math.sin(phi) * (Math.cos(phi) - 1.)) / 0.6141848493043784;
    }

    /**
     * Counts how many of 10 001 northings evenly spaced across {@code [0, C_y]} refuse. Used where
     * a second site rejects part of the interval its first site accepts, so the size of that part
     * is a number rather than an adjective.
     */
    private static int refusalsAcross(String definition, double cy) {
        Projection p = projection(definition);
        int refusals = 0;
        for (int i = 0; i <= 10000; i++) {
            try {
                p.projectInverse(0.0, cy * i / 10000.0, new ProjCoordinate());
            } catch (ProjectionException e) {
                refusals++;
            }
        }
        return refusals;
    }
}
