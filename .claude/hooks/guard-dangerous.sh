#!/usr/bin/env bash
# ScholarChain PreToolUse guard (constitutional artifact; owner: factory-engineer).
#
# Executable policy, not a suggestion. Reads the PreToolUse JSON event on stdin.
#   exit 0, no output      -> no objection (normal permission rules still apply)
#   exit 0, JSON "ask"     -> human must confirm the call
#   exit 2, stderr message -> call is blocked; the message is returned to the agent
#
# Sections:
#   A. Bash command guard: Terraform apply/destroy and state mutation, force push and
#      history rewrite, broad recursive deletes, disk/device writes, secret reads,
#      destructive or scale-changing AWS calls (S3, DynamoDB, Neptune, S3 Vectors, ECR,
#      KMS, EMR, ECS), destructive database/graph statements, remote-code piping.
#      Amendment 02 adds a command-position parser (quote-aware, including $'...' and
#      $"..."; unwraps `bash -c` (also after `--` and other options), `env -S`,
#      `$(...)`, backticks, eval, pipes into a shell, xargs, exec -a and find -exec) for:
#      in-place editing (sed -i, perl -i, ed/ex/vi), watchers (tail -f/-F/--follow,
#      watch, less +F), and shell write targets (redirections, tee, cp/mv/install/
#      rsync/ln destinations, touch), which must lie in the caller's own territory or
#      under /tmp/claude-* or /private/tmp/claude-*. Shell writes to the test marker,
#      sbt test reports, constitutional files, evidence, memory and local settings
#      are blocked. $'...' text with backslash escapes is blocked where it names or
#      feeds a checked command or is run as code.
#      Repair 02: commands and wrappers are compared by basename (/usr/bin/xargs =
#      xargs); wrapper options are read with getopt rules (clusters such as -tn 1, long
#      options such as --max-args 1); after a wrapper, every later word that names a
#      checked command is checked again from there (fail closed); under xargs only
#      read-only commands may run; sort -o/--output, uniq's output operand and sed
#      scripts with w, W or e are checked or blocked; touch never sets file times;
#      sbt ~<task>, docker/kubectl logs -f, journalctl -f and sleep infinity are
#      watchers.
#      Repair 03 (finding B): git never writes a file through an option: --output and
#      its abbreviations on any subcommand, git format-patch and git archive -o are
#      blocked for every agent and the main session ("read-only git only; print to
#      stdout").
#      Repair 04 (final round): a git word that uses shell brace expansion ({a,b} or
#      {a..b}) is blocked for every agent and the main session; the bare wrapper
#      options time -o, env -C and sudo -D / -R are caught (before, only clusters such
#      as -po were).
#      Every block rule runs before any ask is returned (asks are deferred).
#   B. File-path guard for Read/Grep/Write/Edit/MultiEdit/NotebookEdit: secret paths,
#      memory and local settings, sbt test reports, release records, the generated test
#      marker, human-owned evidence, constitutional files, and the one-writer ownership
#      map in docs/agents.md (subagents only).
#
# Must stay compatible with macOS /bin/bash 3.2 (no associative arrays, no ${x,,},
# no mapfile).

set -u
set -f   # never glob-expand tokens taken from tool input
IFS=$' \t\n'
NL=$'\n'
TAB=$'\t'

INPUT="$(cat)"

block() {
  {
    printf 'BLOCKED by .claude/hooks/guard-dangerous.sh: %s\n' "$1"
    [ -n "${2:-}" ] && printf 'What to do instead: %s\n' "$2"
    printf 'Policy: CLAUDE.md and docs/agents.md. Do not try to bypass this guard; report BLOCKED-ON with this message if the work genuinely requires it.\n'
  } >&2
  exit 2
}

ask() {
  local reason="$1"
  reason="${reason//\\/\\\\}"
  reason="${reason//\"/\\\"}"
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"guard-dangerous.sh: %s"}}\n' "$reason"
  exit 0
}

# defer_ask <reason> : Bash guard only. Remember the first ask; it is returned only after
# every block rule has run, so an earlier ask can never hide a later block.
PENDING_ASK=""
defer_ask() {
  [ -z "$PENDING_ASK" ] && PENDING_ASK="$1"
  return 0
}

# jget <dotted.path> : print a string field from the event, or nothing.
if command -v jq >/dev/null 2>&1; then
  jget() { printf '%s' "$INPUT" | jq -r ".$1 // empty" 2>/dev/null; }
elif command -v python3 >/dev/null 2>&1; then
  jget() {
    printf '%s' "$INPUT" | python3 -I -c '
import json, sys
path = sys.argv[1].split(".")
try:
    v = json.load(sys.stdin)
    for k in path:
        v = v.get(k) if isinstance(v, dict) else None
    if isinstance(v, str):
        sys.stdout.write(v)
except Exception:
    pass
' "$1"
  }
else
  block "neither jq nor python3 is available, so the guard cannot parse the tool call (fail-closed)." \
    "Ask the human to make jq or python3 available on PATH."
fi

TOOL="$(jget tool_name)"
AGENT="$(jget agent_type)"          # empty in the main orchestrator session
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(jget cwd)}"
[ -z "$PROJECT_DIR" ] && PROJECT_DIR="$(pwd)"
PROJECT_DIR="${PROJECT_DIR%/}"
CWD="$(jget cwd)"
[ -z "$CWD" ] && CWD="$PROJECT_DIR"
CWD="${CWD%/}"
HOME_DIR="${HOME:-}"
HOME_DIR="${HOME_DIR%/}"

# Dependency-ledger file(s) that dependency-updater may edit (version values only).
# Ratified in Amendment 01. The guard enforces the path only; the value-level rules
# (version values only; never version.scalafmt, which build-engineer bumps together with
# .scalafmt.conf; never image.java-runtime, whose tag and digest build-engineer bumps)
# live in docs/agents.md rule 3 and are enforced by role rules and code review.
# sbt ledgerCheck checks only that version.scalafmt equals the .scalafmt.conf version.
DEPENDENCY_LEDGER_PATHS="project/build.properties"

matches() { printf '%s' "$1" | grep -Eiq -- "$2"; }

MSG_MEMORY="Memory and settings writes are off during runs; put the note in your report."
MSG_EDIT="Use the Edit tool."
MSG_WATCH="No watchers: read with tail -n or grep, and finish the step."
MSG_REPORTS="Run the tests and let sbt write the reports: sbt -batch \"<project>/testOnly <suites>\", or sbt check."
MSG_ANSI="write it plainly, without \$'...' backslash escapes (plain single or double quotes are fine)."
MSG_XARGS="use a literal path: run the command with literal operands inside your own territory or under /private/tmp/claude-*. Under xargs only read-only commands may run (grep, cat, head, wc, ls, echo, printf, stat, du, shasum, basename, dirname, realpath, readlink); never touch test reports or the test marker."
MSG_TOUCH_TIME="Agents never set file times: use touch without -t, -d, -r, -A, --date or --reference, or use the Write and Edit tools."
MSG_SED_SCRIPT="Use the Edit tool to change files; read with sed -n 'N,Mp' or grep, and keep scratch output under /private/tmp/claude-* with a > redirection."
MSG_GIT_WRITE="read the output on stdout (plain git diff, git log, git show), or redirect it with > into your session scratchpad under /private/tmp/claude-* (for example: git diff > /private/tmp/claude-<id>/x.diff)."
MSG_GIT_BRACE="write every git option and argument literally, without braces that hold a comma or .. (for example: git diff --stat, or git diff > /private/tmp/claude-<id>/x.diff). Reflog selectors such as HEAD@{1} or stash@{0} and plain ranges such as HEAD~3..HEAD or @{u}..HEAD are fine; to compare two @{...} selectors, write two words (git log HEAD@{0} ^HEAD@{1}) instead of HEAD@{1}..HEAD@{0}."

