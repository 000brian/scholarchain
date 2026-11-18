# Review report for repair G-12

(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

Summary: The G-12 fix is correct on bash 3.2 and bash 5. Both call sites keep the old bash 5 behaviour, and the raw/ and curated/ prefix checks are unchanged. I found no other quoted replacement and no bash-4-only construct in scripts/** or scripts/lib/**. The human's one-line test copies the helper's loop logic and is safe to paste into zsh. I found one minor finding and no critical or major ones.

## Findings

| ID | Severity | file:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| G-13 | minor | scripts/run-bounded-pipeline.sh:689-698, compared with the one-liner in repair-D6-infra.md:43 | None at runtime. This is a test-coverage gap. The one-liner's `rl` is retyped by hand rather than taken from the script: it uses other local names (`v s f t r`), drops the `[ -n FROM ]` guard and writes `"%s"` instead of `'%s'`. It also tests only one occurrence of FROM. | A passing one-liner proves that the algorithm works on 3.2. It does not prove the shipped function works. There is no script test harness (`scripts/**` holds no tests apart from smoke-test.sh), so a later edit to `replace_literal` would not be caught. The owner states this risk openly in its report. | infra-engineer | no |

No critical or major findings.

## Check 1: the `replace_literal` helper (:689-698)

- **Quoted patterns on bash 3.2:** `${_rl_s%%"${_rl_from}"*}` and `${_rl_s#*"${_rl_from}"}` treat the quoted part as a literal on bash 3.2 and on bash 5. The script already depended on this on 3.2 before the fix:
  - `key_of` (:679) uses `"${1#"s3://${DATA_BUCKET}/"}"`, and the D6 run got past `head_meta` at :1241 with a non-empty sha.
  - The owner's diagnosis shows that the quoted pattern part of the old line did match.
- **Loop logic:** `%%"FROM"*` cuts the text at the first FROM, which gives the prefix. `#*"FROM"` drops everything up to and including the first FROM. The result is a left-to-right, non-overlapping replacement of every occurrence, the same as bash 5 `//`.
- **Edge cases:**
  - FROM occurring several times: the loop handles it.
  - Empty TEXT: the loop does not run and VAR is set to empty.
  - Empty FROM: the guard at :691 skips the loop, so it cannot loop forever.
  - TO containing FROM: TO is added to `_rl_out`, never to `_rl_s`, so it is never searched again and cannot loop forever.
- **Glob characters in FROM** (`{ } * ? [`, for example `{data-bucket}`): always quoted, so they match literally in both `[[ == ]]` and the removal patterns. Brace expansion never applies to expanded values.
- **`printf -v`:** it exists since bash 3.1 and is already used at :1067 (`add_json`). The format is `'%s'`, so a `%` in the value is safe.
- **Shadowing:** locals are prefixed `_rl_`. The callers pass `real_a` and `logical_a`, which are locals of `stage_hadoop` (:1234). Through dynamic scoping, `printf -v` sets the caller's locals. There is no collision. A caller passing `_rl_s` would break, but there is none.
- **`set -u`:** every local is assigned from `$1`..`$4`, and both call sites pass four arguments. `LOGICAL_DATA_PREFIX` (:52, readonly) and `DATA_BUCKET` are set before stage_hadoop.

## Check 2: the call sites (:1244 and :1258)

- **Semantics:** same as the old lines on bash 5, including replacing every occurrence. Bash 5.2's `&` handling in the replacement (patsub_replacement) no longer matters, because there is no replacement part.
- **Prefix checks:** the brace check (:1245-1247), the `"${DATA_BUCKET}/${P_RAW}/"?*` / `"${DATA_BUCKET}/${P_CURATED}/"?*` case (:1250-1253) and the second-`s3://` and `..` check (:1254-1256) are unchanged. They now see correctly unquoted text on 3.2, so they still allow only raw/ or curated/.
- **Two placeholders:** an argument with two `{data-bucket}` placeholders gets both replaced, and the second `s3://` is then refused at :1255.
- **Identifiers:** the error messages print only logical text. `extra_logical` records `logical_a`, the converted-back form.
- **Observation, unchanged by this diff and not a finding:** only `s3://BUCKET/` text is converted back. If an operator types the real bucket name without `s3://`, it is recorded as typed, the same as before the diff.

## Check 3: other bash-3.2 risks in scripts/**

I searched with the Grep tool.

- **Pattern substitutions:** apart from the two G-12 lines, the only ones are archive-evidence.sh:422; lib/identity.sh:263, 271, 317, 320, 323, 335, 338, 341, with unquoted literal `<...>` replacements that contain no `&` or quotes; check.sh:587-588, with an empty replacement. All are safe on 3.2 and 5. The owner's claim is confirmed.
- **Bash-4-only constructs:** none found. Searched: `${x,,}` `${x^^}`, `@Q` and the other `@` operators, `declare`/`local`/`typeset -A` and `-n`, `mapfile`, `readarray`, `|&`, `;&`, `;;&`, `wait -n`, `[[ -v`, `test -v`, `printf '%(…)T'`, `EPOCHSECONDS`/`EPOCHREALTIME`, `coproc`, `globstar`, `lastpipe`, `inherit_errexit`, `BASH_ARGV0`, `&>>`, `{a..b..c}`, zero-padded ranges and negative array indices. The only hits were comment lines (identity.sh:12, :505) and `ACTION_START_EPOCH`/`ACTION_END_EPOCH` in run-experiment.sh, which are ordinary variable names.
- **Constructs that work on 3.2:** `${a: -4}` (identity.sh:305), `+=()`, `${!1}` (:1066), `$'\t'` (:1196, :1367, :1394), `PIPESTATUS`, and unquoted `=~` regexes.
- **Empty arrays under `set -u`** (an error before bash 4.4): `A_HADOOP_EXTRA` is guarded at :1243. `cmd` (:1413) and `OPS_CMD` (:1608-1625) are always assigned before `"${…[@]}"` at :1418, :1631 and :1658, assuming `A_LOADER` was validated earlier (I did not read that validation). `SC_BUILD_INPUT_PATHS` and `SC_PLAN_INPUT_PATHS` (identity.sh:75, :153) are fixed lists.
- **Conclusion:** I found nothing on the D6-D18 path that is known to break on 3.2.

## Check 4: the one-line test

- **Logic:** the `rl` loop is the same as the helper (same `[[ == *"$f"* ]]`, `%%"$f"*` and `#*"$f"` steps, and `printf -v`). The differences are listed under G-13, and none of them changes the result for this input.
- **zsh safety:** yes. The whole program is in one pair of single quotes with no single quote inside and no `!`, so zsh expands nothing.
- **Expected output on /bin/bash 3.2:**
  ```
  bash=3.2.57(1)-release old="s3://example-bucket/"raw/x
  new=s3://example-bucket/raw/x back=s3://{data-bucket}/raw/x
  ```
- **Expected output on bash 5:** the first line ends `old=s3://example-bucket/raw/x`, and the second line is identical.
- **What it covers:** the back direction checks a FROM that contains braces. It does not check several occurrences (G-13).

## Files read

- **In full:** diff-G-12.txt; repair-D6-infra.md (both in the scratchpad phase14-reports folder).
- **In part:** scripts/run-bounded-pipeline.sh, lines 670-704, 1225-1274, 1395-1424, 1595-1664 and the grep hits at 52, 262-272, 313-319, 1063-1080.
- **Grep only, not read in full:** scripts/lib/identity.sh, check.sh, archive-evidence.sh, run-experiment.sh, cleanup.sh, deploy.sh, rollback.sh, package.sh, plan.sh, smoke-test.sh.
- **Not read:** docs/agent-runs/phase-14/prompt-s3.md (worked from the work order as quoted), and nothing under infra/terraform. No tfstate, .terraform, tfplan, plan.txt or apply.txt file was opened.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), decision 11 checklist, review of repair G-12
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none run (reviewer has no Bash). The human runs the owner's one-line test; the expected output is under check 4.
VALIDATION RESULTS: Owner reports `shellcheck -x -P scripts scripts/run-bounded-pipeline.sh` exit 0 with no findings (owner's claim, not run by me). I checked by reading that the fix behaves the same on bash 3.2 and bash 5.
ASSUMPTIONS: Bucket names contain no `&` or backslash, though the helper no longer depends on this. The working-tree copy of scripts/run-bounded-pipeline.sh matches the diff. `A_LOADER` is validated before build_ops_command; I did not read that validation.
RISKS: No automated regression test for `replace_literal` (G-13). The bash-4 construct search was done with grep patterns, not a full parse.
MANUAL ACTION REQUIRED: Run the one-line test under /bin/bash 3.2, and under a bash 5 if one is available, and compare with the expected output above. Then commit and rerun D6.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human runs the one-line test, human commit, D6 rerun. G-13 (minor) goes on the "proposed later" list for infra-engineer; it needs no repair round.

VERDICT: APPROVE
