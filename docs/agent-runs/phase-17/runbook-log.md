# Phase 17 session 3 runbook log

## Sitting 1 (started 2026-11-22 evening, after the session 2 commit)

### Resumption and session 3 start check (decision 1)

- (a) runbook-log.md: none before this sitting (first sitting of session 3).
- (b) `git log 50ba087..HEAD`: one commit, d8b23f24ef55fdd0fc602dc3fe17d07ecacd8eb2 (phase-17 session 2). HEAD = d8b23f24ef55fdd0fc602dc3fe17d07ecacd8eb2, which is the commit named in the human's first line. Pass.
- (c) `git status --short`: prints nothing. Pass.
- (d) step proofs: no E step has run yet; nothing to credit.
- `docs/agent-runs/phase-17/orchestrator-summary-s2.md` exists and was read (SESSION 2 RESULT: NOT_READY), with every open finding.
- prompt.md sha256 c22adfc57c7fde0cae63c8520393c2c81cd12280df7f5df773e70c2e2ec8ac24 (equals session 2's record).
- course-specification.md sha256 6a0eddaddbb91435198ec868699ca99aec22ae8ee5ff515b6f7b34f18e362695 (equals decision 22).
- `docs/agent-runs/phase-17/approvals.md`: not present yet (E0 creates it).
- Orchestrator command note: one `cd docs/agent-runs/phase-17` (read-only, inside a grep line) and one `cd` back to the repository root; recorded as commands outside the list (unplanned deviation).

### Steps

| Step | Status | Evidence |
|---|---|---|
| E0 | done (second attempt). Attempt 1: unverified (no file, no commit in this tree). Attempt 2: commit 95a8d9fead71db252af1d7ca833e368a63e14e23, which changes only docs/agent-runs/phase-17/approvals.md (1 line). | git show --stat HEAD |
| E1 decisions | recorded | Human: (1) repair P17-A-01 with an allow-list rule (exact accepted forms; any negation or absence word, "missing", "zero" and "lack" included, otherwise at most PARTIALLY_SUPPORTED); positive controls and the monotonic test stay; report whether P17-K-02 closes; if still open after this round, known limitation. (2) P17-F-01 accepted as a known limitation, documented in the threat model and final report, no amendment; the human checks for symbolic links in their gate. (3-10) agreed as recommended; the documentation pass (K-10, K-11, K-12 plus the decision wording) runs after the rag repair. The two read-only `cd` uses are accepted as an unplanned deviation. |
| E1 rag repair | done (completion notice received) | repair-E1-rag.md. Targeted rag 208/208, Golden 17/17, sbt check exit 0 (module 2352, contract 399, marker 2026-11-23T05:40:38Z, newer than both changed files: 23:21:31 and 23:21:46), packageCheck ok. Changed: ClaimVerification.scala, AbsenceRuleSuite.scala. New reason code ConnectionNotFound (docs/api.md). P17-K-02: both inputs closed. Disclosed gap: wording with no list word. |
| E1 code re-check | done: APPROVE for the E1 rag slice. P17-A-01 (P17-L-01) RESOLVED against the human's rule as written; P17-K-02 RESOLVED for both inputs; new minors P17-L-02 (hyphenated list words), P17-L-03 (single-name false negatives), P17-L-04 (surname-only, existed before E1). By the human's stopping rule and item 3-10 agreement, these are known limitations (no further code round). | review-report-P17-L-01.md (saved verbatim) |
| E1 decisions 3-10, spelled out (the human answered "agree" to the orchestrator's recommendations) | recorded | (3) P17-K-02 known limitation unless the A-01 fix closes it (it did, for its inputs); (4) P17-K-01 known limitation; (5) P17-K-10, K-11, K-12 repaired in one documentation pass after the rag repair, with the decision wording; (6) verified limits (6 unverified rows: IL-02, IL-04, IL-06, IL-11, IL-14, SL-29) accepted as an unplanned deviation; (7) P17-A-06, A-07, F-04 recorded minors, P17-F-05 avoided by a T/Z clone stamp; (8) F-02, F-03, F-06 to F-09, F-10, F-11, F-13 to F-16 known limitations; (9) the 43 twelve-zero fixture file names are expected file-name matches; (10) session 2 unplanned deviations 1 to 9 and B-03 accepted at the gate as listed. |
| E1 documentation pass | done (completion notice received) | repair-E1-docs.md. 7 Markdown files changed; link check 754/0 missing, 324 fragments/0 bad; PENDING counts unchanged; identifier scan of added lines 0. Agent commands outside the list: `sed -n` twice, `awk` once, `cd` prefixes (read-only). |
| E1 documentation re-check | done: APPROVE for the documentation slice. P17-K-10, K-11, K-12 RESOLVED. New minors P17-L-06 (four session 2 minors labelled "known limitation" instead of "recorded minors"; F-05 mitigation not named), P17-L-07 (P16-L-10 closure attributed to review-report-P17-L-01.md, which does not say it; headline wider than the code: full names needed), P17-L-08 (exact statuses cited to tests that assert only "not verified"), P17-L-09 (citations to the "E1 decisions" row instead of the spelled-out rows). Open for the human's decision (session 3 repair budget used). | review-report-P17-L-05.md (saved verbatim) |
| E1 identifier pre-scan (orchestrator) | done | `git diff` 0 twelve-digit runs; session 3 reports: 0 twelve-digit runs, 0 twelve-zero runs; 1 key-pattern hit = pattern words quoted in repair-E1-docs.md (the writer's scan description), expected. |
| E1 commit | given to the human (pending) | expected paths: 17 |
