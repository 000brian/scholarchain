# Agent run evidence

Owner of this README: `factory-engineer` (Phase 1 human ownership amendment). Every other file under `docs/agent-runs/**` is human-owned evidence. No agent creates or edits it; the guard hook blocks subagent writes and asks the human before any orchestrator write.

## Purpose

This directory is the reproducibility record of the agentic workflow: what each phase was asked to do, what the agents reported, what deterministic commands showed, what reviewers and testers found, what the human decided, and which commit was accepted.

## Hidden model reasoning is not stored

No file here contains hidden model reasoning, chain-of-thought, or private scratch work. The record consists only of work orders, final agent reports, deterministic command output, review and test findings, repair records, and human decisions. A report's claims are supported by the commands and files it cites, not by reasoning transcripts.

## Layout

```text
docs/agent-runs/
  README.md                 this file (factory-engineer)
  phase-00/                 seed agent
  phase-01/                 factory build
  phase-NN/
    prompt.md               exact work order given to the orchestrator (verbatim)
    agent-report.md         final report of the owning agent (verbatim)
    review-report.md        code-reviewer report, when the phase requires review
    test-report.md          test-engineer report, when the phase requires tests
    repair-01.md            one file per repair loop, when applicable (repair-02.md, ...)
    human-gate.md           human decision record
    commit.txt              accepted commit hash
    tool-versions.txt       toolchain versions, when recorded for the phase
    <supporting files>      phase-specific inputs the human records (for example the
                            specification version used as the authority source)
```

Phase directories use two-digit numbers (`phase-00` to `phase-17`).

## File formats

### prompt.md

The exact work order text, including any human ratification preamble. Never edited after the phase starts; corrections create a repair record.

### agent-report.md, review-report.md, test-report.md

The agent's final report, verbatim, beginning with the standard fields:

```text
STATUS: COMPLETE | BLOCKED-ON | FAILED
OWNER:
WORK ORDER:
FILES CREATED:
FILES MODIFIED:
COMMANDS RUN:
TEST RESULTS:
VALIDATION RESULTS:
ASSUMPTIONS:
RISKS:
MANUAL ACTION REQUIRED:
BLOCKED-ON OWNER:
BLOCKED-ON EVIDENCE:
NEXT GATE:
```

followed by agent-specific sections (for `code-reviewer`, the `VERDICT` and findings table). Deterministic command output may be appended in fenced blocks, labeled with the exact command.

### repair-NN.md

- Trigger: the finding, failing gate, or `BLOCKED-ON` that started the repair.
- Owner routed to, and the work order given.
- Files changed and the rerun evidence (commands and output).
- Result: resolved, partially resolved, or escalated.

### human-gate.md

- What was reviewed.
- What was accepted or rejected.
- Any manual changes, disclosed explicitly (agent-generated and accepted; agent-generated and repaired by another agent; agent-generated and manually edited; human-authored).
- Probes run and their outcomes, when the phase requires probes.
- Remaining risk.
- Ratification statement for constitutional changes.
- The accepted commit hash.

### commit.txt

The accepted commit hash on one line, recorded by the human after committing.

## Rules

1. One phase, one fresh orchestrator session, normally one commit.
2. Agents never report a command as successful unless they observed its output; evidence files quote that output rather than paraphrasing it.
3. Secrets, tokens, account credentials, and personal data are never recorded. Redact before archiving.
4. Evidence is append-only in practice: later corrections go in new repair or gate records, not rewrites.
