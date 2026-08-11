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
 *******************************************************************************/
package org.locationtech.proj4j.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.locationtech.proj4j.spi.ProjDatabase;
import org.locationtech.proj4j.spi.ProjDatabaseProvider;

/**
 * The wiring that lets {@code neoproj4j-db} plug into {@code neoproj4j} core:
 * {@link ProjDatabaseProvider#discover(ClassLoader)} must find {@link PjdxDatabaseProvider} when this
 * module is on the classpath.
 *
 * <h2>Why this test exists</h2>
 * The connection between the two modules is a single text file,
 * {@code src/main/resources/META-INF/services/org.locationtech.proj4j.spi.ProjDatabaseProvider}, whose
 * only content is a class name. The compiler never reads it. Delete it, rename it, misspell the class
 * inside it, or drop {@code src/main/resources} from the jar, and every other test in this module still
 * passes — {@code PjdxDatabaseTest} and friends call {@code Proj4jDb.open()} directly and never go
 * through {@code ServiceLoader}. The library would then ship with a database nobody can find, and the
 * first person to notice would be a user whose {@code discover()} call returned an empty list.
 * <p>
 * So this file asserts on the concrete class, not on the list merely being non-empty: a list with one
 * anonymous stub in it would satisfy "non-empty" and prove nothing.
 *
 * <h2>What is pinned here</h2>
 * <ul>
 *   <li>the exact resource path {@code ServiceLoader} looks for, and the class name written in it;</li>
 *   <li>discovery returning {@link PjdxDatabaseProvider} itself, with its published name and
 *       priority;</li>
 *   <li>{@code openFirst} actually opening the shipped index through that route;</li>
 *   <li>the two boundary cases the interface documents: no provider at all, and two of them.</li>
 * </ul>
 *
 * <h2>Core tests the other half</h2>
 * {@code ProjDatabaseSpiTest} in core runs with no implementation on the classpath and pins the empty
 * case there. This module is the only place where both halves are present at once, so it is the only
 * place the round trip can be tested.
 */
public class ProjDatabaseDiscoveryTest {

    /** The name {@code ServiceLoader} derives from the interface. Spelled out so a rename fails here. */
    private static final String SERVICES_PATH =
            "META-INF/services/org.locationtech.proj4j.spi.ProjDatabaseProvider";

    private static final String PROVIDER_CLASS = "org.locationtech.proj4j.db.PjdxDatabaseProvider";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    // ------------------------------------------------------------------ the registration file

    /**
     * The registration file is at the path {@code ServiceLoader} will look for, and names the provider.
     * Asserted separately from discovery so that a failure says which half broke: a missing file here,
     * or a provider that does not load there.
     */
    @Test
    public void theServicesFileIsWhereServiceLoaderLooksAndNamesTheProvider() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        URL url = loader.getResource(SERVICES_PATH);
        assertNotNull("no " + SERVICES_PATH + " on the classpath -- if it was renamed or dropped from "
                + "src/main/resources, discovery finds nothing and the compiler cannot tell you", url);
        assertEquals("the registration file must name exactly the provider class",
                Collections.singletonList(PROVIDER_CLASS), readNonBlankLines(url));

