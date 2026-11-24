# ScholarChain on the AWS Emulator (Phase 16 bonus track)

Owner: `documentation-engineer`. Status: completed after step E14 (Phase 16 session 3) from the result files of run `20261122T1845Z-ecc1305-7d2e`, committed together with this document. Touched up after review (findings P16-L-25 to P16-L-30). The human architect ran every command in this file. No agent ran the emulator. This file is the basis for the course Teams post. It stays at service level: which AWS services and operations were exercised, what happened, the safety contract, and the limits. It has no source code, no prompts and no design detail.

Results folder (committed together with this document): [`experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/`](../experiments/emulator/raw/20261122T1845Z-ecc1305-7d2e/). Every count below is computed from the files in that folder. Facts about the session itself (image, package version, start time, terminal set-up) come from the orchestrator's record of the human's run and are labelled "as recorded".

## 1. What the AWS Emulator is

The AWS Emulator is AWS's local emulator of the AWS APIs ([what is the AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html)). It answers AWS API calls on one local HTTPS endpoint, for one account and one Region, and keeps all state in memory. An operation it does not implement answers HTTP 501 with the error code `NotImplemented`. The documentation says fidelity is closest for control-plane APIs and varies by service for data-plane APIs ([fidelity](https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html)).

ScholarChain's real AWS environment was removed on Nov 21, at the end of Phase 15. In Phase 16, the emulator is the only place where ScholarChain's AWS-facing code can talk to an AWS API. It costs nothing and needs no AWS account.

## 2. Installation and configuration

Installation follows the AWS documentation ([getting started with Docker](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html)). The run book is the "AWS Emulator" section of [`scripts/README.md`](../scripts/README.md): one zsh line per step, run from the repository root, in two terminals. The emulator terminal runs only the emulator. The second terminal is the `[EMULATOR]` terminal, which runs everything else.

### 2.1 The image

ScholarChain's copy of the documented Dockerfile is [`docker/emulator/Dockerfile`](../docker/emulator/Dockerfile). It follows the documented form:
- it starts from the Amazon Linux 2023 minimal image;
- it installs the emulator's `LTS/latest` RPM package for Arm (`aarch64`), because the host is Apple silicon;
- it runs as the documented non-root user;
- it sets `EMULATOR_BIND_ADDRESS=0.0.0.0`, so Docker can forward the emulator's port from inside the container.

Review finding P16-B-01 found that the first draft departed from this form. The file was repaired to the documented form in session 2 and checked statically against the documentation. The E14 run below is the first runtime use of the repaired file.

As recorded for the E14 run:
- The human built the image locally as `scholarchain-emulator`. It was never pushed and is not part of the application build. Image ID: `sha256:39e57593567795809ded746bfd4e81c467fe66e7de72f78d87effa2313a5c57e`.
- During the build, Docker printed the lint warning `FromPlatformFlagConstDisallowed` for the line `FROM --platform=linux/arm64`. The build still produced the image.
- Emulator package: `aws-emulator-1.0.569-1.aarch64`, read with `rpm -q`. The emulator itself prints no version line at start.

### 2.2 Starting it

- The emulator runs in its own terminal with a 2 GB memory limit. On the host, its port 9297 is published on `127.0.0.1` only. The documentation warns that publishing a port without an address opens the emulator to the whole network. Inside the container, the emulator listens on every address because of the documented `EMULATOR_BIND_ADDRESS=0.0.0.0` setting. This is an accepted deviation: only the host side is limited to loopback.
- The human shared the folder `~/.aws/emulator` with the container. At start, the emulator writes four files into `~/.aws/emulator/9297/`: a credentials file, a config file, a CA certificate and a Java trust store.
- It starts with `--telemetry off`, so it sends no usage data ([telemetry](https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html)).
- Readiness is checked with the documentation's bounded health wait: up to 30 tries against the health route, using the emulator's CA certificate ([pointing your SDK at the emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html)).
- Ctrl+C stops it. The emulator then deletes its files and every emulated resource, because its state lives only in memory ([command reference](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html)).

As recorded: started 2026-11-22T18:37:58Z, telemetry off, endpoint `https://127.0.0.1:9297`.

### 2.3 Pointing ScholarChain at it

