review-report-L-01-02: code-reviewer verdict on repairs L-01 and L-02 (Phase 15 session 3)

Both repairs are approved. I found no critical or major issue. There are four minor findings (L-03 to L-06); none of them blocks the next gate.

Work order: docs/agent-runs/phase-15/prompt.md (sha256 3d806922…dfa3e0). I read decision 9.3 (lines 236-241) and the context near lines 160-179 and 540. HEAD is 6a6c31a. According to the session-start git status, the only uncommitted tracked changes are sfn.tf, c-embedding-backlog.sh and f-poison-replay.sh. The untracked folder experiments/raw/p15-e-retrieval-modes/ is outside this review.

## VERDICT: APPROVE

| Repair | Owner | Verdict |
|---|---|---|
| L-01 (infra/terraform/modules/workflow/sfn.tf) | infra-engineer | APPROVE |
| L-02 (experiments/collectors/f-poison-replay.sh, c-embedding-backlog.sh) | experiment-engineer | APPROVE |

## Files read

Read in full:
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/diff-L-01.txt
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/diff-L-02.txt
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/repair-D7a-infra-engineer.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/repair-D10-experiment-engineer.md
- /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/8119bd02-01ec-478f-b947-f2f035600de8/scratchpad/phase15-reports/runbook-log.md
- /Users/brian/Developer/repos/scholarchain/infra/terraform/modules/workflow/sfn.tf (692 lines)
- /Users/brian/Developer/repos/scholarchain/experiments/collectors/f-poison-replay.sh (710 lines)
- /Users/brian/Developer/repos/scholarchain/experiments/collectors/c-embedding-backlog.sh (615 lines)
- /Users/brian/Developer/repos/scholarchain/modules/rag/src/main/scala/org/scholarchain/rag/embedding/EmbeddingModelSpec.scala
- /Users/brian/Developer/repos/scholarchain/experiments/manifests/p15-a-hadoop.json

Read in part:
- experiments/collectors/lib/collector-common.sh: lines 90-114 (cc_info, cc_die) and 755-789 (cc_registry_latest)
- modules/core/.../config/ScholarChainConfig.scala: lines 180-209, plus grep for providerKind (:233)
- modules/core/.../Enums.scala: grep for fromWire (:19-22)
- experiments/collectors/ab-pipeline.sh: lines 300-389 (how it reads the managed-scaling policy)
- docs/agent-runs/phase-15/prompt.md: lines 160-179 and 236-241
- config/course.json: grep (:16 "voyage-4", :61 "voyage", :63 1024)

Grep only:
- Storage: VectorIngestPipeline.scala:518/599, VectorReembedder.scala:350/367
- rag: VoyageEmbedder.scala:78, BedrockTitanEmbedder.scala:75, FakeLexicalEmbedder.scala:23, RagIngestJob.scala:453, ReembedHandler.scala:347
- infra/terraform/modules/emr/main.tf:28-71 and course main.tf:92-94
- experiments/README.md:136, experiments/results-summary-template.md:85, p15-a-spark.json, p15-b-spark-burst.json
- Repository-wide search for clusterComputeLimits, ManagedScalingPolicy, ReadInput and CreateHadoopCluster

Not read: emr/variables.tf, identity/boundary.tf, identity/emr.tf, scripts/run-experiment.sh, and the rest of the lib.

## L-01 check results (sfn.tf)

Same request for absent input and for values 3 and 4: yes.
- The fields moved out unchanged: `cluster_common` keeps LogUri, ServiceRole, JobFlowRole, VisibleToAllUsers, BootstrapActions, Instances (both instance groups, the security groups, KeepJobFlowAliveWhenNoSteps, TerminationProtected), StepConcurrencyLevel, AutoTerminationPolicy and Tags (sfn.tf:147-160). `cluster_kinds` keeps Name, ReleaseLabel and Applications for both kinds (sfn.tf:163-174). The diff shows the values unchanged.
- `$merge` order is common, then kind, then scaling (sfn.tf:235). The keys do not overlap, so nothing is overwritten.
- The ComputeLimits expressions for the default and given cases (:213-214) are the same text that was deployed and tested at D4a in sitting 1. So the ManagedScalingPolicy value is the same for absent input, 3 and 4. Only its position among the request members changes, and the report states this.
- The Terraform default branch `scaling_policy_default` (:220) gives the policy, because max 4 > min 2.

Invalid values still fail in ReadInput:
- `units_error` sits in the same nested conditional as before (:221-228). Both Assign values (:304-305) evaluate it, so `$error` raises in the Pass state, before any Task state.
- The error text is unchanged. `null`, a string, 2.5, 1 and 5 all reach `$error` as before.

