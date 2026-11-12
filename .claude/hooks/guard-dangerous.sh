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
#      KMS, EMR, ECS), destructive database/graph statements, remote-code piping,
#      shell writes to constitutional files or the test marker.
#   B. File-path guard for Read/Grep/Write/Edit/MultiEdit/NotebookEdit: secret paths,
#      the generated test marker, human-owned evidence, constitutional files, and the
#      one-writer ownership map in docs/agents.md (subagents only).
#
# Must stay compatible with macOS /bin/bash 3.2 (no associative arrays, no ${x,,}).

set -u
set -f   # never glob-expand tokens taken from tool input

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

# Dependency-ledger file(s) that dependency-updater may edit (version values only).
# Empty until the human ratifies the ledger path created by build-engineer (Phase 2).
DEPENDENCY_LEDGER_PATHS=""

matches() { printf '%s' "$1" | grep -Eiq -- "$2"; }

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
    build.sbt|project/*|.scalafmt.conf|.gitignore|.jvmopts|.sbtopts|docker/*|compose.yml|compose.yaml|docker-compose.yml|docker-compose.yaml|docker-compose.*.yml|docker-compose.*.yaml) echo "build-engineer" ;;
    modules/core/*) echo "domain-engineer" ;;
    datasets/*) echo "dataset-engineer" ;;
    modules/hadoop/*) echo "hadoop-engineer" ;;
    modules/entity-resolution/*) echo "entity-resolution-engineer" ;;
    modules/spark/*) echo "spark-engineer" ;;
    modules/storage/*) echo "storage-engineer" ;;
    modules/rag/*|prompts/*) echo "rag-engineer" ;;
    modules/services/*) echo "service-engineer" ;;
    tests/*) echo "test-engineer" ;;
    infra/*|scripts/*|.github/workflows/*) echo "infra-engineer" ;;
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

# ==================================================================================
# A. Bash command guard
# ==================================================================================
guard_bash() {
  local cmd="$1"

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
    ask "git commit / git push: only the human architect commits and pushes. Approve only if the human is performing this action."
  fi
  if matches "$cmd" '(^|[^[:alnum:]_-])git([[:space:]]+[^;&|]*)?[[:space:]](filter-branch|filter-repo|reflog[[:space:]]+(expire|delete)|update-ref[[:space:]]+-d|reset[[:space:]]+([^;&|]*[[:space:]])?--hard|clean[[:space:]]+([^;&|]*[[:space:]])?-[[:alnum:]]*f|branch[[:space:]]+([^;&|]*[[:space:]])?-D|gc[[:space:]]+([^;&|]*[[:space:]])?--prune=now)'; then
    block "history rewrite or irreversible working-tree destruction is denied." \
      "Leave the tree as is and report what should be reverted; the human decides."
  fi

  # --- Broad recursive deletes -----------------------------------------------------
  # Every shell segment containing a recursive rm is checked; each target must be a
  # relative build-output directory.
  local seg tok bad="" line
  while IFS= read -r line; do
    if matches "$line" '(^|[`(][[:space:]]*|[[:space:]])(sudo[[:space:]]+)?rm[[:space:]]+([^[:space:]]+[[:space:]]+)*(-[[:alnum:]]*[rR][[:alnum:]]*|--recursive)([[:space:]]|$)'; then
      seg="$(printf '%s' "$line" | sed -E 's/^.*(^|[`([:space:]])rm[[:space:]]+//')"
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
  if matches "$cmd" '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(ba|z|da|k)?sh([[:space:]]|$)'; then
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
      ask "direct AWS mutation. All AWS infrastructure is Terraform-managed and operational writes go through approved scripts; console or ad-hoc CLI mutation is not an implementation path. Approve only if this is a documented, bounded action."
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

  # --- Shell writes to the test marker, constitutional files, or evidence ----------
  local ops='(^|[^[:alnum:]_-])(tee|touch|cp|mv|rm|ln|install|truncate|chmod|chown|rsync|patch)[[:space:]]|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|perl[[:space:]]+-[a-zA-Z]*i|git[[:space:]]+(checkout|restore|apply|am)[[:space:]]'
  local marker='\.claude/\.last-test-run'
  local const='(^|[^[:alnum:]_.-])(CLAUDE\.md|\.mcp\.json|docs/agents\.md|\.claude/|docs/agent-runs/)'
  if matches "$cmd" ">>?[[:space:]]*[\"']?[^[:space:]&]*$marker" || { matches "$cmd" "$marker" && matches "$cmd" "$ops"; }; then
    block ".claude/.last-test-run may be written only by the sbt markTestRun task after tests pass." \
      "Run sbt check."
  fi
  if matches "$cmd" ">>?[[:space:]]*[\"']?[^[:space:]&]*(CLAUDE\.md|\.mcp\.json|docs/agents\.md|\.claude/|docs/agent-runs/)" || { matches "$cmd" "$const" && matches "$cmd" "$ops"; }; then
    if [ "$AGENT" = "factory-engineer" ] \
       && matches "$cmd" '^[[:space:]]*chmod[[:space:]]+(\+x|u\+x|755|0755)([[:space:]]+(\./)?\.claude/hooks/[A-Za-z0-9_.-]+\.sh|[[:space:]]+[^[:space:]]*/\.claude/hooks/[A-Za-z0-9_.-]+\.sh)+[[:space:]]*$'; then
      return 0
    fi
    if [ -z "$AGENT" ]; then
      ask "shell write touching constitutional files or human-owned evidence (CLAUDE.md, docs/agents.md, .claude/**, .mcp.json, docs/agent-runs/**). Constitutional edits belong to factory-engineer via Write/Edit; evidence belongs to the human."
    fi
    block "shell writes to constitutional files or docs/agent-runs/** are denied for subagent '$AGENT'." \
      "Constitutional changes route to factory-engineer through the orchestrator; docs/agent-runs/** is human-owned evidence."
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
  case "/$p/" in */../*) block "paths containing '..' are denied ('$p')." "Use a normalized path inside your owned territory." ;; esac
  if is_secret_path "$p"; then
    block "writing secret material ('$p') is denied." "Commit only env.example placeholders; real secrets live in Secrets Manager."
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
