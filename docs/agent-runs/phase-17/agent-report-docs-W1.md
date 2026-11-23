# agent-report-docs-W1 (documentation-engineer, Phase 17 session 2, step W1)

Progress:

- [x] docs/architecture.md (finished, link-checked)
- [x] docs/data-lineage.md (finished, link-checked)
- [x] docs/api.md (finished, link-checked)

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24, verified with shasum), decision 12 W1; HEAD 50ba087ecd33178eaa8ccc69286b9de85e8a8146 (verified with git rev-parse)
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/docs/architecture.md (249 lines, sha256 0d1e708114782354605dde33929e736faf5d5c7c3c99f2e98177770698252b4a)
  /Users/brian/Developer/repos/scholarchain/docs/data-lineage.md (192 lines, sha256 31d1225dd23c57e2e64a3fcd9e220ad7da671c9fca0024659d89dcaea4f9a37d)
  /Users/brian/Developer/repos/scholarchain/docs/api.md (318 lines, sha256 f2f77697710b6ea8991270d6f1cde7af2537e232413bc9c0feebde10bcff8cea)
  (scratchpad) phase17-reports/agent-report-docs-W1.md (this report)
FILES MODIFIED: none in the repository
COMMANDS RUN: read-only only: shasum, git rev-parse, git status --short, git show HEAD:<path>, git grep HEAD, git ls-files, git check-ignore -v, ls, wc, grep, head (see VALIDATION EVIDENCE and the deviations under RISKS). No sbt, no script, no network, no AWS, no MCP call.
TEST RESULTS: none run (documentation step; the work order forbids sbt for W steps)
VALIDATION RESULTS: link check 23 file targets: all exist, none ignored, 20 tracked + 3 created in this step; 35 fragment links: all match a heading (table below). Identifier scan of the three files: no match (details below).
ASSUMPTIONS:
  - "Phase 14 and 15 counts from the records" is met by the Phase 14 runbook log rows, the Phase 14 and 15 gates and experiments/results-summary.md sections 2.6 to 2.8; I did not open raw CSVs under experiments/raw/ (the summary cites them).
  - docs/service-limits.md and docs/final-state.md are named as W2 outputs in plain text (not as links), because they do not exist yet.
  - docs/api.md documents the claim verifier at HEAD 50ba087 while rag-engineer changes it (step 1); every L2 statement is marked "at 50ba087" and section 9 says W5 reconciles it.
RISKS:
  - Unplanned deviations (read-only, listed for the gate): (a) one Bash `grep -n -i 'api_url'` on the named file infra/terraform/environments/course/outputs.tf (the standing rule says Read tool only there; no state file was touched or matched; I then re-read the file with the Read tool); (b) read-only use of `cd <repo> &&` prefixes, `sed -n`, `cat`, `awk`, `sort -u`, `tr` and `cut` in Bash for reading files and listing headings, which the Phase 17 command list forbids (no file was written by them).
  - The 1.4.7.1 Neptune engine version in docs/architecture.md matches the S-ID IPv4 pattern; it is a version string, not an address.
  - Claims about the L2 rule, reason texts and claim statuses in docs/api.md section 9 may change with step 1 (listed in INTERFACE HANDOFF for W5).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: W2 (documentation-engineer); R-D1 reviews these three files after W5; W5 reconciles docs/api.md section 9 with step 1's final handoff.
