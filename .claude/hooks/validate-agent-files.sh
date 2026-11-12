#!/usr/bin/env bash
# ScholarChain agent-system validator (constitutional artifact; owner: factory-engineer).
#
# Usage:
#   .claude/hooks/validate-agent-files.sh          # standalone: validate everything
#   .claude/hooks/validate-agent-files.sh --hook   # PostToolUse: validate only when a
#                                                  # constitutional file was written
# Checks:
#   1. Agent frontmatter: name, description, explicit non-empty tools; name == file stem.
#   2. Unique agent names and unique routing descriptions; every required agent present.
#   3. Reviewer fence: reviewer/auditor/critic/verifier agents have no Write, Edit,
#      MultiEdit, NotebookEdit, or Bash. deploy-engineer has no Write/Edit/NotebookEdit.
#      dependency-updater has no Write.
#   4. Roster parity: every agent file appears in the docs/agents.md ownership table and
#      every agent in that table has a file. Every writing agent appears in the guard's
#      ownership map.
#   5. Report contract: every agent file names all 14 standard report fields.
#   6. Commands: required commands exist, each has a description, names a responsible
#      agent that exists, and has a Gates section.
#   7. JSON syntax of .claude/settings.json and .mcp.json.
#   8. Hooks: every hook referenced by settings.json exists and is executable; every
#      script in .claude/hooks is referenced; bash -n passes on every hook.
# Exit 0 when all checks pass, 1 (standalone) or 2 (hook mode) on any failure.
# Must stay compatible with macOS /bin/bash 3.2.

set -u

MODE="standalone"
[ "${1:-}" = "--hook" ] && MODE="hook"

ROOT="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$ROOT" ]; then
  ROOT="$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd)"
fi
ROOT="${ROOT%/}"
cd "$ROOT" || { echo "validate-agent-files.sh: cannot cd to $ROOT" >&2; exit 1; }

if [ "$MODE" = "hook" ]; then
  INPUT="$(cat)"
  if command -v jq >/dev/null 2>&1; then
    FILE="$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)"
  else
    FILE="$(printf '%s' "$INPUT" | python3 -I -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))
except Exception: pass' 2>/dev/null)"
  fi
  REL="${FILE#"$ROOT"/}"
  case "$REL" in
    CLAUDE.md|docs/agents.md|.mcp.json|.claude/agents/*|.claude/hooks/*|.claude/commands/*|.claude/settings.json) ;;
    *) exit 0 ;;
  esac
  exec 1>&2   # hook feedback must reach the agent on stderr
fi

REQUIRED_AGENTS="factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater"
REQUIRED_COMMANDS="deploy rollback incident run-pipeline experiment"
REPORT_FIELDS="STATUS OWNER WORK_ORDER FILES_CREATED FILES_MODIFIED COMMANDS_RUN TEST_RESULTS VALIDATION_RESULTS ASSUMPTIONS RISKS MANUAL_ACTION_REQUIRED BLOCKED-ON_OWNER BLOCKED-ON_EVIDENCE NEXT_GATE"

FAILS=0
PASSES=0
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
pass() { PASSES=$((PASSES + 1)); [ "${VERBOSE:-0}" = "1" ] && echo "ok:   $*"; return 0; }

# frontmatter <file> <key> : value of a single-line frontmatter key
frontmatter() {
  awk -v key="$2" '
    NR == 1 { if ($0 != "---") exit; infm = 1; next }
    infm && $0 == "---" { exit }
    infm {
      k = $0; sub(/:.*/, "", k)
      if (k == key) { v = $0; sub(/^[^:]*:[ \t]*/, "", v); print v; exit }
    }' "$1"
}

json_ok() {
  if command -v jq >/dev/null 2>&1; then jq empty "$1" >/dev/null 2>&1
  else python3 -I -m json.tool "$1" >/dev/null 2>&1
  fi
}

# ---------------------------------------------------------------- 1-3, 5 agents
NAMES=""
DESCS_FILE="$(mktemp "${TMPDIR:-/tmp}/sc-validate.XXXXXX")"
trap 'rm -f "$DESCS_FILE"' EXIT

AGENT_FILES="$(ls .claude/agents/*.md 2>/dev/null)"
[ -z "$AGENT_FILES" ] && fail "no agent files under .claude/agents/"

for f in $AGENT_FILES; do
  stem="$(basename "$f" .md)"
  name="$(frontmatter "$f" name)"
  desc="$(frontmatter "$f" description)"
  tools="$(frontmatter "$f" tools)"
  if [ -z "$name" ]; then fail "$f: missing frontmatter name"; else pass "$f name"; fi
  [ -n "$name" ] && [ "$name" != "$stem" ] && fail "$f: name '$name' does not match file stem '$stem'"
  if [ -z "$desc" ]; then fail "$f: missing frontmatter description"; else pass "$f description"; printf '%s\n' "$desc" >>"$DESCS_FILE"; fi
  if [ -z "$tools" ]; then fail "$f: missing or empty explicit tools field (inheriting all tools is forbidden)"; else pass "$f tools"; fi
  NAMES="$NAMES $name"

  tl=",$(printf '%s' "$tools" | tr -d ' '),"
  case "$name" in
    *reviewer*|*auditor*|*critic*|*verifier*)
      for t in Write Edit MultiEdit NotebookEdit Bash; do
        case "$tl" in *",$t,"*) fail "$f: reviewer-class agent has write-capable tool $t" ;; esac
      done ;;
  esac
  if [ "$name" = "deploy-engineer" ]; then
    for t in Write Edit MultiEdit NotebookEdit; do
      case "$tl" in *",$t,"*) fail "$f: deploy-engineer may not author files (has $t)" ;; esac
    done
  fi
  if [ "$name" = "dependency-updater" ]; then
    case "$tl" in *",Write,"*) fail "$f: dependency-updater may only edit version values (has Write)" ;; esac
  fi

  for fld in $REPORT_FIELDS; do
    label="$(printf '%s' "$fld" | tr '_' ' ')"
    grep -q "$label:" "$f" || fail "$f: report contract field '$label:' missing"
  done

  # Writing agents must be represented in the guard ownership map.
  case "$tl" in
    *",Write,"*|*",Edit,"*)
      if [ "$name" != "dependency-updater" ] && ! grep -q "echo \"$name\"" .claude/hooks/guard-dangerous.sh 2>/dev/null; then
        fail "$f: writing agent '$name' has no territory in guard-dangerous.sh owner_of()"
      fi ;;
  esac
