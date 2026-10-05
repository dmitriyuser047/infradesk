package ru.bitec.app.ops
package integration.ssh

import application.port._
import cats.effect.IO
import cats.syntax.all._
import domain.connection.Connection
import domain.integration._
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try

/** The Stage25C installation owns every byte except the one controlled image line.
  * No environment, full inspect, registration data or container output is returned. */
private[ssh] final class SshManagedNodeImages(transport: RemoteConfigurationTransport[IO])
  extends RemnawaveNodeImageRemote[IO] {
  import SshRemnawaveNodeRemote._
  import SshManagedNodeImages._

  private def fail(code: String, uncertain: Boolean = false): IO[Nothing] =
    IO.raiseError(NodeImageRemoteFailure(code, uncertain))
  private def session[A](connection: Connection)(use: (RemoteConfigurationSession[IO], ProfileCommands, Int) => IO[A]): IO[A] =
    transport.withSessionBounded(connection, 65536) { s =>
      new ProfileCommands(s, false).capture("id", List("-u"), 5.seconds, privileged = false).flatMap { r =>
        if (r.exitCode != 0 || !r.stdout.trim.matches("[0-9]{1,8}")) fail("NODE_UPGRADE_SSH_REJECTED")
        else use(s, new ProfileCommands(s, r.stdout.trim == "0"), r.stdout.trim.toInt)
      }
    }.handleErrorWith {
      case e: NodeImageAuthorizationFailure => IO.raiseError(e)
      case e: NodeImageRemoteFailure => IO.raiseError(e)
      case e: ProfileRemoteFailure => fail(e.code, e.uncertain)
      case RemoteConfigurationFailure.HostKeyMismatch => fail("PROVISIONING_HOST_KEY_MISMATCH")
      case RemoteConfigurationFailure.HostKeyNotTrusted => fail("PROVISIONING_HOST_KEY_NOT_TRUSTED")
      case RemoteConfigurationFailure.AuthenticationFailed => fail("PROVISIONING_SSH_AUTHENTICATION_FAILED")
      case _ => fail("NODE_UPGRADE_REMOTE_UNKNOWN", true)
    }
  private def validSpec(s: RemnawaveNodeRemoteSpec): IO[Unit] =
    IO.raiseUnless(RemnawaveReferences(s.imageReference) && s.nodePort >= 1 && s.nodePort <= 65535)(
      NodeImageRemoteFailure("NODE_UPGRADE_UNMANAGED"))
  private def proofArgs(s: RemnawaveNodeRemoteSpec): List[String] = List(directory(s), markerLine(s),
    s.nodePort.toString, ProfileManagedFiles.sha256(renderCompose(s).getBytes(StandardCharsets.UTF_8)), managedPrefix(s), containerName(s))
  def observeImage(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[NodeImageObservation] =
    session(connection)((_, c, _) => validSpec(spec) *> observe(c, spec))
  private def observe(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[NodeImageObservation] =
    c.shell(ObserveImage, proofArgs(spec), 30.seconds).flatMap { r =>
      if (r.exitCode != 0) fail("NODE_UPGRADE_OBSERVATION_UNKNOWN", true)
      else decode(r.stdout).liftTo[IO]
    }
  private def inspectImage(c: ProfileCommands, reference: String): IO[Option[(String, String, Long)]] =
    c.capture("docker", List("image", "inspect", "--format", "{{.Id}}|{{.Os}}/{{.Architecture}}|{{.Size}}", reference), 15.seconds).map { r =>
      if (r.exitCode != 0) None else r.stdout.trim.split("\\|", -1).toList match {
        case List(id, platform, size) if NodeRelease.digest(id) && Set("linux/amd64", "linux/arm64")(platform) =>
          size.toLongOption.filter(_ > 0).map(bytes => (id, platform, bytes))
        case _ => None
      }
    }
  def prefetchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, release: NodeRelease,
    platform: NodeReleasePlatform, baseline: NodeImageObservation, authorize: IO[Unit]): IO[Unit] = session(connection) { (_, c, _) =>
    val reference = s"${release.imageRepository}@${platform.manifestDigest}"
    for {
      _ <- validSpec(spec)
      _ <- IO.raiseUnless(RemnawaveNodeReleaseCatalog.find(release.releaseId).contains(release) &&
        release.status == "AVAILABLE" && release.platforms.contains(platform)) (NodeImageRemoteFailure("NODE_RELEASE_UNREVIEWED"))
      current <- observe(c, spec)
      _ <- IO.raiseUnless(current.healthy && sameBaseline(current, baseline) && current.platform.contains(platform.platform))(
        NodeImageRemoteFailure("NODE_UPGRADE_PLAN_CHANGED"))
      compose <- c.capture("docker", List("compose", "version", "--short"), 10.seconds)
      _ <- IO.raiseUnless(compose.exitCode == 0 && compose.stdout.trim.nonEmpty)(NodeImageRemoteFailure("NODE_UPGRADE_COMPOSE_UNAVAILABLE"))
      // Compressed layer sizes do not claim an exact uncompressed footprint. This is a conservative minimum.
      required = math.max(8L * GiB, current.imageBytes + 5L * platform.compressedBytes + GiB)
      _ <- IO.raiseUnless(current.freeBytes >= required)(NodeImageRemoteFailure("NODE_UPGRADE_DISK_INSUFFICIENT"))
      present <- inspectImage(c, reference)
      _ <- present match {
        case Some((id, p, _)) if id == platform.configDigest && p == platform.platform => IO.unit
        case Some(_) => fail("NODE_UPGRADE_IMAGE_PROOF_FAILED")
        case None => authorize *> c.capture("docker", List("pull", "--platform", platform.platform, reference), 5.minutes).attempt.flatMap { pulled =>
          // An interrupted pull is reconciled from the image store, never blindly replayed.
          inspectImage(c, reference).flatMap {
            case Some((id, p, _)) if id == platform.configDigest && p == platform.platform => IO.unit
            case _ => pulled match {
              case Right(r) if r.exitCode != 0 => fail("NODE_UPGRADE_PREFETCH_FAILED")
              case _ => fail("NODE_UPGRADE_PREFETCH_UNKNOWN", true)
            }
          }
        }
      }
      after <- observe(c, spec)
      _ <- IO.raiseUnless(after.healthy && sameBaseline(after, baseline))(NodeImageRemoteFailure("NODE_UPGRADE_PLAN_CHANGED"))
    } yield ()
  }
  def switchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedComposeHash: String, expectedMarkerHash: String, authorize: IO[Unit]): IO[Unit] =
    session(connection) { (s, c, uid) =>
      val target = RemnawaveNodeReleaseCatalog.releases.iterator.flatMap(r => r.platforms.iterator.map(p =>
        (s"${r.imageRepository}@${p.manifestDigest}", p.configDigest, p.platform))).find(t => t._1 == reference && t._2 == expectedImageId)
      for {
        _ <- validSpec(spec)
        proof <- target.liftTo[IO](NodeImageRemoteFailure("NODE_RELEASE_UNREVIEWED"))
        current <- observe(c, spec)
        _ <- IO.raiseUnless(current.managedFiles && current.composeHash.contains(expectedComposeHash) &&
          current.markerHash.contains(expectedMarkerHash) && current.platform.contains(proof._3))(
          NodeImageRemoteFailure("NODE_UPGRADE_PLAN_CHANGED"))
        stored <- inspectImage(c, reference)
        _ <- IO.raiseUnless(stored.exists(i => i._1 == expectedImageId && i._2 == proof._3 &&
          current.freeBytes >= current.imageBytes + i._3 + GiB))(NodeImageRemoteFailure("NODE_UPGRADE_IMAGE_PROOF_FAILED"))
        bytes = renderCompose(spec).replace(s"    image: ${spec.imageReference}\n", s"    image: $reference\n").getBytes(StandardCharsets.UTF_8)
        _ <- authorize
        _ <- new ProfileManagedFiles(s, c, uid, spec.onboardingId).replace(s"${directory(spec)}/compose.yml", markerLine(spec),
          bytes, Some(expectedComposeHash), validate = Some(List("docker", "compose", "-f", "{candidate}",
            "--env-file", s"${directory(spec)}/.env", "config", "-q")),
          validationFailureCode = "NODE_UPGRADE_COMPOSE_INVALID", beforeCommit = authorize)
        // Re-prove the unchanged marker and the committed target before the container mutation.
        committed <- observe(c, spec)
        _ <- IO.raiseUnless(committed.managedFiles && committed.configuredImage.contains(reference) &&
          committed.markerHash.contains(expectedMarkerHash) && committed.composeHash.contains(ProfileManagedFiles.sha256(bytes)))(
          NodeImageRemoteFailure("NODE_UPGRADE_PLAN_CHANGED"))
        _ <- authorize
        result <- c.capture("docker", List("compose", "-f", s"${directory(spec)}/compose.yml", "--env-file",
          s"${directory(spec)}/.env", "up", "-d", "--no-deps", "--pull", "never", "node"), 3.minutes)
        _ <- IO.raiseUnless(result.exitCode == 0)(NodeImageRemoteFailure("NODE_UPGRADE_SWITCH_UNKNOWN", true))
      } yield ()
    }
  def activateImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedMarkerHash: String, authorize: IO[Unit]): IO[Unit] = session(connection) { (_, c, _) =>
    for {
      _ <- validSpec(spec)
      current <- observe(c, spec)
      stored <- inspectImage(c, reference)
      reviewed = RemnawaveNodeReleaseCatalog.releases.exists(r => r.status != "BLOCKED" && r.platforms.exists(p =>
        s"${r.imageRepository}@${p.manifestDigest}" == reference && p.configDigest == expectedImageId && current.platform.contains(p.platform)))
      _ <- IO.raiseUnless(reviewed && current.managedFiles && current.configuredImage.contains(reference) &&
        current.markerHash.contains(expectedMarkerHash) && stored.exists(_._1 == expectedImageId))(NodeImageRemoteFailure("NODE_UPGRADE_PLAN_CHANGED"))
      _ <- authorize
      result <- c.capture("docker", List("compose", "-f", s"${directory(spec)}/compose.yml", "--env-file", s"${directory(spec)}/.env",
        "up", "-d", "--no-deps", "--pull", "never", "node"), 3.minutes)
      _ <- IO.raiseUnless(result.exitCode == 0)(NodeImageRemoteFailure("NODE_UPGRADE_SWITCH_UNKNOWN", true))
    } yield ()
  }
}

