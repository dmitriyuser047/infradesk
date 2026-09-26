package ru.bitec.app.ops
package integration.notification

import cats.effect.IO
import cats.syntax.all._
import org.xbill.DNS.lookup.LookupSession
import org.xbill.DNS.{AAAARecord, ARecord, Address, ExtendedResolver, Name, Type}

import java.io.IOException
import java.net.InetAddress
import java.time.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Try

sealed trait OutboundDestinationFailure {
  def code: String
}

object OutboundDestinationFailure {
  final case class Forbidden(code: String) extends OutboundDestinationFailure
  final case class ResolutionFailed(code: String) extends OutboundDestinationFailure
}

final class OutboundDestinationRejected(val code: String)
  extends IOException(s"Destination rejected: $code")

final class OutboundDestinationUnresolvable(val code: String)
  extends IOException(s"Destination could not be resolved: $code")

/** Resolves and validates a configured destination in one operation.
  *
  * The returned address is the only address a transport may dial. Resolution is effectful rather
  * than a blocking callback because it belongs to the same cancellation boundary as the network
  * attempt that follows it. That keeps DNS inside a delivery's whole-attempt deadline and also
  * prevents a second lookup between the policy decision and the connect.
  */
trait OutboundDestinationPolicy {
  def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]]
}

/** Production destination policy, backed by dnsjava's asynchronous resolver. */
final class ResolvingOutboundDestinationPolicy(
  allowPrivateNetworks: Boolean,
  lookup: LookupSession
) extends OutboundDestinationPolicy {

  import OutboundDestinationPolicy._

  override def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
    literal(host) match {
      case Some(address) => IO.pure(validate(List(address)))
      case None if host.isEmpty => IO.pure(Left(OutboundDestinationFailure.Forbidden(Forbidden)))
      case None =>
        IO.delay(Name.fromString(host)).attempt.flatMap {
          case Left(_) => IO.pure(Left(OutboundDestinationFailure.ResolutionFailed(ResolutionFailed)))
          case Right(name) =>
            (addresses(name, Type.A), addresses(name, Type.AAAA)).parMapN(_ ++ _).map(validate)
        }
    }

  /** `IO.fromCompletableFuture` propagates fiber cancellation to the Java future. Each query also
    * has the resolver timeout configured by `resolving`, so the underlying DNS operation itself
    * is bounded if a platform is slow to observe cancellation.
    */
  private def addresses(name: Name, recordType: Int): IO[List[InetAddress]] =
    IO.fromCompletableFuture(IO.delay(lookup.lookupAsync(name, recordType).toCompletableFuture))
      .map(_.getRecords.asScala.toList.collect {
        case record: ARecord => record.getAddress
        case record: AAAARecord => record.getAddress
      })
      .handleError(_ => Nil)

  private def validate(
    addresses: List[InetAddress]
  ): Either[OutboundDestinationFailure, InetAddress] = addresses match {
    case Nil => Left(OutboundDestinationFailure.ResolutionFailed(ResolutionFailed))
    case values if values.exists(isForbidden) =>
      Left(OutboundDestinationFailure.Forbidden(Forbidden))
    case values if !allowPrivateNetworks && values.exists(isPrivate) =>
      Left(OutboundDestinationFailure.Forbidden(Forbidden))
    case values => Right(values.head)
  }

  /** dnsjava parses full IPv4 and IPv6 literals without consulting DNS. The IPv4 fallback keeps
    * the JDK's accepted abbreviated forms (for example `127.1`) without invoking its resolver.
    */
  private def literal(host: String): Option[InetAddress] =
    Try(Address.getByAddress(host)).toOption.orElse(abbreviatedIpv4(host))

  private def abbreviatedIpv4(host: String): Option[InetAddress] = {
    val parts = host.split("\\.", -1).toList
    if (parts.isEmpty || parts.length > 4 || parts.exists(part => part.isEmpty || !part.forall(_.isDigit))) None
    else {
      parts.traverse(part => Try(java.lang.Long.parseLong(part)).toOption).flatMap { values =>
        val value = values match {
          case a :: Nil if a <= 0xffffffffL => Some(a)
          case a :: b :: Nil if a <= 0xffL && b <= 0xffffffL => Some((a << 24) | b)
          case a :: b :: c :: Nil if a <= 0xffL && b <= 0xffL && c <= 0xffffL =>
            Some((a << 24) | (b << 16) | c)
          case a :: b :: c :: d :: Nil if List(a, b, c, d).forall(_ <= 0xffL) =>
            Some((a << 24) | (b << 16) | (c << 8) | d)
          case _ => None
        }
        value.map { address =>
          InetAddress.getByAddress(Array(
            ((address >>> 24) & 0xff).toByte,
            ((address >>> 16) & 0xff).toByte,
            ((address >>> 8) & 0xff).toByte,
            (address & 0xff).toByte
          ))
        }
      }
    }
  }

  private def isForbidden(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress ||
      address.isMulticastAddress

  private def isPrivate(address: InetAddress): Boolean =
    address.isSiteLocalAddress || isUniqueLocal(address)

  private def isUniqueLocal(address: InetAddress): Boolean =
    address.getAddress.length == 16 && (address.getAddress()(0) & 0xfe) == 0xfc
}

object OutboundDestinationPolicy {
  val Forbidden: String = "DESTINATION_NOT_ALLOWED"
  val ResolutionFailed: String = "DESTINATION_RESOLUTION_FAILED"

  def resolving(
    allowPrivateNetworks: Boolean,
    resolutionTimeout: FiniteDuration = scala.concurrent.duration.DurationInt(10).seconds
  ): OutboundDestinationPolicy = {
    val resolver = new ExtendedResolver()
    resolver.setTimeout(Duration.ofNanos(resolutionTimeout.toNanos))
    val lookup = LookupSession.defaultBuilder().resolver(resolver).build()
    new ResolvingOutboundDestinationPolicy(allowPrivateNetworks, lookup)
  }

  def classify(error: Throwable): Option[application.port.NotificationSendResult] = error match {
    case rejected: OutboundDestinationRejected =>
      Some(application.port.NotificationSendResult.PermanentFailure(rejected.code))
    case unresolved: OutboundDestinationUnresolvable =>
      Some(application.port.NotificationSendResult.RetryableFailure(unresolved.code))
    case _ => Option(error.getCause).filter(_ ne error).flatMap(classify)
  }
}
