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

  test("inbound set order produces canonical equality and identical snapshot bytes") {
    val second = id
    val a = input.copy(activeInboundIds=List(inbound,second)).normalized.toOption.get
    val b = input.copy(activeInboundIds=List(second,inbound)).normalized.toOption.get
    assertEquals(a,b)
    assertEquals(OnboardingSnapshotCodec.encode(snapshot.copy(input=a)),OnboardingSnapshotCodec.encode(snapshot.copy(input=b)))
  }

  test("reviewed target evolution preserves original installation image across recovery and recreation") {
    val original = snapshot.copy(imageReference="remnawave/node:3.4.1")
    val at = original.integrationUpdatedAt
    val previous = RemnawaveNodeOnboardingRun(id,id,id,input.resourceId,Some(id),id,
      domain.provisioning.ProvisioningRunState.Succeeded,OnboardingPhase.FinalVerify,original,at,at,externalNodeId=Some(id))
    val target = RemnawaveNodeReleaseCatalog.find("node-3.4.0").get.imageReference
    assertEquals(RemnawaveNodeOnboardingRun.recoveryCandidate(List(previous),previous.integrationId,input,target,
      snapshot.connectionId),Right(Some(previous)))
    val proof = OnboardingRecovery(previous.id,previous.externalNodeId,original.correlationId,previous.id,
      "PRESENT_EXACT","RECOVER",Some(input.panelCidrs.sorted),Some(original.imageReference))
    val recovered = snapshot.copy(imageReference=target,recovery=Some(proof),lifecycleVersion=2)
    assertEquals(recovered.installationImageReference,original.imageReference)
    assertEquals(OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(recovered)).installationImageReference,original.imageReference)
    assertEquals(recovered.copy(recovery=Some(proof.copy(action="DELETE_RECREATE"))).installationImageReference,target)
    assert(RemnawaveNodeOnboardingRun.recoveryCandidate(List(previous),previous.integrationId,input,"unreviewed",
      snapshot.connectionId).isLeft)
  }

  test("one terminal identity chain can seed reconciliation, independent of its last failure phase") {
    val integration = id
    val at = snapshot.integrationUpdatedAt
    val previous = RemnawaveNodeOnboardingRun(id,id,integration,input.resourceId,Some(id),id,
      domain.provisioning.ProvisioningRunState.Failed,OnboardingPhase.ConfigureFirewall,snapshot,at,at,externalNodeId=Some(id))
    def recover(rows: List[RemnawaveNodeOnboardingRun], candidate: OnboardingInput = input) =
      RemnawaveNodeOnboardingRun.recoveryCandidate(rows,integration,candidate,snapshot.imageReference,snapshot.connectionId)
    assertEquals(recover(Nil),Right(None))
    assertEquals(recover(List(previous)),Right(Some(previous)))
    assert(recover(List(previous),input.copy(nodePort=2222)).isLeft)
    assertEquals(recover(List(previous),input.copy(panelCidrs=List("2.27.26.18/32"))),Right(Some(previous)))
    List(previous.copy(state=domain.provisioning.ProvisioningRunState.Unknown),previous.copy(phase=OnboardingPhase.InstallNode),
      previous.copy(externalNodeId=None)).foreach(r => assertEquals(recover(List(r)),Right(Some(r))))
    List(previous.copy(integrationId=id),previous.copy(state=domain.provisioning.ProvisioningRunState.Running),
      previous.copy(snapshot=snapshot.copy(connectionId=id)),previous.copy(snapshot=snapshot.copy(imageReference="different"))).foreach(r => assert(recover(List(r)).isLeft))
    assert(recover(List(previous,previous.copy(externalNodeId=Some(id)))).isLeft)
  }

  test("snapshot JSON round trips pinned state and contains no installation credential") {
    val json = OnboardingSnapshotCodec.encode(snapshot)
    assertEquals(OnboardingSnapshotCodec.decode(json), snapshot.copy(input = input.copy(panelCidrs = input.panelCidrs.sorted)))
    assertEquals(OnboardingSnapshotCodec.decode(json.mapObject(_.remove("recovery"))),
      snapshot.copy(input = input.copy(panelCidrs = input.panelCidrs.sorted)))
    val proof=OnboardingRecovery(id,Some(id),snapshot.correlationId,id,"PRESENT_UNHEALTHY","RECOVER")
    assertEquals(OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(snapshot.copy(recovery=Some(proof)))),
      snapshot.copy(input=input.copy(panelCidrs=input.panelCidrs.sorted),recovery=Some(proof)))
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
  test("versioned recreate phases retire firewall before local files and preserve legacy snapshot sequences") {
    List("RECREATE","DELETE_RECREATE").foreach { action =>
      val proof = OnboardingRecovery(id,Some(id),id,id,"CONFIRMED_NOT_FOUND",action)
      val legacy = snapshot.copy(recovery=Some(proof))
      val current = legacy.copy(lifecycleVersion=2)
      val oldPhases = OnboardingPhase.forSnapshot(legacy)
      val newPhases = OnboardingPhase.forSnapshot(current)
      assert(!oldPhases.contains(OnboardingPhase.RetireNodeFirewall))
      assertEquals(newPhases.filterNot(_ == OnboardingPhase.RetireNodeFirewall),oldPhases)
      assertEquals(newPhases.indexOf(OnboardingPhase.RetireNodeFirewall)+1,newPhases.indexOf(OnboardingPhase.RetireLocalNode))
      assert(!OnboardingSnapshotCodec.encode(legacy).hcursor.downField("lifecycleVersion").succeeded)
      assertEquals(OnboardingSnapshotCodec.decode(OnboardingSnapshotCodec.encode(current)).lifecycleVersion,2)
      List(io.circe.Json.Null,io.circe.Json.fromString("2"),io.circe.Json.fromInt(3)).foreach { invalid =>
        intercept[IllegalArgumentException](OnboardingSnapshotCodec.decode(
          OnboardingSnapshotCodec.encode(current).mapObject(_.add("lifecycleVersion",invalid))))
      }
    }
  }

}
