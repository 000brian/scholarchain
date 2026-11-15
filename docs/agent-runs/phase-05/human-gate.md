# Phase 5 human gate

Phase 5 ran twice. Run 1 built entity resolution and locked the scoring rules (policy er-v1). Run 2 checked the system against those labels.

## Gate
- Conflicting IDs never merge: met. If two ORCIDs disagree, the pair or cluster is marked CONFLICT and kept apart (agent-report-er.md, review-report.md).
- The LLM can abstain: met. The LLM step is built and tested, but no real model was connected. The stand-in always abstains: 1000 pairs were sent and all 1000 abstained (agent-report-er-eval.md).
- Test examples can be traced to their sources: met. Every error row shows its dblp keys and links (repair-08, review-report-02.md).
- Gold labels are human-made: met.
- Precision and recall are reported separately: met (agent-report-er-eval.md).
- No leakage from the labels into the matcher: met. The rules were locked and committed (2297990) before any label existed, and they never changed after that. No matching code reads the labels (review-report-02.md).
- sbt check passes: 1065 module tests and 336 contract tests. Two of the contract tests are marked "expected to fail" for known, accepted problems (decision 8).
(All met.)

## Spec checklist
| Item | Status | Source |
|---|---|---|
| Exact ID rules for DOI, ORCID and OpenAlex | Done | agent-report-er.md |
| Exact ID rules for arXiv and repository URLs | Built, but this data has none | agent-report-er.md |
| Semantic Scholar rule | Not used (not one of our datasets) | prompt.md |
| Name, title and DOI clean-up | Done | agent-report-domain.md, agent-report-er.md |
| Match features | Done, except citations (no citation data) and affiliations (no enrichment data yet) | agent-report-er.md |
| Versioned scoring rules | Done: er-v1, sha256 4946b350…807ac | agent-report-er.md |
| Separate thresholds | Done: accept 0.90, reject 0.30, LLM band 0.60–0.90 | agent-report-er.md |
| Conflict detection | Done | agent-report-er.md |
| LLM rules (bounded band, strict format, no new IDs, cache) | Done and tested, no real model | agent-report-er.md |
| Four kinds of output | Done: 8,828,725 accepted, 6,661,746 rejected, 8 conflict; 5.2% unresolved; 200,484 researchers | agent-report-er.md |
| Review queue | Done: 300 pairs, scores hidden | agent-report-er.md |
| At least 200 reviewed pairs | Done: 300 labeled (112 MATCH, 135 NON_MATCH, 53 UNSURE) | agent-report-er-eval.md |
| Precision, recall, F1, confusion matrix, coverage | Done for researchers. Papers: exact links only, and there are 0 in this run because no enrichment data was loaded | agent-report-er-eval.md |
| Results for each match path | Done, except the LLM path, which is empty (no real model) | agent-report-er-eval.md |
| Error analysis | 1 false positive and 19 false negatives (10 shown) | agent-report-er-eval.md |
| Threshold sensitivity | Done (report only) | agent-report-er-eval.md |

## Results
- Plain counts over the 300 labeled pairs: precision 98.9%, recall 83.0%, F1 90.3%, coverage 58.3%, unresolved 36.3%.
- Weighted to the whole run: precision 100.0%. Its safe range is 82.9% to 100.0%, and that range should always be quoted with it (the narrower range in the table is not reliable). Recall 95.3%, F1 97.6%, coverage 92.5%, unresolved 4.3%.
- Data: all 16 venues, 155,978 papers, 758,328 author mentions, 16.8 million candidate pairs.

## What I reviewed
Both prompts, the freeze note, all agent reports, both test reports, both review reports, and repair-01 to repair-09.

## What happened
1. I downloaded the full DBLP file outside the repo and counted the subset: 156,077 papers, all 16 venues present.
2. Run 1:
   - The agents recorded the counts in the dataset manifest.
   - They updated the shared records and improved Hadoop's blocking.
   - They built entity resolution and ran it on the real data.
   - Seven repair rounds fixed what test-engineer and code-reviewer found.
   - Review approved it, and both real runs were redone on the approved code.
