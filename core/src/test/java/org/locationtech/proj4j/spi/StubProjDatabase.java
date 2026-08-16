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
package org.locationtech.proj4j.spi;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A {@link ProjDatabase} that answers only the handful of questions a test has explicitly given it
 * answers to, and throws on everything else.
 * <p>
 * Throwing rather than returning null is the point. A fixture that answers {@code null} to a
 * question nobody meant to ask lets a future change silently read "the database does not know
 * that" and take a fallback path, which is how a test keeps passing after it has stopped testing
 * anything. Here the test fails, naming the method.
 * <p>
 * Populate with {@link #withUnit}, {@link #withDatum}, {@link #withEllipsoid}, {@link #withCrs},
 * {@link #withCoordinateSystem} and {@link #withOperation}; each returns
 * {@code this} so they chain. A code that was never added answers {@code null}, which is what a
 * real database does for an object it does not carry — so "the database does not know this one" is
 * still expressible, deliberately, per object rather than per method.
 */
public class StubProjDatabase implements ProjDatabase {

    private final String label;
    private final Map<String, DbUnit> units = new HashMap<String, DbUnit>();
    private final Map<String, DbDatum> datums = new HashMap<String, DbDatum>();
    private final Map<String, DbEllipsoid> ellipsoids = new HashMap<String, DbEllipsoid>();
    private final Map<String, DbCrs> crss = new HashMap<String, DbCrs>();
    private final Map<String, DbCoordinateSystem> coordinateSystems =
            new HashMap<String, DbCoordinateSystem>();
    private final Map<String, DbOperation> operations = new HashMap<String, DbOperation>();

    /**
     * @param label named in the exception every unanswered method throws, so a failure says which
     *              fixture was asked
     */
    public StubProjDatabase(String label) {
        this.label = label;
    }

    public StubProjDatabase withUnit(DbUnit unit) {
        units.put(key(unit.authName(), unit.code()), unit);
        return this;
    }

    public StubProjDatabase withDatum(DbDatum datum) {
        datums.put(key(datum.authName(), datum.code()), datum);
        return this;
    }

    public StubProjDatabase withEllipsoid(DbEllipsoid ellipsoid) {
        ellipsoids.put(key(ellipsoid.authName(), ellipsoid.code()), ellipsoid);
        return this;
    }

    public StubProjDatabase withCrs(DbCrs crs) {
        crss.put(key(crs.authName(), crs.code()), crs);
        return this;
    }

    public StubProjDatabase withCoordinateSystem(DbCoordinateSystem cs) {
        coordinateSystems.put(key(cs.authName(), cs.code()), cs);
        return this;
    }

    public StubProjDatabase withOperation(DbOperation operation) {
        operations.put(key(operation.authName(), operation.code()), operation);
        return this;
    }

    private static String key(String authName, String code) {
        return authName + ":" + code;
    }

    @Override
    public DbUnit unit(String authName, String code) {
        return units.get(key(authName, code));
    }

    @Override
    public DbDatum datum(DbObjectType type, String authName, String code) {
        DbDatum found = datums.get(key(authName, code));
        return found != null && found.type() == type ? found : null;
    }

    @Override
    public DbEllipsoid ellipsoid(String authName, String code) {
        return ellipsoids.get(key(authName, code));
    }

    protected UnsupportedOperationException notUsed() {
        return new UnsupportedOperationException("the \"" + label + "\" fixture was asked a "
                + "question it has no answer for; give it one or stop asking");
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public Map<String, String> metadata() {
        return Collections.emptyMap();
    }

    @Override
    public SortedSet<String> authorities() {
        return new TreeSet<String>(Collections.singleton("EPSG"));
    }

    @Override
    public DbCrs crs(String a, String c) {
        return crss.get(key(a, c));
    }

    @Override
    public List<DbObjectRef> crsCodes(String a) {
        throw notUsed();
    }

    @Override
    public DbCoordinateSystem coordinateSystem(String a, String c) {
        return coordinateSystems.get(key(a, c));
    }

    @Override
    public DbPrimeMeridian primeMeridian(String a, String c) {
        throw notUsed();
    }

    @Override
    public DbCelestialBody celestialBody(String a, String c) {
        throw notUsed();
    }

    @Override
    public List<DbObjectRef> crsUsingDatum(DbObjectType t, String a, String c) {
        throw notUsed();
    }

    @Override
    public DbConversion conversion(String a, String c) {
        throw notUsed();
    }

    @Override
    public DbOperation operation(String a, String c) {
        return operations.get(key(a, c));
    }

    @Override
    public List<DbOperation> operationsBetween(String sa, String sc, String ta, String tc) {
        throw notUsed();
    }

    @Override
    public List<DbObjectRef> operationsWithSourceCrs(String a, String c) {
        throw notUsed();
    }

    @Override
    public List<DbObjectRef> operationsWithTargetCrs(String a, String c) {
        throw notUsed();
    }

    @Override
    public List<DbExtent> extentsFor(DbObjectRef o) {
        throw notUsed();
    }

    @Override
    public DbExtent extent(String a, String c) {
        throw notUsed();
    }

    @Override
    public List<String> aliases(DbObjectRef o) {
        throw notUsed();
    }

    @Override
    public List<DbObjectRef> findCrsByName(String n) {
        throw notUsed();
    }

    @Override
    public List<DbSupersession> supersededBy(DbObjectRef o) {
        throw notUsed();
    }

    @Override
    public List<DbObjectRef> replacementsFor(DbObjectRef o) {
        throw notUsed();
    }

    @Override
    public DbGridAlternative gridAlternative(String originalGridName) {
        throw notUsed();
    }

    @Override
    public List<DbGridAlternative> gridAlternatives() {
        throw notUsed();
    }

    @Override
    public void close() throws IOException {
    }
}
