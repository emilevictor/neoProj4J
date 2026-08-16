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
package org.locationtech.proj4j.api;

import org.locationtech.proj4j.ErrorCause;

/**
 * What to do when the best-ranked coordinate operation between two CRSs cannot be executed but a
 * worse one can.
 *
 * <p>The classic case is a grid: <i>NAD27 to NAD83 (NADCON5)</i> outranks a three-parameter
 * Helmert, but needs a grid file that is not on the classpath. Silently using the Helmert is a
 * metre-scale change of answer with no signal, so the default refuses.
 *
 * <p>PROJ's equivalent knob is {@code PROJ_ONLY_BEST_DEFAULT} / {@code only_best_default} /
 * {@code ONLY_BEST}. <b>PROJ defaults it off; Proj4J defaults it on</b>, deliberately: PROJ can
 * fetch the missing grid from its CDN, and Proj4J will not, so "degrade quietly" has a very
 * different cost here. Proj4J honours <em>no</em> environment variable for this &mdash; not
 * {@code PROJ_ONLY_BEST_DEFAULT}, not anything else. It is set in code or not at all.
 *
 * <h2>What "degraded" means, precisely</h2>
 *
 * <p>A selection is degraded iff it is <b>strictly less accurate</b> than a candidate that could not
 * be used. That word "strictly" is load-bearing, and the case that forces it is the headline one:
 * {@code EPSG:4267} to {@code EPSG:4269} offers {@code EPSG:1241} (NADCON, 0.15&nbsp;m, which this
 * library can execute) and {@code EPSG:8555} (NADCON&nbsp;5, 0.15&nbsp;m, whose grid feeds the
 * unified {@code +proj=gridshift} operator, which it cannot). They are <b>tied</b>. Refusing a tie
 * would make {@link #REQUIRE_BEST} reject that pair outright, which is a worse answer than either
 * operation &mdash; so a tie is not a degradation, and the skipped candidate is recorded in
 * {@link CrsOperation#warnings()} instead. An <em>unknown</em> accuracy against a known one
 * <em>is</em> a degradation, because "we do not know" cannot be shown to be as good.
 *
 * <p>Two policies have to be conceded to reach the extreme case. Falling all the way back to a
 * ballpark offset is the largest degradation there is, so it needs {@link #ALLOW_DEGRADED}
 * <em>and</em> {@link BallparkPolicy#ALLOW}; either alone still refuses.
 *
 * <h2>Which candidate each policy selects</h2>
 *
 * <p>The two policies do not select from the ranked list the same way, and since 2.2.0 they can return
 * different operations for the same pair:
 *
 * <ul>
 *   <li>{@link #ALLOW_DEGRADED} takes the <b>first usable candidate</b> on the list. The caller has
 *       said rank is enough, so rank is what it gets, and that is {@code projinfo}'s first executable
 *       line.</li>
 *   <li>{@link #REQUIRE_BEST} takes the <b>first usable candidate that is not a degradation</b>, and
 *       refuses only when there is no such candidate. It has to: it promises not to return a degraded
 *       operation, and refusing while holding a perfectly good one would not be that promise, it
 *       would be a different and worse one.</li>
 * </ul>
 *
 * <p>So the stricter policy can return the <em>more</em> accurate operation of the two, which reads
 * backwards until you notice that "strict" here constrains the answer's quality and not the search.
 * {@code EPSG:4267} to {@code EPSG:4269} is the case, in any deployment where both the Canadian NTv1
 * grid and the CONUS NADCON grid are reachable: PROJ ranks by area before accuracy magnitude, so
 * {@code EPSG:1312} (NTv1, 2.0&nbsp;m, all of Canada) heads the list and {@code EPSG:1241} (NADCON,
 * 0.15&nbsp;m, CONUS) is one row further down. {@link #ALLOW_DEGRADED} returns the 2.0&nbsp;m one;
 * {@link #REQUIRE_BEST} returns {@code EPSG:1241}, because 2.0&nbsp;m is a degradation relative to the
 * 0.15&nbsp;m {@code EPSG:8555} that this library cannot execute. Neither policy reorders anything, and
 * {@link CrsOperation#candidates()} shows both the same list in the same order.
 *
 * <p>Reachability is the part to keep hold of. Where <em>no</em> grid is reachable &mdash; the
 * {@code proj4j-db}-only deployment, for one &mdash; there is no usable candidate for either policy to
 * pick and {@link #REQUIRE_BEST} refuses, which is the behaviour this enum was added for and is
 * unchanged.
 *
 * <p>Whenever {@link #REQUIRE_BEST} passes over the head of the list, it says so in
 * {@link CrsOperation#warnings()}, naming both operations and the rejected one that set the bar. A
 * caller never has to infer the substitution from the answer.
 *
 * <h2>Without a database there is nothing to rank</h2>
 *
 * <p>With no {@link ProjContext#database()} there is exactly one candidate operation per CRS pair
 * &mdash; the one the legacy datum model synthesises &mdash; so this enum is recorded and has
 * nothing to act on. {@link ProjContext#describe()} says which of the two states applies rather than
 * letting a caller assume it is being enforced.
 *
 * @since 2.0.0
 */
public enum BestOperationPolicy {

    /**
     * <b>The default.</b> Select the highest-ranked usable candidate that is not less accurate than
     * the best candidate that cannot be executed here. If <em>every</em> usable candidate is less
     * accurate than that one, fail with {@link ErrorCause#BEST_OPERATION_UNAVAILABLE} rather than
     * quietly selecting a worse one. The message names both operations, quantifies the accuracy gap in
     * metres, and names the grid files that would unlock the better one.
     */
    REQUIRE_BEST,

    /**
     * Select the first candidate on the ranked list that <em>can</em> be executed, recording in
     * {@link CrsOperation#warnings()} which one was skipped, why, and what accuracy was given up.
     */
    ALLOW_DEGRADED
}