# ----------------------------------------------------------------------------------
# Ownership map (mirror of docs/agents.md "Artifact ownership"). Keep in sync.
# ----------------------------------------------------------------------------------
owner_of() {
  case "$1" in
    .claude/.last-test-run) echo "generated:sbt-markTestRun" ;;
    .claude/settings.local.json) echo "human" ;;
    CLAUDE.md|docs/agents.md|.mcp.json|docs/agent-runs/README.md|.claude/*) echo "factory-engineer" ;;
    docs/agent-runs/*) echo "human" ;;
    docs/incidents/*) echo "incident-responder" ;;
    README.md|docs/*|reports/*) echo "documentation-engineer" ;;
    build.sbt|project/*|.scalafmt.conf|.sbt/*|.gitignore|.jvmopts|.sbtopts|docker/*|compose.yml|compose.yaml|docker-compose.yml|docker-compose.yaml|docker-compose.*.yml|docker-compose.*.yaml) echo "build-engineer" ;;
    modules/core/*) echo "domain-engineer" ;;
    datasets/*) echo "dataset-engineer" ;;
    modules/hadoop/*) echo "hadoop-engineer" ;;
    modules/entity-resolution/*) echo "entity-resolution-engineer" ;;
    modules/spark/*) echo "spark-engineer" ;;
    modules/storage/*) echo "storage-engineer" ;;
    modules/rag/*|prompts/*) echo "rag-engineer" ;;
    modules/services/*) echo "service-engineer" ;;
    tests/*) echo "test-engineer" ;;
    infra/*|scripts/*|.github/workflows/*|config/*) echo "infra-engineer" ;;
    releases/*) echo "deploy-engineer" ;;
    experiments/*|load-tests/*) echo "experiment-engineer" ;;
    *) echo "unowned" ;;
  esac
}

is_constitutional() {
  case "$1" in
    .claude/.last-test-run|.claude/settings.local.json) return 1 ;;
    CLAUDE.md|docs/agents.md|.mcp.json|docs/agent-runs/README.md|.claude/*) return 0 ;;
    *) return 1 ;;
  esac
}

# Secret material: .env files (env.example placeholders are allowed), key stores,
# cloud and SSH credentials, Terraform state, secret directories.
is_secret_path() {
  local p="$1" base
  base="${p##*/}"
  case "$base" in
    .env|.envrc|.env.*|*.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|id_rsa*|id_ed25519*|id_ecdsa*|id_dsa*|.netrc|*.tfstate|*.tfstate.*|*credentials*.json) return 0 ;;
  esac
  case "/$p/" in
    */secrets/*|*/.secrets/*|*/.aws/*|*/.ssh/*|*/.gnupg/*|*/.config/gh/*|*/.docker/config.json/*) return 0 ;;
  esac
  return 1
}

# Memory and local settings (Amendment 02, decision 7): anything under $HOME/.claude/,
# any .claude/settings.local.json, any CLAUDE.local.md.
is_memory_path() {
  local p="$1"
  case "$p" in
    '~/.claude'|'~/.claude/'*|'$HOME/.claude'|'$HOME/.claude/'*|'${HOME}/.claude'|'${HOME}/.claude/'*) return 0 ;;
    .claude/settings.local.json|*/.claude/settings.local.json|CLAUDE.local.md|*/CLAUDE.local.md) return 0 ;;
  esac
  if [ -n "$HOME_DIR" ]; then
    case "$p" in
      "$HOME_DIR/.claude"|"$HOME_DIR/.claude/"*) return 0 ;;
    esac
  fi
  return 1
}

# sbt test reports (Amendment 02, decision 3b): the evidence verify-tests-ran.sh reads.
is_test_report_path() {
  case "/$1/" in
    */test-reports/*) return 0 ;;
  esac
  return 1
}

# normpath <path> : collapse '//' and '/./', strip a leading './' and a trailing '/.'.
normpath() {
  local p="$1" dslash='//' slash='/' dotseg='/./'
  while :; do
    case "$p" in *//*) p="${p//$dslash/$slash}" ;; *) break ;; esac
  done
  while :; do
    case "$p" in */./*) p="${p//$dotseg/$slash}" ;; *) break ;; esac
  done
  case "$p" in ./*) p="${p#./}" ;; esac
  case "$p" in */.) p="${p%/.}" ;; esac
  printf '%s' "$p"
}

# ----------------------------------------------------------------------------------
# Command normalizer (awk). Output: the command with every literal (quoted or escaped)
# character that is a shell separator or redirection masked by a control character, so
# that `grep -n '->' f` has no redirection and `grep "a; sed -i" f` has no sed command.
# Code that the shell will run is unwrapped onto separate lines: the code argument of a
# shell's -c option (the first word after -c that is not an option, an option argument
# of -o/-O, a redirection or a file-descriptor number), the argument of env -S /
# --split-string, $(...) and backtick substitutions (also inside double quotes), and
# every quoted string when the command uses eval or source, pipes into a shell (also a
# path-qualified or wrapped one, such as `| /usr/bin/env bash`), or feeds a here-string.
# The quote characters themselves are removed, so 'sed', "sed", $'sed', $"sed" and s\ed
# all read as sed. A $'...' string that holds a backslash escape is not decoded: it is
# marked $SCANSI (and $SCANSICODE when it is run as code) so that the guard can refuse
# it. Placeholders $SCCODE / $SCSUB stand for unwrapped code.
# ----------------------------------------------------------------------------------
NORM_AWK='
BEGIN {
  M[" "] = "\002"; M["\t"] = "\003"; M[">"] = "\004"; M["<"] = "\005"
  M[";"] = "\006"; M["&"] = "\016"; M["|"] = "\017"; M["("] = "\020"
  M[")"] = "\021"; M["\n"] = "\022"; M["`"] = "\023"
  EXTRA = ""; ANSICODE = 0
}
{ SRC = (NR == 1) ? $0 : SRC "\n" $0 }
END {
  ALLCODE = 0
  if (SRC ~ /(^|[^A-Za-z0-9_.\/-])(eval|source)([ \t]|$)/) ALLCODE = 1
  if (SRC ~ /\|([^;&]*[ \t\/])?(ba|z|da|k)?sh([ \t;&|)]|$)/) ALLCODE = 1
  if (SRC ~ /(^|[^A-Za-z0-9_.-])(ba|z|da|k)?sh[ \t]+(-[A-Za-z]+[ \t]+)*<</) ALLCODE = 1
  if (index(SRC, "<<<") > 0) ALLCODE = 1
  o = norm(SRC, 0)
  if (ANSICODE) EXTRA = EXTRA "\n$SCANSICODE"
  printf "%s\n%s\n", o, EXTRA
}
function mk(c) { return (c in M) ? M[c] : c }
function maskall(s,    o, i) { o = ""; for (i = 1; i <= length(s); i++) o = o mk(substr(s, i, 1)); return o }
function addcode(s, d,    t) { t = norm(s, d + 1); EXTRA = EXTRA "\n" t }
function match_paren(s, p,    n, d, k, c) {
  n = length(s); d = 0
  for (k = p; k <= n; k++) {
    c = substr(s, k, 1)
    if (c == "(") d++
    else if (c == ")") { d--; if (d == 0) return k }
  }
  return 0
}
function isshell(w) { sub(/.*\//, "", w); return (w ~ /^(ba|z|da|k)?sh$/) }
function isenv(w) { sub(/.*\//, "", w); return (w == "env" || w == "genv") }
# raw   : the current word as the shell passes it (quotes removed, substitutions kept)
# cmode : 1 = after a shell -c, the next non-option word is code; 2 = after env -S
function norm(s, depth,    out, n, i, j, c, k, e, E, buf, rb, q, raw, inw, ansi, hard, shellseen, envseen, cmode, cskip, rtarget) {
  if (depth > 6) return s
  s = s " "
  out = ""; n = length(s); i = 1; raw = ""; inw = 0; ansi = 0
  shellseen = 0; envseen = 0; cmode = 0; cskip = 0; rtarget = 0
  while (i <= n) {
    c = substr(s, i, 1)
    if (c == " " || c == "\t" || c == "\n" || c == ";" || c == "&" || c == "|" || c == "(" || c == ")" || c == "<" || c == ">") {
      if (inw) {
        if (rtarget) rtarget = 0
        else if ((c == ">" || c == "<") && raw ~ /^[0-9]*$/) rtarget = 0
        else if (cmode) {
          if (cmode == 1 && cskip) cskip = 0
          else if (cmode == 1 && raw ~ /^[-+]/) { if (raw ~ /^[-+][oO]$/) cskip = 1 }
          else { if (ansi) ANSICODE = 1; addcode(raw, depth); cmode = 0 }
        } else {
          if (isshell(raw)) shellseen = 1
          else if (shellseen && raw ~ /^-[A-Za-z]*c[A-Za-z]*$/) cmode = 1
          if (isenv(raw)) envseen = 1
          else if (envseen) {
            if (raw ~ /^-[A-Za-z]*S$/ || raw == "--split-string") cmode = 2
            else if (raw ~ /^-[A-Za-z]*S./) { if (ansi) ANSICODE = 1; addcode(substr(raw, index(raw, "S") + 1), depth) }
            else if (raw ~ /^--split-string=/) { if (ansi) ANSICODE = 1; addcode(substr(raw, 16), depth) }
          }
        }
        raw = ""; inw = 0; ansi = 0
      }
      hard = (c == ";" || c == "\n" || c == "(" || c == ")")
      if (c == "&") hard = !(substr(s, i + 1, 1) == ">" || (i > 1 && (substr(s, i - 1, 1) == ">" || substr(s, i - 1, 1) == "<")))
      if (c == "|") hard = !(i > 1 && substr(s, i - 1, 1) == ">")
      if (hard) { shellseen = 0; envseen = 0; cmode = 0; cskip = 0; rtarget = 0 }
      else if (c == "<" || c == ">") rtarget = 1
      out = out c; i++; continue
    }
    if (c == "\\" && substr(s, i + 1, 1) == "\n") { i += 2; continue }
    inw = 1
    if (c == "\\") {
      k = (i < n) ? substr(s, i + 1, 1) : ""
      i += 2
      if (k == "") continue
      out = out mk(k); raw = raw k
      continue
    }
    if (c == "$" && substr(s, i + 1, 1) == "(") {
      E = match_paren(s, i + 1); if (E == 0) E = n + 1
      addcode(substr(s, i + 2, E - i - 2), depth); out = out "$SCSUB"; raw = raw substr(s, i, E - i + 1)
      i = E + 1; continue
    }
    if (c == "`") {
      e = index(substr(s, i + 1), "`"); E = (e == 0) ? n + 1 : i + e
      addcode(substr(s, i + 1, E - i - 1), depth); out = out "$SCSUB"; raw = raw substr(s, i, E - i + 1)
      i = E + 1; continue
    }
    if (c == "$" && substr(s, i + 1, 1) == SQ) {
      j = i + 2; buf = ""; e = 0
      while (j <= n) {
        k = substr(s, j, 1)
        if (k == "\\" && j < n) { buf = buf k substr(s, j + 1, 1); e = 1; j += 2; continue }
        if (k == SQ) break
        buf = buf k; j++
      }
      i = j + 1
      if (e) {
        ansi = 1
        if (ALLCODE) ANSICODE = 1
        out = out "$SCANSI" maskall(buf); raw = raw "$SCANSI" buf
      } else if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE"; raw = raw buf }
      else { out = out maskall(buf); raw = raw buf }
      continue
    }
    if (c == "$" && substr(s, i + 1, 1) == "\"") { i++; continue }
    if (c == SQ) {
      e = index(substr(s, i + 1), SQ); E = (e == 0) ? n + 1 : i + e
      buf = substr(s, i + 1, E - i - 1); i = E + 1
      if (ALLCODE) { addcode(buf, depth); out = out "$SCCODE" }
      else out = out maskall(buf)
      raw = raw buf
      continue
    }
    if (c == "\"") {
      j = i + 1; rb = ""; q = ""
      while (j <= n) {
        k = substr(s, j, 1)
        if (k == "\"") break
        if (k == "\\" && j < n) { k = substr(s, j + 1, 1); rb = rb k; q = q mk(k); j += 2; continue }
        if (k == "`" || (k == "$" && substr(s, j + 1, 1) == "(")) {
          if (k == "`") { e = index(substr(s, j + 1), "`"); E = (e == 0) ? n + 1 : j + e; buf = substr(s, j + 1, E - j - 1) }
          else { E = match_paren(s, j + 1); if (E == 0) E = n + 1; buf = substr(s, j + 2, E - j - 2) }
          if (!ALLCODE) addcode(buf, depth)
          rb = rb substr(s, j, E - j + 1); q = q "$SCSUB"
          j = E + 1; continue
        }
        rb = rb k; q = q mk(k); j++
      }
      i = j + 1
      if (ALLCODE) { addcode(rb, depth); out = out "$SCCODE" }
      else out = out q
      raw = raw rb
      continue
    }
    out = out c; raw = raw c
    i++
  }
  if (inw && cmode) { if (ansi) ANSICODE = 1; addcode(raw, depth) }
  if (substr(out, length(out), 1) == " ") out = substr(out, 1, length(out) - 1)
  return out
}
'

# unmask <text> : restore characters masked by the normalizer. The masks avoid \001 and
# \177, which bash 3.2 uses internally, and \011-\015, which are whitespace.
unmask() {
  printf '%s' "$1" | tr '\002\003\004\005\006\016\017\020\021\022\023' ' \t><;&|()\n`'
}
MASKED_SEMI="$(printf '\006')"

# Regular expressions for [[ =~ ]] live in variables (portable across bash versions).
RE_SED_INPLACE='^-[AEnrsuzal0-9]*[iI]'
RE_LONG_INPLACE='^--in-place'
RE_PERL_INPLACE='^-[aFnpsTtuUwWXcfhlv0-9]*i'
RE_TAIL_FOLLOW='^-[A-Za-z0-9]*[fF]'
RE_TAIL_PLUSF='^\+[0-9]*[fF]'
RE_TAIL_LONG='^--follow'
RE_LESS_F='^\+.*F'
RE_SHORT_FOLLOW='^-[A-Za-z0-9]*f'
RE_SBT_WATCH='(^|;)[[:space:]]*~'
RE_SLEEP_FOREVER='^\+?[iI][nN][fF]'

# wrapper_takes_next <wrapper basename> <option word> : true when the option leaves its
# argument in the next word. Getopt rules: in a short-option cluster such as -tn the
# last letter may take the next word as its argument (-tn 1); a letter followed by more
# text carries its argument inline (-n1, -I{}); --long=value is one word.
wrapper_takes_next() {
  local o="$2" spec="" long="" k n
  case "$1" in
    xargs|gxargs) spec="nILPsdEaJRS"; long="--max-args --max-lines --max-procs --max-chars --delimiter --eof --arg-file --process-slot-var" ;;
    sudo) spec="ugCDhprtUTRc"; long="--user --group --close-from --chdir --host --prompt --role --type --other-user --command-timeout --chroot --login-class" ;;
    doas) spec="uC" ;;
    env|genv) spec="uCSP"; long="--unset --chdir --split-string" ;;
    exec) spec="a" ;;
    nice|gnice) spec="n"; long="--adjustment" ;;
    ionice) spec="cnpPu"; long="--class --classdata --pid --pgid --uid" ;;
    stdbuf|gstdbuf) spec="ioe"; long="--input --output --error" ;;
    timeout|gtimeout) spec="sk"; long="--signal --kill-after" ;;
    time|gtime) spec="of"; long="--output --format" ;;
    caffeinate) spec="tw" ;;
    *) return 1 ;;
  esac
  case "$o" in
    --*=*) return 1 ;;
    --*) case " $long " in *" $o "*) return 0 ;; esac; return 1 ;;
    -?*)
      n=${#o}; k=1
      while [ "$k" -lt "$n" ]; do
        case "$spec" in
          *"${o:$k:1}"*) [ $((k + 1)) -eq "$n" ] && return 0; return 1 ;;
        esac
        k=$((k + 1))
      done ;;
  esac
  return 1
}

