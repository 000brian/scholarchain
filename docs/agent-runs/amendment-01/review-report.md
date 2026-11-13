# Amendment 01 — code-reviewer report

> Orchestrator note: code-reviewer's final report, copied verbatim. Its work order asked it to review the full working-tree diff (7 files changed, 57 insertions(+), 40 deletions(-), against HEAD b4e474e; the diff is in repair-01.md section 5) against docs/agent-runs/amendment-01/prompt.md and repair-01.md. The review was read-only; the reviewer wrote no files.

---

**Verdict: APPROVE.** The amendment makes every ratified change (prompt items 1–5 and repair items 1–6) and adds nothing material beyond them. No floor invariant is weakened. I found no critical or major problems; there are 10 minor findings, all wording drift or risks the human should accept or schedule.

## What I checked

**1. Scope.** Every ratified item is present:
- **Item 1 (ledger):** guard ledger path, docs/agents.md row and rule 3, and the dependency-updater role file.
- **Item 2 (Scala deviation):** CLAUDE.md stack line and module table, and the build-engineer, domain-engineer and spark-engineer laws. All four cite `docs/agent-runs/phase-02/prompt.md`.
- **Items 3–5:** runtime targets (CLAUDE.md:15), `.sbt/**` ownership (docs/agents.md row, guard line 93), and the formatter note (CLAUDE.md:43).
- **Repair items 1–6:** all done, including the corrected guard comment, which the human authorized.

There are a few small additions beyond the ratified text (findings 1–2).

**2. Agreement with the actual build.**
- The project wiring in build.sbt matches CLAUDE.md:21–29. `hadoop`, `entityResolution`, `storage`, `rag` and `services` use the Scala 3 build of core. `sparkAnalytics` uses `core2_13` (build.sbt:244). `contractTests` wires exactly core, hadoop, entityResolution, storage, rag and services (build.sbt:320).
- The project ID `core2_13` matches the comment at build.sbt:203–204 and the cross-build at line 213.
- Java 17 is confirmed: the ledger's `version.java=17`, the `-release` options (build.sbt:63, 99, 101) and the temurin 17 container image (build.properties:46).
- uPickle and MUnit are confirmed (build.sbt:189, 108; build.properties:32–33).
- The formatter paths and dialects match `.scalafmt.conf` lines 12–18.
- The ledger keys `version.scalafmt` and `image.java-runtime` exist. What `ledgerCheck` checks (build.sbt:411–421) matches the corrected guard comment.
- `docs/agents.md`, the guard's ownership map and the role files agree on substance.

**3. Security.**
- **Ledger exemption (guard lines 338–343):** it applies only to `dependency-updater` and requires an exact string match after the path is made relative.
  - Paths containing `..` are rejected first (line 310), and secret paths before that.
  - Any other spelling is refused, because it falls through to the ownership map, which maps it to build-engineer or to unowned. That covers `project//build.properties`, `Project/Build.properties` (on the case-insensitive filesystem) and absolute paths outside the project directory.
  - dependency-updater has no Write tool, and the validator enforces that (validate-agent-files.sh:112–113).
- **Ownership map:** the new `.sbt/*` pattern needs a slash, so it doesn't overlap `.sbtopts` or any other pattern. The `.claude/*` patterns come earlier in the map.
- **Unchanged:** `.claude/settings.json` and the other hooks.
- **Pre-existing gaps:** symlinks and shell writes are not new; they come under the deferred item 8 (see RISKS).

**4. Leftover contradictions:** findings 4–8.

**5. Evidence records.**
- The probe totals add up: 12+5+28+1+5 = 51 for the original run, and 51+20 = 71 after the repair.
- The repair record says spark-engineer.md is unchanged this round, and the diff confirms it.
- The orchestrator says plainly that it did not re-run the probes.
- One overstatement: finding 9.

## Findings

