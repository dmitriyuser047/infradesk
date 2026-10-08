package ru.bitec.app.ops
package application.integration

import application.port.{RemnawaveNodeAddressResolver,RemnawaveNodeRemote}
import domain.connection.Connection
import domain.integration._
import cats.effect.IO
import cats.syntax.all._

/** One selected, authenticated host observation; resource names never supply Node.address. */
object OnboardingNodeAddresses {
  def resolve(input: OnboardingInput,connection: Connection,remote: RemnawaveNodeRemote[IO],
    dns: RemnawaveNodeAddressResolver[IO]): IO[NodeAddressEvidence] = remote.publicNodeAddresses(connection)
      .handleErrorWith(_ => IO.raiseError(IntegrationError("REMNAWAVE_NODE_PUBLIC_IP_UNCONFIRMED","The managed server public IP could not be confirmed"))).flatMap { ips =>
    if(input.nodeAddressMode==NodeAddressMode.Domain) dns.verifyDomain(input.address,ips)
    else {
      val v4=ips.filter(!_.contains(":")); val selected=if(v4.nonEmpty) v4 else ips
      IO.fromOption(Option.when(selected.size==1)(selected.head))(
        IntegrationError("REMNAWAVE_NODE_PUBLIC_IP_AMBIGUOUS","A single confirmed public Node address is required"))
        .map(ip => NodeAddressEvidence(NodeAddressMode.PublicIp,ip,ips))
    }
  }
}