# is_wrapper <basename> : a command that runs the next words as a command.
is_wrapper() {
  case "$1" in
    command|builtin|exec|nohup|gnohup|time|gtime|sudo|doas|env|genv|nice|gnice|xargs|gxargs|stdbuf|gstdbuf|timeout|gtimeout|caffeinate|unbuffer|setsid|ionice|eval|source) return 0 ;;
  esac
  return 1
}

# is_checked <basename> : a command that analyze_segment inspects (or a wrapper).
is_checked() {
  case "$1" in
    sed|gsed|perl|perl5*|ed|red|ex|vi|vim|nvim|view|vimdiff|gvim|tail|gtail|watch|less|tee|gtee|touch|gtouch|cp|mv|install|ln|rsync|gcp|gmv|ginstall|gln|rm|unlink|truncate|chmod|chown|patch|dd|gsplit|split|git|git-*|find|gfind|sort|gsort|uniq|guniq|sbt|sbtn|docker|podman|docker-compose|journalctl|kubectl|sleep|gsleep) return 0 ;;
  esac
  is_wrapper "$1"
}

# --- sed script reader (repair 02, human decision b) ---------------------------------
# sed_skip <delimiter> <brackets 0|1> : move i past the closing delimiter of a regex or
# replacement. Uses the caller's s, n and i (bash dynamic scope). With brackets=1 a
# bracket expression is skipped whole (BSD sed); with 0 it is not (GNU sed).
sed_skip() {
  local d="$1" bk="$2" ch c2
  while [ "$i" -lt "$n" ]; do
    ch="${s:$i:1}"
    if [ "$ch" = '\' ]; then i=$((i + 2)); continue; fi
    [ "$ch" = "$NL" ] && return 1
    if [ "$bk" = 1 ] && [ "$ch" = '[' ] && [ "$d" != '[' ]; then
      i=$((i + 1))
      [ "${s:$i:1}" = '^' ] && i=$((i + 1))
      [ "${s:$i:1}" = ']' ] && i=$((i + 1))
      while [ "$i" -lt "$n" ]; do
        ch="${s:$i:1}"
        if [ "$ch" = ']' ]; then i=$((i + 1)); break; fi
        [ "$ch" = "$NL" ] && return 1
        if [ "$ch" = '[' ]; then
          c2="${s:$((i + 1)):1}"
          case "$c2" in
            ':'|'.'|'=')
              i=$((i + 2))
              while [ "$i" -lt "$n" ]; do
                if [ "${s:$i:1}" = "$c2" ] && [ "${s:$((i + 1)):1}" = ']' ]; then break; fi
                i=$((i + 1))
              done
              [ "$i" -ge "$n" ] && return 1
              i=$((i + 2)); continue ;;
          esac
        fi
        i=$((i + 1))
      done
      continue
    fi
    i=$((i + 1))
    [ "$ch" = "$d" ] && return 0
  done
  return 1
}

