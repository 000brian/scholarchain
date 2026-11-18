STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 13, repair round 3 re-check (read-only), I-27 and the changed lines in scripts/package.sh only
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools)
TEST RESULTS: not applicable. Scripts are never run by agents. I relied on the orchestrator's facts without re-deriving them: shellcheck exits 0 and the grep -E samples behave as stated.
VALIDATION RESULTS: I traced both patterns by hand against the I-27 rule. Both accept linux/arm64 and linux/arm64/<alnum variant>. Both refuse linux/arm/v7, linux/amd64, linux/arm64x and extra segments. There are no quoting, anchoring or exit-code defects.
ASSUMPTIONS: The pinned ledger image is a multi-platform index, so `imagetools inspect` prints indented `Platform:` lines. This was already the case before this round and is not changed by it. The local Docker is 29.x (from the repair report), and its image-inspect output has a `Variant` field.
RISKS: Runtime behaviour is still unverified until the human runs package.sh again. An older Docker without `.Variant` would make the template fail, and the script would stop with exit 5 rather than accept the wrong platform.
MANUAL ACTION REQUIRED: The human runs scripts/package.sh again and confirms that the steps base-image-platform and check-image print RESULT ok.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human's package.sh run, then the phase 13 human gate.

VERDICT: APPROVE

## Ruling

I-27: RESOLVED.

- **Shared constant, scripts/package.sh:25:** `ARM64_PLATFORM_RE='linux/arm64(/[[:alnum:]]+)?'`.
  - It allows one optional variant segment of letters and digits.
  - It is single-quoted, so nothing is expanded when it is defined.
- **Step base-image-platform, scripts/package.sh:368:**
  - The pattern is `"^[[:space:]]*Platform:[[:space:]]+${ARM64_PLATFORM_RE}[[:space:]]*\$"`. Inside double quotes `\$` becomes a literal `$`, so the ERE is anchored at both ends.
  - The line may start with spaces, but only a `Platform:` field counts. Trailing spaces are allowed.
  - `linux/arm/v7` fails because `64` must follow `linux/arm`. `linux/arm64x` and `linux/arm64/v8/x` fail because the line must end after at most one segment.
  - On failure it still exits 6 (:369). The retry and timeout handling (exits 5 and 7, :354-367) is unchanged.
- **Step check-image, scripts/package.sh:494-498:**
  - The template `{{.Os}}/{{.Architecture}}{{with .Variant}}/{{.}}{{end}}` is a valid Go template. `with` skips the block when the variant is an empty string, so the result is either `linux/arm64` or `linux/arm64/v8`.
  - Command substitution removes the trailing newline.
  - At :497, `[[ "${platform}" =~ ^${ARM64_PLATFORM_RE}$ ]]` leaves the right-hand side unquoted on purpose, so it is read as a regex.
    - Parentheses and brackets inside a variable's value are not parsed by the shell. They only become part of the regex.
    - The `$` before ` ]]` is a literal end anchor.
    - The match is anchored at both ends, with no quoting defect.
  - The exit codes are unchanged: 5 if inspect fails (:496), 6 if the platform is refused (:498).
  - Before this round, the old string comparison never read the variant, so it could not cause the exit 6 the human saw. The new check is consistent with step base-image-platform.
- **STEP and RESULT lines:**
  - The step names `base-image-platform` (:352) and `check-image` (:492) are unchanged.
  - The die and info wording changed (:369, :370, :498, :513). The step names and exit codes did not.
- **Record and usage text:**
  - The record value `image.platform` (:520, :525) is still the constant `PLATFORM` = "linux/arm64". This is the platform passed to `buildx build --platform` (:484) and `docker create --platform` (:501), and it is also the OS/architecture the check confirmed. That makes it truthful.
  - The variant actually seen appears only in the INFO line at :513, which the repair report states openly.
  - The usage text at :78-79 and :93-94 matches what the code does.
- **No other platform checks:** I searched scripts/ for `arm64|PLATFORM|platform|Architecture`. The only platform comparisons are at :368 and :497. Every other match is a constant, usage text, the record, or a `--platform` argument. No other script checks the platform.

## New findings

None. No I-28 is raised.

| ID | Severity | Owner | Interface | file:line | Failure input | Impact |
|---|---|---|---|---|---|---|
| (none) | | | | | | |

I checked one thing outside this round and am not raising it. For a single-platform image (one manifest, no index), `imagetools inspect` prints no `Platform:` line, so the step would stop with exit 6. This behaviour existed before this round, and the pinned ledger image is an index, so it does not apply here.

## Coverage (what I read)

- The repair report: /private/tmp/claude-501/-Users-brian-Developer-repos-scholarchain/81476c99-8e4e-4aa9-aabf-b889baacaf6c/scratchpad/phase13-reports/repair-03-infra.md (all of it).
- /Users/brian/Developer/repos/scholarchain/scripts/package.sh:
  - lines 1-110: header, constants and usage text, including :21-25, :78-79 and :93-94;
  - lines 320-519: the end of the attempt-record writer, `read_ledger_image`, `check_base_platform`, `refuse_if_packaged`, `sbt_build`, `one_file`, `check_jar(s)`, `check_context`, `docker_build`, `check_image` and the start of `write_package_record`, up to :525 through the grep results.
- A search of /Users/brian/Developer/repos/scholarchain/scripts for `arm64|PLATFORM|platform|Architecture`, to make sure no platform comparison of the old kind remains.
- Not read: the publish-mode functions in detail (the search found no platform comparison there), and scripts/lib/*.sh (`with_timeout` and `die` are unchanged this round).