        // Exactly one registration, so a second copy cannot creep in from a shaded or duplicated jar
        // and turn discovery into a coin toss.
        int copies = 0;
        for (Enumeration<URL> e = loader.getResources(SERVICES_PATH); e.hasMoreElements(); ) {
            e.nextElement();
            copies++;
        }
        assertEquals("exactly one registration expected on this module's classpath", 1, copies);
    }

    // ------------------------------------------------------------------ the round trip

    /**
     * The whole point: with {@code neoproj4j-db} on the classpath, core's discovery finds this module's
     * provider. Asserted on the concrete class -- "the list is not empty" would pass with any stub in
     * it.
     */
    @Test
    public void discoveryFindsThePjdxProvider() {
        List<ProjDatabaseProvider> found =
                ProjDatabaseProvider.discover(getClass().getClassLoader());
        assertEquals("exactly one provider ships in this module", 1, found.size());
        ProjDatabaseProvider p = found.get(0);
        assertSame(PjdxDatabaseProvider.class, p.getClass());
        assertEquals("pjdx", p.name());
        assertEquals(100, p.priority());
    }

    /** A null loader means "this class's own loader", which here sees the same registration. */
    @Test
    public void discoveryWithANullLoaderFindsTheSameProvider() {
        List<ProjDatabaseProvider> found = ProjDatabaseProvider.discover(null);
        assertEquals(1, found.size());
        assertSame(PjdxDatabaseProvider.class, found.get(0).getClass());
    }

    /** The returned list is unmodifiable, as documented. */
    @Test
    public void theDiscoveredListIsUnmodifiable() {
        List<ProjDatabaseProvider> found =
                ProjDatabaseProvider.discover(getClass().getClassLoader());
        try {
            found.add(new NamedProvider("extra", 1));
            fail("discover() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // as documented
        }
    }

    /**
     * Discovery is not the end of it: the provider it finds must open the real shipped index. A
     * registration that names a class which cannot open anything is no better than no registration.
     */
    @Test
    public void openFirstOpensTheShippedIndex() throws IOException {
        ProjDatabase db = ProjDatabaseProvider.openFirst(getClass().getClassLoader());
        assertNotNull("the shipped .pjdx index must be reachable through discovery", db);
        try {
            assertTrue("the discovered database must be the real one", db.authorities().contains("EPSG"));
            assertNotNull(db.crs("EPSG", "4326"));
        } finally {
            db.close();
        }
    }

    // ------------------------------------------------------------------ zero providers

    /**
     * No provider registered is not an error. Core is expected to run with no database at all, so
     * discovery returns an empty list and {@code openFirst} returns null rather than throwing.
     * <p>
     * The loader below hides only the registration file; the provider class is still loadable through
     * the parent. That is exactly the shape of the failure this whole file guards against -- the jar is
     * present, the registration is not -- and it shows the difference is invisible without a test.
     */
    @Test
    public void noRegistrationMeansNothingIsFoundAndNothingThrows() throws IOException {
        ClassLoader hidden = new FixedServicesLoader(getClass().getClassLoader(),
                Collections.<URL>emptyList());
        assertEquals(Collections.emptyList(), ProjDatabaseProvider.discover(hidden));
        assertNull(ProjDatabaseProvider.openFirst(hidden));
    }

    // ------------------------------------------------------------------ two providers

    /**
     * Two providers are allowed, and the order they come back in is fixed by {@code (priority, name)} --
     * not by which jar the class loader happened to reach first. Classpath order differs between a
     * shaded jar, an IDE and a Spark executor, so the sort is what stops the choice of database from
     * being a property of the deployment.
     */
    @Test
    public void severalProvidersComeBackSortedByPriorityThenName() throws IOException {
        // Registered in the file in the wrong order on purpose: file order must not decide.
        ClassLoader loader = loaderWith(HighPriorityZebra.class, LowPriorityApple.class,
                LowPriorityBanana.class);
        List<ProjDatabaseProvider> found = ProjDatabaseProvider.discover(loader);
        assertEquals(Arrays.asList("apple", "banana", "zebra"), names(found));
        assertSame(LowPriorityApple.class, found.get(0).getClass());
        assertSame(LowPriorityBanana.class, found.get(1).getClass());
        assertSame(HighPriorityZebra.class, found.get(2).getClass());

        // None of them has data, so openFirst reports "no database" rather than the first provider's
        // null.
        assertNull(ProjDatabaseProvider.openFirst(loader));
    }

    /**
     * Two providers that share both {@code priority} and {@code name} are rejected outright. Picking one
     * would mean the answer depends on classpath order; the exception names both classes so the person
     * reading the stack trace knows which two jars to look at.
     */
    @Test
    public void twoProvidersSharingPriorityAndNameAreRejected() throws IOException {
        ClassLoader loader = loaderWith(FirstTwin.class, SecondTwin.class);
        try {
            ProjDatabaseProvider.discover(loader);
            fail("a duplicate (priority, name) must be rejected, not resolved by luck");
        } catch (IllegalStateException expected) {
            String m = expected.getMessage();
            assertTrue(m, m.contains("(7, twin)"));
            assertTrue("both classes must be named: " + m, m.contains(FirstTwin.class.getName()));
            assertTrue("both classes must be named: " + m, m.contains(SecondTwin.class.getName()));
        }
    }

    /**
     * The same name at different priorities is not a duplicate -- priority is part of the key -- so both
     * survive, lower priority first.
     */
    @Test
    public void theSameNameAtDifferentPrioritiesIsNotADuplicate() throws IOException {
        List<ProjDatabaseProvider> found = ProjDatabaseProvider.discover(
                loaderWith(SameNameHigh.class, SameNameLow.class));
        assertEquals(2, found.size());
        assertSame(SameNameLow.class, found.get(0).getClass());
        assertSame(SameNameHigh.class, found.get(1).getClass());
    }

    /**
     * A provider with no name cannot be ordered against anything, so it is refused at discovery rather
     * than sorted into an arbitrary position.
     */
    @Test
    public void aProviderWithoutANameIsRejected() throws IOException {
        try {
            ProjDatabaseProvider.discover(loaderWith(NamelessProvider.class));
            fail("a provider with an empty name() must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(NamelessProvider.class.getName()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(List<ProjDatabaseProvider> providers) {
        List<String> out = new ArrayList<String>();
        for (ProjDatabaseProvider p : providers) {
            out.add(p.name());
        }
        return out;
    }

    private static List<String> readNonBlankLines(URL url) throws IOException {
        List<String> lines = new ArrayList<String>();
        InputStream in = url.openStream();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } finally {
            in.close();
        }
        return lines;
    }

    /**
     * A class loader whose registration file lists exactly {@code classes}, and nothing this module
     * ships. The classes themselves still load through the parent, so {@code ServiceLoader} instantiates
     * the real test doubles below.
     */
    private ClassLoader loaderWith(Class<?>... classes) throws IOException {
        java.io.File file = folder.newFile();
        PrintWriter w = new PrintWriter(file, "UTF-8");
        try {
            for (Class<?> c : classes) {
                w.println(c.getName());
            }
        } finally {
            w.close();
        }
        return new FixedServicesLoader(getClass().getClassLoader(),
                Collections.singletonList(file.toURI().toURL()));
    }

    /** Serves a fixed set of registration files, and delegates everything else to the parent. */
    private static final class FixedServicesLoader extends ClassLoader {
        private final List<URL> registrations;

        FixedServicesLoader(ClassLoader parent, List<URL> registrations) {
            super(parent);
            this.registrations = registrations;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (SERVICES_PATH.equals(name)) {
                return Collections.enumeration(registrations);
            }
            return super.getResources(name);
        }

        @Override
        public URL getResource(String name) {
            if (SERVICES_PATH.equals(name)) {
                return registrations.isEmpty() ? null : registrations.get(0);
            }
            return super.getResource(name);
        }
    }

    /**
     * Base for the test doubles. {@code open()} returns null, which the interface defines as "this
     * provider's data is not on the classpath" -- the honest answer for a provider with no data at all.
     */
    public abstract static class NamedProviderBase implements ProjDatabaseProvider {
        @Override
        public ProjDatabase open() {
            return null;
        }
    }

    /** Used only for the unmodifiable-list check, where it is never discovered. */
    private static final class NamedProvider extends NamedProviderBase {
        private final String name;
        private final int priority;

        NamedProvider(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    public static final class LowPriorityApple extends NamedProviderBase {
        @Override
        public String name() {
            return "apple";
        }

        @Override
        public int priority() {
            return 10;
        }
    }

    public static final class LowPriorityBanana extends NamedProviderBase {
        @Override
        public String name() {
            return "banana";
        }

        @Override
        public int priority() {
            return 10;
        }
    }

    public static final class HighPriorityZebra extends NamedProviderBase {
        @Override
        public String name() {
            return "zebra";
        }

        @Override
        public int priority() {
            return 20;
        }
    }

    public static final class FirstTwin extends NamedProviderBase {
        @Override
        public String name() {
            return "twin";
        }

        @Override
        public int priority() {
            return 7;
        }
    }

    public static final class SecondTwin extends NamedProviderBase {
        @Override
        public String name() {
            return "twin";
        }

        @Override
        public int priority() {
            return 7;
        }
    }

    public static final class SameNameLow extends NamedProviderBase {
        @Override
        public String name() {
            return "same";
        }

        @Override
        public int priority() {
            return 1;
        }
    }

    public static final class SameNameHigh extends NamedProviderBase {
        @Override
        public String name() {
            return "same";
        }

        @Override
        public int priority() {
            return 2;
        }
    }

    public static final class NamelessProvider extends NamedProviderBase {
        @Override
        public String name() {
            return "";
        }

        @Override
        public int priority() {
            return 1;
        }
    }
}
