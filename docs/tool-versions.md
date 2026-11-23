# ScholarChain tool versions (deliverable D02)

This record lists the tools and versions used to build, test and run ScholarChain: the operator's machine as recorded at the start of Phase 17, the build ledger, the Terraform pins, the MCP server pins, the model configuration, and the Phase 00 record for history.

**Sources.** The machine versions were printed by the orchestrator on 2026-11-22 at the start of Phase 17 session 2 (start check 6) and saved verbatim in the scratchpad report `tool-versions-p17.txt`, which the human copies to `docs/agent-runs/phase-17/tool-versions-p17.txt` with the session's other reports. Every other value names its committed source file and line. No version below was invented or upgraded by an agent; a version that a record does not name is written as "not recorded".

Contents:

- [1. Operator machine (Phase 17)](#1-operator-machine-phase-17)
- [2. Build ledger](#2-build-ledger)
- [3. Target runtimes](#3-target-runtimes)
- [4. Terraform](#4-terraform)
- [5. MCP servers](#5-mcp-servers)
- [6. Model configuration](#6-model-configuration)
- [7. How the Claude Code sessions ran](#7-how-the-claude-code-sessions-ran)
- [8. Phase 00 record (history)](#8-phase-00-record-history)

## 1. Operator machine (Phase 17)

Recorded 2026-11-22 (`tool-versions-p17.txt`, start check 6).

| Tool | Version as printed |
|---|---|
| macOS (`sw_vers`) | ProductVersion 26.6.2, BuildVersion 25G83 |
| Architecture (`uname -m`) | arm64 |
| Java (`java -version`) | OpenJDK 17.0.21 2026-10-20, Temurin-17.0.21+9 |
| Claude Code (`claude --version`) | 2.1.330 |
| AWS CLI (`aws --version`) | aws-cli/2.40.0 Python/3.15.2 Darwin/25.6.0 source/arm64 |
| Docker (`docker --version`) | 29.9.3, build 5457199 |
| Python (`python3 --version`) | 3.15.2 |
| Git (`git --version`) | 2.50.1 (Apple Git-155) |
| Terraform (`terraform version`) | v1.17.1 on darwin_arm64 (Terraform printed a notice that 1.17.2 is available; not upgraded) |
| ShellCheck (`shellcheck --version`) | 0.11.0 |

Test marker at the start of Phase 17 (`head -n 5 .claude/.last-test-run`, same record): `timestamp=2026-11-22T15:54:10.491673Z`, `module_tests=2306`, `contract_tests=399` (the last passing `sbt check` of Phase 16; Phase 16 gate, "Results", [`docs/agent-runs/phase-16/human-gate.md`](agent-runs/phase-16/human-gate.md)).

Other tools the scripts need (jq 1.6 or later, GNU coreutils `timeout`, curl 7.55 or later, tar, Docker buildx) are listed with their minimum versions in [`scripts/README.md`](../scripts/README.md), "Prerequisites"; their installed versions were not recorded in Phase 17.

## 2. Build ledger

The one dependency ledger is [`project/build.properties`](../project/build.properties) (ratified in Amendment 01; [`docs/agents.md`](agents.md), "Ownership resolution rules", rule 3). Values at commit `50ba087`:

| Item | Value | Source |
|---|---|---|
| sbt | 1.13.1 | `project/build.properties:20` |
| Java | 17 | `project/build.properties:23` |
| Scala 3 | 3.3.9 | `project/build.properties:24` |
| Scala 2.13 (for `sparkAnalytics` and `core2_13`) | 2.13.17 | `project/build.properties:25` |
| Spark (Provided scope) | 4.1.1 | `project/build.properties:28` |
| Hadoop (Provided scope) | 3.4.2 | `project/build.properties:29` |
| uPickle (the one JSON library) | 4.4.3 | `project/build.properties:32` |
| MUnit (the one test framework) | 1.3.6 | `project/build.properties:33` |
| SLF4J | 2.0.19 | `project/build.properties:34` |
| Logback | 1.6.6 | `project/build.properties:35` |
| AWS SDK for Java 2.x | 2.59.1 | `project/build.properties:37` |
| scalafmt (must equal `.scalafmt.conf`) | 3.11.5 | `project/build.properties:40` |
| sbt-scalafmt, sbt-assembly, sbt-projectmatrix | 2.6.2, 2.5.0, 0.11.0 | `project/build.properties:43-45` |
| Container Java runtime (tag and digest) | `eclipse-temurin:17.0.21_9-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077` | `project/build.properties:48` |

The Scala split (Scala 2.13 for the Spark module and the cross-built `core2_13`, Scala 3 everywhere else) is an instructor-approved deviation recorded in `CLAUDE.md` ("Scala versions") and the Phase 2 gate.

## 3. Target runtimes

| Runtime | Version | Source |
|---|---|---|
| Spark jobs | Amazon EMR `emr-spark-8.1.0` (Spark 4.1.1-amzn-0, Scala 2.13.17, Hadoop 3.4.2-amzn-3, Amazon Corretto 17 by default) | `project/build.properties:16-17`; `infra/terraform/environments/course/variables.tf:83` |
| Hadoop MapReduce jobs | Amazon EMR `emr-7.14.0` | `infra/terraform/environments/course/variables.tf:77` |
| Services (ECS Fargate, Linux ARM64) | the container Java runtime above | `project/build.properties:48`; `infra/terraform/modules/runtime/main.tf:161` |
| Neptune engine | 1.4.7.1, instance class `db.t4g.medium` | `infra/terraform/environments/course/variables.tf:161`, `:167` |

Availability of these releases in us-east-1 is checked in [`docs/service-limits.md`](service-limits.md#1-infrastructure-limits-and-region-availability) (IL-01 to IL-04, IL-12 to IL-14).

## 4. Terraform

| Item | Value | Source |
|---|---|---|
| Terraform CLI (required, exact) | `= 1.17.1` | `infra/terraform/environments/course/versions.tf:6` |
| AWS provider `hashicorp/aws` (exact) | `= 6.59.0` | `infra/terraform/environments/course/versions.tf:11`; lock file `infra/terraform/environments/course/.terraform.lock.hcl:5` |
| Backend | the default local backend (no `backend` block) | `infra/terraform/environments/course/versions.tf:4`; [`infra/terraform/STATE.md`](../infra/terraform/STATE.md) |

## 5. MCP servers

Every server is pinned to an exact version or image digest in [`.mcp.json`](../.mcp.json); a pin changes only through a constitutional amendment ([`docs/agents.md`](agents.md), "MCP servers and trust boundaries").

| Server | Pin | Source | Notes |
|---|---|---|---|
| `github` | `ghcr.io/github/github-mcp-server@sha256:f6056fc10bfffcc8c81a6417a2e9ca12808647e8dd6049642a4feaadee0e0f8a` | `.mcp.json:10` | defined but switched off locally (no token set) |
| `aws-api` | `awslabs.aws-api-mcp-server@1.3.33`, run with `uvx --python 3.12 --with 'mcp<2'` | `.mcp.json:20` | read-only (`READ_OPERATIONS_ONLY=true`, `.mcp.json:24`); no agent used it in Phase 17 |
| `terraform` | `hashicorp/terraform-mcp-server@sha256:11dd418bba6003dd5777e9d0979b68de59929a155b355f338f407947e00e9d80` | `.mcp.json:31` | registry documentation only, no workspace token |
| `aws-docs` | `awslabs.aws-documentation-mcp-server@1.1.21`, run with `uvx --python 3.12 --with 'mcp<2'` | `.mcp.json:35` | public AWS documentation; used for the Phase 17 limits checks |

The `uv`/`uvx` version on the operator's machine was not recorded in Phase 17.

## 6. Model configuration

**Claude Code sessions (build time):** every Claude Code session of this project used Opus 5.5 with medium thinking effort. This is the human architect's statement (Phase 17 work order, decision 13, "Model configuration"); no session record in the repository states the model per session.

**ScholarChain runtime models** (what the deployed services called), from `config/course.json`:

| Use | Provider | Model ID | Source |
|---|---|---|---|
| Answer synthesis and query planning | Anthropic API | `claude-haiku-5-5` | `config/course.json:17`, `:72` |
| Embeddings | Voyage AI | `voyage-4`, 1024 dimensions | `config/course.json:16`, `:61`, `:63` |

Anthropic and Voyage instead of Amazon Bedrock is an instructor-approved deviation (Bedrock quota 0 on the account); the Bedrock clients are kept behind a switch ([`docs/agent-runs/instructor-approvals.md`](agent-runs/instructor-approvals.md)). The prompt versions are the files under `prompts/runtime/` (for example `answer-synthesis-v3.md`, `query-planner-v2.md`); the release manifest records the sha256 of every prompt file (Phase 17 step E10).

## 7. How the Claude Code sessions ran

- **Auto mode:** the sessions ran in Claude Code's auto mode in most phases: a classifier approves most actions, while the guard hook and the stop hook still enforce the safety floor (Phase 16 work order, [`docs/agent-runs/phase-16/prompt.md`](agent-runs/phase-16/prompt.md), line 5; Phase 17 work order, line 5: "as in most earlier phases"). The human declined Claude Code's proposed auto-mode setup (Phase 16 work order, line 81).
- The Claude Code version used in Phase 17 is 2.1.330 (section 1). The versions used in earlier phases were not recorded per phase.

## 8. Phase 00 record (history)

[`docs/agent-runs/phase-00/tool-versions.txt`](agent-runs/phase-00/tool-versions.txt), recorded Tue Nov 10, 2026, at the start of the project:

| Tool | Phase 00 (Nov 10) | Phase 17 (Nov 22) |
|---|---|---|
| macOS | 26.6.2 (25G83) | 26.6.2 (25G83) |
| Java | Temurin-17.0.21+9 | Temurin-17.0.21+9 |
| Terraform | v1.17.1 on darwin_arm64 | v1.17.1 on darwin_arm64 |
| AWS CLI | aws-cli/2.40.0 Python/3.15.2 | aws-cli/2.40.0 Python/3.15.2 |
| Docker | 29.9.3, build 5457199 | 29.9.3, build 5457199 |
| Git | 2.50.1 (Apple Git-155) | 2.50.1 (Apple Git-155) |
| GitHub CLI | gh 2.103.3 (2026-10-26) | not recorded |
| Claude Code | not recorded with a label | 2.1.330 |

The Phase 00 file also holds one unlabelled line, `2.0.12` (line 8); the record does not name the tool that printed it, so it is not attributed here.
