package ru.bitec.app.ops
package domain.integration

import java.time.Instant
import java.util.UUID
import munit.FunSuite

final class RemnawaveOnboardingSpec extends FunSuite {
  private def id = UUID.randomUUID()
  private val inbound = UUID.randomUUID()
  private val api = NodeApiCompatibility(Some("2.8.0"), Some("PROFILE_PUBKEY"), Some("commit"),
    Set(NodeProvisioningCapability.Create, NodeProvisioningCapability.InstallationData,
      NodeProvisioningCapability.Status, NodeProvisioningCapability.ConfigProfile), None)
  private val input = OnboardingInput(id, "edge-01", "192.0.2.10", 30222, id, List(inbound),
    List("198.51.100.0/24", "192.0.2.0/24"))
  private val snapshot = OnboardingSnapshot(input, Instant.parse("2026-01-02T03:04:05Z"), id, id,
    Instant.parse("2026-01-03T03:04:05Z"), id, id, 7, id, 4L, "revision-hash", id, false, api,
    "remnawave/node:2.8.0", id, "prod", "standard", "secure", List("primary"), List("change"), Nil, Nil)

  test("canonicalizes strict CIDRs and rejects broad, duplicate, hostname, and malformed sources") {
    assertEquals(OnboardingInput.canonicalCidrs(List("198.51.100.0/24", "192.0.2.0/24")),
      Right(List("192.0.2.0/24", "198.51.100.0/24")))
    List(List.empty[String], List("0.0.0.0/0"), List("::/0"), List("ANY"),
      List("192.0.2.0/24", "192.0.2.0/24"), List("example.com/24"), List("192.0.2.1")).foreach { values =>
      assert(OnboardingInput.canonicalCidrs(values).isLeft, values.toString)
    }
  }

  test("input rejects duplicate inbound IDs and invalid ports") {
    assert(input.copy(activeInboundIds = List(inbound, inbound)).normalized.isLeft)
    assert(input.copy(nodePort = 0).normalized.isLeft)
  }

  test("snapshot JSON round trips pinned state and contains no installation credential") {
    val json = OnboardingSnapshotCodec.encode(snapshot)
    assertEquals(OnboardingSnapshotCodec.decode(json), snapshot.copy(input = input.copy(panelCidrs = input.panelCidrs.sorted)))
    val serialized = json.noSpaces
    assert(!serialized.contains("secretKey"))
    assert(!serialized.contains("privateKey"))
    assert(!serialized.contains("installationData"))
    intercept[IllegalArgumentException](OnboardingSnapshotCodec.decode(json.mapObject(_.add("secretKey", io.circe.Json.fromString("must-not-be-accepted")))))
  }

  test("image selection uses the reviewed compatible catalog digest and rejects custom Panel metadata") {
    val reviewed = api.copy(sourceCommit = Some("798d74986db5984364897464306928973bce3b67"))
    assertEquals(RemnawaveNodeImagePolicy.select(reviewed), RemnawaveNodeReleaseCatalog.find("node-2.8.0").map(_.imageReference))
    val newer = api.copy(serverVersion = Some("3.4.0"), apiGeneration = Some("PROFILE_SECRET_KEY"),
      sourceCommit = Some("dd0eab160fd91a1c09345df459e146712581736f"))
    assertEquals(RemnawaveNodeImagePolicy.select(newer), RemnawaveNodeReleaseCatalog.find("node-3.4.1").map(_.imageReference))
    assert(NodeRelease.immutableReference(RemnawaveNodeImagePolicy.select(reviewed).get))
    assertEquals(RemnawaveNodeImagePolicy.select(api), None)
    assertEquals(RemnawaveNodeImagePolicy.select(reviewed.copy(blocker = Some("unknown"))), None)
  }
}
