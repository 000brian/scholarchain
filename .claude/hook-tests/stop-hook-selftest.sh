#!/usr/bin/env bash
# ScholarChain stop-hook self-test (constitutional artifact; owner: factory-engineer).
#
# Builds throwaway git repositories under /tmp/claude-stoptest.* with fake modules,
# changed files and fake sbt TEST-*.xml reports (passing, failing, stale, unreadable),
# points CLAUDE_PROJECT_DIR at each one, and feeds Stop and SubagentStop events to
# .claude/hooks/verify-tests-ran.sh. Checks: Stop needs the full marker; SubagentStop
# accepts fresh passing per-project reports; failing, stale or unreadable reports block;
# core needs both its Scala 3 and Scala 2.13 reports; build files and formatter or JVM
# options need the full marker; the BLOCKED-ON / FAILED escape; deletions and target/
# are ignored. Amendment 02 repair: a report whose file time is fresh but whose sbt
# timestamp attribute is not later than the change (touched, missing, zoned, same
# second) does not cover it; a read-only subagent (fixture .claude/agents files) is not
# gated on SubagentStop, while writing, unknown, empty or malformed agent types are.
# Prints one PASS/FAIL line per case and a total; exits non-zero on any
# failure. Writes nothing outside /tmp/claude-stoptest.* and removes it at the end.
#
# Run: bash .claude/hook-tests/stop-hook-selftest.sh   (needs git and jq; bash 3.2)

set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOOK="$ROOT/.claude/hooks/verify-tests-ran.sh"
command -v jq >/dev/null 2>&1 || { echo "FAIL: jq is required"; exit 1; }
command -v git >/dev/null 2>&1 || { echo "FAIL: git is required"; exit 1; }
[ -f "$HOOK" ] || { echo "FAIL: $HOOK not found"; exit 1; }

BASE="$(mktemp -d /tmp/claude-stoptest.XXXXXX)" || { echo "FAIL: cannot create a temp dir"; exit 1; }
case "$BASE" in
  /tmp/claude-stoptest.*) ;;
  *) echo "FAIL: unexpected temp dir $BASE"; exit 1 ;;
esac
cleanup() { case "$BASE" in /tmp/claude-stoptest.*) rm -rf "$BASE" ;; esac; }
trap cleanup EXIT

# Fixed timestamps (touch -t [[CC]YY]MMDDhhmm[.SS]) so ordering never depends on timing.
T_BASE=202601010000.00     # committed baseline
T_STALE=202601010600.00    # stale reports (older than the change)
T_SRC=202601020000.00      # the source change
T_FRESH=202601030000.00    # fresh reports (newer than the change)
T_LATE=202601040000.00     # a second change after the fresh reports
T_MARKER=202601050000.00   # full marker newer than everything

PASSES=0
FAILS=0
R=""

# agentfile <name> <content with \n escapes> : write .claude/agents/<name>.md in the repo.
agentfile() {
  mkdir -p "$(dirname "$R/.claude/agents/$1.md")"
  printf '%b' "$2" >"$R/.claude/agents/$1.md"
}

newrepo() {
  R="$(mktemp -d "$BASE/repo.XXXXXX")"
  mkdir -p "$R/modules/core/src/main/scala" "$R/modules/services/src/main/scala" \
           "$R/modules/rag/src/main/scala" "$R/tests/contracts/src/test/scala" \
           "$R/project" "$R/.claude"
  printf 'lazy val root = project\n' >"$R/build.sbt"
  printf 'target/\n' >"$R/.gitignore"
  printf 'object Base\n' >"$R/modules/services/src/main/scala/Base.scala"
  printf 'object Gone\n' >"$R/modules/services/src/main/scala/Gone.scala"
  printf 'version = "3.11.5"\n' >"$R/.scalafmt.conf"
  printf -- '-Xmx2g\n' >"$R/.jvmopts"
  printf -- '-J-Xss4m\n' >"$R/.sbtopts"
  printf 'addSbtPlugin("x" %% "y" %% "1")\n' >"$R/project/plugins.sbt"
  # Agent files for decision A (read-only exemption on SubagentStop).
  agentfile code-reviewer  '---\nname: code-reviewer\ndescription: read-only reviewer\ntools: Read, Grep, Glob\n---\n\n# Code Reviewer\n'
  agentfile service-engineer '---\nname: service-engineer\ndescription: writer\ntools: Read, Grep, Glob, Write, Edit, Bash\n---\n'
  agentfile bash-reader    '---\nname: bash-reader\ntools: Read, Bash(git status)\n---\n'
  agentfile no-tools       '---\nname: no-tools\ndescription: no tools field\n---\n'
  agentfile list-tools     '---\nname: list-tools\ntools:\n  - Read\n  - Write\n---\n'
  agentfile star-tools     '---\nname: star-tools\ntools: *\n---\n'
  agentfile spaced-tools   '---\nname: spaced-tools\ntools: Read Grep Write\n---\n'
  agentfile alias-reviewer '---\nname: code-reviewer\ntools: Read, Grep, Glob\n---\n'
  agentfile sub/x          '---\nname: sub/x\ntools: Read\n---\n'
  agentfile ""             '---\nname:\ntools: Read\n---\n'
  agentfile open-front     '---\nname: open-front\ntools: Read\n\nbody line\n'
  (
    cd "$R" || exit 1
    git -c init.defaultBranch=main init -q . && git config user.email selftest@example.invalid && git config user.name selftest \
      && git config commit.gpgsign false && git add -A && git commit -q -m base
  ) || { echo "FAIL: git setup in $R"; exit 1; }
  find "$R" -path "$R/.git" -prune -o -type f -exec touch -t "$T_BASE" {} +
}

