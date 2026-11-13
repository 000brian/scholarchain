#!/usr/bin/env bash
# Bounded, idempotent download of the pinned dblp XML monthly release.
# Owner: dataset-engineer. Never touches AWS. No credentials.
#
# Usage: download_dblp.sh [--dest DIR] [--prefix-bytes N] [--full] [--dry-run]
#
# Default (bounded):
#   * dblp.dtd (13 KB), verified against the pinned sha256
#   * the release .md5 file (tiny); must equal the pinned MD5 (detects upstream change)
#   * the first N bytes of the release via HTTP Range (default 16 MiB, max 64 MiB),
#     with a hard byte cap (curl --max-filesize N plus `head -c N`), so a server that
#     ignores Range can never deliver more than N bytes. Verified by HTTP 206,
#     Content-Range total == pinned size, and length == N. The default 16 MiB prefix
#     must also equal the pinned DBLP_PREFIX_16MIB_SHA256 (calibration input in the
#     manifest). A prefix is a truncated gzip stream: good for samples and parser
#     smoke tests only. 16 MiB is the smallest prefix that contains every key in
#     samples/selection.json.
# --full (explicit, ~1.1 GB): the complete release, resumable, MD5-verified.
#
# Idempotent: present files with a matching checksum are skipped.
set -euo pipefail

# Pinned values; must match datasets/dataset-manifest.yaml (checked by validate_datasets.py).
DBLP_RELEASE_ID="2026-11-01"
DBLP_RELEASE_URL="https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-11-01.xml.gz"
DBLP_RELEASE_MD5="18ad5db59592406ec74289975c4b44cb"
DBLP_RELEASE_BYTES="1116142729"
DBLP_DTD_URL="https://dblp.org/xml/dblp.dtd"
DBLP_DTD_SHA256="2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6"
DBLP_PREFIX_16MIB_BYTES="16777216"
DBLP_PREFIX_16MIB_SHA256="62d59ff664ae15fdf4a3dfb0a64b4e1e2559642198477b09555d92658ca07c2f"

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dest=""; prefix_bytes=$((16 * 1024 * 1024)); full=0; dry=0
max_prefix=$((64 * 1024 * 1024))
while [ $# -gt 0 ]; do
  case "$1" in
    --dest) dest="$2"; shift 2 ;;
    --prefix-bytes) prefix_bytes="$2"; shift 2 ;;
    --full) full=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$prefix_bytes" in ''|*[!0-9]*) echo "--prefix-bytes must be a positive integer" >&2; exit 2 ;; esac
if [ "$prefix_bytes" -lt 1 ] || [ "$prefix_bytes" -gt "$max_prefix" ]; then
  echo "--prefix-bytes must be between 1 and $max_prefix (use --full for the whole release)" >&2; exit 2
fi

dest="${dest:-$(ds_dest_root)/dblp/release=$DBLP_RELEASE_ID}"
name="$(basename "$DBLP_RELEASE_URL")"

if [ "$dry" -eq 1 ]; then
  echo "plan: dest=$dest"
  echo "plan: GET $DBLP_DTD_URL -> dblp.dtd (sha256 $DBLP_DTD_SHA256)"
  echo "plan: GET $DBLP_RELEASE_URL.md5 -> must contain $DBLP_RELEASE_MD5"
  if [ "$full" -eq 1 ]; then
    echo "plan: GET $DBLP_RELEASE_URL -> $name ($DBLP_RELEASE_BYTES bytes, md5 $DBLP_RELEASE_MD5)"
  else
    echo "plan: GET bytes 0-$((prefix_bytes - 1)) of $DBLP_RELEASE_URL -> $name.prefix-$prefix_bytes (hard cap $prefix_bytes bytes: curl --max-filesize + head -c)"
    if [ "$prefix_bytes" = "$DBLP_PREFIX_16MIB_BYTES" ]; then
      echo "plan: prefix must match pinned sha256 $DBLP_PREFIX_16MIB_SHA256"
    fi
  fi
  exit 0
fi

ds_refuse_repo_dest "$dest"
cd "$dest"

ds_get_verified sha256 "$DBLP_DTD_SHA256" "$DBLP_DTD_URL" dblp.dtd

curl -fsSL --retry 3 -A "$(ds_user_agent)" -o "$name.md5.part" "$DBLP_RELEASE_URL.md5"
published="$(awk '{print $1}' "$name.md5.part")"
if [ "$published" != "$DBLP_RELEASE_MD5" ]; then
  echo "published MD5 ($published) differs from pinned ($DBLP_RELEASE_MD5); update the manifest first" >&2
  rm -f "$name.md5.part"; exit 3
fi
mv -f "$name.md5.part" "$name.md5"
echo "ok: published md5 matches pinned value"

if [ "$full" -eq 1 ]; then
  ds_get_verified md5 "$DBLP_RELEASE_MD5" "$DBLP_RELEASE_URL" "$name"
  exit 0
fi

out="$name.prefix-$prefix_bytes"
# Expected digest: pinned for the 16 MiB calibration prefix; otherwise self-recorded.
pinned_sha=""
[ "$prefix_bytes" = "$DBLP_PREFIX_16MIB_BYTES" ] && pinned_sha="$DBLP_PREFIX_16MIB_SHA256"
if [ -f "$out" ] && [ -f "$out.sha256" ] && [ "$(ds_size "$out")" = "$prefix_bytes" ] \
   && ds_check sha256 "$(cat "$out.sha256")" "$out" \
   && { [ -z "$pinned_sha" ] || [ "$(cat "$out.sha256")" = "$pinned_sha" ]; }; then
  echo "skip (present, sha256 ok): $dest/$out"
  exit 0
fi
ds_fetch_prefix "$DBLP_RELEASE_URL" "$out" "$prefix_bytes" "$DBLP_RELEASE_BYTES"
if [ -n "$pinned_sha" ]; then
  ds_verify_pinned_sha256 "$out" "$pinned_sha" || exit 5
fi
ds_sha256 "$out" >"$out.sha256"
echo "ok (range verified against pinned size $DBLP_RELEASE_BYTES${pinned_sha:+; sha256 matches pinned calibration prefix}): $dest/$out"
