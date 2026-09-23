package ru.bitec.app.ops
package integration.ssh

import munit.FunSuite
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

import java.net.{ConnectException, SocketTimeoutException}

final class SshTransportFailureSpec extends FunSuite {
  private val raw = "password=super-secret host=10.0.0.1"

  test("connect timeout and refused are classified by typed causes, not messages") {
    val timeout = new TransportException("wrapper", new SocketTimeoutException(raw))
    val refused = new TransportException("wrapper", new ConnectException(raw))
    assert(SshTransportFailure.fromConnect(timeout, hostKeyMismatch = false)
      .isInstanceOf[SshTransportFailure.ConnectTimeout])
    assert(SshTransportFailure.fromConnect(refused, hostKeyMismatch = false)
      .isInstanceOf[SshTransportFailure.ConnectionRefused])
    assert(SshTransportFailure.fromConnect(new TransportException(raw), hostKeyMismatch = false)
      .isInstanceOf[SshTransportFailure.ConnectionFailed])
  }

  test("host-key mismatch takes precedence over generic handshake failure") {
    val error = SshTransportFailure.fromConnect(new TransportException(raw), hostKeyMismatch = true)
    assert(error.isInstanceOf[SshTransportFailure.HostKeyMismatch])
    assert(error.getCause.isInstanceOf[SshHostKeyMismatch])
  }

  test("SSHJ authentication rejection is distinct from a transport failure during auth") {
    assert(SshTransportFailure.fromAuthentication(new UserAuthException(raw))
      .isInstanceOf[SshTransportFailure.AuthenticationFailed])
    assert(SshTransportFailure.fromAuthentication(
      new UserAuthException(raw, new SocketTimeoutException(raw)))
      .isInstanceOf[SshTransportFailure.ConnectionFailed])
    assert(SshTransportFailure.fromAuthenticationTransport(new TransportException(raw))
      .isInstanceOf[SshTransportFailure.ConnectionFailed])
  }

  test("command timeout contains no command or raw transport details in its safe message") {
    val failure = SshTransportFailure.commandTimeout()
    assert(failure.isInstanceOf[SshTransportFailure.CommandTimeout])
    assertEquals(failure.getMessage, "SSH command timed out")
    assert(!failure.getMessage.contains(raw))
  }
}
