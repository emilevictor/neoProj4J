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

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The four extent primitives operation selection needs, ported from PROJ so that a box crossing the
 * antimeridian is handled the way PROJ handles it rather than the way it looks like it should be.
 *
 * <p>Every method here is a transcription of a named upstream function, and the branch shapes are
 * kept rather than simplified:
 *
 * <ul>
 * <li>{@link #pseudoArea} &mdash; {@code getPseudoArea},
 *     {@code 9.8.1:src/iso19111/operation/coordinateoperationfactory.cpp:140-160};</li>
 * <li>{@link #contains} &mdash; {@code GeographicBoundingBox::contains},
 *     {@code 9.8.1:src/iso19111/metadata.cpp:284-329};</li>
 * <li>{@link #intersects} &mdash; {@code GeographicBoundingBox::Private::intersects},
 *     {@code 9.8.1:src/iso19111/metadata.cpp:334-389};</li>
 * <li>{@link #intersection} &mdash; {@code GeographicBoundingBox::Private::intersection},
 *     {@code 9.8.1:src/iso19111/metadata.cpp:420-491}.</li>
 * </ul>
 *
 * <h2>Why the antimeridian gets its own class</h2>
 *
 * <p>A bounding box that crosses 180&deg; is written {@code west > east} &mdash; Fiji is
 * {@code west = 176.8, east = -178.4} &mdash; and every one of these four operations has a
 * <em>separate branch</em> for it. The naive form of each is not a rounding error away from the
 * right answer; it is wrong by the complement. {@code intersects} on a normal box against a wrapping
 * one is answered by splitting the wrapping box at the antimeridian and recursing, which is not
 * something that falls out of comparing four doubles, and {@code contains} treats a full-width
 * {@code (-180, 180)} box as a special case in <em>both</em> argument positions with two different
 * answers.
 *
 * <p>The three-line versions of these predicates have already cost this library one open defect of
 * exactly this shape, so the port is deliberately literal and {@link ExtentsTest} probes the wrap
 * directly rather than only the cases that happen to arise from the shipped database.
 *
 * <p>Stateless; every method is a pure function of its arguments.
 */
final class Extents {

    private Extents() {
    }

    /**
     * PROJ's {@code getPseudoArea}: {@code (east - west) * (sin(north) - sin(south))}, with a west
     * greater than an east meaning the box wraps the antimeridian and {@code east} gaining 360.
     *
     * <p>Not an area in any unit &mdash; it is proportional to solid angle, which is the point: a
     * degree of longitude at 60&deg;N covers half the ground one at the equator does, and a flat
     * {@code lonSpan * latSpan} rectangle would rank a Nordic extent as if it did not.
     *
     * <p>{@link StrictMath} rather than {@link Math}, and {@link ProjectionMath#toRad} rather than
     * {@link Math#toRadians}, for the same reason in both cases: {@code Math.sin} may differ by an
     * ulp between platforms, {@code Math.toRadians} changed body at Java 9, and <b>a ranking that
     * changes with the JVM is not a ranking</b>. PROJ reaches radians through
     * {@code common::Angle::getSIValue()}, which is the same multiply.
     *
     * @param a the area, or null for an operation that declares no usable extent
     * @return the pseudo-area, or {@code 0.0} when there is no bounding box to measure
     */
    static double pseudoArea(AreaOfUse a) {
        if (a == null) {
            return 0.0;
        }
        double west = a.westLongitude();
        double east = a.eastLongitude();
        if (west > east) {
            east += 360.0;
        }
        return (east - west)
                * (StrictMath.sin(ProjectionMath.toRad(a.northLatitude()))
                        - StrictMath.sin(ProjectionMath.toRad(a.southLatitude())));
    }

    /**
     * Whether {@code outer} wholly contains {@code inner}, in PROJ's exact branch shape including
     * both full-width special cases.
     *
     * <p>Note the two asymmetric rules, which are the ones a rewrite loses. A full-width outer box
     * contains any inner box whose west and east differ at all &mdash; {@code return oW != oE}
     * &mdash; so a zero-width inner box is <em>not</em> contained even by the whole world. And a
     * full-width <em>inner</em> box is contained only by a full-width outer box: the second rule
     * returns false flatly, so a box spanning {@code (-179.999, 180)} does not contain
     * {@code (-180, 180)} even though it is a hair short of the world and the latitudes fit. The
     * order matters &mdash; the full-width-outer rule is checked first, which is why the world does
     * contain the world.
     *
     * @param outer the containing box, or null
     * @param inner the contained box, or null
     * @return true iff both are non-null and {@code outer} contains {@code inner}
     */
    static boolean contains(AreaOfUse outer, AreaOfUse inner) {
        if (outer == null || inner == null) {
            return false;
        }
        final double w = outer.westLongitude();
        final double e = outer.eastLongitude();
        final double n = outer.northLatitude();
        final double s = outer.southLatitude();
        final double ow = inner.westLongitude();
        final double oe = inner.eastLongitude();
        final double on = inner.northLatitude();
        final double os = inner.southLatitude();

        if (!(s <= os && n >= on)) {
            return false;
        }
        if (w == -180.0 && e == 180.0) {
            return ow != oe;
        }
        if (ow == -180.0 && oe == 180.0) {
            return false;
        }
        if (w < e) {
            // Normal bounding box: it can only contain another normal one.
            if (ow < oe) {
                return w <= ow && e >= oe;
            }
            return false;
        }
        // Crossing the antimeridian. A normal inner box is contained if it sits wholly in either
        // lobe; a wrapping inner box is compared bound for bound, which works because both wrap.
        if (ow < oe) {
            return ow >= w || oe <= e;
        }
        return w <= ow && e >= oe;
    }

    /**
     * Whether two boxes share any area, in PROJ's exact branch shape.
     *
     * <p>The interesting case is a normal box against a wrapping one: PROJ splits the wrapping box
     * at the antimeridian into {@code (oW, 180)} and {@code (-180, oE)} and recurses on both. That
     * split is transcribed literally, guard included &mdash; upstream bails out on a longitude
     * outside {@code [-180, 180]} specifically to bound the recursion, and its comment says so.
     *
     * <p>Note the comparison is <b>strict</b>: {@code max(W, oW) < min(E, oE)}, so two boxes that
     * merely share an edge do not intersect. That is upstream's choice and it is kept, because
     * loosening it would admit an operation whose extent touches the area of interest at a point.
     *
     * @param a the first box, or null
     * @param b the second box, or null
     * @return true iff both are non-null and they intersect
     */
    static boolean intersects(AreaOfUse a, AreaOfUse b) {
        if (a == null || b == null) {
            return false;
        }
        return intersects(a.westLongitude(), a.southLatitude(), a.eastLongitude(), a.northLatitude(),
                b.westLongitude(), b.southLatitude(), b.eastLongitude(), b.northLatitude());
    }

    private static boolean intersects(double w, double s, double e, double n,
                                      double ow, double os, double oe, double on) {
        if (n < os || s > on) {
            return false;
        }
        // World coverage on one side and an antimeridian-crossing box on the other always meet.
        // Upstream writes both of these with the `>` on the other box for symmetry with
        // intersection(); the asymmetry is deliberate there and is kept here.
        if (w == -180.0 && e == 180.0 && ow > oe) {
            return true;
        }
        if (ow == -180.0 && oe == 180.0 && w > e) {
            return true;
        }
        if (w <= e) {
            if (ow <= oe) {
                return StrictMath.max(w, ow) < StrictMath.min(e, oe);
            }
            // Bail out on longitudes outside [-180, 180]. Upstream's comment: this "at least avoid
            // potential infinite recursion", since the split below would not terminate.
            if (ow > 180.0 || oe < -180.0) {
                return false;
            }
            return intersects(w, s, e, n, ow, os, 180.0, on)
                    || intersects(w, s, e, n, -180.0, os, oe, on);
        }
        if (ow <= oe) {
            return intersects(ow, os, oe, on, w, s, e, n);
        }
        // Both wrap the antimeridian, so both contain 180 and they necessarily meet.
        return true;
    }

    /**
     * The overlap of two boxes, or null if they do not overlap.
     *
     * <p>Used only by {@link SourceTargetCRSExtentUse#INTERSECTION}. Where a normal box meets a
     * wrapping one the overlap is genuinely two disjoint pieces, and upstream returns <b>the wider
     * of the two</b> rather than a multipolygon; that is a lossy answer and it is ported as such,
     * because the alternative is to diverge from the area of interest PROJ would compute.
     *
     * <p>The result carries {@code databaseDerived = false} and no description. Both matter:
     * {@code filterOut}'s "same description" shortcut keys off the description being present, and
     * an intersection this library computed is not something an authority published.
     *
     * @param a the first box, or null
     * @param b the second box, or null
     * @return the overlap, or null if either is null or they are disjoint
     */
    static AreaOfUse intersection(AreaOfUse a, AreaOfUse b) {
        if (a == null || b == null) {
            return null;
        }
        return intersection(a.westLongitude(), a.southLatitude(), a.eastLongitude(),
                a.northLatitude(), b.westLongitude(), b.southLatitude(), b.eastLongitude(),
                b.northLatitude());
    }

    private static AreaOfUse intersection(double w, double s, double e, double n,
                                          double ow, double os, double oe, double on) {
        if (n < os || s > on) {
            return null;
        }
        if (w == -180.0 && e == 180.0 && ow > oe) {
            return box(ow, StrictMath.max(s, os), oe, StrictMath.min(n, on));
        }
        if (ow == -180.0 && oe == 180.0 && w > e) {
            return box(w, StrictMath.max(s, os), e, StrictMath.min(n, on));
        }
        if (w <= e) {
            if (ow <= oe) {
                double resW = StrictMath.max(w, ow);
                double resE = StrictMath.min(e, oe);
                if (resW < resE) {
                    return box(resW, StrictMath.max(s, os), resE, StrictMath.min(n, on));
                }
                return null;
            }
            if (ow > 180.0 || oe < -180.0) {
                return null;
            }
            // Two disjoint pieces. Upstream returns the larger by longitude span, not their union.
            AreaOfUse inter1 = intersection(w, s, e, n, ow, os, 180.0, on);
            AreaOfUse inter2 = intersection(w, s, e, n, -180.0, os, oe, on);
            if (inter1 == null) {
                return inter2;
            }
            if (inter2 == null) {
                return inter1;
            }
            double span1 = inter1.eastLongitude() - inter1.westLongitude();
            double span2 = inter2.eastLongitude() - inter2.westLongitude();
            return span1 > span2 ? inter1 : inter2;
        }
        if (ow <= oe) {
            return intersection(ow, os, oe, on, w, s, e, n);
        }
        return box(StrictMath.max(w, ow), StrictMath.max(s, os),
                StrictMath.min(e, oe), StrictMath.min(n, on));
    }

    /**
     * An {@link AreaOfUse} from four bounds, or null if they are not a usable box.
     *
     * <p>The intersection recursion above generates candidate bounds that {@link AreaOfUse}'s
     * constructor may reject &mdash; a longitude of exactly 180 is fine, but the split can produce a
     * degenerate south-above-north box. Returning null there is the same answer as "they do not
     * overlap", which is what a degenerate overlap means.
     */
    private static AreaOfUse box(double west, double south, double east, double north) {
        try {
            return new AreaOfUse(west, south, east, north, null, false);
        } catch (IllegalArgumentException notUsable) {
            return null;
        }
    }

    /**
     * The smaller of two boxes by {@link #pseudoArea}, for
     * {@link SourceTargetCRSExtentUse#SMALLEST}.
     *
     * <p>Upstream's tie-break is implicit in the shape of its {@code if}: {@code getPseudoArea(a) <
     * getPseudoArea(b)} picks {@code a}, and <b>everything else picks {@code b}</b>, equality
     * included. Kept, because with two equal-area extents the choice is observable in the filter's
     * description shortcut.
     *
     * @param a the first box, or null
     * @param b the second box, or null
     * @return the smaller; {@code a} when {@code b} is null, {@code b} when {@code a} is null
     */
    static AreaOfUse smaller(AreaOfUse a, AreaOfUse b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return pseudoArea(a) < pseudoArea(b) ? a : b;
    }
}
