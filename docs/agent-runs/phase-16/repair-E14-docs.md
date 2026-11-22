REPAIR REPORT: repair-E14-docs (Phase 16 session 3, touch-up of docs/emulator.md for P16-L-25 to P16-L-30; minor, human approved one pass)

## Changes per finding (all in /Users/brian/Developer/repos/scholarchain/docs/emulator.md)

- P16-L-25 (:3, :5): "from the committed result files" -> "from the result files of run ..., committed together with this document"; "Results folder (committed by the human)" -> "Results folder (committed together with this document)". No timing claim beyond that. :3 also carries the one-line history note "Touched up after review (findings P16-L-25 to P16-L-30)."
- P16-L-26 (:101, :193): "20 resources created" / "20 created" -> "20 create operations: 18 resources (2 buckets, 6 tables, 1 vector bucket, 5 indexes, 4 queues), 1 bucket policy, 1 TTL setting; 0 failed". Evidence: setup.jsonl has 20 `created` outcomes (grep -c), PutBucketPolicy at setup.jsonl:5, UpdateTimeToLive at setup.jsonl:10.
- P16-L-27 (:77, :84): :77 adds "Step Functions was attempted by the probe script but not exercised (input missing; section 5.3)." The probe-script row now reads "... single-state tests (attempted; not exercised (input missing))" and its Services cell "S3 Vectors; Step Functions (attempted; not exercised)".
- P16-L-28 (:151, :162): 5.5 now says both refusals (S3 bucket policy and S3 Vectors bucket policy) go against the documentation's statement that resource policies are not evaluated, and that a matching outcome is not evidence the emulator evaluates policies like AWS. The M-01 row adds that both emulator refusals run against that statement, so "same outcome" is not evidence that the emulator evaluates policies like AWS.
- P16-L-29 (:161): D4a cell now reads "single-state tests that matched expectations: four inputs on the D3 plan (absent, A, B, invalid), which the four TestState probes mirror, and five on the L-01 plan". Evidence: docs/agent-runs/phase-15/runbook-log.md:26 (absent, A, B, invalid) and :43 (absent, A, 3, B, invalid).
- P16-L-30 (:131): "every check passed" -> "every check that was asserted passed", plus "3 of the 117 `implemented-passed` lines are recorded refusals, not successful requests: the 2 S3 policy-probe lines (PutObject and GetObject, HTTP 403 `AccessDenied`; recorded, not asserted) and 1 S3 Vectors line (GetVectors, `AccessDeniedException` after the deny policy); see section 5.5." Evidence: results-EmulatorAdaptersEmulatorSuite.jsonl:42-43 (httpStatus 403), probes.jsonl:14 (c-get-vectors-after-deny). Placed in the paragraph directly under the 5.3 table; the table counts are unchanged.

Nothing else changed. File length still 197 lines.

```text
STATUS: COMPLETE
OWNER: documentation-engineer
WORK ORDER: Phase 16 session 3, repair-E14-docs (touch-up P16-L-25..L-30, minor, one pass approved by the human); docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given, not recomputed); human decision P16-C-06
FILES CREATED: none in the repository (this report in the session scratchpad)
FILES MODIFIED: docs/emulator.md
COMMANDS RUN: grep/cut/ls/wc/shasum/git diff --stat (read-only) over docs/emulator.md, the results folder and docs/agent-runs/phase-15/runbook-log.md; one `sed -n '1,3p'` used only as a read filter on grep stdout (no file edit)
TEST RESULTS: not applicable (documentation only; no source change, no sbt run)
VALIDATION RESULTS: see VALIDATION EVIDENCE; identifier scans clean; all 5 relative link targets exist; no "20 resources", "every check passed" or "four single-state" text remains
ASSUMPTIONS: "recorded, not asserted" for the S3 probe follows the reviewer's citation of decision 6.6; P16-C-06 still holds (no code, prompts, diagrams or class structure added)
RISKS: the "committed together with this document" claim is true only if the human commits experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/ in the same commit (without the untracked .DS_Store files under experiments/)
MANUAL ACTION REQUIRED: human commits docs/emulator.md together with experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/, excluding .DS_Store files
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review of the touch-up, then commit; E15 gate check
```

## Replayed commands and outcomes

No runbook commands replayed (they need the running emulator, which only the human runs). Evidence greps listed above were run and observed.

## Link-check results

Relative links: docker/emulator/Dockerfile ok; config/emulator.json ok; scripts/README.md ok; experiments/results-summary.md ok; experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e ok. External AWS documentation URLs not fetched (no web tool); unchanged by this pass.

## Unverified claims

The AWS documentation statements (security page: policies not evaluated) are carried from the earlier draft and were not re-fetched in this pass.

## VALIDATION EVIDENCE

- twelve-digit runs (`(^|[^0-9])[0-9]{12}([^0-9]|$)`) in docs/emulator.md: 0
- `arn:`: 0; `amazonaws`: 0; `AKIA|ASIA`: 0
- `scholarchain-` names outside `scholarchain-emu-`: 1 occurrence, `scholarchain-emulator` (local image tag, used by the work order)
- `under repair|being repaired`: 0
- wc -l docs/emulator.md: 197
- git diff --stat vs HEAD (includes the earlier uncommitted E14 draft): 1 file, 125 insertions, 109 deletions
- shasum -a 256 docs/emulator.md: 9959b0cbc051008ef07517d0958ad538f9c461d6e0142e031623582ed0a20be1

files changed after these runs: none
