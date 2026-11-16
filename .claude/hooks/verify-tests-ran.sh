#!/usr/bin/env bash
# ScholarChain Stop / SubagentStop test gate (constitutional artifact; owner: factory-engineer).
#
# Refuses completion when relevant source changed after the last successful test run
# (the specification's rule: it "prevents a source-changing agent from stopping").
# The marker .claude/.last-test-run is written only by the sbt markTestRun task
# (build-engineer), which runs at the end of `sbt check` after tests pass.
#
# Relevant source: uncommitted (modified, added, or untracked) files matching
#   build.sbt, project/**, modules/**, tests/**, prompts/**
# with extensions .scala .sc .sbt .java .conf .json .yaml .yml .properties, plus the
# files .scalafmt.conf, .jvmopts and .sbtopts. Files under target/ are ignored;
# deletions are skipped (no mtime; reviewed at code review).
#
# Completion is allowed when:
#   - no relevant source changed, or the full marker is newer than every changed file
#     (Stop and SubagentStop);
#   - SubagentStop only (amendment-02 repair, human decision A): the subagent's own
#     file .claude/agents/<agent_type>.md exists, its frontmatter names it, and its
#     single tools field lists none of Write, Edit, MultiEdit, NotebookEdit or Bash.
#     Such an agent cannot change source, so it is not a source-changing agent. Fail
#     closed: an empty or unusual agent_type, a missing file, or a missing, empty,
#     multi-line, wildcard or repeated tools field means no exemption.
#   - SubagentStop only (ratified in amendment-02): every changed file is covered by
#     fresh, passing sbt test reports of its project. Mapping:
#       modules/core/**  -> modules/core/target/jvm-3/test-reports and
#                           modules/core/target/jvm-2.13/test-reports (both builds)
#       modules/<m>/**   -> modules/<m>/target/test-reports
#                           (m = hadoop, entity-resolution, spark, storage, rag, services)
#       prompts/**       -> modules/rag/target/test-reports
#       tests/**         -> tests/contracts/target/test-reports
#     Covered: in each folder at least one TEST-*.xml is newer (file mtime) than the
#     project's newest changed file, and every one of those newer reports
#       - has failures="0" and errors="0" on its <testsuite> element, and
#       - carries the timestamp="YYYY-MM-DDThh:mm:ss" attribute sbt writes inside the
#         file (touch cannot change it), strictly later than the changed file's mtime.
#     sbt writes that timestamp in local time with no zone. It is compared with the
#     changed file's mtime rendered in the same local time as YYYYMMDDhhmmss (BSD
#     `stat -f %Sm -t`, else GNU `date -r`); both must be exactly 14 digits and are
#     compared as integers (no python, no GNU-only date flags on macOS).
#     A report whose element, counts or timestamp cannot be read, a timestamp with a
#     zone suffix, or an mtime that cannot be rendered counts as not covering (fail
#     closed). Whole-second precision: a report from the same second as the change
#     does not cover it.
#     build.sbt, project/**, .scalafmt.conf, .jvmopts, .sbtopts, and modules without a
#     known report folder have no shortcut: the full marker is required.
#   - the agent's last message reports STATUS: BLOCKED-ON or STATUS: FAILED
#     (control rule 6 gates only COMPLETE implementation reports);
#   - second consecutive stop attempt (stop_hook_active) while build.sbt does not
#     exist yet, so tests cannot run; the agent must report BLOCKED-ON build-engineer.
# Stop (the main session) needs the full marker, so no phase ends without one passing
# `sbt check` after the last change. A missing hook_event_name is treated as Stop.
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
EVENT="$(jget hook_event_name)"
[ "$EVENT" = "SubagentStop" ] || EVENT="Stop"
AGENT_TYPE="$(jget agent_type)"

mtime() {
  stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0
}

# is14 <text> : true when text is exactly 14 digits (YYYYMMDDhhmmss).
is14() {
  case "$1" in
    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) return 0 ;;
  esac
  return 1
}

