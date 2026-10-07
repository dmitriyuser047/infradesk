package ru.bitec.app.ops
package integration.ssh

import application.port.{ProvisioningStepResult, RemoteConfigurationFailure, RemoteConfigurationFile, RemoteConfigurationSession, RemoteConfigurationTransport, RemnawaveNodeLocalEvidence, RemnawaveNodeRemote, RemnawaveNodeRemoteSpec}
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all._
import domain.connection.Connection
import domain.integration.{LocalInstallationState, LocalInstallationObservation, LocalInstallationDiagnosis, NodeInstallationData, NodeImageObservation, NodeRelease, NodeReleasePlatform, RemnawaveNodeReleaseCatalog}
import domain.provisioning.ServerProfileContent
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Typed, pinned-SSH implementation of the per-node host installation contract. */
final class SshRemnawaveNodeRemote(transport: RemoteConfigurationTransport[IO]) extends RemnawaveNodeRemote[IO]
  with application.port.RemnawaveNodeImageRemote[IO] {
  import SshRemnawaveNodeRemote._
  private lazy val images = new SshManagedNodeImages(transport)
  def observeImage(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[NodeImageObservation] = images.observeImage(connection, spec)
  def prefetchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, release: NodeRelease,
    platform: NodeReleasePlatform, baseline: NodeImageObservation, authorize: IO[Unit]): IO[Unit] =
    images.prefetchImage(connection, spec, release, platform, baseline, authorize)
  def switchImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedComposeHash: String, expectedMarkerHash: String, authorize: IO[Unit]): IO[Unit] =
    images.switchImage(connection, spec, reference, expectedImageId, expectedComposeHash, expectedMarkerHash, authorize)
  def activateImage(connection: Connection, spec: RemnawaveNodeRemoteSpec, reference: String,
    expectedImageId: String, expectedMarkerHash: String, authorize: IO[Unit]): IO[Unit] =
    images.activateImage(connection, spec, reference, expectedImageId, expectedMarkerHash, authorize)

  override def installationPrerequisites(connection: Connection): IO[ProvisioningStepResult] = step(withSession(connection) { (_, c, _) =>
    for {
      docker <- c.capture("docker", List("info", "--format", "{{.ServerVersion}}"), 15.seconds)
      _ <- IO.raiseUnless(docker.exitCode == 0 && docker.stdout.trim.nonEmpty)(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
      compose <- c.capture("docker", List("compose", "version", "--short"), 10.seconds)
      _ <- IO.raiseUnless(compose.exitCode == 0 && compose.stdout.trim.nonEmpty)(ProfileRemoteFailure("PROVISIONING_NODE_COMPOSE_UNAVAILABLE"))
      firewall <- c.capture("ufw", List("status"), 10.seconds)
      _ <- IO.raiseUnless(firewall.exitCode == 0 && firewall.stdout.linesIterator.exists(_.trim == "Status: active"))(
        ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    } yield success("installationPrerequisites" -> "ready")
  })

  override def preflight(connection: Connection, resourceId: UUID, nodePort: Int): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, _) =>
      validatePort(nodePort) *> runPreflight(c, nodePort, allowDockerMissing = true).flatMap {
        case "CLEAR" => IO.pure(success("preflight" -> "clear"))
        case "PORT" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
        case "UNKNOWN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
        case "DOCKER" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
      }
    })

  override def configureFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    configureFirewall(connection,spec,replaceSources=true)

  override def configureOnboardingFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    configureFirewall(connection,spec,replaceSources=false)

  private def configureFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    replaceSources: Boolean, reviewedSources: Option[Set[String]] = None): IO[ProvisioningStepResult] =
    Ref.of[IO, Boolean](false).flatMap { changed => stepTracked(changed)(withSession(connection) { (_, c, _) =>
      validate(spec) *> firewallRules(spec).flatMap { desired =>
        for {
          ssh <- c.shell("printf '%s' \"$SSH_CONNECTION\"", privileged = false)
          endpoint <- parseSsh(ssh.stdout).liftTo[IO]
          status <- c.capture("ufw", List("status"), 15.seconds)
          _ <- IO.raiseUnless(status.exitCode == 0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
          active = status.stdout.linesIterator.exists(_.trim == "Status: active")
          inactive = status.stdout.linesIterator.exists(_.trim == "Status: inactive")
          _ <- IO.raiseUnless(active || inactive)(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
          _ <- IO.raiseUnless(active)(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          old <- nodeRules(c, spec)
          _ <- connectivityRules(old,spec,reviewedSources)
          _ <- IO.raiseWhen(broadManagementAllow(old, spec))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_BROAD_RULE_PRESENT"))
          _ <- validateOwnedFirewall(old, spec, replaceSources)
          _ <- IO.raiseWhen(old.exists(r => !r.owned && r.action != "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_RULE_UNSUPPORTED"))
          _ <- IO.raiseWhen(ProfileFirewall.sshBlocked(old, endpoint._1, endpoint._2))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          _ <- desired.traverse_ { case (source, comment) =>
            nodeRules(c, spec).flatMap { fresh =>
              connectivityRules(fresh,spec,reviewedSources) *> validateOwnedFirewall(fresh, spec, replaceSources) *>
              IO.raiseWhen(broadManagementAllow(fresh, spec))(ProfileRemoteFailure("PROVISIONING_FIREWALL_BROAD_RULE_PRESENT")) *>
              IO.raiseWhen(ProfileFirewall.sshBlocked(fresh, endpoint._1, endpoint._2))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN")) *>
              (if (fresh.exists(r => r.owned && r.action == "allow" && r.rule.port == spec.nodePort && r.rule.protocol == "tcp" && r.rule.sources.contains(source))) IO.unit
              else changed.set(true) *> checked(c, "ufw", List("allow", "from", source,
                "to", "any", "port", spec.nodePort.toString, "proto", "tcp", "comment", comment)))
            }
          }
          _ <- canary(connection)
          // Fleet reconciliation retains its approved source replacement; onboarding never deletes.
          _ <- if (!replaceSources) IO.unit else old.filter(r => r.owned && r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)
            .flatMap(_.rule.sources).distinct.filterNot(s => desired.exists(_._1 == s)).traverse_ { source =>
              nodeRules(c, spec).flatMap { fresh =>
                connectivityRules(fresh,spec,reviewedSources) *> (if (fresh.exists(r => r.owned && r.action == "allow" && r.rule.port == spec.nodePort && r.rule.sources.contains(source)))
                  changed.set(true) *> checked(c, "ufw", List("--force", "delete", "allow", "from", source,
                    "to", "any", "port", spec.nodePort.toString, "proto", "tcp", "comment", s"infradesk:remnawave:${spec.resourceId}:${spec.externalNodeId}:node"))
                else IO.unit)
              }
            }
          verified <- firewallProof(c, spec)
          _ <- IO.raiseUnless(verified)(ProfileRemoteFailure("PROVISIONING_FIREWALL_VERIFICATION_FAILED"))
          _ <- canary(connection)
        } yield success("firewallRulesApplied" -> desired.size.toString)
      }
    })}

  override def retireFirewall(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    Ref.of[IO, Boolean](false).flatMap { changed => stepTracked(changed)(withSession(connection) { (_, c, _) =>
      validate(spec) *> c.shell("printf '%s' \"$SSH_CONNECTION\"", privileged = false).flatMap(r => parseSsh(r.stdout).liftTo[IO]).flatMap { endpoint =>
        def active: IO[Unit] = c.capture("ufw", List("status"), 15.seconds).flatMap { r =>
          IO.raiseUnless(r.exitCode == 0 && !r.stdoutTruncated && !r.stderrTruncated &&
            r.stdout.linesIterator.exists(_.trim == "Status: active"))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN", uncertain = true))
        }
        def own(rules: List[ProfileFirewallRule]): List[ProfileFirewallRule] = rules.filter(_.owned)
        def sshAllowed(rules: List[ProfileFirewallRule]): Boolean = rules.exists(r => r.action == "allow" &&
          r.rule.protocol == "tcp" && r.rule.port == endpoint._2 && r.rule.sources.exists(ServerProfileContent.sourceCovers(_, endpoint._1)))
        def supported(rules: List[ProfileFirewallRule]): IO[Unit] = {
          val desired = spec.panelCidrs.flatMap(ServerProfileContent.canonicalFirewallSource(_).toOption).toSet
          val ownSources = own(rules).flatMap(_.rule.sources).toSet
          val foreignCollision = rules.exists(r => !r.owned && r.action == "allow" && r.rule.protocol == "tcp" &&
            r.rule.port == spec.nodePort && r.rule.sources.exists(ownSources))
          IO.raiseWhen(foreignCollision || own(rules).exists(r => r.rule.id != "node" || r.action != "allow" ||
            r.rule.protocol != "tcp" || r.rule.port != spec.nodePort || r.rule.sources.isEmpty ||
            !r.rule.sources.forall(s => ServerProfileContent.canonicalFirewallSource(s).toOption.exists(desired))))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT"))
        }
        for {
          _ <- active
          initial <- nodeRules(c, spec)
          _ <- supported(initial)
          _ <- IO.raiseUnless(sshAllowed(initial))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          _ <- IO.raiseWhen(ProfileFirewall.sshBlocked(initial, endpoint._1, endpoint._2))(
            ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
          _ <- own(initial).flatMap(_.rule.sources).distinct.sorted.traverse_ { source =>
            for {
              _ <- active
              fresh <- nodeRules(c, spec)
              _ <- supported(fresh)
              _ <- if (own(fresh).exists(_.rule.sources.contains(source))) {
                val after = fresh.filterNot(r => r.owned && r.rule.sources.contains(source))
                IO.raiseUnless(sshAllowed(after))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN")) *>
                IO.raiseWhen(ProfileFirewall.sshBlocked(after, endpoint._1, endpoint._2))(
                  ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN")) *>
                canary(connection) *>
                changed.set(true) *>
                (c.capture("ufw", List("--force", "delete", "allow", "from", source, "to", "any", "port",
                  spec.nodePort.toString, "proto", "tcp", "comment", s"infradesk:remnawave:${spec.resourceId}:${spec.externalNodeId}:node"), 30.seconds)
                  .flatMap(r => IO.raiseUnless(r.exitCode == 0)(ProfileRemoteFailure("PROVISIONING_FIREWALL_MUTATION_UNCERTAIN", uncertain = true)))) *>
                canary(connection)
              } else IO.unit
            } yield ()
          }
          _ <- active
          after <- nodeRules(c, spec)
          _ <- supported(after)
          _ <- IO.raiseWhen(own(after).nonEmpty)(ProfileRemoteFailure("PROVISIONING_FIREWALL_VERIFICATION_FAILED", uncertain = true))
          _ <- IO.raiseUnless(sshAllowed(after))(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN", uncertain = true))
          _ <- canary(connection)
        } yield success("firewallRulesRetired" -> own(initial).size.toString)
      }
    })}


  override def install(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): IO[ProvisioningStepResult] = Ref.of[IO, Boolean](false).flatMap { changed =>
    installationStep(connection, spec, changed)(withSession(connection) { (s, c, sshUid) =>
      installInSession(s, c, sshUid, spec, credential, changed)
    })
  }

  private def installInSession(s: RemoteConfigurationSession[IO], c: ProfileCommands, sshUid: Int,
    spec: RemnawaveNodeRemoteSpec, credential: NodeInstallationData, changed: Ref[IO, Boolean]): IO[ProvisioningStepResult] = {
    val files = new ProfileManagedFiles(s, c, sshUid, spec.onboardingId)
    val dir = directory(spec)
    val stage = stagingDirectory(spec)
    val marker = markerLine(spec)
    val compose = renderCompose(spec)
    val env = s"SECRET_KEY=${credential.secretKey}\nNODE_PORT=${spec.nodePort}\n"
    val envHash = ProfileManagedFiles.sha256(env.getBytes(StandardCharsets.UTF_8))
    val managed = renderManaged(spec, envHash)
    def put(path: String, bytes: Array[Byte], mode: Int, validation: Option[List[String]] = None): IO[Unit] =
      s.read(path, 65536 + 256).flatMap { current =>
        IO.raiseUnless(safeExistingFile(current, path.substring(path.lastIndexOf('/') + 1)).isRight)(
          ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED")) *>
        (if (current.exists && current.bytes.sameElements(bytes)) IO.unit else files.replace(path, marker, bytes,
          current.sha256, installOwned = true, targetMode = mode, validate = validation,
          validationFailureCode = "PROVISIONING_NODE_COMPOSE_INVALID", beforeCommit = assertOwned(c, spec, sshUid)))
      }
    validate(spec) *> validateSecret(credential.secretKey) *> preflightInSession(c, spec.nodePort) *>
      (for {
        _ <- prepareInstallation(c, spec, sshUid).flatTap(_ => changed.set(true))
        _ <- installationCommand(c, spec, CleanupInstallationStaging, "CLEAN", sshUid)
        _ <- put(s"$stage/.env", env.getBytes(StandardCharsets.UTF_8), 384).flatTap(_ => changed.set(true))
        _ <- put(s"$stage/compose.yml", compose.getBytes(StandardCharsets.UTF_8), 420,
          Some(ManagedNodeCompose.validation(stage))).flatTap(_ => changed.set(true))
        _ <- put(s"$stage/managed.json", managed.getBytes(StandardCharsets.UTF_8), 420).flatTap(_ => changed.set(true))
        _ <- c.capture("docker", List("compose", "-f", s"$stage/compose.yml", "--env-file", s"$stage/.env", "config", "-q"))
          .flatMap(r => IO.raiseUnless(r.exitCode == 0)(ProfileRemoteFailure("PROVISIONING_NODE_COMPOSE_INVALID")))
        _ <- checked(c, "sync", List("-f", s"$stage/.env"))
        _ <- checked(c, "sync", List("-f", s"$stage/compose.yml"))
        _ <- checked(c, "sync", List("-f", s"$stage/managed.json"))
        _ <- installationCommand(c, spec, PublishInstallation, "PUBLISHED", sshUid).flatTap(_ => changed.set(true))
        imageExists <- c.capture("docker", List("image", "inspect", "-f", "{{.Id}}", spec.imageReference), 20.seconds)
        _ <- if (imageExists.exitCode == 0 && imageExists.stdout.trim.nonEmpty) IO.unit
          else checked(c, "docker", List("pull", spec.imageReference), 5.minutes).flatTap(_ => changed.set(true))
      } yield success("installed" -> "true")).handleErrorWith(sanitize)
  }

  override def recoveryPreflight(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, sshUid) =>
      validate(spec) *> recoveryState(c, spec, sshUid).flatMap {
        case LocalInstallationState.Absent => IO.pure(success("installation" -> "absent"))
        case LocalInstallationState.OwnedComplete => IO.pure(success("installation" -> "owned_complete"))
        case LocalInstallationState.OwnedPartial => IO.pure(success("installation" -> "owned_partial"))
        case LocalInstallationState.OwnedDamaged => IO.pure(success("installation" -> "owned_damaged"))
        case LocalInstallationState.PortConflict => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
        case LocalInstallationState.Foreign => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
        case LocalInstallationState.Unknown => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
      }
    })

  override def repair(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    credential: NodeInstallationData): IO[ProvisioningStepResult] = Ref.of[IO, Boolean](false).flatMap { changed =>
    installationStep(connection, spec, changed)(withSession(connection) { (s, c, sshUid) =>
      val dir = directory(spec)
      validate(spec) *> validateSecret(credential.secretKey) *> recoveryState(c, spec, sshUid).flatMap {
        case LocalInstallationState.Absent => installInSession(s, c, sshUid, spec, credential, changed)
        case LocalInstallationState.OwnedComplete | LocalInstallationState.OwnedDamaged => repairExistingInSession(s, c, sshUid, spec, credential, changed)
        case LocalInstallationState.OwnedPartial =>
          c.capture("sh", List("-c", DirectoryState, "infradesk", directory(spec)), 10.seconds).flatMap { r =>
            if (r.exitCode == 0 && r.stdout.trim == "PRESENT") repairExistingInSession(s, c, sshUid, spec, credential, changed)
            else if (r.exitCode == 0 && r.stdout.trim == "ABSENT") installInSession(s, c, sshUid, spec, credential, changed)
            else IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
          }
        case LocalInstallationState.PortConflict => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
        case LocalInstallationState.Foreign => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
        case LocalInstallationState.Unknown => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
      }
    })
  }

  private def repairExistingInSession(s: RemoteConfigurationSession[IO], c: ProfileCommands, sshUid: Int,
    spec: RemnawaveNodeRemoteSpec, credential: NodeInstallationData, changed: Ref[IO, Boolean]): IO[ProvisioningStepResult] = {
    val files = new ProfileManagedFiles(s, c, sshUid, spec.onboardingId)
    val dir = directory(spec)
    val marker = markerLine(spec)
    val env = s"SECRET_KEY=${credential.secretKey}\nNODE_PORT=${spec.nodePort}\n".getBytes(StandardCharsets.UTF_8)
    val managed = renderManaged(spec, ProfileManagedFiles.sha256(env)).getBytes(StandardCharsets.UTF_8)
    val names = List(".env", "compose.yml", "managed.json")
    for {
      _ <- prepareInstallation(c, spec, sshUid).flatTap(_ => changed.set(true))
      _ <- installationCommand(c, spec, CleanupInstallationStaging, "CLEAN", sshUid)
      existing <- names.traverse(name => s.read(s"$dir/$name", if (name == ".env") 65536 + 64 else 65536).map(name -> _))
      current = existing.toMap
      _ <- IO.raiseUnless(names.forall(name => safeExistingFile(current(name), name).isRight))(
        ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
      // A reviewed image-line change keeps the installation identity even when cleaning residue.
      compose = controlledComposeReference(spec, current("compose.yml").bytes)
        .fold(renderCompose(spec))(reference => renderCompose(spec).replace(
          s"    image: ${spec.imageReference}\n", s"    image: $reference\n")).getBytes(StandardCharsets.UTF_8)
      desired = Map(".env" -> env, "compose.yml" -> compose, "managed.json" -> managed)
      runtimeImage = controlledComposeReference(spec, compose).getOrElse(spec.imageReference)
      alreadyExact = names.forall(name => current(name).exists && current(name).bytes.sameElements(desired(name)))
      imageExists <- c.capture("docker", List("image", "inspect", "-f", "{{.Id}}", runtimeImage), 20.seconds)
      _ <- if (alreadyExact && imageExists.exitCode == 0 && imageExists.stdout.trim.nonEmpty) IO.unit
        else if (alreadyExact) checked(c, "docker", List("pull", runtimeImage), 5.minutes).flatTap(_ => changed.set(true))
        else for {
          _ <- if (current(".env").exists && current(".env").bytes.sameElements(env)) IO.unit else files.replace(s"$dir/.env", marker, env, current(".env").sha256,
            installOwned = true, targetMode = 384, beforeCommit = assertOwned(c, spec, sshUid)).flatTap(_ => changed.set(true))
          _ <- if (current("compose.yml").exists && current("compose.yml").bytes.sameElements(compose)) IO.unit else files.replace(s"$dir/compose.yml", marker, compose, current("compose.yml").sha256,
            installOwned = true, validate = Some(ManagedNodeCompose.validation(dir)),
            validationFailureCode = "PROVISIONING_NODE_COMPOSE_INVALID", beforeCommit = assertOwned(c, spec, sshUid)).flatTap(_ => changed.set(true))
          _ <- if (current("compose.yml").exists && current("compose.yml").bytes.sameElements(compose))
            checked(c, "docker", List("compose", "-f", s"$dir/compose.yml", "--env-file", s"$dir/.env", "config", "-q")) else IO.unit
          _ <- if (current("managed.json").exists && current("managed.json").bytes.sameElements(managed)) IO.unit else files.replace(s"$dir/managed.json", marker, managed, current("managed.json").sha256,
            installOwned = true, beforeCommit = assertOwned(c, spec, sshUid)).flatTap(_ => changed.set(true))
          imageExistsAfterRepair <- c.capture("docker", List("image", "inspect", "-f", "{{.Id}}", runtimeImage), 20.seconds)
          _ <- if (imageExistsAfterRepair.exitCode == 0 && imageExistsAfterRepair.stdout.trim.nonEmpty) IO.unit
            else checked(c, "docker", List("pull", runtimeImage), 5.minutes).flatTap(_ => changed.set(true))
        } yield ()
    } yield success("repaired" -> (!alreadyExact).toString)
  }

  override def retireInstallation(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, sshUid) =>
      validate(spec) *> c.capture("sh", List("-c", RetireInstallation, "infradesk") ++ recoveryArgs(spec, sshUid), 2.minutes).flatMap { r =>
        if (r.exitCode != 0 || r.stdoutTruncated || r.stderrTruncated)
          IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_RETIREMENT_UNCERTAIN", uncertain = true))
        else r.stdout.trim match {
          case "RETIRED" => IO.pure(success("retired" -> "true"))
          case "ABSENT" => IO.pure(success("retired" -> "false"))
          case "FOREIGN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
          case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_RETIREMENT_UNCERTAIN", uncertain = true))
        }
      }
    })

  override def start(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_, c, _) => validate(spec) *> startNode(c, spec) })

  override def observe(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[RemnawaveNodeLocalEvidence] =
    withSession(connection) { (_, c, sshUid) => validate(spec) *> recoveryState(c, spec, sshUid).flatMap { state => c.capture("sh", List("-c", Observe, "infradesk",
      directory(spec), markerLine(spec), spec.imageReference, containerName(spec), spec.nodePort.toString,
      ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8)), managedPrefix(spec)), 25.seconds).flatMap { r =>
      val parts = if (r.exitCode == 0 && !r.stdoutTruncated && !r.stderrTruncated) r.stdout.trim.split(":", -1).toList else Nil
      parts match {
        case List(files, image, running, port, stable) if parts.forall(x => x == "1" || x == "0") =>
          firewallProof(c, spec).map { fw =>
            RemnawaveNodeLocalEvidence(files == "1", image == "1", running == "1", port == "1", stable == "1", fw,
              state)
          }
        case _ => IO.pure(RemnawaveNodeLocalEvidence(false, false, false, false, false, false,
          state))
      }
    }}}

  override def installationPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[Boolean] = withSession(connection) { (_, c, sshUid) =>
    validate(spec) *> recoveryState(c, spec, sshUid).flatMap {
      case LocalInstallationState.OwnedComplete => c.capture("sh", List("-c", InstallationProof, "infradesk", directory(spec), markerLine(spec),
      spec.imageReference, spec.nodePort.toString, ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8)),
      managedPrefix(spec)), 20.seconds).map(r => r.exitCode == 0 && r.stdout.trim == "1:1" && !r.stdoutTruncated && !r.stderrTruncated)
      case _ => IO.pure(false)
    }
  }

  override def localInstallationState(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[domain.integration.LocalInstallationState] =
    withSession(connection) { (_, c, sshUid) => validate(spec) *> recoveryState(c, spec, sshUid) }

  override def localInstallationObservation(connection: Connection, spec: RemnawaveNodeRemoteSpec)(implicit F: cats.Functor[IO]): IO[LocalInstallationObservation] =
    withSession(connection) { (_,c,sshUid) => validate(spec) *> recoveryObservation(c,spec,sshUid).flatMap { observed =>
      if(!observed.state.repairable) IO.pure(observed)
      else nodeRules(c,spec).flatMap { rules =>
        validateOwnedFirewall(rules,spec,replaceSources=false).as(
          if(observed.state==LocalInstallationState.Absent && rules.exists(_.owned))
            LocalInstallationObservation.fromState(LocalInstallationState.OwnedPartial) else observed)
      }.handleError {
        case RemoteConfigurationFailure.CommandTimeout => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)
        case p: ProfileRemoteFailure if p.truncated => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.OutputTruncated)
        case p: ProfileRemoteFailure if p.code=="PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT" =>
          LocalInstallationObservation(LocalInstallationState.Foreign,Some(LocalInstallationDiagnosis.OwnerUnproven))
        case _ => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.FirewallStateUnknown)
      }
    }}.timeoutTo(35.seconds,IO.pure(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)))
      .handleError {
        case p: ProfileRemoteFailure if p.truncated => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.OutputTruncated)
        case p: ProfileRemoteFailure if p.code=="PROVISIONING_REMOTE_TIMEOUT" => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout)
        case _ => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.SshUnavailable)
      }

  override def firewallPresent(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[Boolean] = withSession(connection) { (_, c, _) =>
    validate(spec) *> firewallRules(spec).flatMap(wanted => if (wanted.isEmpty) IO.pure(false) else firewallProof(c, spec))
  }

  override def managedPanelCidrs(connection: Connection, spec: RemnawaveNodeRemoteSpec): IO[List[String]] =
    withSession(connection) { (_, c, _) =>
      validatePort(spec.nodePort) *> nodeRules(c, spec).map(_.filter(r => r.owned && r.rule.id == "node" &&
        r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)
        .flatMap(_.rule.sources).distinct.sorted)
    }

  override def reconcilePanelSources(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    previousSources: List[String], targetSources: List[String]): IO[ProvisioningStepResult] =
    step(withSession(connection) { (_,c,_) =>
      val union = (previousSources ++ spec.panelCidrs).distinct.sorted
      IO.raiseUnless(targetSources.nonEmpty && targetSources.distinct==targetSources && targetSources.forall(union.contains))(
        ProfileRemoteFailure("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY")) *>
      validate(spec.copy(panelCidrs=union)) *> nodeRules(c,spec).flatMap { rules =>
        val current = rules.filter(_.owned).flatMap(_.rule.sources).distinct.sorted
        val compatible = current.forall(union.contains) &&
          (previousSources.forall(current.contains) || targetSources.forall(current.contains))
        IO.raiseUnless(compatible && rules.filter(_.owned).forall(r => r.rule.id=="node" &&
          r.action=="allow" && r.rule.protocol=="tcp" && r.rule.port==spec.nodePort) &&
          !rules.exists(r => !r.owned && r.rule.port==spec.nodePort))(
          ProfileRemoteFailure("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY"))
      }
    }.flatMap(_ => configureFirewall(connection,spec.copy(panelCidrs=targetSources),replaceSources=true,
      reviewedSources=Some((previousSources ++ spec.panelCidrs).toSet))))

  override def connectivitySources(connection: Connection,spec: RemnawaveNodeRemoteSpec,
    reviewedSources: List[String]): IO[List[String]] = withSession(connection) { (_,c,_) =>
    validate(spec) *> c.capture("ufw",List("status"),15.seconds).flatMap(r => IO.raiseUnless(
      r.exitCode==0 && !r.stdoutTruncated && r.stdout.linesIterator.exists(_.trim=="Status: active"))(
      ProfileRemoteFailure("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY"))) *> nodeRules(c,spec).flatMap(rules =>
      connectivityRules(rules,spec,Some(reviewedSources.toSet)).as(rules.filter(_.owned).flatMap(_.rule.sources).distinct.sorted))
  }.handleErrorWith {
    case p: ProfileRemoteFailure if Set("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY","FIREWALL_RULE_UNSUPPORTED")(p.code) =>
      IO.raiseError(application.port.PanelConnectivityFailure.ManualOnly)
    case e => IO.raiseError(e)
  }

  private def connectivityRules(rules: List[ProfileFirewallRule],spec: RemnawaveNodeRemoteSpec,
    reviewed: Option[Set[String]]): IO[Unit] = reviewed.traverse_(sources => IO.raiseWhen(
    rules.exists(r => !r.owned && r.rule.port==spec.nodePort) || rules.exists(r => r.owned &&
      (r.rule.id!="node" || r.action!="allow" || r.rule.protocol!="tcp" || r.rule.port!=spec.nodePort || !r.rule.sources.forall(sources))))(
      ProfileRemoteFailure("REMNAWAVE_PANEL_CONNECTIVITY_MANUAL_ONLY")))

  private def withSession[A](connection: Connection)(use: (RemoteConfigurationSession[IO], ProfileCommands, Int) => IO[A]): IO[A] =
    transport.withSessionBounded(connection, 65536) { s =>
      new ProfileCommands(s, root = false).capture("id", List("-u"), 5.seconds, privileged = false).flatMap { r =>
        if (r.exitCode != 0 || r.stdoutTruncated || !r.stdout.trim.matches("[0-9]{1,8}"))
          IO.raiseError(ProfileRemoteFailure("PROVISIONING_SSH_REJECTED"))
        else use(s, new ProfileCommands(s, r.stdout.trim == "0"), r.stdout.trim.toInt)
      }
    }.handleErrorWith(sanitize)

  private def runPreflight(c: ProfileCommands, port: Int, allowDockerMissing: Boolean): IO[String] =
    c.capture("sh", List("-c", Preflight, "infradesk", port.toString,
      if (allowDockerMissing) "1" else "0"), 20.seconds).map { r =>
      if (r.exitCode == 0 && Set("CLEAR", "PORT", "FOREIGN", "UNKNOWN", "DOCKER")(r.stdout.trim)) r.stdout.trim
      else "UNKNOWN"
    }

  private def preflightInSession(c: ProfileCommands, port: Int): IO[Unit] =
    runPreflight(c, port, allowDockerMissing = false).flatMap {
      case "CLEAR" => IO.unit
      case "PORT" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
      case "DOCKER" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_DOCKER_UNAVAILABLE"))
      case "UNKNOWN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE"))
      case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
    }

  private def recoveryState(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec, sshUid: Int): IO[LocalInstallationState] =
    recoveryObservation(c,spec,sshUid).map(_.state)

  private def recoveryObservation(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec, sshUid: Int): IO[LocalInstallationObservation] =
    c.capture("sh", List("-c", RecoveryProbe, "infradesk") ++ recoveryArgs(spec, sshUid), 20.seconds)
      .map { r =>
        if(r.exitCode!=0) LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ProbeExecutionFailed)
        else r.stdout.trim.split(":",-1).toList match {
          case List("UNKNOWN",reason) => LocalInstallationObservation.unknown(
            LocalInstallationDiagnosis.fromCode(reason).getOrElse(LocalInstallationDiagnosis.ProbeOutputInvalid))
          case List("UNKNOWN") => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ProbeOutputInvalid)
          case List("FOREIGN") => LocalInstallationObservation(LocalInstallationState.Foreign,Some(LocalInstallationDiagnosis.OwnerUnproven))
          case List(code) if LocalInstallationState.all.exists(_.code==code) => LocalInstallationObservation.fromState(LocalInstallationState.fromCode(code))
          case _ => LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ProbeOutputInvalid)
        }
      }.handleErrorWith {
        case RemoteConfigurationFailure.CommandTimeout => IO.pure(LocalInstallationObservation.unknown(LocalInstallationDiagnosis.ObservationTimeout))
        case e => IO.raiseError(e)
      }

  private def recoveryArgs(spec: RemnawaveNodeRemoteSpec, sshUid: Int): List[String] = List(directory(spec), markerLine(spec),
    managedPrefix(spec), containerName(spec), spec.imageReference, spec.nodePort.toString, ownershipDirectory(spec),
    ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8)), sshUid.toString)

  private def installationCommand(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec,
    script: String, expected: String, sshUid: Int): IO[Unit] =
    c.capture("sh", List("-c", script, "infradesk") ++ recoveryArgs(spec, sshUid), 30.seconds).flatMap { r =>
      r.stdout.trim match {
        case value if r.exitCode == 0 && value == expected => IO.unit
        case "FOREIGN" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED"))
        case "PORT_CONFLICT" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PORT_OCCUPIED"))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_PREFLIGHT_UNAVAILABLE", uncertain = true))
      }
    }

  private def prepareInstallation(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec, sshUid: Int): IO[Unit] =
    installationCommand(c, spec, PrepareInstallation, "PREPARED", sshUid)

  private def assertOwned(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec, sshUid: Int): IO[Unit] =
    recoveryState(c, spec, sshUid).flatMap(state => IO.raiseUnless(Set[LocalInstallationState](LocalInstallationState.OwnedComplete,
      LocalInstallationState.OwnedPartial, LocalInstallationState.OwnedDamaged)(state))(
      ProfileRemoteFailure("PROVISIONING_NODE_INSTALLATION_UNMANAGED")))

  private def safeExistingFile(file: RemoteConfigurationFile, name: String): Either[String, Unit] = {
    if (!file.exists) Right(())
    else {
      val mode = if (name == ".env") 384 else 420
      Either.cond(file.metadata.exists(m => m.uid == 0 && m.gid == 0 && m.permissions == mode), (),
        "PROVISIONING_NODE_INSTALLATION_UNMANAGED")
    }
  }

  private def startNode(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[ProvisioningStepResult] = {
    val dir = directory(spec)
    val composeHash = ProfileManagedFiles.sha256(renderCompose(spec).getBytes(StandardCharsets.UTF_8))
    c.capture("sh", List("-c", Start, "infradesk", dir, markerLine(spec), spec.imageReference,
      spec.nodePort.toString, composeHash, managedPrefix(spec), containerName(spec)), 3.minutes).flatMap { r =>
      if (r.exitCode != 0 || r.stdoutTruncated || r.stderrTruncated)
        IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true, truncated = r.stdoutTruncated || r.stderrTruncated))
      else r.stdout.trim match {
        case "STARTED" => IO.pure(success("startRequested" -> "true"))
        case "FILES" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_MANAGED_FILES_CHANGED"))
        case "CONFIG" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_COMPOSE_INVALID"))
        case "START_FAILED" => IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_START_FAILED"))
        case _ => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
      }
    }
  }

  private def checked(c: ProfileCommands, executable: String, args: List[String], timeout: FiniteDuration = 30.seconds): IO[Unit] =
    c.capture(executable, args, timeout).flatMap(r => if (r.exitCode == 0) IO.unit else IO.raiseError(ProfileRemoteFailure("PROVISIONING_NODE_REMOTE_COMMAND_FAILED")))

  private def nodeRules(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[List[ProfileFirewallRule]] =
    c.capture("ufw", List("show", "added"), 15.seconds).flatMap { r =>
      if (r.exitCode != 0 || r.stdoutTruncated || r.stderrTruncated) IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_OBSERVATION_FAILED"))
      else SshRemnawaveNodeRemote.parseNodeRules(r.stdout, spec.resourceId, spec.externalNodeId).leftMap(ProfileRemoteFailure(_)).liftTo[IO]
    }

  private def firewallProof(c: ProfileCommands, spec: RemnawaveNodeRemoteSpec): IO[Boolean] =
    c.capture("ufw", List("status"), 15.seconds).flatMap { status =>
      if (status.exitCode != 0 || status.stdoutTruncated || !status.stdout.linesIterator.exists(_.trim == "Status: active")) IO.pure(false)
      else nodeRules(c, spec).map(rules => firewallExact(rules, spec))
    }

  private def canary(connection: Connection): IO[Unit] = transport.withSessionBounded(connection, 128) { s =>
    new ProfileCommands(s, root = true).capture("id", List("-u"), 5.seconds, privileged = false).flatMap(r =>
      if (r.exitCode == 0 && r.stdout.trim.matches("[0-9]{1,8}")) IO.unit
      else IO.raiseError(ProfileRemoteFailure("PROVISIONING_FIREWALL_CANARY_FAILED", uncertain = true)))
  }.timeoutTo(15.seconds, IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_TIMEOUT", uncertain = true)))

  private def sanitize[A](e: Throwable): IO[A] = e match {
    case p: ProfileRemoteFailure => IO.raiseError(p)
    case RemoteConfigurationFailure.HostKeyMismatch => IO.raiseError(ProfileRemoteFailure("PROVISIONING_HOST_KEY_MISMATCH"))
    case RemoteConfigurationFailure.HostKeyNotTrusted => IO.raiseError(ProfileRemoteFailure("PROVISIONING_HOST_KEY_NOT_TRUSTED"))
    case RemoteConfigurationFailure.AuthenticationFailed => IO.raiseError(ProfileRemoteFailure("PROVISIONING_SSH_AUTHENTICATION_FAILED"))
    case _: RemoteConfigurationFailure => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
    case NonFatal(_) => IO.raiseError(ProfileRemoteFailure("PROVISIONING_REMOTE_UNAVAILABLE", uncertain = true))
  }
  private def success(pair: (String, String)) = ProvisioningStepResult(Map(pair), None, None)

  private def step(action: IO[ProvisioningStepResult]): IO[ProvisioningStepResult] =
    action.handleError(e => failure(e))

  private def stepTracked(changed: Ref[IO, Boolean])(action: IO[ProvisioningStepResult]): IO[ProvisioningStepResult] =
    action.handleErrorWith(e => changed.get.map(mutated => failure(e, uncertainAfterMutation = mutated)))

  private def installationStep(connection: Connection, spec: RemnawaveNodeRemoteSpec,
    changed: Ref[IO, Boolean])(action: IO[ProvisioningStepResult]): IO[ProvisioningStepResult] =
    action.handleErrorWith { e => changed.get.flatMap { mutated =>
      val outcome = failure(e)
      if (!mutated || outcome.uncertain || outcome.outputTruncated) IO.pure(outcome)
      else localInstallationState(connection, spec).timeoutTo(25.seconds, IO.pure(LocalInstallationState.Unknown))
        .handleError(_ => LocalInstallationState.Unknown).map { state =>
          outcome.copy(facts = Map("localInstallationState" -> state.code),
            uncertain = state == LocalInstallationState.Unknown)
        }
    }}

  private def failure(e: Throwable, uncertainAfterMutation: Boolean = false): ProvisioningStepResult = e match {
    case p: ProfileRemoteFailure => ProvisioningStepResult(Map.empty, Some(p.code), None,
      uncertain = p.uncertain || uncertainAfterMutation, outputTruncated = p.truncated)
    case RemoteConfigurationFailure.HostKeyMismatch => ProvisioningStepResult(Map.empty, Some("PROVISIONING_HOST_KEY_MISMATCH"), None)
    case RemoteConfigurationFailure.HostKeyNotTrusted => ProvisioningStepResult(Map.empty, Some("PROVISIONING_HOST_KEY_NOT_TRUSTED"), None)
    case RemoteConfigurationFailure.AuthenticationFailed => ProvisioningStepResult(Map.empty, Some("PROVISIONING_SSH_AUTHENTICATION_FAILED"), None)
    case _: RemoteConfigurationFailure => ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true)
    case NonFatal(_) => ProvisioningStepResult(Map.empty, Some("PROVISIONING_REMOTE_UNAVAILABLE"), None, uncertain = true)
  }
}

