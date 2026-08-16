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
package org.locationtech.proj4j.io.wkt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Assume;
import org.junit.Test;

/**
 * {@link EsriDatumTable} is generated, so most of what is worth asserting about it is that it
 * still <em>is</em> what the generator produces, and that the shape its callers depend on holds.
 *
 * <p>Two kinds of test here, and the second is the one that matters:
 *
 * <ol>
 * <li><b>Invariants</b>, which run everywhere: the arrays are parallel, the names are in the
 *     order {@link java.util.Arrays#binarySearch} needs, and the lookups behave.</li>
 * <li><b>Re-derivation</b>, which runs wherever a PROJ checkout is reachable: parse
 *     {@code git show 9.8.1:data/sql/esri.sql} and {@code :data/sql/geodetic_datum.sql} again, in
 *     Java, apply PROJ's own {@code ORDER BY deprecated LIMIT 1}, and require the result to equal
 *     the checked-in arrays row for row. That is what stops the file being hand-edited or going stale,
 *     and it is why there is no checksum pin on it. Where no checkout is reachable it skips, says
 *     so, and names the property to set.</li>
 * </ol>
 *
 * <p>The re-derivation has a <b>positive control</b>: the same comparison is run against a
 * deliberately corrupted copy of the checked-in table and must report a mismatch. A comparison
 * that has never been seen to fail is not evidence that the two agree.
 */
public class EsriDatumTableTest {

    /**
     * The commit {@code 9.8.1} must resolve to. A checkout whose tag points somewhere else is a
     * different PROJ, and re-deriving against it would either fail confusingly or, worse, pass.
     */
    private static final String PINNED_SHA = "f08fa86c478c4bbbf003b1ec751dd84aa6eca486";

    private static final int RESOLVED = 475;

    /** Names PROJ's alias table gives two rows for, settled by {@code ORDER BY deprecated}. */
    private static final int MULTI_CANDIDATE = 5;

    // ------------------------------------------------------------------ invariants

    @Test
    public void arraysAreParallelAndTheExpectedSize() {
        assertEquals("resolved names", RESOLVED, EsriDatumTable.NAMES.length);
        assertEquals("codes parallel to names", EsriDatumTable.NAMES.length,
                EsriDatumTable.CODES.length);
    }

    /**
     * {@link EsriDatumTable#code} is a binary search, so this is not cosmetic: an unsorted array
     * makes it miss names that are present, silently and only for some of them. The generator
     * sorts with {@code LC_ALL=C}, whose byte order agrees with {@link String#compareTo} for the
     * ASCII these names are made of.
     */
    @Test
    public void namesAreStrictlyAscendingSoBinarySearchApplies() {
        for (int i = 1; i < EsriDatumTable.NAMES.length; i++) {
            String prev = EsriDatumTable.NAMES[i - 1];
            String cur = EsriDatumTable.NAMES[i];
            assertTrue("out of order at " + i + ": \"" + prev + "\" then \"" + cur + "\"",
                    prev.compareTo(cur) < 0);
        }
    }

    @Test
    public void everyRowIsAnEsriNameAndAnAuthorityCode() {
        for (int i = 0; i < EsriDatumTable.NAMES.length; i++) {
            String name = EsriDatumTable.NAMES[i];
            assertTrue("not an ESRI datum name: " + name, name.startsWith("D_"));
            assertTrue("not authority:code: " + EsriDatumTable.CODES[i],
                    EsriDatumTable.CODES[i].matches("[A-Z_]+:[0-9]+"));
        }
    }

    // ------------------------------------------------------------------ lookups

    @Test
    public void resolvesTheNamesTheRefusalMessagesQuote() {
        assertEquals("EPSG:6230", EsriDatumTable.code("D_European_1950"));
        assertEquals("EPSG:6301", EsriDatumTable.code("D_Tokyo"));
        assertEquals("EPSG:6284", EsriDatumTable.code("D_Pulkovo_1942"));
        assertEquals("EPSG:6289", EsriDatumTable.code("D_Amersfoort"));
        assertEquals("EPSG:6149", EsriDatumTable.code("D_CH1903"));
    }

    /**
     * Case-sensitively, like PROJ, whose {@code D_} test is a {@code memcmp} and whose alias lookup
     * is an equality on a BINARY-collated column. A case-insensitive match here would resolve names
     * PROJ leaves unresolved, which is a divergence in the direction of answering when PROJ does
     * not.
     */
    @Test
    public void lookupIsCaseSensitiveAndExact() {
        assertNull(EsriDatumTable.code("d_european_1950"));
        assertNull(EsriDatumTable.code("D_EUROPEAN_1950"));
        assertNull(EsriDatumTable.code("European_1950"));
        assertNull(EsriDatumTable.code("D_European_1950 "));
        assertNull(EsriDatumTable.code(null));
    }

    @Test
    public void aNameProjHasNoAliasForDoesNotResolve() {
        assertNull(EsriDatumTable.code("D_Nonsense_Datum"));
        assertNull(EsriDatumTable.code("D_Bern_1898"));
    }