# sed_script_risky <script> <brackets 0|1> : true (0) when the script has a w, W or e
# command, an s///w or s///e flag, or cannot be read (fail closed).
sed_script_risky() {
  local s="$1" bk="$2" n i c d
  n=${#s}; i=0
  while [ "$i" -lt "$n" ]; do
    c="${s:$i:1}"
    case "$c" in
      ' '|"$TAB"|"$NL"|';'|'{'|'}') i=$((i + 1)); continue ;;
      '#') while [ "$i" -lt "$n" ] && [ "${s:$i:1}" != "$NL" ]; do i=$((i + 1)); done; continue ;;
    esac
    # Addresses: line numbers, $, /re/, \cREc, ranges, steps, I/M flags, negation.
    while [ "$i" -lt "$n" ]; do
      c="${s:$i:1}"
      case "$c" in
        [0-9]|'$'|','|'~'|'+'|' '|"$TAB"|'!'|I|M) i=$((i + 1)) ;;
        '/') i=$((i + 1)); sed_skip / "$bk" || return 0 ;;
        '\')
          d="${s:$((i + 1)):1}"; i=$((i + 2))
          if [ -z "$d" ] || [ "$d" = "$NL" ] || [ "$d" = '\' ]; then return 0; fi
          sed_skip "$d" "$bk" || return 0 ;;
        *) break ;;
      esac
    done
    [ "$i" -ge "$n" ] && return 0
    c="${s:$i:1}"; i=$((i + 1))
    case "$c" in
      w|W|e) return 0 ;;
      '{'|'}'|';'|"$NL"|p|P|n|N|d|D|g|G|h|H|x|z|F|'=') ;;
      l|L|q|Q)
        while [ "$i" -lt "$n" ]; do
          case "${s:$i:1}" in [0-9]) i=$((i + 1)) ;; *) break ;; esac
        done ;;
      a|i|c|r|R)
        while [ "$i" -lt "$n" ] && [ "${s:$i:1}" != "$NL" ]; do i=$((i + 1)); done ;;
      b|t|T|':'|v)
        while [ "$i" -lt "$n" ] && [ "${s:$i:1}" != "$NL" ] && [ "${s:$i:1}" != ';' ]; do i=$((i + 1)); done ;;
      s|y)
        d="${s:$i:1}"; i=$((i + 1))
        if [ -z "$d" ] || [ "$d" = "$NL" ] || [ "$d" = '\' ]; then return 0; fi
        if [ "$c" = s ]; then sed_skip "$d" "$bk" || return 0; else sed_skip "$d" 0 || return 0; fi
        sed_skip "$d" 0 || return 0
        if [ "$c" = s ]; then
          while [ "$i" -lt "$n" ]; do
            case "${s:$i:1}" in
              w|e) return 0 ;;
              g|p|i|I|m|M|[0-9]) i=$((i + 1)) ;;
              *) break ;;
            esac
          done
        fi ;;
      *) return 0 ;;
    esac
  done
  return 1
}

# sed_check <script word, masked> : block a sed script that writes or runs commands. It
# is read the BSD way and the GNU way; either reading that writes, runs or fails blocks.
sed_check() {
  local x re='\$[A-Za-z_{(]'
  x="$(unmask "$1")"
  # "$p" or ${p} is expanded by the shell, so the text the guard sees ($p, which reads
  # as "last line, print") is not the script sed runs. Only a script written literally
  # in single quotes may use $ before a letter, { or (.
  if [[ $x =~ $re ]]; then
    case "$RAW_CMD" in
      *"'$x'"*) ;;
      *) block "the sed script '$x' uses a shell variable or substitution, so the guard cannot read the script sed runs." \
           "Write the sed script literally in single quotes. $MSG_SED_SCRIPT" ;;
    esac
  fi
  if sed_script_risky "$x" 1 || sed_script_risky "$x" 0; then
    block "a sed script that writes files or runs commands (w, W, e, s///w, s///e), or that the guard cannot read, is denied ('$x')." "$MSG_SED_SCRIPT"
  fi
}

REDIR_OPS='(&>>|&>|>>|>\||<>|>)'
TARGETS=""      # newline-separated shell write targets (still masked)
HAS_OP=0        # a file-changing command word (tee, cp, mv, rm, chmod, git checkout, ...)
HAS_CD=0        # the command changes directory, so relative targets are ambiguous
IN_SCAN=0       # 1 while re-checking the words after a wrapper (no nested re-scan)
RAW_CMD=""      # the Bash command as received (sed_check looks for single quotes in it)