JSONata validity:
- `jsonencode` produces JSON object, array, string, number and boolean literals. JSONata parses these as constructors, including double-quoted keys and the `\uXXXX` escapes Terraform uses for `<`, `>` and `&`.
- `{}` and every object constructor appear only in prefix position (after `[`, `,`, `?`, `:`), so none is read as JSONata's postfix group-by operator.
- The precedence of the inner ternary `(X = min ? {} : policy)` is explicit.
- A Terraform `%{` directive cannot occur: the strings contain `{%` and `%}` only, and interpolation results are not re-parsed. `terraform validate` passed.
- Variable scope:
  - The two new variables read only `$states.input`, never another variable from the same Assign.
  - The create states are top-level, not inside Map or Parallel.
  - `Arguments = "{% $var %}"` is the documented single-expression form.
- Unverified (stated by the owner): whether the createCluster.sync integration accepts an expression for Arguments. If it does not, either validation or the apply fails, or the create Task fails before any cluster exists. All of these fail safe.

No IAM or other resource change: the diff touches only locals and the States map. `data.aws_iam_policy_document.sfn` and the role are unchanged. The expected plan is an in-place update of `module.workflow.aws_sfn_state_machine.pipeline` (the definition) only.

Consumers:
- No remaining reference to `clusterComputeLimits` outside docs/agent-runs.
- ab-pipeline.sh:342-345 records `managedScalingComputeLimits: null` when there is no policy and does not fail.

The D4a line set:
- Runs `test-state` only on the Pass state ReadInput.
- Prints byte counts, the validate result and codes, status, error name, next state, the ComputeLimits values or "absent", one boolean and the conf string.
- Never prints an ARN, bucket, account or `cause`.
- `restSameAsBefore` uses jq `==`, which ignores member order. It compares against the deployed literal Arguments, which is a strong check of the static fields.
- The same `test-state --definition … --state-name ReadInput` form worked in sitting 1 (runbook-log row D4a).

## L-02 check results (collectors)

ID composition:
- `MODEL="${EMB_PROVIDER}/${EMB_MODEL_NAME}/${DIMS}/document/emb-v1"` (f:196, c:183) matches `EmbeddingModelSpec.id` (EmbeddingModelSpec.scala:35), `SpecVersion` (:52), `InputType.Document.wire` (:11) and `fromConfig` (:113-118).
- Provider wires are voyage, bedrock and fake (ScholarChainConfig.scala:191-195), matched exactly (Enums.scala:21).
- Stored vectors are document-side (RagIngestJob.scala:453, ReembedHandler.scala:347). Storage writes `embedder.modelId` (VectorIngestPipeline.scala:518, VectorReembedder.scala:367).
- With config/course.json at HEAD the result is `voyage/voyage-4/1024/document/emb-v1`.

Lib contract: unchanged. collector-common.sh:781 compares exact strings for `embeddingModel.S` and `embeddingDimensions.N`.

Bash 3.2 and `set -Eeuo pipefail`:
- Uses only `local`, `[[ =~ ${re} ]]` with a variable and a `case` alternation.
- The `&& … || cc_die` lists do not fire the ERR trap.
- The constants are defined before use, so `-u` is safe.
- `($dims | tonumber)` runs only after the positive-integer check.
- One edge case did not change with this repair: a missing config/course.json makes jq fail inside `$(…)`, and the script exits through `set -e` with jq's code, not 3.

Exit codes: 3 for a missing or invalid config part (f:189-195, c:176-182). The check-mismatch paths are still 3 (f:270, c:380). The usage text is unchanged.

Nothing sensitive printed or recorded: only the model ID, the dimensions and the provider wire. URLs and table names stay unprinted, as before.

Preflight is still read-only: `expected_model_id` reads only a local file. F's preflight runs read_names, read_settings, select_valid_key and baseline before `preflight`, with nothing sent. C runs select_keys and check_sample.

Lib and manifests untouched:
- The session-start git status lists neither collector-common.sh nor any manifest as modified, so they match HEAD.
- At HEAD, the F and C manifests carry the approved sha256 values a39c7e45… and 36352e8f… (runbook row D9 and commit b0c92f5).
- I could not recompute sha256 myself (no Bash tool). The owner's shasum values match.

Every other use of MODEL in both files was checked (F: :196, 266, 270, 275, 644; C: :183, 355, 368, 376). No stale use of "voyage-4" remains.

## Findings