private[ssh] object SshRemnawaveNodeRemote {
  private val reviewedTags = Set("remnawave/node:2.8.0", "remnawave/node:3.4.1")
  private val imageDigest = "^remnawave/node@sha256:[0-9a-f]{64}$".r
  private def validatePort(port: Int): IO[Unit] = IO.raiseUnless(port >= 1 && port <= 65535)(ProfileRemoteFailure("PROVISIONING_NODE_INVALID_SPEC"))
  private def validateSecret(data: String): IO[Unit] = IO {
    val valid = data != null && data.length <= 65536 && data.matches("[A-Za-z0-9+/]+={0,2}") &&
      scala.util.Try(java.util.Base64.getDecoder.decode(data)).isSuccess
    if (!valid) throw ProfileRemoteFailure("PROVISIONING_NODE_CREDENTIAL_INVALID")
  }
  private def validate(spec: RemnawaveNodeRemoteSpec): IO[Unit] = for {
    _ <- validatePort(spec.nodePort)
    _ <- IO.raiseUnless(reviewedTags(spec.imageReference) || imageDigest.matches(spec.imageReference) ||
      RemnawaveNodeReleaseCatalog.managedReferences.contains(spec.imageReference))(
      ProfileRemoteFailure("PROVISIONING_NODE_IMAGE_UNPINNED"))
    _ <- IO.raiseUnless(spec.panelCidrs.nonEmpty && spec.panelCidrs.forall(c => c.contains("/") &&
      ServerProfileContent.canonicalFirewallSource(c).exists(x => x == c && x != "ANY" && !x.endsWith("/0"))))(
      ProfileRemoteFailure("PROVISIONING_NODE_INVALID_CIDR"))
  } yield ()
  private[ssh] def directory(s: RemnawaveNodeRemoteSpec) = s"/opt/infradesk/remnawave/${s.externalNodeId}"
  private[ssh] def stagingDirectory(s: RemnawaveNodeRemoteSpec): String = {
    val imageHash = ProfileManagedFiles.sha256(s.imageReference.getBytes(StandardCharsets.UTF_8))
    s"/opt/infradesk/remnawave/.staging-${s.externalNodeId}-${s.onboardingId}-${s.resourceId}-$imageHash"
  }
  // mkdir publishes this complete non-secret identity atomically. It must precede the node directory.
  private[ssh] def ownershipDirectory(s: RemnawaveNodeRemoteSpec): String =
    s"/opt/infradesk/remnawave/.owner-${s.externalNodeId}-${s.onboardingId}-${s.resourceId}-${ProfileManagedFiles.sha256(s.imageReference.getBytes(StandardCharsets.UTF_8))}"
  private[ssh] def containerName(s: RemnawaveNodeRemoteSpec) = s"infradesk-remnawave-${s.externalNodeId}"
  private[ssh] def markerLine(s: RemnawaveNodeRemoteSpec) = s"# infradesk-managed run=${s.onboardingId} node=${s.externalNodeId} resource=${s.resourceId} image=${s.imageReference}"
  private[ssh] def managedPrefix(s: RemnawaveNodeRemoteSpec) =
    s"{\"managedBy\":\"infradesk\",\"runId\":\"${s.onboardingId}\",\"nodeId\":\"${s.externalNodeId}\",\"resourceId\":\"${s.resourceId}\",\"image\":\"${s.imageReference}\",\"envSha256\":\""
  private[ssh] def renderCompose(s: RemnawaveNodeRemoteSpec): String =
    s"""${markerLine(s)}
       |services:
       |  node:
       |    image: ${s.imageReference}
       |    container_name: ${containerName(s)}
       |    network_mode: host
       |    cap_add: ["NET_ADMIN"]
       |    ulimits:
       |      nofile:
       |        soft: 1048576
       |        hard: 1048576
       |    env_file: .env
       |    restart: unless-stopped
       |""".stripMargin.replace("\r\n","\n")
  private[ssh] def controlledComposeReference(s: RemnawaveNodeRemoteSpec, bytes: Array[Byte]): Option[String] = {
    val text = new String(bytes, StandardCharsets.UTF_8)
    val images = text.linesIterator.filter(_.startsWith("    image: ")).toList
    images match {
      case List(line) =>
        val reference = line.stripPrefix("    image: ")
        Option.when(RemnawaveNodeReleaseCatalog.managedReferences.contains(reference) &&
          text.replace(s"    image: $reference\n", s"    image: ${s.imageReference}\n") == renderCompose(s))(reference)
      case _ => None
    }
  }
  private def renderManaged(s: RemnawaveNodeRemoteSpec, envHash: String): String = s"${managedPrefix(s)}$envHash\"}\n"
  private def firewallRules(s: RemnawaveNodeRemoteSpec): IO[List[(String, String)]] = IO.pure(s.panelCidrs.distinct.sorted.map(c =>
    c -> s"infradesk:remnawave:${s.resourceId}:${s.externalNodeId}:node"))
  private def parseSsh(raw: String): Either[ProfileRemoteFailure, (String, Int)] = raw.trim.split("\\s+").toList match {
    case source :: clientPort :: server :: serverPort :: Nil if ServerProfileContent.canonicalFirewallSource(source).isRight &&
      ServerProfileContent.canonicalFirewallSource(server).isRight && !source.contains("/") && !server.contains("/") =>
      serverPort.toIntOption.filter(p => p >= 1 && p <= 65535).map(source -> _).toRight(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
    case _ => Left(ProfileRemoteFailure("PROVISIONING_FIREWALL_SSH_ACCESS_UNPROVEN"))
  }
  private def broadManagementAllow(rules: List[ProfileFirewallRule], spec: RemnawaveNodeRemoteSpec): Boolean =
    rules.exists(r => !r.owned && r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort &&
      r.rule.sources.exists(source => source == "ANY" || source.endsWith("/0") || !spec.panelCidrs.contains(source))) ||
      rules.exists(r => !r.owned && r.action != "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)

  private def validateOwnedFirewall(rules: List[ProfileFirewallRule], spec: RemnawaveNodeRemoteSpec, replaceSources: Boolean): IO[Unit] =
    IO.raiseWhen(rules.exists(r => r.owned && (r.action != "allow" || r.rule.id != "node" ||
      r.rule.protocol != "tcp" || r.rule.port != spec.nodePort || (!replaceSources && !r.rule.sources.forall(spec.panelCidrs.contains)))))(
      ProfileRemoteFailure("PROVISIONING_FIREWALL_OWNERSHIP_CONFLICT"))

  private def firewallExact(rules: List[ProfileFirewallRule], spec: RemnawaveNodeRemoteSpec): Boolean = {
    val expected = spec.panelCidrs.distinct.toSet
    val ownedNamespace = rules.filter(_.owned)
    val ownedNode = rules.filter(r => r.owned && r.rule.id == "node")
    val managedSources = ownedNode.filter(r => r.action == "allow" && r.rule.protocol == "tcp" && r.rule.port == spec.nodePort)
      .flatMap(_.rule.sources).toSet
    expected.nonEmpty && ownedNamespace.forall(_.rule.id == "node") && managedSources == expected &&
      !ownedNode.exists(r => r.action != "allow" || r.rule.protocol != "tcp" || r.rule.port != spec.nodePort) &&
      !broadManagementAllow(rules, spec)
  }
  private val Preflight = """set -u
    |port=$1; allowDockerMissing=$2; root=/opt/infradesk/remnawave
    |echo_result() { printf '%s' "$1"; exit 0; }
    |command -v ss >/dev/null 2>&1 || echo_result UNKNOWN
    |listeners=$(ss -H -ltn "sport = :$port" 2>/dev/null) || echo_result UNKNOWN
    |[ -z "$listeners" ] || echo_result PORT
    |command -v grep >/dev/null 2>&1 || echo_result UNKNOWN
    |for p in /opt /opt/infradesk "$root"; do
    |  [ ! -L "$p" ] || echo_result FOREIGN
    |  if [ -e "$p" ]; then
    |    [ -d "$p" ] && [ "$(stat -c '%u' "$p" 2>/dev/null)" = 0 ] || echo_result FOREIGN
    |    mode=$(stat -c '%a' "$p" 2>/dev/null) || echo_result UNKNOWN
    |    [ $((0$mode & 022)) -eq 0 ] || echo_result FOREIGN
    |  fi
    |done
    |for p in /opt/remnawave /opt/remnawave-node; do [ ! -e "$p" ] && [ ! -L "$p" ] || echo_result FOREIGN; done
    |if [ -d "$root" ]; then
    |  for p in "$root"/* "$root"/.[!.]*; do
    |    [ -e "$p" ] || [ -L "$p" ] || continue
    |    echo_result FOREIGN
    |  done
    |fi
    |if ! command -v docker >/dev/null 2>&1; then [ "$allowDockerMissing" = 1 ] && echo_result CLEAR || echo_result DOCKER; fi
    |containers=$(docker ps -a --format '{{.Image}} {{.Names}}') || echo_result UNKNOWN
    |printf '%s\n' "$containers" | grep -Eiq 'remnawave|node.*remna|remna.*node' && echo_result FOREIGN
    |echo_result CLEAR
    |""".stripMargin
  private val DirectoryState = """set -u
    |d=$1
    |if [ -L "$d" ]; then printf FOREIGN; elif [ -d "$d" ]; then
    |  [ "$(stat -c '%u:%g:%a' "$d" 2>/dev/null)" = '0:0:755' ] && printf PRESENT || printf FOREIGN
    |else printf ABSENT; fi
    |""".stripMargin
  private[ssh] def controlledComposeProof(failure: String): String =
    s"""[ "$$(grep -c '^    image: ' "$$d/compose.yml")" = 1 ] || $failure
       |controlledImage=$$(sed -n 's/^    image: //p' "$$d/compose.yml")
       |case "$$controlledImage" in ${domain.integration.RemnawaveNodeReleaseCatalog.managedReferences.mkString("|")} ) ;; *) $failure ;; esac
       |originalImage=$${marker##* image=}
       |normalizedHash=$$(awk -v image="$$originalImage" '{if ($$0 ~ /^    image: /) print "    image: " image; else print}' "$$d/compose.yml" | sha256sum | cut -d' ' -f1)
       |[ "$$normalizedHash" = "$$composeHash" ] || $failure
       |""".stripMargin

  private[ssh] val RecoveryProbe = """set -u
    |d=$1; marker=$2; managedPrefix=$3; name=$4; image=$5; port=$6; claim=$7; composeHash=$8; sshUid=${9:-0}
    |stageDir=${claim%/.owner-*}/.staging-${claim##*/.owner-}
    |result() { printf '%s' "$1"; if [ "$1" = UNKNOWN ]; then printf ':%s' "${2:?Missing sanitized diagnosis}"; fi; exit 0; }
    |owner=${marker#"# infradesk-managed run="}; owner=${owner%% *}
    |resource=${marker#* resource=}; resource=${resource%% *}; node=${d##*/}
    |imageDigest=$(printf '%s' "$image" | sha256sum 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_HASH_PROBE_FAILED
    |imageHash=${imageDigest%% *}
    |[ "${#imageHash}" = 64 ] || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_HASH_PROBE_FAILED
    |case "$imageHash" in *[!a-f0-9]*) result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_HASH_PROBE_FAILED ;; esac
    |[ "$claim" = "/opt/infradesk/remnawave/.owner-$node-$owner-$resource-$imageHash" ] || result FOREIGN
    |for p in /opt /opt/infradesk /opt/infradesk/remnawave; do
    |  [ ! -L "$p" ] || result FOREIGN
    |  if [ -e "$p" ]; then [ -d "$p" ] || result FOREIGN; metadata=$(stat -c '%u' "$p" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = 0 ] || result FOREIGN; mode=$(stat -c '%a' "$p" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ $((0$mode & 07022)) -eq 0 ] || result FOREIGN; fi
    |done
    |claimOwned=0
    |for p in /opt/infradesk/remnawave/.owner-"$node"-*; do
    |  [ -e "$p" ] || [ -L "$p" ] || continue
    |  [ "$p" = "$claim" ] || result FOREIGN
    |done
    |for p in /opt/infradesk/remnawave/.staging-"$node"-*; do
    |  [ -e "$p" ] || [ -L "$p" ] || continue
    |  [ "$p" = "$stageDir" ] || result FOREIGN
    |done
    |if [ -e "$claim" ] || [ -L "$claim" ]; then
    |  [ ! -L "$claim" ] && [ -d "$claim" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%a' "$claim" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:700' ] || result FOREIGN
    |  for f in "$claim"/* "$claim"/.[!.]* "$claim"/..?*; do [ ! -e "$f" ] && [ ! -L "$f" ] || result FOREIGN; done
    |  claimOwned=1
    |fi
    |stageOwned=0
    |if [ -e "$stageDir" ] || [ -L "$stageDir" ]; then
    |  [ "$claimOwned" = 1 ] || result FOREIGN
    |  [ ! -L "$stageDir" ] && [ -d "$stageDir" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%a' "$stageDir" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:755' ] || result FOREIGN
    |  for f in "$stageDir"/* "$stageDir"/.[!.]* "$stageDir"/..?*; do
    |    [ -e "$f" ] || [ -L "$f" ] || continue
    |    case "$f" in
    |      "$stageDir/compose.yml"|"$stageDir/.env"|"$stageDir/managed.json")
    |        [ ! -L "$f" ] && [ -f "$f" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%h' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:1' ] || result FOREIGN
    |        mode=$(stat -c '%a' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE
    |        case "$f:$mode" in "$stageDir/.env:600"|"$stageDir/compose.yml:644"|"$stageDir/managed.json:644") ;; *) result FOREIGN ;; esac ;;
    |      "$stageDir/.infradesk-$owner-"*)
    |        nonce=${f#"$stageDir/.infradesk-$owner-"}
    |        printf '%s' "$nonce" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || result FOREIGN
    |        [ ! -L "$f" ] && [ -d "$f" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%a' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:700' ] || result FOREIGN
    |        for child in "$f"/* "$f"/.[!.]* "$f"/..?*; do [ ! -e "$child" ] && [ ! -L "$child" ] && continue; case "$child" in "$f/candidate"|"$f/previous") ;; *) result FOREIGN ;; esac; [ ! -L "$child" ] && [ -f "$child" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%h' "$child" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:1' ] || result FOREIGN; mode=$(stat -c '%a' "$child" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; case "$mode" in 600|644) ;; *) result FOREIGN ;; esac; done ;;
    |      *) result FOREIGN ;;
    |    esac
    |  done
    |  stageOwned=1
    |fi
    |[ ! -e /opt/remnawave ] && [ ! -L /opt/remnawave ] && [ ! -e /opt/remnawave-node ] && [ ! -L /opt/remnawave-node ] || result FOREIGN
    |command -v ss >/dev/null 2>&1 || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_PORT_STATE_UNKNOWN
    |listeners=$(ss -H -ltn "sport = :$port" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_PORT_STATE_UNKNOWN
    |command -v docker >/dev/null 2>&1 || { [ ! -e "$d" ] && [ ! -L "$d" ] && [ "$claimOwned" = 0 ] && [ -z "$listeners" ] && result ABSENT; result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_CONTAINER_STATE_UNKNOWN; }
    |allContainers=$(docker ps -a --format '{{.Names}}') || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_CONTAINER_STATE_UNKNOWN
    |ownContainer=0; printf '%s\n' "$allContainers" | grep -Fxq "$name" && ownContainer=1 || true
    |staging=0
    |for upload in /tmp/infradesk-"$owner"-*; do
    |  [ -e "$upload" ] || [ -L "$upload" ] || continue
    |  nonce=${upload#"/tmp/infradesk-$owner-"}
    |  printf '%s' "$nonce" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || result FOREIGN
    |  [ ! -L "$upload" ] && [ -d "$upload" ] || result FOREIGN; metadata=$(stat -c '%u:%a' "$upload" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_STAGING_METADATA_UNAVAILABLE; [ "$metadata" = "$sshUid:700" ] || result FOREIGN
    |  for file in "$upload"/* "$upload"/.[!.]* "$upload"/..?*; do
    |    [ -e "$file" ] || [ -L "$file" ] || continue
    |    [ "$file" = "$upload/input" ] && [ ! -L "$file" ] && [ -f "$file" ] &&
    |      [ "$(stat -c '%u:%a:%h' "$file" 2>/dev/null)" = "$sshUid:600:1" ] || result FOREIGN
    |  done
    |  staging=1
    |done
    |if [ ! -e "$d" ] && [ ! -L "$d" ]; then [ "$ownContainer" = 0 ] || result FOREIGN; [ -z "$listeners" ] || result PORT_CONFLICT; [ "$claimOwned" = 0 ] && [ "$stageOwned" = 0 ] && result ABSENT; result OWNED_PARTIAL; fi
    |[ ! -L "$d" ] && [ -d "$d" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%a' "$d" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:755' ] || result FOREIGN
    |for f in "$d"/* "$d"/.[!.]* "$d"/..?*; do
    |  [ -e "$f" ] || [ -L "$f" ] || continue
    |  case "$f" in
    |    "$d/.infradesk-$owner-"*)
    |      nonce=${f#"$d/.infradesk-$owner-"}
    |      printf '%s' "$nonce" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || result FOREIGN
    |      [ ! -L "$f" ] && [ -d "$f" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%a' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:700' ] || result FOREIGN
    |      for child in "$f"/* "$f"/.[!.]* "$f"/..?*; do
    |        [ -e "$child" ] || [ -L "$child" ] || continue
    |        case "$child" in "$f/candidate"|"$f/previous") ;; *) result FOREIGN ;; esac
    |        [ ! -L "$child" ] && [ -f "$child" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%h' "$child" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:1' ] || result FOREIGN
    |        mode=$(stat -c '%a' "$child" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE
    |        case "$mode" in 600|644) ;; *) result FOREIGN ;; esac
    |      done
    |      staging=1; continue ;;
    |    "$d/compose.yml"|"$d/.env"|"$d/managed.json") ;; *) result FOREIGN ;;
    |  esac
    |  [ ! -L "$f" ] && [ -f "$f" ] || result FOREIGN; metadata=$(stat -c '%u:%g:%h' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE; [ "$metadata" = '0:0:1' ] || result FOREIGN
    |  mode=$(stat -c '%a' "$f" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_FILESYSTEM_METADATA_UNAVAILABLE
    |  case "$f:$mode" in "$d/.env:600"|"$d/compose.yml:644"|"$d/managed.json:644") ;; *) result FOREIGN ;; esac
    |done
    |markerOwned=0; managedOwned=0
    |if [ -f "$d/compose.yml" ]; then
    |  first=$(head -n1 "$d/compose.yml" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_COMPOSE_UNREADABLE
    |  [ "$first" = "$marker" ] && markerOwned=1 || true
    |  case "$first" in '# infradesk-managed'*) [ "$markerOwned" = 1 ] || result FOREIGN ;; esac
    |fi
    |if [ -f "$d/managed.json" ]; then
    |  metadata=$(cat "$d/managed.json") || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_COMPOSE_UNREADABLE
    |  case "$metadata" in "$managedPrefix"*) tail=${metadata#"$managedPrefix"}; hash=${tail%??}; suffix=${tail#"$hash"}; [ "${#hash}" = 64 ] && [ "$suffix" = '"}' ] && case "$hash" in *[!a-f0-9]*) ;; *) managedOwned=1 ;; esac ;; esac
    |  case "$metadata" in '{"managedBy":"infradesk",'*) case "$metadata" in "$managedPrefix"*) ;; *) result FOREIGN ;; esac ;; esac
    |fi
    |[ "$claimOwned" = 1 ] || [ "$markerOwned" = 1 ] || [ "$managedOwned" = 1 ] || result FOREIGN
    |composeOwned=0; controlledImage=$image
    |if [ "$markerOwned" = 1 ]; then
    |  controlled_compose() {
    |CONTROLLED_COMPOSE_PROOF
    |  }
    |  controlled_compose && composeOwned=1 || true
    |  [ "$(grep -c '^    image: ' "$d/compose.yml")" = 1 ] || result FOREIGN
    |  configuredImage=$(sed -n 's/^    image: //p' "$d/compose.yml")
    |  [ "$configuredImage" = "$image" ] || [ "$composeOwned" = 1 ] || result FOREIGN
    |fi
    |[ "$composeOwned" = 1 ] || controlledImage=$image
    |containerRunning=0
    |if [ "$ownContainer" = 1 ]; then
    |  info=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project.config_files" }}|{{.Config.Image}}|{{.State.Running}}' "$name" 2>/dev/null) || result UNKNOWN REMNAWAVE_LOCAL_INSTALLATION_CONTAINER_STATE_UNKNOWN
    |  case "$info" in "$d/compose.yml|$controlledImage|true") containerRunning=1 ;; "$d/compose.yml|$controlledImage|false") ;; *) result FOREIGN ;; esac
    |fi
    |if [ -n "$listeners" ]; then [ "$containerRunning" = 1 ] || result PORT_CONFLICT; fi
    |if [ "$stageOwned" = 0 ] && [ "$staging" = 0 ] && [ "$markerOwned" = 1 ] && [ "$managedOwned" = 1 ] && [ -f "$d/.env" ] &&
    |  [ "$composeOwned" = 1 ] &&
    |  [ "$(sha256sum "$d/.env" | cut -d' ' -f1)" = "$hash" ] &&
    |  [ "$(grep -Ec '^SECRET_KEY=[A-Za-z0-9+/]+={0,2}$' "$d/.env")" = 1 ] &&
    |  [ "$(grep -Fxc "NODE_PORT=$port" "$d/.env")" = 1 ] && [ "$(wc -l < "$d/.env")" = 2 ]; then result OWNED_COMPLETE; fi
    |if [ "$claimOwned" = 1 ] && [ "$stageOwned" = 0 ] && [ "$staging" = 0 ] && [ -f "$d/.env" ] && [ -f "$d/compose.yml" ] && [ -f "$d/managed.json" ] && [ "$markerOwned" = 1 ] && [ "$managedOwned" = 1 ]; then result OWNED_DAMAGED; fi
    |result OWNED_PARTIAL
    |""".stripMargin.replace("CONTROLLED_COMPOSE_PROOF", controlledComposeProof("return 1"))
  private val PrepareInstallation = "recovery_probe() {\n" + RecoveryProbe + "\n}\n" + """set -u
    |d=$1; marker=$2; managedPrefix=$3; name=$4; image=$5; port=$6; claim=$7; composeHash=$8; sshUid=${9:-0}
    |stageDir=${claim%/.owner-*}/.staging-${claim##*/.owner-}
    |result() { printf '%s' "$1"; exit 0; }
    |state=$(recovery_probe "$@")
    |case "$state" in ABSENT|OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN|PORT_CONFLICT) result "$state" ;; *) result UNKNOWN ;; esac
    |umask 077
    |for p in /opt/infradesk /opt/infradesk/remnawave; do
    |  if [ ! -e "$p" ] && [ ! -L "$p" ]; then mkdir -m 0755 -- "$p" || result UNKNOWN; fi
    |  [ ! -L "$p" ] && [ -d "$p" ] && [ "$(stat -c '%u' "$p")" = 0 ] || result FOREIGN
    |  mode=$(stat -c '%a' "$p") || result UNKNOWN; [ $((0$mode & 07022)) -eq 0 ] || result FOREIGN
    |done
    |# Identity is entirely in this backend-selected directory name: mkdir is an atomic ownership publication.
    |if [ ! -e "$claim" ] && [ ! -L "$claim" ]; then
    |  [ "$(recovery_probe "$@")" = "$state" ] || result FOREIGN
    |  mkdir -m 0700 -- "$claim" || result UNKNOWN
    |fi
    |[ ! -L "$claim" ] && [ -d "$claim" ] && [ "$(stat -c '%u:%g:%a' "$claim")" = '0:0:700' ] || result FOREIGN
    |sync -f "$claim" || result UNKNOWN
    |# The initial mkdir mode must already be final even if the process dies before chmod.
    |umask 022
    |if [ ! -e "$d" ] && [ ! -L "$d" ]; then
    |  if [ ! -e "$stageDir" ] && [ ! -L "$stageDir" ]; then mkdir -m 0755 -- "$stageDir" || result UNKNOWN; fi
    |  [ ! -L "$stageDir" ] && [ -d "$stageDir" ] && [ "$(stat -c '%u:%g:%a' "$stageDir")" = '0:0:755' ] || result FOREIGN
    |fi
    |case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) result PREPARED ;; FOREIGN|PORT_CONFLICT) result FOREIGN ;; *) result UNKNOWN ;; esac
    |""".stripMargin
  private val CleanupInstallationStaging = "recovery_probe() {\n" + RecoveryProbe + "\n}\n" + """set -u
    |d=$1; marker=$2; managedPrefix=$3; name=$4; image=$5; port=$6; claim=$7; composeHash=$8; sshUid=${9:-0}
    |stageDir=${claim%/.owner-*}/.staging-${claim##*/.owner-}
    |result() { printf '%s' "$1"; exit 0; }
    |case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |owner=${marker#"# infradesk-managed run="}; owner=${owner%% *}
    |for stage in "$d/.infradesk-$owner-"*; do
    |  [ -e "$stage" ] || [ -L "$stage" ] || continue
    |  case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |  for file in "$stage/candidate" "$stage/previous"; do
    |    [ -e "$file" ] || [ -L "$file" ] || continue
    |    hash=$(sha256sum "$file" | cut -d' ' -f1) || result UNKNOWN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |    [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$hash" ] || result UNKNOWN
    |    rm -- "$file" || result UNKNOWN
    |  done
    |  rmdir -- "$stage" || result UNKNOWN
    |done
    |for stage in "$stageDir/.infradesk-$owner-"*; do
    |  [ -e "$stage" ] || [ -L "$stage" ] || continue
    |  case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |  for file in "$stage/candidate" "$stage/previous"; do
    |    [ -e "$file" ] || [ -L "$file" ] || continue
    |    hash=$(sha256sum "$file" | cut -d' ' -f1) || result UNKNOWN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |    [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$hash" ] || result UNKNOWN
    |    rm -- "$file" || result UNKNOWN
    |  done
    |  rmdir -- "$stage" || result UNKNOWN
    |done
    |for upload in /tmp/infradesk-"$owner"-*; do
    |  [ -e "$upload" ] || [ -L "$upload" ] || continue
    |  case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |  if [ -e "$upload/input" ]; then
    |    hash=$(sha256sum "$upload/input" | cut -d' ' -f1) || result UNKNOWN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |    [ "$(sha256sum "$upload/input" | cut -d' ' -f1)" = "$hash" ] || result UNKNOWN
    |    rm -- "$upload/input" || result UNKNOWN
    |  fi
    |  rmdir -- "$upload" || result UNKNOWN
    |done
    |result CLEAN
    |""".stripMargin
  private val PublishInstallation = "recovery_probe() {\n" + RecoveryProbe + "\n}\n" + """set -u
    |d=$1; marker=$2; managedPrefix=$3; name=$4; image=$5; port=$6; claim=$7; composeHash=$8; sshUid=${9:-0}
    |stageDir=${claim%/.owner-*}/.staging-${claim##*/.owner-}
    |result() { printf '%s' "$1"; exit 0; }
    |[ "$(recovery_probe "$@")" = OWNED_PARTIAL ] || result FOREIGN
    |[ ! -L "$stageDir" ] && [ -d "$stageDir" ] && [ "$(stat -c '%u:%g:%a' "$stageDir")" = '0:0:755' ] || result FOREIGN
    |entryCount=0
    |for entry in "$stageDir"/* "$stageDir"/.[!.]* "$stageDir"/..?*; do
    |  [ -e "$entry" ] || [ -L "$entry" ] || continue
    |  case "$entry" in "$stageDir/.env"|"$stageDir/compose.yml"|"$stageDir/managed.json") entryCount=$((entryCount + 1)) ;; *) result FOREIGN ;; esac
    |done
    |[ "$entryCount" = 3 ] || result FOREIGN
    |for f in "$stageDir/.env" "$stageDir/compose.yml" "$stageDir/managed.json"; do [ ! -L "$f" ] && [ -f "$f" ] && [ "$(stat -c '%u:%g:%h' "$f")" = '0:0:1' ] || result FOREIGN; done
    |[ "$(stat -c '%a' "$stageDir/.env")" = 600 ] && [ "$(stat -c '%a' "$stageDir/compose.yml")" = 644 ] && [ "$(stat -c '%a' "$stageDir/managed.json")" = 644 ] || result FOREIGN
    |[ "$(sha256sum "$stageDir/compose.yml" | cut -d' ' -f1)" = "$composeHash" ] || result FOREIGN
    |envHash=$(sha256sum "$stageDir/.env" | cut -d' ' -f1) || result UNKNOWN
    |[ "$(cat "$stageDir/managed.json")" = "$managedPrefix$envHash\"}" ] || result FOREIGN
    |[ "$(grep -Ec '^SECRET_KEY=[A-Za-z0-9+/]+={0,2}$' "$stageDir/.env")" = 1 ] && [ "$(grep -Fxc "NODE_PORT=$port" "$stageDir/.env")" = 1 ] && [ "$(wc -l < "$stageDir/.env")" = 2 ] || result FOREIGN
    |docker compose -f "$stageDir/compose.yml" --env-file "$stageDir/.env" config -q >/dev/null 2>&1 || result FOREIGN
    |case "$(recovery_probe "$@")" in OWNED_PARTIAL) ;; *) result UNKNOWN ;; esac
    |sync -f "$stageDir" || result UNKNOWN
    |[ ! -e "$d" ] && [ ! -L "$d" ] || result FOREIGN
    |stageDev=$(stat -c '%d' "$stageDir") || result UNKNOWN
    |parentDev=$(stat -c '%d' "$(dirname "$d")") || result UNKNOWN
    |[ "$stageDev" = "$parentDev" ] || result UNKNOWN
    |stageInode=$(stat -c '%i' "$stageDir") || result UNKNOWN
    |mv -nT -- "$stageDir" "$d" || result UNKNOWN
    |[ ! -e "$stageDir" ] && [ ! -L "$stageDir" ] && [ -d "$d" ] && [ "$(stat -c '%i' "$d")" = "$stageInode" ] || result UNKNOWN
    |sync -f "$(dirname "$d")" || result UNKNOWN
    |case "$(recovery_probe "$@")" in OWNED_COMPLETE) result PUBLISHED ;; *) result UNKNOWN ;; esac
    |""".stripMargin
  private[ssh] val ManagedFileProof = """managed_files() {
    |  d=$1; marker=$2; port=$3; composeHash=$4; managedPrefix=$5
    |  for p in /opt /opt/infradesk /opt/infradesk/remnawave; do
    |    [ ! -L "$p" ] || return 1
    |    if [ -e "$p" ]; then [ -d "$p" ] && [ "$(stat -c '%u' "$p" 2>/dev/null)" = 0 ] || return 1; mode=$(stat -c '%a' "$p" 2>/dev/null) || return 1; [ $((0$mode & 022)) -eq 0 ] || return 1; fi
    |  done
    |  [ ! -L "$d" ] && [ -d "$d" ] && [ "$(stat -c '%u:%a' "$d" 2>/dev/null)" = '0:755' ] &&
    |  [ ! -L "$d/compose.yml" ] && [ ! -L "$d/.env" ] && [ ! -L "$d/managed.json" ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/compose.yml" 2>/dev/null)" = '0:644:1' ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/.env" 2>/dev/null)" = '0:600:1' ] &&
    |  [ "$(stat -c '%u:%a:%h' "$d/managed.json" 2>/dev/null)" = '0:644:1' ] &&
    |  [ "$(head -n1 "$d/compose.yml")" = "$marker" ] &&
    |  [ "$(sha256sum "$d/compose.yml" | cut -d' ' -f1)" = "$composeHash" ] || return 1
    |  envHash=$(sha256sum "$d/.env" | cut -d' ' -f1) || return 1
    |  [ "$(cat "$d/managed.json")" = "$managedPrefix$envHash\"}" ] || return 1
    |  [ "$(grep -Ec '^SECRET_KEY=[A-Za-z0-9+/]+={0,2}$' "$d/.env")" = 1 ] &&
    |  [ "$(grep -Fxc "NODE_PORT=$port" "$d/.env")" = 1 ] && [ "$(wc -l < "$d/.env")" = 2 ]
    |}
    |""".stripMargin
  private val RetireInstallation = "recovery_probe() {\n" + RecoveryProbe + "\n}\n" + """set -u
    |d=$1; marker=$2; managedPrefix=$3; name=$4; image=$5; port=$6; claim=$7; composeHash=$8; sshUid=${9:-0}
    |stageDir=${claim%/.owner-*}/.staging-${claim##*/.owner-}
    |result() { printf '%s' "$1"; exit 0; }
    |state=$(recovery_probe "$@")
    |case "$state" in ABSENT) result ABSENT ;; OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |# Legacy exact ownership is upgraded before any destructive boundary as well.
    |if [ ! -e "$claim" ] && [ ! -L "$claim" ]; then
    |  umask 077; mkdir -m 0700 -- "$claim" || result UNKNOWN
    |fi
    |sync -f "$claim" || result UNKNOWN
    |case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; FOREIGN) result FOREIGN ;; *) result UNKNOWN ;; esac
    |allContainers=$(docker ps -a --format '{{.Names}}') || result UNKNOWN
    |ownContainer=0; printf '%s\n' "$allContainers" | grep -Fxq "$name" && ownContainer=1 || true
    |if [ "$ownContainer" = 1 ]; then
    |  info=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project.config_files" }}|{{.Config.Image}}|{{.State.Running}}' "$name" 2>/dev/null) || result UNKNOWN
    |  retiredImage=$image
    |  if [ -f "$d/compose.yml" ]; then retiredImage=$(sed -n 's/^    image: //p' "$d/compose.yml"); fi
    |  case "$info" in "$d/compose.yml|$retiredImage|true"|"$d/compose.yml|$retiredImage|false") ;; *) result FOREIGN ;; esac
    |  state=${info##*|}
    |  if [ "$state" = true ]; then docker stop --time 15 "$name" >/dev/null 2>&1 || result UNCERTAIN; fi
    |  docker rm "$name" >/dev/null 2>&1 || result UNCERTAIN
    |  allContainers=$(docker ps -a --format '{{.Names}}') || result UNCERTAIN
    |  printf '%s\n' "$allContainers" | grep -Fxq "$name" && result UNCERTAIN || true
    |fi
    |case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) result UNCERTAIN ;; esac
    |owner=${marker#"# infradesk-managed run="}; owner=${owner%% *}
    |remove_helpers() {
    |  parent=$1
    |  for helper in "$parent/.infradesk-$owner-"*; do
    |    [ -e "$helper" ] || [ -L "$helper" ] || continue
    |    case "$(recovery_probe "$d" "$marker" "$managedPrefix" "$name" "$image" "$port" "$claim" "$composeHash" "$sshUid")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) return 1 ;; esac
    |    for file in "$helper/candidate" "$helper/previous"; do
    |      [ -e "$file" ] || [ -L "$file" ] || continue
    |      hash=$(sha256sum "$file" | cut -d' ' -f1) || return 1
    |      case "$(recovery_probe "$d" "$marker" "$managedPrefix" "$name" "$image" "$port" "$claim" "$composeHash" "$sshUid")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) return 1 ;; esac
    |      [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$hash" ] || return 1
    |      rm -- "$file" || return 1
    |    done
    |    rmdir -- "$helper" || return 1
    |  done
    |}
    |[ ! -d "$stageDir" ] || { remove_helpers "$stageDir" || result UNCERTAIN; }
    |[ ! -d "$d" ] || { remove_helpers "$d" || result UNCERTAIN; }
    |if [ -d "$stageDir" ]; then
    |  for file in "$stageDir/.env" "$stageDir/compose.yml" "$stageDir/managed.json"; do
    |    [ -e "$file" ] || [ -L "$file" ] || continue
    |    hash=$(sha256sum "$file" | cut -d' ' -f1) || result UNCERTAIN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) result UNCERTAIN ;; esac
    |    [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$hash" ] || result UNCERTAIN
    |    rm -- "$file" || result UNCERTAIN
    |  done
    |  rmdir -- "$stageDir" || result UNCERTAIN
    |fi
    |for upload in /tmp/infradesk-"$owner"-*; do
    |  [ -e "$upload" ] || [ -L "$upload" ] || continue
    |  case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) result UNCERTAIN ;; esac
    |  if [ -e "$upload/input" ]; then
    |    hash=$(sha256sum "$upload/input" | cut -d' ' -f1) || result UNCERTAIN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) result UNCERTAIN ;; esac
    |    [ "$(sha256sum "$upload/input" | cut -d' ' -f1)" = "$hash" ] || result UNCERTAIN
    |    rm -- "$upload/input" || result UNCERTAIN
    |  fi
    |  rmdir -- "$upload" || result UNCERTAIN
    |done
    |if [ -d "$d" ]; then
    |  for file in "$d/compose.yml" "$d/.env" "$d/managed.json"; do
    |    [ -e "$file" ] || [ -L "$file" ] || continue
    |    hash=$(sha256sum "$file" | cut -d' ' -f1) || result UNCERTAIN
    |    case "$(recovery_probe "$@")" in OWNED_COMPLETE|OWNED_PARTIAL|OWNED_DAMAGED) ;; *) result UNCERTAIN ;; esac
    |    [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$hash" ] || result UNCERTAIN
    |    rm -- "$file" || result UNCERTAIN
    |  done
    |  rmdir -- "$d" || result UNCERTAIN
    |fi
    |case "$(recovery_probe "$@")" in OWNED_PARTIAL) ;; *) result UNCERTAIN ;; esac
    |sync -f "$(dirname "$claim")" && sync -f /tmp || result UNCERTAIN
    |rmdir -- "$claim" || result UNCERTAIN
    |result RETIRED
    |""".stripMargin
  private lazy val InstallationProof = "set -u; " + SshManagedNodeImages.ManagedImageProof + """
    |files=0; image=0
    |managed_files "$1" "$2" "$4" "$5" "$6" && files=1 || true
    |if [ "$files" = 1 ]; then imageReference=$(sed -n 's/^    image: //p' "$1/compose.yml"); imageId=$(docker image inspect -f '{{.Id}}' "$imageReference" 2>/dev/null) && [ -n "$imageId" ] && image=1 || true; fi
    |printf '%s:%s' "$files" "$image"
    |""".stripMargin
  private lazy val Observe = "set -u; " + SshManagedNodeImages.ManagedImageProof + """
    |d=$1; marker=$2; image=$3; name=$4; port=$5; composeHash=$6; managedPrefix=$7
    |files=0; images=0; running=0; listener=0; stable=0
    |managed_files "$d" "$marker" "$port" "$composeHash" "$managedPrefix" && files=1 || true
    |[ "$files" = 1 ] && image=$(sed -n 's/^    image: //p' "$d/compose.yml") || true
    |imageId=$(docker image inspect -f '{{.Id}}' "$image" 2>/dev/null) && [ -n "$imageId" ] && images=1 || true
    |configImage=$(docker inspect -f '{{.Config.Image}}' "$name" 2>/dev/null || true)
    |containerImageId=$(docker inspect -f '{{.Image}}' "$name" 2>/dev/null || true)
    |[ "$containerImageId" = "$imageId" ] && [ -n "$imageId" ] || images=0
    |containerImageId=$(docker inspect -f '{{.Image}}' "$name" 2>/dev/null || true)
    |network=$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$name" 2>/dev/null || true)
    |state=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)
    |[ "$configImage" = "$image" ] && [ "$containerImageId" = "$imageId" ] && [ "$network" = host ] && [ "$state" = true ] && running=1 || true
    |listenerOutput=$(ss -H -ltn "sport = :$port" 2>/dev/null); listenerStatus=$?
    |[ "$listenerStatus" = 0 ] && [ -n "$listenerOutput" ] && listener=1 || true
    |restarts=$(docker inspect -f '{{.RestartCount}}' "$name" 2>/dev/null || echo x)
    |started=$(docker inspect -f '{{.State.StartedAt}}' "$name" 2>/dev/null || true)
    |startedSeconds=$(date -d "$started" +%s 2>/dev/null || echo 0); nowSeconds=$(date +%s)
    |[ "$restarts" = 0 ] && [ "$startedSeconds" -gt 0 ] && [ $((nowSeconds-startedSeconds)) -ge 10 ] && stable=1 || true
    |printf '%s:%s:%s:%s:%s' "$files" "$images" "$running" "$listener" "$stable"
    |""".stripMargin
  private lazy val Start = "set -u; " + SshManagedNodeImages.ManagedImageProof + """
    |d=$1; marker=$2; image=$3; port=$4; composeHash=$5; managedPrefix=$6; name=$7
    |managed_files "$d" "$marker" "$port" "$composeHash" "$managedPrefix" || { printf FILES; exit 0; }
    |docker compose -f "$d/compose.yml" --env-file "$d/.env" config -q >/dev/null 2>&1 || { printf CONFIG; exit 0; }
    |docker compose -f "$d/compose.yml" --env-file "$d/.env" up -d >/dev/null 2>&1 || { printf START_FAILED; exit 0; }
    |printf STARTED
    |""".stripMargin

  private[ssh] def parseNodeRules(raw: String, resource: UUID, node: UUID): Either[String, List[ProfileFirewallRule]] = {
    ProfileFirewall.parseOwned(raw, s"infradesk:remnawave:$resource:$node:")
  }
}
