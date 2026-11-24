# review-report-final-docs.md: Phase 17 session 3, step E11, final documentation check

Reviewer: code-reviewer (read-only). Scope: HEAD c6bbe148cc8aad0f65018c6cada3e6543dcada5b plus the uncommitted E11 changes to 8 Markdown files. Release candidate: b4d8f1229fc145e1492e1a8cbb8a44a368d026af.

Inputs I read:
- the diff (`diff-E11-docs.txt`, all 842 lines), with the text around each change in the working tree
- the writer's report `agent-report-docs-E11.md`
- `runbook-log.md` in the scratchpad, rows E0 to the E11 pre-step
- the local logs `/tmp/p17-E03.log`, `/tmp/p17-E04.log`, `/tmp/p17-E05.log` and `/tmp/p17-E10.log` (grep, plus one bounded read)
- `/tmp/p17-E3-marker.txt`
- both E10 manifest files, and `manifest-checksums.txt`
- `review-report-P17-L-05.md` and `repair-E1-rag.md`
- `AbsenceRuleSuite.scala:520-546`
- every `APPROVED-*` line under phase-13 to phase-15
- all 7 release records under `releases/course/`
- `config/course.json`, `datasets/dataset-manifest.yaml`, `experiments/**` run folders and `prompts/**`
- `docs/agent-runs/phase-17/tool-versions-p17.txt`, and the list of files under `docs/agent-runs/phase-17/`

## Result

There are two major findings and eleven minor ones. **VERDICT: REQUEST_CHANGES.** Both majors are one-line documentation fixes, owned by documentation-engineer, inside paths the manifest's rule line allows.

## 1. Replay lines and filled values against the runbook log

Every value E11 filled in matches its runbook-log row. Where a log is cited, it also matches the local log:

- **Exit lines.** E3 `exit=0` is at E03:10854 and the 1303 s success line at :10853. E4 `exit=0` is at E04:11724, with all 9 SUMMARY gates passed and `module_tests=2352 contract_tests=399` at :11720. E10 `exit=0` is at E10:50, with its SUMMARY lines at :44-47. All 47 `E5.NN exit=0` lines are in the E05 log, including E5.08 at :10813.
- **Counts.** Every count in the replay lines matches E05 (600/600 twice, 114, 147, 43, 360+2, 137+1, 399, 17, 321+2). The suites that ran only inside the full test runs also match: QueryApiSuite 11 (E05:2686, E03:2044), AlgorithmsSuite 6 (E03:2010), JsonlExportSuite 14 (E03:3064) and JobConfigSuite 3 (E03:135).
- **E6, E7 and E8 values.** These match the RL rows exactly: state count 0 with exit 0; plan 175/0/0/0 with its plan ID and sha256; the cost figures and the reconciliation line; deregistration 20/20, budget removed 01:05Z, IAM user removed 01:09Z; after the removal ACTIVE 0 / INACTIVE 20.
- **Account fingerprint.** No account fingerprint value appears in any document.
- **Replay-line forms.**
  - `replayed in phase 17 (ENN, exit CODE); evidence: …` and the deferred tag line conform to the results rule.
  - The `not replayed in phase 17 (…)` lines for QueryApiSuite, AlgorithmsSuite, JsonlExportSuite, JobConfigSuite and `archive-evidence.sh --local-only` are correct. None of these were E5 lines, and E12 must list them as open items.
  - **`exit n/a: interactive session` does not conform** (finding P17-M-06). The rule's form is `exit CODE`, and the evidence for these lines is only the human's report. I accept the wording as honest. But it is outside the closed list and should not count silently as "replayed".
  - `git clone` (README:100) is credited to E5.42. That run used the same command with a different target folder; the E3 clone's own exit line is not logged. The writer disclosed this, and I accept it.
  - The `cd` lines are credited through the subshell form, which the log does not show. The writer disclosed this too, and I accept it.
