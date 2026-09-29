package ru.bitec.app.ops
package infrastructure.http

import application.port._
import domain.integration._
import io.circe.Json
import io.circe.syntax._
import serialization.integration.IntegrationSummaryJson

/** Published shapes of the observed inventory. Everything here comes from sanitized, typed values:
  * there is no path from a provider's raw response to an API response.
  */
object IntegrationInventoryJson {
  def session(value: IntegrationSyncSession): Json = Json.obj(
    "id" -> value.id.asJson,
    "integrationId" -> value.integrationId.asJson,
    "trigger" -> value.trigger.code.asJson,
    "status" -> value.status.code.asJson,
    "startedAt" -> value.startedAt.asJson,
    "finishedAt" -> value.finishedAt.asJson,
    "errorCode" -> value.errorCode.asJson,
    "errorMessage" -> value.errorMessage.asJson,
    "counts" -> value.counts.fold(Json.Null)(counts => Json.obj(
      "nodes" -> counts.nodes.asJson,
      "hosts" -> counts.hosts.asJson,
      "configProfiles" -> counts.configProfiles.asJson,
      "deactivated" -> counts.deactivated.asJson)))

  def overview(value: IntegrationOverview): Json = Json.obj(
    "lastSync" -> value.lastSync.fold(Json.Null)(session),
    "lastSuccessfulSyncAt" -> value.lastSuccessfulSyncAt.asJson,
    "nextRunAt" -> value.nextRunAt.asJson,
    "inventory" -> summary(value.inventory))

  def summary(value: IntegrationInventorySummary): Json = {
    def counts(c: InventoryTypeCounts) = Json.obj("active" -> c.active.asJson, "inactive" -> c.inactive.asJson)
    Json.obj("nodes" -> counts(value.nodes), "hosts" -> counts(value.hosts),
      "configProfiles" -> counts(value.configProfiles))
  }

  def inventoryObject(value: IntegrationInventoryObject): Json = Json.obj(
    "id" -> value.id.asJson,
    "objectType" -> value.objectType.code.asJson,
    "externalId" -> value.externalId.asJson,
    "displayName" -> value.displayName.asJson,
    "active" -> value.isActive.asJson,
    "firstSeenAt" -> value.firstSeenAt.asJson,
    "lastSeenAt" -> value.lastSeenAt.asJson,
    "summary" -> IntegrationSummaryJson.encode(value.summary))

  def item(value: InventoryItem): Json = inventoryObject(value.obj).deepMerge(Json.obj(
    "binding" -> value.binding.fold(Json.Null)(bound => Json.obj(
      "resource" -> Json.obj("id" -> bound.id.asJson, "code" -> bound.code.asJson, "name" -> bound.name.asJson),
      "environment" -> Json.obj("id" -> bound.environmentId.asJson, "name" -> bound.environmentName.asJson),
      "project" -> Json.obj("id" -> bound.projectId.asJson, "name" -> bound.projectName.asJson)))))

  def page(value: InventoryPage[InventoryItem], limit: Int, offset: Int): Json = Json.obj(
    "items" -> value.items.map(item).asJson,
    "total" -> value.total.asJson,
    "limit" -> limit.asJson,
    "offset" -> offset.asJson)

  def binding(value: IntegrationResourceBinding): Json = Json.obj(
    "id" -> value.id.asJson,
    "inventoryObjectId" -> value.inventoryObjectId.asJson,
    "resourceId" -> value.resourceId.asJson,
    "createdAt" -> value.createdAt.asJson,
    "updatedAt" -> value.updatedAt.asJson)

  def candidate(value: BindingCandidate): Json = Json.obj(
    "id" -> value.id.asJson,
    "code" -> value.code.asJson,
    "name" -> value.name.asJson,
    "environment" -> Json.obj("id" -> value.environmentId.asJson, "name" -> value.environmentName.asJson),
    "project" -> Json.obj("id" -> value.projectId.asJson, "name" -> value.projectName.asJson))

  def resourceContext(value: ResourceIntegrationContext): Json = Json.obj(
    "integration" -> Json.obj("id" -> value.integrationId.asJson, "name" -> value.integrationName.asJson,
      "providerType" -> value.providerType.code.asJson),
    "object" -> inventoryObject(value.obj))
}
