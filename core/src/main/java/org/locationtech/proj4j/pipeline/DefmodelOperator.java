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
package org.locationtech.proj4j.pipeline;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CrsTransformException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.Proj4jException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;

/**
 * {@code +proj=defmodel} — a gridded deformation model described by a JSON master file,
 * ported from {@code 9.8.1:src/transformations/defmodel.cpp}. The file format is
 * {@link DefmodelMasterFile} and the arithmetic is {@link DefmodelEvaluator}; this class
 * is the parameter handling, the file read, the CRS lookup and the
 * {@link PipelineOperator} contract.
 *
 * <h2>What it does, in one sentence</h2>
 *
 * <p>A master file lists <em>components</em>, each pairing a grid of horizontal and
 * vertical offsets with a function of time; a coordinate's epoch turns each component's
 * time function into a scale factor, the grid is interpolated at the coordinate, and the
 * scaled offsets from every component that covers the point are summed and applied.
 *
 * <h2>Why the epoch is mandatory here and optional in {@code +proj=deformation}</h2>
 *
 * <p>{@link DeformationOperator} has {@code +dt}, a fixed interval that makes the
 * coordinate's own epoch irrelevant. This operator has no such parameter: a deformation
 * model is only defined <em>at</em> an epoch, so a coordinate arriving with
 * {@code t = HUGE_VAL} is {@link ErrorCause#MISSING_TIME} in both directions
 * ({@code defmodel.cpp:348-351} and {@code :364-367}).
 *
 * <p>As with {@code deformation}, note that gie zero-fills an unwritten fourth ordinate,
 * so "no epoch" has to be written out as the literal {@code HUGE_VAL}; a three-ordinate
 * row arrives with {@code t = 0}, which is a valid observation at year zero and will be
 * refused later, by the model's own time extent, rather than here.
 *
 * <h2>The units of both sides depend on the file's contents</h2>
 *
 * <p>{@code defmodel.cpp:441-447}: {@code RADIANS} on both sides when
 * {@code definition_crs} is geographic, {@code PROJECTED} on both sides when it is not.
 * The operator is symmetric, so the two sides are always equal — there is no case where
 * this operator changes units.
 *
 * <p>That decision is made <b>from the model file</b>, not from the proj-string, which is
 * why a projected model such as {@code tests/simple_model_projected.json} works as a
 * bare {@code +proj=pipeline +step +proj=defmodel} with no {@code merc} either side while
 * the metre <em>geographic</em> models in the same corpus are written between two
 * {@code merc} steps. Getting this backwards would not fail loudly; it would make gie
 * measure a geodesic distance over projected metres, or a Euclidean distance over
 * radians, and either one silently rescales every tolerance in the block.
 *
 * <h2>An unknown {@code definition_crs} is read as geographic, deliberately</h2>
 *
 * <p>{@code EvaluatorIface::isGeographicCRS} ({@code defmodel.cpp:281-291}) calls
 * {@code proj_create} and, when that returns null, answers {@code true} with the comment
 * "reasonable default value". So a model naming a CRS this library cannot resolve is
 * treated as geographic rather than refused.
 *
 * <p>This looks like a bug and is not one to fix here. Almost every deformation model in
 * existence is defined on a geographic CRS; guessing geographic is right nearly always,
 * and guessing wrong only mis-scales a model that would otherwise not load at all.
 * Making it fail-closed would refuse every model whose CRS is outside proj4j's shipped
 * dictionary — a much larger set than PROJ's {@code proj.db} — and turn a working
 * transformation into an error for a reason that has nothing to do with the deformation.
 * The corpus's models name only {@code EPSG:4326} and {@code EPSG:2193}, both of which
 * resolve, so this path is not what makes those rows pass; it is here for faithfulness.
 *
 * <h2>Reading the file</h2>
 *
 * <p>Through the deterministic resolver chain, never the working directory, and with
 * upstream's own <b>10 MB</b> ceiling ({@code defmodel.cpp:410-416}) — a tenth of
 * {@code tinshift}'s, and the two are not interchangeable. The bytes are decoded as
 * UTF-8.
 *
 * <h2>Mutable, and not safe to share</h2>
 *
 * <p>The evaluator opens grids on first use and caches per-cell values, so an instance of
 * this class must not be used from two threads. {@code pipeline/package-info.java}
 * already states that for every operator.
 *
 * @since 2.3.0
 */
final class DefmodelOperator implements PipelineOperator {

    /** {@code size > 10 * 1024 * 1024} ({@code defmodel.cpp:412}). Not tinshift's 100 MB. */
    private static final long MAX_MODEL_BYTES = 10L * 1024L * 1024L;

    private final String file;
    private final DefmodelMasterFile model;
    private final DefmodelEvaluator evaluator;
    private final GieIoUnits units;
    private final String description;

    /** Receives {@code {x, y, z}} from the evaluator; reused, so single-threaded only. */
    private final double[] out = new double[3];

    /**
     * @param registry resolves {@code +ellps=}
     * @param params   the step's fully expanded parameter list
     */
    DefmodelOperator(final Registry registry, final ProjParams params) {
        this.file = params.value("model");
        if (file == null || file.isEmpty()) {
            throw new PipelineDefinitionException(PipelineErrorCode.MISSING_ARG,
                    "+model= should be specified.");
        }
        this.model = DefmodelMasterFile.parse(read(file));

        // defmodel.cpp:387-392: a +proj=cart +a=1 child that then inherits this step's
        // ellipsoid, i.e. (a, es) are copied across rather than re-parsed.
        final double[] ellipsoid = StepEllipsoid.resolve(registry, params);
        final double a = ellipsoid[0];
        // PROJ carries b on the PJ as (1 - f) * a. StepEllipsoid answers (a, es) only, so
        // b is recovered as a * sqrt(1 - es). The two agree to about one unit in the last
        // place -- under a nanometre on an Earth-sized ellipsoid -- and b enters only
        // through the northing-to-latitude divisor, where the tightest tolerance in the
        // corpus is a tenth of a millimetre.
        final double b = a * Math.sqrt(1 - ellipsoid[1]);

        this.evaluator = new DefmodelEvaluator(model, a, b,
                isGeographicCRS(model.definitionCRS()));
        this.units = evaluator.isGeographicCRS() ? GieIoUnits.RADIANS : GieIoUnits.PROJECTED;
        this.description = "defmodel model=" + file;
    }

