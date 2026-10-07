package ru.bitec.app.ops
package application.integration

import application.port._
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.integration._

/** Uses the existing tenant-scoped HOST bindings; unrelated Node/Host bindings are never Panel evidence. */
final class OnboardingPanelSources[Tx[_]: Monad](query: RemnawaveOnboardingQuery[Tx],runner: TransactionRunner[IO,Tx],
  dns: RemnawavePanelSourceResolver[IO]) {
  def resolve(context: IntegrationRuntimeContext,mode: PanelSourceMode,manual: List[String]): IO[PanelSourceEvidence] =
    (if(mode==PanelSourceMode.Manual) IO.pure(List.empty[String]) else runner.run(query.managedPanelAddresses(
      context.organizationId,context.id,java.net.URI.create(context.baseUrl.value).getHost))).flatMap {
      case address :: Nil => dns.resolve(context.baseUrl,mode,manual,Some(address))
      case Nil => dns.resolve(context.baseUrl,mode,manual,None)
      case _ => IO.pure(PanelSourceEvidence(mode,Nil,"MANAGED_PANEL_RESOURCE","UNRESOLVED",PanelSourceEvidence.fingerprint(context.baseUrl)))
    }
}
