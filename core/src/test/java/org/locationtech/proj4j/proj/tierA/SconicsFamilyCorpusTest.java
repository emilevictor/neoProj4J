/*******************************************************************************
 * Copyright 2026
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

package org.locationtech.proj4j.proj.tierA;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

/**
 * All seven members of {@code 9.8.1:src/projections/sconics.cpp}, against the corpus.
 *
 * <p><b>Why a whole family is here when only {@code tissot} was in scope.</b>
 * {@code tissot} was listed as free — "the class already exists and is complete, un-comment
 * the registration and verify" — and the verify step failed all sixteen of its rows by 2,336
 * km. The cause was not in {@code TissotProjection} but in {@code SimpleConicProjection},
 * which every member inherits: it ignored {@code +lat_1} and {@code +lat_2} entirely, using
 * hard-coded 30&deg; and 60&deg; behind a {@code FIXME}. All fourteen {@code sconics}
 * operations in {@code builtins.gie} use {@code +lat_1=0.5 +lat_2=2}, so the family's 112
 * assertions were failing on one defect.
 *
 * <p>Six of the seven — {@code euler}, {@code murd1}, {@code murd2}, {@code murd3},
 * {@code pconic}, {@code vitk1} — were <b>already registered and live</b>, returning
 * plausible coordinates for the wrong standard parallels. That is a silent wrong answer, the
 * failure mode this project's third non-negotiable exists to eliminate, and it is worth more
 * than the assertion count: a caller had no way to detect it.
 *
 * <p>Two further defects in the same file's inverse, both from the same mishandling of C's
 * mutate-the-parameter idiom, are covered by {@link #everyMemberRoundTripsThroughItsInverse}:
 * {@code atan2} read the raw northing instead of {@code rho_0 - y}, and the {@code n < 0}
 * branch negated variables that were overwritten two statements later.
 */
public class SconicsFamilyCorpusTest {

    /** Each member has 2 operations of 8 rows: 4 forward and 4 inverse. */
    private static final int ROWS_PER_MEMBER = 16;

    @Test
    public void euler() {
        GieCheck.assertAllRows("builtins.gie", "euler", ROWS_PER_MEMBER);
    }

    @Test
    public void murd1() {
        GieCheck.assertAllRows("builtins.gie", "murd1", ROWS_PER_MEMBER);
    }

    @Test
    public void murd2() {
        GieCheck.assertAllRows("builtins.gie", "murd2", ROWS_PER_MEMBER);
    }

    @Test
    public void murd3() {
        GieCheck.assertAllRows("builtins.gie", "murd3", ROWS_PER_MEMBER);
    }

    @Test
    public void pconic() {
        GieCheck.assertAllRows("builtins.gie", "pconic", ROWS_PER_MEMBER);
    }

    @Test
    public void vitk1() {
        GieCheck.assertAllRows("builtins.gie", "vitk1", ROWS_PER_MEMBER);
    }

    @Test
    public void tissot() {
        GieCheck.assertAllRows("builtins.gie", "tissot", ROWS_PER_MEMBER);
    }

