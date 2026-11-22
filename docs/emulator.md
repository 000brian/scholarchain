# ScholarChain on the AWS Emulator (Phase 16 bonus track)

Owner: `documentation-engineer`. **Status: DRAFT (Phase 16 session 2, step W1). Results `PENDING E14`.** Nothing in this file was run by an agent. The human runs the emulator in session 3, step E14, and this file is completed from the committed result files after that run. This file is the basis for the course Teams post, so it holds no source code, no prompt and no detailed design. It describes what was set up, what was exercised, what was not, and how results will be read.

## 1. What the AWS Emulator is

The AWS Emulator is AWS's local emulator of the AWS APIs ([what is the AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html)). It answers AWS API calls on one local HTTPS endpoint, with one account and one Region, and keeps all state in memory. Where it does not implement an operation yet, it answers HTTP 501 with the error code `NotImplemented`. The documentation says fidelity is closest for control-plane APIs and varies by service for data-plane APIs ([fidelity](https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html)).

ScholarChain's real AWS environment was removed on Nov 21 at the end of Phase 15. The emulator is therefore the only place where ScholarChain's AWS-facing code can talk to an AWS API in Phase 16, at no cost and with no AWS account.

## 2. Installation and configuration

Installation follows the AWS documentation ([getting started with Docker](https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html)). The run book is the "AWS Emulator" section of [`scripts/README.md`](../scripts/README.md): one zsh line per step, from the repository root.

### 2.1 The image

The documented Dockerfile has these parts:
- it starts from the Amazon Linux 2023 minimal image;
- it installs the emulator's `LTS/latest` RPM package from the documented download path. The Arm package (`aarch64`) is used, because the host is Apple silicon;
- it creates the home folder and runs as the documented non-root user (UID 1000) with `HOME` set;
- it sets `EMULATOR_BIND_ADDRESS=0.0.0.0`, so the emulator listens on every address inside the container and Docker can forward its port;
- it starts the `aws-emulator` program.

ScholarChain's copy is `docker/emulator/Dockerfile`. It is built locally as `scholarchain-emulator`, never pushed, and is not part of the application build. Session 2's review found that the first draft departed from the documented form (finding P16-B-01). It is being repaired to the documented form above. The installation described here is the documented one; the file's exact contents are not reproduced. Because the package is `LTS/latest`, the version the emulator prints at start and the image ID are recorded at E14.

### 2.2 Starting it

- The emulator runs in its own terminal with a 2 GB memory limit. Its port, 9297, is published on `127.0.0.1` only. The documentation warns that leaving out the address opens the emulator to the whole network.
- The folder `~/.aws/emulator` is shared with the container. At start, the emulator writes four files into `~/.aws/emulator/9297/`: a credentials file, a config file, a CA certificate and a Java trust store.
- It is started with `--telemetry off`, so it sends no usage data ([telemetry](https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html)).
- Readiness is checked with the documentation's bounded health wait: up to 30 tries against the health route, using the emulator's CA certificate ([pointing your SDK at the emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html)).
- Ctrl+C stops it. The emulator then deletes its files and every emulated resource, because its state lives only in memory ([command reference](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html)).

### 2.3 Pointing ScholarChain at it

- A second terminal sources `scripts/emulator/env.sh`. That file sets the variables the documentation lists: the endpoint `https://127.0.0.1:9297`, the emulator's credentials and config files, the CA bundle, Region `us-east-1`, instance metadata off, and host-prefix injection off. It also unsets every variable that could select real AWS credentials, and prefixes the prompt with `[EMULATOR] `.
- ScholarChain reads a dedicated configuration, [`config/emulator.json`](../config/emulator.json). Every resource name in it starts with `scholarchain-emu-`, and it has no Neptune section.
- `scripts/emulator/setup.sh` creates the resources that configuration names: one data bucket, six DynamoDB tables, one vector bucket with five indexes, and two work queues, each with a dead-letter queue and a maximum receive count of 5. It also creates one bucket with a deny-all bucket policy, used by the policy probe ([`scripts/emulator/OPERATIONS.md`](../scripts/emulator/OPERATIONS.md), resource-name table).
- The Java test runs get the emulator's trust store only inside their own forked test JVM. `JAVA_TOOL_OPTIONS` is never set, because the documentation warns that a trust store set that way reaches every JVM started from the shell.

