# Phase 4 human gate

## Gates
- Real Hadoop MapReduce jobs (J01–J09), run locally, not plain loops.
- The DBLP reader reads every record exactly once, even where the file is split.
- Coauthor pairs are right for any team size (n authors → n(n-1)/2 pairs).
- Counters add up: input = accepted + excluded + rejected, for every job.
- Running the same job twice gives the same records.
- Every output record says which job and run made it.
- Hard test cases were added: broken XML, accented names, missing fields, duplicates, common names, huge author lists.
- Code review approved it on the second round.
- sbt check passes: 799 module tests + 87 contract tests.
(All met.)

## What I reviewed
I read all 11 Phase 4 reports: 3 agent reports, 2 test reports, 4 repair reports and 2 review reports. 

## What happened
1. hadoop-engineer built every job except J06. J06 needed things the core records didn't have yet, so it stopped.
2. I let domain-engineer add those things to core. It made new versions of the changed records instead of changing the old ones.
3. hadoop-engineer then finished J06.
4. test-engineer found bugs: records lost at file edges, accented names not matching, and a setting that wrongly changed the run ID. All were fixed.
5. A network outage stopped test-engineer once. It picked up from where it was.
6. The first review asked for changes: merged pairs had the wrong run ID, plus smaller issues. These were fixed and the second review approved.

## Decisions
1. Core changes allowed: author positions on pairs, a MatchCandidate record, paper links on works, an OpenAlex topic ID. The spec needs these.
2. Not done yet: arXiv/Semantic Scholar IDs (not our datasets), blocking by anything other than author names, and blocking of papers (not just authors). Those need entity-resolution rules, so they move to Phase 5.
3. Every job records which earlier runs it read, so results can be traced back.
4. Accented names are written in one standard form before comparing. This rule moves into core in Phase 5 so every module uses it.
5. J06 only measures simple facts about each pair (same name, homonym number match, shared coauthors, same ORCID, same venue, same paper, year gap). It decides nothing yet; Phase 5 decides.
6. Hadoop libraries were added for tests only. The real app is unchanged.
7. Settings that don't change the output no longer change the run ID.
8. Same run ID means the same records, not byte-identical files. File layout can still differ.
9. Kept the fix that keeps OpenAlex topics with an ID but no name.

## Deferred
- Before any cloud run (Phases 12–14): fix finding 2. Right now a rerun after a code change could reuse old results by mistake.
- Phase 5: move the name rule into core and export abstracts, authorships and affiliations (findings 10 and 11).
- Later: small cleanups (findings 12–14, N1–N5). Big gzip files can't be split, so J01 runs on one machine; handle that when we set up EMR.
- I accept the reviewer’s new minor findings N1–N5 as recorded risks, to fix later.

## Manual changes
None. I did not edit any files the agents made.

## Remaining risks
- Old results could be reused by mistake until finding 2 is fixed.
- J06 found 0 real candidate pairs on the small real sample. It has only been tested on made-up data. Phase 5 needs a bigger piece of real DBLP.
- sbt check now takes about 20 minutes, which slows later phases.
- The reviewer can't run commands itself, so test results come from the agents' output.

## Acceptance
I accept Phase 4 as of commit 4d8598ffc6218ac6efcb32b01f4f3d5707bf01c2.
