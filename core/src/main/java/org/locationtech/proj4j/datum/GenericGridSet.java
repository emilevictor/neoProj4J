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
package org.locationtech.proj4j.datum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.proj4j.datum.tiff.GeoTiffDataset;
import org.locationtech.proj4j.datum.tiff.GeoTiffImage;
import org.locationtech.proj4j.resource.ChainedResourceResolver;
import org.locationtech.proj4j.resource.ResourceHandle;
import org.locationtech.proj4j.resource.ResourceNames;
import org.locationtech.proj4j.resource.ResourceResolver;
import org.locationtech.proj4j.resource.ResourceResolvers;
import org.locationtech.proj4j.resource.Resources;

/**
 * One file's worth of {@link GenericGrid}s — PROJ's {@code GenericShiftGridSet}
 * ({@code 9.8.1:src/grids.hpp:270-296}, {@code src/grids.cpp:3095-3201}).
 *
 * <p>A set is what {@code +grids=} names: one file, one or more top-level grids, each carrying its
 * own subgrid tree. {@link #fromGridsSpec} turns a whole comma-separated {@code +grids=} value into
 * a list of sets, and {@link #find} is the per-coordinate lookup across that list.
 *
 * <h2>{@code TYPE} is consulted twice, and they are different questions</h2>
 *
 * <p>When the tree is <em>built</em>, {@code TYPE} buckets: a grid is only ever nested under a
 * candidate parent of the same {@code TYPE}, which is what stops a vertical-offset image from
 * becoming a child of a horizontal-offset one merely because its extent fits inside.
 * {@code GeoTiffGrid.insertIntoHierarchy} already does that for the horizontal and vertical
 * hierarchies, and {@link #insert} does it here.
 *
 * <p>When a coordinate is <em>looked up</em>, {@code TYPE} <b>selects</b>:
 * {@link #gridAt(String, double, double)} skips every root whose {@link GenericGrid#type()} differs
 * from the one asked for, so a single file carrying both a {@code HORIZONTAL_OFFSET} and a
 * {@code VERTICAL_OFFSET} image over the same area can answer both questions without ambiguity.
 * proj4j had only the first half of this. {@code xyzgridshift} uses the untyped
 * {@link #gridAt(double, double)}; {@code gridshift} and {@code defmodel} will need the typed one.
 *
 * <h2>Everything is decoded up front</h2>
 *
 * <p>PROJ reads nodes from the open TIFF on demand, block by block, and keeps a one-block cache.
 * proj4j parses the whole file once into {@code float} planes and drops the bytes, exactly as
 * {@link Grid} and {@link VerticalGrid} do, because a cached grid must be immutable and safe to
 * share across threads and a lazily-populated block cache is neither. The cost is that every band
 * is materialised even when an operator reads three of them; the whole allocation is bounded
 * against {@code GridExtents.maxDecodedBytes()} before anything is read.
 *
 * <p>Immutable after construction and safe to share; {@link #gridAt} and {@link #find} keep all
 * state in locals.
 *
 * @since 2.2.0
 */
public final class GenericGridSet implements GridCache.Sized {

    /** {@code FILETYPE_PAGE}: the only non-zero {@code SubfileType} upstream tolerates. */
    private static final long FILETYPE_PAGE = 2L;

    private final String name;
    private final String origin;
    private final String resolverName;
    private final String format;
    private final List<GenericGrid> grids;

    private GenericGridSet(String name, String origin, String resolverName, String format,
                           List<GenericGrid> grids) {
        this.name = name;
        this.origin = origin;
        this.resolverName = resolverName;
        this.format = format;
        this.grids = grids;
    }

    // ==========================================================================================
    // Loading
    // ==========================================================================================

