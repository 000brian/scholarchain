[Saved verbatim by the orchestrator from code-reviewer's final message (R-D3).]

# review-report-docs-3.md (Phase 17 session 2, R-D3: documentation review of reports/final-report.md)

Reviewer: code-reviewer. I used only the Read, Grep and Glob tools. Work order: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 as given; I have no Bash, so I did not recompute it). I applied decisions 2, 12, 14 and 15 and the results section.

## VERDICT: APPROVE (R-D3 slice: reports/final-report.md)

- I verified no critical or major finding in reports/final-report.md. There are 9 minor findings, P17-D-61 to P17-D-69. Under decision 15 all of them get the documentation repair pass. All are in documentation-engineer's Markdown, and none needs a code change.
- **Limit of this verdict:** it covers only this file. **The overall final review stays REQUEST_CHANGES.** P17-F-01 (major, constitutional) and P17-A-01 (PARTIAL, major) are still open, and P16-L-10 is not closed (review-report-02.md). The report itself says this correctly (sections 12.2, 19 and 23.4).

## What checks out

- **The 25 sections are all present, in the spec's order, with the spec's titles.** I compared them with course-specification.md:2860-2888, the spec text itself, not a summary. The contents list and every `#n-…` anchor match their headings.
- **Instructor-approved deviations:**
  - The teardown quote at final-report.md:65 matches Phase 15 gate decision 6 (human-gate.md:106) word for word.
  - The model-endpoint quote at :272-278 matches instructor-approvals.md:26-32 word for word. The "How it is applied" values match :34.
  - The Scala 2.13 statement at :185 matches CLAUDE.md "Scala versions", phase-02/human-gate.md:13 and the Phase 2 agent report (Spark 4.1.1, Scala 2.13.17).
  - The budget and enrichment rows match instructor-approvals.md:38-40.
  - The missing public-repository entry is disclosed at :67.
- **Numbers checked against their cited files (all equal):**
  - ER group 1 (evaluation 10.1).
  - Graph group 2: 43/43; 37/4/2; 4 zero and 39 equal checks; the provenance shares.
  - Retrieval (evaluation 10.3).
  - Answers: 111/19/5/1 of 136; 85/86; 3/30; 28/2/1 of 31; ratings.
  - Factory group 6: 87, 38/80, 196 (0/30/149/17), 62, 3.444444, 5, 6, 0, 3, 27; per-agent rates; BLOCKED-ON 2,1,0,1,1.
  - Reproducibility: 7951f94 exit 6; 79b5583 at 2306/399; 59/59; 127 s; 7 records and 10 approval lines with 3 unused.
  - results-summary 1-6: A 4,875 s, $0.95, ContainerPending 514; B 1,416 s against 1,488 s, $0.56 and $0.44; C 0→1→2→0, 170 s, 1,172 s, about 9.0/s and 24.0/s; D error rates; the read route's 28×502 and 526×503; CPU 9.76 %; the scaling-rule statuses; experiments total about $2.2.
  - Phase 6 graph table and averages; Phase 7 M1 (8/12, 13/13); Phase 13 at 2065/398; Phase 14 loads and costs; Phase 15 gate costs ($20.47, $0.24, $20.71, about $6.5, about $3.9 to $9.5); Phase 16 gate costs and decision 9.
  - The Phase 12 and 14 idle estimates; service-limits counts (60/55/5/0, with the five unverified IDs); final-state plans and records (11 signed, 10 applied, 7 records); 11 Terraform modules; Terraform 1.17.1 and AWS provider 6.59.0 (versions.tf:6, :11).
  - 18 tracked gates and 21 tracked work orders (Glob).
- **Claim verification (12.2) against repair-01-rag.md and ClaimVerification.scala:**
  - `object AbsenceRule` is at :978, and `Reasons.all` (:133-165) holds exactly 31 texts.
  - The five repair codes and AbsenceAmbiguous are additive (:117-132). In the Phase 17 block AbsenceNoDepth goes to `unbacked` (:697), so the claim is unsupported.
  - The list additions (lack, absent, unrelated, distinct components) are there. The 35 falls (21 + 14), 228 tests in 15 suites, 17 golden tests and 3 of 56 failing with the repair switched off match repair-01-rag.md:21-29.
