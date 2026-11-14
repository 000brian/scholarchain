# Agent report: domain-engineer

Phase 5, run 1 of 2. Agent reports reproduced verbatim from the orchestrator session, in the order they were returned.

---

## Report 1: domain-engineer, decision 7 (core contract changes)

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: docs/agent-runs/phase-05/prompt.md (run 1 of 2), agent 2 of 6, decision 7. Decisions 5, 6, 12 and 14 were used as context.
FILES CREATED:
- /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/AuthorNameKey.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/AuthorNameKeySuite.scala
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/MatchDecision.json
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/MatchDecision.identity-assignment.json
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/MatchDecision.llm.json
- /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/LlmAdjudication.json
FILES MODIFIED (all under modules/core/src):
- main/scala/org/scholarchain/core/{Enums,Records,SchemaVersions,StableIds,Validator}.scala
- test/scala/org/scholarchain/core/{Fixtures,SchemaVersionsSuite,StableIdsSuite,ValidationSuite,WireContractSuite}.scala
- I did not edit wire/v1/MatchDecision.json. It stays as the frozen record of the superseded version.
COMMANDS RUN:
- Read-only: git status, git diff, grep, find, sed -n, cat, ls, wc.
- I wrote placeholder wire files with printf and then replaced them with the real content.
- `sbt "core/testOnly org.scholarchain.core.WireContractSuite -- *reproduces*"`, run twice. I used it to capture the encoded JSON for the hash-derived IDs.
- `sbt core/test core2_13/test`
- `sbt check`, run in the background.
TEST RESULTS:
- `core/test` (Scala 3): Passed, Total 414, Failed 0.
- `core2_13/test` (Scala 2.13): Passed, Total 414, Failed 0.
- `sbt check`: `[success] Total time: 1168 s`. The output included "Updated .claude/.last-test-run (module_tests=907, contract_tests=87)", and the background task reported exit=0.
VALIDATION RESULTS:
- scalafmt check and ledger check passed inside `sbt check`.
- hadoop, tests/contracts and every other project compiled with no change to them. Nothing outside core used MatchDecision or MatchOutcome; I checked with grep across modules/ and tests/.
- New tests fail without the change: they use the new APIs (compile failure otherwise) and the new frozen files.
ASSUMPTIONS:
- Keys in the LLM adjudication record: "candidate IDs offered" and "chosen candidate ID" are MatchCandidateIds. Each option offered to the model is a MatchCandidate, for example mention_researcher candidates for one mention, so the model can only pick an existing candidate.
- reject and abstain carry no chosen ID.
- Free-text model reasoning is not stored. Cited input fields stand in for it, in line with the "no hidden reasoning" invariant.
- I replaced the MatchOutcome wire names instead of adding a parallel enum. The old names were match, possible_match, non_match, unresolved; the new ones are accepted, rejected, unresolved, conflict. Only MatchDecision used the enum, and its version was bumped.
- Phase 4 hadoop applies no accent stripping, and its keys only use NFD. The loose tokens and the initials key do drop Latin diacritics, because they are recall-oriented blocking keys. Marks on other scripts are kept.
- No separate identity-assignment record was needed. A mention_researcher MatchDecision with no candidateId holds everything decision 5 asks for: mention, cluster ResearcherId, accepted or conflict, path, rules, and reason codes.
- No arXiv or Semantic Scholar enum values were added.
RISKS:
- The initials key treats a single-token name as the token itself, for example "Mausam" -> "mausam".
- The initials key does not handle name-order variants, such as a family name written first. That is left to ER features.
- Letters with no decomposition (ø, ł, ß, æ) are not folded, so "Soren" and "Søren" do not share a key.
- Validator rules that downstream must satisfy:
  - rulesFired and reasonCodes must be sorted and distinct tokens.
  - exact_id and deterministic decisions need at least one rule fired.
  - unresolved and conflict decisions need at least one reason code.
  - modelVersion and promptVersion are present only on the llm path.
  - provenance.sourceType must be pipeline.
  - policyVersion must equal provenance.policyVersion.
  - decisionTime must be exactly `yyyy-MM-ddTHH:mm:ssZ`.
  - LlmAdjudication needs provenance.contentHash == inputHash.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer (decision 8) switches J05 and J06 to AuthorNameKey and adds the initials-surname family. entity-resolution-engineer then builds on MatchDecision v2 and LlmAdjudication. code-reviewer reviews.

CONTRACT CHANGES AND SCHEMA VERSIONS
- MatchDecision 1 -> 2. This is a breaking change.
  - New frozen file: wire/v2/MatchDecision.json (pairwise, probabilistic).
  - Extra frozen examples: wire/v2/MatchDecision.identity-assignment.json and wire/v2/MatchDecision.llm.json.
  - v1 is now in the superseded list, and v1 JSON is rejected.
- LlmAdjudication 1 is new. Frozen file: wire/v1/LlmAdjudication.json.
- Enum changes:
  - MatchOutcome wire names are now accepted, rejected, unresolved, conflict. This is breaking and tied to MatchDecision v2.
  - New MatchPath: exact_id, deterministic, probabilistic, llm.
  - New AdjudicationOutcome: accept, reject, abstain.
- All other schema versions are unchanged.

