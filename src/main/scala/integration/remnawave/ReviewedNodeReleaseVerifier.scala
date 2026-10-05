package ru.bitec.app.ops
package integration.remnawave

import application.port.{NodeImageRemoteFailure, RemnawaveNodeReleaseVerifier}
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration._

/** TLS, fixed repository, immutable references, bounded reads, and no token/provenance logging.
  * BuildKit provenance is unsigned: it supplements the reviewed pins, not a signature claim. */
final class ReviewedNodeReleaseVerifier(client: Client[IO]) extends RemnawaveNodeReleaseVerifier {
  private val root = "https://ghcr.io/v2/remnawave/node/"
  private val invalid = NodeImageRemoteFailure("NODE_RELEASE_ARTIFACT_UNCONFIRMED")
  private val maxBytes = 65536L
  private def assertThat(test: Boolean): IO[Unit] = IO.raiseUnless(test)(invalid)
  private def read(url: String, token: Option[String], redirects: Int = 0): IO[Array[Byte]] = for {
    uri <- IO.fromEither(Uri.fromString(url).leftMap(_ => invalid))
    _ <- assertThat(uri.scheme.contains(Uri.Scheme.https) && uri.authority.exists(a =>
      a.userInfo.isEmpty && a.port.forall(_ == 443) &&
        (a.host.value == "ghcr.io" || a.host.value == "pkg-containers.githubusercontent.com")))
    headers = List(Header.Raw(CIString("Accept"), "application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/json")) ++
      token.filter(_ => uri.authority.exists(_.host.value == "ghcr.io")).map(t => Header.Raw(CIString("Authorization"), s"Bearer $t")).toList
    bytes <- client.run(Request[IO](Method.GET, uri).withHeaders(org.http4s.Headers(headers))).use { response =>
      if (Set(301, 302, 307, 308)(response.status.code) && redirects < 2) {
        response.headers.get(CIString("Location")).map(_.head.value).liftTo[IO](invalid)
          .flatMap(next => read(next, None, redirects + 1))
      } else assertThat(response.status.isSuccess) *> response.body.take(maxBytes + 1).compile.toVector.flatMap { body =>
        assertThat(body.size <= maxBytes).as(body.toArray)
      }
    }
  } yield bytes
  private def json(bytes: Array[Byte]): IO[Json] =
    IO.fromEither(parse(new String(bytes, StandardCharsets.UTF_8)).leftMap(_ => invalid))
  private def artifact(kind: String, digest: String, token: String): IO[Json] = for {
    _ <- assertThat(NodeRelease.digest(digest) && Set("manifests", "blobs")(kind))
    bytes <- read(s"$root$kind/$digest", Some(token))
    actual = MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
    _ <- assertThat(s"sha256:$actual" == digest)
    value <- json(bytes)
  } yield value
  def verify(release: NodeRelease, platform: NodeReleasePlatform): IO[Unit] = (for {
    _ <- assertThat(RemnawaveNodeReleaseCatalog.find(release.releaseId).contains(release) &&
      release.status != "BLOCKED" && release.platforms.contains(platform))
    auth <- read("https://ghcr.io/token?service=ghcr.io&scope=repository:remnawave/node:pull", None).flatMap(json)
    token <- auth.hcursor.get[String]("token").toOption.filter(t => t.nonEmpty && t.length <= 16384 && !t.exists(_.isControl)).liftTo[IO](invalid)
    index <- artifact("manifests", release.manifestDigest, token)
    descriptors = index.hcursor.get[List[Json]]("manifests").toOption.getOrElse(Nil)
    _ <- assertThat(index.hcursor.get[String]("mediaType").toOption.contains("application/vnd.oci.image.index.v1+json") &&
      descriptors.exists { d =>
        val c = d.hcursor
        c.get[String]("digest").toOption.contains(platform.manifestDigest) &&
          c.downField("platform").get[String]("os").toOption.contains("linux") &&
          c.downField("platform").get[String]("architecture").toOption.contains(platform.platform.stripPrefix("linux/"))
      } && descriptors.exists { d =>
        val c = d.hcursor
        c.get[String]("digest").toOption.contains(platform.provenanceManifestDigest) &&
          c.downField("annotations").get[String]("vnd.docker.reference.digest").toOption.contains(platform.manifestDigest) &&
          c.downField("annotations").get[String]("vnd.docker.reference.type").toOption.contains("attestation-manifest")
      })
    manifest <- artifact("manifests", platform.manifestDigest, token)
    layers = manifest.hcursor.get[List[Json]]("layers").toOption.getOrElse(Nil)
    _ <- assertThat(manifest.hcursor.downField("config").get[String]("digest").toOption.contains(platform.configDigest) &&
      layers.nonEmpty && layers.forall(_.hcursor.get[String]("digest").toOption.exists(NodeRelease.digest)) &&
      layers.traverse(_.hcursor.get[Long]("size").toOption).exists(sizes => sizes.forall(_ > 0) && sizes.sum == platform.compressedBytes))
    attestation <- artifact("manifests", platform.provenanceManifestDigest, token)
    _ <- assertThat(attestation.hcursor.get[List[Json]]("layers").toOption.exists(_.exists { layer =>
      layer.hcursor.get[String]("digest").toOption.contains(platform.provenanceBlobDigest) &&
        layer.hcursor.downField("annotations").get[String]("in-toto.io/predicate-type").toOption.contains("https://slsa.dev/provenance/v1")
    }))
    proof <- artifact("blobs", platform.provenanceBlobDigest, token)
    args = proof.hcursor.downField("predicate").downField("buildDefinition").downField("externalParameters")
      .downField("request").downField("root").downField("request").downField("args")
    _ <- assertThat(proof.hcursor.get[String]("predicateType").toOption.contains("https://slsa.dev/provenance/v1") &&
      args.get[String]("vcs:source").toOption.contains("https://github.com/remnawave/node") &&
      args.get[String]("vcs:revision").toOption.contains(release.sourceCommit) &&
      proof.hcursor.get[List[Json]]("subject").toOption.exists(_.exists(_.hcursor.downField("digest")
        .get[String]("sha256").toOption.contains(platform.manifestDigest.stripPrefix("sha256:")))))
  } yield ()).timeoutTo(60.seconds, IO.raiseError(invalid)).handleErrorWith(_ => IO.raiseError(invalid))
}
