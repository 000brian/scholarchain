# ScholarChain architecture

ScholarChain builds an evidence-bearing, temporal, weighted coauthorship graph from the DBLP XML release and answers collaboration-breadth, connectivity and bridge questions with machine-readable evidence. This document describes two related systems: the **build-time workflow** (how the repository was built by a human architect and Claude Code agents) and the **runtime architecture** (the data pipeline and the query services as deployed on AWS in Phases 14 and 15).

**State at Phase 17:** the AWS environment no longer exists. `scripts/cleanup.sh` removed 175 Terraform resources on Nov 21 under the instructor-approved teardown (Phase 15 gate, [`docs/agent-runs/phase-15/human-gate.md`](agent-runs/phase-15/human-gate.md)). Everything below the build-time section describes the system as it was built and run, with the records that show it. The documented final state is in [`docs/final-state.md`](final-state.md) (Phase 17 step W2).

Related documents: [`docs/data-lineage.md`](data-lineage.md) (what each stage reads and writes), [`docs/api.md`](api.md) (the REST routes), [`docs/agents.md`](agents.md) (agent ownership, owned by factory-engineer), [`docs/evaluation.md`](evaluation.md) (measured results).

Contents:

- [1. Build-time workflow](#1-build-time-workflow)
- [2. Runtime architecture](#2-runtime-architecture)
- [3. The runtime query workflow](#3-the-runtime-query-workflow)
- [4. Why each technology is used](#4-why-each-technology-is-used)
- [5. Consolidation of services](#5-consolidation-of-services)
- [6. What was not built](#6-what-was-not-built)

## 1. Build-time workflow

```mermaid
flowchart TD
    H["Human architect<br/>work orders, ratification, plan signatures,<br/>every script run, commits"]
    O["Claude Code orchestrator<br/>(main session, no agent file)"]
    H -->|"phase work order<br/>docs/agent-runs/phase-NN/prompt.md"| O
    O -->|"report to the human<br/>STATUS: BLOCKED-ON human"| H

    subgraph AG["18 subagents (.claude/agents/), fresh context each"]
        direction LR
        F[factory-engineer]
        B[build-engineer]
        DOM[domain-engineer]
        DS[dataset-engineer]
        HA[hadoop-engineer]
        ER[entity-resolution-engineer]
        SP[spark-engineer]
        ST[storage-engineer]
        RG[rag-engineer]
        SV[service-engineer]
        TE[test-engineer]
        CR["code-reviewer<br/>(read-only)"]
        IN[infra-engineer]
        DE["deploy-engineer<br/>(no Write or Edit)"]
        EX[experiment-engineer]
        DOC[documentation-engineer]
        IR[incident-responder]
        DU[dependency-updater]
    end

    O -->|"one work order per step"| AG
    AG -->|"standard report contract<br/>or BLOCKED-ON OWNER"| O

    subgraph CTRL["Control plane (factory-engineer, human-ratified)"]
        G["PreToolUse guard<br/>guard-dangerous.sh"]
        S["Stop and SubagentStop gate<br/>verify-tests-ran.sh"]
        P["Permission rules<br/>.claude/settings.json"]
        M["MCP servers, read-only<br/>.mcp.json"]
    end

    AG -.->|"every tool call"| G
    AG -.->|"completion"| S
    O -.-> G
    AG --> R["Repository: code, tests, Terraform,<br/>scripts, documents"]
    R -->|"sbt check, code review,<br/>human gate, commit"| H
```

*Caption: hub and spoke. The human gives each phase a work order; the orchestrator routes each step to the one owning subagent; subagents never call each other; hooks and permission rules check every tool call; the human ratifies, signs, runs the scripts and commits.*

**How it works** (from `CLAUDE.md` and [`docs/agents.md`](agents.md)):

- **Human architect.** Writes each phase's work order under `docs/agent-runs/phase-NN/prompt.md`, ratifies constitutional changes, signs Terraform plans (`APPROVED-PLAN-SHA256:` lines in the phase's `approvals.md` or `human-gate.md`), labels the entity-resolution gold set, runs every deployment, pipeline, experiment and cleanup script from Phase 14 on, and writes each phase's `human-gate.md` and commit.
- **Orchestrator.** The main Claude Code session. It interprets the work order, invokes the owning subagent, reads its report, routes `BLOCKED-ON` work, and does no specialist work itself.
- **18 subagents.** One file each under `.claude/agents/`, one owner per artifact path ([`docs/agents.md`](agents.md), "Artifact ownership"). The reviewer has no write tools; deploy-engineer has no Write or Edit and runs only approved scripts; infra-engineer may plan but never apply.
- **Hooks.** `.claude/hooks/guard-dangerous.sh` (PreToolUse) blocks destructive commands, Terraform apply and destroy, secret reads, writes outside an agent's territory and watchers; `.claude/hooks/verify-tests-ran.sh` (Stop and SubagentStop) refuses completion after a source change unless fresh, passing sbt test reports or the `sbt check` marker exist; `.claude/hooks/format-scala.sh` formats Scala after writes; `.claude/hooks/validate-agent-files.sh` validates the agent system.
- **Gates.** Each phase ends with targeted tests, a full `sbt check` by the wave closer, code-reviewer (and test-engineer where the phase needs it), and the human gate. Phase records live in `docs/agent-runs/phase-00/` to `docs/agent-runs/phase-17/` plus `amendment-01/` and `amendment-02/`.
- **MCP servers** (`.mcp.json`): `aws-api` (read-only), `aws-docs`, `terraform` (registry documentation) and `github`, which is defined but switched off ([`docs/agents.md`](agents.md), "MCP servers and trust boundaries").

## 2. Runtime architecture

```mermaid
flowchart LR
    subgraph SRC["Sources"]
        DBLP["DBLP XML release<br/>dblp-2026-11-01 + dblp.dtd"]
        ENR["Enrichment datasets<br/>OpenAlex, Crossref, ORCID<br/>(manifest and samples; not run on AWS)"]
    end

    subgraph LAKE["S3 data bucket"]
        RAW["raw/"]
        CUR["curated/<br/>Hadoop outputs, ER outputs"]
        GR["graph/<br/>Spark runs and exports"]
        RAGP["rag/<br/>semantic documents, reports"]
    end

    subgraph EMR["Amazon EMR, transient clusters"]
        HJ["Hadoop MapReduce<br/>emr-7.14.0, J01 to J09"]
        SJ["Spark graph analytics<br/>emr-spark-8.1.0"]
    end

    LAP["Entity resolution<br/>on the architect's laptop<br/>(run-bounded-pipeline.sh --stage er)"]
    SFN["AWS Step Functions<br/>pipeline state machine"]

    DBLP --> RAW
    ENR -.-> RAW
    SFN -->|"starts and always ends"| EMR
    RAW --> HJ --> CUR
    CUR --> LAP --> CUR
    CUR --> SJ --> GR

    subgraph STORES["Serving stores"]
        NEP["Amazon Neptune<br/>db.t4g.medium<br/>coauthorship graph"]
        DDB["Amazon DynamoDB<br/>6 tables, on demand<br/>metrics, evidence, audit, jobs"]
        S3V["Amazon S3 Vectors<br/>5 indexes<br/>semantic context"]
    end

    OPS["ECS ops task<br/>loaders, rag-ingest"]
    GR --> OPS
    OPS --> NEP
    OPS --> DDB
    OPS --> S3V
    OPS --> RAGP

    subgraph RT["ECS on Fargate ARM64, one image"]
        QS["query service<br/>runtime agents in process"]
        ES["evidence service"]
        GS["graph service"]
        WS["worker service<br/>job and embedding queues"]
    end

    CL["Client"] -->|"HTTPS, X-Api-Key"| APIGW["API Gateway HTTP API"]
    APIGW --> ALB["Internal load balancer"]
    ALB --> QS
    ALB --> ES
    ALB --> GS
    QS -->|"async jobs"| SQS["Amazon SQS<br/>job queue, embedding queue,<br/>dead-letter queues"]
    SQS --> WS
    QS --> NEP
    QS --> DDB
    QS --> S3V
    QS --> LLM["Anthropic API<br/>claude-haiku-5-5"]
    QS --> VOY["Voyage AI<br/>voyage-4, 1024 dimensions"]
    WS --> NEP
    WS --> DDB
    WS --> S3V
    ES --> DDB
    GS --> NEP
    RT -.->|"logs, metrics, alarms"| CW["Amazon CloudWatch"]
    EMR -.-> CW
    SECR["AWS Secrets Manager<br/>4 secret containers"] -.-> RT
```

*Caption: the deployed runtime (Phases 14 and 15). Hadoop and Spark ran on transient EMR clusters started by Step Functions; entity resolution ran locally between them; an ECS ops task loaded Neptune, DynamoDB and S3 Vectors; four ECS services served the API behind API Gateway and an internal load balancer. Removed on Nov 21.*

The pieces, with the record that shows each one:

| Piece | As built | Record |
|---|---|---|
| S3 data lake | one bucket, prefixes `raw/`, `curated/`, `graph/`, `rag/`, `artifacts/`, `logs/`, `reports/`; versioning, encryption, public access blocked | Phase 12 gate, area 4 ([`docs/agent-runs/phase-12/human-gate.md`](agent-runs/phase-12/human-gate.md)) |
| EMR | no cluster in Terraform; the state machine starts one per stage; Hadoop on `emr-7.14.0`, Spark on `emr-spark-8.1.0`; On-Demand only; 2 to 4 core nodes by managed scaling; ends after 15 idle minutes | Phase 12 gate, area 5 |
| Entity resolution | runs on the architect's laptop between the Hadoop and Spark stages | [`docs/agent-runs/phase-13/prompt.md`](agent-runs/phase-13/prompt.md), decision 12, deviation 2; Phase 14 gate ([`docs/agent-runs/phase-14/human-gate.md`](agent-runs/phase-14/human-gate.md)), "Pipeline" |
| Neptune | one `db.t4g.medium` instance, private, encrypted, IAM authentication; engine 1.4.7.1 | Phase 12 gate, area 7 and "Checked by me" |
| DynamoDB | six tables (RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry, ResearcherMetricCache), on demand | Phase 12 gate, area 6; `modules/storage/src/main/scala/org/scholarchain/storage/dynamo/TableSpecs.scala` |
| S3 Vectors | one vector bucket, five indexes (`researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1`) | Phase 12 gate, area 8; `config/course.json:48-59` |
| ECS on Fargate | one image, four services (`query`, `evidence`, `graph`, `worker`) plus an `ops` task definition; ARM64 | Phase 12 gate, area 10 |
| API Gateway | HTTP API with HTTPS in front of an internal load balancer; throttle rate 5 requests/s, burst 10 | Phase 12 gate, area 10; [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md), "Ingress" |
| Step Functions | one state machine that runs the EMR pipeline and always ends the cluster | Phase 12 gate, area 11 |
| SQS | job queue and embedding queue, each with a dead-letter queue | Phase 12 gate, area 11 |
| CloudWatch | log groups, a dashboard, alarms, an alarm topic; AWS's default metrics | Phase 12 gate, area 12 |
| Secrets Manager and encryption | four secret containers (values set by hand in the console, Phase 14); AWS-managed encryption keys | Phase 12 gate, area 3; Phase 14 gate, "Only approved, committed scripts" |
| Model providers | Anthropic API (`claude-haiku-5-5`) and Voyage AI (`voyage-4`, 1024 dimensions) instead of Amazon Bedrock; the Bedrock switch is kept | `config/course.json:15-18`, `:60-81`; [`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md) |

The whole environment was defined in Terraform under `infra/terraform/` (modules `foundation`, `network`, `identity`, `data-lake`, `emr`, `dynamodb`, `neptune`, `vectors`, `runtime`, `workflow`, `observability`) and changed only through signed plans and `scripts/deploy.sh`. Terraform is frozen in Phase 17 (work order decision 2).

## 3. The runtime query workflow

```mermaid
flowchart TD
    Q["POST /v1/query<br/>(query service)"] --> V["Validate request,<br/>derive query ID"]
    V --> P["planner<br/>LLM plan with rule fallback"]
    P --> RS["resolver<br/>ResearcherResolver"]
    RS --> M["metrics<br/>CollaborationMetrics<br/>(DynamoDB)"]
    RS --> PA["paths<br/>PathFinder<br/>(Neptune templates)"]
    RS --> C["community<br/>CommunityAnalyst"]
    RS --> SE["semantic<br/>SemanticContext<br/>(S3 Vectors, Voyage)"]
    M --> EB["evidence<br/>EvidenceBuilder<br/>(bundle, put-if-absent)"]
    PA --> EB
    C --> EB
    SE --> EB
    EB --> ID["identity<br/>IdentityCheck"]
    ID --> SY["synthesis<br/>AnswerSynthesis<br/>(LLM, interpretation only)"]
    SY --> CV["verification<br/>ClaimVerification<br/>(deterministic code)"]
    CV --> AU["audit<br/>QueryAudit v3 record"]
    AU --> OUT["Completed result:<br/>answer, claims with status,<br/>evidence links"]
```

*Caption: the runtime agents, in the order `QueryWorkflow` runs them (`modules/rag/src/main/scala/org/scholarchain/rag/runtime/QueryWorkflow.scala:172-240`, stage names at `:828-840`). They run inside one process (the `query` service, or the `worker` for async jobs), not as separate services.*

Deterministic graph facts (counts, paths, components, communities, bridge measures) come from Spark outputs stored in DynamoDB and Neptune. The model writes interpretation only; every claim is checked by `ClaimVerification`, deterministic code, against the evidence bundle, and only `verified` and `partially_supported` claims stay in the answer ([`docs/api.md`](api.md#9-claim-verification-statuses-and-reasons)).

## 4. Why each technology is used

Each reason below is the argument recorded in a phase gate, work order or report, not a product description.

- **S3 raw and curated zones.** The pipeline stages never call each other in code; they exchange versioned files whose schemas are defined in `core` (`CLAUDE.md`, "Module dependency boundaries"). S3 keys are versioned and files are never overwritten, with content hashes and source references (Phase 7 gate, [`docs/agent-runs/phase-07/human-gate.md`](agent-runs/phase-07/human-gate.md)), so every stage's input is a fixed, addressable run.
- **Hadoop MapReduce on EMR.** The parse, normalize, mention, blocking and pair-candidate work is record-at-a-time and shuffle-shaped. The Phase 4 gate ([`docs/agent-runs/phase-04/human-gate.md`](agent-runs/phase-04/human-gate.md)) records why the jobs are real MapReduce: a DBLP reader that reads every record exactly once across file splits, coauthor pairs correct for any team size, counters where input equals accepted plus excluded plus rejected, and identical output on rerun. Phases 14 and 15 reproduced the same counters on EMR at two build commits ([`experiments/results-summary.md`](../experiments/results-summary.md), section 2.6).
- **Entity resolution on the laptop.** `ErMain` reads and writes local files only, the bounded data is small, and running it locally avoids EMR cost ([`docs/agent-runs/phase-13/prompt.md`](agent-runs/phase-13/prompt.md), decision 12, deviation 2). The laptop run in Phase 14 matched Phase 5's accepted run exactly (Phase 14 gate, "Pipeline").
- **Spark on EMR.** Graph construction and analytics (distinct coauthors, weighted degree, team sizes, components, communities, k-core, bridge measures, path evidence) need joins and iterative algorithms over the whole graph. Spark consumes the Hadoop outputs instead of re-parsing DBLP, never collects the whole graph to the driver, and is checked on hand-checkable known graphs (Phase 6 gate, [`docs/agent-runs/phase-06/human-gate.md`](agent-runs/phase-06/human-gate.md)). `sparkAnalytics` compiles with Scala 2.13 to match the EMR Spark runtime, an instructor-approved deviation (`CLAUDE.md`, "Scala versions").
- **Neptune.** Paths and neighbourhoods are graph traversals. The runtime reaches Neptune only through typed, parameterized query templates with validated integers and window labels, so user text never becomes database code (Phase 9 gate, [`docs/agent-runs/phase-09/human-gate.md`](agent-runs/phase-09/human-gate.md)). One `db.t4g.medium` instance was chosen because it is the free-trial class (Phase 12 gate, area 7).
- **DynamoDB.** Per-window metrics, rankings, evidence bundles, the entity-link cache, the run ledger and audit records, and query jobs are key lookups and conditional writes. Per-window metrics live in DynamoDB, full-window metrics also on Neptune nodes; safe repeat writes rely on conditional puts (Phase 7 gate). Tables are on demand with point-in-time recovery off because the data can be rebuilt (Phase 12 gate, area 6).
- **S3 Vectors.** The durable semantic tier for researcher, community, paper, path and topic summaries. Vector metadata stays small and long text stays in S3; vector similarity never supplies a count, edge or path (Phase 7 gate; `CLAUDE.md`, "Architecture invariants"). In the Phase 15 retrieval-mode experiment, vector-only retrieval scored MRR 0.003 against 0.818 graph-only (exploratory, n = 11; Phase 15 gate, Results), which is consistent with using vectors for context, not for graph facts (interpretation; `docs/evaluation.md` states the limits of that experiment).
- **Anthropic and Voyage instead of Bedrock.** Every Amazon Bedrock quota of the project's account was 0. The instructor approved the Anthropic API for synthesis and Voyage AI for embeddings, with the rest on AWS and the Bedrock switch kept ([`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md)).
- **ECS on Fargate, ARM64.** The four services run from one container image and each scales on its own (section 5); ARM64 matches the image built on the architect's arm64 laptop (Phase 12 gate, "Items earlier gates sent to Phase 12", Phase 2 row; [`docs/agent-runs/phase-13/prompt.md`](agent-runs/phase-13/prompt.md) records the laptop as arm64 macOS).
- **API Gateway in front of an internal load balancer.** HTTPS at the edge, no public load balancer, and a request throttle sized for one person's smoke tests and demo (rate 5/s, burst 10; [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md), "Ingress"). The throttle dominated the Phase 15 read-load experiment above one client (Phase 15 gate, Results).
- **Step Functions.** Runs the EMR pipeline and always ends the cluster, including on failure (Phase 12 gate, area 11 and "Step Functions or SQS only": both).
- **SQS.** Carries the async query jobs and the embedding (re-embed) work, each queue with a dead-letter queue; at-least-once delivery with a job lease and a visibility timeout longer than the lease (Phase 12 gate, area 11; [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md)). Phase 15 experiment C showed the worker scaling 0 → 1 → 2 → 0 on the queue (Phase 15 gate).
- **CloudWatch.** Replaced the planned `/metrics` endpoint: log groups, a dashboard and alarms over AWS's default metrics for EMR, ECS, SQS, Neptune, DynamoDB and the API (Phase 12 gate, area 12 and the Phase 10 `/metrics` row).
- **Secrets Manager and AWS-managed keys.** Secret values never sit in code or configuration; configuration holds only environment-variable names (`config/course.json`). Encryption uses AWS-managed keys, no customer-managed KMS key, for cost (Phase 12 gate, area 3; [`docs/agent-runs/phase-12/prompt.md`](agent-runs/phase-12/prompt.md), decision 6, item 3).
- **Terraform and approved scripts.** Every AWS resource is in Terraform under `infra/terraform/`; apply runs only from a human-signed plan through `scripts/deploy.sh` (`CLAUDE.md`, "Security invariants").

## 5. Consolidation of services

The course specification lists per-stage runtime services (query, graph retrieval, vector retrieval, ranking, answer synthesis, verification, workers). The Phase 12 work order allowed "a justified consolidation that preserves independent scaling where needed" and the built system is:

- **One image, four ECS services**, one per `Main --role`: `query`, `evidence` and `graph` behind the load balancer, and `worker` driven by SQS, plus an `ops` task definition for the loaders (Phase 12 gate, area 10).
- **Vector retrieval, ranking, synthesis and verification run inside `query`**, as stages of one in-process workflow (the Phase 10 service design); the `worker` runs the same workflow for async jobs.
- **Each of the four services scales on its own** (CPU target tracking for `query`, `evidence` and `graph`; queue-driven step scaling from 0 for `worker`; [`docs/agent-runs/phase-12/agent-report-infra-2b.md`](agent-runs/phase-12/agent-report-infra-2b.md), "Autoscaling").
- **No service discovery**: the services never call each other, only the shared stores and queues (Phase 12 gate, area 10).
- Role `all` (everything in one process) is used only for local runs and tests, never on AWS.

Reason recorded in the Phase 12 work order ([`docs/agent-runs/phase-12/prompt.md`](agent-runs/phase-12/prompt.md), decision 6, item 10): the specification's baseline needs at least three independently deployable runtime services, and each scales on its own; query, retrieval, ranking, synthesis and verification running inside the query role's workflow is recorded there as a justified consolidation of the specification's per-stage services.

## 6. What was not built

| Item | Status | Record |
|---|---|---|
| OpenSearch (optional tier) | not built: no hybrid, faceted or hot-tier retrieval was implemented | Phase 12 gate, area 9; Phase 7 gate |
| GitHub Actions | not built: no `.github/workflows/` folder exists | repository tree |
| GitHub MCP server | defined in `.mcp.json` but switched off (no token) | [`docs/agents.md`](agents.md), "MCP servers and trust boundaries" |
| OpenAPI file | not generated; the route reference is [`docs/api.md`](api.md) | `docs/api.md` |
| Amazon Bedrock | not used (quota 0); the Bedrock switch is kept in code | [`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md); Phase 15 gate (`RequiresAws` row: both Bedrock tests fail on quota 0) |
| Enrichment on AWS | the Phase 14 and 15 EMR runs read DBLP only; J02 (source normalization) did not run there | [`docs/data-lineage.md`](data-lineage.md#2-sources) |
| Custom CloudWatch metrics (LLM usage, S3 Vectors calls) | not built | Phase 12 gate, area 12; Phase 16 gate ([`docs/agent-runs/phase-16/human-gate.md`](agent-runs/phase-16/human-gate.md)), "custom metrics" row |
| Glue catalog, EventBridge rules | not built (not used; the EMR steps poll) | Phase 12 gate, areas 4 and 11 |
| EMR Spot capacity | not used (On-Demand only: failure handling untested) | Phase 12 gate, area 5 |
| Customer-managed KMS keys | not used (AWS-managed keys) | Phase 12 gate, area 3 |
| Remote Terraform state | not built; local state with the remote design in `infra/terraform/STATE.md` | Phase 12 gate, area 1 |
| Graph neighbourhoods deeper than 1; `HAS_TOPIC`, `AFFILIATED_WITH`, `PUBLISHED_IN` neighbour queries | not served (422) | [`docs/api.md`](api.md#58-get-v1graphnodesnodeidneighbors); Phase 7 gate (no topic or affiliation data) |
| Venue and institution filters | refused with 422 (no venue metrics or institution data) | [`docs/api.md`](api.md#53-post-v1query) |
| Betweenness centrality | not computed; bridge measures are participation coefficient, cross-community degree and communities connected | Phase 6 gate |
| Terraform environment-parity guard (K-01) | not built (Terraform frozen in Phase 17) | Phase 16 gate; Phase 17 work order decision 2 |
| A live system | removed Nov 21 (instructor-approved teardown) | Phase 15 gate |