### 2.4 Commands (from `scripts/README.md`)

Two suites, one after the other, then the probes:

```text
bash scripts/emulator/setup.sh
sbt storage/EmulatorTest/test
sbt services/EmulatorTest/test
bash scripts/emulator/probes.sh
```

The emulator tests carry their own tag. The normal build (`sbt check`) and the live-AWS test configuration both exclude them, so they never run by accident.

## 3. The safety contract, and why

The real AWS environment is gone, but real AWS credentials can still exist on the laptop. The emulator work must never send a request to real AWS by mistake. Every emulator client, test and script checks one contract **before any client is built or any call is made**. If any rule fails, the run stops with nothing sent, and records which rule failed (by name only):

- **The endpoint is exactly `https://127.0.0.1:9297`.** `localhost` is refused, because with it the Java SDK puts the S3 bucket into the host name ([pointing your SDK at the emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html)). Any other host or port is refused too. `AWS_ENDPOINT_URL` must equal it.
- **Credentials and config come only from the emulator's own files** under `$HOME/.aws/emulator/9297/`, and the credentials file must exist.
- **Every variable that could select other credentials is unset.** That means a profile name, access keys, a session token, a role, a web-identity token, or container credentials. A variable set to an empty value counts as set.
- **Paths handed to the forked test JVMs are absolute.** The tests run from the module's own folder.
- **The scripts also check** that the health route answers, and that the caller identity is the emulator's single account. The account number is compared, never printed or written to a file; the scripts print only `emulator account confirmed: yes|no`.
- **Every client gets an explicit endpoint.** Each emulator client is built with the validated loopback endpoint, host-prefix injection off and path-style S3 addressing. Its keys are read from the emulator's credentials file and nothing else. The production client setup is unchanged, and an offline test compares the two.
- **Every CLI call names the endpoint explicitly.** Each call in the scripts passes `--endpoint-url`, with short connect and read timeouts.

Unit tests in the normal build cover each refusal with fake environments and no network. Those tests ran in session 2; the emulator runs themselves have not (status lines below).

## 4. What is exercised

Only ScholarChain's own code runs against the emulator, through its storage adapters and the embedding worker. No test calls the emulator through a hand-written HTTP client.