- The `[EMULATOR]` terminal sources `scripts/emulator/env.sh`. That script sets the variables the documentation lists: the loopback endpoint, the emulator's credentials and config files, its CA bundle, and Region `us-east-1`. It also clears every variable that could select real AWS credentials, and marks the prompt with `[EMULATOR] `.
- ScholarChain uses a separate emulator configuration, [`config/emulator.json`](../config/emulator.json). Every resource name in it starts with `scholarchain-emu-`, and it has no graph-store (Neptune) section.
- `scripts/emulator/setup.sh` creates the resources that configuration names: one data bucket, six DynamoDB tables, one vector bucket with five indexes, and two work queues, each with a dead-letter queue and a maximum receive count of 5. It also creates one extra bucket with a deny-all bucket policy for the policy probe.

As recorded: the human's shell still exported Phase 15 `SCHOLARCHAIN_CFG_*` configuration overrides. The human cleared them in the `[EMULATOR]` terminal with `unset -m 'SCHOLARCHAIN_CFG_*'`. The Phase 16 `env.sh` used in this run did not clear these variables, a known gap of this run (section 8). Since Phase 17 (commit pending), `env.sh` clears every exported `SCHOLARCHAIN_CFG_*` variable itself (`runtime: untested`); this change was made after Phase 16's emulator run (Phase 16 step E14), which was not repeated.

**Why `env.sh` sets no strict mode** (autograder check U01; Phase 17 step E12, documentation of existing code, no code change). `scripts/emulator/env.sh` is a file the operator sources into an interactive terminal; it is not a script that runs on its own.

- **Only the operator's terminal sources it.** Every use in the repository is `source scripts/emulator/env.sh` typed by the operator (`env.sh:4-6`). No script, test or experiment executes it or sources it (read-only check by code-reviewer at commit `d8b49fa`).
- **No strict mode, by design.** Its header says "It changes no shell option" (`env.sh:13`). `set -e` sourced into an interactive shell would close the operator's terminal at the first failing command.
- **Running it under bash is refused.** If it is run with bash instead of sourced, it prints an error and exits with code 2 (`env.sh:60-63`).
- **It has no executable bit.** `git ls-files -s scripts/emulator/env.sh` shows mode 100644.
- **The scripts run after it set strict mode themselves.** `setup.sh` and `probes.sh` run as their own bash processes and set `set -Eeuo pipefail` (`scripts/emulator/setup.sh:10`, `scripts/emulator/probes.sh:13`).

### 2.4 Commands

After the emulator is healthy and `env.sh` is sourced, a run is these lines, one after another:

```text
bash scripts/emulator/setup.sh
sbt storage/EmulatorTest/test
sbt services/EmulatorTest/test
bash scripts/emulator/probes.sh
```

The emulator tests carry their own tag. The normal build and the live-AWS test configuration both exclude them, so they never run by accident.

## 3. The safety contract

The real AWS environment is gone, but real AWS credentials can still exist on the laptop. The emulator work must never send a request to real AWS by mistake. So every emulator test and script checks these rules **before it makes any call**. If one rule fails, the run stops with nothing sent and records which rule failed, by name only.

- The endpoint must be exactly `https://127.0.0.1:9297`. `localhost` is refused, because with it the Java SDK puts the S3 bucket name into the host name ([pointing your SDK at the emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html)). Any other host or port is refused too.
- Credentials and config come only from the emulator's own files under `~/.aws/emulator/9297/`.
- Every variable that could select other credentials must be unset: a profile name, access keys, a session token, a role, a web-identity token, or container credentials.
- The scripts also check that the health route answers and that the caller is the emulator's single account. They compare the account number but never print it or write it to a file. They print only `emulator account confirmed: yes|no`.
- Every call names the loopback endpoint explicitly. The normal production set-up is unchanged.

Unit tests in the normal build cover each refusal, with fake environments and no network.

## 4. What was exercised

Only ScholarChain's own code ran against the emulator, through its storage layer and its embedding worker. In addition, the set-up and probe scripts made AWS CLI calls. Step Functions was attempted by the probe script but not exercised (input missing; section 5.3).