```

## Replayed commands and outcomes

None. Planned deviation 1: documentation-engineer does not replay commands. The three W1 files contain **no shell command** (the API examples are HTTP exchanges from committed fixtures and archived records, not commands), so no replay line is needed in them. The one script line quoted, `API_URL="${API_URL%/}"`, is a quotation of `scripts/smoke-test.sh:333`, not a command to run.

## Link-check results

Method (decision 12): `ls TARGET`, `git check-ignore -v TARGET` (must print nothing), `git ls-files TARGET` (tracked, or created in this step); fragments checked against the target file's headings (`grep -n '^#'`), GitHub anchor rule (lowercase, spaces to hyphens, punctuation dropped).

| Link target (repository path) | Used in | ls | ignored | tracked |
|---|---|---|---|---|
| experiments/results-summary.md | architecture, data-lineage | ok | no | yes |
| docs/agent-runs/instructor-approvals.md | architecture | ok | no | yes |
| docs/agent-runs/phase-04/human-gate.md | architecture, data-lineage | ok | no | yes |
| docs/agent-runs/phase-05/human-gate.md | data-lineage | ok | no | yes |
| docs/agent-runs/phase-06/human-gate.md | architecture, data-lineage | ok | no | yes |
| docs/agent-runs/phase-07/human-gate.md | architecture, data-lineage | ok | no | yes |
| docs/agent-runs/phase-09/human-gate.md | architecture | ok | no | yes |
| docs/agent-runs/phase-10/human-gate.md | api | ok | no | yes |
| docs/agent-runs/phase-10/review-report-03.md | api | ok | no | yes |
| docs/agent-runs/phase-11/human-gate.md | api | ok | no | yes |
| docs/agent-runs/phase-12/agent-report-infra-2b.md | architecture, api | ok | no | yes |
| docs/agent-runs/phase-12/human-gate.md | architecture, api | ok | no | yes |
| docs/agent-runs/phase-12/prompt.md | architecture | ok | no | yes |
| docs/agent-runs/phase-13/prompt.md | architecture, data-lineage | ok | no | yes |
| docs/agent-runs/phase-14/human-gate.md | architecture, data-lineage, api | ok | no | yes |
| docs/agent-runs/phase-14/runbook-log.md | data-lineage, api | ok | no | yes |
| docs/agent-runs/phase-15/human-gate.md | architecture, data-lineage, api | ok | no | yes |
| docs/agent-runs/phase-16/human-gate.md | architecture | ok | no | yes |
| docs/agents.md | architecture | ok | no | yes |
| docs/evaluation.md | architecture | ok | no | yes |
| docs/api.md | architecture, data-lineage | ok | no | created in W1 |
| docs/data-lineage.md | architecture | ok | no | created in W1 |
| docs/architecture.md | (none link to it yet) | ok | no | created in W1 |

Fragment links (all match a heading):

| Link | Heading |
|---|---|
| architecture.md #1-build-time-workflow ... #6-what-was-not-built (6 links) | `## 1. Build-time workflow` ... `## 6. What was not built` |
| data-lineage.md #1-overview ... #9-run-ids-of-the-recorded-runs (9 links) | `## 1. Overview` ... `## 9. Run IDs of the recorded runs` (#7-evidence-answer-claims-and-the-audit-record: comma dropped) |
| api.md #1-base-address-and-the-trailing-slash-g-22 ... #10-examples-and-their-sources (10 links) | `## 1. Base address and the trailing slash (G-22)` ... `## 10. Examples and their sources` |
| api.md#53-post-v1query | `### 5.3 \`POST /v1/query\`` |
| api.md#56-get-v1explainqueryid | `### 5.6 \`GET /v1/explain/{queryId}\`` |
| api.md#58-get-v1graphnodesnodeidneighbors | `### 5.8 \`GET /v1/graph/nodes/{nodeId}/neighbors\`` |
| api.md#9-claim-verification-statuses-and-reasons | `## 9. Claim verification statuses and reasons` |
| data-lineage.md#2-sources | `## 2. Sources` |

External links: none. PENDING entries: none.

## Unverified claims

- The ALB health-path routing and API Gateway settings come from the Phase 12 infra report (as built in Terraform); I did not read the Terraform files under infra/terraform/modules/runtime/ to re-confirm them.
- The six recorded live answers are cited by file and question only; I did not re-verify their claim statuses against docs/evaluation.md.
- "Each of the four services scales on its own" and the autoscaling settings are from the Phase 12 report, not re-read from Terraform.

## R-1 and G-22 (routing rows) as documented

