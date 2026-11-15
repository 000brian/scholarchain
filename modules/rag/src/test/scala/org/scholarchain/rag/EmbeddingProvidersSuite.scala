package org.scholarchain.rag

import java.io.IOException
import java.net.URI

import scala.collection.mutable

import org.scholarchain.core.config.{EmbeddingProvider, ScholarChainConfig}
import org.scholarchain.rag.embedding._
import org.scholarchain.storage.{Sleeper, StoreException}

/** A fake clock that the recording sleeper advances. */
final class FakeTime {
  var now = 1000000L
  val sleeps: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty[Long]
  val sleeper: Sleeper = (ms: Long) => { sleeps += ms; now += ms }
  val clock: () => Long = () => now
}

/** Scripted HTTP transport: returns the next scripted outcome and records each request. */
final class ScriptedTransport(script: List[Either[IOException, HttpResult]]) extends HttpTransport {
  private var rest = script
  val requests: mutable.ArrayBuffer[(URI, List[(String, String)], ujson.Value)] =
    mutable.ArrayBuffer.empty
  def postJson(uri: URI, headers: List[(String, String)], body: String): HttpResult = {
    requests += ((uri, headers, ujson.read(body)))
    val next = rest.headOption.getOrElse(sys.error("script exhausted"))
    rest = rest.tail
    next.fold(e => throw e, identity)
  }
}

object VoyageResponses {
  def ok(n: Int, dims: Int, reversed: Boolean = false, tokens: Int = 42): HttpResult = {
    val items = (0 until n).map(i =>
      ujson.Obj(
        "object" -> "embedding",
        "index" -> i,
        "embedding" -> ujson.Arr.from(
          (0 until dims).map(j => ujson.Num(if (j == i % dims) 1.0 else 0.0))
        )
      )
    )
    HttpResult(
      200,
      ujson
        .Obj(
          "object" -> "list",
          "data" -> ujson.Arr.from(if (reversed) items.reverse else items),
          "model" -> "voyage-4",
          "usage" -> ujson.Obj("total_tokens" -> tokens)
        )
        .render(),
      None
    )
  }
}

class EmbeddingModelSpecSuite extends munit.FunSuite {
  import EmbeddingModelSpec._

  test("the spec ID encodes provider, model, dimensions, input type and version") {
    val s = of(EmbeddingProvider.Voyage, "voyage-4", 1024, InputType.Document).toOption.get
    assertEquals(s.id, "voyage/voyage-4/1024/document/emb-v1")
    val b = of(EmbeddingProvider.Bedrock, BedrockTitanModel, 1024, InputType.Document).toOption.get
    assertEquals(b.id, "bedrock/amazon.titan-embed-text-v2:0/1024/document/emb-v1")
    assert(s.id.matches("\\S+") && s.id.length <= 1024)
  }

  test("changing any field changes the ID, so every key is re-embedded") {
    val s = of(EmbeddingProvider.Voyage, "voyage-4", 1024, InputType.Document).toOption.get
    val variants = List(
      s.copy(model = "voyage-4-lite"),
      s.copy(dimensions = 256),
      s.copy(inputType = InputType.Query),
      s.copy(specVersion = "emb-v2"),
      s.copy(provider = EmbeddingProvider.Bedrock)
    )
    variants.foreach(v => assertNotEquals(v.id, s.id))
    assertEquals(variants.map(_.id).distinct.size, variants.size)
  }

  test("a query spec is compatible only with vectors of the same document-side spec") {
    val q = of(EmbeddingProvider.Voyage, "voyage-4", 1024, InputType.Query).toOption.get
    assertEquals(q.id, "voyage/voyage-4/1024/query/emb-v1")
    assert(q.compatibleWithStored("voyage/voyage-4/1024/document/emb-v1"))
    assert(!q.compatibleWithStored("voyage/voyage-4-lite/1024/document/emb-v1"))
    assert(!q.compatibleWithStored("bedrock/amazon.titan-embed-text-v2:0/1024/document/emb-v1"))
    assert(!q.compatibleWithStored("voyage/voyage-4/1024/query/emb-v1"))
  }

