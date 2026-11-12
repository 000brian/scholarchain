#!/usr/bin/env bash
# ScholarChain Stop / SubagentStop test gate (constitutional artifact; owner: factory-engineer).
#
# Refuses completion when relevant source changed after the last successful test run.
# The marker .claude/.last-test-run is written only by the sbt markTestRun task
# (build-engineer), which runs at the end of `sbt check` after tests pass.
#
# Relevant source: uncommitted (modified, added, or untracked) files matching
#   build.sbt, project/**, modules/**, tests/**, prompts/**
# with extensions .scala .sc .sbt .java .conf .json .yaml .yml .properties.
#
# Escape conditions (completion allowed, with a warning on stderr):
#   - no relevant source changed, or the marker is newer than every changed file;
#   - the agent's last message reports STATUS: BLOCKED-ON or STATUS: FAILED
#     (control rule 6 gates only COMPLETE implementation reports);
#   - second consecutive stop attempt (stop_hook_active) while build.sbt does not
#     exist yet, so tests cannot run; the agent must report BLOCKED-ON build-engineer.
# Must stay compatible with macOS /bin/bash 3.2.

set -u
set -f

INPUT="$(cat)"

jget() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$INPUT" | jq -r ".$1 // empty" 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$INPUT" | python3 -I -c 'import json,sys
try:
    v=json.load(sys.stdin).get(sys.argv[1])
    print("" if v is None else (str(v).lower() if isinstance(v,bool) else v))
except Exception: pass' "$1"
  fi
}

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ROOT="${ROOT%/}"
cd "$ROOT" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

MARKER=".claude/.last-test-run"

mtime() {
  stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0
}

CHANGED=""
NEWEST=0
NEWEST_FILE=""
while IFS= read -r line; do
  [ -z "$line" ] && continue
  status="${line:0:2}"
  path="${line:3}"
  case "$path" in *" -> "*) path="${path##* -> }" ;; esac
  path="${path%\"}"; path="${path#\"}"
  case "$status" in *D*) continue ;; esac   # deletions have no mtime; reviewed at code review
  case "$path" in
    build.sbt|project/*|modules/*|tests/*|prompts/*) ;;
    *) continue ;;
  esac
  case "$path" in
    */target/*|target/*|project/project/*) continue ;;
    *.scala|*.sc|*.sbt|*.java|*.conf|*.json|*.yaml|*.yml|*.properties) ;;
    */) ;;   # untracked directory: inspect files below
    *) continue ;;
  esac
  if [ -d "$path" ]; then
    while IFS= read -r f; do
      t="$(mtime "$f")"
      CHANGED="$CHANGED $f"
      if [ "$t" -gt "$NEWEST" ]; then NEWEST="$t"; NEWEST_FILE="$f"; fi
    done <<EOF
$(find "$path" -type f \( -name '*.scala' -o -name '*.sc' -o -name '*.sbt' -o -name '*.java' -o -name '*.conf' -o -name '*.json' -o -name '*.yaml' -o -name '*.yml' -o -name '*.properties' \) -not -path '*/target/*' 2>/dev/null)
EOF
  elif [ -f "$path" ]; then
    t="$(mtime "$path")"
    CHANGED="$CHANGED $path"
    if [ "$t" -gt "$NEWEST" ]; then NEWEST="$t"; NEWEST_FILE="$path"; fi
  fi
done <<EOF
$(git status --porcelain=v1 --untracked-files=all 2>/dev/null)
EOF

[ -z "$CHANGED" ] && exit 0

if [ -f "$MARKER" ]; then
  M="$(mtime "$MARKER")"
  if [ "$M" -ge "$NEWEST" ]; then
    exit 0
  fi
  STATE="stale: $MARKER is older than $NEWEST_FILE"
else
  STATE="missing: $MARKER does not exist"
fi

# Allow non-COMPLETE reports to be delivered.
LAST="$(jget last_assistant_message)"
if [ -z "$LAST" ]; then
  TP="$(jget agent_transcript_path)"
  [ -z "$TP" ] && TP="$(jget transcript_path)"
  if [ -n "$TP" ] && [ -f "$TP" ] && command -v jq >/dev/null 2>&1; then
    LAST="$(tail -n 400 "$TP" 2>/dev/null | jq -rs '[.[] | select(.type=="assistant")] | last | (.message.content // [])[]? | select(.type=="text") | .text' 2>/dev/null)"
  fi
fi
if printf '%s' "$LAST" | grep -Eq 'STATUS:[[:space:]*]*(BLOCKED-ON|FAILED)'; then
  echo "verify-tests-ran.sh: test marker $STATE; completion allowed only because the report status is BLOCKED-ON or FAILED." >&2
  exit 0
fi

ACTIVE="$(jget stop_hook_active)"
if [ ! -f build.sbt ] && [ "$ACTIVE" = "true" ]; then
  echo "verify-tests-ran.sh: source changed but build.sbt does not exist, so tests cannot run. Report STATUS: BLOCKED-ON with BLOCKED-ON OWNER: build-engineer." >&2
  exit 0
fi

{
  echo "BLOCKED by .claude/hooks/verify-tests-ran.sh: source changed after the last successful test run ($STATE)."
  echo "Changed source files:$(printf '%s' "$CHANGED" | tr ' ' '\n' | head -n 15 | sed 's/^/ /' | tr '\n' ' ')"
  if [ -f build.sbt ]; then
    echo "Run: sbt check   (it runs formatting checks, compilation, tests, and markTestRun, which refreshes $MARKER only when tests pass)."
  else
    echo "Run: sbt check   -- build.sbt does not exist yet, so tests cannot run; finish with STATUS: BLOCKED-ON and BLOCKED-ON OWNER: build-engineer."
  fi
  echo "If tests fail and the defect is outside your ownership, finish with STATUS: BLOCKED-ON or STATUS: FAILED and the evidence. Never write $MARKER yourself."
} >&2
exit 2