# change <relative path> [timestamp] : create or modify a source file at a fixed time.
change() {
  mkdir -p "$(dirname "$R/$1")"
  printf '// changed\n' >>"$R/$1"
  touch -t "${2:-$T_SRC}" "$R/$1"
}

# tsattr <touch time [[CC]YY]MMDDhhmm.SS, 4-digit year> : sbt's local timestamp text.
tsattr() {
  local t="$1"
  printf '%s-%s-%sT%s:%s:%s' "${t:0:4}" "${t:4:2}" "${t:6:2}" "${t:8:2}" "${t:10:2}" "${t:13:2}"
}

# report <dir under repo> <name> <pass|fail|error|garbage> <file time> [sbt time]
#   sbt time: the timestamp attribute sbt writes inside the report (default: the file
#   time, as when sbt writes the report); "none" omits it; "zoned:<time>" adds a Z.
report() {
  local d="$R/$1" f ts attr
  mkdir -p "$d"
  f="$d/TEST-$2.xml"
  ts="${5:-$4}"
  case "$ts" in
    none) attr="" ;;
    zoned:*) attr=" timestamp=\"$(tsattr "${ts#zoned:}")Z\"" ;;
    *) attr=" timestamp=\"$(tsattr "$ts")\"" ;;
  esac
  case "$3" in
    pass)    printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite hostname="h" name="%s" tests="3" errors="0" failures="0" skipped="0" time="0.1"%s>\n</testsuite>\n' "$2" "$attr" >"$f" ;;
    fail)    printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite hostname="h" name="%s" tests="3" errors="0" failures="1" skipped="0" time="0.1"%s>\n</testsuite>\n' "$2" "$attr" >"$f" ;;
    error)   printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite hostname="h" name="%s" tests="3" errors="2" failures="0" skipped="0" time="0.1"%s>\n</testsuite>\n' "$2" "$attr" >"$f" ;;
    garbage) printf 'not a report\n' >"$f" ;;
  esac
  touch -t "$4" "$f"
}

marker() { printf 'ok\n' >"$R/.claude/.last-test-run"; touch -t "$T_MARKER" "$R/.claude/.last-test-run"; }

# run_hook <Stop|SubagentStop|none> <last message> [stop_hook_active] [agent_type|none]
#   : sets RES and CODE. agent_type "none" (the default) omits the field; "" sends "".
run_hook() {
  local ev
  ev="$(jq -n --arg e "$1" --arg m "$2" --argjson a "${3:-false}" --arg t "${4-none}" \
    '(if $e == "none" then {} else {hook_event_name: $e} end) + {stop_hook_active: $a, last_assistant_message: $m}
     + (if $t == "none" then {} else {agent_type: $t} end)')"
  RES="$(printf '%s' "$ev" | CLAUDE_PROJECT_DIR="$R" bash "$HOOK" 2>&1)"
  CODE=$?
}

# expect <ALLOW|BLOCK> <case-name> <Stop|SubagentStop|none> [message fragment] [last message] [active] [agent_type]
expect() {
  local want="$1" name="$2" ev="$3" frag="${4:-}" msg="${5:-STATUS: COMPLETE}" got
  run_hook "$ev" "$msg" "${6:-false}" "${7-none}"
  case "$CODE" in 0) got="ALLOW" ;; 2) got="BLOCK" ;; *) got="OTHER(exit $CODE)" ;; esac
  if [ "$got" = "$want" ] && { [ -z "$frag" ] || printf '%s' "$RES" | grep -qF -- "$frag"; }; then
    PASSES=$((PASSES + 1))
    printf 'PASS  %-5s %s\n' "$want" "$name"
  else
    FAILS=$((FAILS + 1))
    printf 'FAIL  %-5s %s  (got %s%s): %s\n' "$want" "$name" "$got" "${frag:+, needed message '$frag'}" "$(printf '%s' "$RES" | head -n 2 | tr '\n' ' ')"
  fi
}

