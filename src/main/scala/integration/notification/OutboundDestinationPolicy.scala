package ru.bitec.app.ops
package integration.notification

import cats.effect.IO

import java.io.IOException
import java.net.{InetAddress, UnknownHostException}

/** Why a destination may not be connected to. */
sealed trait OutboundDestinationFailure {
  def code: String
}

object OutboundDestinationFailure {

  /** Not allowed, whatever it resolves to on a later attempt: re-entering the configuration is
    * the only thing that can help, so this is never retried.
    */
  final case class Forbidden(code: String) extends OutboundDestinationFailure

  /** The name did not resolve this time. It may resolve on the next attempt — a transient DNS
    * problem says nothing about whether the destination itself is allowed — so this is worth
    * retrying rather than a reason to give up.
    */
  final case class ResolutionFailed(code: String) extends OutboundDestinationFailure
}

/** Carries a destination decision up through a blocking, exception-based API (Jakarta Mail's
  * `SocketFactory`, whose methods may only return a `Socket` or throw) to the classifier that
  * turns it back into a `NotificationSendResult`.
  *
  * The message names only the bounded code, never the host: both are safe to log, to render in
  * an error, or to leave in a stack trace.
  */
final class OutboundDestinationRejected(val code: String)
  extends IOException(s"Destination rejected: $code")

final class OutboundDestinationUnresolvable(val code: String)
  extends IOException(s"Destination could not be resolved: $code")

/** Whether the product may open a connection to a host a person configured, and which one of the
  * addresses that host resolves to it may open it to.
  *
  * A webhook URL and an SMTP relay are the first destinations in this product that a user
  * chooses, which makes the deployment's own network reachable from the outside: a channel
  * pointing at the loopback interface, at a link-local address or at a cloud metadata endpoint
  * would turn a notification into a request the deployment makes to itself, on behalf of whoever
  * configured the channel.
  *
  * Checking a name and connecting to it are two different lookups unless the same answer is used
  * for both. A name that resolves to a public address when it is checked can resolve to the
  * loopback interface, to a metadata endpoint or to a private address by the time a second,
  * separate lookup connects — DNS rebinding — so "is this host allowed" is not a question this
  * answers on its own, ahead of the connection and independent of it. `pin` is the one lookup: it
  * resolves the host itself and returns the single address that may be dialed, and the transports
  * that use it dial exactly that address, never the host name again.
  *
  * The loopback interface, link-local addresses (the metadata endpoint of every major cloud
  * among them), multicast and the unspecified address are refused outright and cannot be
  * enabled: no deployment has a legitimate notification channel there. Private ranges are a
  * different matter — an SMTP relay or a webhook receiver on an internal network is an ordinary
  * arrangement — so they are refused by default and allowed only by an explicit decision of the
  * deployment, never by a channel.
  */
trait OutboundDestinationPolicy {

  /** The blocking half: resolves `host` and validates every address it resolved to, synchronously.
    * Called directly by code that already runs on the blocking pool — Jakarta Mail's socket
    * factories are, because the whole SMTP exchange runs inside one `IO.blocking` — and wrapped
    * in `IO.blocking` by `pin` for callers that are not.
    */
  def pinBlocking(host: String): Either[OutboundDestinationFailure, InetAddress]

  /** The address to connect to, or why none of the ones `host` resolved to may be. */
  def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
    IO.blocking(pinBlocking(host))
}

/** The policy as the product runs it: resolve the name, look at every address it answered with. */
final class ResolvingOutboundDestinationPolicy(allowPrivateNetworks: Boolean)
  extends OutboundDestinationPolicy {

  import OutboundDestinationPolicy._

  override def pinBlocking(host: String): Either[OutboundDestinationFailure, InetAddress] =
    try {
      InetAddress.getAllByName(host).toList match {
        case Nil => Left(OutboundDestinationFailure.ResolutionFailed(ResolutionFailed))
        case addresses if addresses.exists(isForbidden) =>
          Left(OutboundDestinationFailure.Forbidden(Forbidden))
        case addresses if !allowPrivateNetworks && addresses.exists(isPrivate) =>
          Left(OutboundDestinationFailure.Forbidden(Forbidden))
        // Any of the resolved addresses is as good as any other, since all of them passed: the
        // first is used so the choice is deterministic rather than a reason to look again.
        case addresses => Right(addresses.head)
      }
    } catch {
      // A name that does not resolve is a transient problem, not a verdict on the destination.
      case _: UnknownHostException => Left(OutboundDestinationFailure.ResolutionFailed(ResolutionFailed))
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
  val ResolutionFailed: String = "DESTINATION_RESOLUTION_FAILED"

  def resolving(allowPrivateNetworks: Boolean): OutboundDestinationPolicy =
    new ResolvingOutboundDestinationPolicy(allowPrivateNetworks)

  /** Turns a destination decision that surfaced as an exception back into the outcome it was.
    *
    * Every transport that pins a destination calls this first, before its own classification, so
    * a forbidden or an unresolved destination reads the same way whichever transport hit it. The
    * exception may be wrapped — Jakarta Mail wraps a socket failure in a `MessagingException` —
    * which is why this walks `getCause` rather than checking only the exception it was handed.
    */
  def classify(error: Throwable): Option[application.port.NotificationSendResult] = error match {
    case rejected: OutboundDestinationRejected =>
      Some(application.port.NotificationSendResult.PermanentFailure(rejected.code))
    case unresolved: OutboundDestinationUnresolvable =>
      Some(application.port.NotificationSendResult.RetryableFailure(unresolved.code))
    case _ => Option(error.getCause).filter(_ ne error).flatMap(classify)
  }
}