  test("provider, model and dimension combinations are validated") {
    assert(of(EmbeddingProvider.Voyage, "voyage-4", 1000, InputType.Document).isLeft)
    assert(of(EmbeddingProvider.Voyage, "titan", 1024, InputType.Document).isLeft)
    assert(of(EmbeddingProvider.Voyage, "voyage-4", 2048, InputType.Document).isRight)
    assert(
      of(EmbeddingProvider.Bedrock, "amazon.titan-embed-text-v1", 1024, InputType.Document).isLeft
    )
    assert(of(EmbeddingProvider.Bedrock, BedrockTitanModel, 2048, InputType.Document).isLeft)
    assert(
      of(EmbeddingProvider.Fake, "voyage-4", 1024, InputType.Document).isLeft,
      "a fake must be named fake-*"
    )
    assert(of(EmbeddingProvider.Fake, FakeModel, 5000, InputType.Document).isLeft)
  }

  test("specs from the three configurations") {
    assertEquals(
      fromConfig(RagFixtures.voyageConfig, InputType.Document).map(_.id),
      Right("voyage/voyage-4/1024/document/emb-v1")
    )
    assertEquals(
      fromConfig(RagFixtures.bedrockConfig, InputType.Document).map(_.id),
      Right("bedrock/amazon.titan-embed-text-v2:0/1024/document/emb-v1")
    )
    assertEquals(
      fromConfig(RagFixtures.localConfig, InputType.Document).map(_.id),
      Right("fake/fake-lexical-hash-v1/1024/document/emb-v1")
    )
  }
}

class FakeLexicalEmbedderSuite extends munit.FunSuite {
  private val spec = EmbeddingModelSpec
    .of(EmbeddingProvider.Fake, EmbeddingModelSpec.FakeModel, 1024, InputType.Document)
    .toOption
    .get
  private val e = new FakeLexicalEmbedder(spec, 4)
  private def cos(a: Array[Float], b: Array[Float]) =
    a.zip(b).map { case (x, y) => x.toDouble * y }.sum

  test("deterministic, L2-normalized, of the configured dimension") {
    val v1 = e.embed(Vector("Alice Smith publishes at ACL")).toOption.get.vectors.head
    val v2 = e.embed(Vector("Alice Smith publishes at ACL")).toOption.get.vectors.head
    assertEquals(v1.toList, v2.toList)
    assertEquals(v1.length, 1024)
    assertEqualsDouble(cos(v1, v1), 1.0, 1e-5)
    assertEquals(e.modelId, "fake/fake-lexical-hash-v1/1024/document/emb-v1")
  }

  test("lexical: shared words score higher than unrelated words; case and punctuation ignored") {
    val Seq(a, b, c) =
      e.embed(Vector("Alice Smith, ACL.", "alice smith acl", "Bob Jones CVPR")).toOption.get.vectors
    assertEqualsDouble(cos(a, b), 1.0, 1e-5)
    assert(cos(a, c) < 0.5)
  }

  test("tokens are lower-cased letter-or-digit runs; IDs split at ':'") {
    assertEquals(
      FakeLexicalEmbedder.tokens("Researcher X (researcher:v1:ab12), P=4."),
      Vector("researcher", "x", "researcher", "v1", "ab12", "p", "4")
    )
  }

  test("batch limit and the empty-token text") {
    assert(e.embed(Vector.fill(5)("x")).isLeft)
    val v = e.embed(Vector("...")).toOption.get.vectors.head
    assertEqualsDouble(cos(v, v), 1.0, 1e-6)
  }
}

