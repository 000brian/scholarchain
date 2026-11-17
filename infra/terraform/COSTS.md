# ScholarChain course environment: costs (us-east-1)

Owner: `infra-engineer`. Written in Phase 12 step 2b (2026-11-17) from the Terraform configuration, not from a plan or a bill.

**Price sources.** The pricing pages (`aws.amazon.com/<service>/pricing/`) are not reachable with the tools of this step: the aws-docs MCP server reads only `docs.aws.amazon.com`, and the documentation pages it reached state no prices (VPC "Pricing for NAT gateways", Neptune "Choosing instance types", Secrets Manager "intro", all read 2026-11-17). **Every price below is therefore `estimate (unverified)`**, from list prices known before this step. The human checks each one on its pricing page before signing the plan.

**Idle is not free.** With `runtime_enabled = false` and no pipeline running, the NAT gateway, its public IPv4 address, the internal ALB and (after its free trial) Neptune still bill every hour, about **$1.80 to $3.90 per day**.

## Resources that bill while they exist

| Resource (module) | Count | us-east-1 price | Per day | Source | Notes |
|---|---|---|---|---|---|
| NAT gateway (network) | 1 | $0.045 per hour + $0.045 per GB processed | $1.08 + data | estimate (unverified) | Decided: one NAT, not one per AZ |
| Public IPv4 address of the NAT (network) | 1 | $0.005 per hour | $0.12 | estimate (unverified) | All public IPv4 addresses bill hourly (EC2 User Guide notice; the amount is from the pricing page, not read) |
| Internal ALB (runtime) | 1 | $0.0225 per hour + $0.008 per LCU-hour | $0.54 + LCU (about 0 idle) | estimate (unverified) | Exists even with the runtime off |
| Neptune `db.t4g.medium` (neptune) | 1 instance | about $0.09 per hour on demand; storage $0.10 per GB-month; I/O $0.20 per million | $0 during the trial, else about $2.10 | estimate (unverified) | Free trial, see below. Created no earlier than Nov 20 (Phase 14) |
| Secrets Manager secrets (identity) | 4 | $0.40 per secret-month + $0.05 per 10 000 calls | $0.053 | estimate (unverified) | Containers exist from the first apply; values entered by hand |
| CloudWatch dashboard (observability) | 1 | 3 dashboards free, then $3.00 per month | $0 (free tier) or $0.10 | estimate (unverified) | |
| CloudWatch alarms, runtime off (observability) | 4 alarms, 5 alarm metrics (2 DLQ, ALB 5xx with 2 metrics, Neptune CPU) | $0.10 per alarm metric-month (10 free) | about $0.02 | estimate (unverified) | Metric-math alarms bill per metric |
| CloudWatch alarms, runtime on (runtime, observability) | + 3 HealthyHostCount, + 2 worker alarms (3 metrics), + target tracking alarms (about 2 per ALB service, created by Application Auto Scaling) | $0.10 per alarm metric-month | about +$0.05 | estimate (unverified) | |
| CloudWatch Logs (observability) | 5 groups, 7-day retention | $0.50 per GB ingested (vended Step Functions logs: $0.50 per GB), $0.03 per GB-month | about $0 at course traffic | estimate (unverified) | |
| ECR repository (runtime) | 1, up to 10 images | $0.10 per GB-month | about $0.01 (3 GB) | estimate (unverified) | Basic scan on push: no charge (unverified) |
| S3 data lake (data-lake) | 1 bucket, versioned | $0.023 per GB-month, requests $0.005 per 1 000 PUT, $0.0004 per 1 000 GET | about $0.01 for a few GB | estimate (unverified) | Old versions expire after 7 days, `logs/` after 14 |
| S3 Vectors (vectors) | 1 bucket, 5 indexes | storage about $0.06 per GB-month; PUT about $0.20 per GB; queries per call and per TB processed | about $0 at course size | estimate (unverified) | |
| DynamoDB (dynamodb) | 6 tables, on demand | about $0.625 per million write units, $0.125 per million read units; storage $0.25 per GB-month (25 GB free) | about $0 idle | estimate (unverified) | PITR off |
| SQS (workflow) | 4 queues | first 1 million requests per month free, then $0.40 per million | $0 | estimate (unverified) | The worker's 1 s long poll makes about 86 400 receives per worker task-day |
| API Gateway HTTP API (runtime) | 1 API, VPC link | $1.00 per million requests; VPC link for HTTP APIs: no separate charge | $0 | estimate (unverified) | |
| Step Functions (workflow) | 1 standard state machine | $0.025 per 1 000 state transitions (4 000 free per month) | $0 per day; about 15 transitions per run | estimate (unverified) | |
| SNS topic (observability) | 1 | first million publishes free | $0 | estimate (unverified) | |
| Free while they exist | VPC, subnets, route tables, internet gateway, S3 and DynamoDB gateway endpoints, security groups, IAM roles and policies, ECS cluster, task definitions, scaling targets | $0 | $0 | | |