| Part | What it does | Services |
|---|---|---|
| Set-up script | creates the bucket, tables, vector bucket and indexes, and queues named in the emulator configuration | S3, DynamoDB, S3 Vectors, SQS |
| Storage suite (8 tests) | conditional S3 writes (create, already present, conflict) and a listing; conditional DynamoDB writes and reads; an S3 Vectors write, read and similarity query; reads of the configured resources; an SQS send, receive and delete; the SQS dead-letter path; the S3 deny-all policy probe; the re-embed demonstration | S3, DynamoDB, S3 Vectors, SQS |
| Services suite (1 test) | the worker dead-letter demonstration: ScholarChain's embedding worker receives a message it can never process, releases it at once on each receive, and the queue moves it to its dead-letter queue after the maximum receive count. The embedding model must never be called. | SQS |
| Probe script | reads the vector bucket and lists its indexes; the M-01 sequence (a vector read before and after a vector-bucket policy that denies the caller); Step Functions definition validation and single-state tests (attempted; not exercised (input missing)) | S3 Vectors; Step Functions (attempted; not exercised) |

The re-embed demonstration uses ScholarChain's vector re-embed path with a stub embedder, so no model is called. The path stores a vector with its metadata and its text, re-embeds it so that only the vector data changes, and writes a completion record. A second delivery of the same task must then be reported as already done (`replayed`), with no second embedding call.

Every stateful test tags what it creates with the run ID and checks only its own items.

## 5. Results

### 5.1 Run identity (as recorded)

| Item | Value |
|---|---|
| Run ID | `20261122T1845Z-ecc1305-7d2e` |
| Repository commit (HEAD of the run) | `ecc13052b09e0b0396f47894e8858c6029632886` |
| Image ID | `sha256:39e57593567795809ded746bfd4e81c467fe66e7de72f78d87effa2313a5c57e` |
| Emulator package | `aws-emulator-1.0.569-1.aarch64` (from `rpm -q`) |
| Emulator started | 2026-11-22T18:37:58Z, telemetry off |
| Set-up | 20 create operations: 18 resources (2 buckets, 6 tables, 1 vector bucket, 5 indexes, 4 queues), 1 bucket policy, 1 TTL setting; 0 failed; `emulator account confirmed: yes` |
| Suites | storage suite: 8 tests passed; services suite: 1 test passed |

This is the first E14 run, so it replaces no earlier result.

### 5.2 How outcomes are classed

Each operation call is recorded as exactly one outcome class: `implemented-passed` (the emulator answered and the check passed), `implemented-failed` (it answered, but wrongly), `not-implemented` (HTTP 501, a recorded compatibility gap, never counted as working), `not-exercised` (not run because something it needs was missing), or `infrastructure-failure` (no answer, a TLS failure, or a refusal by the safety contract). A suite's exit code shows only that the suite ran. Coverage is read from the result lines.

### 5.3 Coverage by service and outcome class

The table counts operation calls (one result line per call). The brackets give the number of distinct operations. Computed from `setup.jsonl` (36 lines), `results-EmulatorAdaptersEmulatorSuite.jsonl` (54 lines), `results-EmbeddingWorkerEmulatorSuite.jsonl` (18 lines) and `probes.jsonl` (14 lines) in the results folder: 122 lines in total.

| Service | implemented-passed | implemented-failed | not-implemented | not-exercised | infrastructure-failure |
|---|---|---|---|---|---|
| S3 | 14 (7) | 0 | 0 | 0 | 0 |
| DynamoDB | 17 (6) | 0 | 0 | 0 | 0 |
| S3 Vectors | 34 (9) | 0 | 0 | 0 | 0 |
| SQS | 52 (7) | 0 | 0 | 0 | 0 |
| Step Functions | 0 | 0 | 0 | 5 (2) | 0 |
| STS (caller identity check) | no result line | – | – | – | – |
| **Total** | **117** | **0** | **0** | **5** | **0** |

Operations per service, as named in the result files:
- **S3:** HeadBucket, CreateBucket, PutBucketPolicy, PutObject, GetObject, HeadObject, ListObjectsV2.
- **DynamoDB:** CreateTable, DescribeTable, UpdateTimeToLive, PutItem, GetItem, Query.
- **S3 Vectors:** CreateVectorBucket, GetVectorBucket, PutVectorBucketPolicy, CreateIndex, GetIndex, ListIndexes, PutVectors, GetVectors, QueryVectors.
- **SQS:** GetQueueUrl, CreateQueue, GetQueueAttributes, SendMessageBatch, ReceiveMessage, ChangeMessageVisibility, DeleteMessage.
- **Step Functions (not exercised):** ValidateStateMachineDefinition (1 line), TestState (4 lines).

