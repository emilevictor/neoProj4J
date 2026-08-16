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
package org.locationtech.proj4j.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.proj4j.api.Crs;
import org.locationtech.proj4j.api.CrsOperationCandidate;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.api.ProjContext;

/**
 * Covers {@code authority_to_authority_preference}, section {@link PjdxFormat#S_AUTHORITY_PREFERENCE}
 * of the index, and the candidate list it narrows.
 *
 * <h2>What the column actually means</h2>
 *
 * It is <strong>a search order, not a filter set</strong>. PROJ's
 * {@code DatabaseContext::getAllowedAuthorities} (9.8.1 {@code src/iso19111/factory.cpp}) resolves the
 * cell with a four-query cascade — {@code (src,tgt)}, then {@code (src,'any')}, then
 * {@code ('any',tgt)}, then {@code ('any','any')} — and
 * {@code CoordinateOperationFactory}'s {@code findOpsInRegistryDirect} then walks the resulting list
 * <em>prefix by prefix</em>, stopping at the first prefix that yields any non-deprecated operation.
 * {@code PROJ} never terminates the walk on its own, because it only ever contributes synthesised
 * operations; {@code any} means "every authority", i.e. stop narrowing.
 *
 * <p>Reading it as a set — "keep rows whose authority is in this list" — gives a different and wrong
 * answer. On {@code EPSG:4277 → EPSG:4326} the list is {@code PROJ,EPSG,NKG}: as a set that keeps the
 * EPSG rows and drops the ESRI ones, which happens to be right; on a pair where EPSG publishes
 * nothing, the set reading returns nothing at all where PROJ falls through to the next authority.
 *
 * <h2>The shipped rows</h2>
 *
 * Six, verified with {@code sqlite3 proj.db 'select * from authority_to_authority_preference'} against
 * the same 9.8.1 database the index is transcoded from. Note there is no {@code (x,'any')} row and no
 * {@code ('any','any')} row in the shipped data, so only the cascade's first and third queries can
 * match today — the other two are ported because leaving them out would be a guess about data PROJ is
 * free to add.
 */
public class PjdxAuthorityPreferenceTest {

    private static PjdxDatabase db;
    private static ProjContext ctx;

    @BeforeClass
    public static void openTheRealIndex() throws IOException {
        db = Proj4jDb.open();
        assertNotNull("the shipped .pjdx must be on this module's own test classpath", db);
        ctx = ProjContext.builder().database(db).build();
    }

