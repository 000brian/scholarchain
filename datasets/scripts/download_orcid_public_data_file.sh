#!/usr/bin/env bash
# Bounded, idempotent access to the pinned ORCID Public Data File 2026 (Figshare).
# Owner: dataset-engineer. No credentials, never touches AWS.
#
# Usage: download_orcid_public_data_file.sh [--dest DIR] [--file NAME | --all] [--full] [--dry-run]
#
# Default (bounded, a few KB): fetch the Figshare article metadata and verify that
#   every pinned file still has the pinned size and MD5 (detects re-publication).
# Data files are 20-57 GB each, so any data download requires --full:
#   --file NAME --full : one file, MD5-verified (e.g. ORCID_2026_10_summaries.tar.gz)
#   --all --full       : all 12 files (290,958,772,512 bytes)
set -euo pipefail

ORCID_FIGSHARE_ARTICLE="34697766"
ORCID_FIGSHARE_API="https://api.figshare.com/v2/articles/$ORCID_FIGSHARE_ARTICLE"
ORCID_DOI="10.23640/07243.34697766.v1"
# name|bytes|md5|download_url ; must match datasets/dataset-manifest.yaml (checked by validate_datasets.py)
ORCID_FILES="
ORCID_2026_10_summaries.tar.gz|57290954546|a55a16fe0b292bda03cc6016546c8984|https://ndownloader.figshare.com/files/70342863
ORCID_2026_10_activities_0.tar.gz|22873370376|b4408eaed823afbd42d6a800b01c821c|https://ndownloader.figshare.com/files/70335732
ORCID_2026_10_activities_1.tar.gz|20750970476|17fd95ff1cd557902e219e6e5e1c3f9c|https://ndownloader.figshare.com/files/70342284
ORCID_2026_10_activities_2.tar.gz|20472804989|21c7be85e944d8a0a9580e29c617cdaa|https://ndownloader.figshare.com/files/70342578
ORCID_2026_10_activities_3.tar.gz|21754893809|c350088e3653d51ff7f39467bf338bb1|https://ndownloader.figshare.com/files/70335711
ORCID_2026_10_activities_4.tar.gz|22288197758|483d5a5c63bc5ec8f8bf88d0d92e41bd|https://ndownloader.figshare.com/files/70335720
ORCID_2026_10_activities_5.tar.gz|21736843783|99ebd6b33c5d6ed39cbd62fae7550c89|https://ndownloader.figshare.com/files/70346007
ORCID_2026_10_activities_6.tar.gz|20123581975|87a4b05d567436110b01b916a8c119fa|https://ndownloader.figshare.com/files/70335660
ORCID_2026_10_activities_7.tar.gz|21116685124|378338721f5d913cb231a0fee0bb114e|https://ndownloader.figshare.com/files/70342662
ORCID_2026_10_activities_8.tar.gz|20418791870|3d14bdda9e5ebb8c94b0701c99c9bb7f|https://ndownloader.figshare.com/files/70335666
ORCID_2026_10_activities_9.tar.gz|21591658921|54f6739ed5de832fc32b79df6a39daf6|https://ndownloader.figshare.com/files/70342671
ORCID_2026_10_activities_X.tar.gz|20540018885|ba665d7d3471ac873d1d7b4ed93d3a1f|https://ndownloader.figshare.com/files/70335672
"

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dest=""; want=""; all=0; full=0; dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dest) dest="$2"; shift 2 ;;
    --file) want="$2"; shift 2 ;;
    --all) all=1; shift ;;
    --full) full=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if { [ -n "$want" ] || [ "$all" -eq 1 ]; } && [ "$full" -eq 0 ]; then
  echo "data files are 20-57 GB each; pass --full explicitly to download" >&2; exit 2
fi
if [ -n "$want" ] && ! printf '%s\n' "$ORCID_FILES" | grep -q "^$want|"; then
  echo "unknown file: $want" >&2; exit 2
fi

dest="${dest:-$(ds_dest_root)/orcid/public-data-file=2026}"

if [ "$dry" -eq 1 ]; then
  echo "plan: dest=$dest"
  echo "plan: GET $ORCID_FIGSHARE_API (metadata; verify pinned names, sizes, MD5)"
  printf '%s\n' "$ORCID_FILES" | while IFS='|' read -r name bytes md5 url; do
    [ -z "$name" ] && continue
    if [ "$all" -eq 1 ] || [ "$name" = "$want" ]; then echo "plan: GET $url -> $name ($bytes bytes, md5 $md5)"; fi
  done
  exit 0
fi

ds_refuse_repo_dest "$dest"
cd "$dest"
ds_fetch_small "$ORCID_FIGSHARE_API" figshare-article.json
python3 -I - "$ORCID_DOI" "$ORCID_FILES" <<'PY'
import json, sys
doi, pinned = sys.argv[1], sys.argv[2]
a = json.load(open("figshare-article.json"))
errors = []
if a.get("doi") != doi:
    errors.append("doi %s != %s" % (a.get("doi"), doi))
if (a.get("license") or {}).get("name") != "CC0":
    errors.append("license is %r, expected CC0" % (a.get("license") or {}).get("name"))
files = {f["name"]: f for f in a.get("files", [])}
for line in pinned.strip().splitlines():
    name, size, md5, url = line.split("|")
    f = files.get(name)
    if f is None or str(f["size"]) != size or f.get("supplied_md5") != md5 or f.get("download_url") != url:
        errors.append("pinned file changed or missing: %s" % name)
if errors:
    sys.exit("Figshare metadata differs from manifest:\n  " + "\n  ".join(errors))
print("ok: Figshare metadata matches pinned DOI, CC0 license, names, sizes and MD5 values")
PY

[ -z "$want" ] && [ "$all" -eq 0 ] && { echo "bounded default: metadata only"; exit 0; }
printf '%s\n' "$ORCID_FILES" | while IFS='|' read -r name bytes md5 url; do
  [ -z "$name" ] && continue
  if [ "$all" -eq 1 ] || [ "$name" = "$want" ]; then
    ds_get_verified md5 "$md5" "$url" "$name"
  fi
done
