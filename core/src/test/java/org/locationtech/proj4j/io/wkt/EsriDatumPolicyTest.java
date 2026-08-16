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
package org.locationtech.proj4j.io.wkt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.locationtech.proj4j.CrsCreationException;
import org.locationtech.proj4j.api.Crs;
import org.locationtech.proj4j.api.Proj;
import org.locationtech.proj4j.api.ProjContext;

/**
 * {@link EsriDatumPolicy}: refusing an ESRI {@code D_} reference frame this library cannot place,
 * rather than quietly reducing it to its ellipsoid.
 *
 * <h2>The divergence this pins</h2>
 *
 * <p>PROJ 9.8.1 resolves an ESRI {@code D_} name through its own {@code alias_name} table
 * ({@code data/sql/esri.sql}, read by {@code AuthorityFactory::getOfficialNameFromAlias} and used
 * from {@code io.cpp}'s {@code buildGeodeticReferenceFrame}). proj4j 2.1.0 had a ten-entry
 * hardcoded table and nothing else, so every other {@code D_} name fell through to the ellipsoid
 * and the shift to WGS 84 went missing with no diagnostic at all.
 *
 * <p>Measured against the installed PROJ 9.8.1 binaries, both legs of every measurement controlled
 * against {@code projinfo}'s own {@code -o PROJ} export of the same document (which must read
 * 0.000 m, and does):
 *
 * <table>
 * <caption>missing shift, by probe point</caption>
 * <tr><th>ESRI frame</th><th>probe</th><th>shift PROJ applies, proj4j 2.1.0 omitted</th></tr>
 * <tr><td>{@code D_European_1950}</td><td>5&deg;E 52&deg;N</td><td>124.286 m</td></tr>
 * <tr><td>{@code D_Tokyo}</td><td>139.7&deg;E 35.7&deg;N</td><td>462.853 m</td></tr>
 * <tr><td>{@code D_Pulkovo_1942}</td><td>37.6&deg;E 55.75&deg;N</td><td>117.802 m</td></tr>
 * <tr><td>{@code D_CH1903}</td><td>7.44&deg;E 46.95&deg;N</td><td>163.878 m</td></tr>
 * </table>
 *
 * <p>Every one of those figures is a property of its probe point and means nothing quoted without
 * one.
 *
 * <p>And the case where PROJ itself is silently wrong, which is why the default here diverges
 * rather than merely catching up: given {@code DATUM["D_Nonsense_Datum", SPHEROID["GRS_1980",
 * 6378137.0, 298.257222101]]}, PROJ 9.8.1 writes the name through verbatim with <b>no datum
 * identifier</b> and transforms to WGS 84 with a displacement of <b>0.000 m</b>. It answers where
 * it cannot know. proj4j refuses.
 */
public class EsriDatumPolicyTest {

    /** A bare GEOGCS: no conversion, so 2.1.0 computed the ESRI flag and then discarded it. */
    private static final String ED50_GEOGCS =
            "GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\","
                    + "SPHEROID[\"International_1924\",6378388.0,297.0]],"
                    + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";

    private static final String NONSENSE_GEOGCS =
            "GEOGCS[\"GCS_Nonsense_Datum\",DATUM[\"D_Nonsense_Datum\","
                    + "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],"
                    + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";

