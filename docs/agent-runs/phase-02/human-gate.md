# Phase 2 human gate

## Gate
- No application logic is present.
- `sbt Test/compile` succeeds.
- `sbt check` has a stable definition.
- The module graph does not create cycles.
- Dependency versions appear in one ledger.
- Code review returns APPROVE.
(All met)

## Decisions
- The spec said "Scala 3 everywhere", but Scala deviation was made with instructor approval via Microsoft Teams (see prompt.md): `sparkAnalytics` on Scala 2.13.17, `core` cross-built for Scala 3.3.9 and 2.13.17, all other projects Scala 3.
- Target runtime, where the code will actually run on AWS. Spark jobs target runtime emr-spark-8.1.0 (Spark 4.1.1, Scala 2.13, Java 17). Hadoop MapReduce jobs target runtime EMR 7.x, because the Spark only release doesn't include Hadoop. Both use Hadoop 3.4.x, so it'll work either way.
- Declined the orchestrator's proposal to have domain-engineer add a placeholder test because those first tests belong to Phase 3.
- Repair-01 fixed B1–B6; repair-02 fixed N1 and N2. Both re-reviewed with decision APPROVE.
- Some findings were without fix: N3 (comment inaccuracy only), F3 (empty folders appear when owners add files), F4 (contract tests wired to Spark/Hadoop when needed in Phase 11).
- Deferred to a factory-engineer amendment: F1 (ledger path for dependency-updater; scalafmt bumps stay with build-engineer), F2 (record the Scala deviation in CLAUDE.md and agent files), and an owner for `.sbt/**`. These are all things in the rule files, so a new session with factory-engineer will have to edit them.

## What I reviewed
build-engineer's report, code-reviewer's report, repair-01 and repair-02, and the orchestrator summaries; plus my own git check-ignore check below. Accepted.

## Human verification of .gitignore secret rules
Every secret path matched an ignore rule.
```
git check-ignore -v --no-index .env .env.local terraform.tfvars plan.tfplan .aws/config sub/.docker/config.json a/.config/gh/hosts.yml secrets
```
```
.gitignore:29:.env	.env
.gitignore:30:.env.*	.env.local
.gitignore:61:*.tfvars	terraform.tfvars
.gitignore:58:*.tfplan	plan.tfplan
.gitignore:48:.aws	.aws/config
.gitignore:52:**/.docker/config.json	sub/.docker/config.json
.gitignore:51:**/.config/gh	a/.config/gh/hosts.yml
.gitignore:46:secrets	secrets
```


Placeholder files were un-ignored.
```
git check-ignore -v --no-index env.example terraform.tfvars.example
```
```
.gitignore:26:!*.example	env.example
.gitignore:62:!*.tfvars.example	terraform.tfvars.example
```

## Manual changes
None to agent-generated files.

## Remaining risks
- My Docker image is built for arm64. AWS Fargate can run ARM or Intel, so that has to be decided later in Phase 12.
- EMR versions come from AWS documentation and have not been run on a cluster yet.
- Some Java settings for Hadoop tests can't be checked until tests exist.
- git ignores any folder named `secrets`, `.aws`, `.ssh` or `.gnupg`. storage-engineer (or any other agent) must not name a package `secrets`.
- The guard sometimes has false positives and asks about harmless commands because of matching text. I'm leaving it to let me approve it.

## Accepted commit
See commit.txt.
