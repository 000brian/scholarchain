# Review report R-2 (review-report-b.md): X1 (experiments/**), I1 (scripts/**, config/**, infra/**), step 1's docker/emulator/Dockerfile

Work order: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, taken from the orchestrator; I cannot hash). I read decisions 3, 5.3, 6.6, 12, 13, 15, 16 and 17. Reviewer tools: Read, Grep and Glob only. I ran no command and changed nothing.

VERDICT: REQUEST_CHANGES

Two major findings are open (P16-B-01 and P16-B-02), both `interface: no`. There are three minors. No critical finding.

## Findings

| ID | Severity | Interface | Owner | file:line | Failure input → impact |
|---|---|---|---|---|---|
| P16-B-01 | major | no | build-engineer | docker/emulator/Dockerfile:29-65 (download URL :33, tar install :42-52, PATH :55, entrypoint :65) | **What happens:** at E14 step 2 the human runs `docker build -t scholarchain-emulator docker/emulator`, which downloads `https://aws-emulator.s3.amazonaws.com/LTS/latest/linux/aarch64/aws-emulator.tar.gz`. That URL was guessed. The documented source is `https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-aarch64.rpm` (ref-emulator-docker-doc.md:9,17), and it is installed with `rpm -i`, not with tar into `/opt/aws-emulator` plus a PATH change. **Impact:** (1) The build most likely fails at `curl --fail`, which blocks the tier 1 emulator track (E14). (2) If the S3 bucket named in that host exists and someone else controls it, an unknown archive is unpacked and run as "the emulator". That is a supply-chain risk. (3) The file now holds a non-loopback host that names an S3 bucket, against the identifier rule. **Other gaps against the documentation:** the documented binding is `ENV HOME=/home/emulator EMULATOR_BIND_ADDRESS=0.0.0.0` with `ENTRYPOINT ["aws-emulator"]`. The file instead uses `ENTRYPOINT ["aws-emulator","-b","0.0.0.0"]`, and nothing in the evidence shows that `-b` is a valid option (cli-reference.html was not supplied). The documented user is a bare `USER 1000` after `mkdir -p /home/emulator && chown 1000`. The file uses `useradd emulator` plus `dnf install tar gzip shadow-utils`, which is extra and not documented. The builder marked all of these lines `unverified`. **Repair:** copy the documented Dockerfile with the aarch64 rpm, keeping the header, `EXPOSE 9297`, and optionally `--platform=linux/arm64` and the `uname -m` check. The work order's "`-b 0.0.0.0`" wording conflicts with its own rule "exactly the documented one". The documented mechanism is the ENV variable, so the repair should use the ENV, and the human decides whether that counts as a deviation from the wording. The build command, image tag and `docker run … -p 9297 --telemetry off` in scripts/README.md:208 stay valid, hence interface: no. |
| P16-B-02 | major | no | experiment-engineer | experiments/evaluation/evaluate.py:1149-1154, :1138-1148, :1193-1213 | **The rule:** decision 13.4 (and decision 3, group 6) requires deduplicated canonical events with source precedence. **What the code does:** (a) It emits phrase events (manual-intervention, hook-block, ownership-violation, human-rewrite) from every line of every gate and summary file, prose included, not only the "own list rows". It never deduplicates them across gate and summary. (b) It deduplicates BLOCKED-ON rows only against terminal reports, keyed by (phase, agent) instead of (phase, step, agent), and never against each other. So one blocked step listed in both orchestrator-summary.md and orchestrator-summary-s2.md, or in a summary and the runbook log, is counted twice when there is at most one terminal report. **Verified on the real population:** docs/agent-runs/phase-11/human-gate.md:168 and docs/agent-runs/phase-11/orchestrator-summary.md:125 describe the same main-session stop-hook blocks. Both match the hook-block pattern, so `hook_blocks` counts them twice. These blocks also stopped the orchestrator from ending its turn early; they were not an unsafe action. **Impact:** group 6's "hooks that prevented an unsafe action", manual interventions, ownership violations, human rewrites and `blocked_on_rows_not_in_terminal` are inflated. The E11 audit cannot catch this, because it checks "real and correctly classified" and a duplicate is real. **Repair:** apply one source precedence per phase for phrase events (count gate list rows, and count summary rows only when not matched, marking the rest `duplicate_of`). Deduplicate blocked rows by (phase, step, agent). Add a happy-fixture case where the gate and the summary repeat one event and two summaries repeat one BLOCKED-ON row. Update the suite's evaluationCode pin and the rating-sheet generator pin. |
| P16-B-03 | minor | no | infra-engineer | scripts/emulator/probes.sh:195 (with `set -u` at :10) | **What happens:** when an earlier contract rule fails (for example `endpoint`) and `SCHOLARCHAIN_EMULATOR_RESULTS_DIR` is unset, `[ -d "${SCHOLARCHAIN_EMULATOR_RESULTS_DIR}" ]` aborts with "unbound variable". **Impact:** the script exits outside its exit table (not 4), and the "refused by the emulator safety contract: rule …" line is never printed. It fails safe: no AWS call is made. setup.sh:197 guards the same test with `:-`. **Repair:** use `${SCHOLARCHAIN_EMULATOR_RESULTS_DIR:-}`. |
| P16-B-04 | minor | no | experiment-engineer | experiments/evaluation/evaluate.py:442-444 | **What happens:** a real run whose `evaluate.py` sha256 differs from the suite's `evaluationCode.sha256` only prints `WARN` and computes anyway. A changed pinned input exits 3, so the two cases are handled inconsistently. **Impact:** a run made with edited code can look like a signed-suite run; only the orchestrator's E3 shasum check stops it. **Repair:** exit 3, as for pinned inputs. |
| P16-B-05 | minor | no | experiment-engineer | experiments/evaluation/fixtures/reproduce/release-records/input/releases/r1.json:5, r2.json:5, r3.json:5; expected/release-records.csv:2-4 | The fake 40-character all-digit commits (`1111…`, `2222…`, `3333…`) match the staged-diff scan `[0-9]{12}` nine times. They are harmless, but they add scan noise the human must clear at commit. Using hex letters (for example `aaaa…`) would avoid it. |