# local_mtime <file> : the file's mtime as local time YYYYMMDDhhmmss, or nothing.
local_mtime() {
  local out
  out="$(stat -f %Sm -t %Y%m%d%H%M%S "$1" 2>/dev/null)"
  if is14 "$out"; then printf '%s' "$out"; return 0; fi
  out="$(date -r "$1" +%Y%m%d%H%M%S 2>/dev/null)"
  if is14 "$out"; then printf '%s' "$out"; return 0; fi
  return 1
}

CHANGED=""     # newline-separated changed source files
NEWEST=0
NEWEST_FILE=""
note_file() {
  local f="$1" t
  t="$(mtime "$f")"
  CHANGED="$CHANGED
$f"
  if [ "$t" -gt "$NEWEST" ]; then NEWEST="$t"; NEWEST_FILE="$f"; fi
}

while IFS= read -r line; do
  [ -z "$line" ] && continue
  status="${line:0:2}"
  path="${line:3}"
  case "$path" in *" -> "*) path="${path##* -> }" ;; esac
  path="${path%\"}"; path="${path#\"}"
  case "$status" in *D*) continue ;; esac   # deletions have no mtime; reviewed at code review
  case "$path" in
    .scalafmt.conf|.jvmopts|.sbtopts)
      [ -f "$path" ] && note_file "$path"
      continue ;;
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
      [ -z "$f" ] && continue
      note_file "$f"
    done <<EOF
$(find "$path" -type f \( -name '*.scala' -o -name '*.sc' -o -name '*.sbt' -o -name '*.java' -o -name '*.conf' -o -name '*.json' -o -name '*.yaml' -o -name '*.yml' -o -name '*.properties' \) -not -path '*/target/*' 2>/dev/null)
EOF
  elif [ -f "$path" ]; then
    note_file "$path"
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

# ----------------------------------------------------------------------------------
# Read-only subagents (SubagentStop only; amendment-02 repair, human decision A).
# ----------------------------------------------------------------------------------
# read_only_agent <agent_type> : true only when .claude/agents/<agent_type>.md exists,
# starts with a frontmatter block whose name is <agent_type>, and has exactly one
# one-line tools field that is non-empty, has no wildcard, and names none of Write,
# Edit, MultiEdit, NotebookEdit or Bash (also not as Bash(...)). Anything else: false.
read_only_agent() {
  local t="$1" f fm n tools item lc
  [ -n "$t" ] || return 1
  case "$t" in
    -*|*[!A-Za-z0-9_-]*) return 1 ;;
  esac
  f=".claude/agents/$t.md"
  [ -f "$f" ] && [ -r "$f" ] || return 1
  [ "$(head -n 1 "$f" | tr -d '\r')" = "---" ] || return 1
  # frontmatter: line 2 up to the closing '---' line, which must exist
  fm="$(sed -n '2,/^---[[:space:]]*$/p' "$f" | tr -d '\r')"
  printf '%s\n' "$fm" | tail -n 1 | grep -Eq '^---[[:space:]]*$' || return 1
  fm="$(printf '%s\n' "$fm" | sed '$d')"
  [ -n "$fm" ] || return 1
  printf '%s\n' "$fm" | grep -Eq "^name:[[:space:]]*[\"']?$t[\"']?[[:space:]]*$" || return 1
  n="$(printf '%s\n' "$fm" | grep -c '^tools:')"
  [ "$n" = 1 ] || return 1
  # drop [ ] " ' (YAML flow-list brackets and quotes)
  tools="$(printf '%s\n' "$fm" | sed -n 's/^tools:[[:space:]]*//p' | tr -d '\133\135\042\047')"
  [ -n "$(printf '%s' "$tools" | tr -d ' \t,')" ] || return 1
  case "$tools" in *'*'*) return 1 ;; esac
  local IFS=$', \t'     # split on commas and blanks, so "Read Grep Bash" is three items
  for item in $tools; do
    item="${item%%\(*}"
    lc="$(printf '%s' "$item" | tr -d ' \t' | tr 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' 'abcdefghijklmnopqrstuvwxyz')"
    case "$lc" in
      write|edit|multiedit|notebookedit|bash) return 1 ;;
    esac
  done
  return 0
}