| Area | What runs | Services touched |
|---|---|---|
| Storage adapter suite (storage) | the bounded S3, DynamoDB, S3 Vectors and SQS tests of the live-AWS adapter suite, re-pointed at the emulator: at most 2 objects, 2 items and 2 vectors per test, 60 s per test | S3, DynamoDB, S3 Vectors, SQS |
| SQS dead-letter test (storage) | one message is received and released at once through the adapter's own release call until the queue's maximum receive count, and is then looked for on the dead-letter queue | SQS |
| Resource-policy probe (storage) | a read and a write on the bucket with the deny-all policy. The outcome (allowed or refused) is **recorded, not asserted**: the documentation says the emulator does not check S3 bucket policies ([security](https://docs.aws.amazon.com/emulator/latest/userguide/security.html)) | S3 |
| Re-embed demonstration (storage, application code) | ScholarChain's vector re-embed path, with a stub embedder (no model call): one vector with its metadata and text object; a re-embed that changes only the vector data; the completion record written; a second delivery of the same task reported `replayed`, with no embed call | S3, S3 Vectors, DynamoDB |
| Worker dead-letter demonstration (services, application code) | ScholarChain's real embedding-worker loop against the emulator's embedding queue and its dead-letter queue. One poison message is never acknowledged, is released at once on each receive, and reaches the dead-letter queue after the maximum receive count. This replays the path of Phase 15 experiment F. The embedder used fails the test if it is ever called. The suite was still being written when this draft was made; its exact steps and bounds are in service-engineer's step 7 report | SQS |
| Step Functions probes (scripts) | definition validation and a single-state test of the input-resolving state from a saved Phase 15 plan, with the same four inputs Phase 15 used on real AWS (step D4a). If the saved plan is not present locally, these are recorded `not-exercised (input missing)` | Step Functions |
| S3 Vectors probes (scripts) | reading the vector bucket and listing its indexes | S3 Vectors |
| Vector-bucket policy probe, M-01 (scripts) | a vector-bucket policy that denies the caller, then a read, recording whether the emulator refuses. In Phase 15, real AWS refused the equivalent read (finding M-01) | S3 Vectors |

Before the run, infra-engineer classified every operation these tests call against the emulator documentation ([`scripts/emulator/OPERATIONS.md`](../scripts/emulator/OPERATIONS.md)). Three are `documented supported`: caller identity, bucket creation and table creation. Twenty-nine are `unknown`, because the published per-operation fidelity scores are outside the documentation site and were not read in this phase. The two policy behaviors are `documented unsupported`. Every unknown operation is treated as a probe: its outcome is recorded, never assumed.

State isolation: every stateful test tags each key, message, task and record it creates with a run ID, and asserts only on items that carry that ID. Every re-run starts from a restarted emulator, a new setup and a new run ID.

## 5. What is not exercised

The emulator demonstration **does not show the full ScholarChain query working**. Specifically:
- **Neptune (the graph store): not exercised.** The emulator documentation says nothing about the Neptune data API specifically. It also says resources with their own endpoint, such as databases, listen on a separate port, which conflicts with the one-endpoint safety contract ([`OPERATIONS.md`](../scripts/emulator/OPERATIONS.md), Neptune row). There is no Neptune section in the emulator configuration.
- **The query pipeline: not exercised.** Planning, retrieval, evidence building, answer synthesis and claim verification do not run against the emulator. They depend on the graph store.
- **The language model and the embedding provider: not exercised.** No model is called. The re-embed demonstration uses a stub embedder, and the worker demonstration uses an embedder that must never be called.
- **The Hadoop and Spark jobs (EMR): not exercised.**
- **IAM, identity policies and resource policies: not evaluated** by the emulator (section 8). No test creates or relies on roles or policies.
- **Load and scaling: not tested.** The emulator is not built for load testing, and no ECS, autoscaling or alarm behavior exists on it.

## 6. How results are recorded

Every operation a test or probe calls is recorded as exactly one outcome class:

| Class | Meaning | Counts as working functionality? |
|---|---|---|
| `implemented-passed` | the emulator answered and the test's check passed | yes |
| `implemented-failed` | the emulator answered, but the answer was wrong for the test | no (the test fails) |
| `not-implemented` | HTTP 501 `NotImplemented` | **no**: a recorded compatibility gap |
| `not-exercised` | skipped, because an earlier operation it needs did not succeed | no |
| `infrastructure-failure` | no answer, a TLS failure, or a refusal by the safety contract | no (the test fails) |

Each suite and script writes one JSON line per operation into its own file in the run's results folder (`experiments/emulator/raw/RUN_ID/`). A line carries the service, the operation, the class, the HTTP status and the latency, and never a host, account, ARN, queue URL or key. A suite's exit code says only that it ran; coverage is read from the result lines. A `not-implemented` operation lets a test pass as a recorded gap, never as validated functionality.

The two demonstrations each end with one line:
- `demonstration re-embed: demonstrated` or `demonstration re-embed: not demonstrated (REASON)`;
- `demonstration dead-letter: demonstrated` or `demonstration dead-letter: not demonstrated (REASON)`.

`demonstrated` means every operation the path needs was `implemented-passed` and its final check passed. A `not demonstrated` caused by an operation the emulator does not implement is recorded as such. It is not a ScholarChain defect, but it is not a demonstration either.

## 7. Results

### 7.1 Run identity

PENDING E14: emulator version printed at start; image ID; run ID; repository commit used; which earlier results a re-run supersedes.

### 7.2 Coverage by outcome class

PENDING E14. Filled from the committed result files only.

| Service | implemented-passed | implemented-failed | not-implemented | not-exercised | infrastructure-failure |
|---|---|---|---|---|---|
| S3 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |
| DynamoDB | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |
| S3 Vectors | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |
| SQS | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |
| Step Functions | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |
| STS (safety check) | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 | PENDING E14 |

### 7.3 Demonstrations

| Demonstration | Line read from the results | Notes |
|---|---|---|
| re-embed | PENDING E14 | PENDING E14 |
| dead-letter | PENDING E14 | PENDING E14 |

### 7.4 Policy probes

PENDING E14: the outcome (allowed or refused) of the S3 deny-all bucket probe and the M-01 vector-bucket probe.

### 7.5 Comparison with Phase 15's live AWS results

The comparison is planned item by item. Each emulator outcome is set next to the committed Phase 15 record, and differences are listed, not explained away. Real AWS and the emulator were never run at the same time or with the same code.

| Phase 15 live record | Source | Emulator counterpart | Result |
|---|---|---|---|
| D17: the live-AWS adapter suite run against the real environment | `docs/agent-runs/phase-15/runbook-log.md`, row "D17 AwsAdaptersAwsSuite" | storage adapter suite (S3, DynamoDB, S3 Vectors, SQS; Neptune left out) | PENDING E14 |
| Experiment F: poison message to the dead-letter queue, release-at-once rule, sequential replay | [`experiments/results-summary.md`](../experiments/results-summary.md) section 2.1 | worker dead-letter demonstration; storage SQS dead-letter test; re-embed `replayed` | PENDING E14 |
| D4a: Step Functions definition validation and single-state tests on real AWS | `docs/agent-runs/phase-15/runbook-log.md`, row "D3 address list + K-01 + D4a" | Step Functions probes, same four inputs | PENDING E14 |
| M-01: real AWS refused a vector read under the vector-bucket policy | Phase 15 finding M-01; `runbook-log.md` row "D17" | M-01 probe | PENDING E14 |

## 8. Limitations

Limitations of the emulator, from its documentation:
- It does not evaluate IAM policies or resource-based policies the way AWS does. It never denies a request signed with its own key, and it does not check KMS key policies or S3 bucket policies ([what is the AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html), "Limitations"; [security](https://docs.aws.amazon.com/emulator/latest/userguide/security.html)). An "allowed" policy probe is therefore expected emulator behavior, and a "refused" one is not evidence that it evaluates policies in general.
- State is in memory only and is lost when it stops. There is no reset route ([command reference](https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html)).
- Data-plane fidelity varies by service ([fidelity](https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html)). Behaviors such as conditional writes, receive counts and redrive are observed, not assumed.
- It has one account and one Region, and is not tested for load ([what is the AWS Emulator](https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html)).
- S3 Control requests do not reach it ([pointing your SDK at the emulator](https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html)).
- The package is `LTS/latest`, so a later rebuild can produce a different emulator version. The version and image ID are recorded at E14.

Limitations of this work:
- Neptune, the query pipeline and the language model are not run on the emulator (section 5). The emulator results say nothing about answer quality, retrieval or graph analytics.
- A side-by-side live comparison is impossible now: the AWS environment was removed on Nov 21. The comparison in section 7.5 is with Phase 15's committed records, made with Phase 15 code.
- 29 of the operations used are `unknown` in the documentation, so every one of them is a probe.
- The Step Functions probes depend on a saved Phase 15 plan file that may no longer exist locally.
- Every emulator script, suite and demonstration is `runtime: untested` until E14. Every Phase 16 code fix remains `fixed in code, tested offline, not validated live`. The emulator is not a live validation of production behavior.

## 9. Status lines

- `scripts/emulator/env.sh`, `setup.sh`, `probes.sh`, `lib.sh`: static check passed in session 2 (shellcheck clean); runtime: untested.
- `docker/emulator/Dockerfile`: runtime: untested; under repair to the documented form (P16-B-01).
- `sbt storage/EmulatorTest/test`: compiled in session 2 (storage-engineer, step 3); runtime: untested.
- `sbt services/EmulatorTest/test` (worker dead-letter demonstration): being written in session 2 step 7 when this draft was made; its compile status is in service-engineer's step 7 report; runtime: untested.
- Safety-contract unit tests (normal build): ran in session 2's targeted tests.
