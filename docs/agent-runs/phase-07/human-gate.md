# Phase 7 + 8 human gate: storage, embeddings and S3 Vectors

## Gate

**ACCEPTED, with some work deferred.** Phases 7 and 8 ran together in one session (see prompt.md in this folder). The Phase 8 evidence is also here; docs/agent-runs/phase-08/README.md points to this folder.

- Everything that can run on my laptop is built and tested.
- code-reviewer approved the work after one repair round. There was one major problem (M1), and it is fixed.
- Nothing ran on AWS yet. That was the plan (decision 1). The AWS parts are listed under "Deferred", with deadlines.

## Spec checklist

Report names: build, domain, spark, storage and rag = the five agent reports; review = review-report.md; repair = repair-01.md; recheck = review-report-02.md.

### Phase 7 work order

| Item | Status | Source |
|---|---|---|
| S3: the six folders (raw, curated, graph, semantic-context, reports, logs) | Done. Bucket names come from config, never from code. | storage |
| S3: versioned keys and run manifests | Done. Files are never overwritten. | storage |
| S3: content hashes and source references | Done | storage |
| Neptune: load files for researchers, works, venues, institutions, topics and extra nodes | Done. Institutions and topics have no data yet, so they are skipped. Extra nodes added: author mentions, dataset snapshot, text chunks. | storage |
| Neptune: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY | Done. 6,432,669 rows in 21 files. | storage |
| Neptune: AFFILIATED_WITH, HAS_TOPIC | Not done. There is no data for them yet. | storage |
| Neptune: coauthor-edge details (number of joint papers, first and last year, window, link to evidence) | Done | storage, review |
| Neptune: researcher metrics | Done. Full-window metrics are on the researcher nodes; per-window metrics are in DynamoDB. | storage |
| Neptune: stable IDs, safe parameterized queries | Done | storage |
| Neptune: read-only access for runtime services | Partly done. The read-only code exists. The AWS permission (IAM) comes in Phase 12. | storage |
| DynamoDB: the six tables | Done, each with clear keys and the lookups they support | storage |
| DynamoDB: safe repeat writes; TTL only for temporary data | Done. Safe repeat writes were broken on AWS (M1) and are now fixed. Only QuerySession expires. | storage, review, repair |
| S3 Vectors: stable keys, small metadata, long text kept in S3 | Done | storage |
| S3 Vectors: filters (researcher, window, source, topic, institution, community, document type) | Done | storage |
| S3 Vectors: batch writes, retries, record failed vectors | Done | storage |
| OpenSearch (optional) | Not done, on purpose | storage |
| The 5 required vector indexes | Done. Their settings are in a file Terraform will read. | storage |
| Required tests (except the AWS one) | Done | storage, review |
| Required test: AWS tests behind a switch | Partly done. Written and kept out of `sbt check`; never run. | storage, review |
| Run `sbt check` | Done. Last run: 1,431 module and 336 contract tests, 0 failed. | repair, recheck |
| Run the AWS storage test suite | Not done. Deferred. | storage |
| Report: schemas, keys, indexes, metadata, batch sizes, retries, cleanup | Done | storage |
| Report: service limits from current docs | Partly done. Uses my Nov 15 table. Some API details are unchecked because the AWS docs tool could not start (uvx is not installed). | storage |

### Phase 8 work order

| Item | Status | Source |
|---|---|---|
| Work queue for embedding | Done. Local queue works; the SQS version is written but not run. | storage |
| Write vectors in batches | Done in code. Tested only against a fake. | storage |
| Keep source text and profiles in S3 | Done | storage, rag |
| Small metadata with each vector (key, subject, window, type, source, model, text hash, run) | Done | domain, storage |
| Skip text that hasn't changed | Done. The second run skipped all 251,094 documents. | rag |
| Record failures and retries in RunLedger | Done. The retry count can be too low (m2). | storage, review |
| Text templates (researchers, communities, paths, papers) | Done. Spark's text is reused unchanged (0 mismatches). 155,978 short paper summaries made from dblp data. | rag |
| Rules for splitting long text | Done. Nothing needed splitting. | rag |
| Embedding model version | Done. Changing the model forces everything to be re-embedded. | rag |
| Search smoke tests with known answers | Partly done. They pass with a fake model; the real Voyage test is written, not run. | rag, review |
| Format for passing search results to the answer step | Done. It carries links only, and says "never use this for numbers". | rag |
| The 5 required indexes | Done: 64,090 / 30,962 / 155,978 / 64 / 0 documents | rag |
| Local tests | Done | rag, recheck |
| AWS run with at least 10,000 vectors | Not done. Deferred, and the code to run it on AWS doesn't exist yet (A1). | review, rag |
| Report: speed, failures, duplicates, search times, counts, metadata, model version | Done, with local fake numbers marked "not representative" | rag |

