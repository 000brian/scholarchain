I made all four core changes, and core passes its tests under both Scala 3 and Scala 2.13. But `sbt check` fails: `modules/hadoop` no longer compiles against the new contracts. I did not edit `modules/hadoop`; the four compile errors and the follow-up hadoop changes are listed below for hadoop-engineer.

```text
STATUS: BLOCKED-ON
OWNER: domain-engineer
WORK ORDER: Phase 4 supporting work order (routed from a hadoop-engineer BLOCKED-ON): four core contract changes. (1) Author positions on CoauthorPairCandidate. (2) A new MatchCandidate contract. (3) WorkRecord <ee> and <url> lists. (4) An OpenAlex topic ID type. Human-owned context: docs/agent-runs/phase-04/prompt.md.
FILES CREATED:
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/WorkRecord.json
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/CoauthorPairCandidate.json
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/MatchCandidate.json
FILES MODIFIED:
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Records.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Validator.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/StableIds.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/Enums.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/ExternalIds.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/main/scala/org/scholarchain/core/SchemaVersions.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/Fixtures.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/ValidationSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/WireContractSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/SchemaVersionsSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/StableIdsSuite.scala
  /Users/brian/Developer/repos/scholarchain/modules/core/src/test/scala/org/scholarchain/core/ExternalIdsSuite.scala
  (The v1 frozen files wire/v1/WorkRecord.json and wire/v1/CoauthorPairCandidate.json are unchanged and kept as superseded records.)
COMMANDS RUN:
  sbt -batch "core/scalafmtAll" "core2_13/test" "core/test"     -> exit 0
  sbt -batch check                                              -> exit 1 (modules/hadoop compile)
  sbt -batch core/test core2_13/test entityResolution/test sparkAnalytics/test storage/test rag/test services/test -> exit 0
  sbt -batch core/scalafmtAll core/scalafmtCheckAll core2_13/test core/test (after a small refactor) -> exit 0
  sbt -batch check (final)                                      -> exit 1 (same 4 hadoop errors)
  python3 (independent computation of golden IDs and the frozen JSON)
TEST RESULTS:
  core (Scala 3): Passed: Total 360, Failed 0, Errors 0. No [warn] lines.
  core2_13 (Scala 2.13): Passed: Total 360, Failed 0, Errors 0.
  entityResolution, sparkAnalytics, storage, rag, services: [success] (they do not reference the changed records).
  sbt check: ledger check passed and the scalafmt check passed in every project. Compilation then failed in hadoop / Compile, so the hadoop tests, the contract tests and markTestRun did not run. .claude/.last-test-run was not refreshed.
VALIDATION RESULTS:
  - Golden MatchCandidateId was computed independently in Python and matches the Scala value:
    MatchCandidateId.of(mention_pair, mention:v1:00853dba003e529ef7295ad10ecae82b, mention:v1:3fe0d2d64d85640503e25bfdc095bcfc, "dblp-base-name", "Synthetic Author") = match-candidate:v1:aac7158dffa4fd74256a524403a22e6a
  - Frozen JSON round-trips (decode == fixture, encode == frozen).
  - Superseded v1 WorkRecord and CoauthorPairCandidate JSON are rejected by current readers.
ASSUMPTIONS:
  - Phase 4 human decision 5 ("use only ... the modules/core contracts") was explicitly widened by the human architect for this one work order, limited to these four core changes. The orchestrator relayed this; nothing was written under docs/agent-runs/.
  - Frozen wire convention: an example lives under wire/v<currentVersion>/. A breaking bump adds a new file rather than editing the old one. Readers accept only the current version (unchanged policy).
  - <ee> and <url> entries are stored verbatim: entity-decoded and trimmed, not normalized. Duplicates are not allowed, so producers keep the first occurrence and preserve source order.
  - A feature that cannot be computed is left out of the list, never defaulted. A MatchCandidate needs at least one feature.
  - Records for one subject pair seen in two blocks get two candidate IDs.
RISKS:
  - Breaking changes: WorkRecord v1 -> v2 and CoauthorPairCandidate v1 -> v2. Any v1 JSON already written (for example local hadoop test outputs) no longer decodes.
  - New WorkRecord rule: when doi is set, it must equal Doi.fromDblpEe(e) for some entry e in eeLinks. A producer that sets doi without passing the ee list is now rejected. This matches the documented meaning of doi.
  - "Left positions hold the left name" can't be checked inside one record, because the record doesn't carry the team's names. The validator only checks structure: no position appears on both sides. Producers must build positions with PairPosition.forNames.
  - MatchSubjectKind covers mention_pair and mention_researcher only. Work-level candidates (for example dblp work vs OpenAlex work) would need a new wire value, which is an additive enum change.
  - The Topic validator does not yet require OpenAlexId.Topic keys when provenance.sourceType is openalex. That would be a stricter rule and was out of scope. Possible follow-up.
MANUAL ACTION REQUIRED: none from the human beyond routing.
BLOCKED-ON OWNER: hadoop-engineer
BLOCKED-ON EVIDENCE: sbt -batch check, exit 1, exact errors:
  [error] -- [E007] Type Mismatch Error: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpRecordBuilder.scala:156:6
  [error] 156 |      prov
  [error]     |      Found:    (prov : org.scholarchain.core.Provenance)
  [error]     |      Required: List[String]
  [error] -- [E172] Type Error: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/dblp/DblpRecordBuilder.scala:169:40
  [error] 169 |    val errors = Contract.validate(work) ++ mentions.flatMap(m => Contract.validate(m))
  [error]     |Ambiguous given instances: both value publicationTeam in object Validator and value graphNode in object Validator match type org.scholarchain.core.Validator[A] of parameter v of method validate in object Contract
  [error] -- [E007] Type Mismatch Error: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pairs/PairExpansion.scala:101:17
  [error] 101 |            team.teamSize,
  [error]     |            Found:    (team.teamSize : Int)
  [error]     |            Required: List[org.scholarchain.core.PairPosition]
  [error] -- [E007] Type Mismatch Error: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/pairs/PairExpansion.scala:102:12
  [error] 102 |            prov
  [error]     |            Found:    (prov : org.scholarchain.core.Provenance)
  [error]     |            Required: Int
  [error] four errors found
  [error] (hadoop / Compile / compileIncremental) Compilation failed
  The E172 at line 169 is a knock-on error from the line 156 mismatch.
  Required hadoop changes (hadoop-engineer's territory):
  (a) DblpRecordBuilder: pass eeLinks and urls, deduplicated with source order kept, after doi.
  (b) PairExpansion: add positions = List(PairPosition.forNames(...)) before teamSize.
  (c) PairConsolidateJob.consolidate: union the instances' positions, sorted by PairPosition.ordering and distinct. Today it compares (workId, year, left, right, teamSize), which still holds.
  (d) Hadoop tests: once the main sources compile, they will hit the same changes. For example, PairsAndTeamsSuite builds WorkRecord(1, ...) positionally; that needs schemaVersion 2 plus the new fields.
  (e) Optional: replace SourceNormalizers.OpenAlexTopic with core OpenAlexId.Topic, and the OpenAlex key list with OpenAlexId.entities.
  (f) J06 can now emit MatchCandidate.
NEXT GATE: hadoop-engineer repairs modules/hadoop against these contracts, then sbt check passes (observed), then code-reviewer reviews the core contract diff.

## Contract changes and schema versions
| Schema | Old | New | Kind |
|---|---|---|---|
| WorkRecord | 1 | 2 | breaking: new required fields, new doi/ee consistency rule |
| CoauthorPairCandidate | 1 | 2 | breaking: new required field |
| MatchCandidate | none | 1 | new |
| Every other schema | unchanged | unchanged | none |
The enum MatchSubjectKind is new. ExternalIdType is unchanged: no arXiv or Semantic Scholar values, and no openalex_topic.

## Field reference for hadoop-engineer (wire JSON name = Scala field name; every field is required on the wire; Options are written as null)

WorkRecord v2. The wire field order is: schemaVersion, workId, dblpKey, publicationType, title, year, venueKey, doi, eeLinks, urls, authorNames, provenance. The positional constructor follows the same order.
- eeLinks: List[String], wire "eeLinks"
  - Contents: dblp <ee> texts, entity-decoded and trimmed, in source order. Use [] when the record has none.
  - Each entry must not be blank, must have no leading or trailing whitespace, and must contain no ISO control characters.
  - The list must have no duplicates.
- urls: List[String], wire "urls"
  - Contents: dblp <url> texts, in source order. Same rules as eeLinks.
- doi (existing): if non-null, it must be canonical AND equal Doi.fromDblpEe(e) for some e in eeLinks.
- schemaVersion must be 2.

CoauthorPairCandidate v2. The wire field order is: schemaVersion, workId, year, left, right, positions, teamSize, provenance. The positional constructor follows the same order.
- positions: List[PairPosition], wire "positions". Rules:
  - must not be empty;
  - strictly increasing by (leftPosition, rightPosition), so sorted and distinct;
  - no position appears both as a leftPosition and as a rightPosition;
  - an unconsolidated instance has exactly 1 element;
  - a consolidated pair carries the union of its instances' positions. Example: names A, B, A at positions 0, 1, 2 give the pair (A, B) with positions [(0,1),(2,1)].
- PairPosition is an embedded value with no schemaVersion and no provenance. Wire: {"leftPosition": Int, "leftMentionId": "mention:v1:...", "rightPosition": Int, "rightMentionId": "mention:v1:..."}
  - Each position must be >= 0 and < teamSize.
  - leftPosition and rightPosition must differ.
  - leftMentionId == MentionId.of(workId, leftPosition); rightMentionId == MentionId.of(workId, rightPosition).
  - leftPosition is the position of the canonical `left` name, so it may be greater than rightPosition.
- Producer helper: PairPosition.forNames(workId, nameA, posA, nameB, posB): Either[String, PairPosition]. It attaches each position to its canonical name. It returns Left for a self pair, equal positions, or a negative position.
- PairPosition.ordering: by leftPosition, then rightPosition.
- schemaVersion must be 2.

MatchCandidate v1 (new). Wire field order: schemaVersion, candidateId, subjectKind, left, right, blockFamily, blockKey, features, provenance. It has no outcome, label, score or confidence field.
- candidateId: MatchCandidateId, wire string "match-candidate:v1:<32 hex>". It must equal MatchCandidateId.of(subjectKind, left, right, blockFamily, blockKey).
- subjectKind: MatchSubjectKind, wire "mention_pair" | "mention_researcher".
- left / right: String.
  - mention_pair: both are MentionIds, with left < right by code point; self pairs are rejected.
  - mention_researcher: left is a MentionId, right is a ResearcherId.
- blockFamily: String matching [a-z0-9][a-z0-9._-]*, for example "dblp-base-name".
- blockKey: String, not blank, with no ISO control characters. Spaces are allowed.
- features: List[MatchFeature], wire [{"name": String, "value": Double}].
  - Must not be empty.
  - Sorted by name (code point), names distinct and not blank.
  - Values must be finite. Negative values are allowed.
  - A feature that cannot be computed is left out of the list.
- provenance: required, standard rules.

OpenAlexId.Topic (not a wire record): OpenAlexEntity('T').
- Topic.normalize(raw): Option[String] strips one https://openalex.org/ prefix (case-insensitive), trims, uppercases, and requires T[1-9][0-9]*.
- Topic.isCanonical(s) checks a stored key.
- OpenAlexId.entities = List(Work, Author, Source, Institution, Topic).

## Stable ID formulas
- New: MatchCandidateId = "match-candidate:v1:" + digest32("match-candidate/v1", subjectKind.wire, left, right, blockFamily, blockKey).
  - For mention_pair, left and right are first put in canonical order with UndirectedPair, so the ID doesn't depend on argument order; self pairs give Left.
  - For mention_researcher, the roles are fixed: mention, then researcher.
  - The run ID, policy and feature values are not inputs, so the ID is stable across runs (suitable for review queues and gold-set labels).
  - Golden values: see VALIDATION RESULTS.
- Unchanged formulas: WorkId, MentionId, ResearcherId, EdgeId, EvidenceId, RunId, LinkId.

## Pair canonicalization
Names: (left, right) = (min, max) by Unicode code point, strictly increasing. Positions and mention IDs follow their names. Example, Sousa0J23 fixture:
  left = "Alípio Mário Jorge" at position 2, right = "Hugo O. Sousa" at position 0,
  so positions = [{"leftPosition":2,"leftMentionId":"mention:v1:5cb0537407cd4b72a93c06e919a259bb","rightPosition":0,"rightMentionId":"mention:v1:34f70b4567c43fb2e857c8765d12d7b3"}].
Frozen JSON samples: /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/WorkRecord.json, /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v2/CoauthorPairCandidate.json, /Users/brian/Developer/repos/scholarchain/modules/core/src/test/resources/wire/v1/MatchCandidate.json.

## Configuration precedence
Unchanged. No configuration contract was touched.
```
