# Instructor approvals and deviations

Human-owned record (docs/agent-runs/**). Each entry gives the date, how the approval was received, the question, and the instructor's answer verbatim.

## 2026-11-09: Voyage AI embeddings and Anthropic API synthesis (Bedrock quota is 0)

**Received by:** Danny Nguyen, Microsoft Teams 
My account has the same restriction (all Bedrock quotas 0 in Service Quotas), so I follow the terms of this answer exactly and record that here as a deviation from the AWS-only spec.

**Situation:** Hi Professor,
 
My AWS account (Paid plan) has every Amazon Bedrock quota at 0 in us-east-2, including Claude Haiku 4.5, Titan Text Embeddings V2 and Nova Micro. AWS Support says this is a new-account restriction and referred the increase to the Sales team. I have submitted that request, but it may not be approved before the deployment.
 
As a fallback, I plan to keep everything else on AWS and swap only two external calls:
1. LLM synthesis: Claude Haiku 4.5 through the Anthropic API directly instead of Bedrock (about USD 1.50 total).
2. Embeddings: Voyage AI (voyage-4-lite, 256 dimensions) instead of Titan (free tier, about USD 0).
The code keeps the Bedrock client behind a switch, so if the quotas are approved in time, I will use Bedrock.
 
Questions:
1. Is using these non-AWS APIs acceptable for the project?
2. Should their cost (about USD 1.50, billed outside AWS) be counted toward the recommended USD 25 AWS budget, or reported separately?
 

**Instructor's answer (verbatim):**

> Yes, I approve using the Anthropic API for synthesis and Voyage AI for embeddings given the Bedrock quota restriction you described. Keep the remaining infrastructure and processing on AWS, and satisfy the project's existing functionality, evaluation, and reproducibility requirements. You may proceed without waiting for AWS to approve the quota increase.
>
> Document the restriction and your fallback configuration in the report and README. Include the providers, exact model identifiers, embedding dimensions, and instructions for selecting and running each configuration. Retain the Bedrock switch as planned. If you change embedding models, regenerate the stored embeddings and rebuild the affected vector index so that stored vectors and query vectors use the same model and configuration.
>
> Count external API charges toward the $25 overall project spending limit. Report AWS, Anthropic, and Voyage charges as separate items, followed by the combined total. Your estimated $1.50 therefore belongs in that total; any service that incurs no charge should be recorded as $0, with its usage documented.
>
> The $25 limit is a ceiling, not a spending target. Monitor actual charges across providers and stop paid resources when your experiments are complete. Moving a bill outside AWS does not make it disappear.

**How it is applied:** embedding provider `voyage` (`voyage-4`, 1024 dimensions) by default; `bedrock` (`amazon.titan-embed-text-v2:0`, 1024 dimensions) kept as a switch. Synthesis and query planning use the Anthropic API with model `claude-haiku-5-5` from Phase 9; `bedrock` (`anthropic.claude-haiku-5-5`) is kept as a switch. Embeddings first used in docs/agent-runs/phase-07/prompt.md, decision 7; synthesis in docs/agent-runs/phase-09/prompt.md, decision 5d. Phase 9 cost: Anthropic about $0.06, Voyage $0, AWS $0 (docs/agent-runs/phase-09/human-gate.md).

## 2026-11-24: Fixes for TA autograder findings committed as part of Phase 17

**Received by:** Brian Wilk, in person after class

**Situation:** I ran the student autograder (V1 1.0.3, V2 2.3.0, V3 3.0.0) on the repository during Phase 17, after its release candidate was tested, and it reported some failed and warning checks.

**Question:** Can I commit the fixes to the checks I failed from the autograder as a part of Phase 17?

**Instructor's answer (paraphrased):**

> Yes.

**How it is applied:** documentation-only fixes and explanations, with no change to code, scripts, hooks, tests or Terraform, so the tested release candidate is unchanged. Every finding and its answer is in docs/agent-runs/phase-17/autograder-response.md.

## 2026-11-24: Repository made public before the deadline

**Received by:** all of the class in lecture

**Question:** N/A

**Instructor's answer (paraprahsed):**

> Because of intellectual property laws, the repository can be made public at any time.

**How it is applied:** the repository was made public on 2026-11-24 at about 11 AM CST, before the submission deadline. The history was scanned for identifiers first (docs/agent-runs/phase-17/runbook-log.md, E12 history scan).

## 2026-11-24: Written confirmation (Microsoft Teams): code and documentation fixes in Phase 17

**Received by:** Brian Wilk, Microsoft Teams

**Question:** With the extension, I'd like to fix a few autograder findings in code rather than only documenting them. These would be code changes, made by the owning agents with tests and review, and I'd re-run my full fresh-clone build and test checks afterwards. Is it okay to do these code fixes as part of Phase 17, or would you prefer I only document them as known limitations?

**Instructor's answer (verbatim):**

> Your proposed file, docs/agent-runs/phase-17/autograder-response.md, is an appropriate location for documenting each finding, its classification, corrective action, and supporting evidence.
Yes, you may make code and documentation fixes during Phase 17, especially now that the deadline has been extended. There is no reason to preserve known defects merely to comply with an artificial phase boundary. Please ensure that the following requirements hold trye.
Changes are implemented through the appropriate agents, tested, and reviewed.
Each change is documented with its rationale and corresponding autograder finding.
You rerun the relevant tests and verify the complete project from a fresh clone.
You distinguish genuine implementation defects from autograder false positives rather than modifying correct code simply to satisfy an incorrect check.
Remember that the autograder is a tool for evaluating your project, not an infallible authority. Identifying and explaining its mistakes is itself evidence of sound engineering judgment. Please proceed with the fixes and push your validated commits well before the deadline.

**How it is applied:** every fix is made by its owning agent, tested and reviewed; each is documented in docs/agent-runs/phase-17/autograder-response.md with its rationale and finding; the project is re-verified from a fresh clone; findings are classified as genuine defects or autograder false positives, and correct code is not changed to satisfy an incorrect check.

## Earlier approvals 

- Scala 2.13 for sparkAnalytics, core cross-built (Microsoft Teams; see docs/agent-runs/phase-02/prompt.md, decision 1).
- $25 total ceiling across all providers; Budgets alerts at $10, $15 and $20; own AWS account; smaller datasets allowed with documented criteria.
- Bounded enrichment fetch (DOI-driven OpenAlex, Crossref, ORCID) allowed.
