# identifier-scan-3.md: decision 16 identifier scan before the gate commit

Orchestrator, Phase 17 session 3, 2026-11-27. Read-only; counts, line numbers and masked shapes only; no matching value is printed here.

## Scope

- Every repository file created or changed after identifier-scan-R3.md (committed in b049e9e) up to the working tree: 52 paths. 46 are changed in the commits 1823cbf, 5b911d9, 0c4ec52 and 38b5c84 or in the working tree (README.md, docs/demo-script.md, docs/hadoop-job-contracts.md, reports/final-report.md, docs/agent-runs/instructor-approvals.md, and 41 under docs/agent-runs/phase-17/). 6 are new and not yet committed (human-gate.md, commit.txt, orchestrator-summary-5.md, agent-report-docs-run5.md, draft-autograder-run5.md, review-report-run5.md).
- Post-RC path rule: 0 of the 52 paths are outside README.md, docs/** (other than docs/agents.md, docs/incidents/** and other phases' records), reports/** and docs/agent-runs/phase-17/; instructor-approvals.md is the human's approved exception.
- All 6,204 lines added since b049e9e: 5,799 in the tracked diff and 405 in the 6 new files.
- This file, and the copy of runbook-log.md made after it, are checked again by the human's scan step before the commit.

## Whole files (52)

| Kind | Files and count | Shape |
|---|---|---|
| ARN with a twelve-digit account, AKIA/ASIA key shape, `pa-` key shape, twelve zeros, masked digits | 0 | |
| `sk-ant-`, `execute-api` | agent-report-docs-demo.md (1, 1), orchestrator-summary-3.md (1, 1), runbook-log.md (2, 3) | pattern names in search descriptions |
| Twelve-digit run (not inside a hex string) | runbook-log.md (2) | the all-digit release-record short commit (a hash), as in identifier-scan-R3.md |
| Prefixed AWS host | orchestrator-summary-3.md (2), runbook-log.md (4) | the generic ECS service name or service principal, and the masked shape `xxxxxxxx.s3xxxxxxx.amazonaws.com`; no account-specific endpoint |
| Home path (the main checkout's path) | agent-report-docs-demo.md (2), repair-docs-demo.md (1), repair-docs-demo-2.md (1), repair-docs-run4.md (2) | report-contract lines in committed agent reports, the same class as earlier committed records (summary 4, deviation 34). repair-docs-run4.md (1823cbf) is newly counted here: its 2 lines came after identifier-scan-R3.md |
| `/Users/` as text | agent-report-docs-demo.md (1), review-report-demo.md (1), repair-docs-demo-3.md (1) | the pattern name in search descriptions |
| E-mail shape | README.md:3, reports/final-report.md:3 | the "Student:" line with the human's own address (pre-existing, already public; README.md:3 not changed since b049e9e) |
| E-mail shape | diff-final-report-video.txt (1), diff-docs-final-pass.txt (1) | the same "Student:" line as diff context |

## Lines added since b049e9e (6,204)

| Pattern | Count | Shapes |
|---|---|---|
| twelve-digit run (not in hex) | 0 | |
| twelve zeros | 0 | |
| IPv4 | 0 | |
| masked digits | 0 | |
| `AKIA`/`ASIA` | 13 | pattern names only; key-shaped values 0 |
| `sk-ant` | 3 | pattern names only |
| `arn:aws` | 12 | bare prefix in search descriptions; with an account 0 |
| `amazonaws.com` | 4 | the bare suffix, the generic ECS service name and the masked shape |
| `s3://` | 38 | the `s3://{...}` and `s3://example-bucket` placeholders (16) and the bare scheme in descriptions; real bucket names 0 |
| `/Users/` | 9 | the home-path lines and pattern names in the table above |
| e-mail shape | 2 | the "Student:" diff-context lines in the table above |

## Result

Unexpected matches: none. Every hit is a pattern name, a placeholder, the all-digit release-record short commit, a generic AWS service name, the masked shape, the human's own pre-existing address, or home-path text in committed agent reports (the class already disclosed in summary 4, deviation 34). No secret, key, token, real account ID, ARN with an account, bucket name, queue URL, endpoint host or public IP.

## Command notes

The scan used `xargs echo`, `xargs -n1 printf`, `head -n` and `printf` inside loops to show only three-letter prefixes and lengths of host matches, and one `cd` into docs/agent-runs/phase-17/ (it changed the session's working folder; later calls used absolute paths). All read-only and outside the orchestrator's command list (unplanned deviation; recorded in the runbook log). The added lines were collected into a scratchpad file (scan3-added.txt), which is not copied.