add_target() { TARGETS="$TARGETS
$1"; }

# git_write_block <word> : repair 03 (finding B). git wrote a file through an option.
git_write_block() {
  block "read-only git only; print to stdout. git may not write a file through an option ('$1': --output on any subcommand, git format-patch, git archive -o); this applies to every agent and the orchestrator." "$MSG_GIT_WRITE"
}

# analyze_segment <segment> : one simple command, separators already removed.
analyze_segment() {
  local w wb name cmd a sub inexec nonopt last tdir dashdash skip viax=0 wrapped=0 after="" k exp havee gotfirst firstop u b gitarch gito
  set -- $1
  # Strip assignments, keywords and wrappers that run the next word as a command.
  # Wrappers are compared by basename, so /usr/bin/xargs is xargs.
  while [ $# -gt 0 ]; do
    w="$1"; wb="${w##*/}"
    case "$w" in
      [A-Za-z_]*=*)
        name="${w%%=*}"
        case "$name" in *[!A-Za-z0-9_]*) break ;; esac
        shift; continue ;;
      then|do|else|elif|if|while|until|'!'|'{'|'}')
        shift; continue ;;
    esac
    is_wrapper "$wb" || break
    case "$wb" in xargs|gxargs) viax=1 ;; esac
    shift
    if [ "$wrapped" = 0 ]; then wrapped=1; after="$*"; fi
    while [ $# -gt 0 ]; do
      case "$1" in
        --) shift; break ;;
        -*)
          # Repair 04: -o*, -C*, -D* and -R* catch the bare option and its inline
          # argument (-o, -ofile); -[!-]*X* catches it later in a cluster (-po).
          case "$wb" in
            time|gtime)
              case "$1" in
                --o*|-o*|-[!-]*o*)
                  block "$wb -o/--output writes a file the guard cannot check." \
                    "Redirect with > into your own territory or under /private/tmp/claude-* instead." ;;
              esac ;;
            env|genv) case "$1" in --ch*|-C*|-[!-]*C*) HAS_CD=1 ;; esac ;;
            sudo) case "$1" in --ch*|-D*|-R*|-[!-]*D*|-[!-]*R*) HAS_CD=1 ;; esac ;;
          esac
          if wrapper_takes_next "$wb" "$1"; then
            shift; [ $# -gt 0 ] && shift
          else
            shift
          fi ;;
        *) break ;;
      esac
    done
    case "$wb" in
      timeout|gtimeout) [ $# -gt 0 ] && shift ;;   # the duration
    esac
  done
  if [ $# -eq 0 ]; then
    [ "$wrapped" = 1 ] && rescan_after_wrapper "$after"
    return 0
  fi
  case "$1" in
    *'$SCANSI'*)
      block "the command word is \$'...' text with backslash escapes, so the guard cannot read which command runs." "$MSG_ANSI" ;;
  esac
  cmd="${1##*/}"
  shift

  # Commands the guard inspects: an argument written as $'...' with backslash escapes
  # could hide an option (-i, -f, -t, -o, -exec) or a path, so it is refused.
  if is_checked "$cmd" || [ "$cmd" = cd ] || [ "$cmd" = pushd ] || [ "$cmd" = popd ]; then
    for a in "$@"; do
      case "$a" in
        *'$SCANSI'*) block "an argument of $cmd is \$'...' text with backslash escapes, so the guard cannot read it." "$MSG_ANSI" ;;
      esac
    done
  fi

  case "$cmd" in
    sed|gsed)
      for a in "$@"; do
        if [[ $a =~ $RE_SED_INPLACE ]] || [[ $a =~ $RE_LONG_INPLACE ]]; then
          block "in-place editing through the shell ($cmd $a) is denied for every agent and the orchestrator." "$MSG_EDIT"
        fi
      done
      # The script: every -e/--expression argument, else the first operand.
      dashdash=0; exp=""; havee=0; gotfirst=0; firstop=""
      for a in "$@"; do
        if [ -n "$exp" ]; then
          sed_check "$a"; exp=""; continue
        fi
        if [ "$dashdash" = 0 ]; then
          case "$a" in
            --) dashdash=1; continue ;;
            --expression=*) havee=1; sed_check "${a#*=}"; continue ;;
            --e|--ex|--exp|--expr|--expre|--expres|--express|--expressi|--expressio|--expression) havee=1; exp=e; continue ;;
            --f*) block "a sed script file ($cmd $a) cannot be read by the guard." "Pass a plain script inline, or $MSG_SED_SCRIPT" ;;
            --*) continue ;;
            -?*)
              k=1
              while [ "$k" -lt "${#a}" ]; do
                case "${a:$k:1}" in
                  e)
                    havee=1
                    if [ $((k + 1)) -lt "${#a}" ]; then sed_check "${a:$((k + 1))}"; else exp=e; fi
                    break ;;
                  f) block "a sed script file ($cmd $a) cannot be read by the guard." "Pass a plain script inline, or $MSG_SED_SCRIPT" ;;
                esac
                k=$((k + 1))
              done
              continue ;;
          esac
        fi
        if [ "$gotfirst" = 0 ]; then gotfirst=1; firstop="$a"; fi
      done
      if [ "$havee" = 0 ] && [ "$gotfirst" = 1 ]; then sed_check "$firstop"; fi ;;
    perl|perl5*)
      for a in "$@"; do
        if [[ $a =~ $RE_PERL_INPLACE ]]; then
          block "in-place editing through the shell ($cmd $a) is denied for every agent and the orchestrator." "$MSG_EDIT"
        fi
      done ;;
    ed|red|ex|vi|vim|nvim|view|vimdiff|gvim)
      block "editing files with $cmd through the shell is denied for every agent and the orchestrator." "$MSG_EDIT" ;;
    tail|gtail)
      for a in "$@"; do
        if [[ $a =~ $RE_TAIL_FOLLOW ]] || [[ $a =~ $RE_TAIL_PLUSF ]] || [[ $a =~ $RE_TAIL_LONG ]]; then
          block "a command that never ends by itself ($cmd $a) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    watch)
      block "a command that never ends by itself (watch) stalls the run." "$MSG_WATCH" ;;
    less)
      for a in "$@"; do
        if [[ $a =~ $RE_LESS_F ]]; then
          block "a command that never ends by itself ($cmd $a) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    sbt|sbtn)
      for a in "$@"; do
        a="$(unmask "$a")"
        if [[ $a =~ $RE_SBT_WATCH ]]; then
          block "a command that never ends by itself ($cmd with a ~ triggered task) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    docker|podman|docker-compose|kubectl)
      skip=1
      for a in "$@"; do
        if [ "$skip" = 1 ]; then [ "$a" = logs ] && skip=0; continue; fi
        if [[ $a =~ $RE_SHORT_FOLLOW ]] || [[ $a =~ $RE_TAIL_LONG ]]; then
          block "a command that never ends by itself ($cmd logs $a) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    journalctl)
      for a in "$@"; do
        if [[ $a =~ $RE_SHORT_FOLLOW ]] || [[ $a =~ $RE_TAIL_LONG ]]; then
          block "a command that never ends by itself ($cmd $a) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    sleep|gsleep)
      for a in "$@"; do
        if [[ $a =~ $RE_SLEEP_FOREVER ]]; then
          block "a command that never ends by itself ($cmd $a) stalls the run." "$MSG_WATCH"
        fi
      done ;;
    cd|pushd|popd)
      HAS_CD=1 ;;
    tee|gtee)
      HAS_OP=1
      dashdash=0
      for a in "$@"; do
        if [ "$dashdash" = 0 ]; then
          case "$a" in --) dashdash=1; continue ;; -*) continue ;; esac
        fi
        add_target "$a"
      done ;;
    touch|gtouch)
      HAS_OP=1
      dashdash=0
      for a in "$@"; do
        if [ "$dashdash" = 0 ]; then
          case "$a" in
            --) dashdash=1; continue ;;
            --d*|--r*)
              block "setting file times ($cmd $a) is denied for every agent and the orchestrator; it can make a change look older than the test evidence." "$MSG_TOUCH_TIME" ;;
            --*) continue ;;
            -?*)
              case "$a" in
                -*[tdrA]*)
                  block "setting file times ($cmd $a) is denied for every agent and the orchestrator; it can make a change look older than the test evidence." "$MSG_TOUCH_TIME" ;;
              esac
              continue ;;
          esac
        fi
        add_target "$a"
      done ;;
    sort|gsort)
      dashdash=0; exp=""
      for a in "$@"; do
        if [ -n "$exp" ]; then
          [ "$exp" = o ] && { HAS_OP=1; add_target "$a"; }
          exp=""; continue
        fi
        [ "$dashdash" = 1 ] && continue
        case "$a" in
          --) dashdash=1 ;;
          --co*)
            block "$cmd --compress-program runs another program the guard cannot check." "Sort without --compress-program." ;;
          --o=*|--ou=*|--out=*|--outp=*|--outpu=*|--output=*|--t=*|--te=*|--tem=*|--temp*=*)
            HAS_OP=1; add_target "${a#*=}" ;;
          --*=*) ;;
          --o|--ou|--out|--outp|--outpu|--output|--t|--te|--tem|--temp*) exp=o ;;
          --k|--ke|--key|--f|--fi|--fie|--fiel|--field*|--b|--bu|--buf*|--ba|--bat*|--p|--pa|--par*) exp=skip ;;
          --*) ;;
          -?*)
            k=1
            while [ "$k" -lt "${#a}" ]; do
              case "${a:$k:1}" in
                o|T)
                  HAS_OP=1
                  if [ $((k + 1)) -lt "${#a}" ]; then add_target "${a:$((k + 1))}"; else exp=o; fi
                  break ;;
                k|t|S)
                  [ $((k + 1)) -ge "${#a}" ] && exp=skip
                  break ;;
              esac
              k=$((k + 1))
            done ;;
        esac
      done ;;
    uniq|guniq)
      # uniq [options] [input [output]]: every operand after the first is treated as an
      # output (fail closed if an option argument is misread as an operand).
      dashdash=0; exp=""; nonopt=0
      for a in "$@"; do
        if [ -n "$exp" ]; then exp=""; continue; fi
        if [ "$dashdash" = 0 ]; then
          case "$a" in
            --) dashdash=1; continue ;;
            -) ;;
            --*=*) continue ;;
            --sk*|--ch*|--w*) exp=skip; continue ;;
            --*) continue ;;
            -[0-9]*|+[0-9]*) continue ;;
            -?*)
              case "$a" in -*[fsw]) exp=skip ;; esac
              continue ;;
          esac
        fi
        nonopt=$((nonopt + 1))
        if [ "$nonopt" -ge 2 ] && [ "$a" != "-" ]; then HAS_OP=1; add_target "$a"; fi
      done ;;
    cp|mv|install|ln|rsync|gcp|gmv|ginstall|gln)
      HAS_OP=1
      dashdash=0; skip=0; tdir=""; nonopt=0; last=""
      for a in "$@"; do
        if [ "$skip" = 1 ]; then skip=0; tdir="$a"; continue; fi
        if [ "$dashdash" = 0 ]; then
          case "$a" in
            --) dashdash=1; continue ;;
            -t|--target-directory) skip=1; continue ;;
            --target-directory=*) tdir="${a#--target-directory=}"; continue ;;
            -t?*) tdir="${a#-t}"; continue ;;
            -*) continue ;;
          esac
        fi
        nonopt=$((nonopt + 1))
        last="$a"
        # install -d creates every named directory
        case "$cmd" in install|ginstall) case " $* " in *" -d "*) add_target "$a" ;; esac ;; esac
      done
      if [ -n "$tdir" ]; then
        add_target "$tdir"
      elif [ "$nonopt" -ge 2 ]; then
        add_target "$last"
      elif [ "$nonopt" = 1 ] && { [ "$cmd" = ln ] || [ "$cmd" = gln ]; }; then
        add_target "."
      fi ;;
    rm|unlink|truncate|chmod|chown|patch|dd|gsplit|split)
      HAS_OP=1 ;;
    git|git-*)
      # Repair 03 (finding B, human decision): git never writes a file through an
      # option, for every agent and the main session. Blocked: --output and
      # --output=FILE on any subcommand, with every abbreviation git accepts (--o ..
      # --outpu); git format-patch (it writes patch files, also with -o DIR); git
      # archive -o FILE. Every word is checked wherever it stands, and the text inside a
      # word is split into words too, so global options (-C dir, -c k=v), aliases
      # defined with -c, the dashed form (git-format-patch) and quoting cannot hide
      # them. Fail closed: a commit message or search text that contains --output or
      # format-patch is refused as well.
      # Repair 04 (human decision): shell brace expansion. bash and zsh rewrite
      # --outpu{t,t}=X into --output=X --output=X before git runs, so the word the
      # guard reads is not the word git receives. A git word (quotes removed) that has
      # a '{' followed later by a ',' or '..' and then a '}' -- the shape every brace
      # expansion needs -- is blocked, also when it was quoted (fail closed).
      # HEAD@{1}, stash@{0}, HEAD~3..HEAD and @{u}..HEAD do not have that shape.
      gitarch=0; gito=""
      for a in "$cmd" "$@"; do
        u="$(unmask "$a")"
        case "$u" in
          *'{'*','*'}'*|*'{'*'..'*'}'*)
            block "git word '$u' uses shell brace expansion ({a,b} or {a..b}); the shell rewrites it before git runs, so the guard cannot read the options git receives. This applies to every agent and the orchestrator." "$MSG_GIT_BRACE" ;;
        esac
        for b in $u; do
          case "$b" in
            --output-indicator-*) ;;
            --o|--ou|--out|--outp|--outpu|--o=*|--ou=*|--out=*|--outp=*|--outpu=*|*--output*|*format-patch*)
              git_write_block "$b" ;;
            --*) ;;
            -*o*) gito="$b" ;;
          esac
          case "$b" in *archive*) gitarch=1 ;; esac
        done
      done
      if [ "$gitarch" = 1 ] && [ -n "$gito" ]; then git_write_block "archive $gito"; fi
      # skip global options to find the subcommand
      while [ $# -gt 0 ]; do
        case "$1" in
          -C|-c|--git-dir|--work-tree|--namespace) shift; [ $# -gt 0 ] && shift ;;
          -*) shift ;;
          *) break ;;
        esac
      done
      case "${1:-}" in checkout|restore|apply|am) HAS_OP=1 ;; esac ;;
    find|gfind)
      inexec=0; sub=""
      for a in "$@"; do
        if [ "$inexec" = 1 ]; then
          case "$a" in
            "$MASKED_SEMI"|';'|'+')
              analyze_segment "$sub"; inexec=0; sub=""; continue ;;
          esac
          sub="$sub $a"
          continue
        fi
        case "$a" in -exec|-execdir|-ok|-okdir) inexec=1; sub="" ;; esac
      done
      [ "$inexec" = 1 ] && [ -n "$sub" ] && analyze_segment "$sub" ;;
  esac

  # Under xargs the operands come from input the guard cannot see, so any of them could
  # be -i, -o, -f, a path or a script. Only read-only commands may run there.
  if [ "$viax" = 1 ]; then
    case "$cmd" in
      grep|egrep|fgrep|ggrep|cat|head|wc|ls|echo|printf|basename|dirname|stat|du|shasum|sha1sum|sha256sum|sha512sum|md5|md5sum|cksum|realpath|readlink|true) ;;
      *) block "$cmd run through xargs takes its operands from input the guard cannot see, so it cannot check where it writes or whether it ends." "$MSG_XARGS" ;;
    esac
  fi

  [ "$wrapped" = 1 ] && rescan_after_wrapper "$after"
  return 0
}

