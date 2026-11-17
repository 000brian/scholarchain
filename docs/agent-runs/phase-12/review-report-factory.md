STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12, session 1, review of step F (decision 3 items 1–6); prompt sha256 5744ab73aa42946f1c556d1257a45031fd57868b75480de623879a9c73de7f56; HEAD 00fc238
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: none (no sbt in this phase; configuration-only diff)
VALIDATION RESULTS: I read the full diff (/private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/e761fe09-821e-4602-85d0-341fb74af34f/scratchpad/phase12-reports/step-f.diff) and checked it against the current /Users/brian/Developer/repos/scholarchain/.mcp.json, .claude/settings.json, .claude/agents/infra-engineer.md, .claude/agents/dataset-engineer.md, docs/agents.md and .claude/hooks/guard-dangerous.sh. I did not run `validate-agent-files.sh` or `jq empty` again because I have no Bash. The writer reports "72 checks passed, 0 failed" and jq exit 0. I read both JSON files and they look well-formed.
ASSUMPTIONS: Two things depend on Terraform CLI behaviour that cannot be checked inside the repository: what `terraform fmt` rewrites, and the option set of `validate` (`-json`, `-no-color`, `-no-tests`, `-test-directory`). I treat Claude Code `Bash(x)` rules without `:*` as exact matches, and rules with `:*` as prefix matches with compound commands split.
RISKS: See F-03 (the allow rule applies to the whole session). `mcp<2` is a version bound, not a pin (the writer noted this; decision 3 chose it).
MANUAL ACTION REQUIRED: The human ratifies the change, or decides on the minor findings. Then commit, restart, and run `/mcp`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification of step F, then restart before session 2.

Per-check results:
- Item 1 (.mcp.json:20, :35): `"--python", "3.12", "--with", "mcp<2"` comes right before each package name, in the decided order. The package pins @1.3.33 and @1.1.21 are unchanged. Both env blocks are unchanged, and the github and terraform entries are unchanged. Checked, none.
- Item 2 (docs/agents.md:143, :152, :154): The sort/uniq/sed -n claim is removed. The allow list it names (`sbt -batch`, grep, head, `tail -n`, ls, wc, shasum, cut) matches settings.json:22–29. The other claims in the row also hold: deny on `**/test-reports/**` (settings.json:133–134), bypass disabled (:4), `bash -n` not allowed. Both new rules are recorded word for word. The aws-api and aws-docs rows carry the `--python 3.12 --with 'mcp<2'` note, and the Pinned-to column is unchanged. The only accuracy issue is F-02.
- Item 3: No `bash -n` instruction remains in dataset-engineer.md (line 29 now says `shellcheck`) or infra-engineer.md (line 33). The writer merged "`bash -n` and `shellcheck`" into one `shellcheck`, which is correct. Checked, none.
- Item 4 (infra-engineer.md:33): The fmt-once-then-check order is present. The scan definition is `shellcheck` plus the code-reviewer checklist, and any Terraform scanner is run by the human. See F-01 for the bare `terraform validate` wording.
- Item 5 (settings.json:36–37): Exactly two allow rules were added, both word for word as decided. The ask, deny and hooks blocks are unchanged (the diff touches only allow lines). No init, plan, script or scanner rule was added.
- `:*` on the validate rule: The prefix ends in "course validate", so it cannot match another directory or another subcommand. The only extra arguments it admits are validate options (`-json`, `-no-color`, `-no-tests`, `-test-directory=`). None of them writes files. Validate runs only the provider plugins the human installed with `init`, and those are checked against the lock file. It does not widen beyond read-only validation. Checked, none.
- Exact fmt rule: It has no wildcard, so it cannot match extra flags or another path. A compound command is split, and each part needs its own match. Checked, none, apart from the session-wide scope in F-03.
- Ownership and territories: Unchanged. The tables in docs/agents.md and the agent `tools` fields are untouched. All five files belong to factory-engineer. Checked, none.
- Secrets and identifiers: None added. The profile name and region were already there and are not secrets. Checked, none.
- Nothing else changed: The 5 files and 9 insertions / 7 deletions match the items. Checked, none.

VERDICT: APPROVE

| ID | Severity | File:line | Failure input | Impact | Owner |
|---|---|---|---|---|---|
| F-01 | minor | /Users/brian/Developer/repos/scholarchain/.claude/agents/infra-engineer.md:33 | An infra-engineer that follows the role file literally runs `terraform validate` from the repository root. This is allow-listed through `Bash(terraform validate:*)` (settings.json:35), but agents never `cd`. | It validates the wrong directory (the repository root, which has no environment configuration), so the "validate" evidence is missing or misleading. The Phase 12 work order (Standing rules, line 55) prescribes the `-chdir` form, which mitigates this, but the timeless role file is the only procedure text and it names a form that cannot validate `infra/terraform/environments/**`. Fix: say "validate each environment with `terraform -chdir=<env dir> validate`". | factory-engineer |
| F-02 | minor | /Users/brian/Developer/repos/scholarchain/docs/agents.md:143 | The claim that `terraform fmt -recursive infra/terraform` "rewrites only whitespace in `.tf` files". | Per Terraform CLI behaviour (not checkable in the repository), fmt also rewrites `.tfvars` and `.tftest.hcl` files, and it does canonical syntax normalization: interpolation-only expressions are unwrapped and legacy quoted type constraints are rewritten. These changes keep meaning, but the stated limit is too narrow. The wording mirrors decision 3, so the human may accept it as is. | factory-engineer (human may accept) |
| F-03 | minor | /Users/brian/Developer/repos/scholarchain/.claude/settings.json:37 | Any agent with Bash, or the main session, runs `terraform fmt -recursive infra/terraform`. The guard (/Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh:1025, :1029 are its only terraform rules) checks shell write targets but has no territory check for this command. | A non-owner can rewrite files under `infra/terraform/**` without a prompt, which weakens the guard-enforced one-writer property for that tree. The change is formatting only, and `sbt fmt` already follows the same pattern, so impact is low. The docs row describes it as infra-engineer's territory without saying that other agents can also run it. Fix: either record that the rule applies session-wide as an accepted risk, or add a guard rule that limits the command to infra-engineer. | factory-engineer (human risk acceptance) |
