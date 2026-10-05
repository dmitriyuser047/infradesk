package ru.bitec.app.ops
package integration.remnawave

import application.port.NodeImageRemoteFailure
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite
import org.http4s.{HttpApp, Response, Status}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import support.NodeUpgradeFixtures
import scala.collection.mutable.ListBuffer

final class ReviewedNodeReleaseVerifierSpec extends FunSuite {
  private val release = NodeUpgradeFixtures.target
  private val platform = release.forPlatform("linux/amd64").get
  private def artifact(name: String): String = {
    val s = getClass.getResourceAsStream(s"/integration/remnawave/node-release-3.4.1/$name.json")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString finally s.close()
  }
  private def verify(alter: String => String = identity, redirect: Option[String] = None): (Either[Throwable, Unit], List[String]) = {
    val calls = ListBuffer.empty[String]
    val app = HttpApp[IO] { request => IO {
      val path = request.uri.path.renderString
      calls += request.uri.renderString
      if (path == "/token") Response[IO](Status.Ok).withEntity("{\"token\":\"anonymous-registry-proof-token\"}")
      else if (path.endsWith(release.manifestDigest)) Response[IO](Status.Ok).withEntity(artifact("index"))
      else if (path.endsWith(platform.manifestDigest)) Response[IO](Status.Ok).withEntity(artifact("platform"))
      else if (path.endsWith(platform.provenanceManifestDigest)) Response[IO](Status.Ok).withEntity(artifact("attestation"))
      else if (path.endsWith(platform.provenanceBlobDigest)) redirect.fold(Response[IO](Status.Ok).withEntity(alter(artifact("provenance"))))(
        url => Response[IO](Status.Found).putHeaders(org.http4s.Header.Raw(CIString("Location"), url)))
      else Response[IO](Status.NotFound)
    }}
    new ReviewedNodeReleaseVerifier(Client.fromHttpApp(app)).verify(release, platform).attempt.unsafeRunSync() -> calls.toList
  }
  test("the published OCI chain proves the pinned source and architecture without using a mutable tag") {
    val (result, calls) = verify()
    assertEquals(result, Right(()))
    assertEquals(calls.size, 5)
    assert(calls.filter(_.contains("/manifests/")).forall(_.contains("sha256:")))
    assert(calls.forall(!_.contains("/manifests/3.4.1")))
    assertEquals(parse(artifact("provenance")).toOption.get.hcursor.get[String]("predicateType").toOption,
      Some("https://slsa.dev/provenance/v1"))
  }
  test("a moved tag cannot replace an approved digest; tampered source evidence is rejected content-free") {
    val secret = "fake-SECRET_KEY-never-return"
    val (result, calls) = verify(_.replace(release.sourceCommit, secret))
    assert(result.isLeft)
    assertEquals(result.left.toOption.map(_.getMessage), Some("NODE_RELEASE_ARTIFACT_UNCONFIRMED"))
    assert(calls.exists(_.endsWith(release.manifestDigest)))
    assert(!result.toString.contains(secret))
  }
  test("registry redirects to arbitrary hosts, non-TLS URLs or userinfo are rejected before dispatch") {
    List("https://evil.example.test/artifact", "http://ghcr.io/artifact", "https://user@ghcr.io/artifact").foreach { url =>
      val (result, calls) = verify(redirect = Some(url))
      assert(result.isLeft)
      assertEquals(calls.size, 5)
      assert(!calls.contains(url))
    }
  }
  test("bounded provenance reads refuse oversized bodies and strip response text from errors") {
    val (result, _) = verify(_ => "x" * 65537)
    assertEquals(result.left.toOption.collect { case e: NodeImageRemoteFailure => e.code }, Some("NODE_RELEASE_ARTIFACT_UNCONFIRMED"))
  }
}