# rescan_after_wrapper <words after the first wrapper> : an option the guard does not
# know could hide the real command word. Fail closed: check again from every later word
# that names a checked command. Runs once per segment (no nested re-scan).
rescan_after_wrapper() {
  [ "$IN_SCAN" = 1 ] && return 0
  IN_SCAN=1
  set -- $1
  while [ $# -gt 0 ]; do
    if is_checked "${1##*/}"; then analyze_segment "$*"; fi
    shift
  done
  IN_SCAN=0
  return 0
}

# check_write_target <target> : decide one shell write target (Amendment 02, decision 4).
check_write_target() {
  local t abs rel owner
  t="$(unmask "$1")"
  [ -z "$t" ] && return 0
  case "$t" in
    /dev/null|/dev/stdout|/dev/stderr) return 0 ;;
  esac
  if is_memory_path "$t"; then
    block "shell write to '$t': memory and local settings ($HOME_DIR/.claude/**, .claude/settings.local.json, CLAUDE.local.md) are not written during runs." "$MSG_MEMORY"
  fi
  case "$t" in
    *'$'*|*'`'*|*'*'*|*'?'*|*'~'*|*'['*|*'{'*)
      block "shell write target '$t' is not a literal path (it contains \$, a backtick, *, ?, ~, [ or {), so the guard cannot check whose territory it is in." \
        "use a literal path, or use the Write and Edit tools." ;;
  esac
  case "/$t/" in
    */../*) block "shell write target '$t' contains '..'." "use a literal path without '..' inside your own territory or under /private/tmp/claude-*." ;;
  esac
  if is_test_report_path "$t"; then
    block "shell write to sbt test reports ('$t') is denied; they are the test evidence verify-tests-ran.sh reads." "$MSG_REPORTS"
  fi
  if is_secret_path "$t"; then
    block "writing secret material ('$t') is denied." "Commit only env.example placeholders; real secrets live in Secrets Manager."
  fi
  case "$t" in
    /*) abs="$t" ;;
    *)
      if [ "$HAS_CD" = 1 ]; then
        block "relative shell write target '$t' in a command that changes directory (cd, pushd, popd, env -C, sudo -D); the guard cannot tell where it lands." \
          "use a literal path: an absolute path inside your own territory or under /private/tmp/claude-*."
      fi
      abs="$CWD/$t" ;;
  esac
  abs="$(normpath "$abs")"
  case "/$abs/" in
    */../*) block "shell write target '$t' contains '..'." "use a literal path without '..'." ;;
  esac
  case "$abs" in
    /tmp/claude-*|/private/tmp/claude-*) return 0 ;;
  esac
  if is_memory_path "$abs"; then
    block "shell write to '$abs': memory and local settings are not written during runs." "$MSG_MEMORY"
  fi
  case "$abs" in
    "$PROJECT_DIR") rel="" ;;
    "$PROJECT_DIR"/*) rel="${abs#"$PROJECT_DIR"/}" ;;
    *) block "shell write to '$abs' is outside the repository and outside the scratch areas (/tmp/claude-*, /private/tmp/claude-*)." \
         "Write scratch output under your session scratchpad in /private/tmp/claude-*, or write inside your own territory." ;;
  esac
  if [ -z "$rel" ]; then owner="unowned"; else owner="$(owner_of "$rel")"; fi
  if [ "$owner" = "generated:sbt-markTestRun" ]; then
    block ".claude/.last-test-run may be written only by the sbt markTestRun task after tests pass." "Run sbt check."
  fi
  case "$rel" in
    releases/*) block "'$rel' is a release record; records under releases/** are written only by approved scripts that deploy-engineer runs, never by hand." \
                  "Run the approved script through /deploy, or put the record in your final report." ;;
  esac
  if [ -z "$AGENT" ]; then
    block "the orchestrator has no write territory; shell writes inside the repository ('${rel:-.}', owner: $owner) are denied." \
      "Delegate the change to the owning agent ($owner); keep scratch output under /private/tmp/claude-*."
  fi
  [ "$owner" = "$AGENT" ] && return 0
  case "$owner" in
    human) block "'$rel' is human-owned evidence under docs/agent-runs/**; no agent may create or edit it." "Return your final report to the orchestrator; the human archives it." ;;
    unowned) block "shell write to '${rel:-.}', which has no owner in docs/agents.md (ambiguous ownership)." "Return BLOCKED-ON factory-engineer with the path so the human can ratify an owner." ;;
    *) block "shell write to '$rel', which is owned by $owner, not '$AGENT' (one writer per artifact)." "Decline, and return BLOCKED-ON $owner with the needed change so the orchestrator can route it." ;;
  esac
}

# ==================================================================================
# A. Bash command guard. Every rule below either blocks (exit 2 at once) or defers an
# ask; the deferred ask is returned by the dispatcher only after guard_bash has run
# every block rule.
# ==================================================================================
guard_bash() {
  local cmd="$1"
  RAW_CMD="$1"

  # --- Terraform / OpenTofu / Terragrunt -------------------------------------------
  if matches "$cmd" '(^|[^[:alnum:]_-])(terraform|tofu|terragrunt)([[:space:]]+[^;&|]*)?[[:space:]](run-all[[:space:]]+)?(apply|destroy)([[:space:]]|$|[;&|"'"'"'])'; then
    block "terraform/tofu/terragrunt apply and destroy are denied for every agent (control rule 4)." \
      "infra-engineer produces a reviewed plan with scripts/plan.sh; the human approves it; deploy-engineer runs the approved scripts/deploy.sh path. Use /deploy."
  fi
  if matches "$cmd" '(^|[^[:alnum:]_-])(terraform|tofu)([[:space:]]+[^;&|]*)?[[:space:]](state[[:space:]]+(rm|mv|push|replace-provider)|force-unlock|import|taint|untaint|workspace[[:space:]]+delete)([[:space:]]|$)'; then
    block "direct Terraform state mutation is denied." \
      "Describe the needed state change in a report and escalate to the human architect."
  fi

  # --- Git: force push, remote deletion, history rewrite ---------------------------
  if matches "$cmd" '(^|[^[:alnum:]_-])git([[:space:]]+[^;&|]*)?[[:space:]]push([[:space:]]+[^;&|]*)?[[:space:]](--force|--force-with-lease|--force-if-includes|--mirror|--delete|--prune|-[[:alnum:]]*[fd][[:alnum:]]*)([[:space:]=]|$)'; then
    block "force push, mirror push, and remote ref deletion are denied." \
      "Only the human pushes. Report the branch state instead."
  fi
  if matches "$cmd" '(^|[^[:alnum:]_-])git([[:space:]]+[^;&|]*)?[[:space:]]push([[:space:]]+[^;&|]*)?[[:space:]][+:][^[:space:]]'; then
    block "+refspec (forced) and :refspec (delete) pushes are denied." \
      "Only the human pushes. Report the branch state instead."
  fi
  # Only the human architect commits and pushes. Covers every form, including
  # `git -C <dir> push`, `git -c k=v commit`, `git commit --amend`, plain pushes.
  # Subagents: blocked. Main session: the human must confirm. This complements the
  # prefix-based ask rules Bash(git commit:*) / Bash(git push:*) in settings.json.
  if matches "$cmd" '(^|[^[:alnum:]_-])git([[:space:]]+[^;&|]*)?[[:space:]](commit|push)([[:space:]]|$|[;&|])'; then
    if [ -n "$AGENT" ]; then
      block "git commit and git push are reserved for the human architect (subagent '$AGENT')." \
        "Leave changes uncommitted and list them in FILES CREATED / FILES MODIFIED."
    fi
    defer_ask "git commit / git push: only the human architect commits and pushes. Approve only if the human is performing this action."
  fi
  if matches "$cmd" '(^|[^[:alnum:]_-])git([[:space:]]+[^;&|]*)?[[:space:]](filter-branch|filter-repo|reflog[[:space:]]+(expire|delete)|update-ref[[:space:]]+-d|reset[[:space:]]+([^;&|]*[[:space:]])?--hard|clean[[:space:]]+([^;&|]*[[:space:]])?-[[:alnum:]]*f|branch[[:space:]]+([^;&|]*[[:space:]])?-D|gc[[:space:]]+([^;&|]*[[:space:]])?--prune=now)'; then
    block "history rewrite or irreversible working-tree destruction is denied." \
      "Leave the tree as is and report what should be reverted; the human decides."
  fi

  # --- Broad recursive deletes -----------------------------------------------------
  # Every shell segment containing a recursive rm is checked; each target must be a
  # relative build-output directory. rm and sudo may be written with a path (/bin/rm).
  local seg tok bad="" line
  while IFS= read -r line; do
    if matches "$line" '(^|[`(][[:space:]]*|[[:space:]])(([^[:space:]]*/)?sudo[[:space:]]+)?([^[:space:]`(]*/)?rm[[:space:]]+([^[:space:]]+[[:space:]]+)*(-[[:alnum:]]*[rR][[:alnum:]]*|--recursive)([[:space:]]|$)'; then
      seg="$(printf '%s' "$line" | sed -E 's/^.*(^|[`([:space:]/])rm[[:space:]]+//')"
      if matches "$seg" '["'"'"'$`*?~{}[]'; then
        bad="quoted, globbed, or variable targets"
      else
        for tok in $seg; do
          case "$tok" in -*) continue ;; esac
          case "$tok" in *..*) bad="$tok"; break ;; esac
          if ! matches "$tok" '^((\./)?([A-Za-z0-9_.-]+/)*(target|\.bsp|\.bloop|\.metals|out|tmp)(/[A-Za-z0-9_.-]+)*/?)$'; then
            bad="$tok"; break
          fi
        done
      fi
      [ -n "$bad" ] && break
    fi
  done <<EOF
