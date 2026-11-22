# Synthetic ER evaluation input (fixture)

Part 1 (ignored: it lies before the start marker).

- Policy: decoy-before-start (status final)

## Researcher resolution

| Set | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|
| All rows (unweighted) | 99 | 1.0% [0.0%, 2.0%] | 1.0% [0.0%, 2.0%] | 1.0% | 1.0% | 1.0% |

FINAL SUMMARY (verbatim):

# Entity-resolution gold-set evaluation

## Gold set

- Labeled rows: 20 (MATCH 10, NON_MATCH 6, UNSURE 4); unlabeled rows: 0.

## Inputs (read only)

| Role | Path | sha256 |
|---|---|---|
| gold | fixture/gold.csv | b25d8743af2d368ba8736e9adac3b9f9924bd7ba189537de5797722a8df13b42 |

## Run context

- Policy: er-fix (status final); policy sha256 recorded in the run manifest: none.
- Run: run:v1:abcdef, code version code-fix, decision time 2026-11-14T00:00:00Z; name key fixture.

## Researcher resolution

Definitions: as in the real summary.

| Set | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|
| All rows (unweighted) | 20 | 88.9% [56.5%, 98.0%] | 80.0% [49.0%, 94.3%] | 84.2% | 87.5% | 25.0% |
| Weighted estimate (bootstrap) | 20 | 90.0% [80.0%, 100.0%] (unreliable; zero-event bound [70.0%, 100.0%]) | 85.0% [70.0%, 95.0%] | 87.4% | 90.0% | 10.0% |
| path deterministic | 12 | 100.0% [70.1%, 100.0%] | n/a n/a | n/a | 50.0% | 0.0% |
| family dblp-base-name | 8 | n/a n/a | 0.0% [0.0%, 79.3%] | n/a | 0.0% | 100.0% |
| evidence: independent | 20 | 88.9% [56.5%, 98.0%] | 80.0% [49.0%, 94.3%] | 84.2% | 87.5% | 20.0% |

Weighted bootstrap 95%: coverage [85.0%, 95.0%], f1 [80.0%, 92.0%] (unreliable), precision [80.0%, 100.0%] (unreliable; zero-event bound [70.0%, 100.0%]), recall [70.0%, 95.0%], unresolvedRate [10.0%, 10.0%] (design-determined)

- precision: bootstrap [80.0%, 100.0%] unreliable: fixture text. Zero-event bound [70.0%, 100.0%].
- unresolvedRate: bootstrap [10.0%, 10.0%] design-determined: fixture text. Zero-event bound [10.0%, 12.5%].

### Confusion matrix (unweighted rows)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 8 | 1 | 1 |
| rejected | 1 | 4 | 0 |
| unresolved | 1 | 1 | 2 |
| conflict | 0 | 0 | 1 |

### Confusion matrix (weighted: estimated population decisions)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 800.5 | 10.0 | 5.25 |
| rejected | 3.0 | 400.0 | 0.0 |
| unresolved | 20.0 | 15.0 | 30.0 |
| conflict | 0.0 | 0.0 | 12.0 |

## Part 3: addendum

- Run: run:v1:ffffff, code version decoy-after-end, decision time none.