private[ssh] object SshManagedNodeImages {
  val GiB: Long = 1024L * 1024L * 1024L
  private def RemnawaveReferences(reference: String): Boolean = RemnawaveNodeReleaseCatalog.managedReferences.contains(reference)
  def sameBaseline(a: NodeImageObservation, b: NodeImageObservation): Boolean =
    a.actualImageId == b.actualImageId && a.containerId == b.containerId && a.containerCreatedAt == b.containerCreatedAt &&
      a.containerStartedAt == b.containerStartedAt && a.composeHash == b.composeHash && a.markerHash == b.markerHash &&
      a.configuredImage == b.configuredImage && a.platform == b.platform
  // Normalize exactly the controlled image line; every other byte remains the Stage25C template.
  val ManagedImageProof: String = SshRemnawaveNodeRemote.ManagedFileProof.replace(
    "[ \"$(sha256sum \"$d/compose.yml\" | cut -d' ' -f1)\" = \"$composeHash\" ] || return 1",
    """[ "$(grep -c '^    image: ' "$d/compose.yml")" = 1 ] || return 1
      |  controlledImage=$(sed -n 's/^    image: //p' "$d/compose.yml")
      |  case "$controlledImage" in """.stripMargin + RemnawaveNodeReleaseCatalog.managedReferences.mkString("|") + """ ) ;; *) return 1 ;; esac
      |  originalImage=${marker##* image=}
      |  normalizedHash=$(awk -v image="$originalImage" '{if ($0 ~ /^    image: /) print "    image: " image; else print}' "$d/compose.yml" | sha256sum | cut -d' ' -f1)
      |  [ "$normalizedHash" = "$composeHash" ] || return 1""".stripMargin)
  val ObserveImage: String = "set -u; " + ManagedImageProof + """
    |d=$1; marker=$2; port=$3; composeHash=$4; managedPrefix=$5; name=$6
    |managed=0; managed_files "$d" "$marker" "$port" "$composeHash" "$managedPrefix" && managed=1 || true
    |[ "$managed" = 1 ] || { printf 'UNMANAGED'; exit 0; }
    |image=$(sed -n 's/^    image: //p' "$d/compose.yml")
    |actual=$(docker inspect --format '{{.Image}}' "$name" 2>/dev/null || true)
    |config=$(docker inspect --format '{{.Config.Image}}' "$name" 2>/dev/null || true)
    |network=$(docker inspect --format '{{.HostConfig.NetworkMode}}' "$name" 2>/dev/null || true)
    |running=$(docker inspect --format '{{.State.Running}}' "$name" 2>/dev/null || true)
    |[ "$config" = "$image" ] && [ "$network" = host ] || running=false
    |identity=$(docker inspect --format '{{.Id}}|{{.Created}}|{{.State.StartedAt}}|{{.RestartCount}}' "$name" 2>/dev/null || true)
    |imageInfo=$(docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}|{{.Size}}' "$actual" 2>/dev/null || true)
    |digests=$(docker image inspect --format '{{range .RepoDigests}}{{.}},{{end}}' "$actual" 2>/dev/null || true)
    |listener=0; listeners=$(ss -H -ltn "sport = :$port" 2>/dev/null) && [ -n "$listeners" ] && listener=1 || true
    |started=$(docker inspect --format '{{.State.StartedAt}}' "$name" 2>/dev/null || true)
    |startedSeconds=$(date -d "$started" +%s 2>/dev/null || echo 0); nowSeconds=$(date +%s)
    |stable=0; [ "$startedSeconds" -gt 0 ] && [ $((nowSeconds-startedSeconds)) -ge 10 ] && stable=1 || true
    |hash=$(sha256sum "$d/compose.yml" | cut -d' ' -f1); markerHash=$(sha256sum "$d/managed.json" | cut -d' ' -f1)
    |root=$(docker info --format '{{.DockerRootDir}}' 2>/dev/null) || exit 1
    |free=$(df -B1 --output=avail -- "$root" 2>/dev/null | tail -n1 | tr -d ' ') || exit 1
    |printf '%s\n' "$image" "$actual" "$digests" "$imageInfo" "$identity" "$running" "$listener" "$stable" "$hash" "$markerHash" "$free"
    |""".stripMargin
  private val empty = NodeImageObservation(false, None, None, Nil, None, None, None, None, false, 0, false, false, None, None, 0L, 0L)
  def decode(raw: String): Either[NodeImageRemoteFailure, NodeImageObservation] = {
    val lines = raw.stripSuffix("\n").split("\n", -1).toList.map(_.stripSuffix("\r"))
    if (lines == List("UNMANAGED")) Right(empty)
    else lines match {
      case List(ref, actual, digests, imageInfo, identity, running, port, stable, compose, marker, free) =>
        (imageInfo.split("\\|", -1).toList, identity.split("\\|", -1).toList) match {
          case (List(imageId, platform, size), List(id, created, started, restarts)) => for {
            _ <- Either.cond(RemnawaveReferences(ref) && NodeRelease.digest(actual) && imageId == actual &&
              Set("linux/amd64", "linux/arm64")(platform) && id.matches("[0-9a-f]{64}") &&
              compose.matches("[0-9a-f]{64}") && marker.matches("[0-9a-f]{64}") &&
              Set("true", "false")(running) && Set("0", "1")(port) && Set("0", "1")(stable), (), NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
            createdAt <- Try(Instant.parse(created)).toEither.leftMap(_ => NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
            startedAt <- Try(Instant.parse(started)).toEither.leftMap(_ => NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
            restartCount <- restarts.toIntOption.filter(_ >= 0).toRight(NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
            bytes <- size.toLongOption.filter(_ > 0).toRight(NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
            available <- free.toLongOption.filter(_ >= 0).toRight(NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
          } yield NodeImageObservation(true, Some(ref), Some(actual), digests.split(",").filter(NodeRelease.immutableReference).toList,
            Some(platform), Some(id), Some(createdAt), Some(startedAt), running == "true", restartCount, port == "1", stable == "1",
            Some(compose), Some(marker), bytes, available)
          case _ => Left(NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
        }
      case _ => Left(NodeImageRemoteFailure("NODE_UPGRADE_OBSERVATION_UNKNOWN", true))
    }
  }
}
