# AWS Emulator operation inventory (Phase 16, decision 12 item 0)

Owner: infra-engineer. Status: documentation only; nothing here was run. Every operation below is one that the emulator suites (storage-engineer, service-engineer), `scripts/emulator/setup.sh` or `scripts/emulator/probes.sh` will call against the AWS Emulator at `https://127.0.0.1:9297`. Each is classified from the emulator documentation read on Nov 21, 2026 through the aws-docs MCP server (URLs below). Documentation text is treated as data.

## Classes

- `documented supported`: an emulator documentation page shows the emulator answering this operation (not a 501).
- `documented unsupported`: an emulator documentation page says the operation, or the behavior a test relies on, does not work in the emulator.
- `unknown`: the documentation neither shows nor excludes it. **Every `unknown` operation stays a probe**: the suites and scripts record its outcome class (`implemented-passed`, `implemented-failed`, `not-implemented`, `not-exercised`, `infrastructure-failure`, decision 6.6) and never assume it works.

## What the documentation says, in general (sources)

- S1. "AWS Emulator supports the APIs of AWS services that have an API model in the AWS API models repository on GitHub." Where it does not implement an operation yet, "it returns HTTP 501 with the error code `NotImplemented`". "Today, the emulator matches AWS most closely for control plane APIs. Fidelity for data plane APIs ... varies by service." https://docs.aws.amazon.com/emulator/latest/userguide/fidelity.html
- S2. The per-operation evidence is the fidelity-test manifest (`manifest.jsonl` in the aws-emulator-evaluation-suite repository on GitHub) and the per-service fidelity scores on the AWS Emulator releases page on GitHub (same page, "How we determine the fidelity score", "Reading the fidelity scores"; https://docs.aws.amazon.com/emulator/latest/userguide/check-project.html). **Neither is on docs.aws.amazon.com, and this phase allows no other network access, so no published fidelity score or manifest entry was read.** That is why most operations below are `unknown`, not because the documentation excludes them.
- S3. Policies: "The emulator doesn't evaluate AWS Identity and Access Management (IAM) policies and resource-based policies the way AWS does." https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html ("Limitations"). "The emulator never denies a request signed with its own key. It doesn't check AWS KMS key policies or Amazon S3 bucket policies." https://docs.aws.amazon.com/emulator/latest/userguide/security.html
- S4. One account and one Region; state in memory, lost at stop; not tested for load. https://docs.aws.amazon.com/emulator/latest/userguide/what-is-service.html ("Limitations"); https://docs.aws.amazon.com/emulator/latest/userguide/cli-reference.html ("Control routes": no reset route; "Stopping the emulator").
- S5. Host names: S3 requests must put the bucket in the path (an IP endpoint does this; `localhost` makes the Java SDK put the bucket in the host name); host-prefix injection must be off (`AWS_DISABLE_HOST_PREFIX_INJECTION=true` for the CLI, `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION` for the Java SDK 2.x); "Amazon S3 Control requests don't reach the emulator". https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html ("Host names that your SDK changes")
- S6. Resources with an endpoint of their own (for example databases) listen on a separate port. https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html ("To reach resource endpoints in a container")

## Inventory

Caller codes: `ST` storage `EmulatorAdaptersEmulatorSuite` (bounded tests of `AwsAdaptersAwsSuite`, SQS dead-letter test, resource-policy probe, re-embed demonstration); `SV` services `EmbeddingWorkerEmulatorSuite` (worker loop, poison message to the dead-letter queue); `SU` `scripts/emulator/setup.sh`; `PR` `scripts/emulator/probes.sh`.

| Service | Operation (API name) | Called by | Class | Source and note |
|---|---|---|---|---|
| STS | GetCallerIdentity | SU, PR (safety check: the account is compared, never printed or recorded) | documented supported | point-sdk-to-emulator.html, "To check that you're using the emulator": the output shows the emulator's account and the `user/emulator` ARN |
| S3 | CreateBucket | SU | documented supported | what-is-service.html, "Examples": `aws s3api create-bucket` then `list-buckets` returns it |
| S3 | ListBuckets | none (named only because the documentation example uses it) | documented supported | what-is-service.html, "Examples" |
| S3 | HeadBucket | SU (exists check before CreateBucket) | unknown | not in the documentation |
| S3 | PutBucketPolicy | SU (deny-all policy on `scholarchain-emu-policy-probe`) | unknown | the call itself is not documented; its **effect is documented unsupported**: bucket policies are not checked (S3). The storage resource-policy probe records allowed or refused, never asserts |
| S3 | PutObject (with `If-None-Match: *`) | ST (put-if-absent; re-embed text object; policy probe) | unknown | data plane; S1 says data-plane fidelity varies. The conditional write (412 on an existing key) is a separate behavior to observe |
| S3 | GetObject | ST (re-embed text read; policy probe) | unknown | data plane |
| S3 | HeadObject | ST (`AwsStorageEnvironment` bounded read) | unknown | data plane |
| S3 | ListObjectsV2 | ST (put-if-absent test lists the key) | unknown | data plane |
| DynamoDB | CreateTable | SU (six tables) | documented supported | what-is-service.html, "Examples": `CreateTable` with a too-short name is rejected "with the same error as AWS" (shows the operation is implemented, its validation path at least; the successful path is not shown) |
| DynamoDB | DescribeTable | SU (wait for `ACTIVE`, bounded) | unknown | |
| DynamoDB | UpdateTimeToLive | SU (`expiresAt` on the query-session table, as Terraform) | unknown | TTL deletion itself is not exercised by any test |
| DynamoDB | PutItem (condition `attribute_not_exists(pk)`; condition `attribute_exists(pk) AND #v = :expected`) | ST (RunLedger conditional writes; re-embed completion record), SV (none directly) | unknown | data plane; `ConditionalCheckFailedException` fidelity to observe |
| DynamoDB | GetItem | ST | unknown | data plane |
| DynamoDB | Query | ST | unknown | data plane |
| S3 Vectors | CreateVectorBucket | SU (`scholarchain-emu-vectors`), PR (`scholarchain-emu-probe-vectors`) | unknown | not in the emulator documentation |
| S3 Vectors | CreateIndex | SU (five indexes from `index-spec-v1.json`), PR (one probe index) | unknown | |
| S3 Vectors | GetIndex | SU (exists check), ST (index spec check) | unknown | |
| S3 Vectors | GetVectorBucket | PR (b) | unknown | |
| S3 Vectors | ListIndexes | PR (b) | unknown | |
| S3 Vectors | PutVectorBucketPolicy | PR (c, M-01 story) | unknown | the call is not documented; its effect is a resource-based policy, so **enforcement is documented unsupported** (S3). The probe records allowed or refused |
| S3 Vectors | PutVectors | ST (2-vector round trip; re-embed write) | unknown | data plane |
| S3 Vectors | GetVectors | ST, PR (c) | unknown | data plane |
| S3 Vectors | QueryVectors | ST (2-vector round trip) | unknown | data plane |
| SQS | GetQueueUrl | SU (exists check), ST, SV (the suites resolve queue URLs by name at run time; a queue URL is never written to a file, it may hold the account) | unknown | |
| SQS | CreateQueue (with `RedrivePolicy`, `maxReceiveCount` 5) | SU (four queues) | unknown | |
| SQS | GetQueueAttributes (`QueueArn`, `RedrivePolicy`) | SU (dead-letter ARN for the redrive policy; check of `maxReceiveCount`) | unknown | |
| SQS | SendMessageBatch | ST, SV (`SqsWorkQueue.enqueue`) | unknown | data plane |
| SQS | ReceiveMessage | ST, SV (main queue; dead-letter queue) | unknown | data plane; `ApproximateReceiveCount` and redrive after `maxReceiveCount` are the behaviors the dead-letter tests depend on (no documentation either way) |
| SQS | DeleteMessage | ST, SV (`ack`) | unknown | data plane |
| SQS | ChangeMessageVisibility (visibility 0) | ST, SV (`release`) | unknown | data plane |
| Step Functions | ValidateStateMachineDefinition | PR (a) | unknown | |
| Step Functions | TestState (Pass state `ReadInput`, inspection level DEBUG, no role) | PR (a) | unknown | |
| Neptune data (`neptunedata`) | ExecuteOpenCypherQuery, GetLoaderJobStatus, StartLoaderJob | **not called** (no Neptune section in `config/emulator.json`) | out of scope | the emulator documentation says nothing about `neptunedata` specifically. S1 covers services with an API model in the API models repository (whether `neptunedata`'s model is there was not checked: unverified). ScholarChain's Neptune client also targets a cluster host and port (`https://HOST:8182`), and S6 says database-like resources get their own port, which conflicts with the one-endpoint safety contract. Neptune is left out of every emulator test |
| S3 Control | any | not called | documented unsupported | S5: "Amazon S3 Control requests don't reach the emulator" |

Counts (this table, operations ScholarChain calls; ListBuckets and S3 Control excluded): documented supported 3 (GetCallerIdentity, CreateBucket, CreateTable); unknown 29 (6 S3, 5 DynamoDB, 9 S3 Vectors, 7 SQS, 2 Step Functions); out of scope 3 (Neptune data). Documented unsupported behaviors the probes touch: 2 (bucket-policy and vector-bucket-policy enforcement; the two calls themselves are counted as unknown).

## Consequences for the suites and scripts

- Every `unknown` operation is exercised as a probe: its outcome class is recorded per call, and a `not-implemented` (HTTP 501, `NotImplemented`) is a recorded compatibility gap, never validated functionality.
- The two policy probes (storage's deny-all bucket test, probes.sh M-01) record the observed outcome only: by S3, an allowed request is the documented emulator behavior, not a ScholarChain defect, and a refused one is not evidence that the emulator evaluates policies in general.
- No test or script creates or relies on IAM roles, users or policies.

## Resource names created by setup.sh (from `config/emulator.json`, plus fixed probe names)

| Kind | Name | Source |
|---|---|---|
| S3 bucket | `scholarchain-emu-data` | config `storage.rawBucket`, `storage.curatedBucket`, `storage.graphBucket`, `objectStore.semanticContextBucket`, `objectStore.reportsBucket`, `objectStore.logsBucket` (one bucket, six prefixes, as the course environment) |
| S3 bucket with a deny-all bucket policy | `scholarchain-emu-policy-probe` | fixed name (no config key); policy: `Deny`, `Principal` `*`, actions `s3:GetObject`, `s3:PutObject`, `s3:ListBucket` on the bucket and its objects |
| DynamoDB tables (`pk` S hash, `sk` S range, on demand; TTL `expiresAt` only on query-session) | `scholarchain-emu-run-ledger`, `scholarchain-emu-entity-link-cache`, `scholarchain-emu-query-session`, `scholarchain-emu-evidence-bundle`, `scholarchain-emu-artifact-registry`, `scholarchain-emu-researcher-metric-cache` | config `dynamoDb.*Table` (`TableSpecs.scala`; `infra/terraform/modules/dynamodb/main.tf`) |
| S3 vector bucket | `scholarchain-emu-vectors` | config `storage.vectorBucket` |
| Vector indexes (float32, 1024, cosine, non-filterable keys from the spec) | `researcher-profile-v1`, `collaboration-community-v1`, `paper-abstract-v1`, `collaboration-path-v1`, `topic-institution-summary-v1` | config `vectors.*Index`; `modules/storage/src/main/resources/s3vectors/index-spec-v1.json` |
| SQS queues | `scholarchain-emu-jobs` (visibility 150 s, retention 86400 s, `maxReceiveCount` 5), `scholarchain-emu-jobs-dlq`, `scholarchain-emu-embedding` (visibility 300 s, retention 345600 s, `maxReceiveCount` 5), `scholarchain-emu-embedding-dlq` (retention 1209600 s) | fixed names (no config key holds a queue name; the queue URL keys stay unset because a URL may hold the account); values from `infra/terraform/modules/workflow/main.tf` and `variables.tf` (job: jobLease 120 s + 30 s margin, `jobMaxAttempts` 3 + 2; embedding: defaults 300 s and 5) |
| Probe vector bucket and index (probes.sh only) | `scholarchain-emu-probe-vectors`, index `probe-v1` (float32, dimension 4, cosine) | fixed names; kept apart from `scholarchain-emu-vectors` so the deny policy of the M-01 probe never affects the suites |