STABLE ID FORMULAS
- New: `LlmAdjudicationId.of(inputHash, promptVersion, modelVersion, policyVersion): Either[String, LlmAdjudicationId]` = "llm-adjudication:v1:" + digest32("llm-adjudication/v1", inputHash, promptVersion, modelVersion, policyVersion).
  - Left if any input is blank.
  - This is the cache key: each of the four parts changes the ID, and the outcome is not an input.
  - Frozen example: llm-adjudication:v1:ae0a4711d57a6a285da06edce17445e5.
- Existing IDs are unchanged. Mention-pair canonicalization stays UndirectedPair: left < right by code point, no self pairs. It is applied to MatchDecision mention_pair subjects as well.

API SUMMARY (for hadoop-engineer and entity-resolution-engineer; package org.scholarchain.core)

MatchDecision (schemaVersion 2) fields:
- subjectKind: MatchSubjectKind
- left: String, right: String. These follow the MatchCandidate convention:
  - mention_pair: two MentionIds with left < right.
  - mention_researcher: left is a MentionId, right is a ResearcherId.
- candidateId: Option[MatchCandidateId]. Required for mention_pair. For mention_researcher, None means the identity assignment, whose outcome must be accepted or conflict.
- outcome: MatchOutcome
- matchPath: MatchPath
- confidence: Double in [0,1]
- features: List[MatchFeature]. May be empty; sorted by name, distinct, finite; missing features are omitted.
- rulesFired: List[String] and reasonCodes: List[String]. Tokens matching `[A-Za-z0-9][A-Za-z0-9._:/-]*`, sorted by code point and distinct.
- modelVersion: Option[String], promptVersion: Option[String]. Required when the path is llm, null otherwise.
- policyVersion: String. Must equal provenance.policyVersion.
- decisionTime: String. A UTC instant `yyyy-MM-ddTHH:mm:ssZ`: one value per run, from configuration, never the clock.
- provenance: Provenance. sourceType must be pipeline. The run ID is provenance.runId.
- Decision-6 mapping: source record ID = left, candidate target ID = right.

LlmAdjudication (schemaVersion 1) fields:
- adjudicationId: LlmAdjudicationId
- inputHash: String. `sha256:<64hex>`, must equal provenance.contentHash.
- offeredCandidateIds: List[MatchCandidateId]. Non-empty, sorted, distinct.
- outcome: AdjudicationOutcome
- chosenCandidateId: Option[MatchCandidateId]. Required and must be one of the offered IDs when outcome is accept; null for reject and abstain.
- citedInputFields: List[String]. Tokens matching `[A-Za-z0-9][A-Za-z0-9._:/\[\]-]*`, sorted and distinct; non-empty for accept and reject.
- confidence: Double in [0,1]
- reasonCodes: List[String]
- promptVersion: String, modelVersion: String, policyVersion: String. All non-blank; policyVersion must equal provenance.policyVersion.
- provenance: Provenance. sourceType must be pipeline.
- Use Contract.encode and Contract.decode; both validate.

AuthorNameKey (rule version `AuthorNameKey.Version = "author-name-key/1"`):
- `collapseWhitespace(name: String): String`: Unicode whitespace runs become one space, then trimmed.
- `canonical(s: String): String`: NFD only. Identical to hadoop's BlockingKeys.canonical.
- `split(name: String): (String, Option[String])`: base name and homonym suffix. "Wei Wang 0001" -> ("Wei Wang", Some("0001")).
- `homonymSuffix(name: String): Option[String]`
- `baseNameKey(name: String): String`: NFD of the base name. Equals hadoop's `dblp-base-name` key on dblp names (parity test included).
- `fullNameKey(name: String): String`: NFD of the whole name, suffix kept. Equals hadoop's name_exact_match and coauthor comparison form.
- `tokens(name: String): List[String]`: works on the base name. Steps:
  - lowercase with Locale.ROOT, then NFD
  - drop Latin diacritics only
  - drop apostrophes
  - other punctuation and symbols separate tokens
- `foldedName(name: String): String`: the tokens joined by single spaces. Example: "Jean-Pierre O'Brien 0002" -> "jean pierre obrien".
- `initialsSurnameKey(name: String): Option[String]`: the first grapheme of each token except the last, then the last token. "J. Smith", "John Smith", "Jane Smith" -> Some("j smith"). None if the name has no tokens.
- None of these use NFKD, transliteration or cross-script folding: Cyrillic and Latin look-alikes never share a key (tested). All functions are null-safe.

Sample serialized identity assignment (frozen wire/v2/MatchDecision.identity-assignment.json, abridged): {"schemaVersion":2,"subjectKind":"mention_researcher","left":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3","right":"researcher:v1:6d39fb31017461bdf6bbe118864d1809","candidateId":null,"outcome":"accepted","matchPath":"deterministic","confidence":1,"features":[],"rulesFired":["fixture-dblp-prior"],"reasonCodes":[],"modelVersion":null,"promptVersion":null,"policyVersion":"fixture-match-policy/1","decisionTime":"2026-11-14T00:00:00Z","provenance":{...pipeline...}}

Configuration precedence: unchanged in this phase. No config contract change was made. decisionTime is expected to come from ER configuration and be recorded in the run manifest; that is entity-resolution-engineer's work.