3. I committed the code before labeling (2297990, notes in 68d8726). This proves the rules came first.
4. I made the gold labels.
5. Run 2: the agents measured the results against my labels. The report code needed three repair rounds (repair-08 and repair-09). Review approved it.
6. Two interruptions:
   - I stopped a first start of run 2 to edit the prompt. Its files were overwritten and not used.
   - A network outage at 6:58 PM stopped a repair. It picked up again from disk around 8:55 PM.
7. The tool blocked the orchestrator from writing the run 2 reports, so I copied them into the folder myself without changes.

## Decisions
1. F3 (how name variants are grouped): accepted the agents' reading. One starting group per exact dblp name, plus a rule that joins names that differ only by accents.
2. m7 (scoring weights): accepted as locked. The results show only 1 wrong merge among 94 accepted pairs.
3. Middle-name variants are never compared (97,681 names affected): no fix in this phase. Recorded as a known gap.
4. Size limit of 200 for oversize blocks: accepted. The 43,466 names it leaves out stay in their dblp groups.
5. Run 1 small findings:
   - m1: fix before any real LLM is used.
   - m2: the policy hash is recorded here.
   - m3 and m4: explained in the run 2 report.
   - m5: both real runs set the blocking options by hand. The default setting must be fixed before any cloud run.
6. Kept repair-19 (report code only, with a test). The LLM section reports only the counts. "No live model" appears only in the report text.
7. Kept the missing-entry rule. Accepted repair-28.
8. Run 2 small findings (M1 to M4): accepted for now, no repair.
   - Two tests (F8 and F9) are marked "expected to fail". They will start failing on purpose once the problems are fixed.
   - The safe precision range (82.9% to 100.0%) is quoted wherever precision is quoted.
9. I approve the dataset subset (dblp-subset-v1) with its measured size: 156,077 papers and 1.6 million coauthor pairs. Older parts of the manifest will be updated later.
10. Finding 11 (abstracts, authorships, affiliations): moved again. Abstracts are not collected at all. Decide before Phase 9.
11. Agents sometimes used read-only commands that weren't on the allowed list (for example sort, sed -n, and searches under ~/Library). Nothing was changed. Noted as a process slip.

## Manual changes
- My personal settings file (`.claude/settings.local.json`, never committed): allowed access to the DBLP folder, and pre-approved sbt and a few read-only commands. The safety guard and the deny rules were not changed.
- Files I wrote: prompt.md, prompt-02.md, the subset counts, the freeze note and the gold labels.
- I copied the run 2 reports into the folder (see What happened).
- Instructions I gave the orchestrator during the runs:
  - run sbt one at a time;
  - resume after the outage;
  - my rulings on repair-19, Finding 6, the stale 15:16 files, M1 to M4, and F8 and F9.
- I did not edit any code or report the agents wrote.

## Deferred
- Before any cloud run: Phase 4 finding 2 (stale outputs can be reused), and the default blocking setting (m5).
- Before any real LLM: m1, M1 and M2.
- Optional, after Phase 9: run a real LLM on the band pairs to fill in the LLM results.
- Phase 16: fix M3 and M4, and remove the "expected to fail" marks.
- Fetch the enrichment data (OpenAlex, Crossref, ORCID) so paper matching has links. Not scheduled yet.
- The finding 11 decision before Phase 9. Older small findings from Phases 3 and 4.
- Stop tracking the `.DS_Store` files (later build clean-up).

## Remaining risks
- Some real matches can never be found: middle-name variants, the 43,466 left-out names, and 88,663 mentions with no candidates.
- Some weighted ranges are not reliable, so only the safe ranges should be quoted.
- The LLM results and the paper-matching results are empty in this phase.
- Test results come from the agents' own output. The reviewer cannot run commands.


## Acceptance
Freeze before labeling: 2297990 (code) and 68d8726 (run 1 reports).
I accept Phase 5 as of commit a5322d4c421b52ac8d2aeb8f146d94daa78427fc.
