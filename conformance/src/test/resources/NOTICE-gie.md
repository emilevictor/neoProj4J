# NOTICE — third-party material vendored into `conformance/src/test/resources`

**This file is a licence obligation, not documentation.** Three of the four bodies of vendored
material carry conditions that bind anyone who redistributes them: PROJ's MIT/X11 notice must
travel with the copies, the IOGP GIGS copyright requires both that the source be acknowledged
and that *every subsequent recipient be informed of its terms*, and the four grids in
`proj-data-cdn/` require attribution — three under CC-BY-4.0 and one under the Etalab Open Licence.
Shipping the files without this file does not satisfy any of the three. If these resources end up inside a published artifact, this notice (or its content) must
go with them.

Nothing here is authored in this repository. Everything in `gie/`, `gigs/` and `proj-data/` is a
verbatim copy of [PROJ](https://proj.org) at tag **9.8.1**
(`f08fa86c478c4bbbf003b1ec751dd84aa6eca486`, 2026-04-10), extracted by
`conformance/sync-upstream.sh`. `gie-manifest.sha256` records the SHA-256 of every file in those
three directories so that local modification is detectable; re-running the sync script regenerates
it. `proj-data-cdn/` comes from a *different* upstream — the OSGeo **PROJ-data** package — and is
covered by section 4.

| directory | upstream origin | files |
|---|---|---|
| `gie/` | `9.8.1:test/gie/` | 22 |
| `gigs/` | `9.8.1:test/gigs/` | 20 `.gie` + 10 `.gie.failing` |
| `proj-data/` | `9.8.1:data/` — the `for_tests` whitelist built by `data/CMakeLists.txt` | 96 (82 under `tests/`, plus promoted copies) |
| `proj-data-cdn/` | OSGeo [PROJ-data](https://github.com/OSGeo/PROJ-data) / `https://cdn.proj.org/` | 4 GeoTIFF grids — 3 CC-BY-4.0, 1 Etalab Open Licence |

`test/gie/tinshift_gpkg.gie` and `test/gie/tinshift_gpkg_network.gie` are **not** vendored: they do
not exist at 9.8.1, having been added to PROJ `master` afterwards.

---

## 1. PROJ — `gie/**`, and the PROJ-authored parts of `proj-data/**`

`gie/**` is derived from PROJ 9.8.1 `test/gie`. The `.gie` files are unmodified except that the
directory has been flattened from `test/gie/` to `gie/`. The `proj-data/**` tree reproduces the
`for_tests` directory that `9.8.1:data/CMakeLists.txt` assembles at configure time, including its
two renames (`tests/egm96_15_downsampled.gtx` → `egm96_15.gtx`, `tests/ntv2_0_downsampled.gsb` →
`ntv2_0.gsb`) and the deliberately awkward `dir with space/myconus` fixture.

PROJ is MIT/X11 licensed, which is compatible with proj4j's Apache-2.0. The following is
`9.8.1:COPYING` reproduced in full. Note its first sentence: it covers **data files**, not only
source.

> All source, data files and other contents of the PROJ package are 
> available under the following terms.  Note that the PROJ 4.3 and earlier
> was "public domain" as is common with US government work, but apparently
> this is not a well defined legal term in many countries. Frank Warmerdam placed
> everything under the following MIT style license because he believed it is
> effectively the same as public domain, allowing anyone to use the code as
> they wish, including making proprietary derivatives.
>
> Initial PROJ 4.3 public domain code was put as Frank Warmerdam as copyright
> holder, but he didn't mean to imply he did the work. Essentially all work was
> done by Gerald Evenden.
>
> Copyright information can be found in source files.
>
>  --------------
>
>  Permission is hereby granted, free of charge, to any person obtaining a
>  copy of this software and associated documentation files (the "Software"),
>  to deal in the Software without restriction, including without limitation
>  the rights to use, copy, modify, merge, publish, distribute, sublicense,
>  and/or sell copies of the Software, and to permit persons to whom the
>  Software is furnished to do so, subject to the following conditions:
>
>  The above copyright notice and this permission notice shall be included
>  in all copies or substantial portions of the Software.
>
>  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
>  OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
>  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
>  THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
>  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
>  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
>  DEALINGS IN THE SOFTWARE.

Section 3 below qualifies this for the subset of `proj-data/**` that PROJ itself redistributes from
third parties.

---

## 2. IOGP GIGS — `gigs/**`

**All 30 files in `gigs/` are derived from the IOGP Geospatial Integrity of Geoscience Software
(GIGS) test dataset and are subject to the notice reproduced below.** This matters more than for
the PROJ material, because:

- the `.gie` files themselves **carry no licence header** — nothing in `gigs/*.gie` tells a reader
  that IOGP material is involved;
- the notice that once accompanied them, `test/gigs/TESTNOTES.md`, **was deleted from PROJ
  upstream**. It survives only in history, at commit `7b87c520`.

So this section is the only place the obligation is recorded. It must not be dropped.

The two blocks below are reproduced verbatim from
`git show 7b87c520:test/gigs/TESTNOTES.md` (Micah Cochran, 2016-05-24), which in turn reproduces
them from the IOGP publication. In that file the preamble reads *"The disclaimer and copyright
**only** applies to JSON files that originate from GIGS tests, which is a reformatting material
provided by the International Association of Oil & Gas Producers."* — the `.gie` files here are
machine translations of exactly those JSON files (see the chain of custody below), so the notice
carries over to them unchanged.

### Disclaimer

> Whilst every effort has been made to ensure the accuracy of the information contained in this publication,
> neither the OGP nor any of its members past present or future warrants its accuracy or will, regardless
> of its or their negligence, assume liability for any foreseeable or unforeseeable use made thereof, which
> liability is hereby excluded. Consequently, such use is at the recipient’s own risk on the basis that any use
> by the recipient constitutes agreement to the terms of this disclaimer. The recipient is obliged to inform
> any subsequent recipient of such terms.
>
> This document may provide guidance supplemental to the requirements of local legislation. Nothing
> herein, however, is intended to replace, amend, supersede or otherwise depart from such requirements. In
> the event of any conflict or contradiction between the provisions of this document and local legislation,
> applicable laws shall prevail.

### Copyright notice

> The contents of these pages are © The International Association of Oil & Gas Producers. Permission
> is given to reproduce this report in whole or in part provided (i) that the copyright of OGP and (ii)
> the source are acknowledged. All other rights are reserved.” Any other use requires the prior written
> permission of the OGP.
>
> These Terms and Conditions shall be governed by and construed in accordance with the laws of
> England and Wales. Disputes arising here from shall be exclusively subject to the jurisdiction of the
> courts of England and Wales.

### What that requires of us

1. **Acknowledge the copyright and the source.** The copyright is the International Association of
   Oil & Gas Producers (IOGP, formerly OGP). The source is the IOGP GIGS test dataset, series 5100
   (conversions) and 5200 (transformations), from *GIGS Test Dataset v2.0* (2011), reached via PROJ
   as described below. Background: <https://www.iogp.org/> (the URL in the original notice,
   `http://www.iogp.org/Geomatics#2521115-gigs`, is dead).
2. **Inform subsequent recipients of the terms.** The disclaimer text says so explicitly: *"The
   recipient is obliged to inform any subsequent recipient of such terms."* That is a transitive
   obligation — it does not stop at us. Any artifact that carries `gigs/**` must carry this notice
   too.

### Chain of custody

The `.gie` files are several transformations removed from the IOGP publication. Each step is a
commit in the PROJ repository:

| step | what | commit / date |
|---|---|---|
| 1 | **IOGP GIGS Test Dataset v2.0** published | 2011 |
| 2 | Reformatted into JSON, with a Python test driver and `TESTNOTES.md` carrying the notice above | `7b87c520`, Micah Cochran, 2016-05-24 |
| 3 | `json → gie` conversion script added | `a053ad0e`, Kristian Evers, 2017-10-24 |
| 4 | JSON auto-translated to `.gie` and registered as CMake tests | `4cf424f1`, Kristian Evers, 2017-12-11 |
| 5 | Tolerances corrected — GIGS states an infinity norm on angular coordinates in arc-seconds, `gie` needs a linear metre distance, so the values were converted (and in places `+towgs84` overridden to stop roundtrip drift) | `0770483f`, Kristian Evers, 2018-01-31 |
| 6 | Consistently-failing subset quarantined behind a filename suffix | `c75d1879`, Kristian Evers, 2018-02-02 and `ab2d175b`, Thomas Knudsen, 2018-02-12 |

Step 6 is why ten files here end in `.gie.failing` rather than `.gie`. The suffix is upstream's; it
is preserved verbatim. Those ten are: `5101.4-jhs`, `5105.1`, `5110`, `5111.2`, `5203.1`, `5204.1`,
`5205.1`, `5206`, `5207.1`, `5207.2`.

Because of steps 3–5 the numeric expectations in `gigs/**` are **PROJ's rendering** of the GIGS
dataset, not the IOGP publication's own numbers. Agreement with these files is agreement with PROJ.
It is evidence of GIGS conformance, but it is not a GIGS certification, and nothing here should be
described as one.

---

## 3. `proj-data/**` — grids, dictionaries and fixtures

All of it is checked into the PROJ source repository and is therefore covered by the PROJ MIT/X11
notice in section 1, which extends to "data files and other contents of the PROJ package". That is
the operative licence for our redistribution. The per-source detail below is recorded because PROJ
redistributes several of these files from national mapping agencies under those agencies' own
terms, and because attribution is owed regardless of the umbrella licence.

### What is here

| group | files | origin |
|---|---|---|
| Init dictionaries and runtime config | `nad27`, `nad83`, `GL27`, `ITRF2000`, `proj.ini` | `9.8.1:data/`, authored by the PROJ project (`GL27` carries an SCCS ID dating it to 1993-08-25) |
| Grids promoted to the `proj-data/` root | `alaska`, `BETA2007.gsb`, `conus`, `MD`, `ntf_r93.gsb`, `ntv1_can.dat`, plus the renamed `egm96_15.gtx` and `ntv2_0.gsb` | `9.8.1:data/tests/`; copies of the same eight files also remain under `proj-data/tests/` |
| The awkward-path fixture | `dir with space/myconus` | a byte-identical copy of `conus`; upstream creates it for `test_cs2cs_datumfile`, and the space in the directory name is the point of the fixture |
| Format fixtures | the remaining 74 files under `proj-data/tests/` — GeoTIFF, GTX, CT2, NTv2 and JSON variants | `9.8.1:data/tests/` |

### Provenance of the eight grids

All eight arrived in `data/tests/` in a single commit, `a9bc6e5f` (Even Rouault, 2020-02-26,
*"Make tests independent of proj-datumgrid"*, fixing PROJ issue #1984). Its message states that
`BETA2007.gsb`, `MD`, `alaska`, `conus`, `ntf_r93.gsb` and `ntv1_can.dat` were **copied from the
`proj-datumgrid` package**, and that `egm96_15_downsampled.gtx` and `ntv2_0_downsampled.gsb` are
**downsampled/subsetted versions** of the production `egm96_15.gtx` and `ntv2_0.gsb` created for
testing. They are reduced-fidelity test fixtures; they are not the production grids and must not be
used as such, notwithstanding the names they are copied to.

Underlying sources, by grid:

| file | underlying source |
|---|---|
| `ntv1_can.dat`, `ntv2_0.gsb` | Natural Resources Canada (NTv1 / NTv2) |
| `BETA2007.gsb` | AdV, Germany (embedded header: `SYSTEM_F DHDN90`, `SYSTEM_T ETRS89`) |
| `ntf_r93.gsb` | IGN France (embedded header: `IGN07_01`, `SYSTEM_F NTF`, `SYSTEM_T RGF93`) |
| `egm96_15.gtx` | NGA EGM96 geoid |
| `conus`, `alaska`, `MD` | NOAA/NGS NADCON NAD27→NAD83 grids, converted to PROJ's CTABLE V2 format (verified: each begins with the ASCII bytes `CTABLE V2.0`) |

### Licence status — verified versus assumed

**Verified.** The GeoTIFF fixtures under `proj-data/tests/` carry machine-readable licence strings
in their embedded GDAL metadata. Reading them directly out of the vendored files gives:

| statement found in the file | files |
|---|---|
| `Derived from work by NOAA. Public Domain` | `us_noaa_geoid06_ak_subset_at_antimeridian.tif`, `us_noaa_nadcon5_nad83_1986_nad83_harn_conus_extract_sanfrancisco.tif`, `us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif`, `us_noaa_nadcon5_nad83_2007_nad83_2011_conus_extract.tif` |
| `Derived from work by NGA. Public Domain` | `egm96_15_uncompressed_truncated.tif` |
| `Derived from work by IGN France. Open License` (Etalab Licence Ouverte) | `fr_ign_RAGTBT2016.tif`, `subset_of_gr3df97a.tif` |
| `Derived from work by Natural Resources Canada. Open Government Licence - Canada` | `test_hgrid_with_subgrid.tif`, `test_hgrid_with_subgrid_no_grid_name.tif` |
| `The Nordic Geodetic Commission. Creative Commons Attribution 4.0` | `nkgrf03vel_realigned_extract.tif`, `nkgrf03vel_realigned_extract_tiled_256x256.tif` |
| `Land Information New Zealand (2013): Released under Creative Commons Attribution 4.0 International` | `simple_model_polar.tif`, `simple_model_wrap_east.tif`, `simple_model_wrap_west.tif`, `test_3d_grid_projected.tif` |

**Verified.** `proj.db`'s `grid_alternatives` table has an `open_license BOOLEAN` column
(`9.8.1:data/sql/proj_db_table_defs.sql:905`). A CHECK constraint at line 915 makes it structurally
impossible for a row to point at `https://cdn.proj.org/` unless `direct_download = 1` **and**
`open_license = 1`. So `open_license = 1` on a CDN row is PROJ's assertion that the *PROJ-data
CDN copy* of that grid may be freely redistributed. Querying `9.8.1:data/sql/grid_alternatives.sql`
for the grids we vendor: `NTv1_0.gsb`, `NTv2_0.gsb`, `BETA2007.gsb`, `rgf93_ntf.gsb` and `WW15MGH.GRD`
(EGM96) all appear with `open_license = 1`. `conus`, `alaska` and `MD` have **no** row under those
names — the modern NADCON equivalents are separate `us_noaa_*` entries.

**Assumed, not verified.** That `open_license = 1` transfers to the *in-tree* copies here. It is a
statement about the CDN-hosted PROJ-data artifact, and the in-tree files are older-format,
reduced-fidelity copies from `proj-datumgrid`. What we actually rely on is section 1: these files
are checked into the PROJ repository, whose `COPYING` covers "data files and other contents of the
PROJ package".

**Assumed, not verified.** Licence terms for the untagged binary grids — `alaska`, `conus`, `MD`,
`ntv1_can.dat`, `ntf_r93.gsb`, `BETA2007.gsb`, `egm96_15.gtx`, `ntv2_0.gsb` — beyond the agency
attributions above. Unlike the GeoTIFFs, these legacy formats carry no embedded licence field, and
`proj-datumgrid` was retired, so no per-file statement was located at 9.8.1.

**Out of scope.** `proj.db` itself is *not* vendored here. Upstream generates it from
`data/sql/*.sql` at build time and copies it into `for_tests/`; it is not a checked-in artifact.
Note for whoever ships it later: it aggregates EPSG, ESRI, IGNF, IAU and NKG content under
different terms, and proj4j's existing `LICENSE.EPSG` covers only the EPSG portion.

---

## 4. `proj-data-cdn/**` — four production grids from OSGeo PROJ-data

### What is here, and why it is not in `proj-data/`

| file | bytes | SHA-256 | copyright holder |
|---|---:|---|---|
| `eur_nkg_nkgrf03vel_realigned.tif` | 362,495 | `a646bd863c427c7623eb2b365878351bab9a0a9b67aa6792b2dd31c3ef58e0b9` | Nordic Geodetic Commission |
| `eur_nkg_nkgrf17vel.tif` | 715,692 | `515f68aeb59f659a3f3c340626cd060f58de6f93b4546df67732b906949e0d66` | Nordic Geodetic Commission |
| `no_kv_NKGETRF14_EPSG7922_2000.tif` | 2,026,550 | `4f198074a4940171e130edbd7ec59cb1a3ba024c39f18a94404a13f285ef35c6` | Kartverket (SK), the Norwegian Mapping Authority |
| `fr_ign_RAF20.tif` | 342,920 | `dc0cc2a38f0ea1029fe72cca3b5b7ed6dfe7e1db2a8d8482b7326ce3d6f25605` | Institut Géographique National (IGN) France |

The first three are needed by `gie/nkg.gie`, whose 33 assertions name their operation as an OGC URN and
resolve, through the operation database, to pipelines whose second or third step is
`+proj=deformation +grids=…` or `+proj=xyzgridshift +grids=…`. Without the grids the pipelines build
and then cannot run.

`fr_ign_RAF20.tif` is the geoid model behind `gie/epsg_grid.gie`'s second block, RGF93 v2b +
NGF-IGN69 height to RGF93 v2b geographic 3D. It is named by that block's `require_grid`, so without
it the assertion could only ever be skipped.

**One grid the corpus asks for is deliberately absent: `us_nga_egm08_25.tif`, 80,585,622 bytes.** It
backs the other `epsg_grid.gie` block. GitHub warns above 50 MB per file and a vendored copy could
never be removed from the history, so that block stays a permanent `SKIP` — which is, on purpose,
scored as neither a pass nor a failure but still counted in the denominator. The choice was made
knowing the row is closeable; the 80 MB is the reason it was not closed.

All four are **deliberately not** under `proj-data/`, for two reasons, and moving them there will break
the build:

1. `conformance/sync-upstream.sh` begins its second phase with `rm -rf proj-data`, so anything put
   there is deleted on the next re-sync.
2. `gie-manifest.sha256` is generated over `find gie gigs proj-data -type f` and its header says
   *"SHA-256 of every file vendored from PROJ 9.8.1"*. These files are not from PROJ 9.8.1; they are
   from the separate PROJ-data package, under a different licence. Listing them there would make the
   manifest header a false statement.

`proj-data/tests/` does contain `nkgrf03vel_realigned_extract.tif` and
`nkgrf03vel_realigned_extract_tiled_256x256.tif`, which are small **extracts** of the first grid
above, taken into the PROJ repository as format fixtures. They cover a few cells and cannot satisfy
`gie/nkg.gie`, which probes points across seven countries. They are not substitutes.

### Licence, the three NKG grids — CC-BY-4.0, verified four ways

| evidence | what it says |
|---|---|
| The files' own embedded GDAL metadata, read out of the vendored bytes | `The Nordic Geodetic Commission. Creative Commons Attribution 4.0 https://creativecommons.org/licenses/by/4.0/` for the two `eur_nkg_*` grids; `Kartverket (Norwegian Mapping Authority). Creative Commons Attribution 4.0 https://creativecommons.org/licenses/by/4.0/` for `no_kv_NKGETRF14_EPSG7922_2000.tif` |
| PROJ-data's `copyright_and_licenses.csv` | `eur_nkg_nkgrf03vel_realigned.tif,Nordic Geodetic Commission,CC-BY-4.0`; `eur_nkg_nkgrf17vel.tif,Nordic Geodetic Commission,CC-BY-4.0`; `no_kv_NKGETRF14_EPSG7922_2000.tif,Kartverket (SK),CC-BY-4.0` |
| PROJ-data's `README.DATA` | PROJ-data accepts a grid only under a licence compatible with the Open Source Definition, and lists CC-BY v3.0-or-later as suitable |
| `proj.db`'s `grid_alternatives` table | all three carry `direct_download = 1` and `open_license = 1` against a `https://cdn.proj.org/` URL. A CHECK constraint (`9.8.1:data/sql/proj_db_table_defs.sql:915`) makes a CDN row structurally impossible without both flags, so this is PROJ's own assertion that the CDN copy may be freely redistributed |

Note that the licence-status caveat in section 3 — *"assumed, not verified"* — does **not** apply
here. Section 3 falls back on PROJ's umbrella MIT/X11 notice for legacy grids that carry no
per-file statement. These three carry the statement in the file.

### What CC-BY-4.0 requires of us

Attribution, and an indication of any changes. So:

- **Attribution** is the table above, and it must travel with any redistribution of these bytes.
- **No changes.** The files are byte-identical to the PROJ-data release; the SHA-256 column is how
  that is checked. They are not resampled, recompressed or cropped.

### Licence, `fr_ign_RAF20.tif` — Etalab Open Licence, verified three ways

This one is **not** CC-BY-4.0, so it gets its own statement rather than being folded into the table
above.

| evidence | what it says |
|---|---|
| The file's own `TIFFTAG_COPYRIGHT`, read out of the vendored bytes | `Derived from work by IGN France. Open License https://www.etalab.gouv.fr/wp-content/uploads/2014/05/Open_Licence.pdf` |
| PROJ-data's `copyright_and_licenses.csv` | `fr_ign_RAF20.tif,Institut Géographique National (IGN) France,Open License France - https://www.etalab.gouv.fr/wp-content/uploads/2014/05/Open_Licence.pdf,1.11` |
| `proj.db`'s `grid_alternatives` table | `direct_download = 1` and `open_license = 1` against `https://cdn.proj.org/fr_ign_RAF20.tif`. The same CHECK constraint applies, so this is again PROJ's own assertion that the CDN copy may be freely redistributed |

The fourth line of evidence the NKG grids have — `README.DATA`'s Open Source Definition rule — covers
this file too, but it is a statement about the package rather than about this grid, so it is not
counted twice.

**What the Etalab Open Licence requires of us** is attribution to the producer and a mention of the
licence and its version. Both are in the table. Redistribution and derivation are expressly permitted
and there is no share-alike term.

**And the file is already a derived work**, which its own copyright tag says: PROJ-data converted
`RAF20.tac`, last modified 2022-06-09, into this GeoTIFF. We change nothing further — the bytes are
the ones the CDN serves and the SHA-256 above is how that is checked.

### Chain of custody

Copied from an installed PROJ 9.8.1 data directory (`share/proj/`), which is the unpacked PROJ-data
package — the same artifact `https://cdn.proj.org/` serves and the same one
`copyright_and_licenses.csv` and `README.DATA` sit beside. Byte counts and hashes are recorded above
rather than described, so a future copy can be compared rather than trusted.

---

## Refreshing

```sh
conformance/sync-upstream.sh [/path/to/PROJ]     # default: a PROJ checkout beside this repo
```

The script fails loudly if `9.8.1^{commit}` in the given checkout is not
`f08fa86c478c4bbbf003b1ec751dd84aa6eca486`. Re-pinning to a different PROJ release means editing
`PROJ_REV`/`PROJ_REV_SHA` at the top of the script — and re-checking this notice, since a new
release can change what third-party data is in `data/tests/`.

The script does **not** touch `proj-data-cdn/`, and must not be made to: those files come from
PROJ-data, not from a PROJ checkout, and they are not in `gie-manifest.sha256`. To refresh them,
replace the three files from an installed `share/proj/` of the pinned PROJ release and update the
byte counts and hashes in section 4.
