package ru.bitec.app.ops
package integration.ssh

import java.nio.charset.StandardCharsets
import scala.sys.process._
import scala.concurrent.duration._
import munit.FunSuite

/** Runs the production program against real Linux TCP/UDP sockets and real host process IDs.
  * Docker metadata is fixture-controlled; no mocked socket or preclassified outcome is used. */
final class ProtocolSocketProbeShellSpec extends FunSuite {
  override val munitTimeout: Duration=120.seconds
  private val enabled=sys.env.get("INFRADESK_RUN_SHELL_INTEGRATION_TESTS").contains("true")
  test(if(enabled) munit.TestOptions("production protocol socket probe distinguishes transports and exact container process ownership")
    else munit.TestOptions("Linux protocol socket observation").ignore) {
    assertEquals(Seq("docker","build","-q","-t","infradesk-syn-test:ubuntu24","src/test/resources/remnawave-syn").!,0)
    val host=Seq("docker","run","--rm","-d","infradesk-syn-test:ubuntu24").!!.trim
    def exec(args: String*)=(Process(Seq("docker","exec","-i",host,"sh")) #< new java.io.ByteArrayInputStream(
      PosixArgv.encode(args.toList).getBytes(StandardCharsets.UTF_8))).!!
    def probe(t: String, own: Boolean=false)=exec("python3","-c",ProtocolSocketProbe.program,"18443",t,
      if(own) "owned-node" else "",if(own) "/owned" else "",if(own) "pinned-image" else "").trim
    try {
      assertEquals(probe("tcp"),"FREE"); assertEquals(probe("udp"),"FREE")
      exec("sh","-c","mkdir -p /fixture; ln -s /usr/bin/python3 /fixture/xray; printf '%s' 'import socket,time,os; s=socket.socket(); s.bind((\"0.0.0.0\",18443)); s.listen(); open(\"/fixture/pid\",\"w\").write(str(os.getpid())); time.sleep(90)' >/fixture/listen.py; /fixture/xray /fixture/listen.py >/dev/null 2>&1 &")
      exec("sh","-c","n=0; while [ ! -f /fixture/pid ] && [ $n -lt 50 ]; do n=$((n+1)); sleep 0.1; done; test -f /fixture/pid")
      assertEquals(probe("tcp"),"FOREIGN_LISTENER")
      // Hysteria2 queries UDP only: an unrelated TCP listener is permitted.
      assertEquals(probe("udp"),"FREE")
      exec("sh","-c", """printf '%s\n' '#!/bin/sh' 'case "$1" in' 'inspect) printf '\''{"files":"/owned/compose.yml","image":"pinned-image","network":"host","running":true}\n'\'' ;;' 'top) printf "PID COMMAND\n%s xray\n" "$(cat /fixture/pid)" ;;' 'esac' >/usr/local/bin/docker; chmod 755 /usr/local/bin/docker""")
      assertEquals(probe("tcp",true),"OWNED_EXPECTED")
      exec("sh","-c","printf 999999 >/fixture/pid")
      assertEquals(probe("tcp",true),"FOREIGN_LISTENER")
      exec("sh","-c","printf '%s\n' '#!/bin/sh' 'exit 1' >/usr/local/bin/docker")
      assertEquals(probe("tcp",true),"OBSERVATION_UNKNOWN")
      exec("sh","-c","python3 -c 'import socket,time; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind((\"0.0.0.0\",18443)); open(\"/fixture/udp\",\"w\").write(\"ready\"); time.sleep(90)' >/dev/null 2>&1 &")
      exec("sh","-c","n=0; while [ ! -f /fixture/udp ] && [ $n -lt 50 ]; do n=$((n+1)); sleep 0.1; done; test -f /fixture/udp")
      assertEquals(probe("udp"),"FOREIGN_LISTENER")
      exec("sh","-c","printf '%s\n' '#!/bin/sh' 'printf garbage' >/usr/local/bin/ss; chmod 755 /usr/local/bin/ss")
      assertEquals(probe("udp"),"OBSERVATION_UNKNOWN")
    } finally { Seq("docker","stop",host).!; () }
  }
}
