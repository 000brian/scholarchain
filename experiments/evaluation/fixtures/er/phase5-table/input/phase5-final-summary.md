Fixture: verbatim lines of docs/agent-runs/phase-05/agent-report-er-eval.md (final summary.md of Part 2: lines 742, 753, 779, 782, 786-808, 812-816, 881-897 and the Part 3 heading at 1205). The Inputs table (757-768) is left out because it holds absolute home-directory paths.

FULL FINAL summary.md (verbatim):

- Labeled rows: 300 (MATCH 112, NON_MATCH 135, UNSURE 53); unlabeled rows: 0.

- Policy: er-v1 (status final); policy sha256 recorded in the run manifest: 4946b3504759d5f9a61f60646e6ca9d6cd27b0dd6ef26e57a1729c2fb32807ac; overridden from: none.
- Run: run:v1:9f96c70b5115294dcc9d3b2d1d4438b9, code version phase05-1279c37-02-dirty, decision time 2026-11-14T00:00:00Z; name key author-name-key/1; candidate feature set candidate-features/1-provisional.

## Researcher resolution

Definitions (positive = decision accepted and label MATCH): precision = accepted MATCH / accepted labeled MATCH or NON_MATCH; recall = accepted MATCH / all MATCH (a MATCH left rejected, unresolved or conflict is missed); F1 = harmonic mean; coverage = accepted or rejected / labeled MATCH or NON_MATCH; unresolved rate = unresolved / all labeled units (UNSURE included). UNSURE labels never enter precision, recall or coverage.

Precision and recall show a 95% interval: Wilson score (n = labeled units) for unweighted rows; stratified bootstrap percentile for the weighted estimate (marked "bootstrap").

| Set | Units | Precision (95%) | Recall (95%) | F1 | Coverage | Unresolved |
|---|---|---|---|---|---|---|
| All rows (unweighted) | 300 | 98.9% [94.2%, 99.8%] | 83.0% [75.0%, 88.9%] | 90.3% | 58.3% | 36.3% |
| Weighted estimate (bootstrap) | 300 | 100.0% [100.0%, 100.0%] (unreliable) | 95.3% [91.6%, 98.4%] (unreliable) | 97.6% | 92.5% | 4.3% |
| path deterministic | 106 | 100.0% [95.1%, 100.0%] | 94.9% [87.7%, 98.0%] | 97.4% | 91.9% | 0.0% |
| path exact_id | 34 | 100.0% [80.6%, 100.0%] | 80.0% [58.4%, 91.9%] | 88.9% | 48.5% | 8.8% |
| path llm | 7 | n/a n/a | n/a n/a | n/a | 0.0% | 100.0% |
| path probabilistic | 153 | 66.7% [20.8%, 93.9%] | 15.4% [4.3%, 42.2%] | 25.0% | 33.9% | 64.7% |
| family dblp-base-name | 122 | 100.0% [88.3%, 100.0%] | 90.6% [75.8%, 96.8%] | 95.1% | 45.6% | 53.3% |
| family dblp-base-name.coauthor | 10 | 100.0% [43.9%, 100.0%] | 100.0% [43.9%, 100.0%] | 100.0% | 66.7% | 20.0% |
| family dblp-prior.numbered-name | 30 | 100.0% [85.1%, 100.0%] | 84.6% [66.5%, 93.8%] | 91.7% | 84.6% | 0.0% |
| family initials-surname | 87 | 100.0% [86.7%, 100.0%] | 69.4% [53.1%, 82.0%] | 82.0% | 69.3% | 26.4% |
| family initials-surname.coauthor | 51 | 93.3% [70.2%, 98.8%] | 93.3% [70.2%, 98.8%] | 93.3% | 50.0% | 37.3% |
| evidence: dblp_numbered_name only | 4 | 100.0% [20.7%, 100.0%] | 50.0% [9.5%, 90.5%] | 66.7% | 75.0% | 0.0% |
| evidence: independent | 243 | 98.9% [94.2%, 99.8%] | 83.6% [75.6%, 89.4%] | 90.6% | 58.0% | 29.2% |

