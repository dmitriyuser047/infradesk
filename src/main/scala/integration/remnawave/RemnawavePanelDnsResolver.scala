package ru.bitec.app.ops
package integration.remnawave

import application.port.RemnawavePanelSourceResolver
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import org.xbill.DNS.lookup.LookupSession
import org.xbill.DNS.{AAAARecord, ARecord, Address, ExtendedResolver, Name, Resolver, Type}
import java.net.InetAddress
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

final class RemnawavePanelDnsResolver(lookup: LookupSession, allowPrivate: Boolean)
  extends RemnawavePanelSourceResolver[IO] {
  private def addresses(host: String, kind: Int): IO[List[InetAddress]] =
    IO.fromCompletableFuture(IO.delay(lookup.lookupAsync(Name.fromString(host + "."), kind).toCompletableFuture))
      .map(_.getRecords.asScala.toList.collect { case r: ARecord => r.getAddress; case r: AAAARecord => r.getAddress })
      .handleError(_ => Nil)
  def resolve(endpoint: IntegrationBaseUrl, mode: PanelSourceMode, manualSources: List[String],
    managedPanelAddress: Option[String]): IO[PanelSourceEvidence] = {
    val fingerprint = PanelSourceEvidence.fingerprint(endpoint)
    if(mode == PanelSourceMode.Manual) IO.fromEither(OnboardingInput.canonicalCidrs(manualSources)
      .filterOrElse(_ == manualSources.sorted,"REMNAWAVE_ONBOARDING_CIDR_INVALID")
      .leftMap(_ => new IllegalArgumentException("REMNAWAVE_ONBOARDING_CIDR_INVALID")))
      .map(s => PanelSourceEvidence(mode,s,"MANUAL","MANUAL",fingerprint))
    else {
      val method = if(managedPanelAddress.nonEmpty) "MANAGED_PANEL_RESOURCE" else "DNS_BASE_URL"
      val host = managedPanelAddress.getOrElse(java.net.URI.create(endpoint.value).getHost).stripPrefix("[").stripSuffix("]").stripSuffix(".")
      val unresolved = PanelSourceEvidence(mode,Nil,method,"UNRESOLVED",fingerprint)
      val query = if(host.equalsIgnoreCase("localhost") || !OnboardingInput.validAddress(host)) IO.pure(List.empty[InetAddress]) else Try(Address.getByAddress(host)).toOption.fold(
        (addresses(host,Type.A),addresses(host,Type.AAAA)).parMapN(_ ++ _))(a => IO.pure(List(a)))
      query.timeoutTo(5.seconds,IO.pure(Nil)).map { values =>
        val unique = values.distinct
        // Reserved addresses never create rules. Private sources require the existing explicit deployment policy.
        val forbidden = unique.exists(a => a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
          a.isMulticastAddress || (a.getAddress.length == 4 && ((a.getAddress()(0) & 0xff) == 0 || (a.getAddress()(0) & 0xff) >= 240)) ||
          List("100.64.0.0/10","192.0.0.0/24","192.0.2.0/24","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24","2001:db8::/32")
            .exists(domain.provisioning.ServerProfileContent.sourceCovers(_,a.getHostAddress)) ||
          (!allowPrivate && (a.isSiteLocalAddress || (a.getAddress.length == 16 && (a.getAddress()(0) & 0xfe) == 0xfc))))
        if(unique.isEmpty || unique.size > 32 || forbidden) unresolved
        else OnboardingInput.canonicalCidrs(unique.map(a => a.getHostAddress + (if(a.getAddress.length == 4) "/32" else "/128")))
          .toOption.fold(unresolved)(s => PanelSourceEvidence(mode,s,method,"AUTO_CANDIDATE",fingerprint))
      }
    }
  }
}
object RemnawavePanelDnsResolver {
  private[remnawave] def freshLookup(resolver: Resolver): LookupSession =
    LookupSession.builder().resolver(resolver).clearSearchPath().build()
  def production(allowPrivate: Boolean): RemnawavePanelDnsResolver = {
    val resolver = new ExtendedResolver()
    resolver.setTimeout(java.time.Duration.ofSeconds(3))
    new RemnawavePanelDnsResolver(freshLookup(resolver),allowPrivate)
  }
}
