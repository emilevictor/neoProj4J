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
 *******************************************************************************/
package org.locationtech.proj4j.proj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.pipeline.Pipeline;
import org.locationtech.proj4j.pipeline.PipelineFactory;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * {@code +over} and {@code +pm}, in the <em>composed</em> transform: the two places PROJ's
 * {@code inv_finalize} and {@code fwd_prepare} order the prime meridian against the
 * {@code adjlon}, and the one place PROJ inserts no forward step at all.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>{@code +proj=calcofi} is the only projection in {@code core/src/main} that sets
 * {@code +over} ({@code calcofi.cpp:141} hard-codes {@code P->over = 1}). Its inverse
 * therefore returns a longitude past the antimeridian on purpose — on a map drawn past the
 * antimeridian, 200&deg;E and 160&deg;W are different places on the page. The source inverse
 * kept that turn and the <b>target's</b> forward wrap threw it away, because {@code over} on
 * the forward leg is read off the target and the target was {@code +proj=longlat}:
 *
 * <pre>
 * +proj=calcofi +R=6400000 -&gt; +proj=longlat +R=6400000, at (-200, 100)
 *   composed transform  :  152.4550931861857   &lt;- wrong, exactly +360 off
 *   source inverse alone: -207.5449068138143   &lt;- right
 * </pre>
 *
 * <h2>The two oracle runs, and why they are not in tension</h2>
 *
 * <p>PROJ 9.8.1 as installed (<i>Rel. 9.8.1, April 10th, 2026</i>). The first is this test's
 * reference; the second and third are the reason the fix is <b>not</b> "stop wrapping on the
 * geographic side":
 *
 * <pre>
 * $ echo "-200 100" | cs2cs -f "%.9f" +proj=calcofi +R=6400000 +to +proj=longlat +R=6400000
 * -207.544906814	81.314089279
 *
 * $ echo "-170 0" | cs2cs -f "%.15f" +proj=longlat +ellps=bessel +type=crs \
 *                             +to +proj=longlat +ellps=bessel +pm=jakarta +type=crs
 * 83.192280555555556	0.000000000000000
 *
 * $ echo "-200 100" | cs2cs -f "%.9f" +proj=calcofi +R=6400000 +to +proj=longlat +R=6400000 +lon_0=0
 * 152.455093186	81.314089279
 * </pre>
 *
 * <p>{@code projinfo} says why all three are consistent. A plain Greenwich geographic target
 * contributes <b>no step</b>, so nothing re-wraps what the inverse produced:
 *
 * <pre>
 * +proj=pipeline
 *   +step +inv +proj=calcofi +R=6400000
 *   +step +proj=unitconvert +xy_in=rad +xy_out=deg
 * </pre>
 *
 * <p>whereas a geographic target carrying {@code +pm} — or, by <em>presence</em>,
 * {@code +lon_0} — is a real forward step, which runs {@code fwd_prepare} and therefore wraps:
 *
 * <pre>
 * +proj=pipeline
 *   +step +proj=longlat +ellps=bessel +pm=jakarta
 *   +step +proj=unitconvert +xy_in=rad +xy_out=deg
 * </pre>
 *
 * <p>The third command above is the sharpest evidence: {@code 152.455093186} is <em>PROJ's own
 * answer</em> when the target geographic CRS is written with an explicit {@code +lon_0=0}. The
 * fork's old number was not bad arithmetic, it was the wrong pipeline.
 *
 * @see Projection#setOver(boolean)
 */
public class OverAndPrimeMeridianOrderTest {

    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();

    private ProjCoordinate transform(String src, String tgt, double x, double y) {
        CoordinateReferenceSystem s = crsFactory.createFromParameters("src", src);
        CoordinateReferenceSystem t = crsFactory.createFromParameters("tgt", tgt);
        CoordinateTransform ct = ctFactory.createTransform(s, t);
        ProjCoordinate out = new ProjCoordinate();
        ct.transform(new ProjCoordinate(x, y), out);
        return out;
    }

    /**
     * The regression. {@code cs2cs} gives {@code -207.544906813814521 81.314089278595233};
     * {@code proj -I -f "%.15f" +proj=calcofi +R=6400000} gives the same, so the composed
     * transform and the bare inverse agree in PROJ and must agree here.
     */
    @Test
    public void calcofiSphereKeepsItsTurnThroughAGeographicTarget() {
        ProjCoordinate out = transform("+proj=calcofi +R=6400000", "+proj=longlat +R=6400000",
                -200, 100);
        assertEquals(-207.544906813814521, out.x, 1e-9);
        assertEquals(81.314089278595233, out.y, 1e-9);
    }