Weighted bootstrap 95%: coverage [90.1%, 94.5%], f1 [95.6%, 99.2%] (unreliable), precision [100.0%, 100.0%] (unreliable), recall [91.6%, 98.4%] (unreliable), unresolvedRate [4.3%, 4.3%] (design-determined)

- precision: bootstrap [100.0%, 100.0%] unreliable: zero-event strata hold 100.0% of the relevant population weight and 100.0% of the observed denominator weight (13 zero-event strata, largest first: path=deterministic;outcome=accepted;family=dblp-base-name;band=far 34.5% (22 denominator rows, observed 100.0%); path=deterministic;outcome=accepted;family=dblp-prior.numbered-name;band=far 28.0% (22 denominator rows, observed 100.0%); path=deterministic;outcome=accepted;family=initials-surname;band=far 22.3% (18 denominator rows, observed 100.0%); path=deterministic;outcome=accepted;family=initials-surname.coauthor;band=far 8.0% (11 denominator rows, observed 100.0%); path=exact_id;outcome=accepted;family=dblp-base-name;band=far 3.4% (7 denominator rows, observed 100.0%); 8 more in metrics.json). Zero-event bound [82.9%, 100.0%].
- recall: bootstrap [91.6%, 98.4%] unreliable: zero-event strata hold 59.4% of the relevant population weight and 39.3% of the observed denominator weight (48 zero-event strata, largest first: path=probabilistic;outcome=rejected;family=initials-surname;band=far 24.8% (no denominator row among 25 labeled); path=deterministic;outcome=accepted;family=initials-surname;band=far 13.5% (18 denominator rows, observed 100.0%); path=deterministic;outcome=rejected;family=dblp-base-name;band=far 5.9% (no denominator row among 12 labeled); path=deterministic;outcome=accepted;family=initials-surname.coauthor;band=far 4.8% (11 denominator rows, observed 100.0%); path=probabilistic;outcome=unresolved;family=dblp-base-name;band=near 2.1% (no denominator row among 44 labeled); 43 more in metrics.json). Zero-event bound [84.9%, 95.3%].
- f1: bootstrap [95.6%, 99.2%] unreliable, because precision or recall is. Zero-event bound [83.9%, 97.6%].
- coverage: bootstrap [90.1%, 94.5%] not flagged: zero-event strata hold 32.6% of the relevant population weight and 34.7% of the observed denominator weight (44 zero-event strata, largest first: path=deterministic;outcome=accepted;family=initials-surname;band=far 13.5% (18 denominator rows, observed 100.0%); path=deterministic;outcome=rejected;family=dblp-base-name;band=far 5.9% (12 denominator rows, observed 100.0%); path=deterministic;outcome=accepted;family=initials-surname.coauthor;band=far 4.8% (11 denominator rows, observed 100.0%); path=exact_id;outcome=accepted;family=dblp-base-name;band=far 2.0% (7 denominator rows, observed 100.0%); path=probabilistic;outcome=rejected;family=initials-surname.coauthor;band=far 1.5% (6 denominator rows, observed 100.0%); 39 more in metrics.json). Zero-event bound [91.8%, 93.4%].
- unresolvedRate: bootstrap [4.3%, 4.3%] design-determined: the unresolved rate depends only on the decision, never on a label, and every stratum is defined by decision outcome, so the weighted rate is fixed by the strata's population decision counts rather than estimated from labels; its narrow bootstrap interval reflects only the pooling of single-row strata and memberships whose decision differs from their stratum (m3), not label uncertainty. Zero-event bound [4.3%, 5.9%].

### Confusion matrix (unweighted rows)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 93 | 1 | 4 |
| rejected | 1 | 49 | 2 |
| unresolved | 12 | 59 | 38 |
| conflict | 6 | 26 | 9 |

### Confusion matrix (weighted: estimated population decisions)

| Decision | MATCH | NON_MATCH | UNSURE |
|---|---|---|---|
| accepted | 11155611.5 | 20.0 | 413734.3 |
| rejected | 1514.0 | 6427874.6 | 232357.4 |
| unresolved | 65198.2 | 482811.1 | 325548.7 |
| conflict | 487213.0 | 392997.9 | 264098.3 |

## Part 3: addendum