    private static String proj(String wkt) {
        return CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt),
                AxisOrderPolicy.LEGACY);
    }

    private static String projAllowing(String wkt) {
        return CrsDefinitions.toProjParameterString(new WktReader().readDefinition(wkt),
                AxisOrderPolicy.LEGACY, null, EsriDatumPolicy.ALLOW);
    }

    /** An ESRI-flavoured bare GEOGCS, for the cases that differ only in the two names. */
    private static String gcs(String gcsName, String datumName, String spheroid, double a,
                              double rf) {
        return "GEOGCS[\"" + gcsName + "\",DATUM[\"" + datumName + "\","
                + "SPHEROID[\"" + spheroid + "\"," + a + "," + rf + "]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
    }

    private static String refusalFor(String wkt) {
        try {
            String got = proj(wkt);
            fail("expected a refusal, got \"" + got + "\"");
            return null;
        } catch (WktParseException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ the refusal

    /**
     * The motivating case, and the one 2.1.0 could not see: a bare {@code GEOGCS} carries no
     * conversion, so the ESRI confirmation had nowhere to live and {@code appendDatum} never
     * learned of it.
     */
    @Test
    public void bareGeogcsWithUnplaceableEsriFrameIsRefused() {
        String m = refusalFor(ED50_GEOGCS);
        assertTrue(m, m.contains("\"D_European_1950\""));
        assertTrue(m, m.contains("ESRI datum name proj4j cannot place"));
        // The refusal states the answer it is withholding, so the message is actionable and so
        // that a reader can see it is not a parse failure.
        assertTrue(m, m.contains("+ellps=intl"));
        assertTrue(m, m.contains("TOWGS84[]"));
        assertTrue(m, m.contains("EsriDatumPolicy.ALLOW"));
    }

    /**
     * {@link EsriDatumPolicy#ALLOW} restores PROJ 9.8.1's exact answer, byte for byte. Measured:
     * {@code projinfo -o PROJ} on this document prints {@code +proj=longlat +ellps=intl +no_defs}.
     */
    @Test
    public void allowGivesProj981sAnswerExactly() {
        assertEquals("+proj=longlat +ellps=intl +no_defs", projAllowing(ED50_GEOGCS));
    }

    /**
     * The frame PROJ's own table does not contain either. PROJ answers, moving the point 0.000 m
     * to WGS 84; proj4j refuses. This is the deliberate divergence.
     */
    @Test
    public void nonsenseEsriFrameIsRefusedWherePro981Answers() {
        String m = refusalFor(NONSENSE_GEOGCS);
        assertTrue(m, m.contains("\"D_Nonsense_Datum\""));
        // What PROJ 9.8.1 produces for the same document, and what ALLOW reproduces.
        assertEquals("+proj=longlat +ellps=GRS80 +no_defs", projAllowing(NONSENSE_GEOGCS));
    }

    /** A projected CRS over an unplaceable ESRI frame is refused too, not just a bare GEOGCS. */
    @Test
    public void projectedCrsOverUnplaceableEsriFrameIsRefused() {
        String wkt = "PROJCS[\"ED_1950_UTM_Zone_31N\",GEOGCS[\"GCS_European_1950\","
                + "DATUM[\"D_European_1950\",SPHEROID[\"International_1924\",6378388.0,297.0]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],"
                + "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",3.0],"
                + "PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],"
                + "UNIT[\"Meter\",1.0]]";
        assertTrue(refusalFor(wkt).contains("\"D_European_1950\""));
        assertTrue(projAllowing(wkt).contains("+ellps=intl"));
    }

    /**
     * The {@code GCS_} confirmation is enough on its own: PROJ treats either prefix as proof the
     * document is speaking ESRI, and a frame it cannot place in such a document is in the same
     * position whatever the frame happens to be called.
     */
    @Test
    public void gcsPrefixAloneConfirmsEsri() {
        String wkt = "GEOGCS[\"GCS_Something_Local\",DATUM[\"Some_Local_Frame\","
                + "SPHEROID[\"International_1924\",6378388.0,297.0]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertTrue(refusalFor(wkt).contains("\"Some_Local_Frame\""));
    }

    // ------------------------------------------------- what the refusal must NOT reach

    /**
     * The negative control that makes every assertion above meaningful: a document with no ESRI
     * confirmation and an equally unplaceable frame is read exactly as before.
     * <p>
     * Note {@link WktDialect#guess} calls this one ESRI as well — it has neither {@code AXIS[} nor
     * {@code AUTHORITY[} — so this also pins that the <em>guess</em> does not drive the refusal.
     * Only the confirmation does, which is PROJ's {@code esriStyle_} rather than its
     * {@code maybeEsriStyle_}.
     */
    @Test
    public void dialectGuessAloneDoesNotTriggerTheRefusal() {
        String wkt = "GEOGCS[\"Something_Local\",DATUM[\"Some_Local_Frame\","
                + "SPHEROID[\"International_1924\",6378388.0,297.0]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertEquals(WktDialect.WKT1_ESRI, WktDialect.guess(wkt));
        assertEquals("+proj=longlat +ellps=intl +no_defs", proj(wkt));
    }

    /**
     * PROJ's prefix tests are {@code memcmp}, not case-insensitive comparisons
     * ({@code internal.hpp:107-121}), so a lower-case {@code d_} is not an ESRI confirmation. The
     * refusal must inherit that exactly, or it fires on documents PROJ reads differently.
     */
    @Test
    public void lowerCasePrefixesAreNotEsriConfirmations() {
        String wkt = "GEOGCS[\"gcs_european_1950\",DATUM[\"d_european_1950\","
                + "SPHEROID[\"International_1924\",6378388.0,297.0]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertEquals("+proj=longlat +ellps=intl +no_defs", proj(wkt));
    }

    /**
     * A {@code TOWGS84[]} answers the question the ESRI name was asked to answer, so there is
     * nothing left to refuse.
     */
    @Test
    public void aDeclaredToWgs84SatisfiesThePolicy() {
        String wkt = "GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\","
                + "SPHEROID[\"International_1924\",6378388.0,297.0],"
                + "TOWGS84[-87,-98,-121,0,0,0,0]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertEquals("+proj=longlat +ellps=intl +towgs84=-87,-98,-121,0,0,0,0 +no_defs", proj(wkt));
    }

    /**
     * Naming the frame by authority code does <em>not</em>, and this is the one design decision in
     * this file worth arguing with.
     *
     * <p>{@code AUTHORITY["EPSG","6230"]} says which published frame this is. It does not say where
     * that frame sits relative to WGS 84, and proj4j has nowhere to carry a frame's identity
     * onwards: the output of this path is a PROJ.4 parameter list, which has no syntax for one, and
     * {@code OperationSelector.referenceFor} reads a CRS's identifiers rather than its datum's. So
     * accepting the document here would emit {@code +ellps=intl} and drop the 6230 on the floor,
     * producing the same unplaced coordinate as the unidentified case with a more convincing
     * document behind it. An identity is not a position.
     *
     * <p>The refusal message is better for it, though, which is what {@link EsriDatumTable} is for
     * — it names EPSG:6230 whether or not the document did.
     */
    @Test
    public void anAuthorityIdOnTheFrameDoesNotPlaceIt() {
        String wkt = "GEOGCS[\"GCS_European_1950\",DATUM[\"D_European_1950\","
                + "SPHEROID[\"International_1924\",6378388.0,297.0],"
                + "AUTHORITY[\"EPSG\",\"6230\"]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        String message = refusalFor(wkt);
        assertTrue(message, message.contains("\"D_European_1950\""));
        assertTrue(message, message.contains("ESRI datum name proj4j cannot place"));
        assertEquals("+proj=longlat +ellps=intl +no_defs", projAllowing(wkt));
    }

    /**
     * The refusal quotes {@link EsriDatumTable}, so the two are pinned together: a name the table
     * resolves is named in the message, and a name it has never heard of adds nothing rather than
     * adding an empty parenthesis.
     */
    @Test
    public void theRefusalNamesTheFrameWhenProjsTableKnowsIt() {
        assertTrue(refusalFor(gcs("GCS_European_1950", "D_European_1950",
                "International_1924", 6378388.0, 297.0))
                .contains("(PROJ's ESRI table calls this EPSG:6230)"));

        // One of the five names with two alias rows: the message must carry PROJ's own pick, the
        // frame EPSG has not superseded, not the deprecated EPSG:6234 sitting beside it.
        assertTrue(refusalFor(gcs("GCS_Garoua", "D_Garoua",
                "Clarke_1880_RGS", 6378249.145, 293.465))
                .contains("(PROJ's ESRI table calls this EPSG:6197)"));

        String unknown = refusalFor(gcs("GCS_Nonsense", "D_Nonsense_Datum",
                "GRS_1980", 6378137.0, 298.257222101));
        assertFalse(unknown, unknown.contains("PROJ's ESRI table"));
    }

    /**
     * A {@code D_} name proj4j <em>can</em> place is unaffected: it resolves to a built-in
     * {@code +datum=} exactly as before, and never reaches the refusal.
     */
    @Test
    public void placeableEsriFramesAreUntouched() {
        String wkt = "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\","
                + "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertEquals("+proj=longlat +datum=NAD83 +no_defs", proj(wkt));
    }

    /**
     * The same carve-out on a frame whose shift is <em>large</em>. {@code placeableEsriFramesAreUntouched}
     * uses NAD83, which sits within a metre of WGS 84, so it would still pass if the built-in
     * {@code +datum=} lookup were lost and the frame fell through to its ellipsoid. NAD27 would
     * not: it owes <b>34.589 m</b> at 40&deg;N 100&deg;W, measured as {@code EPSG:4267 -> EPSG:4326}
     * under PROJ 9.8.1.
     *
     * <p>This is also the one ESRI name in the measured set where PROJ 9.8.1's own PROJ.4 export
     * keeps the shift &mdash; {@code projinfo <document> -o PROJ} gives
     * {@code +proj=longlat +datum=NAD27 +no_defs +type=crs}, asserted verbatim below. So it is a
     * parity case, not a divergence, and the refusal must stay out of its way.
     */
    @Test
    public void aPlaceableEsriFrameWithALargeShiftKeepsIt() {
        String wkt = "GEOGCS[\"GCS_North_American_1927\",DATUM[\"D_North_American_1927\","
                + "SPHEROID[\"Clarke_1866\",6378206.4,294.9786982]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]";
        assertEquals("+proj=longlat +datum=NAD27 +no_defs", proj(wkt));
        // And the built-in lookup, not the policy, is what makes that so: ALLOW reads the same.
        assertEquals("+proj=longlat +datum=NAD27 +no_defs", projAllowing(wkt));
    }

    /**
     * A vertical frame in an ESRI document is not a geodetic frame and carries no shift to WGS 84,
     * so the policy has nothing to say about it.
     *
     * <h4>Why this expectation carries {@code +vunits=m}</h4>
     *
     * <p>Written against a base without the vertical-coordinate work, this test asserted
     * {@code +proj=longlat +datum=NAD83 +no_defs} &mdash; the whole document minus its vertical
     * half, because the reader took only {@code horizontalComponent()} and dropped the
     * {@code VERT_CS} in silence. That is the defect the vertical stream fixes, so pinning it here
     * would have held the fix down from a test whose subject is something else entirely.
     *
     * <p>The expectation is now PROJ 9.8.1's own answer for this exact document, measured rather
     * than adopted from our output: {@code projinfo <document> -o PROJ} gives
     * {@code +proj=longlat +datum=NAD83 +vunits=m +no_defs +type=crs}, which agrees with us token
     * for token and in the same order. So the two changes do not disagree; one of them had simply
     * recorded the other's starting point.
     *
     * <p>The subject of the test is unchanged, and it is now the stronger claim: the geodetic frame
     * stays placed by the built-in {@code +datum=} lookup <em>and</em> the vertical half survives,
     * with the ESRI policy declining to fire on either.
     */
    @Test
    public void verticalFramesAreOutOfScope() {
        String wkt = "COMPD_CS[\"NAD83 + NAVD88\","
                + "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\","
                + "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],"
                + "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],"
                + "VERT_CS[\"NAVD_1988\",VERT_DATUM[\"North_American_Vertical_Datum_1988\",2005],"
                + "UNIT[\"Meter\",1.0]]]";
        assertEquals("+proj=longlat +datum=NAD83 +vunits=m +no_defs", proj(wkt));
    }

    // ------------------------------------------------------------------- the plumbing

    /** The default is the refusal, on the built-in context and on a freshly built one. */
    @Test
    public void rejectIsTheDefault() {
        assertEquals(EsriDatumPolicy.REJECT, ProjContext.DEFAULT.esriDatumPolicy());
        assertEquals(EsriDatumPolicy.REJECT, ProjContext.builder().build().esriDatumPolicy());
        assertEquals(EsriDatumPolicy.REJECT,
                ProjContext.builder().esriDatumPolicy(null).build().esriDatumPolicy());
    }

    /** {@code withEsriDatumPolicy} follows the same contract as every other {@code withXxx}. */
    @Test
    public void contextRoundTripsThePolicy() {
        ProjContext allow = ProjContext.DEFAULT.withEsriDatumPolicy(EsriDatumPolicy.ALLOW);
        assertEquals(EsriDatumPolicy.ALLOW, allow.esriDatumPolicy());
        assertNotEquals(ProjContext.DEFAULT, allow);
        assertNotEquals(ProjContext.DEFAULT.hashCode(), allow.hashCode());
        // No-op changes return the same instance, and a round trip lands back on the singleton.
        assertTrue(ProjContext.DEFAULT
                == ProjContext.DEFAULT.withEsriDatumPolicy(EsriDatumPolicy.REJECT));
        assertTrue(ProjContext.DEFAULT == allow.withEsriDatumPolicy(EsriDatumPolicy.REJECT));
        assertTrue(allow.toString().contains("esriDatum=ALLOW"));
        assertTrue(allow.describe().contains("esriDatumPolicy"));
        assertTrue(ProjContext.DEFAULT.describe().contains("refused at parse time"));
    }

    /**
     * The policy reaches {@link Proj#createCrs} through the context, both ways.
     * <p>
     * The refusal surfaces as a {@link WktParseException} rather than a
     * {@link CrsCreationException}: {@code Proj.fromWkt} wraps only the parse, not the subsequent
     * {@code CrsDefinitions.toCrs}, so every refusal raised while building the CRS — this one, and
     * the pre-existing vertical-only and no-horizontal-component ones — propagates unwrapped.
     * Pinned as observed rather than corrected here, because changing it would change the
     * exception type of refusals this stream did not introduce.
     */
    @Test
    public void proj4jRefusesThroughTheContextAndOptsOutThroughIt() {
        try {
            Proj.createCrs(ED50_GEOGCS, ProjContext.DEFAULT);
            fail("expected a refusal");
        } catch (WktParseException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("D_European_1950"));
        }

        Crs crs = Proj.createCrs(ED50_GEOGCS,
                ProjContext.DEFAULT.withEsriDatumPolicy(EsriDatumPolicy.ALLOW));
        assertFalse(crs.toProjString().contains("+towgs84"));
        assertTrue(crs.toProjString(), crs.toProjString().contains("+ellps=intl"));
    }

    /**
     * The bound this whole change has to respect: a PROJ.4 string with a bare {@code +ellps=} is a
     * far larger population than ESRI WKT, and it is {@code BallparkPolicy}'s business, not this
     * policy's. Nothing here may touch it.
     */
    @Test
    public void bareEllipsoidProjStringsAreNotInScope() {
        Crs crs = Proj.createCrs("+proj=longlat +ellps=intl +no_defs", ProjContext.DEFAULT);
        assertTrue(crs.toProjString(), crs.toProjString().contains("+ellps=intl"));
    }
}
