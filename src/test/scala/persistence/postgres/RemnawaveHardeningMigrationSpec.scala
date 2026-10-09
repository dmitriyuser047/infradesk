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
  override val munitTimeout: scala.concurrent.duration.Duration=scala.concurrent.duration.Duration(120,"seconds")
  test("production CI V29 and V41 backup fixtures upgrade cleanly through V66") {
    assume(ConfigurationDeploymentWorld.enabled,"PostgreSQL integration tests are opt-in")
    val config = PostgresTestDatabase.config
    val workflow = java.nio.file.Files.readString(java.nio.file.Paths.get(".github/workflows/ci.yml"))
    val marker = "-- Inventory V65: restore the older action contract in this disposable fixture."
    val blocks = workflow.split(java.util.regex.Pattern.quote(marker)).toList.tail.map { section =>
      (marker + section.take(section.indexOf("\n          SQL"))).linesIterator
        .map(_.stripPrefix("          ")).mkString("\n")
    }
    assertEquals(blocks.size,2)
    blocks.zip(List("29","41")).traverse_ { case(fixture,target) =>
      PostgresTestDatabase.isolatedTransactor(config).use { xa =>
        val isolated = config.copy(url=xa.kernel.getJdbcUrl)
        val runner = new DoobieTransactionRunner(xa)
        val org = UUID.randomUUID()
        for {
          _ <- runner.run(sql"insert into organization(id,code,name) values($org,${org.toString},'Backup marker')".update.run)
          _ <- IO.blocking {
            val connection = java.sql.DriverManager.getConnection(isolated.url,isolated.user,isolated.password)
            try {
              val statement = connection.createStatement()
              try { statement.execute(fixture); () } finally statement.close()
            } finally connection.close()
          }
          version <- runner.run(sql"select version from flyway_schema_history where success order by installed_rank desc limit 1".query[String].unique)
          _ = assertEquals(version,target)
          migrated <- DatabaseMigrator.migrate(isolated,NoOpLogger[IO])
          _ = assertEquals(migrated.currentVersion,"66")
          _ = assertEquals(migrated.migrationsApplied,66-target.toInt)
          retained <- runner.run(sql"select count(*) from organization where id=$org and name='Backup marker'".query[Long].unique)
          _ = assertEquals(retained,1L)
        } yield ()
      }
    }.unsafeRunSync()
  }

  test("V52, V56, V60 and V62 upgrades preserve terminal legacy snapshots, journal sequences and fleet identity") {
    assume(ConfigurationDeploymentWorld.enabled,"PostgreSQL integration tests are opt-in")
    val config=PostgresTestDatabase.config
    val remote=RemoteConfigurationServer.start()
    try List("52","56","60","62").traverse_ { version => PostgresTestDatabase.isolatedTransactor(config,Some(version)).use { xa =>
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
        before <- w.run(ids.traverse(id => sql"select input_snapshot::text,state,phase,external_node_id,request_id from remnawave_node_onboarding where id=$id"
          .query[(String,String,String,Option[UUID],Option[UUID])].unique))
        fleetBefore <- w.run(fleets.fleet(w.org,integration,fleet))
        result <- DatabaseMigrator.migrate(config.copy(url=xa.kernel.getJdbcUrl),NoOpLogger[IO])
        _ = assertEquals(result.migrationsApplied,66-version.toInt)
        _ = assertEquals(result.currentVersion,"66")
        after <- w.run(ids.traverse(id => sql"select input_snapshot::text,state,phase,external_node_id,request_id from remnawave_node_onboarding where id=$id"
          .query[(String,String,String,Option[UUID],Option[UUID])].unique))
        decoded <- w.run(ids.traverse(repo.find(w.org,integration,_)))
        fleetAfter <- w.run(fleets.fleet(w.org,integration,fleet))
        _ = assertEquals(after,before)
        _ = assertEquals(fleetAfter,fleetBefore)
        _ = assert(decoded.flatten.forall(_._1.snapshot.lifecycleVersion==1))
        _ <- ids.traverse_ { id => w.run(sql"update remnawave_node_onboarding set failure_code='MUTATED' where id=$id".update.run).attempt.map(r => assert(r.isLeft)) }
        blocked <- w.run(new PostgresIntegrationRepository().delete(w.org,integration)).attempt
        _ = assert(blocked.isLeft)
        _ <- w.run(sql"""insert into audit_event(id,organization_id,actor_user_id,action,target_type,target_id,occurred_at,created_at)
          values(${UUID.randomUUID()},${w.org},$actor,'INTEGRATION_RECOVERY_ABANDONED','INTEGRATION',$integration,$now,$now)""".update.run)
        tombstone <- w.run(new PostgresIntegrationRepository().delete(w.org,integration))
        _ <- w.run(new PostgresIntegrationSecretRepository().delete(w.org,secret))
        retained <- w.run(ids.traverse(repo.find(w.org,integration,_)))
        _ = assertEquals(retained,decoded)
      } yield ()
    }}.unsafeRunSync() finally remote.stop()
  }
}
