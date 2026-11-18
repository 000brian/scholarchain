package org.scholarchain.storage.load

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scholarchain.storage.TestFixtures
import org.scholarchain.storage.ports.ObjectRef

/** The build identity resource (decision 5 item 6 handoff) and the masking of printed text. */
class BuildIdentitySuite extends munit.FunSuite {

  private val good =
    "buildCommit=0123456789abcdef0123456789abcdef01234567\nbuildDirty=true\nmodule=services\nversion=0.1.0-SNAPSHOT\n"

  test("a well-formed resource is read; every key is checked") {
    val id = BuildIdentity.parse(good).fold(e => fail(e), identity)
    assertEquals(
      id,
      BuildIdentity(
        "0123456789abcdef0123456789abcdef01234567",
        "true",
        "services",
        "0.1.0-SNAPSHOT",
        "resource"
      )
    )
    assert(BuildIdentity.parse(good.replace("buildDirty=true", "buildDirty=yes")).isLeft)
    assert(BuildIdentity.parse(good.replace("0123456789abcdef", "XYZ")).isLeft)
    assert(BuildIdentity.parse("module=services\n").isLeft)
    assertEquals(
      BuildIdentity
        .parse(good.replace("0123456789abcdef0123456789abcdef01234567", "unknown"))
        .map(_.buildCommit),
      Right("unknown")
    )
  }

  test("a missing resource (module tests) is unknown, a malformed one is unknown with the reason") {
    val empty = new ClassLoader(null) {}
    assertEquals(BuildIdentity.load(empty), BuildIdentity.unknown("missing"))
    val dir = TestFixtures.tempDir("build-info")
    Files.write(
      dir.resolve(BuildIdentity.ResourceName),
      good.getBytes(StandardCharsets.UTF_8)
    )
    val loader = new URLClassLoader(Array(dir.toUri.toURL), null)
    try assertEquals(BuildIdentity.load(loader).status, "resource")
    finally loader.close()
    val dir2 = TestFixtures.tempDir("build-info-bad")
    Files.write(
      dir2.resolve(BuildIdentity.ResourceName),
      "buildCommit=x\n".getBytes(StandardCharsets.UTF_8)
    )
    val loader2 = new URLClassLoader(Array(dir2.toUri.toURL), null)
    try {
      val id = BuildIdentity.load(loader2)
      assertEquals(id.buildCommit, "unknown")
      assert(id.status.startsWith("malformed: "), id.status)
    } finally loader2.close()
  }

  test("masking hides bucket names, the configured account and ARN accounts, nothing else") {
    val m =
      new Masking(List("example-graph-bucket", "example-graph-bucket-2"), List("210987654321"))
    assertEquals(
      m("s3://example-graph-bucket-2/k and s3://example-graph-bucket/k"),
      "s3://{data-bucket}/k and s3://{data-bucket}/k"
    )
    assertEquals(
      m("arn:aws:iam::123456789012:role/x account 210987654321"),
      "arn:aws:iam::{account}:role/x account {account}"
    )
    val uuid = "6f1c2d3e-aaaa-bbbb-cccc-123456789012"
    assertEquals(m(uuid), uuid)
    assertEquals(m.s3(ObjectRef("example-graph-bucket", "graph/x")), "s3://{data-bucket}/graph/x")
  }
}
