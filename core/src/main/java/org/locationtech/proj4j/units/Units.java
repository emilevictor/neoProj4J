/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

package org.locationtech.proj4j.units;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The unit tables.
 *
 * <h2>Which table {@code +units} uses, and why {@code rad} is not in it</h2>
 *
 * <p>PROJ keeps <b>two</b> unit tables in {@code src/units.cpp}: {@code pj_units}, the
 * <i>linear</i> table of 21 ids, and {@code pj_angular_units}, holding just
 * {@code rad}, {@code deg} and {@code grad}. {@code +units} and {@code +vunits} are
 * resolved against {@code pj_list_linear_units()} alone ({@code init.cpp:679,718}),
 * so an angular id is an <i>error</i> there, not a conversion. Verified against the
 * installed 9.8.1 build:
 *
 * <pre>
 * $ echo "2 1" | proj +proj=merc +ellps=GRS80 +units=rad
 * merc: Invalid value for units          # and identically for +units=deg, +units=grad
 * </pre>
 *
 * <p>{@link #units}, the table {@link #findUnits(String)} searches, therefore carries
 * PROJ's 21 linear ids and nothing else beyond {@link #DEGREES} — which is retained
 * only because Proj4J's own {@code LongLatProjection} and {@code geoapi} module look
 * units up by the {@code "deg"}/{@code "degrees"} symbol. {@link #RADIANS},
 * {@link #GRADS}, {@link #ARC_MINUTES}, {@link #ARC_SECONDS} and {@link #POINTS} are
 * declared but deliberately kept out of it: they are not {@code +units} names in PROJ,
 * and putting them there would make Proj4J accept definitions PROJ rejects.
 *
 * <p><b>{@link #DEGREES} is in {@link #units} for those callers only, and is not
 * reachable through {@code +units=}.</b> It carries name {@code degree}, plural
 * {@code degrees} and abbreviation {@code deg}, so while it sits in the table that
 * {@link #findUnits(String)} searches, {@code +units=deg} used to resolve to it and
 * produce a wrong coordinate rather than an error. {@code Proj4Parser} therefore does
 * <b>not</b> call {@link #findUnits(String)} for {@code +units=} any more: it scans
 * {@link #LINEAR_UNITS} and compares the {@code abbreviation} alone, so the three
 * degree spellings are refused there while staying available here.
 *
 * <h2>The metres fallback, and who relies on it</h2>
 *
 * <p>{@link #findUnits(String)} returns {@link #METRES} for a name it does not know
 * rather than {@code null}, which is why {@code +units=<garbage>} was silently metres
 * for as long as the parser used this method. It no longer does, so the fallback is
 * not on the {@code +units=} path at all — but it is deliberately kept here, because
 * three callers depend on being able to look a unit up by <i>name</i> rather than by id
 * and are not parsing a PROJ.4 string:
 *
 * <ul>
 * <li>{@code io.wkt.WktNames.projUnitsCode} and {@code unitFromProjCode}, reading
 *     {@code UNIT["metre",1]} out of a WKT string, where {@code metre} is the spelling
 *     WKT uses and {@code m} is not.</li>
 * <li>{@code proj4j-geoapi}'s {@code Units.getUnit(String symbol)}, a symbol lookup.</li>
 * </ul>
 *
 * <p>Detection of the fallback therefore still belongs to those callers.
 * {@link #isKnownUnit(String)} is how they do it, and it is intentionally still true
 * for {@code deg}, {@code degree} and {@code degrees} — which is why the refusal of
 * those three had to be built at the parse level and not here.
 *
 * <h2>Discovering what {@code +units=} accepts</h2>
 *
 * <p>{@link #linearUnitIds()} is the supported way to ask. See its own comment for why
 * reading {@link #LINEAR_UNITS} directly is a hazard on a mixed classpath.
 */
public class Units {

    // Angular units
    public final static Unit DEGREES = new DegreeUnit();
    public final static Unit RADIANS = new Unit("radian", "radians", "rad", ProjectionMath.toDeg(1));
    /** {@code grad} — 400 to the turn. PROJ's third angular unit ({@code units.cpp}). */
    public final static Unit GRADS = new Unit("grad", "grads", "grad", 0.9);
    public final static Unit ARC_MINUTES = new Unit("arc minute", "arc minutes", "min", 1/60.0);
    public final static Unit ARC_SECONDS = new Unit("arc second", "arc seconds", "sec", 1/3600.0);

    // Distance units

    // Metric units
    public final static Unit KILOMETRES = new Unit("kilometre", "kilometres", "km", 1000);
    public final static Unit METRES = new Unit("metre", "metres", "m", 1);
    public final static Unit DECIMETRES = new Unit("decimetre", "decimetres", "dm", 0.1);
    public final static Unit CENTIMETRES = new Unit("centimetre", "centimetres", "cm", 0.01);
    public final static Unit MILLIMETRES = new Unit("millimetre", "millimetres", "mm", 0.001);

    // International units
    public final static Unit NAUTICAL_MILES = new Unit("nautical mile", "nautical miles", "kmi", 1852);
    public final static Unit MILES = new Unit("mile", "miles", "mi", 1609.344);
    public final static Unit CHAINS = new Unit("chain", "chains", "ch", 20.1168);
    public final static Unit YARDS = new Unit("yard", "yards", "yd", 0.9144);
    public final static Unit FEET = new Unit("foot", "feet", "ft", 0.3048);
    public final static Unit INCHES = new Unit("inch", "inches", "in", 0.0254);

    // U.S. units
    public final static Unit US_MILES = new Unit("U.S. mile", "U.S. miles", "us-mi", 1609.347218694437);
    public final static Unit US_CHAINS = new Unit("U.S. chain", "U.S. chains", "us-ch", 20.11684023368047);
    public final static Unit US_YARDS = new Unit("U.S. yard", "U.S. yards", "us-yd", 0.914401828803658);
    public final static Unit US_FEET = new Unit("U.S. foot", "U.S. feet", "us-ft", 0.304800609601219);
    public final static Unit US_INCHES = new Unit("U.S. inch", "U.S. inches", "us-in", 1.0/39.37);

    // Indian units. Present in PROJ's pj_units, absent from Proj4J until now, so
    // +units=ind-yd silently scaled by 1 instead of by 0.91439523 - a 9% error.
    public final static Unit INDIAN_YARDS = new Unit("Indian yard", "Indian yards", "ind-yd", 0.91439523);
    public final static Unit INDIAN_FEET = new Unit("Indian foot", "Indian feet", "ind-ft", 0.30479841);
    public final static Unit INDIAN_CHAINS = new Unit("Indian chain", "Indian chains", "ind-ch", 20.11669506);

    // Miscellaneous units
    public final static Unit FATHOMS = new Unit("fathom", "fathoms", "fath", 1.8288);
    public final static Unit LINKS = new Unit("link", "links", "link", 0.201168);

    /** Not a PROJ {@code +units} name; see the class comment. */
    public final static Unit POINTS = new Unit("point", "points", "point", 0.0254/72.27);

    /**
     * PROJ's 21 linear unit ids ({@code pj_units} in {@code src/units.cpp}), in that
     * file's order. These are exactly the names {@code +units} and {@code +vunits}
     * accept.
     */
    public static final Unit[] LINEAR_UNITS = {
        KILOMETRES, METRES, DECIMETRES, CENTIMETRES, MILLIMETRES,
        NAUTICAL_MILES,
        INCHES, FEET, YARDS, MILES,
        FATHOMS, CHAINS, LINKS,
        US_INCHES, US_FEET, US_YARDS, US_CHAINS, US_MILES,
        INDIAN_YARDS, INDIAN_FEET, INDIAN_CHAINS
    };

    /**
     * PROJ's 3 angular unit ids ({@code pj_angular_units}). Reachable through
     * {@code +proj=unitconvert}'s {@code +xy_in}/{@code +xy_out}, <b>not</b> through
     * {@code +units}.
     */
    public static final Unit[] ANGULAR_UNITS = {RADIANS, DEGREES, GRADS};

    /**
     * The table {@link #findUnits(String)} searches: PROJ's linear units, plus
     * {@link #DEGREES} for Proj4J's own {@code +proj=longlat} handling.
     * <p>
     * Kept as a mutable public field for source compatibility; prefer
     * {@link #LINEAR_UNITS}.
     */
    public static Unit[] units = concat(new Unit[]{DEGREES}, LINEAR_UNITS);

    /**
     * Built once from {@link #LINEAR_UNITS}, so {@link #linearUnitIds()} and the
     * {@code +units=} lookup in {@code Proj4Parser} cannot disagree about which ids
     * are accepted: both read that one array.
     */
    private static final Set<String> LINEAR_UNIT_IDS = linearIdSet();

    private static Set<String> linearIdSet() {
        Set<String> ids = new LinkedHashSet<String>();
        for (int i = 0; i < LINEAR_UNITS.length; i++) {
            ids.add(LINEAR_UNITS[i].abbreviation);
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * The unit ids {@code +units=} accepts: PROJ's 21 linear ids from
     * {@code pj_units} in {@code src/units.cpp}, in that file's order.
     *
     * <p>{@code +units=} is resolved against these ids and nothing else,
     * case-sensitively, exactly as {@code init.cpp:679} resolves it against
     * {@code pj_list_linear_units()}. A name or plural is not an id, so
     * {@code +units=feet} and {@code +units=metre} are errors even though
     * {@link #findUnits(String)} resolves both; and the comparison is case-sensitive,
     * so {@code +units=US-FT} is an error while {@code +units=us-ft} is not. This
     * method is the only supported way to discover the accepted set.
     *
     * <p><b>Use this rather than reading {@link #LINEAR_UNITS}.</b> That field is a
     * fork-only public field which does not exist in upstream Proj4J 1.4.3, so code
     * that reads it from a static initialiser fails with {@link NoSuchFieldError} out
     * of {@code <clinit>} when it lands on a 1.4.3 classpath. That is an
     * {@link Error} rather than an {@link Exception}, so it escapes a
     * {@code catch (Exception)} and surfaces as a
     * {@link ExceptionInInitializerError} or {@link NoClassDefFoundError} somewhere
     * unrelated. A consumer hit exactly that. This accessor exists so there is a
     * stable entry point to depend on.
     *
     * @return the 21 accepted ids, unmodifiable; never null and never empty
     * @since 1.5.0
     */
    public static Set<String> linearUnitIds() {
        return LINEAR_UNIT_IDS;
    }

    private static Unit[] concat(Unit[] head, Unit[] tail) {
        Unit[] all = new Unit[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /**
     * Looks a unit up by name, plural or abbreviation.
     *
     * @return the unit, or {@link #METRES} when {@code name} is not a known unit —
     *         <b>never {@code null}</b>. Callers that must distinguish the two have to
     *         check whether the returned unit actually answers to {@code name}.
     */
    public static Unit findUnits(String name) {
        for (int i = 0; i < units.length; i++) {
            if (name.equals(units[i].name) || name.equals(units[i].plural) || name.equals(units[i].abbreviation))
                return units[i];
        }
        return METRES;
    }

    /**
     * Whether {@code name} really is one of the units in {@link #units}, as opposed to
     * a name for which {@link #findUnits(String)} substituted {@link #METRES}.
     */
    public static boolean isKnownUnit(String name) {
        if (name == null)
            return false;
        Unit unit = findUnits(name);
        return name.equals(unit.name) || name.equals(unit.plural) || name.equals(unit.abbreviation);
    }

    public static double convert(double value, Unit from, Unit to) {
        if (from == to)
            return value;
        return to.fromBase(from.toBase(value));
    }

}
