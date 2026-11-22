# agent-report-infra (Phase 16, session 2, step I1)

STATUS: BLOCKED-ON
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, checked with shasum), HEAD 9da45ab616a764e449f74f25feca85ed77d6dfe4 (checked); decision 12 items 0-7; standing rules; decisions 4, 6.6, 17 (E14).
FILES CREATED:
- scripts/emulator/OPERATIONS.md (item 0)
- config/emulator.json (item 1)
- scripts/emulator/env.sh (item 2; sourced)
- scripts/emulator/lib.sh (shared safety contract, CLI wrapper, result lines; sourced by setup.sh and probes.sh)
- scripts/emulator/setup.sh (item 3)
- scripts/emulator/probes.sh (item 4)
FILES MODIFIED:
- scripts/README.md (item 5: section "AWS Emulator (Phase 16, decision 12; run book step E14)")
- scripts/deploy.sh (L-07)
- scripts/lib/identity.sh (L-12 `sc_output_value`; G-04 `er_upload_plan`)
- scripts/cleanup.sh (L-12, L-14, L-15)
- scripts/check.sh (L-12 and G-04 in-process probes in gate 9; help text)
- scripts/run-bounded-pipeline.sh (G-04: ER upload selection through `er_upload_plan`)
- scripts/run-experiment.sh (FECD-4)
COMMANDS RUN: `shasum -a 256` (work order); `git rev-parse HEAD`, `git status --short`, `git diff --stat`; `ls`, `wc -l`, `head`, `cut`; `grep -n`/`grep -rn` with literal words (the Grep tool was not available in this session: "No such tool available: Grep"); `shellcheck --version`; `shellcheck` and `shellcheck -x [-P SCRIPTDIR]` on literal paths; `jq empty config/emulator.json`, `jq empty config/course.json`; `terraform fmt -recursive -check infra/terraform`; `terraform -chdir=infra/terraform/environments/course validate`. aws-docs MCP (read only): getting-started-docker, point-sdk-to-emulator, cli-reference, telemetry, fidelity, what-is-service, check-project, security, versioning; S3 Vectors CreateIndex API page. No aws-api call. No terraform MCP call (no HCL written). **Commands used that are not on the work order's list (all read-only, nothing written):** a `cd /Users/brian/Developer/repos/scholarchain && ...` prefix on most Bash calls until I noticed the rule (later calls use the working directory); one `cat` of agent-report-build.md (scratchpad); one `sed -n '240,253p' scripts/check.sh | head -0` (printed nothing; head refused `-0`); one `awk` print of line numbers in scripts/run-bounded-pipeline.sh; one `pwd`; `ls -lT` on the test marker and build files. No approval prompt was seen. No script, sbt, bash, docker, aws CLI, python or emulator was run.
TEST RESULTS: not applicable (no sbt; scripts have no test harness; the new gate 9 probes in check.sh run first at E2).
VALIDATION RESULTS: see VALIDATION EVIDENCE: shellcheck exit 0 on every changed or new script; jq empty exit 0; terraform fmt -check exit 0; validate "Success! The configuration is valid." exit 0.
ASSUMPTIONS:
- A1. The published fidelity scores and manifest live on GitHub (fidelity.html, check-project.html), unreachable in this phase; so 29 called operations are `unknown` (probes), per the work order's fallback.
- A2. The emulator safety contract's "must be unset" means set at all, even to an empty value, is refused (lib.sh `${!v+x}`). If storage-engineer's Scala check treats an empty value as unset, the two differ in that one case; the orchestrator's fixed-interface comparison should check it.
- A3. Queue names are fixed (`scholarchain-emu-jobs`, `-embedding`, each `-dlq`) because no config key holds a queue name, and the queue URL keys stay unset (an emulator queue URL may hold the account; its format is not documented). Suites resolve URLs with SQS GetQueueUrl at run time.
- A4. probes.sh (a) uses the Phase 15 D3 plan `20261120T211840Z-85dacb447057-8820` (the work order names D3; D3b was the plan later deployed) and its four D4a inputs, compared with the D4a sitting 1 real-AWS row (runbook-log.md line 26). `--plan-name` selects another plan.
- A5. `aws.accountId` is required by ConfigLoader (12 digits) and used only for masking (StorageLoadMain.scala:339, RagIngestJob.scala:446, RetrievalEvalJob.scala:258); config/emulator.json holds the documentation placeholder `123456789012`, never the emulator's account.
- A6. The role rule "config holds no bucket name" is overridden for this phase by the standing rule allowing synthetic `scholarchain-emu-` names (work order "Secrets and identifiers").
RISKS:
- R1. check.sh gains two in-process gate 9 probes (output-value, er-upload-plan). They run first at E2 (fresh clone); a mistake there would fail gate 9. I traced both by hand (expected lines follow LC_ALL=C order: `_` < `d` < `r`, `review` < `run-report`).
- R2. run-bounded-pipeline.sh's ER upload loop now reads `er_upload_plan` output (same selection rule, moved); the ER stage does not run in this phase (runtime: untested).
- R3. env.sh's prompt prefix may be redrawn by a zsh prompt theme; the INFO line still says the terminal targets the emulator.
- R4. CLI-level latencies include CLI start-up; httpStatus is null for answered calls (the CLI prints no status), 501 for NotImplemented.
- R5. L-07: the identifier class the D20 warning named is not in the frozen evidence (the `.apply.txt` is not read by rule). The fix covers the class the code shows unmasked: the endpoint values of the Terraform outputs (API host, queue and DLQ URLs, ALB and Neptune hosts), which deploy.sh never registered, and values created by the apply (re-mask after it). If the D20 warning was another class, it stays open.
MANUAL ACTION REQUIRED: none from this step. No `chmod +x` is needed: the existing scripts carry no executable bit and the run book calls `bash scripts/emulator/setup.sh` and `bash scripts/emulator/probes.sh`; env.sh and lib.sh are sourced.
BLOCKED-ON OWNER: orchestrator
BLOCKED-ON EVIDENCE: stop hook: in-progress files of build-engineer (build.sbt, project/PackageCheck.scala, project/BuildSelfTest.scala). `ls -lT`: `.claude/.last-test-run` Nov 20 14:36:22, build.sbt Nov 21 19:25:54, project/BuildSelfTest.scala Nov 21 19:25:43, project/PackageCheck.scala Nov 21 19:20:52; build.sbt and project/** always need the full marker. None of these paths is in my territory; every item assigned to I1 is done or marked `pending step 1` as instructed (L-09 check.sh side). Waiver candidate under decision 4 / planned deviation 8.
NEXT GATE: R-2 (code-reviewer: X1, I1, step 1 Dockerfile); orchestrator fixed-interface comparison (decision 6.6 contract and result line vs storage step 3); step 3 reads `scripts/emulator/OPERATIONS.md` and `config/emulator.json`; W1 reads the README emulator section.

## Items (decision 12)

| Item | Tier | Status | Where |
|---|---|---|---|
| 0 operation inventory | 1 | done | scripts/emulator/OPERATIONS.md: 3 documented supported (STS GetCallerIdentity, S3 CreateBucket, DynamoDB CreateTable), 29 unknown (probes), Neptune data out of scope (not called), policy enforcement documented unsupported, S3 Control documented unsupported (not called); every fact cited with its URL; resource-name table for step 3 |
| 1 config/emulator.json | 1 | done | region us-east-1; names `scholarchain-emu-*`; no neptune section (`storage.neptuneEndpoint` is the required base key, set to the non-resolving placeholder `scholarchain-emu-neptune-absent.invalid`); embedding and llm provider `fake`; service section without queue URLs; checked by hand against ConfigLoader.scala:96-243 and ConfigValidator (:565-927) |
| 2 env.sh | 1 | done | zsh- and bash-safe; refuses outside the repository root (return 4); unsets the eight contract variables, the four ScholarChain key variables and all 90 `SCHOLARCHAIN_CFG_*` names of ConfigLoader.allKeys; exports the documented eight plus `SCHOLARCHAIN_EMULATOR_ENDPOINT` and `SCHOLARCHAIN_CONFIG_FILE="$PWD/config/emulator.json"`; `[EMULATOR] ` prompt; never sets JAVA_TOOL_OPTIONS (warns if set) |
| 3 setup.sh | 1 | done | contract, config names check, health, account; creates buckets, policy bucket with deny-all policy, six tables (+TTL), vector bucket + five indexes from the spec, two queue pairs with maxReceiveCount 5; each create recorded created/exists/not-implemented/error code; independent creates continue; exit 6 at the end on any failed create |
| 4 probes.sh | 1 | done | (a) SFN validate + 4 TestState vs D4a, `not-exercised (input missing)` if the plan file is absent; (b) GetVectorBucket, ListIndexes; (c) M-01 on a separate probe vector bucket |
| 5 README emulator section | 1 | done | scripts/README.md, 15 one-line steps, zsh-safe, no `\`, no `<` |
| L-09 check.sh side | 1 (only if step 1 names one) | pending step 1 | agent-report-build.md had no INTERFACE HANDOFF at 19:27 or at my last look. My reading: no check.sh change is needed: gate 8 (b) (check.sh:676-686) greps the quoted class in project/PackageCheck.scala, which now has `"org.scholarchain.rag.eval.RetrievalEvalJob"` at :35 (build-engineer's in-progress file); the script names it at run-bounded-pipeline.sh:1833 |
| L-07 | 2 | done | deploy.sh apply_plan registers the endpoint values of the current outputs before the apply (`register_output_identifiers`); verify_deployment registers the new outputs' values and masks the saved `.apply.txt` again in place (`remask_apply_text`) before the identifier check. Fixed in code, not tested (no test harness for scripts), not validated live |
| L-12 | 2 | done | identity.sh `sc_output_value`; cleanup.sh `out()` calls it; check.sh gate 9 probe `output-value` (true, false, 0, string, null, missing; and that cleanup.sh's out() line still calls it) |
| L-14 | 2 | done | cleanup.sh check_protection_off: only an explicit `false` passes for DynamoDB `DeletionProtectionEnabled` and Neptune `DeletionProtection`; missing counts as protected (refused before CONFIRM) |
| L-15 | 2 | done | cleanup.sh check_leftovers: `removed_record` checks each unexpected tagging-API entry with one read-only describe call (NAT gateway, VPC endpoint, security group, security-group rule, EMR cluster, ECS cluster and service); removed ones are listed as REMOVED and not counted; other types or failed calls stay counted (fail closed) |
| FECD-4 | 2 | done | run-experiment.sh on_exit ignores INT and TERM while finalizing, so a second Ctrl-C cannot stop the record, move and read-only step (D-read attempt 2 was recorded and moved but not made read-only). Exit code: INT stays 5, which matches the confirmed exit table in experiments/README.md:121 ("Ctrl-C (INT: status `failed`, signal INT, no export)"); Phase 15 decision 10's table lists only TERM under 7, so FECD-4's exit-code half is not a code defect; the usage text now states the INT row |
| G-04 | 2 | done | identity.sh `er_upload_plan` (the selection rule moved from run-bounded-pipeline.sh); check.sh gate 9 probe `er-upload-plan` on a fixture ER tree (identity-assignments-only, full, unknown mode refused) |
| 7 validation | 1 | done | see VALIDATION EVIDENCE |

Plan summary and checksum: no Terraform change; no plan made (none permitted). IAM changes: none. Stateful-resource actions: none. Cost estimate: $0 (estimate; basis: emulator is local, no AWS call, no paid model call; cleanup.sh's new describe calls run only at a future live `--remove`).

## Emulator account handling (how the twelve-zero literal is kept out)

lib.sh `emu_account_matches` builds the expected value at run time with `printf '%012d' 0`, compares it with `aws sts get-caller-identity --query Account --output text` (held in a local variable; the temporary output file is truncated at once and the private temp folder is removed at exit), and the scripts print only `emulator account confirmed: yes|no`. `grep -rn` of twelve zeros over scripts/ and config/ finds only the pre-existing 64-zero wrong-checksum probe at check.sh:935 (not mine; a hash-like run).

## Runtime lines

- scripts/emulator/env.sh: runtime: untested
- scripts/emulator/lib.sh: runtime: untested
- scripts/emulator/setup.sh: runtime: untested
- scripts/emulator/probes.sh: runtime: untested
- scripts/deploy.sh L-07 change: runtime: untested
- scripts/cleanup.sh L-12, L-14, L-15 changes: runtime: untested
- scripts/lib/identity.sh `sc_output_value`, `er_upload_plan`: runtime: untested (first run: check.sh gate 9 at E2)
- scripts/check.sh gate 9 probes output-value, er-upload-plan: runtime: untested (first run: E2)
- scripts/run-bounded-pipeline.sh G-04 change: runtime: untested
- scripts/run-experiment.sh FECD-4 change: runtime: untested
- Every fix: fixed in code, tested offline (static only: shellcheck), not validated live.

## Required environment variables (names only)

Read by setup.sh and probes.sh: SCHOLARCHAIN_EMULATOR_ENDPOINT, AWS_ENDPOINT_URL, AWS_SHARED_CREDENTIALS_FILE, AWS_CONFIG_FILE, SCHOLARCHAIN_CONFIG_FILE, SCHOLARCHAIN_EMULATOR_RESULTS_DIR (setup: optional; probes: required), SCHOLARCHAIN_EMULATOR_RUN_ID (probes, optional), HOME, TMPDIR; must be unset: AWS_PROFILE, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN, AWS_ROLE_ARN, AWS_WEB_IDENTITY_TOKEN_FILE, AWS_CONTAINER_CREDENTIALS_RELATIVE_URI, AWS_CONTAINER_CREDENTIALS_FULL_URI. Set by env.sh: AWS_ENDPOINT_URL, AWS_SHARED_CREDENTIALS_FILE, AWS_CONFIG_FILE, AWS_CA_BUNDLE, AWS_EC2_METADATA_DISABLED, AWS_DISABLE_HOST_PREFIX_INJECTION, AWS_REGION, AWS_DEFAULT_REGION, SCHOLARCHAIN_EMULATOR_ENDPOINT, SCHOLARCHAIN_CONFIG_FILE. Set per call by lib.sh: AWS_MAX_ATTEMPTS=1, AWS_PAGER="".

## INTERFACE HANDOFF

- Files: config/emulator.json; scripts/emulator/{env.sh, lib.sh, setup.sh, probes.sh, OPERATIONS.md}; scripts/README.md emulator section; scripts/lib/identity.sh (`sc_output_value OUTPUTS_JSON NAME`, `er_upload_plan OUT_ROOT RUN_DIR MODE`).
- Safety contract as implemented in scripts (lib.sh `emu_safety_check MODE`, first failing rule only, name only): `endpoint` (SCHOLARCHAIN_EMULATOR_ENDPOINT == `https://127.0.0.1:9297`), `aws-endpoint-url` (AWS_ENDPOINT_URL equals it), `credentials-file` (AWS_SHARED_CREDENTIALS_FILE == `$HOME/.aws/emulator/9297/emulator_credentials`; HOME must be absolute), `credentials-file-exists`, `config-file` (AWS_CONFIG_FILE == `$HOME/.aws/emulator/9297/config`), `unset:NAME` (the eight; set-but-empty counts as set), `config-file-path` (SCHOLARCHAIN_CONFIG_FILE absolute), `results-dir-path` (SCHOLARCHAIN_EMULATOR_RESULTS_DIR absolute when set; probes: required). Then health route `ok` and STS account match, before any create.
- Every AWS CLI call: `aws --endpoint-url "$SCHOLARCHAIN_EMULATOR_ENDPOINT" --ca-bundle "$HOME/.aws/emulator/9297/ca.pem" --region us-east-1 --cli-connect-timeout 5 --cli-read-timeout 30 --no-cli-pager --output json ...` under `timeout --kill-after=5 60` (lib.sh `emu_aws`); no other aws invocation in scripts/emulator/.
- Config keys and resource names: `storage.rawBucket`, `curatedBucket`, `graphBucket`, `objectStore.semanticContextBucket`, `reportsBucket`, `logsBucket` = `scholarchain-emu-data`; `storage.vectorBucket` = `scholarchain-emu-vectors`; `dynamoDb.*Table` = `scholarchain-emu-run-ledger`, `-entity-link-cache`, `-query-session` (TTL `expiresAt`), `-evidence-bundle`, `-artifact-registry`, `-researcher-metric-cache`; `vectors.*Index` = the five spec names; fixed (no key): `scholarchain-emu-policy-probe` (deny-all bucket policy: Deny, Principal `*`, s3:GetObject, s3:PutObject, s3:ListBucket), queues `scholarchain-emu-jobs` (visibility 150, retention 86400, maxReceiveCount 5), `scholarchain-emu-jobs-dlq`, `scholarchain-emu-embedding` (visibility 300, retention 345600, maxReceiveCount 5), `scholarchain-emu-embedding-dlq` (retention 1209600); probes only: `scholarchain-emu-probe-vectors`, index `probe-v1` (dimension 4). `aws.accountId` = placeholder `123456789012`. No `service.jobQueueUrl`/`embeddingQueueUrl`.
- Result line (one JSON object per line): first `service` (lowercase CLI service: `sts`, `s3`, `dynamodb`, `s3vectors`, `sqs`, `stepfunctions`, or `preflight`), `operation` (API name, e.g. `CreateBucket`), `class` (`implemented-passed`, `implemented-failed`, `not-implemented`, `not-exercised`, `infrastructure-failure`), `httpStatus` (501 for NotImplemented, else null), `latencyMillis` (integer); then additive optional fields `resource`, `outcome` (setup: `created`, `exists`, `not-implemented`, `not-exercised`, or an error code), `probe` (probes: `a-validate`, `a-test-state-absent|A|B|invalid`, `b-get-vector-bucket`, `b-list-indexes`, `c-create-bucket`, `c-create-index`, `c-put-vector`, `c-get-vectors-baseline`, `c-get-bucket-arn`, `c-put-policy`, `c-get-vectors-after-deny`), `observation` (fixed wording; M-01 `allowed (...)` or `refused (...)`; D4a `... d4a-match=yes|no`), `errorCode`, `rule` (preflight only). Files: `$SCHOLARCHAIN_EMULATOR_RESULTS_DIR/setup.jsonl` (when set) and `/probes.jsonl` (required); an existing file is refused (exit 4). A refused contract writes one `{"service":"preflight","operation":"preflight","class":"infrastructure-failure","httpStatus":null,"latencyMillis":0,"rule":"NAME"}` line when the results folder itself is usable.
- Exit codes (setup.sh, probes.sh): 0 ok; 2 usage; 3 missing prerequisite (tool, config or spec file, results folder, health); 4 refused (contract, account, names, existing results file, not at the root); 5 unexpected command failure; 6 a create failed (setup) or a probe call was implemented-failed/infrastructure-failure (probes; results complete); 7 interrupted. Every line printed: `STEP:`, `INFO:`, `RESULT:`, `SUMMARY:`, `ERROR:` with the script name.
- Thread-safety and limits: one script at a time; at most 30 DescribeTable tries per table (1 s apart); one attempt per CLI call; no retries.
- check.sh gate 9 new probe names: `output-value`, `er-upload-plan` (recorded in probes.tsv like the others).

## Proposed later

- run-bounded-pipeline.sh:791 `out()` still uses `// empty` (drops false); it reads only string outputs today, so no defect now; it could call `sc_output_value` too.

## VALIDATION EVIDENCE

- `shellcheck -x -P SCRIPTDIR scripts/check.sh scripts/cleanup.sh scripts/deploy.sh scripts/run-bounded-pipeline.sh scripts/run-experiment.sh scripts/lib/identity.sh` -> exit 0 (no output)
- `shellcheck -x scripts/emulator/env.sh scripts/emulator/lib.sh scripts/emulator/setup.sh scripts/emulator/probes.sh` -> exit 0 (no output); shellcheck 0.11.0
- `jq empty config/emulator.json` -> exit 0; `jq empty config/course.json` -> exit 0
- `terraform fmt -recursive -check infra/terraform` -> exit 0
- `terraform -chdir=infra/terraform/environments/course validate` -> "Success! The configuration is valid." exit 0
- twelve-zero scan of scripts/ and config/: only check.sh:935 (pre-existing 64-zero checksum probe); host/ARN scan of scripts/emulator and config/emulator.json: no match
- files changed after these runs: none
