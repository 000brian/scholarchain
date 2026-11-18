# repair-D5-infra (G-07): multipart upload for --stage upload-input

```text
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: docs/agent-runs/phase-14/prompt-s3.md (sha256 c0bd687e92015df11475ce159307b1c78f8955175cbcf9b86a9e0fe6540fe83e), decision 12 "When a step fails" class 2 (script defect), finding G-07; HEAD 5c4cfb001227c25f1b9d7569b47159c0428e798d
FILES CREATED: none
FILES MODIFIED: scripts/run-bounded-pipeline.sh, scripts/README.md
COMMANDS RUN: shasum -a 256 (work order); git rev-parse HEAD; git status --short; git diff --stat; ls; wc -l; grep -n on scripts/** and on infra/terraform/modules/** (Bash grep: the Grep tool is not available in this session; no grep on infra/terraform/environments/course/); shellcheck -x -P scripts scripts/run-bounded-pipeline.sh (twice); aws-docs MCP reads (listed below). Deviations from the command list, reported honestly: one early `sed -n` read of the work order lines 40-130 (read-only, should have been the Read tool) and one `uniq -c` in a read-only pipeline listing the s3api subcommands of the other scripts. No script run, no aws-api call, no sbt, no terraform command, no *.tfstate* read.
TEST RESULTS: not applicable (no Scala source; scripts are not run by agents)
VALIDATION RESULTS: shellcheck clean (exit 0); see VALIDATION EVIDENCE
ASSUMPTIONS: the human's AWS CLI v2 accepts --if-none-match on complete-multipart-upload (it already accepts it on put-object, used since Phase 13; both came with S3 conditional writes); bash's printf %b emits a NUL byte for \x00 (guarded by a self-test, see item 2); the admin profile `default` has s3:AbortMultipartUpload and s3:ListMultipartUploadParts.
RISKS: runtime untested (first run is the D5c retry); --if-none-match on complete-multipart-upload with an old CLI minor version would fail only at the complete step, after all parts (the script then aborts the upload; exit 5, nothing stored); a TERM sent to bash alone waits for the running part (at most 600 s) before the abort runs; a second Ctrl-C during the abort can stop it (the 1-day lifecycle rule is the backstop); the ER download (get-object, line 1383) is still one request per object (see proposed later).
MANUAL ACTION REQUIRED: review the diff (code-reviewer), commit, then re-run D5c with the same command line (new attempt ID). Optional check before: `aws --version` prints aws-cli/2.x.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews `git diff scripts/`; human commit; D5c re-run
```

## Items

