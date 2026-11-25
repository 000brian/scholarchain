# identifier-scan-R3.md: decision 16 identifier scan, E11 repeat after repair round R3

Orchestrator, Phase 17 session 3, 2026-11-25. Read-only; counts, line numbers and masked shapes only; no matching value is printed here.

## Scope

- Every repository file changed since the first E11 commit (ade85aa) up to the working tree: 92 paths (the E12 and R3 commits 62de5e5..daca689 and the 7 uncommitted E11-repeat documents), plus the new manifest folder releases/manifest/4feef0ff117e9b7b0c1f411e875950e0bde06ba4/ (scanned at E10, see the runbook-log row "E10 (repeat, R3)").
- All 7,475 lines added since ade85aa outside releases/manifest/.
- The scratchpad reports to be copied with the next records commit (agent-report-docs-E11-R3.md, diff-E11-docs-R3.txt, review-report-final-docs-R3.md, manifest-checksums-2.txt, this file, runbook-log.md, autograder-response.md).

## Strong patterns over the 92 files (whole files)

Pattern set: ARN with a twelve-digit account, AKIA/ASIA plus 16, `sk-ant-`, `pa-` plus 20, `execute-api`, twelve zeros.

| File | Pattern | Lines | Shape |
|---|---|---|---|
| docs/agent-runs/phase-17/review-report-autograder.md | execute-api | 16 | pattern name in a search description |
| docs/agent-runs/phase-17/review-report-autograder-2.md | execute-api | 32 | pattern name in a search description |
| docs/agent-runs/phase-17/review-report-E12-docs.md | sk-ant-, execute-api | 27 | pattern names in a search description |
| docs/agent-runs/phase-17/runbook-log.md | execute-api | 117 | row "E12 repeat: history scan", pattern names |
| scripts/release-manifest.sh | execute-api, sk-ant- | 131, 886, 890 | the script's own identifier-check patterns (unchanged lines) |
| scripts/README.md | execute-api | 69 | pattern name in the manifest description (unchanged line) |

Twelve zeros: 0. Key-shaped values, ARNs with an account: 0.

## Twelve-digit runs (not inside a hex string), 92 files

- datasets/dataset-manifest.yaml:479 and :671: byte counts (`snapshot_works_bytes`, `all_files_compressed_bytes`), unchanged lines, not added in R3.
- scripts/release-manifest.sh:28 and scripts/README.md:69: the documented placeholder account values (unchanged lines).
- The release-record short commit (all digits, a hash): docs/final-state.md 1, docs/runbook.md 1, scripts/README.md 1, docs/agent-runs/phase-17/runbook-log.md 2; and in the new manifest 3 (json) + 2 (md). Expected.
- Others: 0.

## Lines added since ade85aa (7,475)

| Pattern | Count | Shapes |
|---|---|---|
| twelve-digit run (not in hex) | 0 | |
| twelve zeros | 0 | |
| IPv4 | 0 | |
| `AKIA` | 12 | bare pattern name only (no key-shaped value) |
| `sk-ant` | 2 | pattern name only |
| `arn:aws` | 11 | bare prefix in search descriptions; no account, no resource |
| `amazonaws.com` | 5 | bare suffix (4) and the masked history-scan shape `xxxxxxxx.s3xxxxxxx.amazonaws.com` (1) |
| `s3://` | 25 | bare scheme in descriptions (7) and the `s3://{data-bucket}` placeholder (18) |

## Result

Unexpected matches: none. Every hit is a pattern name, a placeholder, a byte count, or the all-digit release-record short commit. No secret, key, token, real account ID, ARN with an account, bucket name, queue URL, endpoint host or public IP.

## Command notes

The scan used `cat` on the orchestrator's own scratchpad file list, `xargs echo` to join line numbers, `uniq -c` on masked shapes and one `perl -pe` to mask digits in its own output: all read-only and outside the orchestrator's command list (unplanned deviation; recorded in the runbook log).