    /**
     * The regression test for the hard-coding itself, independent of the corpus: two
     * definitions differing only in {@code +lat_1}/{@code +lat_2} must not project a point to
     * the same place.
     *
     * <p>This is the assertion that would have caught the defect. Every corpus row used the
     * same parallels, so no single row could distinguish "reads the parameters" from "ignores
     * them and happens to be configured correctly" — only a comparison between two different
     * parameterisations can.
     */
    @Test
    public void standardParallelsChangeTheResult() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            ProjCoordinate a = project("+proj=" + name + " +a=6400000 +lat_1=0.5 +lat_2=2");
            ProjCoordinate b = project("+proj=" + name + " +a=6400000 +lat_1=30 +lat_2=60");
            double moved = Math.hypot(a.x - b.x, a.y - b.y);
            assertTrue(name + ": +lat_1/+lat_2 must change the projected coordinate, but"
                    + " (0.5, 2) and (30, 60) both gave (" + a.x + ", " + a.y + ")."
                    + " SimpleConicProjection is ignoring the parameters again.",
                    moved > 1000.0);
        }
    }

    /**
     * {@code sconics.cpp:44-51} rejects a definition with no {@code +lat_1}/{@code +lat_2}.
     * With both at their {@code 0.0} default, {@code del} and {@code sig} are zero and
     * {@code SimpleConicProjection.initialize()}'s {@code |del| < EPS || |sig| < EPS} test
     * would reject them on its own; since the presence check landed in
     * {@code Proj4Parser.setParameters} this case is now refused a step earlier, and names
     * {@code lat_1} — which is also the parameter PROJ names when both are absent.
     */
    @Test
    public void missingStandardParallelsIsRejectedRatherThanGuessed() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            try {
                project("+proj=" + name + " +a=6400000");
                fail(name + ": a definition with no +lat_1/+lat_2 must be rejected, not"
                        + " silently given default parallels");
            } catch (InvalidValueException expected) {
                assertTrue(name + ": the message should name the parameters, was: "
                        + expected.getMessage(),
                        expected.getMessage().contains("lat_1"));
            }
        }
    }

    /**
     * <b>Exactly one</b> standard parallel is the case the {@code |del| < EPS} test cannot
     * see, and it is the one that mattered. A single parallel leaves {@code del} and
     * {@code sig} at half of it, both non-zero, so the setup looked well-formed and was
     * answered as though the other parallel had been typed as 0.
     *
     * <p>Measured on {@code +proj=murd2 +a=6400000} at 10E 20N: we returned
     * {@code (1016992.395865934, 2297381.269569689)} where PROJ 9.8.1 exits with "Missing
     * parameter: lat_2 should be specified". That is 1,191 km from the
     * {@code (1122158.107229810, 3483769.275111472)} that {@code +lat_1=30 +lat_2=60} gives —
     * a different question answered, not a rounding error, and nothing in the output said so.
     *
     * <p>The check lives in {@code Proj4Parser} rather than in the projection because
     * upstream tests <em>presence</em> ({@code pj_param}'s {@code t} sigil) and a
     * {@link Projection} cannot see presence: an omitted {@code +lat_2} and an explicit
     * {@code +lat_2=0} are both {@code 0.0} in the same field by then.
     */
    @Test
    public void exactlyOneStandardParallelIsRefusedLikeUpstream() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            assertRefusedNaming(name, "+proj=" + name + " +a=6400000 +lat_1=30", "lat_2");
            assertRefusedNaming(name, "+proj=" + name + " +a=6400000 +lat_2=60", "lat_1");
        }
    }

    /**
     * The other side of the same coin, and the reason the check above is a presence test
     * rather than a zero test. <b>PROJ accepts an explicit zero standard parallel and
     * answers.</b> A guard that read {@code 0.0} as "absent" would refuse these two, which
     * trades upstream's defect for a locally invented divergence — the harder of the two to
     * defend, since upstream's is at least reproducible.
     *
     * <p>Both rows measured on the binaries, {@code +proj=murd2 +a=6400000} forward at
     * 10E 20N. {@code +lat_1=30 +lat_2=0} matches PROJ to every printed digit;
     * {@code +lat_1=0 +lat_2=60} differs by 2 &micro;m in the northing's last ulp
     * (PROJ prints {@code 2604267.019108736}), which is why the tolerance below is 1e-5 m
     * and not exact.
     *
     * <p>Building a sconics projection <em>directly from Java</em> rather than from a
     * definition string bypasses the presence check, because the values are then all a caller
     * has. That is a stated limitation, not an oversight; see
     * {@code SimpleConicProjection.initialize()}'s comment.
     */
    @Test
    public void anExplicitZeroParallelIsAcceptedLikeUpstream() {
        ProjCoordinate a = projectAt("+proj=murd2 +a=6400000 +lat_1=30 +lat_2=0", 10.0, 20.0);
        assertEquals("+lat_2=0 easting", 1016992.395865934, a.x, 1e-5);
        assertEquals("+lat_2=0 northing", 2297381.269569689, a.y, 1e-5);

        ProjCoordinate b = projectAt("+proj=murd2 +a=6400000 +lat_1=0 +lat_2=60", 10.0, 20.0);
        assertEquals("+lat_1=0 easting", 928382.344182429, b.x, 1e-5);
        assertEquals("+lat_1=0 northing", 2604267.019108736, b.y, 1e-5);

        // Every member must accept them, not just murd2 -- the guard is on the shared base.
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            projectAt("+proj=" + name + " +a=6400000 +lat_1=30 +lat_2=0", 10.0, 20.0);
            projectAt("+proj=" + name + " +a=6400000 +lat_1=0 +lat_2=60", 10.0, 20.0);
        }
    }

    private static void assertRefusedNaming(String name, String def, String parameter) {
        try {
            project(def);
            fail(name + ": \"" + def + "\" supplies one standard parallel and must be"
                    + " refused, as PROJ refuses it. Accepting it answers as though "
                    + parameter + "=0 had been given.");
        } catch (InvalidValueException expected) {
            assertTrue(name + ": the message should name " + parameter + ", was: "
                    + expected.getMessage(),
                    expected.getMessage().contains(parameter));
        }
    }

    private static ProjCoordinate projectAt(String def, double lon, double lat) {
        Projection p = new CRSFactory().createFromParameters("t", def).getProjection();
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(lon, lat), out);
        return out;
    }

    /**
     * Forward then inverse must return the input. This is what fails if {@code atan2} is fed
     * the raw northing rather than {@code rho_0 - y}: the easting survives but the longitude
     * comes back displaced by an amount of order {@code rho_0}, which is metres-scale, not
     * rounding-scale.
     */
    @Test
    public void everyMemberRoundTripsThroughItsInverse() {
        for (String name : new String[] {"euler", "murd1", "murd2", "murd3", "pconic",
                "tissot", "vitk1"}) {
            String def = "+proj=" + name + " +a=6400000 +lat_1=0.5 +lat_2=2";
            Projection p = new CRSFactory().createFromParameters("t", def).getProjection();
            ProjCoordinate xy = new ProjCoordinate();
            p.project(new ProjCoordinate(2.0, 1.0), xy);
            ProjCoordinate back = new ProjCoordinate();
            p.inverseProject(xy, back);
            assertEquals(name + " round trip longitude", 2.0, back.x, 1e-9);
            assertEquals(name + " round trip latitude", 1.0, back.y, 1e-9);
        }
    }

    private static ProjCoordinate project(String def) {
        Projection p = new CRSFactory().createFromParameters("t", def).getProjection();
        ProjCoordinate out = new ProjCoordinate();
        p.project(new ProjCoordinate(2.0, 1.0), out);
        return out;
    }
}
