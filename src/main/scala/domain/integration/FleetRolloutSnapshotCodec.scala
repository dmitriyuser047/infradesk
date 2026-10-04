package ru.bitec.app.ops
package domain.integration

import cats.syntax.all._
import io.circe.Json
import java.time.Instant
import java.util.UUID

/** Closed, versioned representation of the preview snapshot. Decoding rejects unknown keys. */
object FleetRolloutSnapshotCodec {
  val SchemaVersion = 1
  private val Invalid = "REMNAWAVE_FLEET_ROLLOUT_SNAPSHOT_INVALID"
  private def str(value: String) = Json.fromString(value)
  private def id(value: UUID) = str(value.toString)
  private def opt[A](value: Option[A])(f: A => Json) = value.fold(Json.Null)(f)

  private def baseline(value: FleetMemberBaseline) = Json.obj(
    "assignmentProfileId" -> opt(value.assignmentProfileId)(id),
    "assignmentRevisionNumber" -> opt(value.assignmentRevisionNumber)(Json.fromInt),
    "desiredState" -> opt(value.desiredState)(state => str(state.code)),
    "compliance" -> str(value.compliance), "health" -> str(value.health),
    "observedDisabled" -> Json.fromBoolean(value.observedDisabled),
    "sourceConnectionId" -> opt(value.sourceConnectionId)(id),
    "sourceUpdatedAt" -> opt(value.sourceUpdatedAt)(v => str(v.toString)))

  private def member(value: FleetRolloutMemberPlan) = Json.obj(
    "membershipId" -> id(value.membershipId), "membershipVersion" -> Json.fromLong(value.membershipVersion),
    "inventoryNodeId" -> id(value.inventoryNodeId), "resourceId" -> id(value.resourceId),
    "externalNodeId" -> str(value.externalNodeId), "nodeName" -> str(value.nodeName),
    "wave" -> Json.fromInt(value.wave), "position" -> Json.fromInt(value.position),
    "skipReason" -> opt(value.skipReason)(str),
    "actions" -> Json.arr(value.actions.map(kind => str(kind.code)): _*), "baseline" -> baseline(value.baseline))

  def encode(value: FleetRolloutSnapshot): Json = Json.obj(
    "schemaVersion" -> Json.fromInt(SchemaVersion), "fleetId" -> id(value.fleetId),
    "integrationId" -> id(value.integrationId), "integrationPin" -> str(value.integrationPin.toString),
    "revisionId" -> id(value.revisionId), "revisionNumber" -> Json.fromInt(value.revisionNumber),
    "revisionHash" -> str(value.revisionHash), "content" -> value.content.json,
    "policy" -> Json.obj("waveSize" -> Json.fromInt(value.policy.waveSize),
      "canaryMembershipIds" -> Json.arr(value.policy.canaryMembershipIds.map(id): _*),
      "pauseAfterCanary" -> Json.fromBoolean(value.policy.pauseAfterCanary),
      "automaticRollback" -> Json.fromBoolean(value.policy.automaticRollback),
      "rollbackScope" -> str(value.policy.rollbackScope.code)),
    "shared" -> Json.obj("required" -> Json.fromBoolean(value.shared.required),
      "inventoryConfigProfileId" -> id(value.shared.inventoryConfigProfileId),
      "revisionNumber" -> Json.fromInt(value.shared.revisionNumber),
      "revisionHash" -> str(value.shared.revisionHash),
      "baselineRevisionNumber" -> opt(value.shared.baselineRevisionNumber)(Json.fromInt),
      "baselineHash" -> opt(value.shared.baselineHash)(str),
      "externalNodes" -> Json.fromInt(value.shared.externalNodes),
      "externalUnhealthyNodes" -> Json.fromInt(value.shared.externalUnhealthyNodes),
      "consumers" -> Json.arr(value.shared.consumers.map(n => Json.obj("inventoryNodeId" -> id(n.inventoryNodeId),
        "externalNodeId" -> str(n.externalNodeId), "nodeName" -> str(n.nodeName), "disabled" -> Json.fromBoolean(n.disabled),
        "connected" -> Json.fromBoolean(n.connected), "observedAt" -> str(n.observedAt.toString))): _*)),
    "members" -> Json.arr(value.members.map(member): _*))

  private val topKeys = Set("schemaVersion", "fleetId", "integrationId", "integrationPin", "revisionId",
    "revisionNumber", "revisionHash", "content", "policy", "shared", "members")