- **Observation, not a finding.** E05:12548 holds a first `Cloning into …p17-replay…` with no exit line. The repeated E5.41 and the E5.42 clone, which exited 0, come after it (:12549-12552). The RL row "E5 part 4" mentions that E5.41 ran twice, but not this first clone attempt.

## 2. The E10 manifest files

**Compared with the orchestrator's recomputed checksums** (`manifest-checksums.txt`, made with `shasum`; I did not compute any hash myself), value by value against `release-manifest.json`. All are equal:
- the 26 `key_artifacts` sha256
- the 10 `prompts` sha256
- the 8 `signed_experiment_manifests` sha256
- `datasets.source_sha256`
- `generator.script_sha256_at_head`
- the 4 jar sha256
- `tests.source.sha256` (the marker copy)

The `.md` file carries the same jar, marker, record and plan values.

**Compared only with committed records or local files** (Grep or Read; no recomputation):
- **Signed plans.** All 10 signed plan checksums and their file:line equal the `APPROVED-PLAN-SHA256:` lines (phase-13 human-gate:77; phase-14 approvals:9, :17, :29, :37, :45; phase-15 approvals:9, :65, :73, :76).
- **Experiment manifests.** All 8 signature lines equal `APPROVED-MANIFEST-SHA256:` at phase-15 approvals:15 to :57.
- **Local-only archive.** Its checksum equals `EVIDENCE-ARCHIVE-SHA256:` at phase-15 approvals:78.
- **Release records.** For all 7 records, these fields equal the record files: commit, plan_commit, build_commit, plan_sha256, environment, runtime_enabled, image digest, and the 4 task definitions each. Each record's approval-line mapping equals a committed signed line.
- **Candidate SHA.** `release_candidate`, `build_commit` and the clone HEAD all equal the RC SHA, as recorded in RL E2, E3 and E10 and in E10:44.
- **E3 tests.** `marker_timestamp` 2026-11-23T19:15:55.402093Z, 2352 module tests and 399 contract tests equal `/tmp/p17-E3-marker.txt`. The timestamp is later than the E3 start of 18:53:52Z.
- **Models.** These equal `config/course.json` lines 16, 17, 61, 63 and 72.
- **Datasets.** Spot-checked against `dataset-manifest.yaml`: the dblp lines 376-378, 404 and 406, openalex line 466, orcid line 606, and the first and last orcid file md5 (lines 610 and 621).
- **Run folders.** The 10 experiment attempt folders exist, and their `run-record.json` statuses match (2 failed, 8 succeeded). The 18 evaluation folders and the 1 emulator folder exist. The Spark run ID is in the 4 named run records. Whether they are committed I inferred from the clean tree at E10 and from the manifest's `git ls-tree` source; I did not check it with git.
- **Prompts.** There are 10 prompt files, which matches the manifest.
- **Rule line.** It equals prompt.md:213 word for word.
- **Identifier masking.** Both files hold 5 word-bounded twelve-digit runs in total (json 3, md 2). Every one is the documented all-digit commit prefix inside the name of one release record. There is no ARN, AWS host, key pattern or run of twelve zeros.
- **Not verifiable by me.** The sha256 of the two manifest files, quoted in README D14, final-state:132 and final-report:385. I compared them only with RL "E10 release manifest".

## 3. The human's E11 instructions

- **(a) P17-L-06 to L-09: fixed.**
  - L-06: final-report:668, :682, :683, :688 and :689 now say "recorded minor", and the F-05 T/Z stamp is named.
  - L-07: attribution corrected at README:421, threat-model:7 and :59, api:316, final-report:271, :672 and :704, and demo-script:176.
  - L-08: api:315 and :317 cite repair-E1-rag.md:47-51 and :57-60. I checked these against the test lines :528-531 and :540-543.
  - L-09: service-limits:8, threat-model:59 and :183, and final-report:472 and :617 cite the spelled-out row. The general citation left at final-report:8 is not wrong.
