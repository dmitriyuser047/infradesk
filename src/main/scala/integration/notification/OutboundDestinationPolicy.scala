package ru.bitec.app.ops
package integration.notification

import cats.effect.IO

import java.net.{InetAddress, UnknownHostException}

/** Whether the product may open a connection to a host a person configured.
  *
  * A webhook URL and an SMTP relay are the first destinations in this product that a user
  * chooses, which makes the deployment's own network reachable from the outside: a channel
  * pointing at the loopback interface, at a link-local address or at a cloud metadata endpoint
  * would turn a notification into a request the deployment makes to itself, on behalf of whoever
  * configured the channel.
  *
  * The check is on the addresses the host resolves to, not on how it is spelled: a name that
  * resolves to 127.0.0.1 is the loopback interface however it is written, and a decimal or
  * IPv6-mapped form of a private address is still that address.
  *
  * The loopback interface, link-local addresses (the metadata endpoint of every major cloud
  * among them), multicast and the unspecified address are refused outright and cannot be
  * enabled: no deployment has a legitimate notification channel there. Private ranges are a
  * different matter — an SMTP relay or a webhook receiver on an internal network is an ordinary
  * arrangement — so they are refused by default and allowed only by an explicit decision of the
  * deployment, never by a channel.
  *
  * What this does not do: the address is checked and the connection is opened afterwards, so a
  * name whose answer changes in between is resolved twice and could differ. Closing that would
  * mean connecting to the address this checked rather than to the name, which neither the HTTP
  * client nor the mail transport can be told to do without replacing their socket layer.
  */
trait OutboundDestinationPolicy {

  /** Whether a connection to this host may be opened, or why it may not. */
  def check(host: String): IO[Either[String, Unit]]
}

/** The policy as the product runs it: resolve the name, look at the addresses. */
final class ResolvingOutboundDestinationPolicy(allowPrivateNetworks: Boolean)
  extends OutboundDestinationPolicy {

  import OutboundDestinationPolicy._

  /** The lookup blocks, so it is run where blocking belongs. */
  override def check(host: String): IO[Either[String, Unit]] =
    IO.blocking(InetAddress.getAllByName(host).toList)
      .map {
        case Nil => Left(Unresolvable)
        case addresses if addresses.exists(isForbidden) => Left(Forbidden)
        case addresses if !allowPrivateNetworks && addresses.exists(isPrivate) => Left(Forbidden)
        case _ => Right(())
      }
      .handleError {
        // A name that does not resolve is a configuration problem, not a network hiccup: it is
        // reported as its own reason rather than as a refusal.
        case _: UnknownHostException => Left(Unresolvable)
        case _ => Left(Unresolvable)
      }

  private def isForbidden(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress ||
      address.isMulticastAddress

  /** An internal network, in both address families: the ranges a deployment may legitimately
    * run a relay or a receiver on, and may therefore choose to allow.
    */
  private def isPrivate(address: InetAddress): Boolean =
    address.isSiteLocalAddress || isUniqueLocal(address)

  /** fc00::/7, the IPv6 counterpart of the ranges that `isSiteLocalAddress` covers. */
  private def isUniqueLocal(address: InetAddress): Boolean =
    address.getAddress.length == 16 && (address.getAddress()(0) & 0xfe) == 0xfc
}

object OutboundDestinationPolicy {

  val Forbidden: String = "DESTINATION_NOT_ALLOWED"
  val Unresolvable: String = "DESTINATION_UNRESOLVABLE"

  def resolving(allowPrivateNetworks: Boolean): OutboundDestinationPolicy =
    new ResolvingOutboundDestinationPolicy(allowPrivateNetworks)
}