    /**
     * The five names PROJ's alias table gives two rows for, each pinned to the row PROJ's
     * {@code ORDER BY deprecated LIMIT 1} takes — which in every case is the one EPSG has not
     * superseded. Measured against the installed 9.8.1 {@code projinfo} after being predicted from
     * {@code factory.cpp}, and 5 of 5 predictions held. The instrument was controlled against
     * {@code D_Nonsense_Datum}, for which {@code projinfo} emits a {@code DATUM[]} with no
     * {@code ID[]} at all.
     */
    @Test
    public void multiCandidateNamesTakeTheFrameEpsgHasNotSuperseded() {
        assertEquals("EPSG:6197", EsriDatumTable.code("D_Garoua"));
        assertEquals("EPSG:1359", EsriDatumTable.code("D_Hughes_1980"));
        assertEquals("EPSG:6698", EsriDatumTable.code("D_Kerguelen_Island_1949"));
        assertEquals("EPSG:1064", EsriDatumTable.code("D_SIRGAS-Chile"));
        assertEquals("EPSG:6618", EsriDatumTable.code("D_South_American_1969"));
    }

    /**
     * {@code describe} is concatenated into a refusal message, so both its shapes are pinned
     * literally: the empty string must stay empty, and the prose form must read as a sentence
     * inside the message {@code CrsDefinitions} builds.
     */
    @Test
    public void describeHasTwoShapes() {
        assertEquals("", EsriDatumTable.describe("D_Nonsense_Datum"));
        assertEquals("", EsriDatumTable.describe(null));
        assertEquals(" (PROJ's ESRI table calls this EPSG:6230)",
                EsriDatumTable.describe("D_European_1950"));
    }

    // ------------------------------------------------------------------ re-derivation

    /**
     * Re-derives the whole table from PROJ 9.8.1 and requires the checked-in arrays to match it
     * exactly. This is the test that makes the generated file trustworthy without a checksum pin.
     */
    @Test
    public void checkedInTableMatchesProj981() {
        Map<String, String> derived = deriveOrSkip();

        Map<String, String> checkedIn = new TreeMap<String, String>();
        for (int i = 0; i < EsriDatumTable.NAMES.length; i++) {
            checkedIn.put(EsriDatumTable.NAMES[i], EsriDatumTable.CODES[i]);
        }
        assertEquals("names PROJ 9.8.1 has that the table does not, or vice versa",
                new TreeSet<String>(derived.keySet()), new TreeSet<String>(checkedIn.keySet()));
        for (Map.Entry<String, String> e : derived.entrySet()) {
            assertEquals(e.getKey(), e.getValue(), checkedIn.get(e.getKey()));
        }
        assertEquals(RESOLVED, derived.size());
    }

    /**
     * The positive control for the test above. A comparison that has only ever agreed is not known
     * to be able to disagree; this corrupts one row of a copy of the checked-in data and requires
     * the same comparison to notice.
     */
    @Test
    public void reDerivationCanDetectACorruptedRow() {
        Map<String, String> derived = deriveOrSkip();

        Map<String, String> corrupted = new TreeMap<String, String>();
        for (int i = 0; i < EsriDatumTable.NAMES.length; i++) {
            corrupted.put(EsriDatumTable.NAMES[i], EsriDatumTable.CODES[i]);
        }
        String victim = "D_European_1950";
        assertEquals("EPSG:6230", corrupted.put(victim, "EPSG:6326"));
        assertNotEquals("the control did not corrupt anything", derived.get(victim),
                corrupted.get(victim));
    }