class VoyageEmbedderSuite extends munit.FunSuite {
  private val settings = RagFixtures.voyageConfig.embedding.get
  private val spec =
    EmbeddingModelSpec.fromConfig(RagFixtures.voyageConfig, InputType.Document).toOption.get
  private val key = "test-key-value-123"
  private val env: String => Option[String] = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> key).get

  private def voyage(
      script: List[Either[IOException, HttpResult]],
      s: EmbeddingModelSpec = spec,
      time: FakeTime = new FakeTime
  ) = {
    val t = new ScriptedTransport(script)
    val e =
      VoyageEmbedder.create(s, settings, env, t, time.sleeper, time.clock).fold(fail(_), identity)
    (e, t, time)
  }

  test(
    "request: model, input type, output dimension, float, no truncation; key only in the header"
  ) {
    val (e, t, _) = voyage(List(Right(VoyageResponses.ok(2, 1024))))
    val r = e.embed(Vector("a", "b")).fold(f => fail(f.message), identity)
    assertEquals(r.vectors.size, 2)
    val (uri, headers, body) = t.requests.head
    assertEquals(uri.toString, "https://api.voyageai.com/v1/embeddings")
    assertEquals(body("model").str, "voyage-4")
    assertEquals(body("input_type").str, "document")
    assertEquals(body("output_dimension").num.toInt, 1024)
    assertEquals(body("output_dtype").str, "float")
    assertEquals(body("truncation").bool, false)
    assertEquals(body("input").arr.map(_.str).toList, List("a", "b"))
    assertEquals(headers, List("Authorization" -> s"Bearer $key"))
    assert(!body.render().contains(key))
    assertEquals(e.modelId, "voyage/voyage-4/1024/document/emb-v1")
    assertEquals(e.usage.reportedTokens, 42L)
  }

  test("query-side embedder sends input_type query") {
    val (e, t, _) = voyage(List(Right(VoyageResponses.ok(1, 1024))), spec.forInput(InputType.Query))
    e.embed(Vector("who works with Alice?"))
    assertEquals(t.requests.head._3("input_type").str, "query")
  }

  test("response order is restored from data[i].index") {
    val (e, _, _) = voyage(List(Right(VoyageResponses.ok(3, 1024, reversed = true))))
    val vs = e.embed(Vector("a", "b", "c")).toOption.get.vectors
    assertEquals(vs.map(_.indexWhere(_ == 1.0f)).toList, List(0, 1, 2))
  }

  test("429 and 5xx are retried with bounded backoff (Retry-After honoured), then succeed") {
    val (e, t, time) = voyage(
      List(
        Left(new IOException("reset")),
        Right(HttpResult(429, "slow down", Some(3))),
        Right(HttpResult(503, "busy", None)),
        Right(VoyageResponses.ok(1, 1024))
      )
    )
    val r = e.embed(Vector("a")).fold(f => fail(f.message), identity)
    assertEquals(r.retries, 3)
    assertEquals(t.requests.size, 4)
    assertEquals(time.sleeps.toList, List(1000L, 3000L, 4000L))
  }

  test("retries stop at embedding.maxRetries and report the failure with the retry count") {
    val (e, t, _) = voyage(List.fill(6)(Right(HttpResult(500, "error", None))))
    val f = e.embed(Vector("a")).swap.toOption.get
    assertEquals(f.retries, settings.maxRetries)
    assertEquals(t.requests.size, settings.maxRetries + 1)
    assert(f.message.contains("voyage HTTP 500"))
  }

  test("client errors are not retried and never echo the API key") {
    val (e, t, _) = voyage(List(Right(HttpResult(401, s"""{"detail":"bad key $key"}""", None))))
    val f = e.embed(Vector("a")).swap.toOption.get
    assertEquals(t.requests.size, 1)
    assertEquals(f.retries, 0)
    assert(!f.message.contains(key), f.message)
    assert(f.message.contains("<redacted>"))
    assert(!e.toString.contains(key))
  }

  test("malformed or wrong-shaped responses fail closed") {
    val (e1, _, _) = voyage(List(Right(HttpResult(200, "not json", None))))
    assert(e1.embed(Vector("a")).isLeft)
    val (e2, _, _) = voyage(List(Right(VoyageResponses.ok(1, 512))))
    assert(e2.embed(Vector("a")).swap.toOption.get.message.contains("length is not 1024"))
    val (e3, _, _) = voyage(List(Right(VoyageResponses.ok(1, 1024))))
    assert(
      e3.embed(Vector("a", "b")).swap.toOption.get.message.contains("1 embeddings for 2 texts")
    )
  }

  test("the key comes only from the env var named in config; missing name or value fails") {
    val t = new ScriptedTransport(Nil)
    val time = new FakeTime
    val noValue = VoyageEmbedder.create(spec, settings, _ => None, t, time.sleeper, time.clock)
    assertEquals(
      noValue.left.toOption,
      Some("environment variable SCHOLARCHAIN_VOYAGE_API_KEY is not set")
    )
    val noName = VoyageEmbedder.create(
      spec,
      settings.copy(apiKeyEnvVar = None),
      env,
      t,
      time.sleeper,
      time.clock
    )
    assert(noName.isLeft)
    val looked = mutable.ArrayBuffer.empty[String]
    VoyageEmbedder.create(
      spec,
      settings,
      n => { looked += n; Some(key) },
      t,
      time.sleeper,
      time.clock
    )
    assertEquals(looked.toList, List("SCHOLARCHAIN_VOYAGE_API_KEY"))
  }

  test("batches above the configured size are refused; large batches split by estimated tokens") {
    val (e, _, _) = voyage(Nil)
    assertEquals(e.maxBatchSize, settings.batchSize)
    assert(e.embed(Vector.fill(settings.batchSize + 1)("x")).isLeft)
    val groups = VoyageEmbedder.requestGroups(Vector.fill(4)("z" * 140000))
    assertEquals(groups.map(_.size), Vector(2, 2))
  }

  test("the client-side rate limiter holds requests per minute") {
    val time = new FakeTime
    val limiter = new RateLimiter(2, 1000000, time.clock, time.sleeper)
    limiter.acquire(10); limiter.acquire(10)
    assert(time.sleeps.isEmpty)
    limiter.acquire(10)
    assertEquals(time.sleeps.toList, List(60000L))
    val tok = new RateLimiter(100, 100, time.clock, time.sleeper)
    tok.acquire(80)
    tok.acquire(30)
    assertEquals(time.sleeps.size, 2)
    tok.acquire(500) // larger than the budget: let through alone in a fresh window
    assertEquals(time.sleeps.size, 3)
  }
}