| ID | Severity | Interface | Owner | file:line | Failure scenario and impact |
|---|---|---|---|---|---|
| L-03 | minor | no | experiment-engineer (human decides on any manifest change) | experiments/manifests/p15-a-hadoop.json:7, :64 (p15-a-spark.json same wording, by grep); experiments/results-summary-template.md:85; prompt.md:540 (human-owned, for information only) | **Description wording:** A is described as "EMR managed-scaling maximum set to 2 … (= the minimum 2)" and "managed scaling, min FILL, max 2 (A)". After L-01, A runs with no ManagedScalingPolicy: a fixed 1 primary + 2 core cluster, which is the same capacity. ab-pipeline will record `managedScalingComputeLimits: null`. **Metrics:** the manifest's 60 s EMR *Nodes* series may come back empty or coarse, so the runner may classify A's metrics as `partial` (exit 6) even though the action succeeded. Whether CoreNodesRunning/Requested exist without managed scaling is unverified in the repository, so this is a risk, not a certainty. **Impact:** the reporting and interpretation for A could misstate how the flat capacity was achieved. **Fix:** an interpretation note at analysis time, or a re-approved manifest if the human chooses one. L-01 itself is not wrong. |
| L-04 | minor | no | infra-engineer | infra/terraform/modules/workflow/sfn.tf:225 with modules/emr/main.tf:33 | **What can happen:** the emr precondition allows `core_instance_count` anywhere from min to max. If it were raised to 3 with min 2, an execution with `managedScalingMaxUnits = 2` would get no policy. It would then run fixed at InstanceCount 3, above the maximum the caller asked for, and cost more than the manifest assumes. **Why it is minor:** core 2 = min 2 today, so this cannot happen now. The owner already lists the matching precondition under "proposed later". |
| L-05 | minor | no | infra-engineer | repair-D7a-infra-engineer.md, D4a "Expected" (line 66) | **The estimate:** the report expects the definition "larger than D3's 12,931 … about +6 to +8 KB". The diff, however, moves the static request fields out of the two create states rather than duplicating them. The growth is only the `\"` escaping of those fields inside JSON strings plus a second copy of the scaling expression, so the real increase is probably 1-2 KB (my estimate from the diff, not measured). **Impact:** the gate is only "bytes > 0", but a human comparing against +6-8 KB might stop needlessly. Treat the size as information only. |
| L-06 | minor | no | experiment-engineer | experiments/README.md:136 | **What it says:** `cc_registry_latest` checks `embeddingModel` "against config/course.json". **What is true now:** the expected value is config plus the rag code constants `document` and `emb-v1`, copied into both collectors (f:51-52, c:46-47). **Impact:** the README does not say that the copied `EMB_SPEC_VERSION` must change when rag bumps `EmbeddingModelSpec.SpecVersion`. The comments in the two collectors do say it. |

No findings for ownership (each repair stayed in its owner's territory), security, IAM, secrets, injection or cleanup.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15 session 3, review of repairs L-01 (infra-engineer) and L-02 (experiment-engineer), report ID review-report-L-01-02; docs/agent-runs/phase-15/prompt.md sha256 3d806922…dfa3e0 (decisions 9.3, 10, 11, 12, 13); HEAD 6a6c31a
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools: Read, Grep, Glob)
TEST RESULTS: not applicable (no tests apply; I ran nothing). The owners report terraform fmt/validate exit 0 (L-01) and shellcheck -x clean with no new plain-shellcheck codes (L-02). I did not observe these myself.
VALIDATION RESULTS:
- L-01: same request for absent input, 3 and 4 (verified from code); invalid values fail in ReadInput; JSONata constructs valid as far as I can verify statically; no IAM change.
- L-02: the ID matches EmbeddingModelSpec.id; the lib and manifests are unchanged according to git status.
ASSUMPTIONS:
- Step Functions' JSONata (2.0.6) handles `{}`, double-quoted JSON object literals and `\u` escapes as standard JSONata does.
- createCluster.sync accepts an Arguments expression. This is unverified; D4a validate and the apply check it, and both fail safe.
- The approved manifest sha256 values at HEAD are as recorded in runbook row D9. I could not compute sha256 myself.
RISKS:
- A's capacity metrics may be classified `partial` without a managed-scaling policy (L-03).
- The first true end-to-end check of the expression-based create arguments is D7a attempt 2, because test-state covers only ReadInput.
- `EMB_SPEC_VERSION` is a copied constant and can drift from rag.
MANUAL ACTION REQUIRED: the human commits the three files; plan.sh (runtime-on) runs; D4a lines run (read the byte-size estimate as information only, L-05); human signature; deploy.sh; D7a attempt 2. Decide whether A's interpretation note or manifests change (L-03). Repeat D9a --check of p15-f-poison-replay.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit of the L-01/L-02 repairs, then the runtime-on plan and D4a lines, then signing and deploy, then D7a attempt 2 and the D9a F re-check. Minor findings L-03 to L-06 go to the open-findings list, routed by the orchestrator (no repair round required).
