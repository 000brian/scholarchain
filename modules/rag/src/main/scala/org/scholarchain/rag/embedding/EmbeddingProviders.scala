package org.scholarchain.rag.embedding

import org.scholarchain.core.config.{EmbeddingProvider, ScholarChainConfig}
import org.scholarchain.storage.Sleeper
import org.scholarchain.storage.aws.AwsClientSettings
import org.scholarchain.storage.ports.EmbeddingPort

/** The one switch `embedding.provider` (decision 7, instructor approval of 2026-11-09):
  *
  * | provider           | model (`models.embeddingModelId`) | dimensions | notes                                    |
  * |:-------------------|:----------------------------------|:-----------|:-----------------------------------------|
  * | `voyage` (default) | `voyage-4`                        | 1024       | input type `document` / `query`; API key |
  * |                    |                                   |            | from the env var NAMED in config         |
  * | `bedrock`          | `amazon.titan-embed-text-v2:0`    | 1024       | normalized, one text per call            |
  * | `fake`             | `fake-lexical-hash-v1`            | any        | lexical, local tests and local runs only |
  *
  * Network clients are created only for the selected provider.
  */
object EmbeddingProviders {

  /** Client timeouts for live providers (bounded; not configuration). */
  val ConnectTimeoutMillis = 10000
  val RequestTimeoutMillis = 60000

  final case class Wiring(
      env: String => Option[String] = sys.env.get,
      sleeper: Sleeper = Sleeper.real,
      clock: () => Long = () => System.currentTimeMillis(),
      httpTransport: () => HttpTransport = () =>
        new JdkHttpTransport(ConnectTimeoutMillis, RequestTimeoutMillis),
      bedrockInvoker: AwsClientSettings => BedrockInvoker = SdkBedrockInvoker.create(_)
  )

  /** An embedder plus its usage counters. */
  final case class Created(port: EmbeddingPort, spec: EmbeddingModelSpec, usage: EmbeddingUsage)

  def create(
      config: ScholarChainConfig,
      inputType: InputType,
      wiring: Wiring = Wiring()
  ): Either[String, Created] =
    for {
      settings <- config.embedding.toRight("embedding section is not configured")
      spec <- EmbeddingModelSpec.fromConfig(config, inputType)
      created <- spec.provider match {
        case EmbeddingProvider.Fake =>
          val e = new FakeLexicalEmbedder(spec, settings.batchSize)
          Right(Created(e, spec, e.usage))
        case EmbeddingProvider.Voyage =>
          VoyageEmbedder
            .create(
              spec,
              settings,
              wiring.env,
              wiring.httpTransport(),
              wiring.sleeper,
              wiring.clock
            )
            .map(e => Created(e, spec, e.usage))
        case EmbeddingProvider.Bedrock =>
          val aws = AwsClientSettings(
            config.aws.region,
            ConnectTimeoutMillis,
            RequestTimeoutMillis,
            RequestTimeoutMillis,
            maxAttempts = 1
          )
          val e = new BedrockTitanEmbedder(
            spec,
            settings,
            wiring.bedrockInvoker(aws),
            wiring.sleeper,
            wiring.clock
          )
          Right(Created(e, spec, e.usage))
      }
    } yield created
}
