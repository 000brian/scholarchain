"""Retrieval metrics per mode (experiment E).

Owner: experiment-engineer. Python 3 standard library only.
Run: python3 -I experiments/derived/retrieval_modes.py [--manifest ID ...] [--fixtures]
runtime: untested

Reads, per attempt folder (common.py has the shared layout):
  retrieval-modes.csv  the per-mode table the E collector (experiments/collectors/
                       e-retrieval-modes.sh, step 6c) extracts from rag's report with jq:
                       mode,k,recall,mrr,ndcg,questions_scored,errors,empty,abstentions,
                       by_design_weak,latency_p50_ms,latency_p95_ms,universe_size,
                       not_comparable,label
                       (mode graph-only, vector-only or fused; one row per mode and K;
                       an empty cell is missing, for example ndcg without graded labels)
Writes into out/MANIFEST_ID/: modes.csv, recall-ATTEMPT.svg, mrr-ATTEMPT.svg, inputs.csv,
missing.csv.

Rules (decision 7.4): the same universe and K values are the denominator of every mode;
with fewer than 15 questions in the universe every row is labelled exploratory; the fused
mode is labelled "experiment only; the production workflow has no fused ranking". These
plots have K on the x axis, not time: E has no scaling event (decision 3).
"""

import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

DEFAULT_MANIFESTS = ["p15-e-retrieval-modes"]
MODES = ["graph-only", "vector-only", "fused"]
FUSED_LABEL = "experiment only; the production workflow has no fused ranking"


def main():
    ctx = common.Context("retrieval", DEFAULT_MANIFESTS, "retrieval metrics per mode (E)")
    for manifest in ctx.manifests:
        out_rows = []
        for att in ctx.attempts(manifest):
            rows = att.csv("retrieval-modes.csv") or []
            ks = sorted(set(common.to_int(r.get("k")) for r in rows if common.to_int(r.get("k")) is not None))
            recall = {m: [] for m in MODES}
            mrr = {m: [] for m in MODES}
            for m in MODES:
                mrows = {common.to_int(r.get("k")): r for r in rows if r.get("mode") == m}
                if not mrows:
                    ctx.note_missing(manifest, att.name, "retrieval-modes.csv mode %s" % m, "no rows")
                for k in ks:
                    r = mrows.get(k)
                    if r is None:
                        recall[m].append(None)
                        mrr[m].append(None)
                        ctx.note_missing(manifest, att.name, "mode %s K=%d" % (m, k), "no row")
                        continue
                    universe = common.to_int(r.get("universe_size"))
                    labels = [x for x in [(r.get("label") or "").strip()] if x]
                    if universe is None or universe < 15:
                        labels.append("exploratory (universe %s < 15)" % ("missing" if universe is None else universe))
                    if m == "fused":
                        labels.append(FUSED_LABEL)
                    rc, mr = common.to_float(r.get("recall")), common.to_float(r.get("mrr"))
                    recall[m].append(rc)
                    mrr[m].append(mr)
                    out_rows.append([manifest, att.name, att.status(), m, k, common.fmt(rc, 4), common.fmt(mr, 4),
                                     common.fmt(common.to_float(r.get("ndcg")), 4), r.get("questions_scored", ""),
                                     r.get("errors", ""), r.get("empty", ""), r.get("abstentions", ""),
                                     r.get("by_design_weak", ""), r.get("latency_p50_ms", ""),
                                     r.get("latency_p95_ms", ""), "" if universe is None else universe,
                                     r.get("not_comparable", ""), "; ".join(labels)])
            if ks:
                cats = ["K=%d" % k for k in ks]
                for name, data in (("recall", recall), ("mrr", mrr)):
                    path = os.path.join(ctx.out_dir(manifest), "%s-%s.svg" % (name, att.name))
                    common.category_chart(path, "%s %s: %s@K per mode" % (manifest, att.name, name), cats,
                                          [(m, data[m]) for m in MODES], name, "K",
                                          notes=["fused: " + FUSED_LABEL,
                                                 "a mode that errors or returns nothing scores 0 for that question "
                                                 "(counted under errors or empty in modes.csv)"])
                    ctx.written.append(path)
        ctx.write_csv(manifest, "modes.csv",
                      ["manifest", "attempt", "status", "mode", "k", "recall", "mrr", "ndcg", "questions_scored",
                       "errors", "empty", "abstentions", "by_design_weak", "latency_p50_ms", "latency_p95_ms",
                       "universe_size", "not_comparable", "labels"], out_rows)
    ctx.finish()


if __name__ == "__main__":
    main()
