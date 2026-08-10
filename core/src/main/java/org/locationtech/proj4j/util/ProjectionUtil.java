/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.util;

import org.locationtech.proj4j.ProjCoordinate;

/**
 * One static helper that formats a {@link ProjCoordinate} as {@code [x, y]}.
 *
 * <p>No main source calls it. Its only callers are two test classes,
 * {@code ProjectionGridRoundTripper} and {@code MetaCRSTestCase}, which use it to build failure
 * messages. It predates {@link ProjCoordinate#toString()}, which produces
 * {@code ProjCoordinate[x y z]} and includes the third ordinate — for new code that is the one to
 * use, since it does not drop {@code z}.
 *
 * <p>The class is kept because {@code org.locationtech.proj4j.util} is an exported package and
 * removing a public class is a binary break.
 */
public class ProjectionUtil {

    /**
     * Formats the horizontal ordinates of {@code p} as {@code [x, y]}. The {@code z} ordinate is
     * not shown.
     *
     * @param p the coordinate to format
     * @return the two horizontal ordinates in brackets, comma-separated
     */
    public static String toString(ProjCoordinate p) {
        return "[" + p.x + ", " + p.y + "]";
    }

}