final class ScriptedInvoker(script: List[Either[StoreException, String]]) extends BedrockInvoker {
  private var rest = script
  val calls: mutable.ArrayBuffer[(String, ujson.Value)] = mutable.ArrayBuffer.empty
  def invoke(modelId: String, jsonBody: String): String = {
    calls += ((modelId, ujson.read(jsonBody)))
    val n = rest.head
    rest = rest.tail
    n.fold(e => throw e, identity)
  }
}

class BedrockTitanEmbedderSuite extends munit.FunSuite {
  private val settings = RagFixtures.bedrockConfig.embedding.get
  private val spec =
    EmbeddingModelSpec.fromConfig(RagFixtures.bedrockConfig, InputType.Document).toOption.get
  private def ok(dims: Int) = ujson
    .Obj(
      "embedding" -> ujson.Arr.from(Seq.fill(dims)(ujson.Num(0.03125))),
      "inputTextTokenCount" -> 7
    )
    .render()

  test("one InvokeModel call per text with inputText, dimensions and normalize") {
    val inv = new ScriptedInvoker(List(Right(ok(1024)), Right(ok(1024))))
    val time = new FakeTime
    val e = new BedrockTitanEmbedder(spec, settings, inv, time.sleeper, time.clock)
    val r = e.embed(Vector("a", "b")).fold(f => fail(f.message), identity)
    assertEquals(r.vectors.size, 2)
    assertEquals(inv.calls.size, 2)
    assertEquals(inv.calls.head._1, "amazon.titan-embed-text-v2:0")
    assertEquals(
      inv.calls.head._2,
      ujson.Obj("inputText" -> "a", "dimensions" -> 1024, "normalize" -> true)
    )
    assertEquals(e.modelId, "bedrock/amazon.titan-embed-text-v2:0/1024/document/emb-v1")
    assertEquals(e.usage.reportedTokens, 14L)
  }

