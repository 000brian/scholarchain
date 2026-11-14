# Phase 3 work order

## Human architect decisions

1. Datasets: DBLP (required) plus OpenAlex, ORCID and Crossref. dataset-engineer must verify current official access, snapshot availability, license and size for each from official sources and record them; do not invent any URL, license, size or identifier. If a dataset is not usable, report BLOCKED-ON with evidence instead of substituting.
2. Bounded corpus: propose a DBLP subset rule (e.g. venues and year range) that yields at least 100,000 works and roughly 1,000,000 coauthor edges, and restrict enrichment data to records that can link to that subset. Record the rule in the manifest. Scripts must never download a full corpus unless an explicit flag is passed.
3. Small, legally redistributable samples only, small enough for git.
4. Typesafe Config (HOCON) plus environment variables is pre-approved. If domain-engineer needs it, it reports BLOCKED-ON build-engineer and the orchestrator routes a minimal work order to build-engineer to add it, pinned in the ledger.
5. modules/core must compile under Scala 3 and 2.13: use sealed traits with case objects for the "enums" the spec asks for, not Scala 3 enum.
6. dataset-engineer first, then domain-engineer, as separate delegated work orders. Then code-reviewer reviews the full diff (included in its work order), with special attention to invented licenses, identifiers or sources. Route REQUEST_CHANGES findings to the owning agent once and re-review.
7. sbt check must pass at the end of this phase and write .claude/.last-test-run.
8. Write each agent's final report verbatim to docs/agent-runs/phase-03/ (agent-report-dataset.md, agent-report-domain.md, review-report.md, repair-NN.md). Do not commit. Stop and show me a summary.

## Work order (verbatim from course specification, Phase 3)
Use the dataset-engineer and domain-engineer, in separate delegated work orders, to create the ScholarChain data contracts and dataset manifest.

Dataset-engineer work:
- Create datasets/dataset-manifest.yaml for DBLP and every selected enrichment dataset.
- Include source, license, snapshot, format, size estimate, S3 prefixes, checksums, subset rule, schema, linkage keys, update method, limitations, privacy notes, and validation commands.
- Create bounded download and sample-generation scripts under datasets/scripts.
- Create representative legal sample files under datasets/samples, small enough for the repository.
- Create schema notes under datasets/schemas.
- Scripts must be idempotent and must not download a full corpus by default.

Domain-engineer work:
- Create immutable Scala domain records for WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, and AnswerResponse.
- Define stable researcher, work, edge, evidence, and run ID functions.
- Define enums for source type, match outcome, evidence type, collaboration metric, query operation, and verification status.
- Define a configuration loader using environment variables plus versioned HOCON or YAML files. Do not hardcode bucket names, endpoints, thresholds, model IDs, AWS account IDs, graph windows, or bridge-algorithm parameters.
- Create serialization and schema-version tests.
- Freeze representative JSON wire examples in contract tests.
- Add validation that rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and time windows where start year exceeds end year.

Run sbt check.
Report sample serialized records, stable ID formulas, pair canonicalization rule, configuration precedence, schema versions, and known unresolved dataset questions.
