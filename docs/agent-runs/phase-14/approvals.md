# Phase 14 approvals

## Runtime-off plan, attempt 2

The first apply of plan aee655c7 (Phase 13 gate) stopped after 153 of 175 creates: Neptune CreateDBCluster returned KMSKeyNotAccessibleFault for the AWS managed key aws/rds (first use in this account). No code changed. This plan was made at d4ddea8 with the default profile in us-east-1: 22 create, 0 change, 0 delete, 0 replace.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 17, 2026

APPROVED-PLAN-SHA256: 6d4a629c5a6af7167c4581cbe5fdc417b054838b35d5c3653579f7aaf6a70df5

## Runtime-off plan, attempt 3

Attempt 2 (plan 6d4a629c) stopped after 21 of 22 creates: PutDashboard refused the dashboard body (I-29, fixed in e78bb10; widget 7 expression key fixed in 1f9a20e, G-02). This plan was made at 1f9a20e with the default profile in us-east-1: 1 create (the dashboard), 0 change, 0 delete, 0 replace.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 18, 2026

APPROVED-PLAN-SHA256: a865a94dce63f9cc6e683e62105814475a2849f0fa065234320c44d94eab67d1

## Cost bound

Phase 14 cost bound: $8 across AWS, Anthropic and Voyage. Signed: Brian, Nov 18, 2026

## Runtime-off plan, attempt 4 (G-14)

D6 attempt 2 failed: the pipeline state machine role lacked elasticmapreduce:AddTags (G-14, fixed in b15dc78). This plan was made at b15dc78 with the default profile in us-east-1: 0 create, 3 in-place updates (state machine role policy, role boundary policy, deployment role policy re-read because it references the boundary), 0 delete, 0 replace. The boundary change needs my admin profile.

I approve this plan to be applied with my own admin profile (default). Signed: Brian, Nov 18, 2026

APPROVED-PLAN-SHA256: 5c77365f770a5cf85507194a07915d7b0b7f223d6b31ca7aeb434fdaea19cc12

## Runtime-off plan, attempt 5 (G-21)

D8 attempt 1 failed: the Spark driver ran out of memory on EMR with the default 2 GiB heap (G-21, fixed in 20e79f3). This plan was made at 20e79f3 in us-east-1: 0 create, 1 in-place update (pipeline state machine definition: driver memory 6g, shuffle partitions 32 and UI retention for the two Spark steps), 0 delete, 0 replace. No IAM, network or stateful change. Build commit stays f159535e.

I approve this plan. Signed: Brian, Nov 19, 2026

APPROVED-PLAN-SHA256: 619576314f4bcf324397381de79c70d6fde794873770ed526a676f870fdf8975
