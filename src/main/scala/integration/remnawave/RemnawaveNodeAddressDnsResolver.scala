package ru.bitec.app.ops
package integration.remnawave

import application.port.RemnawaveNodeAddressResolver
import application.integration.IntegrationError
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import org.xbill.DNS.{AAAARecord,ARecord,ExtendedResolver,Name,Type}
import org.xbill.DNS.lookup.LookupSession
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

final class RemnawaveNodeAddressDnsResolver(lookup: LookupSession) extends RemnawaveNodeAddressResolver[IO] {
  def verifyDomain(domain: String,publicAddresses: List[String]): IO[NodeAddressEvidence] = {
    def error=IntegrationError("REMNAWAVE_NODE_ADDRESS_DNS_UNCONFIRMED","The selected domain must resolve to the managed server public IP")
    def query(kind: Int): IO[List[String]] = IO.fromCompletableFuture(IO.delay(
      lookup.lookupAsync(Name.fromString(domain+"."),kind).toCompletableFuture)).map(_.getRecords.asScala.toList.collect {
        case a: ARecord => a.getAddress.getHostAddress
        case a: AAAARecord => a.getAddress.getHostAddress
      }).handleError(_ => Nil)
    IO.raiseUnless(OnboardingInput.validAddress(domain) && NodeAddress.literal(domain).isEmpty)(error) *>
      (query(Type.A),query(Type.AAAA)).parMapN(_ ++ _).timeoutTo(5.seconds,IO.pure(Nil)).flatMap { addresses =>
        val resolved=addresses.distinct.sorted
        IO.raiseUnless(resolved.nonEmpty && resolved.size<=32 && resolved.forall(publicAddresses.contains))(error)
          .as(NodeAddressEvidence(NodeAddressMode.Domain,domain,publicAddresses))
      }
  }
}
object RemnawaveNodeAddressDnsResolver {
  def production(): RemnawaveNodeAddressDnsResolver = {
    val resolver=new ExtendedResolver(); resolver.setTimeout(java.time.Duration.ofSeconds(3))
    new RemnawaveNodeAddressDnsResolver(RemnawavePanelDnsResolver.freshLookup(resolver))
  }
}
