# Phase 17 session 3, step E11: identifier scan 2 (decision 16, repeated)

Orchestrator, 2026-11-23 (session 3, after the E11 focused re-check). As at S-ID, the Grep tool is not available in this session, so Bash `grep` with literal patterns ran over an explicit file list (no recursive grep; no grep over infra/terraform/environments/course/). No matching value other than the documented expected values is written in this report.

## Scope

Every file changed or created since S-ID (session 2 commit d8b23f2):
- `git diff --name-only d8b23f2` (committed E0, E1 and E10 changes plus the uncommitted E11 changes): 21 repository files: README.md, docs/api.md, docs/demo-script.md, docs/final-state.md, docs/runbook.md, docs/service-limits.md, docs/threat-model.md, reports/final-report.md, the two rag files of E1 (ClaimVerification.scala, AbsenceRuleSuite.scala), the two release-manifest files under releases/manifest/b4d8f1229fc145e1492e1a8cbb8a44a368d026af/, and 9 files under docs/agent-runs/phase-17/ (approvals.md, checkpoint-s3.md, the E1 diffs, repairs and re-checks, runbook-log.md).
- `git ls-files -o --exclude-standard`: 0 new untracked files.
- The scratchpad reports the human copies with the E11 commit: the 17 `.md` and `.txt` files in phase17-reports/, plus this report.
- Forbidden paths in the list (state, plan, .terraform/, evidence/): 0.

## Patterns and results

| Pattern | Matches | Classification |
|---|---|---|
| `AKIA[0-9A-Z]{16}`, `ASIA[0-9A-Z]{16}` | 0 | – |
| `sk-ant-` | 4 files (repair-E1-docs.md, committed and scratchpad copy; repair-E11-docs.md ×2; review-report-final-docs-2.md) | pattern text: each line lists the patterns of an identifier scan |
| `pa-[A-Za-z0-9_-]{20,}` | 0 | – |
| `arn:aws:…:[0-9]{12}` | 0 | – |
| `amazonaws\.com` | repair-E1-docs.md (both copies), repair-E11-docs.md, runbook-log.md | pattern text in the two repair reports; runbook-log.md:50 names the CloudTrail event source `ecs.amazonaws.com` (an AWS service name, not an endpoint of this project) |
| `execute-api`, `\.elb\.` | repair-E1-docs.md (both copies), repair-E11-docs.md, review-report-final-docs-2.md | pattern text only |
| `neptune\.amazonaws`, `sqs\.….amazonaws` | 0 | – |
| `[0-9]{12}` standalone, other than 123456789012, 210987654321 and 167196849087 | **0** | – |
| twelve zeros | 0 | – |
| IPv4 other than 127.0.0.1 | docs/service-limits.md (1.4.7.1 twice, 1.3.2.0), reports/final-report.md (1.4.7.1) | version strings (Neptune engine and similar), not addresses |

## Expected matches (the documented release-record commit prefix 167196849087, inside the record file name `20261119T193246Z-167196849087-d539`)

Lines holding it: docs/final-state.md 1, docs/runbook.md 1 (both on lines unchanged since earlier phases), release-manifest.json 4 lines, release-manifest.md 2 lines (the E10 manifest; the E10 scan counted 5 word-bounded runs, json 3 and md 2), manifest-checksums.txt 2, runbook-log.md 1. The placeholders 123456789012 and 210987654321: 0 in this file list.

## Unexpected matches

**None.**

## Limits

A targeted, supplementary scan, not proof that no secret or identifier exists (a bucket name without an AWS host, or an unknown key format, would not match). It proves nothing about Git history (every commit was scanned when it was made; E12 runs the history scan).
