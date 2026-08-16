#!/bin/sh
# Generates EsriDatumTable.java from PROJ's own ESRI alias table.
#
# Build-time only, and opt-in: run by the `regen-esri-datums` Maven profile, never by a default
# build. A default `mvn install` compiles the checked-in EsriDatumTable.java, so a machine with no
# PROJ checkout builds successfully -- and cannot silently fall back to a stale or hand-edited one,
# because EsriDatumTableTest re-derives the whole table from the same source whenever that source
# is reachable, and says so loudly when it is not.
#
# Usage: esri-datum-table.sh <proj-checkout> <output.java>
#
# WHAT IS READ, AND WHY EXACTLY THIS
# ----------------------------------
# `git show 9.8.1:data/sql/esri.sql`, and nothing else. Not the checkout's working tree: PROJ's
# master is hundreds of commits past 9.8.1 and reading it would port post-9.8.1 behaviour into a
# library that claims 9.8.1 parity. The tag is resolved to its commit and both are recorded in the
# generated file, so the artifact says what it came from.
#
# Of that file this takes exactly the rows PROJ itself consults when it reads an ESRI datum name
# out of a WKT document: `alias_name` rows whose table_name is 'geodetic_datum' and whose alt_name
# begins `D_`. That is the table behind AuthorityFactory::getOfficialNameFromAlias, which
# io.cpp's buildGeodeticReferenceFrame calls with table 'geodetic_datum' and source 'ESRI'.
#
# The `source` column is NOT filtered to 'ESRI'. PROJ's own query widens it to
# IN ('ESRI','ESRI_OLD') -- 13 of the D_ rows here are ESRI_OLD, and dropping them would silently
# refuse names PROJ resolves.
#
# The 'geodetic_datum' definition rows in the same file -- ESRI's own frames, authority ESRI --
# are deliberately NOT read. They are reached in PROJ through an ESRI *CRS* code, not through
# datum-name matching, and inventing a name-matching path for them would be a mechanism proj4j
# made up. A frame only they cover stays refused, with a message, which is the safe direction.
#
# NAMES WITH MORE THAN ONE ROW
# ----------------------------
# Five D_ names carry two authority codes each. PROJ does not pick between them arbitrarily, and an
# earlier version of this script said it did. AuthorityFactory::getOfficialNameFromAlias, at
# src/iso19111/factory.cpp:9054-9141 in 9.8.1, widens source="ESRI" to IN ('ESRI', 'ESRI_OLD') at
# :9110, builds a UNION ALL over the candidate rows, and closes it at :9140 with
#
#     SELECT name, table_name, auth_name, code FROM (...) x ORDER BY deprecated LIMIT 1
#
# so a non-deprecated frame beats a deprecated one. In all five cases exactly one candidate is
# non-deprecated, which makes PROJ's answer both deterministic and right, and this script copies
# the rule rather than the answers. Verified against the 9.8.1 binaries, all five predicted before
# they were measured: D_Garoua 6197, D_Hughes_1980 1359, D_Kerguelen_Island_1949 6698,
# D_SIRGAS-Chile 1064, D_South_American_1969 6618.
#
# What ORDER BY does not settle -- two candidates at the same deprecation level -- SQLite decides
# by whatever order the UNION ALL happens to produce, which is not a rule anyone should copy. No
# name is in that position at 9.8.1, so rather than carry an untested branch for it, this script
# HARD FAILS if one ever appears. That is the loud moment a maintainer needs.
#
# The deprecated flag is the last column of the geodetic_datum rows, which live in
# geodetic_datum.sql (the EPSG frames) and esri.sql (ESRI's own 375). Both are read.

set -eu

PROJ="${1:?usage: esri-datum-table.sh <proj-checkout> <output.java>}"
OUT="${2:?usage: esri-datum-table.sh <proj-checkout> <output.java>}"
REV=9.8.1

if [ ! -d "$PROJ/.git" ]; then
    echo "esri-datum-table.sh: $PROJ is not a git checkout of PROJ." >&2
    echo "  Point -Dproj.checkout at one that has the $REV tag." >&2
    exit 1
fi