## Checklist (decision 15, slice items)

**Decision 12, I1:**
- Item 0, OPERATIONS.md: every operation is listed and classified, with sources cited. 29 are `unknown` because the fidelity manifest is off docs.aws.amazon.com (stated). Checked, none.
- Item 1, config/emulator.json: region us-east-1; every bucket and table name starts with `scholarchain-emu-`; no top-level neptune section (`storage.neptuneEndpoint` is the non-resolving placeholder `scholarchain-emu-neptune-absent.invalid`, required by ConfigLoader; acceptable, not a real host); accountId is the placeholder 123456789012; environment-variable names only; providers `fake`, which exist (ScholarChainConfig.scala:195, :253). Checked, none.
- Item 2, env.sh: unsets the 8 contract variables (env.sh:63-65); exports the documented 8 plus SCHOLARCHAIN_EMULATOR_ENDPOINT and an absolute SCHOLARCHAIN_CONFIG_FILE (:130-139); refuses outside the repository root (:56-60); sets the prompt (:146-149); never sets JAVA_TOOL_OPTIONS. Checked, none.
- Item 3, setup.sh: contract, then names, then health, then STS account, all before any create (:550-553); exit 6 at the end on a failed create (:562-564); independent creates continue; maxReceiveCount, visibility and retention match workflow main.tf/variables.tf (job 3+2 = 5). Checked, none.
- Item 4, probes.sh: (a) uses the D3 plan, and the expected values match runbook-log.md:26. The variable names `clusterComputeLimits` and `sparkShuffleConf` match the D3-era sfn.tf (agent-report-infra-5a.md:189). (b) and (c) as specified. P16-B-03 is the only finding.
- Item 5, README section: one line per step, zsh-safe, no `\` continuation, no `<` placeholder. Checked, none.

**Tier 2 script fixes:**
- L-07 (deploy.sh:468-475, :552-557): bounded and best effort. Checked, none.
- L-12 (identity.sh `sc_output_value` plus check.sh probe): correct. Checked, none.
- L-14 (cleanup.sh:811-813, :823): only an explicit false passes. Correct.
- L-15 (cleanup.sh:460-510): one read-only describe call per entry; fails closed. Correct.
- FECD-4 (run-experiment.sh:274): `trap '' INT TERM` during finalize; bounded. Correct.
- G-04 (identity.sh `er_upload_plan`; check.sh probe): I traced it by hand. The expected lines follow LC_ALL=C order (`_` < `d` < `r`, `review` < `run-report`) and `sc_bytes` prints bare digits (identity.sh:618-620). Correct.

**Safety contract identical to 6.6 and checked before any call:** yes. Ordering is in lib.sh:96-150; setup.sh:550-553 and probes.sh:497-499 run it before any call. Recorded for R-1a: the scripts treat a variable set to an empty value as SET and refuse it (`${!v+x}`, lib.sh:128).

**Every CLI call has an explicit endpoint:** yes. The only aws invocation is `emu_aws` (lib.sh:182-187: `--endpoint-url`, `--ca-bundle`, connect and read timeouts, one attempt, under timeout). The health check targets 127.0.0.1 with `--cacert` (lib.sh:172-179).

**Emulator account never reaches a file:** it is built at run time with `printf '%012d' 0`; the temporary output is truncated (lib.sh:288-292) and the temporary folder is removed at exit. A Grep for twelve zeros in the slice found only the pre-existing check.sh:935. Queue URLs and ARNs stay in shell variables. Checked, none.

**bash 3.2 (G-12):** no associative arrays, no `${v,,}`, no `mapfile`. `[[ =~ ]]`/BASH_REMATCH, C-style for loops, `args+=()` and `${args[@]+…}` are all valid in 3.2. `${!v+x}` is believed valid in 3.2 (unverified). Checked, none.

**Decision 13, X1:**
- Shared rules: one file, standard library imports only (evaluate.py:47-58, no subprocess); writes only `raw/GROUP/RUN_ID/` or a mkdtemp folder outside the repository (:367-381, :1681-1684); prints the tool sha256 (:1707), the suite sha256 (:441), and every input and output; run.json is kept apart from results; floats are rounded to 6 decimals (:110-127). Derived values are copied verbatim, and a Grep found no number with 7 or more decimals in derived/out CSVs. No missing value is filled. Exit codes are 0/2/3/5. Checked, none, apart from P16-B-04.
- Item 1, suite: entries, kinds and variability as decision 3 requires; versioned inputs listed.
- Items 2-6: see the trace below.
- Item 8, AB-4/J-03/L-08: common.py's defaults reproduce the old literals (font 11, title 14, event labels 9, line step 14, width 1000, legend 230). The intended list names 8 files. Whether every other row stays unchanged is unverified; the orchestrator checks this at E10.

**Fixture trace, every X1 subcommand.** I traced each case by hand through the code. Results and CSV text match the expected files exactly, except the sha256 values, which I cannot compute (they are internally consistent where the same content appears twice).
- retrieval/happy: per-question, summary and checks all match. The bootstrap bounds with n = 2 equal min and max with near certainty; seed-specific, so unverified until E1.
- retrieval/malformed: exit 5, no files. retrieval/missing: exit 3.
- performance/happy: all 7 files match (spark totals 15/3/100/250/0; timings 3.75; steps 1416/4; instances 2/6200; cost 0.413333; per-minute 540/9, 900/15, 1440/24; by-tasks rows 1 and 2; route-class ordering; missing.csv order).
- performance/malformed: exit 5. performance/missing: exit 3.
- factory/happy: events E00001-E00013, unclassified, totals, by-phase, by-agent, audit sample (11 ≤ 20, so all counted events) and strata all match.
- factory/malformed: exit 0 with 2 unclassified rows, matches. factory/missing: exit 3.
- reproduce/happy, differences (counts 2/2/1/1/1/3/2 and verdict), empty-regenerated ("failed: regenerated folder is empty", exit 0), malformed (exit 5), missing (exit 3) and release-records (linkage, digest summary, unreadable row, 3 approval lines with 1 unused) all match. Field names match the real `releases/course/20261121T052512Z-ebcbc03a90bd-01a4.json`. Approval lines match the real `APPROVED-PLAN-SHA256: HEX` format.
- ratings-sheet/happy: exclusion of a2 (empty answer), cap 2, answer_chars 32 and 38, and the CSV quoting of the comma all match. The order depends on sha256, so it is unverified.
- ratings-sheet/malformed: exit 5. ratings-sheet/missing: exit 3.

**Metric kinds, numerators, denominators and sources:** retrieval is (a) with recall and MRR numerator/denominator and inclusion rule (evaluate.py:578-594); performance is (a), and costs are labelled estimates; factory is (a), audited by (c); release records are (a), "record consistency only"; ratings are (c), with the dblp column (b). Checked, none.

**No unjudged retrieval counted as a negative:** precision is `not measurable (unjudged retrievals)` (:590); `unjudged_share` is reported separately. Checked, none.

**No confidence interval on a non-random sample:** factory has none (full population); the retrieval bootstrap is headed EXPLORATORY (n = 11), as decision 3 requires. Checked, none.

**Factory event identities and precedence:** findings, executions and repairs are correct. P16-B-02 covers phrase events and BLOCKED-ON rows.

**Rating sheet hides verification statuses:** sheet.csv holds only the key, question, answer text and empty score columns (:1600, :1608). The fixture proves the source status fields are dropped. The rubric tells the rater not to look. Checked, none.

**Suite sha256 pins:**
- Consistent but not hashed by me: benchmark b58c5e1a… equals the E report's `evalSet.benchmarkSha256` (report line 41).
- Hashed by the orchestrator and matching (orchestrator notes): evaluate.py 4ecc9c7a…, rubric 102478a0….
- Unverified (I cannot hash): gold labels fc39c15f…, E report cadd90b8…, attempt-06 41cf57e6…, clip01 f818418e…, phase-13 gate 1fd5a8ee…, phase-14 approvals 92c8c750…, phase-15 approvals 95dbe226…, intended-differences e1782852…, and the four tree digests.
- Pin-rule note: the tree digests skip dot-named files. Glob found no dot files under experiments/raw, experiments/derived/out or releases/course.

**Secrets and identifiers:** no account IDs other than the placeholder 123456789012; no hosts besides 127.0.0.1, except the Dockerfile S3 host (P16-B-01) and the `.invalid` placeholder; no bucket names beyond `scholarchain-emu-*`. The fixture digit runs are P16-B-05.

**Dockerfile matches the documentation:** no; see P16-B-01. The header covers LTS/latest, never pushed, not part of the services build, and BuildIdentity (`docker` is a build input, so the build is dirty while uncommitted; harmless now).

**Latent, not reported as a finding:** the real E report's fused lists are truncated to 30 `top` entries with listLength 40. `judged_retrieved` counts every target rank, so it would undercount unjudged entries if a target ranked 31-40. A Grep found no rank above 30 in the frozen report.

## Files read

**Read in full: all 123 untracked paths in files-R-2.txt.** That is config/emulator.json, docker/emulator/Dockerfile, experiments/evaluation/evaluate.py (lines 1-1729), every file of the 18 fixture cases, intended-differences-v1.csv, rubric-v1.md, suite-v1.json, and scripts/emulator/OPERATIONS.md, env.sh, lib.sh, probes.sh and setup.sh. None is missing.

**Tracked modified files (12), reviewed through their complete diff hunks in diff-R-2.txt, not read in full:** experiments/README.md, experiments/derived/common.py, emr_capacity.py, queue_worker.py, experiments/results-summary-template.md, scripts/README.md, check.sh, cleanup.sh (plus lines 170-229 read directly), deploy.sh, lib/identity.sh (plus Grep of sc_bytes and sc_lib_err), run-bounded-pipeline.sh, run-experiment.sh.

**Supporting reads:** the work order, the three producer reports, ref-emulator-docker-doc.md, orchestrator-notes-s2.md, files-R-2-status.txt; plus targeted reads of the E retrieval report, a release record, sfn.tf:198-237, index-spec-v1.json, phase-11 human-gate.md:166-169 and orchestrator-summary.md:124-126, and Grep checks of the phase-15 runbook-log, approvals and review reports.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8), session 2 review R-2 (X1, I1, step 1 Dockerfile)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (no test run; every fixture traced by hand, see trace above; sha256-dependent values unverified until E1)
VALIDATION RESULTS: 123 of 123 untracked slice files read in full; 12 tracked files reviewed through their diff hunks; 2 major, 3 minor verified findings
ASSUMPTIONS: documentation text only from ref-emulator-docker-doc.md (no cli-reference excerpt, so `-b` is unverified); the orchestrator's re-hash of evaluate.py and the rubric is taken as recorded in orchestrator-notes-s2.md; bash 3.2 support of ${!v+x} believed, not verified
RISKS: P16-B-01 blocks E14 (tier 1) if not repaired, and the guessed S3 URL is a supply-chain risk if built as is; P16-B-02 inflates group 6 counts in a way the audit cannot detect; any repair to evaluate.py changes two suite pins (evaluationCode, rating-sheet generator)
MANUAL ACTION REQUIRED: none from this review; the human decides whether the documented ENV EMULATOR_BIND_ADDRESS replaces the work order's `-b 0.0.0.0` wording (P16-B-01)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 8 triage: P16-B-01 to build-engineer, P16-B-02 (with P16-B-04, P16-B-05 in the same pass) to experiment-engineer, P16-B-03 to infra-engineer (minor, recorded); step 10 re-check of the repaired files; X2 should build on the repaired factory extractor or re-pin after repair
```
