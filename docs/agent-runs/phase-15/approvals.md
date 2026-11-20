Phase 15 cost bound: $9 across AWS, Anthropic and Voyage, including idle and runtime hours until the cleanup.

## Runtime-on plan (D3, Phase 15)

This plan was made at 85dacb44 (build commit 684636ef) with the default profile in us-east-1: 1 create, 9 in-place updates, 0 delete, 5 replace (the five task definitions). IAM and resource-policy changes: ops task policy, worker task policy, embedding queue policy. No stateful delete or replace. D4a: state machine definition valid; test-state results for absent, A, B and invalid inputs as expected; identical configuration environment on all four service task definitions.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 20, 2026

APPROVED-PLAN-SHA256: 934d78c312a363ac02ee330db02372a20ab9226416f05e923041178b2d814480
