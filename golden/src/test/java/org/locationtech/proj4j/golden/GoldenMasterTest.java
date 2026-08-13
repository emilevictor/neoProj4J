/*
 * Copyright 2026 the Proj4J contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.locationtech.proj4j.golden;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.fail;

/**
 * The gate. Generates a golden table from whatever proj4j is on the classpath and diffs it against the
 * pinned baseline.
 *
 * <h2>Modes</h2>
 * <pre>
 * mvn -Pgolden -pl golden -am verify
 *     Generate from the working tree into target/golden and diff against baseline/1.4.3.
 *     Fails on any UNEXPLAINED row, DEAD_RULE, PENDING_RULE_FIRED, EXPIRED_RULE or COUNT_MISMATCH.
 *
 * mvn -Pgolden,golden-baseline -pl golden verify -Dgolden.regenerate=true
 *     Generate from RELEASED 1.4.3 and overwrite baseline/1.4.3. No diff. Note: no -am, so the
 *     working tree's code cannot leak into the baseline.
 * </pre>
 * Without {@code -Pgolden} the whole thing is skipped and only the fast self-tests run, so a plain
 * {@code mvn install} stays fast.
 *
 * <h2>The six figures are pinned, and re-pinning them is meant to be easy</h2>
 *
 * The {@code golden diff:} line was printed and compared to nothing; only {@code INTENDED} was
 * machine-checked, and only via the sum of every rule's {@code expected_rows}. It is now checked
 * against {@code baseline/1.4.3/golden-expect.txt} ({@code -Dgolden.expect}).
 *
 * <p>Deliberately a pin and not "assert 0 UNEXPLAINED". Work that is already planned will move these
 * figures legitimately, and a gate that such work cannot pass is a gate somebody deletes. So this one
 * fails loudly, prints expected/actual/delta for all six side by side, and is re-pinned by editing
 * one line in a committed file. The cost of a re-pin is not the edit — it is that the PR body has to
 * say which rows moved and why. That is the property worth having: the figures cannot move silently.
 */
public class GoldenMasterTest {

    @Test
    public void goldenMasterMatchesBaselineOrIsDeclared() throws Exception {
        Assume.assumeFalse("golden sweep is opt-in: run with -Pgolden",
                Boolean.parseBoolean(System.getProperty("golden.skip", "true")));

        File goldenDir = new File(System.getProperty("golden.dir", "."));
        File outDir = new File(System.getProperty("golden.out",
                new File(goldenDir, "target/golden").getPath()));
        String baselineName = System.getProperty("golden.baseline", "1.4.3");
        File baselineDir = new File(new File(goldenDir, "baseline"), baselineName);
        File expectFile = new File(System.getProperty("golden.expect",
                new File(baselineDir, "golden-expect.txt").getPath()));
        boolean regenerate = Boolean.parseBoolean(System.getProperty("golden.regenerate", "false"));

        File target = regenerate ? baselineDir : outDir;
        GoldenGenerator.Counters counters = new GoldenGenerator()
                .generate(target, goldenDir, GoldenGenerator.defaultCsvDir(goldenDir));
        System.out.println("golden: " + counters.cases + " cases, " + counters.rows + " rows, "
                + counters.ok + " OK, " + counters.exceptions + " EXC, "
                + counters.noProbe + " NO_PROBE, " + counters.nonFinite + " non-finite");

        if (regenerate) {
            System.out.println("baseline rewritten at " + baselineDir
                    + " -- commit it together with probes.tsv and pairs.tsv, in one commit");
            return;
        }

        File report = new File(outDir, "golden-report.tsv");
        GoldenDiff.Result r = GoldenDiff.run(baselineDir, outDir,
                new File(goldenDir, "rules.yaml"), report, new Date());

        System.out.println(r.summary());
        System.out.println("rows claimed per rule (paste these into expected_rows to pin them):");
        for (Iterator<Map.Entry<String, Integer>> it = r.perRule.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Integer> e = it.next();
            System.out.println("    " + e.getKey() + ": " + e.getValue());
        }
        System.out.println("full report: " + report);

        // A moved figure is reported ALONGSIDE the rule-level failures, never instead of them. Today
        // this run is red on 2,291 UNEXPLAINED rows; if the figure check short-circuited, fixing the
        // pin would look like fixing the backlog.
        String figures = GoldenDiff.Expectation.load(expectFile).mismatch(r);
        if (figures != null) System.out.println(figures);

        if (figures != null || !r.ok()) {
            StringBuilder sb = new StringBuilder(r.summary());
            sb.append("\nsee ").append(report);
            if (figures != null) sb.append("\n\n").append(figures);
            for (int i = 0; i < r.failures.size(); i++) {
                sb.append("\n\n").append(r.failures.get(i));
            }
            fail(sb.toString());
        }
    }
}