    @AfterClass
    public static void closeIt() throws IOException {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ the six shipped rows

    /**
     * All six rows, by value. {@code EPSG|EPSG} is {@code PROJ,EPSG,NKG} rather than the
     * {@code PROJ,EPSG} that {@code data/sql/customizations.sql} seeds: the NKG script
     * {@code data/sql/nkg_post_customizations.sql} runs a later pass that appends {@code ,NKG} with
     * an {@code UPDATE} and adds the sixth row with an {@code INSERT}. Transcribing the seed file
     * instead of the built database would therefore lose both. (There is no
     * {@code authority_to_authority_preference.sql} in PROJ 9.8.1; an earlier version of this
     * comment named one. The five seed rows are at {@code customizations.sql:508-521}.)
     */
    @Test
    public void everyShippedRowIsPresentAndInOrder() {
        assertEquals(Arrays.asList("PROJ", "EPSG", "any"), db.allowedAuthorities("any", "EPSG"));
        assertEquals(Arrays.asList("PROJ", "EPSG", "NKG"), db.allowedAuthorities("EPSG", "EPSG"));
        assertEquals(Arrays.asList("PROJ", "EPSG"), db.allowedAuthorities("PROJ", "EPSG"));
        assertEquals(Arrays.asList("PROJ", "IGNF", "EPSG"), db.allowedAuthorities("IGNF", "EPSG"));
        assertEquals(Arrays.asList("PROJ", "ESRI", "EPSG"), db.allowedAuthorities("ESRI", "EPSG"));
        assertEquals(Arrays.asList("NKG", "PROJ", "EPSG"), db.allowedAuthorities("NKG", "EPSG"));
    }

    /** Order is the payload, so the returned list must not be reorderable by a caller. */
    @Test
    public void theListIsUnmodifiable() {
        List<String> order = db.allowedAuthorities("EPSG", "EPSG");
        try {
            order.add("ESRI");
            org.junit.Assert.fail("the preference order is the payload; it must not be mutable");
        } catch (UnsupportedOperationException expected) {
            // as intended
        }
    }

    // ------------------------------------------------------------------ the four-query cascade

    /**
     * Query 1 wins when an exact {@code (source, target)} row exists: {@code ESRI|EPSG} must give
     * ESRI's own order, not the {@code any|EPSG} fallback, and the two differ in their second element.
     */
    @Test
    public void anExactRowBeatsTheAnySourceFallback() {
        assertEquals(Arrays.asList("PROJ", "ESRI", "EPSG"), db.allowedAuthorities("ESRI", "EPSG"));
        assertFalse("if query 1 were skipped this would be the any|EPSG row instead",
                db.allowedAuthorities("any", "EPSG").equals(db.allowedAuthorities("ESRI", "EPSG")));
    }

    /**
     * Query 3 catches a source authority with no row of its own. {@code IAU_2015} is a real authority
     * in this index and has no preference row, so it must fall through to {@code any|EPSG}.
     */
    @Test
    public void anUnlistedSourceFallsThroughToAnySource() {
        assertEquals(Arrays.asList("PROJ", "EPSG", "any"),
                db.allowedAuthorities("IAU_2015", "EPSG"));
    }

    /**
     * Queries 2 and 4 cannot fire on the shipped data, so an unlisted <em>target</em> has no row at
     * all and the answer is empty — which the selector reads as "no preference expressed", leaving the
     * candidate list untouched. Returning {@code any|EPSG} here would silently apply EPSG's preference
     * to a pair it says nothing about.
     */
    @Test
    public void anUnlistedTargetHasNoPreferenceAtAll() {
        assertTrue(db.allowedAuthorities("EPSG", "ESRI").isEmpty());
        assertTrue(db.allowedAuthorities("ESRI", "ESRI").isEmpty());
        assertTrue(db.allowedAuthorities("EPSG", "IAU_2015").isEmpty());
    }

    /** A string that is not in the pool at all is a miss, not a crash. */
    @Test
    public void authoritiesAbsentFromTheStringPoolAreEmpty() {
        assertTrue(db.allowedAuthorities("NO-SUCH-AUTHORITY", "ALSO-NOT-THERE").isEmpty());
        assertTrue(db.allowedAuthorities(null, null).isEmpty());
    }

    // ------------------------------------------------------------------ what it changes

    /**
     * <strong>{@code EPSG:4277 → EPSG:4326} no longer offers the two ESRI operations PROJ excludes.</strong>
     * <p>
     * {@code projinfo -s EPSG:4277 -t EPSG:4326 --spatial-test intersects} at 9.8.1 lists nine: eight
     * EPSG operations and the synthesised ballpark. This library lists ten — the same nine plus
     * {@code EPSG:5339}, which it reports as a {@code SUPERSEDED} candidate rather than omitting, by
     * design. What it must <em>not</em> list is {@code ESRI:108089}
     * ({@code OSGB_1936_To_WGS_1984_8_BAD_DX}, 5 m) or {@code ESRI:108336}
     * ({@code OSGB_1936_To_WGS_1984_NGA_7PAR}, 21 m): the EPSG prefix answers, so PROJ's walk stops
     * before it reaches ESRI — and neither row is deprecated, so nothing else in the pipeline would
     * have removed them.
     */
    @Test
    public void osgb36ToWgs84DropsTheTwoEsriOperationsProjExcludes() {
        List<String> codes = codes("EPSG:4277", "EPSG:4326");
        assertEquals(codes.toString(), 10, codes.size());
        assertFalse(codes.toString(), codes.contains("ESRI:108089"));
        assertFalse(codes.toString(), codes.contains("ESRI:108336"));
        for (int i = 0; i < codes.size(); i++) {
            assertFalse("the EPSG prefix answered, so no ESRI operation survives: " + codes,
                    codes.get(i).startsWith("ESRI:"));
        }
        // projinfo's nine, all still here.
        assertTrue(codes.toString(), codes.containsAll(Arrays.asList("EPSG:1195", "EPSG:1196",
                "EPSG:1197", "EPSG:1198", "EPSG:1199", "EPSG:1314", "EPSG:5622", "EPSG:7710")));
    }

    /**
     * <strong>{@code EPSG:4314 → EPSG:4326} is the pair where this changes the answer, not just the
     * list.</strong>
     * <p>
     * Five ESRI Helmerts ({@code DHDN_To_WGS_1984_3x} … {@code 7x}) each declare 0.1 m, better than
     * every EPSG operation for the pair, so before this change one of them ranked first. The best
     * usable operation must now come from EPSG.
     * <p>
     * <strong>PROJ's answer, recorded so the order below cannot be mistaken for an accident.</strong>
     * {@code projinfo -s EPSG:4314 -t EPSG:4326 --summary --spatial-test intersects} lists three, all
     * EPSG, in this order:
     * <ol>
     * <li>{@code 15949} — 1.0 m, all Germany onshore</li>
     * <li>{@code 1777} — 3.0 m, former West Germany</li>
     * <li>{@code 15869} — 2.0 m, former East Germany</li>
     * </ol>
     * That is deliberately <em>not</em> accuracy-ascending: 3.0 m sits above 2.0 m because West
     * Germany's extent is the larger of the two, and a larger intersection with the area of interest
     * outranks accuracy magnitude. This library ports that criterion, so it agrees.
     * <p>
     * The one difference is {@code 15949}, which this library demotes below both because its grid is
     * not reachable — an existing usability tier, not part of authority preference. So the first
     * <em>usable</em> operation is {@code EPSG:1777}, which is also PROJ's first usable one.
     */
    @Test
    public void dhdnStopsPreferringEsriHelmertsOverTheEpsgOnes() {
        List<CrsOperationCandidate> candidates = candidates("EPSG:4314", "EPSG:4326");
        for (int i = 0; i < candidates.size(); i++) {
            assertFalse("ESRI is behind EPSG for this pair: " + candidates.get(i),
                    candidates.get(i).authorityCode().startsWith("ESRI:"));
        }
        assertEquals("projinfo's three, plus the ballpark, plus the superseded and deprecated rows "
                + "this library reports rather than omits", 6, candidates.size());
        assertEquals("larger extent outranks accuracy magnitude, as PROJ does it",
                "EPSG:1777", candidates.get(0).authorityCode());
        assertEquals("2.0 m but the smaller extent", "EPSG:15869", candidates.get(1).authorityCode());
        assertEquals("1.0 m, and PROJ's first, but its grid is not reachable here",
                "EPSG:15949", candidates.get(2).authorityCode());
    }

    /**
     * <strong>The positive control: an ESRI source still gets ESRI operations.</strong>
     * <p>
     * {@code ESRI|EPSG} is {@code PROJ,ESRI,EPSG}, so for {@code ESRI:104105 → EPSG:4326} the walk
     * stops at ESRI and EPSG never enters. {@code projinfo} lists three: {@code ESRI:108123},
     * {@code ESRI:108113} and the ballpark. A set-shaped reading of the column keyed on the target
     * authority — the plausible wrong port — would drop both ESRI rows here and leave only the
     * ballpark, so this test fails loudly if the search order is ever collapsed into a filter.
     */
    @Test
    public void anEsriSourceStillSeesEsriOperations() {
        assertEquals(Arrays.asList("PROJ", "ESRI", "EPSG"), db.allowedAuthorities("ESRI", "EPSG"));
        List<String> codes = codes("ESRI:104105", "EPSG:4326");
        assertEquals(codes.toString(), 3, codes.size());
        assertTrue(codes.toString(), codes.contains("ESRI:108123"));
        assertTrue(codes.toString(), codes.contains("ESRI:108113"));
    }

    /**
     * A pair with no preference row at all keeps every candidate it had. {@code EPSG:4267 → EPSG:4269}
     * has one — {@code EPSG|EPSG} — and EPSG answers it, but the count is unchanged at
     * {@code projinfo}'s ten because no ESRI operation was ever a candidate for it. That is the
     * control that says this change removes only what it is meant to.
     */
    @Test
    public void aPairWithNoEsriCandidatesIsUntouched() {
        assertEquals(10, codes("EPSG:4267", "EPSG:4269").size());
    }

    // ------------------------------------------------------------------ helpers

    private static List<CrsOperationCandidate> candidates(String source, String target) {
        Crs s = Proj.createCrs(source, ctx);
        Crs t = Proj.createCrs(target, ctx);
        return Proj.candidateOperations(s, t, ctx);
    }

    private static List<String> codes(String source, String target) {
        List<CrsOperationCandidate> candidates = candidates(source, target);
        List<String> codes = new ArrayList<String>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            codes.add(candidates.get(i).authorityCode());
        }
        return codes;
    }
}