1. Never-overwrite: done. head-object before start (put_once, scripts/run-bounded-pipeline.sh:978-984, unchanged rule); `complete-multipart-upload --if-none-match '*'` (:867-868), supported per the CLI reference (complete-multipart-upload, Options, `--if-none-match`: "Uploads the object only if the object key name does not already exist ... Otherwise ... 412 Precondition Failed") and S3 User Guide conditional-writes.html ("Conditional write behavior": on a versioned bucket a current version blocks it; the data bucket is versioned, infra/terraform/modules/data-lake/main.tf:64-70). After a failed complete the key decides (:874-897): this attempt's object (scholarchain-attempt = ATTEMPT_ID, lost answer) = uploaded; same sha256 from another attempt = already-present and this upload is aborted; other bytes = exit 4; 409 ConditionalRequestConflict = exit 5 (docs: re-initiate); NoSuchUpload with the key absent = exit 6.
2. SHA-256 checksum: done. `--checksum-algorithm SHA256` on create-multipart-upload (:785-787) and on every upload-part (:827-829); create's answer `ChecksumAlgorithm` must be SHA256 (:799-804). Type: COMPOSITE (the only type S3 supports for SHA-256 multipart; checking-object-integrity-upload.html table "SHA-256: Full object No, Composite Yes"; full-object is CRC only). `--checksum-type` is not passed (COMPOSITE is the only valid value for SHA256; avoids an option an older CLI may lack). Each part's returned ChecksumSHA256 is compared with the script's own base64 SHA-256 of the part (:820-823, :849-851); hex_to_b64 (:739-747) is self-tested at prerequisites against two known values incl. a zero byte (:751-761), else that comparison is off with a WARN (S3 still verifies each part). Afterwards `head-object --checksum-mode ENABLED` (:906-917) must show ContentLength = local bytes; it shows ChecksumType COMPOSITE and a ChecksumSHA256 computed from the part checksums (head-object Output: "may not be a direct checksum value of the full object"; the `-N` suffix is unverified); both are recorded. The whole-file sha256 stays in metadata scholarchain-sha256 and is read back by put_once (:978).
3. Metadata: done. `--metadata scholarchain-sha256=...,scholarchain-commit=BUILD,scholarchain-attempt=ATTEMPT` on create-multipart-upload (:787), the same three keys as put-object (:999); head_meta (:681-694) and check_artifact_commit read them unchanged.
4. Abort on failure or signal: done. on_exit calls mpu_abort_pending before the final record (:290-293); mpu_abort_pending (:926-964): abort-multipart-upload, then list-parts must answer NoSuchUpload or zero parts (abort-multipart-upload Description: in-flight parts may land, verify with ListParts), up to 3 rounds, bounded by AWS_TIMEOUT per call; prints `SUMMARY: multipart-abort location=... parts_uploaded=N result=aborted|abort-failed`; adds outputs.multipart_aborted. This is the abort of this attempt's own incomplete upload (frees parts that never became an object), not data removal: no stored object or version is touched; the bucket's 1-day abort_incomplete_multipart_upload rule (data-lake/main.tf:102-104) is the backstop.
5. Honest lines and record: done. `POLL: multipart location=... part=I/N bytes=B tries=T` per part (:855); `SUMMARY: multipart ... checksum_type=... outcome=...` (:919-920); `SUMMARY: upload-input location=... bytes=... method=... outcome=...` (:1206-1207); ERROR lines via die with the failing part and try count. Record fields kept; uploaded_keys entries get `method` and, for multipart, `parts`, `part_bytes`, `s3_checksum_type`, `s3_checksum_sha256` (:1014-1017). A failed attempt keeps uploaded_keys [] plus outputs.multipart_aborted.
6. Threshold and bounds: files > 64 MiB (MULTIPART_THRESHOLD_BYTES, :29-33) go multipart with 64 MiB parts; at most 600 s per part try (PART_TIMEOUT), 5 tries per part (PART_TRIES), complete at most 600 s x 3 tries; 2..10000 parts enforced (:777). D5c: 1,116,142,729 bytes = 17 parts. One part file on disk at a time (TMP_DIR, removed by on_exit). dd and base64 added to the upload-input and er prerequisites (:549-554).
7. Other single put-object uploads (survey):
   - scripts/run-bounded-pipeline.sh:996-1000 put_once put-object: now only for files <= 64 MiB.
   - ER upload (`--upload full` and `identity-assignments-only`): put_once calls at :1436, :1451, :1452 use the same helper, so every ER file above 64 MiB (the ~4.25 GB files: 64 parts, 68 if GiB) is now multipart. Fixed by the same change (stated).
   - hadoop, spark, load stages: no local-file upload (start-execution, run-task, reads only).
   - scripts/package.sh:749-752 upload_once put-object for jars (<= 32 MB) and spark-conf JSON: left as is (allowed by the work order).
   - archive-evidence.sh, cleanup.sh, run-experiment.sh, smoke-test.sh, deploy.sh, rollback.sh, check.sh: no put-object, s3 cp or upload-part (grep).
8. scripts/README.md: D5c row (line 79) names the expected lines; new section "Large uploads (Phase 14 G-07)" before "ER upload size". Usage (options, command lines) unchanged.

## Call table (s3api options used, each checked in the AWS CLI reference 2.41.0 via aws-docs)

