# Phase 4 work order

## Human architect decisions
1. No AWS in this phase. Verify with unit tests and local or mini-cluster integration tests on the Phase 3 samples. The bounded EMR smoke-run script belongs to infra-engineer in a later phase.
2. Build in this order, and get each one passing sbt check before starting the next:
   1. J01 DBLP extraction with a record-boundary-safe XML input format,
   2. J07 publication teams,
   3. J08 coauthor pair expansion, then J02 source normalizers (OpenAlex, ORCID, Crossref), J03/J04 exact identifiers, J05/J06 blocking and candidate features, and J09 graph export.
   If you hit a limit, stop after a passing sbt check and report what remains.
3. Use the Hadoop MapReduce Java API from Scala 3. Hadoop dependencies are Provided and match EMR 7.x (Hadoop 3.4.x), as recorded in CLAUDE.md.
4. All thresholds (maximum block size, oversize author team, and so on) come from configuration with documented defaults. None are hardcoded.
5. Use only the Phase 3 samples, schemas and manifest, and the modules/core contracts (including ExternalIdLink for foreign IDs). No new downloads.
6. No owner exists yet for shared production config files. Put Hadoop job configuration defaults and test fixtures in modules/hadoop's own resources. Shared config ownership will be assigned in a later amendment.
7. After hadoop-engineer finishes, test-engineer adds the adversarial cases the gate lists (malformed and truncated XML, Unicode, missing fields, duplicate IDs, common names, giant teams) without editing production code, and routes any failures to hadoop-engineer. Then code-reviewer reviews the full diff (include it in its work order). Route REQUEST_CHANGES findings to the owning agent once, then re-review.
8. Do not write anything under docs/agent-runs/ until ALL agent work, testing, reviews and repairs are finished. Keep each agent's final report verbatim until then. As the very last step, write them to docs/agent-runs/phase-04/ (agent-report.md, test-report.md, review-report.md, repair-NN.md, review-report-02.md if there is a re-review). Do not commit. Then stop and give me a summary.

## Work order (verbatim from course specification, Phase 4)
Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline.

Required jobs:

1. DBLP record extraction
- Read complete DBLP publication records without splitting an XML record across mappers.
- Support the selected DBLP record types present in the chosen snapshot.
- Extract DBLP key, title, author sequence, year, venue fields, ee links, URLs, and record type.
- Emit valid normalized WorkRecord and AuthorMention records.

2. External source normalization
- Implement one normalizer per selected dataset.
- Map source fields into shared contracts without losing source-specific IDs.
- Preserve author identifiers, affiliations, topic identifiers, abstracts, and work identifiers when available.
- Write malformed and unsupported records to versioned reject outputs.

3. Exact identifier extraction and join preparation
- Normalize DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, and other selected identifiers.
- Emit exact-identifier keys and duplicate or conflict counters.

4. Researcher and work blocking
- Emit bounded candidate blocks using normalized names, initials, coauthor context, title, year, venue, affiliation, and source.
- Prevent pathological common-name or title blocks from overwhelming reducers.

5. Candidate feature construction
- Join records within blocks.
- Compute deterministic match features.
- Emit MatchCandidate records rather than final guesses hidden inside logs.

6. Publication team extraction
- Emit one publication-team record containing the ordered author mentions for every retained work.
- Emit author-paper records keyed by author mention and work ID.
- Emit candidate unordered coauthor pairs for every paper with at least two authors.
- A paper with n authors generates n * (n - 1) / 2 candidate pairs before duplicates are consolidated.
- Preserve work ID, year, author position, and source provenance on every pair record.
- Count and report unusually large author teams because they can create quadratic pair expansion.

7. Graph export preparation
- Emit partitioned work, author-mention, team, pair-candidate, topic, institution, and other selected enrichment files for Spark.

Required operational behavior:
- Hadoop counters for input, accepted, rejected, duplicate, conflict, oversize block, oversize author team, emitted pair, missing key, and schema failure records.
- Explicit partition strategy.
- Idempotent output paths containing run ID and dataset snapshot.
- No mutable shared state across map or reduce tasks.
- Compression for intermediate outputs.
- Deterministic outputs for deterministic inputs.
- Unit tests for parsers, pair generation, normalization, and key functions.
- Mini-cluster or local integration tests for representative jobs.
- A bounded EMR smoke-run script owned by the infrastructure workflow, not by this agent.

Run sbt check and the local bounded pipeline.
Report job graph, mapper and reducer key-value contracts, partition counts, counters, rejected records, output paths, maximum observed team size, largest pair expansion, and the largest observed blocking group.
