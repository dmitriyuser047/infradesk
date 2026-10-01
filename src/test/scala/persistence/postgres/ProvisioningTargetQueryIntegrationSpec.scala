package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class ProvisioningTargetQueryIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  test("eligible target requires one active trusted SSH source and deduplicates repeated refs") {
    run { w =>
      val targets = new PostgresProvisioningTargetQuery
      for {
        node <- w.node("provisioning-target")
        found <- w.run(targets.eligible(w.org, node.resourceId))
        noSource <- w.resource("provisioning-no-source")
        absent <- w.run(targets.eligible(w.org, noSource))
        _ <- w.run(sql"insert into external_ref(id, organization_id, connection_id, external_type, external_id, resource_id) values (${UUID.randomUUID()}, ${w.org}, ${node.connectionId}, 'NODE', ${UUID.randomUUID().toString}, ${node.resourceId})".update.run)
        duplicateRef <- w.run(targets.eligible(w.org, node.resourceId))
        secondConnection <- w.unrelatedConnection()
        _ <- w.run(sql"insert into external_ref(id, organization_id, connection_id, external_type, external_id, resource_id) values (${UUID.randomUUID()}, ${w.org}, $secondConnection, 'NODE', ${UUID.randomUUID().toString}, ${node.resourceId})".update.run)
        ambiguous <- w.run(targets.eligible(w.org, node.resourceId))
        _ <- w.run(sql"delete from external_ref where resource_id=${node.resourceId} and connection_id=$secondConnection".update.run)
        _ <- w.run(sql"update connection set config = config - 'hostKeyFingerprint' where id=${node.connectionId}".update.run)
        untrusted <- w.run(targets.eligible(w.org, node.resourceId))
        _ <- w.run(sql"update resource set is_active=false where id=${node.resourceId}".update.run)
        inactive <- w.run(targets.eligible(w.org, node.resourceId))
        foreign <- w.run(targets.eligible(w.foreignOrg, node.resourceId))
      } yield {
        assert(found.isRight)
        assertEquals(absent.left.toOption, Some("PROVISIONING_TARGET_NOT_FOUND"))
        assert(duplicateRef.isRight, "multiple references to the same SSH connection remain one eligible source")
        assertEquals(ambiguous.left.toOption, Some("PROVISIONING_TARGET_AMBIGUOUS"))
        assertEquals(untrusted.left.toOption, Some("PROVISIONING_TARGET_NOT_FOUND"))
        assertEquals(inactive.left.toOption, Some("PROVISIONING_TARGET_NOT_FOUND"))
        assertEquals(foreign.left.toOption, Some("PROVISIONING_TARGET_NOT_FOUND"))
      }
    }
  }
}
