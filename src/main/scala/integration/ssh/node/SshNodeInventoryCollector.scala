package ru.bitec.app.ops
package integration.ssh.node

import cats.MonadThrow
import cats.syntax.all._
import integration.ssh.SshSession

final class SshNodeInventoryCollector[F[_]: MonadThrow] {

  def collect(session: SshSession[F]): F[CollectedNodeInventory] =
    session.execute(SshNodeInventoryCollector.Command).flatMap { result =>
      if (!result.isSuccess)
        new IllegalStateException(
          s"SSH node inventory command exited with code ${result.exitCode}"
        ).raiseError[F, CollectedNodeInventory]
      else NodeInventoryParser.parse(result.stdout)
        .map(CollectedNodeInventory(_, result.hostKeyFingerprint)).liftTo[F]
    }
}

final case class CollectedNodeInventory(inventory: NodeInventory, hostKeyFingerprint: String)

object SshNodeInventoryCollector {

  /** Reads the human-readable processor name out of `/proc/cpuinfo`.
    *
    * `model` and `model name` are different fields, and `model` — a numeric identifier — comes
    * first, so a pattern that accepts either reports the number. Each key is therefore matched
    * on its own, and the name wins over the ARM `Hardware` fallback regardless of file order.
    * Only POSIX awk features are used, because the default awk of a Debian host is mawk.
    */
  private[node] val CpuModelProgram: String =
    "/^model name[[:space:]]*:/ { if (name == \"\") name = substr($0, index($0, \":\") + 1) } " +
      "/^[Hh]ardware[[:space:]]*:/ { if (hardware == \"\") hardware = substr($0, index($0, \":\") + 1) } " +
      "END { value = (name != \"\" ? name : hardware); " +
      "sub(/^[[:space:]]+/, \"\", value); sub(/[[:space:]]+$/, \"\", value); " +
      "if (value != \"\") print value }"

  val Command: String =
    "LC_ALL=C; export LC_ALL; " +
      "printf 'hostname\\t%s\\n' \"$(hostname 2>/dev/null || true)\"; " +
      "printf 'operating_system\\t%s\\n' \"$(uname -s 2>/dev/null || true)\"; " +
      "printf 'distribution\\t%s\\n' \"$(awk -F= '$1 == \"PRETTY_NAME\" { value=substr($0, index($0, \"=\") + 1); gsub(/^\"|\"$/, \"\", value); print value; exit }' /etc/os-release 2>/dev/null || true)\"; " +
      "printf 'kernel_version\\t%s\\n' \"$(uname -r 2>/dev/null || true)\"; " +
      "printf 'architecture\\t%s\\n' \"$(uname -m 2>/dev/null || true)\"; " +
      "printf 'cpu_model\\t%s\\n' \"$(awk '" + CpuModelProgram + "' /proc/cpuinfo 2>/dev/null || true)\"; " +
      "printf 'cpu_cores\\t%s\\n' \"$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)\"; " +
      "printf 'memory_mb\\t%s\\n' \"$(awk '/^MemTotal:/ { printf \"%d\", $2 / 1024; exit }' /proc/meminfo 2>/dev/null || true)\"; " +
      "cpu_first=\"$(awk '/^cpu / { total=0; for (i=2; i<=9 && i<=NF; i++) total += $i; print total, $5 + $6; exit }' /proc/stat 2>/dev/null || true)\"; " +
      "cpu_second=''; if sleep 0.2 2>/dev/null; then cpu_second=\"$(awk '/^cpu / { total=0; for (i=2; i<=9 && i<=NF; i++) total += $i; print total, $5 + $6; exit }' /proc/stat 2>/dev/null || true)\"; fi; " +
      "printf 'cpu_usage_percent\\t%s\\n' \"$(awk -v first=\"$cpu_first\" -v second=\"$cpu_second\" 'BEGIN { n1=split(first, a, \" \"); n2=split(second, b, \" \"); if (n1 != 2 || n2 != 2) exit; total1=a[1]+0; idle1=a[2]+0; total2=b[1]+0; idle2=b[2]+0; deltaTotal=total2-total1; deltaIdle=idle2-idle1; if (deltaTotal <= 0) exit; usage=100*(deltaTotal-deltaIdle)/deltaTotal; if (usage < 0 || usage > 100) exit; printf \"%.6f\", usage }' 2>/dev/null || true)\"; " +
      "printf 'memory_usage_percent\\t%s\\n' \"$(awk '/^MemTotal:/ { total=$2; hasTotal=1 } /^MemAvailable:/ { available=$2; hasAvailable=1 } END { if (hasTotal && hasAvailable && total > 0 && available >= 0 && available <= total) printf \"%.6f\", 100 * (total - available) / total }' /proc/meminfo 2>/dev/null || true)\"; " +
      "printf 'uptime_seconds\\t%s\\n' \"$(awk '{ print int($1) }' /proc/uptime 2>/dev/null || true)\""
}
