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

## Earlier approvals (add the details you have)

- Scala 2.13 for sparkAnalytics, core cross-built (Microsoft Teams; see docs/agent-runs/phase-02/prompt.md, decision 1).
- $25 total ceiling across all providers; Budgets alerts at $10, $15 and $20; own AWS account; smaller datasets allowed with documented criteria.
- Bounded enrichment fetch (DOI-driven OpenAlex, Crossref, ORCID) allowed.