    /** The {@code +ellps=GRS80} twin; {@code cs2cs} gives {@code -207.447024503690756}. */
    @Test
    public void calcofiEllipsoidKeepsItsTurnThroughAGeographicTarget() {
        ProjCoordinate out = transform("+proj=calcofi +ellps=GRS80", "+proj=longlat +ellps=GRS80",
                -200, 100);
        assertEquals(-207.447024503690756, out.x, 1e-9);
    }

    /**
     * The turn must survive as a turn, not be re-manufactured: the composed transform and
     * {@code Projection.inverseProject} on the same definition must give the same longitude.
     * That equality is what the defect broke — they were 360&deg; apart.
     */
    @Test
    public void composedTransformAgreesWithTheBareSourceInverse() {
        CoordinateReferenceSystem src =
                crsFactory.createFromParameters("src", "+proj=calcofi +R=6400000");
        ProjCoordinate bare = new ProjCoordinate();
        src.getProjection().inverseProject(new ProjCoordinate(-200, 100), bare);

        ProjCoordinate composed = transform("+proj=calcofi +R=6400000",
                "+proj=longlat +R=6400000", -200, 100);
        assertEquals(bare.x, composed.x, 0.0);
        assertEquals(bare.y, composed.y, 0.0);
    }

    /**
     * #99's row, which must not move back. {@code esri:2934} is
     * {@code +proj=merc +lat_ts=0 +lon_0=216.8077194444444 +k=0.997 +x_0=3900000 +y_0=900000
     * +ellps=bessel +pm=jakarta}, the only shipped definition family combining a {@code +lon_0}
     * past 180&deg; with a {@code +pm}. Round-tripping a point on its central meridian used to
     * recover {@code 216.80771944444436} — the right meridian, named by a number outside the
     * range the convention allows — and #99 brought it back to {@code -143.19228055555564}.
     *
     * <p>The wrap that fixed it was the target's, which this change removes; the wrap that
     * fixes it now is the <em>source's</em> own, applied after the prime meridian instead of
     * before it. Same answer, from the step PROJ puts it in.
     */
    @Test
    public void esri2934RoundTripStaysInsideTheAntimeridian() {
        double lon = -143.19228055555564;
        CoordinateReferenceSystem wgs84 =
                crsFactory.createFromParameters("wgs84", "+proj=longlat +datum=WGS84");
        CoordinateReferenceSystem esri = crsFactory.createFromName("esri:2934");

        ProjCoordinate projected = new ProjCoordinate();
        ctFactory.createTransform(wgs84, esri).transform(new ProjCoordinate(lon, 0), projected);
        ProjCoordinate back = new ProjCoordinate();
        ctFactory.createTransform(esri, wgs84).transform(projected, back);

        assertTrue("recovered longitude " + back.x + " is outside +/-180",
                back.x >= -180.0 && back.x <= 180.0);
        assertEquals(lon, back.x, 1e-9);
    }

    /**
     * The other half of the discriminator: a geographic target that carries {@code +pm} <b>is</b>
     * a forward step in PROJ, so it still wraps. Oracle:
     * {@code 83.192280555555556}.
     */
    @Test
    public void geographicTargetWithAPrimeMeridianStillWraps() {
        ProjCoordinate out = transform("+proj=longlat +ellps=bessel",
                "+proj=longlat +ellps=bessel +pm=jakarta", -170, 0);
        assertEquals(83.192280555555556, out.x, 1e-9);
    }

    /** And the in-range case on the same pair, which needs no wrap: {@code -6.807719444444436}. */
    @Test
    public void geographicTargetWithAPrimeMeridianDoesNotWrapWhatIsAlreadyInRange() {
        ProjCoordinate out = transform("+proj=longlat +ellps=bessel",
                "+proj=longlat +ellps=bessel +pm=jakarta", 100, 0);
        assertEquals(-6.807719444444436, out.x, 1e-9);
    }

    /**
     * Neither side of a plain-geographic pair is a step, so an out-of-range longitude passes
     * through untouched. Oracle &mdash; and note {@code projinfo} calls this pair
     * {@code +proj=noop}, so there is nowhere for a wrap to happen:
     *
     * <pre>
     * $ echo "200 10" | cs2cs -f "%.12f" +proj=longlat +R=6400000 +type=crs \
     *                                   +to +proj=longlat +R=6400000 +type=crs
     * 200.000000000000	10.000000000000 0.000000000000
     * </pre>
     */
    @Test
    public void plainGeographicPairIsANoOpAndDoesNotWrap() {
        ProjCoordinate out = transform("+proj=longlat +R=6400000", "+proj=longlat +R=6400000",
                200, 10);
        assertEquals(200.0, out.x, 1e-9);
        assertEquals(10.0, out.y, 1e-9);
    }