- **Test counts:** marker 2026-11-23T01:13:43Z with 2343 module and 399 contract tests (repair-01-rag.md:127; agent-report-test-inventory-2.md:8-10). Step 4: marker 2026-11-23T00:20:15Z with 2336/399 (agent-report-service.md:54).
- **Test inventory:** QueryApiSuite.scala:87, the Spark fixture suites, the three weak tests, and the six `.fail` tests T-02 to T-06 match T1.
- **Section 23:**
  - Every status-line row of routing-p17.md is in 23.2: K-01, K-02, M-07, the manifest parts of K-03 and L-03, L-05, L-13, E-02, P16-A-06, B-01, B-03, F-02, the hook shellcheck item, A-04, G-09/10/11, G-20, G-25/26, P16-A-04, P16-A-08, Spark C1/C2, and the Phase 16 proposed-later and not-built lists. All statuses match except P17-D-69.
  - U-12 matches phase-10/review-report.md:198, and L-04 matches phase-15/review-report-L-01-02.md:116.
  - "Fixed in code, not validated live" appears at :16, :258, :508 and in 23.1.
  - 23.3 equals evaluation section 8.
  - 23.4 matches R-1, F-1, F-2 and the re-check row by row: P17-F-01 major open; P17-A-01 PARTIAL, major open; P16-L-10 not closed; P16-L-11 closed for recognised claims; P17-K-01 and P17-K-02 minor; the verdicts.
- **Cost section 20:** estimates are labelled with their basis, the line `PENDING E7` is present, and the providers are reported separately with a total.
- **Section 21:** A-Hadoop attempt 1, D9a, D-read attempt 2, D27 attempts 1 and 2, E2 attempt 1, the first applies, the G-items (7 rounds, 8 fixes; phase-14/human-gate.md:96), FECD-1 and the incident report are all there.
- **Optional components not built** are stated: OpenSearch, GitHub Actions, the OpenAPI file, and the GitHub MCP server switched off (:230, :297, :347, :599).
- **Links:** every repository-relative target exists (Glob). Every `#fragment` matches a heading in docs/api.md, architecture.md, data-lineage.md, evaluation.md, final-state.md, runbook.md, threat-model.md, service-limits.md and README.md under GitHub's anchor rule. That includes `#103-group-3-retrieval-exploratory-n--11` and `#7-idempotency-and-the-502-rule-r-1`.
- **Identifiers:** no 12-digit run, AKIA or ASIA key, ARN, amazonaws host, execute-api or ELB host, AWS example key or twelve-zero account. The only IPv4-pattern match is the Neptune engine version 1.4.7.1 at :210. The student identity at :3 is required by decision 13.

## Findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| P17-D-61 | minor | reports/final-report.md:455 | "Session 3 needed three rag repair rounds on the absence-claim rule: E00 (PARTIAL), E01 (…) and E02 (the hermetic tests, APPROVE)". The Phase 16 gate says session 3 "repaired L2 twice and made the rag tests hermetic" (phase-16/human-gate.md:9). repair-E02-rag was P16-L-16, the hermetic configuration (:73), not the absence rule. | The report counts one rag repair round too many on the absence-claim rule, and the sentence contradicts its own parenthesis. A wrong process fact. | documentation-engineer | no |
| P17-D-62 | minor | reports/final-report.md:179 (section 8, Interpretation); :132 (section 5, Interpretation) | Section 8's interpretation paragraph brings in new measured numbers: "97,681 names", "43,466 names", "88,663 mentions" (phase-05/human-gate.md:62-63, :98). Section 5's interpretation brings in "27 rows were unclassified", which its Measured paragraph (:130) does not give. | This breaks the report's own rule at :13 ("Paragraphs that interpret them … add no new number") and decision 14 ("Measured facts and interpretation kept apart"). Move the numbers into the measured paragraphs. | documentation-engineer | no |
| P17-D-63 | minor | reports/final-report.md:116 | "in the Phase 1 probes … the permission rules refused to read `.env`". The record (phase-01/human-gate.md:14-17) shows only the model's own reply ("Claude responded: 'I won't read .env …'"). No permission denial or hook block is recorded. | The sentence is offered as evidence that "a prompt can be misread and a hook cannot", but the record shows a model refusal, not a deterministic one. Say that the orchestrator declined and cited the deny rule. | documentation-engineer | no |
| P17-D-64 | minor | reports/final-report.md:150, :152 (6.1, categories 1 and 3) | Category 3 says it lists "the disclosed manual actions", and says they are "on human-owned or untracked files, or on the index". It leaves out two disclosed actions:<br>(1) Phase 16: "copied the six demo answers into the repository after two identifier scans (E7)" (phase-16/human-gate.md:150). Those files are under `experiments/evaluation/raw/answers/p15-demo/` (PROVENANCE.txt:1), outside docs/agent-runs/.<br>(2) Phase 15: "made read-only copies of the S3-only reports (13b) and of DynamoDB samples during D27" (phase-15/human-gate.md:133).<br>Category 4 lists neither. Category 1 says "Every … outside `docs/agent-runs/**` was written by an agent". Several gates (Phases 7, 9, 10, 12-16) say only "no code changes", yet :152 says every gate states that no code, test *or report* was edited. | Decision 14 asks for "every disclosed manual change". The list is incomplete, and the category 1 and 3 sentences overstate what the gates record. | documentation-engineer | no |
| P17-D-65 | minor | reports/final-report.md:152 | "Group 6's 3 manual interventions are process events …: a Phase 15 sitting that ran in the wrong Claude Code session and a load attempt started by accident". events.csv:312, :313 and :316 are the three counted rows. The D-read accident is counted twice (human-gate.md:92 and orchestrator-summary.md:207). | A count of 3 is explained by 2 events, and the double count is not reported. A reader cannot reconcile the numbers. | documentation-engineer | no |
| P17-D-66 | minor | reports/final-report.md:56 (with :62, :65) | "Their record is `instructor-approvals.md` (human-owned) and the phase records named below." That file (read in full) has no teardown entry, only the 2026-11-09 model-endpoint entry and the "Earlier approvals" list. The teardown is recorded only in Phase 15 gate decision 6. :67 flags the missing public-repository entry but not this one. | A reader who looks for the teardown approval in the approval record will not find it. State that its only record is the Phase 15 gate. | documentation-engineer | no |
| P17-D-67 | minor | reports/final-report.md:222 | "a closed set of filterable metadata keys (researcher, window, source, topic, institution, community, document type)". index-spec-v1.json:22 lists 21 filterable keys. It also has workId, year, venueKey, pathLength, startResearcherId, endResearcherId, embeddingModel, embeddingDimensions, datasetId, snapshotId, jobName and policyVersion. | An incomplete list is presented as *the* closed set. Write "for example", or give the full set. | documentation-engineer | no |
| P17-D-68 | minor | reports/final-report.md:255 (12.2 item 6), :442 (section 18) | "Across the existing fixtures (… 59 claims) no status rose and none fell" and "On the fixtures, no existing claim's status changed (59 claims …)". The same run printed "[monotonic corpus] claims=59 absence=0" (repair-01-rag.md:21). :362 says the count is printed but not asserted, but not that it was 0. | The monotonic corpus has no absence claim, so it never exercises the absence rule. The evidence is offered without that limit (uncertainty not reported). | documentation-engineer | no |
| P17-D-69 | minor | reports/final-report.md:623 | The Spark C1, C2 row gives the status "proposed later, unscheduled" under the header "Status line (T0)". T0's status line is "still present" (routing-p17.md:61). | The status-line column does not carry T0's status for this ID (decision 2: T0 writes the line). | documentation-engineer | no |

No finding needs a code, test, script, configuration or build change.

## Coverage

- **Read in full:**
  - reports/final-report.md, lines 1-730.
  - routing-p17.md, review-report-02.md, repair-01-rag.md, agent-report-test-inventory-2.md.
  - docs/agent-runs/instructor-approvals.md and index-spec-v1.json.