$(printf '%s\n' "$cmd" | tr ';&|' '\n\n\n')
EOF
  if [ -n "$bad" ]; then
    block "recursive delete of '$bad' is denied. Recursive rm is allowed only on build-output directories (relative target/, .bsp/, .bloop/, .metals/, out/, tmp/)." \
      "Delete specific files you own, or ask the human."
  fi
  if matches "$cmd" '(^|[^[:alnum:]_-])find[[:space:]][^;&|]*(-delete|-exec[[:space:]]+rm)'; then
    block "find -delete / find -exec rm is denied." "Delete specific files you own by name."
  fi
  if matches "$cmd" 'xargs[[:space:]]+([^;&|]*[[:space:]])?rm[[:space:]]'; then
    block "xargs rm is denied." "Delete specific files you own by name."
  fi

  # --- Disk and device writes ------------------------------------------------------
  if matches "$cmd" '(^|[^[:alnum:]_-])(mkfs(\.[a-z0-9]+)?|newfs_[a-z]+|fdisk|shred|wipefs)([[:space:]]|$)|(^|[^[:alnum:]_-])dd[[:space:]][^;&|]*of=/dev/|>[[:space:]]*/dev/(r?disk|sd|nvme|hd)|diskutil[[:space:]]+(erase|zero|secureErase|partition|reformat|eraseDisk|eraseVolume)|:\(\)[[:space:]]*\{|chmod[[:space:]]+-R[[:space:]]+[0-7]*7[[:space:]]+/([[:space:]]|$)|chown[[:space:]]+-R[[:space:]][^;&|]*[[:space:]]/([[:space:]]|$)'; then
    block "disk, device, or system-wide permission writes are denied." ""
  fi

  # --- Remote code execution by piping downloads into a shell ----------------------
  # The shell may be wrapped or written with a path (| sudo sh, | /usr/bin/env bash).
  if matches "$cmd" '(curl|wget)[^|]*\|([^;&]*[[:space:]/])?(ba|z|da|k)?sh([[:space:]]|$)'; then
    block "piping downloaded content into a shell is denied." \
      "Download to a file, review it, and route installation to the human."
  fi

  # --- Secret reads through the shell ----------------------------------------------
  local noplaceholder
  noplaceholder="$(printf '%s' "$cmd" | sed -E 's/\.env\.(example|sample|template)([^[:alnum:]_.-]|$)/\2/g')"
  if matches "$cmd" '(^|[^[:alnum:]_./-])(\./)?([A-Za-z0-9_./-]*/)?\.env(rc)?([^[:alnum:]_.-]|$)' \
     || matches "$noplaceholder" '\.env\.[[:alnum:]_-]+' \
     || matches "$cmd" '\.aws/(credentials|config)|\.ssh/|\.gnupg/|\.netrc|\.config/gh/|id_(rsa|ed25519|ecdsa|dsa)|\.(pem|p12|pfx|jks|keystore)([^[:alnum:]]|$)|\.tfstate|(^|/|[[:space:]])secrets/' \
     || matches "$cmd" 'secretsmanager[[:space:]]+get-secret-value|ssm[[:space:]]+get-parameters?[[:space:]][^;&|]*--with-decryption|security[[:space:]]+(find-generic-password|find-internet-password|dump-keychain)|aws[[:space:]]+configure[[:space:]]+(get|export-credentials)|gh[[:space:]]+auth[[:space:]]+token' \
     || matches "$cmd" '(^|[;&|][[:space:]]*)(printenv|env|export[[:space:]]+-p|set)[[:space:]]*($|[;&|>])'; then
    block "reading secrets, credentials, key material, Terraform state, or dumping the environment is denied." \
      "Use Secrets Manager references in code and env.example placeholders for local development; never print secret values."
  fi

  # --- AWS CLI and SDK -------------------------------------------------------------
  if matches "$cmd" '(^|[^[:alnum:]_-])aws[[:space:]]'; then
    if matches "$cmd" '[[:space:]]s3[[:space:]]+(rm|rb|mv)([[:space:]]|$)|[[:space:]]s3[[:space:]]+sync[[:space:]][^;&|]*--delete'; then
      block "destructive S3 commands (s3 rm, s3 rb, s3 mv, s3 sync --delete) are denied, including recursive S3 deletion." \
        "Data removal follows the cleanup policy through scripts/cleanup.sh with human approval."
    fi
    if matches "$cmd" '[[:space:]](delete|batch-delete|terminate|remove|purge|deregister|destroy|schedule-key-deletion|disable-key|cancel-key-deletion|disable-key-rotation)(-[a-z0-9-]+)?([[:space:]]|$)'; then
      block "destructive AWS operations (delete/terminate/remove/purge/deregister, KMS key disable or deletion) are denied for S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, EMR, and every other service." \
        "Removal is Terraform-managed or goes through scripts/cleanup.sh after human approval."
    fi
    if matches "$cmd" 'emr[[:space:]]+([^;&|]*[[:space:]])?(terminate-clusters|modify-instance-groups|modify-instance-fleet|add-instance-groups|add-instance-fleet|put-managed-scaling-policy|remove-managed-scaling-policy|put-auto-scaling-policy|set-termination-protection|set-keep-job-flow-alive-when-no-steps)|ecs[[:space:]]+([^;&|]*[[:space:]])?update-service[[:space:]][^;&|]*--desired-count|autoscaling[[:space:]]+([^;&|]*[[:space:]])?(set-desired-capacity|update-auto-scaling-group)|application-autoscaling[[:space:]]+([^;&|]*[[:space:]])?register-scalable-target|dynamodb[[:space:]]+([^;&|]*[[:space:]])?update-table[[:space:]][^;&|]*(provisioned-throughput|billing-mode)|neptune[[:space:]]+([^;&|]*[[:space:]])?modify-db-cluster[[:space:]][^;&|]*serverless'; then
      block "unreviewed EMR termination and capacity or scale changes (EMR, ECS, Auto Scaling, DynamoDB, Neptune) are denied." \
        "Capacity is defined in Terraform and changed only by approved scripts or approved experiment manifests. Use /experiment or /deploy."
    fi
    if matches "$cmd" '--no-deletion-protection|deletion-protection-enabled[[:space:]=]+false|--no-termination-protected|--skip-final-snapshot|--force-delete|--bypass-governance-retention|batch-write-item[^;&|]*DeleteRequest'; then
      block "removing deletion protection, skipping final snapshots, or bulk deletes are denied." ""
    fi
    if matches "$cmd" '[[:space:]](create|put|update|modify|run|start|stop|set|attach|detach|add|tag|untag|associate|disassociate|enable|disable|import|invoke|send|publish|register|restore|reboot|reset|copy|upload|execute|batch-write|batch-put|apply|change|authorize|revoke|replace|rotate|cancel|failover|submit)(-[a-z0-9-]+)?([[:space:]]|$)|[[:space:]]s3[[:space:]]+(cp|sync)([[:space:]]|$)'; then
      defer_ask "direct AWS mutation. All AWS infrastructure is Terraform-managed and operational writes go through approved scripts; console or ad-hoc CLI mutation is not an implementation path. Approve only if this is a documented, bounded action."
    fi
  fi
  if matches "$cmd" '(boto3|aws-sdk|software\.amazon\.awssdk)' && matches "$cmd" '(delete_|terminate_|purge_|remove_|schedule_key_deletion|disable_key|deleteBucket|deleteTable|deleteObjects|terminateJobFlows)'; then
    block "destructive AWS SDK calls from ad-hoc scripts are denied." ""
  fi

  # --- Destructive database and graph statements -----------------------------------
  if matches "$cmd" '(drop|truncate)[[:space:]]+(table|database|schema|index|keyspace|graph)([[:space:]]|$)|delete[[:space:]]+from[[:space:]]|\.drop\(\)|detach[[:space:]]+delete|(initiate|perform)DatabaseReset|(drop|clear)[[:space:]]+(silent[[:space:]]+)?(all|default|named|graph)([[:space:]]|$)'; then
    block "destructive database or graph statements (DROP, TRUNCATE, DELETE FROM, gremlin drop(), openCypher DETACH DELETE, SPARQL DROP/CLEAR, Neptune reset) are denied." \
      "Destructive data repair requires human escalation (docs/agents.md, escalation rules)."
  fi
  if matches "$cmd" '(curl|wget|http|https|xh)[[:space:]][^;&|]*(-X|--request|--method)[[:space:]]*["'"'"']?DELETE|(^|[[:space:]])(http|https|xh)[[:space:]]+DELETE[[:space:]]'; then
    block "HTTP DELETE requests from the shell are denied." ""
  fi

  # --- Command-position analysis (Amendment 02, decisions 3b, 4, 7; repair 02) -----
  local norm segs redirs t
  norm="$(printf '%s' "$cmd" | awk -v SQ="'" "$NORM_AWK")"
  case "$norm" in
    *'$SCANSICODE'*)
      block "\$'...' text with backslash escapes is run as code here (bash -c, env -S, eval, source, a pipe into a shell, or a here-string), so the guard cannot read it." "$MSG_ANSI" ;;
  esac
  # Normalize "N>" to ">", then collect redirection targets.
  norm="$(printf '%s\n' "$norm" | sed -E 's/(^|[[:space:]])[0-9]+'"$REDIR_OPS"'/\1\2/g')"
  redirs="$(printf '%s\n' "$norm" | grep -Eo "$REDIR_OPS"'[[:space:]]*&?[^[:space:];&|<>()]*')"
  while IFS= read -r t; do
    [ -z "$t" ] && continue
    t="$(printf '%s' "$t" | sed -E 's/^'"$REDIR_OPS"'[[:space:]]*//')"
    case "$t" in
      '&-'|'&'[0-9]|'&'[0-9][0-9]) continue ;;     # 2>&1, >&2, >&-: descriptor duplication
      '&'*) t="${t#&}" ;;                            # >&file writes to file
    esac
    add_target "$t"
  done <<EOF
