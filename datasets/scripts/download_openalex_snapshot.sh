#!/usr/bin/env bash
# Bounded, idempotent access to the pinned OpenAlex public snapshot (anonymous HTTPS).
# Owner: dataset-engineer. No AWS CLI, no credentials, never writes to AWS.
#
# Usage: download_openalex_snapshot.sh [--dest DIR] [--entity works|authors|...]
#                                      [--max-parts N] [--full] [--dry-run]
#
# Default (bounded, < 1 MB): LICENSE.txt, RELEASE_NOTES.txt and data/jsonl/manifest.json.
#   The manifest date must equal the pinned snapshot; prints entity size and part count.
# --max-parts N : first N part files of --entity in manifest order (N <= 2 without --full;
#   a works part averages ~355 MB). Each manifest key must match
#   data/jsonl/<entity>/updated_date=YYYY-MM-DD/part_N.gz before it is used as a local
#   path. Each part is verified: byte length == manifest
#   content_length and gunzip line count == manifest record_count.
# --full : every part of --entity (works = 675,298,563,638 bytes in snapshot 2026-10-14).
#
# The ScholarChain bounded corpus uses API lookups (fetch_enrichment.py); this
# snapshot path is the reproducible fallback.
set -euo pipefail

OPENALEX_SNAPSHOT_DATE="2026-10-14"
OPENALEX_HTTPS_BASE="https://openalex.s3.amazonaws.com"

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dest=""; entity="works"; max_parts=0; full=0; dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dest) dest="$2"; shift 2 ;;
    --entity) entity="$2"; shift 2 ;;
    --max-parts) max_parts="$2"; shift 2 ;;
    --full) full=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$entity" in *[!a-z-]*|'') echo "invalid --entity" >&2; exit 2 ;; esac
case "$max_parts" in ''|*[!0-9]*) echo "--max-parts must be a non-negative integer" >&2; exit 2 ;; esac
if [ "$full" -eq 0 ] && [ "$max_parts" -gt 2 ]; then
  echo "--max-parts > 2 requires --full" >&2; exit 2
fi

dest="${dest:-$(ds_dest_root)/openalex/snapshot=$OPENALEX_SNAPSHOT_DATE}"

if [ "$dry" -eq 1 ]; then
  echo "plan: dest=$dest"
  for f in LICENSE.txt RELEASE_NOTES.txt data/jsonl/manifest.json; do echo "plan: GET $OPENALEX_HTTPS_BASE/$f"; done
  if [ "$full" -eq 1 ]; then echo "plan: all parts of entity '$entity' (sizes from manifest)"
  elif [ "$max_parts" -gt 0 ]; then echo "plan: first $max_parts part(s) of entity '$entity'"
  else echo "plan: no data parts (bounded default)"; fi
  exit 0
fi

ds_refuse_repo_dest "$dest"
cd "$dest"
for f in LICENSE.txt RELEASE_NOTES.txt data/jsonl/manifest.json; do
  mkdir -p "$(dirname "$f")"
  ds_fetch_small "$OPENALEX_HTTPS_BASE/$f" "$f"
done
grep -q "CC0 1.0 Universal" LICENSE.txt || { echo "LICENSE.txt no longer states CC0 1.0 Universal" >&2; exit 3; }

plan="$(python3 -I - "$entity" "$OPENALEX_SNAPSHOT_DATE" <<'PY'
import json, sys
entity, pinned = sys.argv[1], sys.argv[2]
m = json.load(open("data/jsonl/manifest.json"))
if m.get("date") != pinned:
    sys.exit("manifest date %s != pinned %s; update the dataset manifest first" % (m.get("date"), pinned))
e = next((x for x in m["entities"] if x["entity"] == entity), None)
if e is None:
    sys.exit("entity %s not in manifest" % entity)
print("# entity=%s records=%d bytes=%d parts=%d" % (entity, e["record_count"], e["content_length"], len(e["files"])), file=sys.stderr)
for f in e["files"]:
    print(f["url"], f["meta"]["content_length"], f["meta"]["record_count"])
PY
)"
n_total="$(printf '%s\n' "$plan" | grep -c . || true)"
if [ "$full" -eq 1 ]; then n="$n_total"; else n="$max_parts"; fi
[ "$n" -eq 0 ] && { echo "bounded default: metadata only (pass --max-parts N or --full for data)"; exit 0; }

printf '%s\n' "$plan" | head -n "$n" | while read -r url bytes records; do
  # url, bytes and records come from the remote manifest: validate before any use as a path.
  case "$url" in s3://openalex/*) ;; *) echo "REFUSING manifest url outside s3://openalex/: $url" >&2; exit 4 ;; esac
  key="${url#s3://openalex/}"
  if ! ds_openalex_key_ok "$key" "$entity"; then
    echo "REFUSING malformed manifest key (expected data/jsonl/$entity/updated_date=YYYY-MM-DD/part_N.gz): $key" >&2; exit 4
  fi
  case "$bytes$records" in ''|*[!0-9]*) echo "REFUSING non-numeric manifest meta for $key" >&2; exit 4 ;; esac
  mkdir -p "$(dirname "$key")"
  if [ -f "$key" ] && [ "$(ds_size "$key")" = "$bytes" ]; then
    echo "skip (present, length ok): $key"; continue
  fi
  ds_fetch "$OPENALEX_HTTPS_BASE/$key" "$key"
  [ "$(ds_size "$key")" = "$bytes" ] || { echo "LENGTH MISMATCH: $key" >&2; mv -f "$key" "$key.bad"; exit 4; }
  lines="$(gzip -dc "$key" | wc -l | tr -d ' ')"
  [ "$lines" = "$records" ] || { echo "RECORD COUNT MISMATCH: $key ($lines != $records)" >&2; mv -f "$key" "$key.bad"; exit 4; }
  echo "ok (length and record_count verified): $key"
done