- **(b) Full-name condition: held** in every L2 passage, stated as a known limitation of the fix with the P17-L-04 example. One residue is in the demo narration (P17-M-11).
- **(c) README step 5:** uses `ls .claude/agents | wc -l` with output 18 (README:133), and states that `/agents` is not replayable in 2.1.330 with the exact message (README:136). The MCP approval prompt is at README:123. **README:67 still tells readers to run `/agents`** (P17-M-01).
- **(d) Final report section 1.1, items 1 to 6:** all six corrections are present and match the RL rows. Item 6 leaves out the session 3 sitting-1 `cd` uses (P17-M-08).
- **prompt.md is unchanged.** It is not among the modified files in the git status snapshot; I did not recompute its sha256.

## 4. The writer's "proposed later" list

| # | Item | Wrong now? |
|---|---|---|
| 1 | README:67 `/agents` | **Yes.** It contradicts README:136 and the RL. Finding P17-M-01 (major). |
| 2 | final-report "to be copied into docs/agent-runs/phase-17/" (:310, :319, :346, :370, :371, :602) | **Stale.** The named reports are already in that folder. P17-M-13 (minor). |
| 3 | `release-manifest.sh` "runtime: untested" | **final-report:610: yes.** It contradicts :352 and :727 and RL E5.40 and E10, so P17-M-03. runbook:191 ("until the human's step E10") is stale, also in P17-M-03. **README:325 is not wrong:** it only says "until the human runs it". **scripts/README.md:59** belongs to infra-engineer; P17-M-04. |
| 4 | threat-model:7 "E1 commit pending" | **Yes.** E1 is commit b4d8f12 (RL "E1 commit"). P17-M-05. |
| 5 | demo-script items 3 and 16 | **Yes, slightly.** P17-M-11. |
| 6 | final-report:8 general "E1 decisions" | **Not wrong.** That row does hold the E1 decisions; no finding. |
| 7 | README:129 repeats the MCP approval | **Inconsistent** with step 4, and README:121 points to "step 5". P17-M-12. |
| 8 | Docker lint warning | **Not a documentation defect.** It belongs to build-engineer. A `docker/` change would make a new release candidate, so record it and do not change it now. |

I found two more defects that are not on the writer's list: P17-M-02 and P17-M-07.

## 5. Overall claims and open items

- No document claims that all final-gate items are met; I grepped the 8 files for that kind of wording and found none.
- The three video marks stay `PENDING E11`: README:398, README:454 and demo-script:242.
- The control-slice REQUEST_CHANGES and "Final reviewer returns APPROVE = accepted deviation, not met" stay visible at README:422 and final-report:408 and :704.
- The known limitations stay listed.
- The added lines hold no new twelve-digit run, ARN, host, key or fingerprint value. The only hits in the 8 files are the documented release-record file name at final-state:59 and runbook:63, both on lines E11 did not change.