### Spec gates

| Gate | Status | Source |
|---|---|---|
| P7: long text is not stored in vector metadata | Pass | review |
| P7: Neptune IDs match Spark IDs | Pass | storage, review |
| P7: each coauthor edge links to its papers | Pass | review |
| P7: DynamoDB keys support the planned lookups | Pass | review |
| P7: loading twice changes nothing | Pass locally. Fixed for AWS (M1), but only tested with stand-ins, not real AWS. | review, recheck |
| P7: AWS tests are limited and switched off by default | Pass | review |
| P7: reviewer approves the design and error handling | Pass (approved in the re-check) | recheck |
| P8: one vector key can't quietly point to different text | Pass. A rare two-writer case is recorded but not repaired automatically (m3). | review |
| P8: model version is stored | Pass | review |
| P8: the text can be fetched from S3 using the vector's link | Pass (100 of 100 checks) | rag, review |
| P8: search smoke tests pass | Pass with the fake model; real model later | review |
| P8: numbers come from the graph and metric stores, not from vector search | Pass for this phase (checked again in Phase 9) | review |
| P8: cost and object counts reported | Pass (estimates; real costs after the AWS run) | storage, rag |

## Results

- **Build:** AWS SDK 2.59.1 added to storage and rag only. One small HTTP client (no Netty, no Apache client). The other modules are unchanged.
- **Core:** new graph labels, new record types for documents and vector metadata, and new config sections. Nothing old was changed.
- **Spark export:** all 65 tables written as JSON Lines: 12,571,066 records, 7.96 GB. Every count matches. The Phase 6 output was not touched.
- **Storage local run:** 0 errors.
  - Neptune files: 6,432,669 rows (about 497 MB compressed).
  - DynamoDB: 758,598 items. Loading a second time changed nothing.
  - Vectors: 95,116 summaries. The second run skipped all of them.
- **Rag local run:** 251,094 documents, 0 errors. The second run skipped all of them.
  - Search check, "does a document find itself first?": profiles 25/25, communities 23/25 (25/25 when filtered by window), paths 25/25, papers 25/25.
- **Tests:** 1,431 module tests (up from 1,158) and 336 contract tests, all passing.
- **Expected cost of outside services:** $0. Voyage gives 200M free tokens and we need about 24–32M. Without the free tokens it would be about $1.42–$1.90. Bedrock and Anthropic: $0.

## What I reviewed

- prompt.md and docs/agent-runs/instructor-approvals.md.
- All five agent reports, the review, the repair report and the re-check, in full.
- The three M1 log files are saved in this folder. The re-check quotes them; I did not read them line by line.
- I checked that the counts agree across the Spark, storage, rag and review reports, and with decision 10.

## What happened

1. Five agents ran in order: build, domain, spark, storage, rag. Each passed `sbt check`.
2. code-reviewer asked for changes: 1 major problem (M1), 3 minor (m1–m3), 10 notes (A1–A10).
3. **M1:** in the AWS code, an error helper changed every AWS error too early, so the code could never react to expected errors. On real AWS, a second load would have crashed instead of skipping. A missing file or a missing index would also have crashed. storage-engineer fixed it and added tests: 8 of 12 failed before the fix, and 13 of 13 pass after.
4. The re-check approved the fix and noted 2 small test gaps (r1, r2).
5. Total time was about 4 hours. No AWS, Voyage or Anthropic calls. Nothing was committed by the agents.

## Decisions