  def decode(json: Json): Either[String, FleetRolloutSnapshot] = {
    def uuid(value: String) = Either.fromOption(
      scala.util.Try(UUID.fromString(value)).toOption.filter(_.toString == value), Invalid)
    def bad[A](result: io.circe.Decoder.Result[A]): Either[String, A] = result.left.map(_ => Invalid)
    def closed(obj: Json, keys: Set[String]) = Either.cond(obj.asObject.exists(_.keys.toSet == keys), (), Invalid)

    def decodeBaseline(value: Json): Either[String, FleetMemberBaseline] = {
      val c = value.hcursor
      for {
        _ <- closed(value, Set("assignmentProfileId", "assignmentRevisionNumber", "desiredState", "compliance", "health",
          "observedDisabled", "sourceConnectionId", "sourceUpdatedAt"))
        profile <- bad(c.get[Option[String]]("assignmentProfileId")).flatMap(_.traverse(uuid))
        number <- bad(c.get[Option[Int]]("assignmentRevisionNumber"))
        desired <- bad(c.get[Option[String]]("desiredState")).flatMap(_.traverse(code =>
          Either.fromOption(IntegrationDesiredNodeState.fromCode(code), Invalid)))
        compliance <- bad(c.get[String]("compliance"))
        health <- bad(c.get[String]("health"))
        disabled <- bad(c.get[Boolean]("observedDisabled"))
        source <- bad(c.get[Option[String]]("sourceConnectionId")).flatMap(_.traverse(uuid))
        sourceAt <- bad(c.get[Option[String]]("sourceUpdatedAt")).flatMap(_.traverse(value =>
          Either.fromOption(scala.util.Try(Instant.parse(value)).toOption, Invalid)))
      } yield FleetMemberBaseline(profile, number, desired, compliance, health, disabled, source, sourceAt)
    }

    def decodeMember(value: Json): Either[String, FleetRolloutMemberPlan] = {
      val c = value.hcursor
      for {
        _ <- closed(value, Set("membershipId", "membershipVersion", "inventoryNodeId", "resourceId",
          "externalNodeId", "nodeName", "wave", "position", "skipReason", "actions", "baseline"))
        membership <- bad(c.get[String]("membershipId")).flatMap(uuid)
        version <- bad(c.get[Long]("membershipVersion"))
        node <- bad(c.get[String]("inventoryNodeId")).flatMap(uuid)
        resource <- bad(c.get[String]("resourceId")).flatMap(uuid)
        external <- bad(c.get[String]("externalNodeId"))
        name <- bad(c.get[String]("nodeName"))
        wave <- bad(c.get[Int]("wave"))
        position <- bad(c.get[Int]("position"))
        skip <- bad(c.get[Option[String]]("skipReason"))
        rawActions <- bad(c.get[List[String]]("actions"))
        actions <- rawActions.traverse(code => Either.fromOption(FleetActionKind.fromCode(code), Invalid))
        base <- bad(c.get[Json]("baseline")).flatMap(decodeBaseline)
      } yield FleetRolloutMemberPlan(membership, version, node, resource, external, name, wave, position, skip,
        actions, base)
    }

    val c = json.hcursor
    for {
      _ <- closed(json, topKeys)
      schema <- bad(c.get[Int]("schemaVersion"))
      _ <- Either.cond(schema == SchemaVersion, (), "REMNAWAVE_FLEET_ROLLOUT_SCHEMA_UNSUPPORTED")
      fleet <- bad(c.get[String]("fleetId")).flatMap(uuid)
      integration <- bad(c.get[String]("integrationId")).flatMap(uuid)
      pinText <- bad(c.get[String]("integrationPin"))
      pin <- Either.fromOption(scala.util.Try(Instant.parse(pinText)).toOption, Invalid)
      revision <- bad(c.get[String]("revisionId")).flatMap(uuid)
      number <- bad(c.get[Int]("revisionNumber"))
      hash <- bad(c.get[String]("revisionHash"))
      content <- bad(c.get[Json]("content")).flatMap(FleetDesiredContentCodec.decode)
      policyJson <- bad(c.get[Json]("policy"))
      _ <- closed(policyJson, Set("waveSize", "canaryMembershipIds", "pauseAfterCanary", "automaticRollback",
        "rollbackScope"))
      p = policyJson.hcursor
      waveSize <- bad(p.get[Int]("waveSize"))
      rawCanary <- bad(p.get[List[String]]("canaryMembershipIds"))
      canary <- rawCanary.traverse(uuid)
      pause <- bad(p.get[Boolean]("pauseAfterCanary"))
      auto <- bad(p.get[Boolean]("automaticRollback"))
      scopeCode <- bad(p.get[String]("rollbackScope"))
      scope <- Either.fromOption(FleetRollbackScope.fromCode(scopeCode), Invalid)
      sharedJson <- bad(c.get[Json]("shared"))
      _ <- closed(sharedJson, Set("required", "inventoryConfigProfileId", "revisionNumber", "revisionHash",
        "baselineRevisionNumber", "baselineHash", "externalNodes", "externalUnhealthyNodes", "consumers"))
      s = sharedJson.hcursor
      required <- bad(s.get[Boolean]("required"))
      profile <- bad(s.get[String]("inventoryConfigProfileId")).flatMap(uuid)
      sharedNumber <- bad(s.get[Int]("revisionNumber"))
      sharedHash <- bad(s.get[String]("revisionHash"))
      baselineNumber <- bad(s.get[Option[Int]]("baselineRevisionNumber"))
      baselineHash <- bad(s.get[Option[String]]("baselineHash"))
      external <- bad(s.get[Int]("externalNodes"))
      unhealthy <- bad(s.get[Int]("externalUnhealthyNodes"))
      consumerJson <- bad(s.get[List[Json]]("consumers"))
      consumers <- consumerJson.traverse { value =>
        val n = value.hcursor
        for {
          _ <- closed(value, Set("inventoryNodeId", "externalNodeId", "nodeName", "disabled", "connected", "observedAt"))
          node <- bad(n.get[String]("inventoryNodeId")).flatMap(uuid)
          external <- bad(n.get[String]("externalNodeId"))
          name <- bad(n.get[String]("nodeName"))
          disabled <- bad(n.get[Boolean]("disabled"))
          connected <- bad(n.get[Boolean]("connected"))
          observedText <- bad(n.get[String]("observedAt"))
          observed <- Either.fromOption(scala.util.Try(Instant.parse(observedText)).toOption, Invalid)
        } yield FleetRolloutConfigConsumer(node, external, name, disabled, connected, observed)
      }
      rawMembers <- bad(c.get[List[Json]]("members"))
      members <- rawMembers.traverse(decodeMember)
    } yield FleetRolloutSnapshot(fleet, integration, pin, revision, number, hash, content,
      FleetRolloutPolicy(waveSize, canary, pause, auto, scope),
      FleetRolloutSharedConfig(required, profile, sharedNumber, sharedHash, baselineNumber, baselineHash,
        external, unhealthy, consumers), members)
  }
}
