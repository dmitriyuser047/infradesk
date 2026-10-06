package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.integration._
import domain.provisioning._
import infrastructure.database.{DatabaseMigrator,DoobieTransactionRunner}
import java.time.Instant
import java.util.UUID
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.noop.NoOpLogger
import support.{AuthorizationFixtures,RemoteConfigurationServer}

final class RemnawaveHardeningMigrationSpec extends FunSuite {
  test("V52 upgrades preserve terminal legacy snapshots, journal sequences and fleet identity") {
    assume(ConfigurationDeploymentWorld.enabled,"PostgreSQL integration tests are opt-in")
    val config=PostgresTestDatabase.config
    val remote=RemoteConfigurationServer.start()
    try PostgresTestDatabase.isolatedTransactor(config,Some("52")).use { xa =>
      val runner=new DoobieTransactionRunner(xa)
      val w=new ConfigurationDeploymentWorld(runner,remote)
      val repo=new PostgresRemnawaveOnboardingRepository
      val fleets=new PostgresRemnawaveFleetRepository
      val integration=UUID.randomUUID(); val secret=UUID.randomUUID(); val fleet=UUID.randomUUID()
      val now=Instant.now(); val actor=AuthorizationFixtures.ActorUserId
      for {
        _ <- w.setUp
        node <- w.node("v52-migration")
        sourceAt <- w.run(sql"select updated_at from connection where id=${node.connectionId}".query[Instant].unique)
        _ <- w.run(for {
          _ <- sql"""insert into integration_secret(id,organization_id,kind,nonce,ciphertext,created_at)
            values($secret,${w.org},'INTEGRATION_CREDENTIAL',${Array.fill[Byte](12)(1)},${Array.fill[Byte](32)(2)},$now)""".update.run
          _ <- sql"""insert into integration(id,organization_id,name,provider_type,base_url,enabled,secret_id,caddy_api_key_configured,created_at,updated_at)
            values($integration,${w.org},'V52 panel','REMNAWAVE','https://panel.example.test',true,$secret,false,$now,$now)""".update.run
          _ <- fleets.insertFleet(RemnawaveFleet(fleet,w.org,integration,"v52-fleet","V52 fleet",None,None,1,false,actor,now,now))
        } yield ())
        api=NodeApiCompatibility(Some("2.8.0"),Some("PROFILE_PUBKEY"),Some("commit"),Set(NodeProvisioningCapability.Create),None)
        snap=OnboardingSnapshot(OnboardingInput(node.resourceId,"v52-node","192.0.2.99",2222,UUID.randomUUID(),List(UUID.randomUUID()),List("198.51.100.0/24")),
          now,secret,node.connectionId,sourceAt,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),1,"a"*64,
          UUID.randomUUID(),false,api,"remnawave/node:2.8.0",UUID.randomUUID(),"Server","Baseline","Config",Nil,Nil,Nil,Nil)
        ids <- List("FAILED","UNKNOWN","SUCCEEDED").traverse { state =>
          val id=UUID.randomUUID(); val ext=UUID.randomUUID(); val request=UUID.randomUUID()
          val phase=if(state=="SUCCEEDED") "FINAL_VERIFY" else "CONFIGURE_NODE_FIREWALL"
          val failure=Option.when(state!="SUCCEEDED")("TEST_FAILURE")
          w.run(for {
            _ <- sql"""insert into remnawave_node_onboarding(id,organization_id,integration_id,resource_id,request_id,created_by,
              state,phase,input_snapshot,external_node_id,failure_code,created_at,updated_at,finished_at)
              values($id,${w.org},$integration,${node.resourceId},$request,$actor,$state,$phase,
                cast(${OnboardingSnapshotCodec.encode(snap).noSpaces} as jsonb),$ext,$failure,$now,$now,$now)""".update.run
            _ <- OnboardingPhase.all.zipWithIndex.traverse_ { case(p,index) =>
              val status=if(state=="SUCCEEDED") "SUCCEEDED" else "PENDING"
              sql"insert into remnawave_node_onboarding_phase(run_id,phase,position,state) values($id,${p.code},$index,$status)".update.run.void
            }
          } yield id)
        }
        before <- w.run(ids.traverse(repo.find(w.org,integration,_)))
        fleetBefore <- w.run(fleets.fleet(w.org,integration,fleet))
        result <- DatabaseMigrator.migrate(config.copy(url=xa.kernel.getJdbcUrl),NoOpLogger[IO])
        _ = assertEquals(result.migrationsApplied,4)
        _ = assertEquals(result.currentVersion,"56")
        after <- w.run(ids.traverse(repo.find(w.org,integration,_)))
        fleetAfter <- w.run(fleets.fleet(w.org,integration,fleet))
        _ = assertEquals(after,before)
        _ = assertEquals(fleetAfter,fleetBefore)
        _ = assert(after.flatten.forall(_._1.snapshot.lifecycleVersion==1))
        _ <- ids.traverse_ { id => w.run(sql"update remnawave_node_onboarding set failure_code='MUTATED' where id=$id".update.run).attempt.map(r => assert(r.isLeft)) }
        tombstone <- w.run(new PostgresIntegrationRepository().delete(w.org,integration))
        _ <- w.run(new PostgresIntegrationSecretRepository().delete(w.org,secret))
        retained <- w.run(ids.traverse(repo.find(w.org,integration,_)))
        _ = assertEquals(retained,before)
      } yield ()
    }.unsafeRunSync() finally remote.stop()
  }
}