Every operation that was called got an answer, and every check that was asserted passed. 3 of the 117 `implemented-passed` lines are recorded refusals, not successful requests: the 2 S3 policy-probe lines (PutObject and GetObject, HTTP 403 `AccessDenied`; recorded, not asserted) and 1 S3 Vectors line (GetVectors, `AccessDeniedException` after the deny policy); see section 5.5. No operation came back `not-implemented`. The five Step Functions lines are `not-exercised` with the observation `input missing`: those probes need a saved Phase 15 deployment plan, and that plan is no longer on the laptop. The caller-identity check has no line in the result files. Its outcome is the set-up script's printed `emulator account confirmed: yes` (as recorded).

Each result line also records a latency. These are single local calls on a laptop, not a performance measurement, so this file draws no conclusion from them.

### 5.4 Demonstrations

| Demonstration | Line read from the results | File |
|---|---|---|
| re-embed | `demonstration re-embed: demonstrated` | `demonstration-EmulatorAdaptersEmulatorSuite.txt` |
| dead-letter | `demonstration dead-letter: demonstrated` | `demonstration-EmbeddingWorkerEmulatorSuite.txt` |

`demonstrated` means that every operation the path needed was `implemented-passed` and the path's final check passed. For re-embed, the final check is that the second delivery was reported `replayed` with exactly one embedding call. In both suites, the dead-letter path's last result line records the observation `dead letter after 5 receives`.

### 5.5 Policy probes

| Probe | Observed |
|---|---|
| S3 bucket with a deny-all bucket policy | PutObject and GetObject were both refused: HTTP 403, `AccessDenied` (`results-EmulatorAdaptersEmulatorSuite.jsonl`, step `s3-policy-probe`) |
| M-01 sequence (S3 Vectors) | before the policy, the vector read succeeded (`baseline readable`). After the deny vector-bucket policy was put, GetVectors was refused with `AccessDeniedException` (`probes.jsonl`, probes `c-get-vectors-baseline` and `c-get-vectors-after-deny`) |

Both refusals, the S3 bucket-policy refusal and the S3 Vectors bucket-policy refusal, go against the emulator documentation's statement that the emulator does not evaluate resource policies ([security](https://docs.aws.amazon.com/emulator/latest/userguide/security.html)). They are reported as observed. These two probes say nothing about policy evaluation in general, and an outcome that matches real AWS is not evidence that the emulator evaluates policies the way AWS does.

## 6. Comparison with Phase 15's live AWS results

Real AWS and the emulator never ran at the same time or on the same code. Real AWS ran Phase 15 code; the emulator ran HEAD `ecc1305`. Each row sets the emulator outcome next to the committed Phase 15 record.

| Phase 15 live record | Source | Emulator counterpart | Comparison |
|---|---|---|---|
| D17, live-AWS adapter suite: 6 tests; 2 passed (S3, DynamoDB); 3 failed (S3 Vectors write refused by the vector-bucket policy, M-01; Neptune and an environment check failed from outside the VPC, as expected); 1 skipped (SQS, variable unset) | `docs/agent-runs/phase-15/runbook-log.md`, row "D17 AwsAdaptersAwsSuite" | storage suite, 8 tests, all passed; S3, DynamoDB, S3 Vectors and SQS calls all `implemented-passed`; no Neptune | Not the same test set. S3 and DynamoDB passed on both. S3 Vectors and SQS were exercised on the emulator but not passed live (S3 Vectors refused by policy, SQS skipped). Neptune: live only, and it failed there. |
| Experiment F: a poison message reached the embedding dead-letter queue after 5 receives under the release-at-once rule; a duplicate task was reported `replayed` | [`experiments/results-summary.md`](../experiments/results-summary.md), section 2.1 | worker dead-letter demonstration; storage dead-letter test; re-embed `replayed` | The same behavior was observed: `dead letter after 5 receives` in both suites, and the re-embed second delivery `replayed`. On the emulator there was no ECS worker, no scaling, no alarm and no CloudWatch metric. |
| D4a: Step Functions definition validation `OK` and single-state tests that matched expectations: four inputs on the D3 plan (absent, A, B, invalid), which the four TestState probes mirror, and five on the L-01 plan | `docs/agent-runs/phase-15/runbook-log.md`, rows "D3 address list + K-01 + D4a" and "D4a (L-01 plan, human read-only)" | Step Functions probes | **Not possible.** All five probe lines are `not-exercised (input missing)`, because the saved Phase 15 plan is no longer present locally. |
| M-01: real AWS refused S3 Vectors data access under the deny vector-bucket policy (the D17 vector write got `AccessDenied`) | `runbook-log.md`, row "D17 AwsAdaptersAwsSuite" | M-01 probe sequence | **Same outcome:** the emulator refused GetVectors with `AccessDeniedException` after the deny policy. The live refusal in D17 was on a vector write; the emulator probe's was on a vector read. Both emulator refusals (S3 bucket policy and S3 Vectors bucket policy) run against the documentation's statement that resource policies are not evaluated (section 5.5), so "same outcome" is not evidence that the emulator evaluates policies like AWS. |