SVC_SRC=modules/services/src/main/scala/A.scala
SVC_REP=modules/services/target/test-reports
CORE_SRC=modules/core/src/main/scala/Id.scala
CORE3_REP=modules/core/target/jvm-3/test-reports
CORE2_REP=modules/core/target/jvm-2.13/test-reports
RAG_REP=modules/rag/target/test-reports
CON_REP=tests/contracts/target/test-reports

echo "== clean tree =="
newrepo
expect ALLOW s01-clean-stop Stop
expect ALLOW s01-clean-subagent SubagentStop

echo "== services change =="
newrepo; change "$SVC_SRC"
expect BLOCK s02-no-reports-subagent SubagentStop 'sbt -batch "services/testOnly'
expect BLOCK s02-no-reports-stop Stop "needs the full marker"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"
expect ALLOW s03-fresh-pass-subagent SubagentStop
expect BLOCK s03-fresh-pass-stop-needs-marker Stop "needs the full marker"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"; report "$SVC_REP" a.BSuite fail "$T_FRESH"
expect BLOCK s04-fresh-failing-report SubagentStop "failing or unreadable report"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite error "$T_FRESH"
expect BLOCK s05-fresh-error-report SubagentStop "failing or unreadable report"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_STALE"
expect BLOCK s06-stale-report SubagentStop "stale or missing"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite garbage "$T_FRESH"
expect BLOCK s07-unreadable-report SubagentStop "failing or unreadable report"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"; change modules/services/src/main/scala/B.scala "$T_LATE"
expect BLOCK s08-change-after-reports SubagentStop "stale or missing"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_STALE"; report "$SVC_REP" a.ASuite2 fail "$T_STALE"; report "$SVC_REP" a.CSuite pass "$T_FRESH"
expect ALLOW s09-old-failure-superseded SubagentStop

echo "== core needs both builds =="
newrepo; change "$CORE_SRC"; report "$CORE3_REP" c.IdSuite pass "$T_FRESH"
expect BLOCK s10-core-scala3-only SubagentStop "jvm-2.13"
newrepo; change "$CORE_SRC"; report "$CORE2_REP" c.IdSuite pass "$T_FRESH"
expect BLOCK s11-core-scala213-only SubagentStop "jvm-3"
newrepo; change "$CORE_SRC"; report "$CORE3_REP" c.IdSuite pass "$T_FRESH"; report "$CORE2_REP" c.IdSuite pass "$T_FRESH"
expect ALLOW s12-core-both-builds SubagentStop
expect BLOCK s12-core-both-builds-stop Stop "needs the full marker"
newrepo; change "$CORE_SRC"
expect BLOCK s13-core-message SubagentStop 'core2_13/testOnly'

echo "== build files need the full marker =="
for f in build.sbt project/plugins.sbt .scalafmt.conf .jvmopts .sbtopts; do
  newrepo; change "$f"
  report "$SVC_REP" a.ASuite pass "$T_FRESH"; report "$CORE3_REP" c.S pass "$T_FRESH"; report "$CORE2_REP" c.S pass "$T_FRESH"
  report "$RAG_REP" r.S pass "$T_FRESH"; report "$CON_REP" k.S pass "$T_FRESH"
  expect BLOCK "s14-full-marker-needed:$f" SubagentStop "full marker is required"
  marker
  expect ALLOW "s15-full-marker-present:$f-subagent" SubagentStop
  expect ALLOW "s15-full-marker-present:$f-stop" Stop
done

echo "== mapping of prompts/** and tests/** =="
newrepo; change prompts/runtime/p.json; report "$RAG_REP" r.PromptSuite pass "$T_FRESH"
expect ALLOW s16-prompts-covered-by-rag SubagentStop
newrepo; change prompts/runtime/p.json; report "$SVC_REP" a.ASuite pass "$T_FRESH"
expect BLOCK s17-prompts-not-covered-by-services SubagentStop 'sbt -batch "rag/testOnly'
newrepo; change tests/integration/src/test/scala/ItSuite.scala; report "$CON_REP" k.ItSuite pass "$T_FRESH"
expect ALLOW s18-tests-covered-by-contracts SubagentStop
newrepo; change tests/integration/src/test/scala/ItSuite.scala
expect BLOCK s19-tests-no-contracts-reports SubagentStop 'contractTests/testOnly'

