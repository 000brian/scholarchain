STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), repair G-12 (class 2, script defect), D6 hadoop-inputs refusal exit 4; HEAD 169680c062212b84610167295d9100e692627e2d
FILES CREATED: none in the repository (this report only)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/scripts/run-bounded-pipeline.sh (sha256 after the change 0a23263e408213e64c50b0545b5442fc680e1bfb199daf475fbbd225dbb27b72; +21 -2 lines)
COMMANDS RUN: read-only grep and git diff over scripts/**; shellcheck --version (0.11.0); shellcheck -x -P scripts scripts/run-bounded-pipeline.sh; shasum -a 256 scripts/run-bounded-pipeline.sh. No bash, script run, aws, sbt or terraform.
TEST RESULTS: none run (agents may not run bash). The human runs the one-line test below.
VALIDATION RESULTS: shellcheck -x -P scripts scripts/run-bounded-pipeline.sh: no output, exit 0.
ASSUMPTIONS: S3 bucket names cannot contain & or backslash; LOGICAL_DATA_PREFIX is 's3://{data-bucket}/' (run-bounded-pipeline.sh:52).
RISKS: The construct search is grep-based (patterns listed below), not a full parse. The fix is not exercised by an automated test; the one-liner below is a copy of the helper logic, not the script itself.
MANUAL ACTION REQUIRED: Run the one-line test under /bin/bash (3.2) and, if available, a bash 5; review the diff; commit; then rerun D6 with the same command.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the diff, human runs the one-line test, human commit, D6 rerun.

## Diagnosis (human hypothesis confirmed)

The bash manual, shopt option compat42: "If set, bash does not process the replacement string in the pattern substitution word expansion using quote removal." Quote removal of the replacement was introduced in bash 4.3 (bash CHANGES for bash-4.3, compat42 added to keep the old behavior). On bash 3.2, `"${a//"${LOGICAL_DATA_PREFIX}"/"s3://${DATA_BUCKET}/"}"` turns `s3://{data-bucket}/raw/dblp/.../dblp.dtd` into `"s3://BUCKET/"raw/dblp/.../dblp.dtd` (literal double quotes kept; the quoted pattern part did match). Then `rest="${real_a#*s3://}"` is `BUCKET/"raw/...`, which does not match `"${DATA_BUCKET}/${P_RAW}/"?*`, giving exactly the observed die 4 at the old line 1233. Bash 5 strips the quotes, so the script passed wherever bash 5 ran it.

## Per-item status

1. FIXED scripts/run-bounded-pipeline.sh:681-698: new helper `replace_literal VAR TEXT FROM TO` (literal replace-all using only quoted-pattern removal `${s%%"FROM"*}` and `${s#*"FROM"}`, result set with `printf -v`, bash 3.1+). No replacement part is used at all, so neither bash 3.2 quote keeping nor bash 5.2 patsub_replacement (& in an unquoted replacement) can affect it. The pattern stays a quoted literal, so {data-bucket} and bucket names are never globbed.
2. FIXED scripts/run-bounded-pipeline.sh:1244 (was 1225): `replace_literal real_a "${a}" "${LOGICAL_DATA_PREFIX}" "s3://${DATA_BUCKET}/"`.
3. FIXED scripts/run-bounded-pipeline.sh:1258 (was 1239): `replace_literal logical_a "${real_a}" "s3://${DATA_BUCKET}/" "${LOGICAL_DATA_PREFIX}"`.

## Search of scripts/** and scripts/lib/** (no further fix needed)

Quoted-replacement pattern `${x//pat/"rep"}`: only the two lines above. Other pattern substitutions, all safe on 3.2 and 5 (quoted pattern, unquoted literal replacement without & or quotes, or empty replacement):
- scripts/archive-evidence.sh:422 `${line//"${account}"/<account>}`
- scripts/lib/identity.sh:263, 271, 317, 320, 323, 335, 338, 341 (`/<account>`, `/<data-bucket>`, `/<vector-bucket>`)
- scripts/check.sh:587, 588 `${line//[^(]/}`, `${line//[^)]/}` (empty replacement)

Bash-4-only constructs searched (declare/local/typeset -A, declare/local -n, mapfile, readarray, |&, ;&, ;;&, wait -n, ${x,,} ${x^^} ${x,} ${x^}, ${x@Q} and other @ operators, negative array indices, coproc, EPOCHSECONDS/EPOCHREALTIME, BASH_ARGV0, globstar/lastpipe/inherit_errexit, declare -g, [[ -v ]], test -v, &>>, printf %(...)T): none found. Only hits: scripts/lib/identity.sh:12 (a comment saying no mapfile) and scripts/lib/identity.sh:305 `${a: -4}` (negative substring offset, supported in bash 3.2; negative length would be 4.2+, not used).

Nothing on the D6-D18 path is known to break on 3.2 after this fix.

## Required bash

All scripts use `#!/usr/bin/env bash` (first bash on PATH: Homebrew bash 5 if installed, else macOS /bin/bash 3.2). scripts/README.md:11 already states "bash | 3.2 or later (macOS /bin/bash works)", and scripts/lib/identity.sh:11 states bash 3.2 compatibility. With this fix that claim holds for the hadoop extra-arg path; README needs no change and must NOT say "bash >= 4". (No root README.md exists.)

## One-line test (zsh-safe)

/bin/bash -c 'B=example-bucket; L="s3://{data-bucket}/"; a="${L}raw/x"; o="${a//"$L"/"s3://$B/"}"; echo "bash=$BASH_VERSION old=$o"; rl(){ local v="$1" s="$2" f="$3" t="$4" r=""; while [[ "$s" == *"$f"* ]]; do r="$r${s%%"$f"*}$t"; s="${s#*"$f"}"; done; printf -v "$v" "%s" "$r$s"; }; rl n "$a" "$L" "s3://$B/"; rl b "$n" "s3://$B/" "$L"; echo "new=$n back=$b"'

Expected on macOS /bin/bash 3.2:
bash=3.2.57(1)-release old="s3://example-bucket/"raw/x
new=s3://example-bucket/raw/x back=s3://{data-bucket}/raw/x

(The version string may differ slightly; the point is the literal double quotes in old=.) On bash 5 the first line shows old=s3://example-bucket/raw/x and the second line is identical.

## INTERFACE HANDOFF

Interface: no. Command-line options, exit codes, step markers, records and outputs of run-bounded-pipeline.sh are unchanged; only the internal string replacement for --hadoop-extra-arg changed. New internal function replace_literal (not exported, not in scripts/lib). Thread-safety: n/a. Limits: FROM empty means no replacement.

## VALIDATION EVIDENCE (infra)

- terraform fmt / validate / plan: not applicable (no Terraform change); plan checksum: none.
- shellcheck 0.11.0: `shellcheck -x -P scripts scripts/run-bounded-pipeline.sh` -> no findings, exit 0.
- IAM changes: none. Stateful-resource actions: none. Cost: none. New environment variables: none.
