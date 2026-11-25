# infra-engineer repair report: Phase 17 session 3, repair round R3 (second pass)

Source findings: review-report-R3-final-2.md, Findings rows 2 (scripts/README.md:5) and 3 (scripts/release-manifest.sh:12 and :148). Row 1 (README.md:327, docs/runbook.md:189) belongs to documentation-engineer and was not touched.

## Dependency grep (done before editing)

Commands:
- `grep -rn "runtime: untested\|shellcheck clean; runtime\|Status (Phase 17)" scripts tests`
- `grep -rn -e "release-manifest" -e "untested" -e "Status: static" -e "shellcheck clean" scripts/check.sh tests/contracts/src`

Findings:
- Every "runtime: untested" match under scripts/ is a status comment or a `--help` Status string inside a script, or prose in scripts/README.md. None is a pattern that a probe matches on. No match under tests/.
- scripts/check.sh probes release-manifest.sh (lines 948-984). The only `--help` probe is line 948, `probe help-release-manifest 0 '^Usage: scripts/release-manifest\.sh'`. It matches the Usage line, not the Status line. The other probes match refusal messages (`exit=2`, `unknown argument`, `marker file not found`, `Git working tree`, `differs from this tree`, `mandatory jar missing`), and none of those changed.
- Result: nothing depends on the changed text, so the edits went ahead.

## Changes (exact old and new text)

### scripts/README.md:5 (only the tail of the line changed; everything before it is unchanged)

Old tail:
```
Phase 17 changes (release-manifest.sh, the env.sh `SCHOLARCHAIN_CFG_*` sweep, probes.sh P16-B-03, deploy.sh help text E-01, archive-evidence.sh E-03) are `runtime: untested`.
```
New tail:
```
Phase 17 changes (the env.sh `SCHOLARCHAIN_CFG_*` sweep, probes.sh P16-B-03, deploy.sh help text E-01, archive-evidence.sh E-03) are `runtime: untested`; release-manifest.sh (new in Phase 17) ran at Phase 17 runbook steps E5.40 (dry run) and E10, both exit 0.
```

### scripts/release-manifest.sh:12-13 (header comment)

Old:
```
# Status (Phase 17): static: shellcheck clean; runtime: untested; human
# execution required.
```
New:
```
# Status (Phase 17): static: shellcheck clean; runtime: ran at Phase 17
# runbook steps E5.40 (dry run) and E10, both exit 0; human execution required.
```
The line count is unchanged (two comment lines).

### scripts/release-manifest.sh:148 (--help text inside the `cat <<'EOF'` heredoc that opens at line 57)

Old:
```
Status: static: shellcheck clean; runtime: untested; human execution required.
```
New:
```
Status: static: shellcheck clean; runtime: ran at Phase 17 runbook steps E5.40 (dry run) and E10, both exit 0; human execution required.
```
The heredoc delimiter is quoted (`'EOF'`), so the new parentheses are literal text. The new words contain no `$`, backtick or `EOF`. No command, variable, condition, exit code or quoting changed.

## Validation

- `shellcheck /Users/brian/Developer/repos/scholarchain/scripts/release-manifest.sh`: no output, exit 0.
- `git -C /Users/brian/Developer/repos/scholarchain diff scripts/README.md scripts/release-manifest.sh > .../phase17-reports/diff-scripts-R3.txt`: exit 0, 46 lines. The diff also contains the earlier R3 change to scripts/README.md:59.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 17 session 3, repair round R3 (instructor-approved): scripts/README.md:5 and scripts/release-manifest.sh:12, :148 runtime wording
FILES CREATED: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/73f10a2e-9b01-4d44-898e-9399e20392db/scratchpad/phase17-reports/diff-scripts-R3.txt; this report
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/scripts/README.md (line 5; line 59 was changed earlier in R3); /Users/brian/Developer/repos/scholarchain/scripts/release-manifest.sh (lines 12-13, 148)
COMMANDS RUN: the dependency greps above (read-only); grep for heredoc openers in release-manifest.sh; shellcheck scripts/release-manifest.sh; git diff into the scratchpad. No script was run.
TEST RESULTS: not applicable (comment, help text and documentation only; no sbt-relevant source)
VALIDATION RESULTS: shellcheck exit 0, no findings; no probe depends on the changed text
ASSUMPTIONS: The runtime wording copies scripts/README.md:59, which the orchestrator gave as evidenced by run-log rows E5.40 and E10. Line 5 now names release-manifest.sh as "new in Phase 17" so the file still says what kind of change it was.
RISKS: The `--help` output changed in text only, so release-manifest.sh's content hash differs from the release candidate b4d8f12. A new release candidate is needed, as the reviewer noted. The script's logic did not change, so E5.40 and E10 still describe the same behavior.
MANUAL ACTION REQUIRED: Cut the new release candidate after these edits. documentation-engineer's minor (README.md:327, docs/runbook.md:189) is still open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused check of diff-scripts-R3.txt; human ratification

INTERFACE HANDOFF
- Files changed: scripts/README.md (line 5), scripts/release-manifest.sh (lines 12-13 comment, line 148 help string).
- Public signatures: unchanged. No option, exit code, output marker (STEP/INFO/SUMMARY/MANIFEST-FOLDER/RESULT) or Usage line changed. The check.sh probe `help-release-manifest` (`^Usage: scripts/release-manifest\.sh`) is not affected.
- Fixtures: none.
- Thread-safety: not applicable.
- Limits: unchanged.