if [ "$EVENT" = "SubagentStop" ] && read_only_agent "$AGENT_TYPE"; then
  echo "verify-tests-ran.sh: subagent '$AGENT_TYPE' has no Write, Edit, MultiEdit, NotebookEdit or Bash tool (.claude/agents/$AGENT_TYPE.md), so it cannot have changed source; its stop is not gated (test marker $STATE). The source-changing agent and the main session are still gated." >&2
  exit 0
fi

# ----------------------------------------------------------------------------------
# Per-project test evidence (SubagentStop only).
# ----------------------------------------------------------------------------------
# project_of <file> : project key, "FULL" (full marker required) or "UNKNOWN".
project_of() {
  case "$1" in
    build.sbt|project/*|.scalafmt.conf|.jvmopts|.sbtopts) echo "FULL" ;;
    modules/core/*) echo "core" ;;
    modules/hadoop/*) echo "hadoop" ;;
    modules/entity-resolution/*) echo "entity-resolution" ;;
    modules/spark/*) echo "spark" ;;
    modules/storage/*) echo "storage" ;;
    modules/rag/*|prompts/*) echo "rag" ;;
    modules/services/*) echo "services" ;;
    tests/*) echo "contracts" ;;
    *) echo "UNKNOWN" ;;
  esac
}

# report_dirs <project> : the sbt test-report folder(s) that must all be fresh.
report_dirs() {
  case "$1" in
    core) printf '%s\n' modules/core/target/jvm-3/test-reports modules/core/target/jvm-2.13/test-reports ;;
    contracts) printf '%s\n' tests/contracts/target/test-reports ;;
    *) printf '%s\n' "modules/$1/target/test-reports" ;;
  esac
}

# sbt_cmd <project> : the targeted command to suggest.
sbt_cmd() {
  case "$1" in
    core) echo 'sbt -batch "core/testOnly <suites>" "core2_13/testOnly <suites>"' ;;
    entity-resolution) echo 'sbt -batch "entityResolution/testOnly <suites>"' ;;
    spark) echo 'sbt -batch "sparkAnalytics/testOnly <suites>"' ;;
    contracts) echo 'sbt -batch "contractTests/testOnly <suites>"' ;;
    *) echo "sbt -batch \"$1/testOnly <suites>\"" ;;
  esac
}

# newer <a> <b> : true when file a was modified after file b (find -newer precision).
newer() {
  [ -n "$(find "$1" -prune -newer "$2" 2>/dev/null)" ]
}

# suite_head <xml> : the first <testsuite ...> start tag, on one line.
suite_head() {
  head -c 8192 "$1" 2>/dev/null | tr '\n\r' '  ' | grep -Eo '<testsuite[[:space:]][^>]*>' | head -n 1
}

# report_passes <xml> : true when the <testsuite> element has failures="0" and errors="0".
report_passes() {
  local h
  h="$(suite_head "$1")"
  [ -n "$h" ] || return 1
  printf '%s' "$h" | grep -Eq "[[:space:]]failures=[\"']0[\"']" || return 1
  printf '%s' "$h" | grep -Eq "[[:space:]]errors=[\"']0[\"']" || return 1
  return 0
}

# report_after <xml> <since> : true when the timestamp attribute sbt wrote inside the
# report (local time, no zone; optional fractional seconds) is strictly later than
# <since> (YYYYMMDDhhmmss in local time). Unreadable or zoned timestamps: false.
report_after() {
  local h ts
  is14 "$2" || return 1
  h="$(suite_head "$1")"
  [ -n "$h" ] || return 1
  ts="$(printf '%s' "$h" | sed -n -E "s/.*[[:space:]]timestamp=[\"']([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\.[0-9]+)?[\"'].*/\1\2\3\4\5\6/p")"
  is14 "$ts" || return 1
  [ "$ts" -gt "$2" ]
}

COVERAGE_MSG=""
ALL_COVERED=1
if [ "$EVENT" = "SubagentStop" ]; then
  PROJECTS=""
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    p="$(project_of "$f")"
    case " $PROJECTS " in *" $p "*) ;; *) PROJECTS="$PROJECTS $p" ;; esac
  done <<EOF