$redirs
EOF
  # Remove redirections, split into simple commands, and analyze each.
  segs="$(printf '%s\n' "$norm" \
    | sed -E 's/'"$REDIR_OPS"'[[:space:]]*&?[^[:space:];&|<>()]*/ /g; s/<<?<?-?[[:space:]]*[^[:space:];&|<>()]*/ /g' \
    | tr ';&|()' '\n\n\n\n\n')"
  while IFS= read -r seg; do
    [ -z "$seg" ] && continue
    analyze_segment "$seg"
  done <<EOF
$segs
EOF
  while IFS= read -r t; do
    [ -z "$t" ] && continue
    check_write_target "$t"
  done <<EOF
$TARGETS
EOF

  # --- Shell writes to the test marker, test reports, constitutional files, evidence
  # A write target was checked above. These rules also catch other file-changing
  # command words (rm, chmod, git checkout, ...) when the command names the path.
  # The command word must be in command position (HAS_OP), so a read-only command that
  # merely mentions .claude/ or docs/agent-runs/ passes.
  local marker='\.claude/\.last-test-run'
  local const='(^|[^[:alnum:]_.-])(CLAUDE\.md|\.mcp\.json|docs/agents\.md|\.claude/|docs/agent-runs/)'
  if matches "$cmd" ">>?[[:space:]]*[\"']?[^[:space:]&]*$marker" || { matches "$cmd" "$marker" && [ "$HAS_OP" = 1 ]; }; then
    block ".claude/.last-test-run may be written only by the sbt markTestRun task after tests pass." \
      "Run sbt check."
  fi
  if matches "$cmd" ">>?[[:space:]]*[\"']?[^[:space:]&]*test-reports/" || { matches "$cmd" 'test-reports' && [ "$HAS_OP" = 1 ]; }; then
    block "sbt test reports (target/**/test-reports/**) are test evidence written only by sbt; no agent may write, copy, touch, or remove them by hand." \
      "$MSG_REPORTS"
  fi
  if matches "$cmd" ">>?[[:space:]]*[\"']?[^[:space:]&]*(CLAUDE\.md|\.mcp\.json|docs/agents\.md|\.claude/|docs/agent-runs/)" || { matches "$cmd" "$const" && [ "$HAS_OP" = 1 ]; }; then
    if [ "$AGENT" = "factory-engineer" ] \
       && matches "$cmd" '^[[:space:]]*chmod[[:space:]]+(\+x|u\+x|755|0755)([[:space:]]+(\./)?\.claude/hooks/[A-Za-z0-9_.-]+\.sh|[[:space:]]+[^[:space:]]*/\.claude/hooks/[A-Za-z0-9_.-]+\.sh)+[[:space:]]*$'; then
      return 0
    fi
    if [ -z "$AGENT" ]; then
      defer_ask "shell command changing constitutional files or human-owned evidence (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, docs/agent-runs/**). Constitutional edits belong to factory-engineer via Write/Edit; evidence belongs to the human."
    else
      block "shell writes to constitutional files or docs/agent-runs/** are denied for subagent '$AGENT'." \
        "Constitutional changes route to factory-engineer through the orchestrator; docs/agent-runs/** is human-owned evidence."
    fi
  fi
  return 0
}

# ==================================================================================
# B. File-path guard
# ==================================================================================
relpath() {
  local p="$1"
  case "$p" in
    "$PROJECT_DIR"/*) printf '%s' "${p#"$PROJECT_DIR"/}" ;;
    /*|\~*) printf '%s' "OUTSIDE:$p" ;;
    ./*) printf '%s' "${p#./}" ;;
    *) printf '%s' "$p" ;;
  esac
}

guard_path_read() {
  local p="$1"
  [ -z "$p" ] && return 0
  if is_secret_path "$p"; then
    block "reading secret material ('$p') is denied (.env*, keys, credentials, Terraform state, secret directories)." \
      "Use env.example placeholders and Secrets Manager references; never read real secret values."
  fi
}

guard_path_write() {
  local p="$1" rel owner
  [ -z "$p" ] && return 0
  if is_memory_path "$p"; then
    block "writing '$p' is denied: memory and local settings ($HOME_DIR/.claude/**, .claude/settings.local.json, CLAUDE.local.md) are not written during runs." "$MSG_MEMORY"
  fi
  case "/$p/" in */../*) block "paths containing '..' are denied ('$p')." "Use a normalized path inside your owned territory." ;; esac
  if is_secret_path "$p"; then
    block "writing secret material ('$p') is denied." "Commit only env.example placeholders; real secrets live in Secrets Manager."
  fi
  if is_test_report_path "$p"; then
    block "writing sbt test reports ('$p') is denied; they are the test evidence verify-tests-ran.sh reads and only sbt writes them." "$MSG_REPORTS"
  fi
  rel="$(relpath "$p")"

  case "$rel" in
    OUTSIDE:/private/tmp/claude-*|OUTSIDE:/tmp/claude-*) return 0 ;;
    OUTSIDE:*)
      [ -z "$AGENT" ] && ask "write outside the repository (${rel#OUTSIDE:})."
      block "subagent '$AGENT' may not write outside the repository (${rel#OUTSIDE:})." "Write only inside your owned territory."
      ;;
  esac

  owner="$(owner_of "$rel")"

  if [ "$owner" = "generated:sbt-markTestRun" ]; then
    block ".claude/.last-test-run is generated by the sbt markTestRun task after tests pass; no agent may write it." "Run sbt check."
  fi

  case "$rel" in
    releases/*) block "'$rel' is a release record; records under releases/** are written only by approved scripts that deploy-engineer runs, never by hand." \
                  "Run the approved script through /deploy, or put the record in your final report." ;;
  esac

  if [ -z "$AGENT" ]; then
    # Main orchestrator session: it routes work and must not perform specialist work inline.
    if [ "$owner" = "human" ]; then
      ask "'$rel' is human-owned evidence (docs/agent-runs/**). Approve only if the human is directing this record."
    fi
    ask "the orchestrator is about to write '$rel', owned by '$owner'. The orchestrator must delegate specialist work to the owning agent."
  fi

  if [ "$AGENT" = "dependency-updater" ] && [ -n "$DEPENDENCY_LEDGER_PATHS" ]; then
    local lp
    for lp in $DEPENDENCY_LEDGER_PATHS; do
      [ "$rel" = "$lp" ] && return 0
    done
  fi

  if [ "$owner" = "$AGENT" ]; then
    return 0
  fi

  case "$owner" in
    human) block "'$rel' is human-owned evidence under docs/agent-runs/**; no agent may create or edit it." "Return your final report to the orchestrator; the human archives it." ;;
    unowned) block "'$rel' has no owner in docs/agents.md (ambiguous ownership)." "Return BLOCKED-ON factory-engineer with the path so the human can ratify an owner." ;;
    *) block "'$rel' is owned by $owner, not '$AGENT' (one writer per artifact)." "Decline, and return BLOCKED-ON $owner with the needed change so the orchestrator can route it." ;;
  esac
}

case "$TOOL" in
  Bash)
    guard_bash "$(jget tool_input.command)"
    # Every block rule has run; only now may a deferred ask be returned.
    [ -n "$PENDING_ASK" ] && ask "$PENDING_ASK"
    ;;
  Read)
    guard_path_read "$(jget tool_input.file_path)"
    ;;
  Grep|Glob)
    guard_path_read "$(jget tool_input.path)"
    ;;
  Write|Edit|MultiEdit)
    guard_path_write "$(jget tool_input.file_path)"
    ;;
  NotebookEdit)
    guard_path_write "$(jget tool_input.notebook_path)"
    ;;
esac

exit 0