- **Read in part:**
  - Work order prompt.md: lines 1-135 (results section, decisions 1-2) and 186-315 (decisions 5-17, including 12, 14 and 15 in full).
  - course-specification.md:2786-2890: the agent-generated deliverable evidence section and the final report structure.
  - review-report-code.md:1-40, review-report-final-control.md:1-40, review-report-final-code.md:1-30.
  - ClaimVerification.scala:80-169 and :560-739; Grep for :978 and :1323.
  - docs/evaluation.md:225-304 and :320-848.
  - experiments/results-summary.md:28-427.
  - docs/final-state.md:20-119; service-limits.md:1-25 plus a Grep.
  - phase-15/human-gate.md:99-130; phase-16/human-gate.md:96-133; phase-01/human-gate.md:3-29; phase-12/human-gate.md:108-110.
  - Every gate's "Manual changes" or "Things I did by hand" section (Grep with context).
  - Greps of the Phase 4, 5, 6, 7, 9-14 gates for the cited facts; events.csv rows of type manual-intervention; config/course.json:1-85; heading lists of every linked document and README.md.
  - Grep of agent-report-docs-W4a.md, W4b.md and W5.md; agent-report-service.md, agent-report-infra-I1a.md and I1b.md (I1b:57-72); orchestrator-notes-s2.md:70.
- **Not read:** agent-report-rag.md (step 1); the body text of README.md, docs/api.md, architecture.md, data-lineage.md, threat-model.md and runbook.md (I checked only their headings and anchors); docs/demo-script.md; agent-report-docs-W4a.md, W4b.md and W5.md in full; data-lineage.md:147 (the 3.61 h figure taken as cited).
- **Not run:** `git check-ignore` and `git ls-files` (I have no Bash). I checked that link targets exist with Glob. Tracked status comes from the session-start git status snapshot: the Phase 17 documents are untracked and new in this phase, and every other target was not listed as untracked.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md (sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 as given, not recomputed), Phase 17 session 2, R-D3 (decision 15): documentation review of reports/final-report.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run; test counts checked against repair-01-rag.md, agent-report-service.md and agent-report-test-inventory-2.md (marker 2026-11-23T01:13:43Z, 2343/399)
VALIDATION RESULTS: 25 sections present and in order; four authorship categories present, with gaps in completeness (P17-D-64, P17-D-65); deviations verified; section 23 status lines, U-12, L-04 and Phase 17 finding statuses verified; links and anchors resolve; no identifier; 9 minor findings P17-D-61..69
ASSUMPTIONS: link targets not listed as untracked in the session-start git status are tracked; the scratchpad reports are the versions to be copied into docs/agent-runs/phase-17/
RISKS: the overall final review stays REQUEST_CHANGES (P17-F-01, P17-A-01; P16-L-10 not closed); the PENDING E3..E11 fill-ins need the E11 documentation re-check
MANUAL ACTION REQUIRED: none from this review
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 documentation triage; repair-01-docs (documentation-engineer) for P17-D-61..69; re-check in review-report-docs-02.md
```

VERDICT: APPROVE (R-D3 slice only; overall final review REQUEST_CHANGES because of P17-F-01 and P17-A-01)

| Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|
| minor (P17-D-61) | reports/final-report.md:455 | E02 counted as an absence-rule round | wrong process count | documentation-engineer |
| minor (P17-D-62) | reports/final-report.md:179, :132 | new numbers inside Interpretation paragraphs | facts and interpretation mixed | documentation-engineer |
| minor (P17-D-63) | reports/final-report.md:116 | the .env probe was a model refusal, not a recorded permission denial | claim the record does not show | documentation-engineer |
| minor (P17-D-64) | reports/final-report.md:150, :152 | demo-answer copy (Phase 16) and S3/DynamoDB copies (Phase 15) missing; overstated gate wording | incomplete authorship disclosure | documentation-engineer |
| minor (P17-D-65) | reports/final-report.md:152 | 3 manual interventions explained by 2 events (double count) | double count not reported | documentation-engineer |
| minor (P17-D-66) | reports/final-report.md:56 | teardown not in instructor-approvals.md | misleading source pointer | documentation-engineer |
| minor (P17-D-67) | reports/final-report.md:222 | filterable-key list incomplete against index-spec-v1.json:22 | inaccurate design detail | documentation-engineer |
| minor (P17-D-68) | reports/final-report.md:255, :442 | monotonic corpus had 0 absence claims, not disclosed | uncertainty not reported | documentation-engineer |
| minor (P17-D-69) | reports/final-report.md:623 | Spark C1/C2 status differs from T0's "still present" | status-line mismatch | documentation-engineer |