echo "== several projects, unknown projects =="
newrepo; change "$SVC_SRC"; change "$CORE_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"
expect BLOCK s20-one-of-two-covered SubagentStop "core:"
newrepo; change modules/newmod/src/main/scala/N.scala; report "$SVC_REP" a.ASuite pass "$T_FRESH"
expect BLOCK s21-unknown-module SubagentStop "without a known test-report folder"

echo "== escapes and ignores =="
newrepo; change "$SVC_SRC"
expect ALLOW s22-blocked-on-escape-subagent SubagentStop "" "STATUS: BLOCKED-ON"
expect ALLOW s22-blocked-on-escape-stop Stop "" "STATUS: BLOCKED-ON"
expect ALLOW s23-failed-escape SubagentStop "" "STATUS: FAILED"
# s24: a staged deletion whose file still exists on disk (untracked, hidden by
# .git/info/exclude), so git status lists only "D " and `[ -f ]` alone would count it:
# the case fails if the hook's deletion skip is removed.
newrepo; ( cd "$R" && git rm -q --cached modules/services/src/main/scala/Gone.scala \
  && printf 'modules/services/src/main/scala/Gone.scala\n' >>.git/info/exclude )
touch -t "$T_SRC" "$R/modules/services/src/main/scala/Gone.scala"
expect ALLOW s24-deletion-only-subagent SubagentStop
expect ALLOW s24-deletion-only-stop Stop
# s25: a force-added file under target/ (the fixture's .gitignore hides target/), so git
# status lists it: the case fails if the hook's target/ skip is removed.
newrepo; change modules/services/target/scala-3/Generated.scala
( cd "$R" && git add -f modules/services/target/scala-3/Generated.scala )
expect ALLOW s25-target-ignored Stop
expect ALLOW s25-target-ignored-subagent SubagentStop
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"
expect BLOCK s26-missing-event-name-is-stop none "needs the full marker"
newrepo; change "$SVC_SRC"; marker
expect ALLOW s27-marker-newer-stop Stop
newrepo; ( cd "$R" && git rm -q build.sbt ); change "$SVC_SRC"
expect ALLOW s28-no-build-sbt-second-attempt SubagentStop "build.sbt does not exist" "STATUS: COMPLETE" true
expect BLOCK s28-no-build-sbt-first-attempt SubagentStop "" "STATUS: COMPLETE" false

echo "== repair, finding 2: the sbt timestamp inside each fresh report must be later than the change =="
TS_MSG="has no sbt timestamp later than"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s29-touched-old-report SubagentStop "$TS_MSG"
newrepo; change "$CORE_SRC"; report "$CORE3_REP" c.IdSuite pass "$T_FRESH" "$T_STALE"; report "$CORE2_REP" c.IdSuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s30-core-both-builds-touched SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" none
expect BLOCK s31-report-without-timestamp SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "$T_SRC"
expect BLOCK s32-timestamp-same-second-as-change SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH" "zoned:$T_FRESH"
expect BLOCK s33-timestamp-with-zone SubagentStop "$TS_MSG"
newrepo; change "$SVC_SRC"; report "$SVC_REP" a.ASuite pass "$T_FRESH"; report "$SVC_REP" a.BSuite pass "$T_FRESH" "$T_STALE"
expect BLOCK s34-one-touched-among-fresh SubagentStop "TEST-a.BSuite.xml $TS_MSG"

echo "== repair, decision A: read-only subagents are not gated on SubagentStop =="
newrepo; change "$SVC_SRC"
expect ALLOW s35-reviewer-exempt-while-services-untested SubagentStop "cannot have changed source" "STATUS: COMPLETE" false code-reviewer
expect BLOCK s36-reviewer-on-stop-event-not-exempt Stop "needs the full marker" "STATUS: COMPLETE" false code-reviewer
expect BLOCK s37-writing-agent-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false service-engineer
expect BLOCK s38-unknown-agent-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false general-purpose
expect BLOCK s39-empty-agent-type-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false ""
expect BLOCK s40-bash-form-tool-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false bash-reader
expect BLOCK s41-missing-tools-field-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false no-tools
expect BLOCK s42-multiline-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false list-tools
expect BLOCK s43-wildcard-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false star-tools
expect BLOCK s44-space-separated-tools-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false spaced-tools
expect BLOCK s45-name-mismatch-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false alias-reviewer
expect BLOCK s46-agent-type-with-slash-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false sub/x
expect BLOCK s47-unclosed-frontmatter-not-exempt SubagentStop "Not covered" "STATUS: COMPLETE" false open-front
expect BLOCK s48-no-agent-type-not-exempt SubagentStop "Not covered"

echo "TOTAL: $PASSES passed, $FAILS failed."
[ "$FAILS" -eq 0 ]