| # | Severity | File:line | Failure input | Impact | Owner | Suggested fix |
|---|---|---|---|---|---|---|
| 1 | minor | CLAUDE.md:22 | The `core2_13` row adds "used only by `sparkAnalytics`" | A new constitutional boundary rule that prompt.md item 2 ("list the extra sbt projects") didn't state. It matches the comment at build.sbt:203–204. | human (authority), then factory-engineer | Ratify it explicitly, or reword it as a description ("the Scala 2.13 build of `core`") |
| 2 | minor | CLAUDE.md:29 | The `contractTests` row describes today's wiring ("Phase 2 build wires …") in the "May depend on" column | It reads as a fixed allow-list. The Phase 2 human gate (finding F4) plans more wiring in Phase 11, which would contradict the table without another amendment. | human, then factory-engineer | Ratify the allowed set as a rule, or say "any project; test project only" |
| 3 | minor | .claude/hooks/guard-dangerous.sh:72–78; .claude/agents/dependency-updater.md:19 | dependency-updater edits the `image.java-runtime` digest or `version.scalafmt` with the Edit tool on the allowed ledger path | The guard can't stop it, because it checks paths, not values. Nothing mechanical catches a digest swap: `Ledger.image` (Ledger.scala:43–48) only checks the format. The scalafmt case is caught by `ledgerCheck`. The base image is a supply-chain input that only role rules and code review protect. | human (accept the risk or ratify the hardening proposed as factory-engineer's open item B) | Record the accepted risk in the amendment's human gate, or ratify a check on the ledger keys after each edit |
| 4 | minor | .claude/agents/build-engineer.md:24 | "No module depends on `services`" | Contradicts the new CLAUDE.md:36 wording, since sbt calls `contractTests` a module. The repair report flags it as open item A, outside the ratified scope. | factory-engineer (next amendment) | "No production module depends on `services`" |
| 5 | minor | .claude/agents/build-engineer.md:37 | "compatibility risks (Scala 3 with Hadoop and Spark)" | Leftover Scala 3-only wording after the deviation | factory-engineer (next amendment) | "(Scala 3 and 2.13 with Hadoop and Spark)" |
| 6 | minor | .claude/agents/dependency-updater.md:3 | The description says "the single approved dependency version ledger … patch and minor version bumps" and has no carve-out | Routing descriptions are now lopsided: build-engineer's names the exceptions; dependency-updater's doesn't. Repair item 6 didn't list the description. | factory-engineer (next amendment) | Add "except version.scalafmt and image.java-runtime (build-engineer)" |
| 7 | minor | build.sbt:274; project/build.properties:7–9, 16–18 | The comment "Nothing depends on services." contradicts `contractTests` at build.sbt:320. The ledger header's ownership note lacks the two exceptions and names only emr-spark-8.1.0 as the target, not EMR 7.x for MapReduce. | Build comments disagree with the newly ratified rules | build-engineer | Update the comments to match CLAUDE.md:15, CLAUDE.md:36 and docs/agents.md rule 3 |
| 8 | minor | CLAUDE.md:43 | "Scala 3 by default" | `.scalafmt.conf:20–25` formats `project/*.scala` with the Scala 2.12 dialect and `*.sbt` with the sbt1 dialect, so the bullet is incomplete | factory-engineer | Add "(build definitions use the sbt and Scala 2.12 dialects)", or say "application sources" |
| 9 | minor | docs/agent-runs/amendment-01/repair-01.md:109 | "The three documents now agree word for word." | Overstated. They agree on substance, but the wording differs (for example docs/agents.md:53 "changed only by `build-engineer`" vs dependency-updater.md:19). | human (evidence record) | Note the overstatement in the human gate; no change to the agent's verbatim text |
| 10 | minor | CLAUDE.md:45 | "until `build.sbt` exists, no Scala gate can pass" | Outdated since Phase 2, but it was outside this amendment's scope and wasn't changed | factory-engineer (next amendment) | Remove or reword the conditional |

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Review of Amendment 01 (constitutional change, including repair round 1), before human ratification. Read-only.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: Not run (read-only reviewer). The probe totals reported by factory-engineer (51/0 original, 71/0 after repair) add up line by line in repair-01.md:150-238 and agent-report.md:69-81; I did not re-run them.
VALIDATION RESULTS: Every claim checked against the files. build.sbt:205-324 (project wiring, core2_13, contractTests), project/build.properties:20-46, project/Ledger.scala:50-80, project/plugins.sbt:22-24, .scalafmt.conf:4-25, .gitignore:5, guard-dangerous.sh:72-107 and 288-354, verify-tests-ran.sh:59-64, validate-agent-files.sh:112-124, settings.json deny rules, all modified role files, CLAUDE.md:1-46, phase-02 prompt.md and human-gate.md.
ASSUMPTIONS: (1) The diff in the work order matches the working tree; the current file contents agree with it. (2) The project ID core2_13 follows sbt-projectmatrix naming, as the comment at build.sbt:203 states; not confirmed by running sbt. (3) "Hadoop 3.4.x on EMR 7.x" is the human's statement (human-gate.md:14); I can't verify it from the repository.
RISKS: (1) Unchanged since Phase 1 (deferred item 8): any agent with Bash can write ledger or territory files with sed or ln -s, bypassing the path guard. That includes replacing project/build.properties with a symlink that a later Edit by dependency-updater would write through. The amendment doesn't make this worse. (2) .sbt/** is gitignored, so build-engineer writes there never appear in a diff. If sbt-projectmatrix loads *.sbt files from row base directories under .sbt/matrix/, code placed there would run during sbt check without being reviewable. I could not verify this behaviour from the repository files; it should be confirmed or ruled out at the next build review. (3) Finding 3: the value-level ledger rules have no mechanical enforcement.
MANUAL ACTION REQUIRED: Human: decide on findings 1, 2, 3 (ratify, reword, or accept the risk) and record them in the amendment-01 human gate. Then ratify, commit and restart the session.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human constitutional ratification of Amendment 01. Then route findings 4-6, 8, 10 to factory-engineer and finding 7 to build-engineer in later work orders.
```

VERDICT: APPROVE

**Findings** (all minor; full evidence in the table above):
1. CLAUDE.md:22 adds an unratified "used only by `sparkAnalytics`" rule. Owner: human, then factory-engineer.
2. CLAUDE.md:29 records today's `contractTests` wiring as if it were an allow-list. Owner: human, then factory-engineer.
3. guard-dangerous.sh:72–78 and dependency-updater.md:19: the `image.java-runtime` and `version.scalafmt` exclusions aren't enforced mechanically. Owner: human (accept the risk or ratify hardening).
4. build-engineer.md:24 still says "No module depends on `services`". Owner: factory-engineer.
5. build-engineer.md:37 has leftover "Scala 3 with Hadoop and Spark" wording. Owner: factory-engineer.
6. dependency-updater.md:3: the description has no carve-out. Owner: factory-engineer.
7. build.sbt:274 and the build.properties:7–9, 16–18 comments are out of date. Owner: build-engineer.
8. CLAUDE.md:43: the formatter note leaves out the build-definition dialects. Owner: factory-engineer.
9. repair-01.md:109: "agree word for word" is overstated. Owner: human.
10. CLAUDE.md:45: the "until `build.sbt` exists" condition is out of date. Owner: factory-engineer.

Files referenced:
- /Users/brian/Developer/repos/scholarchain/CLAUDE.md
- /Users/brian/Developer/repos/scholarchain/docs/agents.md
- /Users/brian/Developer/repos/scholarchain/.claude/hooks/guard-dangerous.sh
- /Users/brian/Developer/repos/scholarchain/.claude/agents/build-engineer.md
- /Users/brian/Developer/repos/scholarchain/.claude/agents/dependency-updater.md
- /Users/brian/Developer/repos/scholarchain/build.sbt
- /Users/brian/Developer/repos/scholarchain/project/build.properties
- /Users/brian/Developer/repos/scholarchain/project/Ledger.scala
- /Users/brian/Developer/repos/scholarchain/.scalafmt.conf
- /Users/brian/Developer/repos/scholarchain/docs/agent-runs/amendment-01/repair-01.md