  test("throttling is retried (bounded); permanent errors and wrong dimensions fail") {
    val time = new FakeTime
    val inv = new ScriptedInvoker(
      List(Left(new StoreException("ThrottlingException", true)), Right(ok(1024)))
    )
    val e = new BedrockTitanEmbedder(spec, settings, inv, time.sleeper, time.clock)
    assertEquals(e.embed(Vector("a")).map(_.retries), Right(1))
    val bad = new BedrockTitanEmbedder(
      spec,
      settings,
      new ScriptedInvoker(List(Left(new StoreException("AccessDenied", false)))),
      time.sleeper,
      time.clock
    )
    assertEquals(bad.embed(Vector("a")).left.map(_.retries), Left(0))
    val dims = new BedrockTitanEmbedder(
      spec,
      settings,
      new ScriptedInvoker(List(Right(ok(256)))),
      time.sleeper,
      time.clock
    )
    assert(dims.embed(Vector("a")).swap.toOption.get.message.contains("expected 1024"))
  }
}

class ProviderSwitchSuite extends munit.FunSuite {
  private val time = new FakeTime
  private def wiring(env: Map[String, String] = Map("SCHOLARCHAIN_VOYAGE_API_KEY" -> "k")) =
    EmbeddingProviders.Wiring(
      env = env.get,
      sleeper = time.sleeper,
      clock = time.clock,
      httpTransport = () => new ScriptedTransport(Nil),
      bedrockInvoker = _ => new ScriptedInvoker(Nil)
    )

  test("embedding.provider selects voyage, bedrock or fake") {
    val v = EmbeddingProviders
      .create(RagFixtures.voyageConfig, InputType.Document, wiring())
      .fold(fail(_), identity)
    assert(v.port.isInstanceOf[VoyageEmbedder])
    assertEquals(v.port.modelId, "voyage/voyage-4/1024/document/emb-v1")
    val b = EmbeddingProviders
      .create(RagFixtures.bedrockConfig, InputType.Document, wiring())
      .fold(fail(_), identity)
    assert(b.port.isInstanceOf[BedrockTitanEmbedder])
    val f = EmbeddingProviders
      .create(RagFixtures.localConfig, InputType.Query, wiring())
      .fold(fail(_), identity)
    assert(f.port.isInstanceOf[FakeLexicalEmbedder])
    assertEquals(f.port.modelId, "fake/fake-lexical-hash-v1/1024/query/emb-v1")
    assertEquals(f.port.dimensions, 1024)
  }

  test("voyage without its key variable fails without touching the network") {
    val r =
      EmbeddingProviders.create(RagFixtures.voyageConfig, InputType.Document, wiring(Map.empty))
    assertEquals(
      r.left.toOption,
      Some("environment variable SCHOLARCHAIN_VOYAGE_API_KEY is not set")
    )
  }

  test("a configuration without the embedding section, or with a mismatched model, fails") {
    val c: ScholarChainConfig = RagFixtures.localConfig.copy(embedding = None)
    assert(EmbeddingProviders.create(c, InputType.Document, wiring()).isLeft)
    val wrong = RagFixtures.localConfig.copy(models =
      RagFixtures.localConfig.models.copy(embeddingModelId = "voyage-4")
    )
    assert(EmbeddingProviders.create(wrong, InputType.Document, wiring()).isLeft)
  }

  test("the voyage config names the key variable and never holds a key") {
    val e = RagFixtures.voyageConfig.embedding.get
    assertEquals(e.apiKeyEnvVar, Some("SCHOLARCHAIN_VOYAGE_API_KEY"))
    assertEquals(e.provider, "voyage")
    assertEquals(RagFixtures.voyageConfig.models.embeddingModelId, "voyage-4")
  }
}