done

DUPS="$(printf '%s\n' $NAMES | sort | uniq -d)"
[ -n "$DUPS" ] && fail "duplicate agent names: $DUPS"
DDUPS="$(sort "$DESCS_FILE" | uniq -d)"
[ -n "$DDUPS" ] && fail "duplicate routing descriptions: $DDUPS"

for a in $REQUIRED_AGENTS; do
  [ -f ".claude/agents/$a.md" ] || fail "required agent file .claude/agents/$a.md is missing"
done

# ---------------------------------------------------------------- 4 roster parity
if [ -f docs/agents.md ]; then
  TABLE_AGENTS="$(awk '/^## Artifact ownership/{on=1;next} /^## /{on=0} on' docs/agents.md | grep -Eo '^\| `[a-z-]+`' | tr -d '|` ' | sort -u)"
  for n in $NAMES; do
    printf '%s\n' "$TABLE_AGENTS" | grep -qx "$n" || fail "agent '$n' has no row in docs/agents.md '## Artifact ownership' table (orphan)"
  done
  for n in $TABLE_AGENTS; do
    [ -f ".claude/agents/$n.md" ] || fail "docs/agents.md lists '$n' but .claude/agents/$n.md does not exist (orphan)"
  done
else
  fail "docs/agents.md is missing"
fi
[ -f CLAUDE.md ] && { grep -q '^@docs/agents.md' CLAUDE.md || fail "CLAUDE.md does not import @docs/agents.md"; }
[ -f CLAUDE.md ] || fail "CLAUDE.md is missing"

# ---------------------------------------------------------------- 6 commands
for c in $REQUIRED_COMMANDS; do
  f=".claude/commands/$c.md"
  if [ ! -f "$f" ]; then fail "required command $f is missing"; continue; fi
  [ -n "$(frontmatter "$f" description)" ] || fail "$f: missing description"
  agent="$(grep -Eo '^\*\*Responsible agent:\*\* `[a-z-]+`' "$f" | grep -Eo '`[a-z-]+`' | tr -d '`' | head -n 1)"
  if [ -z "$agent" ]; then fail "$f: no '**Responsible agent:** \`name\`' line"
  elif [ ! -f ".claude/agents/$agent.md" ]; then fail "$f: responsible agent '$agent' does not exist"
  fi
  grep -q '^## Gates' "$f" || fail "$f: no '## Gates' section"
done
CMD_DUPS="$(ls .claude/commands/ 2>/dev/null | sed 's/\.md$//' | sort | uniq -d)"
[ -n "$CMD_DUPS" ] && fail "duplicate command names: $CMD_DUPS"

# ---------------------------------------------------------------- 7 JSON
for j in .claude/settings.json .mcp.json; do
  if [ ! -f "$j" ]; then fail "$j is missing"
  elif json_ok "$j"; then pass "$j parses"
  else fail "$j is not valid JSON"
  fi
done

# ---------------------------------------------------------------- 8 hooks
if [ -f .claude/settings.json ] && json_ok .claude/settings.json; then
  if command -v jq >/dev/null 2>&1; then
    REFS="$(jq -r '.. | objects | select(has("command")) | .command' .claude/settings.json | grep -Eo '\.claude/hooks/[A-Za-z0-9_.-]+' | sort -u)"
  else
    REFS="$(grep -Eo '\.claude/hooks/[A-Za-z0-9_.-]+' .claude/settings.json | sort -u)"
  fi
  for r in $REFS; do
    if [ ! -f "$r" ]; then fail "settings.json references missing hook $r"
    elif [ ! -x "$r" ]; then fail "hook $r is not executable (chmod +x by factory-engineer)"
    else pass "$r exists and is executable"
    fi
  done
  for h in .claude/hooks/*; do
    [ -e "$h" ] || continue
    printf '%s\n' "$REFS" | grep -qx "$h" || fail "hook script $h is not referenced by settings.json (orphan)"
  done
fi
for h in .claude/hooks/*.sh; do
  [ -e "$h" ] || continue
  if bash -n "$h" 2>/dev/null; then pass "$h bash -n"; else fail "$h fails bash -n"; fi
done

echo "validate-agent-files.sh: $PASSES checks passed, $FAILS failed."
if [ "$FAILS" -gt 0 ]; then
  [ "$MODE" = "hook" ] && { echo "Agent-system validation failed after editing $REL. Repair before reporting COMPLETE." >&2; exit 2; }
  exit 1
fi
exit 0
