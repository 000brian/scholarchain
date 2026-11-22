# ScholarChain answer rating rubric, version 1 (`rubric-v1`)

Owner: `experiment-engineer`. Evidence kind (c), human judgment (Phase 16 decision 3). Used with the rating sheet that `evaluate.py ratings-sheet` writes to `experiments/evaluation/raw/ratings/RUN_ID/sheet.csv`. The sha256 of this file is pinned in the signed evaluation suite; any change makes a new rubric version and a new suite version (decision 17, E3).

## How to rate

1. Open `sheet.csv` only. Do not open `key.csv`, the source answer files, the verifier output, the evidence bundles or any earlier report while rating. `key.csv` maps each answer key to its source, and you need it only after all ratings are done.
2. Rate without looking at verification statuses. The sheet shows only the question and the answer text. If an answer text itself prints a status word (for example "verified" or "unsupported"), ignore that word: rate what the answer says, not what it says about itself.
3. Rate every row, however few rows there are. Rate each answer on its own, not against the other answers. Use whole numbers only.
4. Fill `usefulness_1_to_5` and `clarity_1_to_5`, or write `yes` in `not_rateable` and leave both scores empty. Use `rater_note` for one short reason when a score is 1 or 2, or when a row is not rateable.
5. Optional column `dblp_names_exist`: check the people and papers the answer names on dblp.org and write `yes` (all found), `partly` (some found), `no` (none found) or `not checked`. This is the only check of kind (b) on answer content; it says that the names exist, not that the answer's claims about them are true. Leave it as `not checked` rather than guessing.
6. Do not change the question or the answer text, and do not add or remove rows. Save the file as CSV with the same columns.

## Usefulness (does the answer help someone who asked this question?)

| Score | Anchor |
|---|---|
| 5 | Answers the question fully and directly; a researcher could act on it without asking again. |
| 4 | Answers the question; a small part is missing or needs a follow-up question. |
| 3 | Answers part of the question, or answers it in a way that needs real extra work to use. |
| 2 | Touches the topic but does not answer the question that was asked. |
| 1 | Does not help: off topic, empty of content, or a refusal where an answer was possible. |

A correct abstention ("the data cannot answer this") on a question that cannot be answered counts as useful: score it on how clearly it says what is missing.

## Clarity (can the answer be understood on first reading?)

| Score | Anchor |
|---|---|
| 5 | Clear and well ordered; every number and name is easy to find and its time window is stated. |
| 4 | Clear, with one place that needs a second reading. |
| 3 | Understandable with effort; ordering, wording or missing time windows get in the way. |
| 2 | Hard to follow; the main point is buried or ambiguous. |
| 1 | Cannot be understood. |

## Not rateable

Write `yes` in `not_rateable` when the answer text is truncated, is not text meant for a reader (for example raw JSON or an error trace), or is in a language you cannot judge. A not-rateable row is reported in the count of rows, never dropped and never given a score.

## What these ratings are and are not

- They are one person's judgment (one rater, so no inter-rater agreement can be computed).
- They are not a measure of factual accuracy. Accuracy of answers is not independently measurable from the frozen evidence, except the optional dblp.org existence check.
- The completed sheet is an output of the evaluation: it is frozen and hashed at E12 and is not part of the suite.
