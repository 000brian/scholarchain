STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 17, session 3, repair round R3, minor repair pass: code-reviewer Part B minor finding 1 (stale J05 scaladoc in BlockingJob.scala). Comments only, no code change.
FILES CREATED: none in the repository (this report only, in the scratchpad)
FILES MODIFIED: /Users/brian/Developer/repos/scholarchain/modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala (scaladoc only, lines 323-330 and 476-479)
COMMANDS RUN:
- awk / grep / git diff -U0 (read-only) to check line widths, cited line numbers and remaining TwoFieldPrefix references
- sbt -batch "hadoop/Test/compile" -> [success], compiled 1 Scala source (Nov 25, 2026, 12:20:36 AM)
- sbt -batch "hadoop/testOnly org.scholarchain.hadoop.OversizeBlockStrategySuite" -> Passed: Total 10, Failed 0, Errors 0, Passed 10 (Nov 25, 2026, 12:22:42 AM)
TEST RESULTS: hadoop/Test/compile success; OversizeBlockStrategySuite 10/10 passed, 0 failed, 0 errors. Full sbt check not run (per the work order).
VALIDATION RESULTS:
- The PostToolUse scalafmt hook reformatted the file after each Edit; the final text is the formatter's output (all changed lines are 100 columns or fewer, greedily wrapped).
- File line count unchanged (763 before, 763 after). Cited lines checked after the edit: 358 sizeKey scaladoc, 472 end of BlockingGroupingComparator object, 494 "Oversize strategy" paragraph start, 510 end of it, 558-560 oversizeKeys.foreach, 564-565 setPartitionerClass/setGroupingComparatorClass, 588 BlockingMapper.line, 599 ks.foreach, 614/616, 648 BlockingReducer.reduce, 650 size-marker comment, 659, 744: all at the same lines as before.
- Remaining "TwoFieldPrefix" mentions in BlockingJob.scala are correct: :385 (partitionPrefix calls TwoFieldPrefixPartitioner.prefix for block keys, which is code) and :408 (states block member keys group as with TwoFieldPrefixGroupingComparator, which is true).
ASSUMPTIONS: The new text matches the code: BlockingPartitioner hashes BlockingSort.partitionPrefix (block and marker keys: `<family>\t<block key>`; members: `<family>\t<block key>\t<mention ID>`), BlockingGroupingComparator groups on BlockingSort.groupFields (2 for block keys, 3 for `#size` markers, 4 for `#member` keys).
RISKS: None to behavior (comment-only change; scaladoc does not reach the bytecode). scalafmtCheck is left to test-engineer's sbt check; the hook already applied scalafmt.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer full sbt check (wave), then code-reviewer if required.

## Change

BlockingSort scaladoc (lines 321-331, same 11 lines) now reads, in substance: partitioned by [[BlockingPartitioner]] and grouped by [[BlockingGroupingComparator]] on `<family>\t<block key>` (2 fields), one reduce call per block sorted by mention ID then content hash; a block's partition equals the Phase 4 one; oversize keys use [[memberKey]] (4-field member groups) and [[sizeKey]] (3-field marker groups), see [[groupFields]].

J05 scaladoc (lines 476-479, paragraph 476-492 keeps its 17 lines) now reads: Map out: [[BlockingSort.key]] ([[BlockingPartitioner]] and [[BlockingGroupingComparator]]: one reduce call per 2-field block `<family>\t<key>`, values in a fixed order, so every output file is byte-deterministic; members of oversize keys go to 4-field member groups and 3-field marker groups instead, see below) -> [[BlockMember]] JSON per configured family ... (rest unchanged in wording, reflowed by scalafmt within lines 479-481).

## Line-shift list

None. Every line range cited by documentation (:321-331, :358-472, :476-480, :494-510, :558-560, :564-565, :588-616, :599-614, :648-744, :650-659) is at the same line numbers as before this edit. Note for documentation-engineer: words inside lines 476-481 were reflowed (the J05 "Map in/Map out/Reduce" sentence now spans 476-482 with the same paragraph end at 492).

## INTERFACE HANDOFF

- Files changed: modules/hadoop/src/main/scala/org/scholarchain/hadoop/blocking/BlockingJob.scala (scaladoc only).
- Public signatures: unchanged.
- Fixtures: none added or changed.
- Thread-safety: unchanged.
- Limits: unchanged.

## Job list / counters / reject reasons / sample-run evidence

No job, counter, reject reason or output schema changed. No sample run needed for a comment-only change; OversizeBlockStrategySuite (which runs J05p/J05 local layouts and compares outputs byte for byte) passed 10/10.

## Proposed later

None.
