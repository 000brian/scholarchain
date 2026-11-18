# Phase 14 session 2 step 3: hadoop-engineer (decision 7, DBLP DTD on EMR)

interface: yes (new driver option `--dblp-dtd PATH`, consumed by infra-engineer's README and the D6 hadoop-stage command; unknown driver options are now refused)

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt.md (sha256 b1f951683cc708c297d3e74d6b21332f5218324e4db0086d33307ec8bf8b88a2), decision 7; HEAD c4fbf62; wave closer: no (storage-engineer, step 4, runs the full `sbt check`)
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/test/scala/org/scholarchain/hadoop/ScholarChainHadoopSuite.scala
FILES MODIFIED:
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/ScholarChainHadoop.scala
- /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml (description text of `scholarchain.dblp.dtdPath` only; no value changed)
COMMANDS RUN:
- read-only: `shasum -a 256` (prompt, local dblp.dtd), `ls -l` (local DBLP folder), `grep -rn` (Grep tool missing in this session), `git status --short`, `git diff --stat`, `head -c` on the local DTD
- `sbt -batch "hadoop/testOnly org.scholarchain.hadoop.ScholarChainHadoopSuite org.scholarchain.hadoop.PipelineSuite org.scholarchain.hadoop.SettingsSuite"` (run 1)
- `sbt -batch "hadoop/testOnly org.scholarchain.hadoop.ScholarChainHadoopSuite"` (run 2, after a test-only fix)
TEST RESULTS:
- run 1: `Failed: Total 12, Failed 1, Errors 0, Passed 11`. PipelineSuite 4/4 passed, SettingsSuite passed, ScholarChainHadoopSuite 2 of 3 passed. The failure was in the test fixture, not the fix: the no-DTD control run had zero accepted J01 works, so J01 wrote no `works/` folder and J07 failed with `InvalidInputException` (pre-existing behavior, see "proposed later"). Fixture changed to two records (one with `&eacute;`, one ASCII-only).
- run 2: `Passed: Total 3, Failed 0, Errors 0, Passed 3` (ScholarChainHadoopSuite).
VALIDATION RESULTS: see VALIDATION EVIDENCE. No local bounded sample run on the full release (not in this work order; no `runMain` of a real job allowed).
ASSUMPTIONS:
- EMR's custom-JAR step with `MainClass` runs `hadoop jar JAR MainClass ARGS`, so `ScholarChainHadoop.main` receives `pipeline` first and `ToolRunner` parses the rest (`sfn.tf:226-228`). The test reproduces the argument order through `ToolRunner` locally; the EMR side is `runtime: untested` until D6.
- EMRFS reads the DTD by `s3://` URI on the cluster (the same file system the job already uses for `--dblp` and `--output`).
RISKS:
- Without `--dblp-dtd`, the EMR run SUCCEEDS but rejects every record with a named entity (`undefined_entity`), across the whole release (the check runs before the subset filter). The orchestrator's D6 check should read the J01 counter `scholarchain.reject-reason:undefined_entity` (expected 0, as in Phase 5: 99 rejects, all `no_authors`).
- `--max-upload-mb`: the release file is 1,116,142,729 bytes = 1,064.4 MiB, above the upload-input default of 1024 MiB (`scripts/run-bounded-pipeline.sh:87`, `:856-857`); the upload needs `--max-upload-mb 1100`.
- Unknown driver options are now refused. Only `sfn.tf` calls the driver; its documented extra args (`--dblp-fingerprint`, `--source`, `sfn.tf:32`) remain accepted.
MANUAL ACTION REQUIRED: none from me. The human uploads the DTD at D5 with the line in "Arguments for infra-engineer" (one extra `--stage upload-input` run).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: step 4 storage-engineer (full `sbt check` closes this change too), then R-1 code-reviewer; step 5 infra-engineer applies the arguments below to `scripts/README.md` (decision 9 item 5, case (b) with README lines; no Terraform change). This Hadoop code change moves the build commit, so session 3 needs `package.sh` before `--publish` (already planned, decision 12 D2).

## Answers (decision 7), with file:line

Hadoop paths are under `modules/hadoop/src/main/scala/org/scholarchain/hadoop/`.

1. **Required, optional or unused: optional in code, required in practice for the real release.** Key `scholarchain.dblp.dtdPath`, default empty (`modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml:152-162`; `common/Settings.scala:180`; empty counts as unset, `common/Settings.scala:22`). Every J01 map task reads it in setup (`dblp/DblpExtractJob.scala:122-134`). The external DTD's entities come first and the file's internal subset overrides them. It is fingerprinted: the DTD content goes into the J01 input fingerprint (`dblp/DblpExtractJob.scala:55-61`) and the path into the config fingerprint. The real release declares no entities internally. Its DOCTYPE names an external `dblp-2023-06-28.dtd` that dblp does not publish (`datasets/dataset-manifest.yaml:382-389`, `:431`), and non-ASCII characters are written as DTD named entities such as `&eacute;` (`:389`).
2. **Without it: the job does not fail, and no character is guessed, but records are rejected.** Each record that uses a named entity is rejected as `undefined_entity` (`dblp/DblpExtractJob.scala:182-185`, `dblp/DblpEntities.scala:115-116`, class doc `:9-16`). Expansion runs before the subset rule (`DblpExtractJob.scala:182` before `:204`), so this happens in and out of the subset. The accounting identity still holds; only the reject counters show the loss. The test proves it: control in `ScholarChainHadoopSuite`, 1 accepted and 1 `undefined_entity`.
3. **Path forms:** any Hadoop FileSystem path, because the DTD is opened with `new Path(p).getFileSystem(conf).open` (`DblpExtractJob.scala:123-124`) and fingerprinted with `JobRunner.fingerprintFiles` (`:59`). That covers `s3://` (EMRFS), `hdfs://`, and `file:///`. A bare path resolves against `fs.defaultFS`, which is HDFS on EMR, so do not use a node-local bare path there. Classpath resources are not supported. Size limit: `scholarchain.dblp.maxPrologBytes` = 1 MiB (`defaults.xml:146-151`, `DblpExtractJob.scala:126-129`); dblp.dtd is 13,134 bytes (`dataset-manifest.yaml:408`).
4. **Phase 7's source:** Phase 7 ran no Hadoop job. Its export read Phase 6's graph run (`docs/agent-runs/phase-07/prompt.md:76`), which read Phase 5's J09 attempt-02 (`docs/agent-runs/phase-06/prompt.md:53`). Phase 5 made `-D scholarchain.dblp.dtdPath=/Users/brian/scholarchain-data/dblp/release=2026-11-01/dblp.dtd` a required setting (`docs/agent-runs/phase-05/prompt.md:132`; command at `docs/agent-runs/phase-05/agent-report-hadoop.md:119`). Attempt-02 used the same command with code version `-02`, with identical counts (`:284-287`). Its J01 had 99 rejects, all `no_authors` (`:313-314`), so there were zero `undefined_entity` rejects. The local dblp.dtd sha256 `2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6` equals the manifest's `dtd_sha256` (`dataset-manifest.yaml:406`).
5. **Input and subset: confirmed.** The step's `--dblp` is the full `dblp-2026-11-01.xml.gz` (`scripts/run-bounded-pipeline.sh:93`, `infra/terraform/modules/workflow/sfn.tf:228`). The J01 mapper applies dblp-subset-v1 inside the job (`dblp/DblpExtractJob.scala:102`, `:204-207`) using the defaults `subset.enabled=true`, the 16 key prefixes, years 2015-2025, publtype `informal`, and elements article and inproceedings (`defaults.xml:102-133`). These match `datasets/dataset-manifest.yaml:33-46`. This is the same as Phase 5, which had no subset override.
6. **Why (a) does not work, and why (b) and not (c):** the state machine appends `extraArgs` after the application options: `['pipeline','-D','scholarchain.code.version=…','--output',…,'--dblp-snapshot',SNAP] ++ extraArgs` (`sfn.tf:228`). Hadoop's `GenericOptionsParser` (through `ToolRunner`, `pipeline/ScholarChainHadoop.scala:62`) stops at the first unknown option, `--output`. A trailing `-D scholarchain.dblp.dtdPath=…` therefore reaches `ScholarChainHadoop.parse` and is refused as `unexpected arguments: -D …` before any job runs. Test 1 of `ScholarChainHadoopSuite` passed and shows this. (c) would be a Terraform change plus a new runtime-off plan and apply. (b) needs neither.
7. **Fix (b):** `pipeline/ScholarChainHadoop.scala:29-37` adds the option `--dblp-dtd PATH` (single use, `:72-73`), which sets `scholarchain.dblp.dtdPath` on the job configuration before `Pipeline.run`. An empty value is refused, and so is a different non-empty `-D scholarchain.dblp.dtdPath`. Unknown `--options` are now refused (`:81`); before, they were silently ignored, so a misspelt DTD option would have produced only `undefined_entity` rejects. The usage text (`:65-67`) and class doc (`:10-25`) are updated. Run IDs with `--dblp-dtd P` equal those with `-D scholarchain.dblp.dtdPath=P`: the test re-runs with `-D` and every job is reused.

## Arguments for infra-engineer (README, D5 and D6), logical form

The DTD upload is a second `upload-input` run with the same source and snapshot. Its key is `raw/SOURCE/SNAPSHOT/BASENAME` (`run-bounded-pipeline.sh:858`):

```
bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --file 'LOCAL_DIR/dblp.dtd'
  -> INPUT-LOCATION: s3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd
bash scripts/run-bounded-pipeline.sh --stage upload-input --source dblp --snapshot dblp-2026-11-01 --max-upload-mb 1100 --file 'LOCAL_DIR/dblp-2026-11-01.xml.gz'
```

Before uploading, check that the local `dblp.dtd` sha256 is `2d864498f9eef9298662df11e3874171ff24cd4a21ab5974f1369c90acdb1ef6` (manifest `dtd_sha256`).

D6 hadoop stage: add exactly two `--hadoop-extra-arg` values, in this order. Each is one argument; the script checks the `s3://` value against the raw prefix (`run-bounded-pipeline.sh:880-897`):

```
bash scripts/run-bounded-pipeline.sh --stage hadoop --snapshot dblp-2026-11-01 --dblp 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp-2026-11-01.xml.gz' --hadoop-extra-arg '--dblp-dtd' --hadoop-extra-arg 's3://{data-bucket}/raw/dblp/dblp-2026-11-01/dblp.dtd'
```

Do not use `--hadoop-extra-arg '-D'`/`'scholarchain.dblp.dtdPath=…'`; that form is refused (item 6). Checked by reading: the script accepts a value that starts with `--`. `need_value` checks only the argument count (`run-bounded-pipeline.sh:297-299`), and `:326` appends `$2` as is. `--dblp-dtd` contains no `s3://` and no `{…}`, so the checks at `:880-894` pass it. The flow is `runtime: untested`. infra-engineer may also update the record note `"the Hadoop jobs use their built-in defaults (-D scholarchain.code.version only)"` (`run-bounded-pipeline.sh:910`) to mention the DTD.

## Job list, counters, reject reasons

- Jobs: unchanged (J01-J09). Counters: unchanged. Reject reasons: unchanged. The D6 check should expect `scholarchain.reject-reason:undefined_entity` = 0 in J01.
- Output schemas: unchanged.

## Sample-run evidence

- `ScholarChainHadoopSuite` runs the whole pipeline through the driver in EMR argument order on a two-record file with no internal subset. With `--dblp-dtd`: J01 accepted 2, rejected 0, and an identical second run configured with `-D` reused every job. Without the DTD: accepted 1, `undefined_entity` 1. No run on the full release (not in scope).

## INTERFACE HANDOFF

- Files changed: `modules/hadoop/src/main/scala/org/scholarchain/hadoop/pipeline/ScholarChainHadoop.scala`, `modules/hadoop/src/main/resources/scholarchain-hadoop-defaults.xml` (description only), new `modules/hadoop/src/test/scala/org/scholarchain/hadoop/ScholarChainHadoopSuite.scala`.
- Public interface: `pipeline --output R --dblp P --dblp-snapshot S [--source k:d:s:p]... [--dblp-fingerprint sha256:…] [--dblp-dtd PATH]`. `--dblp-dtd` is single use and may come anywhere among the application options, including after them as the EMR step's `extraArgs`. Unknown `--x` options now throw `IllegalArgumentException("unknown option --x")` before any job runs. `ScholarChainHadoop.parse(args: List[String]): Map[String, List[String]]` has the same signature as before.
- Fixtures: inline in the suite (synthetic ISO-8859-1 dblp XML with an external DOCTYPE; a one-entity DTD).
- Thread safety: unchanged (the driver is single-threaded; it sets the key on the job `Configuration` before submission).
- Limits: DTD ≤ `scholarchain.dblp.maxPrologBytes` (1 MiB). The DTD is read once per J01 map task and once on the driver for the fingerprint.
- Build commit moves (a `modules/` change): `package.sh` must run again before `--publish` (already planned in D2).

## Proposed later

- J01 with zero accepted works writes no `works/` folder, so J07 fails with `InvalidInputException` (seen in test run 1). This does not occur on the real release. A fix would be an empty-input guard in the J07 spec or an always-present output folder (hadoop-engineer, Phase 15 or later).

VALIDATION EVIDENCE
- targeted: sbt -batch "hadoop/testOnly org.scholarchain.hadoop.ScholarChainHadoopSuite org.scholarchain.hadoop.PipelineSuite org.scholarchain.hadoop.SettingsSuite" -> Total 12, Failed 1, Errors 0, Passed 11 (failure in the new suite's fixture, fixed); then sbt -batch "hadoop/testOnly org.scholarchain.hadoop.ScholarChainHadoopSuite" -> Passed: Total 3, Failed 0, Errors 0, Passed 3; 0 ignored
- full: not run: not the wave closer
- package (wave closer): not run: not the wave closer
- finished: after the last command above
- files changed after these runs: no
