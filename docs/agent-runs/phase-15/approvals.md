Phase 15 cost bound: $9 across AWS, Anthropic and Voyage, including idle and runtime hours until the cleanup.

## Runtime-on plan (D3, Phase 15)

This plan was made at 85dacb44 (build commit 684636ef) with the default profile in us-east-1: 1 create, 9 in-place updates, 0 delete, 5 replace (the five task definitions). IAM and resource-policy changes: ops task policy, worker task policy, embedding queue policy. No stateful delete or replace. D4a: state machine definition valid; test-state results for absent, A, B and invalid inputs as expected; identical configuration environment on all four service task definitions.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-PLAN-SHA256: 934d78c312a363ac02ee330db02372a20ab9226416f05e923041178b2d814480

## Manifest p15-a-hadoop (D9)

I approve experiment manifest p15-a-hadoop (A, Hadoop half, managed-scaling maximum 2), cost bound $2.50, maximum duration 18600 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: 1254a11cd8431bfdf41b0549edca9f62adf622a5c13858ee7e469be4fa0488d8

## Manifest p15-a-spark (D9)

I approve experiment manifest p15-a-spark (A, Spark half on Phase 14 inputs, maximum 2, 32 partitions), cost bound $1.50, maximum duration 17400 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: 1875db33a1f57632e9fcec579dcce3f1c717139ac1cba13c685ed654588f9531

## Manifest p15-b-spark-burst (D9)

I approve experiment manifest p15-b-spark-burst (B, Spark on Phase 14 inputs, maximum 4, 128 partitions), cost bound $2.00, maximum duration 17400 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: 1b869c94c0370810d45810cfb1b2f7e91f272f94415b563d8c990b54d8f17c87

## Manifest p15-c-embedding-backlog (D9)

I approve experiment manifest p15-c-embedding-backlog (C, re-embed backlog), cost bound $0.50, maximum duration 7700 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: 36352e8fcdf6287a41b66e386f045f81d88db864a7f07e0169c7a6efa5101ab8

## Manifest p15-d-query-load (D9)

I approve experiment manifest p15-d-query-load (D, LLM queries at 1, 2, 4, 8 clients), cost bound $1.00, maximum duration 2400 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: 2af43f86a39c31b00cb5c95810265e4d417be5522cef2bbdba0afaff33dc480b

## Manifest p15-d-read-load (D9)

I approve experiment manifest p15-d-read-load (D, read routes at 1, 5, 10, 20 clients), cost bound $0.20, maximum duration 2400 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: d851716dfbcd909f96a4b5e5be67d88a85206de67f9e74478b020825c7ce3070

## Manifest p15-e-retrieval-modes (D9)

I approve experiment manifest p15-e-retrieval-modes (E, graph-only, vector-only, fused; no LLM), cost bound $0.20, maximum duration 3600 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: be3868dff8802e0cf2a2b60c5838444a3ec4bafb9cf7e9f15c2cab30ef1b8019

## Manifest p15-f-poison-replay (D9)

I approve experiment manifest p15-f-poison-replay (F, poison message, replay, API idempotency), cost bound $0.10, maximum duration 4800 s, run with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-MANIFEST-SHA256: a39c7e453f3c9264092fc22640e2c56465e06ff947bf031291ee9112a5b5fbaf

## Runtime-on plan after repair L-01 (Phase 15)

Repair L-01 (EMR refused managed-scaling max = min in D7a attempt 1). This plan was made at e94df52 (build commit 684636ef unchanged) with the default profile in us-east-1: 0 create, 1 in-place update (the pipeline state machine definition), 0 delete, 0 replace; no IAM, network or stateful change. D4a: definition valid; test-state results for absent, A (2/32), 3, B (4/128) and invalid inputs as expected, with the rest of each cluster request unchanged.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-PLAN-SHA256: 235b56f540bdc237ae511230736bbe184e420123609098ca809bac27b067de6d
