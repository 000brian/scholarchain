# Phase 17 session 2, step S-ID: identifier scan (decision 16)

Orchestrator, about 21:00 Sunday 2026-11-22. The Grep tool is not available in this session, so the work order's fallback was used: Bash `grep` with literal patterns over an explicit file list (no recursive grep; no grep over infra/terraform/environments/course/ as a folder; its tracked .tf files are named in the list, and no state, plan, .terraform/ or evidence/ path is in the list: 0 such entries). No matching value is written in this report.

## Scope
- `git ls-files -co --exclude-standard`: 1,628 files (every tracked file plus every new, not-ignored file), saved as sid-files.txt.
- Phase 17 subset (changed or new outside docs/agent-runs/phase-17/): 40 files, saved as sid-p17-files.txt.

## Results, all files (1,628)
| Pattern | Files matching | Classification |
|---|---|---|
| `AKIA[0-9A-Z]{16}` | 4 | AWS's published example key at its two known test lines in EmulatorWiringSuite.scala (P16-A-01; lines 482 and 535 now, 340 and 393 at HEAD, shifted by step 2's additions; the Phase 17 diff adds no such line), and quotations of P16-A-01 in three committed Phase 16 reports (orchestrator-summary-s2.md, review-report-a1.md, runbook-log.md; committed in d37389b and be1ec67) |
| `ASIA[0-9A-Z]{16}` | 0 | – |
| `sk-ant-` | 2 | pattern text: docs/agent-runs/phase-17/prompt.md (quotes the scan) and scripts/release-manifest.sh:890 (identifier-check pattern) |
| `pa-[A-Za-z0-9_-]{20,}` | 0 | – |
| `arn:aws:…:[0-9]{12}` | 15 | every account in every match is one of the two documented placeholders (filter for any other account: 0 matches) |
| `amazonaws\.com`, `execute-api`, `\.elb\.`, `neptune\.amazonaws`, `sqs\.….amazonaws` | committed files from earlier phases (scanned at their commits), dataset URLs, Terraform service principals and policy text, test fixtures with made-up hosts, pattern text in scripts | in Phase 17 changed or new files: only pattern or description text (scripts/release-manifest.sh:131-132, :886; scripts/README.md new line describing the identifier check); the Phase 17 diff adds no host line to collector-common.sh or EmulatorWiringSuite.scala |
| `[0-9]{12}` standalone (not inside a longer token or hash), not a placeholder or the release-record commit prefix `167196849087` | 52 occurrences, all in files committed in earlier phases (hook self-tests, dataset-manifest byte counts, amendment-02 diffs, raw experiment data) | committed earlier and scanned at their commits; none in a Phase 17 changed or new file |

## Results, Phase 17 changed and new files (40)
- Standalone twelve-digit runs other than 123456789012, 210987654321 and 167196849087: **0**.
- Key, token, ARN-with-real-account or host patterns: only the pattern text and the P16-A-01 test lines above.
- IPv4: 0.0.0.0 (docs/emulator.md, bind address text), 10.0.0.1 and 10.1.2.3 (private addresses in EmulatorWiringSuite.scala test inputs), and version strings 1.4.7.1 (Neptune engine) and 1.3.2.0 (docs/service-limits.md). No public IP.
- Twelve zeros: none (covered by the standalone-run filter: 0).

## Unexpected matches
**None in file contents.**

## Addendum (orchestrator, after the scan)
1. **Scratchpad reports** (the files the human copies into docs/agent-runs/phase-17/): scanned with the same patterns. 0 standalone twelve-digit runs other than the expected values; 0 key or token shapes; 0 ARNs with a non-placeholder account; host-pattern hits are bare pattern words only (0 host-shaped tokens). One report (repair-01-docs.md:111) quoted the twelve-zero account pattern as a literal run; its author replaced it with the words "twelve zeros" (re-scan: 0).
2. **File names, for the human (not file contents):** 43 tracked paths under experiments/derived/fixtures/, committed before Phase 17, contain a run of twelve zeros in their names. Decision 16's patterns scan contents, and its expected list does not name these, so they are reported here for your decision rather than classified by the orchestrator. They are not changed in Phase 17. The orchestrator's own file list sid-files.txt was rewritten to a regenerable description so that it does not repeat those names in a staged file.

## Limits
A targeted, supplementary scan, not proof that no secret or identifier exists (a bucket name without an AWS host, or an unknown key format, would not match). It proves nothing about Git history (every commit was scanned when it was made; E12 runs the history scan).