    /**
     * The discriminator for {@code +lon_0}, which earns a step by being written down at all.
     * proj4j approximates presence by value, so this pins the value case, which is the one a
     * shipped definition could ever produce. Oracle:
     *
     * <pre>
     * $ echo "-200 100" | cs2cs -f "%.9f" +proj=calcofi +R=6400000 \
     *                                     +to +proj=longlat +R=6400000 +lon_0=0
     * 152.455093186	81.314089279
     * </pre>
     *
     * <p>with {@code +lon_0=50} shifting that by a further 50&deg;, to
     * {@code 102.455093186}.
     */
    @Test
    public void geographicTargetWithANonZeroCentralMeridianStillWraps() {
        ProjCoordinate out = transform("+proj=calcofi +R=6400000",
                "+proj=longlat +R=6400000 +lon_0=50", -200, 100);
        assertEquals(102.4550931861857, out.x, 1e-9);
    }

    /**
     * A geographic <em>source</em> with {@code +pm}, the mirror of the case above:
     * {@code inv_finalize} adds {@code from_greenwich} before its {@code adjlon}, so
     * {@code +pm=jakarta} at 100&deg; east of Jakarta comes back as
     * {@code -153.192280555555556} rather than {@code 206.807719444444436}.
     */
    @Test
    public void geographicSourceWithAPrimeMeridianWrapsAfterTheShift() {
        ProjCoordinate out = transform("+proj=longlat +ellps=bessel +pm=jakarta",
                "+proj=longlat +ellps=bessel", 100, 0);
        assertEquals(-153.192280555555556, out.x, 1e-9);
    }

    // ----------------------------------------------------------------------------------------
    // The pipeline engine. Covered here rather than trusted to the gie corpus, because the
    // corpus contains exactly TWO `+pm=` operations -- both `+proj=krovak ... +pm=ferro` at
    // gie/builtins.gie -- and the manifest expects both to fail for an unrelated reason, so a
    // 7,971-row sweep says nothing at all about the ordering these two tests pin.
    // ----------------------------------------------------------------------------------------

    /**
     * {@code Cs2csOperator} read {@code +over} from the proj-string only, so it never saw the
     * flag {@code calcofi.cpp:141} sets from inside {@code initialize()} and wrapped the turn
     * straight back off. Oracle, the same bare inverse PROJ runs for this operation:
     *
     * <pre>
     * $ echo "-200 100" | proj -I -f "%.15f" +proj=calcofi +R=6400000
     * -207.544906813814521	81.314089278595233
     * </pre>
     */
    @Test
    public void thePipelineEngineHonoursCalcofisInternallySetOverFlag() {
        Pipeline p = new PipelineFactory().create("+proj=calcofi +R=6400000");
        double[] out = p.inverse(new double[] {-200, 100, 0, 0});
        assertEquals(-207.544906813814521, out[0] * ProjectionMath.RTD, 1e-9);
        assertEquals(81.314089278595233, out[1] * ProjectionMath.RTD, 1e-9);
    }

    /**
     * The prime meridian is added before the wrap here too, and on a projected operation it is
     * now added once — by {@link Projection#inverseProjectRadians} — rather than by this class
     * on top of it. The easting is 100&deg; of bessel-radius mercator, which the Jakarta
     * meridian pushes past 180&deg;:
     *
     * <pre>
     * $ echo "11131949.079327108 0" | proj -I -f "%.15f" +proj=merc +ellps=bessel +pm=jakarta
     * -153.180679506069765	0.000000000000000
     *
     * $ echo "11131949.079327108 0" | proj -I -f "%.15f" +proj=merc +ellps=bessel
     * 100.011601049485805	0.000000000000000
     * </pre>
     *
     * <p>and {@code 100.011601049485805 + 106.807719444444444 - 360 = -153.180679506069765}
     * exactly, which is the ordering stated rather than merely the value.
     */
    @Test
    public void thePipelineEngineAddsThePrimeMeridianBeforeItWraps() {
        Pipeline p = new PipelineFactory().create("+proj=merc +ellps=bessel +pm=jakarta");
        double[] out = p.inverse(new double[] {11131949.079327108, 0, 0, 0});
        assertEquals(-153.180679506069765, out[0] * ProjectionMath.RTD, 1e-9);
    }
}