SHA=$(git -C "$PROJ" rev-parse "$REV^{commit}")
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
SQ=$(printf '\47')   # a single quote, which cannot appear literally in the awk
                       # programs below because they are themselves single-quoted.
git -C "$PROJ" show "$REV:data/sql/esri.sql" > "$TMP/esri.sql"
git -C "$PROJ" show "$REV:data/sql/geodetic_datum.sql" > "$TMP/geodetic_datum.sql"

# auth:code<TAB>deprecated, for every geodetic_datum row in either file. The deprecated flag is the
# last comma-separated column and is unquoted, so it survives the commas inside quoted names.
awk '/^INSERT INTO "geodetic_datum" VALUES\(/ {
        line = $0
        sub(/\);[[:space:]]*$/, "", line)
        n = split(line, col, ",")
        dep = col[n]
        split($0, q, SQ)
        print q[2] ":" q[4] "\t" dep
    }' SQ="$SQ" "$TMP/geodetic_datum.sql" "$TMP/esri.sql" | LC_ALL=C sort -u > "$TMP/dep"

# name<TAB>auth:code, for every D_ geodetic_datum alias. Field 2 of a single-quote split is
# table_name, 4 is auth_name, 6 is code, 8 is alt_name, 10 is source.
awk -F"$SQ" '
    /^INSERT INTO alias_name VALUES\(/ && $2 == "geodetic_datum" && $8 ~ /^D_/ {
        print $8 "\t" $4 ":" $6
    }' "$TMP/esri.sql" | LC_ALL=C sort -u > "$TMP/rows"

# PROJ's ORDER BY deprecated LIMIT 1, applied per name. A name whose best two candidates share a
# deprecation level is one SQLite would settle by accident; the script stops rather than guess.
awk -F'\t' '
    NR == FNR { dep[$1] = $2; next }
    {
        if (!($2 in dep)) {
            print "esri-datum-table.sh: alias " $1 " points at " $2 ", which has no" \
                  " geodetic_datum row." > "/dev/stderr"
            bad = 1
            next
        }
        d = dep[$2] + 0
        if (!($1 in seen)) {
            order[++n] = $1
        }
        if (!($1 in best) || d < bestdep[$1]) {
            best[$1] = $2; bestdep[$1] = d; tied[$1] = 0
        } else if (d == bestdep[$1]) {
            tied[$1] = 1
        }
        seen[$1]++
    }
    END {
        if (bad) { exit 1 }
        for (i = 1; i <= n; i++) {
            k = order[i]
            if (tied[k]) {
                print "esri-datum-table.sh: " k " has two candidates at deprecated=" bestdep[k] \
                      ". PROJ would take whichever SQLite returned first, which is not a rule to" \
                      " copy. Decide this one by hand and teach the script the decision." \
                      > "/dev/stderr"
                bad = 1
            }
        }
        if (bad) { exit 1 }
        for (i = 1; i <= n; i++) {
            print order[i] "\t" best[order[i]] "\t" (seen[order[i]] > 1 ? "multi" : "single")
        }
    }' "$TMP/dep" "$TMP/rows" | LC_ALL=C sort > "$TMP/pick"

ROWS=$(awk -F"$SQ" '/^INSERT INTO alias_name VALUES\(/ && $2 == "geodetic_datum" {n++}
    END {print n+0}' "$TMP/esri.sql")
DROWS=$(LC_ALL=C wc -l < "$TMP/rows" | tr -d ' ')
NAMES=$(LC_ALL=C wc -l < "$TMP/pick" | tr -d ' ')
NMULTI=$(LC_ALL=C awk -F'\t' '$3 == "multi"' "$TMP/pick" | wc -l | tr -d ' ')

mkdir -p "$(dirname "$OUT")"
{
cat <<HEADER
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

/**
 * ESRI {@code D_} reference-frame names, and the authority frames they are names for.
 *
 * <p><b>Generated by {@code core/src/gen/esri-datum-table.sh}. Do not edit.</b> Regenerate with
 * {@code mvn -Pregen-esri-datums -Dproj.checkout=/path/to/PROJ generate-sources -pl core}.
 *
 * <h2>Provenance</h2>
 *
 * <ul>
 * <li>source: {@code data/sql/esri.sql} at PROJ {@code $REV} ({@code $SHA})</li>
 * <li>rows read: {@code alias_name} where {@code table_name='geodetic_datum'} -- $ROWS in the
 *     file, of which $DROWS distinct (name, code) pairs have a {@code D_} alt_name</li>
 * <li>distinct {@code D_} names, all resolved here: $NAMES</li>
 * <li>of those, names with more than one candidate row, settled the way PROJ settles them:
 *     $NMULTI</li>
 * </ul>
 *
 * <p>The {@code source} column is not filtered: PROJ's own query widens {@code 'ESRI'} to
 * {@code IN ('ESRI','ESRI_OLD')}, and some of these rows are the latter.
 *
 * <h2>What this table is for, and what it is not for</h2>
 *
 * <p>It says <em>which published frame an ESRI name names</em>. It does not say where that frame
 * is relative to WGS 84, and proj4j has nowhere to carry a reference frame's identity into
 * operation selection -- {@code OperationSelector.referenceFor} reads a CRS's identifiers, and
 * {@code ProjDatabase.crsUsingDatum}, the pivot that would turn a frame identity into candidate
 * operations, has no caller. So resolving a name here makes a refusal <em>specific</em>; it does
 * not turn one into an answer. Wiring it through to selection is the follow-on work, and until it
 * is done a name resolved here is still a name proj4j cannot place.
 *
 * <h2>Names with more than one candidate</h2>
 *
 * <p>$NMULTI of these names have two rows in PROJ's alias table. PROJ does not choose between them
 * arbitrarily: {@code factory.cpp}'s {@code getOfficialNameFromAlias} closes its UNION over the
 * candidates with {@code ORDER BY deprecated LIMIT 1}, so a live frame beats a superseded one, and
 * at 9.8.1 exactly one candidate per name is live. This table copies that rule, and the generator
 * refuses to emit a table at all if a future release ever produces a name the rule cannot settle.
 *
 * @since 2.2.0
 */
final class EsriDatumTable {

    private EsriDatumTable() {
    }

    /**
     * {@code D_} names with exactly one authority frame, sorted by name so that
     * {@link java.util.Arrays#binarySearch} applies. {@link #CODES} is parallel to it and holds
     * {@code authority:code}.
     */
    static final String[] NAMES = {
HEADER

LC_ALL=C cut -f1 "$TMP/pick" | sed 's/.*/        "&",/'

cat <<'MID'
    };

    /** Parallel to {@link #NAMES}: the frame each name is a name for, as {@code authority:code}. */
    static final String[] CODES = {
MID

LC_ALL=C cut -f2 "$TMP/pick" | sed 's/.*/        "&",/'

cat <<'TAIL'
    };

    /**
     * The authority frame an ESRI {@code D_} name is a name for, as {@code authority:code}, or
     * {@code null} when PROJ 9.8.1's ESRI alias table has no row for the name.
     *
     * <p>Matching is case-sensitive and exact, like PROJ's, whose lookup is a SQL equality on a
     * BINARY-collated column reached only after a {@code memcmp} for the {@code D_} prefix.
     */
    static String code(String esriName) {
        if (esriName == null) {
            return null;
        }
        int i = java.util.Arrays.binarySearch(NAMES, esriName);
        return i < 0 ? null : CODES[i];
    }

    /**
     * A clause naming what PROJ's alias table knows about this name, for a refusal message, or the
     * empty string when it knows nothing. Always safe to concatenate.
     *
     * <p>Knowing the frame is not the same as being able to place it, which is why this produces
     * prose for a refusal rather than an escape from one: proj4j has nowhere to carry a reference
     * frame's identity into operation selection yet, so an identified-but-unplaced frame would
     * still export as a bare ellipsoid. See {@link EsriDatumPolicy}.
     */
    static String describe(String esriName) {
        String one = code(esriName);
        return one == null ? "" : " (PROJ's ESRI table calls this " + one + ")";
    }
}
TAIL
} > "$OUT"

echo "esri-datum-table.sh: wrote $OUT"
echo "  PROJ $REV ($SHA)"
echo "  $DROWS D_ (name, code) pairs, $NAMES names, $NMULTI settled by ORDER BY deprecated"