## Per-use costs

| Resource | When | Price | Per run or hour | Source |
|---|---|---|---|---|
| ECS Fargate ARM64 tasks (runtime on, minimum counts) | query 1 vCPU/2 GB, evidence 0.5/1, graph 0.5/1; worker 0 | about $0.0324 per vCPU-hour + $0.0036 per GB-hour | about $0.079 per hour, **$1.90 per day** | estimate (unverified) |
| ECS Fargate at maximum (runtime on) | query, evidence, graph at 2 each; worker at 2 | same | about $0.24 per hour | estimate (unverified) |
| ops task (run-task by hand) | 1 vCPU/4 GB, 30 GiB ephemeral (10 GiB above the free 20) | as above + about $0.000111 per GB-hour of storage | about $0.05 per hour | estimate (unverified) |
| EMR cluster (pipeline only) | m5.xlarge: EC2 about $0.192 + EMR about $0.048 per instance-hour; 3 instances at start, at most 5 | | about $0.72 per hour at start, $1.20 at peak; plus EBS volumes | estimate (unverified) |
| EMR-created `emr-service-vpce` interface endpoint (EMR 8.x) | while it exists | about $0.01 per hour per AZ + $0.01 per GB | about $0.24 to $0.48 per day if left behind | estimate (unverified); whether EMR removes it is unverified (step 2a RISK R2) |
| NAT data processing | image pulls, Anthropic and Voyage calls, EMR package traffic | $0.045 per GB | a 500 MB image pull costs about $0.02 | estimate (unverified) |
| Anthropic, Voyage | per query and per ingest | provider prices | counts against the same $25 ceiling | not an AWS price |

A bounded pipeline run (Hadoop cluster about 1 hour, Spark cluster about 1 hour, both at start size) costs about **$1.50** (estimate (unverified)); a run that reaches peak size and the 4-hour timeout would cost about $5.

## Neptune free trial

- Confirmed from the Neptune User Guide page "Choosing instance types for Amazon Neptune" (`https://docs.aws.amazon.com/neptune/latest/userguide/instance-types.html`, read 2026-11-17): `t3` and `t4g` medium instances are eligible for the Neptune free-tier offer, "which lets new customers use Neptune at no cost for the first 750 instance hours used within a standalone AWS account or rolled up underneath an AWS Organization with Consolidated Billing".
- **Unconfirmed** (the free-trial and pricing pages are not reachable here): that the offer lasts 30 days, that it covers 10 million I/O requests and 1 GB of storage, and that this account still qualifies. 750 hours is about 31 days of one instance.

## Cost per day and how long the money lasts

Money left under the $25 ceiling: about **$24.80** (spent so far $0.13 to $0.19, decision 2). The same ceiling also pays Anthropic and Voyage. All numbers are estimates (unverified).

| State | Per day, Neptune in trial | Days that fit | Per day, Neptune billed | Days that fit |
|---|---|---|---|---|
| Idle: `runtime_enabled = false`, no pipeline | about $1.80 (NAT 1.08, IPv4 0.12, ALB 0.54, secrets 0.05, alarms and the rest 0.03) | about 13 | about $3.90 | about 6 |
| Runtime on at minimum counts (query 1, evidence 1, graph 1, worker 0) | about $3.75 | about 6 | about $5.85 | about 4 |
| Plus each pipeline run | + about $1.50 per run | | | |

Before Neptune exists (first apply before Nov 20 is not planned; decision 2), idle is about $1.80 per day either way.

## Switch off or remove promptly

- **Right after Phase 15** (scaling measurements): `runtime_enabled = false` (removes about $1.90 per day of Fargate and the extra alarms). Confirm no EMR cluster and no `emr-service-vpce` endpoint is left from Phase 15 pipeline runs.
- **Right after Phase 16** (final demo and evidence): run the cleanup order in `README.md` ("Cleanup mode"), starting with the evidence archive. The resources that cost money while idle and must go first: the **NAT gateway and its public IPv4 address**, the **internal ALB** (with the API Gateway HTTP API and VPC link, which go with it), and **Neptune** (it bills hourly as soon as the free-trial hours are used up). The four secrets bill monthly until removed.
- **Never leave running**: an EMR cluster (about $0.72 to $1.20 per hour) or an ops task.
- The budget alerts ($10, $15, $20) are warnings only; they stop nothing.
