# Phase 3 human gate

## Gate
- Every record says where its data came from (provenance). dblp records only hold dblp facts; OpenAlex and ORCID IDs are stored separately, each with its own source and snapshot date.
- Data format tests pass: sbt check, 638/638 (the core tests run under both Scala 3 and Scala 2.13). The shared tests/contracts folder is still empty (deferred).
- Dataset checks pass: validate.sh, 59/59.
- Scripts never download a full dataset by accident. The default DBLP download stops at 16 MB, and full downloads need an explicit --full flag.
- Author-pair ordering and year-range tests pass.
- The reviewer confirmed nothing was made up: no fake licenses, IDs or sources.
(All met.)

## What I reviewed
Both agent reports, both review reports, and both repair records. 

## Repairs
Review 1 had 3 blocking findings:
1. Two different people with the same name could get the same researcher ID.
2. OpenAlex IDs were saved on dblp records without saying which OpenAlex snapshot they came from.
3. The default DBLP download could pull the whole 1.1 GB file.
Both agents fixed their problems (repair-01, repair-02), each with a test that fails without the fix. The second review approved.

## Decisions
1. For the config files, plain JSON is fine. No new library needed. (No comments allowed in config files.)
2. Researcher IDs: approved. A researcher's ID comes from their earliest paper. It stays the same as they publish more, and only changes if two researchers get merged or split.
3. Outside IDs (OpenAlex, ORCID): approved storing them in separate link records, each saying where and when it came from.
4. Accepted the agent's looser dblp key check and the ID length.
5. Dataset subset: approved. 16 big CS conferences and journals, papers from 2015–2025, workshops included. Estimated about 124k–140k papers and 1.0M–1.3M coauthor pairs.
6. Full DBLP download (1.1 GB): approved. I'll run it myself, outside the repo, to get real counts.
7. For the ORCID and Crossref samples, it's okay to keep in my private repo. They're tiny and their license status is written down honestly. Revisit if the repo ever goes public.
8. Accepted the extra file names (separate reports for each agent, two review reports).
9. agent-report-domain.md is partly out of date; repair-01.md replaces those parts. I left the original as is.

## Deferred
- Shared contract tests and checks across records: Phase 11 (test-engineer). Phase 6 Spark tests should check some of these too.
- Three small fixes the reviewer found (N1, N2, N3): a later repair.
- Small text-trimming difference between the Python scripts and Scala.
- Nobody owns real config files yet: amendment-02.

## Manual changes
None.

## Remaining risks
- Paper and pair counts are estimates until I run the full DBLP download.
- Researcher IDs depend on rules that aren't tested across records yet.
- Unknown fields in JSON get silently ignored instead of rejected.
- Unresolved dataset questions: whether dblp keys stay the same between releases, an unpublished DTD version, unknown API rate limits, possibly low counts if OpenAlex has extra entries per venue, and ML conferences like ICML/ICLR not included.
- The reviewer couldn't visit websites itself, instead, the dataset agent checked the official pages.

## Acceptance
I accept Phase 3 as of commit 678895258722700efcc08bba92f7e9c78ba2dda2.

## Accepted commit
See commit.txt.