## Findings

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| P17-M-01 | major | README.md:67 | "start `claude` … and run `/agents` (all 18 are listed) … (both are in-session checks of step 5 …, whose replay line is there)". Step 5 (README:133, :136) no longer has `/agents` and says Claude Code 2.1.330 answers "The /agents wizard has been removed." (RL "E5 optional recorded `claude` session"). | The README tells every reader to run a command the recorded version does not have, and points to a replay line that no longer exists. That breaks the results rule (a command without a replay line) and the human's instruction (c). Fix: point to the `ls .claude/agents \| wc -l` count of step 5. | documentation-engineer |
| P17-M-02 | major | docs/runbook.md:189 (end of the bullet) | "…so that run is not evidence for the current code; the row above is replayed at E5, as in the README." But the row above (runbook:184) and README now say `not replayed in phase 17 (not in the E5 runbook …)`, and no E5 row runs `archive-evidence.sh`. | The document says a command was replayed when the record shows it was not. That is an unsupported replay claim in a document E12 counts for "README commands have been replayed". Fix: say it was not replayed in Phase 17, so the current code of this mode is untested at runtime. | documentation-engineer |
| P17-M-03 | minor | reports/final-report.md:610; docs/runbook.md:191 | final-report:610 lists "the new `release-manifest.sh`, all `runtime: untested`". Against that, final-report:352/:727 and RL E5.40 and E10 show exit 0 for both runs. runbook:191 says "`runtime: untested` until the human's step E10". | The final report contradicts itself, and the runbook is stale on a runtime line. | documentation-engineer |
| P17-M-04 | minor | scripts/README.md:59 | The status line reads "`runtime: untested` (until the human's E10 run …)". The results rule (prompt.md:43) earns `runtime: tested in phase 17 (E10, exit 0)` after a run the human made and the orchestrator checked (RL "E10 release manifest"). | The authoritative runtime line is stale. **Do not repair it after the release candidate:** `scripts/` is outside the manifest's rule line and is a `check.sh` input, so a change would force E2 to E5 and E10 to repeat (decision 18 repeat rule). Recommend recording it as an open item at E12, or carrying the corrected line in the docs only. | infra-engineer |
| P17-M-05 | minor | docs/threat-model.md:7 | "session 2 in commit `d8b23f2`, E1 commit pending". The E1 commit is b4d8f1229fc145e1492e1a8cbb8a44a368d026af (RL "E1 commit"). | A stale state claim in the threat model. | documentation-engineer |
| P17-M-06 | minor | README.md:127, :133, :134, :145; docs/demo-script.md:61, :70 | `replayed in phase 17 (E5, exit n/a: …)`. The results rule (prompt.md:45) allows only `exit CODE`, and the legends at README:9 and demo-script:9 do not list `exit n/a`. The only evidence is the human's report. | These six lines are outside the closed form list and would escape E12's open-item listing. Either keep the wording and have the orchestrator list the six lines at E12 as an unplanned deviation of the replay-line form (my recommendation), or add the form to the legends with that caveat. | documentation-engineer (wording); orchestrator (E12 listing) |
| P17-M-07 | minor | README.md:110 vs :112 | The :110 replay line is `replayed earlier (phase 17, start check 6, tool-versions-p17.txt)`. :112 says "that check was not a README replay". The command was `planned for replay (E5)`, and no E5 row runs it. | The README contradicts itself, and a planned E5 replay that never ran is credited as replayed, so E12 would not list it. Either use `not replayed in phase 17 (…; the orchestrator's session 2 start check printed 2.1.330, tool-versions-p17.txt:12)`, or remove the :112 sentence. The human decides. | documentation-engineer |
| P17-M-08 | minor | reports/final-report.md:78 (section 1.1, item 6) | Item 6 lists the session 3 orchestrator commands outside its list as `sed -n`, `perl -pe` and `sort`/`uniq`. It leaves out the two read-only `cd` uses of sitting 1 (RL "Resumption … Orchestrator command note", line 15), which the human accepted at E1 as an unplanned deviation (RL "E1 decisions"). | The human's instruction (d) list is incomplete. | documentation-engineer |
| P17-M-09 | minor | README.md:9; docs/runbook.md:14 | README:9: "The human ran every Phase 17 replay (steps E3 to E6 and E10) … in a fresh clone of the release candidate". But E6 (state count, `plan.sh`), E5.40 and E10 ran in the main working tree (RL "E6 step 2", "E6 step 3", "E5.40 …", "E10 release manifest"), as the individual lines correctly say. | The legend overstates where the replays ran. | documentation-engineer |
| P17-M-10 | minor | docs/final-state.md:129; reports/final-report.md:496 | "never fired: … covered by AWS promotional credits, so actual cost never passed $10". RL "E8 inventory before" adds "usage covered by credits, not confirmed on the Bills page". | A recorded uncertainty is dropped. | documentation-engineer |
| P17-M-11 | minor | docs/demo-script.md:59, :174 | :59 says "Then the agent list in a Claude Code session", but the replacement command prints a count, not a list. The :174 "Say" text, "such a claim with a negation or absence word must match a fixed accepted form", leaves out the full-name condition that :176 and every other L2 passage now state (instruction (b)). | The video narration overstates the verifier's coverage and describes a screen that cannot be shown. | documentation-engineer |
| P17-M-12 | minor | README.md:121, :123, :129 | Step 4 (:123) says to approve `aws-api`, `aws-docs` and `terraform`. Step 5 (:129) says to approve `aws-api` only "if you set up step 3". :121 says the prompt comes in "(step 5)". | The README gives inconsistent guidance on the MCP approval and repeats it. | documentation-engineer |
| P17-M-13 | minor | reports/final-report.md:310, :319, :346, :370, :371, :602 | These lines say "… to be copied into `docs/agent-runs/phase-17/`" for reports that are already in that folder (Glob: agent-report-rag.md, repair-01-rag.md, agent-report-service.md, routing-p17.md, agent-report-infra-I1a.md, agent-report-infra-I1b.md). | Stale wording that no longer matches the repository. | documentation-engineer |

