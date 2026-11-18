# review-report-G-07: multipart upload in scripts/run-bounded-pipeline.sh

(Saved verbatim by the orchestrator from code-reviewer's final message.)

VERDICT: APPROVE

I found no critical or major defect. The multipart path is correct for the D5c file and for ER files of about 4.25 GB. The never-overwrite rule holds, and no stored object is ever touched. There are four minor findings, G-08 to G-11. All belong to infra-engineer, and none blocks the D5c re-run.

## Findings

| ID | Severity | File:line | Failure input | Impact | Owner | Interface |
|---|---|---|---|---|---|---|
| G-08 | minor | scripts/run-bounded-pipeline.sh:723-729, 827-829, 842, 897, 935-936 (same pattern already in aws_read :591-603) | (a) One upload-part try fails, for example a dropped connection, and the retry succeeds. (b) A part times out five times in a row. | This is the G-06 pattern. Under `set -E`, on_err runs inside the command substitution and exits 5. (a) Every failed part try prints a spurious `ERROR: ... line=827 exit=5: command failed with status 5` line before the WARN retry line, during a run that then succeeds. The already-present path does the same at :935, where the expected NoSuchUpload from list-parts triggers it. (b) The inner substitution at :723 also exits 5 before aws_write can see 124 or 137. So `return 7` (:727), `die 7` (:842, :897) and the "Status 7 on time-out" line in the usage text cannot be reached. A part that times out five times ends `failed`, exit 5, not `timed-out`, exit 7. The aws.err text shown in AWS-ERROR also carries on_err's own line. | infra-engineer | no (exit code 7 versus 5 only) |
| G-09 | minor | :751-760, :820-823, :849; README:114; usage :111-112 | `mpu_self_test` fails in this shell, for example `printf %b` does not emit a NUL byte. | The script's own per-part check is switched off with only a stderr WARN. The record, the `POLL` lines and the `SUMMARY: multipart` line do not show it. README:114 and the usage text say the script compares every part with no "if the self-test passed". Turning it off is acceptable for safety: S3 still checks every part against the SHA-256 the CLI sends, and the local check hashes the same part file the CLI reads, so it adds little. But the record should say so. The whole-file sha256 (`scholarchain-sha256`) is stated, not checked against the stored object. The local composite (sha256 of the part digests) is never compared with head-object's `ChecksumSHA256`. This is the same level of checking as the put-object path, but it is not said. Suggested fix: record `local_part_check: on/off` in the uploaded_keys entry and in the SUMMARY line, and optionally compare the composite. | infra-engineer | yes (additive record field) |
| G-10 | minor | :318 (timeout without `--foreground`), :287-296 (on_exit), README:116 | (a) Ctrl-C during a part. (b) A second or third Ctrl-C while on_exit is aborting. | (a) GNU timeout puts itself and aws in their own process group, so the terminal's SIGINT does not reach aws. bash runs the INT trap only after the running part ends, up to 600 s plus 30 s kill-after, so the script looks hung. The owner's risk note says this only for TERM. (b) on_exit now runs `mpu_abort_pending` (:293) before `write_final_record` (:295), and the INT and TERM traps are still set. A further Ctrl-C during the abort (up to about 3x(120+120)+9 s) exits from inside the EXIT trap, so no final record is written and only `.started.json` remains. README:116 says the abort can be stopped, but not that the record is lost. Suggested fix: `trap '' INT TERM` at the top of on_exit, and say in the README that Ctrl-C takes effect after the current part. The lifecycle backstop is verified (see check 3). | infra-engineer | no |
| G-11 | minor | :884-888 with :906-915 and :1014-1017; README:117 | (a) Another attempt stores the same bytes between the first head-object and the complete. (b) A complete succeeds but its answer is lost, and the head-object at :875 then fails three times. | (a) The uploaded_keys entry gets `method: "multipart"`, `outcome: already-present`, and `parts` and `s3_checksum_*` read from the other attempt's object. README:117 says method is null when the key was already present. (b) die 5, then on_exit aborts an upload that is already complete, gets NoSuchUpload, and records `multipart_aborted[].result = "aborted"`, although this attempt's object exists. No data is lost: a new attempt finds the object as already-present. Only the record is misleading. | infra-engineer | yes (record values only) |

## Checklist

1. **Multipart path.** Checked, correct. Parts are cut by `dd bs=1048576 skip=(i-1)*64 count=64` (:816). The byte offset equals `(i-1)*part_bytes`, the last part is short, and the exact size is checked against `expect` (:813-819).
   - D5c: 1,116,142,729 bytes gives 17 parts, the last one 42,400,905 bytes. Every part but the last is at least 5 MiB, and 2..10000 parts are enforced (:775).
   - One 64 MiB file lives in TMP_DIR (umask 077) and is removed by on_exit. dd truncates the output by default on both BSD and GNU dd. dd and base64 are present on macOS.
   - Parts are numbered 1..N in order. A retried part has the same number and the same bytes. Each try is bounded by `PART_TIMEOUT` (600 s), with 5 tries and NoSuchUpload final.
   - ETag and ChecksumSHA256 are collected per part. The JSON `{Parts:[{ETag, ChecksumSHA256, PartNumber}]}` matches the API.
   - `--if-none-match '*'` is on complete (:868). The pre-start head-object is unchanged (:978). The post-complete check reads the key first, then the error (:874-895). err_text is captured before head_meta overwrites aws.err.
   - Outcome rules: this attempt's object counts as uploaded, the same sha256 from another attempt as already-present plus an abort, other bytes or no metadata give exit 4. A 409 gives exit 5 and NoSuchUpload exit 6. After that, put_once reads the metadata back (:985-992). Timeout exit-code issue: G-08.
2. **Self-test.** Not silent (WARN), but not recorded. See G-09. The known values check out: 00ff10 is AP8Q, and the empty-string sha256 is 47DEQ...uFU=.
3. **Abort.** Only this attempt's `MPU_UPLOAD_ID` is aborted. It is cleared right after a successful complete (:870, :881) and on the lost-answer path, so nothing is aborted after a success.
   - list-parts must show NoSuchUpload or 0 parts. on_exit removes the ERR trap and sets `set +e` before the abort (:289-293), and the abort runs before TMP_DIR is removed.
   - put_once and mpu_upload run in the main shell: stage_upload_input, and the ER loop through `< <(find …)` (:1438). Command-substitution subshells do not inherit the EXIT trap, so no subshell aborts.
   - Lifecycle rule verified: infra/terraform/modules/data-lake/main.tf:92-105, rule `expire-noncurrent-versions`, `filter {}` (the whole bucket), `abort_incomplete_multipart_upload { days_after_initiation = 1 }`. Versioning is on (:64-70). SSE-S3 is AES256 (:54-62), so multipart needs no KMS permission. The bucket policy only denies non-TLS and old TLS (:122-164).
   - Signal and record-loss issues: G-10.
4. **Metadata and small files.** Checked, none. The create call (:787) sets the same three keys as put-object (:999), which is what head_meta and check_artifact_commit read. Files of at most 64 MiB (`-gt` threshold, :985) take the put-object path, which is byte-identical except for the `method` variable.
5. **Record.** Additive only: `method` plus the four multipart fields in uploaded_keys, and `outputs.multipart_aborted`. No other script reads uploaded_keys; I grepped scripts/ and archive-evidence.sh. require_er_succeeded reads only `.outputs.identity_assignments` and is unaffected.
   - Upload IDs are never printed or recorded. Locations are logical. Only `scholarchain-attempt` is printed (:886).
   - Value issues: G-11.
6. **AWS CLI options.** Checked, none rejected. These all exist in CLI v2: create-multipart-upload `--checksum-algorithm`, `--metadata`; upload-part `--upload-id`, `--part-number`, `--body`, `--checksum-algorithm`; complete-multipart-upload `--multipart-upload file://`, `--if-none-match`; abort-multipart-upload, list-parts; head-object `--checksum-mode ENABLED`; the global options as already used.
   Residual risk, not a finding: `--if-none-match` on complete came in the same S3 conditional-writes update as on put-object, which already worked in D5b. An older CLI would fail only at the complete step, after which the upload is aborted with exit 5.
7. **README.** Matches the code, apart from the claims noted in G-09 (unconditional comparison), G-10 (Ctrl-C delay, record loss) and G-11 (method null). These also check out: 17 POLL lines, the SUMMARY forms, 64 or 68 parts for 4.25 GB or GiB, dd and base64.

## Files read

- **In full:** scripts/run-bounded-pipeline.sh (lines 1-1730), the diff file diff-G-07.txt, the owner report repair-D5-infra.md, and runbook-log.md (for the G-06 field evidence: D5b ERROR line=642).
- **In part:** infra/terraform/modules/data-lake/main.tf lines 25-173 (Read and Grep tools only); scripts/lib/identity.sh (sc_sha256, sc_bytes, mask_text, logical_s3, register_identifiers, write_record_atomic, through Grep).
- **Not read:** scripts/README.md outside the diff hunks; the failed record evidence/runs/20261118T192854Z-5c4cfb001227-6765-upload-input.json (not needed for the review); infra/terraform/environments/course/ and every *.tfstate* (deliberately).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), decision 12 class 2 repair G-07, decision 11 checklist; review of diff-G-07.txt
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable (scripts are not run by agents; runtime untested)
VALIDATION RESULTS: static review only. The G-08 runtime behaviour is inferred from the recorded G-06/D5b field evidence (ERROR line=642 inside a guarded $(...)) and bash errtrace semantics; it was not executed.
ASSUMPTIONS: GNU timeout without --foreground puts the child in its own process group (documented coreutils behaviour); AbortMultipartUpload on a completed upload answers NoSuchUpload.
RISKS: an older CLI without --if-none-match on complete fails only after every part has been sent (the upload is then aborted, exit 5); a credential expiry during the ~4.25 GB ER uploads would fail the parts and the abort (the 1-day lifecycle rule is the backstop); Ctrl-C takes up to about 10 minutes to act (G-10).
MANUAL ACTION REQUIRED: the human may accept G-08..G-11 as minors before the D5c re-run; optionally run `aws --version` first.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit of G-07; D5c re-run (expect possible spurious ERROR lines on retried parts, G-08)
```

VERDICT: APPROVE