- R-1 (routing-p17.md row R-1): confirmed from code at 50ba087: QueryService.scala:427-430 (failed workflow -> job WORKFLOW_FAILED, 502 DOWNSTREAM_FAILURE retryable, Errors.scala:76-77); same key -> QueryService.scala:335-336 -> follow -> :575 -> 409 JOB_FAILED (not retryable), details.jobError WORKFLOW_FAILED, until the key's record expires (jobTtlSeconds 86400). Module test IdempotencySuite.scala:117-131; contract test ReplayIdempotencyCrossModuleSuite.scala:45-91 (retry without the failed key: 200, same query ID and bundle) and :25-43 (new key: one query ID, one bundle, two audits). Stated plainly that no test under tests/** asserts the 409 after a 502 itself. Same key is right after 504 WORKFLOW_TIMEOUT (IdempotencySuite.scala:86-115), after 409 IN_PROGRESS / JOB_NOT_READY, after no response, and after 503 on a keyed sync query (admit before any record, QueryService.scala:306-314, :337-338). docs/api.md section 7.
- G-22 (routing row G-22): api_url ends in `/`; smoke-test.sh:333 strips it (the routing row's :309 is now :333 at HEAD). docs/api.md section 1.
- Also documented (found while reading the records, not in the routing table): Phase 10 gate decision 4 asks the API docs to say that an unknown node's neighbour list returns an empty 200 (docs/api.md section 5.8).

## INTERFACE HANDOFF

Files: docs/architecture.md, docs/data-lineage.md, docs/api.md (new; no code, no interface of another owner changed).

Headings and anchors other documents may link (W2 to W5):

- docs/architecture.md: #1-build-time-workflow, #2-runtime-architecture, #3-the-runtime-query-workflow, #4-why-each-technology-is-used, #5-consolidation-of-services, #6-what-was-not-built
- docs/data-lineage.md: #1-overview, #2-sources, #3-hadoop-jobs-j01-to-j09, #4-entity-resolution, #5-spark-graph-construction-and-analytics, #6-loaders-and-stores, #7-evidence-answer-claims-and-the-audit-record, #8-provenance-fields-and-schema-versions, #9-run-ids-of-the-recorded-runs
- docs/api.md: #1-base-address-and-the-trailing-slash-g-22, #2-roles-and-routing, #3-authentication, #4-common-request-and-response-rules, #5-routes (subsections #51-get-healthz, #52-get-readyz, #53-post-v1query, #54-get-v1jobsjobid-and-get-v1jobsjobidresult, #55-get-v1evidenceevidenceid, #56-get-v1explainqueryid, #57-get-v1graphnodesnodeid, #58-get-v1graphnodesnodeidneighbors), #6-error-model, #7-idempotency-and-the-502-rule-r-1, #8-limits-and-timeouts, #9-claim-verification-statuses-and-reasons, #10-examples-and-their-sources

Forward references W2 must satisfy: docs/api.md section 8 and docs/architecture.md (intro) name `docs/service-limits.md` and `docs/final-state.md` in plain text, not as links; W5 may turn them into links once W2 creates them.

L2 and claim-status statements written (for W5 to reconcile with step 1's final handoff):

1. docs/api.md section 9, table: four statuses `verified`, `partially_supported`, `unsupported`, `conflicting` (core Enums.scala:315-332); kept in the answer: verified and partially_supported (uncertainty "Partially supported: REASON."); removed: unsupported and conflicting (claimText "Removed (STATUS): REASON"; unsupported cites no evidence) (QueryWorkflow.scala:302-327 at 50ba087).
2. docs/api.md section 9: "At 50ba087 it gives 25 fixed reason texts (ClaimVerification.Reasons.all, ClaimVerification.scala:109-135)".
3. docs/api.md section 9: the four L2 absence reason texts quoted verbatim (AbsenceBoundedSearch, AbsenceBeyondDepth, AbsenceNoDepth, AbsenceNotSearched; ClaimVerification.scala:101-108 at HEAD).
4. docs/api.md section 9: the absence-claim verified rule at 50ba087 (exactly two resolved distinct researchers; cited complete path=none search for that pair with depth >= stated and the same window; no unparsed year-like phrase; no cited found path or edge connecting the pair; ClaimVerification.scala:42-52).
5. docs/api.md section 9, "Phase 17 note": L2 accepted as a deviation with open findings P16-L-10 to P16-L-15; step 1 changes recognition and checking; W5 reconciles.
6. docs/api.md section 5.6: explain `verificationCounts` counts claims per verification status.
7. docs/architecture.md section 3: "only `verified` and `partially_supported` claims stay in the answer"; ClaimVerification is deterministic code.
8. docs/data-lineage.md section 7 item 3: the four statuses; the verifier checks every number, year, name, identifier, title and path hop against the cited items.
9. docs/api.md section 10: fixture completed-result.json has three claims, each `verified`; Phase 15 recorded the abstention code `path-query-failed` for G-28.

Reason codes and statuses documented: statuses `verified`, `partially_supported`, `unsupported`, `conflicting`; verifier reason texts: the four Absence* texts verbatim plus a count (25) and a pointer to Reasons.all; abstention code `path-query-failed` (Abstentions.scala) and a pointer to AbstentionCodes; job error codes SUPERSEDED, LEASE_EXPIRED, ATTEMPTS_EXHAUSTED, RESULT_TOO_LARGE, WORKFLOW_FAILED; API error codes: all 30 entries of ErrorCode.all (Errors.scala:88-119).

Thread-safety: not applicable (documents). Limits: documents describe HEAD 50ba087 and the archived Phase 14 and 15 records; no live check was possible.

## VALIDATION EVIDENCE

- `shasum -a 256 docs/agent-runs/phase-17/prompt.md` -> c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (matches the work order)
- `git rev-parse HEAD` -> 50ba087ecd33178eaa8ccc69286b9de85e8a8146
- Link check loop (ls, git check-ignore -v, git ls-files) over 23 targets -> all `ls=ok`, `ignored=none`; 20 tracked, 3 untracked (created in W1: docs/api.md, docs/data-lineage.md, docs/architecture.md)
- Fragment check (`grep -n '^#'` per file, anchors compared by hand) -> 35 fragment links, all match
- Identifier scan: `grep -n -E 'AKIA|ASIA[0-9A-Z]{16}|sk-ant-|arn:aws|amazonaws|execute-api|\.elb\.|[0-9]{12}|scholarchain-course|scholarchain-25|scholarchain-readonly'` over the three files -> no match (exit 1); an earlier IPv4-pattern scan matched only the Neptune engine version 1.4.7.1 (a version, not an address)
- `shasum -a 256` of the three files -> architecture 0d1e7081…, data-lineage 31d1225d…, api f2f77697… (full values under FILES CREATED)
- `git status --short modules/rag` (read-only, to know what was in progress) -> ` M modules/rag/src/main/scala/org/scholarchain/rag/runtime/ClaimVerification.scala` (rag-engineer's step 1, not mine; I read the verifier only through `git show HEAD:`)

files changed after these runs: none
