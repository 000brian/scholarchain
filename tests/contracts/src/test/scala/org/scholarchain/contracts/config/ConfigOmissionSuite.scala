package org.scholarchain.contracts.config

import java.nio.charset.StandardCharsets

import org.scholarchain.core.config.ConfigLoader

/** Category 21 (configuration omissions), over the shipped test configurations of storage, rag and
  * services (on the contractTests classpath since Phase 11 step 0): every key removed one at a
  * time, and every string value replaced by a sentinel. A refusal names the key; it never repeats
  * the value it refused.
  */
class ConfigOmissionSuite extends munit.FunSuite {

  private val Files = List(
    "/config/services-local-v1.json",
    "/config/rag-runtime-local-v2.json",
    "/config/storage-local-v1.json"
  )

  /** Looks like a secret pasted where a name belongs: invalid for every name rule in the loader. */
  private val Sentinel = "Sk-Live PHASE11/SENTINEL+value"

  private def text(resource: String): String = {
    val in = getClass.getResourceAsStream(resource)
    assert(in != null, s"$resource is not on the contractTests test classpath")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
  }

  /** Every leaf path (objects recurse; arrays and scalars are leaves) with its value. */
  private def leaves(prefix: List[String], v: ujson.Value): List[(List[String], ujson.Value)] =
    v match {
      case o: ujson.Obj => o.value.toList.flatMap { case (k, x) => leaves(prefix :+ k, x) }
      case other        => List(prefix -> other)
    }

  private def edited(src: String, path: List[String])(f: (ujson.Obj, String) => Unit): String = {
    val root = ujson.read(src)
    // the Obj itself (`.obj` would convert to a copy, and the edit would be lost)
    val parent = path.init.foldLeft(root)((v, k) => v(k)) match {
      case o: ujson.Obj => o
      case other        => sys.error(s"${path.init.mkString(".")} is not an object: $other")
    }
    f(parent, path.last)
    val out = root.render()
    assertNotEquals(out, ujson.read(src).render(), s"the edit of ${path.mkString(".")} was lost")
    out
  }

  private def load(name: String, t: String) =
    ConfigLoader.load(t, name, Map.empty).left.map(_.message)

  test("each shipped test configuration loads as is") {
    Files.foreach(f => assert(load(f, text(f)).isRight, s"$f: ${load(f, text(f))}"))
  }

  test("removing any key one at a time either still loads or is refused naming that key") {
    // non-vacuous: removing a required key is refused
    val f0 = "/config/services-local-v1.json"
    assert(
      load(f0, edited(text(f0), List("aws", "region"))((o, k) => { o.value.remove(k); () })).isLeft
    )
    val unnamed = Files.flatMap { f =>
      val src = text(f)
      leaves(Nil, ujson.read(src)).collect {
        case (p, _) if p.nonEmpty =>
          load(f, edited(src, p)((o, k) => { o.value.remove(k); () })) match {
            case Right(_)                               => None
            case Left(m) if m.contains(p.mkString(".")) => None
            case Left(m)                                => Some(s"$f ${p.mkString(".")}: $m")
          }
      }.flatten
    }
    assertEquals(unnamed, Nil)
  }

  test("removing a whole section is refused naming the section, or the section is optional") {
    val unnamed = Files.flatMap { f =>
      val src = text(f)
      ujson.read(src).obj.keys.toList.flatMap { k =>
        load(f, edited(src, List(k))((o, x) => { o.value.remove(x); () })) match {
          case Right(_)                 => None
          case Left(m) if m.contains(k) => None
          case Left(m)                  => Some(s"$f $k: $m")
        }
      }
    }
    assertEquals(unnamed, Nil)
  }

  /** The string leaves whose refusal repeats the sentinel value. */
  private def echoing(filter: List[String] => Boolean): List[String] =
    Files.flatMap { f =>
      val src = text(f)
      leaves(Nil, ujson.read(src)).collect {
        case (p, ujson.Str(_)) if filter(p) =>
          load(f, edited(src, p)((o, k) => o(k) = Sentinel)) match {
            case Left(m) if m.contains(Sentinel) => Some(s"$f ${p.mkString(".")}")
            case _                               => None
          }
      }.flatten
    }.distinct

  private def isEnvVarName(p: List[String]) = p.last.endsWith("EnvVar")

  // T-08 owner: domain-engineer (ConfigLoader.scala:715 `$field: '$n' must match $EnvVarName`):
  // a key pasted where its environment-variable NAME belongs is written back in the start-up
  // error. Same class as U-6 (service.jobQueueUrl), which covers only that one field.
  // Repaired in Phase 11 step 3d; the .fail mark was removed in step 4.
  test("T-08 an invalid *EnvVar value (a pasted key) is never repeated in the refusal") {
    val bad = echoing(isEnvVarName)
    // the specific wrong value: every apiKeyEnvVar / readOnlyApiKeyEnvVar / embedding key field
    assertEquals(bad, Nil)
  }

  // T-09 owner: domain-engineer (ConfigLoader.scala:546-829, `'$value'` in the messages of the
  // name rules: bucket, table, endpoint, Region, labels, prefixes, index names). A refused value is
  // written to the start-up log; for a bucket or a table that is the resource name itself.
  // Repaired in Phase 11 step 3d; the .fail mark was removed in step 4.
  test("T-09 no other refused string value is repeated in the refusal (U-6 excluded)") {
    val bad = echoing(p => !isEnvVarName(p) && p.mkString(".") != "service.jobQueueUrl")
    assertEquals(bad, Nil)
  }

  // Known U-6 (domain P1, decision 8 step 3d). Owner: domain-engineer. Repaired in step 3d; the
  // .fail mark was removed in step 4.
  test("U-6 service.jobQueueUrl refusal names the field and the rule, never the value") {
    val f = "/config/services-local-v1.json"
    val src = text(f)
    val withUrl = edited(src, List("service", "jobQueueUrl"))((o, k) => o(k) = Sentinel)
    val m = load(f, withUrl).fold(identity, _ => "")
    assert(m.contains("service.jobQueueUrl"), m)
    assert(!m.contains(Sentinel), m)
  }
}
