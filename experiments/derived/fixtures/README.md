# Derive-script fixtures (hand-written, not measured)

Owner: `experiment-engineer`. Every file under `raw/` is **hand-written test data** in the exact layout the runner (`scripts/run-experiment.sh`), the collectors and `load-tests/query-load.sh` write under `experiments/raw/MANIFEST_ID/ATTEMPT_ID/`. Nothing here is a measurement, and nothing here may be quoted as a result.

Purpose (decision 11 item 2, D0a): the human runs each derive script once with `--fixtures` before real data exists; the script reads only `experiments/derived/fixtures/raw/**` and writes only into a new temporary folder outside the repository, whose path it prints.

Placeholders only: commit hashes are `0000…`, cluster IDs `j-EXAMPLE…`, queue and service names `example-…`; no account, host, URL, key or bucket.

What the fixtures cover on purpose:

| Manifest folder | Attempts | Covers |
|---|---|---|
| `p15-a-spark` | 1 succeeded | flat capacity (no scale-out), one empty series (`AppsPending`) |
| `p15-b-spark-burst` | 1 failed (all series empty, no lifecycle record), 1 succeeded | failed attempt kept; scale-out 2 to 4, scale-in before termination, release after it |
| `p15-a-hadoop` | none | a manifest without attempts is reported as missing |
| `p15-c-embedding-backlog` | 1 failed (collector exit 3, no metrics), 1 succeeded | 0 to 1 to 2 to 0, scaling activities, one empty series |
| `p15-f-poison-replay` | 1 succeeded | poison to DLQ, processed then replayed from worker-log events, API replay and 409, alarm samples, one empty series |
| `p15-d-query-load` | 1 succeeded | two levels, every main class, explain rows with missing stages, clock samples, one empty series |
| `p15-d-read-load` | 1 succeeded | ID check, not-found and throttled classes, no metrics folder (missing) |
| `p15-e-retrieval-modes` | 1 succeeded | three modes, a missing K row, a universe under 15 (exploratory) |

Epoch seconds: `1795204800` is 2026-11-20T20:00:00Z.