1. **M1:** fixed. It is only tested with stand-ins; the real AWS test comes later.
2. **m1 (a Neptune neighbour search never says "results were cut off"):** fix in Phase 9, the first phase that uses it.
3. **m2 (AWS retries happen inside our own retries, up to 18 tries, and the retry count is too low):** fix in Phase 9, before any real AWS load.
4. **m3 (two writers at once can leave a vector and its record out of sync):** fix in Phase 11. Until then, the AWS load uses only one writer.
5. **r1, r2 (two small test gaps):** add in Phase 11.
6. **A1 (no code yet to run the vector load on AWS):** add to the Phase 9 prompt. It must be ready by Nov 20.
7. **A2 (Neptune load settings not checked):** check before the first Neptune load (Phase 9 prompt). One known problem: AWS docs say the setting `updateSingleCardinalityProperties` is not supported for openCypher, but our code sets it to TRUE. Remove it. Also check empty number cells, reloading the same edges, and `LIMIT $limit`.
8. **Voyage model:** keep `voyage-4` at 1024 dimensions. The instructor approved Voyage without naming a model; voyage-4-lite at 256 was only in my question. The README will say this.
9. **SQS DeleteMessage:** allowed. It only marks a queue message as done; it does not delete data.
10. **Example account ID 123456789012 in test files:** allowed. It is AWS's official example number, not a real account.
11. **Dataset snapshot ID `scholarchain-graph:dblp-2026-11-01`:** accepted. My prompt's `dblp:2026-11-01` was only an example.
12. **Spark export left out some extra columns** (path hop details, team sizes): accepted. They are listed in the export manifest, and Phase 9 can rebuild them from coauthor edges.
13. **Path lengths in Phase 9:** take them from Neptune or EvidenceBundle, never from vector metadata.
14. **A9 (packaging the services app with the AWS SDK isn't checked):** check before the first Docker build (Phase 13).
15. **No test-engineer this phase:** confirmed. Attack-style storage tests come in Phase 11.
16. **Disk:** keep the 7.5 GB export and the storage output under target/ (not in git). The AWS load will use them.
17. **.DS_Store files:** not committed. Remove them from git in a later cleanup.

## Manual changes

- I created docs/agent-runs/instructor-approvals.md with the instructor's approval of Voyage and the Anthropic API.
- I copied the reports and the three M1 logs into this folder and checked their sha256 hashes.
- I added docs/agent-runs/phase-08/README.md, which points here.
- I made no code changes.

## Deferred

| Item | Owner | Deadline |
|---|---|---|
| A1: code to run the vector load on AWS | storage-engineer, rag-engineer | Phase 9 (needed by Nov 20) |
| m1: Neptune "cut off" flag | storage-engineer | Phase 9 |
| m2: retries inside retries | storage-engineer | Phase 9 |
| A2: Neptune load settings | storage-engineer | Phase 9 |
| Check Voyage request format and limits | rag-engineer | before the first real embedding run (Nov 20) |
| AWS storage test suite | storage-engineer | Phase 14/15 |
| AWS load of at least 10,000 vectors, real search test, real speed and cost | storage-engineer, rag-engineer | Phase 14/15 |
| m3, r1, r2 | storage-engineer, test-engineer | Phase 11 |
| A9: packaging check | build-engineer | before Phase 13 |
| AWS resources: indexes, tables, Neptune load role, SQS queue, read-only Neptune access | infra-engineer | Phase 12 |
| Later ideas: extra core fields, more config keys | domain-engineer | later |
| Spark text fixes ("ACL (1) (2)", repeated ID in community lists) | spark-engineer | later (needs re-embedding) |
| Install uv so the AWS docs tool works | me | before amendment-02 |
| Older deferred items (handoff section 7) | as listed there | unchanged |

## Remaining risks

- The AWS code has never run against real AWS. M1 shows that bugs can hide there.
- Some API details are not checked against current docs (Neptune, S3 Vectors filters, S3 conditional writes, Voyage limits).
- Costs are estimates. DynamoDB prices differ between sources.
- Community summaries repeat across windows, so searches must always filter by window (Phase 9).
- The fake model only matches words. How good the real Voyage search is stays unknown until the AWS run.


## Acceptance

I accept Phase 7 and 8 as of commit 0cfa8875997fc9b40dc6468f8d8d5b257d7edf62.