    /**
     * {@code EvaluatorIface::isGeographicCRS} ({@code defmodel.cpp:281-291}).
     *
     * @param crsName the model's {@code definition_crs}, e.g. {@code EPSG:4326}
     * @return whether it is a geographic CRS; {@code true} when it cannot be resolved
     */
    private static boolean isGeographicCRS(final String crsName) {
        final CoordinateReferenceSystem crs;
        try {
            crs = new CRSFactory().createFromName(crsName);
        } catch (final Proj4jException e) {
            // proj_create returned nullptr. Upstream's own comment: "reasonable default
            // value". See this class's javadoc for why it stays that way.
            return true;
        }
        if (crs == null) {
            return true;
        }
        final Boolean geographic = crs.isGeographic();
        return geographic == null ? true : geographic.booleanValue();
    }

    private static String read(final String fileName) {
        final ChainedResourceResolver chain = ResourceResolvers.resolver();
        final ResourceHandle handle;
        try {
            handle = chain.resolve(fileName);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot open " + fileName + ": " + e.getMessage(), e);
        }
        if (handle == null) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot open " + fileName + ". Resolution chain was " + chain.name()
                            + "; the working directory is deliberately not searched.");
        }
        final byte[] bytes;
        try {
            bytes = Resources.readAll(handle, MAX_MODEL_BYTES);
        } catch (final IOException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot read " + fileName + ": " + e.getMessage(), e);
        }
        try {
            return new String(bytes, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            // Unreachable: every JVM is required to support UTF-8.
            throw new PipelineDefinitionException(PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID,
                    "Cannot decode " + fileName + " as UTF-8", e);
        }
    }

    /** @return the {@code +model=} value as written. */
    String file() {
        return file;
    }

    /** @return the parsed master file, for tests and diagnostics. */
    DefmodelMasterFile model() {
        return model;
    }

    /** {@code P-&gt;left} ({@code defmodel.cpp:441-447}). */
    @Override
    public GieIoUnits declaredLeft() {
        return units;
    }

    /** {@code P-&gt;right}, always equal to the left: the operator does not change units. */
    @Override
    public GieIoUnits declaredRight() {
        return units;
    }

    /** Never called: neither declared side is {@link GieIoUnits#WHATEVER}. */
    @Override
    public void overrideUnits(final GieIoUnits left, final GieIoUnits right) {
        // no-op, deliberately
    }

    /** {@code forward_4d} ({@code defmodel.cpp:345-359}). */
    @Override
    public void forward(final double[] coord) {
        requireEpoch(coord[3]);
        if (!evaluator.forward(coord[0], coord[1], coord[2], coord[3], false, out)) {
            throw failed();
        }
        coord[0] = out[0];
        coord[1] = out[1];
        coord[2] = out[2];
    }

    /** {@code reverse_4d} ({@code defmodel.cpp:361-375}). */
    @Override
    public void inverse(final double[] coord) {
        requireEpoch(coord[3]);
        if (!evaluator.inverse(coord[0], coord[1], coord[2], coord[3], out)) {
            throw failed();
        }
        coord[0] = out[0];
        coord[1] = out[1];
        coord[2] = out[2];
    }

    /**
     * {@code if (coo.xyzt.t == HUGE_VAL)} → {@code PROJ_ERR_COORD_TRANSFM_MISSING_TIME}.
     *
     * <p>One small widening: upstream tests for {@code HUGE_VAL} exactly, so a
     * {@code NaN} epoch passes its test, then passes the model's time extent test too —
     * {@code NaN &lt; first} and {@code NaN &gt; last} are both false — and comes out as a
     * {@code NaN} coordinate with no error raised. Here any non-finite epoch is
     * {@code MISSING_TIME}. gie writes the literal {@code HUGE_VAL} and never a
     * {@code NaN}, so no row can tell the difference; the widening exists so that an API
     * caller cannot get a {@code NaN} coordinate back from a successful call, which is
     * this project's fail-closed rule. {@link DeformationOperator} makes the same choice
     * for the same reason.
     */
    private void requireEpoch(final double t) {
        if (Double.isInfinite(t) || Double.isNaN(t)) {
            throw new CrsTransformException(ErrorCause.MISSING_TIME, "+proj=defmodel +model="
                    + file + " is a deformation model, which is only defined at an epoch, "
                    + "and this coordinate has none");
        }
    }

    /**
     * Upstream returns an all-{@code HUGE_VAL} coordinate here and sets no error code, so
     * a caller sees only that the answer is not a coordinate. Here it is a throw carrying
     * the evaluator's own reason — which point, which component, which grid, or how far
     * the inverse got — and a classification. gie scores the two identically.
     */
    private CrsTransformException failed() {
        return new CrsTransformException(evaluator.cause(),
                "+proj=defmodel +model=" + file + ": " + evaluator.reason());
    }

    @Override
    public boolean hasInverse() {
        return true;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "DefmodelOperator[" + description + ", " + units + "]";
    }
}
