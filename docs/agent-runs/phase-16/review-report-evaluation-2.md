## Re-check: repair-E13-docs (P16-L-17 to P16-L-22) in docs/evaluation.md

(Saved verbatim by the orchestrator from code-reviewer's final message.)

All six findings are fixed, so the verdict is APPROVE. Every new number in the repair matches the frozen file it cites, and no interval value changed. The plain line from your instruction is next to all three EXPLORATORY interval tables and in the method summaries. I raise two new minor findings, both in the section 5 summary table (P16-L-23, P16-L-24). Neither blocks.

This was a read-only review of /Users/brian/Developer/repos/scholarchain/docs/evaluation.md (848 lines) at HEAD ecc13052b09e0b0396f47894e8858c6029632886. I used only Read, Grep and Glob. The diff file was read in part: it compares against the W1 draft at HEAD, so it holds all of E13 plus the repair. I therefore read the current document in full and checked the repaired lines directly.

### Status per finding

| ID | Status | Evidence checked |
|---|---|---|
| P16-L-17 | RESOLVED | The blindness limit is now in section 6 (:219), 4.4 (:148), the 10.4 join (:611-612), the 10.4 interpretation (:621) and 10.8 item 1 (:809). The status strings match the frozen sheet: claim-2 and claim-6 "Partially supported" (sheet.csv:68, :72) and "claim-1: conflicting" (:76). per-answer.csv:43 gives 7 claims for p15-demo-n.json: 4 verified, 2 partially supported, 0 unsupported, 1 conflicting. Lines :44-48 show the other five answers all verified. runbook-log :46 mentions "conflicting". The claim that every non-verified status in the rated set was visible in the answer texts holds. |
| P16-L-18 | RESOLVED | :621 now attributes "correct" to the rater and quotes sheet.csv:86 verbatim. :622 adds the negative result (kind a): `"abstained": false` is at p15-demo-n.json:3, per-answer.csv:43 has `abstained` = no, and rates.csv:71 gives 0/6. "Leads with 6 claims" is right (claim-2 to claim-7). "Not supported … is not in the coauthorship graph" appears only in the notes (sheet.csv:82). benchmark-v1.json has no match for premise, Nobel or prize, so "no false-premise category" holds. Abstention recall stays `not measurable`. |
| P16-L-19 | RESOLVED | :795 states the digest counts, and they match release-records.csv. Rows 2, 3, 4 and 8 read "verified 0; not-applicable 4; no running task 0; other 0". Rows 5, 6 and 7 read "verified 3; not-applicable 0; no running task 1; other 0". It also says that `verified` = yes is not digest verification. |
| P16-L-20 | RESOLVED | The headers now carry EXPLORATORY labels. The attempt-06 Wilson rows are a separate table headed "Wilson 95 % (EXPLORATORY: fixed hand-written benchmark, n = 30)" (:544). The demo table header is "Tool's interval (EXPLORATORY: hand-saved demo set, non-random, n = 6)" (:560). The section 5 Wilson row now names the demo abstention row too (:205). The plain line appears at :50, :51, :130, :153, :205, :480, :550 and :573. Group 3 uses "questions" (:50, :130, :480); group 4 uses your wording verbatim. Interval values are unchanged: :546-548 equal rates.csv:20-22, :562-571 equal rates.csv:50-71 (including Wilson [0, 0.390334]), and the 10.3 table (:476-478) equals all 15 bounds in retrieval summary.csv. |
| P16-L-21 | RESOLVED | T8 now has (f1) at :239 and (f2) at :240, and the D-query caveat is at :554. Both match agent-report-storage-D.md:82-83: the error class is dropped; about 14 Neptune server errors in D-query with no abstained and no other-5xx answer; failures degrade to identity outcome "unknown" with no top coauthors; failed calls are not recorded. The instance class is not quoted. |
| P16-L-22 | RESOLVED | :393 now limits the sentence to precision, recall and F1, and adds that coverage's zero-event bound 91.8-93.4 lies inside its bootstrap interval 90.1-94.5. This matches weighted-intervals.csv:2-5. For all three flagged metrics the zero-event range is wider than the bootstrap range. |

### Plain-line placement and interval values

- **Result tables:** every interval table in section 10 has the plain line directly below it: 10.3 (:480), 10.4 attempt-06 Wilson (:550) and 10.4 demo (:573).
- **Method summaries:** sections 1 (:50, :51), 4.3 (:130), 4.4 (:153) and 5 (:205) also carry it.
- **One gap:** two rows of the section 5 summary table do not carry the line themselves (P16-L-24).
- **Values:** no interval value was changed or removed. No interval was added to a descriptive set.

### New-error, overreach and identifier checks

- **Conclusions:** the new text draws no conclusion beyond the frozen data. Each new statement cites its frozen file or step D's report. It is labelled as a reading where it is one, for example "Step D reads about 14".
- **Identifiers:** a grep of docs/evaluation.md for researcher-ID, instance-class and long-digit patterns found no match. No researcher name, instance class or long digit run was added.
- **Status and history lines:** the line 3 status and the 10.9 repair list (:838-844) describe the repair accurately.

### New findings

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| P16-L-23 | minor | docs/evaluation.md:205 | The Wilson row first lists "group 1 unweighted rows (as printed in Phase 5)", then group 4's two sets, then says "Both are EXPLORATORY: the answers are not a random sample…". | A reader can take "Both" to mean group 1 and group 4. Group 1's Phase 5 Wilson intervals would then look EXPLORATORY and over "answers", which they are not (section 4.1 and 10.1 do not label them so). This ambiguity is new in the repair. Fix: write "The two group 4 sets are EXPLORATORY…". | documentation-engineer |
| P16-L-24 | minor | docs/evaluation.md:207-208 | Row :207 (group 3 percentile bootstrap) has neither the EXPLORATORY label nor the plain line. Row :208 (group 4 bootstrap) says only "EXPLORATORY, as above". | This is a small gap against your instruction ("next to every EXPLORATORY interval") within the section 5 summary table. The result tables and the other method lines are complete, so no reader of section 10 is misled. Fix: add the label and the plain line to :207, and the plain line to :208. | documentation-engineer |

### Not verified

- The repair report's sha256 of docs/evaluation.md (50b5ab9d…2160): I have no hash tool.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-16/prompt.md (sha256 c75cc5f895faed13880a69ec285231c5498fd50924c7b875fe7e02cce1707dc8, as given), Phase 16 session 3 re-check of repair-E13-docs (P16-L-17 to P16-L-22) in docs/evaluation.md; HEAD ecc13052b09e0b0396f47894e8858c6029632886
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation re-check; no test run)
VALIDATION RESULTS: P16-L-17, L-18, L-19, L-20, L-21, L-22 all RESOLVED; every new number equals its frozen source (sheet.csv:68/72/76/82/86, p15-demo-n.json:3, per-answer.csv:43-48, rates.csv:20-22/50-71, release-records.csv:2-8, weighted-intervals.csv:2-6, retrieval summary.csv:2-25, agent-report-storage-D.md:82-83); plain line next to all three EXPLORATORY interval tables and in sections 1, 4.3, 4.4, 5; no interval value changed; no identifier found; 2 new minor findings (P16-L-23, P16-L-24); no critical or major
ASSUMPTIONS: "per-answer.csv row 43" read as file line 43 (the convention used throughout the document); the repair report's document sha256 taken as given
RISKS: P16-L-23 wording could make group 1's Phase 5 Wilson intervals look exploratory; P16-L-24 leaves two section 5 summary rows without the plain line
MANUAL ACTION REQUIRED: the human decides whether P16-L-23 and P16-L-24 (minor) get a repair, or accepts them; the human commits docs/evaluation.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: optional minor touch-up by documentation-engineer (P16-L-23, P16-L-24), then human commit; E14 docs/emulator.md review; E15 gate check
```

VERDICT: APPROVE
