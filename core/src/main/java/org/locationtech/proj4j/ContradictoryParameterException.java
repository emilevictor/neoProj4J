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
package org.locationtech.proj4j;

/**
 * Signals that a CRS definition supplies two or more parameters that contradict each other, or
 * that specify the same quantity inconsistently.
 *
 * <p><b>Nothing in this library throws it.</b> No main-source path constructs it, so a
 * {@code catch} clause naming this class is a branch that can never be entered. The live
 * contradiction path throws {@link org.locationtech.proj4j.pipeline.PipelineDefinitionException} instead — a sibling that also
 * extends {@link InvalidValueException} and also reports
 * {@link ErrorCause#CONTRADICTORY_PARAMS} — from {@code AxisSwapOperator} and
 * {@code DeformationOperator}. Catch {@link InvalidValueException}, or switch on
 * {@link ErrorCause#CONTRADICTORY_PARAMS}, and both are covered.
 *
 * <p>Earlier versions of this Javadoc offered {@code +ellps=GRS80 +rf=300} and
 * {@code +rf=298.257 +f=0.00335} as examples. They were the wrong examples: this library
 * deliberately accepts both, following PROJ's {@code ell_set.cpp}, and lets the later shape
 * parameter win. That is recorded at {@code StepEllipsoid} and in {@code Proj4Parser}'s
 * ellipsoid handling. So the two combinations most likely to be reached for as a test of this
 * exception are combinations that raise nothing at all.
 *
 * <p>PROJ's equivalent condition is {@code PROJ_ERR_INVALID_OP_MUTUALLY_EXCLUSIVE_ARGS}.
 *
 * @see ErrorCause#CONTRADICTORY_PARAMS
 * @see org.locationtech.proj4j.pipeline.PipelineDefinitionException
 * @since 1.5.0
 * @deprecated Never thrown by this library. It is kept because
 *     {@code org.locationtech.proj4j} is an exported package and removing a public class is a
 *     binary break, and because out-of-tree code may throw it itself. Catch
 *     {@link InvalidValueException} for the condition it names.
 */
@Deprecated
public class ContradictoryParameterException extends InvalidValueException {

    private static final long serialVersionUID = -532824264813230743L;

    /** The cause reported by every constructor. */
    private static final ErrorCause DEFAULT_CAUSE = ErrorCause.CONTRADICTORY_PARAMS;

    /**
     * Creates an exception reporting {@link ErrorCause#CONTRADICTORY_PARAMS}.
     *
     * @param message the human-readable detail message; it should name both of the parameters
     *                that disagree, and the value each implies
     */
    public ContradictoryParameterException(String message) {
        super(DEFAULT_CAUSE, message);
    }

    /**
     * Creates an exception reporting {@link ErrorCause#CONTRADICTORY_PARAMS} and wrapping another
     * throwable.
     *
     * @param message   the human-readable detail message
     * @param throwable the underlying throwable, or null
     */
    public ContradictoryParameterException(String message, Throwable throwable) {
        super(DEFAULT_CAUSE, message, throwable);
    }
}
