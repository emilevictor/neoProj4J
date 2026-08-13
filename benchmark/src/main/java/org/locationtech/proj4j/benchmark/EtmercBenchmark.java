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
package org.locationtech.proj4j.benchmark;

import java.util.concurrent.TimeUnit;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Isolates the transverse-Mercator algorithm decision, with no {@code BasicCoordinateTransform}
 * envelope and no datum work.
 *
 * <p>Two classes are registered, and both are reachable from shipped EPSG codes:
 * <ul>
 *   <li>{@code +proj=etmerc} - {@code ExtendedTransverseMercatorProjection}, the Poder/Engsager
 *       series: accurate far from the central meridian, and the more expensive of the two.</li>
 *   <li>{@code +proj=tmerc} - {@code TransverseMercatorProjection}, which carries both algorithms
 *       and PROJ 9.8.1's own selection rules.</li>
 * </ul>
 *
 * <p><b>What the {@code tmerc*} arms measure today is NOT the Evenden/Snyder series.</b>
 * {@code TransverseMercatorProjection}'s default is {@code Algorithm.PODER_ENGSAGER}, matching PROJ
 * 9.8.1, and it runs that by delegating to an {@code ExtendedTransverseMercatorProjection} it holds
 * as a field. So the setup here - a bare {@code +proj=tmerc} string with no {@code +approx} and no
 * {@code +algo} - measures the Poder/Engsager kernel plus one delegation hop, and the
 * {@code etmerc}-vs-{@code tmerc} ratio is currently the cost of that hop, not the cost of choosing
 * an algorithm. To measure the truncated series, the string needs {@code +approx} or
 * {@code +algo=evenden_snyder}; read {@code TransverseMercatorProjection}'s "Which algorithm runs"
 * section before quoting any number from this class.
 *
 * <p><b>Parameterised across the 3-degree seam.</b> The truncated series degrades with distance from
 * the central meridian, which is why the seam is worth sampling at all. The parameter is
 * {@code deltaLonDeg}, the offset from the central meridian, sampled at 0 / 1.5 / <b>3</b> / 6 / 20.
 * Cost is expected to be flat in {@code deltaLonDeg} for both arms - if it is not, some iteration
 * count is data-dependent, which is itself a finding and a determinism hazard.
 *
 * <p>Uses the public {@code projectRadians}/{@code inverseProjectRadians} entry points rather than
 * the protected {@code project(double,double,ProjCoordinate)}. That includes the central-meridian
 * subtraction, {@code totalScale} and the false origin, which is the shape a kernel would actually
 * call, and it avoids widening proj4j's public surface just to benchmark it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class EtmercBenchmark {

    /**
     * Offset from the central meridian in degrees. 3 is the seam UTM zones are drawn around; 6 is
     * where upstream stops asserting E/S round-trip stability; 20 is where the E/S error is
     * unbounded.
     */
    @Param({"0.0", "1.5", "3.0", "6.0", "20.0"})
    public double deltaLonDeg;

    /** Central meridian of UTM zone 33N, so the geometry matches {@link CrsPair#WGS84_TO_UTM33N}. */
    private static final double CENTRAL_MERIDIAN_DEG = 15.0;
    private static final double LAT_DEG = 47.4;

    private Projection etmerc;
    private Projection tmerc;

    private ProjCoordinate geoIn;
    private ProjCoordinate projInEtmerc;
    private ProjCoordinate projInTmerc;
    private ProjCoordinate out;

    @Setup(Level.Trial)
    public void setUp() {
        CRSFactory crsFactory = new CRSFactory();
        String common = " +lat_0=0 +lon_0=" + CENTRAL_MERIDIAN_DEG
                + " +k=0.9996 +x_0=500000 +y_0=0 +datum=WGS84 +units=m";
        etmerc = crsFactory.createFromParameters("etmerc", "+proj=etmerc" + common).getProjection();
        tmerc = crsFactory.createFromParameters("tmerc", "+proj=tmerc" + common).getProjection();

        // projectRadians expects radians and, for these projections, absolute longitude - the
        // central meridian subtraction happens inside.
        geoIn = new ProjCoordinate(
                Math.toRadians(CENTRAL_MERIDIAN_DEG + deltaLonDeg), Math.toRadians(LAT_DEG));
        out = new ProjCoordinate();

        // Seed each inverse with that algorithm's own forward output, so the inverse is solving a
        // point that is exactly on its own image. Seeding both from one algorithm would make the
        // slower one iterate over a residual the other introduced.
        projInEtmerc = new ProjCoordinate();
        etmerc.projectRadians(geoIn, projInEtmerc);
        projInTmerc = new ProjCoordinate();
        tmerc.projectRadians(geoIn, projInTmerc);
    }

    @Benchmark
    public ProjCoordinate etmercForward() {
        return etmerc.projectRadians(geoIn, out);
    }

    @Benchmark
    public ProjCoordinate etmercInverse() {
        return etmerc.inverseProjectRadians(projInEtmerc, out);
    }

    @Benchmark
    public ProjCoordinate tmercForward() {
        return tmerc.projectRadians(geoIn, out);
    }

    @Benchmark
    public ProjCoordinate tmercInverse() {
        return tmerc.inverseProjectRadians(projInTmerc, out);
    }
}