## 7. What was not exercised

The emulator run **does not show the full ScholarChain query working**. Specifically:
- **Neptune (the graph store): not exercised.** The emulator configuration has no Neptune section, and no test or probe calls it.
- **The query pipeline: not exercised.** Planning, retrieval, evidence building, answer synthesis and claim verification did not run against the emulator, because they depend on the graph store.
- **The language model and the embedding provider: not exercised.** No model was called. The re-embed demonstration used a stub embedder, and the worker demonstration used an embedder that fails the test if it is ever called.
- **Step Functions: not exercised** (section 5.3).
- **The Hadoop and Spark jobs (EMR), ECS, autoscaling, alarms and IAM roles: not exercised.**
- **Load: not tested.**

## 8. Limitations

Limitations of the emulator (from its documentation, plus this run):
- State is in memory only and is lost when it stops ([command reference](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html)).
- One account and one Region. It is not built or tested for load ([what is the AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html)).
- Data-plane fidelity varies by service ([fidelity](https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html)). A `passed` here means the emulator gave the answer the test expected. It does not prove that real AWS behaves the same.
- The documentation says the emulator does not evaluate IAM or resource policies the way AWS does ([security](https://docs.aws.amazon.com/emulator/latest/userguide/security.html)). Yet both policy probes in this run were refused (section 5.5). Both statements are reported, and neither is generalized.
- The package is `LTS/latest`, so a later rebuild can install a different emulator version. This run used `aws-emulator-1.0.569-1.aarch64`, and the emulator prints no version line of its own.

Limitations of this work:
- No Neptune, no query pipeline and no language model on the emulator (section 7). The emulator results say nothing about answer quality, retrieval or graph analytics.
- A side-by-side live comparison is not possible now, because the AWS environment was removed on Nov 21. Section 6 compares against Phase 15's committed records, which were made with Phase 15 code.
- The Step Functions comparison (D4a) could not be made, because its input is gone.
- The Phase 16 `scripts/emulator/env.sh` used in this run did not clear `SCHOLARCHAIN_CFG_*` overrides left in the shell; the human cleared them by hand in this run. Since Phase 17 (commit pending), `env.sh` clears every exported `SCHOLARCHAIN_CFG_*` variable itself (`runtime: untested`); this change was made after Phase 16's emulator run (Phase 16 step E14), which was not repeated.
- One run, on one laptop. The emulator is not a live validation of production behavior. Phase 16 code fixes remain "fixed in code, tested offline, not validated live".

## 9. Status lines

- `docker/emulator/Dockerfile`: repaired to the documented form (static review against the documentation); runtime: image built and run in E14 (run `20261122T1845Z-ecc1305-7d2e`), with Docker lint warning `FromPlatformFlagConstDisallowed` (as recorded).
- `scripts/emulator/setup.sh`: ran in E14; 20 create operations: 18 resources (2 buckets, 6 tables, 1 vector bucket, 5 indexes, 4 queues), 1 bucket policy, 1 TTL setting; 0 failed (as recorded).
- `sbt storage/EmulatorTest/test`: ran in E14; 8 tests passed (as recorded); results in `results-EmulatorAdaptersEmulatorSuite.jsonl`.
- `sbt services/EmulatorTest/test`: ran in E14; 1 test passed (as recorded); results in `results-EmbeddingWorkerEmulatorSuite.jsonl`.
- `scripts/emulator/probes.sh`: ran in E14; results in `probes.jsonl` (9 passed, 5 not exercised).
- `scripts/emulator/env.sh`: the Phase 16 version was used in E14 and did not clear `SCHOLARCHAIN_CFG_*` (limitation of that run). Since Phase 17 (commit pending), `env.sh` clears every exported `SCHOLARCHAIN_CFG_*` variable itself: `runtime: untested`, a change made after Phase 16's emulator run (Phase 16 step E14), which was not repeated.