Also recorded, not as findings: the deviations sit as a labelled block inside 1.1 "Instructor-approved deviations" rather than under their own heading, which is the human's call. "runbook:81 'runs them again'" is a tense leftover only.

VERDICT: REQUEST_CHANGES

(Scope: the E11 documentation check. Even after P17-M-01 and P17-M-02 are repaired and an APPROVE follows, the final-gate item "Final reviewer returns APPROVE" stays an accepted deviation, not met, because F-1's REQUEST_CHANGES for P17-F-01 stands; prompt.md:63.)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-17/prompt.md E11 (line 352), results rules (lines 37-45), decisions 12 and 15, release-manifest rule line (line 213); orchestrator's E11 review order (HEAD c6bbe148cc8aad0f65018c6cada3e6543dcada5b plus uncommitted changes; RC b4d8f1229fc145e1492e1a8cbb8a44a368d026af)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run. Counts checked against /tmp/p17-E3-marker.txt, the E03, E04, E05 and E10 logs, and RL E3, E4, E5 and E9.
VALIDATION RESULTS: all filled replay values and PENDING fills match the RL (and the local logs where cited). Manifest: 51 values equal manifest-checksums.txt recomputations; 18 plus all release-record fields equal committed records; identifier masking OK (5 expected digit runs, all the documented release-record name). L-06 to L-09 fixed; instructions (a), (b) and (d) hold with minor residues; (c) holds in step 5 but README:67 still says /agents. 2 major and 11 minor findings.
ASSUMPTIONS: the scratchpad runbook-log.md is the human's authoritative record. Committed status of the run folders is inferred from the clean tree at E10 and the manifest's git ls-tree source. prompt.md unchanged per the git status snapshot (not re-hashed).
RISKS: (1) Every document now cites "runbook-log row E2…E11", but the committed docs/agent-runs/phase-17/runbook-log.md is the older copy (up to E1); the citations dangle until the human copies the session 3 log. (2) final-report:375 says agent-report-test-final.md is "copied by the human to docs/agent-runs/phase-17/"; it is not there yet. (3) P17-M-04 cannot be repaired without a new release candidate. (4) E05:12548 holds an unlogged first replay-clone attempt that the RL does not mention.
MANUAL ACTION REQUIRED: the human copies the session 3 runbook-log.md, agent-report-docs-E11.md, agent-report-test-final.md, manifest-checksums.txt and this report into docs/agent-runs/phase-17/ before or with the E11 commit. Decide P17-M-04 (record, do not change scripts/). Decide the P17-M-06 and P17-M-07 forms for E12.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: one documentation repair pass (documentation-engineer: P17-M-01, M-02, M-03, M-05 to M-13; Markdown only, inside the manifest rule line), a focused re-check by code-reviewer, then the orchestrator's identifier-scan-2.md and the human's commit.
```