| Call (file:line) | Options | Source |
|---|---|---|
| create-multipart-upload (:785) | --bucket, --key, --checksum-algorithm SHA256, --metadata; output UploadId, ChecksumAlgorithm | cli/latest/reference/s3api/create-multipart-upload.html Options, Output |
| upload-part (:827) | --bucket, --key, --upload-id, --part-number, --body (path, no file://), --checksum-algorithm SHA256; output ETag, ChecksumSHA256 | .../upload-part.html Options, Output |
| complete-multipart-upload (:867) | --bucket, --key, --upload-id, --multipart-upload file:// {Parts:[{ETag, ChecksumSHA256, PartNumber}]}, --if-none-match '*' | .../complete-multipart-upload.html Options; userguide/conditional-writes.html |
| abort-multipart-upload (:931) | --bucket, --key, --upload-id | .../abort-multipart-upload.html Synopsis, Description |
| list-parts (:935) | --bucket, --key, --upload-id; output Parts | .../list-parts.html Synopsis, Description |
| head-object (:906) | --bucket, --key, --checksum-mode ENABLED; output ContentLength, ChecksumType, ChecksumSHA256, Metadata | .../head-object.html Synopsis, Options, Output |
| global (aws_write :720-733) | --region, --cli-connect-timeout, --cli-read-timeout, --no-cli-pager, --output json | head-object.html Global Options |

unverified: the `-N` suffix of a composite ChecksumSHA256; the human's CLI minor version.

## IAM changes

None in Terraform. The human's admin profile runs the script; it needs s3:PutObject, s3:AbortMultipartUpload, s3:ListMultipartUploadParts, s3:GetObject on the data bucket (assumed present for an admin; unverified).

## Stateful-resource actions

None by an agent. At run time the script only writes put-if-absent objects under raw/ or curated/er/ATTEMPT_ID/ and aborts its own incomplete upload.

## Cost estimate

Unchanged order of magnitude: 17 UploadPart requests + 3 control requests for D5c (PUT-class request pricing, fractions of a cent; unverified price); parts of an aborted upload are freed at once, else after 1 day by the lifecycle rule.

## Required environment variables (names)

AWS_PROFILE (the human's), TMPDIR (optional; part file and ER folder location).

## Proposed later

- ER download: get-object per export object (:1383) is one request per object with 3 whole-object retries; a large J09 export object could hit the same drop. A ranged or `aws s3 cp` download with resume would fix it (not asked here).

## INTERFACE HANDOFF

- Files changed: scripts/run-bounded-pipeline.sh, scripts/README.md.
- Interface: yes (additive). Command-line options: unchanged. Record: uploaded_keys[] entries gain `method` (string or null) and, for multipart, `parts`, `part_bytes`, `s3_checksum_type`, `s3_checksum_sha256`; outputs gains `multipart_aborted` (list of {location, parts_uploaded, result, note}) only when an abort ran. New printed lines: `POLL: multipart ...`, `SUMMARY: multipart ...`, `SUMMARY: multipart-abort ...`, `SUMMARY: upload-input ...`. S3 object metadata keys: unchanged. Exit codes: unchanged meanings.
- New functions: aws_write SECONDS READ_TIMEOUT ARGS...; aws_err_has REGEX; hex_to_b64 HEX; mpu_self_test; mpu_upload FILE KEY SHA BYTES (main shell only); mpu_abort_pending.
- Limits: threshold 64 MiB, part 64 MiB, 600 s per part try, 5 tries per part, 2..10000 parts, file max still 5000 MiB (--max-upload-mb hard max).
- Thread-safety: single process, sequential parts; one part file in TMP_DIR.
- runtime: untested.

VALIDATION EVIDENCE
- fmt: not applicable (no Terraform file changed)
- validate: not applicable (no Terraform file changed)
- shellcheck: shellcheck -x -P scripts scripts/run-bounded-pipeline.sh -> 0; clean
- finished: after the last command above
- files changed after these runs: no
