#!/usr/bin/env bash
# ScholarChain PostToolUse formatter (constitutional artifact; owner: factory-engineer).
#
# Formats a Scala source after Write/Edit/MultiEdit. Exits 0 silently when:
#   - the written file is not .scala, .sc, or .sbt
#   - the file no longer exists
#   - the repository has no .scalafmt.conf yet (build-engineer creates it)
#   - no formatter is available (scalafmt CLI or sbt with a build.sbt)
# Exits 2 with an actionable message when the formatter rejects the file, so the
# writing agent sees the failure (for example a syntax error) and repairs it.
# Must stay compatible with macOS /bin/bash 3.2.

set -u
set -f

INPUT="$(cat)"

if command -v jq >/dev/null 2>&1; then
  FILE="$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)"
elif command -v python3 >/dev/null 2>&1; then
  FILE="$(printf '%s' "$INPUT" | python3 -I -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))
except Exception: pass')"
else
  exit 0
fi

case "$FILE" in
  *.scala|*.sc|*.sbt) ;;
  *) exit 0 ;;
esac
[ -f "$FILE" ] || exit 0

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ROOT="${ROOT%/}"
case "$FILE" in
  "$ROOT"/*) REL="${FILE#"$ROOT"/}" ;;
  /*) exit 0 ;;   # outside the repository: not ours to format
  *) REL="$FILE" ;;
esac

if [ ! -f "$ROOT/.scalafmt.conf" ]; then
  echo "format-scala.sh: no .scalafmt.conf yet (owned by build-engineer); skipping formatting of $REL." >&2
  exit 0
fi

cd "$ROOT" || exit 0

run_with_timeout() {
  local secs="$1"; shift
  if command -v timeout >/dev/null 2>&1; then timeout "$secs" "$@"
  elif command -v gtimeout >/dev/null 2>&1; then gtimeout "$secs" "$@"
  else "$@"
  fi
}

if command -v scalafmt >/dev/null 2>&1; then
  OUT="$(run_with_timeout 120 scalafmt --non-interactive --quiet "$REL" 2>&1)"; RC=$?
  FMT="scalafmt --non-interactive $REL"
elif command -v sbt >/dev/null 2>&1 && [ -f "$ROOT/build.sbt" ]; then
  OUT="$(run_with_timeout 170 sbt -batch -no-colors "scalafmtOnly $REL" 2>&1)"; RC=$?
  FMT="sbt \"scalafmtOnly $REL\""
else
  echo "format-scala.sh: neither scalafmt nor sbt with build.sbt is available; $REL was not formatted. sbt check will still enforce formatting." >&2
  exit 0
fi

if [ "$RC" -ne 0 ]; then
  {
    echo "format-scala.sh: formatting failed for $REL (exit $RC) using: $FMT"
    printf '%s\n' "$OUT" | tail -n 25
    echo "Fix the reported syntax or formatting error in $REL, then re-run sbt check. Do not disable the formatter."
  } >&2
  exit 2
fi
exit 0
