# review-report-demo-3: code-reviewer re-check of the G-1, G-2 and G-3 repairs, the video-deadline pointers and the CST follow-up (docs/demo-script.md and README.md), Phase 17 session 3, post-release-candidate docs-only pass

(Saved by the orchestrator from code-reviewer's final message; verdict, checks and findings verbatim in substance.)

VERDICT: APPROVE

G-1, G-2 and G-3 are repaired. The three video-deadline pointers carry the correct anchor and the exact quoted heading, and they say "Nov 30, 8 PM CST" as the CST follow-up asked. Compared with diff-demo-script-2.txt, only the lines named in repair-docs-demo-2.md changed. I found no critical, major or minor problem.

## 1. G-1: the clip 27 chain (demo-script.md:223)

- Line 223 now has exactly one `Replay:` token and no `replayed in phase 17 (E` token. The old step sub-bullets (diff-2 lines 406 to 409) are gone; :224 is the clip 28 "Show" bullet.
- The replay line is honest ("not replayed in phase 17 (demonstration-only line for clip 27, to record; not a README line; ...)") and complete: Hadoop E5.13 (row "E5 part 1 (repeat, R3; ...)", 150 s, fresh clone of `4feef0f`, only the output folder differs); Spark E5.07 (43 of 43, 222 s, and where the 222 s comes from); G7 row "E12 G7 replay (test-engineer)" (1 of 1, the suite alone 7 of 7, HEAD `1823cbf`, same code as `4feef0f`, in the main checkout). Values match those verified in review-report-demo-2 section 2.
- Success lines and times stay description only in the "Show (J2, clip 27, to record)" bullet at :222 (diff-4 line 404 equals diff-2 line 404).

## 2. G-2: the definition at :11 and every use of the "E12 G7 replay" row

- :11 adds: "or that test-engineer replayed, runbook-log row "E12 G7 replay (test-engineer)", in the main checkout at HEAD `1823cbf` (the lines that cite this row say "in the main checkout")"; the rest unchanged.
- Every line citing the row says "main checkout": :11, :210, :212, :223. On :223 the row is cited twice; the first citation is the source of the 222 s Spark time (read from the E5 repeat log, a fresh clone), so "in the main checkout" correctly attaches only to the G7 citation.

## 3. G-3: the shot label at :273

"(J7, part 3, and part 4 if it holds the leftover counts):" matches :272, :275 and the "in part 3 or part 4" sentence. Fixed.

## 4. The video-deadline pointers (README.md:400 and :456, demo-script.md:284)

- Target heading instructor-approvals.md:122 is exactly `## 2026-11-24: Deadline extension; demonstration video due Nov 30, 8 PM` (1 anchored match; no other heading has the same text; headings :1, :5, :36, :50, :62, :80, :101, :122, :140).
- GitHub slug: `2026-11-24-deadline-extension-demonstration-video-due-nov-30-8-pm` (unique, no double hyphens, no suffix); all three links use it. Paths correct relative to each file.
- Each line quotes the heading exactly (no zone, as the heading) and says "due Nov 30, 8 PM CST".
- Claims match the entry: :129 moves the project submission deadline to November 30, 2026, 8:00 PM CST; :138 says the tag comes before the video is published and that the README (D19) and docs/demo-script.md say the video was not published at tag time and is due Nov 30, 8 PM CST. "instructor-approved deadline" and "the instructor's deadline extension" are fair descriptions; neither claims more than the entry.

## 5. Nothing else changed since diff-demo-script-2.txt

| diff-2 line | diff-4 line | File:line | Change |
|---|---|---|---|
| 37 | 37 | README.md:400 | adds CST, the anchor and the quoted heading |
| 60 | 60 | README.md:456 (D19) | same as above |
| 66 (hunk header) | 66 | demo-script.md | `+1,290` becomes `+1,286` (4 lines removed in G-1) |
| 82 | 82 | demo-script.md:11 | G-2 clause |
| 405 to 409 | 405 | demo-script.md:223 | G-1, five lines become one |
| 483 | 479 | demo-script.md:273 | G-3 label |
| 497 | 493 | demo-script.md:284 | the pointer, CST, anchor and quoted heading |

Every other line is the same, including the README hunks at :106, :149, :172, :434 and :438. instructor-approvals.md is not in the diff (the human's edit).

## 6. Identifiers in added lines

Short commit hashes `1823cbf` and `4feef0f` (already in the file), the dated anchor (no digit run of 10 or more), counts in words. No `arn:`, `s3://`, `amazonaws`, AKIA key, IPv4, `@`, account ID, bucket name or N/M ratio.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| none | none | none | none | none | none |

Notes for the human (not findings): (1) instructor-approvals.md:132 says only the last commit before the deadline is graded, so the commit that replaces `VIDEO LINK: added after the tag` with the link must land before Nov 30, 8 PM CST. (2) The 421 s attribution (:9) is still unconfirmed. (3) The 222 s value and the "E12 G7 replay" row exist only in the scratchpad runbook-log until copied.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: ScholarChain Phase 17, session 3, post-release-candidate docs-only pass: read-only re-check of the G-1, G-2 and G-3 repairs, the video-deadline pointers and the CST follow-up
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (documentation only)
VALIDATION RESULTS: G-1 one Replay line, honest and complete; G-2 definition widened, every citation says "main checkout"; G-3 fixed; pointers' anchor, quoted heading and CST correct and matching :129, :138; diff-4 vs diff-2 only the 7 listed changes; no identifiers in the added text.
ASSUMPTIONS: GitHub's standard heading-slug rule (no rendered page checked); diff-demo-script-4.txt is the current diff (spot-checked against the files).
RISKS: the video-link commit must land before the deadline; 421 s unconfirmed; log rows only in the scratchpad until copied.
MANUAL ACTION REQUIRED: confirm 421 s; copy the runbook-log and test-report-demo-g7.md; run the identifier scan and commit; commit the video link before Nov 30, 8 PM CST.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human's identifier scan and commit; recording of clips 22 to 28; the video link after the tag, before the deadline.
