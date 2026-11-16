package org.scholarchain.rag.runtime

import org.scholarchain.core.config.LlmProvider

/** Live model probes. Every test is tagged `RequiresAws` (needs a live external API), so
  * `sbt check` excludes it; it runs only through `sbt awsTest` with the provider's credentials, and
  * is bounded to at most 3 requests. Never run by agents.
  *
  * Anthropic: needs the key in the environment variable named by `llm.apiKeyEnvVar` of
  * `rag-runtime-anthropic-v1.json` (SCHOLARCHAIN_ANTHROPIC_API_KEY). Bedrock: needs AWS credentials
  * with InvokeModel on `anthropic.claude-haiku-5-5` (this account's quota is 0).
  */
class LiveLlmAwsSuite extends munit.FunSuite {
  import RuntimeFixtures._
  private val RequiresAws = new munit.Tag("RequiresAws")

  private lazy val anthropic = RagFixturesConfig.anthropic
  private lazy val ctx =
    RuntimeContext.from(anthropic, sparkRun, Set(Full)).fold(e => fail(e), identity)
  private lazy val prompts = Prompts.locate().flatMap(Prompts.loadSet).fold(e => fail(e), identity)

  test(
    "Anthropic claude-haiku-5-5: a planner draft that validates (at most 3 requests)".tag(
      RequiresAws
    )
  ) {
    val rule = new RulePlanner(ctx.windows, ctx.bounds)
    val model = LlmFactory.create(anthropic, rule).fold(e => fail(e), identity)
    assertEquals(model.provider, LlmProvider.Anthropic)
    val out = new QueryPlanner(model, prompts.planner, rule, ctx.copy(maxStructuredRetries = 1))
      .plan("Which researchers have the most distinct coauthors in 2021-2023?")
    assert(model.usage.requests <= 3L, s"requests ${model.usage.requests}")
    assertEquals(
      out.source,
      org.scholarchain.core.PlanSource.Llm,
      out.attempts.map(_.toJson.render()).mkString("\n")
    )
    assertEquals(out.draft.windowLabel, Some("w2021-2023"))
    assert(out.attempts.exists(_.inputTokens.isDefined), "usage.input_tokens is read")
  }

  test(
    "Bedrock anthropic.claude-haiku-5-5: one InvokeModel request parses (at most 3 requests)".tag(
      RequiresAws
    )
  ) {
    val bedrockConfig =
      anthropic.copy(llm = anthropic.llm.map(_.copy(provider = "bedrock", apiKeyEnvVar = None)))
    val model = LlmFactory
      .create(bedrockConfig, new RulePlanner(ctx.windows, ctx.bounds))
      .fold(e => fail(e), identity)
    assertEquals(model.modelId, "anthropic.claude-haiku-5-5")
    val r = model.complete(
      ModelRequest(
        ModelTask.Planner,
        "Reply with the JSON object {\"ok\": true} only.",
        "ping",
        32,
        PlannerFakeInput("ping")
      )
    )
    assert(model.usage.requests <= 3L)
    assert(r.isRight, r.toString)
  }
}
