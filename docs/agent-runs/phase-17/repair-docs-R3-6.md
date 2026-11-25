# documentation-engineer, Phase 17 session 3, repair round R3 (P17-M-04 closure)

## Changes (exact old and new text)

1. `README.md:327`
   - old: `` `scripts/README.md:59` still reads `runtime: untested`, a recorded open item, P17-M-04); ``
   - new: `` P17-M-04 is closed: `scripts/README.md:59` now records the runs at E5.40 (dry run) and E10, both exit 0); ``

2. `reports/final-report.md:612`
   - old: ``Its status line in `scripts/README.md:59` still reads `runtime: untested`; that line belongs to infra-engineer, is not changed after the release candidate, and is a recorded open item (P17-M-04).``
   - new: ``Its status line in `scripts/README.md:59` was updated by infra-engineer before the new release candidate (repair round R3) and now records both runs with exit 0, so P17-M-04 is closed.``

3. `docs/runbook.md:191` (task 3 hit, same now-false fact, documentation-engineer territory)
   - old: `` `scripts/README.md:59` still reads `runtime: untested`, a recorded open item, P17-M-04, owned by infra-engineer and not changed after the release candidate): ``
   - new: `` P17-M-04 is closed: `scripts/README.md:59`, owned by infra-engineer, now records the runs at E5.40 (dry run) and E10, both exit 0): ``

Rest of each line unchanged. Verified against `scripts/README.md:59`: `runtime: ran at Phase 17 runbook steps E5.40 (dry run) and E10, both exit 0`.

## Task 3 grep hits (README.md, docs/ excluding docs/agent-runs/, reports/)

Pattern `P17-M-04|runtime: untested`:
- README.md:327 (release-manifest clause) - fixed (change 1).
- reports/final-report.md:612 (release-manifest sentence) - fixed (change 2).
- docs/runbook.md:191 - fixed (change 3).
- Not changed, about other scripts (env.sh, probes.sh, E-01, E-03), not release-manifest.sh, still accurate: README.md:327 (general preamble "each change is `runtime: untested` until the human runs it", and `probes.sh` clause), reports/final-report.md:612 (first bullet part), reports/final-report.md:648, docs/runbook.md:231, docs/runbook.md:232, docs/emulator.md:48, :195, :205.
- After the edits, no file in scope calls P17-M-04 open or says release-manifest.sh is `runtime: untested`.

## Flagged, not changed (outside the literal scope; orchestrator decision)

- README.md:327, archive-evidence clause: "`scripts/README.md` is not changed after the release candidate and does not record this run". Since R3, `scripts/README.md` was changed (line 59) before the new release candidate. The clause is about the E12r.6 archive-evidence run, which is a different fact; I did not verify whether scripts/README.md now records that run. Possibly stale wording; route if wanted.

## Diffs

- diff-docs-R3-4.txt: `git -C <repo> diff README.md reports/final-report.md` (86 lines). Note: both files already had uncommitted changes from earlier session-3 steps, so this diff against HEAD includes those, not only R3.
- diff-docs-R3-5.txt: `git -C <repo> diff docs/runbook.md` (31 lines), same caveat.