$CHANGED
EOF
  for p in $PROJECTS; do
    case "$p" in
      FULL)
        ALL_COVERED=0
        COVERAGE_MSG="$COVERAGE_MSG
  - build.sbt, project/**, .scalafmt.conf, .jvmopts or .sbtopts changed: no shortcut; the full marker is required. Run: sbt check"
        continue ;;
      UNKNOWN)
        ALL_COVERED=0
        COVERAGE_MSG="$COVERAGE_MSG
  - a changed file is in a project without a known test-report folder: the full marker is required. Run: sbt check"
        continue ;;
    esac
    # newest changed file of this project, and the changed test suites (for the hint)
    pnewest=""; suites=""
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      [ "$(project_of "$f")" = "$p" ] || continue
      if [ -z "$pnewest" ] || newer "$f" "$pnewest"; then pnewest="$f"; fi
      case "$f" in */src/test/*.scala|tests/*.scala)
        s="${f##*/}"; s="${s%.scala}"
        case " $suites " in *" $s "*) ;; *) suites="$suites $s" ;; esac ;;
      esac
    done <<EOF
$CHANGED
EOF
    reason=""
    since="$(local_mtime "$pnewest")"
    if ! is14 "$since"; then
      reason="$reason cannot render the local mtime of $pnewest, so sbt report timestamps cannot be compared;"
    fi
    dirs="$(report_dirs "$p")"
    while IFS= read -r d; do
      [ -z "$d" ] && continue
      if [ ! -d "$d" ]; then
        reason="$reason no test-report folder $d;"
        continue
      fi
      fresh="$(find "$d" -maxdepth 1 -type f -name 'TEST-*.xml' -newer "$pnewest" 2>/dev/null)"
      if [ -z "$fresh" ]; then
        reason="$reason no TEST-*.xml in $d is newer than $pnewest (stale or missing);"
        continue
      fi
      while IFS= read -r r; do
        [ -z "$r" ] && continue
        if ! report_passes "$r"; then
          reason="$reason failing or unreadable report $r;"
        elif ! report_after "$r" "$since"; then
          reason="$reason report $r has no sbt timestamp later than $pnewest (touched, copied or stale report: its file time is new but sbt did not write it after the change);"
        fi
      done <<EOF
$fresh
EOF
    done <<EOF
$dirs
EOF
    if [ -n "$reason" ]; then
      ALL_COVERED=0
      COVERAGE_MSG="$COVERAGE_MSG
  - $p:$reason
    Run: $(sbt_cmd "$p")   (the suites you added or changed and their direct users${suites:+; changed suites:$suites}), or sbt check"
    fi
  done
  if [ "$ALL_COVERED" = 1 ]; then
    echo "verify-tests-ran.sh: every changed project is covered by fresh, passing sbt test reports; the full sbt check is still required before review and before the phase ends." >&2
    exit 0
  fi
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
  echo "Changed source files:$(printf '%s' "$CHANGED" | grep -v '^$' | head -n 15 | sed 's/^/ /' | tr '\n' ' ')"
  if [ "$EVENT" = "SubagentStop" ]; then
    echo "A subagent may stop after fresh, passing sbt test reports of every changed project, or after a passing sbt check. Not covered:$COVERAGE_MSG"
  else
    echo "The main session (Stop) needs the full marker: one passing sbt check after the last change."
  fi
  if [ -f build.sbt ]; then
    echo "Run: sbt check   (it runs formatting checks, compilation, tests, and markTestRun, which refreshes $MARKER only when tests pass)."
  else
    echo "Run: sbt check   -- build.sbt does not exist yet, so tests cannot run; finish with STATUS: BLOCKED-ON and BLOCKED-ON OWNER: build-engineer."
  fi
  echo "If tests fail and the defect is outside your ownership, finish with STATUS: BLOCKED-ON or STATUS: FAILED and the evidence. Never write $MARKER or any test report yourself."
} >&2
exit 2