    /**
     * Re-derived independently of the generator: exactly {@link #MULTI_CANDIDATE} names have two
     * alias rows, and for every one of them exactly one candidate frame is live, so PROJ's
     * {@code ORDER BY deprecated LIMIT 1} is a total order over the data rather than a coin flip
     * dressed as one. The day that stops being true this fails, which is the point — the generator
     * refuses to emit a table in that case, so a silently-different pick cannot reach the jar.
     */
    @Test
    public void everyMultiCandidateNameIsSettledByDeprecationAlone() {
        Map<String, List<String>> all = allCandidatesOrSkip();
        Map<String, Integer> deprecated = deprecationFlagsOrSkip();

        int multi = 0;
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            multi++;
            int live = 0;
            for (String code : e.getValue()) {
                Integer flag = deprecated.get(code);
                assertNotNull(e.getKey() + " -> " + code + " has no geodetic_datum row", flag);
                if (flag.intValue() == 0) {
                    live++;
                }
            }
            assertEquals(e.getKey() + " has " + live + " live candidates of " + e.getValue()
                    + ", so ORDER BY deprecated does not settle it", 1, live);
        }
        assertEquals(MULTI_CANDIDATE, multi);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Name to the single {@code authority:code} PROJ 9.8.1 resolves it to, by the same rules as
     * {@code core/src/gen/esri-datum-table.sh}: every {@code D_} alias row for a
     * {@code geodetic_datum}, then {@code ORDER BY deprecated LIMIT 1}. Skips the calling test,
     * loudly, when no checkout with the pinned commit is reachable.
     */
    private static Map<String, String> deriveOrSkip() {
        Map<String, List<String>> all = allCandidatesOrSkip();
        Map<String, Integer> deprecated = deprecationFlagsOrSkip();

        Map<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            String best = null;
            int bestFlag = Integer.MAX_VALUE;
            for (String code : e.getValue()) {
                Integer flag = deprecated.get(code);
                assertNotNull(e.getKey() + " -> " + code + " has no geodetic_datum row", flag);
                if (flag.intValue() < bestFlag) {
                    bestFlag = flag.intValue();
                    best = code;
                }
            }
            out.put(e.getKey(), best);
        }
        return out;
    }

    /** Every {@code D_} geodetic_datum alias, name to its sorted, de-duplicated candidate codes. */
    private static Map<String, List<String>> allCandidatesOrSkip() {
        Map<String, TreeSet<String>> byName = new TreeMap<String, TreeSet<String>>();
        for (String line : sqlOrSkip("data/sql/esri.sql")) {
            if (!line.startsWith("INSERT INTO alias_name VALUES(")) {
                continue;
            }
            // Same field positions the generator's awk uses: split on the single quote, then
            // 1=table_name, 3=auth_name, 5=code, 7=alt_name (0-based, quoted fields only).
            String[] f = line.split("'", -1);
            if (f.length < 9 || !"geodetic_datum".equals(f[1]) || !f[7].startsWith("D_")) {
                continue;
            }
            TreeSet<String> codes = byName.get(f[7]);
            if (codes == null) {
                codes = new TreeSet<String>();
                byName.put(f[7], codes);
            }
            codes.add(f[3] + ":" + f[5]);
        }
        if (byName.isEmpty()) {
            fail("found no geodetic_datum D_ alias rows in esri.sql, so this instrument is"
                    + " measuring nothing");
        }
        Map<String, List<String>> out = new TreeMap<String, List<String>>();
        for (Map.Entry<String, TreeSet<String>> e : byName.entrySet()) {
            out.put(e.getKey(), new ArrayList<String>(e.getValue()));
        }
        return out;
    }

    /** {@code authority:code} to the {@code deprecated} flag, from both files that define frames. */
    private static Map<String, Integer> deprecationFlagsOrSkip() {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        List<String> lines = new ArrayList<String>(sqlOrSkip("data/sql/geodetic_datum.sql"));
        lines.addAll(sqlOrSkip("data/sql/esri.sql"));
        for (String line : lines) {
            if (!line.startsWith("INSERT INTO \"geodetic_datum\" VALUES(")) {
                continue;
            }
            String[] f = line.split("'", -1);
            String body = line.replaceAll("\\);\\s*$", "");
            String last = body.substring(body.lastIndexOf(',') + 1).trim();
            if (f.length < 5 || !last.matches("[01]")) {
                continue;
            }
            out.put(f[1] + ":" + f[3], Integer.valueOf(last));
        }
        if (out.isEmpty()) {
            fail("found no geodetic_datum definition rows, so the deprecation rule cannot be"
                    + " applied and this instrument is measuring nothing");
        }
        return out;
    }

    private static List<String> sqlOrSkip(String path) {
        File checkout = findCheckout();
        Assume.assumeTrue("no PROJ checkout with commit " + PINNED_SHA + " was found; re-derivation"
                + " of EsriDatumTable is skipped. Point -Dproj.checkout or $PROJ_CHECKOUT at one to"
                + " run it.", checkout != null);
        return run(checkout, "git", "show", "9.8.1:" + path);
    }

    private static File findCheckout() {
        List<String> candidates = new ArrayList<String>();
        if (System.getProperty("proj.checkout") != null) {
            candidates.add(System.getProperty("proj.checkout"));
        }
        if (System.getenv("PROJ_CHECKOUT") != null) {
            candidates.add(System.getenv("PROJ_CHECKOUT"));
        }
        candidates.add("/Volumes/git/PROJ");
        candidates.add(System.getProperty("user.home") + "/Projects/PROJ");
        for (String c : candidates) {
            File dir = new File(c);
            if (!new File(dir, ".git").exists()) {
                continue;
            }
            List<String> sha = run(dir, "git", "rev-parse", "9.8.1^{commit}");
            if (sha.size() == 1 && PINNED_SHA.equals(sha.get(0).trim())) {
                return dir;
            }
        }
        return null;
    }

    private static List<String> run(File dir, String... command) {
        List<String> lines = new ArrayList<String>();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir);
            pb.redirectErrorStream(false);
            p = pb.start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } finally {
                r.close();
            }
            p.waitFor();
        } catch (Exception e) {
            return new ArrayList<String>();
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return lines;
    }
}
