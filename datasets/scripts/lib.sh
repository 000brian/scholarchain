# shellcheck shell=bash
# Shared helpers for ScholarChain dataset download scripts. Source, do not execute.
# Owner: dataset-engineer.
#
# Environment variables (names only; none are secrets, all optional):
#   SCHOLARCHAIN_DATASETS_DIR   download root (default: ${XDG_CACHE_HOME:-$HOME/.cache}/scholarchain/datasets)
#   SCHOLARCHAIN_CONTACT_MAILTO contact address appended to the User-Agent (unset by default)
#
# These helpers never call the AWS CLI and never write to S3.

ds_repo_datasets_dir() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
}

# Repository root = parent of datasets/ (no git dependency).
ds_repo_root() {
  (cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
}

ds_dest_root() {
  printf '%s\n' "${SCHOLARCHAIN_DATASETS_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/scholarchain/datasets}"
}

# Refuse destinations anywhere inside the repository (large files must never be committed).
# Checked twice: lexically before mkdir (so nothing is created in the repository), and
# again after resolving symlinks.
ds_refuse_repo_dest() {
  local dest repo
  repo="$(ds_repo_root)"
  case "$1" in /*) dest="$1" ;; *) dest="$PWD/$1" ;; esac
  case "$dest/" in
    "$repo"/*) echo "refusing destination inside the repository: $dest" >&2; exit 2 ;;
  esac
  mkdir -p "$dest"
  dest="$(cd "$dest" && pwd -P)"
  case "$dest/" in
    "$(cd "$repo" && pwd -P)"/*) echo "refusing destination inside the repository: $dest" >&2; exit 2 ;;
  esac
}

ds_user_agent() {
  local ua="scholarchain-datasets/1 (bounded research fetch)"
  if [ -n "${SCHOLARCHAIN_CONTACT_MAILTO:-}" ]; then
    ua="$ua (mailto:${SCHOLARCHAIN_CONTACT_MAILTO})"
  fi
  printf '%s\n' "$ua"
}

ds_md5() {
  if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | awk '{print $1}'
  else md5 -q "$1"; fi
}

ds_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

ds_size() {
  wc -c <"$1" | tr -d ' '
}

# ds_fetch URL OUT : download to OUT.part then atomically rename. Resumes a partial .part file.
ds_fetch() {
  local url="$1" out="$2"
  curl -fsSL --retry 3 --retry-delay 5 -C - -A "$(ds_user_agent)" -o "$out.part" "$url"
  mv -f "$out.part" "$out"
}

# ds_fetch_small URL OUT : like ds_fetch but never resumes (metadata files that may change).
ds_fetch_small() {
  local url="$1" out="$2"
  rm -f "$out.part"
  curl -fsSL --retry 3 --retry-delay 5 -A "$(ds_user_agent)" -o "$out.part" "$url"
  mv -f "$out.part" "$out"
}

# ds_check ALGO EXPECTED FILE : 0 if FILE exists and its digest equals EXPECTED.
ds_check() {
  local algo="$1" expected="$2" file="$3" got
  [ -f "$file" ] || return 1
  case "$algo" in
    md5) got="$(ds_md5 "$file")" ;;
    sha256) got="$(ds_sha256 "$file")" ;;
    *) echo "unknown algorithm $algo" >&2; return 2 ;;
  esac
  [ "$got" = "$expected" ]
}

# ds_get_verified ALGO EXPECTED URL OUT : idempotent download with checksum verification.
ds_get_verified() {
  local algo="$1" expected="$2" url="$3" out="$4"
  if ds_check "$algo" "$expected" "$out"; then
    echo "skip (present, $algo ok): $out"
    return 0
  fi
  echo "fetch: $url"
  ds_fetch "$url" "$out"
  if ! ds_check "$algo" "$expected" "$out"; then
    echo "CHECKSUM MISMATCH ($algo) for $out; expected $expected" >&2
    mv -f "$out" "$out.bad"
    return 1
  fi
  echo "ok ($algo verified): $out"
}

# ds_fetch_prefix URL OUT N TOTAL : fetch bytes 0..N-1 of URL into OUT with a HARD byte cap.
# Never writes or keeps more than N bytes, even if the server ignores Range and answers
# 200 with the whole file:
#   * --max-filesize N makes curl refuse a response whose announced length exceeds N;
#   * the body is piped through `head -c N`, which stops reading after N bytes, so curl
#     aborts mid-transfer (write error) when the length is not announced.
# Success requires curl exit 0, HTTP 206, Content-Range "0-(N-1)/TOTAL" and exactly N bytes.
# Returns 4 (and removes OUT.part) on any other outcome.
ds_fetch_prefix() {
  local url="$1" out="$2" n="$3" total="$4" hdr rcf rc status range size
  hdr="$(mktemp)"; rcf="$(mktemp)"
  rm -f "$out.part"
  ( if curl -fsS -A "$(ds_user_agent)" -r "0-$((n - 1))" --max-filesize "$n" -D "$hdr" -o - "$url"; then
      echo 0 >"$rcf"
    else
      echo "$?" >"$rcf"
    fi ) | head -c "$n" >"$out.part" || true
  rc="$(cat "$rcf")"
  status="$(awk 'toupper($1) ~ /^HTTP/ {code=$2} END {print code}' "$hdr")"
  range="$(tr -d '\r' <"$hdr" | awk 'tolower($1)=="content-range:" {print $3}')"
  size="$(ds_size "$out.part")"
  rm -f "$hdr" "$rcf"
  if [ "$rc" != "0" ] || [ "$status" != "206" ] || [ "$range" != "0-$((n - 1))/$total" ] || [ "$size" != "$n" ]; then
    echo "unexpected range response: curl_exit=$rc status=$status content-range=$range bytes=$size (cap $n)" >&2
    rm -f "$out.part"
    return 4
  fi
  mv -f "$out.part" "$out"
}

# ds_verify_pinned_sha256 FILE EXPECTED : 0 if sha256(FILE) == EXPECTED; otherwise moves FILE
# to FILE.bad, reports, and returns 5. Used for the pinned 16 MiB DBLP calibration prefix.
ds_verify_pinned_sha256() {
  local file="$1" expected="$2" got
  got="$(ds_sha256 "$file")"
  if [ "$got" != "$expected" ]; then
    echo "PINNED CHECKSUM MISMATCH: $file sha256 $got != pinned $expected" >&2
    mv -f "$file" "$file.bad"
    return 5
  fi
}

# ds_openalex_key_ok KEY ENTITY : 0 if KEY is a well-formed OpenAlex snapshot part key for ENTITY.
# Keys come from the remotely fetched manifest.json and are used as local paths, so
# anything else (.., absolute paths, other directories) is rejected.
ds_openalex_key_ok() {
  local key="$1" entity="$2"
  [[ "$entity" =~ ^[a-z-]+$ ]] || return 1
  [[ "$key" =~ ^data/jsonl/([a-z-]+)/updated_date=[0-9]{4}-[0-9]{2}-[0-9]{2}/part_[0-9]+\.gz$ ]] || return 1
  [ "${BASH_REMATCH[1]}" = "$entity" ]
}