    /**
     * Locates {@code name} through the resolver chain and returns the parsed set, loading it at
     * most once per (resolver, name) pair for the life of the JVM.
     *
     * <p>Same shape, same refusals and same cache discipline as {@code Grid.resolveAndLoad} and
     * {@link VerticalGrid#fromName}: the {@code "null"} special case first, then the
     * {@link ResourceNames} refusal <em>before</em> the chain is consulted, then the chain — which
     * never includes the process working directory.
     */
    public static GenericGridSet open(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Empty generic grid name");
        }
        // GenericShiftGridSet::open (grids.cpp:3095-3103): "null" is a set of one grid that covers
        // the world and shifts nothing. It never reaches the resolver chain.
        if ("null".equals(name)) {
            return new GenericGridSet("null", "built-in", "built-in", "null",
                    Collections.singletonList(GenericGrid.nullGrid()));
        }
        ResourceNames.Rule violation = ResourceNames.violation(name);
        if (violation != null) {
            throw new IOException("Refusing generic grid name \"" + name + "\": "
                    + violation.description() + " (" + violation + ")");
        }
        ChainedResourceResolver chain = ResourceResolvers.resolver();
        final ResourceHandle handle = chain.resolve(name);
        if (handle == null) {
            throw new IOException("Unknown generic grid: " + name + ". Resolution chain was "
                    + chain.name() + "; the working directory is deliberately not searched.");
        }
        ResourceResolver owner = chain.resolverOf(name);
        final String resolverName = owner == null ? "unknown" : owner.name();
        final String requested = name;
        final String origin = handle.origin();
        return GridCache.generic().get(resolverName, name, new GridCache.Loader<GenericGridSet>() {
            @Override
            public GenericGridSet load() throws IOException {
                byte[] bytes = Resources.readAll(handle, GridExtents.maxFileBytes());
                return parse(requested, origin, resolverName, bytes);
            }
        });
    }

    /**
     * A whole {@code +grids=} value — {@code pj_generic_grid_init} ({@code grids.cpp:3213-3247}).
     *
     * <p>Comma-separated, and a leading {@code @} marks a grid as optional: upstream clears the
     * error and carries on, so a missing optional grid is a silently shorter list rather than a
     * failure. A missing <em>required</em> grid is
     * {@code PROJ_ERR_INVALID_OP_FILE_NOT_FOUND_OR_INVALID}, which the caller raises.
     *
     * @param spec the raw parameter value
     * @return the sets that loaded, in the order they were named; unmodifiable
     */
    public static List<GenericGridSet> fromGridsSpec(String spec) throws IOException {
        List<GenericGridSet> out = new ArrayList<GenericGridSet>();
        if (spec == null || spec.isEmpty()) {
            return Collections.unmodifiableList(out);
        }
        for (String token : Grid.splitTokens("grids", spec)) {
            boolean optional = token.startsWith("@");
            String gridName = optional ? token.substring(1) : token;
            try {
                out.add(open(gridName));
            } catch (IOException e) {
                if (!optional) {
                    throw e;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Sniffs the format, in {@code GenericShiftGridSet::open}'s order: the four-byte TIFF
     * signature, then an error. Unlike the vertical path there is no filename-dispatched format —
     * every generic grid PROJ 9.8.1 reads is a Geodetic TIFF Grid.
     */
    static GenericGridSet parse(String gridName, String origin, String resolverName, byte[] bytes)
            throws IOException {
        byte[] header = new byte[4];
        System.arraycopy(bytes, 0, header, 0, Math.min(4, bytes.length));
        if (!GeoTiffDataset.isTiff(header, Math.min(4, bytes.length))) {
            throw new IOException("Unrecognized generic grid format for filename '" + gridName
                    + "' at " + origin + ". proj4j and PROJ 9.8.1 read only GeoTIFF here.");
        }
        return new GenericGridSet(gridName, origin, resolverName, "gtiff",
                readGeoTiff(gridName, bytes));
    }

    /**
     * {@code GTiffGenericGridShiftSet::open} ({@code grids.cpp:3006-3053}).
     *
     * <p>The one behaviour worth reading twice is the cross-IFD metadata inheritance. The
     * <em>trigger</em> is narrow — this is not the first root, this IFD declares no {@code TYPE},
     * and the first root does — but once it fires the fallback covers every metadata key and every
     * band, not just {@code TYPE}. See {@link GenericGrid#metadataItem(String, int)}.
     */
    private static List<GenericGrid> readGeoTiff(String gridName, byte[] bytes) throws IOException {
        GeoTiffDataset dataset = GeoTiffDataset.open(bytes, gridName);
        List<GeoTiffImage> images = dataset.images();

        List<Node> roots = new ArrayList<Node>();
        Map<String, Node> byName = new HashMap<String, Node>();

        for (int ifd = 0; ifd < images.size(); ifd++) {
            GeoTiffImage image = images.get(ifd);

            long subfileType = image.subfileType();
            if (subfileType != 0 && subfileType != FILETYPE_PAGE) {
                if (ifd == 0) {
                    throw new IOException("GeoTIFF generic grid " + gridName
                            + ": IFD 0 has SubfileType " + subfileType
                            + ", which marks it as a reduced-resolution overview or mask");
                }
                continue;
            }

            String subName = image.metadataItem("grid_name");
            String parentName = image.metadataItem("parent_grid_name");

            Node node = new Node(subName.isEmpty()
                    ? gridName + " (index " + (ifd + 1) + ")" : gridName + "#" + subName, image);

            // grids.cpp:3033-3038, verbatim in its three conjuncts. `roots` is upstream's
            // `set->m_grids`, so roots.get(0) is its m_grids[0]: the first top-level grid, which
            // for every real file is IFD 0. Note that the donor is the first ROOT, not this
            // grid's parent — a subgrid of the second root inherits from the first.
            if (!roots.isEmpty() && node.ownType.isEmpty() && !roots.get(0).type().isEmpty()) {
                node.inherited = roots.get(0).metadata;
            }

            insert(node, subName, parentName, roots, byName);
        }

        if (roots.isEmpty()) {
            throw new IOException("GeoTIFF generic grid " + gridName
                    + " contains no usable image");
        }
        List<GenericGrid> built = new ArrayList<GenericGrid>(roots.size());
        for (int i = 0; i < roots.size(); i++) {
            built.add(roots.get(i).build());
        }
        return Collections.unmodifiableList(built);
    }

    /**
     * {@code insertIntoHierarchy} ({@code grids.cpp:1380-1440}), the generic-grid instance of the
     * same routine {@code GeoTiffGrid} runs for the horizontal and vertical hierarchies.
     *
     * <p>The parent is resolved <b>before</b> this node registers under its own name, for the
     * reason {@code GeoTiffGrid.insertIntoHierarchy} sets out at length: an IFD whose
     * {@code parent_grid_name} equals its own {@code grid_name} would otherwise become its own
     * parent, and {@code contains} is inclusive on all four sides so it would pass. Here the
     * consequence would be unbounded recursion in {@link GenericGrid#gridAt} and in
     * {@link Node#build()}.
     */
    private static void insert(Node node, String gridName, String parentName, List<Node> roots,
                               Map<String, Node> byName) {
        Node parent = parentName.isEmpty() ? null : byName.get(parentName);
        if (!gridName.isEmpty()) {
            byName.put(gridName, node);
        }
        if (!parentName.isEmpty()) {
            if (parent != null && parent.contains(node)) {
                parent.children.add(node);
                return;
            }
            // Upstream logs and falls through to the bounding-box method: a declared hierarchy is
            // advisory, the extents are authoritative.
        } else if (!gridName.isEmpty()) {
            roots.add(node);
            return;
        }
        final String type = node.type();
        for (int i = 0; i < roots.size(); i++) {
            Node candidate = roots.get(i);
            if (!type.isEmpty() && !type.equals(candidate.type())) {
                continue;
            }
            if (candidate.contains(node)) {
                candidate.insertDeep(node);
                return;
            }
        }
        roots.add(node);
    }

    // ==========================================================================================
    // Lookup
    // ==========================================================================================

    /**
     * {@code GenericShiftGridSet::gridAt(x, y)} ({@code grids.cpp:3169-3183}).
     *
     * <p>Note the null grid's short circuit: it is returned for <em>any</em> coordinate without an
     * extent test at all, which is what makes {@code +grids=null} a universal no-op rather than a
     * grid that happens to span the world.
     *
     * @return the grid covering the point, or {@code null} if none does
     */
    public GenericGrid gridAt(double lam, double phi) {
        for (int i = 0; i < grids.size(); i++) {
            GenericGrid grid = grids.get(i);
            if (grid.isNullGrid()) {
                return grid;
            }
            if (grid.covers(lam, phi)) {
                return grid.gridAt(lam, phi);
            }
        }
        return null;
    }

    /**
     * {@code GenericShiftGridSet::gridAt(type, x, y)} ({@code grids.cpp:3185-3201}) — the same
     * search, restricted to grids declaring a given dataset-level {@code TYPE}.
     *
     * <p>The type test comes <b>before</b> the null-grid short circuit in upstream's loop, so a
     * typed lookup does not silently pick up {@code +grids=null}. Reproduced.
     *
     * @param type the required {@link GenericGrid#type()}
     * @return the grid covering the point, or {@code null} if none of that type does
     */
    public GenericGrid gridAt(String type, double lam, double phi) {
        for (int i = 0; i < grids.size(); i++) {
            GenericGrid grid = grids.get(i);
            if (!grid.type().equals(type)) {
                continue;
            }
            if (grid.isNullGrid()) {
                return grid;
            }
            if (grid.covers(lam, phi)) {
                return grid.gridAt(lam, phi);
            }
        }
        return null;
    }

    /**
     * {@code pj_find_generic_grid} ({@code grids.cpp:3827-3838}): the first set in the list that
     * covers the point wins, and the search stops there — a later set is never consulted even if
     * the first turns out to hold nodata at that node.
     *
     * @return the grid to read, or {@code null} if no set covers the point
     */
    public static GenericGrid find(List<GenericGridSet> sets, double lam, double phi) {
        for (int i = 0; i < sets.size(); i++) {
            GenericGrid grid = sets.get(i).gridAt(lam, phi);
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    // ==========================================================================================
    // Description
    // ==========================================================================================

    /** The name this set was requested under. */
    public String getName() {
        return name;
    }

    /** Where the bytes came from. */
    public String getOrigin() {
        return origin;
    }

    /** Which resolver produced them. */
    public String getResolverName() {
        return resolverName;
    }

    /** {@code "gtiff"} or {@code "null"}, upstream's own {@code m_format} strings. */
    public String getFormat() {
        return format;
    }

    /** The top-level grids, outermost first; unmodifiable. */
    public List<GenericGrid> grids() {
        return grids;
    }

    /** Whether every grid in this set, at any depth, is referenced in a geographic CRS. */
    public boolean isGeographicThroughout() {
        for (int i = 0; i < grids.size(); i++) {
            if (!isGeographicThroughout(grids.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGeographicThroughout(GenericGrid grid) {
        if (!grid.isGeographic()) {
            return false;
        }
        for (int i = 0; i < grid.children().size(); i++) {
            if (!isGeographicThroughout(grid.children().get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long sizeBytes() {
        long total = 0L;
        for (int i = 0; i < grids.size(); i++) {
            total += grids.get(i).sizeBytes();
        }
        return total;
    }

    @Override
    public String toString() {
        return "GenericGridSet[" + name + "; " + format + "; " + grids.size() + " grid(s)]";
    }

    // ==========================================================================================
    // Assembly
    // ==========================================================================================

    /**
     * A grid under construction: one IFD plus the children that turned out to nest inside it.
     *
     * <p>Mutable, and deliberately short lived — it exists only between the IFD loop and
     * {@link #build()}, on one thread, so that {@link GenericGrid} itself can be built bottom-up
     * with every field {@code final}. Same division of labour as
     * {@code GeoTiffGrid.VerticalLayer} and {@link VerticalGrid}.
     */
    private static final class Node {
        final String name;
        final GeoTiffImage image;
        final Map<String, String> metadata;
        final String ownType;
        final double west;
        final double south;
        final double east;
        final double north;
        final List<Node> children = new ArrayList<Node>();
        Map<String, String> inherited;

        Node(String name, GeoTiffImage image) {
            this.name = name;
            this.image = image;
            this.metadata = image.metadataSnapshot();
            this.ownType = image.metadataItem("TYPE");
            this.west = image.west();
            this.south = image.south();
            this.east = image.east();
            this.north = image.north();
        }

        /**
         * The {@code TYPE} this node buckets under — its own if it declares one, otherwise the
         * inherited one.
         *
         * <p>Not the same as {@link #ownType}, and the difference is load bearing:
         * {@code insertIntoHierarchy} reads {@code grid->metadataItem("TYPE")} <em>after</em>
         * {@code setFirstGrid} has been called, so an IFD with no {@code TYPE} of its own buckets
         * under the inherited one. Reading only the declared item would leave the type empty,
         * which switches the {@code TYPE} filter off entirely and lets the grid nest under a
         * parent of any type.
         */
        String type() {
            if (!ownType.isEmpty()) {
                return ownType;
            }
            if (inherited != null) {
                String up = inherited.get(
                        GeoTiffImage.metadataKey("TYPE", GeoTiffImage.GRID_LEVEL));
                if (up != null) {
                    return up;
                }
            }
            return "";
        }

        /**
         * {@code ExtentAndRes::contains} ({@code grids.cpp:96-99}) — inclusive on all four sides,
         * plus the identity guard {@code GeoTiffGrid.contains} documents. Without the guard every
         * node contains itself, and "contains itself" plus a self-referential
         * {@code parent_grid_name} is a cycle.
         */
        boolean contains(Node other) {
            if (other == this) {
                return false;
            }
            return other.west >= west && other.east <= east
                    && other.south >= south && other.north <= north;
        }

        /** Deepest-first insertion, mirroring {@code GTiffGrid::insertGrid}. */
        void insertDeep(Node sub) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).contains(sub)) {
                    children.get(i).insertDeep(sub);
                    return;
                }
            }
            children.add(sub);
        }

        GenericGrid build() throws IOException {
            int width = image.width();
            int height = image.height();
            int samples = image.samplesPerPixel();

            // GeoTiffImage.of has already bounded one plane against maxDecodedBytes(); this bounds
            // all of them together, which is the number this reader actually allocates. Written as
            // a division rather than a product because samplesPerPixel is a declared LONG: the
            // product would overflow before the check could refuse it.
            long nodes = (long) width * height;
            long maxPlanes = GridExtents.maxDecodedBytes() / (4L * nodes);
            if (samples > maxPlanes) {
                throw new IOException("GeoTIFF generic grid " + name + " declares " + samples
                        + " samples per pixel over " + width + "x" + height
                        + " nodes, which needs more than the decoded-grid budget of "
                        + GridExtents.maxDecodedBytes() + " bytes.");
            }
            GridExtents.checkedCount("GeoTIFF generic grid " + name + " sample planes",
                    nodes * samples, 4L, 0L, GridExtents.maxDecodedBytes(),
                    "the decoded-grid budget");

            int[] all = new int[samples];
            for (int i = 0; i < samples; i++) {
                all[i] = i;
            }
            float[][] planes = image.readSamples(all);

            List<GenericGrid> kids;
            if (children.isEmpty()) {
                kids = Collections.emptyList();
            } else {
                List<GenericGrid> built = new ArrayList<GenericGrid>(children.size());
                for (int i = 0; i < children.size(); i++) {
                    built.add(children.get(i).build());
                }
                kids = Collections.unmodifiableList(built);
            }

            return new GenericGrid(name, width, height, image.isGeographic(), image.west(),
                    image.south(), image.resX(), image.resY(), samples, planes, metadata,
                    inherited, image.hasNodata(), image.noDataValue(), false, kids);
        }
    }
}
